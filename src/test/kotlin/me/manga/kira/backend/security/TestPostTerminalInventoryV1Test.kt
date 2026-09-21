package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/** Local deterministic framing only; native set completeness and both passes belong to the connected original. */
internal class TestPostTerminalInventoryV1Test {
    private val f = TestTerminalTestFixture()
    private val entries: List<TestPostTerminalInventoryEntryV1> get() {
        fun entry(kind: TestTerminalCodecKindV1, keyKind: String, start: Long, end: Long, index: Int) = TestPostTerminalInventoryEntryV1(
            TestTerminalTestFixture.WRITER, kind, start, end, if (kind === TestTerminalCodecKindV1.EPOCH_SEAL) null else TestTerminalTestFixture.opaque(index),
            TestTerminalObjectRefV1(TestTerminalTestFixture.key(end, keyKind, TestTerminalTestFixture.opaque(index)), "version-$index", "c".repeat(64), "d".repeat(64)),
            1024, Instant.ofEpochSecond(100), Instant.ofEpochSecond(1_000), Instant.ofEpochSecond(1_001))
        return listOf(entry(TestTerminalCodecKindV1.EPOCH_SEAL, "epoch-seal", 1, 2, 0),
            entry(TestTerminalCodecKindV1.EPOCH_SEAL, "epoch-seal", 3, 3, 1),
            entry(TestTerminalCodecKindV1.INSTALLATION_MANIFEST, "installation-manifest", 3, 3, 2),
            entry(TestTerminalCodecKindV1.TEST_RUN_PURGE, "test-run-purge", 3, 3, 3)).sortedBy { it.objectRef.objectKey }
    }

    @Test
    fun `postterminal fold commits all families and full retention metadata in a new exact LP32 domain`() {
        val values = entries
        val header = terminalFrame(listOf("kira-test-postterminal-inventory-v1", f.run.dataScopeId,
            f.run.activationCatalogGeneration.toString(), f.run.activationCatalogSha256, f.run.configurationSha256,
            f.run.terminalEncodingSha256, TestTerminalTestFixture.WRITER, f.journal.sealTerminalPrefix, "1", "3", "4"))
        val framed = values.map { value -> terminalFrame(listOf(value.writerGeneration, value.kind.name, value.epochStartInclusive.toString(),
            value.epochEndInclusive.toString(), value.eventId.orEmpty(), value.objectRef.objectKey, value.objectRef.objectVersion,
            value.objectRef.ciphertextSha256, value.objectRef.canonicalSha256, value.ciphertextByteCount.toString(), value.lastModified.toString(),
            value.requestedRetainUntil.toString(), value.retainUntil.toString(), "COMPLIANCE")) }
        val result = fold(values)
        assertEquals(4L, result.versionCount); assertEquals(4096L, result.ciphertextByteCount)
        assertEquals(header.size.toLong() + framed.sumOf { it.size.toLong() }, result.framedByteCount)
        assertEquals(framed.sumOf { it.size.toLong() }, result.entryFramedByteCount)
        assertEquals(terminalHash(header, *framed.toTypedArray()), result.sha256)
        assertEquals(f.rootHash("preTerminalInventory"), f.inventoryRoot(TestTerminalRootsV1(f.journal, 500, 1)).sha256)
        assertNotEquals(f.rootHash("preTerminalInventory"), result.sha256)
    }

    @Test
    fun `each actual metadata change changes the postterminal commitment without changing preterminal goldens`() {
        val values = entries; val first = values.first(); val root = fold(values).sha256
        listOf(first.copy(lastModified = first.lastModified.plusSeconds(1)), first.copy(requestedRetainUntil = first.requestedRetainUntil.minusSeconds(1)),
            first.copy(retainUntil = first.retainUntil.plusSeconds(1)), first.copy(ciphertextByteCount = first.ciphertextByteCount + 1),
            first.copy(objectRef = first.objectRef.copy(objectVersion = "other-version")),
            first.copy(objectRef = first.objectRef.copy(ciphertextSha256 = "a".repeat(64))),
            first.copy(objectRef = first.objectRef.copy(canonicalSha256 = "b".repeat(64)))).forEach { changed ->
            assertNotEquals(root, fold(listOf(changed) + values.drop(1)).sha256)
        }
        val differentContext = TestPostTerminalInventoryFoldV1(f.journal, f.run.copy(configurationSha256 = "f".repeat(64)), 3, 4)
        values.forEach(differentContext::entry); assertNotEquals(root, differentContext.finish().sha256)
    }

    @Test
    fun `count exactness ordering writer epoch prefix and poisoned continuation are mandatory`() {
        val values = entries
        terminalRejected { TestPostTerminalInventoryFoldV1(f.journal, f.run, 3, 0) }
        val incomplete = TestPostTerminalInventoryFoldV1(f.journal, f.run, 3, 4)
        values.dropLast(1).forEach(incomplete::entry); terminalRejected { incomplete.finish() }; terminalRejected { incomplete.entry(values.last()) }
        val duplicate = TestPostTerminalInventoryFoldV1(f.journal, f.run, 3, 4)
        duplicate.entry(values.first()); terminalRejected { duplicate.entry(values.first()) }; terminalRejected { duplicate.finish() }
        val reversed = TestPostTerminalInventoryFoldV1(f.journal, f.run, 3, 4)
        reversed.entry(values.last()); terminalRejected { reversed.entry(values.first()) }
        listOf(values.first().copy(writerGeneration = TestTerminalTestFixture.uuid(9)),
            values.first().copy(epochEndInclusive = 4),
            values.first().copy(objectRef = values.first().objectRef.copy(objectKey = values.first().objectRef.objectKey.replace("/seal-terminal/", "/ordinary/")))).forEach { bad ->
            terminalRejected { TestPostTerminalInventoryFoldV1(f.journal, f.run, 3, 1).entry(bad) }
        }
        terminalRejected { values.first().copy(eventId = TestTerminalTestFixture.OPAQUE) }
        terminalRejected { values.first().copy(lastModified = values.first().lastModified.plusNanos(1)) }
        terminalRejected { values.first().copy(retainUntil = values.first().requestedRetainUntil.minusSeconds(1)) }
    }

    @Test
    fun `fixed retained version and LP32 byte limits apply exactly without ordinary scan domain reuse`() {
        val values = entries; val total = fold(values).framedByteCount
        fun journal(versions: Long, bytes: Long): TestOwnerDeleteJournalConfigurationV1 {
            val declaration = f.journal.declaration()
            return TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(limits = declaration.limits.copy(
                capacity = declaration.limits.capacity.copy(maximumRetainedVersions = versions, maximumScanStagingBytes = bytes))))
        }
        assertEquals(fold(values), fold(values, journal(4, total)))
        terminalRejected { fold(values, journal(4, total - 1)) }
        terminalRejected { TestPostTerminalInventoryFoldV1(journal(3, total), f.run, 3, 4) }
        val ended = TestPostTerminalInventoryFoldV1(f.journal, f.run, 3, 4)
        values.forEach(ended::entry); ended.finish(); terminalRejected { ended.finish() }; terminalRejected { ended.entry(values.last()) }
    }

    private fun fold(values: List<TestPostTerminalInventoryEntryV1>, journal: TestOwnerDeleteJournalConfigurationV1 = f.journal): TestPostTerminalInventoryFoldV1.Summary =
        TestPostTerminalInventoryFoldV1(journal, f.run, 3, values.size.toLong()).let { fold -> values.forEach(fold::entry); fold.finish() }
}
