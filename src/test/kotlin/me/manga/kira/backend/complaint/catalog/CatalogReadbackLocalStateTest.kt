package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenProjection
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class CatalogReadbackLocalStateTest {
    private val fixture by lazy { CatalogReadbackFixture() }

    @Test
    fun `invalid local heads are rejected before even the first provider call`() {
        listOf(0L, -1L, 65537L, Long.MAX_VALUE).forEach { generation ->
            reject(LocalCatalogSnapshot.Accepted(CatalogLocalHead(generation, fixture.head().envelopeSha256)))
        }
        listOf("", "A".repeat(64), "0".repeat(63), "x".repeat(1025)).forEach { hash ->
            reject(LocalCatalogSnapshot.Accepted(CatalogLocalHead(3, hash)))
        }
        reject(LocalCatalogSnapshot.Accepted(CatalogLocalHead(1, "0".repeat(64))))
    }

    @Test
    fun `frozen hashes schema selector token and signed presence are independently checked before calls`() {
        listOf(
            fixture.mutation(manifestHash = "0".repeat(64)),
            fixture.mutation(signedHash = "0".repeat(64)),
            fixture.mutation(schema = 1),
            fixture.mutation(schema = 3),
            fixture.mutation(token = "not-a-token"),
            fixture.mutation(token = "x".repeat(1025)),
            fixture.mutation(signed = false, signedHash = "0".repeat(64)),
            fixture.mutation(signedHash = null),
        ).forEach(::rejectMutation)
    }

    @Test
    fun `canonical unsigned bytes cannot contain trailing text unknown fields or alternate JSON spelling`() {
        val original = OfflineCatalogInventoryFixture.manifestBytes(fixture.chain.generations.last().manifest)
        val text = original.decodeToString()
        val alternatives = listOf(text + "\n", text + "{}", " " + text, text.replaceFirst("{", "{\"unknown\":true,"))
        alternatives.forEach { bytes -> rejectMutation(fixture.mutation(signed = false, manifestBytes = bytes.toByteArray())) }
    }

    @Test
    fun `the exact signed manifest must agree with the separately frozen unsigned manifest`() {
        val changed = fixture.chain.generations.last().manifest.copy(operationToken = OfflineTrustBundleFixture.EVENT_WRITER)
        val signed = OfflineCatalogInventoryFixture.bytes(OfflineCatalogInventoryFixture.signed(changed))
        rejectMutation(fixture.mutation(signedBytes = signed))
    }

    @Test
    fun `local successor generation predecessor initial trust writer and operation cannot be nominated`() {
        val envelope = fixture.chain.generations.last()
        val original = envelope.manifest
        val alternatives = listOf(
            original.copy(generation = 4),
            original.copy(previousEnvelopeSha256 = "0".repeat(64)),
            original.copy(initialTrustBundleEnvelopeSha256 = "0".repeat(64)),
            original.copy(catalogWriterGenerationId = OfflineTrustBundleFixture.EVENT_WRITER),
            original.copy(operation = "REMOVE_SOURCE"),
            original.copy(operationToken = "invalid"),
            original.copy(profile = "UNREVIEWED_PROFILE"),
            original.copy(canonicalizerId = "other-json"),
            original.copy(schemaVersion = 1),
        )
        alternatives.forEach { manifest -> rejectMutation(fixture.mutation(envelope.copy(manifest = manifest), signed = false)) }
    }

    @Test
    fun `local registry commitments chronology exact approvers and empty histories are validated before calls`() {
        val envelope = fixture.chain.generations.last()
        val original = envelope.manifest
        val alternatives = listOf(
            original.copy(initialWriterRegistry = original.initialWriterRegistry.copy(databaseIdentity = OfflineTrustBundleFixture.RESTORE_ID)),
            original.copy(oldestRestoreTimeEpochSecond = original.creation.createdAtEpochSecond + 1),
            original.copy(creation = original.creation.copy(createdAtEpochSecond = -1)),
            original.copy(creation = original.creation.copy(creatorId = "unapproved")),
            original.copy(approvals = original.approvals.take(1)),
            original.copy(approvals = original.approvals.reversed()),
            original.copy(approvals = listOf(original.approvals.first(), original.approvals.first())),
            original.copy(approvals = original.approvals.map { it.copy(approvedAtEpochSecond = original.creation.createdAtEpochSecond - 1) }),
            original.copy(history = original.history.copy(epochSeals = original.history.epochSeals.copy(count = 1))),
        )
        alternatives.forEach { manifest -> rejectMutation(fixture.mutation(envelope.copy(manifest = manifest), signed = false)) }
    }

    @Test
    fun `local writer and approvers still need independently supplied current authority`() {
        val limits = OfflineCatalogRotationFixture.limits()
        val writers = OfflineCatalogRotationFixture.policy(limits, writers = listOf(OfflineTrustBundleFixture.EVENT_WRITER))
        val approvers = OfflineCatalogRotationFixture.policy(limits, approvers = listOf("catalog-approver-a", "current-only"))
        listOf(writers, approvers).forEach { chain ->
            val policy = CatalogReadbackPolicy(
                chain,
                Sha256.hex(fixture.bytes.first()),
                CatalogReadbackFixture.EVALUATED_AT,
                CatalogReadbackFixture.RETAIN_UNTIL,
                1,
                8,
            )
            reject(fixture.prepared(), policy = policy)
        }
    }

    @Test
    fun `local signer mode threshold membership and algorithms cannot be accepted as opaque claims`() {
        val envelope = fixture.chain.generations.last()
        val original = envelope.manifest.requiredSignerPolicy
        val member = original.members.single()
        val alternatives = listOf(
            original.copy(mode = "ROTATION_OVERLAP"),
            original.copy(threshold = "ANY_MEMBER"),
            original.copy(members = emptyList()),
            original.copy(members = listOf(member, member)),
            original.copy(members = listOf(member.copy(keyId = "unknown-key"))),
            original.copy(members = listOf(member.copy(algorithmId = "RSA"))),
        )
        alternatives.forEach { policy ->
            rejectMutation(fixture.mutation(envelope.copy(manifest = envelope.manifest.copy(requiredSignerPolicy = policy)), signed = false))
        }
    }

    @Test
    fun `missing duplicate mismatched and forged local signatures fail before provider invocation`() {
        val original = fixture.chain.generations.last()
        val signature = original.signatures.single()
        val corrupted = Base64.getDecoder().decode(signature.signatureBase64).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val alternatives = listOf(
            original.copy(signatures = emptyList()),
            original.copy(signatures = original.signatures + original.signatures),
            original.copy(signatures = listOf(signature.copy(keyId = "catalog-new"))),
            original.copy(signatures = listOf(signature.copy(signatureBase64 = Base64.getEncoder().encodeToString(corrupted)))),
        )
        alternatives.forEach { rejectMutation(fixture.mutation(it)) }
    }

    @Test
    fun `projection token hash head and signatures are all checked independently before calls`() {
        val original = fixture.projection()
        val bytes = original.projection.signedEnvelopeBytes
        val token = original.projection.operationToken
        val head = original.head
        reject(LocalCatalogSnapshot.ProjectionPending(head, CatalogFrozenProjection("wrong", bytes, head.envelopeSha256)))
        reject(LocalCatalogSnapshot.ProjectionPending(head, CatalogFrozenProjection(token, bytes, "0".repeat(64))))
        reject(LocalCatalogSnapshot.ProjectionPending(fixture.head(2), original.projection))
        val unsigned = OfflineCatalogInventoryFixture.bytes(fixture.chain.generations.last().copy(signatures = emptyList()))
        val hash = Sha256.hex(unsigned)
        reject(LocalCatalogSnapshot.ProjectionPending(CatalogLocalHead(3, hash), CatalogFrozenProjection(token, unsigned, hash)))
    }

    @Test
    fun `genesis projection cannot bypass actual signature verification through its local hash`() {
        val original = fixture.chain.base.genesis
        val forged = original.copy(signatures = emptyList())
        val bytes = OfflineCatalogGenesisFixture.bytes(forged)
        val hash = Sha256.hex(bytes)
        val projection = CatalogFrozenProjection(original.manifest.operationToken, bytes, hash)
        val policy = CatalogReadbackPolicy(
            fixture.policy().chain,
            hash,
            CatalogReadbackFixture.EVALUATED_AT,
            CatalogReadbackFixture.RETAIN_UNTIL,
            1,
            8,
        )
        reject(LocalCatalogSnapshot.ProjectionPending(CatalogLocalHead(1, hash), projection), policy = policy)
    }

    @Test
    fun `frozen mutation and projection own their constructor buffers and never expose retained arrays`() {
        val envelope = fixture.bytes.last().copyOf()
        val manifest = OfflineCatalogInventoryFixture.manifestBytes(fixture.chain.generations.last().manifest)
        val mutation = fixture.mutation(manifestBytes = manifest, signedBytes = envelope)
        manifest.fill(0)
        envelope.fill(0)
        mutation.unsignedManifestBytes.fill(0)
        mutation.signedEnvelopeBytes?.fill(0)
        val prepared = LocalCatalogSnapshot.Prepared(fixture.head(2), mutation)
        assertInstanceOf(CatalogReadbackResult.PreparedCompletionEvidence::class.java, fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes), prepared))
        val projectionBytes = fixture.bytes.last().copyOf()
        val token = fixture.chain.generations.last().manifest.operationToken
        val projection = CatalogFrozenProjection(token, projectionBytes, fixture.head().envelopeSha256)
        projectionBytes.fill(0)
        projection.signedEnvelopeBytes.fill(0)
        val pending = LocalCatalogSnapshot.ProjectionPending(fixture.head(), projection)
        assertInstanceOf(CatalogReadbackResult.ProjectionResumeEvidence::class.java, fixture.verify(SyntheticCatalogReadbackPort(fixture.bytes), pending))
    }

    @Test
    fun `both raw trust inputs are snapshotted before provider code can mutate caller buffers`() {
        val initial = fixture.initial.copyOf()
        val current = fixture.current.copyOf()
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        provider.onList = {
            initial.fill(0)
            current.fill(0)
        }
        val result = fixture.verify(provider, initialBytes = initial, currentBytes = current)
        assertInstanceOf(CatalogReadbackResult.CurrentHeadObserved::class.java, result)
        assertTrue(initial.all { it == 0.toByte() })
        assertTrue(current.all { it == 0.toByte() })
    }

    @Test
    fun `current raw trust is really authenticated before any provider access`() {
        val current = fixture.chain.base.current
        val tampered = OfflineTrustBundleFixture.bytes(current.copy(body = current.body.copy(issuedAtEpochSecond = current.body.issuedAtEpochSecond + 1)))
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        val failure = assertThrows(OfflineTrustBundleException::class.java) { fixture.verify(provider, currentBytes = tampered) }
        assertEquals(OfflineTrustBundleFailure.INVALID_SIGNATURE, failure.code)
        assertTrue(provider.listRequests.isEmpty())
        assertTrue(provider.getRequests.isEmpty())
    }

    @Test
    fun `lowered local byte and generation limits reject before provider calls`() {
        val tiny = fixture.policy(OfflineCatalogRotationFixture.limits().copy(maximumEnvelopeBytes = 1))
        reject(fixture.prepared(), CatalogReadbackFailure.LIMIT_EXCEEDED, tiny)
        val generations = fixture.policy(OfflineCatalogRotationFixture.limits().copy(maximumGenerations = 2))
        reject(LocalCatalogSnapshot.Accepted(fixture.head()), policy = generations)
        reject(fixture.prepared(), policy = generations)
    }

    @Test
    fun `frozen buffers enforce the hard maximum before defensive constructor copies`() {
        listOf(ByteArray(0), ByteArray(OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES + 1)).forEach { bytes ->
            val failure = assertThrows(CatalogReadbackException::class.java) { fixture.mutation(manifestBytes = bytes, signed = false) }
            assertEquals(CatalogReadbackFailure.LIMIT_EXCEEDED, failure.code)
        }
    }

    private fun rejectMutation(mutation: CatalogFrozenMutation) = reject(LocalCatalogSnapshot.Prepared(fixture.head(2), mutation))

    private fun reject(
        local: LocalCatalogSnapshot,
        code: CatalogReadbackFailure = CatalogReadbackFailure.INVALID_LOCAL_STATE,
        policy: CatalogReadbackPolicy = fixture.policy(),
    ) {
        val provider = SyntheticCatalogReadbackPort(fixture.bytes)
        val failure = assertThrows(CatalogReadbackException::class.java) { fixture.verify(provider, local, policy) }
        assertEquals(code, failure.code)
        assertTrue(provider.listRequests.isEmpty())
        assertTrue(provider.getRequests.isEmpty())
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
