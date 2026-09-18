package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.catalog.CatalogGenesisPublishFixture
import me.manga.kira.backend.complaint.catalog.CatalogGenesisPublishHttpFixture
import me.manga.kira.backend.complaint.catalog.CatalogGenesisPublishInvocation
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import me.manga.kira.backend.database.CatalogGenesisExitV1
import me.manga.kira.backend.database.ComplaintCatalogGenesisPublishWorkerMain
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.SdkHttpMethod
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * One controller-local negative worker cut, not a successful-disposal fixture or a same-child PG/death test.
 * The exact failed graph stays reachable until this original test JVM dies; only the controller may then remove its sandbox.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "KIRA_PG_LIFECYCLE_LOCAL_RUN", matches = "[0-9a-f]{32}")
class CatalogGenesisPublishInterruptionIT {
    @AfterAll
    fun revokeControllerView() {
        // Controller mode only: close revokes the fixture view, never stops its PG or removes retained TLS/release material.
        retained?.database?.close()
    }

    @Test
    fun `publisher worker post ACK interruption remains failed with original protected public trust retained`() {
        val home = requireControllerHome() // Before any fixture can choose the Testcontainers fallback or create material.
        assertNull(retained)
        val original = RetainedOriginal().also { retained = it }
        val database = PgLifecycleDatabaseFixture(CatalogGenesisPublishInterruptionIT::class.java).also { original.database = it }
        database.start()
        val tls = VersionBoundPersistenceConnectedFixture(database).also { original.tls = it }
        tls.bind()
        val fixture = CatalogGenesisPublishFixture(tls).also { original.fixture = it }
        fixture.prepare() // Existing genuine AUTHOR freeze and fixed-operator first-D, never supplied selected-D evidence.
        assertEquals(home, fixture.freeze.parent.parent)
        val args = fixture.selected.cliArguments().apply { this[0] = "publish" }
        val environment = publisherEnvironment()
        val before = fixture.state()
        val invocation = fixture.invocation().also { original.invocation = it }
        val budget = invocation.operator.budget
        var arm: ByteArray? = null
        var outcome: ByteArray? = null
        fixture.http.beforePut = {
            assertCurrentRecheck(invocation)
            assertNull(arm)
            assertTrue(fixture.complete(ARM)) // The actual arm method already returned before native PUT preparation.
            assertFalse(fixture.exists(OUTCOME))
            arm = fixture.read(ARM)
        }
        fixture.http.beforeRead = {
            if (fixture.http.put.requests.isNotEmpty()) {
                assertNull(outcome)
                assertTrue(fixture.complete(OUTCOME))
                outcome = fixture.read(OUTCOME)
                original.pem = capturePem(invocation, fixture, database.versionBoundTls().root)
                throw InterruptedException("synthetic-private-publisher-readback-interruption")
            }
        }

        try {
            val exit = ComplaintCatalogGenesisPublishWorkerMain.execute(args, invocation.operator, environment)
            assertEquals(CatalogGenesisExitV1.INTERRUPTED, exit)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            // Test-carrier isolation only, after the boundary observation attempt, including a failed assertion.
            // No command resumption, cleanup retry or repair of sticky close evidence.
            Thread.interrupted()
        }
        invocation.clock.assertNoLostAssertions()
        fixture.http.assertNoLostAssertions()
        assertNull(poolTestField<AtomicReference<AssertionError?>>(invocation, "assertion").get())
        assertSame(budget, invocation.operator.budget)
        assertSame(budget, invocation.attempt.budget)
        assertTrue(poolTestField<Boolean>(invocation.operator, "closed"))
        assertTrue(poolTestField<Boolean>(invocation.attempt, "failed"))
        assertEquals(
            CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN,
            poolTestField<CatalogGenesisPublishExceptionV1>(invocation.operator, "closeFailure").code,
        )
        assertFalse(invocation.cleanupVerified)
        assertEquals(listOf(invocation), fixture.invocations)
        assertCurrentRecheck(invocation)
        assertRetainedOriginalPools(invocation, budget)
        assertPemUnchanged(checkNotNull(original.pem), fixture)
        assertArrayEquals(checkNotNull(arm), fixture.read(ARM))
        assertArrayEquals(checkNotNull(outcome), fixture.read(OUTCOME))
        assertTrue(fixture.complete(ARM) && fixture.complete(OUTCOME))
        assertTrue(String(checkNotNull(outcome), Charsets.UTF_8).contains("\"publish-acknowledged\""))
        assertFalse(fixture.exists(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE))
        assertFalse(fixture.exists(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE))
        assertEquals(1, fixture.http.put.requests.size)
        assertEquals(1, fixture.http.put.replies.single().calls)
        assertEquals(1, fixture.http.put.createdClients)
        assertEquals(1, fixture.http.put.closedClients)
        assertEquals(CatalogGenesisPublishHttpFixture.VERSION, fixture.http.primaryVersion)
        assertArrayEquals(fixture.selected.envelope, fixture.http.bodies.single())
        assertEquals(3, fixture.http.read.requests.size, "Two empty namespace probes and only the interrupted read preparation.")
        assertEquals(2, fixture.http.read.replies.size)
        assertTrue(fixture.http.read.requests.all { it.method() === SdkHttpMethod.GET })
        assertEquals(fixture.http.read.createdClients, fixture.http.read.closedClients)
        assertEquals(invocation.secrets.createdClients, invocation.secrets.closedClients)
        assertEquals(1, fixture.freeze.invocations.sumOf { it.signing.requests.size })
        assertEquals(before, fixture.state(), "Interrupted worker cannot select D, COMPLETE, PROJECT or change counters.")
        fixture.assertUnchangedFreeze()
        fixture.selected.assertNoTargetSessions()
        assertSame(original, retained)
        // Deliberately no fixtureCleanup/close/unregistration: RETAINED is not RELEASED and this JVM has not died yet.
    }

    private fun requireControllerHome(): Path {
        val run = checkNotNull(System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN"))
        check(run.matches(Regex("[0-9a-f]{32}")))
        val expected = Path.of("/root/.kira-validation/$run-test-home")
        val home = Path.of(System.getProperty("user.home"))
        assertEquals(expected, home)
        assertEquals(home, home.toRealPath())
        assertTrue(Files.isDirectory(home, NOFOLLOW_LINKS))
        assertProtected(home, 448)
        return home
    }

    private fun publisherEnvironment(): Map<String, String> = linkedMapOf(
        "SECRETS" to AwsSecretVersionFixture.CREDENTIALS,
        "PRIMARY_PUT" to CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS,
        "PRIMARY_READ" to S3CatalogReadbackFixture.credentials,
        "REPLICA_READ" to S3CatalogReadbackFixture.credentials,
    ).flatMap { (family, credentials) ->
        listOf(
            "KIRA_CATALOG_PUBLISH_${family}_ACCESS_KEY_ID" to credentials.accessKeyId(),
            "KIRA_CATALOG_PUBLISH_${family}_SECRET_ACCESS_KEY" to credentials.secretAccessKey(),
            "KIRA_CATALOG_PUBLISH_${family}_SESSION_TOKEN" to credentials.sessionToken(),
        )
    }.toMap()

    private fun assertCurrentRecheck(invocation: CatalogGenesisPublishInvocation) {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        val phase = invocation.phases.single()
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK, poolTestField(phase, "path"))
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertEquals(1, invocation.observations.size) // Existing observer checks actual session/current operator and pg_stat_ssl.
        assertSame(invocation.attempt, poolTestField(phase, "catalogPublisherAttempt"))
        assertEquals(0, checkNotNull(invocation.coordinator).activeSnapshotOwners())
        assertTrue(invocation.scopes.getValue("targetOwner").actors().none { it.hasEntered() })
    }

    private fun assertRetainedOriginalPools(invocation: CatalogGenesisPublishInvocation, budget: PersistenceTimeBudget) {
        assertEquals(setOf("targetOwner", "operatorOwner"), invocation.scopes.keys)
        invocation.scopes.values.forEach { scope ->
            // Read-only observation against the same original allowance, never another close or a renewed work budget.
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, scope.owner.observeShutdown(budget))
            assertTrue(scope.actors().all { it.termination().ended() && !it.thread.isAlive })
            val pools = checkNotNull(scope.owner.versionBoundPools)
            for (source in listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource)) {
                val lifecycle: PoolLifecycle = poolTestField(source, "lifecycle")
                assertEquals(PersistenceTerminalCall.RETURNED, lifecycle.firstCloseOutcome())
                assertTrue(lifecycle.closeInterruptionObserved())
                while (lifecycle.localShutdownForTrust() === PoolActorObservation.PENDING) {
                    LockSupport.parkNanos(budget.remainingMillis(ceilingMillis = 1) * 1_000_000)
                }
                assertEquals(PoolActorObservation.UNKNOWN, lifecycle.localShutdownForTrust())
                assertTrue(synchronized(poolTestField<Any>(lifecycle, "gate")) { lifecycle.closedPopulationReadyLocked() })
                val actors = lifecycle.actorSnapshot()
                assertEquals(0, actors.constructing)
                assertEquals(0, actors.retainedGenerations)
                assertEquals(0L, actors.futureLeaseEntries)
                assertEquals(0L, actors.activeOperations)
                assertTrue(actors.factorySealed)
            }
            assertEquals(PersistencePublicTrustRelease.RETAINED, scope.owner.releasePublicTrustAfterShutdown())
            assertFalse(scope.root.publicTrustReleaseReady())
            assertFalse(scope.owner.snapshot().ordinaryReady || scope.owner.snapshot().deletionReady)
        }
    }

    private fun capturePem(invocation: CatalogGenesisPublishInvocation, fixture: CatalogGenesisPublishFixture, tlsRoot: Path): RetainedPem {
        val root = invocation.scopes.getValue("operatorOwner").root
        val trust: OwnedPersistencePublicTrust = poolTestField(root, "publicTrust")
        assertSame(root, poolTestField(trust, "owner"))
        assertEquals(trust.path.toString(), root.endpoint.driverProperties().getProperty("sslrootcert"))
        assertEquals(fixture.inputs.protectedTrustParent, trust.path.parent.parent)
        assertTrue(trust.path.startsWith(tlsRoot))
        assertEquals(trust.path, trust.path.toRealPath())
        assertTrue(Files.isRegularFile(trust.path, NOFOLLOW_LINKS))
        assertProtected(trust.path, 256)
        assertProtected(trust.path.parent, 448)
        assertArrayEquals(fixture.inputs.publicTrustPem(), Files.readAllBytes(trust.path))
        return RetainedPem(trust, attributes(trust.path), attributes(trust.path.parent))
    }

    private fun assertPemUnchanged(pem: RetainedPem, fixture: CatalogGenesisPublishFixture) {
        assertEquals(pem.file, attributes(pem.trust.path))
        assertEquals(pem.directory, attributes(pem.trust.path.parent))
        assertArrayEquals(fixture.inputs.publicTrustPem(), Files.readAllBytes(pem.trust.path))
        assertEquals(pem.trust.sha256, Sha256.hex(Files.readAllBytes(pem.trust.path)))
    }

    private fun assertProtected(path: Path, mode: Int) {
        val facts = attributes(path)
        assertEquals(0, facts["uid"])
        assertEquals(0, facts["gid"])
        assertEquals(mode, (facts.getValue("mode") as Int) and 0xFFF)
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) assertEquals(1, facts["nlink"])
    }

    private fun attributes(path: Path): Map<String, Any> = Files.readAttributes(path, "unix:dev,ino,uid,gid,mode,nlink", NOFOLLOW_LINKS)

    private class RetainedOriginal {
        var database: PgLifecycleDatabaseFixture? = null
        var tls: VersionBoundPersistenceConnectedFixture? = null
        var fixture: CatalogGenesisPublishFixture? = null
        var invocation: CatalogGenesisPublishInvocation? = null
        var pem: RetainedPem? = null
    }

    private class RetainedPem(val trust: OwnedPersistencePublicTrust, val file: Map<String, Any>, val directory: Map<String, Any>)

    companion object {
        private var retained: RetainedOriginal? = null // Strong, one-shot, never cleared/replaced while this original JVM remains alive.
        private val ARM = CatalogGenesisReleaseLeafV1.PUBLISH_ARMED
        private val OUTCOME = CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME
    }
}
