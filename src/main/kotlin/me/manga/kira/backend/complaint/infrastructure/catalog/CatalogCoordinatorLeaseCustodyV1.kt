package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Original-coordinator local campaign custody, distinct from its short JDBC permit and remote-refresh slot. */
internal class CatalogCoordinatorLeaseCustodyV1(private val coordinator: CatalogCoordinatorPersistence) {
    private val active = AtomicReference<CatalogCoordinatorLeaseCampaignV1?>()
    private val inFlight = AtomicReference<Attempt?>()

    internal fun acquire(binding: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate): Attempt {
        binding.requireOrdinaryPurpose()
        return acquire(binding, jdbc, null)
    }

    internal fun acquirePreparedRecovery(
        original: CatalogSignerRotationPreparedRecoveryV1,
        binding: CatalogCoordinatorLeaseBindingV1,
        jdbc: JdbcTemplate,
    ): Attempt {
        original.requireLeaseSelection(coordinator.ownership, jdbc, binding)
        return acquire(binding, jdbc, original)
    }

    internal fun acquireInitialAuthor(original: CatalogSignerRotationInitialAuthorV1, binding: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate): Attempt {
        original.requireLeaseSelection(coordinator.ownership, jdbc, binding)
        return acquire(binding, jdbc, null, original)
    }

    internal fun acquireDelivery(original: CatalogSignerRotationDeliveryV1, binding: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate): Attempt {
        original.requireLeaseSelection(coordinator.ownership, jdbc, binding)
        return acquire(binding, jdbc, null, delivery = original)
    }

    internal fun acquireActivation(original: CatalogSignerRotationActivationV1, binding: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate): Attempt {
        original.requireLeaseSelection(coordinator.ownership, jdbc, binding)
        return acquire(binding, jdbc, null, activation = original)
    }

    private fun acquire(
        binding: CatalogCoordinatorLeaseBindingV1,
        jdbc: JdbcTemplate,
        original: CatalogSignerRotationPreparedRecoveryV1?,
        initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
        delivery: CatalogSignerRotationDeliveryV1? = null,
        activation: CatalogSignerRotationActivationV1? = null,
    ): Attempt {
        requireConnectionFree()
        requireBinding(binding, jdbc)
        active.get()?.retireIfExpired()
        if (active.get() != null) refuse(PersistencePhaseFailureCode.ENTRY_REFUSED)
        return reserve(
            Attempt(binding, jdbc, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE, null, null, original, initialAuthor, delivery, activation),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun renew(campaign: CatalogCoordinatorLeaseCampaignV1, jdbc: JdbcTemplate): Attempt {
        try {
            campaign.binding.requireOrdinaryPurpose()
            requireConnectionFree()
            requireCampaign(campaign, jdbc)
            val window = campaign.requireLocalWindow()
            return reserve(Attempt(campaign.binding, jdbc, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW, campaign, window))
        } catch (problem: Throwable) {
            campaign.close() // Includes pre-reservation configuration/entry/foreign-resource failures; restoration cannot revive it.
            throw problem
        }
    }

    internal fun relinquish(campaign: CatalogCoordinatorLeaseCampaignV1, jdbc: JdbcTemplate): Attempt {
        campaign.close() // Stop new work/renewal before any entry/resource check, including a foreign-resource refusal.
        campaign.binding.requireOrdinaryPurpose()
        requireConnectionFree()
        requireCampaign(campaign, jdbc)
        campaign.claimRelinquishment()
        return reserve(Attempt(campaign.binding, jdbc, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH, campaign, null))
    }

    private fun requireBinding(binding: CatalogCoordinatorLeaseBindingV1, jdbc: JdbcTemplate) {
        if (coordinator.leaseCustody !== this || binding.coordinator !== coordinator) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        binding.requirePersistence(coordinator.ownership, jdbc)
    }

    private fun requireCampaign(campaign: CatalogCoordinatorLeaseCampaignV1, jdbc: JdbcTemplate) {
        if (campaign.custody !== this || campaign.jdbc !== jdbc) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        requireBinding(campaign.binding, jdbc)
    }

    private fun reserve(attempt: Attempt): Attempt {
        if (!inFlight.compareAndSet(null, attempt)) {
            attempt.abort()
            refuse(PersistencePhaseFailureCode.ENTRY_REFUSED)
        }
        return attempt
    }

    internal fun isActive(campaign: CatalogCoordinatorLeaseCampaignV1): Boolean = active.get() === campaign

    internal fun stop(campaign: CatalogCoordinatorLeaseCampaignV1) {
        active.compareAndSet(campaign, null) // An old close cannot erase a successor.
    }

    /** A constructor alone grants nothing: the original singleton must retain this exact attempt before any SQL. */
    internal inner class Attempt internal constructor(
        internal val binding: CatalogCoordinatorLeaseBindingV1,
        internal val jdbc: JdbcTemplate,
        internal val path: PersistencePhasePath,
        private val prior: CatalogCoordinatorLeaseCampaignV1?,
        private val priorWindow: CatalogCoordinatorLeaseCampaignV1.Window?,
        private val recovery: CatalogSignerRotationPreparedRecoveryV1? = null,
        private val initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
        private val delivery: CatalogSignerRotationDeliveryV1? = null,
        private val activation: CatalogSignerRotationActivationV1? = null,
    ) {
        internal val custody: CatalogCoordinatorLeaseCustodyV1 get() = this@CatalogCoordinatorLeaseCustodyV1
        private val caller = Thread.currentThread()
        internal val clock = coordinator.ownership.nanoClock
        internal val startedAtNanos = clock.nanoTime() // Before phase entry, UUID generation or dispatch, never reset on return.
        internal val owner: UUID = prior?.owner ?: UUID.randomUUID()
        internal val token: Long? = prior?.token

        // Only this concrete delivery owner's actual pending2 snapshot selects the separate ten-argument SQL leaf.
        // The binding remains genuinely B2; ordinary acquisitions, renewals and relinquishments keep their existing SQL.
        private val deliveryPendingOperation = delivery?.pendingLeaseOperation(binding)
        private val activationPendingOperation = activation?.pendingLeaseOperation(binding)
        private val arguments = binding.arguments().let { values ->
            (deliveryPendingOperation ?: activationPendingOperation)?.let { arrayOf(*values, it) } ?: values
        }
        private var retained: CatalogCoordinatorLeaseOperation? = null
        private var acquired: CatalogCoordinatorLeaseCampaignV1? = null
        private var failed = false
        private var successful = false
        private var ended = false

        internal fun requireRunning(selected: JdbcTemplate) {
            if (caller !== Thread.currentThread() || selected !== jdbc || !hasRunningCustody()) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireBinding(binding, selected)
            recovery?.requireLeaseAttempt(binding)
            initialAuthor?.requireLeaseAttempt(binding)
            delivery?.requireLeaseAttempt(binding)
            activation?.requireLeaseAttempt(binding)
            if (Thread.currentThread().isInterrupted) refuse(PersistencePhaseFailureCode.INTERRUPTED)
            if (path !== PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH) {
                requireLeaseWindow(clock.nanoTime() - startedAtNanos)
                prior?.requireSameWindow(checkNotNull(priorWindow))
            }
        }

        private fun hasRunningCustody(): Boolean = !ended && !failed && inFlight.get() === this

        internal fun retain(operation: CatalogCoordinatorLeaseOperation, selected: JdbcTemplate) {
            requireRunning(selected)
            if (retained != null || !operation.belongsTo(this)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            retained = operation
        }

        internal fun requireOperation(operation: CatalogCoordinatorLeaseOperation, selected: JdbcTemplate) {
            requireRunning(selected)
            if (retained !== operation) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        }

        internal fun sqlArguments(operation: CatalogCoordinatorLeaseOperation, selected: JdbcTemplate): Array<Any?> {
            requireOperation(operation, selected)
            return arguments
        }

        internal fun usesPendingDeliverySql(operation: CatalogCoordinatorLeaseOperation, selected: JdbcTemplate): Boolean {
            requireOperation(operation, selected)
            if (deliveryPendingOperation == null) return false
            check(path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE)
            check(checkNotNull(delivery).pendingLeaseOperation(binding) == deliveryPendingOperation)
            return true
        }

        internal fun usesPendingActivationSql(operation: CatalogCoordinatorLeaseOperation, selected: JdbcTemplate): Boolean {
            requireOperation(operation, selected)
            if (activationPendingOperation == null) return false
            check(path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE)
            check(checkNotNull(activation).pendingLeaseOperation(binding) == activationPendingOperation)
            return true
        }

        /** Under the actual row lock, before the later-clock CAS; the floor is never a caller-supplied long. */
        internal fun requireHistoricalTokenFloor(operation: CatalogCoordinatorLeaseOperation, selected: JdbcTemplate, lockedToken: Long, lockedOwner: UUID?) {
            requireOperation(operation, selected)
            // Also exclude the latest real owner when an earlier recovery left no outcome artifact.
            if (delivery != null || activation != null) check(owner != lockedOwner)
            recovery?.requireHistoricalLeaseFloor(binding, lockedToken)
            delivery?.requireHistoricalLeaseFloor(binding, lockedToken)
            activation?.requireHistoricalLeaseFloor(binding, lockedToken)
            delivery?.requireClosedLeaseControl(binding, selected)
            activation?.requireClosedLeaseControl(binding, selected)
        }

        /** Delivery's additional fixed closed-gate predicate, without changing ordinary acquire/renew SQL. */
        internal fun requireDeliveryControl(operation: CatalogCoordinatorLeaseOperation, selected: JdbcTemplate) {
            requireOperation(operation, selected)
            delivery?.requireClosedLeaseControl(binding, selected)
            activation?.requireClosedLeaseControl(binding, selected)
        }

        /** A historical receipt can be reread after success, but a failed/late original return can never recover one. */
        internal fun requireReleasedResult(operation: CatalogCoordinatorLeaseOperation) {
            if (caller !== Thread.currentThread() || retained !== operation || resultReturnRefused()) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            requireBinding(binding, jdbc)
            if (!successful) requireRunning(jdbc)
        }

        private fun resultReturnRefused(): Boolean = failed || (ended && !successful)

        internal fun acceptAcquire(operation: CatalogCoordinatorLeaseOperation): CatalogCoordinatorLeaseCampaignV1 {
            requireOperation(operation, jdbc)
            if (successful || path !== PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            val campaign = CatalogCoordinatorLeaseCampaignV1.issuedBy(operation)
            acquired = campaign
            if (!active.compareAndSet(null, campaign)) refuse(PersistencePhaseFailureCode.ENTRY_REFUSED)
            successful = true
            return campaign
        }

        internal fun acceptRenewal(operation: CatalogCoordinatorLeaseOperation) {
            requireOperation(operation, jdbc)
            if (successful || path !== PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            checkNotNull(prior).renewWindow(operation)
            successful = true
        }

        internal fun acceptRelinquishment(operation: CatalogCoordinatorLeaseOperation) {
            requireOperation(operation, jdbc)
            if (successful || path !== PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            operation.requireReleasedTransition()
            successful = true // Historical reduction only; the prior campaign stays permanently stopped.
        }

        internal fun renewalWindow(
            operation: CatalogCoordinatorLeaseOperation,
            campaign: CatalogCoordinatorLeaseCampaignV1,
        ): Pair<CatalogCoordinatorLeaseCampaignV1.Window, Long> {
            requireOperation(operation, jdbc)
            if (path !== PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW || prior !== campaign || successful) {
                refuse(PersistencePhaseFailureCode.WORK_FAILED)
            }
            return checkNotNull(priorWindow) to startedAtNanos
        }

        internal fun abort() {
            failed = true
            prior?.close()
            acquired?.close()
        }

        internal fun failure(problem: Throwable): PersistencePhaseException {
            recovery?.observeFailure(problem)
            initialAuthor?.observeFailure(problem)
            delivery?.observeFailure(problem)
            activation?.observeFailure(problem)
            abort()
            return retained?.returnFailure(problem)
                ?: (problem as? PersistencePhaseException ?: PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        }

        /** Only local attempt bookkeeping; the original PersistencePhaseContext still owns all JDBC/quarantine cleanup. */
        @Suppress("TooGenericExceptionCaught")
        internal fun finish() {
            try {
                if (!successful || failed) {
                    abort()
                } else if (path !== PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH) {
                    (acquired ?: checkNotNull(prior)).requireLocalWindow() // Consume completion/response delay too.
                }
            } catch (problem: Throwable) {
                recovery?.observeFailure(problem)
                initialAuthor?.observeFailure(problem)
                delivery?.observeFailure(problem)
                activation?.observeFailure(problem)
                abort()
                throw problem
            } finally {
                ended = true
                inFlight.compareAndSet(this, null)
            }
        }

        override fun toString(): String = "CatalogCoordinatorLeaseAttemptV1(retained,redacted,no-work-authority)"
    }

    override fun toString(): String = "CatalogCoordinatorLeaseCustodyV1(original-coordinator,no-heartbeat-or-work-authority)"
}

/**
 * Private local continuation for the three lease operations only. No data-producing operation accepts
 * it. Thirty seconds run from dispatch, not return. close() stops locally and performs NO SQL.
 */
internal class CatalogCoordinatorLeaseCampaignV1 private constructor(attempt: CatalogCoordinatorLeaseCustodyV1.Attempt, internal val token: Long) :
    AutoCloseable {
    internal val custody = attempt.custody
    internal val binding = attempt.binding
    internal val jdbc = attempt.jdbc
    internal val owner = attempt.owner
    private val clock = attempt.clock
    private val window = AtomicReference<Window?>(Window(attempt.startedAtNanos))
    private val relinquishmentIssued = AtomicBoolean()
    private val cutoffResolutionIssued = AtomicBoolean()

    internal fun requireLocalWindow(): Window {
        val current = window.get() ?: refuse(PersistencePhaseFailureCode.WORK_FAILED)
        requireSameWindow(current)
        return current
    }

    /**
     * Rotation observes continuity of this exact campaign, not a frozen renewal Window identity.
     * Every retry consumes the same original J; this cannot extend a rotation or resurrect a stop.
     * Renewal itself still uses its unchanged expected-window/CAS protocol below.
     */
    internal fun requireRotationContinuity(originalBudget: PersistenceTimeBudget) {
        requireContinuity(originalBudget, COORDINATOR_LEASE_NANOS)
    }

    /** Resolution stops if real locked renewal did not recur within ten seconds; no local clock reset. */
    internal fun requireSealContinuity(originalBudget: PersistenceTimeBudget) {
        requireContinuity(originalBudget, 10_000_000_000L)
    }

    /** Floor the ACTUAL last renewal-dispatch window; provider slices never manufacture a new cadence allowance. */
    @Suppress("TooGenericExceptionCaught")
    internal fun remainingSealContinuityMillis(originalBudget: PersistenceTimeBudget, ceilingMillis: Int): Int {
        try {
            val remaining = requireContinuity(originalBudget, 10_000_000_000L) / 1_000_000L
            if (remaining <= 0) refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
            return minOf(remaining, originalBudget.remainingMillis(ceilingMillis.toLong())).toInt()
        } catch (problem: Throwable) {
            binding.observeRecoveryFailure(problem)
            close()
            throw boundedEpochRotationFailure(problem)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun requireContinuity(originalBudget: PersistenceTimeBudget, maximumElapsedNanos: Long): Long {
        try {
            while (true) {
                originalBudget.remainingMillis(10_000)
                val observed = window.get() ?: refuse(PersistencePhaseFailureCode.WORK_FAILED)
                if (!custody.isActive(this)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                if (Thread.currentThread().isInterrupted) refuse(PersistencePhaseFailureCode.INTERRUPTED)
                val elapsed = clock.nanoTime() - observed.startedAtNanos
                originalBudget.remainingMillis(10_000) // Includes potentially delayed caller/clock readbacks.
                if (window.get() !== observed) continue // A genuine renewal may have won while time was sampled.
                if (elapsed !in 0 until maximumElapsedNanos) {
                    if (!window.compareAndSet(observed, null)) continue // Never close a renewal that already replaced this observation.
                    custody.stop(this)
                    refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
                }
                // A healthy same-campaign replacement is allowed; an irreversible close/inactive campaign is not.
                if (!custody.isActive(this) || window.get() == null) refuse(PersistencePhaseFailureCode.WORK_FAILED)
                return maximumElapsedNanos - elapsed
            }
        } catch (problem: Throwable) {
            binding.observeRecoveryFailure(problem)
            close()
            throw boundedEpochRotationFailure(problem)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun requireSameWindow(expected: Window) {
        try {
            if (window.get() !== expected || !custody.isActive(this)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            if (Thread.currentThread().isInterrupted) refuse(PersistencePhaseFailureCode.INTERRUPTED)
            requireLeaseWindow(clock.nanoTime() - expected.startedAtNanos)
        } catch (problem: Throwable) {
            close()
            throw problem
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun renewWindow(operation: CatalogCoordinatorLeaseOperation) {
        try {
            val (expected, startedAtNanos) = operation.requireReleasedRenewal(this)
            requireSameWindow(expected)
            requireLeaseWindow(clock.nanoTime() - startedAtNanos)
            if (!window.compareAndSet(expected, Window(startedAtNanos))) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        } catch (problem: Throwable) {
            close()
            throw problem
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun retireIfExpired() {
        try {
            val current = window.get() ?: return
            val elapsed = clock.nanoTime() - current.startedAtNanos
            if (elapsed !in 0 until COORDINATOR_LEASE_NANOS) close()
        } catch (problem: Throwable) {
            close()
            throw problem
        }
    }

    /** One resolution per genuine campaign; a failed or completed call cannot restart its J budget. */
    internal fun claimCutoffResolution() {
        binding.requireOrdinaryPurpose()
        requireLocalWindow()
        if (!cutoffResolutionIssued.compareAndSet(false, true)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
    }

    internal fun claimRelinquishment() {
        if (!relinquishmentIssued.compareAndSet(false, true)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun close() {
        window.set(null) // No code path assigns a new window after this irreversible stop.
        custody.stop(this)
    }

    override fun toString(): String = "CatalogCoordinatorLeaseCampaignV1(local-only,no-heartbeat-or-data-commit-authority)"

    internal class Window internal constructor(internal val startedAtNanos: Long)

    companion object {
        internal fun issuedBy(operation: CatalogCoordinatorLeaseOperation): CatalogCoordinatorLeaseCampaignV1 {
            val (attempt, token) = operation.requireReleasedAcquisition()
            return CatalogCoordinatorLeaseCampaignV1(attempt, token)
        }
    }
}

private const val COORDINATOR_LEASE_NANOS = 30_000_000_000L

private fun requireLeaseWindow(elapsed: Long) {
    if (elapsed !in 0 until COORDINATOR_LEASE_NANOS) refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
}

private fun refuse(code: PersistencePhaseFailureCode): Nothing = throw PersistencePhaseException(code)
