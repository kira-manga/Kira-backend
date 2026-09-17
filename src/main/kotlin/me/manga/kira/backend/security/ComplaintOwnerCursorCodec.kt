package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPosition
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.rejectOwnerHistory
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

/** Fixed owner-list codec. Explicit retained keys are not deployment/rotation provenance. No bean. */
internal class ComplaintOwnerCursorCodec(
    private val activeKeyId: String,
    verificationKeys: Map<String, ByteArray>,
    forbiddenKeys: List<ByteArray>,
    private val clock: Clock,
) {
    private val keys: Map<String, ByteArray>

    init {
        require(verificationKeys.size in 1..8 && forbiddenKeys.size in 1..64) { CONFIGURATION }
        require(forbiddenKeys.all { it.size in 32..128 }) { CONFIGURATION }
        require(verificationKeys.containsKey(activeKeyId)) { CONFIGURATION }
        val copied = verificationKeys.map { (id, key) ->
            require(KEY_ID.matches(id) && key.size in 32..128) { CONFIGURATION }
            require(forbiddenKeys.none { sameKey(it, key) }) { CONFIGURATION }
            id to key.copyOf()
        }
        require(copied.indices.none { a -> (0 until a).any { b -> sameKey(copied[a].second, copied[b].second) } }) { CONFIGURATION }
        keys = copied.toMap()
    }

    fun encode(actor: ScopedInstallationId, limit: Int, position: ComplaintOwnerHistoryPosition): String {
        requireConnectionFree()
        require(limit in 1..50) { CONFIGURATION }
        val output = ByteArrayOutputStream(256)
        DataOutputStream(output).use { data ->
            field(data, ascii(ROUTE))
            field(data, ascii(DIRECTION))
            field(data, selection(actor, limit))
            data.writeLong(position.createdAt.epochSecond)
            data.writeInt(position.createdAt.nano)
            data.writeLong(position.id.mostSignificantBits)
            data.writeLong(position.id.leastSignificantBits)
            data.writeLong(clock.instant().plusSeconds(TTL_SECONDS).epochSecond)
            field(data, ascii(activeKeyId))
        }
        val payload = output.toByteArray()
        return "v1.${base64(payload)}.${base64(mac(checkNotNull(keys[activeKeyId]), frame(actor, payload)))}"
    }

    @Suppress("SwallowedException") // Input-bearing decoder/provider diagnostics must not escape.
    fun decode(value: String, actor: ScopedInstallationId, limit: Int): ComplaintOwnerHistoryPosition {
        requireConnectionFree()
        return try {
            if (limit !in 1..50 || value.length > 2048 || !CURSOR.matches(value)) invalid()
            val parts = value.split('.')
            val payload = decodePart(parts[1], 512)
            val signature = decodePart(parts[2], 32)
            if (signature.size != 32) invalid()
            val decoded = payload(payload, actor, limit)
            val key = keys[decoded.keyId] ?: invalid()
            if (!MessageDigest.isEqual(signature, mac(key, frame(actor, payload)))) invalid()
            val now = clock.instant()
            if (!decoded.expiry.isAfter(now) || decoded.expiry.isAfter(now.plusSeconds(TTL_SECONDS + 60))) invalid()
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

    private fun payload(bytes: ByteArray, actor: ScopedInstallationId, limit: Int): Payload = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        if (!MessageDigest.isEqual(field(data, 64), ascii(ROUTE)) || !MessageDigest.isEqual(field(data, 16), ascii(DIRECTION))) invalid()
        if (!MessageDigest.isEqual(field(data, 32), selection(actor, limit))) invalid()
        val seconds = data.readLong()
        val nanos = data.readInt()
        if (nanos !in 0..999999999) invalid()
        val position = ComplaintOwnerHistoryPosition(Instant.ofEpochSecond(seconds, nanos.toLong()), UUID(data.readLong(), data.readLong()))
        val expiry = Instant.ofEpochSecond(data.readLong())
        val keyId = field(data, 64).toString(Charsets.US_ASCII)
        if (!KEY_ID.matches(keyId) || data.read() != -1) invalid()
        Payload(position, expiry, keyId)
    }

    private fun selection(actor: ScopedInstallationId, limit: Int): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(ascii("owner-list-v1:${actor.scope.id}:$limit"))

    private fun frame(actor: ScopedInstallationId, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(640)
        DataOutputStream(output).use { data ->
            field(data, ascii("kira-complaint-owner-cursor-v1"))
            field(data, ascii("INSTALLATION"))
            field(data, ascii(actor.id.toString()))
            field(data, payload)
        }
        return output.toByteArray()
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

    private fun mac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun sameKey(first: ByteArray, second: ByteArray): Boolean = MessageDigest.isEqual(
        mac(first, ascii("kira-owner-cursor-key-separation-v1")),
        mac(second, ascii("kira-owner-cursor-key-separation-v1")),
    )

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)
    private fun base64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun invalid(): Nothing = rejectOwnerHistory(ComplaintOwnerHistoryFailure.INVALID_CURSOR)
    override fun toString(): String = "ComplaintOwnerCursorCodec(redacted)"

    private class Payload(val position: ComplaintOwnerHistoryPosition, val expiry: Instant, val keyId: String)

    private companion object {
        const val CONFIGURATION = "Invalid owner cursor configuration."
        const val TTL_SECONDS = 900L
        const val ROUTE = "GET:/api/v1/complaints"
        const val DIRECTION = "DESC"
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
        val CURSOR = Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    }
}
