package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

/** Exact signed PREPARED G1 first-D, on one retained fenced operator holder. No catalog write or counter operation exists here. */
internal class ComplaintSignedGenesisFirstDOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val attempt: ComplaintSignedGenesisFirstDAttemptV1,
) {
    private var stage = Stage.RETAINED
    private var transition: ComplaintSignedGenesisFirstDTransitionV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    internal fun releasedResult(): ComplaintSignedGenesisFirstDResultV1 {
        phase.signedGenesisFirstDesired.requireCommitted(this)
        requireConnectionFree()
        attempt.requireOperation(this)
        return ComplaintSignedGenesisFirstDResultV1(checkNotNull(transition), attempt.desiredGeneration)
    }

    internal fun requireRelease(selected: ComplaintSignedGenesisFirstDInputsV1.Verified) {
        phase.signedGenesisFirstDesired.requireRetained(this, jdbc)
        attempt.requireRelease(this, selected)
        state(stage === Stage.CATALOG_LOCKED || stage === Stage.REREADING)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute() {
        try {
            requireAt(Stage.RETAINED)
            requireDesiredInstallation(
                jdbc.query(AUTHENTICATE_DESIRED_OPERATOR_V1, { row, _ -> truth(row, "authenticated") }, attempt.databaseName).single(),
                ComplaintDesiredInstallationFailureV1.AUTHENTICATION_REFUSED,
            )
            val before = jdbc.query(LOCK_DESIRED_CONTROL_V1, { row, _ -> DesiredControlRowV1.copy(row) }).single()
            requireAt(Stage.RETAINED)
            stage = Stage.CONTROL_LOCKED
            // The phase already holds the genuine shared epoch fence. Cooperating history writers take this same order.
            state(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> truth(row, "locked") }).single())
            requireAt(Stage.CONTROL_LOCKED)
            stage = Stage.CATALOG_LOCKED
            state(before.pristineExceptHash()) // Initial scan request remains true; neither route manufactures or clears it.
            requireInitialHistory()
            requireAt(Stage.CATALOG_LOCKED)
            val sampledAt: Instant?
            if (before.binding.hashAbsent()) {
                sampledAt = jdbc.query(SELECT_SIGNED_GENESIS_FIRST_D_V1, { row, _ ->
                    state(truth(row, "finite_times"))
                    checkNotNull(row.getTimestamp("sampled_at")).toInstant().also { at ->
                        state(row.getTimestamp("updated_at")?.toInstant() == at)
                    }
                }, attempt.configurationHash(), *before.preimageArguments()).single()
                transition = ComplaintSignedGenesisFirstDTransitionV1.SELECTED
            } else {
                state(before.exactTarget(attempt))
                sampledAt = null
                transition = ComplaintSignedGenesisFirstDTransitionV1.ALREADY_SELECTED
            }
            requireAt(Stage.CATALOG_LOCKED)
            stage = Stage.REREADING
            val after = jdbc.query(READ_DESIRED_CONTROL_V1, { row, _ -> DesiredControlRowV1.copy(row) }).single()
            requireInitialHistory() // Same retained locks, all history and the complete initial-state predicates again.
            requireAt(Stage.REREADING)
            state(after.pristineExceptHash() && after.exactTarget(attempt))
            if (sampledAt == null) {
                state(before.same(after)) // Exact no-op: even updated_at and opaque preserved fields are untouched.
            } else {
                state(before.samePreserved(after) && before.sameGatesAndLease(after) && before.binding.sameExceptDesired(after.binding))
                state(after.updatedAt == sampledAt)
            }
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            stage = Stage.FAILED
            attempt.abort()
            phase.recordFailure(problem)
            throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        }
    }

    private fun requireInitialHistory() {
        val rows = jdbc.query(
            READ_SIGNED_GENESIS_FIRST_HISTORY_V1,
            { row, _ -> truth(row, "exact_signed_prepared") },
            *attempt.releaseArguments(this),
        )
        state(rows.size == 1 && rows.single()) // NEVER filter away competitors before checking ALL-history cardinality.
        state(jdbc.query(READ_SIGNED_GENESIS_FIRST_OTHER_STATE_V1, { row, _ -> truth(row, "initial_state") }).single())
    }

    private fun requireAt(expected: Stage) {
        phase.signedGenesisFirstDesired.requireRetained(this, jdbc)
        attempt.requireOperation(this)
        state(stage === expected)
    }

    override fun toString(): String = "ComplaintSignedGenesisFirstDOperationV1(D-and-time-only,redacted,no-authority)"
    private enum class Stage { RETAINED, CONTROL_LOCKED, CATALOG_LOCKED, REREADING, COMPLETE, FAILED }

    companion object {
        internal fun select(jdbc: JdbcTemplate, attempt: ComplaintSignedGenesisFirstDAttemptV1): ComplaintSignedGenesisFirstDOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            phase.signedGenesisFirstDesired.requireOperation(jdbc)
            val operation = ComplaintSignedGenesisFirstDOperationV1(phase, jdbc, attempt)
            phase.signedGenesisFirstDesired.retain(operation, jdbc)
            attempt.retain(operation)
            operation.execute()
            return operation
        }
    }
}

private fun truth(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { state(!row.wasNull()) }
private fun state(condition: Boolean) = requireDesiredInstallation(condition, ComplaintDesiredInstallationFailureV1.STATE_REFUSED)
