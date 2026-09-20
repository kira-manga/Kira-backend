package me.manga.kira.backend.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.ownerDeleteLiteralResource
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Synthetic content/transport declarations only; no SQL authorization or registered profile evidence. */
class TestAdminDeleteJournalCodecV1Test {
    private val journal = adminDeleteTestJournal()
    private val routing = ownerDeleteTestRouting(journal)
    private val noKeys = NeverOwnerDeleteAllDataKeys()
    private val codec = TestOwnerDeleteJournalCodecV1(routing, noKeys)
    private val actor = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val owner = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val key = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val grant = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val target = UUID.fromString("55555555-5555-4555-8555-555555555555")
    private fun tuple(grantId: UUID = grant, ownerId: UUID = owner) = TestAdminDeleteJournalTupleV1(17, actor, key, ByteArray(32) { 7 }, journal.scope, grantId, ownerId)

    @Test
    fun `one explicit lower Admin declaration never silently reinterprets either registered owner J`() {
        assertTrue(journal.adminDelete); assertTrue(journal.ownerDeleteAll)
        assertEquals("LOWER_TEST_ADMIN_ERASURE", journal.document().profile)
        assertEquals("KJEV-1/OWNER_DELETE+OWNER_DELETE_ALL+ADMIN_DELETE/INSTALLATION+ADMIN/TEST/LP32BE-UTF8/HMAC-SHA-256/AES-256-GCM/FRESH_PER_OBJECT_KMS_WRAPPED", journal.document().protocol)
        assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.fromDocument(journal.document()) }
        for (all in listOf(false, true)) {
            val old = ownerDeleteTestJournal(ownerDeleteAll = all)
            assertFalse(old.adminDelete)
            assertEquals(if (all) "REGISTERED_TEST_OWNER_ERASURE" else "REGISTERED_TEST_OWNER_DELETE", old.document().profile)
            assertArrayEquals(old.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.of(journal.declaration(), all).canonicalBytes())
            assertArrayEquals(old.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.fromDocument(old.document()).canonicalBytes())
            assertNotEquals(old.sha256, journal.sha256)
            assertThrows<IllegalArgumentException> { ownerDeleteTestRouting(old).derive(tuple()) }
            assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.fromDocument(journal.document().copy(profile = old.document().profile)) }
        }
    }

    @Test
    fun `registered Admin J is an explicit distinct roundtrip and never makes the lower declaration registerable`() {
        val registered = TestOwnerDeleteJournalConfigurationV1.registeredAdminErasure(journal.declaration())
        assertTrue(registered.adminDelete && registered.ownerDeleteAll && registered.registeredAdminDelete)
        assertFalse(journal.registeredAdminDelete)
        assertEquals("REGISTERED_TEST_ADMIN_ERASURE", registered.document().profile)
        assertEquals(journal.document().protocol, registered.document().protocol)
        assertEquals(journal.document().copy(profile = registered.profile), registered.document())
        assertNotEquals(journal.sha256, registered.sha256)
        assertArrayEquals(registered.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.fromDocument(registered.document()).canonicalBytes())
        assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.fromDocument(journal.document()) }
        listOf("REGISTERED_TEST_OWNER_DELETE", "REGISTERED_TEST_OWNER_ERASURE", "REGISTERED_TEST_ADMIN_DELETE", "LIVE").forEach { wrong ->
            assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.fromDocument(registered.document().copy(profile = wrong)) }
        }
        // The pre-existing independent owner literals and roundtrip checks above still use their
        // original constructor; adding this profile does not rewrite either older J document.
        registered.canonicalBytes().fill(0)
        assertArrayEquals(registered.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.fromDocument(registered.document()).canonicalBytes())
    }

    @Test
    fun `registered native decoder selects each closed family once before KMS and refuses lower Admin intake`() {
        val registered = TestOwnerDeleteJournalConfigurationV1.registeredAdminErasure(journal.declaration())
        val selectedRouting = ownerDeleteTestRouting(registered)
        val selectedCodec = TestOwnerDeleteJournalCodecV1(selectedRouting, noKeys)
        val events = listOf(
            selectedCodec.canonicalize(TestOwnerDeleteJournalTupleV1(17, owner, 1, key, ByteArray(32) { 7 }, registered.scope), listOf(target)),
            selectedCodec.canonicalize(TestOwnerDeleteJournalTupleV1(17, owner, 1, key, ByteArray(32) { 7 }, registered.scope,
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL), emptyList()),
            selectedCodec.canonicalizeAdmin(tuple(), target),
        )
        events.forEach { event ->
            val transport = TestOwnerDeleteJournalPublisherFixture(selectedRouting, event)
            val bytes = transport.envelope()
            try {
                AwsTestOwnerDeleteDataKeyAdapterV1.withHttpFixture(registered, TestOwnerDeleteJournalPublisherFixture.CREDENTIALS,
                    transport.kms::httpClient) { transport.nanos }.use { keys ->
                    val decoder = TestOwnerDeleteJournalCodecV1(selectedRouting, keys, nanoTime = { transport.nanos })
                    val before = transport.decrypted()
                    val opened = decoder.openRegisteredOrdinary(registered.declaration().journalLocation.bucket,
                        event.route.objectKey, bytes, decoder.startAttempt())
                    assertEquals(before + 1, transport.decrypted(), "One named decoder and one AEAD, never trial decryption.")
                    assertArrayEquals(event.canonicalBytes(), opened.event.canonicalBytes())
                    assertEquals(event.comparison.eventKind, opened.event.comparison.eventKind)
                }
            } finally { bytes.fill(0) }
            transport.assertClientsClosed()
        }
        val lower = codec.canonicalizeAdmin(tuple(), target)
        val transport = TestOwnerDeleteJournalPublisherFixture(routing, lower)
        val bytes = transport.envelope()
        try {
            AwsTestOwnerDeleteDataKeyAdapterV1.withHttpFixture(journal, TestOwnerDeleteJournalPublisherFixture.CREDENTIALS,
                transport.kms::httpClient) { transport.nanos }.use { keys ->
                val decoder = TestOwnerDeleteJournalCodecV1(routing, keys, nanoTime = { transport.nanos })
                val before = transport.decrypted()
                assertThrows<OwnerDeleteAllJournalException> {
                    decoder.openRegisteredOrdinary(journal.declaration().journalLocation.bucket, lower.route.objectKey, bytes, decoder.startAttempt())
                }
                assertEquals(before, transport.decrypted(), "Lower J cannot become registration/inventory authority.")
            }
        } finally { bytes.fill(0) }
        transport.assertClientsClosed()
        assertEquals(0, noKeys.calls.get())
    }

    @Test
    fun `canonical Admin payload separates current actor original grant and resolved owner without credential slot`() {
        val tuple = tuple()
        val event = codec.canonicalizeAdmin(tuple, target)
        val expected = JsonObject(sortedMapOf(
            "schemaVersion" to JsonPrimitive(1), "eventKind" to JsonPrimitive("ADMIN_DELETE"), "eventId" to JsonPrimitive(event.route.eventId),
            "publicationEpoch" to JsonPrimitive(17), "writerGeneration" to JsonPrimitive(journal.declaration().writer.generationId),
            "actorKind" to JsonPrimitive("ADMIN"), "actorId" to JsonPrimitive(actor.toString()), "operationKey" to JsonPrimitive(key.toString()),
            "requestFingerprint" to JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })),
            "consumedGrantId" to JsonPrimitive(grant.toString()), "ownerInstallationIds" to JsonArray(listOf(JsonPrimitive(owner.toString()))),
            "dataScopeKind" to JsonPrimitive("TEST"), "dataScopeId" to JsonPrimitive(journal.scope.id.toString()),
            "complaintIds" to JsonArray(listOf(JsonPrimitive(target.toString()))),
        )).toString().toByteArray()
        assertArrayEquals(expected, event.canonicalBytes())
        assertNull(event.adminTuple.credentialVersion)
        assertThrows<OwnerDeleteAllJournalException> { event.tuple }
        assertEquals(0, noKeys.calls.get())
        assertArrayEquals(expected, TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(routing, expected, event.route.routingKeyId).canonicalBytes())
        assertThrows<OwnerDeleteAllJournalException> { TestOwnerDeleteJournalCodecV1.restoreCanonical(routing, expected, event.route.routingKeyId) }
        val text = expected.decodeToString()
        for (invalid in listOf(text.replace("{", "{\"credentialVersion\":1,"), text.replace("{", "{\"unknown\":\"x\","),
            text.replace("\"actorKind\":\"ADMIN\"", "\"actorKind\":\"INSTALLATION\""),
            text.replace("\"ownerInstallationIds\":[\"$owner\"]", "\"ownerInstallationIds\":[]"),
            text.replace("\"complaintIds\":[\"$target\"]", "\"complaintIds\":[\"$target\",\"$target\"]"),
            text.replace("\"consumedGrantId\":\"$grant\",", ""), text + " ")) {
            assertThrows<OwnerDeleteAllJournalException> { TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(routing, invalid.toByteArray(), event.route.routingKeyId) }
        }
        // These fields are payload comparisons, not routing identity; exact readback must compare them.
        val other = codec.canonicalizeAdmin(tuple(UUID.randomUUID(), UUID.randomUUID()), target)
        assertEquals(event.route, other.route); assertFalse(event.canonicalBytes().contentEquals(other.canonicalBytes()))
    }

    @Test
    fun `independent Admin HMAC frame has an empty credential field never null or fabricated installation version`() {
        val tuple = tuple()
        val inputs = ownerDeleteLiteralResource("inputs.json").getValue("journal_routes").jsonArray.map { it.jsonObject }
        for (route in routing.derive(tuple).candidates()) {
            val keyBytes = HexFormat.of().parseHex(inputs.single { it.getValue("routing_key_id").jsonPrimitive.content == route.routingKeyId }
                .getValue("hmac_key_hex").jsonPrimitive.content)
            fun digest(domain: String): String {
                val fields = listOf(domain, "1", journal.declaration().writer.generationId, journal.ordinaryPrefix, "TEST", journal.scope.id.toString(),
                    "17", route.routingKeyId, "ADMIN_DELETE", "ADMIN", actor.toString(), "", key.toString(), tuple.encodedFingerprint())
                val frame = ByteArrayOutputStream()
                DataOutputStream(frame).use { out -> fields.forEach { field -> val bytes = field.toByteArray(); out.writeInt(bytes.size); out.write(bytes) } }
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keyBytes, "HmacSHA256")) }.doFinal(frame.toByteArray())
                return Base64.getUrlEncoder().withoutPadding().encodeToString(mac)
            }
            assertEquals(digest("kira-complaint-journal-event-id-v1"), route.eventId)
            assertEquals(digest("kira-complaint-journal-routing-v1"), route.objectKey.substringAfterLast('/'))
        }
    }

    @Test
    fun `real shared envelope opens only through explicit Admin decoder and a corrupted tag never authenticates`() {
        val event = codec.canonicalizeAdmin(tuple(), target)
        val wire = TestOwnerDeleteJournalPublisherFixture(routing, event)
        val bytes = wire.envelope()
        AwsTestOwnerDeleteDataKeyAdapterV1.withHttpFixture(journal, TestOwnerDeleteJournalPublisherFixture.CREDENTIALS, wire.kms::httpClient) { wire.nanos }.use { keys ->
            val decoder = TestOwnerDeleteJournalCodecV1(routing, keys, nanoTime = { wire.nanos })
            val bucket = journal.declaration().journalLocation.bucket
            val before = wire.decrypted()
            assertThrows<OwnerDeleteAllJournalException> { decoder.open(bucket, event.route.objectKey, bytes, decoder.startAttempt()) }
            assertEquals(before, wire.decrypted(), "Owner decoder must refuse the Admin header before KMS")
            val opened = decoder.openAdmin(bucket, event.route.objectKey, bytes, decoder.startAttempt())
            assertArrayEquals(event.canonicalBytes(), opened.event.canonicalBytes())
            assertEquals(grant, opened.event.adminTuple.consumedGrantId)
            val corrupted = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertEquals(OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED, assertThrows<OwnerDeleteAllJournalException> {
                decoder.openAdmin(bucket, event.route.objectKey, corrupted, decoder.startAttempt())
            }.code)
        }
        wire.assertClientsClosed()
    }

    @Test
    fun `existing independent owner canonical and routing literals remain exact under lower composition`() {
        val vector = ownerDeleteLiteralResource("journal-vectors.json").getValue("vectors").jsonArray.first().jsonObject
        val literal = vector.getValue("payload").jsonObject.getValue("canonical_utf8").jsonPrimitive.content
        val payload = Json.parseToJsonElement(literal).jsonObject
        fun text(name: String) = payload.getValue(name).jsonPrimitive.content
        val tuple = TestOwnerDeleteJournalTupleV1(17, UUID.fromString(text("actorId")), 9, UUID.fromString(text("operationKey")),
            Base64.getUrlDecoder().decode(text("requestFingerprint")), ComplaintDataScope.of(UUID.fromString(text("dataScopeId"))))
        val event = codec.canonicalize(tuple, payload.getValue("complaintIds").jsonArray.map { UUID.fromString(it.jsonPrimitive.content) })
        assertArrayEquals(literal.toByteArray(), event.canonicalBytes())
        assertEquals(vector.getValue("object_key").jsonPrimitive.content, event.route.objectKey)
        assertThrows<OwnerDeleteAllJournalException> { TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(routing, event.canonicalBytes(), event.route.routingKeyId) }
    }
}
