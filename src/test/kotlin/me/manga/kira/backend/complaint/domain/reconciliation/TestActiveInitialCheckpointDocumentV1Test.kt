package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** Comparison syntax only. A caller-created document or matching hash does not issue checkpoint/native authority. */
internal class TestActiveInitialCheckpointDocumentV1Test {
    @Test fun fixedSchemaOneProfileBindsEveryScalarAndOrderedEmptyPassInCanonicalBytes() {
        val document = document()
        val bytes = document.canonicalBytes()
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("TEST_INITIAL_EMPTY_EPOCH1", json.getValue("profile").jsonPrimitive.content)
        assertEquals("kira-complaint-reconciliation-checkpoint", json.getValue("kind").jsonPrimitive.content)
        assertEquals("1", json.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("kcj-1", json.getValue("canonicalizerId").jsonPrimitive.content)
        assertEquals("SUCCESS", json.getValue("result").jsonPrimitive.content)
        assertEquals("1", json.getValue("cutoffEpoch").jsonPrimitive.content)
        assertEquals("0", json.getValue("objectCount").jsonPrimitive.content); assertEquals("0", json.getValue("byteCount").jsonPrimitive.content)
        assertEquals(document.sealOperationToken, json.getValue("scanId").jsonPrimitive.content)
        val values = json.getValue("passes").jsonArray
        assertEquals(2, values.size)
        values.forEachIndexed { index, value ->
            val pass = value.jsonObject
            assertEquals((index + 1).toString(), pass.getValue("pass").jsonPrimitive.content)
            assertEquals(document.manifestSha256, pass.getValue("manifestSha256").jsonPrimitive.content)
            assertEquals("0", pass.getValue("objectCount").jsonPrimitive.content); assertEquals("0", pass.getValue("byteCount").jsonPrimitive.content)
        }
        assertEquals("2030-01-01T00:00:00.000001Z", json.getValue("startedAt").jsonPrimitive.content)
        assertEquals("2030-01-01T00:00:03.000001Z", json.getValue("completedAt").jsonPrimitive.content)
        assertArrayEquals(CanonicalJson.canonicalize(json).toByteArray(), bytes)
        assertArrayEquals(bytes, document.canonicalBytes())
        assertEquals(64, Sha256.hex(bytes).length); assertTrue(bytes.size < 65_536)
        assertFalse(bytes.decodeToString().contains("SEAL_PREPARED"))
        assertEquals("r".repeat(1024), document(version = "r".repeat(1024)).sealObjectVersion)
    }

    @Test fun timesMustBeFiniteMicrosecondOrderedAndNoInitialProfileScalarCanBecomeGeneralReplay() {
        assertThrows<IllegalArgumentException> { TestActiveInitialCheckpointDocumentV1.Pass(at.plusNanos(1), at.plusSeconds(1)) }
        assertThrows<IllegalArgumentException> { TestActiveInitialCheckpointDocumentV1.Pass(at, at.minusNanos(1000)) }
        assertThrows<IllegalArgumentException> { TestActiveInitialCheckpointDocumentV1.Pass(Instant.parse("+10000-01-01T00:00:00Z"), Instant.MAX) }
        assertThrows<IllegalArgumentException> { document(secondStart = at) }
        assertThrows<IllegalArgumentException> { document(version = "null") }
        assertThrows<IllegalArgumentException> { document(version = "x".repeat(1025)) }
        assertThrows<IllegalArgumentException> { document(fence = 0) }
        assertThrows<IllegalArgumentException> { document(framed = 0) }
        assertThrows<IllegalArgumentException> { document(manifest = "A".repeat(64)) }
    }

    private val at = Instant.parse("2030-01-01T00:00:00.000001Z")
    private fun document(version: String = "retained-version", fence: Long = 11, framed: Long = 341,
        manifest: String = "c".repeat(64), secondStart: Instant = at.plusSeconds(2)) = TestActiveInitialCheckpointDocumentV1(
        scope = "11111111-1111-4111-8111-111111111111", desiredGeneration = 7, fencingToken = fence,
        configurationSha256 = "a".repeat(64), journalConfigurationSha256 = "b".repeat(64),
        databaseIdentity = "22222222-2222-4222-8222-222222222222", restoreIdentity = "33333333-3333-4333-8333-333333333333",
        catalogGeneration = 4, catalogSha256 = "d".repeat(64), trustBundleSha256 = "e".repeat(64),
        catalogWriterGeneration = "44444444-4444-4444-8444-444444444444", writerGeneration = "55555555-5555-4555-8555-555555555555",
        sealOperationToken = "66666666-6666-4666-8666-666666666666", sealObjectKey = "test/first/epoch-seal/key", sealObjectVersion = version,
        sealCanonicalSha256 = "f".repeat(64), sealCiphertextSha256 = "0".repeat(64), manifestSha256 = manifest, manifestFramedBytes = framed,
        first = TestActiveInitialCheckpointDocumentV1.Pass(at, at.plusSeconds(1)),
        second = TestActiveInitialCheckpointDocumentV1.Pass(secondStart, at.plusSeconds(3)))
}
