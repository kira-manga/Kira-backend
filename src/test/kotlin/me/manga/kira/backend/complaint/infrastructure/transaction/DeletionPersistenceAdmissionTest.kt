package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.racePersistenceAdmissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** These are local admission owners, not actual JDBC connections or verified cleanup receipts. */
class DeletionPersistenceAdmissionTest {
    @Test
    fun `routine work stops at three leaving a fourth slot for privacy`() {
        val admission = DeletionPersistenceAdmission()
        val routine = List(3) { requireNotNull(admission.tryRoutineDeletion()) }
        assertEquals(DeletionAdmissionSnapshot(3, 0), admission.activeOwners())
        assertNull(admission.tryRoutineDeletion())
        val privacy = requireNotNull(admission.tryPrivacyDeletion())
        assertEquals(DeletionAdmissionSnapshot(3, 1), admission.activeOwners())
        assertEquals(4, admission.activeOwners().totalOwners)
        assertNull(admission.tryPrivacyDeletion())
        assertNull(admission.tryRoutineDeletion())
        routine.forEach { assertTrue(it.releaseAfterQuiescence()) }
        assertEquals(DeletionAdmissionSnapshot(0, 1), admission.activeOwners())
        assertTrue(privacy.releaseAfterQuiescence())
        assertEquals(DeletionAdmissionSnapshot(0, 0), admission.activeOwners())
    }

    @Test
    fun `privacy can use all four slots and a fifth caller cannot wait inside admission`() {
        val admission = DeletionPersistenceAdmission()
        val held = List(4) { requireNotNull(admission.tryPrivacyDeletion()) }
        try {
            assertEquals(DeletionAdmissionSnapshot(0, 4), admission.activeOwners())
            assertTrue(racePersistenceAdmissions { admission.tryPrivacyDeletion() }.all { it == null })
            assertTrue(racePersistenceAdmissions { admission.tryRoutineDeletion() }.all { it == null })
            assertEquals(4, admission.activeOwners().totalOwners)
        } finally {
            held.forEach { it.releaseAfterQuiescence() }
        }
        assertEquals(0, admission.activeOwners().totalOwners)
    }

    @Test
    fun `new routine work yields while any privacy owner remains including its cleanup period`() {
        val admission = DeletionPersistenceAdmission()
        val first = requireNotNull(admission.tryPrivacyDeletion())
        val second = requireNotNull(admission.tryPrivacyDeletion())
        assertTrue(first.releaseAfterQuiescence())
        assertEquals(DeletionAdmissionSnapshot(0, 1), admission.activeOwners())
        try {
            assertTrue(racePersistenceAdmissions { admission.tryRoutineDeletion() }.all { it == null })
            assertEquals(DeletionAdmissionSnapshot(0, 1), admission.activeOwners())
        } finally {
            second.releaseAfterQuiescence()
        }
        val nextRoutine = requireNotNull(admission.tryRoutineDeletion())
        assertEquals(DeletionAdmissionSnapshot(1, 0), admission.activeOwners())
        assertTrue(nextRoutine.releaseAfterQuiescence())
    }

    @Test
    fun `already admitted routine owners can finish without losing a still held privacy reservation`() {
        val admission = DeletionPersistenceAdmission()
        val routine = List(3) { requireNotNull(admission.tryRoutineDeletion()) }
        val privacy = requireNotNull(admission.tryPrivacyDeletion())
        assertTrue(routine[1].releaseAfterQuiescence())
        assertEquals(DeletionAdmissionSnapshot(2, 1), admission.activeOwners())
        assertNull(admission.tryRoutineDeletion())
        val secondPrivacy = requireNotNull(admission.tryPrivacyDeletion())
        assertEquals(DeletionAdmissionSnapshot(2, 2), admission.activeOwners())
        assertTrue(routine[0].releaseAfterQuiescence())
        assertTrue(routine[2].releaseAfterQuiescence())
        assertTrue(privacy.releaseAfterQuiescence())
        assertTrue(secondPrivacy.releaseAfterQuiescence())
        assertEquals(DeletionAdmissionSnapshot(0, 0), admission.activeOwners())
    }

    @Test
    fun `simultaneous privacy admissions retain no more than four owners until completion`() {
        val admission = DeletionPersistenceAdmission()
        val permits = racePersistenceAdmissions { admission.tryPrivacyDeletion() }.filterNotNull()
        try {
            assertTrue(permits.size in 1..4)
            assertEquals(DeletionAdmissionSnapshot(0, permits.size), admission.activeOwners())
        } finally {
            permits.forEach { it.releaseAfterQuiescence() }
        }
        assertEquals(DeletionAdmissionSnapshot(0, 0), admission.activeOwners())
    }

    @Test
    fun `simultaneous routine admissions retain no more than three and do not consume the privacy slot`() {
        val admission = DeletionPersistenceAdmission()
        val permits = racePersistenceAdmissions { admission.tryRoutineDeletion() }.filterNotNull()
        try {
            assertTrue(permits.size in 1..3)
            assertEquals(DeletionAdmissionSnapshot(permits.size, 0), admission.activeOwners())
            val privacy = requireNotNull(admission.tryPrivacyDeletion())
            assertEquals(DeletionAdmissionSnapshot(permits.size, 1), admission.activeOwners())
            assertTrue(privacy.releaseAfterQuiescence())
        } finally {
            permits.forEach { it.releaseAfterQuiescence() }
        }
        assertEquals(DeletionAdmissionSnapshot(0, 0), admission.activeOwners())
    }

    @Test
    fun `duplicate concurrent privacy release cannot decrement a subsequently admitted routine owner`() {
        val admission = DeletionPersistenceAdmission()
        val old = requireNotNull(admission.tryPrivacyDeletion())
        assertEquals(1, racePersistenceAdmissions { old.releaseAfterQuiescence() }.count { it })
        assertEquals(DeletionAdmissionSnapshot(0, 0), admission.activeOwners())
        val next = requireNotNull(admission.tryRoutineDeletion())
        assertFalse(old.releaseAfterQuiescence())
        assertEquals(DeletionAdmissionSnapshot(1, 0), admission.activeOwners())
        assertTrue(next.releaseAfterQuiescence())
        assertEquals(DeletionAdmissionSnapshot(0, 0), admission.activeOwners())
    }

    @Test
    fun `separate controllers do not share capacity or privacy preference state`() {
        val first = DeletionPersistenceAdmission()
        val second = DeletionPersistenceAdmission()
        val privacy = requireNotNull(first.tryPrivacyDeletion())
        val routine = requireNotNull(second.tryRoutineDeletion())
        assertNull(first.tryRoutineDeletion())
        assertTrue(privacy.releaseAfterQuiescence())
        assertEquals(DeletionAdmissionSnapshot(0, 0), first.activeOwners())
        assertEquals(DeletionAdmissionSnapshot(1, 0), second.activeOwners())
        assertTrue(routine.releaseAfterQuiescence())
        assertEquals("DeletionPersistenceAdmission(redacted)", first.toString())
    }
}
