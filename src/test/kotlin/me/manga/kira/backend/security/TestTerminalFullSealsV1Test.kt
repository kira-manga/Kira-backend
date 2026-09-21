package me.manga.kira.backend.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.key
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.opaque
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.uuid
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Ordered canonical commitments only. Synthetic references are not native proof or a terminal catalog. */
class TestTerminalFullSealsV1Test {
    private val f = TestTerminalTestFixture()
    private val owner = TestTerminalRootsV1(f.journal, 500, 1)

    @Test
    fun fullRootAppendsActualTerminalRoleWithoutChangingImmutablePurgePrefix() {
        val ordinary = f.seals()
        val beforeBytes = f.json.encodeSealSet(ordinary)
        val before = owner.preTerminalSeals(ordinary)
        val records = listOf(f.sealRef, terminal(f.sealRef))
        val full = owner.fullSeals(f.seals(records))
        assertEquals(2L, full.count)
        assertEquals(independentRoot(records), full.sha256)
        assertNotEquals(before.sha256, full.sha256)
        assertEquals(f.countHash("preTerminalSeals"), before)
        assertEquals(before, owner.preTerminalSeals(ordinary))
        assertEquals(before, f.purge.preTerminalSeals)
        assertArrayEquals(beforeBytes, f.json.encodeSealSet(ordinary))
        terminalRejected { owner.preTerminalSeals(f.seals(records)) }
        terminalRejected { owner.preTerminalInventory(f.run, f.seals(records)) }
    }

    @Test
    fun fullRootPreservesCapturedWriterAndEpochOrderRatherThanSortingOrHashingTheEnvelope() {
        val secondWriter = f.sealRef.copy(writerGeneration = uuid(9), sealId = opaque(9),
            objectRef = f.sealRef.objectRef.copy(objectKey = key(2, "epoch-seal", opaque(9), writer = uuid(9)), canonicalSha256 = "f".repeat(64)))
        val nextOrdinary = secondWriter.copy(epochStartInclusive = 3, epochEndInclusive = 4, sealId = opaque(10),
            precedingSealSha256 = secondWriter.objectRef.canonicalSha256,
            objectRef = secondWriter.objectRef.copy(objectKey = key(4, "epoch-seal", opaque(10), writer = uuid(9)), canonicalSha256 = "e".repeat(64)))
        val prefix = listOf(f.sealRef, secondWriter, nextOrdinary)
        val records = prefix + terminal(nextOrdinary)
        val complete = f.seals(records)
        val actual = owner.fullSeals(complete)
        assertEquals(4L, actual.count)
        assertEquals(independentRoot(records), actual.sha256)
        assertNotEquals(independentRoot(records.sortedBy { it.writerGeneration }), actual.sha256)
        assertNotEquals(terminalHash(f.json.encodeSealSet(complete)), actual.sha256, "Root hashes the records list, not the contextual set envelope.")
        assertEquals(independentRoot(prefix), owner.preTerminalSeals(f.seals(prefix)).sha256)
        assertEquals(prefix, complete.records().dropLast(1))
    }

    @Test
    fun missingFinalRoleInvalidOrderingRepeatedWriterAndBrokenPrecedingHashAreRejected() {
        val terminal = terminal(f.sealRef)
        terminalRejected { owner.fullSeals(f.seals()) }
        terminalRejected { owner.fullSeals(f.seals(listOf(f.sealRef, terminal.copy(role = TestTerminalSealRoleV1.ORDINARY)))) }
        val secondWriter = f.sealRef.copy(writerGeneration = uuid(9), sealId = opaque(9),
            objectRef = f.sealRef.objectRef.copy(objectKey = key(2, "epoch-seal", opaque(9), writer = uuid(9))))
        for (records in listOf(
            listOf(terminal),
            listOf(terminal, f.sealRef),
            listOf(f.sealRef, terminal, secondWriter),
            listOf(f.sealRef, terminal, terminal),
            listOf(f.sealRef, secondWriter, f.sealRef, terminal),
            listOf(f.sealRef, terminal.copy(precedingSealSha256 = "f".repeat(64))),
            listOf(f.sealRef, terminal.copy(epochStartInclusive = 4, epochEndInclusive = 4,
                objectRef = terminal.objectRef.copy(objectKey = key(4, "epoch-seal", opaque(11))))),
        )) terminalRejected { owner.fullSeals(f.seals(records)) }
    }

    private fun terminal(previous: TestTerminalSealRefV1): TestTerminalSealRefV1 {
        val epoch = previous.epochEndInclusive + 1
        return previous.copy(role = TestTerminalSealRoleV1.TERMINAL, epochStartInclusive = epoch, epochEndInclusive = epoch,
            sealId = opaque(11), precedingSealSha256 = previous.objectRef.canonicalSha256,
            objectRef = previous.objectRef.copy(objectKey = key(epoch, "epoch-seal", opaque(11), writer = previous.writerGeneration),
                objectVersion = "terminal-version-1", ciphertextSha256 = "1".repeat(64), canonicalSha256 = "2".repeat(64)))
    }

    /** Independently spell every flattened record field; do not invoke a MAIN serializer/canonicalizer. */
    private fun independentRoot(records: List<TestTerminalSealRefV1>): String = terminalHash(terminalCanonical(JsonArray(records.map { ref ->
        buildJsonObject {
            put("role", ref.role.name); put("writerGeneration", ref.writerGeneration)
            put("epochStartInclusive", ref.epochStartInclusive); put("epochEndInclusive", ref.epochEndInclusive)
            put("sealId", ref.sealId); put("precedingSealSha256", ref.precedingSealSha256)
            put("object", buildJsonObject {
                put("objectKey", ref.objectRef.objectKey); put("objectVersion", ref.objectRef.objectVersion)
                put("ciphertextSha256", ref.objectRef.ciphertextSha256); put("canonicalSha256", ref.objectRef.canonicalSha256)
            })
        }
    })))
}
