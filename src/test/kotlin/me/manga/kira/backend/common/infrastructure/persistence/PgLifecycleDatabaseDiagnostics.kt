package me.manga.kira.backend.common.infrastructure.persistence

import java.util.UUID

/** Diagnostic-only scalars. No sample is admission, disposal, a linearizable snapshot or a replacement request. */
internal object PgLifecycleDatabaseDiagnostics {
    const val MAX_CHARACTERS = 2_048
    const val UNAVAILABLE = "PG_DATABASE_DIAGNOSTIC_UNAVAILABLE v=1"

    /** Child-stack observations only; callers place these before/after existing operations outside F/G/T. */
    fun childStage(case: PgLifecycleDatabaseCase, ordinal: Int, application: String, stage: PgLifecycleDatabaseChildStage) = safely {
        emit(listOf(childStageLine(case, ordinal, application, stage)))
    }

    fun childStageLine(case: PgLifecycleDatabaseCase, ordinal: Int, application: String, stage: PgLifecycleDatabaseChildStage): String =
        "PG_DATABASE_CHILD_STAGE v=1 ${identity(case, ordinal, application)} observation=CHILD_STACK stage=${stage.name}"
            .also { checkBound(listOf(it)) }

    /** Wrap exactly the existing one-shot caller, not retention polling or a second request. Never inspect its candidate or Throwable. */
    fun <T : Any> originalCall(
        case: PgLifecycleDatabaseCase,
        ordinal: Int,
        application: String,
        operation: () -> PersistenceFactoryResult<T>,
    ): PersistenceFactoryResult<T> {
        safely { emit(originalCallLines(case, ordinal, application, null)) }
        val outcome = runCatching(operation)
        safely { emit(originalCallLines(case, ordinal, application, outcome)) }
        return outcome.getOrThrow()
    }

    fun originalCallLines(case: PgLifecycleDatabaseCase, ordinal: Int, application: String, outcome: Result<PersistenceFactoryResult<*>>?): List<String> {
        val fields = when {
            outcome == null -> "state=PENDING result=UNOBSERVED"
            outcome.isFailure -> "state=THREW result=UNOBSERVED"
            else -> "state=RETURNED ${resultFields(outcome.getOrThrow(), null, null)}"
        }
        return listOf("PG_DATABASE_ORIGINAL_CALL v=1 ${identity(case, ordinal, application)} entry=UNAVAILABLE $fields").also(::checkBound)
    }

    /** Runtime hook is called outside F/G/T. Binding lookup failure removes only the optional locked details. */
    fun result(
        case: PgLifecycleDatabaseCase,
        ordinal: Int,
        application: String,
        scope: PgLifecycleTestScope,
        witness: PgLifecycleDatabaseWitness,
        result: PersistenceFactoryResult<*>,
    ) = safely {
        val binding = runCatching { scope.binding(case.lane.deleting) }.getOrNull()
        emit(resultLines(case, ordinal, application, binding, witness.entry, result))
    }

    fun resultLines(
        case: PgLifecycleDatabaseCase,
        ordinal: Int,
        application: String,
        binding: PersistencePhysicalFactoryBinding?,
        entry: PersistencePhysicalEntry,
        result: PersistenceFactoryResult<*>,
    ): List<String> {
        check(ordinal in 0 until case.attempts)
        val identity = identity(case, ordinal, application)
        val locked = runCatching { binding?.let { lockedFacts(it, entry) } ?: LockedFacts() }.getOrElse { LockedFacts() }
        val control = entry.control
        val facts = entry.openingFacts
        return listOf(
            "PG_DATABASE_RESULT v=1 $identity observation=RESULT_OBSERVED " +
                resultFields(result, control?.receipt, locked.attemptReceipt) +
                " policy=${policyName(entry.policy)} control=${control?.state()?.name ?: "UNAVAILABLE"}",
            "PG_DATABASE_OPENING v=1 $identity observation=MIXED " +
                "driver_entered=${facts.driverEntered.get()} driver_ended=${facts.driverEnded.get()} " +
                "factory_entered=${facts.factoryEntered.get()} factory_ended=${facts.factoryEnded.get()} " +
                "opening_outcome=${facts.outcome.get()?.name ?: "NOT_RECORDED"} scope_call_ended=${facts.scopeCallEnded.get()} " +
                "raw_present=${entry.raw.get() != null} fatal=${facts.fatal.get()} retirement_requested=${entry.retirementRequested.get()} " +
                runCatching { budgetFields(control) }.getOrDefault("allowance_ms=UNAVAILABLE elapsed_at_observation_ms=UNAVAILABLE"),
            "PG_DATABASE_BOOKKEEPING v=1 $identity observation=MIXED " +
                "fg_sample=${locked.status} entry_present=${scalar(locked.member)} dispatched=${scalar(locked.dispatched)} " +
                "opening_phase=${locked.opening?.name ?: "UNAVAILABLE"} scope_ended=${scalar(locked.scopeEnded)} " +
                "attempt_phase=${locked.phase?.name ?: "UNAVAILABLE"} attempt_failure=${locked.failure?.name ?: locked.noFailure()} " +
                "worker_settled=${scalar(locked.workerSettled)} budget_same=${scalar(locked.budgetSame)}",
        ).also(::checkBound)
    }

    /** Pure result rendering never reads the value, Throwable, or an object's toString. */
    fun resultFields(result: PersistenceFactoryResult<*>, controlReceipt: PersistenceFactoryReceipt?, attemptReceipt: PersistenceFactoryReceipt?): String {
        val receipt = receipt(result)
        val variant = when (result) {
            is PersistenceFactoryResult.Success -> "SUCCESS"
            is PersistenceFactoryResult.Failed -> "FAILED"
            is PersistenceFactoryResult.Refused -> "REFUSED"
        }
        val reason = when (result) {
            is PersistenceFactoryResult.Success -> "NOT_APPLICABLE"
            is PersistenceFactoryResult.Failed -> result.reason.name
            is PersistenceFactoryResult.Refused -> result.reason.name
        }
        return "variant=$variant reason=$reason receipt_present=${receipt != null} " +
            "receipt_state=${receipt?.state()?.name ?: "NOT_APPLICABLE"} " +
            "control_receipt=${receiptIdentity(receipt, controlReceipt)} attempt_receipt=${receiptIdentity(receipt, attemptReceipt)}"
    }

    fun relay(case: PgLifecycleDatabaseCase, application: String, observation: PgLifecycleDatabaseObservation, relay: PgLifecycleDatabaseRelay) {
        emit(
            listOf(
                "PG_DATABASE_PARENT v=1 ${identity(case, observation.ordinal, application)} observation=PRE_CLEANUP_MIXED " +
                    "phase=${observation.phase.name} retained_consumed=${observation.retainedConsumed} gate_returned=${observation.gateReturned} " +
                    "arrival_completed=${observation.arrivalCompleted} parent_release_completed=${observation.parentReleaseCompleted}",
                "PG_DATABASE_RELAY v=1 ${relay.diagnostic(observation.ordinal)}",
            ),
        )
    }

    /** Reporter failure cannot replace a scenario failure or turn it into a successful return. */
    fun <T> preservingFailure(diagnostic: () -> Unit, operation: () -> T): T = runCatching(operation).onFailure {
        safely(diagnostic)
    }.getOrThrow()

    fun safely(diagnostic: () -> Unit) {
        runCatching(diagnostic).onFailure {
            // Even a failing output sink must not replace the original result/assertion.
            runCatching { println(UNAVAILABLE) }
        }
    }

    private fun identity(case: PgLifecycleDatabaseCase, ordinal: Int, application: String): String {
        check(ordinal in -1 until case.attempts && application.startsWith("w03c_"))
        val nonce = application.removePrefix("w03c_")
        check(UUID.fromString(nonce).toString() == nonce)
        return "${case.label} nonce=$nonce ordinal=$ordinal"
    }

    private fun receipt(result: PersistenceFactoryResult<*>): PersistenceFactoryReceipt? = when (result) {
        is PersistenceFactoryResult.Success -> result.receipt
        is PersistenceFactoryResult.Failed -> result.receipt
        is PersistenceFactoryResult.Refused -> null
    }

    private fun receiptIdentity(actual: PersistenceFactoryReceipt?, expected: PersistenceFactoryReceipt?): String = when {
        actual == null -> "NOT_APPLICABLE"
        expected == null -> "UNAVAILABLE"
        actual === expected -> "MATCH"
        else -> "MISMATCH"
    }

    private fun policyName(policy: PersistenceDriverAttemptPolicy): String = when {
        policy === PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER -> "ORIGINAL_PROVIDER"
        policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT -> "TRACKED_ORDINARY_CONTRACT"
        policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION -> "TRACKED_ORDINARY_CONJUNCTION"
        policy === PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION -> "TRACKED_DELETION_CONJUNCTION"
        else -> "UNRECOGNIZED"
    }

    private fun budgetFields(control: PersistenceOwnedCallerControl?): String {
        if (control == null) return "allowance_ms=UNAVAILABLE elapsed_at_observation_ms=UNAVAILABLE"
        // Existing immutable own-project budget only. No start/renew/remaining call or diagnostic allowance.
        val allowance = lifecycleField(control.budget, "allowanceNanos") as Long
        val started = lifecycleField(control.budget, "startedAtNanos") as Long
        val elapsed = System.nanoTime() - started
        val observed = if (elapsed >= 0) (elapsed / 1_000_000).toString() else "UNAVAILABLE"
        return "allowance_ms=${allowance / 1_000_000} elapsed_at_observation_ms=$observed"
    }

    private fun lockedFacts(binding: PersistencePhysicalFactoryBinding, entry: PersistencePhysicalEntry): LockedFacts {
        val facts = LockedFacts()
        val factory = binding.rendezvous.lock
        val ledger = binding.ledger.lock
        if (factory.isHeldByCurrentThread || ledger.isHeldByCurrentThread) return facts
        if (!factory.tryLock()) return facts
        try {
            if (!ledger.tryLock()) return facts
            try {
                facts.status = "AVAILABLE"
                facts.member = binding.ledger.current(entry.record) === entry
                facts.dispatched = entry.dispatched
                facts.opening = entry.opening
                facts.scopeEnded = entry.scopeEnded
                val attempt = entry.attempt
                facts.attemptReceipt = attempt?.receipt
                facts.phase = attempt?.phase
                facts.failure = attempt?.failure
                facts.workerSettled = attempt?.workerSettled
                facts.budgetSame = attempt?.let { it.budget === entry.control?.budget }
                // Receipt identity is inspected after unlock; no formatting or T sampling here.
            } finally {
                ledger.unlock()
            }
        } finally {
            factory.unlock()
        }
        return facts
    }

    private fun scalar(value: Boolean?): String = value?.toString() ?: "UNAVAILABLE"

    private fun emit(lines: List<String>) {
        checkBound(lines)
        lines.forEach(::println)
    }

    private fun checkBound(lines: List<String>) {
        check(lines.size in 1..3 && lines.sumOf { it.length + 1 } <= MAX_CHARACTERS)
        check(lines.all { line -> line.all { it.code in 32..126 } })
    }

    private class LockedFacts {
        var status = "UNAVAILABLE"
        var member: Boolean? = null
        var dispatched: Boolean? = null
        var opening: PersistencePhysicalOpeningPhase? = null
        var scopeEnded: Boolean? = null
        var phase: PersistenceFactoryAttemptPhase? = null
        var failure: PersistenceFactoryFailure? = null
        var workerSettled: Boolean? = null
        var budgetSame: Boolean? = null
        var attemptReceipt: PersistenceFactoryReceipt? = null

        fun noFailure(): String = if (phase != null) "NONE" else "UNAVAILABLE"
    }
}

internal enum class PgLifecycleDatabaseChildStage {
    WAIT_START,
    WAIT_RECEIVER,
    START_CALLER,
    WAIT_RETAIN,
    RETAINED,
}

internal enum class PgLifecycleDatabaseObservationPhase {
    START_RELAY,
    START_CHILD,
    WAIT_PREPARED,
    SAMPLE_BEFORE,
    WAIT_RETAINED,
    WAIT_FIRST_GATE,
    WITNESS_ARRIVAL,
    ARM_FAULT,
    RELEASE_GATE,
    WAIT_EXPIRED_DRIVER,
    KEEP_LIVE,
    RETIRE_AND_ABSENCE,
    WAIT_OWNER_DRAINED,
    SAMPLE_FINAL,
    PUBLISH_EXIT,
    WAIT_VERIFIED,
}

/** Parent-stack progress only; not a producer of any handshake, wire or lifecycle fact. */
internal class PgLifecycleDatabaseObservation {
    var phase = PgLifecycleDatabaseObservationPhase.START_RELAY
    var ordinal = -1
    var retainedConsumed = false
    var gateReturned = false
    var arrivalCompleted = false
    var parentReleaseCompleted = false

    fun begin(ordinal: Int) {
        this.ordinal = ordinal
        retainedConsumed = false
        gateReturned = false
        arrivalCompleted = false
        parentReleaseCompleted = false
        phase = PgLifecycleDatabaseObservationPhase.SAMPLE_BEFORE
    }
}
