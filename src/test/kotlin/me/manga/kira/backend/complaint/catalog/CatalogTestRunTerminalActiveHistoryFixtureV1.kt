package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withRegisteredInitialCheckpointCreate
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalEpochSealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceResultV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Instant

/**
 * Real global G1/full-D/capture -> fresh TEST registration -> enrollment -> A's initial EMPTY
 * seal/checkpoint -> C CREATE -> closed gates -> D's full 1..2 drain and 1,2,3 seal history.
 * This independently composes producer entry points; no copied completed cut or fake APPLIED row.
 * The ordinary journal is genuinely empty despite retained nonempty complaint/installation data.
 * B/nonempty ordinary composition is a separate pending authentic-producer prerequisite.
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
                    val approval = terminalInputs.approval(terminalInputs.statement(d)) // One real PSS input retained through E.
                    val raw = terminalInputs.rawEvidence
                    try {
                        terminalCatalogHistoryBoundaries(f, { probe.assertReleased(requireCommitted = false) }) {
                            assertEquals(TestRunTerminalQuiescenceResultV1.TERMINAL_PREFIX_QUIESCENT_AND_SEALED, d.quiesce(approval, raw))
                        }
                        probe.assertReleased(); f.assertReleased(); assertEquals(history, terminalCatalogActiveRows(f))
                        CatalogTestRunTerminalFixtureV1(f, d, approval, raw, ordinaryApproval).use { terminal ->
                            action(CatalogTestRunTerminalActiveHistoryFixtureV1(terminal, c))
                        }
                    } finally { approval.fill(0); raw.forEach { it.fill(0) } }
                }
            } finally { ordinaryApproval.fill(0); ordinaryRaw.forEach { it.fill(0) } }
        }
    }
}

internal class CatalogTestRunTerminalActiveHistoryFixtureV1(
    val catalog: CatalogTestRunTerminalFixtureV1,
    val create: TestRegisteredInitialCheckpointCreateFixtureV1,
) {
    val initialSeal = catalog.record.sealSet.records().first()
    fun historyRows(): Map<String, List<String>> = terminalCatalogActiveRows(catalog.f)
    fun preservedRows(): Map<String, List<String>> = CatalogTestRunTerminalCasesV1.preservedRows(catalog) + historyRows()
    fun fullImage(): Map<String, List<String>> = CatalogTestRunTerminalCasesV1.fullImage(catalog) + historyRows()
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
