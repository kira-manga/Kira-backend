package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnership
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Deterministic synthetic keys prove framing/separation, never real custody or rotation readiness. */
class ComplaintAdminCursorCodecTest {
    @Test
    fun independentFramingBindsNormalizedSelectionAndNonV4NoticePosition() {
        val selection = query(
            text = " \tcafé\r\n日本語😀\t ",
            status = ComplaintStatus.IN_PROGRESS,
            type = ComplaintType.SITE_ERROR,
            ownership = ComplaintOwnership.INSTALLATION,
            updatedFrom = LOWER,
            updatedBefore = UPPER,
            limit = 17,
        )
        // Independent fixed-order vector: LP32 UTF-8, one-byte nullable markers, big-endian numbers.
        val hash = MessageDigest.getInstance("SHA-256").digest(
            wire {
                field("kira-complaint-admin-search-selection-v1")
                field(SCOPE.id.toString())
                field("café\n日本語😀")
                writeByte(1)
                field("IN_PROGRESS")
                writeByte(1)
                field("SITE_ERROR")
                writeByte(1)
                field("INSTALLATION")
                writeByte(1)
                writeLong(LOWER.epochSecond)
                writeInt(100000000)
                writeByte(1)
                writeLong(UPPER.epochSecond)
                writeInt(123456000)
                field("UPDATED_DESC")
                writeInt(17)
            },
        )
        val expectedPayload = referencePayload(hash = hash)
        val cursors = createCodec()
        val value = cursors.encode(ACTOR, selection, POSITION)
        assertEquals(sign(expectedPayload), value)
        val payload = Base64.getUrlDecoder().decode(value.split('.')[1])
        assertArrayEquals(expectedPayload, payload)
        assertTrue(value.length <= 2048 && Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matches(value))
        assertTrue(payload.size <= 512)
        assertEquals(32, Base64.getUrlDecoder().decode(value.split('.')[2]).size)
        assertEquals(5, POSITION.id.version())
        assertPosition(POSITION, cursors.decode(value, ACTOR, selection))
        val visibleBytes = payload.toString(Charsets.ISO_8859_1)
        for (privateValue in listOf(ACTOR.toString(), SCOPE.id.toString(), selection.text)) {
            assertFalse(visibleBytes.contains(privateValue.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)))
        }
    }

    @Test
    fun omittedExplicitAndNormalizedDefaultsMatchAndCursorIsExcluded() {
        val cursors = createCodec()
        val value = cursors.encode(ACTOR, query(), POSITION)
        val explicit = query(
            text = " \t\r\n ",
            status = null,
            type = null,
            ownership = null,
            updatedFrom = null,
            updatedBefore = null,
            sort = "UPDATED_DESC",
            limit = 50,
            cursor = value,
        )
        assertEquals(value, cursors.encode(ACTOR, explicit, POSITION))
        assertPosition(POSITION, cursors.decode(value, ACTOR, explicit))
        val padded = query(text = "\u2002\talpha\r\nbeta \u00a0")
        val normalized = query(text = "alpha\nbeta")
        val textCursor = cursors.encode(ACTOR, padded, POSITION)
        assertEquals(textCursor, cursors.encode(ACTOR, normalized, POSITION))
        assertPosition(POSITION, cursors.decode(textCursor, ACTOR, normalized))
        assertEquals(sign(referencePayload()), value)
    }

    @Test
    fun actorScopeAndEverySelectionFilterBindTheCursor() {
        val cursors = createCodec()
        fun selection(
            scope: ComplaintDataScope = SCOPE,
            text: String = "panel",
            status: ComplaintStatus? = ComplaintStatus.OPEN,
            type: ComplaintType? = ComplaintType.TECHNICAL,
            ownership: ComplaintOwnership? = ComplaintOwnership.INSTALLATION,
            lower: Instant? = LOWER,
            upper: Instant? = UPPER,
            limit: Int = 25,
        ): ComplaintAdminSearchQuery = query(scope, text, status, type, ownership, lower, upper, limit = limit)

        val selected = selection()
        val value = cursors.encode(ACTOR, selected, POSITION)
        invalid { cursors.decode(value, OTHER_ACTOR, selected) }
        listOf(
            selection(scope = OTHER_SCOPE), selection(text = "Panel"), selection(text = ""),
            selection(status = ComplaintStatus.RESOLVED), selection(status = null),
            selection(type = ComplaintType.FEATURES), selection(type = null),
            selection(ownership = ComplaintOwnership.SYSTEM), selection(ownership = null),
            selection(lower = LOWER.plusNanos(1000)), selection(lower = null),
            selection(upper = UPPER.minusNanos(1000)), selection(upper = null), selection(limit = 26),
        ).forEach { changed -> invalid { cursors.decode(value, ACTOR, changed) } }
        val wrongSort = referencePayload(hash = referenceSelection(selected, sort = "UPDATED_ASC"))
        invalid { cursors.decode(sign(wrongSort), ACTOR, selected) }
    }

    @Test
    fun fixedRouteDirectionActorKindAndDomainsCannotBeSubstituted() {
        val cursors = createCodec()
        val selection = query()
        for (route in listOf("GET:/api/v1/complaints", "GET:/api/v1/admin/complaints/search", "$ROUTE/", ROUTE.lowercase())) {
            invalid { cursors.decode(sign(referencePayload(route = route)), ACTOR, selection) }
        }
        for (direction in listOf("ASC", "desc", "DESC ")) {
            invalid { cursors.decode(sign(referencePayload(direction = direction)), ACTOR, selection) }
        }
        val payload = referencePayload()
        invalid { cursors.decode(sign(payload, kind = "INSTALLATION"), ACTOR, selection) }
        invalid { cursors.decode(sign(payload, kind = "admin"), ACTOR, selection) }
        invalid { cursors.decode(sign(payload, domain = "kira-complaint-owner-cursor-v1"), ACTOR, selection) }
        invalid { cursors.decode(sign(payload, actor = OTHER_ACTOR), ACTOR, selection) }
        val otherDomain = referencePayload(hash = referenceSelection(selection, domain = "owner-list-v1"))
        invalid { cursors.decode(sign(otherDomain), ACTOR, selection) }
        invalid { cursors.decode(sign(referencePayload(hash = ByteArray(32))), ACTOR, selection) }
    }

    @Test
    fun envelopeBoundsSignatureSizesAndNoncanonicalBase64AliasesAreRefused() {
        val cursors = createCodec()
        val selection = query()
        val valid = cursors.encode(ACTOR, selection, POSITION)
        val parts = valid.split('.')
        val payload = Base64.getUrlDecoder().decode(parts[1])
        val signature = Base64.getUrlDecoder().decode(parts[2])
        val payloadAlias = padBitsAlias(parts[1])
        val signatureAlias = padBitsAlias(parts[2])
        assertArrayEquals(payload, Base64.getUrlDecoder().decode(payloadAlias))
        assertArrayEquals(signature, Base64.getUrlDecoder().decode(signatureAlias))
        val tampered = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tooLong = "v1.${"A".repeat(2043)}.AA"
        assertEquals(2049, tooLong.length)
        listOf(
            "", " ", "v2." + valid.substringAfter('.'), "V1." + valid.substringAfter('.'), "v01." + valid.substringAfter('.'),
            "$valid=", " $valid", "$valid ", "$valid.extra", "v1..${parts[2]}", "v1.${parts[1]}.",
            "v1.A.${parts[2]}", "v1.${parts[1]}.A", "v1.${parts[1]}=.${parts[2]}", "v1.+A.${parts[2]}", "v1./A.${parts[2]}",
            "v1.${parts[1]}\n.${parts[2]}", "v1.$payloadAlias.${parts[2]}", "v1.${parts[1]}.$signatureAlias", tooLong,
            sign(ByteArray(513)), envelope(payload, ByteArray(31)), envelope(payload, ByteArray(33)), envelope(payload, tampered),
        ).forEach { invalid { cursors.decode(it, ACTOR, selection) } }
    }

    @Test
    fun authenticatedMalformedPayloadLengthsTruncationAndTrailingBytesAreSanitized() {
        val cursors = createCodec()
        val selection = query()
        val payload = referencePayload()
        for (length in payload.indices) invalid { cursors.decode(sign(payload.copyOf(length)), ACTOR, selection) }
        val directionOffset = 4 + ROUTE.length
        val hashOffset = directionOffset + 4 + "DESC".length
        val keyOffset = hashOffset + 4 + 32 + 8 + 4 + 16 + 8
        for ((offset, maximum) in listOf(0 to 64, directionOffset to 16, hashOffset to 32, keyOffset to 64)) {
            for (length in listOf(-1, 0, maximum + 1, Int.MAX_VALUE)) {
                invalid { cursors.decode(sign(withInt(payload, offset, length)), ACTOR, selection) }
            }
        }
        listOf(
            referencePayload(route = ""), referencePayload(direction = ""), referencePayload(hash = ByteArray(0)),
            referencePayload(hash = ByteArray(31)), referencePayload(hash = ByteArray(33)), referencePayload(keyId = byteArrayOf()),
            referencePayload(keyId = "unknown".toByteArray(Charsets.UTF_8)), referencePayload(keyId = "bad key".toByteArray(Charsets.UTF_8)),
            referencePayload(keyId = byteArrayOf(0xff.toByte())), referencePayload(keyId = ByteArray(65) { 'a'.code.toByte() }),
            payload + byteArrayOf(0), payload.copyOf(512),
        ).forEach { invalid { cursors.decode(sign(it), ACTOR, selection) } }
    }

    @Test
    fun positionsAcceptFiniteMicrosecondsAndAnyResourceUuidButNeverNormalizeInvalidNanos() {
        val cursors = createCodec()
        val selection = query()
        listOf(
            ComplaintAdminReadPosition(Instant.parse("0001-01-01T00:00:00Z"), UUID(0, 0)),
            ComplaintAdminReadPosition(Instant.parse("9999-12-31T23:59:59.999999Z"), UUID(-1, -1)),
            ComplaintAdminReadPosition(Instant.ofEpochSecond(-1, 999999000), POSITION.id),
            ComplaintAdminReadPosition(Instant.EPOCH, ACTOR),
        ).forEach { position -> assertPosition(position, cursors.decode(cursors.encode(ACTOR, selection, position), ACTOR, selection)) }
        for (nanos in listOf(-1, 1, 999999999, 1000000000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            invalid { cursors.decode(sign(referencePayload(nanos = nanos)), ACTOR, selection) }
        }
        val invalidSeconds = listOf(
            Instant.parse("0001-01-01T00:00:00Z").epochSecond - 1,
            Instant.parse("9999-12-31T23:59:59Z").epochSecond + 1,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
        )
        for (seconds in invalidSeconds) {
            invalid { cursors.decode(sign(referencePayload(seconds = seconds)), ACTOR, selection) }
        }
    }

    @Test
    fun expiryIsExclusiveAndMaximumFutureSkewIsSixtySeconds() {
        val selection = query()
        val value = createCodec().encode(ACTOR, selection, POSITION)
        assertPosition(POSITION, createCodec(NOW.plusSeconds(900).minusNanos(1)).decode(value, ACTOR, selection))
        invalid { createCodec(NOW.plusSeconds(900)).decode(value, ACTOR, selection) }
        invalid { createCodec(NOW.plusSeconds(901)).decode(value, ACTOR, selection) }
        assertPosition(POSITION, createCodec(NOW.minusSeconds(60)).decode(value, ACTOR, selection))
        invalid { createCodec(NOW.minusSeconds(61)).decode(value, ACTOR, selection) }
        val cursors = createCodec()
        val skewBoundary = createCodec(NOW.plusSeconds(60)).encode(ACTOR, selection, POSITION)
        assertPosition(POSITION, cursors.decode(skewBoundary, ACTOR, selection))
        val future = createCodec(NOW.plusSeconds(61)).encode(ACTOR, selection, POSITION)
        invalid { cursors.decode(future, ACTOR, selection) }
        for (expiry in listOf(NOW.epochSecond - 1, NOW.epochSecond, NOW.epochSecond + 961, Long.MIN_VALUE, Long.MAX_VALUE)) {
            invalid { cursors.decode(sign(referencePayload(expiry = expiry)), ACTOR, selection) }
        }
        val fractional = createCodec(NOW.plusNanos(123456789)).encode(ACTOR, selection, POSITION)
        assertEquals(value, fractional)
    }

    @Test
    fun retainedVerifierRotationAcceptsOldKeysOnlyUntilTheOriginalExpiry() {
        val selection = query()
        val old = createCodec().encode(ACTOR, selection, POSITION)
        val retained = mapOf(KEY_ID to key(), "next.key-_" to key(73))
        val next = createCodec(active = "next.key-_", keys = retained)
        assertPosition(POSITION, next.decode(old, ACTOR, selection))
        val fresh = next.encode(ACTOR, selection, POSITION)
        assertPosition(POSITION, next.decode(fresh, ACTOR, selection))
        invalid { createCodec().decode(fresh, ACTOR, selection) }
        invalid { createCodec(active = "next.key-_", keys = mapOf("next.key-_" to key(73))).decode(old, ACTOR, selection) }
        invalid { createCodec(NOW.plusSeconds(900), "next.key-_", retained).decode(old, ACTOR, selection) }
    }

    @Test
    fun configurationRejectsWeakUnknownOversizedDuplicateAndEffectivelyReusedKeys() {
        configurationInvalid { createCodec(keys = emptyMap()) }
        configurationInvalid { createCodec(active = "absent") }
        configurationInvalid { createCodec(keys = (0..8).associate { "key$it" to key(it) }, active = "key0") }
        for (id in listOf("", "a".repeat(65), "bad key", "bad\nkey", "kid:/", "日本語")) {
            configurationInvalid { createCodec(active = id, keys = mapOf(id to key())) }
        }
        for (size in listOf(0, 31, 129)) {
            configurationInvalid { createCodec(keys = mapOf(KEY_ID to key(size = size))) }
            configurationInvalid { createCodec(forbidden = listOf(key(177, size))) }
        }
        configurationInvalid { createCodec(forbidden = emptyList()) }
        configurationInvalid { createCodec(forbidden = List(65) { key(177) }) }
        configurationInvalid { createCodec(forbidden = listOf(key())) }
        configurationInvalid { createCodec(forbidden = listOf(key().copyOf(64))) }
        configurationInvalid { createCodec(keys = mapOf(KEY_ID to key().copyOf(64)), forbidden = listOf(key())) }
        configurationInvalid { createCodec(keys = mapOf(KEY_ID to key(), "duplicate" to key())) }
        configurationInvalid { createCodec(keys = mapOf(KEY_ID to key(), "padded" to key().copyOf(64))) }
        val longKey = key(size = 128)
        val effectiveKey = MessageDigest.getInstance("SHA-256").digest(longKey)
        configurationInvalid { createCodec(keys = mapOf(KEY_ID to longKey, "digest-alias" to effectiveKey)) }
        configurationInvalid { createCodec(keys = mapOf(KEY_ID to longKey), forbidden = listOf(effectiveKey)) }
    }

    @Test
    fun configurationAcceptsExactRingKeyIdKeySizeAndForbiddenListBounds() {
        val active = "a".repeat(64)
        val keys = (0 until 8).associate { index ->
            (if (index == 0) active else "key.$index-_") to key(17 + index * 3, if (index % 2 == 0) 128 else 32)
        }
        val forbidden = List(64) { key(100 + it, if (it % 2 == 0) 128 else 32) }
        val cursors = createCodec(active = active, keys = keys, forbidden = forbidden)
        assertEquals(active, cursors.activeKeyId)
        val selection = query()
        assertPosition(POSITION, cursors.decode(cursors.encode(ACTOR, selection, POSITION), ACTOR, selection))
        val shortestId = createCodec(active = ".", keys = mapOf("." to key()))
        assertPosition(POSITION, shortestId.decode(shortestId.encode(ACTOR, selection, POSITION), ACTOR, selection))
    }

    @Test
    fun defensiveKeyCopiesAndRedactedViewsSurviveCallerMutation() {
        val material = key()
        val otherMaterial = key(73)
        val forbiddenMaterial = key(177)
        val keys = mutableMapOf(KEY_ID to material, "next" to otherMaterial)
        val forbidden = mutableListOf(forbiddenMaterial)
        val cursors = createCodec(keys = keys, forbidden = forbidden)
        val selection = query(text = "private search prose")
        val value = cursors.encode(ACTOR, selection, POSITION)
        val next = createCodec(active = "next", keys = mapOf("next" to otherMaterial)).encode(ACTOR, selection, POSITION)
        material.fill(0)
        otherMaterial.fill(0)
        forbiddenMaterial.fill(0)
        keys.clear()
        forbidden.clear()
        assertEquals(value, cursors.encode(ACTOR, selection, POSITION))
        assertPosition(POSITION, cursors.decode(value, ACTOR, selection))
        assertPosition(POSITION, cursors.decode(next, ACTOR, selection))
        assertEquals("ComplaintAdminCursorCodec(redacted)", cursors.toString())
        assertEquals("ComplaintAdminSearchQuery(redacted)", selection.toString())
        assertEquals("ComplaintAdminReadPosition(redacted)", POSITION.toString())
        invalid { cursors.decode("private invalid cursor prose", ACTOR, selection) }
    }

    @Test
    fun encodingAndDecodingRefuseRetainedSpringPersistenceBeforeCursorWork() {
        val cursors = createCodec()
        val selection = query()
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            entryRefused { cursors.encode(ACTOR, selection, POSITION) }
            entryRefused { cursors.decode("malformed", ACTOR, selection) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        TransactionSynchronizationManager.initSynchronization()
        try {
            entryRefused { cursors.encode(ACTOR, selection, POSITION) }
            entryRefused { cursors.decode("malformed", ACTOR, selection) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        val resource = Any()
        TransactionSynchronizationManager.bindResource(resource, Any())
        try {
            entryRefused { cursors.encode(ACTOR, selection, POSITION) }
            entryRefused { cursors.decode("malformed", ACTOR, selection) }
        } finally {
            TransactionSynchronizationManager.unbindResource(resource)
        }
        assertPosition(POSITION, cursors.decode(cursors.encode(ACTOR, selection, POSITION), ACTOR, selection))
    }

    private fun createCodec(
        at: Instant = NOW,
        active: String = KEY_ID,
        keys: Map<String, ByteArray> = mapOf(KEY_ID to key()),
        forbidden: List<ByteArray> = listOf(key(177), key(193), key(209), key(225)),
    ): ComplaintAdminCursorCodec = ComplaintAdminCursorCodec(active, keys, forbidden, Clock.fixed(at, ZoneOffset.UTC))

    private fun query(
        scope: ComplaintDataScope = SCOPE,
        text: String = "",
        status: ComplaintStatus? = null,
        type: ComplaintType? = null,
        ownership: ComplaintOwnership? = null,
        updatedFrom: Instant? = null,
        updatedBefore: Instant? = null,
        sort: String = "UPDATED_DESC",
        limit: Int = 50,
        cursor: String? = null,
    ): ComplaintAdminSearchQuery = ComplaintAdminSearchQuery(scope, text, status, type, ownership, updatedFrom, updatedBefore, sort, limit, cursor)

    private fun referenceSelection(
        query: ComplaintAdminSearchQuery,
        domain: String = "kira-complaint-admin-search-selection-v1",
        sort: String = query.sort,
    ): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        wire {
            field(domain)
            field(query.scope.id.toString())
            field(query.text)
            for (value in listOf(query.status?.name, query.type?.name, query.ownership?.name)) {
                writeByte(if (value == null) 0 else 1)
                if (value != null) field(value)
            }
            for (value in listOf(query.updatedFrom, query.updatedBefore)) {
                writeByte(if (value == null) 0 else 1)
                if (value != null) {
                    writeLong(value.epochSecond)
                    writeInt(value.nano)
                }
            }
            field(sort)
            writeInt(query.limit)
        },
    )

    private fun referencePayload(
        hash: ByteArray = referenceSelection(query()),
        route: String = ROUTE,
        direction: String = "DESC",
        seconds: Long = POSITION.updatedAt.epochSecond,
        nanos: Int = POSITION.updatedAt.nano,
        id: UUID = POSITION.id,
        expiry: Long = NOW.plusSeconds(900).epochSecond,
        keyId: ByteArray = KEY_ID.toByteArray(Charsets.UTF_8),
    ): ByteArray = wire {
        field(route)
        field(direction)
        field(hash)
        writeLong(seconds)
        writeInt(nanos)
        writeLong(id.mostSignificantBits)
        writeLong(id.leastSignificantBits)
        writeLong(expiry)
        field(keyId)
    }

    private fun sign(
        payload: ByteArray,
        signingKey: ByteArray = key(),
        actor: UUID = ACTOR,
        domain: String = "kira-complaint-admin-cursor-v1",
        kind: String = "ADMIN",
    ): String {
        val frame = wire {
            field(domain)
            field(kind)
            field(actor.toString())
            field(payload)
        }
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signingKey, "HmacSHA256"))
            doFinal(frame)
        }
        return envelope(payload, signature)
    }

    private fun wire(body: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data -> body(data) }
        return output.toByteArray()
    }

    private fun DataOutputStream.field(value: String) = field(value.toByteArray(Charsets.UTF_8))

    private fun DataOutputStream.field(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun envelope(payload: ByteArray, signature: ByteArray): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "v1.${encoder.encodeToString(payload)}.${encoder.encodeToString(signature)}"
    }

    private fun padBitsAlias(value: String): String {
        assertTrue(value.length % 4 in 2..3)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return value.dropLast(1) + alphabet[alphabet.indexOf(value.last()) + 1]
    }

    private fun withInt(value: ByteArray, offset: Int, replacement: Int): ByteArray = value.copyOf().also {
        ByteBuffer.wrap(it).putInt(offset, replacement)
    }

    private fun key(seed: Int = 17, size: Int = 32): ByteArray = ByteArray(size) { (seed + it).toByte() }

    private fun assertPosition(expected: ComplaintAdminReadPosition, actual: ComplaintAdminReadPosition) {
        assertEquals(expected.updatedAt, actual.updatedAt)
        assertEquals(expected.id, actual.id)
    }

    private fun invalid(work: () -> Unit) {
        val failure = assertThrows<ComplaintAdminReadRejected> { work() }
        assertEquals(ComplaintAdminReadFailure.INVALID_CURSOR, failure.failure)
        assertEquals("Complaint Admin read refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.stackTrace.isEmpty())
    }

    private fun configurationInvalid(work: () -> Unit) {
        val failure = assertThrows<IllegalArgumentException> { work() }
        assertEquals("Invalid Admin cursor configuration.", failure.message)
        assertNull(failure.cause)
    }

    private fun entryRefused(work: () -> Unit) {
        val failure = assertThrows<PersistencePhaseException> { work() }
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
        assertNull(failure.cause)
    }

    private companion object {
        const val KEY_ID = "admin-key"
        const val ROUTE = "POST:/api/v1/admin/complaints/search"
        val NOW = Instant.parse("2026-09-19T12:34:56Z")
        val LOWER = Instant.parse("2026-09-01T02:03:04.100000Z")
        val UPPER = Instant.parse("2026-09-19T12:34:56.123456Z")
        val ACTOR = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val OTHER_ACTOR = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val SCOPE = ComplaintDataScope.of(UUID.fromString("123e4567-e89b-42d3-a456-426614174001"))
        val OTHER_SCOPE = ComplaintDataScope.of(UUID.fromString("123e4567-e89b-42d3-a456-426614174002"))
        val POSITION = ComplaintAdminReadPosition(
            Instant.parse("2026-09-18T02:03:04.123456Z"),
            UUID.fromString("ffffffff-ffff-5fff-bfff-ffffffffffff"),
        )
    }
}
