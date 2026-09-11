package me.manga.kira.backend.user

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import me.manga.kira.backend.security.DbUserJwtAuthenticationConverter
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.application.UserAdminService
import me.manga.kira.backend.user.domain.CredentialVersionExhaustedException
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserNotFoundException
import me.manga.kira.backend.user.domain.UserRepository
import me.manga.kira.backend.user.infrastructure.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** Real PostgreSQL mutations: causal row contention, deliberately stale JPA contexts and rollback. */
class UserCredentialMutationIT
@Autowired
constructor(
    private val users: UserRepository,
    private val admin: UserAdminService,
    private val passwords: PasswordEncoder,
    private val transactions: PlatformTransactionManager,
    private val tokens: JwtService,
    private val decoder: JwtDecoder,
) : AbstractIntegrationTest() {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `concurrent password updates wait on the real row lock and increment the stored version twice`() {
        val user = createUser("concurrent-reset@example.com")
        val hashA = passwords.encode(FIRST_PASSWORD)
        val hashB = passwords.encode(SECOND_PASSWORD)
        val aUpdated = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bEntering = CountDownLatch(1)
        val aPid = AtomicInteger()
        val bPid = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit(
                Callable {
                    inTransaction {
                        aPid.set(backendPid())
                        users.updatePasswordHash(user.id, hashA)
                        val updated = users.findById(user.id)!!
                        assertState(updated, hashA, 1, Role.USER, true)
                        aUpdated.countDown()
                        await(releaseA, "first reset release")
                        updated
                    }
                },
            )
            await(aUpdated, "first reset update")
            val second = workers.submit(
                Callable {
                    inTransaction {
                        bPid.set(backendPid())
                        val stale = entityManager.find(UserEntity::class.java, user.id)
                        assertEquals(0L, stale.credentialVersion) // A's update has not committed.
                        bEntering.countDown()
                        users.updatePasswordHash(user.id, hashB)
                        assertFalse(entityManager.contains(stale))
                        users.findById(user.id)!! // No test clear/refresh: the actual mutation owns freshness.
                    }
                },
            )
            await(bEntering, "second reset entry")
            awaitBlockedOn(bPid.get(), aPid.get())
            assertFalse(second.isDone, "the second reset must wait, not just race a start barrier")
            assertState(users.findById(user.id)!!, user.passwordHash, 0, Role.USER, true)
            releaseA.countDown()

            assertState(first.get(WAIT_SECONDS, TimeUnit.SECONDS), hashA, 1, Role.USER, true)
            assertState(second.get(WAIT_SECONDS, TimeUnit.SECONDS), hashB, 2, Role.USER, true)
            val stored = users.findById(user.id)!!
            assertState(stored, hashB, 2, Role.USER, true)
            assertTrue(passwords.matches(SECOND_PASSWORD, stored.passwordHash))
            assertFalse(passwords.matches(FIRST_PASSWORD, stored.passwordHash))
            assertFalse(passwords.matches(ORIGINAL_PASSWORD, stored.passwordHash))
        } finally {
            releaseA.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "owned reset workers must finish")
        }
    }

    @Test
    fun `stale enabled and role mutations cannot restore old password state`() {
        listOf(Mutation.DISABLE, Mutation.PROMOTE).forEach { assertStaleMutation(it, resetLast = false) }
    }

    @Test
    fun `stale reset cannot undo an already committed enabled or role mutation`() {
        listOf(Mutation.DISABLE, Mutation.PROMOTE).forEach { assertStaleMutation(it, resetLast = true) }
    }

    @Test
    fun `all mutations flush unrelated pending work clear managed state and explicitly stamp timestamps`() {
        val resetHash = passwords.encode(FIRST_PASSWORD)
        Mutation.entries.forEach { mutation ->
            val user = createUser("timestamp-${mutation.name.lowercase()}@example.com")
            val unrelated = createUser("pending-${mutation.name.lowercase()}@example.com")
            val changedEmail = "flushed-${mutation.name.lowercase()}@example.com"
            jdbcTemplate.update("UPDATE users SET updated_at = ? WHERE id = ?", Timestamp.from(Instant.EPOCH), user.id)
            inTransaction {
                val cached = entityManager.find(UserEntity::class.java, user.id)
                val pending = entityManager.find(UserEntity::class.java, unrelated.id)
                pending.email = changedEmail // Dirty managed work must flush BEFORE clearAutomatically.
                mutate(mutation, user.id, resetHash)
                assertFalse(entityManager.contains(cached))
                assertFalse(entityManager.contains(pending))
                val fresh = users.findById(user.id)!!
                assertTrue(fresh.updatedAt.isAfter(Instant.EPOCH), "bulk updates bypass @PreUpdate")
                assertState(
                    fresh,
                    if (mutation == Mutation.PASSWORD) resetHash else user.passwordHash,
                    if (mutation == Mutation.PASSWORD) 1L else 0L,
                    if (mutation == Mutation.PROMOTE) Role.ADMIN else Role.USER,
                    mutation != Mutation.DISABLE,
                )
                assertEquals(changedEmail, jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String::class.java, unrelated.id))
            }
            assertEquals(changedEmail, users.findById(unrelated.id)!!.email)
        }
    }

    @Test
    fun `service reset rollback restores password version bearer validity and audit before a safe commit`() {
        val user = createUser("rollback-reset@example.com")
        val originalToken = decoder.decode(tokens.issue(user).value)
        val converter = DbUserJwtAuthenticationConverter(users)
        converter.convert(originalToken)
        assertThrows<ExpectedRollback> {
            inTransaction {
                admin.resetPassword(user.id, FIRST_PASSWORD)
                entityManager.flush()
                val changed = users.findById(user.id)!!
                assertEquals(1L, changed.credentialVersion)
                assertTrue(passwords.matches(FIRST_PASSWORD, changed.passwordHash))
                assertEquals(1L, resetAudits(user.id))
                throw ExpectedRollback()
            }
        }
        assertEquals(user, users.findById(user.id))
        assertEquals(0L, resetAudits(user.id))
        converter.convert(originalToken)

        admin.resetPassword(user.id, FIRST_PASSWORD)
        val committed = users.findById(user.id)!!
        assertEquals(1L, committed.credentialVersion)
        assertTrue(passwords.matches(FIRST_PASSWORD, committed.passwordHash))
        assertEquals(1L, resetAudits(user.id))
        assertEquals(
            "{}",
            jdbcTemplate.queryForObject(
                "SELECT detail::text FROM audit_log WHERE action = 'USER_PASSWORD_RESET' AND entity_id = ?",
                String::class.java,
                user.id.toString(),
            ),
        )
        assertThrows<InvalidBearerTokenException> { converter.convert(originalToken) }
        converter.convert(decoder.decode(tokens.issue(committed).value))
    }

    @Test
    fun `last available credential version increments once and exhaustion cannot change hash or audit`() {
        val user = createUser("exhausted-reset@example.com")
        jdbcTemplate.update("UPDATE users SET credential_version = ? WHERE id = ?", Long.MAX_VALUE - 1, user.id)
        admin.resetPassword(user.id, FIRST_PASSWORD)
        val atMax = users.findById(user.id)!!
        assertEquals(Long.MAX_VALUE, atMax.credentialVersion)
        assertTrue(passwords.matches(FIRST_PASSWORD, atMax.passwordHash))
        assertEquals(1L, resetAudits(user.id))
        val token = decoder.decode(tokens.issue(atMax).value)

        val failure = assertThrows<CredentialVersionExhaustedException> { admin.resetPassword(user.id, SECOND_PASSWORD) }

        assertEquals("CREDENTIAL_VERSION_EXHAUSTED", failure.code)
        assertEquals(409, failure.status.value())
        assertEquals(atMax, users.findById(user.id))
        assertFalse(passwords.matches(SECOND_PASSWORD, atMax.passwordHash))
        assertEquals(1L, resetAudits(user.id))
        DbUserJwtAuthenticationConverter(users).convert(token)
    }

    @Test
    fun `missing targets remain not found for every direct mutation and service reset`() {
        val missing = UUID.randomUUID()
        val hash = passwords.encode(FIRST_PASSWORD)
        Mutation.entries.forEach { mutation ->
            val failure = assertThrows<UserNotFoundException> { mutate(mutation, missing, hash) }
            assertEquals("USER_NOT_FOUND", failure.code)
        }
        assertThrows<UserNotFoundException> { admin.resetPassword(missing, FIRST_PASSWORD) }
        assertEquals(0L, resetAudits(missing))
    }

    private fun assertStaleMutation(mutation: Mutation, resetLast: Boolean) {
        val user = createUser("stale-${mutation.name.lowercase()}-$resetLast@example.com")
        val hash = passwords.encode(FIRST_PASSWORD)
        val loaded = CountDownLatch(1)
        val competitorCommitted = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val result = workers.submit(
                Callable {
                    inTransaction {
                        val cached = entityManager.find(UserEntity::class.java, user.id)
                        assertEquals(0L, cached.credentialVersion)
                        loaded.countDown()
                        await(competitorCommitted, "competing mutation commit")
                        assertSame(cached, entityManager.find(UserEntity::class.java, user.id))
                        assertEquals(user.passwordHash, cached.passwordHash)
                        assertEquals(Role.USER, cached.role)
                        assertTrue(cached.enabled) // The first-level context is deliberately stale.
                        if (resetLast) users.updatePasswordHash(user.id, hash) else mutate(mutation, user.id, hash)
                        assertFalse(entityManager.contains(cached))
                        val fresh = users.findById(user.id)!! // No test-side refresh/clear.
                        assertState(fresh, hash, 1, if (mutation == Mutation.PROMOTE) Role.ADMIN else Role.USER, mutation != Mutation.DISABLE)
                        fresh
                    }
                },
            )
            await(loaded, "stale context preload")
            // These direct port calls have no outer transaction; each must commit on return.
            if (resetLast) mutate(mutation, user.id, hash) else users.updatePasswordHash(user.id, hash)
            val committed = users.findById(user.id)!!
            assertState(
                committed,
                if (resetLast) user.passwordHash else hash,
                if (resetLast) 0L else 1L,
                if (resetLast && mutation == Mutation.PROMOTE) Role.ADMIN else Role.USER,
                !resetLast || mutation != Mutation.DISABLE,
            )
            competitorCommitted.countDown()
            assertEquals(result.get(WAIT_SECONDS, TimeUnit.SECONDS), users.findById(user.id))
        } finally {
            competitorCommitted.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "owned stale-context worker must finish")
        }
    }

    private fun createUser(email: String): User = users.findById(users.create(email, passwords.encode(ORIGINAL_PASSWORD), Role.USER).id)!!

    private fun mutate(mutation: Mutation, id: UUID, hash: String) {
        when (mutation) {
            Mutation.PASSWORD -> users.updatePasswordHash(id, hash)
            Mutation.DISABLE -> users.setEnabled(id, false)
            Mutation.PROMOTE -> users.updateRole(id, Role.ADMIN)
        }
    }

    private fun assertState(user: User, hash: String, version: Long, role: Role, enabled: Boolean) {
        assertEquals(hash, user.passwordHash)
        assertEquals(version, user.credentialVersion)
        assertEquals(role, user.role)
        assertEquals(enabled, user.enabled)
    }

    private fun resetAudits(id: UUID): Long = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM audit_log WHERE action = 'USER_PASSWORD_RESET' AND entity_id = ?",
        Long::class.java,
        id.toString(),
    )!!

    private fun <T : Any> inTransaction(block: () -> T): T = requireNotNull(
        TransactionTemplate(transactions).apply { timeout = WAIT_SECONDS.toInt() }.execute {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '20s'")
            jdbcTemplate.execute("SET LOCAL statement_timeout = '25s'")
            block()
        },
    )

    private fun backendPid(): Int = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!

    private fun await(latch: CountDownLatch, label: String) {
        assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "timed out waiting for $label")
    }

    private fun awaitBlockedOn(waiter: Int, blocker: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (jdbcTemplate.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean::class.java, blocker, waiter) == true) return
            // This is only a bounded diagnostic polling interval. The PostgreSQL wait edge, not
            // elapsed time or simultaneous task starts, establishes the causal ordering.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        throw AssertionError("second reset never blocked on the owned first reset transaction")
    }

    private enum class Mutation { PASSWORD, DISABLE, PROMOTE }

    private class ExpectedRollback : RuntimeException()

    private companion object {
        const val WAIT_SECONDS = 40L
        const val ORIGINAL_PASSWORD = "original password phrase for reset"
        const val FIRST_PASSWORD = "first replacement password phrase"
        const val SECOND_PASSWORD = "second replacement password phrase"
    }
}
