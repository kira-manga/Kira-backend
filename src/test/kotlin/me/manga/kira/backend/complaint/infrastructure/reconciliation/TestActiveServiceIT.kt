package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestDeploymentInputFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException
import java.nio.file.Path

/**
 * SOURCE_ONLY / NOT_COMPILED / NOT_RUN. Real PG/TLS and SDKs, synthetic raw HTTP only.
 * Sequential same-JVM A retirement -> service-owned cold B, not concurrent/two-process or AWS
 * qualification. No test-side B checkpoint/poll/APPLY. Connected poisoned-child shutdown,
 * empty-queue service recurrence and long-cadence fairness remain separate coverage frontiers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@EnabledOnOs(OS.LINUX, OS.MAC)
class TestActiveServiceIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestActiveServiceIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun realAndRawDefaultUnknownRefuseBeforeManifestProviderOrDatabaseAcquisition() {
        val initializedBefore = database.isInitialized()
        var providers = 0
        val rawDefault = TestActiveServiceV1.withHttpFixture(
            secretHttpFactory = { providers++; error("UNKNOWN must not acquire Secrets HTTP.") },
            catalogHttpFactory = { providers++; error("UNKNOWN must not acquire catalog HTTP.") },
        )
        for (service in listOf(TestActiveServiceV1.begin(), rawDefault)) {
            assertEquals(TestActiveServiceStatusV1.RETAINED, service.status)
            assertEquals(TestActiveServiceStatusV1.FAILED, service.serve(absentManifest, testActiveServiceSessionsV1()))
            assertEquals(TestActiveServiceFailureV1.LAUNCH_UNQUALIFIED, service.failureCode)
            val original = ownedCutField(service, "assembly") as ComplaintTestProcessAssemblyV1
            assertEquals(false, ownedCutField(original, "entered"), "The manifest reader was never entered.")
            listOf("channel", "resolver", "owner", "persistence", "assembled").forEach { assertNull(ownedCutField(original, it)) }
            assertColdChildren(service)
            service.requireCleanupProven()
            service.requestStop(); service.close()
            assertEquals(TestActiveServiceFailureV1.STARTUP_REFUSED,
                assertThrows<TestActiveServiceExceptionV1> { service.serve(absentManifest, testActiveServiceSessionsV1()) }.code)
            assertSame(original, ownedCutField(service, "assembly"))
            assertEquals(TestActiveServiceStatusV1.FAILED, service.status)
            assertEquals(TestActiveServiceFailureV1.LAUNCH_UNQUALIFIED, service.failureCode)
        }
        assertEquals(0, providers)
        assertEquals(initializedBefore, database.isInitialized())
    }

    @Test fun stopBeforeServeAndCloseBeforeServeCannotBeReenteredAsFreshOwners() {
        val stopped = TestActiveServiceV1.withHttpFixture(
            secretHttpFactory = { error("Stopped service must stay cold.") },
            catalogHttpFactory = { error("Stopped service must stay cold.") },
            runtimeLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY,
        )
        stopped.requestStop()
        assertEquals(TestActiveServiceStatusV1.STOPPED, stopped.serve(absentManifest, testActiveServiceSessionsV1()))
        assertNull(ownedCutField(stopped, "assembly")); assertColdChildren(stopped)
        assertNull(stopped.failureCode); stopped.requireCleanupProven(); stopped.close()
        assertThrows<TestActiveServiceExceptionV1> { stopped.serve(absentManifest, testActiveServiceSessionsV1()) }
        val closed = TestActiveServiceV1.begin()
        closed.close(); closed.requireCleanupProven()
        assertEquals(TestActiveServiceStatusV1.STOPPED, closed.status)
        assertThrows<TestActiveServiceExceptionV1> { closed.serve(absentManifest, testActiveServiceSessionsV1()) }
        assertNull(ownedCutField(closed, "assembly")); assertColdChildren(closed)
    }

    @Test fun coldServiceOwnsMissingPrimaryRecurrentApplyThenDrainsTheHeldQueueBeforeStopping() =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { tls ->
            tls.bind()
            withRecurrentFixture(tls, applied = false) { precursor ->
                TestActiveServiceFixtureV1(precursor).use { it.serveAndStopDuringOriginalQueue() }
            }
        }

    @Test fun failedOriginalSecretsCloseRetainsColdAssemblyWithoutRedispatchOrFalseStopped() {
        val initializedBefore = database.isInitialized()
        val document = testActiveServiceColdDocumentV1()
        val raw = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(raw, document)
        raw.onClientClose = { throw IOException("Synthetic original Secrets HTTP close refusal.") }
        var catalogCalls = 0
        val service = TestActiveServiceV1.withHttpFixture(raw::httpClient,
            catalogHttpFactory = { catalogCalls++; error("Pre-database failure cannot read catalog.") },
            runtimeLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            assertEquals(TestActiveServiceStatusV1.CUSTODY_RETAINED, service.serve(path, testActiveServiceSessionsV1()))
            assertEquals(TestActiveServiceFailureV1.CLEANUP_UNPROVEN, service.failureCode)
            val original = ownedCutField(service, "assembly") as ComplaintTestProcessAssemblyV1
            val resolver = ownedCutField(original, "resolver")
            val failure = ownedCutField(original, "closeFailure")
            assertNotNull(resolver); assertNotNull(failure)
            assertEquals(true, ownedCutField(original, "resolverCloseFailed"))
            assertEquals(false, ownedCutField(service, "assemblyClosed"))
            assertNull(ownedCutField(original, "channel"))
            listOf("owner", "persistence", "assembled").forEach { assertNull(ownedCutField(original, it)) }
            assertColdChildren(service)
            assertEquals(1, raw.createdClients); assertEquals(1, raw.closedClients); assertEquals(1, raw.requests.size)
            raw.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertTrue(it.closes > 0) }
            service.requestStop()
            assertThrows<TestActiveServiceExceptionV1> { service.requireCleanupProven() }
            assertThrows<TestActiveServiceExceptionV1> { service.close() }
            assertThrows<TestActiveServiceExceptionV1> { service.serve(path, testActiveServiceSessionsV1()) }
            assertSame(original, ownedCutField(service, "assembly")); assertSame(resolver, ownedCutField(original, "resolver"))
            assertSame(failure, ownedCutField(original, "closeFailure"))
            assertEquals(TestActiveServiceStatusV1.CUSTODY_RETAINED, service.status)
            assertEquals(1, raw.closedClients); assertEquals(1, raw.requests.size); assertEquals(0, catalogCalls)
        }
        assertEquals(initializedBefore, database.isInitialized())
        // No connected poisoned parent was created, forcibly destroyed, or declared cleaned by the test.
    }

    private fun assertColdChildren(service: TestActiveServiceV1) {
        listOf("process", "registrationAttempt", "registration", "factory", "emf", "audit", "auditRepository", "auditRepositories",
            "ordinaryOwnership", "ordinaryAdmission", "ordinaryJdbc", "ordinaryManager", "deletionOwnership", "deletionAdmission", "deletionJdbc", "deletionManager",
            "queueOriginal", "queueCompleted", "recurrentOriginal", "recurrentResult").forEach { assertNull(ownedCutField(service, it)) }
    }

    private val absentManifest = Path.of("/kira-service-no-such-input/manifest.json")
}
