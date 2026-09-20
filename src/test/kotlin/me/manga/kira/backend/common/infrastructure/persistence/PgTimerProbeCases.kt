package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path

internal object PgTimerProbeCases {
    fun verify(mode: PgTimerProbeCase, root: Path) {
        when {
            mode.name.startsWith("META_") -> metadata(mode, root)
            mode.name.startsWith("MODEL_") -> model(mode, root)
            mode.name.startsWith("REAL_") -> real(mode)
            else -> error("Unsupported timer scenario.")
        }
    }

    private fun metadata(mode: PgTimerProbeCase, root: Path) {
        PgTimerMetadataFixture(root, mode).use { fixture ->
            fixture.verifyInheritedMetadataShape()
            val prepared = PersistenceDriverBootstrap.prepareWithLoader(fixture.loader)
            val result = prepared.constructWithTimerAccess()
            check(result.driver.javaClass.classLoader === fixture.loader)
            check(System.getProperty(PG_TIMER_INITIALIZED) == null) { "Metadata inspection initialized SharedTimer." }
            check(fixture.count("constructors") == 1 && fixture.count("utility") == 0 && fixture.count("acquire") == 0 && fixture.count("release") == 0)
            if (mode === PgTimerProbeCase.META_EXACT) {
                PgTimerProbeScope(requireNotNull(result.timerAccess)).use { check(!it.calls.observe().guard.entered) }
            } else {
                check(result.timerAccess == null) { "Invalid timer metadata was accepted." }
            }
            check(prepared.construct().javaClass === result.driver.javaClass)
            check(fixture.count("constructors") == 2) { "Optional timer rejection changed ordinary construction." }
            check(System.getProperty(PG_TIMER_INITIALIZED) == null)
            println("PG_TIMER_METADATA mode=${mode.name} initialized=false getters=0 ordinary_preserved=true proof=SYNTHETIC_SHAPE")
        }
    }

    private fun model(mode: PgTimerProbeCase, root: Path) {
        PgTimerMetadataFixture(root, mode).use { fixture ->
            val result = PersistenceDriverBootstrap.prepareWithLoader(fixture.loader).constructWithTimerAccess()
            check(System.getProperty(PG_TIMER_INITIALIZED) == null)
            PgTimerProbeScope(requireNotNull(result.timerAccess)).use { scope ->
                scope.start()
                when (mode) {
                    PgTimerProbeCase.MODEL_GUARD_RECHECK -> guard(scope, fixture)

                    PgTimerProbeCase.MODEL_NULL_UTILITY, PgTimerProbeCase.MODEL_SUBCLASS_UTILITY, PgTimerProbeCase.MODEL_UTILITY_FAILURE,
                    PgTimerProbeCase.MODEL_ACQUIRE_FAILURE, PgTimerProbeCase.MODEL_ACQUIRE_INTERRUPTED, PgTimerProbeCase.MODEL_ACQUIRE_FATAL,
                    PgTimerProbeCase.MODEL_NULL_TIMER,
                    -> unavailable(scope, fixture, mode)

                    else -> acquiredModel(scope, fixture, mode)
                }
                check(scope.calls.acquireReference() === PersistenceTimerAction.REFUSED)
                check(scope.call { it.acquireReference() } === PersistenceTimerAction.REFUSED)
            }
            check(fixture.count("rendered") == 0) { "A hostile failure graph was read." }
        }
    }

    private fun guard(scope: PgTimerProbeScope, fixture: PgTimerMetadataFixture) {
        System.setProperty("hikaricp.configurationFile", "synthetic-forbidden-presence")
        try {
            check(scope.call { it.acquireReference() } === PersistenceTimerAction.FAILED)
            val observation = scope.calls.observe()
            check(observation.guard.outcome === PersistenceTimerOutcome.THREW && observation.guard.extentEnded)
            check(!observation.utility.entered && !observation.referenceReturned)
            check(fixture.count("utility") == 0 && fixture.count("acquire") == 0)
        } finally {
            System.clearProperty("hikaricp.configurationFile")
        }
    }

    private fun unavailable(scope: PgTimerProbeScope, fixture: PgTimerMetadataFixture, mode: PgTimerProbeCase) {
        val action = runCatching {
            scope.call {
                val outcome = it.acquireReference()
                if (mode === PgTimerProbeCase.MODEL_ACQUIRE_INTERRUPTED) check(Thread.currentThread().isInterrupted)
                outcome
            }
        }
        val state = scope.calls.observe()
        check(state.guard.outcome === PersistenceTimerOutcome.RETURNED && state.guard.extentEnded)
        if (mode === PgTimerProbeCase.MODEL_ACQUIRE_FATAL) {
            check(action.exceptionOrNull() === fixture.fatal())
        } else {
            val expected = if (mode in setOf(
                    PgTimerProbeCase.MODEL_UTILITY_FAILURE,
                    PgTimerProbeCase.MODEL_ACQUIRE_FAILURE,
                    PgTimerProbeCase.MODEL_ACQUIRE_INTERRUPTED,
                )
            ) {
                PersistenceTimerAction.FAILED
            } else {
                PersistenceTimerAction.UNAVAILABLE
            }
            check(action.getOrThrow() === expected)
        }
        val utilityOnly = mode in setOf(PgTimerProbeCase.MODEL_NULL_UTILITY, PgTimerProbeCase.MODEL_SUBCLASS_UTILITY, PgTimerProbeCase.MODEL_UTILITY_FAILURE)
        check(fixture.count("utility") == 1 && fixture.count("acquire") == if (utilityOnly) 0 else 1)
        val call = if (utilityOnly) state.utility else state.acquisition
        val returnedNull = mode in setOf(PgTimerProbeCase.MODEL_NULL_UTILITY, PgTimerProbeCase.MODEL_SUBCLASS_UTILITY, PgTimerProbeCase.MODEL_NULL_TIMER)
        check(call.entered && call.extentEnded && call.outcome === if (returnedNull) PersistenceTimerOutcome.RETURNED else PersistenceTimerOutcome.THREW)
        check(!state.referenceReturned && !state.stockTimerReturned)
        check(state.capture.termination === PersistenceThreadTermination.UNKNOWN)
        check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.REFUSED)
        check(scope.call { it.captureThread() } === PersistenceTimerAction.REFUSED)
        check(fixture.count("release") == 0)
        println("PG_TIMER_MODEL mode=${mode.name} no_guessed_release=true no_retry=true proof=MODEL_PROVIDER_NOT_VM_EXHAUSTION")
    }

    private fun acquiredModel(scope: PgTimerProbeScope, fixture: PgTimerMetadataFixture, mode: PgTimerProbeCase) {
        if (mode === PgTimerProbeCase.MODEL_REENTRANCY) installReentrancyHooks(scope, fixture)
        val acquired = scope.call { it.acquireReference() }
        check(scope.calls.observe().referenceReturned)
        if (mode === PgTimerProbeCase.MODEL_SUBCLASS_TIMER) {
            check(acquired === PersistenceTimerAction.UNAVAILABLE && !scope.calls.observe().stockTimerReturned)
            check(scope.call { it.captureThread() } === PersistenceTimerAction.REFUSED)
            check(scope.calls.observe().capture.termination === PersistenceThreadTermination.UNKNOWN)
        } else {
            check(acquired === PersistenceTimerAction.SUCCEEDED)
            check(scope.call { it.captureThread() } === PersistenceTimerAction.SUCCEEDED)
            awaitPgFixtureFact { scope.calls.observe().capture.status === PersistenceTimerCaptureStatus.CAPTURED }
            check(PgTimerModelAccess.capturedThread(scope.calls) === fixture.timerThread())
            when (mode) {
                PgTimerProbeCase.MODEL_SCHEDULE_EXTENT -> PgTimerModelAccess.verifyHeldExtent(scope)
                PgTimerProbeCase.MODEL_MISSING_PUBLICATION -> PgTimerModelAccess.verifyMissingPublication(scope)
                PgTimerProbeCase.MODEL_SCHEDULE_FAILURE -> PgTimerModelAccess.verifyFailedSchedule(scope)
                else -> Unit
            }
        }
        val released = scope.call { it.releaseOwnedReference() }
        val failed = mode === PgTimerProbeCase.MODEL_RELEASE_FAILURE
        check(released === if (failed) PersistenceTimerAction.FAILED else PersistenceTimerAction.SUCCEEDED)
        check(scope.calls.observe().release.extentEnded)
        check(scope.calls.observe().release.outcome === if (failed) PersistenceTimerOutcome.THREW else PersistenceTimerOutcome.RETURNED)
        check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.REFUSED)
        check(scope.call { it.captureThread() } === PersistenceTimerAction.REFUSED)
        check(fixture.count("acquire") == 1 && fixture.count("release") == 1)
        awaitPgFixtureFact { fixture.timerThread()?.isAlive == false }
        if (mode === PgTimerProbeCase.MODEL_SUBCLASS_TIMER || mode === PgTimerProbeCase.MODEL_SCHEDULE_FAILURE) {
            check(scope.calls.observe().capture.termination === PersistenceThreadTermination.UNKNOWN)
        } else {
            check(scope.calls.observe().capture.termination === PersistenceThreadTermination.TERMINATED)
        }
        println("PG_TIMER_MODEL mode=${mode.name} pin_retained=true release_once=true proof=MODEL_PROVIDER")
    }

    private fun installReentrancyHooks(scope: PgTimerProbeScope, fixture: PgTimerMetadataFixture) {
        fixture.hook(
            "acquireHook",
            Runnable {
                check(scope.calls.acquireReference() === PersistenceTimerAction.REFUSED)
                check(scope.calls.captureThread() === PersistenceTimerAction.REFUSED)
                check(scope.calls.releaseOwnedReference() === PersistenceTimerAction.REFUSED)
            },
        )
        fixture.hook(
            "releaseHook",
            Runnable {
                check(scope.calls.releaseOwnedReference() === PersistenceTimerAction.REFUSED)
                check(scope.calls.captureThread() === PersistenceTimerAction.REFUSED)
                check(scope.calls.acquireReference() === PersistenceTimerAction.REFUSED)
            },
        )
    }

    private fun real(mode: PgTimerProbeCase) {
        val result = PersistenceDriverBootstrap.prepare().constructWithTimerAccess()
        if (mode === PgTimerProbeCase.REAL_CAPTURE) {
            unsharedReal(requireNotNull(result.timerAccess))
            return
        }
        val scope = PgTimerProbeScope(requireNotNull(result.timerAccess))
        val foreign = PgTimerForeignReference(result.driver, mode === PgTimerProbeCase.REAL_HELD_TASK)
        try {
            foreign.acquire()
            scope.start()
            check(scope.call { it.acquireReference() } === PersistenceTimerAction.SUCCEEDED)
            if (mode === PgTimerProbeCase.REAL_NO_CAPTURE) {
                check(scope.calls.observe().capture.status === PersistenceTimerCaptureStatus.NOT_SCHEDULED)
            } else {
                captureReal(scope, foreign, mode)
            }
            check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.SUCCEEDED)
            check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.REFUSED)
            check(scope.call { it.acquireReference() } === PersistenceTimerAction.REFUSED)
            check(scope.call { it.captureThread() } === PersistenceTimerAction.REFUSED)
            check(foreign.capturedThread().isAlive) { "Our release consumed the separately owned foreign reference." }
            val expected = if (mode === PgTimerProbeCase.REAL_NO_CAPTURE) PersistenceThreadTermination.UNKNOWN else PersistenceThreadTermination.PENDING
            check(scope.calls.observe().capture.termination === expected)
            foreign.releaseReference()
            awaitPgFixtureFact { !foreign.capturedThread().isAlive }
            if (mode === PgTimerProbeCase.REAL_NO_CAPTURE) {
                check(scope.calls.observe().capture.termination === PersistenceThreadTermination.UNKNOWN)
            } else {
                check(scope.calls.observe().capture.termination === PersistenceThreadTermination.TERMINATED)
            }
            println("PG_TIMER_REAL mode=${mode.name} thread_id=${foreign.capturedThread().threadId()} release_not_termination=true proof=REAL_DRIVER")
        } finally {
            foreign.releaseHold()
            val controller = runCatching { scope.close() }
            val timer = runCatching { foreign.close() }
            controller.getOrThrow()
            timer.getOrThrow()
        }
    }

    private fun unsharedReal(access: PersistencePgTimerAccess) {
        PgTimerProbeScope(access).use { scope ->
            scope.start()
            check(scope.call { it.acquireReference() } === PersistenceTimerAction.SUCCEEDED)
            check(scope.call { it.captureThread() } === PersistenceTimerAction.SUCCEEDED)
            awaitPgFixtureFact { scope.calls.observe().capture.status === PersistenceTimerCaptureStatus.CAPTURED }
            val thread = requireNotNull(PgTimerModelAccess.capturedThread(scope.calls))
            check(thread.isAlive && scope.calls.observe().referenceReturned && scope.calls.observe().stockTimerReturned)
            check(scope.calls.releaseOwnedReference() === PersistenceTimerAction.REFUSED)
            check(!scope.calls.observe().release.entered)
            check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.SUCCEEDED)
            check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.REFUSED)
            awaitPgFixtureFact { scope.calls.observe().capture.termination === PersistenceThreadTermination.TERMINATED }
            check(!thread.isAlive)
            println("PG_TIMER_REAL mode=REAL_CAPTURE thread_id=${thread.threadId()} own_last_release=true proof=REAL_DRIVER")
        }
    }

    private fun captureReal(scope: PgTimerProbeScope, foreign: PgTimerForeignReference, mode: PgTimerProbeCase) {
        check(scope.call { it.captureThread() } === PersistenceTimerAction.SUCCEEDED)
        if (mode === PgTimerProbeCase.REAL_HELD_TASK) {
            foreign.verifyHeld()
            check(scope.calls.observe().scheduling.extentEnded)
            check(!scope.calls.observe().capture.publicationReceived)
            check(scope.calls.observe().capture.status === PersistenceTimerCaptureStatus.PENDING)
            check(scope.call { it.releaseOwnedReference() } === PersistenceTimerAction.REFUSED)
            foreign.releaseHold()
        }
        awaitPgFixtureFact { scope.calls.observe().capture.status === PersistenceTimerCaptureStatus.CAPTURED }
        check(PgTimerModelAccess.capturedThread(scope.calls) === foreign.capturedThread())
        check(scope.call { it.captureThread() } === PersistenceTimerAction.REFUSED)
    }
}
