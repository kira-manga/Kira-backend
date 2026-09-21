package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationEntryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationReadV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.security.TestTerminalFramesV1
import me.manga.kira.backend.security.TestTerminalChunkFoldV1
import java.security.MessageDigest

/**
 * Comparison data only. The real SQL original supplies BOTH complete passes and later rereads each
 * selected chunk. Retains at most 4096 descriptors and one <=500-entry chunk, never all identities.
 */
internal class TestInstallationManifestSourceV1(
    private val journal: TestOwnerDeleteJournalConfigurationV1,
    private val context: TestTerminalRunContextV1,
    private val binding: TestInstallationSourceV1.Binding,
    installationLimit: Long,
    private val enrolledCount: Long,
    private val historical: TestTerminalInstallationReadV1?,
) {
    private val source = TestInstallationSourceV1(journal, context, binding, installationLimit, enrolledCount)
    private val maximumBytes = journal.declaration().limits.capacity.maximumScanStagingBytes
    private val expectedChunks = TestTerminalSyntaxV1.chunkCount(enrolledCount)
    private val descriptors = ArrayList<Descriptor>(expectedChunks)
    private var pass = 0
    private var ordinal = 0
    private var after: String? = null
    private var chunk: RawChunk? = null
    private var oldHash: MessageDigest? = null
    private var oldBytes = 0L
    private var done = false
    private var failed = false

    init {
        historical?.let {
            requireManifest(it.databaseIdentity == binding.databaseIdentity && it.restoreIdentity == binding.restoreIdentity &&
                it.desiredGeneration == binding.desiredGeneration && it.fencingToken in 1..binding.fencingToken &&
                it.sourceHighWater.enrolledCount == enrolledCount && it.installationCount == enrolledCount && it.chunkCount == expectedChunks)
        }
    }

    fun begin(observed: TestInstallationSourceV1.Binding, started: Long) = guarded {
        requireManifest(!done && pass in 0..1 && chunk == null)
        source.beginPass(observed, started)
        pass++; ordinal = 0; after = null
        historical?.let { previous ->
            // Reproduce ONLY its digest prefix over newly read rows. Never call beginPass with an
            // old fence, invent old timestamps, replace current authority or emit a historical read.
            oldHash = MessageDigest.getInstance("SHA-256")
            val oldBinding = TestInstallationSourceV1.Binding(previous.databaseIdentity, previous.restoreIdentity,
                previous.desiredGeneration, previous.fencingToken)
            oldBytes = append(checkNotNull(oldHash), 0, TestInstallationSourceV1.sourcePrefix(context, oldBinding, enrolledCount))
        }
    }

    fun entry(row: TestInstallationSourceV1.Row) = guarded {
        requireManifest(!done && pass in 1..2)
        source.entry(row)
        oldHash?.let { oldBytes = append(it, oldBytes, row.fields()) }
        val selected = chunk ?: RawChunk(context, ordinal, after, maximumBytes).also { chunk = it }
        selected.entry(row)
        if (selected.size == TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK) flush()
    }

    fun end(observed: TestInstallationSourceV1.Binding, completed: Long) = guarded {
        requireManifest(!done && pass in 1..2)
        source.endPass(observed, completed)
        flush()
        requireManifest(ordinal == expectedChunks)
        historical?.let { previous ->
            requireManifest(oldBytes == previous.sourceHighWater.framedByteCount &&
                TestTerminalFramesV1.finish(checkNotNull(oldHash)) == previous.sourceHighWater.sourceSha256)
        }
        oldHash = null
    }

    fun finish(): Observation = guarded {
        requireManifest(!done && pass == 2 && chunk == null && descriptors.size == expectedChunks)
        val progress = source.finish()
        historical?.let { requireSameMembership(it, progress.installationReads().first()) }
        done = true
        Observation(progress, descriptors.toList(), source)
    }

    private fun flush() {
        val selected = chunk ?: return
        requireManifest(ordinal < expectedChunks)
        val group = source.lastChunk()
        requireManifest(group.ordinal == ordinal && group.count == selected.size)
        val descriptor = selected.descriptor(group.entriesSha256)
        if (pass == 1) descriptors.add(descriptor) else requireManifest(descriptors[ordinal] == descriptor)
        after = descriptor.lastId; ordinal++; chunk = null
    }

    private fun append(hash: MessageDigest, before: Long, fields: List<String>): Long {
        val bytes = TestTerminalFramesV1.update(hash, fields)
        requireManifest(bytes >= 0 && before >= 0 && bytes <= maximumBytes - before)
        return before + bytes
    }

    private fun <T> guarded(action: () -> T): T {
        requireManifest(!done && !failed)
        try { return action() } catch (problem: Throwable) {
            failed = true; chunk = null; descriptors.clear(); oldHash?.reset(); oldHash = null
            throw problem
        }
    }

    data class Descriptor(val ordinal: Int, val afterId: String?, val lastId: String, val count: Int,
        val entriesSha256: String, val sourceSha256: String, val sourceBytes: Long) {
        override fun toString(): String = "TestInstallationManifestSourceV1.Descriptor(comparison-only,redacted)"
    }

    class Observation(val progress: TestTerminalProgressV1, private val chunks: List<Descriptor>, private val source: TestInstallationSourceV1) {
        val count: Int get() = chunks.size
        fun chunk(index: Int): Descriptor = chunks[index]
        internal fun startChunks(epoch: Long): TestTerminalChunkFoldV1 = source.startChunks(epoch)
        fun requireSame(other: Observation) {
            requireManifest(progress.context() == other.progress.context() && chunks == other.chunks)
            val first = progress.installationReads().first()
            val next = other.progress.installationReads().first()
            requireManifest(first.copy(startedAtEpochSecond = next.startedAtEpochSecond, completedAtEpochSecond = next.completedAtEpochSecond) == next)
        }
        override fun toString(): String = "TestInstallationManifestSourceV1.Observation(two-actual-reads-required,redacted)"
    }

    /** Used only for a selected reread, after the full source fold has released its own entry chunk. */
    class Chunk(private val context: TestTerminalRunContextV1, ordinal: Int, after: String?, maximumBytes: Long) : AutoCloseable {
        private val entries = ArrayList<TestTerminalInstallationEntryV1>(TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK)
        private val raw = RawChunk(context, ordinal, after, maximumBytes)
        private var finished: Descriptor? = null
        private var failed = false
        val size: Int get() = entries.size
        fun entry(row: TestInstallationSourceV1.Row): Unit = guarded {
            requireManifest(finished == null)
            val target = row.target(context.dataScopeId)
            raw.entry(row)
            entries.add(target)
            Unit
        }
        fun descriptor(): Descriptor = guarded {
            finished ?: raw.descriptor(TestTerminalSyntaxV1.entriesSha256(entries)).also { finished = it }
        }
        fun entries(): List<TestTerminalInstallationEntryV1> = guarded { requireManifest(finished != null); entries.toList() }
        private fun <T> guarded(action: () -> T): T {
            requireManifest(!failed)
            try { return action() } catch (problem: Throwable) { failed = true; entries.clear(); throw problem }
        }
        override fun close() { failed = true; entries.clear() }
        override fun toString(): String = "TestInstallationManifestSourceV1.Chunk(bounded-comparison,redacted)"
    }

    /** Scalar metadata only: avoids retaining a second chunk alongside the existing source fold. */
    private class RawChunk(context: TestTerminalRunContextV1, private val ordinal: Int, private val after: String?, private val maximumBytes: Long) {
        private val hash = MessageDigest.getInstance("SHA-256")
        private var bytes = TestTerminalFramesV1.update(hash, listOf("kira-local-test-installation-chunk-v1", context.dataScopeId,
            context.activationCatalogGeneration.toString(), context.activationCatalogSha256, context.configurationSha256,
            context.terminalEncodingSha256, ordinal.toString(), after.orEmpty()))
        private var previous: String? = after
        private var finished = false
        var size = 0
            private set
        init { requireManifest(ordinal in 0 until TestTerminalProfileV1.MAX_MANIFEST_CHUNKS && bytes <= maximumBytes) }
        fun entry(row: TestInstallationSourceV1.Row) {
            requireManifest(!finished && size < TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK && previous?.let { it < row.id } != false)
            val additional = TestTerminalFramesV1.update(hash, row.fields())
            requireManifest(additional >= 0 && additional <= maximumBytes - bytes)
            bytes += additional; previous = row.id; size++
        }
        fun descriptor(entriesSha256: String): Descriptor {
            requireManifest(!finished && size > 0)
            finished = true
            return Descriptor(ordinal, after, checkNotNull(previous), size, entriesSha256, TestTerminalFramesV1.finish(hash), bytes)
        }
    }

    companion object {
        private fun requireSameMembership(old: TestTerminalInstallationReadV1, current: TestTerminalInstallationReadV1) {
            requireManifest(old.sourceHighWater.enrolledCount == current.sourceHighWater.enrolledCount &&
                old.sourceHighWater.reservationCount == current.sourceHighWater.reservationCount &&
                old.sourceHighWater.greatestReservationId == current.sourceHighWater.greatestReservationId &&
                old.installationCount == current.installationCount && old.retiredCount == current.retiredCount && old.deletedCount == current.deletedCount &&
                old.chunkCount == current.chunkCount && old.installationsSha256 == current.installationsSha256 &&
                old.installationsFramedBytes == current.installationsFramedBytes && old.chunkSetSha256 == current.chunkSetSha256 &&
                old.chunkSetFramedBytes == current.chunkSetFramedBytes)
        }
    }
    override fun toString(): String = "TestInstallationManifestSourceV1(bounded-local-comparison,no-authority)"
}
