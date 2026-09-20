package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCryptoReferenceV1
import me.manga.kira.backend.security.terminalCanonical
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal enum class TestOrdinarySealHistoryCutV1 { PENDING, ORPHAN_RECEIPT, FOREIGN_WRITER, UNSUPPORTED_KIND, SECOND_PASS_XMIN }

/** Actual registered producer/SQL/SDK path; raw HTTP authority is explicitly synthetic and pre-D. */
internal object TestOrdinarySealCasesV1 {
    fun emptyAndReplay(tls: VersionBoundPersistenceConnectedFixture) = withOrdinarySealRun(tls) { f, _ ->
        val before = f.image()
        val counters = f.p.counters()
        val unused = unused(f)
        val inputs = linkedMapOf<TestOrdinarySealStepV1, Map<String, List<String>>>()
        var atomicWrites = 0
        f.probe.before = { call -> inputs.getOrPut(call.step, f::image) }
        f.probe.after = { call ->
            if (call.sql.startsWith("UPDATE ") || call.sql.startsWith("INSERT ") || call.sql == TestOrdinarySealSqlV1.capture) {
                assertEquals(inputs.getValue(call.step), f.image(), "No phase write is visible on an independent connection before actual commit.")
                atomicWrites++
            }
        }
        val original = f.begin()
        assertEquals(TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, original.seal())
        f.probe.before = {}; f.probe.after = {}
        f.assertReleased(original)
        assertTrue(atomicWrites >= 5)
        assertEquals(TestOrdinarySealStepV1.entries, f.probe.calls.map { it.step }.distinct())
        assertEquals(4, f.probe.observations.size)
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "GENERATE", "LIST", "PUT", "LIST", "GET", "DECRYPT"), f.http.order)
        assertOnlyOneSidecarSpend(f, counters, unused)
        assertVerifiedLocalOnly(f, cutoff = 1, expectedCount = 0)
        assertEquals(before.filterKeys { it !in changed }, f.image().filterKeys { it !in changed })
        assertKmsContextMatchesWire(f)

        val sidecar = f.sidecarImage()
        val after = f.image()
        val firstVerified = f.control()["seal_verified_at"]
        val calls = f.probe.calls.size
        assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
        assertEquals(calls, f.probe.calls.size)
        f.expireLeaseForRetry() // Synthetic expiry, not a new registration or proof that an STS session is revoked.
        val order = f.http.order.size
        val replay = f.begin()
        assertEquals(TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, replay.seal())
        f.assertReleased(replay)
        assertEquals(listOf(TestOrdinarySealStepV1.CAPTURE, TestOrdinarySealStepV1.VERIFY), f.probe.calls.map { it.step }.distinct())
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "LIST", "GET", "DECRYPT"), f.http.order.drop(order))
        assertEquals(sidecar, f.sidecarImage(), "The frozen winner, randomness and sidecar xmin are immutable on replay.")
        assertEquals(firstVerified, f.control()["seal_verified_at"])
        assertEquals(after.filterKeys { it != "complaint_journal_control" }, f.image().filterKeys { it != "complaint_journal_control" })
        assertOnlyOneSidecarSpend(f, counters, unused)
    }

    fun completeHistory(tls: VersionBoundPersistenceConnectedFixture, syntheticExpiredReceipt: Boolean) = withOrdinarySealRun(tls, histories = 2) { f, history ->
        val h = checkNotNull(history)
        if (syntheticExpiredReceipt) backdateHistoricalComparison(f, h.histories.first().eventId)
        val before = f.image()
        val expected = expectedManifest(f, 11)
        val original = f.begin()
        assertEquals(TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, original.seal())
        f.assertReleased(original)
        assertVerifiedLocalOnly(f, cutoff = 11, expectedCount = 2)
        assertEquals(expected, f.canonical().getValue("eventManifestSha256").jsonPrimitive.content)
        assertEquals(before.filterKeys { it !in changed }, f.image().filterKeys { it !in changed }, "No receipt, APPLIED, recovery remainder, installation, notice or history rewrite.")
        if (syntheticExpiredReceipt) assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts " +
            "WHERE data_scope_id = ? AND operation = 'OWNER_DELETE' AND expires_at < clock_timestamp()", Long::class.java, f.scope))
    }

    fun invalidHistory(tls: VersionBoundPersistenceConnectedFixture, cut: TestOrdinarySealHistoryCutV1) =
        withOrdinarySealRun(tls, histories = 1, drain = cut !== TestOrdinarySealHistoryCutV1.PENDING) { f, history ->
            val h = checkNotNull(history)
            when (cut) {
                TestOrdinarySealHistoryCutV1.PENDING -> Unit
                TestOrdinarySealHistoryCutV1.ORPHAN_RECEIPT -> assertEquals(1, f.observer.update("DELETE FROM complaint_idempotency_receipts WHERE publication_ref = ?", h.eventId))
                TestOrdinarySealHistoryCutV1.FOREIGN_WRITER -> assertEquals(1, f.observer.update("UPDATE complaint_journal_publications SET writer_generation = ? WHERE event_id = ?", UUID.randomUUID(), h.eventId))
                TestOrdinarySealHistoryCutV1.UNSUPPORTED_KIND -> assertEquals(1, f.observer.update("UPDATE complaint_journal_publications SET event_kind = 'OWNER_DELETE_ALL' WHERE event_id = ?", h.eventId))
                TestOrdinarySealHistoryCutV1.SECOND_PASS_XMIN -> Unit
            }
            val before = f.image()
            var pages = 0
            var mutated = false
            f.probe.before = { call ->
                if (cut === TestOrdinarySealHistoryCutV1.SECOND_PASS_XMIN && call.step === TestOrdinarySealStepV1.CAPTURE && call.sql == TestOrdinarySealSqlV1.manifestPage && ++pages == 3) {
                    // Same original transaction, actual UPDATE, unchanged values but different xmin. No query result substitution.
                    assertEquals(1, JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource).update("UPDATE complaint_journal_publications SET created_at = created_at WHERE event_id = ?", h.eventId))
                    mutated = true
                }
            }
            val original = f.begin()
            assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
            f.probe.before = {}
            f.assertNativeCloseBoundary()
            f.probe.assertNoLostAssertions()
            assertEquals(cut === TestOrdinarySealHistoryCutV1.SECOND_PASS_XMIN, mutated)
            assertEquals(before, f.image(), "Refusal rolls back the cutoff and lease; it does not silently seal a smaller set.")
            assertTrue(f.http.order.isEmpty())
            f.http.assertDisposed()
        }

    fun missingVerifiedObjectNeverReput(tls: VersionBoundPersistenceConnectedFixture) = withOrdinarySealRun(tls) { f, _ ->
        assertEquals(TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, f.begin().seal())
        val sidecar = f.sidecarImage()
        val proofs = f.control().filterKeys { it.startsWith("seal_") }.mapValues { (_, value) -> if (value is ByteArray) value.toList() else value }
        val counters = f.p.counters()
        f.expireLeaseForRetry()
        f.http.hideObject = true
        val start = f.http.order.size
        val original = f.begin()
        assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
        f.assertNativeCloseBoundary()
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "LIST"), f.http.order.drop(start))
        assertEquals(sidecar, f.sidecarImage())
        assertEquals(proofs, f.control().filterKeys { it.startsWith("seal_") }.mapValues { (_, value) -> if (value is ByteArray) value.toList() else value })
        assertEquals(counters, f.p.counters())
        assertEquals(1, f.http.requests.count { it.kind == "PUT" }, "Only the first successful original may PUT; missing verified version cannot be recreated.")
        f.http.assertDisposed()
        f.probe.assertNoLostAssertions()
    }

    fun absentColdInputRefuses(tls: VersionBoundPersistenceConnectedFixture) = ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls) { p, _, registration, probe ->
        assertNull(registration.process.ordinarySeal)
        val before = p.image()
        val calls = probe.calls.size
        val reads = p.f.http.read.requests.size
        assertThrows<TestOrdinarySealExceptionV1> { TestRunOrdinarySealV1.begin(registration) }
        requireConnectionFree()
        assertEquals(before, p.image())
        assertEquals(calls, probe.calls.size)
        assertEquals(reads, p.f.http.read.requests.size)
    }

    internal fun assertVerifiedLocalOnly(f: TestOrdinarySealObservationV1, cutoff: Long, expectedCount: Long) {
        val c = f.control()
        assertEquals("SEAL_VERIFIED", c["seal_state"])
        assertEquals(1L, c["rotation_sequence"])
        assertEquals("CAPTURED", c["rotation_state"])
        assertEquals(cutoff, c["rotation_epoch_before"])
        assertEquals(cutoff + 1, c["publication_epoch"])
        assertEquals(c["rotation_id"], c["seal_operation_token"])
        assertEquals(true, c["maintenance_closed"]); assertEquals(true, c["creation_closed"]); assertEquals(true, c["scan_requested"])
        for (column in listOf("seal_format", "seal_rotation_id", "seal_rotation_sequence", "seal_preparing_fencing_token", "seal_routing_key_id", "seal_epoch_start", "seal_preceding_hash")) assertNull(c[column], column)
        c.filterKeys { it.startsWith("checkpoint_") }.forEach { (key, value) -> assertNull(value, key) }
        val r = f.observer.queryForMap("SELECT * FROM complaint_test_runs WHERE data_scope_id = ?", f.scope)
        assertEquals("SEALED", r["state"])
        val retained = setOf("data_scope_id", "test_only", "state", "configuration_hash", "accounting_version", "installation_limit", "enrolled_count", "original_reserve", "unused_reserve", "activation_catalog_generation", "activation_catalog_hash", "created_at", "sealed_at")
        r.filterKeys { it !in retained }.forEach { (key, value) -> assertNull(value, key) }
        val row = f.sidecar()
        assertEquals("EPOCH_SEAL", row["object_kind"]); assertEquals(0, (row["object_ordinal"] as Number).toInt()); assertEquals("WIRE_FROZEN", row["state"])
        assertNull(row["publication_ref"])
        assertArrayEquals(row["canonical_bytes"] as ByteArray, c["seal_bytes"] as ByteArray)
        assertArrayEquals(row["canonical_hash"] as ByteArray, c["seal_hash"] as ByteArray)
        assertArrayEquals(row["wire_hash"] as ByteArray, c["seal_ciphertext_hash"] as ByteArray)
        assertArrayEquals(row["wire_bytes"] as ByteArray, checkNotNull(f.http.stored).bytes)
        assertEquals(TestOrdinarySealHttpFixtureV1.VERSION, c["seal_object_version"])
        val json = f.canonical()
        assertEquals("EPOCH_SEAL", json.getValue("objectKind").jsonPrimitive.content)
        assertEquals("1", json.getValue("epochStartInclusive").jsonPrimitive.content)
        assertEquals(cutoff.toString(), json.getValue("epochEndInclusive").jsonPrimitive.content)
        assertEquals("", json.getValue("precedingSealSha256").jsonPrimitive.content)
        assertEquals(expectedCount.toString(), json.getValue("eventCount").jsonPrimitive.content)
        assertEquals(expectedManifest(f, cutoff), json.getValue("eventManifestSha256").jsonPrimitive.content)
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ? AND event_kind = 'EPOCH_SEAL'", Long::class.java, f.scope))
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_publications WHERE data_scope_id = ? AND event_kind = 'EPOCH_SEAL'", Long::class.java, f.scope))
    }

    internal fun unused(f: TestOrdinarySealObservationV1): ComplaintCapacityVector = f.observer.query("SELECT unused_reserve FROM complaint_test_runs WHERE data_scope_id = ?", { row, _ ->
        val array = row.getArray(1)
        try { ComplaintCapacityVector.of((array.array as Array<*>).map { it as Long }.toLongArray()) } finally { array.free() }
    }, f.scope).single()

    internal fun assertOnlyOneSidecarSpend(f: TestOrdinarySealObservationV1, before: Map<String, ProjectionCounterObservation>, unused: ComplaintCapacityVector) {
        val after = f.p.counters()
        val charge = TestTerminalCapacityChargesV1.SIDECAR
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            val old = before.getValue(counter.storedName); val current = after.getValue(counter.storedName)
            assertEquals(old.free, current.free); assertEquals(old.recovery, current.recovery)
            assertEquals(old.actual + charge[counter], current.actual); assertEquals(old.reserved - charge[counter], current.reserved)
            assertEquals(old.preserved, current.preserved)
            if (charge[counter] == 0L) assertEquals(old.full, current.full)
        }
        assertEquals(unused - charge, unused(f))
    }

    private fun expectedManifest(f: TestOrdinarySealObservationV1, cutoff: Long): String {
        val triples = f.observer.query("SELECT object_key, object_version, encode(ciphertext_hash, 'hex') FROM complaint_journal_publications WHERE data_scope_id = ? ORDER BY object_key COLLATE \"C\"", { row, _ ->
            listOf(row.getString(1), row.getString(2), row.getString(3))
        }, f.scope)
        val j = f.registration.process.consumers.journalConfiguration
        val fields = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", j.declaration().writer.generationId, j.ordinaryPrefix, "TEST", f.scope.toString(), "1", cutoff.toString(), triples.size.toString())
        return terminalHash(terminalFrame(fields), *triples.map(::terminalFrame).toTypedArray())
    }

    private fun assertKmsContextMatchesWire(f: TestOrdinarySealObservationV1) {
        val parts = TestTerminalCryptoReferenceV1.parts(checkNotNull(f.http.stored).bytes)
        val header = Json.parseToJsonElement(parts.header.toString(Charsets.UTF_8)).jsonObject
        val expected = TestTerminalCryptoReferenceV1.context(TestTerminalCodecKindV1.EPOCH_SEAL, header)
        assertEquals(2, f.http.kms.requests.size)
        f.http.kms.requests.forEach { request ->
            val context = request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() }
            assertEquals(expected, context, "Independent LP32 header context, not a LIVE relabel or arbitrary fake KMS result.")
        }
    }

    /** Only a stored-comparison expiry fixture: real history first, then explicitly synthetic historical times and proof digest. */
    private fun backdateHistoricalComparison(f: TestOrdinarySealObservationV1, id: String) {
        val bytes = f.observer.queryForObject("SELECT verification_bytes FROM complaint_journal_publications WHERE event_id = ?", ByteArray::class.java, id)!!
        val proof = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val delta = 9 * 86_400L
        val changes = listOf("objectCreatedAt", "verifiedAt").associateWith { JsonPrimitive(Instant.parse(proof.getValue(it).jsonPrimitive.content).minusSeconds(delta).toString()) }
        val replacement = terminalCanonical(JsonObject(proof + changes))
        assertEquals(1, f.observer.update("UPDATE complaint_journal_publications SET created_at = created_at - interval '9 days', object_created_at = object_created_at - interval '9 days', " +
            "verified_at = verified_at - interval '9 days', applied_at = applied_at - interval '9 days', verification_bytes = ?, verification_hash = ? WHERE event_id = ?",
            replacement, HexFormat.of().parseHex(terminalHash(replacement)), id))
        assertEquals(1, f.observer.update("UPDATE complaint_idempotency_receipts SET created_at = created_at - interval '9 days', authorized_at = authorized_at - interval '9 days', " +
            "completed_at = completed_at - interval '9 days', expires_at = expires_at - interval '9 days' WHERE publication_ref = ?", id))
        assertEquals(1, f.observer.update("UPDATE complaint_deletion_journal_applied SET applied_at = applied_at - interval '9 days' WHERE event_id = ?", id))
        assertEquals(1, f.observer.update("UPDATE complaint_recovery_capacity_reservations SET created_at = created_at - interval '9 days', converted_at = converted_at - interval '9 days' WHERE event_id = ?", id))
        assertTrue((f.observer.queryForObject("SELECT expires_at FROM complaint_idempotency_receipts WHERE publication_ref = ?", Timestamp::class.java, id)!!).toInstant() < f.p.databaseTime())
        bytes.fill(0); replacement.fill(0)
    }

    private val changed = setOf("complaint_journal_control", "complaint_test_runs", "counters", "complaint_test_terminal_intents")
}
