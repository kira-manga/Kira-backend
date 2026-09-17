package me.manga.kira.backend.common.infrastructure.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllVerificationV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllVerificationSql
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationExceptionV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID

/** Corrupt only comparison storage/HTTP fixture data, never construct or reflect an authoritative readback. */
internal fun assertOwnerDeleteAllVerificationCorruption(f: OwnerDeleteAllVerificationFixture) {
    val readback = f.readback()
    val committed = f.phases.verify(readback)
    val bytes = committed.verificationBytes()
    val original = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    assertEquals(VERIFICATION_FIELDS, original.keys)
    for ((label, corrupt) in corruptVerificationBytes(original, bytes)) {
        val parserFailure = assertThrows<OwnerDeleteAllVerificationExceptionV1> { f.codec.parse(corrupt, readback.event) }
        assertNull(parserFailure.cause)
        assertTrue(parserFailure.suppressed.isEmpty())
        replaceVerificationBytes(f, corrupt) // Matching SHA is intentional: V14 shape/hash alone is not the verification parser.
        try {
            val before = f.auth.state()
            f.statements.clear()
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertEquals(before, f.auth.state(), label)
            f.assertOnlyVerificationStatements(write = false)
            f.assertReleased()
        } finally {
            replaceVerificationBytes(f, bytes)
        }
    }

    // Fully canonical bytes with a changed observed value exercise scalar equality rather than grammar rejection.
    for ((name, value) in listOf(
        "objectVersion" to JsonPrimitive("different-valid-version"),
        "ciphertextSha256" to JsonPrimitive("ee".repeat(32)),
        "objectCreatedAt" to JsonPrimitive(committed.objectCreatedAt.minusSeconds(1).toString()),
        "retainUntil" to JsonPrimitive(committed.retainUntil.plusSeconds(1).toString()),
        "verifiedAt" to JsonPrimitive(committed.verifiedAt.plusNanos(1000).toString()),
    )) {
        replaceVerificationBytes(f, canonical(original + (name to value)))
        try {
            val before = f.auth.state()
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertEquals(before, f.auth.state(), name)
        } finally {
            replaceVerificationBytes(f, bytes)
        }
    }

    for (assignment in listOf(
        "object_version = 'different-valid-version'",
        "ciphertext_hash = decode(repeat('ee', 32), 'hex')",
        "object_created_at = object_created_at - interval '1 second'",
        "object_created_at = object_created_at + interval '1 microsecond'",
        "retain_until = retain_until + interval '1 second'",
        "verified_at = verified_at + interval '1 microsecond'",
        "state = 'APPLIED', applied_at = clock_timestamp()", // Synthetic terminal-row corruption, not an APPLY operation.
    )) {
        assertEquals(1, f.auth.observer.update("UPDATE complaint_journal_publications SET $assignment WHERE event_id = ?", committed.eventId))
        try {
            val before = f.auth.state()
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertEquals(before, f.auth.state(), assignment)
        } finally {
            restoreVerification(f, committed)
        }
    }

    // Even a self-consistent forged local version/hash/creation cannot override the private publisher's immutable facts.
    for ((name, value, scalar) in listOf(
        Triple("objectVersion", JsonPrimitive("different-valid-version"), "object_version = 'different-valid-version'"),
        Triple("ciphertextSha256", JsonPrimitive("ee".repeat(32)), "ciphertext_hash = decode(repeat('ee', 32), 'hex')"),
        Triple("objectCreatedAt", JsonPrimitive(committed.objectCreatedAt.minusSeconds(1).toString()), "object_created_at = object_created_at - interval '1 second'"),
    )) {
        replaceVerificationBytes(f, canonical(original + (name to value)))
        f.auth.observer.update("UPDATE complaint_journal_publications SET $scalar WHERE event_id = ?", committed.eventId)
        try {
            val before = f.auth.state()
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertEquals(before, f.auth.state())
        } finally {
            restoreVerification(f, committed)
        }
    }

    val before = f.auth.state()
    assertThrows<DataIntegrityViolationException> {
        f.auth.observer.update("UPDATE complaint_journal_publications SET verification_hash = ? WHERE event_id = ?", ByteArray(32) { 99 }, committed.eventId)
    }
    val oversized = ByteArray(65_537) { ' '.code.toByte() }
    assertThrows<OwnerDeleteAllVerificationExceptionV1> { f.codec.parse(oversized, readback.event) }
    assertThrows<DataIntegrityViolationException> { replaceVerificationBytes(f, oversized) }
    assertEquals(before, f.auth.state()) // V14 rejects hash/size corruption before a reader could accept it.
    f.assertReleased()
}

internal fun assertOwnerDeleteAllVerificationFrozenIdentity(f: OwnerDeleteAllVerificationFixture) {
    val readback = f.readback()
    val event = readback.event
    val eventId = event.route.eventId
    val original = f.auth.state()
    for ((assignment, undo) in listOf(
        "writer_generation = '${UUID.randomUUID()}'::uuid" to "writer_generation = '${f.auth.routing.journalConfiguration.declaration().writer.generationId}'::uuid",
        "journal_epoch = journal_epoch + 1" to "journal_epoch = journal_epoch - 1",
        "target_count = 2" to "target_count = 1",
        "event_kind = 'OWNER_DELETE'" to "event_kind = 'OWNER_DELETE_ALL'",
        "routing_key_id = 'route-a'" to "routing_key_id = '${event.route.routingKeyId}'",
        "object_key = 'synthetic-other-key'" to "object_key = '${event.route.objectKey}'",
        "created_at = created_at + interval '1 microsecond'" to "created_at = created_at - interval '1 microsecond'",
    )) {
        f.auth.observer.update("UPDATE complaint_journal_publications SET $assignment WHERE event_id = ?", eventId)
        try {
            val before = f.auth.state()
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertEquals(before, f.auth.state(), assignment)
        } finally {
            f.auth.observer.update("UPDATE complaint_journal_publications SET $undo WHERE event_id = ?", eventId)
        }
    }
    val canonical = event.canonicalBytes()
    for (changed in listOf(canonical + byteArrayOf(' '.code.toByte()), canonical.toString(Charsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray())) {
        replaceEventBytes(f, changed)
        try {
            val before = f.auth.state()
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertEquals(before, f.auth.state())
        } finally {
            replaceEventBytes(f, canonical)
        }
    }

    for ((assignment, undo) in listOf(
        "deletion_key = '${UUID.randomUUID()}'::uuid" to "deletion_key = '${f.candidate.operationKey}'::uuid",
        "submitted_credential_version = submitted_credential_version + 1" to "submitted_credential_version = submitted_credential_version - 1",
        "fingerprint = decode(repeat('aa', 32), 'hex')" to "fingerprint = decode('${HexFormat.of().formatHex(ComplaintDeleteAllFingerprint.of(f.candidate).bytes())}', 'hex')",
        "authorized_at = authorized_at + interval '1 microsecond'" to "authorized_at = authorized_at - interval '1 microsecond'",
    )) {
        f.auth.observer.update("UPDATE installation_deletion_receipts SET $assignment WHERE installation_id = ?", f.candidate.installation.id)
        try {
            val before = f.auth.state()
            assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
            assertEquals(before, f.auth.state(), assignment)
        } finally {
            f.auth.observer.update("UPDATE installation_deletion_receipts SET $undo WHERE installation_id = ?", f.candidate.installation.id)
        }
    }

    val extraKey = UUID.randomUUID()
    assertEquals(
        1,
        f.auth.observer.update(
            "INSERT INTO installation_deletion_receipts (installation_id, deletion_key, submitted_credential_version, fingerprint, " +
                "data_scope_id, test_only, state, publication_ref, created_at, authorized_at) " +
                "SELECT installation_id, ?, submitted_credential_version, fingerprint, data_scope_id, test_only, state, publication_ref, created_at, authorized_at " +
                "FROM installation_deletion_receipts WHERE installation_id = ? AND deletion_key = ?",
            extraKey, f.candidate.installation.id, f.candidate.operationKey,
        ),
    )
    try {
        val before = f.auth.state()
        f.statements.clear()
        assertVerificationRolledBack(assertThrows { f.phases.verify(readback) })
        assertEquals(before, f.auth.state())
        assertEquals(listOf(OwnerDeleteAllVerificationSql.LOCK_RECEIPTS), f.statements.toList())
    } finally {
        f.auth.observer.update("DELETE FROM installation_deletion_receipts WHERE installation_id = ? AND deletion_key = ?", f.candidate.installation.id, extraKey)
    }
    assertEquals(original, f.auth.state())
    f.phases.verify(readback) // Every negative control restored the original genuine PREPARED continuation.
    f.assertReleased()
}

private fun corruptVerificationBytes(original: JsonObject, bytes: ByteArray): List<Pair<String, ByteArray>> {
    val cases = mutableListOf<Pair<String, ByteArray>>()
    original.keys.forEach { field ->
        cases.add("missing-$field" to canonical(original - field))
        cases.add("null-$field" to canonical(original + (field to JsonNull)))
    }
    val values: List<Pair<String, JsonElement>> = listOf(
        "unknown" to JsonPrimitive("not-in-v1"), "schema" to JsonPrimitive(2), "schema" to JsonPrimitive("1"),
        "testOnly" to JsonPrimitive("false"), "testOnly" to JsonPrimitive(true), "journalEpoch" to JsonPrimitive(0),
        "journalEpoch" to JsonPrimitive("11"), "journalEpoch" to JsonPrimitive(12), "eventKind" to JsonPrimitive("OWNER_DELETE"),
        "dataScopeId" to JsonPrimitive(UUID.randomUUID().toString()), "journalConfigurationSha256" to JsonPrimitive("aa".repeat(32)),
        "journalConfigurationSha256" to JsonPrimitive("AB".repeat(32)), "writerGeneration" to JsonPrimitive(UUID.randomUUID().toString()),
        "eventId" to JsonPrimitive("A".repeat(43)), "routingKeyId" to JsonPrimitive("not-retained"), "objectKey" to JsonPrimitive("wrong-key"),
        "semanticSha256" to JsonPrimitive("bb".repeat(32)), "objectVersion" to JsonPrimitive("null"), "objectVersion" to JsonPrimitive(""),
        "objectVersion" to JsonPrimitive("x".repeat(1025)), "objectVersion" to JsonPrimitive("version\u0001"),
        "ciphertextSha256" to JsonPrimitive("BB".repeat(32)), "objectLockMode" to JsonPrimitive("GOVERNANCE"),
        "objectCreatedAt" to JsonPrimitive("2030-01-02T03:04:05.000001Z"), "objectCreatedAt" to JsonPrimitive("1969-12-31T23:59:59Z"),
        "retainUntil" to JsonPrimitive("infinity"), "retainUntil" to JsonPrimitive("+10000-01-01T00:00:00Z"),
        "verifiedAt" to JsonPrimitive("2030-01-02T03:04:05.123456789Z"), "verifiedAt" to JsonPrimitive("2030-01-02T03:04:05.123456001Z"),
        "verifiedAt" to JsonPrimitive("2030-01-02T03:04:05.123456+00:00"),
        "verifiedAt" to JsonObject(mapOf("instant" to JsonPrimitive("2030-01-02T03:04:05Z"))),
    )
    values.forEachIndexed { index, (name, value) -> cases.add("field-$index-$name" to canonical(original + (name to value))) }
    val text = bytes.toString(Charsets.UTF_8)
    listOf(
        "leading-space" to " $text", "trailing-space" to "$text ", "trailing-document" to "$text{}",
        "duplicate-field" to text.dropLast(1) + ",\"schema\":1}",
        "float-integer" to text.replace("\"schema\":1", "\"schema\":1.0"),
        "exponent-integer" to text.replace("\"schema\":1", "\"schema\":1e0"),
        "overflow-integer" to text.replace("\"journalEpoch\":11", "\"journalEpoch\":9223372036854775808"),
        "negative-zero" to text.replace("\"schema\":1", "\"schema\":-0"),
        "equivalent-escaped-name" to text.replace("\"schema\"", "\"\\u0073chema\""),
        "unpaired-surrogate" to text.replace("\"objectVersion\":${original.getValue("objectVersion")}", "\"objectVersion\":\"\\ud800\""),
    ).forEach { (name, textValue) -> cases.add(name to textValue.toByteArray(Charsets.UTF_8)) }
    cases.add("invalid-UTF8" to (bytes + byteArrayOf(0xc3.toByte(), 0x28)))
    cases.add("UTF8-BOM" to (byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + bytes))
    return cases
}

private fun canonical(fields: Map<String, JsonElement>): ByteArray = CanonicalJson.canonicalize(JsonObject(fields)).toByteArray(Charsets.UTF_8)

private fun replaceVerificationBytes(f: OwnerDeleteAllVerificationFixture, bytes: ByteArray) {
    assertEquals(
        1,
        f.auth.observer.update(
            "UPDATE complaint_journal_publications SET verification_bytes = ?, verification_hash = sha256(?) WHERE event_id = ?",
            bytes, bytes, f.publisher.event.route.eventId,
        ),
    )
}

private fun replaceEventBytes(f: OwnerDeleteAllVerificationFixture, bytes: ByteArray) {
    assertEquals(
        1,
        f.auth.observer.update(
            "UPDATE complaint_journal_publications SET event_bytes = ?, semantic_hash = sha256(?) WHERE event_id = ?",
            bytes, bytes, f.publisher.event.route.eventId,
        ),
    )
}

internal fun restoreVerification(f: OwnerDeleteAllVerificationFixture, value: CommittedOwnerDeleteAllVerificationV1) {
    assertEquals(
        1,
        f.auth.observer.update(
            "UPDATE complaint_journal_publications SET state = 'VERIFIED', object_version = ?, ciphertext_hash = ?, object_created_at = ?, " +
                "retain_until = ?, verified_at = ?, verification_bytes = ?, verification_hash = ?, applied_at = NULL WHERE event_id = ?",
            value.objectVersion, HexFormat.of().parseHex(value.ciphertextSha256), Timestamp.from(value.objectCreatedAt),
            Timestamp.from(value.retainUntil), Timestamp.from(value.verifiedAt), value.verificationBytes(), value.verificationHash(), value.eventId,
        ),
    )
}

private val VERIFICATION_FIELDS = setOf(
    "schema", "eventKind", "dataScopeId", "testOnly", "journalConfigurationSha256", "eventId", "writerGeneration", "journalEpoch", "routingKeyId",
    "objectKey", "semanticSha256", "objectVersion", "ciphertextSha256", "objectCreatedAt", "objectLockMode", "retainUntil", "verifiedAt",
)
