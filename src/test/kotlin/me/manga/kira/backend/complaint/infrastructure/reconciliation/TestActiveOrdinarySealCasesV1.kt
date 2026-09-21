package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHttpFixtureV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCryptoReferenceV1
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.Timestamp

/** Genuine EMPTY epoch1 only. No synthetic prior seal/checkpoint, ordinary AUTH or health success. */
internal object TestActiveOrdinarySealCasesV1 {
    fun genuineEmpty(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean, lostPutAcknowledgment: Boolean = false) =
        withActiveSealFixture(tls, enrolled) { f ->
            val before = f.image()
            val counters = f.first.counters()
            val capture = f.control()
            assertNull(capture["lease_owner"]); assertNull(capture["lease_expires_at"])
            val captureToken = (capture.getValue("rotation_capture_token") as Number).toLong()
            val requestToken = (capture.getValue("rotation_request_token") as Number).toLong()
            var frozenWinner: List<String>? = null
            f.native.lostPutAcknowledgment = lostPutAcknowledgment
            f.native.beforeS3 = { request ->
                f.assertProviderBoundary()
                assertEquals("WIRE_FROZEN", f.paid()["state"])
                val image = f.image().getValue("complaint_test_active_seal_intents")
                if (frozenWinner == null) frozenWinner = image else assertEquals(frozenWinner, image)
                if (request.kind == "PUT") assertArrayEquals(f.paid()["wire_bytes"] as ByteArray, request.body)
            }
            f.native.onNativeClose = {
                f.assertSqlReleased()
                assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners,
                    "The real shared-J seal owner stays held until all native closes return.")
            }
            val original = f.begin()
            val verified = f.seal(original)
            f.assertReleased()
            assertTrue(original.leaseToken > captureToken && original.leaseToken > requestToken)
            assertEquals(frozenWinner, f.image().getValue("complaint_test_active_seal_intents"), "VERIFY cannot re-freeze or rewrite the first winner.")
            assertEquals(counters, f.first.counters(), "C already paid exactly one slot; A spends/refunds neither ordinary nor terminal capacity.")
            assertUnchangedExcept(f, before, setOf("complaint_journal_control", "complaint_test_active_seal_intents"))
            assertVerifiedOnly(f, verified, 0)
            assertTrue(f.ordinary.sts.requests.isEmpty(), "An EMPTY resolver must not fabricate an ordinary event/native publication.")
            assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.native.requests.map { it.kind })
            assertEquals(1, f.native.order.count { it == "GENERATE" }); assertEquals(1, f.native.order.count { it == "DECRYPT" })
            if (lostPutAcknowledgment) assertEquals(500, f.native.requests.single { it.kind == "PUT" }.reply?.status)
            val keyPages = f.probe.calls.count { it.sql == TestActiveCutoffPublicationSqlV1.keyPage }
            assertEquals(2, keyPages, "Both complete local empty-set passes are real, not an empty shortcut.")
            val image = f.image(); val providers = f.native.order.toList(); val sql = f.probe.calls.size
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
            assertThrows<RuntimeException> { TestActiveOrdinarySealV1.begin(f.captured, f.registration, f.first.assembly) }
            assertEquals(image, f.image()); assertEquals(providers, f.native.order); assertEquals(sql, f.probe.calls.size)
        }

    internal fun assertVerifiedOnly(f: TestActiveOrdinarySealFixtureV1, result: TestActiveOrdinarySealV1.Verified, count: Int) {
        val c = f.control(); val paid = f.paid()
        assertEquals("SEAL_VERIFIED", c["seal_state"])
        assertEquals("ACTIVE", f.observer.queryForObject("SELECT state FROM complaint_test_runs WHERE data_scope_id = ?", String::class.java, f.scope))
        assertEquals(false, c["maintenance_closed"]); assertEquals(false, c["creation_closed"]); assertEquals(false, c["scan_requested"])
        assertEquals("CAPTURED", c["rotation_state"]); assertEquals(1L, c["rotation_sequence"])
        assertEquals(1L, c["rotation_epoch_before"]); assertEquals(2L, c["publication_epoch"])
        assertEquals(c["rotation_id"], c["seal_operation_token"])
        c.filterKeys { it.startsWith("checkpoint_") }.forEach { (name, value) -> assertNull(value, name) }
        listOf("seal_format", "seal_rotation_id", "seal_rotation_sequence", "seal_preparing_fencing_token", "seal_routing_key_id", "seal_epoch_start", "seal_preceding_hash").forEach { assertNull(c[it], it) }
        assertEquals("WIRE_FROZEN", paid["state"]); assertEquals(2_097_152L, paid["charged_storage_bytes"])
        assertArrayEquals(paid["canonical_bytes"] as ByteArray, c["seal_bytes"] as ByteArray)
        assertArrayEquals(paid["canonical_hash"] as ByteArray, c["seal_hash"] as ByteArray)
        assertArrayEquals(paid["wire_hash"] as ByteArray, c["seal_ciphertext_hash"] as ByteArray)
        assertArrayEquals(paid["wire_bytes"] as ByteArray, checkNotNull(f.native.stored).bytes)
        val created = (paid["created_at"] as Timestamp).toInstant()
        assertFalse(created.isBefore((paid["captured_at"] as Timestamp).toInstant()))
        assertEquals(0, created.nano)
        val json = Json.parseToJsonElement((paid["canonical_bytes"] as ByteArray).toString(Charsets.UTF_8)).jsonObject
        assertEquals("EPOCH_SEAL", json.getValue("objectKind").jsonPrimitive.content)
        assertEquals("1", json.getValue("epochStartInclusive").jsonPrimitive.content)
        assertEquals("1", json.getValue("epochEndInclusive").jsonPrimitive.content)
        assertEquals(count.toString(), json.getValue("eventCount").jsonPrimitive.content)
        assertEquals("", json.getValue("precedingSealSha256").jsonPrimitive.content)
        assertEquals(paid["preparing_fencing_token"].toString(), json.getValue("preparingFencingToken").jsonPrimitive.content)
        assertEquals(expectedManifest(f), json.getValue("eventManifestSha256").jsonPrimitive.content)
        assertEquals(f.scope, result.scope); assertEquals(paid["object_key"], result.objectKey)
        assertEquals(TestOrdinarySealHttpFixtureV1.VERSION, result.version)
        assertEquals(c["seal_object_version"], result.version)
        assertEquals(Sha256.hex(checkNotNull(f.native.stored).bytes), result.ciphertextSha256)
        assertTrue(f.image().getValue("complaint_test_terminal_intents").isEmpty())
        assertTrue(f.image().getValue("complaint_journal_scan_runs").isEmpty())
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_journal_publications WHERE data_scope_id = ? AND event_kind = 'EPOCH_SEAL'", Long::class.java, f.scope))
        val parts = TestTerminalCryptoReferenceV1.parts(checkNotNull(f.native.stored).bytes)
        val header = Json.parseToJsonElement(parts.header.toString(Charsets.UTF_8)).jsonObject
        val context = TestTerminalCryptoReferenceV1.context(TestTerminalCodecKindV1.EPOCH_SEAL, header)
        f.native.kms.requests.forEach { request ->
            assertEquals(context, request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() })
        }
    }

    internal fun assertUnchangedExcept(f: TestActiveOrdinarySealFixtureV1, before: Map<String, List<String>>, changed: Set<String>) =
        assertEquals(before.filterKeys { it !in changed }, f.image().filterKeys { it !in changed })

    private fun expectedManifest(f: TestActiveOrdinarySealFixtureV1): String {
        val j = f.process.consumers.journalConfiguration
        val triples = f.observer.query("SELECT object_key, object_version, encode(ciphertext_hash, 'hex') FROM complaint_journal_publications " +
            "WHERE data_scope_id = ? AND writer_generation = ?::uuid AND journal_epoch = 1 ORDER BY object_key COLLATE \"C\"",
            { row, _ -> listOf(row.getString(1), row.getString(2), row.getString(3)) }, f.scope, j.declaration().writer.generationId)
        val fields = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", j.declaration().writer.generationId,
            j.ordinaryPrefix, "TEST", f.scope.toString(), "1", "1", triples.size.toString())
        return terminalHash(terminalFrame(fields), *triples.map(::terminalFrame).toTypedArray())
    }
}
