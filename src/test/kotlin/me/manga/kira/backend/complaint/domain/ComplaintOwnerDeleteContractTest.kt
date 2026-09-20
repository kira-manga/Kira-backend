package me.manga.kira.backend.complaint.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.HexFormat
import java.util.UUID

/** Frozen independent literals, not registration, SQL, publication or runtime authority. */
class ComplaintOwnerDeleteContractTest {
    private val inputs = ownerDeleteLiteralResource("inputs.json")
    private val vectors = ownerDeleteLiteralResource("request-vectors.json").getValue("vectors").jsonArray
    private val scope = ComplaintDataScope.of(UUID.fromString(inputs.text("scope_id")))
    private val target = UUID.fromString(inputs.text("target_id"))
    private val key = UUID.fromString(inputs.text("operation_key"))
    private val actor = ScopedInstallationId(UUID.fromString(inputs.text("actor_id")), scope)

    @Test
    fun `ordinary minimum and maximum empty DELETE frames match all frozen bytes and digests`() {
        assertEquals(listOf("ordinary", "minimum", "maximum"), vectors.map { it.jsonObject.text("id") })
        assertEquals(listOf(235, 234, 252), vectors.map { it.jsonObject.getValue("frame").jsonObject.getValue("byte_count").jsonPrimitive.int })
        assertEquals(256, ComplaintOwnerDeleteFingerprint.MAX_FRAME_BYTES)
        assertEquals(1, ComplaintOwnerDeleteFingerprint.VERSION)
        assertEquals("/api/v1/complaints/{id}", ComplaintOwnerDeleteFingerprint.ROUTE)
        for (entry in vectors) {
            val vector = entry.jsonObject
            val request = request(vector.text("resource_version_decimal").toLong())
            val frame = vector.getValue("frame").jsonObject
            assertEquals(vector.text("canonical_precondition"), request.precondition.canonical)
            assertArrayEquals(HexFormat.of().parseHex(frame.text("hex")), ComplaintOwnerDeleteFingerprint.frameBytes(request), vector.text("id"))
            val actual = ComplaintOwnerDeleteFingerprint.of(request)
            assertEquals(vector.text("fingerprint_base64url"), actual.encoded)
            assertArrayEquals(HexFormat.of().parseHex(frame.text("sha256")), actual.bytes())
            actual.bytes().fill(0)
            assertArrayEquals(HexFormat.of().parseHex(frame.text("sha256")), actual.bytes())
        }
    }

    @Test
    fun `strong precondition accepts only the exact target and positive canonical Long within 256 bytes`() {
        val exact = "\"complaint-$target-v42\""
        val parsed = ComplaintOwnerDeletePrecondition.parse(target, " \t$exact\t ")
        assertEquals(42L, parsed.version)
        assertEquals(target, parsed.targetId)
        assertEquals(exact, parsed.canonical)
        assertEquals(exact, ComplaintOwnerDeletePrecondition.parse(target, " ".repeat(256 - exact.length) + exact).canonical)
        assertEquals(Long.MAX_VALUE, ComplaintOwnerDeletePrecondition.parse(target, "\"complaint-$target-v9223372036854775807\"").version)
        assertEquals(
            ComplaintOwnerOperationFailure.PRECONDITION_REQUIRED,
            assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerDeletePrecondition.parse(target, null) }.failure,
        )
        val invalid = listOf(
            "", "*", "W/$exact", "$exact,$exact", "$exact, \"other\"", exact.removeSurrounding("\""),
            "$exact\r\n", "$exact\u0000", "$exact\u007f", "\u00a0$exact", exact.uppercase(),
            exact.replace("v42", "v0"), exact.replace("v42", "v042"), exact.replace("v42", "v+42"),
            exact.replace("v42", "v-1"), exact.replace("v42", "v4.2"), exact.replace("v42", "v9223372036854775808"),
            exact.replace(target.toString(), key.toString()), " ".repeat(257 - exact.length) + exact,
        )
        for (header in invalid) {
            assertEquals(
                ComplaintOwnerOperationFailure.PRECONDITION_FAILED,
                assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerDeletePrecondition.parse(target, header) }.failure,
            )
        }
    }

    @Test
    fun `fingerprint excludes the operation key and binds scope target and strong precondition without a body slot`() {
        val original = ComplaintOwnerDeleteFingerprint.of(request()).encoded
        assertEquals("PgZfSK3fannK-yvw2dmhLHQjZrV9InbiTKOwF-vE4Gs", original)
        assertEquals(original, ComplaintOwnerDeleteFingerprint.of(request(selectedKey = UUID.randomUUID())).encoded)
        assertEquals(
            original,
            ComplaintOwnerDeleteFingerprint.of(
                ComplaintOwnerDeleteRequest.normalize(
                    scope,
                    ComplaintOwnerDeleteInput(target, key, ComplaintOwnerDeletePrecondition.parse(target, " \t\"complaint-$target-v42\" ")),
                ),
            ).encoded,
        )
        assertNotEquals(original, ComplaintOwnerDeleteFingerprint.of(request(43)).encoded)
        assertNotEquals(original, ComplaintOwnerDeleteFingerprint.of(request(selectedTarget = key)).encoded)
        val otherScope = ComplaintDataScope.of(UUID.fromString("b2222222-2222-4222-8222-222222222223"))
        assertNotEquals(original, ComplaintOwnerDeleteFingerprint.of(request(selectedScope = otherScope)).encoded)
        assertThrows<IllegalArgumentException> { request(selectedScope = ComplaintDataScope.LIVE) }
    }

    @Test
    fun `delete input cannot substitute an edit precondition target or a non v4 operation key`() {
        val other = ComplaintOwnerDeletePrecondition.parse(key, "\"complaint-$key-v42\"")
        assertThrows<IllegalArgumentException> { ComplaintOwnerDeleteInput(target, key, other) }
        val nonV4 = UUID.fromString("123e4567-e89b-52d3-a456-426614174000")
        assertThrows<ComplaintValidationException> { request(selectedKey = nonV4) }
        // Server/import resource UUID grammar is deliberately wider than client idempotency-key grammar.
        assertEquals(nonV4, request(selectedTarget = nonV4).targetId)
        assertEquals(setOf("OWNER_CREATE", "OWNER_REPLY"), ComplaintOwnerCreationOperation.entries.map { it.name }.toSet())
        assertThrows<ComplaintOwnerOperationRejected> {
            ComplaintOwnerStatusQuery("OWNER_DELETE", key.toString(), target.toString(), "PgZfSK3fannK-yvw2dmhLHQjZrV9InbiTKOwF-vE4Gs")
        }
    }

    @Test
    fun `status accepts one canonical target and exact unpadded digest without treating syntax as authority`() {
        val digest = HexFormat.of().parseHex("3e065f48addf6a79cafb2bf0d9d9a12c742366b57d2276e24ca3b017ebc4e06b")
        val encoded = "PgZfSK3fannK-yvw2dmhLHQjZrV9InbiTKOwF-vE4Gs"
        val query = ComplaintOwnerDeleteStatusQuery(key.toString(), listOf(target.toString()), encoded)
        assertEquals(key, query.key)
        assertEquals(target, query.targetId)
        query.fingerprintBytes().fill(0)
        assertArrayEquals(digest, query.fingerprintBytes())
        for (targets in listOf(emptyList(), listOf(target.toString(), target.toString()), listOf(target.toString(), key.toString()))) {
            assertEquals(
                ComplaintOwnerOperationFailure.INVALID_REQUEST,
                assertThrows<ComplaintOwnerOperationRejected> { ComplaintOwnerDeleteStatusQuery(key.toString(), targets, encoded) }.failure,
            )
        }
        for (bad in listOf("", "$encoded=", encoded.dropLast(1), encoded.dropLast(1) + "t", "!".repeat(43))) {
            assertThrows<ComplaintValidationException> { ComplaintOwnerDeleteStatusQuery(key.toString(), listOf(target.toString()), bad) }
        }
        assertThrows<ComplaintValidationException> { ComplaintOwnerDeleteStatusQuery(key.toString().uppercase(), listOf(target.toString()), encoded) }
        assertThrows<ComplaintValidationException> { ComplaintOwnerDeleteStatusQuery(key.toString(), listOf(target.toString().uppercase()), encoded) }
        assertEquals("ComplaintOwnerDeleteStatusQuery(redacted)", query.toString())
    }

    @Test
    fun `tuple snapshots every identity dimension and never accepts LIVE or a wrong digest width`() {
        val original = HexFormat.of().parseHex("3e065f48addf6a79cafb2bf0d9d9a12c742366b57d2276e24ca3b017ebc4e06b")
        val supplied = original.copyOf()
        val tuple = ComplaintOwnerDeleteTuple(actor, key, target, supplied)
        supplied.fill(0)
        tuple.fingerprintBytes().fill(0)
        assertArrayEquals(original, tuple.fingerprintBytes())
        assertTrue(tuple.matches(ComplaintOwnerDeleteTuple(actor, key, target, original)))
        val changed = listOf(
            ComplaintOwnerDeleteTuple(ScopedInstallationId(key, scope), key, target, original),
            ComplaintOwnerDeleteTuple(ScopedInstallationId(actor.id, ComplaintDataScope.of(key)), key, target, original),
            ComplaintOwnerDeleteTuple(actor, actor.id, target, original),
            ComplaintOwnerDeleteTuple(actor, key, actor.id, original),
            ComplaintOwnerDeleteTuple(actor, key, target, ByteArray(32)),
        )
        changed.forEach { assertFalse(tuple.matches(it)) }
        assertThrows<IllegalArgumentException> { ComplaintOwnerDeleteTuple(ScopedInstallationId(actor.id, ComplaintDataScope.LIVE), key, target, original) }
        for (size in listOf(0, 31, 33)) assertThrows<IllegalArgumentException> { ComplaintOwnerDeleteTuple(actor, key, target, ByteArray(size)) }
        assertEquals("OWNER_DELETE", ComplaintOwnerDeleteTuple.OPERATION)
        assertEquals("ComplaintOwnerDeleteTuple(redacted)", tuple.toString())
    }

    @Test
    fun `delete receipts are a closed historical union with no resource version or accepted 202 branch`() {
        assertSame(ComplaintOwnerDeleteReceipt.Applied, ComplaintOwnerDeleteReceipt.Applied)
        assertEquals("ComplaintOwnerDeleteReceipt(redacted)", ComplaintOwnerDeleteReceipt.Applied.toString())
        assertEquals(
            mapOf("COMPLAINT_NOT_FOUND" to 404, "COMPLAINT_DELETION_PENDING" to 409, "PRECONDITION_FAILED" to 412),
            ComplaintOwnerDeleteRejection.entries.associate { it.name to it.status },
        )
        for (code in ComplaintOwnerDeleteRejection.entries) {
            val receipt = ComplaintOwnerDeleteReceipt.Rejected(code)
            assertEquals(code.status, receipt.status)
            assertEquals(code.name, receipt.problemCode)
        }
        assertEquals("ComplaintOwnerDeleteRequest(redacted)", request().toString())
        assertEquals("ComplaintOwnerDeleteFingerprint(redacted)", ComplaintOwnerDeleteFingerprint.of(request()).toString())
    }

    @Test
    fun `authorization and immutable four candidate recovery promise match every frozen counter slot`() {
        // Explicit stored-ordinal vectors, independent of product charge composition and enum ordering.
        val rejection = longArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 131072, 0)
        val actual = longArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 475136, 0)
        val future = longArrayOf(0, 5, 0, 0, 0, 0, 0, 1, 0, 4, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 491520, 0)
        val required = longArrayOf(0, 6, 0, 0, 0, 0, 0, 1, 0, 4, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 966656, 0)
        assertEquals(4, OwnerDeleteCapacityCharges.MAX_RETAINED_CANDIDATES)
        assertArrayEquals(rejection, OwnerDeleteCapacityCharges.RECEIPT.toLongArray())
        assertArrayEquals(actual, OwnerDeleteCapacityCharges.AUTHORIZATION.toLongArray())
        assertArrayEquals(future, OwnerDeleteCapacityCharges.RECOVERY.toLongArray())
        assertArrayEquals(required, (OwnerDeleteCapacityCharges.AUTHORIZATION + OwnerDeleteCapacityCharges.RECOVERY).toLongArray())
        OwnerDeleteCapacityCharges.RECOVERY.toLongArray().fill(0)
        assertArrayEquals(future, OwnerDeleteCapacityCharges.RECOVERY.toLongArray())
        assertEquals(0L, OwnerDeleteCapacityCharges.RECOVERY[ComplaintCapacityCounter.JOURNAL_RETIREMENTS])
        assertEquals(0L, OwnerDeleteCapacityCharges.RECOVERY[ComplaintCapacityCounter.APP_INSTALLATIONS])
        assertEquals(0L, OwnerDeleteCapacityCharges.AUTHORIZATION[ComplaintCapacityCounter.TEST_RUNS])
    }

    @Test
    fun `ordinary measured apply spends only 96 KiB and retains the separate 384 KiB promise`() {
        val spent = ComplaintCapacityVector.of(longArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 98304, 0))
        val expected = longArrayOf(0, 4, 0, 0, 0, 0, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 393216, 0)
        assertArrayEquals(expected, (OwnerDeleteCapacityCharges.RECOVERY - spent).toLongArray())
        assertEquals(spent, ComplaintCapacityCharges.AUDIT + OwnerDeleteCapacityCharges.APPLIED)
        assertEquals(262144L, ComplaintCapacityCharges.INSTALLATION_CONTENT_V1[ComplaintCapacityCounter.STORAGE_BYTES])
        // Arithmetic is not an issuer of PARTIAL conversion or ordinary-range quiescence.
        assertFalse((OwnerDeleteCapacityCharges.RECOVERY - spent).isZero())
    }

    private fun request(
        version: Long = 42,
        selectedKey: UUID = key,
        selectedTarget: UUID = target,
        selectedScope: ComplaintDataScope = scope,
    ): ComplaintOwnerDeleteRequest = ComplaintOwnerDeleteRequest.normalize(
        selectedScope,
        ComplaintOwnerDeleteInput(
            selectedTarget,
            selectedKey,
            ComplaintOwnerDeletePrecondition.parse(selectedTarget, "\"complaint-$selectedTarget-v$version\""),
        ),
    )
}

/** Read only the three independently supplied literal resources; raw provenance labels are never opened. */
internal fun ownerDeleteLiteralResource(name: String): JsonObject {
    require(name in setOf("inputs.json", "request-vectors.json", "journal-vectors.json"))
    val bytes = checkNotNull(ComplaintOwnerDeleteContractTest::class.java.getResourceAsStream("/complaint/owner-delete-v1/$name")).use { it.readBytes() }
    return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
}

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
