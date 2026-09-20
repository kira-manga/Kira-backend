package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Strict, bounded single-field shapes only. Closure text normalization waits for current ADMIN authentication. */
internal object ComplaintAdminStatusParser {
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
        precondition: ComplaintAdminStatusPrecondition,
        operation: ComplaintAdminStatusOperation,
    ): ComplaintAdminStatusInput {
        if (bytes.size > MAX_BODY_BYTES) rejectAdminStatus(ComplaintAdminStatusFailure.TOO_LARGE)
        return try {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            try {
                check(decoded.hasArray())
                factory.createParser(decoded.array(), decoded.arrayOffset() + decoded.position(), decoded.remaining()).use { json ->
                    if (json.nextToken() != JsonToken.START_OBJECT || json.nextToken() != JsonToken.FIELD_NAME) invalid()
                    val expected = when (operation) {
                        ComplaintAdminStatusOperation.ADMIN_STATUS -> "status"
                        ComplaintAdminStatusOperation.ADMIN_CLOSURE -> "reason"
                    }
                    if (json.currentName() != expected || json.nextToken() != JsonToken.VALUE_STRING) invalid()
                    val value = json.text
                    if (json.nextToken() != JsonToken.END_OBJECT || json.nextToken() != null) invalid()
                    val operationKey = ComplaintIdentifiers.idempotencyKey(key)
                    when (operation) {
                        ComplaintAdminStatusOperation.ADMIN_STATUS -> ComplaintAdminStatusInput.Transition(
                            scope, target, operationKey, ComplaintStatus.valueOf(value), precondition,
                        )
                        ComplaintAdminStatusOperation.ADMIN_CLOSURE -> ComplaintAdminStatusInput.Closure(
                            scope, target, operationKey, value, precondition,
                        )
                    }
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

    private fun invalid(): Nothing = rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
}
