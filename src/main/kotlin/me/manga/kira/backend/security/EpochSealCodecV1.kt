package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher

/** Actual same-J seal cryptography. Nothing here is a lease, complete database pass, durable intent or publication authority. */
internal class EpochSealCodecV1(
    private val routingOwner: VersionBoundComplaintJournalRouting,
    private val dataKeys: JournalDataKeyPortV1,
    private val random: SecureRandom = SecureRandom(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val declaration = routingOwner.journalConfiguration.declaration()
    private val limits = declaration.limits.decoder
    private val writer = declaration.writer.generationId
    private val sealPrefix = OfflineBootstrapGrammar.sealTerminalPrefix(writer)
    private val retainedIds = declaration.routing.keys.map { it.keyId }.toSet()
    private val json = EpochSealJsonV1(limits)
    private val wire = EpochSealWireV1(limits)

    /** Start once before the complete seal operation. A surrounding scan passes its genuine original budget. */
    fun startAttempt(enclosingBudget: PersistenceTimeBudget? = null): EpochSealAttemptV1 = epochSealBoundary {
        requireConnectionFree()
        EpochSealAttemptV1(routingOwner, nanoTime, enclosingBudget).also { it.remainingMillis(1) }
    }

    /** Count/root come only from the two-pass helper; durable completeness and initial-G1 acceptance remain outside the codec. */
    fun canonicalize(manifest: EpochSealManifestV1, preparingFencingToken: Long, attempt: EpochSealAttemptV1): EpochSealContentV1 = epochSealBoundary {
        requireConnectionFree()
        manifest.requireOwner(routingOwner, attempt)
        requireEpochSeal(preparingFencingToken > 0)
        val route = routingOwner.deriveEpochSeal(EpochSealRoutingTupleV1(manifest.range, manifest.eventManifestSha256)).active
        val payload = EpochSealPayloadV1(
            1, KIND, route.sealId, writer, "LIVE", LIVE_SCOPE, manifest.range.epochStartInclusive, manifest.range.epochEndInclusive,
            manifest.eventCount, manifest.eventManifestSha256, manifest.range.precedingSealSha256, preparingFencingToken,
        )
        withEpochSealBuffers { buffers ->
            val canonical = buffers.own(json.encodePayload(payload))
            requireEpochSeal(bindPayload(payload, route.routingKeyId) == route)
            attempt.remainingMillis(1)
            EpochSealContentV1(routingOwner, payload, route, canonical)
        }
    }

    /** Revalidate exact frozen canonical bytes; never regenerate a successor token, ID, key or payload. */
    fun restoreCanonical(
        canonicalBytes: ByteArray,
        selectedRoutingKeyId: String,
        expectedObjectKey: String,
        expectedSemanticSha256: String,
        attempt: EpochSealAttemptV1,
    ): EpochSealContentV1 = epochSealBoundary {
        checkAttempt(attempt)
        requireEpochSeal(canonicalBytes.size in 1..limits.maximumPlaintextBytes, EpochSealFailureV1.LIMIT_EXCEEDED)
        requireEpochSeal(OfflineBootstrapGrammar.sha256(expectedSemanticSha256))
        withEpochSealBuffers { buffers ->
            val canonical = buffers.own(canonicalBytes.copyOf())
            requireEpochSeal(Sha256.hex(canonical) == expectedSemanticSha256)
            val payload = json.payload(canonical)
            val route = bindPayload(payload, selectedRoutingKeyId)
            requireEpochSeal(route.objectKey == expectedObjectKey)
            attempt.remainingMillis(1)
            EpochSealContentV1(routingOwner, payload, route, canonical)
        }
    }

    /** Fresh candidate ONLY for a missing wire stage. A retry of wire-ready work must reuse its durable exact bytes instead. */
    fun seal(content: EpochSealContentV1, attempt: EpochSealAttemptV1): EpochSealEnvelopeV1 = epochSealBoundary {
        checkAttempt(attempt)
        withEpochSealBuffers { buffers ->
            val plaintext = checkedContent(content, buffers)
            val nonce = buffers.own(ByteArray(EpochSealWireV1.NONCE_BYTES))
            random.nextBytes(nonce)
            val header = header(content, EpochSealFramesV1.encode(nonce))
            val headerBytes = buffers.own(json.encodeHeader(header))
            val ciphertextLength = plaintext.size + EpochSealWireV1.TAG_BYTES
            val available = limits.maximumEnvelopeBytes.toLong() - EpochSealWireV1.OUTER_BYTES - headerBytes.size - ciphertextLength
            requireEpochSeal(available > 0, EpochSealFailureV1.LIMIT_EXCEEDED)
            val request = request(header, minOf(limits.maximumWrappedKeyBytes.toLong(), available).toInt(), attempt, buffers)
            requireConnectionFree()
            val lease = journalKeyCall { dataKeys.generate(request) }
            var selectedWrapped: ByteArray? = null
            val ciphertext = withJournalDataKey(request, lease, generated = true) { key, wrapped ->
                attempt.remainingMillis(1)
                val selected = buffers.own(checkNotNull(wrapped).copyOf()).also { selectedWrapped = it }
                val aad = buffers.own(aad(header, headerBytes.size, selected, ciphertextLength))
                buffers.own(EpochSealWireV1.crypt(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext))
            }
            attempt.remainingMillis(1)
            requireEpochSeal(ciphertext.size == ciphertextLength, EpochSealFailureV1.CRYPTO_FAILURE)
            val bytes = buffers.own(wire.pack(headerBytes, checkNotNull(selectedWrapped), ciphertext))
            attempt.remainingMillis(1)
            EpochSealEnvelopeV1(content, bytes)
        }
    }

    /** Independent storage coordinates AND frozen expected content; plaintext equality is not all-version/retention evidence. */
    fun open(
        expectedBucket: String,
        expectedObjectKey: String,
        expected: EpochSealContentV1,
        wireBytes: ByteArray,
        attempt: EpochSealAttemptV1,
    ): EpochSealDecodedV1 = epochSealBoundary {
        checkAttempt(attempt)
        requireEpochSeal(expectedBucket == declaration.journalLocation.bucket && expectedObjectKey == expected.route.objectKey)
        requireEpochSeal(wireBytes.size in EpochSealWireV1.OUTER_BYTES..limits.maximumEnvelopeBytes, EpochSealFailureV1.LIMIT_EXCEEDED)
        withEpochSealBuffers { buffers ->
            val canonical = checkedContent(expected, buffers)
            val bytes = buffers.own(wireBytes.copyOf())
            val parts = wire.split(bytes, buffers)
            val header = json.header(parts.header)
            val nonce = buffers.own(bindHeader(header, expected))
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
                buffers.own(EpochSealWireV1.crypt(Cipher.DECRYPT_MODE, key, nonce, associatedData, parts.ciphertext))
            }
            attempt.remainingMillis(1)
            requireEpochSeal(plaintext.size <= limits.maximumPlaintextBytes, EpochSealFailureV1.LIMIT_EXCEEDED)
            val payload = json.payload(plaintext)
            requireEpochSeal(bindPayload(payload, header.routingKeyId) == expected.route)
            requireEpochSeal(payload == expected.payload && plaintext.contentEquals(canonical))
            attempt.remainingMillis(1)
            EpochSealDecodedV1(expected, Sha256.hex(bytes))
        }
    }

    private fun checkAttempt(attempt: EpochSealAttemptV1) {
        attempt.requireOwner(routingOwner)
        attempt.remainingMillis(1)
    }

    private fun checkedContent(content: EpochSealContentV1, buffers: EpochSealBuffersV1): ByteArray {
        requireEpochSeal(content.belongsTo(routingOwner))
        requireEpochSeal(content.byteCount <= limits.maximumPlaintextBytes, EpochSealFailureV1.LIMIT_EXCEEDED)
        val canonical = buffers.own(content.canonicalBytes())
        val payload = json.payload(canonical)
        requireEpochSeal(content.payload == payload && content.semanticSha256 == Sha256.hex(canonical))
        requireEpochSeal(bindPayload(payload, content.route.routingKeyId) == content.route)
        return canonical
    }

    private fun bindPayload(value: EpochSealPayloadV1, selectedId: String): EpochSealRoutingCandidateV1 {
        requireEpochSeal(value.schemaVersion == 1 && value.objectKind == KIND)
        requireEpochSeal(value.writerGeneration == writer && value.dataScopeKind == "LIVE" && value.dataScopeId == LIVE_SCOPE)
        requireEpochSeal(value.preparingFencingToken > 0 && value.eventCount in 0..declaration.limits.capacity.maximumRetainedVersions)
        requireEpochSeal(selectedId in retainedIds)
        val range = EpochSealRangeV1(value.epochStartInclusive, value.epochEndInclusive, value.precedingSealSha256)
        val routes = routingOwner.deriveEpochSeal(EpochSealRoutingTupleV1(range, value.eventManifestSha256))
        val route = routes.candidates().single { it.routingKeyId == selectedId }
        requireEpochSeal(value.sealId == route.sealId)
        return route
    }

    private fun bindHeader(value: EpochSealHeaderV1, expected: EpochSealContentV1): ByteArray {
        // Key/range/predecessor/opaque identity must agree with the already checked frozen content BEFORE KMS.
        requireEpochSeal(value == header(expected, value.nonce))
        requireEpochSeal(
            value.nonce.length == 16 && value.nonce.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' },
        )
        val nonce = Base64.getUrlDecoder().decode(value.nonce)
        if (nonce.size != EpochSealWireV1.NONCE_BYTES || EpochSealFramesV1.encode(nonce) != value.nonce) {
            nonce.fill(0)
            throw EpochSealExceptionV1(EpochSealFailureV1.INVALID_INPUT)
        }
        return nonce
    }

    private fun header(content: EpochSealContentV1, nonce: String): EpochSealHeaderV1 = EpochSealHeaderV1(
        1, 1, "kcj-1", KIND, "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED", declaration.encryption.keyId, declaration.encryption.keyArn,
        declaration.journalLocation.bucket, content.route.objectKey, writer, sealPrefix, "LIVE", LIVE_SCOPE, content.payload.epochStartInclusive,
        content.payload.epochEndInclusive, content.route.routingKeyId, content.route.sealId, content.payload.precedingSealSha256, nonce,
    )

    private fun request(
        header: EpochSealHeaderV1,
        maximumWrappedBytes: Int,
        attempt: EpochSealAttemptV1,
        buffers: EpochSealBuffersV1,
    ): JournalDataKeyRequestV1 {
        val contextFrame = buffers.own(EpochSealFramesV1.frame(listOf(EpochSealFramesV1.KMS_DOMAIN, "1") + header.framedValues()))
        val context = EpochSealFramesV1.encode(contextFrame)
        requireEpochSeal(EpochSealFramesV1.CONTEXT_KEY.length + context.length <= 8192, EpochSealFailureV1.LIMIT_EXCEEDED)
        return JournalDataKeyRequestV1(
            declaration.encryption.keyArn,
            mapOf(EpochSealFramesV1.CONTEXT_KEY to context),
            maximumWrappedBytes,
            attempt.remainingMillis(declaration.limits.deadlines.kmsCallMillis),
        )
    }

    private fun aad(header: EpochSealHeaderV1, headerLength: Int, wrapped: ByteArray, ciphertextLength: Int): ByteArray = EpochSealFramesV1.frame(
        listOf(EpochSealFramesV1.AAD_DOMAIN, "1", "KJEV", "1", headerLength.toString()) + header.framedValues() +
            listOf(wrapped.size.toString(), EpochSealFramesV1.encode(wrapped), ciphertextLength.toString()),
    )

    override fun toString(): String = "EpochSealCodecV1(redacted,no-authority)"

    private companion object {
        const val KIND = "EPOCH_SEAL"
        const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
    }
}
