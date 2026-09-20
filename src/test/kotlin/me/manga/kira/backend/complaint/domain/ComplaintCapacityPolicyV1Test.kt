package me.manga.kira.backend.complaint.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.HexFormat

class ComplaintCapacityPolicyV1Test {
    @Test
    fun `golden bytes bind explicit v1 identity and all twenty two ordered counters`() {
        val policy = goldenPolicy()
        val expected = requireNotNull(javaClass.getResourceAsStream("/fixtures/complaint-capacity-policy-v1/full-policy.json"))
            .use { it.readBytes() }
        assertEquals(1829, expected.size)
        assertArrayEquals(expected, policy.canonicalBytes())
        assertEquals(GOLDEN_SHA256, policy.sha256)
        assertArrayEquals(HexFormat.of().parseHex(GOLDEN_SHA256), policy.digestBytes())

        val document = document(policy)
        assertEquals("kira-complaint-capacity-policy", document.getValue("kind").jsonPrimitive.content)
        assertEquals("kcj-1", document.getValue("canonicalizerId").jsonPrimitive.content)
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(1, document.getValue("accountingVersion").jsonPrimitive.int)
        assertEquals(941L, document.getValue("dailyEnrollmentLimit").jsonPrimitive.long)
        val counters = document.getValue("counters").jsonArray
        assertEquals(22, counters.size)
        STORED_NAMES.forEachIndexed { index, name ->
            val row = counters[index].jsonObject
            assertEquals(name, row.getValue("name").jsonPrimitive.content)
            assertEquals(index + 1, row.getValue("ordinal").jsonPrimitive.int)
            assertEquals(1000L + (index + 1) * 17L, row.getValue("hardLimit").jsonPrimitive.long)
            assertEquals(200L + (index + 1) * 7L, row.getValue("creationLimit").jsonPrimitive.long)
        }
    }

    @Test
    fun `every declared hard creation and daily limit participates in the digest`() {
        val policy = goldenPolicy()
        ComplaintCapacityEncoding.vectorOrder().forEach { counter ->
            val changedHard = policy.hardLimit.with(counter, policy.hardLimit[counter] + 1L)
            val changedCreation = policy.creationLimit.with(counter, policy.creationLimit[counter] + 1L)
            assertNotEquals(policy.sha256, ComplaintCapacityPolicyV1.of(changedHard, policy.creationLimit, 941L).sha256)
            assertNotEquals(policy.sha256, ComplaintCapacityPolicyV1.of(policy.hardLimit, changedCreation, 941L).sha256)
        }
        assertNotEquals(policy.sha256, ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit, 942L).sha256)
    }

    @Test
    fun `construction order and mutations of inputs or returned bytes cannot alter policy`() {
        val hard = LongArray(22) { 1000L + (it + 1) * 17L }
        val creation = LongArray(22) { 200L + (it + 1) * 7L }
        val policy = ComplaintCapacityPolicyV1.of(ComplaintCapacityVector.of(hard), ComplaintCapacityVector.of(creation), 941L)
        val bytes = policy.canonicalBytes()
        val digest = policy.digestBytes()
        hard.fill(0L)
        creation.fill(0L)
        policy.hardLimit.toLongArray().fill(0L)
        policy.creationLimit.toLongArray().fill(0L)
        policy.canonicalBytes().fill(0)
        policy.digestBytes().fill(0)
        assertArrayEquals(bytes, policy.canonicalBytes())
        assertArrayEquals(digest, policy.digestBytes())
        assertEquals(GOLDEN_SHA256, policy.sha256)

        val reversed = ComplaintCapacityEncoding.vectorOrder().reversed()
        val reorderedHard = reversed.fold(ComplaintCapacityVector.ZERO) { vector, counter ->
            vector.with(counter, 1000L + counter.storedOrdinal * 17L)
        }
        val reorderedCreation = reversed.fold(ComplaintCapacityVector.ZERO) { vector, counter ->
            vector.with(counter, 200L + counter.storedOrdinal * 7L)
        }
        val reordered = ComplaintCapacityPolicyV1.of(reorderedHard, reorderedCreation, 941L)
        assertEquals(reorderedHard, policy.hardLimit)
        assertEquals(reorderedCreation, policy.creationLimit)
        assertArrayEquals(bytes, reordered.canonicalBytes())
        assertArrayEquals(digest, reordered.digestBytes())
    }

    @Test
    fun `zero and maximum limits are represented explicitly without arithmetic`() {
        listOf(0L, Long.MAX_VALUE).forEach { limit ->
            val vector = ComplaintCapacityVector.of(LongArray(22) { limit })
            val document = document(ComplaintCapacityPolicyV1.of(vector, vector, limit))
            assertEquals(limit.toString(), document.getValue("dailyEnrollmentLimit").jsonPrimitive.content)
            val counters = document.getValue("counters").jsonArray
            assertEquals(22, counters.size)
            counters.forEach { element ->
                val row = element.jsonObject
                assertEquals(limit.toString(), row.getValue("hardLimit").jsonPrimitive.content)
                assertEquals(limit.toString(), row.getValue("creationLimit").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `negative daily limits and creation above any hard limit are rejected`() {
        val policy = goldenPolicy()
        listOf(-1L, Long.MIN_VALUE).forEach { daily ->
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) {
                ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit, daily)
            }
        }
        ComplaintCapacityEncoding.vectorOrder().forEach { counter ->
            val excessive = policy.creationLimit.with(counter, policy.hardLimit[counter] + 1L)
            assertFailure(ComplaintCapacityFailureCode.INVALID_CREATION_LIMIT) {
                ComplaintCapacityPolicyV1.of(policy.hardLimit, excessive, policy.dailyEnrollmentLimit)
            }
            assertFailure(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT) { policy.hardLimit.with(counter, -1L) }
        }
        listOf(0, 21, 23).forEach { width ->
            assertFailure(ComplaintCapacityFailureCode.INVALID_VECTOR_WIDTH) { ComplaintCapacityVector.of(LongArray(width)) }
        }
        assertFailure(ComplaintCapacityFailureCode.UNSUPPORTED_VERSION) { ComplaintCapacityVector.of(LongArray(22), version = 2) }
    }

    @Test
    fun `canonical fields exclude observations and diagnostics are redacted`() {
        val policy = goldenPolicy()
        val document = document(policy)
        assertEquals(
            setOf("kind", "schemaVersion", "canonicalizerId", "accountingVersion", "counters", "dailyEnrollmentLimit"),
            document.keys,
        )
        document.getValue("counters").jsonArray.forEach { row ->
            assertEquals(setOf("name", "ordinal", "hardLimit", "creationLimit"), row.jsonObject.keys)
        }
        assertEquals("ComplaintCapacityPolicyV1(redacted)", policy.toString())
        val failure = assertThrows<ComplaintCapacityException> {
            ComplaintCapacityPolicyV1.of(policy.hardLimit, policy.creationLimit, Long.MIN_VALUE)
        }
        assertEquals("Complaint capacity rejected: INVALID_CONFIGURATION.", failure.message)
    }

    private fun goldenPolicy(): ComplaintCapacityPolicyV1 = ComplaintCapacityPolicyV1.of(
        hardLimit = ComplaintCapacityVector.of(LongArray(22) { 1000L + (it + 1) * 17L }),
        creationLimit = ComplaintCapacityVector.of(LongArray(22) { 200L + (it + 1) * 7L }),
        dailyEnrollmentLimit = 941L,
    )

    private fun document(policy: ComplaintCapacityPolicyV1): JsonObject = Json.parseToJsonElement(policy.canonicalBytes().toString(Charsets.UTF_8)).jsonObject

    private fun assertFailure(code: ComplaintCapacityFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows<ComplaintCapacityException> { action() }.code)
    }

    companion object {
        private const val GOLDEN_SHA256 = "f2ae2d3224e75f6b5ca20ab2dc6de84174d819be5a482abd6b95ec9f95ab759e"
        private val STORED_NAMES = listOf(
            "app_installations",
            "audit_rows",
            "catalog_mutations",
            "complaint_rows",
            "import_artifacts",
            "import_runs",
            "import_staging",
            "installation_ids",
            "installation_receipts",
            "journal_applied",
            "journal_control",
            "journal_publications",
            "journal_retirements",
            "legacy_records",
            "moderation_grants",
            "normal_receipts",
            "recovery_reservations",
            "resource_ids",
            "scan_entries",
            "scan_runs",
            "storage_bytes",
            "test_runs",
        )
    }
}
