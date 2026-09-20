package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogProjectedHeadReadOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_PROJECTED_HEAD_CONTROL_V1
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_PROJECTED_HEAD_MUTATION_V1
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_PROJECTED_HEAD_CONTROL_V1
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_PROJECTED_HEAD_MUTATION_V1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicReference

/** Same retained guarded holder and real statements. Test callbacks observe or fault, never manufacture a successful read operation. */
internal class ProjectedCatalogRefreshProbeJdbc(private val coordinator: CatalogCoordinatorPersistence) : JdbcTemplate(coordinator.dataSource) {
    var phase: PersistencePhaseContext? = null
        private set
    var beforeSql: (ProjectedHeadSqlStep) -> Unit = {}
    var afterSql: (ProjectedHeadSqlStep) -> Unit = {}
    val steps = mutableListOf<ProjectedHeadSqlStep>()
    var controlArguments: List<Any?> = emptyList()
        private set
    var mutationArguments: List<Any?> = emptyList()
        private set
    private val assertionFailure = AtomicReference<AssertionError?>()

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = observed(sql) { super.query(sql, rowMapper) }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql) {
        val history = sql == LOCK_PROJECTED_HEAD_MUTATION_V1 || sql == READ_PROJECTED_HEAD_MUTATION_V1
        val detached = args.map { if (it is ByteArray) it.copyOf() else it }
        if (history) mutationArguments = detached else controlArguments = detached
        assertEquals(if (history) 27 else 9, args.size)
        super.query(
            sql,
            RowMapper<T> { row, index ->
                assertEquals(if (history) 3 else 2, row.metaData.columnCount, "History must return only exactness and finite times, not document bytes.")
                rowMapper.mapRow(row, index)
            },
            *args,
        )
    }

    override fun update(sql: String, vararg args: Any?): Int = preserveAssertions {
        throw AssertionError("A projected-current observation must not execute mutation SQL.")
    }

    fun retainedOperation(): CatalogProjectedHeadReadOperationV1 = poolTestField(checkNotNull(phase).catalogProjectedHead, "retained")

    fun assertNoLostAssertions() {
        assertionFailure.get()?.let { throw it }
    }

    fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertionFailure.compareAndSet(null, failure)
        throw failure
    }

    private fun <T> observed(sql: String, action: () -> T): T = preserveAssertions {
        val current = checkNotNull(PersistencePhaseOwnership.current())
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD, poolTestField<PersistencePhasePath>(current, "path"))
        val holder = TransactionSynchronizationManager.getResource(coordinator.dataSource) as ConnectionHolder
        assertEquals(setOf(coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertSame(coordinator.dataSource, dataSource)
        assertEquals(1, coordinator.activeSnapshotOwners())
        assertFalse(holder.connection.isReadOnly)
        phase = current
        val step = when (sql) {
            LOCK_PROJECTED_HEAD_CONTROL_V1 -> ProjectedHeadSqlStep.LOCK_CONTROL
            TRY_CATALOG_LOCK -> ProjectedHeadSqlStep.LOCK_CATALOG
            LOCK_PROJECTED_HEAD_MUTATION_V1 -> ProjectedHeadSqlStep.LOCK_HISTORY
            READ_PROJECTED_HEAD_MUTATION_V1 -> ProjectedHeadSqlStep.READ_HISTORY
            READ_PROJECTED_HEAD_CONTROL_V1 -> ProjectedHeadSqlStep.READ_CONTROL
            else -> throw AssertionError("Unexpected projected-current observation statement.")
        }
        steps += step
        beforeSql(step)
        action().also { afterSql(step) }
    }
}

internal enum class ProjectedHeadSqlStep { LOCK_CONTROL, LOCK_CATALOG, LOCK_HISTORY, READ_HISTORY, READ_CONTROL }
