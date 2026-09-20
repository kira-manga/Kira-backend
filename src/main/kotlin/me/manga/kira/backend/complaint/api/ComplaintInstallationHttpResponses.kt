package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import me.manga.kira.backend.complaint.domain.ComplaintInstallationHttpFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSessionResponse
import me.manga.kira.backend.complaint.domain.rejectInstallationHttp
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Small-response owner; reuses the accepted counting buffer, not the large-list semaphore or a new writer framework. */
internal class ComplaintInstallationHttpResponses {
    private val closed = AtomicBoolean()
    private val factory = JsonFactory()

    fun isOpen(): Boolean = !closed.get()

    fun failClosed() {
        closed.set(true)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun encode(session: ComplaintInstallationSessionResponse): ComplaintHistoryEncodedBody {
        check(isOpen()) { "Installation responses are closed." }
        val body = ComplaintHistoryEncodedBody(MAX_BYTES)
        try {
            factory.createGenerator(body).use { json ->
                json.writeStartObject()
                json.writeStringField("installationId", session.installation.id.toString())
                json.writeStringField("accessToken", session.accessToken)
                json.writeStringField("tokenType", "Bearer")
                json.writeNumberField("credentialVersion", session.credentialVersion)
                json.writeStringField("dataScopeId", session.installation.scope.id.toString())
                json.writeStringField("issuedAt", session.issuedAt.toString())
                json.writeNumberField("expiresInSeconds", ComplaintInstallationSessionResponse.EXPIRES_IN_SECONDS)
                json.writeEndObject()
            }
            return body
        } catch (failure: IOException) {
            // Encoding has not touched the servlet response; unlike failed delivery, this is a bounded problem.
            body.destroy()
            rejectInstallationHttp(ComplaintInstallationHttpFailure.INTERNAL)
        } catch (failure: Throwable) {
            body.destroy()
            throw failure
        }
    }

    override fun toString(): String = "ComplaintInstallationHttpResponses(bounded)"

    companion object {
        const val MAX_BYTES = 16 * 1024

        /** Fixed ApiError/NON_EMPTY shape; never serialize a domain exception or interpolate input. */
        val PROBLEMS: Map<ComplaintInstallationHttpFailure, ByteArray> = ComplaintInstallationHttpFailure.entries.associateWith { failure ->
            (
                """{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                    """{"code":"${failure.code}","message":"Installation request refused."}]}"""
                ).toByteArray(Charsets.UTF_8).also {
                check(it.size <= MAX_BYTES)
            }
        }
    }
}
