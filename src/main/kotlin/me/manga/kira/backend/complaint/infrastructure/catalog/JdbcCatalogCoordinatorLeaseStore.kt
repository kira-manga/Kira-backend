package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Three fixed row-only operations on the existing coordinator; no epoch/advisory/counter/domain lock or callback. */
internal class JdbcCatalogCoordinatorLeaseStore(private val jdbc: JdbcTemplate) {
    fun acquire(attempt: CatalogCoordinatorLeaseCustodyV1.Attempt): CatalogCoordinatorLeaseOperation = CatalogCoordinatorLeaseOperation.acquire(jdbc, attempt)

    fun renew(attempt: CatalogCoordinatorLeaseCustodyV1.Attempt): CatalogCoordinatorLeaseOperation = CatalogCoordinatorLeaseOperation.renew(jdbc, attempt)

    fun relinquish(attempt: CatalogCoordinatorLeaseCustodyV1.Attempt): CatalogCoordinatorLeaseOperation =
        CatalogCoordinatorLeaseOperation.relinquish(jdbc, attempt)
}

/** Concrete retained lock -> later-clock CAS -> exact reread. Completion alone cannot mint a receipt or continuation. */
internal class CatalogCoordinatorLeaseOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val attempt: CatalogCoordinatorLeaseCustodyV1.Attempt,
) {
    private var stage = Stage.RETAINED
    private var fact: CatalogCoordinatorLeaseTransitionFact? = null

    fun belongsTo(candidate: CatalogCoordinatorLeaseCustodyV1.Attempt): Boolean = attempt === candidate

    fun belongsTo(selected: PersistencePhaseContext, path: PersistencePhasePath): Boolean = phase === selected && attempt.path === path

    fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    val receipt: CatalogCoordinatorLeaseReceiptV1 get() = CatalogCoordinatorLeaseReceiptV1.issuedBy(this)

    internal fun requireReleasedTransition(): CatalogCoordinatorLeaseTransitionFact {
        phase.coordinatorLease.requireCommitted(this)
        requireConnectionFree()
        phase.coordinatorLease.requireProcessBinding(attempt.binding, jdbc)
        attempt.requireReleasedResult(this)
        return checkNotNull(fact)
    }

    internal fun requireReleasedAcquisition(): Pair<CatalogCoordinatorLeaseCustodyV1.Attempt, Long> {
        val actual = requireReleasedTransition()
        check(attempt.path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE)
        return attempt to actual.token
    }

    internal fun requireReleasedRenewal(campaign: CatalogCoordinatorLeaseCampaignV1): Pair<CatalogCoordinatorLeaseCampaignV1.Window, Long> {
        requireReleasedTransition()
        return attempt.renewalWindow(this, campaign)
    }

    internal fun acceptAcquisition(): CatalogCoordinatorLeaseCampaignV1 {
        requireReleasedAcquisition()
        return attempt.acceptAcquire(this)
    }

    internal fun renewedReceipt(): CatalogCoordinatorLeaseReceiptV1 {
        val actual = receipt
        attempt.acceptRenewal(this)
        return actual
    }

    internal fun relinquishedReceipt(): CatalogCoordinatorLeaseReceiptV1 {
        val actual = receipt
        attempt.acceptRelinquishment(this)
        return actual
    }

    internal fun returnFailure(problem: Throwable): PersistencePhaseException {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        return phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute() {
        try {
            requireAt(Stage.RETAINED)
            val before = jdbc.query(LOCK_COORDINATOR_LEASE_CONTROL, { row, _ -> readControl(row) }, *arguments()).single()
            requireAt(Stage.RETAINED)
            val token = if (attempt.path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE) {
                check(before.token < Long.MAX_VALUE)
                Math.addExact(before.token, 1L)
            } else {
                check(before.owner == attempt.owner && before.token == attempt.token && before.expiresAt != null)
                before.token
            }
            stage = Stage.CONTROL_LOCKED
            // Only now may a later statement sample clock_timestamp(); no time expression was in the locking projection.
            val changed = when (attempt.path) {
                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE -> jdbc.query(
                    ACQUIRE_COORDINATOR_LEASE, { row, _ -> Changed.copy(row) }, attempt.owner, *arguments(), *before.arguments(),
                )

                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW -> jdbc.query(
                    RENEW_COORDINATOR_LEASE, { row, _ -> Changed.copy(row) }, *arguments(), *before.arguments(), attempt.owner, attempt.token,
                )

                PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH -> jdbc.query(
                    RELINQUISH_COORDINATOR_LEASE, { row, _ -> Changed.copy(row) }, *arguments(), *before.arguments(), attempt.owner, attempt.token,
                )

                else -> error("Unsupported coordinator lease phase.")
            }.single() // Zero is refusal, never contention-as-success; more than one is equally invalid.
            requireAt(Stage.CONTROL_LOCKED)
            val release = attempt.path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH
            val expected = StoredLease(
                if (release) null else attempt.owner,
                token,
                if (release) null else changed.sampledAt.plusSeconds(30),
                changed.sampledAt,
            )
            check(changed.lease == expected)
            stage = Stage.REREADING
            val after = jdbc.query(READ_COORDINATOR_LEASE_CONTROL, { row, _ -> readControl(row) }, *arguments()).single()
            requireAt(Stage.REREADING)
            check(after == expected)
            fact = CatalogCoordinatorLeaseTransitionFact(transition(), attempt.owner, token, changed.sampledAt, expected.expiresAt)
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    private fun arguments(): Array<Any?> = attempt.sqlArguments(this, jdbc)

    private fun readControl(row: ResultSet): StoredLease {
        check(requiredBoolean(row, "binding_matches"))
        return StoredLease.copy(row)
    }

    private fun transition(): CatalogCoordinatorLeaseTransitionV1 = when (attempt.path) {
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE -> CatalogCoordinatorLeaseTransitionV1.ACQUIRED
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW -> CatalogCoordinatorLeaseTransitionV1.RENEWED
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH -> CatalogCoordinatorLeaseTransitionV1.RELINQUISHED
        else -> error("Unsupported coordinator lease phase.")
    }

    private fun requireAt(expected: Stage) {
        phase.coordinatorLease.requireRetained(this, jdbc)
        check(stage === expected)
        phase.coordinatorLease.requireProcessBinding(attempt.binding, jdbc)
        attempt.requireOperation(this, jdbc)
    }

    private fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        attempt.abort()
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "CatalogCoordinatorLeaseOperation(fixed-row-CAS,no-work-authority)"

    private enum class Stage { RETAINED, CONTROL_LOCKED, REREADING, COMPLETE, FAILED }

    companion object {
        internal fun acquire(jdbc: JdbcTemplate, attempt: CatalogCoordinatorLeaseCustodyV1.Attempt): CatalogCoordinatorLeaseOperation =
            execute(jdbc, attempt, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE)

        internal fun renew(jdbc: JdbcTemplate, attempt: CatalogCoordinatorLeaseCustodyV1.Attempt): CatalogCoordinatorLeaseOperation =
            execute(jdbc, attempt, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW)

        internal fun relinquish(jdbc: JdbcTemplate, attempt: CatalogCoordinatorLeaseCustodyV1.Attempt): CatalogCoordinatorLeaseOperation =
            execute(jdbc, attempt, PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH)

        @Suppress("TooGenericExceptionCaught")
        private fun execute(
            jdbc: JdbcTemplate,
            attempt: CatalogCoordinatorLeaseCustodyV1.Attempt,
            expected: PersistencePhasePath,
        ): CatalogCoordinatorLeaseOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.coordinatorLease.requireOperation(jdbc, expected)
                check(attempt.path === expected)
                val operation = CatalogCoordinatorLeaseOperation(phase, jdbc, attempt)
                phase.coordinatorLease.retain(operation, jdbc)
                attempt.retain(operation, jdbc)
                operation.execute()
                return operation
            } catch (problem: Throwable) {
                attempt.abort()
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}

internal class CatalogCoordinatorLeaseTransitionFact(
    val transition: CatalogCoordinatorLeaseTransitionV1,
    val owner: UUID,
    val token: Long,
    val sampledAt: Instant,
    val expiresAt: Instant?,
)

private data class StoredLease(val owner: UUID?, val token: Long, val expiresAt: Instant?, val updatedAt: Instant) {
    fun arguments(): Array<Any?> = arrayOf(owner, token, expiresAt?.let(Timestamp::from), Timestamp.from(updatedAt))

    companion object {
        fun copy(row: ResultSet): StoredLease {
            check(requiredBoolean(row, "finite_times"))
            val owner = row.getObject("lease_owner", UUID::class.java)
            val token = row.getLong("lease_token").also { check(!row.wasNull() && it >= 0) }
            val expiry = row.getTimestamp("lease_expires_at")?.toInstant()
            check((owner == null && expiry == null) || (owner != null && expiry != null && token > 0 && owner.version() == 4 && owner.variant() == 2))
            return StoredLease(owner, token, expiry, checkNotNull(row.getTimestamp("updated_at")).toInstant())
        }
    }
}

private class Changed(val lease: StoredLease, val sampledAt: Instant) {
    companion object {
        fun copy(row: ResultSet): Changed = Changed(StoredLease.copy(row), checkNotNull(row.getTimestamp("sampled_at")).toInstant())
    }
}

private fun requiredBoolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { check(!row.wasNull()) }
