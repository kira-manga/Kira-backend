package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMe
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeFailure
import me.manga.kira.backend.complaint.domain.rejectInstallationMe
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A closed three-scalar shape in the existing counting/zeroing buffer; no new serialization framework. */
internal class ComplaintInstallationMeHttpResponses {
    private val closed = AtomicBoolean()
    private val factory = JsonFactory()

    fun isOpen(): Boolean = !closed.get()

    fun failClosed() {
        closed.set(true)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun encode(installation: ComplaintInstallationMe): ComplaintHistoryEncodedBody {
        check(isOpen()) { "Installation responses are closed." }
        val body = ComplaintHistoryEncodedBody(MAX_BYTES)
        try {
            factory.createGenerator(body).use { json ->
                json.writeStartObject()
                json.writeStringField("installationId", installation.installation.id.toString())
                json.writeNumberField("credentialVersion", installation.credentialVersion)
                json.writeStringField("dataScopeId", installation.installation.scope.id.toString())
                json.writeEndObject()
            }
            return body
        } catch (failure: IOException) {
            body.destroy()
            rejectInstallationMe(ComplaintInstallationMeFailure.INTERNAL)
        } catch (failure: Throwable) {
            body.destroy()
            throw failure
        }
    }

    override fun toString(): String = "ComplaintInstallationMeHttpResponses(bounded)"

    companion object {
        const val MAX_BYTES = 16 * 1024

        /** Fixed ApiError shape; no request or exception is ever interpolated. */
        val PROBLEMS: Map<ComplaintInstallationMeFailure, ByteArray> = ComplaintInstallationMeFailure.entries.associateWith { failure ->
            (
                """{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                    """{"code":"${failure.code}","message":"Installation read refused."}]}"""
                ).toByteArray(Charsets.UTF_8).also { check(it.size <= MAX_BYTES) }
        }
    }
}
