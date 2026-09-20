package me.manga.kira.backend.audit.infrastructure

import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1

/** One fixed SYSTEM RUN_SEALED audit, paid by the original run/counter transaction. No supplied body or time. */
internal class ComplaintTestRunSealedAuditInsertionV1 private constructor(private val original: TestRunSealingOperationV1) {
    private var completed = false
    internal fun belongsTo(operation: TestRunSealingOperationV1): Boolean = original === operation
    internal fun completedFor(operation: TestRunSealingOperationV1): Boolean = belongsTo(operation) && completed
    override fun toString(): String = "ComplaintTestRunSealedAuditInsertionV1(original-paid-seal,redacted)"

    companion object {
        internal fun insert(operation: TestRunSealingOperationV1): ComplaintTestRunSealedAuditInsertionV1 {
            try {
                val insertion = ComplaintTestRunSealedAuditInsertionV1(operation)
                val jdbc = operation.beginSealAudit(insertion)
                val arguments = operation.sealAuditArguments(insertion, jdbc)
                check(jdbc.update(TestRunSealingSqlV1.insertAudit, *arguments) == 1)
                operation.requireSealAudit(insertion, jdbc)
                insertion.completed = true
                return insertion
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }
    }
}
