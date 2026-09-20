package me.manga.kira.backend.common.infrastructure.persistence

/** Admission scheduling composes with pending mode's MODEL G/T query; every independent cleanup is attempted after failures. */
internal class PgLifecycleTrackedWeakNoRawFixture(private val mode: PgLifecycleCase, private val count: Int) : AutoCloseable {
    val peer = PgLifecyclePeer(count, PgLifecyclePeerMode.HOLD_REFUSE)
    val capture = PgLifecycleTrackedWeakNoRawCapture()
    private var managed: PgLifecycleTestScope? = null
    val scope: PgLifecycleTestScope get() = requireNotNull(managed)
    private val callers = arrayOfNulls<PgLifecycleTrackedWeakNoRawCaller>(count)
    private var pendingCall: PgLifecycleTrackedWeakNoRawPendingCall? = null
    private var failedConstruction: PgLifecycleTrackedWeakNoRawFailedConstruction? = null
    private var admission: PgLifecycleAdmissionScheduling? = null
    private var witness: PgLifecycleTrackedWeakNoRawWitness? = null
    val pendingWitness: PgLifecycleTrackedWeakNoRawWitness get() = requireNotNull(witness)

    init {
        require(count in 1..2)
    }

    fun start() {
        peer.start()
        managed = PgLifecycleTestScope(pgProbeEndpoint(peer.port))
        val scheduling = PgLifecycleAdmissionScheduling(scope)
        admission = scheduling // Own before installation/start, including failed publication or pause waits.
        if (mode === PgLifecycleCase.MODEL_TRACKED_WEAK_NO_RAW_PENDING_CALL) {
            val retained = PgLifecycleTrackedWeakNoRawWitness(scope.binding(), scheduling)
            witness = retained // Own the inert witness before prestart installation, including partial-install failure.
            installLifecycleModelLock(scope, retained)
            retained.requireInstalled()
        } else {
            installLifecycleModelLock(scope, PgLifecycleAdmissionLock(scheduling))
        }
        capture.acquireAndHold()
        scope.start(waitTimer = false)
        capture.awaitPending(scope)
    }

    fun startAttempt(ordinal: Int): PgLifecycleTrackedWeakNoRawAttempt {
        check(ordinal in callers.indices && callers[ordinal] == null)
        if (ordinal > 0) requireNotNull(callers[ordinal - 1]).close()
        awaitLifecycleFact { scope.binding().isOwnedReceiverReady() }
        capture.requirePending(scope)
        val caller = PgLifecycleTrackedWeakNoRawCaller(scope, ordinal)
        callers[ordinal] = caller // Retained even if Thread.start or any following assertion fails.
        requireNotNull(admission).during("mode=$mode ordinal=$ordinal") {
            caller.start()
            PgLifecycleDatabaseDiagnostics.preservingFailure(
                {
                    println(
                        "PG_LIFECYCLE_STARTUP_DIAGNOSTIC fixture=TRACKED_WEAK_NO_RAW mode=${mode.name} ordinal=$ordinal " +
                            caller.diagnostic() + " " + peer.diagnostic(ordinal),
                    )
                },
                { peer.awaitRefusal(ordinal) },
            )
        }
        val retained = PgLifecycleTrackedWeakNoRawAttempt(scope, caller)
        retained.beforeRefusal()
        capture.requirePending(scope)
        return retained
    }

    fun pendingCall(attempt: PgLifecycleTrackedWeakNoRawAttempt): PgLifecycleTrackedWeakNoRawPendingCall {
        check(pendingCall == null)
        val retained = PgLifecycleTrackedWeakNoRawPendingCall(attempt.transport)
        pendingCall = retained
        retained.admit()
        return retained
    }

    fun failedConstruction(attempt: PgLifecycleTrackedWeakNoRawAttempt): PgLifecycleTrackedWeakNoRawFailedConstruction {
        check(failedConstruction == null)
        val retained = PgLifecycleTrackedWeakNoRawFailedConstruction(attempt.transport)
        failedConstruction = retained
        scope.expectedUnknown = true
        retained.installAndFail()
        return retained
    }

    override fun close() {
        // Release all gates and the fixture's own foreign reference BEFORE any blocking root shutdown observation.
        val admissionCut = runCatching { admission?.close() }
        val timerGates = runCatching { capture.releaseGates() }
        val peerGates = callers.indices.map { ordinal -> runCatching { peer.releaseRefusal(ordinal) } }
        val query = runCatching { witness?.close() } // Disarm/release only owned sample counts before token release and root waits.
        val token = runCatching { pendingCall?.close() }
        val model = runCatching { failedConstruction?.close() }
        val reference = runCatching { capture.releaseReferenceIfAcquired() }
        val stop = runCatching { managed?.owner?.requestShutdown() }
        val peerClose = runCatching { peer.close() }
        val callerCloses = callers.filterNotNull().map { caller -> runCatching { caller.close() } }
        val rootClose = runCatching { managed?.close() }
        val timerClose = runCatching { capture.close() }
        val outcomes = listOf(admissionCut, timerGates, query, token, model, reference, stop, peerClose, rootClose, timerClose) +
            peerGates + callerCloses
        val failures = outcomes.mapNotNull { it.exceptionOrNull() }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
        println("PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_CLEANUP mode=$mode callers=${callers.count { it != null }} all_terminated=true")
    }
}
