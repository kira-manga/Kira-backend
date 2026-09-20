package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

/** One fixed shared-fence/control/catalog/point-history read. No projection, counters, mutable callback or admitted-current capability. */
internal class CatalogProjectedHeadReadOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val input: CatalogProjectedHeadInputV1,
) {
    private var stage = Stage.RETAINED

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    internal fun requireReleased(selected: CatalogProjectedHeadInputV1) {
        phase.catalogProjectedHead.requireCommitted(this)
        requireConnectionFree()
        check(input === selected)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun read() {
        try {
            requireAt(Stage.RETAINED)
            readControl(lock = true)
            requireAt(Stage.RETAINED)
            stage = Stage.CONTROL_LOCKED
            check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.projectedBoolean("locked") }).single())
            requireAt(Stage.CONTROL_LOCKED)
            stage = Stage.CATALOG_LOCKED
            val original = readMutation(lock = true)
            requireAt(Stage.CATALOG_LOCKED)
            stage = Stage.MUTATION_LOCKED
            check(readMutation(lock = false) == original)
            requireAt(Stage.MUTATION_LOCKED)
            readControl(lock = false)
            requireAt(Stage.MUTATION_LOCKED)
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    private fun readControl(lock: Boolean) {
        val exact = jdbc.query(
            if (lock) LOCK_PROJECTED_HEAD_CONTROL_V1 else READ_PROJECTED_HEAD_CONTROL_V1,
            { row, _ -> row.projectedBoolean("exact_matches") && row.projectedBoolean("pending_absent") },
            *input.controlArguments(this, jdbc),
        ).single()
        check(exact)
    }

    private fun readMutation(lock: Boolean): ProjectedTimes = jdbc.query(
        if (lock) LOCK_PROJECTED_HEAD_MUTATION_V1 else READ_PROJECTED_HEAD_MUTATION_V1,
        { row, _ ->
            check(row.projectedBoolean("exact_matches"))
            ProjectedTimes(checkNotNull(row.getTimestamp("completed_at")).toInstant(), checkNotNull(row.getTimestamp("projected_at")).toInstant())
        },
        *input.mutationArguments(this, jdbc),
    ).single()

    internal fun requireInput(selected: CatalogProjectedHeadInputV1, resource: JdbcTemplate) {
        phase.catalogProjectedHead.requireRetained(this, resource)
        check(input === selected && jdbc === resource && stage in setOf(Stage.RETAINED, Stage.CATALOG_LOCKED, Stage.MUTATION_LOCKED))
    }

    private fun requireAt(expected: Stage) {
        phase.catalogProjectedHead.requireRetained(this, jdbc)
        check(stage === expected)
    }

    private fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "CatalogProjectedHeadReadOperationV1(sealed-historical-observation,no-current-authority)"

    private enum class Stage { RETAINED, CONTROL_LOCKED, CATALOG_LOCKED, MUTATION_LOCKED, COMPLETE, FAILED }
    private data class ProjectedTimes(val completedAt: Instant, val projectedAt: Instant)

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun read(input: CatalogProjectedHeadInputV1, jdbc: JdbcTemplate): CatalogProjectedHeadReadOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.catalogProjectedHead.requireOperation(input, jdbc)
                val operation = CatalogProjectedHeadReadOperationV1(phase, jdbc, input)
                phase.catalogProjectedHead.retain(operation, jdbc)
                operation.read()
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}

private fun ResultSet.projectedBoolean(column: String): Boolean = getBoolean(column).also { check(!wasNull()) }
