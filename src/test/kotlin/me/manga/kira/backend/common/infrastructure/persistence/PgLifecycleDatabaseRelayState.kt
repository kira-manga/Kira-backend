package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class PgLifecycleDatabaseGate {
    AUTHENTICATED_READY,
    AUTHENTICATION_REFUSAL,
}

internal enum class PgLifecycleDatabaseClientEnd {
    EOF,
    RESET,
}

private enum class PgLifecycleDatabaseInputOrigin {
    CLIENT,
    FIXTURE,
}

/** Detached bounded wire facts. Fixture teardown can never set a candidate-originated close receipt. */
internal class PgLifecycleDatabaseRelayState(private val case: PgLifecycleDatabaseCase, val acceptedIndex: Int? = null) {
    val fixtureClosing = AtomicBoolean()
    val failure = AtomicReference<PgLifecycleDatabaseRelayFailure?>()
    val completed = AtomicBoolean()
    val backendPid = AtomicInteger()
    val authentication = CopyOnWriteArrayList<Int>()
    val statements = CopyOnWriteArrayList<PgLifecycleDatabaseSql>()
    val errorState = AtomicReference<String?>()
    val frontendTerminate = AtomicBoolean()
    val clientEnd = AtomicReference<PgLifecycleDatabaseClientEnd?>()
    val originOrder = AtomicLong()
    val upstreamCloseOrder = AtomicLong()
    val clientEndNanos = AtomicLong()
    val outputHalfCloseOrder = AtomicLong()
    val lastServerWriteNanos = AtomicLong()
    val gate = AtomicReference<PgLifecycleDatabaseGate?>()
    val readyFault = PgLifecycleDatabaseReadyFault(case)
    val role = PgLifecycleDatabaseRoleWitness(case)
    private val held = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val releasedByParent = AtomicBoolean()
    private val sequence = AtomicLong()
    private val inputOrigin = AtomicReference<PgLifecycleDatabaseInputOrigin?>()

    @Volatile
    var ordinal: Int = -1

    @Volatile
    var auxiliary = false

    @Volatile
    var association = PgLifecycleDatabaseRelayAssociation.UNREGISTERED
        private set

    /** Called only after the original registration validations succeed; never used as admission authority. */
    fun publishAssociation(association: PgLifecycleDatabaseRelayAssociation) {
        this.association = association
    }

    fun captureFailure(stage: PgLifecycleDatabaseRelayStage, thrown: Throwable, retainDuringCleanup: Boolean = false) {
        val closing = fixtureClosing.get()
        if (closing && !retainDuringCleanup) return
        failure.compareAndSet(null, PgLifecycleDatabaseRelayFailure(acceptedIndex, association, stage, PgLifecycleDatabaseRelayFailureType.of(thrown), closing))
    }

    fun hold(reason: PgLifecycleDatabaseGate): Boolean {
        check(gate.compareAndSet(null, reason))
        held.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "PostgreSQL response gate was not released within its fixture bound." }
        return !fixtureClosing.get()
    }

    fun isHeld(): Boolean = held.count == 0L

    /** Read-only, bounded, mixed observations. Reached and actually unreleased are deliberately separate. */
    fun diagnostic(): String {
        val reached = isHeld()
        val signalled = released.count == 0L
        val closing = fixtureClosing.get()
        val auth = authentication.take(4).joinToString(",") { code ->
            when (code) {
                0 -> "OK"
                10 -> "SASL"
                11 -> "CONTINUE"
                12 -> "FINAL"
                else -> "OTHER"
            }
        }.ifEmpty { "NONE" }
        val error = when (errorState.get()) {
            null -> "NONE"
            "28P01" -> "WRONG_PASSWORD"
            else -> "OTHER"
        }
        return "gate=${gate.get()?.name ?: "NOT_RECORDED"} held_reached=$reached release_signalled=$signalled " +
            "unreleased=${reached && !signalled} parent_released=${releasedByParent.get()} fixture_closing=$closing " +
            "auth_count=${authentication.size} auth=$auth backend_key_present=${backendPid.get() > 0} error_category=$error " +
            "client_end=${clientEnd.get()?.name ?: "NOT_RECORDED"} origin_order=${originOrder.get()} upstream_close_order=${upstreamCloseOrder.get()} " +
            "frontend_terminate=${frontendTerminate.get()} completed=${completed.get()} failure_present=${failure.get() != null}"
    }

    fun release() {
        check(isHeld() && !fixtureClosing.get())
        if (case.supplemental) check(readyFault.armed.get())
        check(releasedByParent.compareAndSet(false, true))
        released.countDown()
    }

    fun clientOriginatedEnd(kind: PgLifecycleDatabaseClientEnd) {
        if (!inputOrigin.compareAndSet(null, PgLifecycleDatabaseInputOrigin.CLIENT)) {
            check(inputOrigin.get() === PgLifecycleDatabaseInputOrigin.FIXTURE) { "Duplicate independent client-input ending." }
            return
        }
        check(clientEnd.compareAndSet(null, kind))
        clientEndNanos.set(System.nanoTime())
        originOrder.set(sequence.incrementAndGet()) // Publication is strictly before the upstream close invocation.
        readyFault.stop()
    }

    fun outputHalfCloseInvoked() {
        check(!fixtureClosing.get() && clientEnd.get() == null)
        check(readyFault.outputHalfCloseEntered.compareAndSet(false, true))
        check(outputHalfCloseOrder.compareAndSet(0, sequence.incrementAndGet()))
    }

    fun propagatingUpstreamClose() {
        check(originOrder.get() > 0 && clientEnd.get() != null && !fixtureClosing.get())
        check(upstreamCloseOrder.compareAndSet(0, sequence.incrementAndGet()))
    }

    fun requireClientDisposal(primary: Boolean) {
        check(failure.get() == null && !fixtureClosing.get() && completed.get())
        check(clientEnd.get() != null && clientEndNanos.get() != 0L)
        check(originOrder.get() > 0 && upstreamCloseOrder.get() > originOrder.get())
        if (primary) check(releasedByParent.get())
        if (readyFault.outputHalfCloseEntered.get()) check(outputHalfCloseOrder.get() < originOrder.get())
    }

    fun cleanupRelease() {
        inputOrigin.compareAndSet(null, PgLifecycleDatabaseInputOrigin.FIXTURE)
        fixtureClosing.set(true)
        released.countDown()
        readyFault.stop()
    }
}
