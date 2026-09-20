package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Length-framed KJEV wire only. Separate seal schemas still decide every authenticated field. */
internal class EpochSealWireV1(private val limits: JournalDecoderLimitsV1) {
    fun pack(header: ByteArray, wrapped: ByteArray, ciphertext: ByteArray): ByteArray {
        val length = OUTER_BYTES.toLong() + header.size + wrapped.size + ciphertext.size
        requireEpochSeal(length <= limits.maximumEnvelopeBytes, EpochSealFailureV1.LIMIT_EXCEEDED)
        return ByteBuffer.allocate(length.toInt()).putInt(MAGIC).putInt(1)
            .putInt(header.size).put(header).putInt(wrapped.size).put(wrapped).putInt(ciphertext.size).put(ciphertext).array()
    }

    fun split(wire: ByteArray, buffers: EpochSealBuffersV1): Parts {
        val input = ByteBuffer.wrap(wire)
        requireEpochSeal(input.remaining() >= OUTER_BYTES && input.int == MAGIC && input.int == 1)
        val header = section(input, 1, minOf(4096, limits.maximumPlaintextBytes), buffers)
        val wrapped = section(input, 1, limits.maximumWrappedKeyBytes, buffers)
        val ciphertext = section(input, TAG_BYTES + 1, limits.maximumPlaintextBytes + TAG_BYTES, buffers)
        requireEpochSeal(!input.hasRemaining())
        return Parts(header, wrapped, ciphertext)
    }

    private fun section(input: ByteBuffer, minimum: Int, maximum: Int, buffers: EpochSealBuffersV1): ByteArray {
        requireEpochSeal(input.remaining() >= 4)
        val length = input.int.toLong() and 0xffff_ffffL
        requireEpochSeal(length in minimum.toLong()..maximum.toLong(), EpochSealFailureV1.LIMIT_EXCEEDED)
        requireEpochSeal(length <= input.remaining().toLong())
        return buffers.own(ByteArray(length.toInt())).also(input::get)
    }

    internal class Parts(val header: ByteArray, val wrapped: ByteArray, val ciphertext: ByteArray)

    companion object {
        const val OUTER_BYTES = 20
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        private const val MAGIC = 0x4b4a4556

        fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(input) // No unauthenticated update output is ever released to the JSON parser.
        } catch (_: AEADBadTagException) {
            throw EpochSealExceptionV1(EpochSealFailureV1.AUTHENTICATION_FAILED)
        } catch (_: GeneralSecurityException) {
            throw EpochSealExceptionV1(EpochSealFailureV1.CRYPTO_FAILURE)
        }
    }
}

internal class EpochSealBuffersV1 {
    private val owned = ArrayList<ByteArray>()

    fun own(bytes: ByteArray): ByteArray = bytes.also { owned.add(it) }
    fun clear() = owned.forEach { it.fill(0) }
}

internal fun <T> withEpochSealBuffers(action: (EpochSealBuffersV1) -> T): T {
    val buffers = EpochSealBuffersV1()
    try {
        return action(buffers)
    } finally {
        buffers.clear()
    }
}
