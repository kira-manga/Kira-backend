package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/** Failure-only observation after our caller terminated; no join, new request, raw value or Throwable inspection. */
internal fun pgLifecycleCallerDiagnostic(task: FutureTask<out PersistenceFactoryResult<*>>, thread: Thread): String {
    val threadState = thread.state
    if (threadState !== Thread.State.TERMINATED || !task.isDone) {
        return "thread_state=${threadState.name} outcome=UNAVAILABLE"
    }
    // FutureTask.state/resultNow may yield during COMPLETING. Only our now-ended caller runs this private task.
    val state = task.state()
    val outcome = if (state === Future.State.SUCCESS) {
        PgLifecycleDatabaseDiagnostics.resultFields(task.resultNow(), null, null)
    } else {
        "outcome=UNAVAILABLE"
    }
    return "thread_state=${threadState.name} task_state=${state.name} $outcome"
}
