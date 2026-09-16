package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogRotationChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogRotationChainVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class OfflineCatalogRotationChainVerifierTest {
    private val fixture = OfflineCatalogRotationFixture
    private val bundles = OfflineTrustBundleFixture
    private val chain by lazy { fixture.chain() }
    private val overlap get() = chain.rotations[0]
    private val activation get() = chain.rotations[1]
    private val genesisBytes get() = OfflineCatalogGenesisFixture.bytes(chain.genesis)

    @Test
    fun `two real rotations verify the contiguous sequence and return only bound tail and trust metadata`() {
        val multiple = fixture.chain(twoRotations = true)
        val current = bundles.signed(multiple.current.body.copy(minimumCatalogHeadGeneration = 5))
        val input = multiple.bytes()
        val checked = verify(multiple, current = current)
        assertEquals(5L, checked.tail.generation)
        assertEquals(Sha256.hex(input.last()), checked.tail.envelopeSha256)
        assertEquals(Sha256.hex(fixture.manifestBytes(multiple.rotations.last().manifest)), checked.tail.manifestSha256)
        assertEquals(bundles.CATALOG_WRITER, checked.tail.catalogWriterGenerationId)
        assertEquals(CatalogRotationState.Stable(fixture.member("catalog-third")), checked.rotation)
        assertEquals(Sha256.hex(input.first()), checked.trust.genesisEnvelopeSha256)
        assertEquals(Sha256.hex(bundles.bytes(multiple.initial)), checked.trust.initialBundleEnvelopeSha256)
        assertEquals(Sha256.hex(bundles.bytes(current)), checked.trust.currentBundleEnvelopeSha256)
        assertEquals(9L, checked.trust.currentBundleVersion)
        assertEquals(5L, checked.trust.minimumHeadGeneration)
        assertEquals(input.sumOf { it.size.toLong() }, checked.encodedBytes)
        assertEquals("CheckedOfflineCatalogRotationChain(no-accepted-head,no-signer-disable-permission)", checked.toString())
    }

    @Test
    fun `a qualifying overlap tail is explicitly pending and never stable or signer disable permission`() {
        val current = bundles.signed(chain.current.body.copy(minimumCatalogHeadGeneration = 2))
        val checked = verify(envelopes = chain.bytes().take(2), current = current)
        assertEquals(2L, checked.tail.generation)
        assertEquals(CatalogRotationState.AwaitingActivation(fixture.member("catalog-old"), fixture.member("catalog-new")), checked.rotation)
        assertNotEquals(CatalogRotationState.Stable(fixture.member("catalog-new")), checked.rotation)
    }

    @Test
    fun `current signed head floor is enforced at EOF independently of valid historical signatures`() {
        val current = bundles.signed(chain.current.body.copy(minimumCatalogHeadGeneration = 3))
        verify(current = current)
        reject(chain.bytes().take(2), OfflineTrustBundleFailure.POLICY_MISMATCH, current = current)
        reject(chain.bytes().take(1), OfflineTrustBundleFailure.POLICY_MISMATCH, current = current)
    }

    @Test
    fun `genesis uses W04d while every non genesis writer needs independent current permission`() {
        val denied = fixture.policy(writers = listOf(bundles.EVENT_WRITER))
        val genesisOnly = verify(envelopes = listOf(genesisBytes), policy = denied)
        assertEquals(CatalogRotationState.Stable(fixture.member("catalog-old")), genesisOnly.rotation)
        reject(chain.bytes(), OfflineTrustBundleFailure.POLICY_MISMATCH, policy = denied)
        val nominated = fixture.signed(overlap.manifest.copy(catalogWriterGenerationId = bundles.EVENT_WRITER))
        reject(listOf(genesisBytes, fixture.bytes(nominated)), OfflineTrustBundleFailure.POLICY_MISMATCH, policy = denied)
    }

    @Test
    fun `actual approvals need both predecessor and current allowlists neither alone grants them authority`() {
        val currentOnly = fixture.policy(approvers = listOf("catalog-approver-a", "current-only"))
        reject(chain.bytes(), OfflineTrustBundleFailure.POLICY_MISMATCH, policy = currentOnly)
        val approvals = overlap.manifest.approvals.mapIndexed { index, approval ->
            if (index == 1) approval.copy(approverId = "current-only") else approval
        }
        val nominated = fixture.signed(overlap.manifest.copy(approvals = approvals))
        reject(listOf(genesisBytes, fixture.bytes(nominated)), OfflineTrustBundleFailure.POLICY_MISMATCH, policy = currentOnly)
    }

    @Test
    fun `raw chain entry point still needs current root version and exact historical T0 genesis binding`() {
        val stale = bundles.signed(chain.current.body.copy(version = 8))
        reject(chain.bytes(), OfflineTrustBundleFailure.VERSION_ROLLBACK, current = stale)
        val tampered = chain.current.copy(body = chain.current.body.copy(issuedAtEpochSecond = chain.current.body.issuedAtEpochSecond + 1))
        reject(chain.bytes(), OfflineTrustBundleFailure.INVALID_SIGNATURE, current = tampered)
        val substitute = bundles.signed(chain.initial.body)
        val failure = assertThrows(OfflineTrustBundleException::class.java) {
            OfflineCatalogRotationChainVerifier.verifyRotationChain(
                chain.bytes().asSequence(),
                bundles.bytes(substitute),
                bundles.bytes(chain.current),
                fixture.policy(),
            )
        }
        assertEquals(OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH, failure.code)
    }

    @Test
    fun `retained intermediate public key is mandatory even when genesis and final keys are still available`() {
        val multiple = fixture.chain(twoRotations = true)
        val current = bundles.signed(multiple.current.body.copy(signers = multiple.current.body.signers.filterNot { it.keyId == "catalog-new" }))
        OfflineTrustBundleVerifier.verify(bundles.bytes(current), bundles.policy(minimumVersion = 9))
        reject(multiple.bytes(), OfflineTrustBundleFailure.POLICY_MISMATCH, multiple, current)
    }

    @Test
    fun `retargeted intermediate key material fails actual catalog verification despite a valid current root signature`() {
        val multiple = fixture.chain(twoRotations = true)
        val third = multiple.current.body.signers.last().copy(keyId = "catalog-new")
        val current = bundles.signed(multiple.current.body.copy(signers = listOf(multiple.current.body.signers.first(), third)))
        OfflineTrustBundleVerifier.verify(bundles.bytes(current), bundles.policy(minimumVersion = 9))
        reject(multiple.bytes(), OfflineTrustBundleFailure.INVALID_SIGNATURE, multiple, current)
    }

    @Test
    fun `direct single key jump or undeclared operation cannot replace the overlap step`() {
        val direct = fixture.manifest(chain.genesis, 2, genesisBytes, "SINGLE", listOf("catalog-new"))
        rejectManifest(direct)
        listOf("GENESIS", "APPEND", "FUTURE_ROTATION", "LEGACY_IMPORT").forEach { operation ->
            rejectManifest(overlap.manifest.copy(operation = operation))
        }
    }

    @Test
    fun `overlap is ordered old then new with ALL_MEMBERS and a distinct previously unused key`() {
        val required = overlap.manifest.requiredSignerPolicy
        listOf(
            required.copy(members = required.members.reversed()),
            required.copy(members = listOf(required.members[0], required.members[0])),
            required.copy(members = required.members.take(1)),
            required.copy(threshold = "ANY_MEMBER"),
            required.copy(mode = "SINGLE"),
        ).forEach { rejectManifest(overlap.manifest.copy(requiredSignerPolicy = it)) }
    }

    @Test
    fun `overlap requires both real signatures with no omitted reversed duplicate or extra signature records`() {
        val signatures = overlap.signatures
        listOf(
            emptyList(),
            signatures.take(1),
            signatures.takeLast(1),
            signatures.reversed(),
            listOf(signatures[0], signatures[0]),
            signatures + signatures[0],
        ).forEach { rejectOverlap(overlap.copy(signatures = it)) }
    }

    @Test
    fun `each overlap signature and the exact signed manifest bytes are actually verified`() {
        overlap.signatures.indices.forEach { index ->
            val signatures = overlap.signatures.toMutableList()
            val bytes = Base64.getDecoder().decode(signatures[index].signatureBase64)
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            signatures[index] = signatures[index].copy(signatureBase64 = Base64.getEncoder().encodeToString(bytes))
            rejectOverlap(overlap.copy(signatures = signatures), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        }
        val tampered = overlap.copy(manifest = overlap.manifest.copy(operationToken = bundles.EVENT_WRITER))
        rejectOverlap(tampered, OfflineTrustBundleFailure.INVALID_SIGNATURE)
    }

    @Test
    fun `immediate activation must be single new without an interleaved or repeated overlap operation`() {
        val old = fixture.member("catalog-old")
        val bad = listOf(
            activation.manifest.copy(operation = "APPEND"),
            activation.manifest.copy(requiredSignerPolicy = CatalogSignerPolicyV1("SINGLE", "ALL_MEMBERS", listOf(old))),
            fixture.manifest(chain.genesis, 3, fixture.bytes(overlap), "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new")),
        )
        bad.forEach { changed -> reject(chain.bytes().take(2) + fixture.bytes(fixture.signed(changed))) }
    }

    @Test
    fun `activation signature is checked and cannot be replaced by an old key record`() {
        val old = overlap.signatures.first()
        reject(chain.bytes().take(2) + fixture.bytes(activation.copy(signatures = listOf(old))))
        val record = activation.signatures.single()
        val invalid = activation.copy(signatures = listOf(record.copy(signatureBase64 = overlap.signatures.last().signatureBase64)))
        reject(chain.bytes().take(2) + fixture.bytes(invalid), OfflineTrustBundleFailure.INVALID_SIGNATURE)
    }

    @Test
    fun `a retained old verifier cannot be reactivated after a completed rotation`() {
        val returning = fixture.manifest(chain.genesis, 4, fixture.bytes(activation), "ROTATION_OVERLAP", listOf("catalog-new", "catalog-old"))
        reject(chain.bytes() + fixture.bytes(fixture.signed(returning)))
        val repeatedActivation = fixture.manifest(chain.genesis, 4, fixture.bytes(activation), "SINGLE", listOf("catalog-new"))
        reject(chain.bytes() + fixture.bytes(fixture.signed(repeatedActivation)))
    }

    @Test
    fun `numbering is exactly G1 through N without a missing genesis gap duplicate or reordering`() {
        listOf(0L, 1L, 3L, Long.MAX_VALUE).forEach { rejectManifest(overlap.manifest.copy(generation = it)) }
        reject(emptyList())
        reject(chain.bytes().drop(1))
        reject(listOf(genesisBytes, genesisBytes))
        reject(listOf(genesisBytes, fixture.bytes(activation), fixture.bytes(overlap)))
    }

    @Test
    fun `links bind complete preceding envelopes not manifests and every rotation retains the exact T0 binding`() {
        val bodyHash = Sha256.hex(OfflineCatalogGenesisFixture.manifestBytes(chain.genesis.manifest))
        rejectManifest(overlap.manifest.copy(previousEnvelopeSha256 = bodyHash))
        rejectManifest(overlap.manifest.copy(previousEnvelopeSha256 = "1".repeat(64)))
        val alternate = OfflineCatalogGenesisFixture.signed(chain.genesis.manifest)
        assertNotEquals(Sha256.hex(genesisBytes), Sha256.hex(OfflineCatalogGenesisFixture.bytes(alternate)))
        reject(listOf(OfflineCatalogGenesisFixture.bytes(alternate), fixture.bytes(overlap)))
        rejectManifest(overlap.manifest.copy(initialTrustBundleEnvelopeSha256 = "1".repeat(64)), OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH)
    }

    @Test
    fun `bootstrap registry remains immutable including its initial signer policy while the separate rotation policy changes`() {
        val registry = overlap.manifest.initialWriterRegistry
        val catalog = registry.catalogWriter
        val range = registry.eventWriter.liveRange
        val changedInitialPolicy = catalog.requiredSignerPolicy.copy(members = listOf(fixture.member("catalog-new")))
        val changes = listOf(
            registry.copy(catalogWriter = catalog.copy(catalogApproverIds = catalog.catalogApproverIds + "catalog-approver-c")),
            registry.copy(catalogWriter = catalog.copy(requiredSignerPolicy = changedInitialPolicy)),
            registry.copy(eventWriter = registry.eventWriter.copy(liveRange = range.copy(ordinaryPrefix = range.sealTerminalPrefix))),
        )
        changes.forEach { rejectManifest(overlap.manifest.copy(initialWriterRegistry = it)) }
    }

    @Test
    fun `restore inventory every history head and the empty restore floor must remain unchanged`() {
        val manifest = overlap.manifest
        val history = manifest.history
        val nonempty = manifest.restoreInventory.copy(count = 1)
        listOf(
            manifest.copy(restoreInventory = nonempty),
            manifest.copy(history = history.copy(expiredRestoreSources = nonempty)),
            manifest.copy(history = history.copy(testRunActivations = nonempty)),
            manifest.copy(history = history.copy(testRunTerminals = nonempty)),
            manifest.copy(history = history.copy(installationManifests = nonempty)),
            manifest.copy(history = history.copy(epochSeals = nonempty)),
            manifest.copy(history = history.copy(retirementAuthorizations = nonempty)),
            manifest.copy(history = history.copy(retirementCompletions = nonempty)),
            manifest.copy(restoreInventory = manifest.restoreInventory.copy(sha256 = "1".repeat(64))),
            manifest.copy(oldestRestoreTimeEpochSecond = manifest.oldestRestoreTimeEpochSecond + 1),
        ).forEach { rejectManifest(it) }
    }

    @Test
    fun `creation follows all predecessor approvals and each bounded approval follows its own creation`() {
        val manifest = overlap.manifest
        val beforePredecessor = chain.genesis.manifest.approvals.maxOf { it.approvedAtEpochSecond } - 1
        rejectManifest(manifest.copy(creation = manifest.creation.copy(createdAtEpochSecond = beforePredecessor)))
        val early = manifest.approvals.map { it.copy(approvedAtEpochSecond = manifest.creation.createdAtEpochSecond - 1) }
        rejectManifest(manifest.copy(approvals = early))
        rejectManifest(manifest.copy(approvals = manifest.approvals.map { it.copy(approvedAtEpochSecond = 253402300800) }))
        val beforeOverlap = overlap.manifest.approvals.maxOf { it.approvedAtEpochSecond } - 1
        val earlyActivation = fixture.signed(activation.manifest.copy(creation = activation.manifest.creation.copy(createdAtEpochSecond = beforeOverlap)))
        reject(chain.bytes().take(2) + fixture.bytes(earlyActivation))
    }

    @Test
    fun `approval tuple is exactly two sorted distinct identities and creator belongs to that actual tuple`() {
        val manifest = overlap.manifest
        listOf(manifest.approvals.take(1), manifest.approvals.reversed(), listOf(manifest.approvals[0], manifest.approvals[0])).forEach { approvals ->
            rejectManifest(manifest.copy(approvals = approvals))
        }
        rejectManifest(manifest.copy(creation = manifest.creation.copy(creatorId = "catalog-approver-c")))
    }

    @Test
    fun `rotation schema operation token and algorithm remain pinned rather than generic provider choices`() {
        rejectOverlap(overlap.copy(schemaVersion = 2), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectManifest(overlap.manifest.copy(schemaVersion = 2), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectManifest(overlap.manifest.copy(canonicalizerId = "generic-json"), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectManifest(overlap.manifest.copy(operationToken = "00000000-0000-0000-0000-000000000000"))
        val required = overlap.manifest.requiredSignerPolicy
        val changed = required.copy(members = required.members.map { it.copy(algorithmId = "Ed25519") })
        rejectManifest(overlap.manifest.copy(requiredSignerPolicy = changed), OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM)
    }

    @Test
    fun `new rotation parser remains canonical closed and rejects generic histories duplicate fields nulls and floats`() {
        val text = fixture.bytes(overlap).decodeToString()
        reject(listOf(genesisBytes, (text + " ").toByteArray()), OfflineTrustBundleFailure.NON_CANONICAL)
        listOf("{", "\"manifest\":{", "\"requiredSignerPolicy\":{", "\"history\":{", "\"signatures\":[{").forEach { marker ->
            val unknown = text.replaceFirst(marker, "$marker\"unknown\":0,").toByteArray()
            reject(listOf(genesisBytes, unknown), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
        listOf(
            text.replaceFirst("\"generation\":2", "\"generation\":2,\"generation\":2"),
            text.replaceFirst("\"generation\":2", "\"generation\":null"),
            text.replaceFirst("\"generation\":2", "\"generation\":2.0"),
            text.replaceFirst("\"history\":{", "\"history\":{\"records\":[],"),
        ).forEach { reject(listOf(genesisBytes, it.toByteArray()), OfflineTrustBundleFailure.MALFORMED_INPUT) }
    }

    private fun verify(
        source: RotationChainFixture = chain,
        envelopes: List<ByteArray> = source.bytes(),
        current: OfflineTrustBundleEnvelopeV1 = source.current,
        policy: OfflineCatalogChainReaderPolicy = fixture.policy(),
    ): CheckedOfflineCatalogRotationChain = OfflineCatalogRotationChainVerifier.verifyRotationChain(
        envelopes.asSequence(),
        bundles.bytes(source.initial),
        bundles.bytes(current),
        policy,
    )

    private fun reject(
        envelopes: List<ByteArray>,
        code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT,
        source: RotationChainFixture = chain,
        current: OfflineTrustBundleEnvelopeV1 = source.current,
        policy: OfflineCatalogChainReaderPolicy = fixture.policy(),
    ) {
        val failure = assertThrows(OfflineTrustBundleException::class.java) { verify(source, envelopes, current, policy) }
        assertEquals(code, failure.code)
    }

    private fun rejectOverlap(value: OfflineCatalogRotationEnvelopeV1, code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT) {
        reject(listOf(genesisBytes, fixture.bytes(value)), code)
    }

    private fun rejectManifest(value: OfflineCatalogRotationManifestV1, code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT) {
        rejectOverlap(fixture.signed(value), code)
    }
}
