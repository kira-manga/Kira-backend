package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.DESIRED_OPERATOR_TEST_PASSWORD
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.ended
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFixture
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationInputFixture
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxGenesisReleaseFilesV1
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/** Actual original publisher, acquired cold graph and fixed operator; raw HTTP/time and read-only holder observation only. */
internal class CatalogGenesisPublishInvocation(private val fixture: CatalogGenesisPublishFixture) {
    private val caller = Thread.currentThread()
    val secrets = AwsSecretVersionFixture()
    val http get() = fixture.http
    val clock = DesiredInstallationTestClock()
    val wallClock = MutableClock(VersionBoundCatalogReadbackTestFixture.evaluatedAt)
    val operator = CatalogGenesisPublishV1.withHttpFixtures(secrets::httpClient, http::putClient, http::readClient, clock, wallClock)
    val assembly: ComplaintDesiredProcessAssemblyV1 = poolTestField(operator, "assembly")
    val scopes = linkedMapOf<String, PgLifecycleTestScope>()
    val phases = linkedSetOf<PersistencePhaseContext>()
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    var target: VersionBoundComplaintProcessConfiguration? = null
        private set
    var coordinator: CatalogCoordinatorPersistence? = null
        private set
    var beforePhaseWork: (PersistencePhaseContext) -> Unit = {}
    var afterSample: () -> Unit = {}
    var operatorPasswordOverride: ByteArray? = null
    var cleanupVerified = false
        private set
    private var observing = false
    private val assertion = AtomicReference<AssertionError?>()
    val attempt: CatalogGenesisPublishAttemptV1 get() = poolTestField(operator, "attempt")

    init {
        val acquired = DesiredInstallationInputFixture.acquired(
            fixture.inputs,
            PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray(),
            DESIRED_OPERATOR_TEST_PASSWORD.toByteArray(),
        )
        secrets.respond = { request ->
            preserveAssertions {
                requireConnectionFree()
                val fields = request.fields()
                assertEquals(setOf("SecretId", "VersionId"), fields.keys)
                val selected = acquired.single {
                    it.descriptor.version.resourceArn == fields["SecretId"] &&
                        it.descriptor.version.versionId == fields["VersionId"]
                }
                assertTrue(fixture.inputs.allBindings().any { sameBinding(it, selected.descriptor) })
                val override = operatorPasswordOverride?.takeIf { sameBinding(selected.descriptor, fixture.inputs.operatorPassword) }
                if (override == null) {
                    selected.useMaterial { AwsSecretVersionFixture.reply(selected.descriptor.version, it) }
                } else {
                    AwsSecretVersionFixture.reply(selected.descriptor.version, override)
                }
            }
        }
        clock.onSample = {
            if (caller === Thread.currentThread() && !observing) {
                observing = true
                try {
                    observeAssembly()
                    observePhase()
                    afterSample()
                } finally {
                    observing = false
                }
            }
        }
    }

    fun execute(recover: Boolean = false, request: CatalogGenesisPublishRequestV1 = fixture.request) = try {
        val sealer = if (fixture.inputs.sealerMapping == null) null else AwsSecretVersionFixture.CREDENTIALS
        if (recover) {
            operator.recover(request, AwsSecretVersionFixture.CREDENTIALS, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials, sealer)
        } else {
            operator.publish(
                request,
                AwsSecretVersionFixture.CREDENTIALS,
                CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS,
                S3CatalogReadbackFixture.credentials,
                S3CatalogReadbackFixture.credentials,
                sealer,
            )
        }
    } finally {
        assertNoLostAssertions()
    }

    fun assertReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(secrets.createdClients, secrets.closedClients)
        assertEquals(http.put.createdClients, http.put.closedClients)
        assertEquals(http.read.createdClients, http.read.closedClients)
        scopes.values.forEach { scope ->
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, scope.owner.observeShutdown())
            assertTrue(scope.actors().all { it.termination().ended() && !it.thread.isAlive })
            assertEquals(PersistencePublicTrustRelease.RELEASED, scope.owner.releasePublicTrustAfterShutdown())
            assertFalse(scope.owner.snapshot().ordinaryReady || scope.owner.snapshot().deletionReady)
        }
        ownedCutField(operator, "release")?.let { release ->
            val custody: CatalogGenesisReleaseCustodyV1 = poolTestField(release, "custody")
            assertTrue(poolTestField<LinuxGenesisReleaseFilesV1>(custody, "files").cleanupComplete())
        }
        fixture.selected.assertNoTargetSessions()
        assertNoLostAssertions()
    }

    fun fixtureCleanup() {
        if (cleanupVerified) return assertNoLostAssertions()
        assertSame(caller, Thread.currentThread())
        clock.onSample = {}
        runCatching(operator::close)
        scopes.values.forEach { it.owner.requestShutdown() }
        scopes.values.forEach { it.close() }
        scopes.values.forEach { it.owner.versionBoundPools?.close() }
        requireConnectionFree()
        // Test disposal only, after actual retirement of the SAME original JDBC roots/lease; never renew command work or emit its result.
        runCatching { (ownedCutField(operator, "release") as? AutoCloseable)?.close() }
        assertReleased()
        cleanupVerified = true
    }

    private fun observeAssembly() {
        for (name in listOf("targetOwner", "operatorOwner")) {
            val owner = ownedCutField(assembly, name) as? PersistenceJdbcLifecycleOwner ?: continue
            if (name !in scopes) scopes[name] = PgLifecycleTestScope(owner)
        }
        if (!poolTestField<Boolean>(assembly, "stopping")) {
            target = ownedCutField(assembly, "assembled") as? VersionBoundComplaintProcessConfiguration
        }
        coordinator = scopes["operatorOwner"]?.owner?.let { ownedCutField(it, "catalogResources") as? CatalogCoordinatorPersistence }
    }

    private fun observePhase() {
        val phase = PersistencePhaseOwnership.current() ?: return
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK, poolTestField(phase, "path"))
        phases.add(phase)
        if (phase in observations) return
        // Observe only the already-entered real work phase; never issue fixture JDBC during partially returned begin/setup.
        if (poolTestField<Enum<*>>(phase, "stage").name != "WORK") return
        val retained = coordinator ?: return
        val holder = TransactionSynchronizationManager.getResource(retained.dataSource) as? ConnectionHolder ?: return
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        assertEquals(setOf(retained.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, holder.connection.transactionIsolation)
        assertFalse(holder.connection.isReadOnly) // Genuine FOR UPDATE recheck, not permission for D/history writes.
        val identity = holder.connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT pg_backend_pid(), txid_current(), session_user, current_user, (SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid())",
            ).use { row ->
                assertTrue(row.next())
                assertEquals(ComplaintDesiredInstallationFixture.OPERATOR, row.getString(3))
                assertEquals(ComplaintDesiredInstallationFixture.OPERATOR, row.getString(4))
                assertTrue(row.getBoolean(5) && !row.wasNull())
                (row.getInt(1) to row.getLong(2)).also { assertFalse(row.next()) }
            }
        }
        observations[phase] = StepUpPhaseObservation(phase, ownedPoolLease(holder.connection), identity)
        beforePhaseWork(phase)
    }

    fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    private fun assertNoLostAssertions() {
        clock.assertNoLostAssertions()
        http.assertNoLostAssertions()
        assertion.get()?.let { throw it }
    }

    private fun sameBinding(first: VersionedSecretBinding, second: VersionedSecretBinding): Boolean =
        first.family == second.family && first.purpose == second.purpose && first.logicalKeyId == second.logicalKeyId && first.version == second.version
}
