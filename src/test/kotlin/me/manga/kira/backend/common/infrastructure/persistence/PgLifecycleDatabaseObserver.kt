package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.time.Instant

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

    override fun close() = connection.close()

    companion object {
        private val QUERY = """
            SELECT pg_postmaster_start_time(), current_database(), current_user, pg_backend_pid(), 1, pg_is_in_recovery(), a.pid, a.backend_start
            FROM (SELECT 1) AS health
            LEFT JOIN pg_stat_activity AS a
              ON a.datname = ? AND a.usename = ? AND a.application_name = ? AND a.backend_type = 'client backend'
        """.trimIndent()
    }
}
