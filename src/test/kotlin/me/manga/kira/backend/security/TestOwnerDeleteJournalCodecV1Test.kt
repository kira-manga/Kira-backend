package me.manga.kira.backend.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import me.manga.kira.backend.complaint.domain.JournalRoutingKeyV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalDeclarationV1
import me.manga.kira.backend.complaint.domain.ownerDeleteLiteralResource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Content/crypto checks only. Every identity, key and TEST declaration here is explicitly synthetic. */
class TestOwnerDeleteJournalCodecV1Test {
    private val vectors = ownerDeleteLiteralResource("journal-vectors.json").getValue("vectors").jsonArray.map { it.jsonObject }
    private val target = UUID.fromString("e5555555-5555-4555-8555-555555555555")

    @Test
    fun `all four retained ordinary candidates and separate min max profiles match independent HMAC and payload literals`() {
        assertEquals(listOf("ordinary_k1", "ordinary_k2", "ordinary_k3", "ordinary_k4", "minimum", "maximum"), vectors.map { it.text("id") })
        for (vector in vectors) {
            val env = env(vector)
            val journal = env.routing.journalConfiguration
            val tuple = tuple(vector)
            val routes = env.routing.derive(tuple)
            val selected = routes.candidates().single { it.routingKeyId == vector.text("routing_key_id") }
            assertEquals(vector.text("ordinary_prefix"), journal.ordinaryPrefix)
            assertEquals(vector.text("seal_terminal_prefix"), journal.sealTerminalPrefix)
            assertEquals(vector.text("object_key"), selected.objectKey)
            assertEquals(vector.getValue("event_id").jsonObject.text("hmac_sha256_base64url"), selected.eventId)
            assertEquals(vector.getValue("routing").jsonObject.text("hmac_sha256_base64url"), selected.objectKey.substringAfterLast('/'))
            assertNotEquals(selected.eventId, selected.objectKey.substringAfterLast('/'))
            assertEquals(if (vector.text("set") == "ordinary_four_retained_keys") 4 else 1, routes.candidates().size)
            assertEquals(if (vector.text("set") == "ordinary_four_retained_keys") "test-route-01" else vector.text("routing_key_id"), routes.active.routingKeyId)
            val event = env.codec.canonicalize(tuple, listOf(target), selected.routingKeyId)
            val literal = vector.getValue("payload").jsonObject
            assertArrayEquals(literal.text("canonical_utf8").toByteArray(), event.canonicalBytes())
            assertEquals(literal.text("sha256"), event.semanticSha256)
            assertEquals(listOf(target), event.complaintIds())
            assertEquals(selected, event.route)
            val restored = TestOwnerDeleteJournalCodecV1.restoreCanonical(env.routing, literal.text("canonical_utf8").toByteArray(), selected.routingKeyId)
            assertArrayEquals(event.canonicalBytes(), restored.canonicalBytes())
            assertEquals(0, env.keys.generateCalls)
            assertEquals(0, env.keys.unwrapCalls)
        }
    }

    @Test
    fun `TEST declarations bind their isolated run profile and reject LIVE extra keys and duplicate key material`() {
        val journal = ownerDeleteTestJournal()
        val declaration = journal.declaration()
        val doc = Json.parseToJsonElement(journal.canonicalBytes().toString(Charsets.UTF_8)).jsonObject
        assertEquals("REGISTERED_TEST_OWNER_DELETE", doc.text("profile"))
        assertEquals("TEST", doc.text("dataScopeKind"))
        assertEquals("b2222222-2222-4222-8222-222222222222", doc.text("dataScopeId"))
        val bytes = journal.canonicalBytes()
        journal.canonicalBytes().fill(0)
        journal.digestBytes().fill(0)
        assertArrayEquals(bytes, journal.canonicalBytes())
        assertEquals(journal.sha256, HexFormat.of().formatHex(journal.digestBytes()))
        assertTrue(journal.toString().contains("declaration-only"))
        assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(scope = ComplaintDataScope.LIVE)) }
        assertThrows<IllegalArgumentException> {
            TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(routing = declaration.routing.copy(keys = emptyList())))
        }
        assertThrows<IllegalArgumentException> {
            TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(routing = declaration.routing.copy(keys = declaration.routing.keys + declaration.routing.keys.first())))
        }
        assertThrows<IllegalArgumentException> {
            TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(routing = declaration.routing.copy(activeKeyId = "unknown")))
        }
        val acquired = ownerDeleteTestSecrets(journal)
        assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalRoutingV1.fromAcquired(journal, acquired.dropLast(1)) }
        assertThrows<IllegalArgumentException> {
            TestOwnerDeleteJournalRoutingV1.fromAcquired(
                journal,
                acquired.map { input -> AcquiredVersionedSecret.acquire(input.descriptor) { SecretVersionSnapshot(input.descriptor.version, ByteArray(32) { 7 }) } },
            )
        }
        val old = ComplaintJournalConfigurationV1.of(InitialLiveJournalTestFixture.declaration())
        assertNotEquals(old.sha256, journal.sha256)
        assertThrows<IllegalArgumentException> { VersionBoundComplaintJournalRouting.fromAcquired(old, acquired) }
    }

    @Test
    fun `two-family TEST profile is explicit and round trips while old J and literal DELETE bytes retain their meaning`() {
        val old = ownerDeleteTestJournal()
        val selected = ownerDeleteTestJournal(ownerDeleteAll = true)
        assertFalse(old.ownerDeleteAll)
        assertTrue(selected.ownerDeleteAll)
        assertEquals("REGISTERED_TEST_OWNER_ERASURE", selected.document().profile)
        assertEquals("KJEV-1/OWNER_DELETE+OWNER_DELETE_ALL/INSTALLATION/TEST/LP32BE-UTF8/HMAC-SHA-256/AES-256-GCM/FRESH_PER_OBJECT_KMS_WRAPPED",
            selected.document().protocol)
        assertNotEquals(old.sha256, selected.sha256)
        assertArrayEquals(old.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.of(old.declaration()).canonicalBytes())
        for (journal in listOf(old, selected)) {
            assertArrayEquals(journal.canonicalBytes(), TestOwnerDeleteJournalConfigurationV1.fromDocument(journal.document()).canonicalBytes())
        }
        for (bad in listOf(
            selected.document().copy(profile = old.document().profile),
            old.document().copy(profile = selected.document().profile),
            selected.document().copy(protocol = old.document().protocol),
            selected.document().copy(profile = "REGISTERED_TEST_ANY_DELETE"),
        )) assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.fromDocument(bad) }
        // The new J/full-D selection does not silently change the old event family or KJEV wire format.
        val vector = vectors.first()
        val env = env(vector, selected)
        val event = env.codec.canonicalize(tuple(vector), listOf(target))
        assertArrayEquals(vector.getValue("payload").jsonObject.text("canonical_utf8").toByteArray(), event.canonicalBytes())
        assertArrayEquals(independentEnvelope(independentHeader(vector, old), event.canonicalBytes()),
            env.codec.seal(event, env.codec.startAttempt()).wireBytes())
        val all = allTuple(tuple(vector))
        assertThrows<IllegalArgumentException> { ownerDeleteTestRouting(old).derive(all) }
        rejected { env(journal = old).codec.canonicalize(all, listOf(target)) }
    }

    @Test
    fun `explicit TEST ALL encodes authenticates and restores zero one and hundred targets but not101 or foreign owners`() {
        for (count in listOf(0, 1, 100)) {
            val selected = ownerDeleteTestJournal(ownerDeleteAll = true)
            val env = env(journal = selected)
            val all = allTuple(tuple(vectors.first()))
            val targets = List(count) { UUID.randomUUID() }.sortedBy(UUID::toString)
            val event = env.codec.canonicalize(all, targets)
            assertEquals("OWNER_DELETE_ALL", Json.parseToJsonElement(event.canonicalBytes().decodeToString()).jsonObject.text("eventKind"))
            assertEquals(targets, event.complaintIds())
            assertNotEquals(env.routing.derive(tuple(vectors.first())).active, event.route)
            val encoded = env.codec.seal(event, env.codec.startAttempt())
            val decoded = env.codec.open(env.bucket, event.route.objectKey, encoded.wireBytes(), env.codec.startAttempt())
            assertArrayEquals(event.canonicalBytes(), decoded.event.canonicalBytes())
            assertArrayEquals(event.canonicalBytes(), TestOwnerDeleteJournalCodecV1.restoreCanonical(env.routing,
                event.canonicalBytes(), event.route.routingKeyId).canonicalBytes())
            val foreign = env(journal = selected)
            rejected { foreign.codec.seal(event, foreign.codec.startAttempt()) }
            rejected { env.codec.seal(event, foreign.codec.startAttempt()) }
            val old = env(journal = ownerDeleteTestJournal())
            rejected { old.codec.open(env.bucket, event.route.objectKey, encoded.wireBytes(), old.codec.startAttempt()) }
            assertEquals(0, old.keys.unwrapCalls, "Old profile refuses ALL before any key operation.")
            val scope = ComplaintDataScope.of(UUID.randomUUID())
            val elsewhere = env(journal = ownerDeleteTestJournal(scope = scope, ownerDeleteAll = true))
            rejected { TestOwnerDeleteJournalCodecV1.restoreCanonical(elsewhere.routing, event.canonicalBytes(), event.route.routingKeyId) }
            assertEquals(0, foreign.keys.generateCalls)
        }
        val env = env(journal = ownerDeleteTestJournal(ownerDeleteAll = true))
        rejected { env.codec.canonicalize(allTuple(tuple(vectors.first())), List(101) { UUID.randomUUID() }.sortedBy(UUID::toString)) }
        rejected { env.codec.canonicalize(allTuple(tuple(vectors.first())), listOf(target, target)) }
        assertEquals(0, env.keys.generateCalls)
    }

    @Test
    fun `authenticated header and payload must select the same family even when ALL has one target`() {
        val vector = vectors.first()
        val env = env(journal = ownerDeleteTestJournal(ownerDeleteAll = true))
        for (tuple in listOf(tuple(vector), allTuple(tuple(vector)))) {
            val event = env.codec.canonicalize(tuple, listOf(target))
            val opposite = if (tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE) "OWNER_DELETE_ALL" else "OWNER_DELETE"
            val header = JsonObject(independentHeader(vector, env.routing.journalConfiguration) + mapOf(
                "objectKind" to JsonPrimitive(opposite), "objectKey" to JsonPrimitive(event.route.objectKey),
                "eventId" to JsonPrimitive(event.route.eventId),
            ))
            val before = env.keys.unwrapCalls
            rejected { env.codec.open(env.bucket, event.route.objectKey, independentEnvelope(header, event.canonicalBytes()), env.codec.startAttempt()) }
            assertEquals(before + 1, env.keys.unwrapCalls, "Actual valid GCM tag cannot authorize a mixed-family event.")
        }
    }

    private fun allTuple(tuple: TestOwnerDeleteJournalTupleV1) = TestOwnerDeleteJournalTupleV1(tuple.epoch, tuple.actorId,
        tuple.credentialVersion, tuple.operationKey, tuple.fingerprintBytes(), tuple.scope, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)

    @Test
    fun `routing separates actor credential operation fingerprint epoch and other TEST scope and snapshots digest`() {
        val env = env()
        val ordinary = tuple(vectors.first())
        val first = env.routing.derive(ordinary).active
        val digest = ordinary.fingerprintBytes()
        val copied = TestOwnerDeleteJournalTupleV1(17, ordinary.actorId, 9, ordinary.operationKey, digest, ordinary.scope)
        digest.fill(0)
        copied.fingerprintBytes().fill(0)
        assertEquals(first, env.routing.derive(copied).active)
        for (changed in listOf(
            TestOwnerDeleteJournalTupleV1(18, ordinary.actorId, 9, ordinary.operationKey, ordinary.fingerprintBytes(), ordinary.scope),
            TestOwnerDeleteJournalTupleV1(17, ordinary.operationKey, 9, ordinary.operationKey, ordinary.fingerprintBytes(), ordinary.scope),
            TestOwnerDeleteJournalTupleV1(17, ordinary.actorId, 10, ordinary.operationKey, ordinary.fingerprintBytes(), ordinary.scope),
            TestOwnerDeleteJournalTupleV1(17, ordinary.actorId, 9, ordinary.actorId, ordinary.fingerprintBytes(), ordinary.scope),
            TestOwnerDeleteJournalTupleV1(17, ordinary.actorId, 9, ordinary.operationKey, ByteArray(32), ordinary.scope),
        )) assertNotEquals(first, env.routing.derive(changed).active)
        val otherScope = ComplaintDataScope.of(UUID.fromString("b2222222-2222-4222-8222-222222222223"))
        val otherTuple = TestOwnerDeleteJournalTupleV1(17, ordinary.actorId, 9, ordinary.operationKey, ordinary.fingerprintBytes(), otherScope)
        assertThrows<IllegalArgumentException> { env.routing.derive(otherTuple) }
        assertNotEquals(first, ownerDeleteTestRouting(ownerDeleteTestJournal(scope = otherScope)).derive(otherTuple).active)
        assertThrows<IllegalArgumentException> {
            TestOwnerDeleteJournalTupleV1(17, ordinary.actorId, 9, ordinary.operationKey, ordinary.fingerprintBytes(), ComplaintDataScope.LIVE)
        }
        for (invalid in listOf(0L, -1L)) {
            assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalTupleV1(invalid, ordinary.actorId, 9, ordinary.operationKey, ByteArray(32), ordinary.scope) }
            assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalTupleV1(17, ordinary.actorId, invalid, ordinary.operationKey, ByteArray(32), ordinary.scope) }
        }
    }

    @Test
    fun `closed canonical payload refuses malformed dimensions extra fields and noncanonical JSON without KMS`() {
        val env = env()
        val vector = vectors.first()
        val literal = vector.getValue("payload").jsonObject.text("canonical_utf8")
        val payload = Json.parseToJsonElement(literal).jsonObject
        val mutations = invalidPayloads(payload)
        for (bad in mutations.map(::canonical) + listOf(
            (literal + "\n").toByteArray(), (literal + "{}").toByteArray(),
            literal.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").toByteArray(),
            literal.replace("\"credentialVersion\":9", "\"credentialVersion\":9.0").toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28), ByteArray(65_537) { 32 },
        )) {
            rejected { TestOwnerDeleteJournalCodecV1.restoreCanonical(env.routing, bad, "test-route-01") }
        }
        for (ids in listOf(emptyList(), listOf(target, target), listOf(target, UUID.randomUUID()))) {
            rejected { env.codec.canonicalize(tuple(vector), ids) }
        }
        rejected { env.codec.canonicalize(tuple(vector), listOf(target), "unknown-key") }
        rejected { TestOwnerDeleteJournalCodecV1.restoreCanonical(env.routing, literal.toByteArray(), "test-route-02") }
        assertEquals(0, env.keys.generateCalls)
        assertEquals(0, env.keys.unwrapCalls)
    }

    @Test
    fun `KJEV envelope and KMS context match independent AES GCM framing while decoded content grants no readback custody`() {
        val env = env()
        val vector = vectors.first()
        val header = independentHeader(vector, env.routing.journalConfiguration)
        val payload = vector.getValue("payload").jsonObject.text("canonical_utf8").toByteArray()
        val expected = independentEnvelope(header, payload)
        val event = env.codec.canonicalize(tuple(vector), listOf(target))
        val candidate = env.codec.seal(event, env.codec.startAttempt())
        assertArrayEquals(expected, candidate.wireBytes())
        assertEquals(
            mapOf("kira-complaint-journal-context-v1" to b64(frame(listOf("kira-complaint-journal-kms-context-v1", "1") + values(header)))),
            env.keys.requests.single().encryptionContext(),
        )
        val opened = env.codec.open(env.bucket, vector.text("object_key"), expected, env.codec.startAttempt())
        assertArrayEquals(payload, opened.event.canonicalBytes())
        assertEquals(candidate.wireSha256, opened.wireSha256)
        assertEquals(2, env.keys.closed)
        assertTrue(env.keys.transferred.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(env.keys.unwrapInputs.all { bytes -> bytes.all { it == 0.toByte() } })
        event.canonicalBytes().fill(0)
        candidate.wireBytes().fill(0)
        opened.event.canonicalBytes().fill(0)
        assertArrayEquals(payload, event.canonicalBytes())
        assertArrayEquals(expected, candidate.wireBytes())
        val second = env.codec.seal(event, env.codec.startAttempt())
        assertNotEquals(candidate.wireSha256, second.wireSha256)
        assertEquals(candidate.semanticSha256, second.semanticSha256)
        val rendered = listOf(event, candidate, opened, env.codec, env.routing, tuple(vector)).joinToString()
        for (secret in listOf(target.toString(), vector.text("object_key"), vector.getValue("event_id").jsonObject.text("hmac_sha256_base64url"))) {
            assertFalse(rendered.contains(secret))
        }
    }

    @Test
    fun `every authenticated header wrapped key ciphertext tag and independent location resists substitution`() {
        val env = env()
        val vector = vectors.first()
        val header = independentHeader(vector, env.routing.journalConfiguration)
        val payload = vector.getValue("payload").jsonObject.text("canonical_utf8").toByteArray()
        val original = independentEnvelope(header, payload)
        val sections = sections(original)
        for (name in HEADER_ORDER) {
            val changed = when (name) {
                "envelopeSchemaVersion", "payloadSchemaVersion" -> JsonPrimitive(2)
                "publicationEpoch" -> JsonPrimitive(18)
                "eventId" -> JsonPrimitive("A".repeat(43))
                "nonce" -> JsonPrimitive(b64(ByteArray(12) { 4 }))
                "dataScopeKind" -> JsonPrimitive("LIVE")
                "dataScopeId" -> JsonPrimitive("00000000-0000-0000-0000-000000000000")
                "routingKeyId" -> JsonPrimitive("test-route-02")
                else -> JsonPrimitive(header.text(name) + "-other")
            }
            rejected { env.codec.open(env.bucket, vector.text("object_key"), pack(canonical(change(header, name, changed)), sections.second, sections.third), env.codec.startAttempt()) }
        }
        for (index in listOf(0, sections.third.size - 16, sections.third.lastIndex)) {
            val corrupted = sections.third.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(
                OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED,
                rejected { env.codec.open(env.bucket, vector.text("object_key"), pack(sections.first, sections.second, corrupted), env.codec.startAttempt()) }.code,
            )
        }
        val changedWrapped = sections.second.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(
            OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED,
            rejected { env.codec.open(env.bucket, vector.text("object_key"), pack(sections.first, changedWrapped, sections.third), env.codec.startAttempt()) }.code,
        )
        val before = env.keys.unwrapCalls
        rejected { env.codec.open("other-bucket", vector.text("object_key"), original, env.codec.startAttempt()) }
        rejected { env.codec.open(env.bucket, vector.text("object_key") + "a", original, env.codec.startAttempt()) }
        assertEquals(before, env.keys.unwrapCalls)
    }

    @Test
    fun `authenticated but semantically invalid payloads cannot become a valid event`() {
        val env = env()
        val vector = vectors.first()
        val header = independentHeader(vector, env.routing.journalConfiguration)
        val payload = Json.parseToJsonElement(vector.getValue("payload").jsonObject.text("canonical_utf8")).jsonObject
        for (invalid in invalidPayloads(payload)) {
            val before = env.keys.unwrapCalls
            rejected {
                env.codec.open(env.bucket, vector.text("object_key"), independentEnvelope(header, canonical(invalid)), env.codec.startAttempt())
            }
            assertEquals(before + 1, env.keys.unwrapCalls)
        }
    }

    @Test
    fun `binary lengths cap exact plaintext and envelope before allocations or key calls`() {
        val env = env()
        val vector = vectors.first()
        val original = independentEnvelope(independentHeader(vector, env.routing.journalConfiguration), vector.getValue("payload").jsonObject.text("canonical_utf8").toByteArray())
        val malformed = listOf(
            ByteArray(19), ByteArray(98_305), original + 0,
            original.copyOf().also { ByteBuffer.wrap(it).putInt(0, 0x4b4a4557) },
            original.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) },
            original.copyOf().also { ByteBuffer.wrap(it).putInt(8, -1) },
            original.copyOf().also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) },
            original.copyOf().also { ByteBuffer.wrap(it).putInt(8, 4097) },
            original.copyOf(original.size - 1),
        )
        for (wire in malformed) rejected { env.codec.open(env.bucket, vector.text("object_key"), wire, env.codec.startAttempt()) }
        assertEquals(0, env.keys.unwrapCalls)
        val declaration = env.routing.journalConfiguration.declaration()
        val length = vector.getValue("payload").jsonObject.text("canonical_utf8").toByteArray().size
        val limited = declaration.limits.decoder.copy(maximumPlaintextBytes = length - 1, maximumJsonTokens = length - 1, maximumStringUtf8Bytes = length - 1)
        val small = env(vector, ownerDeleteTestJournal(decoder = limited))
        rejected { small.codec.canonicalize(tuple(vector), listOf(target)) }
        assertEquals(0, small.keys.generateCalls)
    }

    @Test
    fun `attempt ownership and monotonic deadline cannot be replaced or renewed by equal declarations`() {
        val first = env()
        val second = env()
        val event = first.codec.canonicalize(tuple(vectors.first()), listOf(target))
        rejected { second.codec.seal(event, second.codec.startAttempt()) }
        rejected { first.codec.seal(event, second.codec.startAttempt()) }
        assertEquals(0, first.keys.generateCalls)
        assertEquals(0, second.keys.generateCalls)
        val attempt = first.codec.startAttempt()
        first.nanos = 5_000_000_000L
        assertEquals(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED, rejected { first.codec.seal(event, attempt) }.code)
        first.nanos = 0
        assertEquals(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED, rejected { first.codec.seal(event, attempt) }.code)
        val fresh = first.codec.startAttempt()
        first.nanos = -1
        assertEquals(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED, rejected { first.codec.seal(event, fresh) }.code)
    }

    @Test
    fun `key failure width and native cleanup failures sanitize diagnostics and zero transferred buffers`() {
        for (width in listOf(0, 31, 33)) {
            val env = env().also { it.keys.returnedWidth = width }
            val event = env.codec.canonicalize(tuple(vectors.first()), listOf(target))
            assertEquals(OwnerDeleteAllJournalFailure.KEY_FAILURE, rejected { env.codec.seal(event, env.codec.startAttempt()) }.code)
            assertEquals(1, env.keys.closed)
            assertTrue(env.keys.transferred.all { bytes -> bytes.all { it == 0.toByte() } })
        }
        val env = env().also { it.keys.failClose = true }
        val event = env.codec.canonicalize(tuple(vectors.first()), listOf(target))
        assertEquals(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE, rejected { env.codec.seal(event, env.codec.startAttempt()) }.code)
        assertTrue(env.keys.transferred.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    private fun invalidPayloads(value: JsonObject): List<JsonObject> = listOf(
        change(value, "schemaVersion", JsonPrimitive(2)),
        change(value, "eventKind", JsonPrimitive("OWNER_DELETE_ALL")),
        change(value, "actorKind", JsonPrimitive("ADMIN")),
        change(value, "actorId", JsonPrimitive("a1111111-1111-1111-8111-111111111111")),
        change(value, "credentialVersion", JsonPrimitive(0)),
        change(value, "publicationEpoch", JsonPrimitive(0)),
        change(value, "operationKey", JsonPrimitive("00000000-0000-0000-0000-000000000000")),
        change(value, "requestFingerprint", JsonPrimitive(value.text("requestFingerprint") + "=")),
        change(value, "dataScopeKind", JsonPrimitive("LIVE")),
        change(value, "dataScopeId", JsonPrimitive("00000000-0000-0000-0000-000000000000")),
        change(value, "eventId", JsonPrimitive("A".repeat(43))),
        change(value, "ownerInstallationIds", JsonArray(emptyList())),
        change(value, "ownerInstallationIds", JsonArray(listOf(JsonPrimitive("d4444444-4444-4444-8444-444444444444")))),
        change(value, "ownerInstallationIds", JsonArray(listOf(value.getValue("actorId"), value.getValue("actorId")))),
        change(value, "complaintIds", JsonArray(emptyList())),
        change(value, "complaintIds", JsonArray(listOf(JsonPrimitive(target.toString()), JsonPrimitive(target.toString())))),
        change(value, "complaintIds", JsonArray(listOf(JsonPrimitive(target.toString().uppercase())))),
        change(value, "resourceVersion", JsonPrimitive(42)),
        change(value, "body", JsonPrimitive("synthetic-private-prose")),
        change(value, "createdAt", JsonPrimitive("2026-09-18T00:00:00Z")),
    ) + value.keys.map { JsonObject(value - it) } + value.keys.map { change(value, it, JsonNull) }

    private fun env(vector: JsonObject = vectors.first(), journal: TestOwnerDeleteJournalConfigurationV1? = null): Env {
        val ids = if (vector.text("set") == "ordinary_four_retained_keys") null else listOf(vector.text("id"))
        return Env(ownerDeleteTestRouting(journal ?: ownerDeleteTestJournal(vectorIds = ids)))
    }

    private fun tuple(vector: JsonObject): TestOwnerDeleteJournalTupleV1 {
        val payload = Json.parseToJsonElement(vector.getValue("payload").jsonObject.text("canonical_utf8")).jsonObject
        return TestOwnerDeleteJournalTupleV1(
            vector.text("publication_epoch_decimal").toLong(), UUID.fromString(payload.text("actorId")),
            vector.text("credential_version_decimal").toLong(), UUID.fromString(payload.text("operationKey")),
            Base64.getUrlDecoder().decode(payload.text("requestFingerprint")), ComplaintDataScope.of(UUID.fromString(payload.text("dataScopeId"))),
        )
    }

    private fun rejected(action: () -> Unit): OwnerDeleteAllJournalException = assertThrows<OwnerDeleteAllJournalException> { action() }.also {
        assertNull(it.cause)
        assertTrue(it.suppressed.isEmpty())
        assertEquals("Complaint journal codec rejected operation: ${it.code.name}", it.message)
    }

    private fun independentHeader(vector: JsonObject, journal: TestOwnerDeleteJournalConfigurationV1): JsonObject {
        val declaration = journal.declaration()
        return JsonObject(linkedMapOf(
            "envelopeSchemaVersion" to JsonPrimitive(1), "payloadSchemaVersion" to JsonPrimitive(1),
            "canonicalizerId" to JsonPrimitive("kcj-1"), "objectKind" to JsonPrimitive("OWNER_DELETE"),
            "encryptionAlgorithm" to JsonPrimitive("AES-256-GCM"), "dataKeyMode" to JsonPrimitive("FRESH_PER_OBJECT_KMS_WRAPPED"),
            "kmsKeyId" to JsonPrimitive(declaration.encryption.keyId), "kmsKeyArn" to JsonPrimitive(declaration.encryption.keyArn),
            "bucket" to JsonPrimitive(declaration.journalLocation.bucket), "objectKey" to JsonPrimitive(vector.text("object_key")),
            "writerGeneration" to JsonPrimitive("c3333333-3333-4333-8333-333333333333"),
            "ordinaryPrefix" to JsonPrimitive(vector.text("ordinary_prefix")), "dataScopeKind" to JsonPrimitive("TEST"),
            "dataScopeId" to JsonPrimitive("b2222222-2222-4222-8222-222222222222"),
            "publicationEpoch" to JsonPrimitive(vector.text("publication_epoch_decimal").toLong()),
            "routingKeyId" to JsonPrimitive(vector.text("routing_key_id")),
            "eventId" to JsonPrimitive(vector.getValue("event_id").jsonObject.text("hmac_sha256_base64url")),
            "nonce" to JsonPrimitive(b64(ByteArray(12) { (it + 3).toByte() })),
        ))
    }

    /** Only fixture LP, restricted JSON and JCE; no production framing, serializer or encoder. */
    private fun independentEnvelope(header: JsonObject, payload: ByteArray): ByteArray {
        val headerBytes = canonical(header)
        val wrapped = ByteArray(64) { (it + 65).toByte() }
        val aad = frame(
            listOf("kira-complaint-journal-aad-v1", "1", "KJEV", "1", headerBytes.size.toString()) + values(header) +
                listOf(wrapped.size.toString(), b64(wrapped), (payload.size + 16).toString()),
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES"), GCMParameterSpec(128, Base64.getUrlDecoder().decode(header.text("nonce"))))
        cipher.updateAAD(aad)
        return pack(headerBytes, wrapped, cipher.doFinal(payload))
    }

    private fun pack(header: ByteArray, wrapped: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteBuffer.allocate(20 + header.size + wrapped.size + ciphertext.size).apply {
            putInt(0x4b4a4556).putInt(1)
            listOf(header, wrapped, ciphertext).forEach { putInt(it.size).put(it) }
        }.array()

    private fun sections(wire: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val buffer = ByteBuffer.wrap(wire).apply { position(8) }
        fun section(): ByteArray = ByteArray(buffer.int).also { buffer.get(it) }
        return Triple(section(), section(), section())
    }

    private fun frame(fields: List<String>): ByteArray {
        val bytes = fields.map { it.toByteArray() }
        return ByteBuffer.allocate(bytes.sumOf { 4 + it.size }).apply { bytes.forEach { putInt(it.size).put(it) } }.array()
    }

    private fun values(header: JsonObject): List<String> = HEADER_ORDER.map(header::text)
    private fun canonical(value: JsonObject): ByteArray = JsonObject(value.toSortedMap()).toString().toByteArray()
    private fun change(value: JsonObject, name: String, replacement: JsonElement): JsonObject = JsonObject(value + (name to replacement))
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private class Env(val routing: TestOwnerDeleteJournalRoutingV1) {
        val keys = Keys()
        var nanos = 0L
        private val nonces = object : SecureRandom() {
            var serial = 0
            override fun nextBytes(bytes: ByteArray) {
                assertEquals(12, bytes.size)
                bytes.indices.forEach { bytes[it] = (it + 3 + serial * 17).toByte() }
                serial++
            }
        }
        val codec = TestOwnerDeleteJournalCodecV1(routing, keys, nonces) { nanos }
        val bucket = routing.journalConfiguration.declaration().journalLocation.bucket
    }

    /** Permissive synthetic unwrap isolates AEAD checks; it is never KMS or provider custody. */
    private class Keys : JournalDataKeyPortV1 {
        var generateCalls = 0
        var unwrapCalls = 0
        var closed = 0
        var returnedWidth = 32
        var failClose = false
        val requests = mutableListOf<JournalDataKeyRequestV1>()
        val transferred = mutableListOf<ByteArray>()
        val unwrapInputs = mutableListOf<ByteArray>()

        override fun generate(request: JournalDataKeyRequestV1): JournalGeneratedDataKeyV1 {
            val serial = generateCalls++
            requests.add(request)
            val key = ByteArray(returnedWidth) { (it + 1 + serial * 11).toByte() }.also(transferred::add)
            val wrapped = ByteArray(64) { (it + 65 + serial * 13).toByte() }.also(transferred::add)
            return object : JournalGeneratedDataKeyV1 {
                override val keyArn = request.keyArn
                override val plaintextKey = key
                override val wrappedKey = wrapped
                override fun close() = finish(key, wrapped)
            }
        }

        override fun unwrap(request: JournalDataKeyRequestV1, wrappedKey: ByteArray): JournalPlaintextDataKeyV1 {
            unwrapCalls++
            requests.add(request)
            unwrapInputs.add(wrappedKey)
            val key = ByteArray(returnedWidth) { (it + 1).toByte() }.also(transferred::add)
            return object : JournalPlaintextDataKeyV1 {
                override val keyArn = request.keyArn
                override val plaintextKey = key
                override fun close() = finish(key)
            }
        }

        private fun finish(vararg values: ByteArray) {
            closed++
            assertTrue(values.all { bytes -> bytes.all { it == 0.toByte() } })
            if (failClose) error("synthetic-private-provider-close")
        }
    }

    private companion object {
        val HEADER_ORDER = listOf(
            "envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
            "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "ordinaryPrefix", "dataScopeKind", "dataScopeId",
            "publicationEpoch", "routingKeyId", "eventId", "nonce",
        )
    }
}

/** Reuse existing declaration values, with an explicitly isolated synthetic TEST namespace. Never activation evidence. */
internal fun ownerDeleteTestJournal(
    scope: ComplaintDataScope = ComplaintDataScope.of(UUID.fromString("b2222222-2222-4222-8222-222222222222")),
    vectorIds: List<String>? = null,
    decoder: JournalDecoderLimitsV1? = null,
    ownerDeleteAll: Boolean = false,
): TestOwnerDeleteJournalConfigurationV1 {
    val base = InitialLiveJournalTestFixture.declaration()
    val inputs = ownerDeleteLiteralResource("inputs.json").getValue("journal_routes").jsonArray.map { it.jsonObject }
    val selected = inputs.filter { if (vectorIds == null) it.text("set") == "ordinary_four_retained_keys" else it.text("id") in vectorIds }
    val routingKeys = selected.mapIndexed { index, value ->
        JournalRoutingKeyV1(
            value.text("routing_key_id"),
            ImmutableSecretVersion.awsSecretsManager(
                "arn:aws:secretsmanager:us-east-1:123456789012:secret:test-owner-delete-$index-ABC123",
                "f0000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}",
            ),
        )
    }
    fun role(value: me.manga.kira.backend.complaint.domain.catalog.InitialEventRoleV1) = value.copy(
        roleId = "test-${value.roleId}", credentialId = "test-${value.credentialId}", policy = value.policy.copy(policyId = "test-${value.policy.policyId}"),
    )
    return TestOwnerDeleteJournalConfigurationV1.of(
        TestOwnerDeleteJournalDeclarationV1(
            scope, base.writer.copy(generationId = "c3333333-3333-4333-8333-333333333333"),
            base.journalLocation.copy(bucket = "kira-test-owner-delete-fixture"),
            base.authorities.copy(ordinary = role(base.authorities.ordinary), sealTerminal = role(base.authorities.sealTerminal), recovery = role(base.authorities.recovery)),
            base.routing.copy(activeKeyId = selected.single { it.text("role") == "active" }.text("routing_key_id"), keys = routingKeys),
            base.encryption, base.recovery.copy(
                queue = base.recovery.queue.copy(arn = "arn:aws:sqs:us-east-1:123456789012:test-owner-delete-events"),
                deadLetterQueue = base.recovery.deadLetterQueue.copy(arn = "arn:aws:sqs:us-east-1:123456789012:test-owner-delete-dlq"),
            ),
            if (decoder == null) base.limits else base.limits.copy(decoder = decoder),
        ),
        ownerDeleteAll = ownerDeleteAll,
    )
}

internal fun ownerDeleteTestRouting(journal: TestOwnerDeleteJournalConfigurationV1 = ownerDeleteTestJournal()): TestOwnerDeleteJournalRoutingV1 =
    TestOwnerDeleteJournalRoutingV1.fromAcquired(journal, ownerDeleteTestSecrets(journal))

private fun ownerDeleteTestSecrets(journal: TestOwnerDeleteJournalConfigurationV1): List<AcquiredVersionedSecret> {
    val inputs = ownerDeleteLiteralResource("inputs.json").getValue("journal_routes").jsonArray.map { it.jsonObject }.associateBy { it.text("routing_key_id") }
    return journal.declaration().routing.keys.map { key ->
        val descriptor = VersionedSecretBinding.of(SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING, SecretMaterialPurpose.HMAC_SHA256, key.keyId, key.secret)
        AcquiredVersionedSecret.acquire(descriptor) { SecretVersionSnapshot(key.secret, HexFormat.of().parseHex(inputs.getValue(key.keyId).text("hmac_key_hex"))) }
    }
}

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
