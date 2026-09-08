package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ComplaintCapacityCounterTest {
    @Test
    fun `version one encodes exactly the independent persisted names and positions`() {
        assertEquals(1, ComplaintCapacityEncoding.VERSION)
        assertEquals(22, ComplaintCapacityEncoding.WIDTH)
        assertEquals(22, ComplaintCapacityCounter.entries.size)
        val actual = ComplaintCapacityEncoding.vectorOrder()
        assertEquals(expectedNames, actual.map { it.storedName })
        assertEquals((1..22).toList(), actual.map { it.storedOrdinal })
        expectedNames.forEachIndexed { index, name ->
            val decoded = ComplaintCapacityEncoding.counter(1, index + 1, name)
            assertEquals(index + 1, decoded.storedOrdinal)
            assertEquals(name, decoded.storedName)
        }
    }

    @Test
    fun `name order is explicit and returned order lists cannot mutate the catalogue`() {
        assertEquals(expectedNames.sorted(), ComplaintCapacityEncoding.lockOrder().map { it.storedName })
        for (order in listOf(ComplaintCapacityEncoding.vectorOrder(), ComplaintCapacityEncoding.lockOrder())) {
            if (order is MutableList<ComplaintCapacityCounter>) {
                val rejection = runCatching { order[0] = order.last() }.exceptionOrNull()
                if (rejection != null) assertInstanceOf(UnsupportedOperationException::class.java, rejection)
            }
        }
        assertEquals(expectedNames, ComplaintCapacityEncoding.vectorOrder().map { it.storedName })
        assertEquals(expectedNames.sorted(), ComplaintCapacityEncoding.lockOrder().map { it.storedName })
    }

    @Test
    fun `each ordinal is bound to its exact name rather than either field alone`() {
        expectedNames.forEachIndexed { index, name ->
            for (alias in listOf(name.uppercase(), " $name", "$name ", "$name\n", "unknown")) {
                assertFailure(ComplaintCapacityFailureCode.INVALID_COUNTER_ENCODING) {
                    ComplaintCapacityEncoding.counter(1, index + 1, alias)
                }
            }
            assertFailure(ComplaintCapacityFailureCode.INVALID_COUNTER_ENCODING) {
                ComplaintCapacityEncoding.counter(1, ((index + 1) % 22) + 1, name)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, -1, 0, 23, Int.MAX_VALUE])
    fun `out of range ordinals cannot decode even with a valid name`(ordinal: Int) {
        assertFailure(ComplaintCapacityFailureCode.INVALID_COUNTER_ENCODING) {
            ComplaintCapacityEncoding.counter(1, ordinal, "app_installations")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE])
    fun `version is checked before interpreting any names or ordinals`(version: Int) {
        assertFailure(ComplaintCapacityFailureCode.UNSUPPORTED_VERSION) { ComplaintCapacityEncoding.requireVersion(version) }
        assertFailure(ComplaintCapacityFailureCode.UNSUPPORTED_VERSION) {
            ComplaintCapacityEncoding.counter(version, 1, "app_installations")
        }
        assertFailure(ComplaintCapacityFailureCode.UNSUPPORTED_VERSION) {
            ComplaintCapacityEncoding.counter(version, 0, "rejected-input-marker")
        }
    }

    @Test
    fun `closed failure diagnostics contain only categories not rejected inputs`() {
        val error = assertThrows(ComplaintCapacityException::class.java) {
            ComplaintCapacityEncoding.counter(1, Int.MAX_VALUE, "rejected-input-marker")
        }
        assertEquals("Complaint capacity rejected: INVALID_COUNTER_ENCODING.", error.message)
        assertFalse(error.toString().contains("rejected-input-marker"))
        assertFalse(error.toString().contains(Int.MAX_VALUE.toString()))
        assertNull(error.cause)
    }

    private fun assertFailure(code: ComplaintCapacityFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintCapacityException::class.java, action).code)
    }

    private val expectedNames = listOf(
        "app_installations", "audit_rows", "catalog_mutations", "complaint_rows", "import_artifacts",
        "import_runs", "import_staging", "installation_ids", "installation_receipts", "journal_applied",
        "journal_control", "journal_publications", "journal_retirements", "legacy_records", "moderation_grants",
        "normal_receipts", "recovery_reservations", "resource_ids", "scan_entries", "scan_runs", "storage_bytes", "test_runs",
    )
}
