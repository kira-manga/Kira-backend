package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenProjection
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier.Overlap2Readback
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier.Overlap2Readback.State
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.Base64

/** Genuine in-memory signatures and the existing raw-port fixture only; no custody, PUT, SQL or release qualification. */
class CatalogOverlap2ReadbackVerifierTest {
    private val fixture by lazy { CatalogReadbackFixture() }
    private val overlap get() = fixture.chain.base.rotations.first()
    private val envelope get() = OfflineCatalogRotationFixture.bytes(overlap)
    private val head2 get() = CatalogLocalHead(2, Sha256.hex(envelope))
    private val dual2 get() = fixture.bytes.take(1) + envelope

    @Test
    fun `signed PREPARED2 with dual G1 only retains predecessor and no copy2 evidence`() {
        val local = prepared()
        val provider = SyntheticCatalogReadbackPort(fixture.bytes.take(1))
        val readback = verify(provider, local)
        assertEquals(State.PREPARED_UNPUBLISHED, readback.state)
        assertEquals(fixture.head(1), readback.snapshotHead)
        assertEquals(local.mutation.operationToken, readback.operationToken)
        assertEquals(head2.envelopeSha256, readback.frozenEnvelopeSha256)
        assertEquals(1L, readback.observedTail.generation)
        assertEquals(fixture.head(1).envelopeSha256, readback.observedTail.envelopeSha256)
        assertEquals(readback.observedTail, checkNotNull(readback.commonHeadEvidence()).chain.tail)
        assertEquals("catalog-version-1", readback.objectVersion)
        assertEquals(CatalogReadbackFixture.RETAIN_UNTIL, readback.retainUntilEpochSecond)
        assertArrayEquals(envelope, readback.frozenEnvelopeBytes())
        assertArrayEquals(fixture.bytes.first(), readback.observedEnvelopeBytes())
        assertEquals(2L, readback.generation().claims.generation)
        assertEquals(head2.envelopeSha256, readback.generation().envelopeSha256)
        assertNull(readback.primaryEvidenceBytes())
        assertNull(readback.replicaEvidenceBytes())
        readback.requireSnapshot(prepared())
        assertClosed(provider, 2)

        val author = CatalogDualLocationVerifier.SignerRotationAuthorReadback.verify(
            SyntheticCatalogReadbackPort(fixture.bytes.take(1)),
            fixture.initial,
            fixture.current,
            fixture.policy(),
            local,
        )
        assertEquals(author.commonHeadEvidence().chain.tail, readback.observedTail)
        assertArrayEquals(author.genesisBytes(), readback.observedEnvelopeBytes())
        failure(CatalogReadbackFailure.HEAD_CONFLICT) {
            CatalogDualLocationVerifier.SignerRotationAuthorReadback.verify(provider(), fixture.initial, fixture.current, fixture.policy(), local)
        }
    }

    @Test
    fun `primary-only frozen2 waits for either status without common or copy authority`() {
        for (status in listOf("PENDING", "COMPLETED")) {
            val provider = SyntheticCatalogReadbackPort(dual2, fixture.bytes.take(1))
            provider.transformMetadata = { metadata ->
                if (metadata.requestBinding.key == CatalogReadbackProtocol.key(2)) metadata.copy(replicationStatus = status) else metadata
            }
            val readback = verify(provider)
            assertEquals(State.PREPARED_AWAIT_REPLICATION, readback.state)
            assertEquals(fixture.head(1), readback.snapshotHead)
            assertEquals(2L, readback.observedTail.generation)
            assertEquals(head2.envelopeSha256, readback.observedTail.envelopeSha256)
            assertEquals(head2.envelopeSha256, readback.frozenEnvelopeSha256)
            assertEquals(overlap.manifest.operationToken, readback.operationToken)
            assertEquals("catalog-version-2", readback.objectVersion)
            assertEquals(CatalogReadbackFixture.RETAIN_UNTIL, readback.retainUntilEpochSecond)
            assertArrayEquals(envelope, readback.observedEnvelopeBytes())
            assertArrayEquals(envelope, readback.frozenEnvelopeBytes())
            // The raw stream still retains the G1 pair. It must not be relabeled as a common/copy2 proof.
            assertNull(readback.commonHeadEvidence())
            assertNull(readback.primaryEvidenceBytes())
            assertNull(readback.replicaEvidenceBytes())
            readback.requireSnapshot(prepared())
            failure(CatalogReadbackFailure.INVALID_LOCAL_STATE) { readback.requireSnapshot(projection()) }
            assertClosed(provider, 3)
            assertEquals(1, provider.getRequests.count { it.location.role == "REPLICA" })
        }
    }

    @Test
    fun `dual frozen2 retains head1 and immutable exact copy tuples after raw bodies close`() {
        val initial = fixture.initial.copyOf()
        val current = fixture.current.copyOf()
        val provider = provider()
        provider.onList = {
            initial.fill(0)
            current.fill(0)
        }
        val readback = verify(provider, initial = initial, current = current)
        assertEquals(State.PREPARED_DUAL_COPY, readback.state)
        assertEquals(fixture.head(1), readback.snapshotHead)
        assertEquals(2L, readback.observedTail.generation)
        assertEquals(head2.envelopeSha256, readback.observedTail.envelopeSha256)
        assertEquals(head2.envelopeSha256, readback.frozenEnvelopeSha256)
        assertEquals(overlap.manifest.operationToken, readback.operationToken)
        val common = checkNotNull(readback.commonHeadEvidence())
        assertEquals(readback.observedTail, common.chain.tail)
        assertNotEquals(readback.snapshotHead.generation, common.chain.tail.generation)
        assertEquals(Sha256.hex(fixture.initial), readback.initialTrustBundleSha256)
        assertEquals(Sha256.hex(fixture.current), readback.currentTrustBundleSha256)
        assertEquals(CatalogReadbackFixture.EVALUATED_AT, readback.evaluatedAtEpochSecond)
        assertEquals(CatalogReadbackFixture.RETAIN_UNTIL, readback.requiredRetainUntilEpochSecond)
        assertEquals("catalog-version-2", readback.objectVersion)
        assertClosed(provider, 4)
        readback.requireSnapshot(prepared())

        val primary = checkNotNull(readback.primaryEvidenceBytes())
        val replica = checkNotNull(readback.replicaEvidenceBytes())
        assertCopy(primary, "PRIMARY")
        assertCopy(replica, "REPLICA")
        assertFalse(primary.contentEquals(replica))
        primary.fill(0)
        replica.fill(0)
        readback.frozenEnvelopeBytes().fill(0)
        readback.observedEnvelopeBytes().fill(0)
        readback.generation().manifestBytes.fill(0)
        checkNotNull(readback.generation().envelopeBytes).fill(0)
        assertArrayEquals(envelope, readback.frozenEnvelopeBytes())
        assertArrayEquals(envelope, readback.observedEnvelopeBytes())
        assertArrayEquals(OfflineCatalogRotationFixture.manifestBytes(overlap.manifest), readback.generation().manifestBytes)
        assertEquals(2L, readback.generation().claims.generation)
        assertEquals("ROTATION_OVERLAP", readback.generation().claims.operation)
        assertCopy(checkNotNull(readback.primaryEvidenceBytes()), "PRIMARY")
        assertCopy(checkNotNull(readback.replicaEvidenceBytes()), "REPLICA")

        val policy = fixture.policy()
        val later = verify(
            provider(),
            policy = CatalogReadbackPolicy(
                policy.chain,
                policy.expectedGenesisEnvelopeSha256,
                policy.evaluatedAtEpochSecond + 10,
                policy.requiredRetainUntilEpochSecond,
                policy.pageSize,
                policy.maximumPagesPerLocation,
            ),
        )
        assertArrayEquals(readback.primaryEvidenceBytes(), later.primaryEvidenceBytes())
        assertArrayEquals(readback.replicaEvidenceBytes(), later.replicaEvidenceBytes())
    }

    @Test
    fun `pending2 resumes only its exact operation head and randomized envelope`() {
        val provider = provider()
        val readback = verify(provider, projection())
        assertEquals(State.PROJECTION_PENDING_DUAL_COPY, readback.state)
        assertEquals(head2, readback.snapshotHead)
        assertEquals(head2.envelopeSha256, readback.frozenEnvelopeSha256)
        assertEquals(overlap.manifest.operationToken, readback.operationToken)
        assertEquals(head2.envelopeSha256, readback.observedTail.envelopeSha256)
        assertEquals(readback.observedTail, checkNotNull(readback.commonHeadEvidence()).chain.tail)
        assertArrayEquals(envelope, readback.observedEnvelopeBytes())
        assertArrayEquals(envelope, readback.frozenEnvelopeBytes())
        assertCopy(checkNotNull(readback.primaryEvidenceBytes()), "PRIMARY")
        assertCopy(checkNotNull(readback.replicaEvidenceBytes()), "REPLICA")
        readback.requireSnapshot(projection())
        assertClosed(provider, 4)

        val replacement = OfflineCatalogRotationFixture.bytes(OfflineCatalogRotationFixture.signed(overlap.manifest))
        assertNotEquals(head2.envelopeSha256, Sha256.hex(replacement))
        for (changed in listOf(
            projection(token = OTHER_TOKEN),
            projection(head = fixture.head(1)),
            projection(head = head2.copy(envelopeSha256 = "0".repeat(64))),
            projection(bytes = replacement, hash = head2.envelopeSha256),
        )) {
            failure(CatalogReadbackFailure.INVALID_LOCAL_STATE) { readback.requireSnapshot(changed) }
            val cold = provider()
            reject(cold, changed, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            assertTrue(cold.listRequests.isEmpty())
        }
        failure(CatalogReadbackFailure.INVALID_LOCAL_STATE) { readback.requireSnapshot(prepared()) }
        reject(SyntheticCatalogReadbackPort(dual2, fixture.bytes.take(1)), projection())
    }

    @Test
    fun `fixed2 raw factory rejects diagnostic promotion unsupported snapshots and later tails`() {
        assertRawFactoryOnly()
        val genesis = fixture.chain.base.genesis
        val activation2 = OfflineCatalogRotationFixture.signed(
            OfflineCatalogRotationFixture.manifest(genesis, 2, fixture.bytes.first(), "SINGLE", listOf("catalog-new")),
        )
        val overlap3 = OfflineCatalogRotationFixture.signed(
            OfflineCatalogRotationFixture.manifest(genesis, 3, envelope, "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new")),
        )
        val slots = prepared().mutation.signatureSlots
        val incompleteSlots = listOf(slots.first(), CatalogFrozenSignatureSlot(slots.last().keyId, slots.last().algorithmId, null))
        val badLocals = listOf(
            LocalCatalogSnapshot.NeverAccepted,
            fixture.preparedGenesis(),
            LocalCatalogSnapshot.Accepted(fixture.head(1)),
            LocalCatalogSnapshot.Accepted(head2),
            prepared(signed = false),
            prepared(token = OTHER_TOKEN),
            prepared(head = head2),
            fixture.prepared(generation = 2),
            prepared(selected = activation2),
            prepared(selected = overlap3, head = head2),
            prepared(slots = incompleteSlots),
            prepared(slots = slots.reversed()),
        )
        val exact = verify(provider())
        for (local in badLocals) {
            val cold = provider()
            reject(cold, local, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            assertTrue(cold.listRequests.isEmpty())
            failure(CatalogReadbackFailure.INVALID_LOCAL_STATE) { exact.requireSnapshot(local) }
        }
        val throughActivation3 = fixture.chain.base.bytes()
        reject(SyntheticCatalogReadbackPort(throughActivation3))
        reject(SyntheticCatalogReadbackPort(throughActivation3, fixture.bytes.take(1)))
        reject(SyntheticCatalogReadbackPort(throughActivation3), projection())
    }

    @Test
    fun `different genuine signatures foreign operations and forged G2 metadata cannot replace frozen proof`() {
        val resigned = OfflineCatalogRotationFixture.signed(overlap.manifest)
        val foreign = OfflineCatalogRotationFixture.signed(overlap.manifest.copy(operationToken = OTHER_TOKEN))
        assertArrayEquals(
            OfflineCatalogRotationFixture.manifestBytes(overlap.manifest),
            OfflineCatalogRotationFixture.manifestBytes(resigned.manifest),
        )
        val exact = verify(provider())
        for (different in listOf(resigned, foreign)) {
            val bytes = OfflineCatalogRotationFixture.bytes(different)
            assertNotEquals(head2.envelopeSha256, Sha256.hex(bytes))
            val chain = fixture.bytes.take(1) + bytes
            // Prove the alternate is genuinely acceptable only with its own frozen tuple, never our operation2 proof.
            assertInstanceOf(
                CatalogReadbackResult.PreparedCompletionEvidence::class.java,
                fixture.verify(SyntheticCatalogReadbackPort(chain), prepared(selected = different)),
            )
            reject(SyntheticCatalogReadbackPort(chain))
            reject(SyntheticCatalogReadbackPort(chain, fixture.bytes.take(1)))
            reject(SyntheticCatalogReadbackPort(chain), projection())
            failure(CatalogReadbackFailure.INVALID_LOCAL_STATE) { exact.requireSnapshot(prepared(selected = different)) }
        }
        val faults: List<Pair<CatalogReadbackFailure, (CatalogObjectMetadata) -> CatalogObjectMetadata>> = listOf(
            CatalogReadbackFailure.INVALID_READBACK to { it.copy(requestBinding = it.requestBinding.copy(versionId = "foreign-version")) },
            CatalogReadbackFailure.RETENTION_MISMATCH to { it.copy(retainUntilEpochSecond = checkNotNull(it.retainUntilEpochSecond) + 1) },
            CatalogReadbackFailure.REPLICATION_MISMATCH to { it.copy(replicationStatus = "COMPLETED") },
        )
        for ((code, corrupt) in faults) {
            val provider = provider()
            provider.transformMetadata = { metadata ->
                if (metadata.requestBinding.key == CatalogReadbackProtocol.key(2) && metadata.requestBinding.location.role == "REPLICA") {
                    corrupt(metadata)
                } else {
                    metadata
                }
            }
            reject(provider, code = code)
        }
    }

    private fun assertRawFactoryOnly() {
        val type = Overlap2Readback::class.java
        val constructors = type.declaredConstructors.filterNot { it.isSynthetic }
        assertEquals(1, constructors.size)
        assertTrue(Modifier.isPrivate(constructors.single().modifiers))
        assertFalse(type.declaredMethods.any { it.name == "copy" || it.name.startsWith("set") })
        assertTrue(type.declaredFields.filterNot { it.isSynthetic }.all { Modifier.isFinal(it.modifiers) })
        val factories = Overlap2Readback.Companion::class.java.declaredMethods.filter {
            !it.isSynthetic && Modifier.isPublic(it.modifiers) && it.returnType == type
        }
        assertEquals(1, factories.size)
        assertTrue(
            factories.single().parameterTypes.contentEquals(
                arrayOf(
                    CatalogReadbackPort::class.java,
                    ByteArray::class.java,
                    ByteArray::class.java,
                    CatalogReadbackPolicy::class.java,
                    LocalCatalogSnapshot::class.java,
                ),
            ),
        )
        val diagnostic = assertInstanceOf(
            CatalogReadbackResult.PreparedCompletionEvidence::class.java,
            fixture.verify(provider(), prepared()),
        )
        assertFalse(type.isInstance(diagnostic.copy(operationToken = OTHER_TOKEN)))
    }

    private fun assertCopy(bytes: ByteArray, role: String) {
        assertTrue(bytes.size in 1..65536)
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(
            setOf(
                "schemaVersion", "canonicalizerId", "location", "objectKey", "objectVersion", "contentLength",
                "envelopeSha256", "objectLockMode", "retainUntilEpochSecond", "replicationStatus",
            ),
            json.keys,
        )
        val location = OfflineTrustBundleFixture.locations.single { it.role == role }
        assertEquals(
            mapOf("role" to role, "bucket" to location.bucket, "accountId" to location.accountId, "region" to location.region),
            json.getValue("location").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        val expected = mapOf(
            "schemaVersion" to "1",
            "canonicalizerId" to CanonicalJson.CANON_VERSION,
            "objectKey" to CatalogReadbackProtocol.key(2),
            "objectVersion" to "catalog-version-2",
            "contentLength" to envelope.size.toString(),
            "envelopeSha256" to head2.envelopeSha256,
            "objectLockMode" to "COMPLIANCE",
            "retainUntilEpochSecond" to CatalogReadbackFixture.RETAIN_UNTIL.toString(),
            "replicationStatus" to if (role == "PRIMARY") "COMPLETED" else "REPLICA",
        )
        expected.forEach { (key, value) -> assertEquals(value, json.getValue(key).jsonPrimitive.content) }
    }

    private fun prepared(
        selected: OfflineCatalogRotationEnvelopeV1 = overlap,
        head: CatalogLocalHead = fixture.head(1),
        token: String = selected.manifest.operationToken,
        signed: Boolean = true,
        slots: List<CatalogFrozenSignatureSlot> = selected.signatures.map {
            CatalogFrozenSignatureSlot(it.keyId, it.algorithmId, if (signed) Base64.getDecoder().decode(it.signatureBase64) else null)
        },
    ): LocalCatalogSnapshot.Prepared {
        val unsigned = OfflineCatalogRotationFixture.manifestBytes(selected.manifest)
        val bytes = if (signed) OfflineCatalogRotationFixture.bytes(selected) else null
        return LocalCatalogSnapshot.Prepared(
            head,
            CatalogFrozenMutation(selected.schemaVersion, token, unsigned, Sha256.hex(unsigned), bytes, bytes?.let(Sha256::hex), slots),
        )
    }

    private fun projection(
        bytes: ByteArray = envelope,
        head: CatalogLocalHead = head2,
        token: String = overlap.manifest.operationToken,
        hash: String = Sha256.hex(bytes),
    ): LocalCatalogSnapshot.ProjectionPending = LocalCatalogSnapshot.ProjectionPending(head, CatalogFrozenProjection(token, bytes, hash))

    private fun provider(): SyntheticCatalogReadbackPort = SyntheticCatalogReadbackPort(dual2)

    private fun verify(
        provider: SyntheticCatalogReadbackPort,
        local: LocalCatalogSnapshot = prepared(),
        policy: CatalogReadbackPolicy = fixture.policy(),
        initial: ByteArray = fixture.initial,
        current: ByteArray = fixture.current,
    ): Overlap2Readback = Overlap2Readback.verify(provider, initial, current, policy, local)

    private fun reject(
        provider: SyntheticCatalogReadbackPort,
        local: LocalCatalogSnapshot = prepared(),
        code: CatalogReadbackFailure = CatalogReadbackFailure.HEAD_CONFLICT,
    ) {
        failure(code) { verify(provider, local) }
        assertClosed(provider, provider.getRequests.size)
    }

    private fun assertClosed(provider: SyntheticCatalogReadbackPort, count: Int) {
        assertEquals(count, provider.closedBodies)
        assertEquals(0, provider.openBodies)
    }

    private fun failure(code: CatalogReadbackFailure, action: () -> Unit) {
        val failure = assertThrows(CatalogReadbackException::class.java) { action() }
        assertEquals(code, failure.code)
        assertEquals("Catalog readback rejected: ${code.name}.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    companion object {
        private const val OTHER_TOKEN = "66666666-6666-4666-8666-666666666666"
    }
}
