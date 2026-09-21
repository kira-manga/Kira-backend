package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
import java.util.concurrent.atomic.AtomicReference

/** Failure-only passive facts. No SQL, clock sample, cleanup, cause/message, credential or row-value output. */
internal object TestActiveFirstCutSuccessorDiagnosticsV1 {
    fun snapshot(fixture: TestActiveFirstCutSuccessorFixtureV1?, problem: Throwable?,
        original: TestActiveFirstCutSuccessorV1? = null): String = runCatching {
        buildString {
            append("failure=${failure(problem)}")
            if (fixture == null) { append(" fixture=NOT_ENTERED"); return@buildString }
            val first = fixture.first
            val previous = (ownedCutField(first, "observedOriginal") as AtomicReference<*>).get() as? TestActiveFirstCutV1
            val held = SignedActivationObservation.active(first.runtime.pools.catalogCoordinator)
            val custody = when {
                held == null -> "NONE"
                held === previous -> "FIRST_CUT"
                held === original -> "THIS_SUCCESSOR"
                else -> "OTHER"
            }
            append(" custody=$custody previous=${owner(previous)} successor=${owner(original)}")
            val last = first.probe.calls.lastOrNull()
            append(" lastPhase=${last?.path?.name ?: "NONE"} lastStep=${last?.step?.substringBefore(':')?.take(96) ?: "NONE"}")
            if (last != null) {
                val code = (ownedCutField(last.phase, "failure") as AtomicReference<*>).get() as? PersistencePhaseFailureCode
                append(" phaseStage=${stage(last.phase)} phaseFailure=${code?.name ?: "NONE"}")
                append(" phaseOutcome=${last.phase.databaseOutcome().name} phaseCleanup=${last.phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven}")
            }
            append(" previousNative=${native(first.observedNative.get())} successorNativeCount=${fixture.nativeSessions.size}")
            append(" successorNative=${fixture.nativeSessions.takeLast(2).joinToString(";") { native(it) }}")
        }
    }.getOrElse { "failure=${failure(problem)} diagnostic=UNAVAILABLE" }

    fun report(point: String, fixture: TestActiveFirstCutSuccessorFixtureV1?, problem: Throwable?,
        original: TestActiveFirstCutSuccessorV1? = null) {
        // A diagnostic output failure must not replace the original tested failure.
        runCatching { println("TEST_FIRST_CUT_SUCCESSOR_DIAGNOSTIC point=$point ${snapshot(fixture, problem, original)}") }
    }

    private fun failure(problem: Throwable?): String = when (problem) {
        null -> "NONE"
        is PersistencePhaseException -> "PersistencePhaseException:${problem.code.name}:${problem.databaseOutcome.name}:cleanup=${problem.cleanupProven}"
        is PersistenceBoundaryException -> "PersistenceBoundaryException:${problem.code.name}"
        else -> problem.javaClass.simpleName.take(96)
    }

    private fun owner(value: Any?): String {
        if (value == null) return "NONE"
        val flags = listOf("closed", "cleanupProven", "cleanupUncertain", "reserved", "released")
            .joinToString(",") { "$it=${ownedCutField(value, it) as Boolean}" }
        return "${stage(value)}[$flags,closeFailure=${ownedCutField(value, "closeFailure") != null}]"
    }

    private fun stage(value: Any): String = (ownedCutField(value, "stage") as Enum<*>).name
    private fun native(value: PersistenceEpochRotationSession?): String {
        if (value == null) return "NONE"
        val code = (ownedCutField(value, "problem") as AtomicReference<*>).get() as? PersistencePhaseFailureCode
        val facts = value.failure()
        return "${stage(value)}[failure=${code?.name ?: "NONE"},outcome=${facts.databaseOutcome.name},cleanup=${facts.cleanupProven}]"
    }
}
