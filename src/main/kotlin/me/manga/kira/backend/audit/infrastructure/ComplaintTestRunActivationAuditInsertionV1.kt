package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionSqlV1

/**
 * Four fixed SYSTEM audits prepaid by the same original TEST PROJECT transaction.
 * No caller-supplied action, subject, detail, timestamp or alternate JDBC holder is accepted.
 */
internal class ComplaintTestRunActivationAuditInsertionV1 private constructor(
    private val original: CatalogTestRunActivationOperationV1,
) {
    private var completed = false

    internal fun belongsTo(operation: CatalogTestRunActivationOperationV1): Boolean = original === operation

    internal fun completedFor(operation: CatalogTestRunActivationOperationV1): Boolean =
        belongsTo(operation) && completed

    override fun toString(): String = "ComplaintTestRunActivationAuditInsertionV1(original-TEST-PROJECT,redacted)"

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun insert(operation: CatalogTestRunActivationOperationV1): ComplaintTestRunActivationAuditInsertionV1 {
            try {
                val insertion = ComplaintTestRunActivationAuditInsertionV1(operation)
                val jdbc = operation.beginProjectionAudit(insertion)
                statements.forEachIndexed { ordinal, sql ->
                    // The original holder spends this exact next stage BEFORE SQL dispatch.
                    val arguments = operation.projectionAuditArguments(insertion, jdbc, ordinal)
                    check(jdbc.update(sql, *arguments) == 1)
                    operation.requireProjectionAudit(insertion, jdbc)
                }
                operation.requireProjectionAudit(insertion, jdbc)
                insertion.completed = true
                return insertion
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private val statements = arrayOf(
            CatalogTestRunActivationProjectionSqlV1.insertFirstNoticeAudit,
            CatalogTestRunActivationProjectionSqlV1.insertSecondNoticeAudit,
            CatalogTestRunActivationProjectionSqlV1.insertActivatedAudit,
            CatalogTestRunActivationProjectionSqlV1.insertProjectedAudit,
        )
    }
}
