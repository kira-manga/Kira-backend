package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** The stock SetupQueryRunner uses one simple query. Retain only bounded framing/value facts, never SQL or result buffers. */
internal class PgLifecycleDatabaseRoleWitness(private val case: PgLifecycleDatabaseCase) {
    private val requested = AtomicBoolean()
    private val row = AtomicBoolean()
    private val command = AtomicBoolean()
    private val completed = AtomicBoolean()

    fun request(message: PgLifecycleDatabaseWire.Message) {
        check(case.roleProbe && message.type == 'Q'.code && requested.compareAndSet(false, true))
    }

    fun response(message: PgLifecycleDatabaseWire.Message) {
        if (!requested.get() || completed.get()) return
        when (message.type.toChar()) {
            'D' -> {
                PgLifecycleDatabaseWire.requirePrimaryRoleRow(message)
                check(!command.get() && row.compareAndSet(false, true))
            }

            'C' -> {
                PgLifecycleDatabaseWire.requireRoleCommand(message)
                check(row.get() && command.compareAndSet(false, true))
            }

            'Z' -> {
                PgLifecycleDatabaseWire.readyFrame(message)
                check(row.get() && command.get() && completed.compareAndSet(false, true))
            }

            'E' -> error("Real role query failed; a role mismatch was not proved.")
        }
    }

    fun requireEvidence() {
        check(requested.get() == case.roleProbe && row.get() == case.roleProbe)
        check(command.get() == case.roleProbe && completed.get() == case.roleProbe)
    }
}
