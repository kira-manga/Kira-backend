package me.manga.kira.backend.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.aws.AwsTestOwnerDeleteDataKeyAdapterV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Closed synthetic declarations and raw SDK transport only; not AUTH, registration or activation. */
class TestAdminBatchDeleteJournalCodecV1Test {
    private val journal = adminBatchDeleteTestJournal()
    private val routing = ownerDeleteTestRouting(journal)
    private val noKeys = NeverOwnerDeleteAllDataKeys()
    private val codec = TestOwnerDeleteJournalCodecV1(routing, noKeys)
    private val actor = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val owners = listOf(UUID.fromString("22222222-2222-4222-8222-222222222222"), UUID.fromString("77777777-7777-4777-8777-777777777777"))
    private val key = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val grant = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val ids = listOf(UUID.fromString("123e4567-e89b-52d3-a456-426614174000"), UUID.fromString("fedcba98-7654-4321-8fed-cba987654321"))
    private fun tuple(selectedOwners: List<UUID> = owners, selectedGrant: UUID = grant) =
        TestAdminBatchDeleteJournalTupleV1(17, actor, key, ByteArray(32) { 7 }, journal.scope, selectedGrant, selectedOwners)

    @Test
    fun newExplicitLowerAndRegisteredBatchProfilesNeverRelabelOldDeclarationsOrScalarWire() {
        assertEquals("LOWER_TEST_ADMIN_BATCH_ERASURE", journal.profile)
        assertTrue(journal.adminBatchDelete && journal.adminDelete && journal.ownerDeleteAll)
        assertFalse(journal.registeredAdminBatchDelete)
        val registered = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(journal.declaration())
        assertEquals("REGISTERED_TEST_ADMIN_BATCH_ERASURE", registered.profile)
        assertTrue(registered.registeredAdminDelete && registered.registeredAdminBatchDelete)
        assertEquals(journal.document().copy(profile = registered.profile), registered.document())
        assertArrayEquals(registered.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.fromDocument(registered.document()).canonicalBytes())
        assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.fromDocument(journal.document()) }
        val oldLower = adminDeleteTestJournal(journal.scope)
        val oldRegistered = TestOwnerDeleteJournalConfigurationV1.registeredAdminErasure(journal.declaration())
        assertEquals(oldLower.document().copy(profile = oldRegistered.profile), oldRegistered.document())
        assertFalse(oldLower.adminBatchDelete || oldRegistered.adminBatchDelete)
        assertNotEquals(oldLower.sha256, journal.sha256); assertNotEquals(oldRegistered.sha256, registered.sha256)
        assertArrayEquals(oldRegistered.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.fromDocument(oldRegistered.document()).canonicalBytes())
        assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.fromDocument(registered.document().copy(profile = oldRegistered.profile)) }
        assertThrows<IllegalArgumentException> { ownerDeleteTestRouting(oldRegistered).derive(tuple()) }
        val scalar = TestAdminDeleteJournalTupleV1(17, actor, key, ByteArray(32) { 7 }, journal.scope, grant, owners.first())
        val oldEvent = TestOwnerDeleteJournalCodecV1(ownerDeleteTestRouting(oldLower), noKeys).canonicalizeAdmin(scalar, ids.first())
        val event = codec.canonicalizeAdmin(scalar, ids.first())
        assertArrayEquals(oldEvent.canonicalBytes(), event.canonicalBytes()); assertEquals(oldEvent.route, event.route)
        val batchOne = codec.canonicalizeAdminBatch(tuple(owners.take(1)), ids.take(1))
        assertNotEquals(event.route, batchOne.route)
        assertThrows<OwnerDeleteAllJournalException> { TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(routing, batchOne.canonicalBytes(), batchOne.route.routingKeyId) }
    }

    @Test
    fun completeAuthenticatedOwnerAndTargetSetsAreStrictBoundedImmutableAndNotScalarAliases() {
        val mutableOwners = owners.toMutableList()
        val selected = tuple(mutableOwners)
        mutableOwners.clear()
        assertEquals(owners, selected.ownerInstallationIds())
        assertThrows<UnsupportedOperationException> { (selected.ownerInstallationIds() as MutableList<*>).clear() }
        val event = codec.canonicalizeAdminBatch(selected, ids)
        val body = Json.parseToJsonElement(event.canonicalBytes().decodeToString()).jsonObject
        assertEquals(TestAdminDeleteJournalJsonV1.FIELDS, body.keys)
        assertEquals(JsonPrimitive("ADMIN_BATCH_DELETE"), body["eventKind"])
        assertEquals(JsonArray(owners.map { JsonPrimitive(it.toString()) }), body["ownerInstallationIds"])
        assertEquals(JsonArray(ids.map { JsonPrimitive(it.toString()) }), body["complaintIds"])
        assertNull(event.adminBatchTuple.credentialVersion)
        assertThrows<OwnerDeleteAllJournalException> { event.adminTuple }
        assertThrows<OwnerDeleteAllJournalException> { event.tuple }
        assertArrayEquals(event.canonicalBytes(), TestOwnerDeleteJournalCodecV1.restoreAdminBatchCanonical(routing, event.canonicalBytes(), event.route.routingKeyId).canonicalBytes())
        assertThrows<OwnerDeleteAllJournalException> { TestOwnerDeleteJournalCodecV1.restoreCanonical(routing, event.canonicalBytes(), event.route.routingKeyId) }
        assertThrows<OwnerDeleteAllJournalException> { TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(routing, event.canonicalBytes(), event.route.routingKeyId) }
        val invalid = listOf(
            body + ("credentialVersion" to JsonPrimitive(1)), body + ("eventKind" to JsonPrimitive("ADMIN_DELETE")),
            body + ("ownerInstallationIds" to JsonArray(emptyList())),
            body + ("ownerInstallationIds" to JsonArray(owners.reversed().map { JsonPrimitive(it.toString()) })),
            body + ("complaintIds" to JsonArray(ids.reversed().map { JsonPrimitive(it.toString()) })),
            body + ("complaintIds" to JsonArray(List(2) { JsonPrimitive(ids.first().toString()) })),
            body + ("complaintIds" to JsonArray(List(51) { JsonPrimitive(UUID(0, it.toLong()).toString()) })),
        )
        invalid.forEach { fields -> assertThrows<OwnerDeleteAllJournalException> {
            TestOwnerDeleteJournalCodecV1.restoreAdminBatchCanonical(routing, JsonObject(fields.toSortedMap()).toString().toByteArray(), event.route.routingKeyId)
        } }
        assertThrows<OwnerDeleteAllJournalException> { codec.canonicalizeAdminBatch(selected, ids.take(1)) }
        assertThrows<OwnerDeleteAllJournalException> { codec.canonicalizeAdminBatch(selected, ids.reversed()) }
        val other = codec.canonicalizeAdminBatch(tuple(selectedGrant = UUID.randomUUID()), ids)
        assertEquals(event.route, other.route); assertFalse(event.canonicalBytes().contentEquals(other.canonicalBytes()))
        val maxOwners = List(50) { UUID.randomUUID() }.sortedBy(UUID::toString)
        val maximum = codec.canonicalizeAdminBatch(tuple(maxOwners), List(50) { UUID(0, it.toLong() + 1) })
        assertEquals(50, maximum.complaintIds().size); assertEquals(50, maximum.adminBatchTuple.ownerInstallationIds().size)
        assertEquals(0, noKeys.calls.get())
    }

    @Test
    fun registeredHeaderDispatchRejectsOwnerSingleAndOldProfilesBeforeKmsAndDecryptsNamedBatchExactlyOnce() {
        val registered = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(journal.declaration())
        val selectedRouting = ownerDeleteTestRouting(registered)
        val event = TestOwnerDeleteJournalCodecV1(selectedRouting, noKeys).canonicalizeAdminBatch(tuple(), ids)
        val wire = TestOwnerDeleteJournalPublisherFixture(selectedRouting, event)
        val bytes = wire.envelope()
        try {
            AwsTestOwnerDeleteDataKeyAdapterV1.withHttpFixture(registered, TestOwnerDeleteJournalPublisherFixture.CREDENTIALS, wire.kms::httpClient) { wire.nanos }.use { keys ->
                val decoder = TestOwnerDeleteJournalCodecV1(selectedRouting, keys, nanoTime = { wire.nanos })
                val bucket = registered.declaration().journalLocation.bucket
                val before = wire.decrypted()
                assertThrows<OwnerDeleteAllJournalException> { decoder.open(bucket, event.route.objectKey, bytes, decoder.startAttempt()) }
                assertThrows<OwnerDeleteAllJournalException> { decoder.openAdmin(bucket, event.route.objectKey, bytes, decoder.startAttempt()) }
                val old = TestOwnerDeleteJournalCodecV1(ownerDeleteTestRouting(TestOwnerDeleteJournalConfigurationV1.registeredAdminErasure(journal.declaration())), keys, nanoTime = { wire.nanos })
                assertThrows<OwnerDeleteAllJournalException> { old.openRegisteredOrdinary(bucket, event.route.objectKey, bytes, old.startAttempt()) }
                assertEquals(before, wire.decrypted())
                val opened = decoder.openRegisteredOrdinary(bucket, event.route.objectKey, bytes, decoder.startAttempt())
                assertEquals(before + 1, wire.decrypted())
                assertArrayEquals(event.canonicalBytes(), opened.event.canonicalBytes())
                assertEquals(owners, opened.event.adminBatchTuple.ownerInstallationIds())
                val explicit = decoder.openAdminBatch(bucket, event.route.objectKey, bytes, decoder.startAttempt())
                assertEquals(before + 2, wire.decrypted()); assertArrayEquals(event.canonicalBytes(), explicit.event.canonicalBytes())
            }
        } finally { bytes.fill(0) }
        wire.assertClientsClosed(); assertEquals(0, noKeys.calls.get())
    }
}
