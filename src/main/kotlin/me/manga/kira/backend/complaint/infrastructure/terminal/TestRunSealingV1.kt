package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One caller, one retained runtime registration, two actual released transactions. First seal only
 * the run; only then enter E/control/counters/run/audit. No provider, drain, retention or purge right
 * follows from completion. A new attempt can resume SEALED with this same registration after a
 * lost acknowledgment; an original failed attempt is never rehabilitated or given a new budget.
 */
internal class TestRunSealingV1 private constructor(internal val registration: ComplaintTestNamespaceRegistrationV1) {
    private val caller = Thread.currentThread()
    internal val coordinator = registration.process.pools.catalogCoordinator
    internal val budget = PersistenceTimeBudget.start(registration.process.catalogReadback.totalAttemptMillis, coordinator.ownership.nanoClock)
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var stage = Stage.NEW
    private var jdbc: JdbcTemplate? = null
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var cleanupUncertain = false
    private var sealedAt: Instant? = null

    init { registration.requireUsable() }

    fun seal(): TestRunSealingResultV1 {
        requireSealing(caller === Thread.currentThread())
        throwIfSignalled()
        requireSealing(!started)
        started = true // Every attempted invocation is single-use, including failed admission.
        stage = Stage.SEAL
        try {
            requireConnectionFree()
            requireRunning()
            sealedAt = coordinator.testRunSealing.execute(this).releasedSealedAt()
            requireSealing(phase == null && !cleanupUncertain)
            stage = Stage.AUDIT
            val audit = coordinator.testRunSealing.execute(this)
            requireSealing(audit.releasedSealedAt() == sealedAt && phase == null && !cleanupUncertain)
            requireConnectionFree()
            requireRunning()
            return TestRunSealingResultV1.SEALED_AND_AUDITED
        } catch (problem: Throwable) {
            observeFailure(problem)
            throwIfSignalled()
            throw TestRunSealingExceptionV1()
        } finally {
            finished = true
        }
    }

    internal val path: PersistencePhasePath get() {
        requireRunning()
        return when (stage) {
            Stage.SEAL -> PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL
            Stage.AUDIT -> PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT
            Stage.NEW -> throw TestRunSealingExceptionV1()
        }
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning()
        registration.requireSealingOwner(ownership)
        requireSealing(selected.dataSource === coordinator.dataSource && stage !== Stage.NEW)
        if (jdbc == null) jdbc = selected
        requireSealing(jdbc === selected)
    }

    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        registration.requireSealingOwner(ownership)
        requireSealing(selected === path && !phaseEntered && phase == null &&
            (stage === Stage.SEAL && sealedAt == null || stage === Stage.AUDIT && sealedAt != null))
        phaseEntered = true
    }

    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning()
        requireSealing(phaseEntered && phase == null)
        phase = selected
    }

    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testRunSealingCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) {
                cleanupUncertain = true
            } else {
                phase = null
                phaseEntered = false
            }
        } catch (problem: Throwable) {
            cleanupUncertain = true
            observeFailure(problem)
        }
    }

    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        val openings = registration.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }
            .openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single()
        val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireSealing(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        requirePersistence(ownership, selected)
    }

    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning()
        registration.requireSealingOwner(ownership)
        requireSealing(selected === path && phaseEntered && phase != null)
        registration.requireSealingGate(gate)
    }

    internal fun priorSealedAt(): Instant {
        requireRunning()
        requireSealing(stage === Stage.AUDIT)
        return checkNotNull(sealedAt)
    }

    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST run sealing interrupted.")
        requireSealing(caller === Thread.currentThread() && !finished && !cleanupUncertain)
        budget.remainingMillis(1)
        registration.requireSealingOwner(coordinator.ownership)
    }

    /** Every failure is sticky; preserve fatal/cancellation/interrupt semantics through the real cleanup. */
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST run sealing cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ->
                InterruptedException("TEST run sealing interrupted.")
            else -> TestRunSealingExceptionV1()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || (previous is CancellationException && retained !is Error) ||
                (previous is InterruptedException && retained !is Error && retained !is CancellationException) ||
                (previous != null && retained is TestRunSealingExceptionV1)) return
            if (failure.compareAndSet(previous, retained)) return
        }
    }

    internal fun throwIfSignalled() {
        failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it }
    }

    override fun toString(): String = "TestRunSealingV1(same-registration,barrier-and-audit-only,redacted)"
    private enum class Stage { NEW, SEAL, AUDIT }

    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1): TestRunSealingV1 = TestRunSealingV1(registration)
    }
}

internal enum class TestRunSealingResultV1 { SEALED_AND_AUDITED }
internal class TestRunSealingExceptionV1 : RuntimeException("TEST run sealing refused.", null, false, false)
internal fun requireSealing(allowed: Boolean) { if (!allowed) throw TestRunSealingExceptionV1() }
