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
                PgLifecycleDatabaseRelay(database, child.application, case.attempts).use { relay ->
                    relay.start()
                    child.start(relay.port)
                    val handshake = child.handshake
                    handshake.await(PgLifecycleDatabasePhase.PREPARED, progress = { progress(child, relay) })
                    var previous: PgLifecycleDatabaseSession? = null
                    repeat(case.attempts) { ordinal ->
                        val before = observer.sample(child.application)
                        check(before.isEmpty())
                        val arrivalDeadline = PgLifecycleDatabaseDeadline(8_000)
                        handshake.publish(PgLifecycleDatabasePhase.START, ordinal)
                        handshake.await(PgLifecycleDatabasePhase.RETAINED, ordinal, arrivalDeadline) { progress(child, relay) }
                        val gate = relay.awaitGate(ordinal, arrivalDeadline, child::requireAlive)
                        val witnessed = arrival(case, child, observer, relay, gate, before, arrivalDeadline)
                        check(witnessed == null || witnessed != previous)
                        gate.release() // Both real arrival AND retained-Entry receipt have been observed before first ReadyForQuery delivery.
                        if (case.returnsRaw) keepLive(case, child, observer, relay, ordinal, requireNotNull(witnessed))
                        retire(case, child, observer, relay, ordinal, witnessed)
                        previous = witnessed
                    }
                    handshake.await(PgLifecycleDatabasePhase.OWNER_DRAINED, progress = { progress(child, relay) })
                    check(observer.sample(child.application).isEmpty())
                    progress(child, relay)
                    handshake.publish(PgLifecycleDatabasePhase.EXIT)
                    child.awaitVerified()
                }
            }
        }
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
        child.handshake.publish(PgLifecycleDatabasePhase.ABSENCE_CONFIRMED, ordinal)
    }

    private fun progress(child: PgLifecycleDatabaseProbeProcess, relay: PgLifecycleDatabaseRelay) {
        child.requireAlive()
        relay.progress()
    }
}
