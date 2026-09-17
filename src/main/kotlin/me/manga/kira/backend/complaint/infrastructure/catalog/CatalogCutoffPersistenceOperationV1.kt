package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import org.springframework.jdbc.core.JdbcTemplate

/** Closed control-only, row-page-only OR publication-only operation. No backward locks or network under a holder. */
internal class CatalogCutoffPersistenceOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val attempt: CatalogCutoffAttemptV1,
    internal val path: PersistencePhasePath,
    private val verification: CapturedCutoffVerificationV1?,
) {
    private var complete = false
    private var wasReleased = false
    private var control: CatalogEpochRotationRowV1? = null
    private var publications: List<CatalogCutoffPublicationRowV1>? = null

    internal fun belongsTo(selected: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = phase === selected && path === selectedPath
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && complete
    internal fun released(): Boolean = wasReleased

    internal fun requireReleased() {
        phase.cutoffPublications.requireCommitted(this)
        requireConnectionFree()
        wasReleased = true
    }

    internal fun controlRow(): CatalogEpochRotationRowV1 {
        requireReleased()
        check(path === PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL)
        return checkNotNull(control)
    }

    internal fun rows(): List<CatalogCutoffPublicationRowV1> {
        requireReleased()
        check(path === PersistencePhasePath.COMPLAINT_CUTOFF_PAGE)
        return checkNotNull(publications)
    }

    internal fun contains(row: CatalogCutoffPublicationRowV1): Boolean = rows().any { it === row }

    internal fun verified() {
        requireReleased()
        check(path === PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY)
        checkNotNull(verification).validateReleased(checkNotNull(publications).single())
    }

    private fun execute() {
        requireRetained()
        when (path) {
            PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL -> control()

            PersistencePhasePath.COMPLAINT_CUTOFF_PAGE -> publications = jdbc.query(
                attempt.pageSql(),
                { row, _ -> CatalogCutoffPublicationRowV1.copy(row) },
                *attempt.pageArguments(),
            )

            PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY -> verify()

            else -> error("Invalid cutoff persistence phase.")
        }
        requireRetained()
        complete = true
    }

    private fun control() {
        val locked = jdbc.query(LOCK_EPOCH_ROTATION_CONTROL, { row, _ -> CatalogEpochRotationRowV1.copy(row) }).single()
        requireRetained()
        // Strict DB-time lease/full B is sampled only AFTER the actual row lock returns.
        val sampled = jdbc.query(READ_EPOCH_ROTATION_CONTROL, { row, _ -> CatalogEpochRotationRowV1.copy(row) }).single()
        requireRetained()
        check(locked.sameState(sampled))
        attempt.checkControl(sampled)
        val final = jdbc.query(READ_EPOCH_ROTATION_CONTROL, { row, _ -> CatalogEpochRotationRowV1.copy(row) }).single()
        requireRetained()
        check(sampled.sameState(final))
        attempt.checkControl(final)
        control = final
    }

    private fun verify() {
        val input = checkNotNull(verification)
        input.requireOwned(attempt)
        // Historical immutable evidence is deliberately NOT fenced. No control/receipt/counter/domain lock.
        val locked = jdbc.query(
            CatalogCutoffPublicationSqlV1.LOCK_PUBLICATION,
            { row, _ -> CatalogCutoffPublicationRowV1.copy(row) },
            input.row.eventId,
        ).single()
        requireRetained()
        check(input.row.sameImmutable(locked))
        val changed = locked.state == "PREPARED"
        val stored = if (changed) {
            jdbc.query(
                CatalogCutoffPublicationSqlV1.RECORD_VERIFIED,
                { row, _ -> CatalogCutoffPublicationRowV1.copy(row) },
                *input.arguments(),
            ).single()
        } else {
            locked // VERIFIED/APPLIED race preserves the exact first proof, never a rewritten timestamp/hash.
        }
        requireRetained()
        input.checkStored(stored, changed)
        publications = listOf(stored)
    }

    private fun requireRetained() {
        phase.cutoffPublications.requireRetained(this, jdbc)
        attempt.requireOperation(this)
    }

    override fun toString(): String = "CatalogCutoffPersistenceOperationV1(closed,redacted,no-provider-or-seal-authority)"

    companion object {
        internal fun supports(path: PersistencePhasePath): Boolean = path === PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL ||
            path === PersistencePhasePath.COMPLAINT_CUTOFF_PAGE || path === PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY

        internal fun execute(
            jdbc: JdbcTemplate,
            attempt: CatalogCutoffAttemptV1,
            path: PersistencePhasePath,
            verification: CapturedCutoffVerificationV1?,
        ): CatalogCutoffPersistenceOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            phase.cutoffPublications.requireOperation(jdbc, path)
            check((path === PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY) == (verification != null))
            val operation = CatalogCutoffPersistenceOperationV1(phase, jdbc, attempt, path, verification)
            phase.cutoffPublications.retain(operation, jdbc)
            attempt.retain(operation)
            operation.execute()
            return operation
        }
    }
}

/** Fixed entries using the original coordinator's exact JdbcTemplate and one existing permit. */
internal class CatalogCutoffPersistenceExecutorV1(private val coordinator: CatalogCoordinatorPersistence, private val jdbc: JdbcTemplate) {
    private val ownership = coordinator.ownership

    internal fun control(attempt: CatalogCutoffAttemptV1): CatalogCutoffPersistenceOperationV1 =
        persist(attempt, PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL, null)

    internal fun page(attempt: CatalogCutoffAttemptV1): CatalogCutoffPersistenceOperationV1 = persist(attempt, PersistencePhasePath.COMPLAINT_CUTOFF_PAGE, null)

    internal fun verify(attempt: CatalogCutoffAttemptV1, proof: CapturedCutoffVerificationV1) {
        persist(attempt, PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY, proof).verified()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun persist(
        attempt: CatalogCutoffAttemptV1,
        path: PersistencePhasePath,
        proof: CapturedCutoffVerificationV1?,
    ): CatalogCutoffPersistenceOperationV1 {
        attempt.requirePersistence(ownership, jdbc, path)
        attempt.beginOperation(path)
        val phase = when (path) {
            PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL -> ownership.enterComplaintCutoffControl(attempt)
            PersistencePhasePath.COMPLAINT_CUTOFF_PAGE -> ownership.enterComplaintCutoffPage(attempt)
            PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY -> ownership.enterComplaintCutoffVerify(attempt)
            else -> error("Invalid cutoff persistence phase.")
        }
        var operation: CatalogCutoffPersistenceOperationV1? = null
        try {
            phase.begin()
            operation = CatalogCutoffPersistenceOperationV1.execute(jdbc, attempt, path, proof)
            attempt.requirePersistence(ownership, jdbc, path)
            phase.commit()
        } catch (problem: Throwable) {
            attempt.abort()
            phase.recordFailure(boundedEpochRotationFailure(problem))
        } finally {
            phase.finish()
        }
        val result = operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        result.requireReleased()
        return result
    }
}
