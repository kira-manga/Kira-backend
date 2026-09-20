package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationEntryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationReadV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSourceHighWaterV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.security.TestTerminalFramesV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalRootsV1
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Supplied local observations only; the fixed VERIFY SQL operation establishes their complete source.
 * New source/grouping hashes are ephemeral comparisons, NOT a persisted or provider protocol.
 * No row is retired here. A later terminal preparation must reacquire ownership and revalidate.
 */
internal class TestInstallationSourceV1(
    private val journal: TestOwnerDeleteJournalConfigurationV1,
    private val context: TestTerminalRunContextV1,
    private val binding: Binding,
    installationLimit: Long,
    private val enrolledCount: Long,
) {
    private val roots = TestTerminalRootsV1(journal, installationLimit, TestTerminalSyntaxV1.chunkCount(installationLimit))
    private val installations = roots.installations(context)
    private val maximumBytes = journal.declaration().limits.capacity.maximumScanStagingBytes
    private val expectedChunks = TestTerminalSyntaxV1.chunkCount(enrolledCount)
    private var stage = Stage.FIRST_READY
    private var current: Pass? = null
    private var completedChunk: ChunkSummary? = null
    private val completed = ArrayList<Read>(2)

    init {
        requireOrdinarySeal(enrolledCount in 0..installationLimit)
        requireOrdinarySeal(expectedChunks.toLong() < journal.declaration().limits.capacity.maximumRetainedVersions)
    }

    fun beginPass(observed: Binding, startedAt: Long) = guarded {
        requireOrdinarySeal(observed == binding && startedAt >= 0)
        requireOrdinarySeal(stage === Stage.FIRST_READY || stage === Stage.SECOND_READY)
        completed.lastOrNull()?.let { requireOrdinarySeal(startedAt >= it.completedAt) }
        stage = if (stage === Stage.FIRST_READY) Stage.FIRST else Stage.SECOND
        completedChunk = null
        current = Pass(startedAt)
    }

    fun entry(row: Row) = guarded {
        requireOrdinarySeal(stage === Stage.FIRST || stage === Stage.SECOND)
        val pass = checkNotNull(current)
        requireOrdinarySeal(pass.count < enrolledCount && pass.greatest?.let { it < row.id } != false)
        val target = row.target(context.dataScopeId)
        if (stage === Stage.FIRST) installations.firstPass(target) else installations.secondPass(target)
        pass.sourceBytes = append(pass.source, pass.sourceBytes, row.fields())
        pass.entryBytes = bounded(pass.entryBytes, frameSize(listOf(target.installationId, target.disposition.name)))
        pass.chunk.add(target)
        pass.count++
        if (target.disposition === TestTerminalDispositionV1.RETIRED) pass.retired++
        pass.greatest = row.id
        if (pass.chunk.size == TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK) pass.flushChunk()
    }

    fun endPass(observed: Binding, completedAt: Long) = guarded {
        requireOrdinarySeal((stage === Stage.FIRST || stage === Stage.SECOND) && observed == binding)
        val pass = checkNotNull(current)
        requireOrdinarySeal(completedAt >= pass.startedAt && pass.count == enrolledCount)
        pass.flushChunk()
        requireOrdinarySeal(pass.chunks == expectedChunks)
        val entryBytes = bounded(pass.entryBytes, frameSize(TestTerminalFramesV1.installationPrefix(context, pass.count, pass.retired, pass.count - pass.retired)))
        val read = Read(pass.startedAt, completedAt, pass.count, pass.retired, pass.greatest.orEmpty(),
            TestTerminalFramesV1.finish(pass.source), pass.sourceBytes, entryBytes, TestTerminalFramesV1.finish(pass.groups), pass.groupBytes)
        completed.firstOrNull()?.let { first -> requireOrdinarySeal(first.copy(startedAt = read.startedAt, completedAt = read.completedAt) == read) }
        completed.add(read)
        current = null
        if (stage === Stage.FIRST) {
            installations.beginSecondPass()
            stage = Stage.SECOND_READY
        } else stage = Stage.COMPLETE
    }

    fun finish(): TestTerminalProgressV1 = guarded {
        requireOrdinarySeal(stage === Stage.COMPLETE && completed.size == 2)
        val root = installations.finish()
        val reads = completed.map { read ->
            requireOrdinarySeal(read.count == root.installationCount && read.retired == root.retiredCount)
            TestTerminalInstallationReadV1(binding.databaseIdentity, binding.restoreIdentity, binding.desiredGeneration, binding.fencingToken,
                read.startedAt, read.completedAt,
                TestTerminalSourceHighWaterV1(enrolledCount, read.count, read.greatest, read.sourceHash, read.sourceBytes),
                read.count, read.retired, read.count - read.retired, expectedChunks, root.sha256, read.entryBytes, read.groupHash, read.groupBytes)
        }
        val progress = TestTerminalProgressV1.create(context, emptyList(), reads)
        TestTerminalJsonV1(journal).encodeProgress(progress).fill(0) // Enforce actual J/closed-codec bounds; nothing is persisted.
        stage = Stage.DONE
        progress
    }

    /** Last bounded plaintext grouping only; no identities or database/read authority are exposed. */
    internal fun lastChunk(): ChunkSummary {
        requireOrdinarySeal(stage in setOf(Stage.FIRST, Stage.SECOND, Stage.SECOND_READY, Stage.COMPLETE))
        return checkNotNull(completedChunk)
    }
    internal data class ChunkSummary(val ordinal: Int, val count: Int, val entriesSha256: String)

    private inner class Pass(val startedAt: Long) {
        val source: MessageDigest = MessageDigest.getInstance("SHA-256")
        val groups: MessageDigest = MessageDigest.getInstance("SHA-256")
        val chunk = ArrayList<TestTerminalInstallationEntryV1>(TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK)
        var sourceBytes = append(source, 0, sourcePrefix(context, binding, enrolledCount))
        var groupBytes = append(groups, 0, localPrefix("kira-local-test-installation-groups-v1") + listOf(enrolledCount.toString(), expectedChunks.toString()))
        var entryBytes = 0L
        var count = 0L
        var retired = 0L
        var chunks = 0
        var greatest: String? = null

        fun flushChunk() {
            if (chunk.isEmpty()) return
            requireOrdinarySeal(chunks < expectedChunks)
            val entriesHash = TestTerminalSyntaxV1.entriesSha256(chunk)
            groupBytes = append(groups, groupBytes, listOf(chunks.toString(), chunk.size.toString(), entriesHash))
            completedChunk = ChunkSummary(chunks, chunk.size, entriesHash)
            chunks++
            chunk.clear()
        }
    }

    private fun localPrefix(domain: String) = listOf(domain, context.dataScopeId, context.activationCatalogGeneration.toString(),
        context.activationCatalogSha256, context.configurationSha256, context.terminalEncodingSha256)
    private fun append(digest: MessageDigest, prior: Long, fields: List<String>): Long = bounded(prior, TestTerminalFramesV1.update(digest, fields))
    private fun frameSize(fields: List<String>): Long = TestTerminalFramesV1.bytes(fields).let { try { it.size.toLong() } finally { it.fill(0) } }
    private fun bounded(prior: Long, next: Long): Long {
        requireOrdinarySeal(prior >= 0 && next >= 0 && next <= maximumBytes - prior)
        return prior + next
    }
    private fun <T> guarded(action: () -> T): T {
        requireOrdinarySeal(stage !== Stage.FAILED && stage !== Stage.DONE)
        try { return action() } catch (problem: Throwable) {
            stage = Stage.FAILED
            current?.let { it.chunk.clear(); it.source.reset(); it.groups.reset() }
            current = null
            completedChunk = null
            completed.clear()
            throw problem
        }
    }

    internal data class Binding(val databaseIdentity: String, val restoreIdentity: String, val desiredGeneration: Long, val fencingToken: Long) {
        init {
            TestTerminalSyntaxV1.uuid(databaseIdentity); TestTerminalSyntaxV1.uuid(restoreIdentity)
            requireOrdinarySeal(desiredGeneration > 0 && fencingToken > 0)
        }
        override fun toString(): String = "TestInstallationSourceV1.Binding(local-observation,redacted)"
    }

    /** Metadata only. SQL checks credential shape without returning a verifier, platform, or owner reference. */
    internal data class Credential(val id: String, val scope: String, val testOnly: Boolean, val state: InstallationCredentialState,
        val credentialVersion: Long, val rowVersion: Long, val stamp: String) {
        override fun toString(): String = "TestInstallationSourceV1.Credential(metadata-only,redacted)"
    }

    internal data class Row(val id: String, val scope: String, val testOnly: Boolean, val state: InstallationIdentityState,
        val createdAt: Instant, val terminalAt: Instant?, val stamp: String, val credential: Credential?) {
        fun target(expectedScope: String): TestTerminalInstallationEntryV1 {
            TestTerminalSyntaxV1.uuid(id)
            requireOrdinarySeal(scope == expectedScope && testOnly && validStamp(stamp))
            requireOrdinarySeal((state in setOf(InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED)) == (terminalAt != null))
            credential?.let { c ->
                requireOrdinarySeal(c.id == id && c.scope == scope && c.testOnly && c.credentialVersion > 0 && c.rowVersion > 0 && validStamp(c.stamp))
            }
            requireOrdinarySeal(when (state) {
                InstallationIdentityState.ACTIVE -> credential?.state === InstallationCredentialState.ACTIVE
                InstallationIdentityState.RETIRED, InstallationIdentityState.RECOVERY_RESERVED -> credential == null
                InstallationIdentityState.DELETED -> credential == null || credential.state === InstallationCredentialState.DELETED
                InstallationIdentityState.DELETION_PENDING -> false
            })
            return TestTerminalInstallationEntryV1(id, if (state === InstallationIdentityState.DELETED) TestTerminalDispositionV1.DELETED else TestTerminalDispositionV1.RETIRED)
        }

        fun fields(): List<String> = listOf(id, scope, testOnly.toString(), state.name, createdAt.toString(), terminalAt?.toString().orEmpty(), stamp,
            if (credential == null) "ABSENT" else "PRESENT", credential?.id.orEmpty(), credential?.scope.orEmpty(), credential?.testOnly?.toString().orEmpty(),
            credential?.state?.name.orEmpty(), credential?.credentialVersion?.toString().orEmpty(), credential?.rowVersion?.toString().orEmpty(), credential?.stamp.orEmpty())
        override fun toString(): String = "TestInstallationSourceV1.Row(local-source-metadata,redacted)"

        companion object {
            fun read(value: ResultSet): Row {
                requireOrdinarySeal(TestOrdinarySealRowsV1.boolean(value, "reservation_valid") && TestOrdinarySealRowsV1.boolean(value, "credential_valid"))
                val credentialId = value.getObject("credential_id", UUID::class.java)
                val credential = credentialId?.let { Credential(it.toString(), checkNotNull(value.getObject("credential_scope", UUID::class.java)).toString(),
                    TestOrdinarySealRowsV1.boolean(value, "credential_test_only"), InstallationCredentialState.valueOf(checkNotNull(value.getString("credential_state"))),
                    value.getLong("credential_version"), value.getLong("credential_row_version"), checkNotNull(value.getString("credential_stamp"))) }
                return Row(checkNotNull(value.getObject("id", UUID::class.java)).toString(), checkNotNull(value.getObject("data_scope_id", UUID::class.java)).toString(),
                    TestOrdinarySealRowsV1.boolean(value, "test_only"), InstallationIdentityState.valueOf(checkNotNull(value.getString("state"))),
                    checkNotNull(value.getTimestamp("created_at")).toInstant(), value.getTimestamp("terminal_at")?.toInstant(),
                    checkNotNull(value.getString("reservation_stamp")), credential)
            }
            private fun validStamp(value: String): Boolean = value.matches(Regex("[0-9]{1,10}")) && checkNotNull(value.toLongOrNull()) in 0..4_294_967_295L
        }
    }

    private data class Read(val startedAt: Long, val completedAt: Long, val count: Long, val retired: Long, val greatest: String,
        val sourceHash: String, val sourceBytes: Long, val entryBytes: Long, val groupHash: String, val groupBytes: Long)
    private enum class Stage { FIRST_READY, FIRST, SECOND_READY, SECOND, COMPLETE, DONE, FAILED }
    override fun toString(): String = "TestInstallationSourceV1(local-two-pass-observation,no-terminal-authority,redacted)"

    companion object {
        /** Shared spelling for historical hash comparison; using this prefix does not create an observed read. */
        internal fun sourcePrefix(context: TestTerminalRunContextV1, binding: Binding, enrolledCount: Long): List<String> = listOf(
            "kira-local-test-installation-source-v1", context.dataScopeId, context.activationCatalogGeneration.toString(),
            context.activationCatalogSha256, context.configurationSha256, context.terminalEncodingSha256,
            binding.databaseIdentity, binding.restoreIdentity, binding.desiredGeneration.toString(), binding.fencingToken.toString(), enrolledCount.toString(),
        )
    }
}
