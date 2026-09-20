package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusAcknowledgement
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTarget
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRequest
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

/** Supplied-data/framing tests only; the connected IT proves authentication, transaction and receipt authority. */
class ComplaintAdminBatchStatusParserTest {
    private val mapper = ObjectMapper()
    private val scope = ComplaintDataScope.of(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val key = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val low = UUID.fromString("123e4567-e89b-52d3-a456-426614174000")
    private val high = UUID.fromString("fedcba98-7654-4321-8fed-cba987654321")

    @Test
    fun fixedGoldenFingerprintSortsIntactPairsTextuallyAndBindsStatusScopeAndEveryTagButNotFormattingOrKey() {
        val raw = body(listOf(target(high, 9_007_199_254_740_993L), target(low, 7)))
        val request = ComplaintAdminBatchStatusRequest.normalize(parse(raw))
        assertEquals(listOf(low, high), request.targets.map { it.id }) // UUID.compareTo would put the high-bit UUID first.
        assertEquals(listOf(7L, 9_007_199_254_740_993L), request.targets.map { it.precondition.version })
        val digest = ComplaintAdminBatchStatusFingerprint.of(request)
        assertEquals("GxkR1zGqj5s1YfQ_LoeurdmzQhyjMO57ZHyc1bAfJKY", digest.encoded)
        val frame = ComplaintAdminBatchStatusFingerprint.frameBytes(request)
        assertEquals(367, frame.size)
        DataInputStream(ByteArrayInputStream(frame)).use { input ->
            fun field(): String = input.readNBytes(input.readInt()).toString(Charsets.UTF_8)
            assertEquals("kira-complaint-request-fingerprint", field())
            assertEquals(1, input.readInt())
            assertEquals("POST", field())
            assertEquals("/api/v1/admin/complaints/batch", field())
            assertEquals("ADMIN_BATCH_STATUS", field())
            assertEquals(scope.id.toString(), field())
            assertEquals(2, input.readInt())
            for ((id, version) in listOf(low to 7L, high to 9_007_199_254_740_993L)) {
                assertEquals(id.toString(), field())
                assertEquals("\"complaint-$id-v$version\"", field())
            }
            assertEquals("RESOLVED", field())
            assertEquals(-1, input.read())
        }
        val reordered = """ { "targets" : [${target(low, 7)},${target(high, 9_007_199_254_740_993L)}], "status":"RESOLVED", "action":"STATUS" } """
        assertArrayEquals(digest.bytes(), fingerprint(parse(reordered)))
        assertArrayEquals(digest.bytes(), fingerprint(ComplaintAdminBatchStatusParser.parse(raw.toByteArray(), scope, UUID.randomUUID().toString())))
        for (changed in listOf(
            raw.replace("RESOLVED", "PLANNED"), body(listOf(target(low, 8), target(high, 9_007_199_254_740_993L))),
            body(listOf(target(low, 7))), body(listOf(target(low, 9_007_199_254_740_993L), target(high, 7))),
        )) assertNotEquals(digest.encoded, ComplaintAdminBatchStatusFingerprint.of(ComplaintAdminBatchStatusRequest.normalize(parse(changed))).encoded)
        assertNotEquals(digest.encoded, ComplaintAdminBatchStatusFingerprint.of(ComplaintAdminBatchStatusRequest.normalize(
            ComplaintAdminBatchStatusParser.parse(raw.toByteArray(), ComplaintDataScope.of(UUID.randomUUID()), key.toString()),
        )).encoded)
        val single = ComplaintAdminStatusRequest.normalize(ComplaintAdminStatusInput.Transition(
            scope, low, key, ComplaintStatus.RESOLVED, ComplaintAdminStatusPrecondition.parse(low, "\"complaint-$low-v7\""),
        ))
        assertNotEquals(ComplaintAdminStatusFingerprint.of(single).encoded, ComplaintAdminBatchStatusFingerprint.of(
            ComplaintAdminBatchStatusRequest.normalize(parse(body(listOf(target(low, 7))))),
        ).encoded)
        val escaped = raw.replace("STATUS", "\\u0053TATUS").replace("actionTag", "action\\u0054ag")
        assertArrayEquals(digest.bytes(), fingerprint(parse(escaped)))
    }

    @Test
    fun schemaAndTagFailuresAreDistinctAndEveryTargetIsBoundedCanonicalAndUnique() {
        for (raw in listOf(
            "null", "[]", "{}", body(emptyList()), body(listOf(target(low), target(low))),
            body(listOf(target(low))).replace("STATUS", "DELETE"), body(listOf(target(low))).replace("RESOLVED", "CLOSED"),
            body(listOf(target(low))).replace("RESOLVED", "resolved"), body(listOf(target(low))) + " {}",
            """{"action":"STATUS","action":"STATUS","status":"RESOLVED","targets":[${target(low)}]}""",
            """{"action":"STATUS","status":"RESOLVED","targets":[${target(low)}],"reason":"private"}""",
            body(listOf("""{"actionTag":"unused"}""")), body(listOf(target(low).dropLast(1) + ",\"id\":\"$low\"}")),
            body(listOf(target(low).dropLast(1) + ",\"actor\":\"private\"}")),
            body(listOf("""{"id":"$low","actionTag":null}""")), body(listOf("""{"id":"$low","actionTag":7}""")),
            body(listOf("""{"id":"$low","actionTag":[]}""")), body(listOf("""{"id":"$low","actionTag":{}}""")),
            body(listOf(target(low))).replace(low.toString(), low.toString().uppercase()),
            body(listOf(target(low))).replace(low.toString(), "1-1-1-1-1"),
        )) refused(raw, ComplaintAdminStatusFailure.INVALID_REQUEST)
        refused(body(listOf("""{"id":"$low"}""")), ComplaintAdminStatusFailure.PRECONDITION_REQUIRED)
        val missing = """{"id":"$low"}"""
        val malformed = """{"id":"$high","actionTag":"invalid"}"""
        for (ordered in listOf(listOf(missing, malformed), listOf(malformed, missing))) {
            refused(body(ordered), ComplaintAdminStatusFailure.PRECONDITION_REQUIRED)
        }
        // A later structural violation still wins over a merely missing tag; null/non-string is never classified as missing.
        refused(body(listOf("""{"id":"$low"}""", target(high).dropLast(1) + ",\"unknown\":1}")), ComplaintAdminStatusFailure.INVALID_REQUEST)
        for (tag in listOf("", "*", "W/\"complaint-$low-v7\"", "\"complaint-$high-v7\"", "\"complaint-$low-v0\"",
            "\"complaint-$low-v07\"", "\"complaint-$low-v9223372036854775808\"", "\"complaint-$low-v7\",\"complaint-$low-v8\"", "x".repeat(257))) {
            refused(body(listOf("{\"id\":\"$low\",\"actionTag\":${mapper.writeValueAsString(tag)}}")), ComplaintAdminStatusFailure.PRECONDITION_FAILED)
        }
        for (count in listOf(1, 50)) {
            val parsed = parse(body((1..count).map { target(UUID(0, it.toLong()), Long.MAX_VALUE) }.reversed()))
            assertEquals(count, parsed.targets.size)
            assertTrue(ComplaintAdminBatchStatusFingerprint.frameBytes(ComplaintAdminBatchStatusRequest.normalize(parsed)).size <= 8192)
        }
        refused(body((1..51).map { target(UUID(0, it.toLong())) }), ComplaintAdminStatusFailure.INVALID_REQUEST)
    }

    @Test
    fun rawByteCapUtf8AndImmutableCopiesPreventUnboundedOrMutableRequestIdentity() {
        val raw = body(listOf(target(low)))
        val maximum = raw.toByteArray() + ByteArray(32 * 1024 - raw.toByteArray().size) { 32 }
        assertEquals(1, ComplaintAdminBatchStatusParser.parse(maximum, scope, key.toString()).targets.size)
        val over = assertThrows<ComplaintAdminStatusRejected> { ComplaintAdminBatchStatusParser.parse(maximum + byteArrayOf(32), scope, key.toString()) }
        assertEquals(ComplaintAdminStatusFailure.TOO_LARGE, over.failure)
        val malformed = assertThrows<ComplaintAdminStatusRejected> {
            ComplaintAdminBatchStatusParser.parse(byteArrayOf(0xc3.toByte(), 0x28), scope, key.toString())
        }
        assertEquals(ComplaintAdminStatusFailure.INVALID_REQUEST, malformed.failure)
        val targets = mutableListOf(ComplaintAdminBatchStatusTarget(low, ComplaintAdminStatusPrecondition.parse(low, "\"complaint-$low-v7\"")))
        val input = ComplaintAdminBatchStatusInput(scope, key, ComplaintStatus.RESOLVED, targets)
        val before = fingerprint(input)
        targets.clear()
        assertEquals(1, input.targets.size)
        assertThrows<UnsupportedOperationException> { (input.targets as MutableList<*>).clear() }
        assertArrayEquals(before, fingerprint(input))
        val tuple = ComplaintAdminBatchStatusTuple(UUID.randomUUID(), scope, key, input.targets.map { it.id }, before)
        before.fill(0)
        assertArrayEquals(fingerprint(input), tuple.fingerprintBytes())
        assertThrows<UnsupportedOperationException> { (tuple.targetIds() as MutableList<*>).clear() }
        val items = mutableListOf(ComplaintAdminBatchStatusAcknowledgement(low, 8))
        val receipt = ComplaintAdminBatchStatusReceipt.Applied(items)
        items.clear()
        assertEquals(1, receipt.items.size)
        assertThrows<UnsupportedOperationException> { (receipt.items as MutableList<*>).clear() }
    }

    private fun target(id: UUID, version: Long = 7): String = mapper.writeValueAsString(linkedMapOf("id" to id.toString(), "actionTag" to "\"complaint-$id-v$version\""))
    private fun body(targets: List<String>): String = "{\"action\":\"STATUS\",\"status\":\"RESOLVED\",\"targets\":[${targets.joinToString(",")}]}"
    private fun parse(raw: String): ComplaintAdminBatchStatusInput = ComplaintAdminBatchStatusParser.parse(raw.toByteArray(), scope, key.toString())
    private fun fingerprint(input: ComplaintAdminBatchStatusInput): ByteArray = ComplaintAdminBatchStatusFingerprint.of(ComplaintAdminBatchStatusRequest.normalize(input)).bytes()
    private fun refused(raw: String, failure: ComplaintAdminStatusFailure) = assertEquals(failure, assertThrows<ComplaintAdminStatusRejected> { parse(raw) }.failure)
}
