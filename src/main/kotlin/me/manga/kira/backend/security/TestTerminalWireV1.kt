package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventHeaderV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealHeaderV1
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bounded KJEV-1 framing/AEAD only. The closed TEST schemas choose every authenticated header field. */
internal class TestTerminalWireV1(limits: JournalDecoderLimitsV1) {
    val maximumPlaintextBytes = minOf(limits.maximumPlaintextBytes, TestTerminalProfileV1.MAX_PLAINTEXT_BYTES)
    val maximumEnvelopeBytes = minOf(limits.maximumEnvelopeBytes, TestTerminalProfileV1.MAX_ENVELOPE_BYTES)
    val maximumHeaderBytes = minOf(maximumPlaintextBytes, TestTerminalProfileV1.MAX_HEADER_BYTES)
    val maximumWrappedBytes = minOf(limits.maximumWrappedKeyBytes, TestTerminalProfileV1.MAX_WRAPPED_KEY_BYTES, MAX_PROVIDER_WRAPPED_BYTES)

    fun pack(header: ByteArray, wrapped: ByteArray, ciphertext: ByteArray): ByteArray {
        requireTestTerminalCodec(header.size in 1..maximumHeaderBytes, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
        requireTestTerminalCodec(wrapped.size in 1..maximumWrappedBytes, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
        requireTestTerminalCodec(
            ciphertext.size in TAG_BYTES + 1..maximumPlaintextBytes + TAG_BYTES,
            TestTerminalCodecFailureV1.LIMIT_EXCEEDED,
        )
        val length = OUTER_BYTES.toLong() + header.size + wrapped.size + ciphertext.size
        requireTestTerminalCodec(length <= maximumEnvelopeBytes, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
        return ByteBuffer.allocate(length.toInt()).putInt(MAGIC).putInt(1)
            .putInt(header.size).put(header).putInt(wrapped.size).put(wrapped).putInt(ciphertext.size).put(ciphertext).array()
    }

    fun split(wire: ByteArray, buffers: TestTerminalBuffersV1): Parts {
        requireTestTerminalCodec(wire.size in OUTER_BYTES..maximumEnvelopeBytes, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
        val input = ByteBuffer.wrap(wire)
        requireTestTerminalCodec(input.int == MAGIC && input.int == 1)
        val header = section(input, 1, maximumHeaderBytes, buffers)
        val wrapped = section(input, 1, maximumWrappedBytes, buffers)
        val ciphertext = section(input, TAG_BYTES + 1, maximumPlaintextBytes + TAG_BYTES, buffers)
        requireTestTerminalCodec(!input.hasRemaining())
        return Parts(header, wrapped, ciphertext)
    }

    private fun section(input: ByteBuffer, minimum: Int, maximum: Int, buffers: TestTerminalBuffersV1): ByteArray {
        requireTestTerminalCodec(input.remaining() >= 4)
        val length = input.int.toLong() and 0xffff_ffffL
        requireTestTerminalCodec(length in minimum.toLong()..maximum.toLong(), TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
        requireTestTerminalCodec(length <= input.remaining().toLong())
        return buffers.own(ByteArray(length.toInt())).also(input::get)
    }

    internal class Parts(val header: ByteArray, val wrapped: ByteArray, val ciphertext: ByteArray)

    companion object {
        const val OUTER_BYTES = 20
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_PROVIDER_WRAPPED_BYTES = 6144
        const val MAX_CONTEXT_BYTES = 8192
        const val AAD_DOMAIN = "kira-complaint-journal-aad-v1"
        const val KMS_DOMAIN = "kira-complaint-journal-kms-context-v1"
        const val CONTEXT_KEY = "kira-complaint-journal-context-v1"
        private const val MAGIC = 0x4b4a4556
        private const val MAX_FRAME_BYTES = 2 * TestTerminalProfileV1.MAX_ENVELOPE_BYTES

        /** LP32BE strict UTF-8; unlike descriptor frames, AAD has a bounded base64 wrapped-key field. */
        fun frame(fields: List<String>): ByteArray {
            requireTestTerminalCodec(fields.size in 1..32, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
            val encoded = ArrayList<ByteArray>(fields.size)
            try {
                var length = 0L
                fields.forEach { field ->
                    requireTestTerminalCodec(field.length <= MAX_FRAME_BYTES, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
                    val bytes = field.toByteArray(Charsets.UTF_8).also(encoded::add)
                    requireTestTerminalCodec(bytes.size <= MAX_FRAME_BYTES, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
                    requireTestTerminalCodec(bytes.toString(Charsets.UTF_8) == field)
                    length += 4L + bytes.size
                    requireTestTerminalCodec(length <= MAX_FRAME_BYTES, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
                }
                val output = ByteBuffer.allocate(length.toInt())
                encoded.forEach { output.putInt(it.size).put(it) }
                return output.array()
            } finally {
                encoded.forEach { it.fill(0) }
            }
        }

        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(input) // No unauthenticated plaintext from update() can reach a parser.
        } catch (_: AEADBadTagException) {
            throw TestTerminalCodecExceptionV1(TestTerminalCodecFailureV1.AUTHENTICATION_FAILED)
        } catch (_: GeneralSecurityException) {
            throw TestTerminalCodecExceptionV1(TestTerminalCodecFailureV1.CRYPTO_FAILURE)
        }
    }
}

/** Exact declaration order, not canonical JSON object-key order. */
internal fun TestTerminalEventHeaderV1.framedValues(): List<String> = listOf(
    envelopeSchemaVersion.toString(), payloadSchemaVersion.toString(), canonicalizerId, objectKind, encryptionAlgorithm, dataKeyMode,
    kmsKeyId, kmsKeyArn, bucket, objectKey, writerGeneration, sealTerminalPrefix, dataScopeKind, dataScopeId, publicationEpoch.toString(),
    routingKeyId, eventId, nonce,
)

internal fun TestTerminalSealHeaderV1.framedValues(): List<String> = listOf(
    envelopeSchemaVersion.toString(), payloadSchemaVersion.toString(), canonicalizerId, objectKind, encryptionAlgorithm, dataKeyMode,
    kmsKeyId, kmsKeyArn, bucket, objectKey, writerGeneration, sealTerminalPrefix, dataScopeKind, dataScopeId, epochStartInclusive.toString(),
    epochEndInclusive.toString(), routingKeyId, sealId, nonce,
)

internal class TestTerminalBuffersV1 {
    private val owned = ArrayList<ByteArray>()

    fun own(bytes: ByteArray): ByteArray = runCatching {
        owned.add(bytes)
        bytes
    }.getOrElse { failure ->
        bytes.fill(0)
        throw failure
    }
    fun clear() = owned.forEach { it.fill(0) }
}

internal fun <T> withTestTerminalBuffers(action: (TestTerminalBuffersV1) -> T): T {
    val buffers = TestTerminalBuffersV1()
    try {
        return action(buffers)
    } finally {
        buffers.clear()
    }
}
