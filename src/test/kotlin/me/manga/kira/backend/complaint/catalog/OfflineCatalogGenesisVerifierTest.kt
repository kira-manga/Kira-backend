package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogGenesis
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHeadV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogGenesisCrypto
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogGenesisVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleCrypto
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class OfflineCatalogGenesisVerifierTest {
    private val fixture = OfflineCatalogGenesisFixture
    private val bundleFixture = OfflineTrustBundleFixture
    private val bundle by lazy { fixture.bundle() }
    private val bundleBytes by lazy { bundleFixture.bytes(bundle) }
    private val manifest by lazy { fixture.manifest(bundle) }
    private val signed by lazy { fixture.signed(manifest) }

    @Test
    fun `real current T0 and catalog signatures check the complete genesis candidate but grant no head`() {
        val bytes = fixture.bytes(signed)
        val checked = OfflineCatalogGenesisVerifier.verify(bytes, bundleBytes, bundleFixture.policy())
        assertEquals(manifest, checked.manifest)
        assertArrayEquals(fixture.manifestBytes(manifest), checked.canonicalManifestBytes)
        assertArrayEquals(bytes, checked.canonicalEnvelopeBytes)
        assertEquals(Sha256.hex(fixture.manifestBytes(manifest)), checked.manifestSha256)
        assertEquals(Sha256.hex(bytes), checked.envelopeSha256)
        assertEquals("CheckedOfflineCatalogGenesis(no-accepted-head)", checked.toString())
        assertEquals(42L, bundleFixture.body().minimumCatalogHeadGeneration)
        assertEquals(1L, bundle.body.minimumCatalogHeadGeneration)
    }

    @Test
    fun `literal catalog frame binds its own domain key algorithm and raw manifest digest`() {
        val literal = "000000256b6972612e636f6d706c61696e74732e636174616c6f672d67656e65726174696f6e2e7631" +
            "000000056b636a2d31" +
            "0000000b636174616c6f672d6f6c64" +
            "000000125253415353415f5053535f5348415f323536" +
            "0000002044136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        val expected = literal.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val bytes = "{}".toByteArray()
        assertArrayEquals(expected, OfflineCatalogGenesisCrypto.signatureFrame("catalog-old", bytes))
        assertArrayEquals(expected, fixture.independentFrame("catalog-old", bytes))
        assertFalse(expected.contentEquals(OfflineTrustBundleCrypto.signatureFrame("catalog-old", bytes)))
    }

    @Test
    fun `raw genesis API enforces independent root version and genesis head floor without lowering policy`() {
        reject(signed, OfflineTrustBundleFailure.INVALID_SIGNATURE, policy = bundleFixture.policy(rootSpki = bundleFixture.secondSigner.public.encoded))
        reject(signed, OfflineTrustBundleFailure.VERSION_ROLLBACK, policy = bundleFixture.policy(minimumVersion = 8))
        val head42 = bundleFixture.signed()
        val head42Bytes = bundleFixture.bytes(head42)
        OfflineTrustBundleVerifier.verify(head42Bytes, bundleFixture.policy())
        reject(fixture.signed(fixture.manifest(head42)), OfflineTrustBundleFailure.POLICY_MISMATCH, head42Bytes)
    }

    @Test
    fun `different valid root signature over identical T0 body cannot replace the exact initial envelope`() {
        OfflineCatalogGenesisVerifier.verify(fixture.bytes(signed), bundleBytes, bundleFixture.policy())
        val alternate = bundleFixture.signed(bundle.body)
        val alternateBytes = bundleFixture.bytes(alternate)
        val checked = OfflineTrustBundleVerifier.verify(alternateBytes, bundleFixture.policy())
        assertEquals(bundle.body, checked.body)
        assertNotEquals(Sha256.hex(bundleBytes), checked.envelopeSha256)
        reject(signed, OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH, alternateBytes)
        val bodyOnlyHash = Sha256.hex(bundleFixture.bodyBytes(bundle.body))
        rejectManifest(manifest.copy(initialTrustBundleEnvelopeSha256 = bodyOnlyHash), OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH)
    }

    @Test
    fun `genesis schema and canonicalizer have no generic catalog fallback`() {
        reject(signed.copy(schemaVersion = 2), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectManifest(manifest.copy(schemaVersion = 2), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectManifest(manifest.copy(canonicalizerId = "generic-json"), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
    }

    @Test
    fun `inline registry reuses both exact rooted hash binding and complete role and prefix validation`() {
        val registry = manifest.initialWriterRegistry
        val catalog = registry.catalogWriter
        val range = registry.eventWriter.liveRange
        val roleAlias = registry.copy(catalogWriter = catalog.copy(putAuthority = catalog.putAuthority.copy(principalId = range.ordinaryAuthority.roleId)))
        rejectManifest(manifest.copy(initialWriterRegistry = roleAlias), OfflineTrustBundleFailure.REGISTRY_HASH_MISMATCH)
        val prefixAlias = registry.copy(eventWriter = registry.eventWriter.copy(liveRange = range.copy(ordinaryPrefix = range.sealTerminalPrefix)))
        listOf(roleAlias, prefixAlias).forEach { changed ->
            // Rebind ONLY the registry hash and genuinely root-sign: valid root/hash cannot mask omitted registry semantics.
            val rooted = bundleFixture.signed(fixture.bundleBody(changed))
            val rootedBytes = bundleFixture.bytes(rooted)
            val verified = OfflineTrustBundleVerifier.verify(rootedBytes, bundleFixture.policy())
            assertEquals(Sha256.hex(bundleFixture.registryBytes(changed)), verified.body.bootstrapAuthority.initialWriterRegistrySha256)
            reject(fixture.signed(fixture.manifest(rooted, changed)), OfflineTrustBundleFailure.INVALID_DOCUMENT, rootedBytes)
        }
    }

    @Test
    fun `genesis cannot nominate its own catalog writer or another valid bundle signing key`() {
        rejectManifest(manifest.copy(catalogWriterGenerationId = bundleFixture.EVENT_WRITER))
        val otherPolicy = manifest.requiredSignerPolicy.copy(members = listOf(manifest.requiredSignerPolicy.members.single().copy(keyId = "catalog-new")))
        val nominated = fixture.signed(manifest.copy(requiredSignerPolicy = otherPolicy), bundleFixture.secondSigner, "catalog-new")
        reject(nominated) // The substituted key really signed; merely being root-allowlisted is not the bootstrap required policy.
        rejectManifest(manifest.copy(requiredSignerPolicy = manifest.requiredSignerPolicy.copy(mode = "ROTATION_OVERLAP")))
        rejectManifest(manifest.copy(requiredSignerPolicy = manifest.requiredSignerPolicy.copy(threshold = "ANY_MEMBER")))
    }

    @Test
    fun `genesis approvals are exactly two ASCII sorted distinct rooted catalog identities not release approvers`() {
        val approvals = manifest.approvals
        listOf(approvals.take(1), approvals.reversed(), listOf(approvals[0], approvals[0])).forEach { rejectManifest(manifest.copy(approvals = it)) }
        val releaseApprovals = bundle.body.approvals.map { OfflineCatalogGenesisApprovalV1(it.approverId, manifest.creation.createdAtEpochSecond) }
        rejectManifest(manifest.copy(approvals = releaseApprovals, creation = manifest.creation.copy(creatorId = releaseApprovals[0].approverId)))
    }

    @Test
    fun `creator must be one of the actual two approvals even if independently catalog allowlisted`() {
        val registry = manifest.initialWriterRegistry
        val ids = registry.catalogWriter.catalogApproverIds + "catalog-approver-c"
        val changed = registry.copy(catalogWriter = registry.catalogWriter.copy(catalogApproverIds = ids))
        val body = fixture.bundleBody(changed)
        val rooted = bundleFixture.signed(body.copy(bootstrapAuthority = body.bootstrapAuthority.copy(catalogApproverIds = ids)))
        val rootedBytes = bundleFixture.bytes(rooted)
        val valid = fixture.manifest(rooted, changed)
        OfflineCatalogGenesisVerifier.verify(fixture.bytes(fixture.signed(valid)), rootedBytes, bundleFixture.policy())
        val nominated = valid.copy(creation = valid.creation.copy(creatorId = "catalog-approver-c"))
        reject(fixture.signed(nominated), OfflineTrustBundleFailure.INVALID_DOCUMENT, rootedBytes)
    }

    @Test
    fun `signature records must equal the complete ordered required set without missing duplicate or extra members`() {
        val record = signed.signatures.single()
        listOf(
            emptyList(),
            listOf(record, record),
            listOf(record, record.copy(keyId = "catalog-new")),
            listOf(record.copy(keyId = "catalog-new")),
            listOf(record.copy(algorithmId = "Ed25519")),
        ).forEach { reject(signed.copy(signatures = it)) }
    }

    @Test
    fun `actual catalog verification rejects manifest tampering signature tampering and the bundle domain`() {
        reject(signed.copy(manifest = manifest.copy(operationToken = bundleFixture.EVENT_WRITER)), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        val record = signed.signatures.single()
        val bytes = Base64.getDecoder().decode(record.signatureBase64)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tamperedSignature = record.copy(signatureBase64 = Base64.getEncoder().encodeToString(bytes))
        reject(signed.copy(signatures = listOf(tamperedSignature)), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        val wrongDomain = fixture.signed(manifest, transformFrame = { bundleFixture.independentFrame("catalog-old", fixture.manifestBytes(manifest)) })
        reject(wrongDomain, OfflineTrustBundleFailure.INVALID_SIGNATURE)
    }

    @Test
    fun `only generation one GENESIS with zero predecessor and canonical UUIDv4 operation token is permitted`() {
        listOf(
            manifest.copy(operation = "APPEND"),
            manifest.copy(generation = 0),
            manifest.copy(generation = 2),
            manifest.copy(previousEnvelopeSha256 = "1".repeat(64)),
            manifest.copy(previousEnvelopeSha256 = "0".repeat(63)),
            manifest.copy(operationToken = "00000000-0000-0000-0000-000000000000"),
        ).forEach { rejectManifest(it) }
    }

    @Test
    fun `restore inventory and every explicit nonlegacy history head must be the exact empty commitment`() {
        val nonempty = GenesisEmptyHeadV1(1, manifest.restoreInventory.sha256)
        val history = manifest.history
        listOf(
            manifest.copy(restoreInventory = nonempty),
            manifest.copy(history = history.copy(expiredRestoreSources = nonempty)),
            manifest.copy(history = history.copy(testRunActivations = nonempty)),
            manifest.copy(history = history.copy(testRunTerminals = nonempty)),
            manifest.copy(history = history.copy(installationManifests = nonempty)),
            manifest.copy(history = history.copy(epochSeals = nonempty)),
            manifest.copy(history = history.copy(retirementAuthorizations = nonempty)),
            manifest.copy(history = history.copy(retirementCompletions = nonempty)),
            manifest.copy(restoreInventory = manifest.restoreInventory.copy(count = -1)),
            manifest.copy(restoreInventory = manifest.restoreInventory.copy(sha256 = Sha256.hexUtf8("[{}]"))),
        ).forEach { rejectManifest(it) }
    }

    @Test
    fun `issuance creation approvals and empty restore floor obey the bounded chronology`() {
        val created = manifest.creation.createdAtEpochSecond
        val first = manifest.approvals[0]
        val second = manifest.approvals[1]
        val beforeIssuance = bundle.body.issuedAtEpochSecond - 1
        listOf(
            manifest.copy(creation = manifest.creation.copy(createdAtEpochSecond = beforeIssuance), oldestRestoreTimeEpochSecond = beforeIssuance),
            manifest.copy(approvals = listOf(first.copy(approvedAtEpochSecond = created - 1), second)),
            manifest.copy(approvals = listOf(first, second.copy(approvedAtEpochSecond = 253402300800))),
            manifest.copy(oldestRestoreTimeEpochSecond = created - 1),
        ).forEach { rejectManifest(it) }
        val last = 253402300799L
        val atUpperBound = manifest.copy(
            creation = manifest.creation.copy(createdAtEpochSecond = last),
            approvals = manifest.approvals.map { it.copy(approvedAtEpochSecond = last) },
            oldestRestoreTimeEpochSecond = last,
        )
        OfflineCatalogGenesisVerifier.verify(fixture.bytes(fixture.signed(atUpperBound)), bundleBytes, bundleFixture.policy())
        rejectManifest(atUpperBound.copy(creation = atUpperBound.creation.copy(createdAtEpochSecond = last + 1), oldestRestoreTimeEpochSecond = last + 1))
    }

    @Test
    fun `all new genesis objects are closed and do not admit legacy general history or policy overrides`() {
        val text = fixture.bytes(signed).decodeToString()
        listOf("{", "\"manifest\":{", "\"creation\":{", "\"approvals\":[{", "\"signatures\":[{", "\"history\":{", "\"restoreInventory\":{").forEach { marker ->
            rejectBytes(text.replaceFirst(marker, "$marker\"unknown\":0,").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
        listOf("legacy", "importMetadata", "historyRecords", "allowStale", "trustedPublicKey").forEach { name ->
            rejectBytes(text.replaceFirst("\"manifest\":{", "\"manifest\":{\"$name\":0,").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
        rejectBytes(text.replace("\"operation\":\"GENESIS\",", "").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    @Test
    fun `new genesis entry point rejects malformed and coercible scalar types without normalization`() {
        val text = fixture.bytes(signed).decodeToString()
        listOf("null", "1.0", "true").forEach { value ->
            rejectBytes(text.replace("\"generation\":1", "\"generation\":$value").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
        rejectBytes(text.replace("\"generation\":1", "\"generation\":\"1\"").toByteArray(), OfflineTrustBundleFailure.NON_CANONICAL)
        rejectBytes(text.replace("\"generation\":1", "\"generation\":1,\"generation\":1").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    @Test
    fun `new genesis entry point keeps narrow byte array and exact canonical input bounds`() {
        rejectBytes(ByteArray(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES + 1), OfflineTrustBundleFailure.LIMIT_EXCEEDED, trustBundleBytes = byteArrayOf())
        rejectBytes(" ".toByteArray() + fixture.bytes(signed), OfflineTrustBundleFailure.NON_CANONICAL)
        val seventeen = signed.copy(signatures = List(17) { signed.signatures.single() })
        rejectBytes(fixture.bytes(seventeen), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `checked genesis protects exact bytes and returned approval signer and inline registry lists`() {
        val input = fixture.bytes(signed)
        val original = input.copyOf()
        val rootInput = bundleBytes.copyOf()
        val checked = OfflineCatalogGenesisVerifier.verify(input, rootInput, bundleFixture.policy())
        input.fill(0)
        rootInput.fill(0)
        checked.canonicalManifestBytes.fill(0)
        checked.canonicalEnvelopeBytes.fill(0)
        val returned = checked.manifest
        (returned.approvals as MutableList<OfflineCatalogGenesisApprovalV1>).clear()
        (returned.initialWriterRegistry.catalogWriter.catalogApproverIds as MutableList<String>).clear()
        assertNotSame(returned.requiredSignerPolicy.members, checked.manifest.requiredSignerPolicy.members)
        val returnedRegistrySigners = returned.initialWriterRegistry.catalogWriter.requiredSignerPolicy.members
        assertNotSame(returnedRegistrySigners, checked.manifest.initialWriterRegistry.catalogWriter.requiredSignerPolicy.members)
        assertEquals(manifest, checked.manifest)
        assertArrayEquals(original, checked.canonicalEnvelopeBytes)
        assertArrayEquals(fixture.manifestBytes(manifest), checked.canonicalManifestBytes)
        assertEquals(Sha256.hex(original), checked.envelopeSha256)
    }

    @Test
    fun `genesis evidence holder snapshots caller supplied lists without claiming they were accepted`() {
        val signers = manifest.requiredSignerPolicy.members.toMutableList()
        val approvals = manifest.approvals.toMutableList()
        val registry = manifest.initialWriterRegistry
        val registrySigners = registry.catalogWriter.requiredSignerPolicy.members.toMutableList()
        val registryApprovers = registry.catalogWriter.catalogApproverIds.toMutableList()
        val mutable = manifest.copy(
            requiredSignerPolicy = manifest.requiredSignerPolicy.copy(members = signers),
            approvals = approvals,
            initialWriterRegistry = registry.copy(
                catalogWriter = registry.catalogWriter.copy(
                    requiredSignerPolicy = registry.catalogWriter.requiredSignerPolicy.copy(members = registrySigners),
                    catalogApproverIds = registryApprovers,
                ),
            ),
        )
        val bodyBytes = fixture.manifestBytes(manifest)
        val envelopeBytes = fixture.bytes(signed)
        val checked = CheckedOfflineCatalogGenesis(mutable, bodyBytes, envelopeBytes, Sha256.hex(bodyBytes), Sha256.hex(envelopeBytes))
        signers.clear()
        approvals.clear()
        registrySigners.clear()
        registryApprovers.clear()
        bodyBytes.fill(0)
        envelopeBytes.fill(0)
        assertEquals(manifest, checked.manifest)
        assertArrayEquals(fixture.manifestBytes(manifest), checked.canonicalManifestBytes)
        assertArrayEquals(fixture.bytes(signed), checked.canonicalEnvelopeBytes)
    }

    private fun rejectManifest(value: OfflineCatalogGenesisManifestV1, code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT) {
        val signed = fixture.signed(value)
        reject(signed, code)
    }

    private fun reject(
        value: OfflineCatalogGenesisEnvelopeV1,
        code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT,
        trustBundleBytes: ByteArray = bundleBytes,
        policy: OfflineTrustBundlePolicy = bundleFixture.policy(),
    ) {
        rejectBytes(fixture.bytes(value), code, trustBundleBytes, policy)
    }

    private fun rejectBytes(
        bytes: ByteArray,
        code: OfflineTrustBundleFailure,
        trustBundleBytes: ByteArray = bundleBytes,
        policy: OfflineTrustBundlePolicy = bundleFixture.policy(),
    ) {
        val failure = assertThrows(OfflineTrustBundleException::class.java) { OfflineCatalogGenesisVerifier.verify(bytes, trustBundleBytes, policy) }
        assertEquals(code, failure.code)
    }
}
