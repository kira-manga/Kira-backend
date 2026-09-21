package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueExceptionV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialDeletionNativeRecordV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withActiveQueueFixture
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withRegisteredInitialCheckpointCreate
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withRegisteredInitialCheckpointDeletion
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceResultV1
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawGetReply
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Real global G1/full-D/capture -> fresh TEST registration -> enrollment -> A's initial EMPTY
 * seal/checkpoint -> C CREATE -> closed gates -> D's full 1..2 drain and 1,2,3 seal history.
 * This independently composes producer entry points; no copied completed cut or fake APPLIED row.
 * The ordinary journal is genuinely empty despite retained nonempty complaint/installation data.
 * The separate nonempty helper below starts with A's actual registered deletion instead.
 */
internal fun withActiveHistoryTerminalCatalogRun(tls: VersionBoundPersistenceConnectedFixture,
    action: (CatalogTestRunTerminalActiveHistoryFixtureV1) -> Unit) {
    val inputs = TestOrdinaryDrainFixtureInputsV1(terminalQuiescence = TestTerminalQuiescenceFixtureInputsV1())
    withRegisteredInitialCheckpointCreate(tls, terminalHistory = inputs) { c ->
        val before = c.counters(); val attempt = c.attempt()
        c.assertApplied(c.create(attempt), attempt); c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE); c.assertReleased()
        val sealer = c.checkpoint.sealer
        assertEquals(PersistenceLifecycleObservation.READY, sealer.runtime.pools.deletion.prepareDeletion())
        TestRunPurgeFixtureV1(sealer.p, sealer.runtime, c.registration, c.exchange.service, sealer.native, inputs).use { f ->
            val history = terminalCatalogActiveRows(f)
            assertEquals(1, history.getValue("V26").size); assertTrue(history.getValue("V29").isEmpty())
            CatalogTerminalHistorySealingProbeV1(f).use { probe ->
                assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, probe.begin().seal())
                probe.assertReleased(); f.assertReleased()
            }
            val drain = f.beginDrain()
            val ordinaryApproval = activeHistoryOrdinaryApproval(f, inputs, drain)
            val ordinaryRaw = f.rawEvidence
            try {
                assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                    drain.drain(ordinaryApproval, ordinaryRaw, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS))
                f.assertReleased(); assertEquals(history, terminalCatalogActiveRows(f))
                assertEquals(listOf("LIST", "LIST"), f.inventoryRequests.map { it.kind }); assertTrue(f.inventoryKeys.requests.isEmpty())
                withDrainedActiveHistoryCatalog(f, inputs, drain, ordinaryApproval, history) { terminal ->
                    action(CatalogTestRunTerminalActiveHistoryFixtureV1(terminal, c))
                }
            } finally { ordinaryApproval.fill(0); ordinaryRaw.forEach { it.fill(0) } }
        }
    }
}

internal enum class TerminalCatalogQueueHistoryV1 { ABSENT, SETTLED, POLLING }

/**
 * Genuine A AUTH -> original PUT/readback -> SQL VERIFY, optionally genuine B APPLY/ACK/SETTLED
 * or malformed-message POLLING. Pins are supplied before full D. D keeps A's exact registered
 * deletion owner/template and rereads the original ciphertext; no second publisher or fake D.
 * One OWNER_DELETE/one installation only. All cases remain source-authored, NOT_RUN.
 */
internal fun withNonemptyActiveHistoryTerminalCatalogRun(tls: VersionBoundPersistenceConnectedFixture,
    queue: TerminalCatalogQueueHistoryV1 = TerminalCatalogQueueHistoryV1.ABSENT,
    action: (CatalogTestRunTerminalActiveHistoryFixtureV1) -> Unit) {
    val inputs = TestOrdinaryDrainFixtureInputsV1(terminalQuiescence = TestTerminalQuiescenceFixtureInputsV1())
    fun continueFrom(a: TestRegisteredInitialCheckpointDeletionFixtureV1, b: TestActiveOwnerDeleteQueueFixtureV1?) {
        a.assertReleased()
        val record = checkNotNull(a.record)
        assertSame(checkNotNull(a.event), record.event); assertEquals(2L, record.event.comparison.epoch)
        val producerCounts = a.native.counts(); val originalBytes = record.stored.bytes.copyOf()
        val queueOrder = b?.raw?.order?.toList(); val acknowledgements = b?.raw?.ackRequests?.toList()
        fun queueClients() = b?.raw?.let { listOf(it.sts.createdClients, it.kms.createdClients, it.sqs.createdClients, it.s3Created) }
        val queueOpenings = queueClients(); val queueSqlCalls = b?.calls?.size
        val sealer = a.checkpoint.sealer
        assertSame(a.runtime, sealer.runtime)
        TestRunPurgeFixtureV1(sealer.p, a.runtime, a.registration, a.audit, sealer.native, inputs).use { f ->
            val history = terminalCatalogActiveRows(f)
            assertEquals(1, history.getValue("V26").size)
            assertEquals(if (b == null) 0 else 1, history.getValue("V29").size)
            CatalogTerminalHistorySealingProbeV1(f).use { probe ->
                assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, probe.begin().seal())
                probe.assertReleased(); f.assertReleased()
            }
            CatalogTerminalHistoryDrainProbeV1(f, a).use { probe ->
                val native = CatalogTerminalOriginalOrdinaryHttpV1(a.process.consumers.journalConfiguration, record, probe::assertReleased)
                val drain = probe.begin(native::client, native.keys::httpClient)
                val approval = activeHistoryOrdinaryApproval(f, inputs, drain); val raw = f.rawEvidence
                try {
                    terminalCatalogHistoryBoundaries(f, probe::assertReleased) {
                        assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                            drain.drain(approval, raw, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS))
                    }
                    probe.assertReleased(); native.assertReadPairs(2); f.assertReleased(); a.assertReleased()
                    assertTrue(f.inventoryRequests.isEmpty() && f.inventoryKeys.requests.isEmpty(), "The empty helper's new deletion pair and raw graph are never used.")
                    assertEquals(history, terminalCatalogActiveRows(f))
                    assertEquals("APPLIED", a.publication()["state"])
                    assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, f.scope))
                    probe.assertPrimaryApplied(queue != TerminalCatalogQueueHistoryV1.SETTLED)
                    withDrainedActiveHistoryCatalog(f, inputs, drain, approval, history, record) { terminal ->
                        action(CatalogTestRunTerminalActiveHistoryFixtureV1(terminal, a.creators.single(), a, b))
                    }
                } finally { approval.fill(0); raw.forEach { it.fill(0) }; native.assertClosed() }
            }
        }
        assertArrayEquals(originalBytes, record.stored.bytes); originalBytes.fill(0)
        assertEquals(producerCounts, a.native.counts(), "D/E never re-encrypt, PUT or reopen A's producer graph.")
        assertEquals(queueOrder, b?.raw?.order); assertEquals(acknowledgements, b?.raw?.ackRequests)
        assertEquals(queueOpenings, queueClients()); assertEquals(queueSqlCalls, b?.calls?.size)
        b?.assertReleased()
    }
    if (queue === TerminalCatalogQueueHistoryV1.ABSENT) {
        withRegisteredInitialCheckpointDeletion(tls, ComplaintJournalDeletionKindV1.OWNER_DELETE, terminalHistory = inputs) { a ->
            a.authorize(); a.publish(); a.verify(); continueFrom(a, null)
        }
    } else withActiveQueueFixture(tls, terminalHistory = inputs) { b ->
        val before = b.counters()
        if (queue === TerminalCatalogQueueHistoryV1.POLLING) {
            b.raw.primaryBody = "{" // Genuine failed original: no native journal dispatch, APPLY or ACK.
            val original = b.begin()
            assertThrows<TestActiveOwnerDeleteQueueExceptionV1> { b.poll(original) }
            assertTrue(b.raw.requests.isEmpty() && b.raw.kms.requests.isEmpty() && b.raw.ackRequests.isEmpty())
            assertEquals(0L, b.count("complaint_deletion_journal_applied"))
        } else {
            val completed = b.poll()
            assertEquals(1, completed.primaryAcknowledged); assertEquals(0, completed.dlqAcknowledged)
            assertEquals(1L, b.count("complaint_deletion_journal_applied"))
        }
        b.assertReleased(); b.assertNoAuthority(); assertEquals(queue.name, b.observation()?.get("state"))
        val after = b.counters()
        before.forEach { (counter, old) ->
            val observation = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
            val applied = queue === TerminalCatalogQueueHistoryV1.SETTLED
            val used = if (applied) (OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit.scaled(2))[counter] else 0L
            val refund = if (applied) OwnerDeleteLiteralCharges.content[counter] else 0L
            assertEquals(old.copy(free = old.free + refund - observation, actual = old.actual + used - refund + observation,
                recovery = old.recovery - used), after.getValue(counter), counter.storedName)
        }
        continueFrom(b.precursor, b) // Keep the actual observation alive until every D/E assertion.
    }
}

/** Shared actual post-drain producers; accepts an original drain, never a supplied completed cut. */
private fun withDrainedActiveHistoryCatalog(f: TestRunPurgeFixtureV1, inputs: TestOrdinaryDrainFixtureInputsV1,
    drain: TestRunOrdinaryDrainV1, ordinaryApproval: ByteArray, history: Map<String, List<String>>,
    ordinaryRecord: TestRegisteredInitialDeletionNativeRecordV1? = null, action: (CatalogTestRunTerminalFixtureV1) -> Unit) {
    val preparation = TestRunInstallationManifestV1.begin(drain)
    TestInstallationManifestSqlProbeV1(f, expectedDrain = drain).use { probe ->
        probe.original = preparation
        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, preparation.prepare())
    }
    val manifest = preparation.beginPublication()
    assertEquals(TestRunInstallationManifestPublicationResultV1.ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED, manifest.publish())
    assertEquals(1L, manifest.authenticatedSummary().installationCount); assertEquals(1, manifest.capturedSource().count)
    val purge = TestRunPurgeSqlProbeV1(f).use { probe ->
        val value = manifest.beginPurgePublication().also { probe.original = it }
        terminalCatalogHistoryBoundaries(f, { probe.assertReleased(requireCommitted = false) }) {
            assertEquals(TestRunPurgePublicationResultV1.PURGE_AUTHENTICATED_AND_VERIFIED, value.publish())
        }
        probe.assertReleased(); f.assertReleased(); value
    }
    val seal = TestTerminalEpochSealSqlProbeV1(f).use { probe ->
        val value = purge.beginTerminalEpochSeal().also { probe.original = it }
        terminalCatalogHistoryBoundaries(f, { probe.assertReleased(requireCommitted = false) }) {
            assertEquals(TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED, value.seal())
        }
        probe.assertReleased(); f.assertReleased(); value
    }
    TestTerminalQuiescenceSqlProbeV1(f).use { probe ->
        val d = seal.beginTerminalQuiescence().also { probe.original = it }
        val terminalInputs = checkNotNull(inputs.terminalQuiescence)
        val approval = terminalInputs.approval(terminalInputs.statement(d)) // One exact PSS input retained through E.
        val raw = terminalInputs.rawEvidence
        try {
            terminalCatalogHistoryBoundaries(f, { probe.assertReleased(requireCommitted = false) }) {
                assertEquals(TestRunTerminalQuiescenceResultV1.TERMINAL_PREFIX_QUIESCENT_AND_SEALED, d.quiesce(approval, raw))
            }
            probe.assertReleased(); f.assertReleased(); assertEquals(history, terminalCatalogActiveRows(f))
            CatalogTestRunTerminalFixtureV1(f, d, approval, raw, ordinaryApproval, ordinaryRecord).use(action)
        } finally { approval.fill(0); raw.forEach { it.fill(0) } }
    }
}

internal class CatalogTestRunTerminalActiveHistoryFixtureV1(
    val catalog: CatalogTestRunTerminalFixtureV1,
    val create: TestRegisteredInitialCheckpointCreateFixtureV1,
    val deletion: TestRegisteredInitialCheckpointDeletionFixtureV1? = null,
    val queue: TestActiveOwnerDeleteQueueFixtureV1? = null,
) {
    val initialSeal = catalog.record.sealSet.records().first()
    fun historyRows(): Map<String, List<String>> = terminalCatalogActiveRows(catalog.f)
    fun preservedRows(): Map<String, List<String>> = CatalogTestRunTerminalCasesV1.preservedRows(catalog) + historyRows() + (deletion?.image() ?: emptyMap())
    fun fullImage(): Map<String, List<String>> = CatalogTestRunTerminalCasesV1.fullImage(catalog) + historyRows() + (deletion?.image() ?: emptyMap())
}

/** Exact row+xmin observations, not a current-history admission or recovery capability. */
private fun terminalCatalogActiveRows(f: TestRunPurgeFixtureV1): Map<String, List<String>> = mapOf(
    "V26" to "complaint_test_active_seal_intents", "V29" to "complaint_test_active_queue_observations",
).mapValues { (_, table) -> f.observer.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t " +
    "WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, f.scope) }

/** Input signing only, distinct from the legacy helper's asserted no-A 1..1 history. */
private fun activeHistoryOrdinaryApproval(f: TestRunPurgeFixtureV1, inputs: TestOrdinaryDrainFixtureInputsV1,
    original: TestRunOrdinaryDrainV1): ByteArray {
    val journal = f.registration.process.consumers.journalConfiguration
    val pin = inputs.authorityInput(journal, f.registration.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment)
    val role = journal.declaration().authorities.ordinary; val context = original.runContext
    val at = Instant.now().minusSeconds(2).epochSecond; val raw = f.rawEvidence
    fun digest(value: ByteArray) = TestTerminalEvidenceDigestV1(Sha256.hex(value), value.size.toLong())
    return try { inputs.approval(TestOrdinaryDenialStatementV1(1, pin.purpose, pin.minimumApprovalVersion, pin.authorityGrant,
        pin.implementationAcceptance, pin.evidenceRetentionPolicy, pin.environment, context.dataScopeId,
        context.activationCatalogGeneration, context.activationCatalogSha256, context.configurationSha256, context.terminalEncodingSha256,
        f.registration.process.catalogActivation.initialWriterRegistrySha256, pin.writerGeneration, pin.databaseIdentity, pin.restoreIdentity,
        1, 2, pin.bucket, pin.accountId, pin.region, journal.ordinaryPrefix, role.roleId,
        TestTerminalPolicyRefV1(role.policy.policyId, role.policy.version, role.policy.sha256), at, at, 1, 0,
        f.sealHttp.horizon.epochSecond, digest(raw[1]), listOf(TestOrdinaryDeniedPathV1("synthetic-terminal-active-history-path", role.roleId, at, at, digest(raw[0])))), pin.keyId)
    } finally { raw.forEach { it.fill(0) } }
}

private fun <T> terminalCatalogHistoryBoundaries(f: TestRunPurgeFixtureV1, check: () -> Unit, action: () -> T): T {
    val boundary = f.sealHttp.boundary; val close = f.sealHttp.nativeBoundary
    f.sealHttp.boundary = { boundary(); check() }; f.sealHttp.nativeBoundary = { close(); check() }
    return try { action() } finally { f.sealHttp.boundary = boundary; f.sealHttp.nativeBoundary = close }
}

/**
 * Read-only raw transport over ONE original A object and its private wrapped-key responder.
 * D and E open their own actual readers/clients. This is not another producer, key export,
 * reconstructed event, supplied readback, SQL result or native-cleanup receipt.
 */
internal class CatalogTerminalOriginalOrdinaryHttpV1(private val journal: TestOwnerDeleteJournalConfigurationV1,
    private val record: TestRegisteredInitialDeletionNativeRecordV1, private val boundary: () -> Unit) {
    val requests = mutableListOf<JournalPublisherHttpRequest>()
    val keys = AwsJournalKmsFixture()
    private var created = 0
    private var closed = 0
    private var returnedCloses = 0
    private val assertion = AtomicReference<AssertionError?>()
    init {
        keys.beforePrepare = { checked { boundary() } }
        keys.onClientClose = { checked { boundary() } }
        keys.respond = { request -> checked {
            boundary(); signedKms(request)
            assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target(), "Readback cannot generate another data key.")
            assertEquals(journal.declaration().encryption.keyArn, request.fields()["KeyId"].textValue())
            assertEquals(record.kmsContext, request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() })
            record.decrypt(request).apply {
                beforeCall = { checked { boundary() } }; beforeRead = { checked { boundary() } }
                onAbort = { checked { boundary() } }; onClose = { checked { boundary() } }
            }
        } }
    }
    fun client(): SdkHttpClient {
        checked { boundary() }; created++
        return journalPublisherRawHttpClient(requests, { checked { boundary() } }, {}, {
            closed++; checked { boundary() }; returnedCloses++
        }) { request -> checked {
            boundary()
            val location = journal.declaration().journalLocation; val stored = record.stored
            journalPublisherRawAssertSigned(request, location.region, location.accountId, AwsJournalKmsFixture.CREDENTIALS)
            assertTrue(request.body.isEmpty())
            when (request.kind) {
                "LIST" -> {
                    assertEquals(listOf(journal.ordinaryPrefix), request.http.rawQueryParameters()["prefix"])
                    assertEquals(listOf("2"), request.http.rawQueryParameters()["max-keys"])
                    assertTrue(request.http.rawQueryParameters().keys.none { it in setOf("key-marker", "version-id-marker") })
                    OwnerDeleteAllJournalPublisherFixture.xmlReply(journalPublisherRawListDocument(location.bucket, journal.ordinaryPrefix, listOf(stored)))
                }
                "GET" -> {
                    assertEquals("/${location.bucket}/${stored.key}", request.http.encodedPath())
                    assertEquals(listOf(stored.version), request.http.rawQueryParameters()["versionId"])
                    journalPublisherRawGetReply(location.region, stored.copy(bytes = stored.bytes.copyOf()))
                }
                else -> error("Original ordinary readback permits only full-prefix LIST and exact-version GET, never PUT.")
            }
        } }
    }
    fun assertReadPairs(passes: Int) {
        assertEquals(List(passes) { listOf("LIST", "GET") }.flatten(), requests.map { it.kind })
        assertEquals(passes, keys.requests.size)
        keys.requests.forEach { assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, it.target()) }
        requests.filter { it.kind == "GET" }.forEach { assertArrayEquals(record.stored.bytes, checkNotNull(it.reply).bytes) }
        assertClosed()
    }
    fun assertClosed() {
        requireConnectionFree(); assertion.get()?.let { throw it }
        assertEquals(created, closed); assertEquals(created, returnedCloses)
        assertEquals(keys.createdClients, keys.closedClients); assertEquals(keys.createdClients, keys.returnedClientCloses)
        requests.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertTrue(it.responseReturned); assertEquals(1, checkNotNull(it.reply).closes) }
        keys.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(1, it.closes) }
    }
    private fun signedKms(request: JournalKmsHttpRequest) {
        val http = request.http; val region = journal.declaration().journalLocation.region; val credentials = AwsJournalKmsFixture.CREDENTIALS
        assertEquals("https", http.protocol()); assertEquals("kms.$region.amazonaws.com", http.host())
        assertEquals(credentials.sessionToken(), http.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val authorization = http.firstMatchingHeader("Authorization").orElseThrow()
        val scope = authorization.substringAfter("Credential=${credentials.accessKeyId()}/").substringBefore(',')
        val signed = authorization.substringAfter("SignedHeaders=").substringBefore(',').split(';')
        val headers = signed.joinToString("") { name -> "$name:${http.firstMatchingHeader(name).orElseThrow().trim().replace(Regex("[ \\t]+"), " ")}\n" }
        assertTrue(http.rawQueryParameters().isEmpty())
        val canonical = "${http.method()}\n${http.encodedPath().ifEmpty { "/" }}\n\n$headers\n${signed.joinToString(";")}\n${Sha256.hex(request.json.toByteArray())}"
        val date = http.firstMatchingHeader("x-amz-date").orElseThrow()
        assertEquals("${date.take(8)}/$region/kms/aws4_request", scope)
        fun hmac(key: ByteArray, text: String) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray()) }
        val day = hmac(("AWS4" + credentials.secretAccessKey()).toByteArray(), date.take(8))
        val regional = hmac(day, region); val service = hmac(regional, "kms"); val signing = hmac(service, "aws4_request")
        try { assertEquals(HexFormat.of().formatHex(hmac(signing, "AWS4-HMAC-SHA256\n$date\n$scope\n${Sha256.hex(canonical.toByteArray())}")), authorization.substringAfter("Signature=")) }
        finally { day.fill(0); regional.fill(0); service.fill(0); signing.fill(0) }
    }
    private fun <T> checked(action: () -> T): T = try { action() } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
}
