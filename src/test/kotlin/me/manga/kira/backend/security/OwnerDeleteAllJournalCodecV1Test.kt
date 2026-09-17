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
import kotlinx.serialization.json.long
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class OwnerDeleteAllJournalCodecV1Test {
    private val fixtureBytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/owner-delete-all-journal-v1/golden.json")).use { it.readBytes() }
    private val vectors = Json.parseToJsonElement(fixtureBytes.toString(Charsets.UTF_8)).jsonObject.getValue("vectors").jsonArray.map { Vector(it.jsonObject) }

    @Test
    fun `independent Python AESGCM vectors pin empty and hundred target payload header framing and wire bytes`() {
        assertEquals("68553335d79619209e60ded83884b03dbebc3d7477c350e09f454628622cb168", sha(fixtureBytes))
        assertEquals(2, vectors.size)
        vectors.forEach { vector ->
            val keys = Keys(vector)
            val env = env(keys = keys, nonces = Nonces(vector.hex("nonceHex").first().toInt()))
            val ids = vector.payload.getValue("complaintIds").jsonArray.map { UUID.fromString(it.jsonPrimitive.content) }
            val event = env.codec.canonicalize(tuple(vector.payload), ids)
            assertArrayEquals(vector.hex("plaintextHex"), event.canonicalBytes())
            assertEquals(vector.text("semanticSha256"), event.semanticSha256)
            assertEquals(ids, event.complaintIds())
            val candidate = env.codec.seal(event, env.codec.startAttempt())
            assertArrayEquals(vector.hex("wireHex"), candidate.wireBytes())
            assertEquals(vector.text("wireSha256"), candidate.wireSha256)
            assertNotEquals(candidate.wireSha256, candidate.semanticSha256)
            val contextFrame = independentFrame(listOf("kira-complaint-journal-kms-context-v1", "1") + values(vector.header))
            assertArrayEquals(vector.hex("kmsContextFrameHex"), contextFrame)
            assertArrayEquals(vector.hex("aadHex"), independentAad(vector.header, vector.hex("wrappedKeyHex"), vector.hex("plaintextHex").size + 16))
            val request = keys.requests.single()
            assertEquals(mapOf(vector.text("kmsContextKey") to vector.text("kmsContextValue")), request.encryptionContext())
            assertEquals(32, request.dataKeyBytes)
            assertEquals(1000, request.timeoutMillis)
            assertEquals(vector.header.text("kmsKeyArn"), request.keyArn)
            assertTrue(request.maximumWrappedKeyBytes <= env.journal.declaration().limits.decoder.maximumWrappedKeyBytes)
            val opened = env.codec.open(env.bucket, event.route.objectKey, candidate.wireBytes(), env.codec.startAttempt())
            assertArrayEquals(event.canonicalBytes(), opened.event.canonicalBytes())
            assertEquals(candidate.wireSha256, opened.wireSha256)
            assertEquals(event.route, opened.event.route)
            assertEquals(2, keys.closed)
            assertTrue(keys.transferred.all { bytes -> bytes.all { it == 0.toByte() } })
            assertTrue(keys.unwrapInputs.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test
    fun `fresh candidates use fresh key and nonce while all retained content copies and diagnostics stay isolated`() {
        val env = env()
        val ids = mutableListOf(UUID.fromString("73000000-0000-1000-8000-000000000001"))
        val fingerprint = ByteArray(32) { (128 + it).toByte() }
        val input = tuple(fingerprint = fingerprint)
        val event = env.codec.canonicalize(input, ids)
        val expected = event.canonicalBytes()
        fingerprint.fill(0)
        ids.clear()
        event.canonicalBytes().fill(0)
        assertArrayEquals(expected, event.canonicalBytes())
        assertEquals(1, event.complaintIds().size)
        val first = env.codec.seal(event, env.codec.startAttempt())
        val second = env.codec.seal(event, env.codec.startAttempt())
        val firstWire = first.wireBytes()
        first.wireBytes().fill(0)
        assertArrayEquals(firstWire, first.wireBytes())
        assertEquals(first.semanticSha256, second.semanticSha256)
        assertEquals(first.route, second.route)
        assertNotEquals(first.wireSha256, second.wireSha256)
        assertFalse(parts(firstWire).wrapped.contentEquals(parts(second.wireBytes()).wrapped))
        assertNotEquals(parts(firstWire).header.text("nonce"), parts(second.wireBytes()).header.text("nonce"))
        val contextCopy = env.keys.requests.first().encryptionContext() as MutableMap<String, String>
        contextCopy.clear()
        assertEquals(1, env.keys.requests.first().encryptionContext().size)
        val sourceCopy = first.wireBytes()
        val opened = env.codec.open(env.bucket, first.route.objectKey, sourceCopy, env.codec.startAttempt())
        sourceCopy.fill(0)
        opened.event.canonicalBytes().fill(0)
        assertArrayEquals(expected, opened.event.canonicalBytes())
        assertEquals(2, env.keys.generateCalls)
        assertTrue(env.nonces.destinations.all { bytes -> bytes.all { it == 0.toByte() } })
        val rendered = listOf(env.codec, event, first, opened, first.route, input, env.codec.startAttempt(), env.keys.requests).joinToString()
        listOf(input.actorId.toString(), input.operationKey.toString(), "route-b", env.bucket, event.semanticSha256).forEach {
            assertFalse(rendered.contains(it))
        }
        val contextText = Base64.getUrlDecoder().decode(env.keys.requests.first().encryptionContext().values.single()).toString(Charsets.UTF_8)
        assertFalse(contextText.contains(input.actorId.toString()))
        assertFalse(contextText.contains(input.operationKey.toString()))
        assertFalse(contextText.contains(input.encodedFingerprint()))
    }

    @Test
    fun `all semantic headers wrapped bytes ciphertext and tag reject tampering or cross location replay`() {
        val vector = vectors.first()
        // Deliberately permissive fake unwrap isolates GCM AAD verification from KMS context checking.
        val keys = Keys(vector, permitContextMismatch = true)
        val env = env(keys = keys)
        val original = parts(vector.hex("wireHex"))
        HEADER_ORDER.forEach { name ->
            val changed = when (name) {
                "envelopeSchemaVersion", "payloadSchemaVersion" -> JsonPrimitive(2)
                "publicationEpoch" -> JsonPrimitive(43)
                "eventId" -> JsonPrimitive(b64(ByteArray(32) { 9 }))
                "nonce" -> JsonPrimitive(b64(ByteArray(12) { (it + 1).toByte() }))
                "routingKeyId" -> JsonPrimitive("route-a")
                else -> JsonPrimitive(original.header.text(name) + "-different")
            }
            val before = keys.unwrapCalls
            val failure = rejected {
                val wire = pack(canonical(change(original.header, name, changed)), original.wrapped, original.encrypted)
                env.codec.open(env.bucket, original.header.text("objectKey"), wire, env.codec.startAttempt())
            }
            val passesStructuralBinding = name in setOf("eventId", "nonce")
            assertEquals(if (passesStructuralBinding) 1 else 0, keys.unwrapCalls - before, name)
            if (passesStructuralBinding) assertEquals(OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED, failure.code)
        }
        val changedWrapped = original.wrapped.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val wireMutations = listOf(pack(canonical(original.header), changedWrapped, original.encrypted)) +
            listOf(0, original.encrypted.size - 16, original.encrypted.lastIndex).map { index ->
                val changed = original.encrypted.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
                pack(canonical(original.header), original.wrapped, changed)
            }
        wireMutations.forEach { wire ->
            assertEquals(OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED, rejected { open(env, wire, original.header.text("objectKey")) }.code)
        }
        val newKey = original.header.text("objectKey").substringBeforeLast('/') + "/" + b64(ByteArray(32) { 3 })
        val replay = pack(canonical(change(original.header, "objectKey", JsonPrimitive(newKey))), original.wrapped, original.encrypted)
        assertEquals(OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED, rejected { open(env, replay, newKey) }.code)
        val declared = env.journal.declaration()
        val otherJ = ComplaintJournalConfigurationV1.of(declared.copy(journalLocation = declared.journalLocation.copy(bucket = "kira-other-journal")))
        val other = env(journal = otherJ, keys = keys)
        val otherHeader = change(original.header, "bucket", JsonPrimitive(other.bucket))
        val otherWire = pack(canonical(otherHeader), original.wrapped, original.encrypted)
        assertEquals(
            OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED,
            rejected { open(other, otherWire, original.header.text("objectKey")) }.code,
        )
    }

    @Test
    fun `closed canonical JSON binary lengths UTF8 and every J decoder limit are bounded before KMS when knowable`() {
        val vector = vectors.first()
        val original = parts(vector.hex("wireHex"))
        val headerText = canonical(original.header).toString(Charsets.UTF_8)
        val invalidHeaders = listOf(
            canonical(change(original.header, "unknown", JsonPrimitive("synthetic-private"))),
            canonical(JsonObject(original.header - "nonce")),
            (headerText.dropLast(1) + ",\"nonce\":\"${original.header.text("nonce")}\"}").toByteArray(),
            (headerText + " ").toByteArray(), (headerText + "{}").toByteArray(),
            headerText.replace("\"publicationEpoch\":42", "\"publicationEpoch\":9223372036854775808").toByteArray(),
            headerText.replace("\"publicationEpoch\":42", "\"publicationEpoch\":42.0").toByteArray(),
            canonical(change(original.header, "eventId", JsonPrimitive(original.header.text("eventId").dropLast(1) + "B"))),
            canonical(change(original.header, "nonce", JsonPrimitive(original.header.text("nonce") + "="))),
            canonical(change(original.header, "nonce", JsonNull)),
            headerText.replace(original.header.text("nonce"), "\\uD800").toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28), ByteArray(4097) { 32 },
        )
        val env = env(keys = Keys(vector))
        invalidHeaders.forEach { bad -> rejected { open(env, pack(bad, original.wrapped, original.encrypted), original.header.text("objectKey")) } }
        val wire = vector.hex("wireHex")
        val wrappedLengthOffset = 12 + canonical(original.header).size
        val ciphertextLengthOffset = wrappedLengthOffset + 4 + original.wrapped.size
        val invalidWire = listOf(0, 4, 8, wrappedLengthOffset, ciphertextLengthOffset).map { offset ->
            wire.copyOf().also { ByteBuffer.wrap(it).putInt(offset, -1) }
        } + listOf(
            wire.copyOf(wire.size - 1), wire + byteArrayOf(0), ByteArray(98_305),
            pack(ByteArray(0), original.wrapped, original.encrypted),
            pack(canonical(original.header), ByteArray(0), original.encrypted),
            pack(canonical(original.header), ByteArray(6145), original.encrypted),
            pack(canonical(original.header), original.wrapped, ByteArray(16)),
            pack(canonical(original.header), original.wrapped, ByteArray(65_553)),
        )
        invalidWire.forEach { bad -> rejected { open(env, bad, original.header.text("objectKey")) } }
        assertEquals(0, env.keys.unwrapCalls)
        val profiles = listOf(
            profile(envelope = wire.size - 1, plaintext = wire.size - 2),
            profile(plaintext = vector.hex("plaintextHex").size - 1),
            profile(fields = 17), profile(tokens = 37), profile(strings = original.header.text("objectKey").length - 1),
            profile(wrapped = original.wrapped.size - 1),
        )
        profiles.forEach { limits ->
            val limited = env(journal = journal(limits), keys = Keys(vector))
            rejected { open(limited, wire, original.header.text("objectKey")) }
            assertEquals(0, limited.keys.unwrapCalls)
        }
        // Header is flat; payload arrays are only observable after authentication with this stricter J.
        val shallow = env(journal = journal(profile(depth = 1)), keys = Keys(vector))
        assertEquals(OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED, rejected { open(shallow, wire, original.header.text("objectKey")) }.code)
        assertEquals(1, shallow.keys.unwrapCalls)
    }

    @Test
    fun `key response cleanup cancellation fatal errors and shared shrinking deadlines fail closed without diagnostic graphs`() {
        val vector = vectors.first()
        listOf(0, 31, 33).forEach { size ->
            val keys = Keys(vector).also { it.returnedKeyBytes = size }
            val env = env(keys = keys)
            assertEquals(OwnerDeleteAllJournalFailure.KEY_FAILURE, rejected { seal(env) }.code)
            assertEquals(1, keys.closed)
        }
        listOf(0, 6145).forEach { size ->
            val env = env(keys = Keys(vector).also { it.returnedWrappedBytes = size })
            assertEquals(OwnerDeleteAllJournalFailure.KEY_FAILURE, rejected { seal(env) }.code)
            assertEquals(1, env.keys.closed)
        }
        val wrongArn = env(keys = Keys(vector).also { it.returnedArn = "synthetic-other-private-key" })
        assertEquals(OwnerDeleteAllJournalFailure.KEY_FAILURE, rejected { seal(wrongArn) }.code)
        val providerError = env(keys = Keys(vector).also { it.callFailure = IllegalStateException("synthetic-provider-secret") })
        assertEquals(OwnerDeleteAllJournalFailure.KEY_FAILURE, rejected { seal(providerError) }.code)
        val closingError = env(keys = Keys(vector).also { it.closeFailure = IllegalStateException("synthetic-cleanup-secret") })
        assertEquals(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE, rejected { seal(closingError) }.code)
        val cancelled = env(keys = Keys(vector).also { it.closeFailure = CancellationException("synthetic-cancel-secret") })
        val cancellation = assertThrows(CancellationException::class.java) { seal(cancelled) }
        assertNull(cancellation.cause)
        assertTrue(cancellation.suppressed.isEmpty())
        assertFalse(cancellation.toString().contains("synthetic"))
        val interrupted = env(keys = Keys(vector).also { it.closeFailure = InterruptedException("synthetic-interrupt-secret") })
        try {
            val failure = assertThrows(InterruptedException::class.java) { seal(interrupted) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("synthetic"))
        } finally {
            Thread.interrupted()
        }
        val fatal = AssertionError("synthetic-fatal")
        val fatalEnv = env(
            keys = Keys(vector).also {
                it.arnFailure = fatal
                it.closeFailure = IllegalStateException("synthetic-close")
            },
        )
        assertSame(fatal, assertThrows(AssertionError::class.java) { seal(fatalEnv) })
        assertEquals(1, fatalEnv.keys.closed)
        val clock = Clock()
        val timed = env(clock = clock)
        val event = timed.codec.canonicalize(tuple(), emptyList())
        val attempt = timed.codec.startAttempt()
        clock.nanos = 4_500_000_000L
        val encoded = timed.codec.seal(event, attempt)
        assertEquals(500, timed.keys.requests.last().timeoutMillis)
        clock.nanos = 4_800_000_000L
        timed.codec.open(timed.bucket, event.route.objectKey, encoded.wireBytes(), attempt)
        assertEquals(200, timed.keys.requests.last().timeoutMillis)
        clock.nanos = 5_000_000_000L
        assertEquals(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED, rejected { timed.codec.seal(event, attempt) }.code)
        clock.nanos = 4_900_000_000L
        assertEquals(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED, rejected { timed.codec.seal(event, attempt) }.code)
        assertEquals(1, timed.keys.generateCalls)
        val lateClock = Clock()
        val lateKeys = Keys(vector).also { it.onGenerate = { lateClock.nanos = 5_000_000_000L } }
        val late = env(keys = lateKeys, clock = lateClock)
        assertEquals(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED, rejected { seal(late) }.code)
        assertEquals(1, late.keys.closed)
        val phase = env()
        val phaseEvent = phase.codec.canonicalize(tuple(), emptyList())
        val phaseAttempt = phase.codec.startAttempt()
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true)
            assertThrows(PersistencePhaseException::class.java) { phase.codec.seal(phaseEvent, phaseAttempt) }
            assertEquals(0, phase.keys.generateCalls)
        } finally {
            TransactionSynchronizationManager.clear()
        }
    }

    @Test
    fun `retained historical routes open without active relabel and authenticated tuple or external location mismatches fail`() {
        val initial = env()
        val event = initial.codec.canonicalize(tuple(), emptyList())
        val encoded = initial.codec.seal(event, initial.codec.startAttempt())
        val declared = initial.journal.declaration()
        val rotatedJ = ComplaintJournalConfigurationV1.of(declared.copy(routing = declared.routing.copy(activeKeyId = "route-a")))
        val rotated = env(journal = rotatedJ, keys = initial.keys)
        assertEquals("route-a", rotated.owner.derive(tuple()).active.routingKeyId)
        val opened = rotated.codec.open(rotated.bucket, event.route.objectKey, encoded.wireBytes(), rotated.codec.startAttempt())
        assertEquals("route-b", opened.event.route.routingKeyId)
        assertArrayEquals(event.canonicalBytes(), opened.event.canonicalBytes())
        val historical = rotated.codec.canonicalize(tuple(), emptyList(), "route-b")
        assertEquals(event.route, historical.route)
        val before = initial.keys.unwrapCalls
        rejected { rotated.codec.open("foreign-bucket", event.route.objectKey, encoded.wireBytes(), rotated.codec.startAttempt()) }
        rejected { rotated.codec.open(rotated.bucket, event.route.objectKey + "x", encoded.wireBytes(), rotated.codec.startAttempt()) }
        assertEquals(before, initial.keys.unwrapCalls)
        rejected { rotated.codec.seal(event, rotated.codec.startAttempt()) }
        rejected { rotated.codec.canonicalize(tuple(), emptyList(), "not-retained") }
        val vector = vectors.first()
        val exact = env(keys = Keys(vector))
        val differentTuple = change(vector.payload, "credentialVersion", JsonPrimitive(8))
        val tupleFailure = rejected { open(exact, authenticate(vector, payload = canonical(differentTuple)), vector.header.text("objectKey")) }
        assertEquals(OwnerDeleteAllJournalFailure.INVALID_INPUT, tupleFailure.code)
        assertEquals(1, exact.keys.unwrapCalls)
        val differentId = JsonPrimitive(b64(ByteArray(32) { 19 }))
        val mismatched = authenticate(vector, change(vector.header, "eventId", differentId), canonical(change(vector.payload, "eventId", differentId)))
        val permissive = env(keys = Keys(vector, permitContextMismatch = true))
        val failure = rejected { open(permissive, mismatched, vector.header.text("objectKey")) }
        assertEquals(OwnerDeleteAllJournalFailure.INVALID_INPUT, failure.code)
        assertEquals(1, permissive.keys.unwrapCalls)
    }

    @Test
    fun `only closed live owner delete all payloads with singleton actor owner and sorted zero to hundred UUIDs survive authentication`() {
        val vector = vectors.first()
        val actor = vector.payload.text("actorId")
        val other = "71000000-0000-4000-8000-000000000002"
        val id1 = JsonPrimitive("73000000-0000-1000-8000-000000000001")
        val id2 = JsonPrimitive("73000000-0000-1000-8000-000000000002")
        val mutations = listOf(
            change(vector.payload, "schemaVersion", JsonPrimitive(2)),
            change(vector.payload, "publicationEpoch", JsonPrimitive(0)),
            change(vector.payload, "credentialVersion", JsonPrimitive(0)),
            change(vector.payload, "actorKind", JsonPrimitive("ADMIN")),
            change(vector.payload, "actorId", JsonPrimitive("71000000-0000-1000-8000-000000000001")),
            change(vector.payload, "operationKey", JsonPrimitive("00000000-0000-0000-0000-000000000000")),
            change(vector.payload, "requestFingerprint", JsonPrimitive(vector.payload.text("requestFingerprint") + "=")),
            change(vector.payload, "ownerInstallationIds", JsonArray(emptyList())),
            change(vector.payload, "ownerInstallationIds", JsonArray(listOf(JsonPrimitive(other)))),
            change(vector.payload, "ownerInstallationIds", JsonArray(listOf(JsonPrimitive(actor), JsonPrimitive(other)))),
            change(vector.payload, "complaintIds", JsonArray(listOf(id1, id1))),
            change(vector.payload, "complaintIds", JsonArray(listOf(id2, id1))),
            change(vector.payload, "complaintIds", JsonArray(List(101) { JsonPrimitive("73000000-0000-1000-8000-${it.toString(16).padStart(12, '0')}") })),
            change(vector.payload, "complaintIds", JsonArray(listOf(JsonPrimitive("73000000-0000-1000-8000-00000000000A")))),
            change(vector.payload, "dataScopeKind", JsonPrimitive("TEST")),
            change(vector.payload, "dataScopeId", JsonPrimitive(other)),
            change(vector.payload, "secret", JsonPrimitive("synthetic-forbidden")),
            change(vector.payload, "createdAt", JsonPrimitive("2026-09-17T00:00:00Z")),
            JsonObject(vector.payload - "complaintIds"),
        ) + listOf("OWNER_DELETE", "ADMIN_DELETE", "ADMIN_BATCH_DELETE", "INSTALLATION_RETIREMENT", "TEST_RUN_PURGE", "EPOCH_SEAL").map {
            change(vector.payload, "eventKind", JsonPrimitive(it))
        } + vector.payload.keys.map { change(vector.payload, it, JsonNull) }
        val env = env(keys = Keys(vector))
        mutations.forEach { payload ->
            val before = env.keys.unwrapCalls
            rejected { open(env, authenticate(vector, payload = canonical(payload)), vector.header.text("objectKey")) }
            assertEquals(before + 1, env.keys.unwrapCalls)
        }
        val typed = env()
        rejected { typed.codec.canonicalize(tuple(kind = ComplaintJournalDeletionKindV1.OWNER_DELETE), emptyList()) }
        rejected { typed.codec.canonicalize(tuple(), List(101) { UUID(0, it.toLong()) }) }
        rejected { typed.codec.canonicalize(tuple(), listOf(UUID(0, 1), UUID(0, 1))) }
        rejected { typed.codec.canonicalize(tuple(), listOf(UUID(0, 2), UUID(0, 1))) }
        assertEquals(0, typed.keys.generateCalls)
    }

    private fun env(
        journal: ComplaintJournalConfigurationV1 = journal(),
        keys: Keys = Keys(),
        nonces: Nonces = Nonces(),
        clock: Clock = Clock(),
    ): Env {
        val inputs = journal.declaration().routing.keys.map { key ->
            val binding = VersionedSecretBinding.of(SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING, SecretMaterialPurpose.HMAC_SHA256, key.keyId, key.secret)
            AcquiredVersionedSecret.acquire(binding) { SecretVersionSnapshot(key.secret, ByteArray(32) { (it + (key.keyId.last() - 'a') * 32).toByte() }) }
        }
        return Env(journal, VersionBoundComplaintJournalRouting.fromAcquired(journal, inputs), keys, nonces, clock)
    }

    private fun journal(limits: JournalDecoderLimitsV1 = profile()): ComplaintJournalConfigurationV1 {
        val declared = InitialLiveJournalTestFixture.declaration()
        return ComplaintJournalConfigurationV1.of(declared.copy(limits = declared.limits.copy(decoder = limits)))
    }

    private fun profile(
        envelope: Int = 98_304,
        plaintext: Int = 65_536,
        depth: Int = 16,
        tokens: Int = 4096,
        fields: Int = 32,
        strings: Int = 4096,
        wrapped: Int = 6144,
    ): JournalDecoderLimitsV1 = JournalDecoderLimitsV1(
        envelope, plaintext, depth, minOf(tokens, plaintext), fields, minOf(strings, plaintext), minOf(wrapped, envelope),
    )

    private fun tuple(
        payload: JsonObject = vectors.first().payload,
        kind: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL,
        fingerprint: ByteArray = Base64.getUrlDecoder().decode(payload.text("requestFingerprint")),
    ): ComplaintJournalDeletionTupleV1 = ComplaintJournalDeletionTupleV1(
        payload.getValue("publicationEpoch").jsonPrimitive.long, kind, ComplaintJournalActorKindV1.INSTALLATION,
        UUID.fromString(payload.text("actorId")), payload.getValue("credentialVersion").jsonPrimitive.long,
        UUID.fromString(payload.text("operationKey")), fingerprint, ComplaintDataScope.LIVE,
    )

    private fun seal(env: Env): EncodedOwnerDeleteAllEnvelopeV1 = env.codec.seal(env.codec.canonicalize(tuple(), emptyList()), env.codec.startAttempt())

    private fun open(env: Env, wire: ByteArray, key: String): DecodedOwnerDeleteAllJournalEventV1 =
        env.codec.open(env.bucket, key, wire, env.codec.startAttempt())

    private fun rejected(action: () -> Unit): OwnerDeleteAllJournalException {
        val failure = assertThrows(OwnerDeleteAllJournalException::class.java) { action() }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains("synthetic"))
        assertEquals("Complaint journal codec rejected operation: ${failure.code.name}", failure.message)
        return failure
    }

    private fun authenticate(vector: Vector, header: JsonObject = vector.header, payload: ByteArray = vector.hex("plaintextHex")): ByteArray {
        val wrapped = vector.hex("wrappedKeyHex")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE, SecretKeySpec(vector.hex("dataKeyHex"), "AES"),
            GCMParameterSpec(128, Base64.getUrlDecoder().decode(header.text("nonce"))),
        )
        cipher.updateAAD(independentAad(header, wrapped, payload.size + 16))
        return pack(canonical(header), wrapped, cipher.doFinal(payload))
    }

    private fun independentAad(header: JsonObject, wrapped: ByteArray, ciphertextLength: Int): ByteArray = independentFrame(
        listOf("kira-complaint-journal-aad-v1", "1", "KJEV", "1", canonical(header).size.toString()) + values(header) +
            listOf(wrapped.size.toString(), b64(wrapped), ciphertextLength.toString()),
    )

    private fun values(header: JsonObject): List<String> = HEADER_ORDER.map { header.getValue(it).jsonPrimitive.content }

    private fun independentFrame(fields: List<String>): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            fields.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
        }
        buffer.toByteArray()
    }

    private fun pack(header: ByteArray, wrapped: ByteArray, encrypted: ByteArray): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x4b4a4556)
            output.writeInt(1)
            listOf(header, wrapped, encrypted).forEach {
                output.writeInt(it.size)
                output.write(it)
            }
        }
        buffer.toByteArray()
    }

    private fun parts(wire: ByteArray): Parts {
        val buffer = ByteBuffer.wrap(wire)
        assertEquals(0x4b4a4556, buffer.int)
        assertEquals(1, buffer.int)
        fun section(): ByteArray = ByteArray(buffer.int).also { buffer.get(it) }
        val header = Json.parseToJsonElement(section().toString(Charsets.UTF_8)).jsonObject
        return Parts(header, section(), section())
    }

    private fun canonical(value: JsonObject): ByteArray = JsonObject(value.toSortedMap()).toString().toByteArray(Charsets.UTF_8)
    private fun change(value: JsonObject, key: String, replacement: JsonElement): JsonObject = JsonObject(value + (key to replacement))
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private class Vector(val root: JsonObject) {
        val payload: JsonObject = root.getValue("payload").jsonObject
        val header: JsonObject = root.getValue("header").jsonObject
        fun text(name: String): String = root.getValue(name).jsonPrimitive.content
        fun hex(name: String): ByteArray = HexFormat.of().parseHex(text(name))
    }

    private class Clock(var nanos: Long = 0)
    private class Parts(val header: JsonObject, val wrapped: ByteArray, val encrypted: ByteArray)

    private class Nonces(private val seed: Int = 0) : SecureRandom() {
        private var count = 0
        val destinations = ArrayList<ByteArray>()

        override fun nextBytes(bytes: ByteArray) {
            assertEquals(12, bytes.size)
            destinations.add(bytes)
            bytes.indices.forEach { bytes[it] = (seed + count * 17 + it).toByte() }
            count++
        }
    }

    private class Env(
        val journal: ComplaintJournalConfigurationV1,
        val owner: VersionBoundComplaintJournalRouting,
        val keys: Keys,
        val nonces: Nonces,
        clock: Clock,
    ) {
        val codec = OwnerDeleteAllJournalCodecV1(owner, keys, nonces) { clock.nanos }
        val bucket: String = journal.declaration().journalLocation.bucket
    }

    /** Synthetic key/context store, not KMS qualification. Retained references inspect codec zeroization only. */
    private class Keys(private val vector: Vector? = null, private val permitContextMismatch: Boolean = false) : JournalDataKeyPortV1 {
        var generateCalls = 0
        var unwrapCalls = 0
        var closed = 0
        var returnedKeyBytes = 32
        var returnedWrappedBytes: Int? = null
        var returnedArn: String? = null
        var callFailure: Throwable? = null
        var closeFailure: Throwable? = null
        var arnFailure: Throwable? = null
        var onGenerate: () -> Unit = {}
        val requests = ArrayList<JournalDataKeyRequestV1>()
        val transferred = ArrayList<ByteArray>()
        val unwrapInputs = ArrayList<ByteArray>()
        private val records = HashMap<String, Pair<ByteArray, Map<String, String>>>()

        override fun generate(request: JournalDataKeyRequestV1): JournalGeneratedDataKeyV1 {
            generateCalls++
            requests.add(request)
            callFailure?.let { throw it }
            onGenerate()
            val key = vector?.hex("dataKeyHex") ?: ByteArray(32) { (it + generateCalls * 13).toByte() }
            val wrapped = vector?.hex("wrappedKeyHex") ?: ByteArray(64) { (it + generateCalls * 19).toByte() }
            checkContext(request)
            records[HexFormat.of().formatHex(wrapped)] = key.copyOf() to request.encryptionContext()
            val returnedKey = key.copyOf(returnedKeyBytes).also { transferred.add(it) }
            val returnedWrapped = wrapped.copyOf(returnedWrappedBytes ?: wrapped.size).also { transferred.add(it) }
            return object : JournalGeneratedDataKeyV1 {
                override val keyArn: String get() = responseArn(request)
                override val plaintextKey: ByteArray = returnedKey
                override val wrappedKey: ByteArray = returnedWrapped
                override fun close() = closeLease(returnedKey, returnedWrapped)
            }
        }

        override fun unwrap(request: JournalDataKeyRequestV1, wrappedKey: ByteArray): JournalPlaintextDataKeyV1 {
            unwrapCalls++
            requests.add(request)
            unwrapInputs.add(wrappedKey)
            callFailure?.let { throw it }
            val key = if (vector != null) {
                checkContext(request)
                require(permitContextMismatch || wrappedKey.contentEquals(vector.hex("wrappedKeyHex"))) { "synthetic-wrapper-mismatch" }
                vector.hex("dataKeyHex")
            } else {
                val stored = checkNotNull(records[HexFormat.of().formatHex(wrappedKey)])
                require(stored.second == request.encryptionContext()) { "synthetic-context-mismatch" }
                stored.first.copyOf()
            }
            val returnedKey = key.copyOf(returnedKeyBytes).also { transferred.add(it) }
            return object : JournalPlaintextDataKeyV1 {
                override val keyArn: String get() = responseArn(request)
                override val plaintextKey: ByteArray = returnedKey
                override fun close() = closeLease(returnedKey, null)
            }
        }

        private fun checkContext(request: JournalDataKeyRequestV1) {
            if (vector != null && !permitContextMismatch) {
                require(request.encryptionContext() == mapOf(vector.text("kmsContextKey") to vector.text("kmsContextValue"))) { "synthetic-context-mismatch" }
            }
        }

        private fun responseArn(request: JournalDataKeyRequestV1): String {
            arnFailure?.let { throw it }
            return returnedArn ?: request.keyArn
        }

        private fun closeLease(key: ByteArray, wrapped: ByteArray?) {
            closed++
            assertTrue(key.all { it == 0.toByte() })
            assertTrue(wrapped == null || wrapped.all { it == 0.toByte() })
            closeFailure?.let { throw it }
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
