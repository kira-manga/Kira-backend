package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateRejection
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReplyRejection
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** One finite response owner for BOTH handlers; exact closed scalars, no content snapshots or deferred MVC work. */
internal class ComplaintOwnerOperationResponse {
    private val slots = Semaphore(8)
    private val closed = AtomicBoolean()
    private val factory = JsonFactory()

    fun acquire(): ComplaintOwnerHistoryResponses.Permit? {
        val permit = ComplaintOwnerHistoryResponses.Permit(slots)
        if (closed.get() || !permit.acquire()) return null
        if (!closed.get()) return permit
        permit.close()
        return null
    }

    fun isOpen(): Boolean = !closed.get()
    fun failClosed() = closed.set(true)

    @Suppress("TooGenericExceptionCaught")
    fun encode(permit: ComplaintOwnerHistoryResponses.Permit, receipt: ComplaintOwnerReceipt, statusLookup: Boolean): ComplaintHistoryEncodedBody {
        check(permit.belongsTo(slots) && isOpen()) { "Complaint response refused." }
        val maximum = if (!statusLookup && receipt is ComplaintOwnerReceipt.Applied) 32 * 1024 else 16 * 1024
        val buffer = ComplaintHistoryEncodedBody(maximum)
        try {
            if (!statusLookup && receipt is ComplaintOwnerReceipt.Rejected) {
                buffer.write(checkNotNull(REJECTIONS[receipt.problemCode]))
            } else {
                factory.createGenerator(buffer).use { json ->
                    json.writeStartObject()
                    if (statusLookup) writeStatus(json, receipt)
                    if (receipt is ComplaintOwnerReceipt.Applied) {
                        json.writeStringField("id", receipt.id.toString())
                        json.writeNumberField("version", receipt.version)
                    }
                    if (statusLookup && receipt is ComplaintOwnerReceipt.Applied) json.writeEndObject()
                    json.writeEndObject()
                }
            }
            return buffer
        } catch (failure: Throwable) {
            buffer.destroy()
            throw failure
        }
    }

    private fun writeStatus(json: JsonGenerator, receipt: ComplaintOwnerReceipt) {
        when (receipt) {
            is ComplaintOwnerReceipt.Applied -> {
                json.writeStringField("outcome", "APPLIED")
                json.writeNumberField("originalStatus", 201)
                json.writeStringField("location", receipt.location)
                json.writeStringField("etag", receipt.etag)
                json.writeObjectFieldStart("body")
            }

            is ComplaintOwnerReceipt.Rejected -> {
                json.writeStringField("outcome", "REJECTED")
                json.writeNumberField("originalStatus", receipt.status)
                json.writeStringField("problemCode", receipt.problemCode)
            }
        }
    }

    override fun toString(): String = "ComplaintOwnerOperationResponse(bounded)"

    companion object {
        val PROBLEMS = ComplaintOwnerOperationFailure.entries.associateWith { problem(it.status, it.title, it.code) }
        private val REJECTIONS = ComplaintOwnerCreateRejection.entries.associate { it.name to problem(409, "Conflict", it.name) } +
            ComplaintOwnerReplyRejection.entries.associate {
                it.name to problem(it.status, if (it.status == 404) "Not Found" else "Conflict", it.name)
            }

        private fun problem(status: Int, title: String, code: String): ByteArray = (
            """{"type":"about:blank","title":"$title","status":$status,"errors":[""" +
                """{"code":"$code","message":"Complaint request refused."}]}"""
            ).toByteArray(Charsets.UTF_8)
    }
}
