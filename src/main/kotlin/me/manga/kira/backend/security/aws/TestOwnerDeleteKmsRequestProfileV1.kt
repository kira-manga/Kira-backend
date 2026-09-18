package me.manga.kira.backend.security.aws

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.security.JournalDataKeyRequestV1
import java.nio.ByteBuffer
import java.util.Base64

/** Exactly OWNER_DELETE/TEST/run header and context. No LIVE or seal profile is accepted. */
internal class TestOwnerDeleteKmsRequestProfileV1(val journal: TestOwnerDeleteJournalConfigurationV1) {

    private val declaration = journal.declaration()
    val keyArn: String = declaration.encryption.keyArn
    val region: String = declaration.journalLocation.region
    val callLimitMillis: Int = declaration.limits.deadlines.kmsCallMillis
    private val wrappedLimit = declaration.limits.decoder.maximumWrappedKeyBytes
    private val prefix = journal.ordinaryPrefix
    private val routingIds = declaration.routing.keys.map { it.keyId }.toSet()

    fun prepare(
        request: JournalDataKeyRequestV1,
        operation: JournalKmsOperation,
        wrapped: ByteArray?,
        started: Long,
        nanoTime: () -> Long,
    ): JournalKmsCall {
        requireJournalKms(request.keyArn == keyArn && request.dataKeyBytes == 32)
        requireJournalKms(request.timeoutMillis in 1..callLimitMillis)
        requireJournalKms(request.maximumWrappedKeyBytes in 1..wrappedLimit)
        val maximum = minOf(request.maximumWrappedKeyBytes, wrappedLimit, MAX_WRAPPED_BYTES)
        requireJournalKms((operation == JournalKmsOperation.DECRYPT) == (wrapped != null))
        wrapped?.let { requireJournalKms(it.size in 1..maximum) }
        val context = request.encryptionContext()
        requireJournalKms(context.size == 1 && context.containsKey(CONTEXT_KEY))
        val value = checkNotNull(context[CONTEXT_KEY])
        requireJournalKms(value.length in 1..MAX_CONTEXT_BYTES - CONTEXT_KEY.length)
        val decoded = canonicalUrlBytes(value)
        try {
            val fields = frameFields(decoded)
            validateHeader(fields)
        } finally {
            decoded.fill(0)
        }
        val copied = wrapped?.copyOf()
        return runCatching {
            JournalKmsCall(operation, keyArn, value, maximum, request.timeoutMillis, copied, started, nanoTime)
        }.getOrElse { failure ->
            copied?.fill(0)
            throw failure
        }
    }

    private fun frameFields(bytes: ByteArray): List<String> {
        val input = ByteBuffer.wrap(bytes)
        val fieldCount = 20
        val fields = ArrayList<String>(fieldCount)
        repeat(fieldCount) {
            requireJournalKms(input.remaining() >= 4)
            val count = input.int.toLong() and 0xffff_ffffL
            val minimum = 1L
            requireJournalKms(count in minimum..4096L && count <= input.remaining().toLong())
            val start = input.position()
            repeat(count.toInt()) { requireJournalKms(input.get().toInt() in 32..126) }
            fields.add(String(bytes, start, count.toInt(), Charsets.US_ASCII))
        }
        requireJournalKms(!input.hasRemaining())
        return fields
    }

    private fun validateHeader(fields: List<String>) {
        validateFixedHeader(fields, "OWNER_DELETE")
        val epoch = fields[16].toLongOrNull()
        requireJournalKms(epoch != null && epoch > 0 && epoch.toString() == fields[16])
        requireJournalKms(fields[17] in routingIds)
        val objectPrefix = "${prefix}writer/${declaration.writer.generationId}/epoch/${fields[16].padStart(19, '0')}/${fields[17]}/"
        requireJournalKms(fields[11].startsWith(objectPrefix))
        requireUrlSize(fields[11].removePrefix(objectPrefix), 32)
        requireUrlSize(fields[18], 32)
        requireUrlSize(fields[19], 12)
    }

    private fun validateFixedHeader(fields: List<String>, objectKind: String) {
        val fixed = mapOf(
            0 to CONTEXT_DOMAIN,
            1 to "1",
            2 to "1",
            3 to "1",
            4 to "kcj-1",
            5 to objectKind,
            6 to "AES-256-GCM",
            7 to "FRESH_PER_OBJECT_KMS_WRAPPED",
            8 to declaration.encryption.keyId,
            9 to keyArn,
            10 to declaration.journalLocation.bucket,
            12 to declaration.writer.generationId,
            13 to prefix,
            14 to "TEST",
            15 to journal.scope.id.toString(),
        )
        requireJournalKms(fixed.all { (index, expected) -> fields[index] == expected })
    }

    private fun requireUrlSize(value: String, size: Int) {
        val bytes = canonicalUrlBytes(value)
        try {
            requireJournalKms(bytes.size == size)
        } finally {
            bytes.fill(0)
        }
    }

    private fun canonicalUrlBytes(value: String): ByteArray {
        requireJournalKms(value.isNotEmpty() && value.length <= MAX_CONTEXT_BYTES)
        requireJournalKms(value.all { it in URL_ALPHABET })
        val bytes = Base64.getUrlDecoder().decode(value)
        return runCatching {
            requireJournalKms(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
            bytes
        }.getOrElse { failure ->
            bytes.fill(0)
            throw failure
        }
    }

    override fun toString(): String = "TestOwnerDeleteKmsRequestProfileV1(J-bound,redacted,no-authority)"

    companion object {
        const val MAX_WRAPPED_BYTES = 6144
        const val MAX_CONTEXT_BYTES = 8192
        const val CONTEXT_KEY = "kira-complaint-journal-context-v1"
        const val CONTEXT_DOMAIN = "kira-complaint-journal-kms-context-v1"
        private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    }
}
