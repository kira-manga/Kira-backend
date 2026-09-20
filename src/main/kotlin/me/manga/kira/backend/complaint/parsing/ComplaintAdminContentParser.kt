package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminContent
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Strict bounded shape only: no role/row lookup and no pre-authentication prose normalization. */
internal object ComplaintAdminContentParser {
    const val MAX_BODY_BYTES = 16_384
    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(1).maxNameLength(16).maxStringLength(MAX_BODY_BYTES).maxNumberLength(20).build())
        .build()

    @Suppress("SwallowedException")
    fun parse(
        bytes: ByteArray,
        scope: ComplaintDataScope,
        target: UUID,
        key: String,
        precondition: ComplaintAdminContentPrecondition,
    ): ComplaintAdminContentInput {
        if (bytes.size > MAX_BODY_BYTES) rejectAdminContent(ComplaintAdminContentFailure.TOO_LARGE)
        return try {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            try {
                check(decoded.hasArray())
                factory.createParser(decoded.array(), decoded.arrayOffset() + decoded.position(), decoded.remaining()).use { json ->
                    if (json.nextToken() != JsonToken.START_OBJECT) invalid()
                    var type: ComplaintType? = null
                    var subject: String? = null
                    var body: String? = null
                    var fields = 0
                    while (json.nextToken() != JsonToken.END_OBJECT) {
                        if (json.currentToken() != JsonToken.FIELD_NAME || ++fields > 3) invalid()
                        val name = json.currentName()
                        if (json.nextToken() != JsonToken.VALUE_STRING) invalid()
                        when (name) {
                            "type" -> type = ComplaintType.valueOf(json.text)
                            "subject" -> subject = json.text
                            "body" -> body = json.text
                            else -> invalid()
                        }
                    }
                    if (json.nextToken() != null || body == null ||
                        !(fields == 1 && type == null && subject == null || fields == 3 && type != null && subject != null)
                    ) invalid()
                    ComplaintAdminContentInput(scope, target, ComplaintIdentifiers.idempotencyKey(key), type, subject, checkNotNull(body), precondition)
                }
            } finally {
                if (decoded.hasArray()) decoded.array().fill('\u0000')
            }
        } catch (failure: IOException) {
            invalid()
        } catch (failure: ComplaintValidationException) {
            invalid()
        } catch (failure: IllegalArgumentException) {
            invalid()
        }
    }

    private fun invalid(): Nothing = rejectAdminContent(ComplaintAdminContentFailure.INVALID_REQUEST)
}
