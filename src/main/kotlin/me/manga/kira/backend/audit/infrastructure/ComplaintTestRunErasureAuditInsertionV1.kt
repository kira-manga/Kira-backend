package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureSqlV1

/** Fixed scope-only audit; the exact erasure holder chooses a real reducer transition or final PURGED. */
internal class ComplaintTestRunErasureAuditInsertionV1 private constructor(private val original: TestRunErasureOperationV1) {
    private var complete = false
    fun belongsTo(operation: TestRunErasureOperationV1) = original === operation
    fun completedFor(operation: TestRunErasureOperationV1) = belongsTo(operation) && complete
    override fun toString(): String = "ComplaintTestRunErasureAuditInsertionV1(original-owned,no-installation-identity)"
    companion object {
        internal fun insert(operation: TestRunErasureOperationV1): ComplaintTestRunErasureAuditInsertionV1 {
            try {
                val value = ComplaintTestRunErasureAuditInsertionV1(operation)
                val jdbc = operation.beginAudit(value)
                val arguments = operation.auditArguments(value, jdbc) // One use, spent before dispatch.
                check(jdbc.update(TestRunErasureSqlV1.insertAudit, *arguments) == 1)
                operation.requireAudit(value, jdbc); value.complete = true
                return value
            } catch (problem: Throwable) { operation.failed(problem) }
        }
    }
}
