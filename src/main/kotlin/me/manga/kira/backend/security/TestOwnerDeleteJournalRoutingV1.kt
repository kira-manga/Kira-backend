package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import java.util.UUID
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Actual immutable routing consumer. Local derivation only; no lookup, publication, D or runtime authority. */
internal class TestOwnerDeleteJournalRoutingV1 private constructor(
    val journalConfiguration: TestOwnerDeleteJournalConfigurationV1,
    private val keys: List<RoutingKey>,
) {
    private val writerGeneration = journalConfiguration.declaration().writer.generationId
    private val ordinaryPrefix = journalConfiguration.ordinaryPrefix
    private val activeKeyId = journalConfiguration.declaration().routing.activeKeyId

    fun descriptors(): List<VersionedSecretBinding> = keys.map { it.binding }

    /** All retained keys participate in local collision accounting; only the declared active candidate is selected. */
    fun derive(tuple: TestDeletionJournalTupleV1): TestOwnerDeleteJournalRoutesV1 {
        require(tuple.scope == journalConfiguration.scope && when (tuple) {
            is TestOwnerDeleteJournalTupleV1 -> tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE || journalConfiguration.ownerDeleteAll
            is TestAdminDeleteJournalTupleV1 -> journalConfiguration.adminDelete
        }) { INVALID_CONFIGURATION }
        val candidates = keys.map { key ->
            val opaqueKey = deriveMac(key, ROUTING_DOMAIN, tuple)
            val eventId = deriveMac(key, EVENT_ID_DOMAIN, tuple)
            val epoch = tuple.epoch.toString().padStart(19, '0')
            val objectKey = "${ordinaryPrefix}writer/$writerGeneration/epoch/$epoch/${key.binding.logicalKeyId}/$opaqueKey"
            TestOwnerDeleteRoutingCandidateV1(key.binding.logicalKeyId, objectKey, eventId)
        }
        return TestOwnerDeleteJournalRoutesV1(candidates.single { it.routingKeyId == activeKeyId }, candidates)
    }

    /** Only the two fixed TEST seal domains, using THESE keys; no arbitrary-frame or second-material bridge. */
    internal fun epochSealMac(keyId: String, epoch: Long, descriptor: String, objectKey: Boolean): ByteArray {
        require(epoch > 0 && descriptor.matches(Regex("[0-9a-f]{64}"))) { INVALID_CONFIGURATION }
        val domain = if (objectKey) "kira-test-epoch-seal-object-key-v1" else "kira-test-epoch-seal-id-v1"
        val frame = TestTerminalFramesV1.bytes(listOf(domain, "EPOCH_SEAL", writerGeneration, "TEST",
            journalConfiguration.scope.id.toString(), epoch.toString(), keyId, descriptor))
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(keys.single { it.binding.logicalKeyId == keyId }.secretKey)
                doFinal(frame)
            }
        } finally { frame.fill(0) }
    }

    /** Fixed-family comparison tags from these actual HMAC keys, never a caller's second retained-material list. */
    fun admissionForbiddenFamily(): ComplaintAdmissionForbiddenFamily {
        val copies = keys.map { it.secretKey.encoded }
        return try {
            ComplaintAdmissionForbiddenFamily("journal-routing", copies)
        } finally {
            copies.forEach { it.fill(0) }
        }
    }

    private fun deriveMac(key: RoutingKey, domain: String, tuple: TestDeletionJournalTupleV1): String {
        val frame = frameBytes(domain, key.binding.logicalKeyId, tuple)
        return try {
            val digest = Mac.getInstance("HmacSHA256").run {
                init(key.secretKey)
                doFinal(frame)
            }
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        } finally {
            frame.fill(0)
        }
    }

    /** Fixed LP32BE-UTF8 field order. The domain/version/prefix are never caller-selected. */
    private fun frameBytes(domain: String, keyId: String, tuple: TestDeletionJournalTupleV1): ByteArray {
        val fields = listOf(
            domain, "1", writerGeneration, ordinaryPrefix, "TEST", journalConfiguration.scope.id.toString(),
            tuple.epoch.toString(), keyId, tuple.eventKind.name, tuple.actorKind.name, tuple.actorId.toString(),
            tuple.credentialVersion?.toString().orEmpty(), tuple.operationKey.toString(), tuple.encodedFingerprint(),
        ).map { it.toByteArray(Charsets.UTF_8) }
        return try {
            val frame = ByteBuffer.allocate(fields.sumOf { 4 + it.size })
            fields.forEach { frame.putInt(it.size).put(it) }
            frame.array()
        } finally {
            fields.forEach { it.fill(0) }
        }
    }

    override fun toString(): String = "TestOwnerDeleteJournalRoutingV1(redacted,no-authority)"

    private class RoutingKey(val binding: VersionedSecretBinding, val secretKey: SecretKeySpec)

    companion object {
        fun fromAcquired(journal: TestOwnerDeleteJournalConfigurationV1, secrets: List<AcquiredVersionedSecret>): TestOwnerDeleteJournalRoutingV1 {
            val inputs = snapshot(secrets).sortedBy { it.descriptor.logicalKeyId }
            val declared = journal.declaration().routing.keys
            require(inputs.size == declared.size) { INVALID_CONFIGURATION }
            inputs.zip(declared).forEach { (acquired, expected) ->
                val binding = acquired.descriptor
                require(
                    binding.family == SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING && binding.purpose == SecretMaterialPurpose.HMAC_SHA256 &&
                        binding.logicalKeyId == expected.keyId && binding.version == expected.secret,
                ) { INVALID_CONFIGURATION }
            }
            val keys = inputs.map { acquired ->
                acquired.useMaterial { material ->
                    require(material.size in 32..128) { INVALID_CONFIGURATION }
                    RoutingKey(acquired.descriptor, SecretKeySpec(material, "HmacSHA256"))
                }
            }
            requireDistinctEffectiveKeys(keys)
            return TestOwnerDeleteJournalRoutingV1(journal, keys)
        }

        private fun requireDistinctEffectiveKeys(keys: List<RoutingKey>) {
            val comparisons = ArrayList<ComplaintAdmissionKey>(keys.size)
            try {
                keys.forEach { key ->
                    val material = key.secretKey.encoded
                    val candidate = try {
                        ComplaintAdmissionKey(key.binding.logicalKeyId, material)
                    } finally {
                        material.fill(0)
                    }
                    comparisons.add(candidate)
                    require(comparisons.dropLast(1).none { it.sameSecret(candidate) }) { INVALID_CONFIGURATION }
                }
            } finally {
                comparisons.forEach { it.destroy() }
            }
        }

        private fun snapshot(secrets: List<AcquiredVersionedSecret>): List<AcquiredVersionedSecret> {
            val count = secrets.size
            require(count in 1..4) { INVALID_CONFIGURATION }
            val iterator = secrets.iterator()
            val result = ArrayList<AcquiredVersionedSecret>(count)
            repeat(count) {
                require(iterator.hasNext()) { INVALID_CONFIGURATION }
                result.add(iterator.next())
            }
            require(!iterator.hasNext()) { INVALID_CONFIGURATION }
            return result
        }

        private const val ROUTING_DOMAIN = "kira-complaint-journal-routing-v1"
        private const val EVENT_ID_DOMAIN = "kira-complaint-journal-event-id-v1"
        private const val INVALID_CONFIGURATION = "Invalid complaint journal routing configuration"
    }
}

/** Frozen local candidates only. A selected candidate has not been authorized or committed to PREPARED. */
internal class TestOwnerDeleteJournalRoutesV1(val active: TestOwnerDeleteRoutingCandidateV1, candidates: List<TestOwnerDeleteRoutingCandidateV1>) {
    private val retained = candidates.toList()

    fun candidates(): List<TestOwnerDeleteRoutingCandidateV1> = retained.toList()

    override fun toString(): String = "TestOwnerDeleteJournalRoutesV1(redacted,no-authority)"
}

internal data class TestOwnerDeleteRoutingCandidateV1(val routingKeyId: String, val objectKey: String, val eventId: String) {
    override fun toString(): String = "TestOwnerDeleteRoutingCandidateV1(redacted,no-authority)"
}

/** Closed TEST OWNER_DELETE/INSTALLATION tuple. Not an assigned epoch or authenticated actor. */
internal class TestOwnerDeleteJournalTupleV1(
    override val epoch: Long,
    override val actorId: UUID,
    override val credentialVersion: Long,
    override val operationKey: UUID,
    fingerprint: ByteArray,
    override val scope: ComplaintDataScope,
    override val eventKind: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE,
) : TestDeletionJournalTupleV1 {
    private val storedFingerprint = fingerprint.copyOf()
    override val actorKind: ComplaintJournalActorKindV1 get() = ComplaintJournalActorKindV1.INSTALLATION

    init {
        require(epoch > 0 && credentialVersion > 0 && scope.testOnly && storedFingerprint.size == 32)
        require(eventKind in setOf(ComplaintJournalDeletionKindV1.OWNER_DELETE, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL))
        ComplaintIdentifiers.installationId(actorId.toString())
        ComplaintIdentifiers.idempotencyKey(operationKey.toString())
    }

    override fun fingerprintBytes(): ByteArray = storedFingerprint.copyOf()
    override fun encodedFingerprint(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(storedFingerprint)
    override fun toString(): String = "TestOwnerDeleteJournalTupleV1(redacted,no-authority)"
}
