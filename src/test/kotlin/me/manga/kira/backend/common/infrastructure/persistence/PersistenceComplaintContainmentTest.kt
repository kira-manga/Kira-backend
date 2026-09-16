package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Gate/accounting controls only. These inert identities never start a pool, manager, SQL or native work. */
class PersistenceComplaintContainmentTest {
    @Test
    fun `a seal after reservation defeats final admission publication without waiting or borrowing`() {
        val fixture = ComplaintGateFixture()
        fixture.reserve().use { incident ->
            incident.publish()
            OwnedCallerTestScope().use { callers ->
                val reserved = CountDownLatch(1)
                val publish = CountDownLatch(1)
                callers.beforeClose { publish.countDown() }
                val candidate = callers.launch {
                    fixture.reserve().use { selected ->
                        reserved.countDown()
                        check(publish.await(5, TimeUnit.SECONDS))
                        val failure = assertThrows<PersistencePhaseException> { selected.publish() }
                        assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, failure.code)
                    }
                }
                assertTrue(reserved.await(5, TimeUnit.SECONDS))
                assertTrue(incident.seal())
                assertFalse(incident.seal())
                fixture.assertSealed()
                publish.countDown()
                candidate.value()
                fixture.assertSealed() // The rejected candidate's unused-custody cleanup cannot clear this incident.
            }
        }
        fixture.reserve().use { it.publish() }
        requireConnectionFree()
    }

    @Test
    fun `two incident identities require two exact completions and stale clearing cannot erase a replacement`() {
        val fixture = ComplaintGateFixture()
        fixture.reserve().use { first ->
            first.publish()
            fixture.reserve().use { second ->
                second.publish()
                assertTrue(first.seal())
                assertTrue(second.seal())
                first.close()
                fixture.assertSealed()
                assertFalse(first.clear())
                assertFalse(first.seal())
                second.close()
                fixture.reserve().use { replacement ->
                    replacement.publish()
                    assertTrue(replacement.seal())
                    assertFalse(first.clear())
                    assertFalse(first.seal())
                    assertEquals(
                        PersistencePhaseFailureCode.RESOURCE_REFUSED,
                        assertThrows<PersistencePhaseException> { replacement.claim.clearAfterCleanup(first.phase) }.code,
                    )
                    fixture.assertSealed()
                }
            }
        }
        fixture.reserve().use { it.publish() }
        requireConnectionFree()
    }

    @Test
    fun `eight retained claims are a fixed bound and exact completed slots are reusable`() {
        val fixture = ComplaintGateFixture()
        val retained = mutableListOf<ComplaintGateEntry>()
        try {
            repeat(8) {
                val entry = fixture.reserve()
                retained.add(entry)
                entry.publish()
            }
            assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { fixture.reserve() }.code)
        } finally {
            retained.forEach { it.close() }
        }
        fixture.reserve().use { it.publish() }
        requireConnectionFree()
    }

    @Test
    fun `the release claim is not completion and a foreign caller cannot clear the original incident`() {
        val fixture = ComplaintGateFixture()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        fixture.reserve {
            entered.countDown()
            check(returned.await(5, TimeUnit.SECONDS))
        }.use { incident ->
            incident.publish()
            assertTrue(incident.seal())
            OwnedCallerTestScope().use { callers ->
                callers.beforeClose { returned.countDown() }
                val release = callers.launch {
                    assertEquals(
                        PersistencePhaseFailureCode.RESOURCE_REFUSED,
                        assertThrows<PersistencePhaseException> { incident.clear() }.code,
                    )
                    assertTrue(incident.permit.releaseAfterQuiescence())
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(incident.permit.releaseCompleted())
                assertFalse(incident.permit.releaseAfterQuiescence())
                assertThrows<IllegalStateException> { incident.clear() }
                fixture.assertSealed()
                returned.countDown()
                release.value()
            }
            assertTrue(incident.permit.releaseCompleted())
            assertTrue(incident.clear())
            assertFalse(incident.clear())
        }
        fixture.reserve().use { it.publish() }
        requireConnectionFree()
    }

    @Test
    fun `a failed accounting callback keeps its exact incident sealed and is never retried as success`() {
        val fixture = ComplaintGateFixture()
        val releases = AtomicInteger()
        // Deliberately unresolved synthetic accounting belongs to a short-lived caller, not the reusable JUnit worker.
        OwnedCallerTestScope().use { callers ->
            callers.launch {
                val incident = fixture.reserve {
                    releases.incrementAndGet()
                    throw ComplaintGateReleaseFailure()
                }
                incident.publish()
                assertTrue(incident.seal())
                assertThrows<ComplaintGateReleaseFailure> { incident.permit.releaseAfterQuiescence() }
                assertFalse(incident.permit.releaseCompleted())
                assertThrows<IllegalStateException> { incident.clear() }
                assertFalse(incident.permit.releaseAfterQuiescence())
                assertEquals(1, releases.get())
                assertTrue(LocalPersistencePermit.callerHasOutstandingPermit())
                fixture.assertSealed()
            }.value()
        }
        fixture.assertSealed()
        requireConnectionFree() // A different thread's empty Spring state cannot clear that root incident.
        fixture.assertSealed()
    }
}

/** Only constructs real private owner identities. No lifecycle activation or native-fault simulation. */
private class ComplaintGateFixture {
    private val endpoint = resolveEndpoint("jdbc:postgresql://127.0.0.1:5432/containment_fixture")
    private val owner = PersistenceJdbcLifecycleOwner(endpoint, 4, PersistencePathStyle.POSIX)
    private val pool = GuardedDataSource.deletion(owner, endpoint)
    private val ownership = PersistencePhaseOwnership.deletion(DeletionPersistenceAdmission(), GuardedJdbcTransactionManager(pool))

    fun reserve(release: () -> Unit = {}): ComplaintGateEntry {
        val caller = PersistenceOwnedFactoryCaller.capture()
        val phase = PersistencePhaseContext(ownership, 0, caller, PersistencePhasePath.COMPLAINT_DELETION_FENCE_PREFIX)
        val claim = pool.complaintContainment.reserve(phase, caller)
        val permit = LocalPersistencePermit(release)
        claim.bind(phase, permit)
        return ComplaintGateEntry(phase, claim, permit)
    }

    fun assertSealed() {
        assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, assertThrows<PersistencePhaseException> { reserve() }.code)
    }
}

/** The test identities have no work to finalize; this helper releases only their exact synthetic accounting. */
private class ComplaintGateEntry(val phase: PersistencePhaseContext, val claim: PersistenceComplaintContainment.Claim, val permit: LocalPersistencePermit) :
    AutoCloseable {
    private var cleared = false

    fun publish() = claim.publish(phase)

    fun seal(): Boolean = claim.seal(phase)

    fun clear(): Boolean = claim.clearAfterCleanup(phase).also { if (it) cleared = true }

    override fun close() {
        if (!cleared) {
            if (!permit.releaseCompleted()) assertTrue(permit.releaseAfterQuiescence())
            assertTrue(clear())
        }
    }
}

private class ComplaintGateReleaseFailure : RuntimeException("Synthetic accounting release failure.")
