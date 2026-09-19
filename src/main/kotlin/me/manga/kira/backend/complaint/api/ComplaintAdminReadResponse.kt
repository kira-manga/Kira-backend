package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import me.manga.kira.backend.complaint.domain.ComplaintAdminItem
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadResult
import java.io.IOException

/** Closed Admin fields, sharing the original aggregate-eight response owner with co-composed owner reads. */
internal class ComplaintAdminReadResponses(private val owner: ComplaintOwnerHistoryResponses) {
    private val factory = JsonFactory()

    fun acquire(): ComplaintOwnerHistoryResponses.Permit? = owner.acquire()
    fun failClosed() = owner.failClosed()
    fun isOpen(): Boolean = owner.isOpen()

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    fun encode(permit: ComplaintOwnerHistoryResponses.Permit, result: ComplaintAdminReadResult): ComplaintHistoryEncodedBody {
        owner.requirePermit(permit)
        val maximum = if (result is ComplaintAdminReadResult.Page) PAGE_MAX_BYTES else ITEM_MAX_BYTES
        val buffer = ComplaintHistoryEncodedBody(maximum)
        try {
            factory.createGenerator(buffer).use { json ->
                when (result) {
                    is ComplaintAdminReadResult.Detail -> item(json, result.item)
                    is ComplaintAdminReadResult.Page -> {
                        json.writeStartObject()
                        json.writeArrayFieldStart("items")
                        result.items.forEachIndexed { index, row ->
                            json.flush()
                            val before = buffer.length
                            item(json, row)
                            json.flush()
                            if (buffer.length - before - (if (index == 0) 0 else 1) > ITEM_MAX_BYTES) throw ComplaintHistorySerializationFailure()
                        }
                        json.writeEndArray()
                        nullable(json, "nextCursor", result.nextCursor)
                        json.writeEndObject()
                    }
                }
            }
            owner.requirePermit(permit)
            return buffer
        } catch (failure: IOException) {
            buffer.destroy()
            throw ComplaintHistorySerializationFailure()
        } catch (failure: RuntimeException) {
            buffer.destroy()
            throw ComplaintHistorySerializationFailure()
        } catch (failure: Error) {
            buffer.destroy()
            throw failure
        }
    }

    private fun item(json: JsonGenerator, item: ComplaintAdminItem) {
        when (item) {
            is ComplaintAdminItem.Content -> content(json, item)
            is ComplaintAdminItem.Notice -> notice(json, item)
        }
    }

    private fun notice(json: JsonGenerator, item: ComplaintAdminItem.Notice) {
        val row = item.notice
        json.writeStartObject()
        json.writeStringField("id", row.id.toString())
        json.writeStringField("kind", "NOTICE")
        json.writeStringField("status", "PINNED")
        json.writeStringField("createdAt", row.createdAt.toString())
        json.writeStringField("updatedAt", row.updatedAt.toString())
        json.writeNumberField("version", row.version)
        json.writeStringField("ownership", "SYSTEM")
        json.writeNullField("ownerReference")
        json.writeStringField("noticeKey", row.noticeKey)
        json.writeEndObject()
    }

    private fun content(json: JsonGenerator, item: ComplaintAdminItem.Content) {
        val row = item.content
        json.writeStartObject()
        json.writeStringField("id", row.id.toString())
        json.writeStringField("kind", row.kind.name)
        json.writeStringField("status", row.status.name)
        json.writeStringField("createdAt", row.createdAt.toString())
        json.writeStringField("updatedAt", row.updatedAt.toString())
        json.writeNumberField("version", row.version)
        json.writeStringField("ownership", "INSTALLATION")
        json.writeStringField("ownerReference", item.ownerReference.toString())
        json.writeStringField("type", row.type.name)
        nullable(json, "subject", row.subject)
        json.writeStringField("body", row.body)
        json.writeStringField("actionTag", ComplaintOwnerHistoryResponses.actionTag(row))
        nullable(json, "appVersion", row.appVersion)
        json.writeStringField("platform", row.platform.name)
        json.writeStringField("osVersion", row.osVersion)
        json.writeStringField("manufacturer", row.manufacturer)
        json.writeStringField("deviceModel", row.deviceModel)
        nullable(json, "closureReason", row.closureReason)
        nullable(json, "replyToId", row.replyToId?.toString())
        nullable(json, "closedAt", item.closedAt?.toString())
        nullable(json, "closureProvenance", item.closureProvenance)
        nullable(json, "closureActorId", item.closureActorId?.toString())
        row.noticeKey?.let { json.writeStringField("noticeKey", it) }
        json.writeEndObject()
    }

    private fun nullable(json: JsonGenerator, name: String, value: String?) {
        if (value == null) json.writeNullField(name) else json.writeStringField(name, value)
    }

    override fun toString(): String = "ComplaintAdminReadResponses(bounded,shared-owner)"

    private companion object {
        const val ITEM_MAX_BYTES = 32 * 1024
        const val PAGE_MAX_BYTES = 2 * 1024 * 1024
    }
}
