package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One authentic warmed scope DataRow or COMMIT completion, never a startup fault or a general protocol parser. */
internal class PgLifecycleDatabaseWarmControl {
    val application = "w03c_${UUID.randomUUID()}"
    val primaryLimit = 7 // Ordinary <=2, four prepared deletion sessions, and the sole failed session's replacement.
    private val selection = AtomicReference<Selection?>()
    private val held = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private val cleanup = AtomicBoolean()
    private val heldAt = AtomicLong()
    private val witnessedAt = AtomicLong()

    fun arm(state: PgLifecycleDatabaseRelayState, scope: UUID) {
        check(state.warmedReady.get() && state.backendPid.get() > 0 && state.clientEnd.get() == null && !state.fixtureClosing.get())
        check(scope != UUID(0, 0) && selection.compareAndSet(null, Selection(state, scope)))
    }

    fun armCommit(state: PgLifecycleDatabaseRelayState) {
        check(state.warmedReady.get() && state.backendPid.get() > 0 && state.clientEnd.get() == null && !state.fixtureClosing.get())
        check(selection.compareAndSet(null, Selection(state, null)))
    }

    /** Only the real server pump calls this. Its bounded local frame remains unforwarded until actual client end. */
    fun forward(state: PgLifecycleDatabaseRelayState, message: PgLifecycleDatabaseWire.Message): Boolean {
        val selected = selection.get() ?: return true
        if (selected.scope == null) {
            if (selected.state !== state || message.type != 'C'.code) return true
            check(held.count == 1L && !cleanup.get() && state.clientEnd.get() == null)
            check(message.bytes.contentEquals(COMMIT_COMPLETE)) // First CommandComplete must match; never skip UPDATE or ROLLBACK.
        } else {
            if (selected.state !== state || message.type != 'D'.code) return true
            check(held.count == 1L && !cleanup.get() && state.clientEnd.get() == null)
            requireScopeRow(message, selected.scope) // The first DataRow must match; never skip a mismatching result.
        }
        heldAt.set(System.nanoTime())
        held.countDown()
        check(stopped.await(3, TimeUnit.SECONDS)) { "The real warmed response did not reach client-origin disposal within its fixture bound." }
        check(cleanup.get() || state.clientEnd.get() != null)
        return false // Neither normal client end nor failure cleanup forwards the held response.
    }

    fun awaitHeld(deadline: PgLifecycleDatabaseDeadline, progress: () -> Unit) {
        while (held.count != 0L) {
            progress()
            check(!cleanup.get() && selection.get()?.state?.clientEnd?.get() == null)
            deadline.pause()
        }
        deadline.checkRemaining()
        progress()
        requireHeld()
    }

    fun requireHeld(): PgLifecycleDatabaseRelayState = checkNotNull(selection.get()).state.also {
        check(held.count == 0L && stopped.count == 1L && !cleanup.get())
        check(!it.fixtureClosing.get() && it.clientEnd.get() == null)
        check(heldAt.get() > 0L && it.lastServerWriteNanos.get() <= heldAt.get())
    }

    fun witnessed() {
        requireHeld()
        check(witnessedAt.compareAndSet(0, System.nanoTime()))
    }

    fun requireStopped(): PgLifecycleDatabaseRelayState {
        val state = checkNotNull(selection.get()).state
        check(!cleanup.get() && !state.fixtureClosing.get() && stopped.count == 0L && state.clientEnd.get() != null)
        check(heldAt.get() > 0L && witnessedAt.get() >= heldAt.get() && state.clientEndNanos.get() >= witnessedAt.get())
        check(state.lastServerWriteNanos.get() <= heldAt.get())
        return state
    }

    fun clientEnded(state: PgLifecycleDatabaseRelayState) {
        if (selection.get()?.state === state) stopped.countDown()
    }

    fun fixtureClosing(state: PgLifecycleDatabaseRelayState) {
        if (selection.get()?.state === state) {
            cleanup.set(true)
            stopped.countDown()
        }
    }

    private fun requireScopeRow(message: PgLifecycleDatabaseWire.Message, scope: UUID) {
        val row = ByteBuffer.wrap(message.bytes)
        check(row.remaining() >= 2 && row.short.toInt() == 11)
        repeat(11) { column ->
            check(row.remaining() >= 4)
            val length = row.int
            check(length >= -1 && length <= row.remaining())
            if (column == 0) {
                val actual = when (length) {
                    16 -> UUID(row.long, row.long)
                    36 -> UUID.fromString(String(ByteArray(length).also { row.get(it) }, Charsets.US_ASCII))
                    else -> error("The held synthetic control row did not contain a scope UUID.")
                }
                check(actual == scope)
            } else if (length >= 0) {
                row.position(row.position() + length)
            }
        }
        check(!row.hasRemaining())
    }

    private class Selection(val state: PgLifecycleDatabaseRelayState, val scope: UUID?)

    companion object {
        private val COMMIT_COMPLETE = "COMMIT\u0000".toByteArray(Charsets.US_ASCII)

        // Compared inside PostgreSQL; only a bounded boolean leaves the observer, never the observed SQL text.
        const val EXACT_SQL = "SELECT data_scope_id, test_only, maintenance_closed, creation_closed, scan_requested, " +
            "publication_epoch, event_writer_generation, accepted_catalog_generation, accepted_catalog_hash, " +
            "catalog_writer_generation, pending_projection_token FROM complaint_journal_control " +
            "WHERE data_scope_id = $1 AND data_scope_id <> '00000000-0000-0000-0000-000000000000' FOR UPDATE"
    }
}
