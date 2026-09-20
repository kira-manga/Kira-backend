package me.manga.kira.backend.security

import jakarta.servlet.AsyncContext
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UNREGISTERED synchronous servlet boundary, outside body buffering AND Spring authentication.
 * The one temporary frame refers to the existing ingress registry; no second admission registry,
 * request attribute capability, ambient-context fallback, pool or transaction is introduced.
 */
internal class ComplaintHttpIngressBridge(private val ingress: ComplaintIngressAdmission) : Filter {
    private val current = ThreadLocal<Frame>()
    private val closed = AtomicBoolean()

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val http = request as HttpServletRequest
        val output = response as HttpServletResponse
        if (current.get() != null) refuse()
        if (!ComplaintInstallationRoutes.matches(http)) {
            chain.doFilter(request, response)
            return
        }
        val tracked = DeliveryResponse(output)
        try {
            ingress.withIngress(http) { context ->
                val frame = Frame(context, root(http), Thread.currentThread(), http.method, http.requestURI, http.contextPath)
                current.set(frame)
                try {
                    respond(http, tracked) {
                        if (closed.get() || http.dispatcherType != DispatcherType.REQUEST || http.isAsyncStarted) refuse()
                        ComplaintSecurityResponses.headers(tracked)
                        chain.doFilter(SynchronousRequest(http), tracked)
                    }
                } finally {
                    frame.finished = true
                    current.remove()
                }
            }
        } catch (failure: ComplaintAdmissionRejected) {
            val kind = if (failure.status == 429) ComplaintSecurityFailure.RATE_LIMITED else ComplaintSecurityFailure.UNAVAILABLE
            fail(http, tracked, kind, failure.retryAfterSeconds)
        } catch (failure: ComplaintSecurityRejected) {
            fail(http, tracked, failure.failure)
        }
    }

    /** Crypto/current-row authentication may inspect, but cannot claim the terminal handler or start semantics. */
    fun authenticationContext(request: HttpServletRequest): ComplaintIngressContext {
        val frame = frame(request)
        if (frame.claimed) refuse()
        return frame.context
    }

    /** Only the fixed dispatcher consumes this once; ordinary handlers never open nested ingress. */
    fun claimHandler(request: HttpServletRequest): ComplaintIngressContext {
        val frame = frame(request)
        if (frame.claimed) refuse()
        frame.claimed = true
        return frame.context
    }

    private fun frame(request: HttpServletRequest): Frame {
        requireConnectionFree()
        val frame = current.get() ?: refuse()
        if (frame.finished || frame.caller !== Thread.currentThread()) refuse()
        if (frame.request !== root(request) || request.dispatcherType != DispatcherType.REQUEST) refuse()
        if (frame.method != request.method || frame.uri != request.requestURI || frame.contextPath != request.contextPath) refuse()
        ingress.requireLiveContext(frame.context)
        return frame
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun respond(request: HttpServletRequest, response: DeliveryResponse, operation: () -> Unit) {
        try {
            requireNotInterrupted()
            operation()
            requireNotInterrupted()
        } catch (failure: ComplaintAdmissionRejected) {
            val kind = if (failure.status == 429) ComplaintSecurityFailure.RATE_LIMITED else ComplaintSecurityFailure.UNAVAILABLE
            fail(request, response, kind, failure.retryAfterSeconds)
        } catch (failure: ComplaintSecurityRejected) {
            fail(request, response, failure.failure)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: IOException) {
            requireNotInterrupted()
            throw IOException(ComplaintSecurityResponses.DELIVERY_FAILURE)
        } catch (failure: RuntimeException) {
            requireNotInterrupted()
            closed.set(true)
            fail(request, response, unexpectedFailure(request))
        } catch (failure: OutOfMemoryError) {
            closed.set(true)
            fail(request, response, unexpectedFailure(request))
        }
    }

    /** Bootstrap never translates unavailable local resources into a credential error or a plausible scope. */
    private fun unexpectedFailure(request: HttpServletRequest): ComplaintSecurityFailure =
        if (request.requestURI == request.contextPath + ComplaintInstallationRoutes.BOOTSTRAP) ComplaintSecurityFailure.UNAVAILABLE else ComplaintSecurityFailure.INTERNAL

    private fun fail(request: HttpServletRequest, response: DeliveryResponse, failure: ComplaintSecurityFailure, retry: Long? = null) {
        requireNotInterrupted()
        // Even a not-yet-committed container buffer can already contain success bytes. Never append a problem.
        if (response.deliveryStarted) throw IOException(ComplaintSecurityResponses.DELIVERY_FAILURE)
        ComplaintSecurityResponses.problem(request, response, failure, retry)
    }

    private fun requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Complaint security request interrupted.")
    }

    override fun toString(): String = "ComplaintHttpIngressBridge(dormant,synchronous)"

    private class Frame(
        val context: ComplaintIngressContext,
        val request: ServletRequest,
        val caller: Thread,
        val method: String,
        val uri: String,
        val contextPath: String,
    ) {
        var claimed = false
        var finished = false
    }

    private class DeliveryResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        var deliveryStarted = false
            private set

        override fun getOutputStream(): jakarta.servlet.ServletOutputStream {
            deliveryStarted = true
            return super.getOutputStream()
        }

        override fun getWriter(): java.io.PrintWriter {
            deliveryStarted = true
            return super.getWriter()
        }
    }

    private class SynchronousRequest(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        override fun isAsyncSupported(): Boolean = false
        override fun startAsync(): AsyncContext = refuse()
        override fun startAsync(request: ServletRequest, response: ServletResponse): AsyncContext = refuse()
    }

    private companion object {
        fun refuse(): Nothing = throw ComplaintSecurityRejected(ComplaintSecurityFailure.UNAVAILABLE)

        fun root(request: HttpServletRequest): ServletRequest {
            var selected: ServletRequest = request
            repeat(16) {
                if (selected !is HttpServletRequestWrapper) return selected
                selected = (selected as HttpServletRequestWrapper).request
            }
            refuse()
        }
    }
}
