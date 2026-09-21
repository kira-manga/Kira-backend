package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** Deliberately caller-created syntax. None of these values is supplied to a product native/SQL issuer. */
internal class TestActiveRecurrentCheckpointDocumentV1Test {
    @Test fun canonicalRecurrentDocumentBindsAllRangesFullIdentityActualCiphertextTotalsAndCoverage() {
        val history = history(3)
        val document = document(history)
        val bytes = document.canonicalBytes()
        val parsed = TestActiveRecurrentCheckpointDocumentV1.parse(bytes)
        parsed.requireHistory(history)
        assertArrayEquals(bytes, parsed.canonicalBytes())
        assertEquals(3L, parsed.cutoffEpoch)
        assertEquals(2L, parsed.objectCount)
        assertEquals(2_048L, parsed.byteCount)
        assertEquals(history.last.operationToken, parsed.scanId)
        assertEquals(history.rootSha256, parsed.sealHistorySha256)
        assertEquals("d".repeat(64), parsed.predecessorCheckpointSha256)
        assertEquals(parsed.first.applicationCoverageSha256, parsed.second.applicationCoverageSha256)
        assertNotEquals(history.entries.sumOf { it.manifestFramedBytes }, parsed.byteCount)
        assertTrue(bytes.size < TestActiveRecurrentStorageV1.MAX_CHECKPOINT_BYTES)
        assertFalse(parsed.toString().contains(history.first.identity.scope))
    }

    @Test fun orderedHistoryIncludesEveryNativeSealHashButExcludesEveryCheckpointField() {
        val original = history(2)
        original.entries.forEach { entry ->
            val value = Json.parseToJsonElement(entry.canonicalBytes().decodeToString()).jsonObject
            assertTrue(value.keys.none { it.contains("checkpoint", ignoreCase = true) })
            assertEquals(entry.sha256, TestActiveCheckpointHistoryV1.Entry.parse(entry.canonicalBytes()).sha256)
        }
        val a = document(original, predecessor = "a".repeat(64))
        val b = document(original, predecessor = "b".repeat(64))
        assertNotEquals(Sha256.hex(a.canonicalBytes()), Sha256.hex(b.canonicalBytes()))
        assertEquals(a.sealHistorySha256, b.sealHistorySha256)
        val last = original.last
        val changed = entryChanged(last, "objectVersion", JsonPrimitive("different-native-version"))
        assertNotEquals(original.rootSha256, TestActiveCheckpointHistoryV1.of(listOf(original.first, changed)).rootSha256)
        assertThrows<IllegalArgumentException> { a.requireHistory(TestActiveCheckpointHistoryV1.of(listOf(original.first, changed))) }
    }

    @Test fun gapsWrongPredecessorDuplicateTokenIdentityAndFifteenthSealCannotFormHistory() {
        val entries = history(3).entries
        assertThrows<IllegalArgumentException> { TestActiveCheckpointHistoryV1.of(listOf(entries[0], entries[2])) }
        assertThrows<IllegalArgumentException> { TestActiveCheckpointHistoryV1.of(listOf(entries[1], entries[0])) }
        assertThrows<IllegalArgumentException> { TestActiveCheckpointHistoryV1.of(listOf(entries[0], entryChanged(entries[1], "precedingSealSha256", JsonPrimitive("9".repeat(64))))) }
        assertThrows<IllegalArgumentException> { TestActiveCheckpointHistoryV1.of(listOf(entries[0], entryChanged(entries[1], "operationToken", JsonPrimitive(entries[0].operationToken)))) }
        val identity = entries[1].identity.json().toMutableMap().apply { put("journalConfigurationSha256", JsonPrimitive("9".repeat(64))) }
        assertThrows<IllegalArgumentException> { TestActiveCheckpointHistoryV1.of(listOf(entries[0], entryChanged(entries[1], "identity", JsonObject(identity)))) }
        assertEquals(14, history(14).entries.size)
        assertThrows<IllegalArgumentException> { history(15) }
    }

    @Test fun duplicateUnknownNoncanonicalUtf8AndNestedDocumentsAreRejectedBeforeAnyAuthority() {
        val bytes = document(history(2)).canonicalBytes()
        val text = bytes.decodeToString()
        listOf(" $text", text.replaceFirst("{", "{\"unexpected\":1,"),
            text.replaceFirst("{", "{\"schemaVersion\":1,"), text.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            text.replace("\"result\":\"SUCCESS\"", "\"result\":\"FAILURE\"")).forEach {
            assertThrows<IllegalArgumentException> { TestActiveRecurrentCheckpointDocumentV1.parse(it.toByteArray()) }
        }
        assertThrows<Exception> { TestActiveRecurrentCheckpointDocumentV1.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows<IllegalArgumentException> { TestActiveRecurrentCheckpointDocumentV1.parse(ByteArray(65_537) { ' '.code.toByte() }) }
        assertThrows<IllegalArgumentException> { TestActiveRecurrentCheckpointDocumentV1.parse(("{\"x\":" + "[".repeat(8) + "0" + "]".repeat(8) + "}").toByteArray()) }
    }

    @Test fun sameInventoryCoverageNativeCountsAndMonotonicTimesAreMandatory() {
        val h = history(2)
        val doc = document(h)
        assertThrows<IllegalArgumentException> { TestActiveRecurrentCheckpointDocumentV1(h.first.identity, doc.fencingToken, doc.scanId,
            doc.predecessorCheckpointSha256, h.rootSha256, doc.ranges, doc.first,
            TestActiveRecurrentCheckpointDocumentV1.Pass(doc.second.startedAt, doc.second.completedAt, "f".repeat(64), "c".repeat(64), 1, 1_024)) }
        assertThrows<IllegalArgumentException> { TestActiveRecurrentCheckpointDocumentV1.Pass(at.plusNanos(1), at.plusSeconds(1), "a".repeat(64), "b".repeat(64), 1, 1) }
        assertThrows<IllegalArgumentException> { TestActiveRecurrentCheckpointDocumentV1.Pass(at, at.minusSeconds(1), "a".repeat(64), "b".repeat(64), 1, 1) }
        assertThrows<IllegalArgumentException> { document(h, fence = h.last.preparingFencingToken).requireHistory(h) }
    }

    private val at = Instant.parse("2030-01-01T00:00:00Z")
    private fun uuid(n: Int) = "${n.toString(16).padStart(8, '0')}-1111-4111-8111-111111111111"
    private fun hash(n: Int) = n.toString(16).padStart(64, '0')
    private fun history(size: Int): TestActiveCheckpointHistoryV1 {
        val identity = TestActiveCheckpointHistoryV1.Identity(uuid(90), at, 1, 7, hash(91), hash(92), uuid(93), uuid(94),
            uuid(95), 2, hash(96), 2, hash(96), hash(97), uuid(98))
        return TestActiveCheckpointHistoryV1.of((1..size).map { n ->
            val time = at.plusSeconds(n * 10L)
            TestActiveCheckpointHistoryV1.Entry(identity, if (n == 1) TestActiveCheckpointHistoryV1.Source.V26_INITIAL else TestActiveCheckpointHistoryV1.Source.V31_RECURRENT,
                n, uuid(n), n.toLong(), n.toLong(), n + 1L, uuid(91), n * 3L, time, uuid(92), n * 3L, time.plusSeconds(1), n * 3L + 1,
                ('A'.code + n).toChar().toString().repeat(43), "synthetic/seal/$n", "route-1", hash(n), hash(100 + n), hash(200 + n), hash(300),
                at.plusSeconds(400_000_000), time.plusSeconds(2), at.plusSeconds(400_000_000), time.plusSeconds(2), "version-$n", time.plusSeconds(2),
                at.plusSeconds(400_000_000), time.plusSeconds(3), hash(400 + n), if (n == 1) "" else hash(n - 1),
                if (n == 1) 0 else 1, hash(500 + n), if (n == 1) 341 else 541, 2_097_152, 2_097_152)
        })
    }
    private fun document(h: TestActiveCheckpointHistoryV1, predecessor: String = "d".repeat(64), fence: Long = 99): TestActiveRecurrentCheckpointDocumentV1 {
        val time = h.last.verifiedAt.plusSeconds(1)
        val count = h.entries.sumOf { it.eventCount }
        return TestActiveRecurrentCheckpointDocumentV1(h.first.identity, fence, h.last.operationToken, predecessor, h.rootSha256,
            h.entries.map(TestActiveRecurrentCheckpointDocumentV1.Range::from),
            TestActiveRecurrentCheckpointDocumentV1.Pass(time, time.plusSeconds(1), "a".repeat(64), "c".repeat(64), count, count * 1_024),
            TestActiveRecurrentCheckpointDocumentV1.Pass(time.plusSeconds(2), time.plusSeconds(3), "a".repeat(64), "c".repeat(64), count, count * 1_024))
    }
    private fun entryChanged(entry: TestActiveCheckpointHistoryV1.Entry, field: String, value: kotlinx.serialization.json.JsonElement): TestActiveCheckpointHistoryV1.Entry =
        TestActiveCheckpointHistoryV1.Entry.parse(CanonicalJson.canonicalize(JsonObject(entry.json().toMutableMap().apply { put(field, value) })).toByteArray())
}
