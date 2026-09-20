package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunVerifiedOwnerDeleteV1
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.time.Clock

/**
 * Existing PROJECT/raw-registration/ordinary-audit/history fixtures only. Full four-key TEST J,
 * independently retained denial policy and actual native seal owner precede signed full D.
 * Earlier AUTH's open/checkpoint/lower-D comparisons remain explicitly SYNTHETIC in the reused
 * history fixture, not external lineage/denial evidence. AUTH/VERIFY/APPLY themselves are the real
 * producers. Nothing here writes PARTIAL→CONVERTED, scan results or a successful seal/denial state.
 */
internal fun withOrdinaryDrainRun(
    tls: VersionBoundPersistenceConnectedFixture,
    prepared: Boolean = false,
    inputs: TestOrdinaryDrainFixtureInputsV1 = TestOrdinaryDrainFixtureInputsV1(),
    expireClosedSetupPredecessors: Boolean = false,
    manifestPublication: Boolean = false,
    additionalRawEnrolled: Int = 0,
    action: (TestRunOrdinaryDrainFixtureV1) -> Unit,
) {
    require(additionalRawEnrolled == 0 || manifestPublication && additionalRawEnrolled == 500)
    TestOrdinarySealHttpFixtureV1(manifestPublication = manifestPublication).use { sealHttp ->
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(
            tls, ordinarySealHttp = sealHttp, ordinaryDrain = inputs,
            expireClosedSetupPredecessors = expireClosedSetupPredecessors,
        ) { p, runtime, registration, _ ->
            assertEquals(4, registration.process.consumers.journalConfiguration.declaration().routing.keys.size)
            assertNotNull(registration.process.ordinaryDenial)
            assertNotNull(registration.process.ordinarySeal)
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
            ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit,
                    expectSubsequentProviderReads = true).use { history ->
                    history.authorEarlierHistory(verified = !prepared, additionalRawEnrolled = additionalRawEnrolled)
                    assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                    TestRunOrdinaryDrainFixtureV1(history, sealHttp, inputs).use { f ->
                        if (prepared) {
                            assertEquals("PREPARED", history.publicationState())
                            assertEquals("AUTHORIZED_DELETE", history.receiptState())
                        } else {
                            val before = p.counters()
                            // Registration retains this EXACT deletion template/owner on first use.
                            // Lower AUTH/VERIFY used the old fixture, not registered continuation.
                            val original = TestRunVerifiedOwnerDeleteV1.begin(registration, f.ownership,
                                f.jdbc, audit, history.actor.id, history.key)
                            assertSame(ComplaintOwnerDeleteReceipt.Applied, original.complete())
                            f.jdbc.assertReleased()
                            history.assertApplied(before) // Real APPLY leaves the exact P−U reservation PARTIAL.
                        }
                        action(f)
                    }
                }
            }
        }
    }
}

/**
 * Gives scanner tests the real same-object raw provider and original; install
 * TestOrdinaryInventoryHttpFixtureV1(provider) around the actual drain call. Tests, not this helper,
 * must assert native sequence/count/bounds and intermediate accounting. No synthetic pass factory.
 */
internal class TestRunOrdinaryDrainFixtureV1(
    val history: TestRunVerifiedOwnerDeleteFixture,
    val sealHttp: TestOrdinarySealHttpFixtureV1,
    private val inputs: TestOrdinaryDrainFixtureInputsV1,
) : AutoCloseable {
    val registration = history.registration
    val ownership = history.ownership
    val jdbc = TestOrdinaryDrainSqlProbeV1(history.p, history.runtime, deletion = true)
    val coordinatorProbe = TestOrdinaryDrainSqlProbeV1(history.p, history.runtime)
    val probe: TestOrdinaryDrainSqlProbeV1 get() = coordinatorProbe
    val audit = history.audit
    val provider: TestOwnerDeleteJournalPublisherFixture get() = history.provider
    val observer = history.observer
    val scope = history.scope.id
    val clock: Clock = Clock.systemUTC()
    val nanoTime: () -> Long = System::nanoTime
    val primaryCredentials = TestOwnerDeleteJournalPublisherFixture.CREDENTIALS
    val readCredentials = TestOwnerDeleteJournalPublisherFixture.CREDENTIALS
    private val rawClosure = listOf(
        "synthetic-complete-ordinary-path-denial:$scope".toByteArray(Charsets.UTF_8),
        "synthetic-provider-request-bound:one-second:$scope".toByteArray(Charsets.UTF_8),
    )
    val rawEvidence: List<ByteArray> get() = rawClosure.map(ByteArray::copyOf)
    private val originalControl = checkNotNull(observer.queryForObject(
        "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, scope))
    private var selected: TestRunOrdinaryDrainV1? = null
    private var statement: TestOrdinaryDenialStatementV1? = null
    private var signed: ByteArray? = null
    private var closed = false
    private val previousPrepare = provider.beforePrepare
    private val previousClose = provider.onClientClose
    private val previousKmsClose = provider.kms.onClientClose
    private val previousSealBoundary = sealHttp.boundary
    private val previousSealNativeBoundary = sealHttp.nativeBoundary
    private val coordinatorTemplates = listOf<Any>(
        registration.process.pools.catalogCoordinator.testOrdinaryDrain,
        registration.process.pools.catalogCoordinator.testOrdinarySeal,
    ).map { executor ->
        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
        Triple(executor, field, field.get(executor) as JdbcTemplate)
    }

    init {
        // Only SQL observation is installed, after genuine SEALED. Same driver/results/holder;
        // no phase original, authority, proof or successful result is manufactured or replaced.
        coordinatorTemplates.forEach { (executor, field, previous) ->
            assertSame(coordinatorProbe.dataSource, previous.dataSource)
            field.set(executor, coordinatorProbe)
        }
        provider.beforePrepare = { previousPrepare(); assertProviderBoundary() }
        provider.onClientClose = { assertDatabaseReleased(); previousClose() }
        provider.kms.onClientClose = { assertDatabaseReleased(); previousKmsClose() }
        sealHttp.boundary = { previousSealBoundary(); assertProviderBoundary() }
        sealHttp.nativeBoundary = { previousSealNativeBoundary(); assertDatabaseReleased() }
    }

    fun begin(clock: Clock = this.clock, nanoTime: () -> Long = this.nanoTime): TestRunOrdinaryDrainV1 {
        check(!closed)
        assertDatabaseReleased()
        return TestRunOrdinaryDrainV1.withHttpFixture(
            registration, ownership, jdbc, audit, clock, nanoTime,
            { assertProviderBoundary(); provider.beforeOpen(); provider.httpClient() },
            { assertProviderBoundary(); provider.kms.httpClient() },
        ).also {
            selected = it
            jdbc.original = it
            coordinatorProbe.original = it
        }
    }

    /**
     * Freeze one genuinely signed fixture artifact for retries of the same closed range. Cutoff is
     * independently observed BEFORE original capture, not injected into that original. Its actual
     * locked capture/admission must reject any intervening drift. No approved result is constructed.
     */
    fun approval(original: TestRunOrdinaryDrainV1 = checkNotNull(selected), clock: Clock = this.clock): ByteArray {
        check(!closed)
        assertSame(registration, original.registration)
        assertDatabaseReleased()
        val control = observer.queryForMap(
            "SELECT publication_epoch, rotation_sequence, rotation_epoch_before FROM complaint_journal_control WHERE data_scope_id = ?", scope)
        val sequence = (control.getValue("rotation_sequence") as Number).toLong()
        check(sequence in 0..1)
        val cutoff = (control.getValue(if (sequence == 0L) "publication_epoch" else "rotation_epoch_before") as Number).toLong()
        val previous = statement
        if (previous != null) {
            assertEquals(cutoff, previous.epochEndInclusive)
            assertEquals(original.runContext.configurationSha256, previous.configurationSha256)
            assertEquals(original.runContext.activationCatalogSha256, previous.activationCatalogSha256)
            return checkNotNull(signed).copyOf()
        }
        val journal = registration.process.consumers.journalConfiguration
        val declaration = journal.declaration()
        val environment = registration.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment
        val input = inputs.authorityInput(journal, environment)
        val role = declaration.authorities.ordinary
        val context = original.runContext
        val deniedAt = clock.instant().minusSeconds(2).epochSecond
        val body = TestOrdinaryDenialStatementV1(
            schemaVersion = 1, purpose = input.purpose, approvalVersion = input.minimumApprovalVersion,
            authorityGrant = input.authorityGrant, implementationAcceptance = input.implementationAcceptance,
            evidenceRetentionPolicy = input.evidenceRetentionPolicy, environment = input.environment,
            dataScopeId = context.dataScopeId, activationCatalogGeneration = context.activationCatalogGeneration,
            activationCatalogSha256 = context.activationCatalogSha256, configurationSha256 = context.configurationSha256,
            terminalEncodingSha256 = context.terminalEncodingSha256,
            initialWriterRegistrySha256 = registration.process.catalogActivation.initialWriterRegistrySha256,
            writerGeneration = input.writerGeneration, databaseIdentity = input.databaseIdentity, restoreIdentity = input.restoreIdentity,
            epochStartInclusive = 1, epochEndInclusive = cutoff, bucket = input.bucket, accountId = input.accountId, region = input.region,
            ordinaryPrefix = journal.ordinaryPrefix, roleId = role.roleId,
            policy = TestTerminalPolicyRefV1(role.policy.policyId, role.policy.version, role.policy.sha256),
            denialEffectiveAtEpochSecond = deniedAt, lastSessionExpiryEpochSecond = deniedAt,
            acceptedRequestBoundSeconds = 1, utcUncertaintySeconds = 0,
            evidenceRetainUntilEpochSecond = sealHttp.horizon.epochSecond,
            boundEvidence = digest(rawClosure[1]),
            effectivePaths = listOf(TestOrdinaryDeniedPathV1("synthetic-ordinary-path", role.roleId, deniedAt, deniedAt, digest(rawClosure[0]))),
        )
        signed = inputs.approval(body, input.keyId)
        statement = body
        return checkNotNull(signed).copyOf()
    }

    fun assertDatabaseReleased() {
        history.assertDatabaseReleased()
        jdbc.assertPhysicallyReleased()
        coordinatorProbe.assertPhysicallyReleased()
        assertEquals(0, registration.process.pools.catalogCoordinator.activeSnapshotOwners())
    }

    /** Dispatch needs committed typed cleanup; native/failure disposal needs physical retirement only. */
    fun assertProviderBoundary() {
        assertDatabaseReleased()
        jdbc.assertReleased()
        coordinatorProbe.assertReleased()
    }

    fun assertReleased() {
        assertDatabaseReleased()
        history.assertReleased() // Retains the exact no-new-catalog-read assertion.
        provider.assertClientsClosed()
        sealHttp.assertDisposed()
        assertEquals(0L, registration.process.publicationLanes.activeOwners().totalOwners)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            assertReleased()
        } finally {
            jdbc.before = {}; jdbc.after = {}
            coordinatorProbe.before = {}; coordinatorProbe.after = {}
            provider.beforePrepare = previousPrepare
            provider.onClientClose = previousClose
            provider.kms.onClientClose = previousKmsClose
            sealHttp.boundary = previousSealBoundary
            sealHttp.nativeBoundary = previousSealNativeBoundary
            coordinatorTemplates.forEach { (executor, field, previous) ->
                assertSame(coordinatorProbe, field.get(executor))
                field.set(executor, previous)
            }
            cleanupOwnedRows()
            signed?.fill(0)
            rawClosure.forEach { it.fill(0) }
        }
    }

    /** TEST-only isolation AFTER caller assertions/finished invocations; never settlement or a result oracle. */
    private fun cleanupOwnedRows() {
        requireConnectionFree()
        checkNotNull(observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                val cleanup = JdbcTemplate(SingleConnectionDataSource(connection, true))
                cleanup.update("DELETE FROM complaint_journal_scan_entries WHERE data_scope_id = ? AND test_only", scope)
                cleanup.update("DELETE FROM complaint_journal_scan_runs WHERE data_scope_id = ? AND test_only", scope)
                // Same narrowly scoped teardown recipe as the existing ordinary-seal fixture. DDL is
                // transactional; rollback also restores the trigger if cleanup is interrupted.
                cleanup.execute("ALTER TABLE complaint_test_terminal_intents DISABLE TRIGGER complaint_test_terminal_immutable")
                cleanup.update("DELETE FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND test_only", scope)
                cleanup.execute("ALTER TABLE complaint_test_terminal_intents ENABLE TRIGGER complaint_test_terminal_immutable")
                assertEquals(1, cleanup.update(
                    "UPDATE complaint_journal_control SET ($DRAIN_FIELDS) = " +
                        "(SELECT $DRAIN_FIELDS FROM jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)) WHERE data_scope_id = ? AND test_only",
                    originalControl, scope))
                connection.commit()
            } catch (problem: Throwable) {
                connection.rollback()
                throw problem
            }
        }
    }

    private fun digest(bytes: ByteArray) = TestTerminalEvidenceDigestV1(Sha256.hex(bytes), bytes.size.toLong())

    private companion object {
        const val DRAIN_FIELDS = "publication_epoch, scan_requested, lease_owner, lease_token, lease_expires_at, updated_at, " +
            "rotation_sequence, rotation_id, rotation_state, rotation_epoch_before, rotation_implementation_schema, rotation_desired_generation, " +
            "rotation_desired_configuration_hash, rotation_database_identity, rotation_restore_identity, rotation_event_writer_generation, " +
            "rotation_accepted_catalog_generation, rotation_accepted_catalog_hash, rotation_trust_bundle_hash, rotation_catalog_writer_generation, " +
            "rotation_request_owner, rotation_request_token, rotation_requested_at, rotation_capture_owner, rotation_capture_token, rotation_captured_at, rotation_epoch_after"
    }
}
