package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintOwnership
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64

/** Closed byte-to-query parser, not HTTP framing, ADMIN authentication or TEST-run authority. */
internal object ComplaintAdminSearchParser {
    const val MAX_BODY_BYTES = 32 * 1024

    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(1)
                .maxNameLength(32)
                .maxStringLength(MAX_BODY_BYTES)
                .maxNumberLength(2)
                .build(),
        )
        .build()
    private val date = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,6}))?Z")
    private val pageLimit = Regex("(?:[1-9]|[1-4][0-9]|50)")
    private val cursor = Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    /** The HTTP owner must also enforce the streamed cap before allocating the caller-owned bytes. */
    @Suppress("SwallowedException") // No JSON, Unicode, identifier or date input/cause crosses this boundary.
    fun parse(body: ByteArray): ComplaintAdminSearchQuery {
        if (body.size > MAX_BODY_BYTES) rejectAdminRead(ComplaintAdminReadFailure.TOO_LARGE)
        return try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
            factory.createParser(text).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) invalid()
                val fields = Fields()
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) invalid()
                    val name = parser.currentName()
                    if (parser.nextToken() == null) invalid()
                    fields.read(name, parser)
                }
                if (parser.nextToken() != null) invalid()
                fields.query()
            }
        } catch (ex: IOException) {
            invalid()
        } catch (ex: ComplaintValidationException) {
            invalid()
        } catch (ex: IllegalArgumentException) {
            invalid()
        } catch (ex: DateTimeException) {
            invalid()
        }
    }

    private class Fields {
        private var scope: ComplaintDataScope? = null
        private var text = ""
        private var status: ComplaintStatus? = null
        private var type: ComplaintType? = null
        private var ownership: ComplaintOwnership? = null
        private var updatedFrom: Instant? = null
        private var updatedBefore: Instant? = null
        private var sort = "UPDATED_DESC"
        private var limit = 50
        private var cursor: String? = null

        fun read(name: String, parser: JsonParser) {
            when (name) {
                "dataScopeId" -> scope = ComplaintIdentifiers.dataScope(string(parser))
                "text" -> text = string(parser)
                "status" -> status = nullableString(parser)?.let(::statusValue)
                "type" -> type = nullableString(parser)?.let(::typeValue)
                "ownership" -> ownership = nullableString(parser)?.let(::ownershipValue)
                "updatedFrom" -> updatedFrom = nullableString(parser)?.let(::timestamp)
                "updatedBefore" -> updatedBefore = nullableString(parser)?.let(::timestamp)
                "sort" -> sort = string(parser)
                "limit" -> limit = limitValue(parser)
                "cursor" -> cursor = nullableString(parser)?.also(::cursorSyntax)
                else -> invalid()
            }
        }

        fun query(): ComplaintAdminSearchQuery = ComplaintAdminSearchQuery(
            scope = scope ?: invalid(),
            text = text,
            status = status,
            type = type,
            ownership = ownership,
            updatedFrom = updatedFrom,
            updatedBefore = updatedBefore,
            sort = sort,
            limit = limit,
            cursor = cursor,
        )
    }

    private fun string(parser: JsonParser): String {
        if (parser.currentToken() != JsonToken.VALUE_STRING) invalid()
        return parser.text
    }

    private fun nullableString(parser: JsonParser): String? = if (parser.currentToken() == JsonToken.VALUE_NULL) null else string(parser)

    private fun limitValue(parser: JsonParser): Int {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT || !pageLimit.matches(parser.text)) invalid()
        return parser.text.toInt()
    }

    private fun statusValue(value: String): ComplaintStatus = when (value) {
        "OPEN" -> ComplaintStatus.OPEN
        "IN_PROGRESS" -> ComplaintStatus.IN_PROGRESS
        "RESOLVED" -> ComplaintStatus.RESOLVED
        "CLOSED" -> ComplaintStatus.CLOSED
        "PLANNED" -> ComplaintStatus.PLANNED
        "PINNED" -> ComplaintStatus.PINNED
        "NOT_PLANNED" -> ComplaintStatus.NOT_PLANNED
        else -> invalid()
    }

    private fun typeValue(value: String): ComplaintType = when (value) {
        "TECHNICAL" -> ComplaintType.TECHNICAL
        "LANGUAGES" -> ComplaintType.LANGUAGES
        "SITES_ADD" -> ComplaintType.SITES_ADD
        "SITE_ERROR" -> ComplaintType.SITE_ERROR
        "FEATURES" -> ComplaintType.FEATURES
        "CUSTOM" -> ComplaintType.CUSTOM
        else -> invalid()
    }

    private fun ownershipValue(value: String): ComplaintOwnership = when (value) {
        "INSTALLATION" -> ComplaintOwnership.INSTALLATION
        "SYSTEM" -> ComplaintOwnership.SYSTEM
        else -> invalid()
    }

    private fun timestamp(value: String): Instant {
        val parts = date.matchEntire(value)?.groupValues ?: invalid()
        val year = parts[1].toInt()
        if (year == 0) invalid()
        return LocalDateTime.of(
            year,
            parts[2].toInt(),
            parts[3].toInt(),
            parts[4].toInt(),
            parts[5].toInt(),
            parts[6].toInt(),
            parts[7].padEnd(9, '0').toInt(),
        ).toInstant(ZoneOffset.UTC)
    }

    /** Only canonical envelope syntax here; the dedicated codec authenticates selection, actor and expiry. */
    @Suppress("SwallowedException")
    private fun cursorSyntax(value: String) {
        if (value.length > 2048 || !cursor.matches(value)) rejectAdminRead(ComplaintAdminReadFailure.INVALID_CURSOR)
        try {
            val parts = value.split('.')
            val payload = Base64.getUrlDecoder().decode(parts[1])
            val signature = Base64.getUrlDecoder().decode(parts[2])
            val encoder = Base64.getUrlEncoder().withoutPadding()
            if (payload.size !in 1..512 || signature.size != 32 ||
                encoder.encodeToString(payload) != parts[1] || encoder.encodeToString(signature) != parts[2]
            ) rejectAdminRead(ComplaintAdminReadFailure.INVALID_CURSOR)
        } catch (ex: IllegalArgumentException) {
            rejectAdminRead(ComplaintAdminReadFailure.INVALID_CURSOR)
        }
    }

    private fun invalid(): Nothing = rejectAdminRead(ComplaintAdminReadFailure.INVALID_REQUEST)
}
