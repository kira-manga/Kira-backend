package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllPreparation
import me.manga.kira.backend.complaint.infrastructure.VersionBoundOwnerDeleteAllConfiguration
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.historyTestRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.UUID

/** Existing exact TLS/root/three-pool fixture only; no second PG launcher or plaintext relabelling. */
internal fun withCurrentOwnerDeleteAll(
    tls: VersionBoundPersistenceConnectedFixture,
    test: (OwnerDeleteAllCurrentBindingCases) -> Unit,
) {
    tls.start()
    assertEquals(PersistenceLifecycleObservation.READY, tls.pools.deletion.prepareDeletion())
    assertEquals(PersistenceLifecycleObservation.READY, tls.pools.catalogCoordinator.prepare())
    val pids = listOf(tls.pools.ordinary, tls.pools.deletion, tls.pools.catalogCoordinator.dataSource).map(tls::tlsPid)
    assertEquals(3, pids.toSet().size)
    val acquired = BoundComplaintConsumerFixture()
    val consumers = acquired.configuration()
    val writer = consumers.journalConfiguration.declaration().writer
    val process = VersionBoundComplaintProcessConfiguration.fromRetained(
        consumers,
        tls.pools,
        1,
        7,
        UUID.fromString(writer.databaseIdentity),
        UUID.fromString(writer.restoreIdentity),
    )
    assertSame(consumers, process.consumers)
    assertSame(tls.pools, process.pools)
    assertEquals(9, acquired.lookups)
    tls.withEnrollment { base ->
        OwnerDeleteAllAuthorizationFixture(base, tls.pools.deletion, process, closePoolOnClose = false).use { auth ->
            assertSame(consumers.capacityPolicy, auth.policy)
            assertSame(consumers.journalRouting, auth.routing)
            assertSame(consumers.ingressAdmission, auth.ingress)
            assertArrayEquals(process.configurationHashBytes(), auth.desired.configurationHashBytes())
            OwnerDeleteAllCurrentBindingCases(process, auth).use(test)
        }
    }
}

/**
 * Actual computed-D consumer graph and original private outcomes. The existing continuation fixture
 * supplies raw SDK replies, paid content and snapshots only; its legacy stores are not selected.
 * Synthetic catalog/checkpoint evidence remains explicitly NOT current/LIVE/restore authority.
 */
internal class OwnerDeleteAllCurrentBindingCases(
    private val process: VersionBoundComplaintProcessConfiguration,
    private val auth: OwnerDeleteAllAuthorizationFixture,
) : AutoCloseable {
    private val candidate = auth.enrolled()
    private val connected = OwnerDeleteAllContinuationFixture(auth, candidate, paidApplyContent(auth, candidate, 1))
    private val bound = VersionBoundOwnerDeleteAllConfiguration(
        process, auth.base.ordinary.ownership, auth.ownership, auth.audit, auth.catalog, auth.dataKeys,
    )
    private val publishers = connected.publishers(store = bound.authorizationStore)
    private val http by lazy { OwnerDeleteAllHttpFixture(connected, bound.continuation(publishers)) }
    private val mapper = ObjectMapper()

    init {
        // Track the exact expected event even when rejection precedes INSERT; cleanup never scans foreign rows.
        auth.events.addIfAbsent(connected.publisher.event.route.eventId)
    }

    fun completedReplay() {
        http.assertEmpty(http.exchange(), 204)
        assertEquals("COMPLETED", connected.receiptState())
        assertEquals("APPLIED", connected.publicationState())
        val completed = auth.state()
        val proof = connected.proofSnapshot()
        for (change in ControlChange.entries) {
            withStaleControl(change) {
                val before = snapshot()
                assertProblem(http.exchange())
                assertUnchanged(before)
            }
        }
        assertEquals(completed, auth.state()) // Includes the original completion/expiry/audit/proof times.
        http.assertEmpty(http.exchange(), 204)

        val control = auth.controlRow()
        val originalProviderCheck = connected.publisher.beforePrepare
        connected.publisher.beforePrepare = { throw IOException("Synthetic ordinary provider outage.") }
        try {
            changeControl("maintenance_closed = true")
            publishers.close() // No new publication reservation is available either.
            val before = snapshot()
            val response = withoutDeletionSlots { http.exchange() }
            http.assertEmpty(response, 204)
            assertUnchanged(before)
            assertEquals(proof, connected.proofSnapshot())
        } finally {
            connected.publisher.beforePrepare = originalProviderCheck
            auth.restoreControl(control)
        }
        assertEquals(completed, auth.state())
    }

    fun staleActiveAndAuthorized() {
        rejectDifferentOrdinaryAdmission()
        assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, bound.preflights.preflight(candidate))
        for (authorized in listOf(false, true)) {
            if (authorized) {
                prepared()
                assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, bound.preflights.preflight(candidate))
            }
            for (change in ControlChange.entries) {
                withStaleControl(change) {
                    val before = snapshot()
                    assertRolledBack(PersistencePhaseFailureCode.RESOURCE_REFUSED) { bound.preflights.preflight(candidate) }
                    assertProblem(http.exchange())
                    assertUnchanged(before)
                }
            }
        }
        assertTrue(connected.publisher.requests.isEmpty() && connected.publisher.kms.requests.isEmpty())
    }

    fun lockedPhases() {
        auth.ingress.withIngress(historyTestRequest()) { context ->
            auth.ingress.startOwnerDeleteAll(context)
            val observed = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, bound.preflights.preflight(candidate))
            val admitted = auth.ingress.admitOwnerDeleteAll(context, observed)
            withStaleControl(ControlChange.GENERATION) {
                val before = snapshot()
                assertRolledBack { bound.authorization.authorize(candidate, observed, admitted) }
                assertUnchanged(before)
            }
        }
        val work = prepared()
        val authorized = assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, bound.preflights.preflight(candidate))
        withStaleControl(ControlChange.HASH) {
            val before = snapshot()
            assertRolledBack { bound.authorization.reload(candidate, authorized) }
            assertUnchanged(before)
        }

        val readback = publishers.reserve().publish(work) // Genuine original publisher result and successful owned close.
        connected.assertPublisherClosed()
        withStaleControl(ControlChange.RESTORE) {
            val before = snapshot()
            assertRolledBack { bound.verification.verify(readback) }
            assertUnchanged(before)
        }
        // VERIFY/APPLY continue existing privacy work, not new AUTH: do not add its freshness/maintenance gate.
        changeControl(
            "maintenance_closed = true, checkpoint_started_at = clock_timestamp() - interval '2 days', " +
                "checkpoint_completed_at = clock_timestamp() - interval '1 day'",
        )
        val proof = bound.verification.verify(readback)
        withStaleControl(ControlChange.HASH) {
            val before = snapshot()
            assertRolledBack { bound.application.apply(work, proof) }
            assertUnchanged(before)
        }
        val completed = bound.application.apply(work, proof)
        assertEquals(204, completed.responseStatus)
        assertEquals("COMPLETED", connected.receiptState())
        assertEquals("APPLIED", connected.publicationState())
        http.assertEmpty(http.exchange(), 204)
    }

    private fun rejectDifferentOrdinaryAdmission() {
        val before = snapshot()
        val size = actualPool(process.pools.ordinary).maximumPoolSize
        assertEquals(2, size)
        for (admission in listOf(OrdinaryPersistenceAdmission.sourceOnly(size), OrdinaryPersistenceAdmission(size + 1))) {
            // Fresh metadata-only manager; never rebind the existing manager or enter an alternate phase.
            val manager = GuardedJpaTransactionManager(auth.base.ordinary.entityManagerFactory, process.pools.ordinary)
            val ownership = PersistencePhaseOwnership(admission, manager)
            val failure = assertThrows<PersistencePhaseException> {
                VersionBoundOwnerDeleteAllConfiguration(process, ownership, auth.ownership, auth.audit, auth.catalog, auth.dataKeys)
            }
            assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, failure.code)
            assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertEquals(0, admission.activeOwners())
            assertUnchanged(before)
        }
    }

    private fun prepared(): CommittedOwnerDeleteAllWork.Prepared = auth.ingress.withIngress(historyTestRequest()) { context ->
        val result = assertInstanceOf(OwnerDeleteAllPreparation.Durable::class.java, bound.coordinator.prepare(context, candidate))
        assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, result.work)
    }

    private fun withStaleControl(change: ControlChange, test: () -> Unit) {
        val original = auth.controlRow()
        try {
            when (change) {
                ControlChange.HASH -> {
                    val changed = ByteArray(32) { 99 }
                    changeControl("desired_configuration_hash = ?, checkpoint_configuration_hash = ?", changed, changed)
                }

                ControlChange.GENERATION -> changeControl("desired_generation = desired_generation + 1")

                ControlChange.RESTORE -> {
                    val changed = UUID.randomUUID()
                    changeControl("restore_identity = ?, checkpoint_restore_identity = ?", changed, changed)
                }
            }
            test()
        } finally {
            auth.restoreControl(original)
        }
    }

    private fun changeControl(assignments: String, vararg values: Any) {
        assertEquals(
            1,
            auth.observer.update("UPDATE complaint_journal_control SET $assignments WHERE data_scope_id = ?", *values, ComplaintDataScope.LIVE.id),
        )
    }

    private fun assertProblem(response: DeleteAllHttpResponse) {
        val bytes = response.contentAsByteArray
        assertEquals(503, response.status)
        assertTrue(bytes.size in 1..16 * 1024)
        assertEquals(bytes.size.toString(), response.getHeader("Content-Length"))
        assertEquals(1, response.streams)
        assertEquals(0, response.writers)
        assertEquals("SERVICE_UNAVAILABLE", mapper.readTree(bytes)["errors"][0]["code"].textValue())
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertNull(response.getHeader("Location"))
        assertNull(response.getHeader("WWW-Authenticate"))
    }

    private fun assertRolledBack(code: PersistencePhaseFailureCode = PersistencePhaseFailureCode.WORK_FAILED, operation: () -> Unit) {
        val failure = assertThrows<PersistencePhaseException> { operation() }
        assertEquals(code, failure.code)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertReleased()
    }

    private fun snapshot(): Snapshot {
        requireConnectionFree()
        val semantics = poolTestField<Any>(auth.ingress, "semantics")
        val registry = poolTestField<Any>(auth.ingress, "mutationMembers")
        return Snapshot(
            auth.state(),
            poolTestField(semantics, "events"),
            poolTestField<Map<Any, Long>>(registry, "members").toMap(),
            listOf(
                connected.publisher.requests.size,
                connected.publisher.kms.requests.size,
                connected.publisher.s3ClientsCreated,
                connected.publisher.s3ClientsClosed,
                connected.publisher.kms.createdClients,
                connected.publisher.kms.closedClients,
            ),
        )
    }

    private fun assertUnchanged(before: Snapshot) {
        assertEquals(before, snapshot())
        assertReleased()
    }

    private fun <T> withoutDeletionSlots(operation: () -> T): T = OwnedCallerTestScope().use { callers ->
        val held = callers.gate()
        val owner = callers.launch {
            val permits = List(4) { checkNotNull(auth.admission.tryPrivacyDeletion()) }
            try {
                held.hold()
            } finally {
                permits.forEach { assertTrue(it.releaseAfterQuiescence()) }
            }
            true
        }
        held.awaitEntered()
        try {
            assertEquals(4, auth.admission.activeOwners().totalOwners)
            operation()
        } finally {
            held.release()
            assertTrue(owner.value())
        }
    }

    private fun assertReleased() {
        connected.assertReleased()
        assertEquals(0L, connected.journalLanes.activeOwners().totalOwners)
        for (source in listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource)) {
            assertEquals(0, actualPool(source).hikariPoolMXBean.activeConnections)
        }
        requireConnectionFree()
    }

    override fun close() {
        val publicationClose = runCatching(publishers::close)
        val lanesClose = runCatching(connected.journalLanes::close)
        publicationClose.getOrThrow()
        lanesClose.getOrThrow()
        assertReleased()
    }

    private enum class ControlChange { HASH, GENERATION, RESTORE }
    private data class Snapshot(
        val rows: Map<String, List<String>>,
        val semanticEvents: Int,
        val members: Map<Any, Long>,
        val providerCounts: List<Int>,
    )
}
