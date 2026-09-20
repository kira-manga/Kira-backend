package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/** One inert MODEL AUX on the real owner. This is not a native AUX or literal Socket.close failure claim. */
internal object PgLifecycleAuxiliaryFaultCases {
    fun verify() {
        PgLifecyclePeer().use { peer ->
            peer.start()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port)).use { scope ->
                scope.start()
                val result = scope.request()
                val entry = scope.entries().single()
                val binding = scope.binding()
                val transports = requireNotNull(entry.transports)
                val owner = lifecycleField(transports, "owner") as PersistenceTransportOwner<*>
                val before = owner.snapshot() as PersistenceTransportSnapshot.Available
                check(before.auxiliary == null && before.primary != null)
                val source = requireNotNull(entry.driverScope).extentSource
                val ticket = owner.prepareBoundConstruction(PersistenceTransportExtent(source, PersistenceTransportRole.AUX_CANCEL))
                val fault = ModelAuxiliary(owner, binding, entry, ticket.record)
                scope.expectedUnknown = true
                try {
                    // Reflection is confined to this erased own-project MODEL type. The real PRIMARY and owner are never replaced.
                    binding.ledger.lock.withLock {
                        check(invokeOwner(owner, "prepareBoundReservation", ticket) == null)
                        check(invokeOwner(owner, "reserveBoundConstruction", ticket) == null)
                    }
                    val constructor: (PersistenceTransportRecord) -> AutoCloseable = { record ->
                        check(record === fault.record && !binding.ledger.lock.isHeldByCurrentThread && !owner.ownershipLockHeld())
                        fault
                    }
                    val created = invokeOwner(owner, "constructReserved", ticket, constructor) as PersistenceTransportCreation.Created<*>
                    check(created.record === fault.record && created.resource === fault)
                    check((owner.snapshot() as PersistenceTransportSnapshot.Available).primary?.record === before.primary.record)
                    check(result.value.requestRetirement())
                    check(fault.entered.await(5, TimeUnit.SECONDS))
                    val work = requireNotNull(entry.terminalWork)
                    val pending = owner.snapshot() as PersistenceTransportSnapshot.Available
                    check(pending.auxiliary?.firstClose === PersistenceTransportClosePhase.RUNNING)
                    check(work.disposition() === PersistenceTerminalDisposition.PENDING && !work.bodyExited())
                    check(work.closeState() === PersistenceTerminalCall.NOT_INVOKED && scope.entries().single() === entry)
                    fault.proceed.countDown()
                    awaitLifecycleFact { work.bodyExited() }
                    val ended = owner.snapshot() as PersistenceTransportSnapshot.Available
                    check(ended.auxiliary?.firstClose === PersistenceTransportClosePhase.FAILED && fault.calls.get() == 1)
                    check(ended.primary?.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED)
                    check(work.disposition() === PersistenceTerminalDisposition.UNKNOWN_ENDED && work.acknowledgedBoundary() != null)
                    check(work.closeState() === PersistenceTerminalCall.RETURNED && work.producerDrainProven())
                    check(entry.scopeEnded && result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
                    check(scope.entries().single() === entry)
                    binding.ledger.lock.withLock {
                        check(transports.terminalStateLocked(work) === PersistenceTerminalTransportState.FAILED_ENDED)
                    }
                    peer.verify()
                    println("PG_LIFECYCLE_AUX_FAULT retained_unknown=true primary_disposed=true proof=MODEL_AUX_REAL_TERMINAL")
                } finally {
                    fault.proceed.countDown()
                }
            }
        }
    }

    private fun invokeOwner(owner: PersistenceTransportOwner<*>, name: String, vararg arguments: Any): Any? =
        owner.javaClass.declaredMethods.single { it.name == name }.invoke(owner, *arguments)

    /** Ordinary first-close protocol, driven by TerminalWork itself. It owns no native resource to strand on failure. */
    private class ModelAuxiliary(
        private val owner: PersistenceTransportOwner<*>,
        private val binding: PersistencePhysicalFactoryBinding,
        private val entry: PersistencePhysicalEntry,
        val record: PersistenceTransportRecord,
    ) : AutoCloseable {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val calls = AtomicInteger()

        override fun close() {
            val claim = invokeOwner(owner, "claimClose", record, this) as? PersistenceTransportCloseClaim ?: return
            check(entry.terminalWork?.isRunnerThread() == true && calls.incrementAndGet() == 1)
            check(!binding.ledger.lock.isHeldByCurrentThread && !binding.rendezvous.lock.isHeldByCurrentThread && !owner.ownershipLockHeld())
            val outcome = runCatching {
                entered.countDown()
                check(proceed.await(12, TimeUnit.SECONDS))
                throw IOException("Synthetic inert AUX MODEL first-close failure.")
            }
            check(owner.completeClose(claim, outcome.isSuccess))
            outcome.getOrThrow()
        }
    }
}
