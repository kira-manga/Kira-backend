package me.manga.kira.backend.complaint.infrastructure.journal

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Closed kcj-1 persisted observations, not an object-evidence or transaction-result issuer. Only
 * [observed] accepts evidence, and only the publisher's private same-routing readback can supply it.
 * Stored bytes are parsed without a JSONB round trip, routing HMAC, envelope open or provider call.
 */
internal class TestOwnerDeleteVerificationCodecV1 private constructor(private val routing: TestOwnerDeleteJournalRoutingV1,
    private val kinds: Set<ComplaintJournalDeletionKindV1>) {
    constructor(routing: TestOwnerDeleteJournalRoutingV1) : this(routing, setOf(ComplaintJournalDeletionKindV1.OWNER_DELETE))
    private val declaration = routing.journalConfiguration.declaration()
    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(1)
                .maxNameLength(32)
                .maxStringLength(1024)
                .maxNumberLength(19)
                .build(),
        ).build()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    fun observed(readback: TestOwnerDeleteJournalReadbackV1): TestOwnerDeleteVerificationRecordV1 {
        requireConnectionFree()
        requireTestDeleteVerification(readback.event.belongsTo(routing))
        val event = readback.event
        // Floor once, before BOTH JSON serialization and the JDBC timestamp binding. No new clock
        // observation or PostgreSQL/driver rounding may replace the genuine publisher observation.
        val verifiedAt = readback.verifiedAt.truncatedTo(ChronoUnit.MICROS)
        val value = TestOwnerDeleteVerificationRecordV1(
            1, event.comparison.eventKind.name, routing.journalConfiguration.scope.id.toString(), true,
            routing.journalConfiguration.sha256, event.route.eventId, declaration.writer.generationId,
            event.comparison.epoch, event.route.routingKeyId, event.route.objectKey, event.semanticSha256,
            readback.versionId, readback.wireSha256, readback.lastModified.toString(), "COMPLIANCE",
            readback.retainUntil.toString(), verifiedAt.toString(),
        )
        requireBound(value, event)
        return value
    }

    /** Serialization of comparison data is deliberately not publisher or committed-VERIFIED authority. */
    fun canonicalBytes(value: TestOwnerDeleteVerificationRecordV1): ByteArray = testDeleteVerificationValue {
        validate(value)
        CanonicalJson.canonicalize(TestOwnerDeleteVerificationRecordV1.serializer(), value).toByteArray(Charsets.UTF_8).also {
            requireTestDeleteVerification(it.size in 1..MAXIMUM_BYTES)
        }
    }

    /** The SQL reader separately checks the stored SHA-256 with complaint_bytes_match on this bytea. */
    fun parse(bytes: ByteArray, event: TestOwnerDeleteJournalEventV1): TestOwnerDeleteVerificationRecordV1 = testDeleteVerificationValue {
        requireTestDeleteVerification(bytes.size in 1..MAXIMUM_BYTES)
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        val text = try {
            decoded.toString()
        } finally {
            if (decoded.hasArray()) decoded.array().fill('\u0000')
        }
        structure(text)
        val value = json.decodeFromString(TestOwnerDeleteVerificationRecordV1.serializer(), text)
        requireBound(value, event)
        val canonical = canonicalBytes(value)
        try {
            requireTestDeleteVerification(canonical.contentEquals(bytes))
        } finally {
            canonical.fill(0)
        }
        value
    }

    private fun requireBound(value: TestOwnerDeleteVerificationRecordV1, event: TestOwnerDeleteJournalEventV1) {
        validate(value)
        val tuple = event.comparison
        requireTestDeleteVerification(
            event.belongsTo(routing) && tuple.scope == routing.journalConfiguration.scope &&
                tuple.eventKind in kinds && value.eventKind == tuple.eventKind.name,
        )
        requireTestDeleteVerification(
            value.journalConfigurationSha256 == routing.journalConfiguration.sha256 && value.writerGeneration == declaration.writer.generationId &&
                value.eventId == event.route.eventId && value.journalEpoch == tuple.epoch && value.routingKeyId == event.route.routingKeyId &&
                value.objectKey == event.route.objectKey && value.semanticSha256 == event.semanticSha256,
        )
    }

    private fun validate(value: TestOwnerDeleteVerificationRecordV1) = testDeleteVerificationValue {
        requireTestDeleteVerification(value.schema == 1 && kinds.any { it.name == value.eventKind })
        requireTestDeleteVerification(value.dataScopeId == routing.journalConfiguration.scope.id.toString() && value.testOnly && value.objectLockMode == "COMPLIANCE")
        requireTestDeleteVerification(value.journalEpoch > 0 && OfflineBootstrapGrammar.uuidV4(value.writerGeneration))
        requireTestDeleteVerification(
            listOf(value.journalConfigurationSha256, value.semanticSha256, value.ciphertextSha256).all(OfflineBootstrapGrammar::sha256) &&
                EVENT_ID.matches(value.eventId) && OfflineBootstrapGrammar.referenceId(value.routingKeyId),
        )
        requireTestDeleteVerification(value.objectKey.length in 1..1024 && value.objectKey.all { it in ' '..'~' })
        requireTestDeleteVerification(
            value.objectVersion.length in 1..1024 && value.objectVersion != "null" && value.objectVersion.none { it < ' ' || it == '\u007f' },
        )
        utf8Bound(value.objectVersion, 1024)
        val created = instant(value.objectCreatedAt, wholeSecond = true)
        val retained = instant(value.retainUntil, wholeSecond = true)
        val verified = instant(value.verifiedAt, wholeSecond = false)
        requireTestDeleteVerification(
            !created.isAfter(verified) && retained.isAfter(verified) &&
                retained.epochSecond - created.epochSecond >= declaration.limits.retention.ordinaryRetentionSeconds,
        ) // Necessary J-relative checks only; no stronger restore-horizon/current runtime authority.
    }

    private fun instant(value: String, wholeSecond: Boolean): Instant {
        requireTestDeleteVerification(value.length in 20..(if (wholeSecond) 20 else 27))
        val parsed = Instant.parse(value)
        requireTestDeleteVerification(
            parsed.epochSecond in 0..LAST_EPOCH_SECOND && parsed.toString() == value &&
                (if (wholeSecond) parsed.nano == 0 else parsed.nano % 1000 == 0),
        )
        return parsed
    }

    private fun structure(text: String) {
        factory.createParser(text).use { parser ->
            requireTestDeleteVerification(parser.nextToken() == JsonToken.START_OBJECT)
            val seen = HashSet<String>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireTestDeleteVerification(parser.currentToken() == JsonToken.FIELD_NAME && seen.size < FIELDS.size)
                val name = parser.text
                requireTestDeleteVerification(name in FIELDS && seen.add(name))
                val token = parser.nextToken()
                when (name) {
                    "schema", "journalEpoch" -> {
                        requireTestDeleteVerification(token == JsonToken.VALUE_NUMBER_INT)
                        requireTestDeleteVerification(DECIMAL.matches(parser.text) && parser.text.toLongOrNull() != null)
                    }

                    "testOnly" -> requireTestDeleteVerification(token == JsonToken.VALUE_FALSE || token == JsonToken.VALUE_TRUE)

                    else -> {
                        requireTestDeleteVerification(token == JsonToken.VALUE_STRING)
                        utf8Bound(parser.text, if (name == "eventKind" && ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE in kinds) 18 else STRING_BYTES.getValue(name))
                    }
                }
            }
            requireTestDeleteVerification(seen == FIELDS && parser.nextToken() == null)
        }
    }

    private fun utf8Bound(value: String, maximum: Int) {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        try {
            requireTestDeleteVerification(encoded.remaining() in 1..maximum)
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    override fun toString(): String = "TestOwnerDeleteVerificationCodecV1(redacted,comparison-only)"

    companion object {
        fun forAdmin(routing: TestOwnerDeleteJournalRoutingV1): TestOwnerDeleteVerificationCodecV1 {
            require(routing.journalConfiguration.adminDelete)
            return TestOwnerDeleteVerificationCodecV1(routing, setOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE))
        }
        fun forAdminBatch(routing: TestOwnerDeleteJournalRoutingV1): TestOwnerDeleteVerificationCodecV1 {
            require(routing.journalConfiguration.adminBatchDelete)
            return TestOwnerDeleteVerificationCodecV1(routing, setOf(ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE))
        }
        fun forAdminErasure(routing: TestOwnerDeleteJournalRoutingV1): TestOwnerDeleteVerificationCodecV1 {
            require(routing.journalConfiguration.adminDelete)
            return TestOwnerDeleteVerificationCodecV1(routing, if (routing.journalConfiguration.adminBatchDelete)
                setOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE)
                else setOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE))
        }
        const val MAXIMUM_BYTES = 65_536
        private const val LAST_EPOCH_SECOND = 253_402_300_799L
        private val DECIMAL = Regex("0|[1-9][0-9]{0,18}")
        private val EVENT_ID = Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
        private val STRING_BYTES = mapOf(
            "eventKind" to 16, "dataScopeId" to 36, "journalConfigurationSha256" to 64,
            "eventId" to 43, "writerGeneration" to 36, "routingKeyId" to 128, "objectKey" to 1024,
            "semanticSha256" to 64, "objectVersion" to 1024, "ciphertextSha256" to 64,
            "objectCreatedAt" to 20, "objectLockMode" to 10, "retainUntil" to 20, "verifiedAt" to 27,
        )
        private val FIELDS = STRING_BYTES.keys + setOf("schema", "journalEpoch", "testOnly")
    }
}

/** No defaults: every one of the 17 required fields survives global kcj-1 default omission. */
@Serializable
internal data class TestOwnerDeleteVerificationRecordV1(
    val schema: Int,
    val eventKind: String,
    val dataScopeId: String,
    val testOnly: Boolean,
    val journalConfigurationSha256: String,
    val eventId: String,
    val writerGeneration: String,
    val journalEpoch: Long,
    val routingKeyId: String,
    val objectKey: String,
    val semanticSha256: String,
    val objectVersion: String,
    val ciphertextSha256: String,
    val objectCreatedAt: String,
    val objectLockMode: String,
    val retainUntil: String,
    val verifiedAt: String,
) {
    override fun toString(): String = "TestOwnerDeleteVerificationRecordV1(redacted,no-authority)"
}

internal class TestOwnerDeleteVerificationExceptionV1 : RuntimeException("Invalid complaint journal verification.", null, false, false)

private fun requireTestDeleteVerification(condition: Boolean) {
    if (!condition) throw TestOwnerDeleteVerificationExceptionV1()
}

@Suppress("TooGenericExceptionCaught")
private fun <T> testDeleteVerificationValue(action: () -> T): T = try {
    action()
} catch (failure: TestOwnerDeleteVerificationExceptionV1) {
    throw failure
} catch (_: Exception) {
    throw TestOwnerDeleteVerificationExceptionV1() // No parser's row text/cause/suppressed graph escapes.
}
