package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

internal class PgLifecycleTlsWitness(val entry: PersistencePhysicalEntry, val primary: PersistenceTransportRecord)

/** Read-only own-project evidence. No raw JDBC operation, invented lifecycle ACK, or writable production seam. */
internal object PgLifecycleTlsAssertions {
    fun admitted(scope: PgLifecycleTestScope, deletion: Boolean, rootCertificate: Path): PgLifecycleTlsWitness {
        val binding = scope.binding(deletion)
        return binding.ledger.lock.withLock {
            val entry = binding.ledger.entries.filterNotNull().single()
            val expected = if (deletion) {
                PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
            } else {
                PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
            }
            check(entry.policy === expected && entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.ACTIVE)
            check(entry.policy.route === if (deletion) PersistenceDriverTransportRoute.APPROVED_DIRECT else PersistenceDriverTransportRoute.ORDINARY)
            check(entry.raw.get() == null && entry.openingFacts.driverEntered.get() && !entry.openingFacts.driverEnded.get())
            check(entry.openingFacts.factoryEntered.get() && !entry.openingFacts.factoryEnded.get())
            check(!entry.retirementRequested.get() && entry.control?.state() === PersistenceOwnedCallerDisposition.ATTACHED)
            val opening = requireNotNull(entry.driverOpening)
            check(opening.policy === expected && opening.image != null && opening.timer === scope.root.timer)
            check(lifecycleField(opening, "driver") === scope.root.retainedDriver.forOpening())
            settings(opening, requireNotNull(entry.control), deletion, rootCertificate)
            val transport = requireNotNull(entry.transports).snapshot() as PersistenceTransportSnapshot.Available
            check(transport.auxiliary == null)
            PgLifecycleTlsWitness(entry, requireNotNull(transport.primary).record)
        }
    }

    private fun settings(opening: PersistencePgDriverOpening, control: PersistenceOwnedCallerControl, deletion: Boolean, rootCertificate: Path) {
        val endpoint = lifecycleField(opening, "endpoint") as ResolvedPersistenceEndpoint
        val properties = endpoint.driverProperties()
        check(properties.getProperty("sslmode") == "verify-full" && properties.getProperty("sslNegotiation") == "postgres")
        check(properties.getProperty("sslfactory") == "org.postgresql.ssl.LibPQFactory" && !properties.containsKey("sslhostnameverifier"))
        check(properties.getProperty("sslrootcert") == rootCertificate.toString() && rootCertificate.isAbsolute)
        check(properties.getProperty("sslcert") == "" && properties.getProperty("sslkey") == "" && !properties.containsKey("sslpasswordcallback"))
        check(properties.getProperty("requireAuth") == "password" && properties.getProperty("gssEncMode") == "disable")
        check(properties.getProperty("channelBinding") == "disable" && properties.getProperty("loginTimeout") == "0")
        check(!properties.containsKey("socketFactory") && !properties.containsKey("socketFactoryArg"))
        check(properties.getProperty("queryTimeout") == "0" && properties.getProperty("readOnly") == "false")
        val allowance = if (deletion) 2_000L else 6_000L
        check(opening.loginPolicy.durationMillis == allowance)
        check(lifecycleField(control.budget, "allowanceNanos") == allowance * 1_000_000)
        check(properties.getProperty("connectTimeout") == if (deletion) "1" else "2")
        check(properties.getProperty("socketTimeout") == if (deletion) "2" else "3")
        check(properties.getProperty("cancelSignalTimeout") == if (deletion) "1" else "2")
    }

    fun retired(scope: PgLifecycleTestScope, deletion: Boolean, witness: PgLifecycleTlsWitness, receipt: PersistenceFactoryReceipt, raw: Boolean) {
        val entry = witness.entry
        val binding = scope.binding(deletion)
        awaitLifecycleFact { scope.entries(deletion).isEmpty() && receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED }
        val work = requireNotNull(entry.terminalWork)
        check(work.bodyExited() && work.producerDrainProven() && work.disposition() === PersistenceTerminalDisposition.TRACKED_DISPOSED)
        check(!work.hasCleanupFailure() && !work.hasFatalFailure())
        check((entry.raw.get() != null) == raw)
        val call = if (raw) PersistenceTerminalCall.RETURNED else PersistenceTerminalCall.NOT_INVOKED
        check(work.closeState() === call && (lifecycleField(work, "abort") as AtomicReference<*>).get() === call)
        val boundary = requireNotNull(work.acknowledgedBoundary())
        val observation = boundary.observation()
        check(boundary.acknowledged() && observation.acknowledgement)
        check(observation.scheduling.entered && observation.scheduling.extentEnded && observation.scheduling.outcome === PersistenceTimerOutcome.RETURNED)
        check(!work.failedTimerWorkEnded())
        openingAndProcessingEnded(binding, entry, work, receipt, raw)
        transportDisposed(witness, boundary)
        check(!entry.candidate.requestRetirement())
    }

    private fun openingAndProcessingEnded(
        binding: PersistencePhysicalFactoryBinding,
        entry: PersistencePhysicalEntry,
        work: PersistenceTerminalWork,
        receipt: PersistenceFactoryReceipt,
        raw: Boolean,
    ) {
        val facts = entry.openingFacts
        check(facts.factoryEntered.get() && facts.factoryEnded.get() && facts.driverEntered.get() && facts.driverEnded.get())
        check(facts.scopeCallEnded.get() && !facts.fatal.get())
        check(facts.outcome.get() === if (raw) PersistencePhysicalOpening.RETAINED else PersistencePhysicalOpening.FAILED)
        binding.rendezvous.lock.withLock {
            binding.ledger.lock.withLock {
                check(binding.ledger.current(entry.record) == null && entry.scopeEnded && entry.decisionDelivered && entry.retiring)
                check(entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.terminal === work.claim)
                val attempt = requireNotNull(entry.attempt)
                check(attempt.workerSettled && !attempt.unresolved && attempt.receipt === receipt && entry.control?.receipt === receipt)
                check(attempt.input === entry.record && binding.rendezvous.current !== attempt)
            }
        }
    }

    private fun transportDisposed(witness: PgLifecycleTlsWitness, boundary: PersistenceTimerBoundary) {
        val entry = witness.entry
        val transports = requireNotNull(entry.transports)
        val owner = lifecycleField(transports, "owner") as PersistenceTransportOwner<*>
        check(owner.terminalState(entry.driverScope?.extentSource, boundary, false) === PersistenceTerminalTransportState.DISPOSED)
        val state = transports.snapshot() as PersistenceTransportSnapshot.Available
        val primary = requireNotNull(state.primary)
        check(state.sealed && !state.revisionExhausted && state.auxiliary == null && primary.record === witness.primary)
        check(primary.rawReturned && primary.construction === PersistenceTransportConstruction.RETURNED)
        check(primary.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED && primary.businessSealed && primary.allCallsSealed)
        check(primary.activeBusiness == 0 && primary.activeObservations == 0)
    }

    fun shutdown(scope: PgLifecycleTestScope) {
        check(scope.owner.requestShutdown())
        check(scope.owner.observeShutdown() === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED)
        val state = scope.owner.snapshot()
        check(!state.weakEvidenceUsed && !state.cleanupFailureObserved && state.ordinaryRetained == 0 && state.deletionRetained == 0)
        check(scope.actors().all { it.termination().ended() && !it.thread.isAlive })
    }
}
