package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationReadV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalTestFixture
import me.manga.kira.backend.security.terminalCanonical
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** Pure synthetic metadata specimens, never SQL-read, registration, denial or terminal authority. */
class TestInstallationManifestSourceV1Test {
    private val f = TestTerminalTestFixture()
    private val binding = TestInstallationSourceV1.Binding(TestTerminalTestFixture.uuid(8001), TestTerminalTestFixture.uuid(8002), 3, 17)
    private val at = Instant.parse("2026-09-20T00:00:00Z")

    @Test
    fun fiveHundredAndOneMixedReservationsHaveExactIndependentDescriptorsAndUnsignedBoundaries() {
        val states = listOf(InstallationIdentityState.ACTIVE, InstallationIdentityState.RECOVERY_RESERVED,
            InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED)
        val rows = (1..501).map { row(it, states[(it - 1) % states.size]) }
        val observed = fold(rows)
        assertEquals(2, observed.count)
        assertEquals(listOf(500, 1), (0 until observed.count).map { observed.chunk(it).count })
        var after: String? = null
        rows.chunked(500).forEachIndexed { index, chunk ->
            assertIndependentDescriptor(index, after, chunk, observed.chunk(index))
            val reread = newChunk(index, after)
            chunk.forEach(reread::entry)
            assertEquals(observed.chunk(index), reread.descriptor())
            assertEquals(chunk.map { it.id to target(it) }, reread.entries().map { it.installationId to it.disposition.name })
            after = chunk.last().id
        }
        val read = observed.progress.installationReads().first()
        assertEquals(501L, read.installationCount)
        assertEquals(rows.count { target(it) == "DELETED" }.toLong(), read.deletedCount)
        val low = row(1, InstallationIdentityState.RECOVERY_RESERVED).copy(id = "7fffffff-ffff-4fff-8fff-ffffffffffff")
        val high = low.copy(id = "80000000-0000-4000-8000-000000000000")
        assertIndependentDescriptor(0, null, listOf(low, high), fold(listOf(low, high)).chunk(0))
        assertThrows<RuntimeException> { fold(listOf(high, low)) }
        assertEquals(0, fold(emptyList()).count)
    }

    @Test
    fun freshFenceComparesHistoricRawHashWithoutReplacingOldReadBytesOrInventingOldAuthority() {
        val rows = listOf(row(1), row(2, InstallationIdentityState.RECOVERY_RESERVED))
        val prior = fold(rows)
        val json = TestTerminalJsonV1(f.journal)
        val bytes = json.encodeProgress(prior.progress)
        val oldRead = prior.progress.installationReads().first()
        val currentBinding = binding.copy(fencingToken = 18)
        val current = fold(rows, currentBinding, oldRead, 200)
        assertEquals(18L, current.progress.installationReads().first().fencingToken)
        assertEquals(200L, current.progress.installationReads().first().startedAtEpochSecond)
        assertNotEquals(oldRead.sourceHighWater.sourceSha256, current.progress.installationReads().first().sourceHighWater.sourceSha256)
        assertEquals(oldRead.installationsSha256, current.progress.installationReads().first().installationsSha256)
        assertEquals(oldRead.chunkSetSha256, current.progress.installationReads().first().chunkSetSha256)
        assertEquals(prior.chunk(0), current.chunk(0))
        assertArrayEquals(bytes, json.encodeProgress(prior.progress), "Digest-only historical comparison cannot rewrite old observations.")
        assertThrows<TestInstallationManifestExceptionV1> { prior.requireSame(current) }
        current.requireSame(fold(rows, currentBinding, oldRead, 300))
    }

    @Test
    fun rawVersionOrReservationDriftRefusesHistoricReuseEvenWhenFinalDispositionIsUnchanged() {
        val row = row(1)
        val historical = fold(listOf(row)).progress.installationReads().first()
        val current = binding.copy(fencingToken = 18)
        val changed = listOf(row.copy(stamp = "19"), row.copy(credential = checkNotNull(row.credential).copy(rowVersion = 2)),
            row.copy(state = InstallationIdentityState.RECOVERY_RESERVED, credential = null))
        changed.forEach { value ->
            assertEquals(target(row), target(value))
            val source = newSource(1, current, historical)
            source.begin(current, 200); source.entry(value)
            assertThrows<TestInstallationManifestExceptionV1> { source.end(current, 201) }
            assertThrows<TestInstallationManifestExceptionV1> { source.begin(current, 202) }
            assertThrows<TestInstallationManifestExceptionV1> { source.finish() }
        }
    }

    @Test
    fun actualSecondPassDivergenceAndLateCallsPermanentlyPoisonTheComparison() {
        val first = row(1)
        val source = newSource(1)
        source.begin(binding, 100); source.entry(first); source.end(binding, 101)
        source.begin(binding, 102); source.entry(first.copy(credential = checkNotNull(first.credential).copy(stamp = "20")))
        assertThrows<RuntimeException> { source.end(binding, 103) }
        assertThrows<TestInstallationManifestExceptionV1> { source.finish() }
        assertThrows<TestInstallationManifestExceptionV1> { source.entry(first) }
        val complete = newSource(1)
        repeat(2) { pass -> complete.begin(binding, 100L + 2 * pass); complete.entry(first); complete.end(binding, 101L + 2 * pass) }
        complete.finish()
        assertThrows<TestInstallationManifestExceptionV1> { complete.finish() }
        assertThrows<TestInstallationManifestExceptionV1> { complete.begin(binding, 200) }
    }

    @Test
    fun selectedChunkMetadataChangesCannotHideBehindEqualEntriesAndFinalizedChunksCannotGrow() {
        val first = row(1)
        val expected = fold(listOf(first)).chunk(0)
        val changed = newChunk(0, null)
        changed.entry(first.copy(credential = checkNotNull(first.credential).copy(stamp = "20")))
        val descriptor = changed.descriptor()
        assertEquals(expected.entriesSha256, descriptor.entriesSha256)
        assertNotEquals(expected.sourceSha256, descriptor.sourceSha256)
        assertThrows<TestInstallationManifestExceptionV1> { changed.entry(row(2)) }
        assertThrows<TestInstallationManifestExceptionV1> { changed.entries() }
    }

    private fun newSource(count: Int, selected: TestInstallationSourceV1.Binding = binding, old: TestTerminalInstallationReadV1? = null) =
        TestInstallationManifestSourceV1(f.journal, f.run, selected, maxOf(1, count).toLong(), count.toLong(), old)

    private fun fold(rows: List<TestInstallationSourceV1.Row>, selected: TestInstallationSourceV1.Binding = binding,
        old: TestTerminalInstallationReadV1? = null, start: Long = 100): TestInstallationManifestSourceV1.Observation {
        val source = newSource(rows.size, selected, old)
        repeat(2) { pass -> source.begin(selected, start + 2 * pass); rows.forEach(source::entry); source.end(selected, start + 2 * pass + 1) }
        return source.finish()
    }

    private fun newChunk(index: Int, after: String?) = TestInstallationManifestSourceV1.Chunk(f.run, index, after,
        f.journal.declaration().limits.capacity.maximumScanStagingBytes)

    private fun row(index: Int, state: InstallationIdentityState = InstallationIdentityState.ACTIVE): TestInstallationSourceV1.Row {
        val id = TestTerminalTestFixture.uuid(index)
        return TestInstallationSourceV1.Row(id, f.run.dataScopeId, true, state, at,
            if (state in setOf(InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED)) at else null, "7",
            if (state === InstallationIdentityState.ACTIVE) TestInstallationSourceV1.Credential(id, f.run.dataScopeId, true, InstallationCredentialState.ACTIVE, 1, 1, "8") else null)
    }
    private fun target(row: TestInstallationSourceV1.Row): String = if (row.state === InstallationIdentityState.DELETED) "DELETED" else "RETIRED"

    private fun assertIndependentDescriptor(index: Int, after: String?, rows: List<TestInstallationSourceV1.Row>, actual: TestInstallationManifestSourceV1.Descriptor) {
        assertEquals(index, actual.ordinal); assertEquals(after, actual.afterId); assertEquals(rows.last().id, actual.lastId); assertEquals(rows.size, actual.count)
        val plaintext = terminalCanonical(JsonArray(rows.map { JsonObject(mapOf("installationId" to JsonPrimitive(it.id), "disposition" to JsonPrimitive(target(it)))) }))
        assertEquals(terminalHash(plaintext), actual.entriesSha256)
        val prefix = terminalFrame(listOf("kira-local-test-installation-chunk-v1", f.run.dataScopeId,
            f.run.activationCatalogGeneration.toString(), f.run.activationCatalogSha256, f.run.configurationSha256,
            f.run.terminalEncodingSha256, index.toString(), after.orEmpty()))
        val frames = listOf(prefix) + rows.map { value ->
            val c = value.credential
            terminalFrame(listOf(value.id, value.scope, value.testOnly.toString(), value.state.name, value.createdAt.toString(), value.terminalAt?.toString().orEmpty(), value.stamp,
                if (c == null) "ABSENT" else "PRESENT", c?.id.orEmpty(), c?.scope.orEmpty(), c?.testOnly?.toString().orEmpty(), c?.state?.name.orEmpty(),
                c?.credentialVersion?.toString().orEmpty(), c?.rowVersion?.toString().orEmpty(), c?.stamp.orEmpty()))
        }
        assertEquals(terminalHash(*frames.toTypedArray()), actual.sourceSha256)
        assertEquals(frames.sumOf { it.size.toLong() }, actual.sourceBytes)
    }
}
