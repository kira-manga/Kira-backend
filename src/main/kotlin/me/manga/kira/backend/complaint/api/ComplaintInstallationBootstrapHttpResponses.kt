package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrap
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapFailure
import me.manga.kira.backend.complaint.domain.rejectInstallationBootstrap
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Exactly two scalar fields, using the existing bounded, zeroing response owner. */
internal class ComplaintInstallationBootstrapHttpResponses {
    private val closed = AtomicBoolean()
    private val factory = JsonFactory()

    fun isOpen(): Boolean = !closed.get()

    fun failClosed() { closed.set(true) }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun encode(bootstrap: ComplaintInstallationBootstrap): ComplaintHistoryEncodedBody {
        if (!isOpen()) rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.UNAVAILABLE)
        val body = ComplaintHistoryEncodedBody(MAX_BYTES)
        try {
            factory.createGenerator(body).use { json ->
                json.writeStartObject()
                json.writeStringField("dataScopeId", bootstrap.scope.id.toString())
                json.writeNumberField("contractVersion", ComplaintInstallationBootstrap.CONTRACT_VERSION)
                json.writeEndObject()
            }
            return body
        } catch (failure: IOException) {
            body.destroy()
            failClosed()
            rejectInstallationBootstrap(ComplaintInstallationBootstrapFailure.UNAVAILABLE)
        } catch (failure: Throwable) {
            body.destroy()
            throw failure
        }
    }

    override fun toString(): String = "ComplaintInstallationBootstrapHttpResponses(two-fields,bounded)"

    companion object {
        const val MAX_BYTES = 16 * 1024

        val PROBLEMS: Map<ComplaintInstallationBootstrapFailure, ByteArray> = ComplaintInstallationBootstrapFailure.entries.associateWith { failure ->
            (
                """{"type":"about:blank","title":"${failure.title}","status":${failure.status},"errors":[""" +
                    """{"code":"${failure.code}","message":"Installation bootstrap refused."}]}"""
                ).toByteArray(Charsets.UTF_8).also { check(it.size <= MAX_BYTES) }
        }
    }
}
