package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.catalog.catalogPublicationSignal
import me.manga.kira.backend.complaint.infrastructure.catalog.requirePublication
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet

/** Current matching NONNULL D and exact signed PREPARED G1 only. There is no NULL-D CAS, catalog write or counter operation. */
internal class ComplaintCatalogGenesisPublishRecheckOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val attempt: CatalogGenesisPublishAttemptV1,
) {
    private var stage = Stage.RETAINED

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    /** No reconstructed result is issued: later publisher stages must consult this very operation and its released phase. */
    internal fun requireReleased() {
        phase.catalogGenesisPublishRecheck.requireCommitted(this)
        requireConnectionFree()
        attempt.requireOperation(this)
    }

    internal fun requireRelease(selected: ComplaintSignedGenesisFirstDInputsV1.Verified) {
        phase.catalogGenesisPublishRecheck.requireRetained(this, jdbc)
        attempt.requireRelease(this, selected)
        state(stage === Stage.CATALOG_LOCKED || stage === Stage.REREADING)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute() {
        try {
            requireAt(Stage.RETAINED)
            requirePublication(
                jdbc.query(AUTHENTICATE_DESIRED_OPERATOR_V1, { row, _ -> truth(row, "authenticated") }, attempt.databaseName).single(),
                CatalogGenesisPublishFailureV1.AUTHENTICATION_REFUSED,
            )
            val before = jdbc.query(LOCK_DESIRED_CONTROL_V1, { row, _ -> DesiredControlRowV1.copy(row) }).single()
            requireAt(Stage.RETAINED)
            stage = Stage.CONTROL_LOCKED
            // Genuine shared epoch -> LIVE row -> catalog advisory lock, then a LATER READ COMMITTED all-history statement.
            state(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> truth(row, "locked") }).single())
            requireAt(Stage.CONTROL_LOCKED)
            stage = Stage.CATALOG_LOCKED
            state(before.pristineExceptHash() && !before.binding.hashAbsent() && before.exactTarget(attempt))
            requireInitialHistory()
            requireAt(Stage.CATALOG_LOCKED)
            stage = Stage.REREADING
            val after = jdbc.query(READ_DESIRED_CONTROL_V1, { row, _ -> DesiredControlRowV1.copy(row) }).single()
            requireInitialHistory()
            requireAt(Stage.REREADING)
            state(after.pristineExceptHash() && after.exactTarget(attempt) && before.same(after))
            stage = Stage.COMPLETE // Exact no-op, including updated_at, gates, opaque slots and every lease/checkpoint field.
        } catch (problem: Throwable) {
            stage = Stage.FAILED
            attempt.abort()
            val failure = catalogPublicationSignal(problem)
            phase.recordFailure(failure)
            throw failure // Preserve fatal/cancellation/interruption through actual phase cleanup; the outer publisher sanitizes ordinary details.
        }
    }

    private fun requireInitialHistory() {
        val rows = jdbc.query(
            READ_SIGNED_GENESIS_FIRST_HISTORY_V1,
            { row, _ -> truth(row, "exact_signed_prepared") },
            *attempt.releaseArguments(this),
        )
        state(rows.size == 1 && rows.single()) // No predicate may filter competitors away before the ALL-history cardinality check.
        state(jdbc.query(READ_SIGNED_GENESIS_FIRST_OTHER_STATE_V1, { row, _ -> truth(row, "initial_state") }).single())
    }

    private fun requireAt(expected: Stage) {
        phase.catalogGenesisPublishRecheck.requireRetained(this, jdbc)
        attempt.requireOperation(this)
        state(stage === expected)
    }

    override fun toString(): String = "ComplaintCatalogGenesisPublishRecheckOperationV1(current-read-only,redacted,no-portable-authority)"
    private enum class Stage { RETAINED, CONTROL_LOCKED, CATALOG_LOCKED, REREADING, COMPLETE, FAILED }

    companion object {
        internal fun recheck(jdbc: JdbcTemplate, attempt: CatalogGenesisPublishAttemptV1): ComplaintCatalogGenesisPublishRecheckOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            phase.catalogGenesisPublishRecheck.requireOperation(jdbc)
            val operation = ComplaintCatalogGenesisPublishRecheckOperationV1(phase, jdbc, attempt)
            phase.catalogGenesisPublishRecheck.retain(operation, jdbc)
            attempt.retain(operation)
            operation.execute()
            return operation
        }
    }
}

private fun truth(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { state(!row.wasNull()) }
private fun state(condition: Boolean) = requirePublication(condition, CatalogGenesisPublishFailureV1.STATE_REFUSED)
