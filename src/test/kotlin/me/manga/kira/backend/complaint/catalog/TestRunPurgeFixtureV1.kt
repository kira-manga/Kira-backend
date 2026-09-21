package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * GENUINELY UNUSED registration -> real sealing/drain -> empty manifests. No historical gate,
 * checkpoint, enrollment, APPLIED row or seal is seeded and then erased to fabricate this path.
 * Raw IAM/retention/denial specimens and the two CLOSED setup-root lease expiries are synthetic;
 * their presence is not installed-denial, nonempty-history, terminal or deployment acceptance.
 */
internal fun withUnusedPurgeRun(tls: VersionBoundPersistenceConnectedFixture, shortHorizon: Boolean = false,
    checkUnstartedManifest: Boolean = false,
    action: (TestRunPurgeFixtureV1, TestRunInstallationManifestPublicationV1, TestRunPurgeSqlProbeV1) -> Unit) {
    withUnusedSealedPurgeRun(tls, shortHorizon) { f ->
        val http = f.sealHttp
        f.enableUnexpectedDrainDiagnostic()
        val drain = try {
            f.beginDrain().also { original ->
                assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                    original.drain(f.approval(original), f.rawEvidence, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS))
            }
        } catch (problem: Throwable) {
            runCatching { f.reportUnexpectedDrainFailure() }
            throw problem // Preserve the exact failure and existing enclosing cleanup.
        }
        f.assertReleased()
        assertEquals(listOf("LIST", "LIST"), f.inventoryRequests.map { it.kind })
        assertTrue(f.inventoryKeys.requests.isEmpty(), "No dummy empty event is constructed or decrypted.")
        assertEquals(0L, drain.manifestCut().denial.firstInventory.versionCount)
        val preparation = TestRunInstallationManifestV1.begin(drain)
        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, preparation.prepare())
        val manifest = preparation.beginPublication()
        if (checkUnstartedManifest) {
            val before = f.p.image(); val providers = http.order.toList()
            assertThrows<RuntimeException> { manifest.beginPurgePublication() }
            assertEquals(before, f.p.image()); assertEquals(providers, http.order)
            assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND object_kind = 'TEST_RUN_PURGE'", Long::class.java, f.scope))
        }
        assertEquals(TestRunInstallationManifestPublicationResultV1.ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED, manifest.publish())
        assertEquals(0, manifest.capturedSource().count)
        assertEquals(0, manifest.authenticatedSummary().chunkCount)
        assertTrue(http.manifestObjects.isEmpty(), "Zero chunks means no fake empty chunk or manifest provider call.")
        val beforeInventory = f.inventoryRequests.size
        TestRunPurgeSqlProbeV1(f).use { probe ->
            val boundary = http.boundary; val nativeBoundary = http.nativeBoundary
            http.boundary = { boundary(); probe.assertReleased(requireCommitted = false) }
            http.nativeBoundary = { nativeBoundary(); probe.assertReleased(requireCommitted = false) }
            try { action(f, manifest, probe) }
            finally { http.boundary = boundary; http.nativeBoundary = nativeBoundary }
        }
        assertEquals(beforeInventory, f.inventoryRequests.size, "Purge current SQL cannot reuse the retired ordinary native reader.")
        f.assertFinishedPurge()
    }
}

/** Same genuine setup stopped before drain, for exact control-predicate comparisons, not authority. */
internal fun withUnusedSealedPurgeRun(tls: VersionBoundPersistenceConnectedFixture, shortHorizon: Boolean = false,
    action: (TestRunPurgeFixtureV1) -> Unit) {
    val inputs = TestOrdinaryDrainFixtureInputsV1(scanMillis = if (shortHorizon) 30_000 else null)
    val horizon = if (shortHorizon) Instant.now().plusSeconds(86_400).truncatedTo(ChronoUnit.SECONDS) else Instant.parse("2038-01-01T00:00:00Z")
    TestOrdinarySealHttpFixtureV1(horizon = horizon, manifestPublication = true, purgePublication = true).use { http ->
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ordinarySealHttp = http, ordinaryDrain = inputs,
            expireClosedSetupPredecessors = true) { p, runtime, registration, _ ->
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
            ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { _, audit ->
                TestRunPurgeFixtureV1(p, runtime, registration, audit, http, inputs).use { f ->
                    f.assertUnused()
                    assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                    action(f)
                }
            }
        }
    }
}

internal class TestRunPurgeFixtureV1(
    val p: ProjectionActivationObservation,
    val runtime: VersionBoundPersistenceConnectedFixture,
    val registration: ComplaintTestNamespaceRegistrationV1,
    private val audit: AuditService,
    val sealHttp: TestOrdinarySealHttpFixtureV1,
    private val inputs: TestOrdinaryDrainFixtureInputsV1,
) : AutoCloseable {
    val observer = p.f.rows.observer
    val scope = registration.process.consumers.journalConfiguration.scope.id
    val inventoryKeys = AwsJournalKmsFixture()
    val inventoryRequests = mutableListOf<JournalPublisherHttpRequest>()
    private var inventoryCreated = 0
    private var inventoryClosed = 0
    private val ownership = PersistencePhaseOwnership.deletion(DeletionPersistenceAdmission(), GuardedJdbcTransactionManager(runtime.pools.deletion))
    private val deletion = TestOrdinaryDrainSqlProbeV1(p, runtime, deletion = true)
    private val coordinator = TestOrdinaryDrainSqlProbeV1(p, runtime)
    private val raw = listOf("synthetic-unused-ordinary-denial:$scope".toByteArray(), "synthetic-unused-one-second-bound:$scope".toByteArray())
    private var expectUnreturnedNativeClose = false
    val rawEvidence get() = raw.map(ByteArray::copyOf)
    private val beforeBoundary = sealHttp.boundary
    private val beforeNative = sealHttp.nativeBoundary
    private val templates = listOf<Any>(runtime.pools.catalogCoordinator.testOrdinaryDrain, runtime.pools.catalogCoordinator.testOrdinarySeal).map { executor ->
        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
        Triple(executor, field, field.get(executor) as JdbcTemplate)
    }

    init {
        requireConnectionFree()
        templates.forEach { (executor, field, previous) -> assertSame(coordinator.dataSource, previous.dataSource); field.set(executor, coordinator) }
        sealHttp.boundary = { beforeBoundary(); assertDatabaseReleased(); coordinator.assertReleased(); deletion.assertReleased() }
        sealHttp.nativeBoundary = { beforeNative(); assertDatabaseReleased() }
        inventoryKeys.beforePrepare = { error("Unused inventory must never call KMS.") }
        inventoryKeys.onClientClose = ::assertDatabaseReleased
    }

    fun assertUnused() {
        assertTrue(observer.queryForObject("SELECT state = 'ACTIVE' AND enrolled_count = 0 FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java, scope) == true)
        assertTrue(observer.queryForObject("SELECT maintenance_closed AND creation_closed AND scan_requested AND publication_epoch = 1 " +
            "AND rotation_sequence = 0 AND rotation_id IS NULL AND rotation_state IS NULL AND rotation_epoch_before IS NULL " +
            "AND rotation_epoch_after IS NULL FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, scope) == true,
            "The real fresh PROJECT request is retained, never cleared to pass ordinary drain.")
        assertNoPreviousHistory()
        for (table in listOf("complaint_installation_ids", "app_installations", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
            "complaint_deletion_journal_applied", "complaint_test_terminal_intents", "complaint_journal_scan_runs", "complaint_journal_scan_entries"))
            assertEquals(0L, observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, scope), table)
    }

    fun assertNoPreviousHistory() {
        assertTrue(observer.queryForObject("SELECT seal_state IS NULL AND seal_epoch IS NULL AND seal_bytes IS NULL AND checkpoint_generation IS NULL " +
            "AND checkpoint_bytes IS NULL FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, scope) == true)
    }

    fun beginDrain(): TestRunOrdinaryDrainV1 = TestRunOrdinaryDrainV1.withHttpFixture(registration, ownership, deletion, audit,
        Clock.systemUTC(), System::nanoTime, {
            assertDatabaseReleased(); coordinator.assertReleased(); deletion.assertReleased(); inventoryCreated++
            journalPublisherRawHttpClient(inventoryRequests, ::assertDatabaseReleased, {}, { inventoryClosed++; assertDatabaseReleased() }) { request ->
                val journal = registration.process.consumers.journalConfiguration
                val location = journal.declaration().journalLocation
                journalPublisherRawAssertSigned(request, location.region, location.accountId, AwsJournalKmsFixture.CREDENTIALS)
                assertEquals("LIST", request.kind); assertTrue(request.body.isEmpty())
                assertEquals(listOf(journal.ordinaryPrefix), request.http.rawQueryParameters()["prefix"])
                assertEquals(listOf("2"), request.http.rawQueryParameters()["max-keys"])
                assertTrue(request.http.rawQueryParameters().keys.none { it in setOf("key-marker", "version-id-marker") })
                OwnerDeleteAllJournalPublisherFixture.xmlReply(journalPublisherRawListDocument(location.bucket, journal.ordinaryPrefix, emptyList()))
            }
        }, inventoryKeys::httpClient).also { coordinator.original = it; deletion.original = it }

    fun approval(original: TestRunOrdinaryDrainV1): ByteArray {
        assertDatabaseReleased(); assertNoPreviousHistory()
        val journal = registration.process.consumers.journalConfiguration
        val input = inputs.authorityInput(journal, registration.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment)
        val role = journal.declaration().authorities.ordinary
        val context = original.runContext
        val cutoff = checkNotNull(observer.queryForObject("SELECT publication_epoch FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, scope))
        val deniedAt = Instant.now().minusSeconds(2).epochSecond
        fun digest(bytes: ByteArray) = TestTerminalEvidenceDigestV1(Sha256.hex(bytes), bytes.size.toLong())
        return inputs.approval(TestOrdinaryDenialStatementV1(1, input.purpose, input.minimumApprovalVersion, input.authorityGrant,
            input.implementationAcceptance, input.evidenceRetentionPolicy, input.environment, context.dataScopeId,
            context.activationCatalogGeneration, context.activationCatalogSha256, context.configurationSha256, context.terminalEncodingSha256,
            registration.process.catalogActivation.initialWriterRegistrySha256, input.writerGeneration, input.databaseIdentity, input.restoreIdentity,
            1, cutoff, input.bucket, input.accountId, input.region, journal.ordinaryPrefix, role.roleId,
            TestTerminalPolicyRefV1(role.policy.policyId, role.policy.version, role.policy.sha256), deniedAt, deniedAt, 1, 0,
            sealHttp.horizon.epochSecond, digest(raw[1]), listOf(TestOrdinaryDeniedPathV1("synthetic-unused-path", role.roleId, deniedAt, deniedAt, digest(raw[0])))), input.keyId)
    }

    fun assertDatabaseReleased() {
        requireConnectionFree()
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, runtime.pools.catalogCoordinator.activeSnapshotOwners())
        coordinator.assertPhysicallyReleased(); deletion.assertPhysicallyReleased()
    }
    fun assertReleased() = assertDisposed(expectUnreturnedClose = false)

    /** Diagnostic CPU overhead is explicit, not timing-neutral or a causal fix. Before beginDrain only. */
    fun enableUnexpectedDrainDiagnostic() {
        coordinator.enableUnexpectedFailureDiagnostic(); deletion.enableUnexpectedFailureDiagnostic()
    }

    /** Passive existing observations only; this never opens another database/provider operation. */
    fun reportUnexpectedDrainFailure() {
        coordinator.reportUnexpectedFailure(); deletion.reportUnexpectedFailure()
        val allowed = setOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "GENERATE", "LIST", "PUT", "GET", "DECRYPT")
        System.err.println("UNUSED_PURGE_DRAIN_NATIVE inventoryKinds=${inventoryRequests.takeLast(4).map { if (it.kind == "LIST") "LIST" else "UNEXPECTED_KIND" }} " +
            "sealKinds=${sealHttp.order.takeLast(12).map { if (it in allowed) it else "UNEXPECTED_KIND" }}")
    }

    /** Negative-only teardown: retain the failed native close and its J owner, never call it released. */
    fun expectUnreturnedNativeCloseForTeardown() {
        assertTrue(!expectUnreturnedNativeClose)
        assertDisposed(expectUnreturnedClose = true)
        expectUnreturnedNativeClose = true
    }

    fun assertFinishedPurge() = assertDisposed(expectUnreturnedClose = expectUnreturnedNativeClose)

    private fun assertDisposed(expectUnreturnedClose: Boolean) {
        assertDatabaseReleased(); coordinator.assertReleased(); deletion.assertReleased()
        sealHttp.assertDisposed(requireReturnedClose = !expectUnreturnedClose)
        assertEquals(inventoryCreated, inventoryClosed)
        assertEquals(inventoryKeys.createdClients, inventoryKeys.returnedClientCloses)
        inventoryRequests.forEach { request -> assertEquals(1, request.calls); assertEquals(1, request.aborts); assertEquals(1, checkNotNull(request.reply).closes) }
        if (expectUnreturnedClose) assertEquals(1, sealHttp.s3Closed - sealHttp.s3CloseReturned +
            sealHttp.sts.closedClients - sealHttp.sts.returnedClientCloses + sealHttp.kms.closedClients - sealHttp.kms.returnedClientCloses)
        assertEquals(if (expectUnreturnedClose) 1L else 0L, registration.process.publicationLanes.activeOwners().totalOwners)
    }
    fun <T> raw(action: (Connection) -> T): T = checkNotNull(observer.dataSource).connection.use(action)

    /** Explicit test-only expiry after real retirement; not elapsed-time or lease-transfer evidence. */
    fun expireLeaseForRetry() {
        assertReleased()
        assertEquals(1, observer.update("UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 second' " +
            "WHERE data_scope_id = ? AND test_only AND lease_owner IS NOT NULL", scope))
    }

    override fun close() {
        try { assertFinishedPurge() }
        finally {
            sealHttp.boundary = beforeBoundary; sealHttp.nativeBoundary = beforeNative
            templates.forEach { (executor, field, _) -> assertSame(coordinator, field.get(executor)) }
            templates.forEach { (executor, field, previous) -> field.set(executor, previous) }
            // Post-assertion TEST teardown only. Never used to reach a successful producer state.
            raw { connection ->
                connection.autoCommit = false
                try {
                    val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                    jdbc.update("DELETE FROM complaint_journal_scan_entries WHERE data_scope_id = ?", scope)
                    jdbc.update("DELETE FROM complaint_journal_scan_runs WHERE data_scope_id = ?", scope)
                    jdbc.execute("ALTER TABLE complaint_test_terminal_intents DISABLE TRIGGER complaint_test_terminal_immutable")
                    jdbc.update("DELETE FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND test_only", scope)
                    jdbc.execute("ALTER TABLE complaint_test_terminal_intents ENABLE TRIGGER complaint_test_terminal_immutable")
                    jdbc.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ? AND test_only", scope)
                    jdbc.update("DELETE FROM complaint_journal_publications WHERE data_scope_id = ? AND test_only", scope)
                    connection.commit()
                } catch (problem: Throwable) { connection.rollback(); throw problem }
            }
            raw.forEach { it.fill(0) }
        }
    }
}
