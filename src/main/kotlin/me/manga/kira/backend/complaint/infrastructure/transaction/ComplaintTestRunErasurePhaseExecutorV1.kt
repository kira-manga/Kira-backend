package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import org.springframework.jdbc.core.JdbcTemplate

/** Same retained deletion root, routine admission. No callback, pool, retry or uncommitted result. */
internal class ComplaintTestRunErasurePhaseExecutorV1(
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
) {
    internal fun execute(original: TestRunErasureV1): TestRunErasureOperationV1 {
        requireConnectionFree()
        original.requirePersistence(ownership, jdbc)
        val phase = ownership.enterTestRunErasure(original)
        var operation: TestRunErasureOperationV1? = null
        try {
            phase.begin()
            original.authenticate(ownership, jdbc)
            operation = TestRunErasureOperationV1.execute(jdbc, original)
            phase.commit()
        } catch (problem: Throwable) {
            original.observeFailure(problem)
            phase.recordFailure(problem)
        } finally {
            try { runCatching(phase::finish).exceptionOrNull()?.let(original::observeFailure) }
            finally { original.observePhaseCleanup(phase) }
        }
        original.throwIfSignalled()
        return (operation ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)).also { it.requireReleased() }
    }
}
