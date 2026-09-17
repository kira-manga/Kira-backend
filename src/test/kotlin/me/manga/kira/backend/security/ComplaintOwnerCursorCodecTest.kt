package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPosition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRejected
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ComplaintOwnerCursorCodecTest {
    private val now = Instant.parse("2026-09-17T01:00:00Z")
    private val actor = ScopedInstallationId(UUID.randomUUID(), ComplaintDataScope.of(UUID.randomUUID()))
    private val position = ComplaintOwnerHistoryPosition(now.minusSeconds(10).plusNanos(123456789), UUID.randomUUID())
    private val codec = historyTestCursors(clock(now))

    @Test
    fun `real cursor MAC round trips exact tuple while payload omits actor identity`() {
        val value = codec.encode(actor, 50, position)
        assertTrue(value.length <= 2048 && Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matches(value))
        val decoded = codec.decode(value, actor, 50)
        assertEquals(position.id, decoded.id)
        assertEquals(position.createdAt, decoded.createdAt)
        val payload = Base64.getUrlDecoder().decode(value.split('.')[1]).toString(Charsets.ISO_8859_1)
        assertFalse(payload.contains(actor.id.toString()))
        assertFalse(payload.contains(actor.scope.id.toString()))
        assertFalse(codec.toString().contains("history-cursor"))
    }

    @Test
    fun `malformed envelope noncanonical Base64 oversized payload and tampered MAC are bounded refusals`() {
        val valid = codec.encode(actor, 50, position)
        for (value in listOf("", "v2." + valid.substringAfter('.'), valid + "=", "v1.A.A", "v1.${"A".repeat(2050)}.A", JwtTestSupport.tamperSignature(valid))) {
            invalid { codec.decode(value, actor, 50) }
        }
        invalid { codec.decode("v1.${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(513))}." + valid.substringAfterLast('.'), actor, 50) }
    }

    @Test
    fun `actor scope limit route direction and future expiry cannot be substituted even with a valid payload MAC`() {
        val valid = codec.encode(actor, 50, position)
        invalid { codec.decode(valid, ScopedInstallationId(UUID.randomUUID(), actor.scope), 50) }
        invalid { codec.decode(valid, ScopedInstallationId(actor.id, ComplaintDataScope.of(UUID.randomUUID())), 50) }
        invalid { codec.decode(valid, actor, 49) }
        val payload = Base64.getUrlDecoder().decode(valid.split('.')[1])
        val route = payload.copyOf().also { it[4] = 'P'.code.toByte() }
        invalid { codec.decode(resign(route), actor, 50) }
        val direction = payload.copyOf().also { it[4 + "GET:/api/v1/complaints".length + 4] = 'A'.code.toByte() }
        invalid { codec.decode(resign(direction), actor, 50) }
        val future = historyTestCursors(clock(now.plusSeconds(61))).encode(actor, 50, position)
        invalid { codec.decode(future, actor, 50) }
    }

    @Test
    fun `fifteen minute expiry is exclusive and retained verifier rotation accepts only known keys`() {
        val old = codec.encode(actor, 50, position)
        assertEquals(position.id, historyTestCursors(clock(now.plusSeconds(900).minusNanos(1))).decode(old, actor, 50).id)
        invalid { historyTestCursors(clock(now.plusSeconds(900))).decode(old, actor, 50) }
        val retained = mapOf("history-cursor" to historyTestCursorKey(), "next" to historyTestCursorKey(111))
        val next = historyTestCursors(clock(now), "next", retained)
        assertEquals(position.id, next.decode(old, actor, 50).id)
        val fresh = next.encode(actor, 50, position)
        invalid { codec.decode(fresh, actor, 50) }
        invalid { historyTestCursors(clock(now), "next", mapOf("next" to historyTestCursorKey(111))).decode(old, actor, 50) }
    }

    @Test
    fun `weak duplicate effective reused and unknown active keys fail configuration without enabling anything`() {
        assertThrows<IllegalArgumentException> { historyTestCursors(clock(now), "absent") }
        assertThrows<IllegalArgumentException> { historyTestCursors(clock(now), keys = mapOf("history-cursor" to ByteArray(31))) }
        assertThrows<IllegalArgumentException> { historyTestCursors(clock(now), keys = mapOf("history-cursor" to historyTestJwtKey())) }
        assertThrows<IllegalArgumentException> {
            historyTestCursors(clock(now), keys = mapOf("history-cursor" to historyTestCursorKey(), "alias" to historyTestCursorKey().copyOf(64)))
        }
        assertThrows<IllegalArgumentException> {
            ComplaintOwnerCursorCodec("key", mapOf("key" to historyTestJwtKey().copyOf(64)), listOf(historyTestJwtKey()), clock(now))
        }
        val material = historyTestCursorKey()
        val stable = historyTestCursors(clock(now), keys = mapOf("history-cursor" to material))
        val token = stable.encode(actor, 50, position)
        material.fill(0)
        assertEquals(position.id, stable.decode(token, actor, 50).id)
    }

    private fun resign(payload: ByteArray): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            for (part in listOf("kira-complaint-owner-cursor-v1".toByteArray(), "INSTALLATION".toByteArray(), actor.id.toString().toByteArray(), payload)) {
                data.writeInt(part.size)
                data.write(part)
            }
        }
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(historyTestCursorKey(), "HmacSHA256"))
            doFinal(output.toByteArray())
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "v1.${encoder.encodeToString(payload)}.${encoder.encodeToString(signature)}"
    }

    private fun invalid(work: () -> Unit) {
        val failure = assertThrows<ComplaintOwnerHistoryRejected> { work() }
        assertEquals(ComplaintOwnerHistoryFailure.INVALID_CURSOR, failure.failure)
        assertEquals(null, failure.cause)
    }

    private fun clock(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)
}
