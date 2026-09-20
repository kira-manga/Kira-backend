package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogInventoryParser
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineCatalogGeneration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfflineCatalogInventoryParserTest {
    private val fixture = OfflineCatalogInventoryFixture
    private val rotations = OfflineCatalogRotationFixture
    private val chain by lazy { fixture.chain() }
    private val register get() = chain.generations.first()

    @Test
    fun `closed new schema round trips exact canonical bytes without pretending to authenticate inventory`() {
        val parsed = OfflineCatalogInventoryParser.parse(fixture.bytes(register), 4096)
        assertEquals(register, (parsed as ParsedOfflineCatalogGeneration.InventoryV2).envelope)
    }

    @Test
    fun `separate inventory parser supports more than sixteen copies without widening the original parser`() {
        val source = register.manifest.restoreInventory.sources.single()
        val copies = (1..17).map { fixture.copy(source, it) }
        val enlarged = register.copy(manifest = register.manifest.copy(restoreInventory = register.manifest.restoreInventory.copy(copies = copies)))
        val bytes = fixture.bytes(enlarged)
        val parsed = OfflineCatalogInventoryParser.parse(bytes, 4096) as ParsedOfflineCatalogGeneration.InventoryV2
        assertEquals(17, parsed.envelope.manifest.restoreInventory.copies.size)
        val failure = assertThrows(OfflineTrustBundleException::class.java) { OfflineTrustBundleParser.parseRotation(bytes, 4096) }
        assertEquals(OfflineTrustBundleFailure.LIMIT_EXCEEDED, failure.code)
    }

    @Test
    fun `whole manifest record limit includes source bundle artifact and copy version wrappers and resets per envelope`() {
        val first = fixture.bytes(register)
        val second = fixture.bytes(chain.generations.last())
        val firstCount = rotations.manifestRecords(first)
        val secondCount = rotations.manifestRecords(second)
        assertEquals(firstCount + 4, secondCount)
        OfflineCatalogInventoryParser.parse(first, firstCount)
        OfflineCatalogInventoryParser.parse(second, secondCount)
        OfflineCatalogInventoryParser.parse(first, firstCount)
        reject(first, OfflineTrustBundleFailure.LIMIT_EXCEEDED, firstCount - 1)
        reject(second, OfflineTrustBundleFailure.LIMIT_EXCEEDED, secondCount - 1)
        assertTrue(firstCount > 1 + 5 + 4, "The whole manifest bound is stricter than counting inventory objects alone")
    }

    @Test
    fun `signature objects outside manifest do not consume manifest records but retain their own sixteen item cap`() {
        val records = rotations.manifestRecords(fixture.bytes(register))
        val signatures = List(16) { register.signatures.single() }
        OfflineCatalogInventoryParser.parse(fixture.bytes(register.copy(signatures = signatures)), records)
        reject(fixture.bytes(register.copy(signatures = signatures + signatures.first())), OfflineTrustBundleFailure.LIMIT_EXCEEDED, records)
    }

    @Test
    fun `schema one prefix still goes through the original closed rotation decoder`() {
        val old = chain.base.rotations.first()
        val parsed = OfflineCatalogInventoryParser.parse(rotations.bytes(old), 4096)
        assertEquals(old, (parsed as ParsedOfflineCatalogGeneration.RotationV1).envelope)
        val text = rotations.bytes(old).decodeToString().replaceFirst("\"history\":{", "\"history\":{\"records\":[],")
        reject(text.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    @Test
    fun `new envelope nested inventory bundle copy version and delta objects remain closed`() {
        val text = fixture.bytes(register).decodeToString()
        listOf(
            "{", "\"manifest\":{", "\"restoreInventory\":{", "\"sources\":[{", "\"copies\":[{",
            "\"bundle\":{", "\"dump\":{", "\"inventoryDelta\":{", "\"history\":{", "\"signatures\":[{",
        ).forEach { marker ->
            val changed = text.replaceFirst(marker, "$marker\"unknown\":0,")
            reject(changed.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
    }

    @Test
    fun `duplicate fields null boolean float and overflowing integer values are rejected before typed use`() {
        val text = fixture.bytes(register).decodeToString()
        listOf(
            text.replaceFirst("\"generation\":2", "\"generation\":2,\"generation\":2"),
            text.replaceFirst("\"generation\":2", "\"generation\":null"),
            text.replaceFirst("\"generation\":2", "\"generation\":true"),
            text.replaceFirst("\"generation\":2", "\"generation\":2.0"),
            text.replaceFirst("\"generation\":2", "\"generation\":9223372036854775808"),
        ).forEach { reject(it.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT) }
        val tooLong = text.replaceFirst("\"generation\":2", "\"generation\":92233720368547758080")
        reject(tooLong.toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `strict UTF8 canonical byte equality and single document rules still apply to larger inventory input`() {
        val bytes = fixture.bytes(register)
        reject(bytes + byteArrayOf(0xc3.toByte()), OfflineTrustBundleFailure.MALFORMED_INPUT)
        reject(bytes + " {}".toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        reject(bytes + "\n".toByteArray(), OfflineTrustBundleFailure.NON_CANONICAL)
        val surrogate = bytes.decodeToString().replace("NEW_BACKEND_LOGICAL_BUNDLE_V1", "\\uD800")
        reject(surrogate.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        reject("[]".toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    @Test
    fun `unsupported envelope schema and invalid configured record ceilings have no generic fallback`() {
        reject(fixture.bytes(register.copy(schemaVersion = 3)), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        listOf(0, -1, 4097, Int.MAX_VALUE).forEach { maximum ->
            reject(fixture.bytes(register), OfflineTrustBundleFailure.INVALID_POLICY, maximum)
        }
    }

    @Test
    fun `complete inventory envelope byte ceiling is checked before trying to decode bytes`() {
        reject(ByteArray(OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES + 1), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    private fun reject(bytes: ByteArray, code: OfflineTrustBundleFailure, maximum: Int = 4096) {
        val failure = assertThrows(OfflineTrustBundleException::class.java) { OfflineCatalogInventoryParser.parse(bytes, maximum) }
        assertEquals(code, failure.code)
    }
}
