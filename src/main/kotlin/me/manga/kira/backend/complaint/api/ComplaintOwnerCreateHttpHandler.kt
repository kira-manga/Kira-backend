package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReplyInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintReportMetadataInput
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.rejectOwnerOperation
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.web.HttpRequestHandler
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Fixed create/reply/status POST routes, intentionally UNREGISTERED. Production's closed graph is unchanged. */
internal class ComplaintOwnerCreateHttpHandler(
    private val service: ComplaintOwnerCreateService,
    private val ingress: ComplaintIngressAdmission,
    private val responses: ComplaintOwnerOperationResponse = ComplaintOwnerOperationResponse(),
    private val editStatus: ComplaintOwnerEditHttpHandler? = null,
) : HttpRequestHandler {
    init {
        require(editStatus == null || editStatus.sharesOwner(ingress, responses)) { "Complaint status composition refused." }
    }

    internal fun hasEditStatus(): Boolean = editStatus != null

    internal fun usesEditStatus(handler: ComplaintOwnerEditHttpHandler): Boolean = editStatus === handler

    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) = responseBoundary(request, response) {
        ingress.withIngress(request) { context -> exchangeHttp(request, response, context) }
    }

    /** The concrete outer bridge already owns ingress; validation never starts or renews it. */
    internal fun handleWithinIngress(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) =
        responseBoundary(request, response) {
            ingress.requireLiveContext(context)
            exchangeHttp(request, response, context)
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun responseBoundary(request: HttpServletRequest, response: HttpServletResponse, operation: () -> Unit) {
        try {
            operation()
        } catch (failure: ComplaintOwnerOperationRejected) {
            if (failure.failure == ComplaintOwnerOperationFailure.INTERNAL) responses.failClosed()
            problem(request, response, failure.failure, if (failure.failure == ComplaintOwnerOperationFailure.IN_PROGRESS) 1 else null)
        } catch (failure: ComplaintAdmissionRejected) {
            problem(
                request,
                response,
                if (failure.status == 429) ComplaintOwnerOperationFailure.RATE_LIMITED else ComplaintOwnerOperationFailure.UNAVAILABLE,
                failure.retryAfterSeconds,
            )
        } catch (failure: IOException) {
            throw IOException("Complaint operation delivery failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            problem(request, response, ComplaintOwnerOperationFailure.INTERNAL)
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            problem(request, response, ComplaintOwnerOperationFailure.INTERNAL)
        }
    }

    private fun exchangeHttp(request: HttpServletRequest, response: HttpServletResponse, context: ComplaintIngressContext) {
        val path = request.requestURI.removePrefix(request.contextPath)
        val reply = REPLY.matchEntire(path)
        if (request.method != "POST" || (path !in PATHS && reply == null)) rejectOwnerOperation(ComplaintOwnerOperationFailure.NOT_FOUND)
        if (request.queryString != null ||
            single(request, "If-Match", 128) != null
        ) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        }
        val statusLookup = path == STATUS
        val bearer = bearer(request)
        val key = single(request, "X-Kira-Idempotency-Key", 36)
        if (statusLookup == (key != null)) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        val permit = responses.acquire() ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
        permit.use {
            val body = body(request)
            val receipt = try {
                if (statusLookup) {
                    when (val query = ComplaintOwnerOperationJson.statusInput(body)) {
                        is ComplaintOwnerStatusInput.Creation -> service.status(context, bearer, query.query)
                        is ComplaintOwnerStatusInput.Edit -> {
                            val selected = editStatus ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
                            body.fill(0)
                            selected.handleStatusWithinIngress(response, context, bearer, query.query, permit)
                            return@use
                        }
                    }
                } else if (reply != null) {
                    service.reply(context, bearer, ComplaintOwnerOperationJson.reply(body, checkNotNull(key), reply.groupValues[1]))
                } else {
                    service.create(context, bearer, ComplaintOwnerOperationJson.create(body, checkNotNull(key)))
                }
            } finally {
                body.fill(0)
            }
            val encoded = responses.encode(permit, receipt, statusLookup)
            try {
                if (!responses.isOpen()) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAVAILABLE)
                deliver(response, encoded, receipt, statusLookup)
            } finally {
                encoded.destroy()
            }
        }
    }

    private fun bearer(request: HttpServletRequest): String {
        val value = single(request, "Authorization", 4103) ?: rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        if (!value.startsWith("Bearer ") || value.length == 7) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNAUTHORIZED)
        single(request, "X-Kira-Complaint-Contract", 1)?.let { if (it != "1") rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST) }
        return value.substring(7)
    }

    @Suppress("SwallowedException")
    private fun body(request: HttpServletRequest): ByteArray {
        val length = single(request, "Content-Length", 64)?.trim(' ', '\t')
        val transfer = single(request, "Transfer-Encoding", 64)?.trim(' ', '\t')
        if (length != null && transfer != null) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        if (length != null && (length.isEmpty() || length.any { it !in '0'..'9' })) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        }
        if (transfer != null && !transfer.equals("chunked", ignoreCase = true)) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        val media = single(request, "Content-Type", 128)?.trim(' ', '\t')
        val encoding = single(request, "Content-Encoding", 64)?.trim(' ', '\t')
        if (media == null || !MEDIA.matches(media)) rejectOwnerOperation(ComplaintOwnerOperationFailure.UNSUPPORTED_MEDIA)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.UNSUPPORTED_MEDIA)
        }
        val declared = if (transfer != null) -1L else length?.let { it.toLongOrNull() ?: Long.MAX_VALUE } ?: request.contentLengthLong
        if (declared > MAX_BODY_BYTES) rejectOwnerOperation(ComplaintOwnerOperationFailure.PAYLOAD_TOO_LARGE)
        val bytes = try {
            request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        } catch (failure: IOException) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        }
        if (bytes.size > MAX_BODY_BYTES || (declared >= 0 && declared != bytes.size.toLong())) {
            val failure = if (bytes.size > MAX_BODY_BYTES) ComplaintOwnerOperationFailure.PAYLOAD_TOO_LARGE else ComplaintOwnerOperationFailure.INVALID_REQUEST
            bytes.fill(0)
            rejectOwnerOperation(failure)
        }
        return bytes
    }

    private fun single(request: HttpServletRequest, name: String, maximum: Int): String? {
        val values = request.getHeaders(name)
        if (!values.hasMoreElements()) return null
        val value = values.nextElement()
        if (values.hasMoreElements() || value.length > maximum) rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        if (value.any { it.code !in 32..126 && it != '\t' }) {
            rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
        }
        return value
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun deliver(response: HttpServletResponse, body: ComplaintHistoryEncodedBody, receipt: ComplaintOwnerReceipt, statusLookup: Boolean) {
        try {
            ingress.requireResponseReady()
            val status = if (statusLookup) {
                200
            } else {
                when (receipt) {
                    is ComplaintOwnerReceipt.Applied -> 201
                    is ComplaintOwnerReceipt.Rejected -> receipt.status
                }
            }
            val media = if (!statusLookup && receipt is ComplaintOwnerReceipt.Rejected) "application/problem+json" else "application/json"
            headers(response, status, media, body.length)
            if (!statusLookup && receipt is ComplaintOwnerReceipt.Applied) {
                response.setHeader("Location", receipt.location)
                response.setHeader("ETag", receipt.etag)
            }
            body.sendTo(response.outputStream)
        } catch (failure: IOException) {
            throw IOException("Complaint operation delivery failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            throw IOException("Complaint operation delivery failed.")
        } catch (failure: OutOfMemoryError) {
            responses.failClosed()
            throw IOException("Complaint operation delivery failed.")
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun problem(request: HttpServletRequest, response: HttpServletResponse, failure: ComplaintOwnerOperationFailure, retry: Long? = null) {
        if (response.isCommitted) return
        val bytes = checkNotNull(ComplaintOwnerOperationResponse.PROBLEMS[failure])
        try {
            ingress.requireResponseReady()
            headers(response, failure.status, "application/problem+json", bytes.size)
            retry?.let { response.setHeader("Retry-After", it.toString()) }
            if (failure == ComplaintOwnerOperationFailure.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer realm=\"kira-complaints\"")
            if (request.method != "HEAD") response.outputStream.write(bytes)
        } catch (failure: IOException) {
            throw IOException("Complaint operation delivery failed.")
        } catch (failure: RuntimeException) {
            responses.failClosed()
            throw IOException("Complaint operation delivery failed.")
        }
    }

    private fun headers(response: HttpServletResponse, status: Int, media: String, length: Int) {
        response.status = status
        response.setHeader("X-Kira-Complaint-Contract", "1")
        response.setHeader("Cache-Control", "no-store, no-transform")
        response.contentType = "$media;charset=UTF-8"
        response.setContentLength(length)
    }

    override fun toString(): String = "ComplaintOwnerCreateHttpHandler(dormant,POST-only)"

    companion object {
        const val CREATE = "/api/v1/complaints"
        const val STATUS = "/api/v1/complaint-operations/status"
        const val MAX_BODY_BYTES = 16 * 1024
        private val PATHS = setOf(CREATE, STATUS)
        private val REPLY = Regex("$CREATE/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/replies")
        private val MEDIA = Regex("""application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:utf-8|"utf-8"))?""", RegexOption.IGNORE_CASE)
    }
}

/** Only these fixed status producers can be dispatched; edit never enters the creation enum/tuple/receipt. */
internal sealed interface ComplaintOwnerStatusInput {
    class Creation(val query: ComplaintOwnerStatusQuery) : ComplaintOwnerStatusInput
    class Edit(val query: ComplaintOwnerEditStatusQuery) : ComplaintOwnerStatusInput
}

/** Closed structural parser only. It cannot supply authenticated scope/platform or normalize prose. */
internal object ComplaintOwnerOperationJson {
    private val mapper = ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(16384).maxNameLength(64).maxNumberLength(20).build(),
            ).build(),
    )
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun create(body: ByteArray, key: String): ComplaintOwnerCreateInput = parse {
        val root = read(body, setOf("id", "type", "subject", "body", "metadata"))
        val metadata = root["metadata"]
        fields(metadata, setOf("appVersion", "osVersion", "manufacturer", "deviceModel"))
        ComplaintOwnerCreateInput(
            ComplaintIdentifiers.clientResourceId(string(root, "id")),
            ComplaintIdentifiers.idempotencyKey(key),
            ComplaintType.valueOf(string(root, "type")),
            string(root, "subject"),
            string(root, "body"),
            ComplaintReportMetadataInput(
                if (metadata["appVersion"].isNull) null else string(metadata, "appVersion"),
                string(metadata, "osVersion"),
                string(metadata, "manufacturer"),
                string(metadata, "deviceModel"),
            ),
        )
    }

    fun status(body: ByteArray): ComplaintOwnerStatusQuery = when (val parsed = statusInput(body)) {
        is ComplaintOwnerStatusInput.Creation -> parsed.query
        is ComplaintOwnerStatusInput.Edit -> rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    }

    fun statusInput(body: ByteArray): ComplaintOwnerStatusInput = parse {
        val root = read(body, setOf("operation", "key", "targetIds", "fingerprint"))
        val ids = root["targetIds"]
        require(ids.isArray && ids.size() in 1..2 && ids.all { it.isTextual })
        val operation = string(root, "operation")
        if (operation == "OWNER_EDIT") {
            ComplaintOwnerStatusInput.Edit(ComplaintOwnerEditStatusQuery(string(root, "key"), ids.map { it.textValue() }, string(root, "fingerprint")))
        } else {
            ComplaintOwnerStatusInput.Creation(
                ComplaintOwnerStatusQuery(operation, string(root, "key"), ids.map { it.textValue() }, string(root, "fingerprint")),
            )
        }
    }

    fun edit(body: ByteArray, key: String, target: UUID, precondition: ComplaintOwnerEditPrecondition): ComplaintOwnerEditInput = parse {
        val root = read(body)
        val subject = if (root.has("subject")) string(root, "subject") else null
        fields(root, if (subject == null) setOf("body") else setOf("subject", "body"))
        ComplaintOwnerEditInput(target, ComplaintIdentifiers.idempotencyKey(key), subject, string(root, "body"), precondition)
    }

    fun reply(body: ByteArray, key: String, parentId: String): ComplaintOwnerReplyInput = parse {
        val root = read(body, setOf("id", "body", "metadata"))
        val metadata = root["metadata"]
        fields(metadata, setOf("appVersion", "osVersion", "manufacturer", "deviceModel"))
        ComplaintOwnerReplyInput(
            ComplaintIdentifiers.resourceId(parentId),
            ComplaintIdentifiers.clientResourceId(string(root, "id")),
            ComplaintIdentifiers.idempotencyKey(key),
            string(root, "body"),
            ComplaintReportMetadataInput(
                if (metadata["appVersion"].isNull) null else string(metadata, "appVersion"),
                string(metadata, "osVersion"),
                string(metadata, "manufacturer"),
                string(metadata, "deviceModel"),
            ),
        )
    }

    private fun read(body: ByteArray, expected: Set<String>? = null): JsonNode {
        require(body.size <= ComplaintOwnerCreateHttpHandler.MAX_BODY_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
        val root = mapper.readTree(text)
        require(root != null && root.isObject)
        if (expected != null) fields(root, expected)
        return root
    }

    private fun fields(root: JsonNode?, expected: Set<String>) {
        require(root != null && root.isObject && root.size() == expected.size && root.fieldNames().asSequence().toSet() == expected)
    }

    private fun string(root: JsonNode, name: String): String {
        val node = root[name]
        require(node != null && node.isTextual)
        return node.textValue()
    }

    @Suppress("SwallowedException")
    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (failure: JsonProcessingException) {
        rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    } catch (failure: CharacterCodingException) {
        rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    } catch (failure: ComplaintValidationException) {
        rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    } catch (failure: IllegalArgumentException) {
        rejectOwnerOperation(ComplaintOwnerOperationFailure.INVALID_REQUEST)
    }
}
