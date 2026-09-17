package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import org.junit.jupiter.api.Assertions.assertTrue
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID

/** Existing J/acquisition fixtures, synthetic in-memory keys only; no provider, retention or durable authority. */
internal class EpochSealTestFixtureV1(val journal: ComplaintJournalConfigurationV1 = journal(), val keys: Keys = Keys(), val clock: Clock = Clock()) {
    val owner = owner(journal)
    val nonces = Nonces()
    val codec = EpochSealCodecV1(owner, keys, nonces) { clock.nanos }
    val bucket: String = journal.declaration().journalLocation.bucket

    fun entry(number: Int, epoch: Long = number.toLong()): Entry {
        val route = owner.derive(tuple(number, epoch)).active
        return Entry(route.objectKey, "synthetic-version-$number", sha("synthetic-wire-$number".toByteArray()))
    }

    fun manifest(
        attempt: EpochSealAttemptV1,
        entries: List<Entry> = listOf(entry(1), entry(2)),
        start: Long = 1,
        end: Long = 42,
        predecessor: String = "",
    ): EpochSealManifestV1 {
        val builder = EpochSealManifestV1.start(owner, start, end, predecessor, attempt)
        entries.forEach { builder.firstPass(it.key, it.version, it.checksum) }
        builder.beginSecondPass()
        entries.forEach { builder.secondPass(it.key, it.version, it.checksum) }
        return builder.finish()
    }

    fun content(attempt: EpochSealAttemptV1, entries: List<Entry> = listOf(entry(1), entry(2)), token: Long = 7): EpochSealContentV1 =
        codec.canonicalize(manifest(attempt, entries), token, attempt)

    internal data class Entry(val key: String, val version: String, val checksum: String)
    internal class Clock(var nanos: Long = 0)

    internal class Nonces : SecureRandom() {
        private var next = 0
        val destinations = ArrayList<ByteArray>()

        override fun nextBytes(bytes: ByteArray) {
            destinations.add(bytes)
            bytes.indices.forEach { bytes[it] = (next + it).toByte() }
            next += 17
        }
    }

    internal class Keys : JournalDataKeyPortV1 {
        var generations = 0
        var unwraps = 0
        var closes = 0
        var permitContextMismatch = false
        var closeFailure: Throwable? = null
        var keyArnFailure: Throwable? = null
        var onGenerate: () -> Unit = {}
        val requests = ArrayList<JournalDataKeyRequestV1>()
        val transferred = ArrayList<ByteArray>()
        val unwrapInputs = ArrayList<ByteArray>()
        private val records = HashMap<String, Pair<ByteArray, Map<String, String>>>()

        override fun generate(request: JournalDataKeyRequestV1): JournalGeneratedDataKeyV1 {
            generations++
            requests.add(request)
            onGenerate()
            val key = ByteArray(32) { (it + generations * 13).toByte() }
            val wrapped = ByteArray(64) { (it + generations * 19).toByte() }
            records[sha(wrapped)] = key.copyOf() to request.encryptionContext()
            transferred.add(key)
            transferred.add(wrapped)
            return object : JournalGeneratedDataKeyV1 {
                override val keyArn: String get() = arn(request)
                override val plaintextKey: ByteArray = key
                override val wrappedKey: ByteArray = wrapped
                override fun close() = closeLease(key, wrapped)
            }
        }

        override fun unwrap(request: JournalDataKeyRequestV1, wrappedKey: ByteArray): JournalPlaintextDataKeyV1 {
            unwraps++
            requests.add(request)
            unwrapInputs.add(wrappedKey)
            val record = if (permitContextMismatch) records.values.first() else checkNotNull(records[sha(wrappedKey)])
            check(permitContextMismatch || record.second == request.encryptionContext())
            val key = record.first.copyOf().also(transferred::add)
            return object : JournalPlaintextDataKeyV1 {
                override val keyArn: String get() = arn(request)
                override val plaintextKey: ByteArray = key
                override fun close() = closeLease(key, null)
            }
        }

        private fun arn(request: JournalDataKeyRequestV1): String {
            keyArnFailure?.let { throw it }
            return request.keyArn
        }

        private fun closeLease(key: ByteArray, wrapped: ByteArray?) {
            closes++
            assertTrue(key.all { it == 0.toByte() })
            assertTrue(wrapped == null || wrapped.all { it == 0.toByte() })
            closeFailure?.let { throw it }
        }
    }

    companion object {
        fun journal(): ComplaintJournalConfigurationV1 = ComplaintJournalConfigurationV1.of(InitialLiveJournalTestFixture.declaration())

        fun owner(journal: ComplaintJournalConfigurationV1): VersionBoundComplaintJournalRouting {
            val acquired = journal.declaration().routing.keys.map { key ->
                val binding = VersionedSecretBinding.of(
                    SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING,
                    SecretMaterialPurpose.HMAC_SHA256,
                    key.keyId,
                    key.secret,
                )
                AcquiredVersionedSecret.acquire(binding) { SecretVersionSnapshot(key.secret, material(key.keyId)) }
            }
            return VersionBoundComplaintJournalRouting.fromAcquired(journal, acquired)
        }

        fun material(id: String): ByteArray = ByteArray(32) { (it + (id.last() - 'a') * 32).toByte() }

        fun tuple(number: Int = 1, epoch: Long = 42): ComplaintJournalDeletionTupleV1 = ComplaintJournalDeletionTupleV1(
            epoch,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL,
            ComplaintJournalActorKindV1.INSTALLATION,
            UUID.fromString("71000000-0000-4000-8000-000000000001"),
            7,
            UUID.fromString("72000000-0000-4000-8000-000000000001"),
            ByteArray(32) { (it + number).toByte() },
            ComplaintDataScope.LIVE,
        )

        fun sha(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
