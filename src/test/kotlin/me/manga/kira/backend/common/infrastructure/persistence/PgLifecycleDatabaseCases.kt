package me.manga.kira.backend.common.infrastructure.persistence

/** Actual retained owner and factory. The parent, never this JVM, witnesses PostgreSQL session presence/removal. */
internal object PgLifecycleDatabaseCases {
    fun verify(case: PgLifecycleDatabaseCase, port: Int, application: String, handshake: PgLifecycleDatabaseHandshake) {
        PgLifecycleTestScope(PgLifecycleDatabaseSettings.endpoint(case, port, application), capacity = 1).use { scope ->
            scope.start()
            if (case.originalProvider) unavailableDeletion(scope)
            if (case.lane.deleting) scope.prepareDeletion()
            check(scope.owner.snapshot().timerReady && !scope.owner.snapshot().weakEvidenceUsed)
            val actors = scope.actors()
            val driver = scope.root.retainedDriver.forOpening()
            handshake.publish(PgLifecycleDatabasePhase.PREPARED)
            var previous: PgLifecycleDatabaseWitness? = null
            repeat(case.attempts) { ordinal ->
                PgLifecycleDatabaseDiagnostics.childStage(case, ordinal, application, PgLifecycleDatabaseChildStage.WAIT_START)
                handshake.await(PgLifecycleDatabasePhase.START, ordinal)
                PgLifecycleDatabaseDiagnostics.childStage(case, ordinal, application, PgLifecycleDatabaseChildStage.WAIT_RECEIVER)
                awaitLifecycleFact { scope.binding(case.lane.deleting).isOwnedReceiverReady() }
                PgLifecycleDatabaseRequest(scope, case, application, ordinal).use { request ->
                    PgLifecycleDatabaseDiagnostics.childStage(case, ordinal, application, PgLifecycleDatabaseChildStage.START_CALLER)
                    request.start()
                    PgLifecycleDatabaseDiagnostics.childStage(case, ordinal, application, PgLifecycleDatabaseChildStage.WAIT_RETAIN)
                    val witness = PgLifecycleDatabaseAssertions.retain(scope, case, application, request.original)
                    PgLifecycleDatabaseDiagnostics.childStage(case, ordinal, application, PgLifecycleDatabaseChildStage.RETAINED)
                    if (previous != null) reuseIdentity(requireNotNull(previous), witness)
                    val result = openingResult(scope, case, application, witness, request, handshake, ordinal)
                    val receipt = resultReceipt(case, result)
                    if (case.succeeds) {
                        PgLifecycleDatabaseAssertions.live(scope, case, witness, result)
                        handshake.publish(PgLifecycleDatabasePhase.LIVE, ordinal)
                        if (previous != null) {
                            requireReuse(scope, case, requireNotNull(previous), witness)
                            handshake.publish(PgLifecycleDatabasePhase.STALE_REJECTED, ordinal)
                        }
                        handshake.await(PgLifecycleDatabasePhase.RETIRE, ordinal, PgLifecycleDatabaseDeadline(20_000))
                        PgLifecycleDatabaseAssertions.live(scope, case, witness, result)
                        check(witness.entry.candidate.requestRetirement())
                    }
                    PgLifecycleDatabaseAssertions.retired(scope, case, witness, receipt)
                    check(scope.root.retainedDriver.forOpening() === driver && scope.actors() == actors)
                    handshake.publish(PgLifecycleDatabasePhase.RETIRED, ordinal)
                    val next = if (case.originalProvider && !case.returnsRaw) {
                        PgLifecycleDatabasePhase.WEAK_CLEANUP_CONFIRMED
                    } else {
                        PgLifecycleDatabasePhase.ABSENCE_CONFIRMED
                    }
                    handshake.await(next, ordinal)
                    previous = witness
                }
            }
            PgLifecycleDatabaseAssertions.shutdown(scope, case)
            handshake.publish(PgLifecycleDatabasePhase.OWNER_DRAINED)
            handshake.await(PgLifecycleDatabasePhase.EXIT)
        }
    }

    private fun resultReceipt(case: PgLifecycleDatabaseCase, result: PersistenceFactoryResult<PersistenceJdbcCandidate>): PersistenceFactoryReceipt {
        if (case.succeeds) {
            check(result is PersistenceFactoryResult.Success) { "Expected the matrix's accepted S outcome, not timeout/refusal/failure." }
            return result.receipt
        }
        val expected = if (case.deadlineFailure) PersistenceFactoryFailure.TIMEOUT else PersistenceFactoryFailure.CREATE_FAILED
        check(result is PersistenceFactoryResult.Failed && result.reason === expected) {
            "Expected this exact accepted F/T outcome, not a different failure, refusal or success."
        }
        return result.receipt
    }

    /** The strong deadline vectors hold existing G: explicit MODEL pre-transport fence, never an injected driver-return callback. */
    private fun openingResult(
        scope: PgLifecycleTestScope,
        case: PgLifecycleDatabaseCase,
        application: String,
        witness: PgLifecycleDatabaseWitness,
        request: PgLifecycleDatabaseRequest,
        handshake: PgLifecycleDatabaseHandshake,
        ordinal: Int,
    ): PersistenceFactoryResult<PersistenceJdbcCandidate> {
        val entry = witness.entry
        val lock = scope.binding(case.lane.deleting).ledger.lock
        val modelGate = case.deadlineFailure && !case.originalProvider
        var originalResult: PersistenceFactoryResult<PersistenceJdbcCandidate>? = null
        if (modelGate) lock.lock()
        try {
            if (modelGate) check(entry.raw.get() == null && !entry.openingFacts.driverEnded.get() && !entry.retirementRequested.get())
            // This retained Entry survives automatic reclamation of short-lived constructor failures.
            handshake.publish(PgLifecycleDatabasePhase.RETAINED, ordinal)
            if (case.supplemental) {
                handshake.await(PgLifecycleDatabasePhase.ARRIVAL_CONFIRMED, ordinal)
                val minimum = when {
                    case.mode === PgLifecycleDatabaseMode.SOCKET_TIMEOUT -> 1_250L
                    case.deadlineFailure -> if (case.lane.deleting) 1_250L else 4_000L
                    else -> 500L
                }
                check(persistenceFactoryRemainingMillis(requireNotNull(entry.control).budget) >= minimum)
                check(entry.raw.get() == null && !entry.openingFacts.driverEnded.get())
                handshake.publish(PgLifecycleDatabasePhase.FAULT_ARMED, ordinal)
            }
            val result = request.result()
            originalResult = result
            if (!modelGate) PgLifecycleDatabaseDiagnostics.result(case, ordinal, application, scope, witness, result)
            resultReceipt(case, result)
            if (case.deadlineFailure) {
                deadlineActive(scope, case, entry, handshake, ordinal, modelGate)
            } else if (!case.succeeds) {
                val remaining = persistenceFactoryRemainingMillis(requireNotNull(entry.control).budget)
                check(remaining > 0L) { "F arrived after the original caller budget." }
                println("PG_DATABASE_FAILURE ${case.label} original_remaining_ms=$remaining result=CREATE_FAILED raw=false proof=REAL_COMPOSITION")
            }
            if (case.lateReturn) lateRaw(scope, case, entry, handshake, ordinal, modelGate)
            return result
        } finally {
            if (modelGate) {
                lock.unlock() // Every assertion/phase failure releases this MODEL cut before request/scope cleanup.
                originalResult?.let { result ->
                    PgLifecycleDatabaseDiagnostics.safely {
                        println("PG_DATABASE_DIAGNOSTIC_CUT ${case.label} ordinal=$ordinal observation=POST_MODEL_G_UNLOCK original_result_retained=true")
                        PgLifecycleDatabaseDiagnostics.result(case, ordinal, application, scope, witness, result)
                    }
                }
            }
        }
    }

    private fun deadlineActive(
        scope: PgLifecycleTestScope,
        case: PgLifecycleDatabaseCase,
        entry: PersistencePhysicalEntry,
        handshake: PgLifecycleDatabaseHandshake,
        ordinal: Int,
        modelGate: Boolean,
    ) {
        val control = requireNotNull(entry.control)
        check(persistenceFactoryRemainingMillis(control.budget) == 0L && control.state().phase === PersistenceOwnedCallerPhase.ABANDONED)
        check(entry.raw.get() == null && entry.openingFacts.driverEntered.get() && !entry.openingFacts.driverEnded.get())
        check(control.receipt.state() === PersistenceFactoryProcessing.PENDING)
        if (modelGate) {
            check(scope.binding(case.lane.deleting).ledger.lock.isHeldByCurrentThread && !entry.retiring)
            var transport: PersistenceTransportSnapshot.Available? = null
            awaitLifecycleFact {
                transport = requireNotNull(entry.transports).snapshot() as? PersistenceTransportSnapshot.Available
                transport != null
            }
            val conclusive = requireNotNull(transport)
            check(conclusive.primary?.firstClose === PersistenceTransportClosePhase.NOT_STARTED && !conclusive.sealed)
        }
        check(entry.raw.get() == null && !entry.openingFacts.driverEnded.get())
        val cut = if (modelGate) "OWN_PROJECT_MODEL_G_PRE_TRANSPORT_FENCE" else "NO_MODEL_GATE_ORIGINAL_PROVIDER"
        println("PG_DATABASE_DEADLINE ${case.label} original_remaining_ms=0 driver_active=true raw=false caller=TIMEOUT cut=$cut")
        handshake.publish(PgLifecycleDatabasePhase.DEADLINE_DRIVER_ACTIVE, ordinal)
        handshake.await(PgLifecycleDatabasePhase.DEADLINE_OBSERVED, ordinal)
    }

    private fun lateRaw(
        scope: PgLifecycleTestScope,
        case: PgLifecycleDatabaseCase,
        entry: PersistencePhysicalEntry,
        handshake: PgLifecycleDatabaseHandshake,
        ordinal: Int,
        modelGate: Boolean,
    ) {
        // The parent releases actual incomplete PostgreSQL Ready bytes only after DEADLINE_DRIVER_ACTIVE and positive session observation.
        awaitLifecycleFact { entry.raw.get() != null && entry.openingFacts.driverEnded.get() }
        check(entry.control?.state()?.phase === PersistenceOwnedCallerPhase.ABANDONED)
        if (modelGate) {
            check(scope.binding(case.lane.deleting).ledger.lock.isHeldByCurrentThread)
            check(entry.terminalWork?.closeState() === PersistenceTerminalCall.NOT_INVOKED && !entry.openingFacts.factoryEnded.get())
        }
        handshake.publish(PgLifecycleDatabasePhase.LATE_RAW_RETAINED, ordinal)
        val cut = if (modelGate) "OWN_PROJECT_MODEL_G_PRE_TRANSPORT_FENCE" else "NO_MODEL_GATE_ORIGINAL_PROVIDER"
        println("PG_DATABASE_LATE_RAW ${case.label} deadline_before_driver_return=true caller=TIMEOUT delivered=false cut=$cut proof=REAL_DRIVER_REAL_SERVER")
    }

    private fun unavailableDeletion(scope: PgLifecycleTestScope) {
        check(scope.owner.prepareDeletion() === PersistenceLifecycleActivation.STARTED)
        check(scope.owner.observeDeletionPreparation() === PersistenceLifecycleObservation.UNAVAILABLE)
        check(scope.owner.requestDeletion() is PersistenceFactoryResult.Refused)
        check(scope.owner.prepareDeletion() === PersistenceLifecycleActivation.ALREADY_CLAIMED)
        check(scope.entries(true).isEmpty() && scope.owner.snapshot().ordinaryReady && !scope.owner.snapshot().deletionReady)
        println("PG_DATABASE_ORIGINAL_DELETION preparation=UNAVAILABLE request=REFUSED entries=0")
    }

    private fun reuseIdentity(previous: PgLifecycleDatabaseWitness, current: PgLifecycleDatabaseWitness) {
        check(previous.entry !== current.entry && previous.entry.record !== current.entry.record)
        check(previous.entry.record.slotHint == current.entry.record.slotHint && previous.entry.terminalWork !== current.entry.terminalWork)
        check(!previous.entry.candidate.requestRetirement() && !current.entry.retirementRequested.get())
    }

    private fun requireReuse(
        scope: PgLifecycleTestScope,
        case: PgLifecycleDatabaseCase,
        previous: PgLifecycleDatabaseWitness,
        current: PgLifecycleDatabaseWitness,
    ) {
        check(previous.entry !== current.entry && previous.entry.record !== current.entry.record)
        check(previous.entry.record.slotHint == current.entry.record.slotHint)
        check(previous.entry.terminalWork !== current.entry.terminalWork)
        check(!previous.entry.candidate.requestRetirement()) // Issue the stale alias only after the successor is actually LIVE.
        check(!current.entry.retirementRequested.get() && scope.entries(case.lane.deleting).single() === current.entry)
    }
}
