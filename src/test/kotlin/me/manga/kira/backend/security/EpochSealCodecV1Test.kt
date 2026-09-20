package me.manga.kira.backend.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.security.EpochSealTestFixtureV1.Companion.sha
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EpochSealCodecV1Test {
    @Test
    fun `exact twelve payload twenty header and twenty two context fields match independent encryption and preserved copies`() {
        val fixture = EpochSealTestFixtureV1()
        val attempt = fixture.codec.startAttempt()
        val content = fixture.content(attempt)
        val p = content.payload
        val payload = JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(1), "objectKind" to JsonPrimitive("EPOCH_SEAL"), "sealId" to JsonPrimitive(content.route.sealId),
                "writerGeneration" to JsonPrimitive(fixture.journal.declaration().writer.generationId), "dataScopeKind" to JsonPrimitive("LIVE"),
                "dataScopeId" to JsonPrimitive(SCOPE), "epochStartInclusive" to JsonPrimitive(1), "epochEndInclusive" to JsonPrimitive(42),
                "eventCount" to JsonPrimitive(2), "eventManifestSha256" to JsonPrimitive(p.eventManifestSha256),
                "precedingSealSha256" to JsonPrimitive(""), "preparingFencingToken" to JsonPrimitive(7),
            ),
        )
        assertArrayEquals(canonical(payload), content.canonicalBytes())
        assertEquals(sha(canonical(payload)), content.semanticSha256)
        val encoded = fixture.codec.seal(content, attempt)
        val wire = encoded.wireBytes()
        val parts = parts(wire)
        val h = parts.header
        assertEquals(HEADER_ORDER.toSet(), h.keys)
        assertEquals(20, h.size)
        assertEquals("EPOCH_SEAL", h.text("objectKind"))
        assertEquals("", h.text("precedingSealSha256"))
        assertFalse(h.containsKey("ordinaryPrefix"))
        val request = fixture.keys.requests.single()
        assertEquals(
            mapOf(CONTEXT_KEY to b64(frame(listOf("kira-complaint-journal-kms-context-v1", "1") + values(h)))),
            request.encryptionContext(),
        )
        assertArrayEquals(ByteArray(64) { (it + 19).toByte() }, parts.wrapped)
        assertArrayEquals(authenticate(parts, content.canonicalBytes()), wire)
        val opened = fixture.codec.open(fixture.bucket, content.route.objectKey, content, wire, attempt)
        assertArrayEquals(content.canonicalBytes(), opened.content.canonicalBytes())
        assertEquals(encoded.wireSha256, opened.wireSha256)
        assertSame(content, opened.content)
        encoded.wireBytes().fill(0)
        assertArrayEquals(wire, encoded.wireBytes())
        content.canonicalBytes().fill(0)
        assertArrayEquals(canonical(payload), content.canonicalBytes())
        val second = fixture.codec.seal(content, attempt)
        assertNotEquals(encoded.wireSha256, second.wireSha256)
        assertNotEquals(parts.header.text("nonce"), parts(second.wireBytes()).header.text("nonce"))
        assertTrue(fixture.keys.transferred.all { it.all { b -> b == 0.toByte() } })
        assertTrue(fixture.keys.unwrapInputs.all { it.all { b -> b == 0.toByte() } })
        assertTrue(fixture.nonces.destinations.all { it.all { b -> b == 0.toByte() } })
        val rendered = listOf(fixture.codec, content, encoded, opened, content.payload, content.route, attempt, request).joinToString()
        listOf(fixture.bucket, content.semanticSha256, content.route.sealId, "route-b", p.writerGeneration).forEach { assertFalse(rendered.contains(it)) }
    }

    @Test
    fun `all frozen header identities reject before KMS and nonce wrapped ciphertext or tag changes never authenticate`() {
        val fixture = EpochSealTestFixtureV1()
        fixture.keys.permitContextMismatch = true // Isolate GCM authentication from the fake KMS context check.
        val attempt = fixture.codec.startAttempt()
        val content = fixture.content(attempt)
        val original = parts(fixture.codec.seal(content, attempt).wireBytes())
        HEADER_ORDER.forEach { field ->
            val changed = when (field) {
                "envelopeSchemaVersion", "payloadSchemaVersion", "epochStartInclusive", "epochEndInclusive" -> JsonPrimitive(99)
                "sealId" -> JsonPrimitive(b64(ByteArray(32) { 9 }))
                "nonce" -> JsonPrimitive(b64(ByteArray(12) { (it + 1).toByte() }))
                else -> JsonPrimitive(original.header.text(field) + "-different")
            }
            val before = fixture.keys.unwraps
            val failure = rejected {
                open(fixture, content, pack(canonical(change(original.header, field, changed)), original.wrapped, original.encrypted), attempt)
            }
            assertEquals(if (field == "nonce") 1 else 0, fixture.keys.unwraps - before, field)
            if (field == "nonce") assertEquals(EpochSealFailureV1.AUTHENTICATION_FAILED, failure.code)
        }
        listOf(0, original.encrypted.size - 16, original.encrypted.lastIndex).forEach { index ->
            val altered = original.encrypted.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(
                EpochSealFailureV1.AUTHENTICATION_FAILED,
                rejected { open(fixture, content, pack(canonical(original.header), original.wrapped, altered), attempt) }.code,
            )
        }
        val alteredWrapped = original.wrapped.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(
            EpochSealFailureV1.AUTHENTICATION_FAILED,
            rejected { open(fixture, content, pack(canonical(original.header), alteredWrapped, original.encrypted), attempt) }.code,
        )
        val before = fixture.keys.unwraps
        rejected {
            val bytes = pack(canonical(original.header), original.wrapped, original.encrypted)
            fixture.codec.open("other-bucket", content.route.objectKey, content, bytes, attempt)
        }
        rejected { fixture.codec.open(fixture.bucket, content.route.objectKey + "x", content, ByteArray(1), attempt) }
        assertEquals(before, fixture.keys.unwraps)
    }

    @Test
    fun `closed grammar rejects duplicate unknown omitted coerced malformed UTF8 and oversized wire before expensive unwrap`() {
        val fixture = EpochSealTestFixtureV1()
        val attempt = fixture.codec.startAttempt()
        val content = fixture.content(attempt)
        val original = parts(fixture.codec.seal(content, attempt).wireBytes())
        val initial = canonical(original.header).toString(Charsets.UTF_8)
        val headers = listOf(
            (initial.dropLast(1) + ",\"objectKind\":\"EPOCH_SEAL\"}").toByteArray(),
            canonical(change(original.header, "actorId", JsonPrimitive("forbidden"))),
            canonical(JsonObject(original.header - "precedingSealSha256")),
            canonical(change(original.header, "epochEndInclusive", JsonPrimitive("42"))),
            canonical(change(original.header, "epochEndInclusive", JsonPrimitive(42.0))),
            canonical(change(original.header, "epochEndInclusive", JsonPrimitive(true))),
            canonical(change(original.header, "epochEndInclusive", JsonNull)),
            initial.replace("\"epochEndInclusive\":42", "\"epochEndInclusive\":042").toByteArray(),
            initial.replace("\"epochEndInclusive\":42", "\"epochEndInclusive\":9223372036854775808").toByteArray(),
            initial.replace("\"nonce\":\"", "\"nonce\":\"\\ud800").toByteArray(),
            byteArrayOf(0xc0.toByte(), 0x80.toByte()), initial.toByteArray() + 0.toByte(),
        )
        val before = fixture.keys.unwraps
        headers.forEach { rejected { open(fixture, content, pack(it, original.wrapped, original.encrypted), attempt) } }
        rejected { open(fixture, content, ByteArray(98_305), attempt) }
        val hugeLength = pack(canonical(original.header), original.wrapped, original.encrypted).also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) }
        rejected { open(fixture, content, hugeLength, attempt) }
        assertEquals(before, fixture.keys.unwraps)
    }

    @Test
    fun `authenticated payload cannot change frozen count token scope or kind and restoration never regenerates retained IDs`() {
        val fixture = EpochSealTestFixtureV1()
        val attempt = fixture.codec.startAttempt()
        val content = fixture.content(attempt)
        val original = parts(fixture.codec.seal(content, attempt).wireBytes())
        val payload = Json.parseToJsonElement(content.canonicalBytes().toString(Charsets.UTF_8)).jsonObject
        val mutations = listOf(
            change(payload, "preparingFencingToken", JsonPrimitive(8)), change(payload, "eventCount", JsonPrimitive(1)),
            change(payload, "objectKind", JsonPrimitive("OWNER_DELETE_ALL")), change(payload, "dataScopeKind", JsonPrimitive("TEST")),
            change(payload, "eventCount", JsonPrimitive("2")), change(payload, "eventCount", JsonPrimitive(2.0)),
            change(payload, "preparingFencingToken", JsonPrimitive(-1)), change(payload, "preparingFencingToken", JsonNull),
            change(payload, "actorId", JsonPrimitive("forbidden")), JsonObject(payload - "precedingSealSha256"),
        )
        mutations.forEach { altered ->
            val before = fixture.keys.unwraps
            rejected { open(fixture, content, authenticate(original, canonical(altered)), attempt) }
            assertEquals(before + 1, fixture.keys.unwraps)
        }
        val restored = fixture.codec.restoreCanonical(
            content.canonicalBytes(),
            content.route.routingKeyId,
            content.route.objectKey,
            content.semanticSha256,
            attempt,
        )
        assertArrayEquals(content.canonicalBytes(), restored.canonicalBytes())
        assertEquals(7L, restored.payload.preparingFencingToken)
        rejected { fixture.codec.restoreCanonical(content.canonicalBytes(), "route-a", content.route.objectKey, content.semanticSha256, attempt) }
        rejected { fixture.codec.restoreCanonical(content.canonicalBytes(), "route-b", content.route.objectKey, "f".repeat(64), attempt) }
        val routes = fixture.owner.deriveEpochSeal(
            EpochSealRoutingTupleV1(EpochSealRangeV1(1, 42, ""), content.payload.eventManifestSha256),
        )
        val prior = routes.candidates().first()
        val priorBytes = canonical(change(payload, "sealId", JsonPrimitive(prior.sealId)))
        val priorContent = fixture.codec.restoreCanonical(priorBytes, prior.routingKeyId, prior.objectKey, sha(priorBytes), attempt)
        assertEquals("route-a", priorContent.route.routingKeyId)
        assertArrayEquals(priorBytes, priorContent.canonicalBytes())
        assertEquals(1, fixture.keys.generations) // Restoration is local and cannot call KMS or silently adopt the active key.
    }

    @Test
    fun `same owner original seal total and enclosing total never restart and all transferred keys clear on late return`() {
        val fixture = EpochSealTestFixtureV1()
        val attempt = fixture.codec.startAttempt()
        val content = fixture.content(attempt)
        fixture.clock.nanos = 29_500_000_000L
        val encoded = fixture.codec.seal(content, attempt)
        assertEquals(500, fixture.keys.requests.last().timeoutMillis)
        fixture.clock.nanos = 29_800_000_000L
        open(fixture, content, encoded.wireBytes(), attempt)
        assertEquals(200, fixture.keys.requests.last().timeoutMillis)
        fixture.clock.nanos = 30_000_000_000L
        assertEquals(EpochSealFailureV1.DEADLINE_EXHAUSTED, rejected { fixture.codec.seal(content, attempt) }.code)
        fixture.clock.nanos = 29_900_000_000L
        assertEquals(EpochSealFailureV1.DEADLINE_EXHAUSTED, rejected { fixture.codec.seal(content, attempt) }.code)
        val foreign = EpochSealTestFixtureV1(fixture.journal)
        rejected { foreign.codec.seal(content, foreign.codec.startAttempt()) }
        assertEquals(0, foreign.keys.generations)

        val late = EpochSealTestFixtureV1()
        val parent = PersistenceTimeBudget.start(100, PersistenceNanoClock { late.clock.nanos })
        val bounded = late.codec.startAttempt(parent)
        val pending = late.content(bounded)
        late.clock.nanos = 99_000_000L
        late.keys.onGenerate = { late.clock.nanos = 100_000_000L }
        assertEquals(EpochSealFailureV1.DEADLINE_EXHAUSTED, rejected { late.codec.seal(pending, bounded) }.code)
        assertEquals(1, late.keys.requests.single().timeoutMillis)
        assertEquals(1, late.keys.closes)
        assertTrue(late.keys.transferred.all { it.all { b -> b == 0.toByte() } })
        assertEquals(EpochSealFailureV1.DEADLINE_EXHAUSTED, rejected { late.codec.startAttempt(parent) }.code)
    }

    @Test
    fun `canonical construction refuses active JDBC and cleanup preserves bounded cancellation fatal and failure signals`() {
        val fixture = EpochSealTestFixtureV1()
        val attempt = fixture.codec.startAttempt()
        val manifest = fixture.manifest(attempt, emptyList())
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows(PersistencePhaseException::class.java) { fixture.codec.canonicalize(manifest, 7, attempt) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        val content = fixture.codec.canonicalize(manifest, 7, attempt)
        fixture.keys.closeFailure = IllegalStateException("synthetic-private-close")
        assertEquals(EpochSealFailureV1.KEY_CLEANUP_FAILURE, rejected { fixture.codec.seal(content, attempt) }.code)
        fixture.keys.closeFailure = CancellationException("synthetic-private-cancel")
        val cancelled = assertThrows(CancellationException::class.java) { fixture.codec.seal(content, attempt) }
        assertNull(cancelled.cause)
        assertFalse(cancelled.toString().contains("synthetic"))
        fixture.keys.closeFailure = null
        val fatal = AssertionError("synthetic-private-fatal")
        fixture.keys.keyArnFailure = fatal
        assertSame(fatal, assertThrows(AssertionError::class.java) { fixture.codec.seal(content, attempt) })
        assertEquals(3, fixture.keys.closes)
        assertTrue(fixture.keys.transferred.all { it.all { b -> b == 0.toByte() } })
    }

    private fun open(fixture: EpochSealTestFixtureV1, content: EpochSealContentV1, wire: ByteArray, attempt: EpochSealAttemptV1): EpochSealDecodedV1 =
        fixture.codec.open(fixture.bucket, content.route.objectKey, content, wire, attempt)

    private fun rejected(action: () -> Unit): EpochSealExceptionV1 {
        val failure = assertThrows(EpochSealExceptionV1::class.java) { action() }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains("synthetic"))
        return failure
    }

    private fun authenticate(parts: Parts, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(ByteArray(32) { (it + 13).toByte() }, "AES"),
            GCMParameterSpec(128, unb64(parts.header.text("nonce"))),
        )
        cipher.updateAAD(
            frame(
                listOf("kira-complaint-journal-aad-v1", "1", "KJEV", "1", canonical(parts.header).size.toString()) + values(parts.header) +
                    listOf(parts.wrapped.size.toString(), b64(parts.wrapped), (plaintext.size + 16).toString()),
            ),
        )
        return pack(canonical(parts.header), parts.wrapped, cipher.doFinal(plaintext))
    }

    private fun pack(header: ByteArray, wrapped: ByteArray, encrypted: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x4b4a4556)
            out.writeInt(1)
            listOf(header, wrapped, encrypted).forEach {
                out.writeInt(it.size)
                out.write(it)
            }
        }
        bytes.toByteArray()
    }

    private fun frame(fields: List<String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            fields.forEach { value ->
                val encoded = value.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size)
                out.write(encoded)
            }
        }
        bytes.toByteArray()
    }

    private fun parts(wire: ByteArray): Parts {
        val input = ByteBuffer.wrap(wire)
        assertEquals(0x4b4a4556, input.int)
        assertEquals(1, input.int)
        fun section(): ByteArray = ByteArray(input.int).also { input.get(it) }
        return Parts(Json.parseToJsonElement(section().toString(Charsets.UTF_8)).jsonObject, section(), section())
    }

    private fun values(header: JsonObject): List<String> = HEADER_ORDER.map { header.getValue(it).jsonPrimitive.content }
    private fun canonical(value: JsonObject): ByteArray = JsonObject(value.toSortedMap()).toString().toByteArray(Charsets.UTF_8)
    private fun change(value: JsonObject, key: String, replacement: JsonElement): JsonObject = JsonObject(value + (key to replacement))
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private class Parts(val header: JsonObject, val wrapped: ByteArray, val encrypted: ByteArray)

    private companion object {
        const val SCOPE = "00000000-0000-0000-0000-000000000000"
        const val CONTEXT_KEY = "kira-complaint-journal-context-v1"
        val HEADER_ORDER = listOf(
            "envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
            "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "sealTerminalPrefix", "dataScopeKind", "dataScopeId",
            "epochStartInclusive", "epochEndInclusive", "routingKeyId", "sealId", "precedingSealSha256", "nonce",
        )
    }
}
