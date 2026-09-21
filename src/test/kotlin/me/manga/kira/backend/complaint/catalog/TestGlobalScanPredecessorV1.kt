package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipant
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalFactoryBinding
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseTransitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Existing genuine AUTHOR -> first-D -> G1 refresh ->
 * original LIVE campaign/request/nonpooled-E capture, all retired before a fresh TEST signer root.
 * Capture only settles the original global request. It is NOT a global seal/checkpoint/health proof.
 * No G1/control/D seed, synthetic lease expiry, gate opening or new production ownership seam.
 */
internal fun withTestGlobalScanPredecessor(
    tls: VersionBoundPersistenceConnectedFixture,
    capacity: ComplaintCapacityPolicyV1,
    action: (VersionBoundPersistenceConnectedFixture, TestGlobalScanPredecessorV1) -> Unit,
) = CatalogSignerRotationD7Fixture(tls).use { live ->
    live.prepareEpochRotation(capacity)
    val captured = captureGlobalRequest(live)
    val predecessor = TestGlobalScanPredecessorV1(live, captured)
    live.retireRuntime() // Original LIVE coordinator, ordinary/deletion pools, E actors and shared Timer retire first.
    tls.close() // AUTHOR already stopped this old peer; complete its real root/trust/session cleanup too.
    live.freeze.assertNoAuthorSessions()
    assertTrue(live.firstD.cleanupVerified)
    assertEquals(0L, live.observer.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND usename IN (?, ?, ?)",
        Long::class.java, PgLifecycleDatabaseSettings.CANDIDATE, CatalogGenesisFreezeFixture.AUTHOR,
        me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFixture.OPERATOR,
    ))
    predecessor.assertReadyForActivation(live.observer)
    VersionBoundPersistenceConnectedFixture(tls.database, testActivation = true, activeFirstCut = true,
        endpointPort = tls.endpointPort).use { fresh ->
        fresh.bind()
        action(fresh, predecessor)
    }
    live.assertFrozenUnchanged()
}

/** Read-only observations of this fixture's actual result; never supplied to a product factory as eligibility. */
internal class TestGlobalScanPredecessorV1(live: CatalogSignerRotationD7Fixture, private val cutoff: CatalogEpochRotationV1.Cutoff) {
    private val initialBytes = live.freeze.initial.copyOf()
    private val currentBytes = live.freeze.current.copyOf()
    private val envelopeBytes = live.envelope.copyOf()
    private val liveD = live.process.canonicalBytes()
    private val liveHash = live.process.configurationHashBytes()
    private val globalImage = globalInvariant(live.observer)
    private val genesisImage = genesisImage(live.observer)
    val initial: ByteArray get() = initialBytes.copyOf()
    val current: ByteArray get() = currentBytes.copyOf()
    val envelope: ByteArray get() = envelopeBytes.copyOf()
    val policy = checkNotNull(live.inputs.catalog).chainPolicy
    val capacity = live.inputs.capacity
    val retainedUntil = live.retainUntil.epochSecond
    val rotations = RotationChainFixture(
        Json.decodeFromString(OfflineTrustBundleEnvelopeV1.serializer(), initialBytes.decodeToString()),
        Json.decodeFromString(OfflineTrustBundleEnvelopeV1.serializer(), currentBytes.decodeToString()),
        Json.decodeFromString(OfflineCatalogGenesisEnvelopeV1.serializer(), envelopeBytes.decodeToString()),
        emptyList(),
    )

    init {
        assertArrayEquals(initialBytes, OfflineTrustBundleFixture.bytes(rotations.initial))
        assertArrayEquals(currentBytes, OfflineTrustBundleFixture.bytes(rotations.current))
        assertArrayEquals(envelopeBytes, rotations.bytes().single())
        assertEquals(Sha256.hex(liveD), HexFormat.of().formatHex(liveHash))
        val g1 = live.genesisRow()
        assertEquals("catalog-version-1", g1["object_version"])
        assertEquals(live.retainUntil, (g1["retain_until"] as Timestamp).toInstant())
        assertReadyForActivation(live.observer)
    }

    fun assertReadyForActivation(observer: JdbcTemplate) {
        assertPreserved(observer)
        val row = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
        assertEquals(1L, row["accepted_catalog_generation"])
        assertEquals(Sha256.hex(envelopeBytes), HexFormat.of().formatHex(row["accepted_catalog_hash"] as ByteArray))
        assertEquals(true, row["maintenance_closed"]); assertEquals(true, row["creation_closed"])
        assertNull(row["lease_owner"]); assertNull(row["lease_expires_at"])
        assertNull(row["pending_projection_token"])
        assertEquals(1L, observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_test_runs", Long::class.java))
    }

    fun assertTestInputs(evidence: CatalogTestRunActivationEvidenceFixture) {
        assertArrayEquals(initialBytes, evidence.initial); assertArrayEquals(currentBytes, evidence.current)
        assertArrayEquals(envelopeBytes, evidence.prefix.single())
        assertArrayEquals(capacity.digestBytes(), evidence.process.consumers.capacityPolicy.digestBytes())
        assertNotEquals(HexFormat.of().formatHex(liveHash), HexFormat.of().formatHex(evidence.process.configurationHashBytes()),
            "The independently retained LIVE full D is not the new TEST full D.")
        assertEquals(2L, evidence.generation)
    }

    fun assertPreserved(observer: JdbcTemplate) {
        assertEquals(globalImage, globalInvariant(observer), "TEST may change its head/gates/lease, never the captured global D/epoch/rotation/seal/checkpoint.")
        assertEquals(genesisImage, genesisImage(observer), "The actual completed/projected G1 row remains byte- and xmin-identical.")
        val row = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
        assertArrayEquals(liveHash, row["desired_configuration_hash"] as ByteArray)
        assertEquals(2L, row["publication_epoch"]); assertEquals(false, row["scan_requested"])
        assertEquals("CAPTURED", row["rotation_state"]); assertEquals(cutoff.rotationId, row["rotation_id"])
        assertEquals(cutoff.sequence, row["rotation_sequence"]); assertEquals(cutoff.epoch, row["rotation_epoch_before"])
        assertEquals(cutoff.writer, row["rotation_event_writer_generation"])
        assertEquals(cutoff.epochAfter, row["rotation_epoch_after"])
        assertEquals(cutoff.requestOwner, row["rotation_request_owner"]); assertEquals(cutoff.requestToken, row["rotation_request_token"])
        assertEquals(cutoff.requestedAt, (row["rotation_requested_at"] as Timestamp).toInstant())
        assertEquals(cutoff.captureOwner, row["rotation_capture_owner"]); assertEquals(cutoff.captureToken, row["rotation_capture_token"])
        assertEquals(cutoff.capturedAt, (row["rotation_captured_at"] as Timestamp).toInstant())
        assertNull(row["seal_state"]); assertNull(row["checkpoint_generation"])
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_journal_scan_runs WHERE data_scope_id = ?",
            Long::class.java, ComplaintDataScope.LIVE.id))
    }
}

private fun captureGlobalRequest(live: CatalogSignerRotationD7Fixture): CatalogEpochRotationV1.Cutoff {
    val observer = live.observer
    val scope = ComplaintDataScope.LIVE.id
    val before = observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
    assertEquals(true, before["scan_requested"], "Real G1/first-D must preserve the original migration request until capture.")
    assertEquals(1L, before["publication_epoch"]); assertEquals(0L, before["rotation_sequence"])
    assertNull(before["rotation_id"]); assertNull(before["seal_state"]); assertNull(before["checkpoint_generation"])
    assertArrayEquals(live.selectedHash, before["desired_configuration_hash"] as ByteArray)
    val resource = checkNotNull(live.process.pools.epochRotation)
    assertTrue(resource.belongsTo(live.process.pools))
    val participant = poolTestField<PersistenceJdbcParticipant>(resource, "participant")
    val binding = poolTestField<PersistencePhysicalFactoryBinding>(participant, "binding")
    fun entries(): List<PersistencePhysicalEntry> = binding.ledger.lock.withLock { binding.ledger.entries.filterNotNull() }
    assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, resource.observePreparation())
    assertTrue(entries().isEmpty())
    assertEquals(PersistenceLifecycleObservation.READY, resource.prepare())
    assertTrue(entries().isEmpty(), "Cold E preparation must not create a fourth pool or idle physical connection.")
    val pooled = listOf(live.process.pools.ordinary, live.process.pools.deletion, live.coordinator.dataSource).map(live.tls::tlsPid).toSet()
    assertEquals(3, pooled.size)
    val counters = live.counters()
    val history = genesisImage(observer)
    val request = live.coordinator.epochRotation.requestScan(live.lease.campaign)
    live.released()
    assertEquals("REQUESTED", observer.queryForObject("SELECT rotation_state FROM complaint_journal_control WHERE data_scope_id = ?", String::class.java, scope))
    val caller = Thread.currentThread()
    var native: PersistenceEpochRotationSession? = null
    live.clock.onSample = {
        if (Thread.currentThread() === caller && native == null) {
            val active = (ownedCutField(resource, "active") as AtomicReference<*>).get()
            val session = active?.let { ownedCutField(it, "session") as? PersistenceEpochRotationSession }
            // No I/O is added to the 100ms M prefix. Observe only after the actual exclusive E has returned.
            if (session != null && ownedCutField(session, "stage").toString() == "EXCLUSIVE") {
                native = session
                val pids = observer.queryForList(
                    "SELECT a.pid FROM pg_stat_activity a JOIN pg_locks l ON l.pid = a.pid " +
                        "WHERE a.datname = current_database() AND a.usename = ? AND l.locktype = 'advisory' AND l.mode = 'ExclusiveLock' AND l.granted " +
                        "AND l.classid::bigint = (hashtextextended('complaint-journal-epoch', 0) >> 32 & 4294967295) " +
                        "AND l.objid::bigint = (hashtextextended('complaint-journal-epoch', 0) & 4294967295)",
                    Int::class.java, PgLifecycleDatabaseSettings.CANDIDATE,
                )
                val pid = pids.single()
                assertFalse(pid in pooled); assertEquals(pid, live.tls.observeTlsPid(pid))
                assertSame(entries().single(), poolTestField<PersistencePhysicalEntry>(session, "entry"))
            }
        }
    }
    val cutoff = try {
        live.coordinator.epochRotation.captureEpoch(request)
    } catch (failure: Throwable) {
        live.clock.assertNoLostAssertions() // Preserve any observer assertion instead of its sanitized owner wrapper.
        throw failure
    } finally { live.clock.onSample = {} }
    live.clock.assertNoLostAssertions()
    val session = checkNotNull(native)
    assertEquals(PersistenceDatabaseOutcome.COMMITTED, session.failure().databaseOutcome)
    assertTrue(session.failure().cleanupProven)
    val entry = poolTestField<PersistencePhysicalEntry>(session, "entry")
    awaitLifecycleFact { entry.jdbc.terminalCompletion().reclaimed() && entries().isEmpty() }
    assertEquals(1L, cutoff.epoch); assertEquals(2L, cutoff.epochAfter); assertEquals(1L, cutoff.sequence)
    assertEquals(live.lease.receipt.owner, cutoff.requestOwner); assertEquals(live.lease.receipt.token, cutoff.requestToken)
    assertEquals(cutoff.requestOwner, cutoff.captureOwner); assertEquals(cutoff.requestToken, cutoff.captureToken)
    assertEquals(counters, live.counters()); assertEquals(history, genesisImage(observer))
    // Actual authority-reducing lease operation, never fixture backdating or a transplanted campaign.
    assertEquals(CatalogCoordinatorLeaseTransitionV1.RELINQUISHED, live.coordinator.lease.relinquish(live.lease.campaign).transition)
    live.released()
    requireConnectionFree()
    return cutoff
}

private fun globalInvariant(observer: JdbcTemplate): String = checkNotNull(observer.queryForObject(
    "SELECT (to_jsonb(c) - ARRAY['accepted_catalog_generation','accepted_catalog_hash','pending_projection_token'," +
        "'maintenance_closed','creation_closed','lease_owner','lease_token','lease_expires_at','updated_at'])::text " +
        "FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id,
))

private fun genesisImage(observer: JdbcTemplate): String = checkNotNull(observer.queryForObject(
    "SELECT jsonb_build_array(to_jsonb(m), m.xmin::text)::text FROM complaint_catalog_mutations m WHERE successor_generation = 1 AND operation_type = 'GENESIS'",
    String::class.java,
))
