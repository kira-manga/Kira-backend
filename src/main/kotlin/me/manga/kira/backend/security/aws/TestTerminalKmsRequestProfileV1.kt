package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventHeaderV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealHeaderV1
import me.manga.kira.backend.security.JournalDataKeyRequestV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalWireV1
import java.nio.ByteBuffer
import java.util.Base64

/** One exact TEST J/attempt/kind, never ordinary TEST, LIVE, caller-defined context or provider authority. */
internal class TestTerminalKmsRequestProfileV1(
    val journal: TestOwnerDeleteJournalConfigurationV1,
    private val attempt: TestTerminalAttemptV1,
) {
    private val declaration = journal.declaration()
    private val json = TestTerminalJsonV1(journal)
    val keyArn: String = declaration.encryption.keyArn
    val region: String = declaration.journalLocation.region
    val callLimitMillis: Int = declaration.limits.deadlines.kmsCallMillis
    private val wrappedLimit = minOf(declaration.limits.decoder.maximumWrappedKeyBytes, MAX_WRAPPED_BYTES)
    private val prefix = journal.sealTerminalPrefix
    private val routingIds = declaration.routing.keys.map { it.keyId }.toSet()

    init { attempt.requireJournal(journal) }

    fun prepare(request: JournalDataKeyRequestV1, operation: JournalKmsOperation, wrapped: ByteArray?, started: Long): JournalKmsCall {
        requireConnectionFree()
        attempt.requireJournal(journal)
        requireJournalKms(request.keyArn == keyArn && request.dataKeyBytes == 32)
        requireJournalKms(request.timeoutMillis in 1..callLimitMillis)
        // A wider declaration/request never silently widens the real provider's 6144-byte limit.
        requireJournalKms(request.maximumWrappedKeyBytes in 1..wrappedLimit)
        requireJournalKms((operation == JournalKmsOperation.DECRYPT) == (wrapped != null))
        wrapped?.let { requireJournalKms(it.size in 1..request.maximumWrappedKeyBytes) }
        val context = request.encryptionContext()
        requireJournalKms(context.size == 1 && context.containsKey(CONTEXT_KEY))
        val value = checkNotNull(context[CONTEXT_KEY])
        requireJournalKms(value.length in 1..MAX_CONTEXT_BYTES - CONTEXT_KEY.length)
        val decoded = canonicalUrlBytes(value)
        try {
            validateHeader(frameFields(decoded))
        } finally {
            decoded.fill(0)
        }
        val timeout = attempt.remainingProviderMillis(request.timeoutMillis)
        val copied = wrapped?.copyOf()
        return runCatching {
            JournalKmsCall(
                operation, keyArn, value, request.maximumWrappedKeyBytes, timeout, copied, started,
                attempt::providerNanoTime, terminalAttempt = attempt,
            )
        }.getOrElse { failure ->
            copied?.fill(0)
            throw failure
        }
    }

    private fun frameFields(bytes: ByteArray): List<String> {
        val input = ByteBuffer.wrap(bytes)
        val count = if (attempt.kind == TestTerminalCodecKindV1.EPOCH_SEAL) 21 else 20
        val fields = ArrayList<String>(count)
        repeat(count) {
            requireJournalKms(input.remaining() >= 4)
            val size = input.int.toLong() and 0xffff_ffffL
            requireJournalKms(size in 1L..4096L && size <= input.remaining().toLong())
            val start = input.position()
            repeat(size.toInt()) { requireJournalKms(input.get().toInt() in 32..126) }
            fields.add(String(bytes, start, size.toInt(), Charsets.US_ASCII))
        }
        requireJournalKms(!input.hasRemaining())
        return fields
    }

    private fun validateHeader(fields: List<String>) {
        val fixed = mapOf(
            0 to CONTEXT_DOMAIN, 1 to "1", 2 to "1", 3 to "1", 4 to "kcj-1", 5 to attempt.kind.name,
            6 to "AES-256-GCM", 7 to "FRESH_PER_OBJECT_KMS_WRAPPED", 8 to declaration.encryption.keyId, 9 to keyArn,
            10 to declaration.journalLocation.bucket, 12 to declaration.writer.generationId, 13 to prefix,
            14 to "TEST", 15 to journal.scope.id.toString(),
        )
        requireJournalKms(fixed.all { (index, expected) -> fields[index] == expected })
        val seal = attempt.kind == TestTerminalCodecKindV1.EPOCH_SEAL
        val start = positiveDecimal(fields[16])
        val end = if (seal) positiveDecimal(fields[17]) else start
        requireJournalKms(end >= start)
        val routeIndex = if (seal) 18 else 17
        requireJournalKms(fields[routeIndex] in routingIds)
        val path = when (attempt.kind) {
            TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> "installation-manifest"
            TestTerminalCodecKindV1.TEST_RUN_PURGE -> "test-run-purge"
            TestTerminalCodecKindV1.EPOCH_SEAL -> "epoch-seal"
        }
        val objectPrefix = "$prefix$end/${fields[routeIndex]}/$path/"
        val key = fields[11]
        requireJournalKms(key.length <= 1024 && key.startsWith(objectPrefix) && key.endsWith(".kjev"))
        requireUrlSize(key.removePrefix(objectPrefix).removeSuffix(".kjev"), 32)
        requireUrlSize(fields[routeIndex + 1], 32)
        requireUrlSize(fields[routeIndex + 2], 12)
        // A direct trusted-port caller must still fit the actual J header/parser limits, not just the context ceiling.
        val encoded = if (seal) {
            json.encodeSealHeader(
                TestTerminalSealHeaderV1(
                    1, 1, fields[4], fields[5], fields[6], fields[7], fields[8], fields[9], fields[10], key, fields[12], fields[13],
                    fields[14], fields[15], start, end, fields[18], fields[19], fields[20],
                ),
            )
        } else {
            json.encodeEventHeader(
                TestTerminalEventHeaderV1(
                    1, 1, fields[4], fields[5], fields[6], fields[7], fields[8], fields[9], fields[10], key, fields[12], fields[13],
                    fields[14], fields[15], start, fields[17], fields[18], fields[19],
                ),
            )
        }
        encoded.fill(0)
    }

    private fun positiveDecimal(value: String): Long {
        val parsed = value.toLongOrNull()
        requireJournalKms(parsed != null && parsed > 0 && parsed.toString() == value)
        return checkNotNull(parsed)
    }

    private fun requireUrlSize(value: String, size: Int) {
        val decoded = canonicalUrlBytes(value)
        try {
            requireJournalKms(decoded.size == size)
        } finally {
            decoded.fill(0)
        }
    }

    private fun canonicalUrlBytes(value: String): ByteArray {
        requireJournalKms(value.isNotEmpty() && value.length <= MAX_CONTEXT_BYTES && value.all { it in URL_ALPHABET })
        val bytes = Base64.getUrlDecoder().decode(value)
        return runCatching {
            requireJournalKms(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
            bytes
        }.getOrElse { failure ->
            bytes.fill(0)
            throw failure
        }
    }

    override fun toString(): String = "TestTerminalKmsRequestProfileV1(TEST,J-attempt-bound,redacted,no-authority)"

    companion object {
        const val MAX_WRAPPED_BYTES = TestTerminalWireV1.MAX_PROVIDER_WRAPPED_BYTES
        const val MAX_CONTEXT_BYTES = TestTerminalWireV1.MAX_CONTEXT_BYTES
        const val CONTEXT_KEY = TestTerminalWireV1.CONTEXT_KEY
        const val CONTEXT_DOMAIN = TestTerminalWireV1.KMS_DOMAIN
        private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    }
}
