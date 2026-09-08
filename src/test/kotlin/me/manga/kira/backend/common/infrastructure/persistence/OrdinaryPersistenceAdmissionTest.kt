package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class OrdinaryPersistenceAdmissionTest {
    @ParameterizedTest
    @CsvSource("1,1", "2,1", "3,2", "4,3", "5,4", "10,4", "2147483647,4")
    fun `scoped owner limit preserves one ordinary slot except source only single slot compatibility`(poolSize: Int, expectedLimit: Int) {
        val admission = OrdinaryPersistenceAdmission(poolSize)
        assertEquals(expectedLimit, admission.ownerLimit)
        val permits = List(expectedLimit) { requireNotNull(admission.trySourceBoundary()) }
        try {
            assertEquals(expectedLimit, admission.activeOwners())
            assertNull(admission.trySourceBoundary())
            assertNull(admission.tryComplaintBoundary())
        } finally {
            permits.forEach { assertTrue(it.releaseAfterQuiescence()) }
        }
        assertEquals(0, admission.activeOwners())
    }

    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, -1, 0])
    fun `invalid configured pool capacity is rejected without creating an owner budget`(poolSize: Int) {
        val failure = assertThrows(PersistenceBoundaryException::class.java) { OrdinaryPersistenceAdmission(poolSize) }
        assertEquals(PersistenceBoundaryFailureCode.INVALID_POOL_CAPACITY, failure.code)
        assertEquals("Persistence boundary rejected: INVALID_POOL_CAPACITY.", failure.message)
    }

    @Test
    fun `a one connection ordinary pool permits source boundaries but never complaints`() {
        val admission = OrdinaryPersistenceAdmission(1)
        assertNull(admission.tryComplaintBoundary())
        assertEquals(0, admission.activeOwners())
        val source = requireNotNull(admission.trySourceBoundary())
        assertNull(admission.tryComplaintBoundary())
        assertEquals(1, admission.activeOwners())
        assertTrue(source.releaseAfterQuiescence())
        assertNull(admission.tryComplaintBoundary())
        assertEquals(0, admission.activeOwners())
    }

    @Test
    fun `source and complaint boundaries spend the same budget not separate copies`() {
        val admission = OrdinaryPersistenceAdmission(3)
        val source = requireNotNull(admission.trySourceBoundary())
        val complaint = requireNotNull(admission.tryComplaintBoundary())
        assertEquals(2, admission.activeOwners())
        assertNull(admission.trySourceBoundary())
        assertNull(admission.tryComplaintBoundary())
        assertTrue(source.releaseAfterQuiescence())
        assertEquals(1, admission.activeOwners())
        val next = requireNotNull(admission.tryComplaintBoundary())
        assertNull(admission.trySourceBoundary())
        assertTrue(complaint.releaseAfterQuiescence())
        assertTrue(next.releaseAfterQuiescence())
        assertEquals(0, admission.activeOwners())
    }

    @Test
    fun `separate controllers never share counters or release each others capacity`() {
        val first = OrdinaryPersistenceAdmission(2)
        val second = OrdinaryPersistenceAdmission(2)
        val a = requireNotNull(first.tryComplaintBoundary())
        val b = requireNotNull(second.trySourceBoundary())
        assertTrue(a.releaseAfterQuiescence())
        assertEquals(0, first.activeOwners())
        assertEquals(1, second.activeOwners())
        assertNull(second.trySourceBoundary())
        assertTrue(b.releaseAfterQuiescence())
        assertEquals(0, second.activeOwners())
    }

    @Test
    fun `simultaneous admissions never exceed available owners and do not wait for release`() {
        val admission = OrdinaryPersistenceAdmission(5)
        val permits = racePersistenceAdmissions { admission.tryComplaintBoundary() }.filterNotNull()
        try {
            assertTrue(permits.size in 1..4, "One-CAS contention may refuse work but cannot exceed capacity.")
            assertEquals(permits.size, admission.activeOwners())
        } finally {
            permits.forEach { it.releaseAfterQuiescence() }
        }
        assertEquals(0, admission.activeOwners())
    }

    @Test
    fun `full budget refuses every concurrent caller before any held owner is released`() {
        val admission = OrdinaryPersistenceAdmission(2)
        val held = requireNotNull(admission.tryComplaintBoundary())
        try {
            assertTrue(racePersistenceAdmissions { admission.trySourceBoundary() }.all { it == null })
            assertEquals(1, admission.activeOwners())
        } finally {
            held.releaseAfterQuiescence()
        }
        assertEquals(0, admission.activeOwners())
    }

    @Test
    fun `simultaneous duplicate release decrements once and stale completion cannot release a new owner`() {
        val admission = OrdinaryPersistenceAdmission(3)
        val old = requireNotNull(admission.tryComplaintBoundary())
        val retained = requireNotNull(admission.trySourceBoundary())
        val releases = racePersistenceAdmissions { old.releaseAfterQuiescence() }
        assertEquals(1, releases.count { it })
        assertEquals(1, admission.activeOwners())
        val replacement = requireNotNull(admission.tryComplaintBoundary())
        assertFalse(old.releaseAfterQuiescence())
        assertEquals(2, admission.activeOwners())
        assertTrue(retained.releaseAfterQuiescence())
        assertTrue(replacement.releaseAfterQuiescence())
        assertEquals(0, admission.activeOwners())
    }

    @Test
    fun `permit has no lexical autoclose contract and diagnostics expose no callback`() {
        val admission = OrdinaryPersistenceAdmission(2)
        val permit = requireNotNull(admission.trySourceBoundary())
        assertFalse(AutoCloseable::class.java.isInstance(permit))
        assertEquals("LocalPersistencePermit(redacted)", permit.toString())
        assertEquals("OrdinaryPersistenceAdmission(redacted)", admission.toString())
        assertNotNull(permit)
        assertTrue(permit.releaseAfterQuiescence())
    }
}
