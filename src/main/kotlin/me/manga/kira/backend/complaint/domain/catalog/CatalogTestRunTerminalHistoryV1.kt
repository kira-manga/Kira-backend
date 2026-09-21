package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

@Serializable
internal data class CatalogTestRunTerminalHeadV1(val count: Long, val sha256: String)

/** Counts are history records, not byte counts, ciphertext objects or installation UUIDs. */
@Serializable
internal data class CatalogTestRunTerminalHistoryV1(
    val expiredRestoreSources: CatalogTestRunTerminalHeadV1,
    val testRunActivations: CatalogTestRunTerminalHeadV1,
    val testRunTerminals: CatalogTestRunTerminalHeadV1,
    val installationManifests: CatalogTestRunTerminalHeadV1,
    val epochSeals: CatalogTestRunTerminalHeadV1,
    val retirementAuthorizations: CatalogTestRunTerminalHeadV1,
    val retirementCompletions: CatalogTestRunTerminalHeadV1,
) {
    companion object {
        private val empty = CatalogTestRunTerminalHeadV1(0, Sha256.hexUtf8("[]"))

        fun append(previous: CatalogTestRunActivationHistoryV1, record: CatalogTestRunTerminalRecordV1): CatalogTestRunTerminalHistoryV1 {
            requireOfflineTrustBundle(previous.testRunActivations.count == 1L)
            requireOfflineTrustBundle(listOf(previous.expiredRestoreSources, previous.testRunTerminals, previous.installationManifests,
                previous.epochSeals, previous.retirementAuthorizations, previous.retirementCompletions).all { it.count == 0L && it.sha256 == empty.sha256 })
            val activation = CatalogTestRunTerminalHeadV1(previous.testRunActivations.count, previous.testRunActivations.sha256)
            return terminalHeads(activation, record)
        }

        fun requireTerminalHeads(value: CatalogTestRunTerminalHistoryV1, record: CatalogTestRunTerminalRecordV1) {
            requireOfflineTrustBundle(value.testRunActivations.count == 1L && OfflineBootstrapGrammar.sha256(value.testRunActivations.sha256))
            requireOfflineTrustBundle(value == terminalHeads(value.testRunActivations, record), OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH)
        }

        private fun terminalHeads(activation: CatalogTestRunTerminalHeadV1, record: CatalogTestRunTerminalRecordV1): CatalogTestRunTerminalHistoryV1 {
            val context = record.context()
            val manifest = CatalogTestTerminalManifestHistoryRecordV1(context.dataScopeId, context.activationCatalogGeneration,
                context.activationCatalogSha256, record.installationManifest)
            val seals = record.sealSet.records().map { CatalogTestTerminalSealHistoryRecordV1(context.dataScopeId,
                context.activationCatalogGeneration, context.activationCatalogSha256, it) }
            return CatalogTestRunTerminalHistoryV1(empty, activation, head(CatalogTestRunTerminalRecordV1.serializer(), listOf(record)),
                head(CatalogTestTerminalManifestHistoryRecordV1.serializer(), listOf(manifest)),
                head(CatalogTestTerminalSealHistoryRecordV1.serializer(), seals), empty, empty)
        }

        fun sealHead(records: List<TestTerminalSealRefV1>): CatalogTestRunTerminalHeadV1 = head(TestTerminalSealRefV1.serializer(), records)

        private fun <T> head(serializer: KSerializer<T>, records: List<T>): CatalogTestRunTerminalHeadV1 =
            CatalogTestRunTerminalHeadV1(records.size.toLong(), Sha256.hexUtf8(CanonicalJson.canonicalize(ListSerializer(serializer), records)))

        /** Same fixed LP32 chunk-reference commitment as TestTerminalChunkFoldV1; never a new root format. */
        fun requireChunks(record: CatalogTestRunTerminalRecordV1) {
            val context = record.context()
            val chunks = record.installationManifest.chunks
            val summary = record.installationManifest.summary
            requireOfflineTrustBundle(chunks.size == summary.chunkCount && chunks.size <= TestTerminalProfileV1.MAX_MANIFEST_CHUNKS)
            val hash = MessageDigest.getInstance("SHA-256")
            frame(hash, listOf("kira-test-manifest-chunks-v1", context.dataScopeId, context.activationCatalogGeneration.toString(),
                context.activationCatalogSha256, chunks.size.toString()))
            var installations = 0L
            var retired = 0L
            var deleted = 0L
            val ids = HashSet<String>()
            chunks.forEachIndexed { index, chunk ->
                requireOfflineTrustBundle(chunk.chunkIndex == index && chunk.installationCount in 1..TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK.toLong() &&
                    chunk.retiredCount in 0..chunk.installationCount && chunk.deletedCount in 0..chunk.installationCount &&
                    chunk.retiredCount + chunk.deletedCount == chunk.installationCount && OfflineBootstrapGrammar.sha256(chunk.entriesSha256) && ids.add(chunk.eventId))
                requireOfflineTrustBundle(index == chunks.lastIndex || chunk.installationCount == TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK.toLong())
                TestTerminalSyntaxV1.opaque(chunk.eventId)
                // Independently domain-separated payload/object-key IDs are both committed below;
                // exact retained-route derivation belongs to the native codec, not this declaration.
                TestTerminalSyntaxV1.terminalKey(chunk.objectRef.objectKey, record.closure.writerGeneration, context.dataScopeId,
                    record.closure.terminalEpoch, "installation-manifest")
                installations = Math.addExact(installations, chunk.installationCount)
                retired = Math.addExact(retired, chunk.retiredCount)
                deleted = Math.addExact(deleted, chunk.deletedCount)
                frame(hash, listOf(index.toString(), chunk.eventId, chunk.installationCount.toString(), chunk.retiredCount.toString(), chunk.deletedCount.toString(),
                    chunk.entriesSha256, chunk.objectRef.objectKey, chunk.objectRef.objectVersion, chunk.objectRef.ciphertextSha256, chunk.objectRef.canonicalSha256))
            }
            requireOfflineTrustBundle(installations == summary.installationCount && retired == summary.retiredCount && deleted == summary.deletedCount &&
                HexFormat.of().formatHex(hash.digest()) == summary.chunksSha256)
        }

        private fun frame(hash: MessageDigest, values: List<String>) {
            values.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                hash.update(bytes)
            }
        }
    }
}

@Serializable
private data class CatalogTestTerminalManifestHistoryRecordV1(
    val dataScopeId: String, val activationCatalogGeneration: Long, val activationCatalogSha256: String,
    val manifest: CatalogTestRunTerminalInstallationV1,
)

@Serializable
private data class CatalogTestTerminalSealHistoryRecordV1(
    val dataScopeId: String, val activationCatalogGeneration: Long, val activationCatalogSha256: String,
    val seal: TestTerminalSealRefV1,
)
