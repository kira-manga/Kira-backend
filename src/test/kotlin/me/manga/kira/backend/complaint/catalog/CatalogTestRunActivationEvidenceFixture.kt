package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.complaint.domain.JournalWriterV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalDocumentV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogRestoreInventoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationAccountingV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationHeadV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationRecordV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationRunV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunNoticeSeedV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisCreationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationEnvelopeV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEncodingV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintProcessPoolFixture
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogInventoryChainVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.BoundTestComplaintConsumerFixture
import me.manga.kira.backend.security.fullTestJournal
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

internal enum class ActivationEvidencePrefix { GENESIS, ROTATED, INVENTORY_ROTATED, PENDING_OVERLAP }

/** Existing cold pools/consumers/lanes only. No new provider, JDBC, process or concurrency harness. */
internal fun withActivationEvidence(
    prefix: ActivationEvidencePrefix = ActivationEvidencePrefix.INVENTORY_ROTATED,
    selectedSigner: String = if (prefix == ActivationEvidencePrefix.GENESIS) "catalog-old" else "catalog-new",
    testActivation: Boolean = false,
    action: (CatalogTestRunActivationEvidenceFixture) -> Unit,
) {
    val rotations = OfflineCatalogRotationFixture.chain()
    val registry = rotations.genesis.manifest.initialWriterRegistry
    val original = fullTestJournal().declaration()
    val journal = TestOwnerDeleteJournalConfigurationV1.of(
        original.copy(
            writer = JournalWriterV1(registry.databaseIdentity, registry.restoreIdentity, registry.eventWriter.generationId),
            limits = original.limits.copy(capacity = original.limits.capacity.copy(maximumRetainedVersions = 10_000)),
        ),
    )
    ComplaintProcessPoolFixture(testActivation = testActivation).use { database ->
        val pools = database.bind()
        JournalPublicationLanesV1(journal).use { lanes ->
            action(CatalogTestRunActivationEvidenceFixture(rotations, prefix, selectedSigner, journal, pools, lanes))
        }
    }
}

/** The same signed evidence, consumers and lanes on the existing original TLS TEST-only root. */
internal fun withActivationEvidence(
    tls: VersionBoundPersistenceConnectedFixture,
    prefix: ActivationEvidencePrefix = ActivationEvidencePrefix.INVENTORY_ROTATED,
    selectedSigner: String = if (prefix == ActivationEvidencePrefix.GENESIS) "catalog-old" else "catalog-new",
    action: (CatalogTestRunActivationEvidenceFixture) -> Unit,
) {
    val rotations = OfflineCatalogRotationFixture.chain()
    val registry = rotations.genesis.manifest.initialWriterRegistry
    val original = fullTestJournal().declaration()
    val journal = TestOwnerDeleteJournalConfigurationV1.of(
        original.copy(
            writer = JournalWriterV1(registry.databaseIdentity, registry.restoreIdentity, registry.eventWriter.generationId),
            limits = original.limits.copy(capacity = original.limits.capacity.copy(maximumRetainedVersions = 10_000)),
        ),
    )
    JournalPublicationLanesV1(journal).use { lanes ->
        action(CatalogTestRunActivationEvidenceFixture(rotations, prefix, selectedSigner, journal, tls.pools, lanes))
    }
}

/**
 * Manifests and complete chains below are independently assembled from existing raw fixture inputs,
 * not from the new assembler/parser/checked result. Signatures reuse genuine in-memory PSS keys and
 * the existing independent LP32 frame. Metadata remains synthetic, never an AWS/registration proof.
 */
internal class CatalogTestRunActivationEvidenceFixture(
    val rotations: RotationChainFixture,
    prefixKind: ActivationEvidencePrefix,
    val signerId: String,
    val journal: TestOwnerDeleteJournalConfigurationV1,
    val pools: VersionBoundPersistencePools,
    private val lanes: JournalPublicationLanesV1,
) {
    val initial = OfflineTrustBundleFixture.bytes(rotations.initial)
    val current = OfflineTrustBundleFixture.bytes(rotations.current)
    val policy = OfflineCatalogRotationFixture.policy()
    private val inventoryChain = OfflineCatalogInventoryFixture.chain(base = rotations)
    val prefix: List<ByteArray> = when (prefixKind) {
        ActivationEvidencePrefix.GENESIS -> rotations.bytes().take(1)
        ActivationEvidencePrefix.ROTATED -> rotations.bytes()
        ActivationEvidencePrefix.PENDING_OVERLAP -> rotations.bytes().take(2)
        ActivationEvidencePrefix.INVENTORY_ROTATED -> inventoryRotationPrefix()
    }
    val inventory: CatalogRestoreInventoryV1 = if (prefixKind == ActivationEvidencePrefix.INVENTORY_ROTATED) {
        inventoryChain.generations.last().manifest.restoreInventory
    } else {
        CatalogRestoreInventoryV1(emptyList(), emptyList())
    }
    val reader = VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
        initial, current, policy, Sha256.hex(prefix.first()), S3CatalogReadbackLimits(), 600_000, pageSize = 1,
    )
    private val consumers = BoundTestComplaintConsumerFixture(journal).configuration()
    private val activation = FullTestCatalogInputs.activation(
        pools, journal, reader, FullTestCatalogInputs.key(signerId, key(signerId).public.encoded),
        OfflineTrustBundleFixture.registryBytes(rotations.genesis.manifest.initialWriterRegistry),
    )
    val process = process()
    val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, INSTALLATION_LIMIT)
    val generation = prefix.size + 1L
    val token = "${generation.toString(16).padStart(8, '0')}-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    val creation = OfflineCatalogGenesisCreationV1("catalog-approver-a", 1720000000 + generation * 100)
    val approvals = listOf(
        OfflineCatalogGenesisApprovalV1("catalog-approver-a", creation.createdAtEpochSecond + 10),
        OfflineCatalogGenesisApprovalV1("catalog-approver-b", creation.createdAtEpochSecond + 20),
    )
    val manifest = independentManifest()
    val envelope = signed(manifest)
    val envelopeBytes = bytes(envelope)
    val complete = prefix + envelopeBytes
    val head = CatalogLocalHead(generation, Sha256.hex(envelopeBytes))
    val readbackPolicy = reader.policyAt(Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT))
    val retainedUntil = maxOf(
        readbackPolicy.requiredRetainUntilEpochSecond,
        Instant.ofEpochSecond(creation.createdAtEpochSecond).atOffset(ZoneOffset.UTC).plusYears(10).toEpochSecond(),
    )

    fun process(desiredGeneration: Long = 7): VersionBoundTestNamespaceProcessV1 {
        val writer = journal.declaration().writer
        return VersionBoundTestNamespaceProcessV1.fromRetained(
            consumers, pools, 1, desiredGeneration, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity),
            lanes, reader, activation,
        )
    }

    /** Reuses the original signed prefix/configuration; no randomized re-signing changes its raw identity. */
    fun processOn(pools: VersionBoundPersistencePools, desiredGeneration: Long = 7): VersionBoundTestNamespaceProcessV1 {
        val writer = journal.declaration().writer
        val activation = FullTestCatalogInputs.activation(
            pools, journal, reader, FullTestCatalogInputs.key(signerId, key(signerId).public.encoded),
            OfflineTrustBundleFixture.registryBytes(rotations.genesis.manifest.initialWriterRegistry),
        )
        return VersionBoundTestNamespaceProcessV1.fromRetained(
            consumers, pools, 1, desiredGeneration, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity),
            lanes, reader, activation,
        )
    }

    fun assembled(): ByteArray = expected.assemble(
        OfflineCatalogInventoryChainVerifier.verifyInventoryChain(prefix.asSequence(), initial, current, policy),
        rotations.genesis.manifest.oldestRestoreTimeEpochSecond, token, creation, approvals,
    )

    fun verify(
        generations: List<ByteArray> = complete,
        selected: CatalogTestRunActivationCanonicalV3 = expected,
        chainPolicy: OfflineCatalogChainReaderPolicy = policy,
    ) = OfflineCatalogInventoryChainVerifier.verifyTestRunActivationChain(generations.asSequence(), initial, current, chainPolicy, selected)

    fun provider(generations: List<ByteArray> = complete): SyntheticCatalogReadbackPort = SyntheticCatalogReadbackPort(generations).also {
        it.transformMetadata = { metadata -> metadata.copy(retainUntilEpochSecond = retainedUntil) }
    }

    fun readback(
        provider: SyntheticCatalogReadbackPort,
        expectedHead: CatalogLocalHead = head,
        policy: CatalogReadbackPolicy = readbackPolicy,
    ): CatalogTestRunActivationReadbackV3 =
        CatalogTestRunActivationReadbackV3.verify(provider, initial, current, policy, expectedHead, expected)

    fun withRun(run: CatalogTestRunActivationRunV1): OfflineCatalogTestRunActivationManifestV3 {
        val record = manifest.activationRecord.copy(run = run)
        return manifest.copy(activationRecord = record, history = history(record))
    }

    private fun independentManifest(): OfflineCatalogTestRunActivationManifestV3 {
        val original = rotations.genesis.manifest
        val run = CatalogTestRunActivationRunV1(
            journal.scope.id.toString(), 1, 7, Sha256.hex(process.canonicalBytes()),
            Json.decodeFromString(TestOwnerDeleteJournalDocumentV1.serializer(), journal.canonicalBytes().decodeToString()),
            1, INSTALLATION_LIMIT, TestTerminalEncodingV1("TEST_TERMINAL_V1", 1),
            accounting(INSTALLATION_LIMIT, 10_000), notices(journal.scope.id.toString()),
        )
        val record = CatalogTestRunActivationRecordV1(token, generation, Sha256.hex(prefix.last()), run)
        return OfflineCatalogTestRunActivationManifestV3(
            3, "NEW_BACKEND_TEST_RUN_ACTIVATION_V1", "kcj-1", "TEST_RUN_ACTIVATION", token, generation, record.previousEnvelopeSha256,
            original.initialTrustBundleEnvelopeSha256, original.catalogWriterGenerationId,
            CatalogSignerPolicyV1("SINGLE", "ALL_MEMBERS", listOf(OfflineCatalogRotationFixture.member(signerId))), creation, approvals,
            original.oldestRestoreTimeEpochSecond, original.initialWriterRegistry, inventory,
            CatalogInventoryDeltaV1(emptyList(), emptyList()), history(record), record,
        )
    }

    private fun inventoryRotationPrefix(): List<ByteArray> {
        val old = inventoryChain.generations.last()
        val overlap = OfflineCatalogInventoryFixture.signed(
            OfflineCatalogInventoryFixture.manifest(
                rotations.genesis, 4, OfflineCatalogInventoryFixture.bytes(old), "ROTATION_OVERLAP",
                old.manifest.restoreInventory, CatalogInventoryDeltaV1(emptyList(), emptyList()),
            ).copy(requiredSignerPolicy = CatalogSignerPolicyV1("ROTATION_OVERLAP", "ALL_MEMBERS", listOf("catalog-old", "catalog-new").map(OfflineCatalogRotationFixture::member))),
        )
        val activated = OfflineCatalogInventoryFixture.signed(
            OfflineCatalogInventoryFixture.manifest(
                rotations.genesis, 5, OfflineCatalogInventoryFixture.bytes(overlap), "ROTATION_ACTIVATE",
                old.manifest.restoreInventory, CatalogInventoryDeltaV1(emptyList(), emptyList()), "catalog-new",
            ),
        )
        return inventoryChain.bytes() + listOf(OfflineCatalogInventoryFixture.bytes(overlap), OfflineCatalogInventoryFixture.bytes(activated))
    }

    companion object {
        const val INSTALLATION_LIMIT = 501L

        fun manifestBytes(value: OfflineCatalogTestRunActivationManifestV3): ByteArray =
            CanonicalJson.canonicalize(OfflineCatalogTestRunActivationManifestV3.serializer(), value).toByteArray(Charsets.UTF_8)

        fun bytes(value: OfflineCatalogTestRunActivationEnvelopeV3): ByteArray =
            CanonicalJson.canonicalize(OfflineCatalogTestRunActivationEnvelopeV3.serializer(), value).toByteArray(Charsets.UTF_8)

        fun signed(value: OfflineCatalogTestRunActivationManifestV3): OfflineCatalogTestRunActivationEnvelopeV3 {
            val member = value.requiredSignerPolicy.members.single()
            val signer = Signature.getInstance("RSASSA-PSS").apply {
                setParameter(OfflineTrustBundleFixture.parameters)
                initSign(key(member.keyId).private)
                update(OfflineCatalogGenesisFixture.independentFrame(member.keyId, manifestBytes(value)))
            }
            val signature = OfflineCatalogGenesisSignatureV1(member.keyId, member.algorithmId, Base64.getEncoder().encodeToString(signer.sign()))
            return OfflineCatalogTestRunActivationEnvelopeV3(3, value, listOf(signature))
        }

        fun history(record: CatalogTestRunActivationRecordV1): CatalogTestRunActivationHistoryV1 {
            val empty = CatalogTestRunActivationHeadV1(0, Sha256.hexUtf8("[]"))
            val recordJson = CanonicalJson.canonicalize(CatalogTestRunActivationRecordV1.serializer(), record)
            return CatalogTestRunActivationHistoryV1(
                empty, CatalogTestRunActivationHeadV1(1, Sha256.hexUtf8("[$recordJson]")), empty, empty, empty, empty, empty,
            )
        }

        /** Independent fixed22 ordinal and literal-price oracle, not the production accounting calculator. */
        fun accounting(n: Long, r: Long): CatalogTestRunActivationAccountingV1 {
            val chunks = (n + 499) / 500
            val publications = chunks + 1
            val sidecars = chunks + 17
            fun vector(vararg values: Pair<Int, Long>): List<Long> = List(22) { index -> values.toMap()[index + 1] ?: 0L }
            val storage = n * 32_768 + publications * 278_528 + sidecars * 1_340_736 + 3_219_968 +
                (n + 3) * 65_536 + 2 * 3_776 + 2 * r * 37_696 + 1_068_608
            return CatalogTestRunActivationAccountingV1(
                "TEST_TERMINAL_ACCOUNTING_V1", 1,
                vector(3 to 1L, 21 to 3_219_968L),
                vector(2 to 4L, 4 to 2L, 11 to 1L, 18 to 2L, 21 to 1_933_120L, 22 to 1L),
                vector(1 to n, 2 to n + 3, 3 to 1L, 8 to n, 12 to publications, 17 to publications, 19 to 2 * r, 20 to 2L, 21 to storage),
            )
        }

        /** Ledger literals plus independent JSON framing/UUID formatting; no call to the production notice derivation. */
        private fun notices(runId: String): List<CatalogTestRunNoticeSeedV1> = listOf(
            Triple("complaints.notice.content-policy", "Adult content policy", "References to adult / 18+ content aren't allowed here. Please keep submissions consistent with our community guidelines."),
            Triple("complaints.notice.source-requirements", "New manga site requirements", "Any new manga site must offer at least 200 titles, have no bot verification steps, and be worth the setup effort. Adding a site takes significant time and work."),
        ).map { (noticeKey, subject, body) ->
            val frame = "[\"kira-test-notice-id-v1\",\"$runId\",\"$noticeKey\",1]"
            val id = MessageDigest.getInstance("SHA-256").digest(frame.toByteArray(Charsets.UTF_8)).copyOf(16)
            id[6] = ((id[6].toInt() and 15) or 64).toByte()
            id[8] = ((id[8].toInt() and 63) or 128).toByte()
            val hex = HexFormat.of().formatHex(id)
            val resourceId = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
            val definition = buildJsonObject {
                put("defaultBody", body)
                put("defaultSubject", subject)
                put("definitionVersion", 1)
                put("noticeKey", noticeKey)
            }
            CatalogTestRunNoticeSeedV1(noticeKey, 1, resourceId, Sha256.hexUtf8(CanonicalJson.canonicalize(definition)))
        }

        private fun key(id: String) = when (id) {
            "catalog-old" -> OfflineTrustBundleFixture.firstSigner
            "catalog-new" -> OfflineTrustBundleFixture.secondSigner
            else -> error("Unknown synthetic signing key")
        }
    }
}
