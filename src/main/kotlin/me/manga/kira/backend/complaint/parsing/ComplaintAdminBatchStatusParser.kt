package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTarget
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintStateMachine
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectAdminStatus
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Closed root/target schemas, bounded before materialization. Missing tags differ from malformed JSON/schema. */
internal object ComplaintAdminBatchStatusParser {
    const val MAX_BODY_BYTES = 32 * 1024
    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxNameLength(16).maxStringLength(MAX_BODY_BYTES).maxNumberLength(20).build())
        .build()

    @Suppress("SwallowedException")
    fun parse(bytes: ByteArray, scope: ComplaintDataScope, key: String): ComplaintAdminBatchStatusInput {
        if (bytes.size > MAX_BODY_BYTES) rejectAdminStatus(ComplaintAdminStatusFailure.TOO_LARGE)
        return try {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            try {
                check(decoded.hasArray())
                factory.createParser(decoded.array(), decoded.arrayOffset() + decoded.position(), decoded.remaining()).use { json ->
                    read(json, scope, key)
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

    private fun read(json: JsonParser, scope: ComplaintDataScope, key: String): ComplaintAdminBatchStatusInput {
        if (json.nextToken() != JsonToken.START_OBJECT) invalid()
        var action: String? = null
        var status: String? = null
        var targets: List<RawTarget>? = null
        while (json.nextToken() != JsonToken.END_OBJECT) {
            if (json.currentToken() != JsonToken.FIELD_NAME) invalid()
            val name = json.currentName()
            val token = json.nextToken()
            when (name) {
                "action" -> { if (token != JsonToken.VALUE_STRING) invalid(); action = json.text }
                "status" -> { if (token != JsonToken.VALUE_STRING) invalid(); status = json.text }
                "targets" -> { if (token != JsonToken.START_ARRAY) invalid(); targets = readTargets(json) }
                else -> invalid()
            }
        }
        if (json.nextToken() != null || action != "STATUS" || status == null || targets == null) invalid()
        val operationKey = ComplaintIdentifiers.idempotencyKey(key)
        val requestedStatus = ComplaintStatus.valueOf(status)
        if (!ComplaintStateMachine.isStatusTarget(requestedStatus)) invalid()
        // Validate the entire JSON schema and IDs before the per-target precondition classification.
        val ids = targets.map { ComplaintIdentifiers.resourceId(it.id) }
        if (ids.distinct().size != ids.size) invalid()
        if (targets.any { it.tag == null }) rejectAdminStatus(ComplaintAdminStatusFailure.PRECONDITION_REQUIRED)
        return ComplaintAdminBatchStatusInput(scope, operationKey, requestedStatus, targets.mapIndexed { index, raw ->
            ComplaintAdminBatchStatusTarget(ids[index], ComplaintAdminStatusPrecondition.parse(ids[index], raw.tag))
        })
    }

    private fun readTargets(json: JsonParser): List<RawTarget> {
        val targets = ArrayList<RawTarget>()
        while (json.nextToken() != JsonToken.END_ARRAY) {
            if (json.currentToken() != JsonToken.START_OBJECT || targets.size == ComplaintAdminBatchStatusInput.MAX_TARGETS) invalid()
            var id: String? = null
            var tag: String? = null
            while (json.nextToken() != JsonToken.END_OBJECT) {
                if (json.currentToken() != JsonToken.FIELD_NAME) invalid()
                val name = json.currentName()
                if (json.nextToken() != JsonToken.VALUE_STRING) invalid() // null/non-string is schema400, not missing428.
                when (name) {
                    "id" -> id = json.text
                    "actionTag" -> tag = json.text
                    else -> invalid()
                }
            }
            targets.add(RawTarget(id ?: invalid(), tag))
        }
        if (targets.isEmpty()) invalid()
        return targets
    }

    private class RawTarget(val id: String, val tag: String?) {
        override fun toString(): String = "ComplaintAdminBatchStatusRawTarget(redacted)"
    }

    private fun invalid(): Nothing = rejectAdminStatus(ComplaintAdminStatusFailure.INVALID_REQUEST)
}
