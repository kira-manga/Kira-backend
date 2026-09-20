package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejection
import java.io.IOException

/** Minimal closed acknowledgements share the existing aggregate-eight read/response owner. */
internal class ComplaintAdminStatusResponses(private val owner: ComplaintOwnerHistoryResponses) {
    private val factory = JsonFactory()

    fun acquire(): ComplaintOwnerHistoryResponses.Permit? = owner.acquire()
    fun failClosed() = owner.failClosed()
    fun isOpen(): Boolean = owner.isOpen()

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    fun encode(permit: ComplaintOwnerHistoryResponses.Permit, receipt: ComplaintAdminStatusReceipt): ComplaintHistoryEncodedBody {
        owner.requirePermit(permit)
        val buffer = ComplaintHistoryEncodedBody(32 * 1024)
        try {
            when (receipt) {
                is ComplaintAdminStatusReceipt.Applied -> factory.createGenerator(buffer).use { json ->
                    json.writeStartObject()
                    json.writeStringField("id", receipt.id.toString())
                    json.writeNumberField("version", receipt.version) // Long end to end; never a Double or numeric string.
                    json.writeEndObject()
                }
                is ComplaintAdminStatusReceipt.Rejected -> buffer.write(checkNotNull(REJECTIONS[receipt.code]))
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

    override fun toString(): String = "ComplaintAdminStatusResponses(bounded,shared-owner)"

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
