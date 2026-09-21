package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.security.TestTerminalTestFixture
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.key
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.opaque
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Synthetic local references only: no native inventory, publication, quiescence or terminal-completion proof. */
class TestTerminalEpochSealManifestV1Test {
    private val f = TestTerminalTestFixture()
    private val purge = Row("TEST_RUN_PURGE", 0, opaque(9),
        TestTerminalObjectRefV1(key(3, "test-run-purge", opaque(9)), "purge-version-é", "a".repeat(64), "b".repeat(64)), "c".repeat(64))

    @Test
    fun zeroChunksStillCommitsPurgeAndCompleteChunksMatchIndependentLp32Root() {
        for (chunks in listOf(0, 1, 2)) {
            val rows = rows(chunks)
            val actual = fold(rows, chunks)
            assertEquals(chunks.toLong() + 1L, actual.count)
            assertEquals(terminalHash(terminalFrame(prefix(rows.size)), *rows.map { terminalFrame(it.fields()) }.toTypedArray()), actual.sha256)
            actual.requireSame(fold(rows, chunks))
            assertNotEquals(terminalHash(terminalFrame(prefix(0))), actual.sha256, "Even an unused run includes its authenticated purge reference.")
        }
    }

    @Test
    fun missingDuplicateReversedOrSealRowsCannotBecomeACompleteTerminalManifest() {
        val expected = rows(2)
        val malformed = listOf(
            expected.dropLast(1), // Missing purge.
            listOf(expected.first(), purge), // Missing one chunk.
            listOf(expected.first(), expected.first(), purge), // Repeated exact key.
            expected.reversed(),
            listOf(expected.first(), expected[1].copy(ordinal = 0), purge), // Distinct key, duplicate chunk ordinal.
            listOf(expected.first().copy(ordinal = 2), expected[1], purge),
            listOf(expected.first(), expected[1], purge.copy(ordinal = 1)),
            expected + purge.copy(reference = purge.reference.copy(objectKey = key(3, "test-run-purge", opaque(10)))),
            listOf(expected.first().copy(kind = "EPOCH_SEAL"), expected[1], purge),
            listOf(expected.first().copy(kind = "OWNER_DELETE"), expected[1], purge),
        )
        for (rows in malformed) {
            val builder = builder(chunks = 2)
            assertThrows<RuntimeException> { rows.forEach { builder.add(it) }; builder.beginSecond() }
            assertThrows<TestTerminalEpochSealExceptionV1> { builder.add(purge) }
            assertThrows<TestTerminalEpochSealExceptionV1> { builder.finish() }
        }
        val empty = builder(chunks = 0)
        assertThrows<TestTerminalEpochSealExceptionV1> { empty.beginSecond() }
        assertThrows<TestTerminalEpochSealExceptionV1> { empty.add(purge) }
    }

    @Test
    fun wrongEpochScopeKindAndUnretainedRoutePoisonTheBuilder() {
        for (key in listOf(key(2, "test-run-purge", opaque(9)), key(3, "epoch-seal", opaque(9)),
            key(3, "test-run-purge", opaque(9), writer = TestTerminalTestFixture.uuid(4)),
            key(3, "test-run-purge", opaque(9), routing = "unretained-route"))) {
            val builder = builder(chunks = 0)
            assertThrows<RuntimeException> { builder.add(purge.copy(reference = purge.reference.copy(objectKey = key))) }
            assertThrows<TestTerminalEpochSealExceptionV1> { builder.beginSecond() }
        }
        val malformedProof = builder(chunks = 0)
        assertThrows<TestTerminalExceptionV1> { malformedProof.add(purge.copy(proof = "not-a-hash")) }
        assertThrows<TestTerminalEpochSealExceptionV1> { malformedProof.add(purge) }
    }

    @Test
    fun changedSecondPassCanonicalProofIdentityOrNativeReferenceCannotBeRepaired() {
        val alternatives = listOf(
            purge.copy(reference = purge.reference.copy(objectVersion = "another-version")),
            purge.copy(reference = purge.reference.copy(ciphertextSha256 = "d".repeat(64))),
            purge.copy(reference = purge.reference.copy(canonicalSha256 = "e".repeat(64))),
            purge.copy(id = opaque(10)),
            purge.copy(proof = "f".repeat(64)),
        )
        for (changed in alternatives) {
            val builder = builder(chunks = 0)
            builder.add(purge); builder.beginSecond(); builder.add(changed)
            assertThrows<TestTerminalEpochSealExceptionV1> { builder.finish() }
            assertThrows<TestTerminalEpochSealExceptionV1> { builder.add(purge) }
            assertThrows<TestTerminalEpochSealExceptionV1> { builder.finish() }
        }
        val incomplete = builder(chunks = 0)
        incomplete.add(purge); incomplete.beginSecond()
        assertThrows<TestTerminalEpochSealExceptionV1> { incomplete.finish() }
        assertThrows<TestTerminalEpochSealExceptionV1> { incomplete.add(purge) }

        val original = fold(listOf(purge), 0)
        for (changed in alternatives.drop(2)) {
            val other = fold(listOf(changed), 0)
            assertEquals(original.sha256, other.sha256, "Canonical/proof/identity comparisons do not alter the three-field provider manifest root.")
            assertThrows<TestTerminalEpochSealExceptionV1> { original.requireSame(other) }
        }
    }

    @Test
    fun expectedVersionAndByteCapsAreExactAndValidateChunkBoundsBeforeAllocation() {
        assertThrows<TestTerminalEpochSealExceptionV1> { builder(chunks = -1) }
        assertThrows<TestTerminalEpochSealExceptionV1> { builder(chunks = TestTerminalProfileV1.MAX_MANIFEST_CHUNKS + 1) }
        assertThrows<TestTerminalEpochSealExceptionV1> { builder(chunks = Int.MAX_VALUE) }
        assertThrows<TestTerminalEpochSealExceptionV1> { builder(epoch = 1, chunks = 0) }
        builder(chunks = TestTerminalProfileV1.MAX_MANIFEST_CHUNKS)
        assertThrows<TestTerminalEpochSealExceptionV1> { builder(journal(maximumVersions = 1), chunks = 1) }
        assertEquals(fold(rows(1), 1).sha256, fold(rows(1), 1, journal(maximumVersions = 2)).sha256)

        val exact = terminalFrame(prefix(1)).size.toLong() + terminalFrame(purge.fields()).size
        assertEquals(fold(listOf(purge), 0).sha256, fold(listOf(purge), 0, journal(maximumBytes = exact)).sha256)
        val tooSmall = builder(journal(maximumBytes = exact - 1), chunks = 0)
        tooSmall.add(purge)
        assertThrows<TestTerminalEpochSealExceptionV1> { tooSmall.beginSecond() }
        assertThrows<TestTerminalEpochSealExceptionV1> { tooSmall.finish() }

        val completed = builder(chunks = 0)
        completed.add(purge); completed.beginSecond(); completed.add(purge); completed.finish()
        assertThrows<TestTerminalEpochSealExceptionV1> { completed.finish() }
        assertThrows<TestTerminalEpochSealExceptionV1> { completed.add(purge) }
    }

    @Test
    fun referenceVersionSyntaxIsBoundedBeforeAnyLocalEntry() {
        for (version in listOf("", "null", "x".repeat(1025), "é".repeat(513), "\uD800")) {
            assertThrows<TestTerminalExceptionV1> { purge.reference.copy(objectVersion = version) }
        }
        val maximum = purge.copy(reference = purge.reference.copy(objectVersion = "é".repeat(512)))
        assertEquals(1L, fold(listOf(maximum), 0).count)
    }

    private fun rows(chunks: Int): List<Row> = ((0 until chunks).map { index ->
        Row("INSTALLATION_MANIFEST", index, opaque(index), f.manifestRef.copy(objectKey = key(3, "installation-manifest", opaque(index))), "d".repeat(64))
    } + purge).sortedBy { it.reference.objectKey }

    private fun builder(journal: TestOwnerDeleteJournalConfigurationV1 = f.journal, epoch: Long = 3, chunks: Int) =
        TestTerminalEpochSealManifestV1.Builder(journal, epoch, chunks)

    private fun fold(rows: List<Row>, chunks: Int, journal: TestOwnerDeleteJournalConfigurationV1 = f.journal): TestTerminalEpochSealManifestV1 {
        val builder = builder(journal, chunks = chunks)
        rows.forEach { builder.add(it) }; builder.beginSecond(); rows.forEach { builder.add(it) }
        return builder.finish()
    }

    private fun prefix(count: Int): List<String> = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest",
        f.journal.declaration().writer.generationId, f.journal.sealTerminalPrefix, "TEST", f.run.dataScopeId, "3", "3", count.toString())

    private fun journal(maximumVersions: Long = f.journal.declaration().limits.capacity.maximumRetainedVersions,
        maximumBytes: Long = f.journal.declaration().limits.capacity.maximumScanStagingBytes): TestOwnerDeleteJournalConfigurationV1 {
        val d = f.journal.declaration()
        return TestOwnerDeleteJournalConfigurationV1.of(d.copy(limits = d.limits.copy(capacity = d.limits.capacity.copy(
            maximumRetainedVersions = maximumVersions, maximumScanStagingBytes = maximumBytes))))
    }

    private fun TestTerminalEpochSealManifestV1.Builder.add(row: Row) = entry(row.kind, row.ordinal, row.id, row.reference, row.proof)
    private data class Row(val kind: String, val ordinal: Int, val id: String, val reference: TestTerminalObjectRefV1, val proof: String) {
        fun fields(): List<String> = listOf(reference.objectKey, reference.objectVersion, reference.ciphertextSha256)
    }
}
