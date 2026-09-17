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
    val activeKeyId: String,
    verificationKeys: Map<String, ByteArray>,
    forbiddenKeys: List<ByteArray>,
    private val clock: Clock,
) {
    private val keys: Map<String, ByteArray>
    val protocol: ComplaintOwnerCursorProtocol get() = ComplaintOwnerCursorProtocol

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

    /** Only the actual immutable verifier map is projected; never a second caller-supplied key list. */
    internal fun verificationKeyIds(): Set<String> = keys.keys.toSet()

    fun encode(actor: ScopedInstallationId, limit: Int, position: ComplaintOwnerHistoryPosition): String {
        requireConnectionFree()
        require(limit in 1..protocol.MAX_PAGE_LIMIT) { CONFIGURATION }
        val output = ByteArrayOutputStream(256)
        DataOutputStream(output).use { data ->
            field(data, ascii(protocol.ROUTE))
            field(data, ascii(protocol.DIRECTION))
            field(data, selection(actor, limit))
            data.writeLong(position.createdAt.epochSecond)
            data.writeInt(position.createdAt.nano)
            data.writeLong(position.id.mostSignificantBits)
            data.writeLong(position.id.leastSignificantBits)
            data.writeLong(clock.instant().plusSeconds(protocol.TTL_SECONDS).epochSecond)
            field(data, ascii(activeKeyId))
        }
        val payload = output.toByteArray()
        return "${protocol.ENVELOPE_VERSION}.${base64(payload)}.${base64(mac(checkNotNull(keys[activeKeyId]), frame(actor, payload)))}"
    }

    @Suppress("SwallowedException") // Input-bearing decoder/provider diagnostics must not escape.
    fun decode(value: String, actor: ScopedInstallationId, limit: Int): ComplaintOwnerHistoryPosition {
        requireConnectionFree()
        return try {
            if (limit !in 1..protocol.MAX_PAGE_LIMIT || value.length > protocol.MAX_CURSOR_CHARACTERS || !CURSOR.matches(value)) invalid()
            val parts = value.split('.')
            val payload = decodePart(parts[1], protocol.MAX_PAYLOAD_BYTES)
            val signature = decodePart(parts[2], protocol.SIGNATURE_BYTES)
            if (signature.size != protocol.SIGNATURE_BYTES) invalid()
            val decoded = payload(payload, actor, limit)
            val key = keys[decoded.keyId] ?: invalid()
            if (!MessageDigest.isEqual(signature, mac(key, frame(actor, payload)))) invalid()
            val now = clock.instant()
            if (!decoded.expiry.isAfter(now) ||
                decoded.expiry.isAfter(now.plusSeconds(protocol.TTL_SECONDS + protocol.FUTURE_SKEW_SECONDS))
            ) {
                invalid()
            }
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
        if (!MessageDigest.isEqual(field(data, 64), ascii(protocol.ROUTE)) || !MessageDigest.isEqual(field(data, 16), ascii(protocol.DIRECTION))) invalid()
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
        .digest(ascii("${protocol.SELECTION_DOMAIN}:${actor.scope.id}:$limit"))

    private fun frame(actor: ScopedInstallationId, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(640)
        DataOutputStream(output).use { data ->
            field(data, ascii(protocol.MAC_DOMAIN))
            field(data, ascii(protocol.ACTOR_KIND))
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
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
        val CURSOR = Regex("${ComplaintOwnerCursorProtocol.ENVELOPE_VERSION}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    }
}

/** Existing codec constants, not a new serialized configuration protocol, digest or activation proof. */
internal object ComplaintOwnerCursorProtocol {
    const val ENVELOPE_VERSION = "v1"
    const val SELECTION_DOMAIN = "owner-list-v1"
    const val MAC_DOMAIN = "kira-complaint-owner-cursor-v1"
    const val ACTOR_KIND = "INSTALLATION"
    const val ROUTE = "GET:/api/v1/complaints"
    const val DIRECTION = "DESC"
    const val TTL_SECONDS = 900L
    const val FUTURE_SKEW_SECONDS = 60L
    const val MAX_PAGE_LIMIT = 50
    const val MAX_CURSOR_CHARACTERS = 2048
    const val MAX_PAYLOAD_BYTES = 512
    const val SIGNATURE_BYTES = 32
}
