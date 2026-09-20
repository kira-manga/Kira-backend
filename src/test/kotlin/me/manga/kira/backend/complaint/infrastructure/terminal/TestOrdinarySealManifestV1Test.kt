package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import me.manga.kira.backend.security.TestTerminalTestFixture
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Local two-pass commitments only; these synthetic rows supply no provider inventory or quiescence proof. */
class TestOrdinarySealManifestV1Test {
    private val source = TestTerminalTestFixture()
    private val routing = TestOwnerDeleteJournalRoutingV1.fromAcquired(source.journal, source.acquired())
    private val rows = listOf(1, 2).map { index -> Row(
        "${source.journal.ordinaryPrefix}1/test-route-01/${TestTerminalTestFixture.opaque(index)}.kjev",
        "version-é$index", index.toString().repeat(64), "1:2:3:$index",
    ) }

    @Test
    fun emptyAndSortedRowsMatchIndependentLp32ManifestAndComparison() {
        for (selected in listOf(emptyList(), rows)) {
            val manifest = fold(selected)
            assertEquals(selected.size.toLong(), manifest.count)
            assertEquals(terminalHash(terminalFrame(prefix(selected.size)), *selected.map { terminalFrame(it.fields()) }.toTypedArray()), manifest.sha256)
            assertEquals(terminalHash(*selected.map { terminalFrame(it.fields() + it.stamps) }.toTypedArray()), manifest.comparison)
            manifest.requireSame(fold(selected))
        }
    }

    @Test
    fun changedSecondPassOrMvccImageCannotBeAcceptedOrRepaired() {
        val original = rows.first()
        for (changed in listOf(original.copy(version = "changed-version"), original.copy(hash = "f".repeat(64)), original.copy(stamps = "1:2:3:9"))) {
            val builder = builder()
            builder.add(original); builder.beginSecond(); builder.add(changed)
            assertThrows<TestOrdinarySealExceptionV1> { builder.finish() }
            assertThrows<TestOrdinarySealExceptionV1> { builder.add(original) }
            assertThrows<TestOrdinarySealExceptionV1> { builder.finish() }
        }
        val incomplete = builder()
        incomplete.add(original); incomplete.beginSecond()
        assertThrows<TestOrdinarySealExceptionV1> { incomplete.finish() }
        assertThrows<TestOrdinarySealExceptionV1> { incomplete.add(original) }

        val before = fold(listOf(original))
        val after = fold(listOf(original.copy(stamps = "1:2:3:9")))
        assertEquals(before.sha256, after.sha256, "MVCC stamps are comparison evidence, not provider manifest fields.")
        assertNotEquals(before.comparison, after.comparison)
        assertThrows<TestOrdinarySealExceptionV1> { before.requireSame(after) }
    }

    @Test
    fun duplicateOrReversedKeysPoisonTheBuilderAndCutoffMustBePositive() {
        assertThrows<TestOrdinarySealExceptionV1> { builder(cutoff = 0) }
        for (first in rows) {
            val builder = builder()
            builder.add(first)
            assertThrows<TestOrdinarySealExceptionV1> { builder.add(rows.first()) }
            assertThrows<TestOrdinarySealExceptionV1> { builder.beginSecond() }
            assertThrows<TestOrdinarySealExceptionV1> { builder.finish() }
        }
    }

    @Test
    fun positiveRowAndByteCapsIncludeTheManifestPrefixWithoutAllowingRetry() {
        val rowLimited = builder(routingWithLimits(maximumRows = 1))
        rowLimited.add(rows.first())
        assertThrows<TestOrdinarySealExceptionV1> { rowLimited.add(rows.last()) }
        assertThrows<TestOrdinarySealExceptionV1> { rowLimited.beginSecond() }

        val selected = listOf(rows.first())
        val exactBytes = terminalFrame(prefix(1)).size.toLong() + terminalFrame(rows.first().fields()).size
        assertEquals(fold(selected).sha256, fold(selected, routingWithLimits(maximumBytes = exactBytes)).sha256)
        val tooSmall = builder(routingWithLimits(maximumBytes = exactBytes - 1))
        tooSmall.add(rows.first())
        assertThrows<TestOrdinarySealExceptionV1> { tooSmall.beginSecond() }
        assertThrows<TestOrdinarySealExceptionV1> { tooSmall.finish() }
    }

    private fun builder(routing: TestOwnerDeleteJournalRoutingV1 = this.routing, cutoff: Long = 7): TestOrdinarySealManifestV1.Builder {
        val attempt = TestTerminalCodecV1.fromRetained(routing) { 0L }.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL)
        return TestOrdinarySealManifestV1.Builder(routing, cutoff, attempt)
    }

    private fun fold(rows: List<Row>, routing: TestOwnerDeleteJournalRoutingV1 = this.routing): TestOrdinarySealManifestV1 {
        val builder = builder(routing)
        rows.forEach { builder.add(it) }
        builder.beginSecond()
        rows.forEach { builder.add(it) }
        return builder.finish()
    }

    private fun prefix(count: Int): List<String> = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest",
        source.journal.declaration().writer.generationId, source.journal.ordinaryPrefix, "TEST", source.journal.scope.id.toString(), "1", "7", count.toString())

    private fun routingWithLimits(
        maximumRows: Long = source.journal.declaration().limits.capacity.maximumRetainedVersions,
        maximumBytes: Long = source.journal.declaration().limits.capacity.maximumScanStagingBytes,
    ): TestOwnerDeleteJournalRoutingV1 {
        val original = source.journal.declaration()
        val journal = TestOwnerDeleteJournalConfigurationV1.of(original.copy(limits = original.limits.copy(
            capacity = original.limits.capacity.copy(maximumRetainedVersions = maximumRows, maximumScanStagingBytes = maximumBytes))))
        return TestOwnerDeleteJournalRoutingV1.fromAcquired(journal, source.acquired())
    }

    private fun TestOrdinarySealManifestV1.Builder.add(row: Row) = entry(row.key, row.version, row.hash, row.stamps)
    private data class Row(val key: String, val version: String, val hash: String, val stamps: String) {
        fun fields(): List<String> = listOf(key, version, hash)
    }
}
