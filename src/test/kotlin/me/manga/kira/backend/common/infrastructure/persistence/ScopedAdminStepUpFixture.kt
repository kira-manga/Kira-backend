package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintGrantCleanupPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.OrdinaryPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ScopedAdminStepUpPhaseExecutor
import me.manga.kira.backend.config.KiraAdminStudioProperties
import me.manga.kira.backend.security.AuthLoginAttempt
import me.manga.kira.backend.security.AuthThrottle
import me.manga.kira.backend.security.ComplaintGrantCleanup
import me.manga.kira.backend.security.IssuedScopedAdminStepUp
import me.manga.kira.backend.security.JdbcComplaintGrantCleanupStore
import me.manga.kira.backend.security.JdbcScopedAdminStepUpStore
import me.manga.kira.backend.security.ScopedAdminStepUpIssuer
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.SourceGrantCleanup
import me.manga.kira.backend.security.VerifiedScopedAdminStepUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Composition only: reuses the existing owned PG/JPA fixture and exact synthetic-counter restore owner. */
internal class ScopedStepUpFixture(
    val ordinary: OrdinarySourceGrantCleanupFixture,
    val counters: SyntheticComplaintCounters,
    expected: ByteArray? = counters.syntheticPolicyDigest(),
    grantJdbc: JdbcTemplate? = null,
    counterJdbc: JdbcTemplate? = null,
    phaseClock: Clock? = null,
) {
    val clock = StepUpFixtureClock(ordinary.cutoff)
    // Existing step-up tests keep their original clock; actual DB-time consumers can issue current real proofs.
    private val selectedClock = phaseClock ?: clock
    val properties = KiraAdminStudioProperties()
    val jdbc = ScopedStepUpJdbc(ordinary)
    val capacity = JdbcComplaintCapacityStore(counterJdbc ?: jdbc, expected)
    val store = JdbcScopedAdminStepUpStore(grantJdbc ?: jdbc, capacity, selectedClock, properties)
    val cleanups = mutableListOf<StepUpPhaseObservation>()
    var beforeCleanup: () -> Unit = {}
    val sourceCleanup = OrdinaryPersistencePhaseExecutor(
        ordinary.ownership,
        object : SourceGrantCleanup {
            override fun deleteEligibleSourceGrants(cutoff: Instant): Int {
                cleanups.add(observeStepUpPhase(ordinary))
                beforeCleanup()
                return ordinary.sourceStore.deleteEligibleSourceGrants(cutoff)
            }
        },
        selectedClock,
    )
    val complaintCleanup = ComplaintGrantCleanupPhaseExecutor(
        ordinary.ownership,
        object : ComplaintGrantCleanup {
            override fun deleteEligibleComplaintGrantsAndRefund(cutoff: Instant): Int {
                cleanups.add(observeStepUpPhase(ordinary))
                beforeCleanup()
                return JdbcComplaintGrantCleanupStore(ordinary.jdbc, capacity).deleteEligibleComplaintGrantsAndRefund(cutoff)
            }
        },
        selectedClock,
    )
    val phases = ScopedAdminStepUpPhaseExecutor(ordinary.ownership, store, sourceCleanup, complaintCleanup)
    val dependencies = StepUpExternalObservation(ordinary, jdbc)
    val issuer = ScopedAdminStepUpIssuer(phases, dependencies, dependencies)

    fun issue(scope: ScopedAdminStepUpScope, password: String = PASSWORD, userId: UUID = ordinary.userId): IssuedScopedAdminStepUp = when (scope) {
        ScopedAdminStepUpScope.SOURCE -> issuer.issueSource(userId, password, "192.0.2.29")
        ScopedAdminStepUpScope.COMPLAINT -> issuer.issueComplaint(userId, password, "192.0.2.29")
    }

    fun verify(scope: ScopedAdminStepUpScope): VerifiedScopedAdminStepUp {
        val snapshot = when (scope) {
            ScopedAdminStepUpScope.SOURCE -> phases.readSourceSnapshot(ordinary.userId)
            ScopedAdminStepUpScope.COMPLAINT -> phases.readComplaintSnapshot(ordinary.userId)
        }
        assertEquals("StepUpUserSnapshot(redacted)", snapshot.toString())
        val verified = VerifiedScopedAdminStepUp.verify(snapshot, PASSWORD, "192.0.2.29", dependencies, dependencies)
        assertEquals("VerifiedScopedAdminStepUp(redacted)", verified.toString())
        return verified
    }

    fun assertStoredProof(proof: IssuedScopedAdminStepUp, createdAt: Instant) {
        assertEquals(32, Base64.getUrlDecoder().decode(proof.token).size)
        assertTrue(proof.token.length <= 128)
        assertFalse(proof.toString().contains(proof.token))
        val matched = ordinary.foreignTemplate().query(
            "SELECT user_id, token_hash, scope, created_at, expires_at, used_at FROM admin_step_up_grants WHERE token_hash = ?",
            { result, _ ->
                result.getObject("user_id", UUID::class.java) == ordinary.userId &&
                    result.getString("token_hash") == Sha256.hexUtf8(proof.token) &&
                    result.getString("scope") == proof.scope.storedName &&
                    result.getTimestamp("created_at").toInstant() == createdAt &&
                    result.getTimestamp("expires_at").toInstant() == proof.expiresAt && result.getTimestamp("used_at") == null
            },
            Sha256.hexUtf8(proof.token),
        )
        assertEquals(listOf(true), matched, "Only the hash, exact final timestamps, user and selected scope may be stored.")
        assertEquals(createdAt.plus(properties.stepUpTtl), proof.expiresAt)
        assertEquals(0, ordinary.admission.activeOwners())
        requireConnectionFree()
    }

    fun assertGrantDelta(before: Map<String, CounterSnapshot>, added: Int) {
        val after = counters.snapshot()
        assertEquals(before.keys, after.keys)
        for ((name, old) in before) {
            val current = after.getValue(name)
            if (name == "moderation_grants" || name == "storage_bytes") {
                val units = if (name == "moderation_grants") added.toLong() else added * 16_384L
                assertEquals(old.preserved, current.preserved)
                assertEquals(old.actual + units, current.actual)
                assertEquals(old.free - units, current.free)
            } else {
                assertEquals(old, current)
            }
        }
    }

    companion object {
        const val PASSWORD = "synthetic-w03-password"
    }
}

internal enum class StepUpExternalCall { CHECK, PASSWORD, FAILURE, SUCCESS }

/** Passwords are really matched against the fixture hash; throttle calls are phase-aware test spies, not Redis qualification. */
internal class StepUpExternalObservation(private val f: OrdinarySourceGrantCleanupFixture, private val jdbc: ScopedStepUpJdbc) :
    PasswordEncoder,
    AuthThrottle {
    private val passwords = PasswordEncoderFactories.createDelegatingPasswordEncoder()
    val calls = mutableListOf<StepUpExternalCall>()
    var onCall: (StepUpExternalCall) -> Unit = {}

    override fun encode(rawPassword: CharSequence): String = passwords.encode(rawPassword)

    override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean {
        observe(StepUpExternalCall.PASSWORD)
        return passwords.matches(rawPassword, encodedPassword)
    }

    override fun beginLoginAttempt(normalizedEmail: String, clientIp: String): AuthLoginAttempt {
        observe(StepUpExternalCall.CHECK) // Existing label now observes the atomic reservation, before password work.
        return AuthLoginAttempt { success -> observe(if (success) StepUpExternalCall.SUCCESS else StepUpExternalCall.FAILURE) }
    }

    override fun checkRegistrationAllowed(clientIp: String): Unit = error("Registration is not a step-up operation.")

    private fun observe(call: StepUpExternalCall) {
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        assertEquals(0, f.admission.activeOwners())
        assertTrue(
            jdbc.snapshots.last().lease.completion.quiescent(),
            "The actual snapshot lease must have ended before every dependency call.",
        )
        requireConnectionFree()
        calls.add(call)
        onCall(call)
    }
}

/** Above real SQL only: callbacks observe or fault this fixture's selected transaction, never fabricate native results. */
internal class ScopedStepUpJdbc(private val f: OrdinarySourceGrantCleanupFixture) : JdbcTemplate(f.pool) {
    val snapshots = mutableListOf<StepUpPhaseObservation>()
    val issuances = mutableListOf<StepUpPhaseObservation>()
    val operations = mutableListOf<String>()
    var counterUpdates = 0
        private set
    var insertAttempts = 0
        private set
    var beforeUserLock: () -> Unit = {}
    var afterCounterUpdate: (Int) -> Unit = {}
    var afterInsert: () -> Unit = {}

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> {
        observeQuery(sql)
        return super.query(sql, rowMapper)
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        observeQuery(sql)
        return super.query(sql, rowMapper, *args)
    }

    private fun observeQuery(sql: String) {
        when {
            sql.startsWith("SELECT id, email, password_hash") -> snapshots.add(observeStepUpPhase(f))

            sql.startsWith("SELECT name, ordinal, accounting_version") && issuancePhase() -> operations.add("COUNTERS_LOCK")

            sql.startsWith("SELECT id, password_hash, enabled, role") -> {
                operations.add("USER_LOCK")
                issuances.add(observeStepUpPhase(f))
                beforeUserLock()
            }
        }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        val insert = sql.startsWith("INSERT INTO admin_step_up_grants")
        if (insert) {
            insertAttempts++
            operations.add("INSERT")
        }
        val count = super.update(sql, *args)
        if (sql.startsWith("UPDATE complaint_capacity_counters") && issuancePhase()) {
            if (count == 1) counterUpdates++
            operations.add("COUNTER_UPDATE")
            afterCounterUpdate(count)
        }
        if (insert) {
            assertEquals(1, count)
            afterInsert()
        }
        return count
    }

    private fun issuancePhase(): Boolean = TransactionSynchronizationManager.getCurrentTransactionName() in setOf(
        PersistencePhasePath.SOURCE_STEP_UP_ISSUANCE.name,
        PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE.name,
    )
}

internal class StepUpPhaseObservation(val phase: PersistencePhaseContext, val lease: PersistenceJdbcLease, val identity: Pair<Int, Long>)

internal fun observeStepUpPhase(f: OrdinarySourceGrantCleanupFixture): StepUpPhaseObservation {
    val phase = checkNotNull(PersistencePhaseOwnership.current())
    val holder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder
    val entityHolder = TransactionSynchronizationManager.getResource(f.entityManagerFactory) as EntityManagerHolder
    val identity = f.jdbc.queryForObject("SELECT pg_backend_pid(), txid_current()", { result, _ -> result.getInt(1) to result.getLong(2) })!!
    assertEquals(identity.second, (entityHolder.entityManager.createNativeQuery("SELECT txid_current()").singleResult as Number).toLong())
    assertSame(holder, TransactionSynchronizationManager.getResource(f.pool))
    return StepUpPhaseObservation(phase, ownedPoolLease(holder.connection), identity)
}

internal class StepUpFixtureClock(private val first: Instant, val samples: AtomicInteger = AtomicInteger(), private val zone: ZoneId = ZoneOffset.UTC) :
    Clock() {
    override fun instant(): Instant = first.plusSeconds(samples.getAndIncrement() * 37L)
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = StepUpFixtureClock(first, samples, zone)
}

internal class ScopedStepUpFixtureFailure : RuntimeException("Synthetic step-up failure containing no credential material.")
