package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** MODEL JDBC faults after a real managed LIVE. The actual terminal runner, not a fixture, produces every completion fact. */
internal object PgLifecycleTerminalFaultCases {
    fun verify(mode: PgLifecycleCase) {
        val weak = mode === PgLifecycleCase.MODEL_WEAK_ABORT_FAILURE || mode === PgLifecycleCase.MODEL_WEAK_CLOSE_FAILURE
        val closing = mode === PgLifecycleCase.MODEL_WEAK_CLOSE_FAILURE || mode === PgLifecycleCase.MODEL_STRONG_CLOSE_FAILURE ||
            mode === PgLifecycleCase.MODEL_STRONG_CLOSE_FATAL
        val fatal = mode === PgLifecycleCase.MODEL_STRONG_ABORT_FATAL || mode === PgLifecycleCase.MODEL_STRONG_CLOSE_FATAL
        PgLifecyclePeer().use { peer ->
            peer.start()
            val extras = if (weak) mapOf("socketFactoryArg" to "synthetic-ignored") else emptyMap()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port, extras)).use { scope ->
                scope.start()
                val result = scope.request()
                val entry = scope.entries().single()
                val work = requireNotNull(entry.terminalWork)
                val actual = requireNotNull(entry.raw.get())
                val fault = JdbcFault(actual, scope.binding(), entry, closing, fatal)
                // Own-project MODEL substitution only, before retirement. No production provider or raw getter is added.
                check(entry.raw.compareAndSet(actual, fault))
                scope.expectedUnknown = true
                try {
                    check(result.value.requestRetirement())
                    check(fault.entered.await(5, TimeUnit.SECONDS))
                    pending(scope, entry, fault, closing)
                    fault.proceed.countDown()
                    awaitLifecycleFact { work.bodyExited() }
                    val retained = fatal || (weak && closing)
                    if (!retained) awaitLifecycleFact { scope.entries().isEmpty() }
                    val expected = when {
                        retained -> PersistenceTerminalDisposition.UNKNOWN_ENDED
                        weak -> PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED
                        else -> PersistenceTerminalDisposition.TRACKED_DISPOSED
                    }
                    check(work.disposition() === expected && work.hasCleanupFailure() && work.hasFatalFailure() == fatal)
                    check(work.closeState() === if (closing) PersistenceTerminalCall.THREW else PersistenceTerminalCall.RETURNED)
                    check(abortState(work) === if (closing) PersistenceTerminalCall.RETURNED else PersistenceTerminalCall.THREW)
                    check(fault.aborts.get() == 1 && fault.closes.get() == 1)
                    check(entry.scopeEnded && entry.openingFacts.driverEnded.get() && work.producerDrainProven())
                    check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
                    check(scope.entries().any { it === entry } == retained)
                    check(scope.binding().completion.cleanupFailure.get())
                    if (weak) {
                        check(work.acknowledgedBoundary() == null && entry.transports == null)
                        check(scope.binding().completion.weakEvidence.get() == !retained)
                    } else {
                        check(work.acknowledgedBoundary() != null && entry.transports != null)
                    }
                    peer.verify()
                    println("PG_LIFECYCLE_TERMINAL_FAULT mode=$mode disposition=$expected retained=$retained proof=MODEL_FAULT_REAL_TERMINAL")
                } finally {
                    fault.proceed.countDown()
                }
            }
        }
    }

    private fun pending(scope: PgLifecycleTestScope, entry: PersistencePhysicalEntry, fault: JdbcFault, closing: Boolean) {
        val work = requireNotNull(entry.terminalWork)
        check(work.disposition() === PersistenceTerminalDisposition.PENDING && !work.bodyExited())
        check(entry.decisionDelivered && entry.retiring && scope.entries().single() === entry)
        check(work.closeState() === if (closing) PersistenceTerminalCall.RUNNING else PersistenceTerminalCall.NOT_INVOKED)
        check(abortState(work) === if (closing) PersistenceTerminalCall.RETURNED else PersistenceTerminalCall.RUNNING)
        check(fault.aborts.get() == 1 && fault.closes.get() == if (closing) 1 else 0)
        check(!scope.binding().completion.scanReclamation(entry.record.slotHint))
        check(scope.entries().single() === entry)
    }

    private fun abortState(work: PersistenceTerminalWork): PersistenceTerminalCall =
        (lifecycleField(work, "abort") as java.util.concurrent.atomic.AtomicReference<*>).get() as PersistenceTerminalCall

    /** Only the owned test changes this reference. Both real driver operations still execute exactly once. */
    private class JdbcFault(
        private val actual: Connection,
        private val binding: PersistencePhysicalFactoryBinding,
        private val entry: PersistencePhysicalEntry,
        private val closing: Boolean,
        private val fatal: Boolean,
    ) : Connection by actual {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val aborts = AtomicInteger()
        val closes = AtomicInteger()

        override fun abort(executor: Executor?) {
            check(aborts.incrementAndGet() == 1)
            invokeFault(!closing) { actual.abort(executor) }
        }

        override fun close() {
            check(closes.incrementAndGet() == 1)
            invokeFault(closing) { actual.close() }
        }

        private fun invokeFault(selected: Boolean, actualCall: () -> Unit) {
            check(entry.terminalWork?.isRunnerThread() == true)
            check(!binding.ledger.lock.isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread)
            check(entry.transports?.ownershipLockHeld() != true)
            if (selected) {
                entered.countDown()
                check(proceed.await(12, TimeUnit.SECONDS)) { "Synthetic terminal fault gate was not released." }
            }
            actualCall()
            if (selected) {
                if (fatal) throw AssertionError("Synthetic terminal MODEL fatal after real driver call.")
                throw SQLException("Synthetic terminal MODEL failure after real driver call.")
            }
        }
    }
}
