package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

internal class PgLifecycleDatabaseWitness(val entry: PersistencePhysicalEntry, val primary: PersistenceTransportRecord?)

/** Read-only own-project assertions. Raw existence is observed, but no raw Connection method/getter or production fact write is introduced. */
internal object PgLifecycleDatabaseAssertions {
    fun retain(scope: PgLifecycleTestScope, case: PgLifecycleDatabaseCase, application: String): PgLifecycleDatabaseWitness {
        var witness: PgLifecycleDatabaseWitness? = null
        val binding = scope.binding(case.lane.deleting)
        awaitLifecycleFact {
            binding.ledger.lock.withLock {
                val entry = binding.ledger.entries.filterNotNull().singleOrNull()
                val transport = entry?.transports?.snapshot() as? PersistenceTransportSnapshot.Available
                val primary = transport?.primary?.takeIf(::primaryConstructionReturned)
                val transportReady = case.originalProvider || primary != null
                if (entry != null && entry.openingFacts.driverEntered.get() && transportReady) {
                    admitted(scope, case, application, entry)
                    witness = PgLifecycleDatabaseWitness(entry, primary?.record)
                }
                witness != null
            }
        }
        return requireNotNull(witness)
    }

    private fun primaryConstructionReturned(primary: PersistenceTransportRecordSnapshot): Boolean =
        primary.rawReturned && primary.construction === PersistenceTransportConstruction.RETURNED

    private fun admitted(scope: PgLifecycleTestScope, case: PgLifecycleDatabaseCase, application: String, entry: PersistencePhysicalEntry) {
        val policy = if (case.originalProvider) {
            PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER
        } else if (case.lane.deleting) {
            PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
        } else {
            PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
        }
        check(entry.policy === policy && entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.ACTIVE)
        check(policy.route === if (case.lane.deleting) PersistenceDriverTransportRoute.APPROVED_DIRECT else PersistenceDriverTransportRoute.ORDINARY)
        check(entry.raw.get() == null && !entry.openingFacts.driverEnded.get() && entry.openingFacts.factoryEntered.get())
        check(!entry.openingFacts.factoryEnded.get() && !entry.retirementRequested.get())
        check(entry.control?.state() === PersistenceOwnedCallerDisposition.ATTACHED)
        val opening = requireNotNull(entry.driverOpening)
        check(opening.policy === policy)
        check(lifecycleField(opening, "driver") === scope.root.retainedDriver.forOpening())
        if (case.originalProvider) {
            check(opening.image == null && opening.timer == null && entry.driverScope == null && entry.transports == null)
        } else {
            check(opening.image != null && opening.timer === scope.root.timer)
            check((lifecycleField(requireNotNull(entry.driverScope), "phase") as AtomicReference<*>).get() === PersistencePgScopePhase.ACTIVE)
        }
        preparedSettings(case, application, entry, opening)
    }

    private fun preparedSettings(case: PgLifecycleDatabaseCase, application: String, entry: PersistencePhysicalEntry, opening: PersistencePgDriverOpening) {
        val endpoint = lifecycleField(opening, "endpoint") as ResolvedPersistenceEndpoint
        val properties = endpoint.driverProperties()
        check(properties.getProperty("ApplicationName") == application && properties.getProperty("assumeMinServerVersion") == "17")
        check(properties.getProperty("requireAuth") == "scram-sha-256" && properties.getProperty("scramMaxIterations") == "100000")
        check(properties.getProperty("gssEncMode") == "disable" && properties.getProperty("sslmode") == "disable")
        check(properties.getProperty("loginTimeout") == "0" && properties.getProperty("queryTimeout") == case.queryTimeout.toString())
        check(properties.getProperty("readOnly") == (case.recipe.settings["readOnly"] ?: "false"))
        check(properties.getProperty("readOnlyMode") == case.recipe.settings["readOnlyMode"])
        check(properties.getProperty("preferQueryMode") == (case.recipe.settings["preferQueryMode"] ?: "extended"))
        check(properties.getProperty("binaryTransfer") == case.recipe.settings["binaryTransfer"])
        val types = properties.stringPropertyNames().filter { it.startsWith("datatype.") }.associateWith(properties::getProperty)
        check(types == case.recipe.settings.filterKeys { it.startsWith("datatype.") })
        check(!properties.containsKey("socketFactory"))
        check(properties.getProperty("socketFactoryArg") == if (case.originalProvider) "synthetic-ignored" else null)
        check(!properties.containsKey("authenticationPluginClassName"))
        val role = if (case.roleProbe) if (case.mode === PgLifecycleDatabaseMode.PRIMARY_ROLE) "primary" else "secondary" else null
        check(properties.getProperty("targetServerType") == role)
        check(properties.getProperty("loadBalanceHosts") == if (case.roleProbe) "false" else null)
        check(opening.loginPolicy.durationMillis == case.lane.allowanceMillis)
        check(lifecycleField(requireNotNull(entry.control).budget, "allowanceNanos") == case.lane.allowanceMillis * 1_000_000)
        check(properties.getProperty("connectTimeout") == if (case.lane.deleting) "1" else "2")
        check(properties.getProperty("socketTimeout") == case.socketTimeout.toString())
        check(properties.getProperty("cancelSignalTimeout") == if (case.lane.deleting) "1" else "2")
    }

    fun live(scope: PgLifecycleTestScope, case: PgLifecycleDatabaseCase, witness: PgLifecycleDatabaseWitness, result: PersistenceFactoryResult<*>) {
        check(result is PersistenceFactoryResult.Success && result.value === witness.entry.candidate)
        awaitLifecycleFact { result.receipt.state() !== PersistenceFactoryProcessing.PENDING }
        check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        check(scope.entries(case.lane.deleting).single() === witness.entry)
        check(witness.entry.raw.get() != null && witness.entry.control?.state()?.phase === PersistenceOwnedCallerPhase.TAKEN)
        check(!witness.entry.retirementRequested.get() && !witness.entry.retiring)
        check(witness.entry.terminalWork?.disposition() === PersistenceTerminalDisposition.PENDING)
    }

    fun retired(scope: PgLifecycleTestScope, case: PgLifecycleDatabaseCase, witness: PgLifecycleDatabaseWitness, receipt: PersistenceFactoryReceipt) {
        val entry = witness.entry
        val binding = scope.binding(case.lane.deleting)
        awaitLifecycleFact { scope.entries(case.lane.deleting).isEmpty() && receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED }
        val work = requireNotNull(entry.terminalWork)
        val expected = when {
            !case.originalProvider -> PersistenceTerminalDisposition.TRACKED_DISPOSED
            case.returnsRaw -> PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED
            else -> PersistenceTerminalDisposition.NO_RAW_DRIVER_RETURN_ONLY
        }
        check(work.bodyExited() && work.producerDrainProven() && work.disposition() === expected)
        check(!work.hasCleanupFailure() && !work.hasFatalFailure() && !work.failedTimerWorkEnded())
        check((entry.raw.get() != null) == case.returnsRaw)
        val call = if (case.returnsRaw) PersistenceTerminalCall.RETURNED else PersistenceTerminalCall.NOT_INVOKED
        check(work.closeState() === call && (lifecycleField(work, "abort") as AtomicReference<*>).get() === call)
        val boundary = work.acknowledgedBoundary()
        if (case.originalProvider) {
            check(boundary == null && entry.transports == null && witness.primary == null)
            check(binding.completion.weakEvidence.get() && binding.completion.unprovedProvider.get() == !case.returnsRaw)
        } else {
            requireAcknowledgedTimerBoundary(boundary)
        }
        openingEnded(entry, case.returnsRaw)
        binding.rendezvous.lock.withLock {
            binding.ledger.lock.withLock {
                check(binding.ledger.current(entry.record) == null && entry.scopeEnded && entry.decisionDelivered && entry.retiring)
                check(entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.terminal === work.claim)
                val attempt = requireNotNull(entry.attempt)
                check(attempt.workerSettled && !attempt.unresolved && attempt.receipt === receipt && entry.control?.receipt === receipt)
                check(attempt.input === entry.record && binding.rendezvous.current !== attempt)
            }
        }
        if (!case.originalProvider) transportDisposed(entry, witness, requireNotNull(boundary))
        check(!entry.candidate.requestRetirement())
        println(
            "PG_DATABASE_TERMINAL ${case.label} raw=${case.returnsRaw} abort=$call close=$call boundary_ack=${boundary != null} " +
                "disposition=$expected scope_ended=true processing=PROCESSING_ENDED body_exited=true removed=true proof=REAL_COMPOSITION",
        )
    }

    private fun requireAcknowledgedTimerBoundary(boundary: PersistenceTimerBoundary?) {
        val observation = requireNotNull(boundary).observation()
        check(boundary.acknowledged() && observation.acknowledgement)
        check(observation.scheduling.entered && observation.scheduling.extentEnded && observation.scheduling.outcome === PersistenceTimerOutcome.RETURNED)
    }

    private fun openingEnded(entry: PersistencePhysicalEntry, returnedRaw: Boolean) {
        val facts = entry.openingFacts
        check(facts.factoryEntered.get() && facts.factoryEnded.get() && facts.driverEntered.get() && facts.driverEnded.get())
        check(facts.scopeCallEnded.get() && !facts.fatal.get())
        check(facts.outcome.get() === if (returnedRaw) PersistencePhysicalOpening.RETAINED else PersistencePhysicalOpening.FAILED)
        if (entry.policy === PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER) {
            check(entry.driverScope == null && entry.transports == null)
        } else {
            val scope = requireNotNull(entry.driverScope)
            check(scope.extentSource.primaryOpeningEnded.get())
            check((lifecycleField(scope, "phase") as AtomicReference<*>).get() === PersistencePgScopePhase.ENDED)
        }
    }

    private fun transportDisposed(entry: PersistencePhysicalEntry, witness: PgLifecycleDatabaseWitness, boundary: PersistenceTimerBoundary) {
        val transports = requireNotNull(entry.transports)
        val owner = lifecycleField(transports, "owner") as PersistenceTransportOwner<*>
        check(owner.terminalState(entry.driverScope?.extentSource, boundary, false) === PersistenceTerminalTransportState.DISPOSED)
        val state = transports.snapshot() as PersistenceTransportSnapshot.Available
        check(state.sealed && !state.revisionExhausted && witness.primary != null && state.primary?.record === witness.primary)
        // readOnly constructor-failure cleanup can attempt AUX cancellation even though no query-timeout task was enqueued.
        listOfNotNull(state.primary, state.auxiliary).forEach { resource ->
            check(resource.rawReturned && resource.construction === PersistenceTransportConstruction.RETURNED && !resource.unknown)
            check(resource.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED && resource.businessSealed && resource.allCallsSealed)
            check(resource.activeBusiness == 0 && resource.activeObservations == 0)
        }
    }

    fun shutdown(scope: PgLifecycleTestScope, case: PgLifecycleDatabaseCase) {
        check(scope.owner.requestShutdown())
        val expected = if (case.originalProvider) {
            PersistenceLifecycleObservation.DRIVER_CONTRACT_ONLY_ENDED
        } else {
            PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED
        }
        check(scope.owner.observeShutdown() === expected)
        val state = scope.owner.snapshot()
        check(state.weakEvidenceUsed == case.originalProvider && !state.cleanupFailureObserved && state.ordinaryRetained == 0 && state.deletionRetained == 0)
        check(scope.binding().completion.unprovedProvider.get() == (case.originalProvider && !case.returnsRaw))
        check(!scope.binding(true).completion.unprovedProvider.get())
        check(scope.actors().all { it.termination().ended() && !it.thread.isAlive })
    }
}
