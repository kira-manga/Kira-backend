package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteTarget
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFamily
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusRejected
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Supplied-data tests only. Actual AUTH/APPLY and historical replay live in the connected IT. */
class ComplaintAdminBatchDeleteParserTest {
    private val mapper = ObjectMapper()
    private val scope = ComplaintDataScope.of(UUID.fromString("11111111-1111-4111-8111-111111111111"))
    private val key = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val low = UUID.fromString("123e4567-e89b-52d3-a456-426614174000")
    private val high = UUID.fromString("fedcba98-7654-4321-8fed-cba987654321")

    @Test
    fun intactTextSortedPairsUseIndependentOperationScopedFrameAndMaxLongNeedsNoSuccessor() {
        val raw = body(listOf(target(high, Long.MAX_VALUE), target(low, 7)))
        val request = ComplaintAdminDeleteRequest.normalize(parse(raw))
        assertEquals(ComplaintAdminDeleteFamily.BATCH, request.family)
        assertEquals(listOf(low, high), request.targets.map { it.id })
        assertEquals(listOf(7L, Long.MAX_VALUE), request.targets.map { it.precondition.version })
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun field(value: String) { val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
            field("kira-complaint-request-fingerprint"); out.writeInt(1)
            field("POST"); field("/api/v1/admin/complaints/batch"); field("ADMIN_BATCH_DELETE"); field(scope.id.toString()); out.writeInt(2)
            field(low.toString()); field("\"complaint-$low-v7\"")
            field(high.toString()); field("\"complaint-$high-v${Long.MAX_VALUE}\"")
        }
        val digest = fingerprint(parse(raw))
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()), digest)
        assertArrayEquals(digest, fingerprint(parse(" { \"targets\":[${target(low, 7)},${target(high, Long.MAX_VALUE)}],\"action\":\"DELETE\" } ")))
        assertArrayEquals(digest, fingerprint(parse(raw, key = UUID.randomUUID())))
        assertFalse(digest.contentEquals(fingerprint(parse(raw, selectedScope = ComplaintDataScope.of(UUID.randomUUID())))))
        for (changed in listOf(body(listOf(target(low, 8), target(high, Long.MAX_VALUE))), body(listOf(target(low, Long.MAX_VALUE), target(high, 7))), body(listOf(target(low, 7)))))
            assertFalse(digest.contentEquals(fingerprint(parse(changed))))
        val singleBatch = fingerprint(parse(body(listOf(target(low, 7)))))
        val single = ComplaintAdminDeleteRequest.normalize(ComplaintAdminDeleteInput(scope, low, key, ComplaintAdminDeletePrecondition.parse(low, "\"complaint-$low-v7\"")))
        assertFalse(singleBatch.contentEquals(ComplaintAdminDeleteFingerprint.of(single).bytes()))
        val status = ComplaintAdminBatchStatusParser.parse(body(listOf(target(low, 7))).replace("\"DELETE\"", "\"STATUS\",\"status\":\"RESOLVED\"").toByteArray(), scope, key.toString())
        assertFalse(singleBatch.contentEquals(ComplaintAdminBatchStatusFingerprint.of(ComplaintAdminBatchStatusRequest.normalize(status)).bytes()))
        assertThrows<IllegalStateException> { request.targetId }
        assertThrows<IllegalStateException> { request.precondition }
    }

    @Test
    fun closedDeleteSchemaKeepsWholeBody400BeforeMissing428BeforeInvalid412() {
        for (raw in listOf(
            "{}", body(emptyList()), body(listOf(target(low), target(low))), body(listOf(target(low))) + " {}",
            body(listOf(target(low))).replace("\"DELETE\"", "\"DELETE\",\"status\":\"RESOLVED\""),
            body(listOf(target(low))).replace("\"DELETE\"", "\"DELETE\",\"action\":\"DELETE\""),
            body(listOf(target(low))).dropLast(1) + ",\"ownerInstallationIds\":[]}",
            body(listOf("{\"id\":\"$low\",\"actionTag\":null}")),
            body(listOf("{\"id\":\"$low\"}", "{\"id\":\"$high\",\"extra\":true}")),
            body(listOf("{\"id\":\"${low.toString().uppercase()}\"}")),
            body((1..51).map { target(UUID(0, it.toLong())) }),
        )) refused(raw, 400)
        for (targets in listOf(
            listOf("{\"id\":\"$low\"}", "{\"id\":\"$high\",\"actionTag\":\"invalid\"}"),
            listOf("{\"id\":\"$high\",\"actionTag\":\"invalid\"}", "{\"id\":\"$low\"}"),
        )) refused(body(targets), 428)
        for (tag in listOf("", "*", "W/\"complaint-$low-v7\"", "\"complaint-$high-v7\"", "\"complaint-$low-v07\"", "\"complaint-$low-v0\"", "\"complaint-$low-v9223372036854775808\""))
            refused(body(listOf(mapper.writeValueAsString(mapOf("id" to low.toString(), "actionTag" to tag)))), 412)
        for (count in listOf(1, 50)) {
            val parsed = parse(body((1..count).map { target(UUID(0, it.toLong()), Long.MAX_VALUE) }.reversed()))
            assertEquals(count, parsed.targets.size)
            assertEquals((1..count).map { UUID(0, it.toLong()) }, parsed.targets.map { it.id })
            assertEquals(32, fingerprint(parsed).size)
        }
        assertEquals(400, status(assertThrows<ComplaintAdminStatusRejected> {
            ComplaintAdminBatchStatusParser.parse(body(listOf(target(low))).toByteArray(), scope, key.toString())
        }))
    }

    @Test
    fun oneOwnedBoundedUtf8BodyAndImmutableTargetsAndIdOnlyReceipt() {
        val raw = body(listOf(target(low))).toByteArray()
        val maximum = raw + ByteArray(32768 - raw.size) { 32 }
        assertEquals(1, (ComplaintAdminBatchParser.parse(maximum, scope, key.toString()) as ComplaintAdminBatchInput.Delete).input.targets.size)
        assertEquals(413, status(assertThrows<ComplaintAdminStatusRejected> { ComplaintAdminBatchParser.parse(maximum + byteArrayOf(32), scope, key.toString()) }))
        assertEquals(400, status(assertThrows<ComplaintAdminStatusRejected> { ComplaintAdminBatchParser.parse(byteArrayOf(0xc3.toByte(), 0x28), scope, key.toString()) }))
        val targets = mutableListOf(ComplaintAdminBatchDeleteTarget(low, ComplaintAdminDeletePrecondition.parse(low, "\"complaint-$low-v7\"")))
        val input = ComplaintAdminBatchDeleteInput(scope, key, targets)
        val digest = fingerprint(input)
        targets.clear()
        assertEquals(listOf(low), input.targets.map { it.id })
        assertThrows<UnsupportedOperationException> { (input.targets as MutableList<*>).clear() }
        assertArrayEquals(digest, fingerprint(input))
        val ids = mutableListOf(low)
        val receipt = ComplaintAdminDeleteReceipt.BatchApplied(ids, UUID.randomUUID())
        ids.clear()
        assertEquals(listOf(low), receipt.ids)
        assertThrows<UnsupportedOperationException> { (receipt.ids as MutableList<*>).clear() }
    }

    private fun target(id: UUID, version: Long = 7) = mapper.writeValueAsString(linkedMapOf("id" to id.toString(), "actionTag" to "\"complaint-$id-v$version\""))
    private fun body(targets: List<String>) = "{\"action\":\"DELETE\",\"targets\":[${targets.joinToString(",") }]}"
    private fun parse(raw: String, selectedScope: ComplaintDataScope = scope, key: UUID = this.key) =
        (ComplaintAdminBatchParser.parse(raw.toByteArray(), selectedScope, key.toString()) as ComplaintAdminBatchInput.Delete).input
    private fun fingerprint(input: ComplaintAdminBatchDeleteInput) = ComplaintAdminBatchDeleteFingerprint.of(ComplaintAdminDeleteRequest.normalize(input))
    private fun refused(raw: String, expected: Int) = assertEquals(expected, status(assertThrows<RuntimeException> { parse(raw) }))
    private fun status(failure: RuntimeException) = when (failure) {
        is ComplaintAdminStatusRejected -> failure.failure.status
        is ComplaintAdminDeleteRejected -> failure.failure.status
        else -> throw failure
    }
}
