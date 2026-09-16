package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogFrozenManifestParser
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogLocalSnapshotVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** Genuine existing in-memory signatures only; these observations cannot publish, project or accept a head. */
class CatalogSparseSignatureTest {
    private val fixture by lazy { CatalogReadbackFixture() }
    private val overlap get() = fixture.chain.base.rotations.first()
    private val trust by lazy { OfflineTrustBundleVerifier.verify(fixture.current, fixture.policy().chain.trustBundlePolicy) }

    @Test
    fun `either overlap slot may be retained alone but every present signature is authenticated`() {
        for (retained in 0..1) {
            val slots = overlap.signatures.mapIndexed { index, signature ->
                CatalogFrozenSignatureSlot(signature.keyId, signature.algorithmId, if (index == retained) bytes(index) else null)
            }
            val checked = validate(mutation(slots))
            assertNull(checked.frozen?.envelopeBytes)
            assertEquals(overlap.manifest.operationToken, checked.frozen?.claims?.operationToken)
        }
    }

    @Test
    fun `forged or wrong-length partial signatures never hide behind an absent envelope`() {
        for (retained in 0..1) {
            val corrupted = bytes(retained).also { it[0] = (it[0].toInt() xor 1).toByte() }
            reject(mutation(slots(retained, corrupted)))
        }
        reject(mutation(slots(0, ByteArray(383))))
    }

    @Test
    fun `sparse slot identity order algorithm and exact manifest frame are binding`() {
        val original = slots(0, bytes(0))
        reject(mutation(original.reversed()))
        val first = original.first()
        reject(mutation(listOf(CatalogFrozenSignatureSlot(first.keyId, "RSA", first.signatureBytes), original.last())))
        val changed = overlap.manifest.copy(
            creation = overlap.manifest.creation.copy(createdAtEpochSecond = overlap.manifest.creation.createdAtEpochSecond + 1),
        )
        reject(mutation(original, unsigned = OfflineCatalogRotationFixture.manifestBytes(changed)))
    }

    @Test
    fun `complete envelope must use the exact stored PSS bytes not another genuine re-signing`() {
        val fresh = OfflineCatalogRotationFixture.signed(overlap.manifest)
        val frozen = overlap.signatures.map { CatalogFrozenSignatureSlot(it.keyId, it.algorithmId, Base64.getDecoder().decode(it.signatureBase64)) }
        reject(mutation(frozen, envelope = OfflineCatalogRotationFixture.bytes(fresh)))
        reject(mutation(slots(0, bytes(0)), envelope = OfflineCatalogRotationFixture.bytes(overlap)))
    }

    @Test
    fun `partial signature constructors and getters never expose retained byte arrays or slot collection`() {
        val original = bytes(1)
        val slots = slots(1, original).toMutableList()
        val local = mutation(slots)
        original.fill(0)
        slots.clear()
        local.mutation.signatureSlots.last().signatureBytes?.fill(0)
        validate(local)
        assertTrue(local.mutation.signatureSlots.last().signatureBytes!!.contentEquals(bytes(1)))
    }

    @Test
    fun `V14 schema selector is the actual root field and never inferred from a nested registry or operation`() {
        val limits = fixture.policy().chain.limits
        val rotation = OfflineCatalogRotationFixture.manifestBytes(overlap.manifest)
        val inventory = OfflineCatalogInventoryFixture.manifestBytes(fixture.chain.generations.last().manifest)
        assertEquals(1, CatalogFrozenManifestParser.schemaVersion(rotation, limits))
        assertEquals(2, CatalogFrozenManifestParser.schemaVersion(inventory, limits))
        listOf(
            "{\"registry\":{\"schemaVersion\":2}}",
            "{\"schemaVersion\":1,\"schemaVersion\":2}",
            "{\"schemaVersion\":\"2\"}",
            "{\"schemaVersion\":3}",
            "{\"schemaVersion\":2}{}",
        ).forEach { text ->
            val failure = assertThrows(CatalogReadbackException::class.java) { CatalogFrozenManifestParser.schemaVersion(text.toByteArray(), limits) }
            assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, failure.code)
        }
    }

    private fun bytes(index: Int): ByteArray = Base64.getDecoder().decode(overlap.signatures[index].signatureBase64)

    private fun slots(retained: Int, bytes: ByteArray): List<CatalogFrozenSignatureSlot> = overlap.signatures.mapIndexed { index, signature ->
        CatalogFrozenSignatureSlot(signature.keyId, signature.algorithmId, if (index == retained) bytes else null)
    }

    private fun mutation(
        slots: List<CatalogFrozenSignatureSlot>,
        unsigned: ByteArray = OfflineCatalogRotationFixture.manifestBytes(overlap.manifest),
        envelope: ByteArray? = null,
    ): LocalCatalogSnapshot.Prepared = LocalCatalogSnapshot.Prepared(
        fixture.head(1),
        CatalogFrozenMutation(1, overlap.manifest.operationToken, unsigned, Sha256.hex(unsigned), envelope, envelope?.let(Sha256::hex), slots),
    )

    private fun validate(local: LocalCatalogSnapshot.Prepared) = CatalogLocalSnapshotVerifier.validate(local, fixture.initial, trust, fixture.policy())

    private fun reject(local: LocalCatalogSnapshot.Prepared) {
        val failure = assertThrows(CatalogReadbackException::class.java) { validate(local) }
        assertEquals(CatalogReadbackFailure.INVALID_LOCAL_STATE, failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
