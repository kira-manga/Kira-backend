package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal data class PgLifecycleDatabaseSession(val pid: Int, val backendStart: Instant)

/** Real parent-process SQL. An exception/restart/missing health row never becomes an empty session set. */
internal class PgLifecycleDatabaseObserver(private val connection: Connection, private val generation: Instant) : AutoCloseable {
    private var observerPid: Int? = null

    fun sample(application: String): Set<PgLifecycleDatabaseSession> {
        check(application.matches(Regex("w03c_[0-9a-f-]{36}")))
        return connection.prepareStatement(QUERY).use { statement ->
            statement.queryTimeout = 1
            statement.maxRows = 4 // At most two sequential identities; a third observed row is a fixture violation, not truncation success.
            statement.setString(1, PgLifecycleDatabaseSettings.DATABASE)
            statement.setString(2, PgLifecycleDatabaseSettings.CANDIDATE)
            statement.setString(3, application)
            statement.executeQuery().use { result ->
                val sessions = mutableSetOf<PgLifecycleDatabaseSession>()
                var rows = 0
                while (result.next()) {
                    check(++rows <= 2) { "Synthetic session witness exceeded its fixed bound." }
                    check(result.getTimestamp(1).toInstant() == generation) { "Observer server generation changed." }
                    check(result.getString(2) == PgLifecycleDatabaseSettings.DATABASE && result.getString(3) == PgLifecycleDatabaseSettings.OBSERVER)
                    val observer = result.getInt(4)
                    check(observer > 0 && result.getInt(5) == 1)
                    if (observerPid == null) observerPid = observer
                    check(observer == observerPid) { "Observer connection identity changed." }
                    check(!result.getBoolean(6) && !result.wasNull()) { "Observer did not witness the unchanged server as primary." }
                    val pid = result.getInt(7)
                    if (!result.wasNull()) {
                        check(pid > 0 && sessions.add(PgLifecycleDatabaseSession(pid, result.getTimestamp(8).toInstant())))
                    } else {
                        check(result.getTimestamp(8) == null)
                    }
                }
                check(rows > 0) { "Observer health row was absent." }
                sessions
            }
        }
    }

    fun requireNew(before: Set<PgLifecycleDatabaseSession>, current: Set<PgLifecycleDatabaseSession>): PgLifecycleDatabaseSession {
        check(before.isEmpty() && current.size == 1) { "No exact positive synthetic-session arrival was witnessed." }
        return (current - before).single()
    }

    fun awaitNew(
        application: String,
        before: Set<PgLifecycleDatabaseSession>,
        deadline: PgLifecycleDatabaseDeadline,
        progress: () -> Unit,
    ): PgLifecycleDatabaseSession {
        check(before.isEmpty())
        while (true) {
            deadline.checkRemaining()
            progress()
            val current = sample(application)
            progress()
            deadline.checkRemaining()
            check(current.size <= 1)
            if (current.isNotEmpty()) return requireNew(before, current)
            deadline.pause()
        }
    }

    fun awaitAbsent(application: String, witnessed: PgLifecycleDatabaseSession, deadline: PgLifecycleDatabaseDeadline, progress: () -> Unit) {
        while (true) {
            deadline.checkRemaining()
            progress()
            val current = sample(application)
            progress()
            deadline.checkRemaining()
            check(current.all { it == witnessed }) { "Unexpected synthetic successor during retirement observation." }
            if (witnessed !in current) return
            deadline.pause()
        }
    }

    /** Exact synthetic execution only. Neither a missing session nor an unhealthy observer is a positive witness. */
    fun awaitActiveSleep(
        application: String,
        witnessed: PgLifecycleDatabaseSession,
        nonce: String,
        deadline: PgLifecycleDatabaseDeadline,
        progress: () -> Unit,
    ): Long {
        check(application.matches(Regex("w03c_[0-9a-f-]{36}")) && nonce.matches(Regex("[0-9a-f-]{36}")))
        while (true) {
            deadline.checkRemaining()
            progress()
            val active = activeSleep(application, witnessed, nonce)
            progress()
            deadline.checkRemaining()
            if (active) return System.nanoTime()
            deadline.pause()
        }
    }

    private fun activeSleep(application: String, witnessed: PgLifecycleDatabaseSession, nonce: String): Boolean =
        connection.prepareStatement(ACTIVE_SLEEP_QUERY).use { statement ->
            statement.queryTimeout = 1
            statement.maxRows = 4
            statement.setString(1, "SELECT pg_sleep(10) /* w03_wire_cancel_$nonce */")
            statement.setString(2, PgLifecycleDatabaseSettings.DATABASE)
            statement.setString(3, PgLifecycleDatabaseSettings.CANDIDATE)
            statement.setString(4, application)
            statement.executeQuery().use { result ->
                var rows = 0
                var seen = false
                var active = false
                while (result.next()) {
                    check(++rows <= 2) { "Synthetic activity witness exceeded its fixed bound." }
                    check(result.getTimestamp(1).toInstant() == generation) { "Observer server generation changed." }
                    check(result.getString(2) == PgLifecycleDatabaseSettings.DATABASE && result.getString(3) == PgLifecycleDatabaseSettings.OBSERVER)
                    val observer = result.getInt(4)
                    check(observer > 0 && result.getInt(5) == 1)
                    if (observerPid == null) observerPid = observer
                    check(observer == observerPid) { "Observer connection identity changed." }
                    check(!result.getBoolean(6) && !result.wasNull()) { "Observer did not witness the unchanged server as primary." }
                    val pid = result.getInt(7)
                    if (result.wasNull()) {
                        check(result.getTimestamp(8) == null)
                    } else {
                        check(!seen && pid > 0 && PgLifecycleDatabaseSession(pid, result.getTimestamp(8).toInstant()) == witnessed) {
                            "Unexpected synthetic session or successor during active-query observation."
                        }
                        seen = true
                    }
                    active = result.getBoolean(9)
                    check(!result.wasNull() && (!active || seen))
                }
                check(rows > 0) { "Observer health row was absent." }
                seen && active
            }
        }

    /** One exact warmed PID, not a relaxation of the constructor fixture's two-session observation bound. */
    fun requireHeldControl(application: String, witnessed: PgLifecycleDatabaseSession) {
        check(application.matches(Regex("w03c_[0-9a-f-]{36}")) && witnessed.pid > 0)
        connection.prepareStatement(HELD_CONTROL_QUERY).use { statement ->
            statement.queryTimeout = 1
            statement.maxRows = 2
            statement.setString(1, PgLifecycleDatabaseWarmControl.EXACT_SQL)
            statement.setString(2, PgLifecycleDatabaseSettings.DATABASE)
            statement.setString(3, PgLifecycleDatabaseSettings.CANDIDATE)
            statement.setString(4, application)
            statement.setInt(5, witnessed.pid)
            statement.executeQuery().use { result ->
                check(result.next()) { "The exact control observer health row was absent." }
                check(result.getTimestamp(1).toInstant() == generation)
                check(result.getString(2) == PgLifecycleDatabaseSettings.DATABASE && result.getString(3) == PgLifecycleDatabaseSettings.OBSERVER)
                val observer = result.getInt(4)
                check(observer > 0 && result.getInt(5) == 1)
                if (observerPid == null) observerPid = observer
                check(observer == observerPid && !result.getBoolean(6) && !result.wasNull())
                check(result.getInt(7) == witnessed.pid && !result.wasNull())
                check(result.getTimestamp(8).toInstant() == witnessed.backendStart)
                check(result.getBoolean(9) && !result.wasNull()) { "The exact warmed control query/transaction was not observed." }
                check(!result.next())
            }
        }
    }

    /** Persisted synthetic mutation while the exact caller still awaits its withheld COMMIT reply. */
    fun awaitCommittedFixture(
        application: String,
        witnessed: PgLifecycleDatabaseSession,
        resource: UUID,
        scope: UUID,
        deadline: PgLifecycleDatabaseDeadline,
        progress: () -> Unit,
    ) {
        check(application.matches(Regex("w03c_[0-9a-f-]{36}")) && witnessed.pid > 0)
        check(resource != UUID(0, 0) && scope != UUID(0, 0))
        while (true) {
            deadline.checkRemaining()
            progress()
            val committed = committedFixture(application, witnessed, resource, scope)
            progress()
            deadline.checkRemaining()
            if (committed) return
            deadline.pause()
        }
    }

    private fun committedFixture(application: String, witnessed: PgLifecycleDatabaseSession, resource: UUID, scope: UUID): Boolean =
        connection.prepareStatement(COMMITTED_FIXTURE_QUERY).use { statement ->
            statement.queryTimeout = 1
            statement.maxRows = 2
            statement.setObject(1, resource)
            statement.setObject(2, scope)
            statement.setString(3, PgLifecycleDatabaseSettings.DATABASE)
            statement.setString(4, PgLifecycleDatabaseSettings.CANDIDATE)
            statement.setString(5, application)
            statement.setInt(6, witnessed.pid)
            statement.executeQuery().use { result ->
                check(result.next()) { "The exact commit observer health row was absent." }
                check(result.getTimestamp(1).toInstant() == generation)
                check(result.getString(2) == PgLifecycleDatabaseSettings.DATABASE && result.getString(3) == PgLifecycleDatabaseSettings.OBSERVER)
                val observer = result.getInt(4)
                check(observer > 0 && result.getInt(5) == 1)
                if (observerPid == null) observerPid = observer
                check(observer == observerPid && !result.getBoolean(6) && !result.wasNull())
                check(result.getInt(7) == witnessed.pid && !result.wasNull())
                check(result.getTimestamp(8).toInstant() == witnessed.backendStart)
                val settled = result.getBoolean(9)
                check(!result.wasNull())
                val committed = result.getBoolean(10)
                check(!result.wasNull() && !result.next())
                settled && committed
            }
        }

    override fun close() = connection.close()

    companion object {
        private val QUERY = """
            SELECT pg_postmaster_start_time(), current_database(), current_user, pg_backend_pid(), 1, pg_is_in_recovery(), a.pid, a.backend_start
            FROM (SELECT 1) AS health
            LEFT JOIN pg_stat_activity AS a
              ON a.datname = ? AND a.usename = ? AND a.application_name = ? AND a.backend_type = 'client backend'
        """.trimIndent()

        // Return a bounded boolean, never the observed SQL text or an arbitrary server diagnostic.
        private val ACTIVE_SLEEP_QUERY = """
            SELECT pg_postmaster_start_time(), current_database(), current_user, pg_backend_pid(), 1, pg_is_in_recovery(), a.pid, a.backend_start,
                   COALESCE(a.state = 'active' AND a.wait_event_type = 'Timeout' AND a.wait_event = 'PgSleep' AND a.query = ?, FALSE)
            FROM (SELECT 1) AS health
            LEFT JOIN pg_stat_activity AS a
              ON a.datname = ? AND a.usename = ? AND a.application_name = ? AND a.backend_type = 'client backend'
        """.trimIndent()

        private val HELD_CONTROL_QUERY = """
            SELECT pg_postmaster_start_time(), current_database(), current_user, pg_backend_pid(), 1, pg_is_in_recovery(), a.pid, a.backend_start,
                   COALESCE(a.xact_start IS NOT NULL AND a.query = ? AND a.state IN ('active', 'idle in transaction'), FALSE)
            FROM (SELECT 1) AS health
            LEFT JOIN pg_stat_activity AS a
              ON a.datname = ? AND a.usename = ? AND a.application_name = ? AND a.pid = ? AND a.backend_type = 'client backend'
        """.trimIndent()

        private val COMMITTED_FIXTURE_QUERY = """
            SELECT pg_postmaster_start_time(), current_database(), current_user, pg_backend_pid(), 1, pg_is_in_recovery(), a.pid, a.backend_start,
                   COALESCE(a.xact_start IS NULL AND a.state = 'idle' AND a.query = 'COMMIT', FALSE),
                   EXISTS (SELECT 1 FROM complaints WHERE id = ? AND data_scope_id = ? AND status = 'IN_PROGRESS' AND version = 2)
            FROM (SELECT 1) AS health
            LEFT JOIN pg_stat_activity AS a
              ON a.datname = ? AND a.usename = ? AND a.application_name = ? AND a.pid = ? AND a.backend_type = 'client backend'
        """.trimIndent()
    }
}
