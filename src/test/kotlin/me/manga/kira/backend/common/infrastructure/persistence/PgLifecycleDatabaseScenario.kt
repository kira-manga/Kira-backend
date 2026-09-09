package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertThrows
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Parent orchestration keeps child, relay and server alive until exact local AND independent session evidence exists. */
internal class PgLifecycleDatabaseScenario(private val database: PgLifecycleDatabaseFixture) {
    fun verify(directory: Path, case: PgLifecycleDatabaseCase) {
        PgLifecycleDatabaseProbeProcess(directory, case).use { child ->
            database.observer(child.nonce).use { observer ->
                PgLifecycleDatabaseRelay(database, child.application, case).use { relay ->
                    verifyOwned(case, child, observer, relay)
                }
            }
        }
    }

    private fun verifyOwned(
        case: PgLifecycleDatabaseCase,
        child: PgLifecycleDatabaseProbeProcess,
        observer: PgLifecycleDatabaseObserver,
        relay: PgLifecycleDatabaseRelay,
    ) {
        val observation = PgLifecycleDatabaseObservation()
        PgLifecycleDatabaseDiagnostics.preservingFailure(
            diagnostic = { PgLifecycleDatabaseDiagnostics.relay(case, child.application, observation, relay) },
        ) {
            relay.start()
            observation.phase = PgLifecycleDatabaseObservationPhase.START_CHILD
            child.start(relay.port)
            val handshake = child.handshake
            observation.phase = PgLifecycleDatabaseObservationPhase.WAIT_PREPARED
            handshake.await(PgLifecycleDatabasePhase.PREPARED, progress = { progress(child, relay) })
            var previous: PgLifecycleDatabaseSession? = null
            repeat(case.attempts) { ordinal ->
                observation.begin(ordinal)
                val before = observer.sample(child.application)
                check(before.isEmpty())
                val arrivalDeadline = PgLifecycleDatabaseDeadline(8_000)
                handshake.publish(PgLifecycleDatabasePhase.START, ordinal)
                observation.phase = PgLifecycleDatabaseObservationPhase.WAIT_RETAINED
                handshake.await(PgLifecycleDatabasePhase.RETAINED, ordinal, arrivalDeadline) { progress(child, relay) }
                observation.retainedConsumed = true
                observation.phase = PgLifecycleDatabaseObservationPhase.WAIT_FIRST_GATE
                val gate = relay.awaitGate(ordinal, arrivalDeadline, child::requireAlive)
                observation.gateReturned = true
                observation.phase = PgLifecycleDatabaseObservationPhase.WITNESS_ARRIVAL
                val witnessed = arrival(case, child, observer, relay, gate, before, arrivalDeadline)
                observation.arrivalCompleted = true
                check(witnessed == null || witnessed != previous)
                observation.phase = PgLifecycleDatabaseObservationPhase.ARM_FAULT
                if (case.supplemental) arm(case, child, relay, ordinal, arrivalDeadline)
                observation.phase = PgLifecycleDatabaseObservationPhase.RELEASE_GATE
                gate.release() // Both real arrival AND retained-Entry receipt have been observed before first ReadyForQuery delivery.
                observation.parentReleaseCompleted = true
                observation.phase = PgLifecycleDatabaseObservationPhase.WAIT_EXPIRED_DRIVER
                if (case.deadlineFailure) deadline(case, child, observer, relay, ordinal, requireNotNull(witnessed))
                observation.phase = PgLifecycleDatabaseObservationPhase.KEEP_LIVE
                if (case.succeeds) keepLive(case, child, observer, relay, ordinal, requireNotNull(witnessed))
                observation.phase = PgLifecycleDatabaseObservationPhase.RETIRE_AND_ABSENCE
                retire(case, child, observer, relay, ordinal, witnessed)
                previous = witnessed
            }
            observation.phase = PgLifecycleDatabaseObservationPhase.WAIT_OWNER_DRAINED
            handshake.await(PgLifecycleDatabasePhase.OWNER_DRAINED, progress = { progress(child, relay) })
            observation.phase = PgLifecycleDatabaseObservationPhase.SAMPLE_FINAL
            check(observer.sample(child.application).isEmpty())
            progress(child, relay)
            observation.phase = PgLifecycleDatabaseObservationPhase.PUBLISH_EXIT
            handshake.publish(PgLifecycleDatabasePhase.EXIT)
            observation.phase = PgLifecycleDatabaseObservationPhase.WAIT_VERIFIED
            child.awaitVerified()
        }
    }

    private fun arm(
        case: PgLifecycleDatabaseCase,
        child: PgLifecycleDatabaseProbeProcess,
        relay: PgLifecycleDatabaseRelay,
        ordinal: Int,
        deadline: PgLifecycleDatabaseDeadline,
    ) {
        check(case.supplemental)
        child.handshake.publish(PgLifecycleDatabasePhase.ARRIVAL_CONFIRMED, ordinal)
        child.handshake.await(PgLifecycleDatabasePhase.FAULT_ARMED, ordinal, deadline) { progress(child, relay) }
        relay.armFault(ordinal)
    }

    private fun deadline(
        case: PgLifecycleDatabaseCase,
        child: PgLifecycleDatabaseProbeProcess,
        observer: PgLifecycleDatabaseObserver,
        relay: PgLifecycleDatabaseRelay,
        ordinal: Int,
        witnessed: PgLifecycleDatabaseSession,
    ) {
        val deadline = PgLifecycleDatabaseDeadline(10_000)
        child.handshake.await(PgLifecycleDatabasePhase.DEADLINE_DRIVER_ACTIVE, ordinal, deadline) { progress(child, relay) }
        check(observer.sample(child.application) == setOf(witnessed)) { "The driver-active expiry receipt lacked the same live server session." }
        progress(child, relay)
        deadline.checkRemaining()
        relay.confirmDeadline(ordinal)
        if (case.lateReturn) relay.releaseLateReady(ordinal, deadline, child::requireAlive)
        // Progress-only never releases the final byte. This releases only the child's explicit pre-transport-fence MODEL cut.
        child.handshake.publish(PgLifecycleDatabasePhase.DEADLINE_OBSERVED, ordinal)
        if (case.lateReturn) {
            child.handshake.await(PgLifecycleDatabasePhase.LATE_RAW_RETAINED, ordinal, deadline) { progress(child, relay) }
        }
        println(
            "PG_DATABASE_EXPIRED_SESSION ${case.label} ordinal=$ordinal pid=${witnessed.pid} backend_start=${witnessed.backendStart} " +
                "present_after_driver_active_expiry=true generation_unchanged=true primary=true child_alive=true late_ready=${case.lateReturn}",
        )
    }

    private fun arrival(
        case: PgLifecycleDatabaseCase,
        child: PgLifecycleDatabaseProbeProcess,
        observer: PgLifecycleDatabaseObserver,
        relay: PgLifecycleDatabaseRelay,
        gate: PgLifecycleDatabaseRelayState,
        before: Set<PgLifecycleDatabaseSession>,
        deadline: PgLifecycleDatabaseDeadline,
    ): PgLifecycleDatabaseSession? {
        if (case.mode === PgLifecycleDatabaseMode.WRONG_PASSWORD) {
            check(gate.gate.get() === PgLifecycleDatabaseGate.AUTHENTICATION_REFUSAL && gate.errorState.get() == "28P01")
            check(observer.sample(child.application).isEmpty())
            progress(child, relay)
            return null // Authentication refusal has no fabricated previously authenticated-session witness.
        }
        check(gate.gate.get() === PgLifecycleDatabaseGate.AUTHENTICATED_READY)
        val witnessed = observer.awaitNew(child.application, before, deadline) { progress(child, relay) }
        check(witnessed.pid == gate.backendPid.get()) { "Observer session did not match real BackendKeyData pid." }
        return witnessed
    }

    private fun keepLive(
        case: PgLifecycleDatabaseCase,
        child: PgLifecycleDatabaseProbeProcess,
        observer: PgLifecycleDatabaseObserver,
        relay: PgLifecycleDatabaseRelay,
        ordinal: Int,
        witnessed: PgLifecycleDatabaseSession,
    ) {
        child.handshake.await(PgLifecycleDatabasePhase.LIVE, ordinal, progress = { progress(child, relay) })
        relay.requireLive(ordinal)
        check(observer.sample(child.application) == setOf(witnessed))
        if (ordinal == 1) {
            child.handshake.await(PgLifecycleDatabasePhase.STALE_REJECTED, ordinal, progress = { progress(child, relay) })
        }
        if (case.mode === PgLifecycleDatabaseMode.REUSE && ordinal == 0) {
            negativeObservers(child, observer, relay, witnessed)
        }
        val duration = if (case.mode === PgLifecycleDatabaseMode.REUSE && ordinal == 0) case.lane.allowanceMillis + 250 else 150
        requirePresenceFor(child, observer, relay, ordinal, witnessed, duration)
        // All successful rows have a no-retirement presence control. Reuse additionally survives the whole establishment allowance.
        child.handshake.publish(PgLifecycleDatabasePhase.RETIRE, ordinal)
    }

    private fun requirePresenceFor(
        child: PgLifecycleDatabaseProbeProcess,
        observer: PgLifecycleDatabaseObserver,
        relay: PgLifecycleDatabaseRelay,
        ordinal: Int,
        witnessed: PgLifecycleDatabaseSession,
        duration: Long,
    ) {
        val deadline = PgLifecycleDatabaseDeadline(duration + 3_000)
        val began = System.nanoTime()
        var samples = 0
        do {
            deadline.checkRemaining()
            progress(child, relay)
            relay.requireLive(ordinal)
            check(observer.sample(child.application) == setOf(witnessed)) { "Live candidate disappeared without its retirement command." }
            progress(child, relay)
            check(++samples <= 40)
            Thread.sleep(deadline.millis(if (duration > 1_000) 250 else 50))
        } while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) <= duration)
        check(samples >= 2)
        relay.requireLive(ordinal)
        check(observer.sample(child.application) == setOf(witnessed))
    }

    private fun negativeObservers(
        child: PgLifecycleDatabaseProbeProcess,
        observer: PgLifecycleDatabaseObserver,
        relay: PgLifecycleDatabaseRelay,
        witnessed: PgLifecycleDatabaseSession,
    ) {
        val wrongIdentity = "w03c_${UUID.randomUUID()}"
        check(wrongIdentity != child.application)
        val wrong = observer.sample(wrongIdentity)
        check(wrong.isEmpty())
        assertThrows(IllegalStateException::class.java) { observer.requireNew(emptySet(), wrong) }
        database.observer().use { closedObserver ->
            closedObserver.close()
            assertThrows(SQLException::class.java) { closedObserver.sample(child.application) }
        }
        progress(child, relay)
        check(observer.sample(child.application) == setOf(witnessed))
        println("PG_DATABASE_ORACLE_NEGATIVE ${child.case.label} wrong_identity_rejected=true observer_exception_not_absence=true")
    }

    private fun retire(
        case: PgLifecycleDatabaseCase,
        child: PgLifecycleDatabaseProbeProcess,
        observer: PgLifecycleDatabaseObserver,
        relay: PgLifecycleDatabaseRelay,
        ordinal: Int,
        witnessed: PgLifecycleDatabaseSession?,
    ) {
        val deadline = PgLifecycleDatabaseDeadline(12_000)
        child.handshake.await(PgLifecycleDatabasePhase.RETIRED, ordinal, deadline) { progress(child, relay) }
        if (case.originalProvider && !case.returnsRaw) {
            weakNoRaw(case, child, observer, relay, ordinal, requireNotNull(witnessed), deadline)
            return
        }
        relay.awaitClientDisposal(ordinal, deadline, child::requireAlive)
        if (witnessed == null) {
            check(case.mode === PgLifecycleDatabaseMode.WRONG_PASSWORD && observer.sample(child.application).isEmpty())
        } else {
            observer.awaitAbsent(child.application, witnessed, deadline) { progress(child, relay) }
        }
        relay.requireRecipe(case, ordinal)
        progress(child, relay)
        val presence = witnessed?.let { "pid=${it.pid} backend_start=${it.backendStart}" } ?: "pre_auth_refusal=true"
        println(
            "PG_DATABASE_SESSION ${case.label} nonce=${child.nonce} ordinal=$ordinal $presence " +
                "absent=true generation_unchanged=true client_eof_before_upstream_close=true child_alive=true proof=REAL_POSTGRESQL",
        )
        if (case.roleProbe) {
            println("PG_DATABASE_ROLE ${case.label} ordinal=$ordinal wire=SHOW_TRANSACTION_READ_ONLY value=off primary_health=true generation_unchanged=true")
        }
        if (case.originalProvider) println("PG_DATABASE_WEAK_POLICY ${case.label} ordinal=$ordinal observed_absence_does_not_upgrade=DRIVER_CONTRACT_ONLY")
        child.handshake.publish(PgLifecycleDatabasePhase.ABSENCE_CONFIRMED, ordinal)
    }

    private fun weakNoRaw(
        case: PgLifecycleDatabaseCase,
        child: PgLifecycleDatabaseProbeProcess,
        observer: PgLifecycleDatabaseObserver,
        relay: PgLifecycleDatabaseRelay,
        ordinal: Int,
        witnessed: PgLifecycleDatabaseSession,
        deadline: PgLifecycleDatabaseDeadline,
    ) {
        val clientEnd = relay.weakBeforeCleanup(ordinal)
        val current = observer.sample(child.application)
        check(current.isEmpty() || current == setOf(witnessed)) { "Weak no-raw observation found an unexpected server identity." }
        progress(child, relay)
        deadline.checkRemaining()
        relay.requireRecipe(case, ordinal)
        println(
            "PG_DATABASE_WEAK_NO_RAW ${case.label} ordinal=$ordinal pid=${witnessed.pid} backend_start=${witnessed.backendStart} " +
                "present_before_fixture_cleanup=${witnessed in current} client_end=${clientEnd ?: "NONE"} child_alive=true primary_health=true " +
                "local=NO_RAW_DRIVER_RETURN_ONLY provider_disposal=UNPROVED fixture_cleanup_started=false",
        )
        relay.cleanupWeakNoRaw(ordinal, deadline, child::requireAlive)
        observer.awaitAbsent(child.application, witnessed, deadline) { progress(child, relay) }
        println(
            "PG_DATABASE_WEAK_FIXTURE_CLEANUP ${case.label} ordinal=$ordinal observed_absent=true generation_unchanged=true child_alive=true " +
                "retained_relays_closed=true retained_relay_actors_ended=true late_predecessor_registration=CLEANUP_ONLY " +
                "provider_disposal=UNPROVED cleanup=FIXTURE_ONLY",
        )
        child.handshake.publish(PgLifecycleDatabasePhase.WEAK_CLEANUP_CONFIRMED, ordinal)
    }

    private fun progress(child: PgLifecycleDatabaseProbeProcess, relay: PgLifecycleDatabaseRelay) {
        child.requireAlive()
        relay.progress()
    }
}
