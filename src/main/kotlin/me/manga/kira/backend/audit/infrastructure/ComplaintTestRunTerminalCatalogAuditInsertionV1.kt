package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalProjectionSqlV1

/** The one fixed prepaid terminal-catalog audit, owned by the exact original PROJECT holder. */
internal class ComplaintTestRunTerminalCatalogAuditInsertionV1 private constructor(private val original: CatalogTestRunTerminalOperationV1) {
    private var completed = false
    fun belongsTo(operation: CatalogTestRunTerminalOperationV1): Boolean = original === operation
    fun completedFor(operation: CatalogTestRunTerminalOperationV1): Boolean = belongsTo(operation) && completed
    override fun toString(): String = "ComplaintTestRunTerminalCatalogAuditInsertionV1(one-original-prepaid-audit)"
    companion object {
        internal fun insert(operation: CatalogTestRunTerminalOperationV1): ComplaintTestRunTerminalCatalogAuditInsertionV1 {
            try {
                val insertion = ComplaintTestRunTerminalCatalogAuditInsertionV1(operation)
                val jdbc = operation.beginProjectionAudit(insertion)
                val arguments = operation.projectionAuditArguments(insertion, jdbc) // Consume before any dispatch.
                check(jdbc.update(CatalogTestRunTerminalProjectionSqlV1.insertAudit, *arguments) == 1)
                operation.requireProjectionAudit(insertion, jdbc); insertion.completed = true
                return insertion
            } catch (problem: Throwable) { operation.failed(problem) }
        }
    }
}
