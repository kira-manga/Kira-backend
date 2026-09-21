package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalChunkV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalClosureV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalInstallationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalPurgeV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalRecordV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisCreationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalManifestV4
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalCanonicalV4
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreparedRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogInventoryChainVerifier
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialDeletionNativeRecordV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.SdkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Genuine registration -> drain -> manifest -> purge -> terminal seal -> D, never a success factory.
 * Exact signed/raw denial inputs are kept externally for E; the fixtures' IAM/retention statements
 * remain synthetic. An unused or one-enrollment run does NOT qualify ACTIVE/V26, B/V29 or erasure.
 */
internal fun withTerminalCatalogRun(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean = false,
    action: (CatalogTestRunTerminalFixtureV1) -> Unit) {
    val inputs = TestTerminalQuiescenceFixtureInputsV1()
    withTerminalEpochSealRun(tls, enrolled = enrolled, terminalQuiescence = inputs) { f, purge, epochProbe ->
        val seal = purge.beginTerminalEpochSeal().also { epochProbe.original = it }
        assertEquals(TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED, seal.seal())
        epochProbe.assertReleased(); f.assertReleased()
        TestTerminalQuiescenceSqlProbeV1(f).use { probe ->
            var diagnosticPhase = "QUIESCENCE_BEGIN"
            try {
                val d = seal.beginTerminalQuiescence().also { probe.original = it }
                diagnosticPhase = "APPROVAL"
                val approval = inputs.approval(inputs.statement(d)) // PSS ONCE, before the actual D call.
                val raw = inputs.rawEvidence
                try {
                    diagnosticPhase = "QUIESCE"
                    assertEquals(TestRunTerminalQuiescenceResultV1.TERMINAL_PREFIX_QUIESCENT_AND_SEALED, d.quiesce(approval, raw))
                    diagnosticPhase = "QUIESCENCE_RELEASE"
                    probe.assertReleased(); f.assertReleased()
                    diagnosticPhase = "CATALOG_HANDOFF"
                    val fixture = try { CatalogTestRunTerminalFixtureV1(f, d, approval, raw) }
                    catch (problem: CatalogTestRunTerminalExceptionV1) {
                        try { System.err.println("TEST_CATALOG_TERMINAL_FIXTURE_FAILURE stage=CONSTRUCT category=TERMINAL") }
                        catch (_: Throwable) { /* Preserve the original constructor failure. */ }
                        throw problem
                    }
                    fixture.use { actual ->
                        // PROJECT cleanup leaves its global DB lease intact; wait before E owns a fresh budget.
                        actual.awaitRealLeaseExpiry()
                        try { action(actual) }
                        catch (problem: CatalogTestRunTerminalExceptionV1) {
                            try { System.err.println("TEST_CATALOG_TERMINAL_FIXTURE_FAILURE stage=ACTION category=TERMINAL") }
                            catch (_: Throwable) { /* Preserve the original action failure and use cleanup. */ }
                            throw problem
                        }
                    }
                } finally { approval.fill(0); raw.forEach { it.fill(0) } }
            } catch (problem: Throwable) {
                runCatching { probe.reportUnexpectedFailure(problem, diagnosticPhase) }
                throw problem
            }
        }
    }
}

/** Raw transports/SQL observations only. No supplied mutation, lease, readback, signature result or cleanup receipt. */
internal class CatalogTestRunTerminalFixtureV1(
    val f: TestRunPurgeFixtureV1,
    val d: TestRunTerminalQuiescenceV1,
    terminalApproval: ByteArray,
    terminalRaw: List<ByteArray>,
    ordinaryApprovalInput: ByteArray? = null,
    ordinaryRecord: TestRegisteredInitialDeletionNativeRecordV1? = null,
) : AutoCloseable {
    val process = f.registration.process
    val scope = f.scope
    val token: UUID = UUID.randomUUID()
    val observer = f.observer
    val evidence = f.p.f.rows.evidence
    val limit = f.p.run.installationLimit
    val expected = CatalogTestRunTerminalCanonicalV4.fromRetained(process, limit)
    val prefix = evidence.prefix + f.p.f.envelope // Actual V3 signature from its original durable release.
    // The A-history fixture signs its wider 1..2 denial once outside the legacy no-A helper.
    // This is the exact input used by the actual drain, never a substituted admission/result.
    private val ordinaryApproval = ordinaryApprovalInput?.copyOf() ?: f.ordinaryApproval
    private val ordinaryRaw = f.rawEvidence
    private val terminalApproval = terminalApproval.copyOf()
    private val terminalRaw = terminalRaw.map(ByteArray::copyOf)
    private val parent = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-terminal-catalog-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val root: Path = Files.createDirectory(parent.resolve("release"),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val record: CatalogTestRunTerminalRecordV1 = record()
    private val creationEpochSecond = maxOf(d.acquisition.sampleUtc().epochSecond,
        record.progress.completedCuts().maxOf { it.denial.secondInventory.completedAtEpochSecond })
    val unsigned: ByteArray = expected.assemble(
        OfflineCatalogInventoryChainVerifier.verifyTestRunActivationChain(prefix.asSequence(), evidence.initial, evidence.current,
            process.catalogReadback.chainPolicy, expected.activation), record,
        OfflineCatalogGenesisCreationV1("catalog-approver-a", creationEpochSecond),
        listOf(OfflineCatalogGenesisApprovalV1("catalog-approver-a", creationEpochSecond), OfflineCatalogGenesisApprovalV1("catalog-approver-b", creationEpochSecond)))
    val manifest: OfflineCatalogTestRunTerminalManifestV4 = CanonicalJson.json.decodeFromString(
        OfflineCatalogTestRunTerminalManifestV4.serializer(), unsigned.toString(Charsets.UTF_8))
    val http = CatalogGenesisPublishHttpFixture(ByteArray(0), manifest.creation.createdAtEpochSecond,
        prefixBytes = prefix, prefixRetainUntil = evidence.retainedUntil,
        prefixVersions = evidence.prefix.indices.map { "catalog-version-${it + 1}" } + checkNotNull(f.p.f.http.primaryVersion),
        prefixRetentions = evidence.prefix.map { evidence.retainedUntil } + f.p.f.http.primaryRetention)
    val signing = AwsJournalKmsFixture()
    val signatures = mutableListOf<ByteArray>()
    val originalOrdinary = ordinaryRecord?.let { CatalogTerminalOriginalOrdinaryHttpV1(process.consumers.journalConfiguration, it, ::released) }
    val ordinaryKeys = originalOrdinary?.keys ?: AwsJournalKmsFixture()
    val ordinaryRequests = originalOrdinary?.requests ?: mutableListOf<JournalPublisherHttpRequest>()
    private var ordinaryCreated = 0
    private var ordinaryClosed = 0
    private val originals = mutableListOf<CatalogTestRunTerminalV1>()
    val probe = CatalogTestRunTerminalSqlProbeV1(f, f.runtime)
    var beforeSign: () -> Unit = {}
    private val oldBoundary = f.sealHttp.boundary
    private val oldNative = f.sealHttp.nativeBoundary
    private var extraReleased: () -> Unit = {}

    init {
        requireConnectionFree()
        f.sealHttp.boundary = { oldBoundary(); released() }
        f.sealHttp.nativeBoundary = { oldNative(); released() }
        http.beforeRead = ::released
        http.beforePut = ::released
        http.afterPutClientClose = ::released
        http.afterReadClientClose = ::released
        if (originalOrdinary == null) {
            ordinaryKeys.beforePrepare = { error("This genuinely empty ordinary history must never decrypt an invented event.") }
            ordinaryKeys.onClientClose = ::released
        }
        signing.respond = { request ->
            released()
            assertTrue(signatures.isEmpty(), "The terminal envelope is signed once; the activation signature is a different original.")
            assertEquals("TrentService.Sign", request.target())
            val fields = request.fields()
            assertEquals(setOf("KeyId", "Message", "MessageType", "SigningAlgorithm"), fields.fieldNames().asSequence().toSet())
            assertEquals(FullTestCatalogInputs.KEY_ARN, fields["KeyId"].textValue())
            assertEquals("RAW", fields["MessageType"].textValue())
            assertEquals("RSASSA_PSS_SHA_256", fields["SigningAlgorithm"].textValue())
            val frame = Base64.getDecoder().decode(fields["Message"].textValue())
            assertArrayEquals(OfflineCatalogGenesisFixture.independentFrame(evidence.signerId, unsigned), frame)
            beforeSign()
            val key = if (evidence.signerId == "catalog-old") OfflineTrustBundleFixture.firstSigner else OfflineTrustBundleFixture.secondSigner
            val signature = Signature.getInstance("RSASSA-PSS").run {
                setParameter(OfflineTrustBundleFixture.parameters); initSign(key.private); update(frame); sign()
            }
            signatures.add(signature.copyOf()); frame.fill(0)
            JournalKmsHttpReply("""{"KeyId":"${FullTestCatalogInputs.KEY_ARN}","SigningAlgorithm":"RSASSA_PSS_SHA_256","Signature":"${Base64.getEncoder().encodeToString(signature)}"}""").apply {
                beforeCall = ::released; beforeRead = ::released; onAbort = ::released; onClose = ::released
            }
        }
    }

    fun request(ordinary: ByteArray = ordinaryApproval, ordinaryEvidence: List<ByteArray> = ordinaryRaw,
        terminal: ByteArray = terminalApproval, terminalEvidence: List<ByteArray> = terminalRaw, custody: Path = root) =
        CatalogTestRunTerminalRequestV1(custody, ordinary, ordinaryEvidence, terminal, terminalEvidence,
            AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS, CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS,
            S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun begin(): CatalogTestRunTerminalV1 = d.beginTerminalCatalog().withHttpFixtures(signing::httpClient,
        http::putClient, http::readClient, ::ordinaryClient, ordinaryKeys::httpClient, Clock.systemUTC()).also(originals::add)

    /** New retained runtime process/pools with the same pre-D pins. The token is not a recovery capability. */
    fun freshRecovery(selectedToken: UUID = token, action: (CatalogTestRunTerminalPreparedRecoveryV1, CatalogTestRunTerminalSqlProbeV1) -> Unit) {
        val runtime = VersionBoundPersistenceConnectedFixture(f.runtime.database, endpointPort = f.runtime.endpointPort,
            testIntake = evidence.intakeAssembly, testRegistrationPredecessor = f.runtime)
        var bodyFailure: Throwable? = null
        try {
            runtime.bind(); runtime.start()
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
            val target = evidence.processOn(runtime.pools)
            CatalogTestRunTerminalSqlProbeV1(f, runtime).use { probe ->
                extraReleased = { probe.assertReleased(requireCommitted = false) }
                val recovery = CatalogTestRunTerminalPreparedRecoveryV1.begin(target, selectedToken, limit)
                    .withHttpFixtures(signing::httpClient, http::putClient, http::readClient, ::ordinaryClient, ordinaryKeys::httpClient, Clock.systemUTC())
                try { action(recovery, probe) }
                finally { runCatching(recovery::close); extraReleased = {} }
                probe.assertReleased(requireCommitted = false)
            }
        } catch (problem: Throwable) { bodyFailure = problem; throw problem }
        finally {
            extraReleased = {}
            try { runtime.closeWith(f.runtime) } catch (cleanup: Throwable) {
                val problem = bodyFailure
                if (problem == null) throw cleanup
                if (cleanup !== problem) problem.addSuppressed(cleanup)
            }
        }
    }

    /** Setup/recovery waits for ACTUAL DB-clock expiry. No fixture UPDATE/refund/replacement release. */
    fun awaitRealLeaseExpiry() {
        released()
        val deadline = System.nanoTime() + 35_000_000_000L
        while (observer.queryForObject("SELECT lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp() " +
                "FROM complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'", Boolean::class.java) != true) {
            check(System.nanoTime() < deadline); Thread.sleep(50)
        }
    }

    fun released() { requireConnectionFree(); f.assertDatabaseReleased(); probe.assertReleased(requireCommitted = false); extraReleased() }
    fun files(): Map<String, String> = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList().associate {
        root.relativize(it).toString() to Sha256.hex(Files.readAllBytes(it))
    } }
    fun bytes(column: String): ByteArray {
        require(column in setOf("unsigned_bytes", "signer_one_signature", "envelope_bytes", "primary_evidence_bytes", "replica_evidence_bytes"))
        return checkNotNull(observer.queryForObject("SELECT $column FROM complaint_catalog_mutations WHERE operation_token = ?", ByteArray::class.java, token))
    }

    private fun ordinaryClient(): SdkHttpClient {
        originalOrdinary?.let { return it.client() }
        released(); ordinaryCreated++
        return journalPublisherRawHttpClient(ordinaryRequests, ::released, {}, { ordinaryClosed++; released() }) { request ->
            val journal = process.consumers.journalConfiguration; val location = journal.declaration().journalLocation
            journalPublisherRawAssertSigned(request, location.region, location.accountId, AwsJournalKmsFixture.CREDENTIALS)
            assertEquals("LIST", request.kind); assertTrue(request.body.isEmpty())
            assertEquals(listOf(journal.ordinaryPrefix), request.http.rawQueryParameters()["prefix"])
            assertEquals(listOf("2"), request.http.rawQueryParameters()["max-keys"])
            assertTrue(request.http.rawQueryParameters().keys.none { it in setOf("key-marker", "version-id-marker") })
            OwnerDeleteAllJournalPublisherFixture.xmlReply(journalPublisherRawListDocument(location.bucket, journal.ordinaryPrefix, emptyList()))
        }
    }
    private fun record(): CatalogTestRunTerminalRecordV1 {
        val json = TestTerminalJsonV1(process.consumers.journalConfiguration)
        val source = d.manifest.capturedSource()
        val chunks = (0 until source.count).map { index ->
            val bytes = checkNotNull(observer.queryForObject("SELECT canonical_bytes FROM complaint_test_terminal_intents " +
                "WHERE data_scope_id = ? AND object_kind = 'INSTALLATION_MANIFEST' AND object_ordinal = ?", ByteArray::class.java, scope, index))
            try {
                val actual = json.installationManifest(bytes)
                CatalogTestRunTerminalChunkV1(index, actual.eventId, actual.installationCount, actual.retiredCount, actual.deletedCount,
                    actual.entriesSha256, d.manifest.authenticatedFacts(index).objectRef)
            } finally { bytes.fill(0) }
        }
        val actual = d.purge.authenticatedFactsForSeal(); val roots = d.purge.capturedRoots()
        val purge = TestTerminalPurgeV1.create(TestTerminalEventContextV1(d.runContext, actual.id, d.epoch,
            process.consumers.journalConfiguration.declaration().writer.generationId), d.control.cutoff, d.ordinarySeal,
            roots.seals, roots.inventory, d.manifest.authenticatedSummary())
        val journal = process.consumers.journalConfiguration
        return CatalogTestRunTerminalRecordV1(token.toString(), d.runContext.activationCatalogGeneration + 1, d.runContext.activationCatalogSha256,
            checkNotNull(observer.queryForObject("SELECT sealed_at FROM complaint_test_runs WHERE data_scope_id = ?", java.sql.Timestamp::class.java, scope)).toInstant().epochSecond,
            CatalogTestRunTerminalClosureV1("SEALED", journal.declaration().writer.generationId, process.databaseIdentity.toString(), process.restoreIdentity.toString(),
                journal.ordinaryPrefix, journal.sealTerminalPrefix, 1, d.control.cutoff, d.epoch),
            CatalogTestRunTerminalPurgeV1(actual.objectRef, purge), CatalogTestRunTerminalInstallationV1(d.manifest.authenticatedSummary(), chunks),
            d.fullSealSet, d.authenticatedProgress())
    }

    override fun close() {
        beforeSign = {}; http.beforeRead = {}; http.beforePut = {}; http.afterPutAccepted = {}
        http.afterPutClientClose = {}; http.afterReadClientClose = {}
        originals.forEach { runCatching(it::close) } // Failed originals stay failed; never relabel this as success.
        try {
            released(); probe.assertReleased(requireCommitted = false)
            assertEquals(ordinaryCreated, ordinaryClosed)
            assertEquals(signing.createdClients, signing.closedClients)
            assertEquals(ordinaryKeys.createdClients, ordinaryKeys.closedClients)
            originalOrdinary?.assertClosed()
            assertEquals(http.put.createdClients, http.put.closedClients)
            assertEquals(http.read.createdClients, http.read.closedClients)
            http.assertNoLostAssertions()
            ordinaryRequests.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(1, checkNotNull(it.reply).closes) }
        } finally {
            f.sealHttp.boundary = oldBoundary; f.sealHttp.nativeBoundary = oldNative; probe.close()
            // TEST isolation only, after assertions/resource disposal. Not a producer erasure/reconciliation path.
            observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ? AND operation_type = 'TEST_RUN_TERMINAL' AND data_scope_id = ?", token, scope)
            Files.walk(parent).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            ordinaryApproval.fill(0); terminalApproval.fill(0); ordinaryRaw.forEach { it.fill(0) }; terminalRaw.forEach { it.fill(0) }
            signatures.forEach { it.fill(0) }
        }
    }
}
