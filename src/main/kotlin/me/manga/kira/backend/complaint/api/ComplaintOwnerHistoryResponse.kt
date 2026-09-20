package me.manga.kira.backend.complaint.api

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetail
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryContent
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryNotice
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPage
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** One handler's finite response owner. No file/cache spill and no deferred MVC serialization. */
internal class ComplaintOwnerHistoryResponses {
    private val semaphore = Semaphore(8)
    private val closed = AtomicBoolean()
    private val factory = JsonFactory()

    fun acquire(): Permit? {
        // Allocate custody before spending a slot: allocation failure must not leak capacity.
        val permit = Permit(semaphore)
        if (closed.get() || !permit.acquire()) return null
        if (!closed.get()) return permit
        permit.close()
        return null
    }

    fun failClosed() {
        closed.set(true)
    }

    fun isOpen(): Boolean = !closed.get()

    /** The concrete Admin read serializer shares this exact aggregate-eight owner, not a second semaphore. */
    internal fun requirePermit(permit: Permit) {
        if (!permit.belongsTo(semaphore) || !isOpen()) throw ComplaintHistorySerializationFailure()
    }

    // Keep every bounded-writer guard and cleanup/rethrow branch explicit.
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    fun encode(permit: Permit, page: ComplaintOwnerHistoryPage): ComplaintHistoryEncodedBody {
        if (!permit.belongsTo(semaphore) || !isOpen()) throw ComplaintHistorySerializationFailure()
        if (page.items.size > 50 || page.notices.size > 16) throw ComplaintHistorySerializationFailure()
        val ids = page.items.map { it.id } + page.notices.map { it.id }
        if (ids.toSet().size != ids.size || page.notices.map { it.noticeKey }.toSet().size != page.notices.size) throw ComplaintHistorySerializationFailure()
        val buffer = ComplaintHistoryEncodedBody(2 * 1024 * 1024)
        try {
            factory.createGenerator(buffer).use { json ->
                json.writeStartObject()
                json.writeArrayFieldStart("notices")
                page.notices.forEach { notice(json, it) }
                json.writeEndArray()
                json.writeArrayFieldStart("items")
                for ((index, item) in page.items.withIndex()) {
                    json.flush()
                    val start = buffer.length
                    content(json, item)
                    json.flush()
                    val separator = if (index == 0) 0 else 1
                    if (buffer.length - start - separator > 32 * 1024) throw ComplaintHistorySerializationFailure()
                }
                json.writeEndArray()
                nullable(json, "nextCursor", page.nextCursor)
                json.writeEndObject()
            }
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

    /** Same closed fields as history, but one root item and its own post-escaping 32 KiB buffer. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ThrowsCount")
    fun encodeDetail(permit: Permit, detail: ComplaintOwnerDetail): ComplaintHistoryEncodedBody {
        if (!permit.belongsTo(semaphore) || !isOpen()) throw ComplaintHistorySerializationFailure()
        val buffer = ComplaintHistoryEncodedBody(DETAIL_MAX_BYTES)
        try {
            factory.createGenerator(buffer).use { json ->
                when (detail) {
                    is ComplaintOwnerDetail.Content -> content(json, detail.item)
                    is ComplaintOwnerDetail.Notice -> notice(json, detail.item)
                }
            }
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

    private fun notice(json: JsonGenerator, row: ComplaintOwnerHistoryNotice) {
        json.writeStartObject()
        json.writeStringField("id", row.id.toString())
        json.writeStringField("kind", "NOTICE")
        json.writeStringField("noticeKey", row.noticeKey)
        json.writeStringField("status", "PINNED")
        json.writeStringField("createdAt", row.createdAt.toString())
        json.writeStringField("updatedAt", row.updatedAt.toString())
        json.writeNumberField("version", row.version)
        json.writeEndObject()
    }

    private fun content(json: JsonGenerator, row: ComplaintOwnerHistoryContent) {
        json.writeStartObject()
        json.writeStringField("id", row.id.toString())
        json.writeStringField("kind", row.kind.name)
        json.writeStringField("type", row.type.name)
        nullable(json, "subject", row.subject)
        json.writeStringField("body", row.body)
        json.writeStringField("status", row.status.name)
        json.writeStringField("createdAt", row.createdAt.toString())
        json.writeStringField("updatedAt", row.updatedAt.toString())
        json.writeNumberField("version", row.version)
        json.writeStringField("actionTag", actionTag(row))
        nullable(json, "appVersion", row.appVersion)
        json.writeStringField("platform", row.platform.name)
        json.writeStringField("osVersion", row.osVersion)
        json.writeStringField("manufacturer", row.manufacturer)
        json.writeStringField("deviceModel", row.deviceModel)
        nullable(json, "closureReason", row.closureReason)
        nullable(json, "replyToId", row.replyToId?.toString())
        row.noticeKey?.let { json.writeStringField("noticeKey", it) }
        json.writeEndObject()
    }

    private fun nullable(json: JsonGenerator, name: String, value: String?) {
        if (value == null) json.writeNullField(name) else json.writeStringField(name, value)
    }

    override fun toString(): String = "ComplaintOwnerHistoryResponses(bounded)"

    companion object {
        const val DETAIL_MAX_BYTES = 32 * 1024

        fun actionTag(row: ComplaintOwnerHistoryContent): String = "\"complaint-${row.id}-v${row.version}\""
    }

    internal class Permit(private val semaphore: Semaphore) : AutoCloseable {
        private val retained = AtomicBoolean()
        private var attempted = false

        fun acquire(): Boolean {
            check(!attempted) { "Response permit is one use." }
            attempted = true
            if (!semaphore.tryAcquire()) return false
            retained.set(true)
            return true
        }

        fun belongsTo(selected: Semaphore): Boolean = semaphore === selected && retained.get()

        override fun close() {
            if (retained.compareAndSet(true, false)) semaphore.release()
        }
    }
}

/** Exactly one fixed raw buffer. OutputStream.close from Jackson does not erase before transmission. */
internal class ComplaintHistoryEncodedBody(maximum: Int) : OutputStream() {
    private val bytes = ByteArray(maximum.also { require(it in 1..2 * 1024 * 1024) })
    private var destroyed = false
    var length: Int = 0
        private set

    override fun write(value: Int) {
        if (destroyed || length == bytes.size) throw ComplaintHistorySerializationFailure()
        bytes[length++] = value.toByte()
    }

    @Suppress("ComplexCondition")
    override fun write(source: ByteArray, offset: Int, count: Int) {
        if (destroyed || offset < 0 || count < 0 || offset > source.size - count || count > bytes.size - length) throw ComplaintHistorySerializationFailure()
        source.copyInto(bytes, length, offset, offset + count)
        length += count
    }

    fun sendTo(output: OutputStream) {
        check(!destroyed) { "History response is closed." }
        output.write(bytes, 0, length)
    }

    fun destroy() {
        bytes.fill(0)
        destroyed = true
        length = 0
    }

    override fun toString(): String = "ComplaintHistoryEncodedBody(redacted)"
}

internal class ComplaintHistorySerializationFailure : RuntimeException("History serialization refused.", null, false, false)
