package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fixed Admin-search codec, not DB ADMIN authorization or deployment/rotation provenance. No bean. */
internal class ComplaintAdminCursorCodec(
    val activeKeyId: String,
    verificationKeys: Map<String, ByteArray>,
    forbiddenKeys: List<ByteArray>,
    private val clock: Clock,
) {
    private val keys: Map<String, ByteArray> = copyKeys(verificationKeys, forbiddenKeys)

    fun encode(actorId: UUID, query: ComplaintAdminSearchQuery, position: ComplaintAdminReadPosition): String {
        requireConnectionFree()
        val output = ByteArrayOutputStream(256)
        DataOutputStream(output).use { data ->
            field(data, utf8(ROUTE))
            field(data, utf8(DIRECTION))
            field(data, selection(query))
            data.writeLong(position.updatedAt.epochSecond)
            data.writeInt(position.updatedAt.nano)
            data.writeLong(position.id.mostSignificantBits)
            data.writeLong(position.id.leastSignificantBits)
            data.writeLong(clock.instant().plusSeconds(TTL_SECONDS).epochSecond)
            field(data, utf8(activeKeyId))
        }
        val payload = output.toByteArray()
        val signature = mac(checkNotNull(keys[activeKeyId]), frame(actorId, payload))
        return "v1.${base64(payload)}.${base64(signature)}"
    }

    @Suppress("SwallowedException") // Discard all input-bearing decoder, position and provider diagnostics.
    fun decode(value: String, actorId: UUID, query: ComplaintAdminSearchQuery): ComplaintAdminReadPosition {
        requireConnectionFree()
        return try {
            if (value.length > MAX_CURSOR_CHARACTERS || !CURSOR.matches(value)) invalid()
            val parts = value.split('.')
            val payload = decodePart(parts[1], MAX_PAYLOAD_BYTES)
            val signature = decodePart(parts[2], SIGNATURE_BYTES)
            if (signature.size != SIGNATURE_BYTES) invalid()
            val decoded = payload(payload, query)
            val key = keys[decoded.keyId] ?: invalid()
            if (!MessageDigest.isEqual(signature, mac(key, frame(actorId, payload)))) invalid()
            val now = clock.instant()
            if (!decoded.expiry.isAfter(now) || decoded.expiry.isAfter(now.plusSeconds(TTL_SECONDS + FUTURE_SKEW_SECONDS))) invalid()
            decoded.position
        } catch (ex: IOException) {
            invalid()
        } catch (ex: IllegalArgumentException) {
            invalid()
        } catch (ex: DateTimeException) {
            invalid()
        } catch (ex: ArithmeticException) {
            invalid()
        } catch (ex: GeneralSecurityException) {
            invalid()
        }
    }

    private fun payload(bytes: ByteArray, query: ComplaintAdminSearchQuery): Payload = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        if (!MessageDigest.isEqual(field(data, 64), utf8(ROUTE)) || !MessageDigest.isEqual(field(data, 16), utf8(DIRECTION))) invalid()
        if (!MessageDigest.isEqual(field(data, 32), selection(query))) invalid()
        val seconds = data.readLong()
        val nanos = data.readInt()
        // Instant.ofEpochSecond normalizes arbitrary nanosecond adjustments; the wire must not.
        if (nanos !in 0..999999999) invalid()
        val position = ComplaintAdminReadPosition(Instant.ofEpochSecond(seconds, nanos.toLong()), UUID(data.readLong(), data.readLong()))
        val expiry = Instant.ofEpochSecond(data.readLong())
        val keyId = field(data, 64).toString(Charsets.US_ASCII)
        if (!KEY_ID.matches(keyId) || data.read() != -1) invalid()
        Payload(position, expiry, keyId)
    }

    private fun selection(query: ComplaintAdminSearchQuery): ByteArray {
        val output = ByteArrayOutputStream(640)
        DataOutputStream(output).use { data ->
            field(data, utf8(SELECTION_DOMAIN))
            field(data, utf8(query.scope.id.toString()))
            field(data, utf8(query.text)) // An empty normalized text is a zero-length field, not null.
            nullableField(data, query.status?.name)
            nullableField(data, query.type?.name)
            nullableField(data, query.ownership?.name)
            nullableTime(data, query.updatedFrom)
            nullableTime(data, query.updatedBefore)
            field(data, utf8(query.sort))
            data.writeInt(query.limit)
        }
        return MessageDigest.getInstance("SHA-256").digest(output.toByteArray())
    }

    private fun frame(actorId: UUID, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(640)
        DataOutputStream(output).use { data ->
            field(data, utf8(MAC_DOMAIN))
            field(data, utf8("ADMIN"))
            field(data, utf8(actorId.toString()))
            field(data, payload)
        }
        return output.toByteArray()
    }

    private fun nullableField(output: DataOutputStream, value: String?) {
        output.writeByte(if (value == null) 0 else 1)
        if (value != null) field(output, utf8(value))
    }

    private fun nullableTime(output: DataOutputStream, value: Instant?) {
        output.writeByte(if (value == null) 0 else 1)
        if (value != null) {
            output.writeLong(value.epochSecond)
            output.writeInt(value.nano)
        }
    }

    private fun field(output: DataOutputStream, value: ByteArray) {
        output.writeInt(value.size)
        output.write(value)
    }

    private fun field(input: DataInputStream, maximum: Int): ByteArray {
        val length = input.readInt()
        if (length !in 1..maximum || length > input.available()) invalid()
        return ByteArray(length).also(input::readFully)
    }

    private fun decodePart(value: String, maximum: Int): ByteArray {
        val bytes = Base64.getUrlDecoder().decode(value)
        if (bytes.size !in 1..maximum || base64(bytes) != value) invalid()
        return bytes
    }

    @Suppress("SwallowedException") // Configuration failures reveal neither key IDs nor provider diagnostics.
    private fun copyKeys(verificationKeys: Map<String, ByteArray>, forbiddenKeys: List<ByteArray>): Map<String, ByteArray> {
        require(verificationKeys.size in 1..8 && forbiddenKeys.size in 1..64) { CONFIGURATION }
        require(verificationKeys.containsKey(activeKeyId)) { CONFIGURATION }
        require(forbiddenKeys.all { it.size in 32..128 }) { CONFIGURATION }
        return try {
            val copied = verificationKeys.map { (id, key) ->
                require(KEY_ID.matches(id) && key.size in 32..128) { CONFIGURATION }
                val material = key.copyOf()
                require(forbiddenKeys.none { sameKey(it, material) }) { CONFIGURATION }
                id to material
            }
            require(copied.indices.none { a -> (0 until a).any { b -> sameKey(copied[a].second, copied[b].second) } }) { CONFIGURATION }
            copied.toMap()
        } catch (ex: GeneralSecurityException) {
            throw IllegalArgumentException(CONFIGURATION)
        }
    }

    private fun sameKey(first: ByteArray, second: ByteArray): Boolean = MessageDigest.isEqual(
        mac(first, utf8("kira-admin-cursor-key-separation-v1")),
        mac(second, utf8("kira-admin-cursor-key-separation-v1")),
    )

    private fun mac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun utf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
    private fun base64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun invalid(): Nothing = rejectAdminRead(ComplaintAdminReadFailure.INVALID_CURSOR)
    override fun toString(): String = "ComplaintAdminCursorCodec(redacted)"

    private class Payload(val position: ComplaintAdminReadPosition, val expiry: Instant, val keyId: String)

    private companion object {
        const val CONFIGURATION = "Invalid Admin cursor configuration."
        const val ROUTE = "POST:/api/v1/admin/complaints/search"
        const val DIRECTION = "DESC"
        const val SELECTION_DOMAIN = "kira-complaint-admin-search-selection-v1"
        const val MAC_DOMAIN = "kira-complaint-admin-cursor-v1"
        const val TTL_SECONDS = 900L
        const val FUTURE_SKEW_SECONDS = 60L
        const val MAX_CURSOR_CHARACTERS = 2048
        const val MAX_PAYLOAD_BYTES = 512
        const val SIGNATURE_BYTES = 32
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
        val CURSOR = Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    }
}
