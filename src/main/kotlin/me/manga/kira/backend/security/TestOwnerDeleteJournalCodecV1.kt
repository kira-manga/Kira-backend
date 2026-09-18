package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Actual J-bound content/cryptography only; never a receipt, PREPARED row, durable object or capability. */
internal class TestOwnerDeleteJournalCodecV1(
    private val routingOwner: TestOwnerDeleteJournalRoutingV1,
    private val dataKeys: JournalDataKeyPortV1,
    private val random: SecureRandom = SecureRandom(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val declaration = routingOwner.journalConfiguration.declaration()
    private val limits = declaration.limits.decoder
    private val writer = declaration.writer.generationId
    private val ordinaryPrefix = routingOwner.journalConfiguration.ordinaryPrefix
    private val json = TestOwnerDeleteJournalJsonV1(limits)
    private val retainedIds = declaration.routing.keys.map { it.keyId }.toSet()

    /** Start once for the entire connection-free publication attempt, not once for each key call. */
    fun startAttempt(enclosingBudget: PersistenceTimeBudget? = null): TestOwnerDeleteCodecAttemptV1 = testDeleteCodecBoundary {
        requireConnectionFree()
        TestOwnerDeleteCodecAttemptV1(routingOwner, nanoTime, enclosingBudget).also { it.remainingMillis(1) }
    }

    /** Null selects active for a new candidate. A retry must supply its already selected retained ID. */
    fun canonicalize(tuple: TestOwnerDeleteJournalTupleV1, complaintIds: List<UUID>, selectedRoutingKeyId: String? = null): TestOwnerDeleteJournalEventV1 =
        testDeleteCodecBoundary {
            requireJournalCodec(tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE)
            requireJournalCodec(tuple.actorKind == ComplaintJournalActorKindV1.INSTALLATION && tuple.scope == routingOwner.journalConfiguration.scope)
            val ids = targetSnapshot(complaintIds)
            val routes = routingOwner.derive(tuple)
            val route = if (selectedRoutingKeyId == null) {
                routes.active
            } else {
                routes.candidates().singleOrNull { it.routingKeyId == selectedRoutingKeyId }
                    ?: throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.INVALID_INPUT)
            }
            val payload = TestOwnerDeleteJournalPayloadV1(
                1, KIND, route.eventId, tuple.epoch, writer, "INSTALLATION", tuple.actorId.toString(),
                checkNotNull(tuple.credentialVersion), tuple.operationKey.toString(), tuple.encodedFingerprint(),
                listOf(tuple.actorId.toString()), "TEST", routingOwner.journalConfiguration.scope.id.toString(), ids.map(UUID::toString),
            )
            withTestDeleteBuffers { buffers ->
                val canonical = buffers.own(json.encodePayload(payload))
                val bound = bindTestOwnerDeletePayload(routingOwner, payload, route.routingKeyId)
                event(bound, canonical)
            }
        }

    /** Fresh randomized candidate. Its exact bytes may be reused, but it is not durably frozen evidence. */
    fun seal(event: TestOwnerDeleteJournalEventV1, attempt: TestOwnerDeleteCodecAttemptV1): EncodedTestOwnerDeleteEnvelopeV1 = testDeleteCodecBoundary {
        attempt.requireOwner(routingOwner)
        attempt.remainingMillis(1)
        requireJournalCodec(event.belongsTo(routingOwner))
        requireJournalCodec(event.byteCount <= limits.maximumPlaintextBytes, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        withTestDeleteBuffers { buffers ->
            val plaintext = buffers.own(event.canonicalBytes())
            val bound = bindTestOwnerDeletePayload(routingOwner, json.payload(plaintext), event.route.routingKeyId)
            requireJournalCodec(bound.route == event.route)
            val nonce = buffers.own(ByteArray(NONCE_BYTES))
            random.nextBytes(nonce)
            val header = header(bound.route, bound.tuple.epoch, encode(nonce))
            val headerBytes = buffers.own(json.encodeHeader(header))
            val ciphertextLength = plaintext.size + TAG_BYTES
            val availableWrappedBytes = limits.maximumEnvelopeBytes.toLong() - OUTER_BYTES - headerBytes.size - ciphertextLength
            requireJournalCodec(availableWrappedBytes > 0, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
            val request = request(header, minOf(limits.maximumWrappedKeyBytes.toLong(), availableWrappedBytes).toInt(), attempt, buffers)
            requireConnectionFree()
            val lease = journalKeyCall { dataKeys.generate(request) }
            var selectedWrapped: ByteArray? = null
            val ciphertext = withJournalDataKey(request, lease, generated = true) { key, wrapped ->
                attempt.remainingMillis(1)
                val selected = buffers.own(checkNotNull(wrapped).copyOf()).also { selectedWrapped = it }
                val aad = buffers.own(aad(header, headerBytes.size, selected, ciphertextLength))
                buffers.own(crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext))
            }
            attempt.remainingMillis(1)
            requireJournalCodec(ciphertext.size == ciphertextLength, OwnerDeleteAllJournalFailure.CRYPTO_FAILURE)
            val wire = buffers.own(envelope(headerBytes, checkNotNull(selectedWrapped), ciphertext))
            attempt.remainingMillis(1)
            EncodedTestOwnerDeleteEnvelopeV1(bound.route, Sha256.hex(plaintext), wire)
        }
    }

    /** Caller must independently supply the storage location. A decoded event is not S3/version evidence. */
    fun open(expectedBucket: String, expectedObjectKey: String, wireBytes: ByteArray, attempt: TestOwnerDeleteCodecAttemptV1): DecodedTestOwnerDeleteJournalEventV1 =
        testDeleteCodecBoundary {
            attempt.requireOwner(routingOwner)
            attempt.remainingMillis(1)
            requireJournalCodec(expectedBucket == declaration.journalLocation.bucket)
            requireJournalCodec(expectedObjectKey.length in 1..1024 && expectedObjectKey.all { it in ' '..'~' })
            requireJournalCodec(wireBytes.size in OUTER_BYTES..limits.maximumEnvelopeBytes, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
            withTestDeleteBuffers { buffers ->
                val wire = buffers.own(wireBytes.copyOf())
                val parts = split(wire, buffers)
                val header = json.header(parts.header)
                val nonce = buffers.own(bindHeader(header, expectedBucket, expectedObjectKey))
                val associatedData = buffers.own(aad(header, parts.header.size, parts.wrapped, parts.ciphertext.size))
                val wrappedForPort = buffers.own(parts.wrapped.copyOf())
                requireConnectionFree()
                val request = request(header, limits.maximumWrappedKeyBytes, attempt, buffers)
                val lease = try {
                    journalKeyCall { dataKeys.unwrap(request, wrappedForPort) }
                } finally {
                    wrappedForPort.fill(0)
                }
                val plaintext = withJournalDataKey(request, lease, generated = false) { key, _ ->
                    attempt.remainingMillis(1)
                    // One doFinal: no unauthenticated update output is released or parsed.
                    buffers.own(crypt(Cipher.DECRYPT_MODE, key, nonce, associatedData, parts.ciphertext))
                }
                attempt.remainingMillis(1)
                requireJournalCodec(plaintext.size <= limits.maximumPlaintextBytes, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
                val payload = json.payload(plaintext)
                requireJournalCodec(payload.eventId == header.eventId && payload.publicationEpoch == header.publicationEpoch)
                val bound = bindTestOwnerDeletePayload(routingOwner, payload, header.routingKeyId)
                requireJournalCodec(bound.route.objectKey == expectedObjectKey && bound.route.eventId == header.eventId)
                attempt.remainingMillis(1)
                DecodedTestOwnerDeleteJournalEventV1(event(bound, plaintext), Sha256.hex(wire))
            }
        }

    private fun event(bound: TestOwnerDeleteBoundPayload, canonical: ByteArray): TestOwnerDeleteJournalEventV1 = TestOwnerDeleteJournalEventV1(
        routingOwner,
        bound.tuple,
        bound.targets,
        bound.route,
        canonical,
    )

    private fun bindHeader(value: TestOwnerDeleteJournalHeaderV1, expectedBucket: String, expectedKey: String): ByteArray {
        requireJournalCodec(value.envelopeSchemaVersion == 1 && value.payloadSchemaVersion == 1 && value.canonicalizerId == "kcj-1")
        requireJournalCodec(value.objectKind == KIND && value.encryptionAlgorithm == "AES-256-GCM" && value.dataKeyMode == DATA_KEY_MODE)
        requireJournalCodec(value.kmsKeyId == declaration.encryption.keyId && value.kmsKeyArn == declaration.encryption.keyArn)
        requireJournalCodec(value.bucket == expectedBucket && value.objectKey == expectedKey)
        requireJournalCodec(value.writerGeneration == writer && value.ordinaryPrefix == ordinaryPrefix)
        requireJournalCodec(value.dataScopeKind == "TEST" && value.dataScopeId == routingOwner.journalConfiguration.scope.id.toString() && value.publicationEpoch > 0)
        requireJournalCodec(value.routingKeyId in retainedIds)
        canonicalOpaque(value.eventId)
        val prefix = "${ordinaryPrefix}writer/$writer/epoch/${value.publicationEpoch.toString().padStart(19, '0')}/${value.routingKeyId}/"
        requireJournalCodec(expectedKey.startsWith(prefix) && expectedKey.length == prefix.length + 43)
        canonicalOpaque(expectedKey.substring(prefix.length))
        requireJournalCodec(value.nonce.length == 16 && BASE64_URL.matches(value.nonce))
        val nonce = Base64.getUrlDecoder().decode(value.nonce)
        if (nonce.size != NONCE_BYTES || encode(nonce) != value.nonce) {
            nonce.fill(0)
            throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.INVALID_INPUT)
        }
        return nonce
    }

    private fun canonicalOpaque(value: String) {
        val decoded = ComplaintIdentifiers.fingerprint(value)
        decoded.fill(0)
    }

    private fun targetSnapshot(ids: List<UUID>): List<UUID> {
        val count = ids.size
        requireJournalCodec(count == 1, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        val iterator = ids.iterator()
        val copied = ArrayList<UUID>(count)
        repeat(count) {
            requireJournalCodec(iterator.hasNext())
            copied.add(iterator.next())
        }
        requireJournalCodec(!iterator.hasNext())
        requireJournalCodec(copied.zipWithNext().all { (left, right) -> left.toString() < right.toString() })
        return copied
    }

    private fun header(route: TestOwnerDeleteRoutingCandidateV1, epoch: Long, nonce: String): TestOwnerDeleteJournalHeaderV1 = TestOwnerDeleteJournalHeaderV1(
        1, 1, "kcj-1", KIND, "AES-256-GCM", DATA_KEY_MODE, declaration.encryption.keyId, declaration.encryption.keyArn,
        declaration.journalLocation.bucket, route.objectKey, writer, ordinaryPrefix, "TEST", routingOwner.journalConfiguration.scope.id.toString(),
        epoch, route.routingKeyId, route.eventId, nonce,
    )

    private fun request(
        header: TestOwnerDeleteJournalHeaderV1,
        maximumWrappedBytes: Int,
        attempt: TestOwnerDeleteCodecAttemptV1,
        buffers: TestDeleteOwnedBuffers,
    ): JournalDataKeyRequestV1 {
        val contextFrame = buffers.own(frame(listOf(KMS_DOMAIN, "1") + header.framedValues()))
        val context = encode(contextFrame)
        // The fixed key and Base64url value are ASCII: characters are exactly UTF-8 bytes here.
        requireJournalCodec(CONTEXT_KEY.length + context.length <= 8192, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        return JournalDataKeyRequestV1(
            declaration.encryption.keyArn,
            mapOf(CONTEXT_KEY to context),
            maximumWrappedBytes,
            attempt.remainingMillis(declaration.limits.deadlines.kmsCallMillis),
        )
    }

    private fun aad(header: TestOwnerDeleteJournalHeaderV1, headerLength: Int, wrapped: ByteArray, ciphertextLength: Int): ByteArray = frame(
        listOf(AAD_DOMAIN, "1", "KJEV", "1", headerLength.toString()) + header.framedValues() +
            listOf(wrapped.size.toString(), encode(wrapped), ciphertextLength.toString()),
    )

    private fun frame(fields: List<String>): ByteArray {
        val encoded = ArrayList<ByteArray>(fields.size)
        try {
            fields.forEach { encoded.add(it.toByteArray(Charsets.UTF_8)) }
            val length = encoded.sumOf { 4L + it.size }
            requireJournalCodec(length <= 2L * 98_304, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
            val buffer = ByteBuffer.allocate(length.toInt()).order(ByteOrder.BIG_ENDIAN)
            encoded.forEach { buffer.putInt(it.size).put(it) }
            return buffer.array()
        } finally {
            encoded.forEach { it.fill(0) }
        }
    }

    private fun envelope(header: ByteArray, wrapped: ByteArray, ciphertext: ByteArray): ByteArray {
        val length = OUTER_BYTES.toLong() + header.size + wrapped.size + ciphertext.size
        requireJournalCodec(length <= limits.maximumEnvelopeBytes, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        return ByteBuffer.allocate(length.toInt()).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putInt(1).putInt(header.size).put(header)
            .putInt(wrapped.size).put(wrapped).putInt(ciphertext.size).put(ciphertext).array()
    }

    private fun split(wire: ByteArray, buffers: TestDeleteOwnedBuffers): TestDeleteEnvelopeParts {
        val input = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN)
        requireJournalCodec(input.remaining() >= OUTER_BYTES && input.int == MAGIC && input.int == 1)
        val header = section(input, 1, minOf(4096, limits.maximumPlaintextBytes), buffers)
        val wrapped = section(input, 1, limits.maximumWrappedKeyBytes, buffers)
        val ciphertext = section(input, TAG_BYTES + 1, limits.maximumPlaintextBytes + TAG_BYTES, buffers)
        requireJournalCodec(!input.hasRemaining())
        return TestDeleteEnvelopeParts(header, wrapped, ciphertext)
    }

    private fun section(input: ByteBuffer, minimum: Int, maximum: Int, buffers: TestDeleteOwnedBuffers): ByteArray {
        requireJournalCodec(input.remaining() >= 4)
        val length = input.int.toLong() and 0xffff_ffffL
        requireJournalCodec(length in minimum.toLong()..maximum.toLong(), OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        requireJournalCodec(length <= input.remaining().toLong())
        val bytes = buffers.own(ByteArray(length.toInt()))
        input.get(bytes)
        return bytes
    }

    private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(input)
    } catch (_: AEADBadTagException) {
        throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED)
    } catch (_: GeneralSecurityException) {
        throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.CRYPTO_FAILURE)
    }

    override fun toString(): String = "TestOwnerDeleteJournalCodecV1(redacted,no-authority)"

    private class TestDeleteEnvelopeParts(val header: ByteArray, val wrapped: ByteArray, val ciphertext: ByteArray)

    companion object {
        /** Portless local restoration only; callers must independently match the complete durable row and proof. */
        fun restoreCanonical(
            routingOwner: TestOwnerDeleteJournalRoutingV1,
            canonicalBytes: ByteArray,
            selectedRoutingKeyId: String,
        ): TestOwnerDeleteJournalEventV1 = testDeleteCodecBoundary {
            val limits = routingOwner.journalConfiguration.declaration().limits.decoder
            requireJournalCodec(canonicalBytes.size in 1..limits.maximumPlaintextBytes, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
            withTestDeleteBuffers { buffers ->
                val canonical = buffers.own(canonicalBytes.copyOf())
                val bound = bindTestOwnerDeletePayload(routingOwner, TestOwnerDeleteJournalJsonV1(limits).payload(canonical), selectedRoutingKeyId)
                TestOwnerDeleteJournalEventV1(routingOwner, bound.tuple, bound.targets, bound.route, canonical)
            }
        }

        private const val MAGIC = 0x4b4a4556
        private const val OUTER_BYTES = 20
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val KIND = "OWNER_DELETE"
        private const val DATA_KEY_MODE = "FRESH_PER_OBJECT_KMS_WRAPPED"
        private const val KMS_DOMAIN = "kira-complaint-journal-kms-context-v1"
        private const val AAD_DOMAIN = "kira-complaint-journal-aad-v1"
        private const val CONTEXT_KEY = "kira-complaint-journal-context-v1"
        private val BASE64_URL = Regex("[A-Za-z0-9_-]+")

        private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Shared monotonic time accounting only, never durable authorization or an external-effect verdict. */
internal class TestOwnerDeleteCodecAttemptV1(
    private val owner: TestOwnerDeleteJournalRoutingV1,
    private val nanoTime: () -> Long,
    private val enclosingBudget: PersistenceTimeBudget? = null,
) {
    private val started = nanoTime()
    private val allowanceNanos = owner.journalConfiguration.declaration().limits.deadlines.publicationAttemptMillis * 1_000_000L
    private var lastElapsed = 0L
    private var expired = false

    init {
        if (enclosingBudget != null) remainingMillis(1)
    }

    internal fun requireOwner(expected: TestOwnerDeleteJournalRoutingV1) = requireJournalCodec(owner === expected)

    @Synchronized
    fun remainingMillis(ceilingMillis: Int): Int {
        requireJournalCodec(ceilingMillis > 0)
        val elapsed = nanoTime() - started
        val remaining = (allowanceNanos - elapsed) / 1_000_000L
        val invalidClock = elapsed < lastElapsed || elapsed < 0
        val exhausted = elapsed >= allowanceNanos || remaining <= 0
        if (expired || invalidClock || exhausted) {
            expired = true
            throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED)
        }
        lastElapsed = elapsed
        val local = minOf(remaining, ceilingMillis.toLong())
        val outer = runCatching { enclosingBudget?.remainingMillis(local) ?: local }
        if (outer.isFailure) expired = true
        return try {
            outer.getOrThrow().toInt()
        } catch (failure: PersistenceBoundaryException) {
            val code = if (failure.code == PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) {
                OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED
            } else {
                OwnerDeleteAllJournalFailure.INVALID_INPUT
            }
            throw OwnerDeleteAllJournalException(code)
        }
    }

    override fun toString(): String = "TestOwnerDeleteCodecAttemptV1(redacted,no-authority)"
}

/** Immutable canonical content bound to the actual routing owner; not authorization or an outbox row. */
internal class TestOwnerDeleteJournalEventV1(
    private val owner: TestOwnerDeleteJournalRoutingV1,
    val tuple: TestOwnerDeleteJournalTupleV1,
    targets: List<UUID>,
    val route: TestOwnerDeleteRoutingCandidateV1,
    canonical: ByteArray,
) {
    private val storedTargets = targets.toList()
    private val storedCanonical = canonical.copyOf()
    val semanticSha256: String = Sha256.hex(storedCanonical)
    internal val byteCount: Int get() = storedCanonical.size

    internal fun belongsTo(candidate: TestOwnerDeleteJournalRoutingV1): Boolean = owner === candidate
    fun canonicalBytes(): ByteArray = storedCanonical.copyOf()
    fun complaintIds(): List<UUID> = storedTargets.toList()

    override fun toString(): String = "TestOwnerDeleteJournalEventV1(redacted,no-authority)"
}

/** Candidate bytes only. A retry may retain this value, but nothing here certifies durable persistence. */
internal class EncodedTestOwnerDeleteEnvelopeV1(val route: TestOwnerDeleteRoutingCandidateV1, val semanticSha256: String, wire: ByteArray) {
    private val storedWire = wire.copyOf()
    val wireSha256: String = Sha256.hex(storedWire)

    fun wireBytes(): ByteArray = storedWire.copyOf()

    override fun toString(): String = "EncodedTestOwnerDeleteEnvelopeV1(redacted,no-authority)"
}

/** Tag-verified content and local wire digest, explicitly not provider/version/retention evidence. */
internal class DecodedTestOwnerDeleteJournalEventV1(val event: TestOwnerDeleteJournalEventV1, val wireSha256: String) {
    override fun toString(): String = "DecodedTestOwnerDeleteJournalEventV1(redacted,no-authority)"
}

private class TestOwnerDeleteBoundPayload(val tuple: TestOwnerDeleteJournalTupleV1, val targets: List<UUID>, val route: TestOwnerDeleteRoutingCandidateV1)

private fun bindTestOwnerDeletePayload(
    routingOwner: TestOwnerDeleteJournalRoutingV1,
    value: TestOwnerDeleteJournalPayloadV1,
    selectedId: String,
): TestOwnerDeleteBoundPayload {
    val declaration = routingOwner.journalConfiguration.declaration()
    val writer = declaration.writer.generationId
    val retainedIds = declaration.routing.keys.map { it.keyId }.toSet()
    requireJournalCodec(value.schemaVersion == 1 && value.eventKind == "OWNER_DELETE" && value.actorKind == "INSTALLATION")
    requireJournalCodec(value.writerGeneration == writer && value.publicationEpoch > 0 && value.credentialVersion > 0)
    requireJournalCodec(value.dataScopeKind == "TEST" && value.dataScopeId == routingOwner.journalConfiguration.scope.id.toString() && selectedId in retainedIds)
    val actor = ComplaintIdentifiers.installationId(value.actorId)
    val operation = ComplaintIdentifiers.idempotencyKey(value.operationKey)
    requireJournalCodec(value.ownerInstallationIds == listOf(value.actorId))
    requireJournalCodec(value.complaintIds.size == 1, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
    val targets = value.complaintIds.map(ComplaintIdentifiers::resourceId)
    requireJournalCodec(value.complaintIds.zipWithNext().all { (left, right) -> left < right })
    val fingerprint = ComplaintIdentifiers.fingerprint(value.requestFingerprint)
    val tuple = try {
        TestOwnerDeleteJournalTupleV1(
            value.publicationEpoch,
            actor,
            value.credentialVersion,
            operation,
            fingerprint,
            routingOwner.journalConfiguration.scope,
        )
    } finally {
        fingerprint.fill(0)
    }
    val route = routingOwner.derive(tuple).candidates().single { it.routingKeyId == selectedId }
    requireJournalCodec(value.eventId == route.eventId)
    return TestOwnerDeleteBoundPayload(tuple, targets, route)
}

private class TestDeleteOwnedBuffers {
    private val owned = ArrayList<ByteArray>()
    fun own(bytes: ByteArray): ByteArray = bytes.also { owned.add(it) }
    fun clear() = owned.forEach { it.fill(0) }
}

private fun <T> withTestDeleteBuffers(action: (TestDeleteOwnedBuffers) -> T): T {
    val buffers = TestDeleteOwnedBuffers()
    try {
        return action(buffers)
    } finally {
        buffers.clear()
    }
}

@Suppress("TooGenericExceptionCaught")
private fun <T> testDeleteCodecBoundary(action: () -> T): T = try {
    action()
} catch (failure: OwnerDeleteAllJournalException) {
    throw failure
} catch (failure: PersistencePhaseException) {
    throw failure
} catch (_: CancellationException) {
    throw CancellationException("Complaint journal operation cancelled.")
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    throw InterruptedException("Complaint journal operation interrupted.")
} catch (_: Exception) {
    throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.INVALID_INPUT)
}
