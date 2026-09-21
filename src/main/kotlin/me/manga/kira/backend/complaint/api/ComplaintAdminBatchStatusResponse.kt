package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejection
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import java.io.IOException

/** Minimal closed acknowledgements share the existing aggregate-eight read/response owner. */
internal class ComplaintAdminBatchStatusResponses(private val owner: ComplaintOwnerHistoryResponses) {
    private val factory = JsonFactory()

    fun acquire(): ComplaintOwnerHistoryResponses.Permit? = owner.acquire()
    fun failClosed() = owner.failClosed()
    fun isOpen(): Boolean = owner.isOpen()

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    fun encode(permit: ComplaintOwnerHistoryResponses.Permit, receipt: ComplaintAdminBatchStatusReceipt): ComplaintHistoryEncodedBody {
        owner.requirePermit(permit)
        val buffer = ComplaintHistoryEncodedBody(32 * 1024)
        try {
            when (receipt) {
                is ComplaintAdminBatchStatusReceipt.Applied -> factory.createGenerator(buffer).use { json ->
                    json.writeStartObject()
                    json.writeArrayFieldStart("items")
                    receipt.items.forEach { item ->
                        json.writeStartObject()
                        json.writeStringField("id", item.id.toString())
                        json.writeNumberField("version", item.version) // Long end to end, including values above 2^53.
                        json.writeEndObject()
                    }
                    json.writeEndArray()
                    json.writeEndObject()
                }
                is ComplaintAdminBatchStatusReceipt.Rejected -> buffer.write(checkNotNull(REJECTIONS[receipt.code]))
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

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    fun encodeDelete(permit: ComplaintOwnerHistoryResponses.Permit, receipt: ComplaintAdminDeleteReceipt): ComplaintHistoryEncodedBody {
        owner.requirePermit(permit)
        val buffer = ComplaintHistoryEncodedBody(32 * 1024)
        try {
            when (receipt) {
                is ComplaintAdminDeleteReceipt.BatchApplied -> factory.createGenerator(buffer).use { json ->
                    json.writeStartObject()
                    json.writeArrayFieldStart("items")
                    receipt.ids.forEach { id ->
                        json.writeStartObject()
                        json.writeStringField("id", id.toString())
                        json.writeEndObject()
                    }
                    json.writeEndArray()
                    json.writeEndObject()
                }
                is ComplaintAdminDeleteReceipt.Rejected -> buffer.write(checkNotNull(REJECTIONS[ComplaintAdminStatusRejection.valueOf(receipt.code.name)]))
                is ComplaintAdminDeleteReceipt.Applied -> error("Scalar DELETE receipt cannot acknowledge a batch")
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

    override fun toString(): String = "ComplaintAdminBatchStatusResponses(bounded,shared-owner)"

    private companion object {
        val REJECTIONS = ComplaintAdminStatusRejection.entries.associateWith { rejection ->
            val title = when (rejection.status) {
                404 -> "Not Found"
                412 -> "Precondition Failed"
                else -> "Conflict"
            }
            (
                """{"type":"about:blank","title":"$title","status":${rejection.status},"errors":[""" +
                    """{"code":"${rejection.name}","message":"Complaint request refused."}]}"""
                ).toByteArray(Charsets.UTF_8).also { check(it.size <= 512) }
        }
    }
}
