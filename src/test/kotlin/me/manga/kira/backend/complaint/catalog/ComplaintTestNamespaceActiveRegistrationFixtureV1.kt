package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpRejected
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceActiveRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.SdkHttpClient

/** Actual old graph retirement + fresh protected assembly/native/pools/consumers. Same JVM, NOT two-process qualification. */
internal fun withColdActiveRoot(f: InitialAdmissionFixture,
    changeDocument: (ComplaintTestDeploymentDocumentV1) -> ComplaintTestDeploymentDocumentV1 = { it },
    action: (ColdActiveRegistrationFixtureV1) -> Unit) {
    f.assertSqlReleased()
    val previous = f.registration.process
    f.registration.close()
    f.runtime.closeRegisteredRuntimeForRecovery()
    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.registration.requireReleasedIdentityAdmission() }
    // No synthetic expiry or wait: identity admission never borrows/replaces the predecessor's lease.
    f.p.f.rows.evidence.reassembleRecoveryIntake(f.native, changeDocument).use { assembly ->
        val runtime = VersionBoundPersistenceConnectedFixture(f.runtime.database, endpointPort = f.runtime.endpointPort, testIntake = assembly)
        try {
            runtime.bind(); runtime.start()
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
            val process = assembly.target
            assertNotSame(previous, process); assertNotSame(previous.pools, process.pools)
            assertNotSame(previous.consumers, process.consumers); assertNotSame(previous.consumers.jwt, process.consumers.jwt)
            assertNotSame(previous.consumers.journalRouting, process.consumers.journalRouting)
            assertNotSame(previous.publicationLanes, process.publicationLanes); assertNotSame(previous.ordinarySeal, process.ordinarySeal)
            val cold = ColdActiveRegistrationFixtureV1(f, assembly, runtime)
            val executor = runtime.pools.catalogCoordinator.testNamespaceActiveRegistration
            val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
            val actual = field.get(executor) as JdbcTemplate
            assertSame(actual.dataSource, cold.probe.dataSource)
            field.set(executor, cold.probe) // Passive real-SQL observer only; no supplied outcome, proof or issuer.
            try { action(cold); cold.probe.assertNoLostAssertions() }
            finally {
                assertSame(cold.probe, field.get(executor)); field.set(executor, actual)
                cold.probe.assertPhysicallyReleased(); cold.assertRawClosed()
            }
        } finally { runtime.close() }
    }
}

internal class ColdActiveRegistrationFixtureV1(val f: InitialAdmissionFixture, val assembly: ComplaintTestProcessAssemblyV1,
    val runtime: VersionBoundPersistenceConnectedFixture) {
    val probe = TestActiveRegistrationSqlProbeV1(f.p, runtime)
    var beforeRaw: () -> Unit = {}
    val raw = S3CatalogReadbackFixture().apply {
        respond = { request ->
            probe.assertCommittedAndReleased(); beforeRaw()
            f.p.f.http.read.respond(request).apply {
                val previousRead = beforeRead; val previousClose = onClose; val previousAbort = onAbort
                beforeRead = { probe.assertCommittedAndReleased(); previousRead() }
                onClose = { probe.assertCommittedAndReleased(); previousClose() }
                onAbort = { probe.assertCommittedAndReleased(); previousAbort() }
            }
        }
    }
    fun begin(http: () -> SdkHttpClient = raw::httpClient) = ComplaintTestNamespaceActiveRegistrationAttemptV1.withHttpFixture(assembly, http,
        SignedActivationObservation.WALL_CLOCK).also { probe.original = it }
    fun register(original: ComplaintTestNamespaceActiveRegistrationAttemptV1): ComplaintTestNamespaceRegistrationV1 =
        original.register(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun assertSuccessful(original: ComplaintTestNamespaceActiveRegistrationAttemptV1, registration: ComplaintTestNamespaceRegistrationV1) {
        original.requireActualCleanup(); registration.requireActiveIdentityTarget(assembly)
        probe.assertCommittedAndReleased()
        assertEquals(2, probe.observations.size)
        assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
        assertEquals(2, raw.createdClients)
        assertTrue(raw.requests.isNotEmpty() && raw.requests.all { it.method().name == "GET" })
        assertRawClosed()
        probe.calls.groupBy { it.phase }.values.forEach { calls ->
            val controls = calls.filter { it.sql == TestNamespaceActiveRegistrationSqlV1.lockControl }
            assertEquals(listOf(ComplaintDataScope.LIVE.id, f.p.scope, ComplaintDataScope.LIVE.id, f.p.scope), controls.map { it.arguments.last() })
            val catalog = calls.indexOfFirst { it.sql == TRY_CATALOG_LOCK }
            val history = calls.indexOfFirst { it.sql == CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory }
            val counters = calls.indexOfFirst { "FROM complaint_capacity_counters" in it.sql }
            val run = calls.indexOfFirst { it.sql == TestNamespaceActiveRegistrationSqlV1.lockRunIdentity }
            val ids = calls.indexOfFirst { it.sql == TestNamespaceActiveRegistrationSqlV1.installationCounts }
            assertTrue(calls.indexOf(controls[0]) < calls.indexOf(controls[1]) && calls.indexOf(controls[1]) < catalog &&
                catalog < history && history < counters && counters < run && run < ids)
            assertTrue(calls.none { it.sql.trimStart().startsWith("UPDATE") || it.sql.trimStart().startsWith("INSERT") || it.sql.trimStart().startsWith("DELETE") })
        }
    }
    fun assertRawClosed() {
        assertEquals(raw.createdClients, raw.closedClients)
        raw.replies.filter { it.calls > 0 && it.bodyPresent }.forEach { assertTrue(it.closes > 0) }
    }
    fun image(): Map<String, List<String>> = f.p.image().toMutableMap().apply {
        put("all-controls", f.controls())
        listOf("app_installations", "complaint_installation_ids").forEach { table ->
            put(table, f.observer.query("SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM $table r WHERE data_scope_id = ? ORDER BY id",
                { row, _ -> row.getString(1) }, f.p.scope))
        }
    }
    fun withExchange(registration: ComplaintTestNamespaceRegistrationV1, action: (ColdActiveIdentityExchangeV1) -> Unit) =
        ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, service ->
            val jdbc = InitialIdentityProbe(ordinary)
            val audit = ComplaintInstallationEnrollmentAudit { scope, allocation, at -> service.recordInstallationEnrollment(scope, allocation, at) }
            val adapter = ComplaintInstallationExchangeAdapter(registration, ordinary.ownership, jdbc, audit)
            try { action(ColdActiveIdentityExchangeV1(registration, adapter, jdbc, ordinary.ownership)) }
            finally { jdbc.assertNoLostAssertions(); requireConnectionFree() }
        }
}

internal class ColdActiveIdentityExchangeV1(val registration: ComplaintTestNamespaceRegistrationV1,
    val adapter: ComplaintInstallationExchangeAdapter, val jdbc: InitialIdentityProbe, val ownership: PersistencePhaseOwnership) {
    fun enroll(candidate: InstallationEnrollmentCandidate) = registration.process.consumers.ingressAdmission.withIngress(request()) { adapter.enroll(it, candidate) }
    fun session(candidate: InstallationEnrollmentCandidate) = registration.process.consumers.ingressAdmission.withIngress(request()) {
        adapter.session(it, InstallationEnrollmentCredentials.prepareSession(candidate.installation, ByteArray(32) { index -> index.toByte() }))
    }
    fun unavailable(candidate: InstallationEnrollmentCandidate) {
        assertEquals(ComplaintInstallationHttpFailure.UNAVAILABLE, assertThrows<ComplaintInstallationHttpRejected> { enroll(candidate) }.failure)
        assertEquals(ComplaintInstallationHttpFailure.UNAVAILABLE, assertThrows<ComplaintInstallationHttpRejected> { session(candidate) }.failure)
    }
    fun assertReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        jdbc.observations.forEach { (_, observed) -> assertTrue(observed.lease.completion.quiescent()) }
        jdbc.assertNoLostAssertions()
    }
    private fun request() = MockHttpServletRequest().apply { remoteAddr = "192.0.2.47" }
}
