package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Connection

/**
 * Actual drain -> all-chunks PREPARE. Opt-in raw SDK responses precede consumers/full D/signing.
 * IAM, retention and historical open/D comparisons are synthetic, not deployed acceptance.
 * The sole501-row caller uses real lower-core enrollment, not HTTP quota/activation proof.
 */
internal fun withManifestPublicationRun(tls: VersionBoundPersistenceConnectedFixture, multiChunk: Boolean = false,
    action: (TestRunOrdinaryDrainFixtureV1, TestRunInstallationManifestV1, TestInstallationManifestPublicationSqlProbeV1) -> Unit) =
    withOrdinaryDrainRun(tls, expireClosedSetupPredecessors = true, manifestPublication = true,
        additionalRawEnrolled = if (multiChunk) 500 else 0) { f ->
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val drain = f.begin()
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                drain.drain(f.approval(drain), f.rawEvidence, f.primaryCredentials, f.readCredentials))
            f.assertReleased()
            val original = TestRunInstallationManifestV1.begin(drain)
            TestInstallationManifestSqlProbeV1(f).use { probe ->
                probe.original = original
                assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, original.prepare())
            }
            val ordinary = f.history.providerImage()
            val inventoryCalls = native.requests.size
            val previousBoundary = f.sealHttp.boundary
            val previousNative = f.sealHttp.nativeBoundary
            TestInstallationManifestPublicationSqlProbeV1(f).use { probe ->
                f.sealHttp.boundary = { previousBoundary(); probe.assertReleased(requireCommitted = false) }
                f.sealHttp.nativeBoundary = { previousNative(); probe.assertReleased(requireCommitted = false) }
                try { action(f, original, probe) }
                finally { f.sealHttp.boundary = previousBoundary; f.sealHttp.nativeBoundary = previousNative }
            }
            f.assertReleased()
            assertEquals(ordinary, f.history.providerImage(), "Manifest publication cannot repeat ordinary S3/KMS or registration/catalog reads.")
            assertEquals(inventoryCalls, native.requests.size)
        }
    }

/** Independent raw connection, including when a caller is observing an in-flight original phase. */
internal fun manifestPublicationImage(f: TestRunOrdinaryDrainFixtureV1, ordinal: Int? = null): List<String> =
    checkNotNull(f.observer.dataSource).connection.use { connection ->
        connection.prepareStatement("SELECT jsonb_build_array(to_jsonb(p), p.xmin::text)::text FROM complaint_journal_publications p " +
            "JOIN complaint_test_terminal_intents i ON i.publication_ref = p.event_id WHERE p.data_scope_id = ? " +
            "AND i.object_kind = 'INSTALLATION_MANIFEST' AND (?::integer IS NULL OR i.object_ordinal = ?) ORDER BY i.object_ordinal").use { statement ->
            statement.setObject(1, f.scope); statement.setObject(2, ordinal); statement.setObject(3, ordinal)
            statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1)) } }
        }
    }

internal fun manifestStatePairs(f: TestRunOrdinaryDrainFixtureV1): List<Pair<String, String>> =
    checkNotNull(f.observer.dataSource).connection.use { connection ->
        connection.prepareStatement("SELECT p.state, i.state FROM complaint_journal_publications p JOIN complaint_test_terminal_intents i " +
            "ON i.publication_ref = p.event_id WHERE p.data_scope_id = ? AND p.event_kind = 'INSTALLATION_MANIFEST' ORDER BY i.object_ordinal").use { statement ->
            statement.setObject(1, f.scope)
            statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1) to row.getString(2)) } }
        }
    }

internal fun manifestInvariantImage(f: TestRunOrdinaryDrainFixtureV1): Map<String, List<String>> =
    TestOrdinaryDrainAccountingObservationV1(f).image().filterKeys {
        it !in setOf("complaint_journal_control", "complaint_journal_publications", "complaint_test_terminal_intents")
    }

internal fun assertPublishedManifests(f: TestRunOrdinaryDrainFixtureV1, original: TestRunInstallationManifestPublicationV1, entries: Int = 1) {
    requireConnectionFree()
    val json = TestTerminalJsonV1(f.registration.process.consumers.journalConfiguration)
    val expectedIds = f.observer.query("SELECT id::text FROM complaint_installation_ids WHERE data_scope_id = ? ORDER BY id", { row, _ -> row.getString(1) }, f.scope)
    val seen = arrayListOf<String>()
    var chunks = 0
    f.observer.query("""
        SELECT p.event_bytes, p.object_key, p.object_version, encode(p.semantic_hash, 'hex') AS canonical_hash,
            encode(p.ciphertext_hash, 'hex') AS wire_hash, i.wire_bytes, i.canonical_bytes, i.object_ordinal,
            (p.state = 'VERIFIED' AND i.state = 'WIRE_FROZEN' AND p.applied_at IS NULL AND p.ciphertext_hash = i.wire_hash
                AND p.object_created_at <= p.verified_at AND p.retain_until >= i.retain_until AND p.retain_until > clock_timestamp()
                AND r.state = 'RESERVED' AND r.reserved_amounts = array_fill(0::bigint, ARRAY[22])
                AND r.converted_at IS NULL AND r.converted_amounts IS NULL) AS valid
        FROM complaint_journal_publications p JOIN complaint_test_terminal_intents i ON i.publication_ref = p.event_id
            JOIN complaint_recovery_capacity_reservations r ON r.publication_ref = p.event_id
        WHERE p.data_scope_id = ? AND p.event_kind = 'INSTALLATION_MANIFEST' ORDER BY i.object_ordinal
    """.trimIndent(), { row, _ ->
        assertTrue(row.getBoolean("valid"))
        val index = row.getInt("object_ordinal")
        assertEquals(chunks++, index)
        val body = row.getBytes("event_bytes")
        assertArrayEquals(body, row.getBytes("canonical_bytes"))
        val declaration = json.installationManifest(body)
        assertEquals(original.runContext, declaration.context().run)
        assertEquals(index, declaration.chunkIndex)
        seen.addAll(declaration.entries().map { it.installationId })
        assertTrue(declaration.entries().all { it.disposition === TestTerminalDispositionV1.RETIRED })
        val provider = f.sealHttp.manifestObjects.getValue(row.getString("object_key"))
        assertArrayEquals(provider.bytes, row.getBytes("wire_bytes"))
        assertEquals(provider.version, row.getString("object_version"))
        val ref = original.authenticatedChunk(index)
        assertEquals(provider.key, ref.objectKey); assertEquals(provider.version, ref.objectVersion)
        assertEquals(Sha256.hex(provider.bytes), ref.ciphertextSha256); assertEquals(Sha256.hex(body), ref.canonicalSha256)
        assertEquals(row.getString("wire_hash"), ref.ciphertextSha256); assertEquals(row.getString("canonical_hash"), ref.canonicalSha256)
        Unit
    }, f.scope)
    assertEquals(entries, seen.size); assertEquals(expectedIds, seen)
    assertEquals((entries + 499) / 500, chunks)
    assertEquals(chunks, f.sealHttp.manifestObjects.size)
    assertTrue(f.observer.queryForObject("""
        SELECT state = 'SEALED' AND purging_at IS NULL AND purged_at IS NULL AND event_manifest_count IS NULL AND event_manifest_root IS NULL
            AND installation_manifest_count IS NULL AND installation_manifest_root IS NULL AND installation_chunk_count IS NULL
            AND retired_count IS NULL AND deleted_count IS NULL AND terminal_event_id IS NULL AND terminal_object_key IS NULL
            AND terminal_object_version IS NULL AND terminal_ciphertext_hash IS NULL AND terminal_catalog_generation IS NULL AND terminal_catalog_hash IS NULL
        FROM complaint_test_runs WHERE data_scope_id = ?
    """.trimIndent(), Boolean::class.java, f.scope) == true, "No purge, final root, terminal catalog or PURGED state is fabricated.")
    assertTrue(f.observer.queryForObject("SELECT lease_owner IS NULL AND lease_expires_at IS NULL AND lease_token = ? " +
        "FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, original.leaseToken, f.scope) == true)
}

internal fun <T> manifestRaw(f: TestRunOrdinaryDrainFixtureV1, action: (Connection) -> T): T =
    checkNotNull(f.observer.dataSource).connection.use(action)
