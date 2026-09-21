package me.manga.kira.backend.complaint.api

import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejection
import java.io.IOException

/** Minimal closed acknowledgements share the existing aggregate-eight read/response owner. */
internal class ComplaintAdminDeleteResponses(private val owner: ComplaintOwnerHistoryResponses) {
    fun acquire(): ComplaintOwnerHistoryResponses.Permit? = owner.acquire()
    fun failClosed() = owner.failClosed()
    fun isOpen(): Boolean = owner.isOpen()

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    fun encode(permit: ComplaintOwnerHistoryResponses.Permit, receipt: ComplaintAdminDeleteReceipt): ComplaintHistoryEncodedBody {
        owner.requirePermit(permit)
        val buffer = ComplaintHistoryEncodedBody(32 * 1024)
        try {
            when (receipt) {
                is ComplaintAdminDeleteReceipt.Applied -> Unit // No body writer, JSON or invented acknowledgement.
                is ComplaintAdminDeleteReceipt.BatchApplied -> error("Batch receipt cannot acknowledge scalar DELETE")
                is ComplaintAdminDeleteReceipt.Rejected -> buffer.write(checkNotNull(REJECTIONS[receipt.code]))
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

    override fun toString(): String = "ComplaintAdminDeleteResponses(bounded,shared-owner)"

    private companion object {
        val REJECTIONS = ComplaintAdminDeleteRejection.entries.associateWith { rejection ->
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
