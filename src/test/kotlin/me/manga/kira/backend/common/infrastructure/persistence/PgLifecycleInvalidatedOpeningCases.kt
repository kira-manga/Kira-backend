package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Real managed F1 waits on a MODEL G cut. Expiry, permanent seal and interruption must all be revalidated. */
internal object PgLifecycleInvalidatedOpeningCases {
    fun verify(mode: PgLifecycleCase) {
        val stage = when {
            mode.name.startsWith("MODEL_CLAIM_") -> 0
            mode.name.startsWith("MODEL_PRIMARY_PREPARE_") -> 1
            mode.name.startsWith("MODEL_PRIMARY_INSTALL_") -> 2
            else -> error("Unknown invalidated opening gate.")
        }
        val action = Action.valueOf(mode.name.substringAfterLast('_'))
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
            PgLifecycleTestScope(pgProbeEndpoint(listener.localPort)).use { scope ->
                val gate = PgLifecycleContentionLock.install(scope, stage)
                scope.expectedUnknown = action === Action.INTERRUPTED
                scope.start()
                runInvalidated(scope, gate, stage, action)
            }
        }
    }

    private fun runInvalidated(scope: PgLifecycleTestScope, gate: PgLifecycleContentionLock, stage: Int, action: Action) {
        val binding = scope.binding()
        val control = PersistenceOwnedCallerControl.prepare(if (action === Action.EXPIRED) 750 else 6_000)
        val opening = requireNotNull(scope.root.ordinary.selectOpening())
        check(opening.policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION)
        val entry = requireNotNull(binding.reserve(control, opening.policy, opening))
        gate.entry.set(entry)
        try {
            check(binding.admit(entry))
            check(gate.entered.await(4, TimeUnit.SECONDS))
            gate.lock()
            try {
                gate.proceed.countDown()
                awaitLifecycleFact { gate.hasQueuedThread(gate.target) || gate.attemptEnded.get() }
                check(gate.hasQueuedThread(gate.target) && !gate.attemptEnded.get())
                check(control.state() === PersistenceOwnedCallerDisposition.ATTACHED && entry.raw.get() == null)
                invalidate(action, scope, control, gate.target)
                // The waiter is still in the original acquisition, not a retried ticket or fresh allowance.
                check(!gate.attemptEnded.get() && entry.attempt?.budget === control.budget)
            } finally {
                gate.unlock()
            }
            // First prove that the original invalidation, not caller detachment, rejected the opening.
            awaitLifecycleFact { entry.openingFacts.factoryEnded.get() }
            check(gate.attemptEnded.get() && entry.raw.get() == null && entry.openingFacts.factoryEnded.get())
            check(entry.openingFacts.driverEntered.get() == (stage != 0))
            if (stage != 0) check(entry.openingFacts.driverEnded.get() && entry.scopeEnded)
            var transports: PersistenceTransportSnapshot = PersistenceTransportSnapshot.Unavailable
            awaitLifecycleFact {
                transports = requireNotNull(entry.transports).snapshot()
                transports is PersistenceTransportSnapshot.Available
            }
            val snapshot = transports
            check(snapshot is PersistenceTransportSnapshot.Available && snapshot.primary == null && snapshot.auxiliary == null) {
                "An invalidated G waiter installed a physical transport."
            }
            completeCaller(action, binding, entry, control)
            awaitLifecycleFact { entry.terminalWork?.bodyExited() == true && control.receipt.state() !== PersistenceFactoryProcessing.PENDING }
            val work = requireNotNull(entry.terminalWork)
            check(work.closeState() === PersistenceTerminalCall.NOT_INVOKED)
            val expected = if (stage == 0) PersistenceTerminalDisposition.BEFORE_DRIVER else PersistenceTerminalDisposition.TRACKED_DISPOSED
            check(work.disposition() === expected)
            check(control.state().phase === PersistenceOwnedCallerPhase.ABANDONED)
            if (action === Action.INTERRUPTED) {
                check(control.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED)
                check(scope.entries().single() === entry)
            } else {
                awaitLifecycleFact { scope.entries().isEmpty() }
                check(control.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
            }
            println("PG_LIFECYCLE_G_INVALIDATED stage=$stage action=$action native_allocations=0 proof=OWN_PROJECT_MODEL_REAL_ACTORS_DRIVER")
        } finally {
            gate.proceed.countDown()
            control.fail(PersistenceFactoryFailure.CLOSED)
        }
    }

    /** The hand-driven bound caller must observe failure too; neither F1 nor the scanner can write its disposition. */
    private fun completeCaller(
        action: Action,
        binding: PersistencePhysicalFactoryBinding,
        entry: PersistencePhysicalEntry,
        control: PersistenceOwnedCallerControl,
    ) {
        check(control.state() === PersistenceOwnedCallerDisposition.ATTACHED)
        when (action) {
            Action.EXPIRED -> {
                check(persistenceFactoryRemainingMillis(control.budget) == 0L)
                check(control.fail(PersistenceFactoryFailure.TIMEOUT))
            }

            Action.SEALED -> {
                check(binding.isClosed())
                check(control.fail(PersistenceFactoryFailure.CLOSED))
            }

            Action.INTERRUPTED -> awaitLifecycleFact {
                check(binding.offered(entry) == null)
                control.failureResult() != null
            }
        }
    }

    private fun invalidate(action: Action, scope: PgLifecycleTestScope, control: PersistenceOwnedCallerControl, factory: Thread) {
        when (action) {
            Action.EXPIRED -> awaitLifecycleFact { persistenceFactoryRemainingMillis(control.budget) == 0L }
            Action.SEALED -> check(scope.owner.requestShutdown())
            Action.INTERRUPTED -> factory.interrupt()
        }
    }

    private enum class Action { EXPIRED, SEALED, INTERRUPTED }
}
