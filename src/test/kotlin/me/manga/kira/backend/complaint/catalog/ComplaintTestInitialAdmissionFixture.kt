package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.OrdinarySourceGrantCleanupFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpRejected
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal val INITIAL_CAPTURE = PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE
internal val INITIAL_RELEASE = PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE

/** The existing protected-input -> real signed PROJECT -> genuine registration composition. */
internal fun withInitialAdmission(tls: VersionBoundPersistenceConnectedFixture, activeFirstCut: Boolean = false,
    ordinaryRawHttp: TestActiveOrdinaryRawHttpV1? = null,
    action: (InitialAdmissionFixture) -> Unit) =
    TestOrdinarySealHttpFixtureV1(protectedIntake = true).use { native ->
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ordinarySealHttp = native,
            // Only closed setup predecessor leases: not the tested release or natural-expiry qualification.
            expireClosedSetupPredecessors = true, activeFirstCut = activeFirstCut,
            ordinaryDrain = if (activeFirstCut) TestOrdinaryDrainFixtureInputsV1() else null,
            ordinaryRawHttp = ordinaryRawHttp) { p, runtime, registration, probe ->
            val fixture = InitialAdmissionFixture(p, runtime, registration, probe, native)
            val executor = runtime.pools.catalogCoordinator.testInitialAdmission
            val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
            val original = field.get(executor) as JdbcTemplate
            assertSame(original.dataSource, probe.dataSource)
            field.set(executor, probe) // Observe the original SQL/holder; never replace a result or operation.
            val sealing = probe.beforeSql
            probe.beforeSql = { step ->
                val path = probe.calls.last().path
                if (path.testInitialAdmission) {
                    val holder = p.holder(runtime)
                    assertEquals(path === INITIAL_CAPTURE, p.advisory(holder, "complaint-maintenance-v1", "ShareLock"))
                    assertEquals(path === INITIAL_RELEASE, p.advisory(holder, "complaint-maintenance-v1", "ExclusiveLock"),
                        "RELEASE chooses exclusive M directly; it cannot upgrade CAPTURE's retired shared lock.")
                    assertTrue(p.advisory(holder, "complaint-journal-epoch", "ShareLock"))
                } else sealing(step)
            }
            p.f.http.beforeRead = { p.f.signed.releasedSql(); fixture.assertSqlReleased() }
            try { action(fixture); probe.assertNoLostAssertions() }
            finally {
                p.f.http.beforeRead = p.f.signed::releasedSql
                p.f.http.afterReadClientClose = {}
                native.offsetNanos = 0 // Fixture cleanup only, never a renewed original budget.
                Thread.interrupted()
                probe.beforeSql = sealing
                probe.afterSql = {}
                assertSame(probe, field.get(executor)); field.set(executor, original)
                fixture.cleanupIdentities()
            }
        }
    }

internal class InitialAdmissionFixture(
    val p: ProjectionActivationObservation,
    val runtime: VersionBoundPersistenceConnectedFixture,
    val registration: ComplaintTestNamespaceRegistrationV1,
    val probe: CatalogSignerRotationProbeJdbc,
    val native: TestOrdinarySealHttpFixtureV1,
) {
    val assembly = checkNotNull(p.f.rows.evidence.intakeAssembly)
    val observer = p.f.rows.observer
    private val identities = linkedSetOf<UUID>()

    init { assertSame(assembly.target, registration.process) }

    fun begin(): ComplaintTestInitialAdmissionV1 = ComplaintTestInitialAdmissionV1.withHttpFixture(
        registration, assembly, p.f.http::readClient, SignedActivationObservation.WALL_CLOCK)

    fun release(original: ComplaintTestInitialAdmissionV1 = begin()) {
        original.release(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
        original.requireActualCleanup()
        registration.requireReleasedIdentityAdmission()
        assertSqlReleased()
        assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
        assertEquals(listOf(INITIAL_CAPTURE, INITIAL_RELEASE), probe.observations.keys.map { poolTestField<PersistencePhasePath>(it, "path") })
        probe.observations.forEach { (phase, _) -> assertTrue(phase.testInitialAdmissionCleanupProven(original)) }
        assertEquals(p.f.http.read.createdClients, p.f.http.read.closedClients)
    }

    fun assertSqlReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        probe.observations.forEach { (phase, observed) ->
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(observed.lease.completion.quiescent())
        }
        probe.assertNoLostAssertions()
    }

    fun assertNoLatch() = assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { registration.requireReleasedIdentityAdmission() }

    fun candidate(): InstallationEnrollmentCandidate {
        val id = UUID.randomUUID().also(identities::add)
        return InstallationEnrollmentCredentials.prepare(ScopedInstallationId(id, registration.process.desiredSettings().scope),
            ComplaintPlatform.ANDROID, ByteArray(32) { it.toByte() })
    }

    fun withExchange(action: (InitialIdentityExchangeFixture) -> Unit) =
        ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, service ->
            val jdbc = InitialIdentityProbe(ordinary)
            val audit = ComplaintInstallationEnrollmentAudit { scope, allocation, at -> service.recordInstallationEnrollment(scope, allocation, at) }
            val adapter = ComplaintInstallationExchangeAdapter(registration, ordinary.ownership, jdbc, audit)
            try { action(InitialIdentityExchangeFixture(this, ordinary, jdbc, adapter)) }
            finally { jdbc.assertNoLostAssertions(); requireConnectionFree() }
        }

    fun controls(preserved: Boolean = false): List<String> = observer.queryForList(
        if (preserved) "SELECT (to_jsonb(c) - ARRAY['maintenance_closed','creation_closed','updated_at'])::text FROM complaint_journal_control c ORDER BY data_scope_id"
        else "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaint_journal_control c ORDER BY data_scope_id", String::class.java)

    fun gates(open: Boolean) {
        assertEquals(2L, observer.queryForObject("SELECT count(*) FROM complaint_journal_control WHERE data_scope_id IN (?, ?) " +
            "AND maintenance_closed = ? AND creation_closed = ?", Long::class.java, UUID(0L, 0L), p.scope, !open, !open))
    }

    fun nonControlImage() = p.image().filterKeys { it !in setOf("global", "complaint_journal_control") }

    /** A negative observer change. Direct JDBC avoids enlisting a foreign Spring resource in the tested phase. */
    fun foreignUpdate(sql: String, vararg arguments: Any?): Int = checkNotNull(observer.dataSource).connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = 1
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    fun cleanupIdentities() {
        requireConnectionFree()
        // Only our freshly generated identities, after all outcome assertions. This is not a refund/purge producer.
        identities.forEach { id ->
            observer.update("DELETE FROM app_installations WHERE id = ? AND data_scope_id = ?", id, p.scope)
            observer.update("DELETE FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ?", id, p.scope)
        }
        observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_INSTALLATION_ENROLLED'", p.scope)
    }
}

internal class InitialIdentityExchangeFixture(
    val f: InitialAdmissionFixture,
    val ordinary: OrdinarySourceGrantCleanupFixture,
    val jdbc: InitialIdentityProbe,
    val adapter: ComplaintInstallationExchangeAdapter,
) {
    fun enroll(candidate: InstallationEnrollmentCandidate) = f.registration.process.consumers.ingressAdmission.withIngress(request()) { adapter.enroll(it, candidate) }
    fun session(candidate: InstallationEnrollmentCandidate) = f.registration.process.consumers.ingressAdmission.withIngress(request()) {
        adapter.session(it, InstallationEnrollmentCredentials.prepareSession(candidate.installation, ByteArray(32) { index -> index.toByte() }))
    }
    fun unavailable(candidate: InstallationEnrollmentCandidate) {
        assertEquals(ComplaintInstallationHttpFailure.UNAVAILABLE, assertThrows<ComplaintInstallationHttpRejected> { enroll(candidate) }.failure)
        assertEquals(ComplaintInstallationHttpFailure.UNAVAILABLE, assertThrows<ComplaintInstallationHttpRejected> { session(candidate) }.failure)
    }
    fun identityImage(): Map<String, List<String>> = checkNotNull(f.observer.dataSource).connection.use { connection ->
        listOf("app_installations", "complaint_installation_ids").associateWith { table ->
            connection.prepareStatement("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM $table c WHERE data_scope_id = ? ORDER BY id").use { statement ->
                statement.queryTimeout = 1; statement.setObject(1, f.p.scope)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
    }
    fun assertReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        jdbc.observations.forEach { (_, value) -> assertTrue(value.lease.completion.quiescent()) }
        jdbc.assertNoLostAssertions()
    }
    private fun request() = MockHttpServletRequest().apply { remoteAddr = "192.0.2.43" }
}

/** Passive same-pool template selected BEFORE registration binds its ordinary pair. All SQL/results are real. */
internal class InitialIdentityProbe(private val ordinary: OrdinarySourceGrantCleanupFixture) : JdbcTemplate(ordinary.pool) {
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<Pair<PersistencePhasePath, String>>()
    var after: (PersistencePhasePath, String) -> Unit = { _, _ -> }
    private val assertion = AtomicReference<AssertionError?>()

    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql) { super.query(sql, mapper, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql) { super.update(sql, *args) }

    private fun <T> observed(sql: String, action: () -> T): T = try {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val path = poolTestField<PersistencePhasePath>(phase, "path")
        val holder = TransactionSynchronizationManager.getResource(ordinary.pool) as ConnectionHolder
        assertSame(ordinary.pool, dataSource)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, holder.connection.transactionIsolation)
        if (phase !in observations) {
            val identity = holder.connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                    assertTrue(row.next()); (row.getInt(1) to row.getLong(2)).also { assertFalse(row.next()) }
                }
            }
            observations[phase] = StepUpPhaseObservation(phase, ownedPoolLease(holder.connection), identity)
        }
        calls.add(path to sql)
        action().also { after(path, sql) }
    } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }

    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}
