package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentRejection
import java.io.IOException

/** Minimal closed acknowledgements share the existing aggregate-eight read/response owner. */
internal class ComplaintAdminContentResponses(private val owner: ComplaintOwnerHistoryResponses) {
    private val factory = JsonFactory()

    fun acquire(): ComplaintOwnerHistoryResponses.Permit? = owner.acquire()
    fun failClosed() = owner.failClosed()
    fun isOpen(): Boolean = owner.isOpen()

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    fun encode(permit: ComplaintOwnerHistoryResponses.Permit, receipt: ComplaintAdminContentReceipt): ComplaintHistoryEncodedBody {
        owner.requirePermit(permit)
        val buffer = ComplaintHistoryEncodedBody(32 * 1024)
        try {
            when (receipt) {
                is ComplaintAdminContentReceipt.Applied -> factory.createGenerator(buffer).use { json ->
                    json.writeStartObject()
                    json.writeStringField("id", receipt.id.toString())
                    json.writeNumberField("version", receipt.version) // Long end to end; never a Double or numeric string.
                    json.writeEndObject()
                }
                is ComplaintAdminContentReceipt.Rejected -> buffer.write(checkNotNull(REJECTIONS[receipt.code]))
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

    override fun toString(): String = "ComplaintAdminContentResponses(bounded,shared-owner)"

    private companion object {
        val REJECTIONS = ComplaintAdminContentRejection.entries.associateWith { rejection ->
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
