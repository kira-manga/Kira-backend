package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventHeaderV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealHeaderV1
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher

/** Dormant typed TEST-terminal cryptography, never registered-run, producer, persistence or provider authority. */
internal class TestTerminalCodecV1 private constructor(
    private val journal: TestOwnerDeleteJournalConfigurationV1,
    routing: TestTerminalRoutingV1,
    private val random: SecureRandom,
    private val nanoTime: () -> Long,
) {
    private val owner = Any()
    private val declaration = journal.declaration()
    private val canonical = TestTerminalCanonicalV1(owner, journal, routing)
    private val json = TestTerminalJsonV1(journal)
    private val wire = TestTerminalWireV1(declaration.limits.decoder)

    /** Start once before the complete operation; retries inside it cannot replace the original enclosing budget. */
    fun startAttempt(kind: TestTerminalCodecKindV1, enclosingBudget: PersistenceTimeBudget? = null): TestTerminalAttemptV1 =
        testTerminalCodecBoundary {
            requireConnectionFree()
            TestTerminalAttemptV1(owner, journal, kind, nanoTime, enclosingBudget).also { it.remainingMillis(1) }
        }

    fun canonicalizeInstallationManifest(
        value: TestTerminalInstallationManifestV1,
        attempt: TestTerminalAttemptV1,
        selectedRoutingKeyId: String? = null,
    ): TestTerminalContentV1 = canonical.canonicalizeInstallationManifest(value, attempt, selectedRoutingKeyId)

    fun canonicalizePurge(
        value: TestTerminalPurgeV1,
        attempt: TestTerminalAttemptV1,
        selectedRoutingKeyId: String? = null,
    ): TestTerminalContentV1 = canonical.canonicalizePurge(value, attempt, selectedRoutingKeyId)

    fun canonicalizeEpochSeal(
        value: TestTerminalEpochSealV1,
        attempt: TestTerminalAttemptV1,
        selectedRoutingKeyId: String? = null,
    ): TestTerminalContentV1 = canonical.canonicalizeEpochSeal(value, attempt, selectedRoutingKeyId)

    /** Exact persisted bytes, selected retained route and hash only; never repair an ID or switch to the active key. */
    fun restoreCanonical(
        kind: TestTerminalCodecKindV1,
        canonicalBytes: ByteArray,
        selectedRoutingKeyId: String,
        expectedObjectKey: String,
        expectedCanonicalSha256: String,
        attempt: TestTerminalAttemptV1,
    ): TestTerminalContentV1 = canonical.restoreCanonical(
        kind, canonicalBytes, selectedRoutingKeyId, expectedObjectKey, expectedCanonicalSha256, attempt,
    )

    /** Fresh candidate for a missing wire stage only; wire-ready retries must reuse their exact frozen bytes. */
    fun seal(
        content: TestTerminalContentV1,
        attempt: TestTerminalAttemptV1,
        dataKeys: TestTerminalDataKeyPortV1,
    ): TestTerminalEnvelopeV1 = testTerminalCodecBoundary {
        canonical.checkAttempt(attempt, content.kind)
        requireTestTerminalCodec(dataKeys.attempt === attempt)
        withTestTerminalBuffers { buffers ->
            val checked = canonical.checkedContent(content, buffers)
            val nonce = buffers.own(ByteArray(TestTerminalWireV1.NONCE_BYTES))
            random.nextBytes(nonce)
            val header = header(checked.binding, TestTerminalWireV1.encode(nonce), buffers)
            val ciphertextLength = checked.bytes.size + TestTerminalWireV1.TAG_BYTES
            val available = wire.maximumEnvelopeBytes.toLong() - TestTerminalWireV1.OUTER_BYTES - header.bytes.size - ciphertextLength
            requireTestTerminalCodec(available > 0, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
            val request = request(header, minOf(wire.maximumWrappedBytes.toLong(), available).toInt(), attempt, buffers)
            requireConnectionFree()
            val lease = journalKeyCall { dataKeys.generate(request) }
            var selectedWrapped: ByteArray? = null
            val ciphertext = withJournalDataKey(request, lease, generated = true) { key, wrapped ->
                attempt.remainingMillis(1)
                val selected = buffers.own(checkNotNull(wrapped).copyOf()).also { selectedWrapped = it }
                val associatedData = buffers.own(aad(header, selected, ciphertextLength))
                buffers.own(TestTerminalWireV1.crypt(Cipher.ENCRYPT_MODE, key, nonce, associatedData, checked.bytes))
            }
            attempt.remainingMillis(1)
            requireTestTerminalCodec(ciphertext.size == ciphertextLength, TestTerminalCodecFailureV1.CRYPTO_FAILURE)
            val bytes = buffers.own(wire.pack(header.bytes, checkNotNull(selectedWrapped), ciphertext))
            attempt.remainingMillis(1)
            val envelope = TestTerminalEnvelopeV1(content, bytes)
            runCatching {
                attempt.remainingMillis(1)
                envelope
            }.getOrElse { failure ->
                envelope.close()
                throw failure
            }
        }
    }

    /** Independent storage coordinates plus frozen expected content; no S3/version/retention proof is issued. */
    fun open(
        expectedBucket: String,
        expectedObjectKey: String,
        expected: TestTerminalContentV1,
        wireBytes: ByteArray,
        attempt: TestTerminalAttemptV1,
        dataKeys: TestTerminalDataKeyPortV1,
    ): TestTerminalDecodedV1 = testTerminalCodecBoundary {
        canonical.checkAttempt(attempt, expected.kind)
        requireTestTerminalCodec(dataKeys.attempt === attempt)
        requireTestTerminalCodec(expectedBucket == declaration.journalLocation.bucket && expectedObjectKey == expected.route.objectKey)
        requireTestTerminalCodec(
            wireBytes.size in TestTerminalWireV1.OUTER_BYTES..wire.maximumEnvelopeBytes,
            TestTerminalCodecFailureV1.LIMIT_EXCEEDED,
        )
        withTestTerminalBuffers { buffers ->
            val checked = canonical.checkedContent(expected, buffers)
            val bytes = buffers.own(wireBytes.copyOf())
            val parts = wire.split(bytes, buffers)
            val header = bindHeader(parts.header, checked.binding)
            val nonce = buffers.own(Base64.getUrlDecoder().decode(header.nonce))
            requireTestTerminalCodec(nonce.size == TestTerminalWireV1.NONCE_BYTES && TestTerminalWireV1.encode(nonce) == header.nonce)
            val associatedData = buffers.own(aad(header, parts.wrapped, parts.ciphertext.size))
            val wrappedForPort = buffers.own(parts.wrapped.copyOf())
            val request = request(header, wire.maximumWrappedBytes, attempt, buffers)
            requireConnectionFree()
            val lease = try {
                journalKeyCall { dataKeys.unwrap(request, wrappedForPort) }
            } finally {
                wrappedForPort.fill(0)
            }
            val plaintext = withJournalDataKey(request, lease, generated = false) { key, _ ->
                attempt.remainingMillis(1)
                buffers.own(TestTerminalWireV1.crypt(Cipher.DECRYPT_MODE, key, nonce, associatedData, parts.ciphertext))
            }
            attempt.remainingMillis(1)
            requireTestTerminalCodec(plaintext.size in 1..wire.maximumPlaintextBytes, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
            // The decrypted bytes reach a closed parser only after GCM doFinal AND key-lease cleanup succeeded.
            val binding = canonical.bindBytes(expected.kind, plaintext, expected.route.routingKeyId)
            requireTestTerminalCodec(binding == checked.binding && plaintext.contentEquals(checked.bytes))
            val digest = Sha256.hex(bytes)
            attempt.remainingMillis(1)
            TestTerminalDecodedV1(expected, digest)
        }
    }

    private fun header(binding: TestTerminalCanonicalV1.Binding, nonce: String, buffers: TestTerminalBuffersV1): Header =
        if (binding.kind == TestTerminalCodecKindV1.EPOCH_SEAL) {
            val value = sealHeader(binding, nonce)
            Header(buffers.own(json.encodeSealHeader(value)), value.framedValues(), value.nonce)
        } else {
            val value = eventHeader(binding, nonce)
            Header(buffers.own(json.encodeEventHeader(value)), value.framedValues(), value.nonce)
        }

    /**
     * Historical exact-reference decode when the accepted erasure already removed V21. References
     * are comparison data, never catalog/erasure authority. The owning reader separately proves
     * actual version/retention/full inventory and the original authenticates the complete catalog.
     * No canonical row is fabricated and no active route is substituted for the retained route.
     */
    internal fun openReferenced(
        kind: TestTerminalCodecKindV1,
        expectedObjectKey: String,
        expectedRoutingKeyId: String,
        expectedId: String,
        expectedStart: Long,
        expectedEnd: Long,
        expectedCanonicalSha256: String,
        expectedWireSha256: String,
        wireBytes: ByteArray,
        attempt: TestTerminalAttemptV1,
        dataKeys: TestTerminalDataKeyPortV1,
    ): TestTerminalDecodedV1 = testTerminalCodecBoundary {
        canonical.checkAttempt(attempt, kind)
        requireTestTerminalCodec(dataKeys.attempt === attempt && expectedStart > 0 && expectedEnd >= expectedStart &&
            (kind == TestTerminalCodecKindV1.EPOCH_SEAL || expectedStart == expectedEnd))
        requireTestTerminalCodec(declaration.routing.keys.any { it.keyId == expectedRoutingKeyId })
        requireTestTerminalCodec(wireBytes.size in TestTerminalWireV1.OUTER_BYTES..wire.maximumEnvelopeBytes,
            TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
        val expected = TestTerminalCanonicalV1.Binding(kind, TestTerminalRouteV1(expectedRoutingKeyId, expectedObjectKey, expectedId),
            expectedStart, expectedEnd)
        withTestTerminalBuffers { buffers ->
            val bytes = buffers.own(wireBytes.copyOf())
            requireTestTerminalCodec(Sha256.hex(bytes) == expectedWireSha256)
            val parts = wire.split(bytes, buffers)
            val header = bindHeader(parts.header, expected) // Exact scope/bucket/KMS/route/epoch before any unwrap.
            val nonce = buffers.own(Base64.getUrlDecoder().decode(header.nonce))
            requireTestTerminalCodec(nonce.size == TestTerminalWireV1.NONCE_BYTES && TestTerminalWireV1.encode(nonce) == header.nonce)
            val associatedData = buffers.own(aad(header, parts.wrapped, parts.ciphertext.size))
            val wrapped = buffers.own(parts.wrapped.copyOf())
            val request = request(header, wire.maximumWrappedBytes, attempt, buffers)
            requireConnectionFree()
            val lease = try { journalKeyCall { dataKeys.unwrap(request, wrapped) } } finally { wrapped.fill(0) }
            val plaintext = withJournalDataKey(request, lease, generated = false) { key, _ ->
                attempt.remainingMillis(1)
                buffers.own(TestTerminalWireV1.crypt(Cipher.DECRYPT_MODE, key, nonce, associatedData, parts.ciphertext))
            }
            // Only after doFinal AND actual key-lease cleanup can plaintext reach the closed parser.
            attempt.remainingMillis(1)
            requireTestTerminalCodec(plaintext.size in 1..wire.maximumPlaintextBytes, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
            val content = canonical.restoreCanonical(kind, plaintext, expectedRoutingKeyId, expectedObjectKey, expectedCanonicalSha256, attempt)
            try {
                requireTestTerminalCodec(canonical.checkedContent(content, buffers).binding == expected)
                attempt.remainingMillis(1)
                TestTerminalDecodedV1(content, expectedWireSha256)
            } catch (problem: Throwable) { content.close(); throw problem }
        }
    }

    private fun bindHeader(bytes: ByteArray, expected: TestTerminalCanonicalV1.Binding): Header =
        if (expected.kind == TestTerminalCodecKindV1.EPOCH_SEAL) {
            val value = json.sealHeader(bytes)
            requireTestTerminalCodec(value == sealHeader(expected, value.nonce))
            Header(bytes, value.framedValues(), value.nonce)
        } else {
            val value = json.eventHeader(bytes)
            requireTestTerminalCodec(value == eventHeader(expected, value.nonce))
            Header(bytes, value.framedValues(), value.nonce)
        }

    private fun eventHeader(binding: TestTerminalCanonicalV1.Binding, nonce: String): TestTerminalEventHeaderV1 = TestTerminalEventHeaderV1(
        1, 1, "kcj-1", binding.kind.name, "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED",
        declaration.encryption.keyId, declaration.encryption.keyArn,
        declaration.journalLocation.bucket, binding.route.objectKey, declaration.writer.generationId, journal.sealTerminalPrefix, "TEST",
        journal.scope.id.toString(), binding.epochEndInclusive, binding.route.routingKeyId, binding.route.journalId, nonce,
    )

    private fun sealHeader(binding: TestTerminalCanonicalV1.Binding, nonce: String): TestTerminalSealHeaderV1 = TestTerminalSealHeaderV1(
        1, 1, "kcj-1", "EPOCH_SEAL", "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED",
        declaration.encryption.keyId, declaration.encryption.keyArn,
        declaration.journalLocation.bucket, binding.route.objectKey, declaration.writer.generationId, journal.sealTerminalPrefix, "TEST",
        journal.scope.id.toString(), binding.epochStartInclusive, binding.epochEndInclusive, binding.route.routingKeyId, binding.route.journalId, nonce,
    )

    private fun request(
        header: Header,
        maximumWrappedBytes: Int,
        attempt: TestTerminalAttemptV1,
        buffers: TestTerminalBuffersV1,
    ): JournalDataKeyRequestV1 {
        val frame = buffers.own(TestTerminalWireV1.frame(listOf(TestTerminalWireV1.KMS_DOMAIN, "1") + header.fields))
        val context = TestTerminalWireV1.encode(frame)
        requireTestTerminalCodec(
            TestTerminalWireV1.CONTEXT_KEY.length + context.length <= TestTerminalWireV1.MAX_CONTEXT_BYTES,
            TestTerminalCodecFailureV1.LIMIT_EXCEEDED,
        )
        return JournalDataKeyRequestV1(
            declaration.encryption.keyArn, mapOf(TestTerminalWireV1.CONTEXT_KEY to context), maximumWrappedBytes,
            attempt.remainingMillis(declaration.limits.deadlines.kmsCallMillis),
        )
    }

    private fun aad(header: Header, wrapped: ByteArray, ciphertextLength: Int): ByteArray = TestTerminalWireV1.frame(
        listOf(TestTerminalWireV1.AAD_DOMAIN, "1", "KJEV", "1", header.bytes.size.toString()) + header.fields +
            listOf(wrapped.size.toString(), TestTerminalWireV1.encode(wrapped), ciphertextLength.toString()),
    )

    private class Header(val bytes: ByteArray, val fields: List<String>, val nonce: String)

    override fun toString(): String = "TestTerminalCodecV1(TEST,redacted,no-authority)"

    companion object {
        /** Cold fixed-family codec tied to the original TEST process's HMAC consumer, not a supplied secret list. */
        internal fun fromRetained(owner: TestOwnerDeleteJournalRoutingV1, nanoTime: () -> Long): TestTerminalCodecV1 {
            requireConnectionFree()
            return TestTerminalCodecV1(owner.journalConfiguration, TestTerminalRoutingV1.fromRetained(owner), SecureRandom(), nanoTime)
        }

        fun fromAcquired(
            journal: TestOwnerDeleteJournalConfigurationV1,
            secrets: List<AcquiredVersionedSecret>,
            random: SecureRandom = SecureRandom(),
            nanoTime: () -> Long = System::nanoTime,
        ): TestTerminalCodecV1 = testTerminalCodecBoundary {
            requireConnectionFree()
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val routing = TestTerminalRoutingV1.fromAcquired(journal, secrets)
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            TestTerminalCodecV1(journal, routing, random, nanoTime)
        }
    }
}
