package me.manga.kira.backend.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCompletedCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationReadV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSourceHighWaterV1
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.SCOPE
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.WRITER
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.uuid
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure supplied observations, not actual scans, complete DB reads, SEALED state, evidence or recycle authority. */
class TestTerminalProgressV1Test {
    private val f = TestTerminalTestFixture()

    @Test
    fun roundTripsClosedProgressWithoutFinalAuthorityOrMutableAliases() {
        val cuts = listOf(cut(), cut(TestTerminalDenialPrefixV1.SEAL_TERMINAL))
        val reads = readPair()
        val suppliedCuts = cuts.toMutableList()
        val suppliedReads = reads.toMutableList()
        val value = progress(suppliedCuts, suppliedReads)
        suppliedCuts.clear()
        suppliedReads.clear()
        assertEquals(cuts, value.completedCuts())
        assertEquals(reads, value.installationReads())
        assertNotSame(value.completedCuts(), value.completedCuts())
        assertNotSame(value.installationReads(), value.installationReads())
        (value.completedCuts() as MutableList<TestTerminalCompletedCutV1>).clear()
        (value.installationReads() as MutableList<TestTerminalInstallationReadV1>).clear()
        assertEquals(cuts, value.completedCuts())
        assertEquals(reads, value.installationReads())

        for (record in listOf(progress(), value)) {
            val expected = terminalCanonical(reference(record))
            assertArrayEquals(expected, f.json.encodeProgress(record))
            val input = expected.copyOf()
            val decoded = f.json.progress(input)
            input.fill(0)
            assertEquals(record.context(), decoded.context())
            assertEquals(record.completedCuts(), decoded.completedCuts())
            assertEquals(record.installationReads(), decoded.installationReads())
            assertArrayEquals(expected, f.json.encodeProgress(decoded))
        }
        val rendered = listOf(value, cuts.first(), reads.first(), reads.first().sourceHighWater, value.context()).joinToString()
        for (privateValue in listOf(SCOPE, WRITER, cuts.first().scanId, reads.first().databaseIdentity,
            reads.first().restoreIdentity, cuts.first().denial.roleId, f.run.configurationSha256)) {
            assertFalse(rendered.contains(privateValue))
        }
        assertTrue(value.toString().contains("no-terminal-or-recycle-authority"))
        val lying = object : AbstractList<TestTerminalCompletedCutV1>() {
            override val size: Int = 0
            override fun get(index: Int): TestTerminalCompletedCutV1 = cuts[index]
            override fun iterator(): Iterator<TestTerminalCompletedCutV1> = cuts.iterator()
        }
        terminalRejected { progress(lying) }
    }

    @Test
    fun maximumProgressFitsExactCanonicalAndParserBounds() {
        // Long.MAX_VALUE R/B exercise syntax geometry only: not a paid-pool or admission qualification.
        val maximum = maximumProgress()
        val expected = terminalCanonical(reference(maximum))
        assertEquals(51_216, expected.size)
        assertEquals(51_291, TestTerminalProgressV1.MAX_CANONICAL_BYTES)
        assertEquals(30, maximum.completedCuts().size)
        assertEquals(15, maximum.completedCuts().map { it.writerGeneration }.distinct().size)
        assertEquals(60, maximum.completedCuts().flatMap { listOf(it.denial.firstInventory, it.denial.secondInventory) }.size)
        assertEquals(60, maximum.completedCuts().flatMap { listOf(it.denial.policyEvidence, it.denial.boundEvidence) }.size)
        assertEquals(2, maximum.installationReads().size)
        val required = f.journal.declaration().limits.decoder.copy(
            maximumPlaintextBytes = expected.size, maximumJsonTokens = 2568, maximumJsonDepth = 5,
            maximumObjectFields = 15, maximumStringUtf8Bytes = 64,
        )
        val exact = json(required, Long.MAX_VALUE, Long.MAX_VALUE)
        assertArrayEquals(expected, exact.encodeProgress(maximum))
        assertArrayEquals(expected, exact.encodeProgress(exact.progress(expected)))
        assertEquals(maximum.completedCuts(), exact.progress(expected).completedCuts())
        for (limit in listOf(
            required.copy(maximumPlaintextBytes = expected.size - 1), required.copy(maximumJsonTokens = 2567),
            required.copy(maximumJsonDepth = 4), required.copy(maximumObjectFields = 14),
            required.copy(maximumStringUtf8Bytes = 63),
        )) {
            val below = json(limit, Long.MAX_VALUE, Long.MAX_VALUE)
            assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { below.encodeProgress(maximum) }.code)
            assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { below.progress(expected) }.code)
        }
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected {
            f.json.progress(ByteArray(TestTerminalProgressV1.MAX_CANONICAL_BYTES + 1) { 32 })
        }.code)
        assertEquals(listOf(500, 4096, 15, 16, 15, 65536, 98304, 6, 32, 8192, 1024), listOf(
            TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK, TestTerminalProfileV1.MAX_MANIFEST_CHUNKS,
            TestTerminalProfileV1.MAX_PRE_TERMINAL_SEALS, TestTerminalProfileV1.MAX_SEALS,
            TestTerminalProfileV1.MAX_DENIAL_RANGES, TestTerminalProfileV1.MAX_PLAINTEXT_BYTES,
            TestTerminalProfileV1.MAX_ENVELOPE_BYTES, TestTerminalProfileV1.MAX_JSON_DEPTH,
            TestTerminalProfileV1.MAX_OBJECT_FIELDS, TestTerminalProfileV1.MAX_JSON_TOKENS, TestTerminalProfileV1.MAX_STRING_BYTES,
        ))
        assertEquals(4096, f.journal.declaration().limits.decoder.maximumJsonTokens)
    }

    @Test
    fun rejectsMalformedNoncanonicalAndCrossDocumentInputs() {
        val value = progress(listOf(cut()), readPair())
        val root = reference(value)
        val canonical = terminalCanonical(root)
        val literal = canonical.toString(Charsets.UTF_8)
        fun rejectChanged(bytes: ByteArray) {
            assertFalse(canonical.contentEquals(bytes), "mutation must change the input")
            terminalRejected { f.json.progress(bytes) }
        }
        for (field in root.keys) rejectChanged(terminalCanonical(JsonObject(root - field)))
        val rootChanges = listOf(
            "unexpected" to JsonPrimitive("not-admitted"), "schemaVersion" to JsonPrimitive(2),
            "documentKind" to JsonPrimitive("TEST_TERMINAL_V1"), "dataScopeKind" to JsonPrimitive("LIVE"),
            "configurationSha256" to JsonPrimitive("B".repeat(64)), "terminalEncodingSha256" to JsonPrimitive("f".repeat(64)),
            "completedCuts" to JsonNull, "installationReads" to JsonNull, "dataScopeId" to JsonNull,
            "activationCatalogGeneration" to JsonPrimitive(0), "activationCatalogGeneration" to JsonPrimitive(65537),
        )
        rootChanges.forEach { rejectChanged(terminalCanonical(JsonObject(root + it))) }
        val cutObject = reference(value.completedCuts().single())
        val readObject = reference(value.installationReads().first())
        val highWater = reference(value.installationReads().first().sourceHighWater)
        for (field in cutObject.keys) rejectChanged(terminalCanonical(JsonObject(root + (
            "completedCuts" to JsonArray(listOf(JsonObject(cutObject - field)))
        ))))
        for (field in readObject.keys) rejectChanged(terminalCanonical(JsonObject(root + (
            "installationReads" to JsonArray(listOf(JsonObject(readObject - field), reference(value.installationReads()[1])))
        ))))
        for (field in highWater.keys) rejectChanged(terminalCanonical(JsonObject(root + (
            "installationReads" to JsonArray(listOf(JsonObject(readObject + ("sourceHighWater" to JsonObject(highWater - field))),
                reference(value.installationReads()[1])))
        ))))
        for (change in listOf(
            "prefixKind" to JsonPrimitive("TERMINAL"), "denial" to JsonNull, "unexpected" to JsonPrimitive(1),
        )) rejectChanged(terminalCanonical(JsonObject(root + ("completedCuts" to JsonArray(listOf(JsonObject(cutObject + change)))))))
        for (change in listOf("sourceHighWater" to JsonNull, "unexpected" to JsonPrimitive(1))) {
            rejectChanged(terminalCanonical(JsonObject(root + ("installationReads" to JsonArray(listOf(
                JsonObject(readObject + change), reference(value.installationReads()[1]),
            ))))))
        }
        rejectChanged(terminalCanonical(JsonObject(root + ("installationReads" to JsonArray(listOf(
            JsonObject(readObject + ("sourceHighWater" to JsonObject(highWater + ("unexpected" to JsonPrimitive(1))))),
            reference(value.installationReads()[1]),
        ))))))
        for (number in listOf("01", "+1", "1.0", "1e0", "-0", "-1", "9223372036854775808", "\"1\"")) {
            rejectChanged(literal.replace("\"activationCatalogGeneration\":4", "\"activationCatalogGeneration\":$number").toByteArray())
        }
        val malformed = listOf(
            JsonObject(root.entries.reversed().associate { it.key to it.value }).toString().toByteArray(),
            (" " + literal).toByteArray(), (literal + "\n").toByteArray(), (literal + "{}").toByteArray(),
            literal.dropLast(1).toByteArray(), ByteArray(0),
            literal.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").toByteArray(),
            literal.replace("\"prefixKind\":\"ORDINARY\"", "\"prefixKind\":\"ORDINARY\",\"prefixKind\":\"ORDINARY\"").toByteArray(),
            literal.replace("TEST_TERMINAL_PROGRESS_V1", "\\u0054EST_TERMINAL_PROGRESS_V1").toByteArray(),
            literal.replace(WRITER, WRITER.uppercase()).toByteArray(),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + canonical,
            byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
        )
        malformed.forEach(::rejectChanged)
        val oldReaders: Map<String, (ByteArray) -> Any> = mapOf(
            "encoding" to f.json::encoding, "installationManifest" to f.json::installationManifest, "purge" to f.json::purge,
            "epochSeal" to f.json::epochSeal, "sealSet" to f.json::sealSet, "denialSet" to f.json::denialSet,
            "eventHeader" to f.json::eventHeader, "sealHeader" to f.json::sealHeader,
        )
        // Even all 30 complete cuts remain progress, not a final DenialSet or any other authority-bearing family.
        val full = terminalCanonical(reference(maximumProgress()))
        for ((name, reader) in oldReaders) {
            terminalRejected(name) { reader(canonical) }
            terminalRejected("full progress to $name") { reader(full) }
            terminalRejected("$name to progress") { f.json.progress(f.bytes(name)) }
        }
        assertArrayEquals(f.bytes("denialSet"), f.json.encodeDenialSet(f.json.denialSet(f.bytes("denialSet"))))
        assertArrayEquals(f.bytes("sealSet"), f.json.encodeSealSet(f.json.sealSet(f.bytes("sealSet"))))
        assertArrayEquals(f.bytes("encoding"), TestTerminalProfileV1.encodingBytes())
        terminalRejected { f.json.denialSet(terminalCanonical(JsonObject(f.value("denialSet") + ("completedCuts" to JsonArray(emptyList()))))) }
        terminalRejected { f.json.sealSet(terminalCanonical(JsonObject(f.value("sealSet") + ("installationReads" to JsonArray(emptyList()))))) }
    }

    @Test
    fun rejectsIncompleteDuplicateUnorderedAndForeignCutClaims() {
        val ordinary = cut()
        val terminal = cut(TestTerminalDenialPrefixV1.SEAL_TERMINAL)
        val maximumCuts = maximumProgress().completedCuts()
        for (cuts in listOf(
            listOf(ordinary, ordinary.copy(scanId = uuid(99))), listOf(terminal, ordinary),
            listOf(ordinary, terminal.copy(denial = ordinary.denial)),
            listOf(ordinary.copy(writerGeneration = uuid(1)), ordinary.copy(writerGeneration = uuid(2))),
        )) {
            terminalRejected { progress(cuts) }
            val root = JsonObject(reference(progress()) + ("completedCuts" to JsonArray(cuts.map { reference(it) })))
            terminalRejected { f.json.progress(terminalCanonical(root)) }
        }
        val tooManyWriters = (1..16).map { cut(writer = uuid(it), scan = uuid(100 + it)) }
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected {
            progress(tooManyWriters)
        }.code)
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected {
            f.json.progress(terminalCanonical(JsonObject(reference(progress()) + ("completedCuts" to JsonArray(tooManyWriters.map { reference(it) })))))
        }.code)
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { progress(maximumCuts + maximumCuts.last()) }.code)
        val tooMany = JsonObject(reference(progress()) + ("completedCuts" to JsonArray((maximumCuts + maximumCuts.last()).map { reference(it) })))
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { f.json.progress(terminalCanonical(tooMany)) }.code)
        for (change in listOf<() -> Unit>(
            { ordinary.copy(writerGeneration = WRITER.uppercase()) }, { ordinary.copy(scanId = "not-a-uuid") },
            { ordinary.copy(databaseIdentity = "00000000-0000-3000-8000-000000000001") },
            { ordinary.copy(restoreIdentity = "") }, { ordinary.copy(epochStartInclusive = 0) },
            { ordinary.copy(epochStartInclusive = 3, epochEndInclusive = 2) }, { ordinary.copy(desiredGeneration = 0) },
            { ordinary.copy(fencingToken = -1) }, { ordinary.copy(framedByteCount = 0) },
            { ordinary.denial.copy(firstInventory = ordinary.denial.firstInventory.copy(startedAtEpochSecond = 94)) },
            { ordinary.denial.copy(secondInventory = ordinary.denial.secondInventory.copy(startedAtEpochSecond = 105)) },
            { ordinary.denial.copy(secondInventory = ordinary.denial.secondInventory.copy(versionCount = 7)) },
            { ordinary.denial.copy(secondInventory = ordinary.denial.secondInventory.copy(byteCount = 4097)) },
            { ordinary.denial.copy(secondInventory = ordinary.denial.secondInventory.copy(sha256 = "a".repeat(64))) },
            { ordinary.denial.copy(acceptedRequestBoundSeconds = Long.MAX_VALUE) },
        )) terminalRejected(action = change)

        // Canonical lowercase text / unsigned PostgreSQL UUID order crosses Java UUID's signed MSB boundary.
        val low = ordinary.copy(writerGeneration = "7fffffff-ffff-4fff-8fff-ffffffffffff", scanId = uuid(71))
        val high = ordinary.copy(writerGeneration = "80000000-0000-4000-8000-000000000000", scanId = uuid(72))
        val ordered = progress(listOf(low, high))
        assertEquals(listOf(low, high), f.json.progress(f.json.encodeProgress(ordered)).completedCuts())
        terminalRejected { progress(listOf(high, low)) }
        terminalRejected { f.json.progress(terminalCanonical(JsonObject(reference(ordered) + (
            "completedCuts" to JsonArray(listOf(reference(high), reference(low)))
        )))) }
        val foreign = progress(listOf(ordinary), context = f.run.copy(dataScopeId = uuid(99)))
        terminalRejected { f.json.encodeProgress(foreign) }
        terminalRejected { f.json.progress(terminalCanonical(reference(foreign))) }
        // Stored restore/fence values are historical observations; this codec cannot compare them to a current lease.
        val historical = progress(listOf(ordinary.copy(restoreIdentity = uuid(73), desiredGeneration = 1, fencingToken = 1)))
        assertEquals(historical.completedCuts(), f.json.progress(f.json.encodeProgress(historical)).completedCuts())
    }

    @Test
    fun requiresCompleteMatchingInstallationReadPairsAndHighWater() {
        val first = read()
        val second = readPair(first)[1]
        terminalRejected { progress(reads = listOf(first)) }
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { progress(reads = listOf(first, second, second)) }.code)
        for (reads in listOf(listOf(first), listOf(first, second, second))) {
            val root = JsonObject(reference(progress()) + ("installationReads" to JsonArray(reads.map { reference(it) })))
            terminalRejected { f.json.progress(terminalCanonical(root)) }
        }
        val touching = progress(reads = listOf(first, second.copy(startedAtEpochSecond = first.completedAtEpochSecond)))
        assertEquals(touching.installationReads(), f.json.progress(f.json.encodeProgress(touching)).installationReads())
        val changedSeconds = listOf(
            second.copy(databaseIdentity = uuid(81)), second.copy(restoreIdentity = uuid(82)),
            second.copy(desiredGeneration = second.desiredGeneration + 1), second.copy(fencingToken = second.fencingToken + 1),
            second.copy(sourceHighWater = second.sourceHighWater.copy(greatestReservationId = uuid(83))),
            second.copy(sourceHighWater = second.sourceHighWater.copy(sourceSha256 = "b".repeat(64))),
            second.copy(sourceHighWater = second.sourceHighWater.copy(framedByteCount = second.sourceHighWater.framedByteCount + 1)),
            second.copy(installationsSha256 = "c".repeat(64)), second.copy(chunkSetSha256 = "d".repeat(64)),
            second.copy(installationsFramedBytes = second.installationsFramedBytes + 1), second.copy(chunkSetFramedBytes = second.chunkSetFramedBytes + 1),
            second.copy(retiredCount = 0, deletedCount = 2),
            second.copy(installationCount = 3, retiredCount = 2, sourceHighWater = second.sourceHighWater.copy(enrolledCount = 3, reservationCount = 3)),
            second.copy(installationCount = 501, retiredCount = 500, chunkCount = 2,
                sourceHighWater = second.sourceHighWater.copy(enrolledCount = 501, reservationCount = 501)),
            second.copy(startedAtEpochSecond = first.startedAtEpochSecond),
        )
        for (changed in changedSeconds) {
            assertNotEquals(second, changed)
            terminalRejected { progress(reads = listOf(first, changed)) }
            val root = JsonObject(reference(progress()) + ("installationReads" to JsonArray(listOf(reference(first), reference(changed)))))
            terminalRejected { f.json.progress(terminalCanonical(root)) }
        }
        for (change in listOf<() -> Unit>(
            { first.sourceHighWater.copy(enrolledCount = 1) },
            { first.sourceHighWater.copy(enrolledCount = 2_048_001, reservationCount = 2_048_001) },
            { first.sourceHighWater.copy(enrolledCount = -1, reservationCount = -1) },
            { first.sourceHighWater.copy(greatestReservationId = "") },
            { first.sourceHighWater.copy(enrolledCount = 0, reservationCount = 0) },
            { first.sourceHighWater.copy(greatestReservationId = "00000000-0000-3000-8000-000000000003") },
            { first.sourceHighWater.copy(sourceSha256 = "A".repeat(64)) }, { first.sourceHighWater.copy(framedByteCount = 0) },
            { first.copy(databaseIdentity = "") }, { first.copy(restoreIdentity = "") }, { first.copy(desiredGeneration = 0) },
            { first.copy(fencingToken = 0) }, { first.copy(startedAtEpochSecond = -1) }, { first.copy(completedAtEpochSecond = 199) },
            { first.copy(installationCount = 3) }, { first.copy(retiredCount = -1) }, { first.copy(deletedCount = 2) },
            { first.copy(retiredCount = Long.MAX_VALUE, deletedCount = 1) }, { first.copy(chunkCount = 0) }, { first.copy(chunkCount = 2) },
            { first.copy(installationsSha256 = "bad") }, { first.copy(chunkSetSha256 = "bad") },
            { first.copy(installationsFramedBytes = 0) }, { first.copy(chunkSetFramedBytes = 0) },
        )) terminalRejected(action = change)
        val zero = first.copy(
            sourceHighWater = first.sourceHighWater.copy(enrolledCount = 0, reservationCount = 0, greatestReservationId = ""),
            installationCount = 0, retiredCount = 0, deletedCount = 0, chunkCount = 0,
        )
        val emptySource = progress(reads = readPair(zero))
        assertEquals(emptySource.installationReads(), f.json.progress(f.json.encodeProgress(emptySource)).installationReads())
        assertTrue(zero.installationsFramedBytes > 0 && zero.sourceHighWater.framedByteCount > 0 && zero.chunkSetFramedBytes > 0)
        // Counts/end-key equality is checked; actual membership, raw states and a real maximum UUID are not proved here.
    }

    @Test
    fun keepsActualJournalVersionAndFramedLimitsSeparateFromJson() {
        val base = cut()
        val versionBound = progress(listOf(base))
        val versionBytes = terminalCanonical(reference(versionBound))
        val exactVersions = json(versions = 6)
        assertArrayEquals(versionBytes, exactVersions.encodeProgress(versionBound))
        assertEquals(versionBound.completedCuts(), exactVersions.progress(versionBytes).completedCuts())
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { json(versions = 5).encodeProgress(versionBound) }.code)
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { json(versions = 5).progress(versionBytes) }.code)

        val byteCountIsNotFraming = base.copy(denial = base.denial.copy(
            firstInventory = base.denial.firstInventory.copy(byteCount = 1_000_000),
            secondInventory = base.denial.secondInventory.copy(byteCount = 1_000_000),
            policyEvidence = base.denial.policyEvidence.copy(byteCount = 1_000_000),
        ))
        val declarations = listOf(
            progress(listOf(byteCountIsNotFraming.copy(framedByteCount = 8192))),
            progress(reads = readPair(read().copy(sourceHighWater = read().sourceHighWater.copy(framedByteCount = 8192)))),
            progress(reads = readPair(read().copy(installationsFramedBytes = 8192))),
            progress(reads = readPair(read().copy(chunkSetFramedBytes = 8192))),
        )
        for (record in declarations) {
            val bytes = terminalCanonical(reference(record))
            assertArrayEquals(bytes, json(framedBytes = 8192).encodeProgress(record))
            assertArrayEquals(bytes, json(framedBytes = 8192).encodeProgress(json(framedBytes = 8192).progress(bytes)))
            assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { json(framedBytes = 8191).encodeProgress(record) }.code)
            assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { json(framedBytes = 8191).progress(bytes) }.code)
        }
        val many = read().copy(
            sourceHighWater = TestTerminalSourceHighWaterV1(2_048_000, 2_048_000, uuid(90), "a".repeat(64), 124_928_477),
            installationCount = 2_048_000, retiredCount = 1_000_000, deletedCount = 1_048_000, chunkCount = 4096,
            installationsFramedBytes = 104_448_316, chunkSetFramedBytes = 397_612,
        )
        val manyRecord = progress(reads = readPair(many))
        val manyBytes = terminalCanonical(reference(manyRecord))
        val separateLimits = json(versions = 4097, framedBytes = 124_928_477)
        assertTrue(many.installationCount > 4097 && many.sourceHighWater.framedByteCount > 65_536 && manyBytes.size < 65_536)
        assertArrayEquals(manyBytes, separateLimits.encodeProgress(manyRecord))
        assertEquals(manyRecord.installationReads(), separateLimits.progress(manyBytes).installationReads())
        // K < R is only the codec's minimal chunk/purge-slot check, not all terminal headroom or admitted N.
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected {
            json(versions = 4096, framedBytes = 124_928_477).encodeProgress(manyRecord)
        }.code)
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected {
            json(versions = 4096, framedBytes = 124_928_477).progress(manyBytes)
        }.code)

        // Measure one existing installation fold with the independent LP32 helper; no new source-fold producer.
        val header = listOf("kira-test-installations-v1", SCOPE, "4", f.run.activationCatalogSha256, f.run.configurationSha256,
            f.run.terminalEncodingSha256, "2", "1", "1")
        val frames = listOf(terminalFrame(header)) + f.entries.map { terminalFrame(listOf(it.installationId, it.disposition.name)) }
        val measured = frames.sumOf { it.size.toLong() }
        assertEquals(f.summary.installationsSha256, terminalHash(*frames.toTypedArray()))
        val measuredRead = progress(reads = readPair(read().copy(installationsFramedBytes = measured)))
        assertEquals(measured, f.json.progress(f.json.encodeProgress(measuredRead)).installationReads().first().installationsFramedBytes)
    }

    private fun progress(
        cuts: List<TestTerminalCompletedCutV1> = emptyList(), reads: List<TestTerminalInstallationReadV1> = emptyList(),
        context: TestTerminalRunContextV1 = f.run,
    ): TestTerminalProgressV1 = TestTerminalProgressV1.create(context, cuts, reads)

    private fun cut(
        prefix: TestTerminalDenialPrefixV1 = TestTerminalDenialPrefixV1.ORDINARY, writer: String = WRITER,
        scan: String = uuid(200 + prefix.ordinal),
    ): TestTerminalCompletedCutV1 = TestTerminalCompletedCutV1(
        writer, prefix, 1, 2, scan, uuid(300), uuid(400), 7, 17, 8192,
        f.cut(if (prefix == TestTerminalDenialPrefixV1.ORDINARY) "ordinary" else "terminal"),
    )

    private fun read(): TestTerminalInstallationReadV1 = TestTerminalInstallationReadV1(
        uuid(300), uuid(400), 7, 17, 200, 201,
        TestTerminalSourceHighWaterV1(2, 2, f.entries.last().installationId, "a".repeat(64), 1000),
        2, 1, 1, 1, f.summary.installationsSha256, 500, "b".repeat(64), 200,
    )

    private fun readPair(first: TestTerminalInstallationReadV1 = read()): List<TestTerminalInstallationReadV1> = listOf(
        first, first.copy(startedAtEpochSecond = first.completedAtEpochSecond + 1, completedAtEpochSecond = first.completedAtEpochSecond + 2),
    )

    private fun maximumProgress(): TestTerminalProgressV1 {
        val first = TestTerminalInventoryWitnessV1(2_000_000_000_000_000_000, 3_000_000_000_000_000_000,
            Long.MAX_VALUE, Long.MAX_VALUE, "a".repeat(64))
        val denial = TestTerminalDenialCutV1(
            "o".repeat(64), TestTerminalPolicyRefV1("p".repeat(64), Long.MAX_VALUE, "b".repeat(64)),
            1_000_000_000_000_000_000, 1_000_000_000_000_000_000, 1_000_000_000_000_000_000,
            TestTerminalEvidenceDigestV1("c".repeat(64), Long.MAX_VALUE), TestTerminalEvidenceDigestV1("d".repeat(64), Long.MAX_VALUE),
            first, first.copy(startedAtEpochSecond = 4_000_000_000_000_000_000, completedAtEpochSecond = 5_000_000_000_000_000_000),
        )
        val cuts = (1..15).flatMap { index -> TestTerminalDenialPrefixV1.entries.map { prefix ->
            cut(prefix, uuid(index), uuid(100 + index * 2 + prefix.ordinal)).copy(
                epochStartInclusive = Long.MAX_VALUE, epochEndInclusive = Long.MAX_VALUE, desiredGeneration = Long.MAX_VALUE,
                fencingToken = Long.MAX_VALUE, framedByteCount = Long.MAX_VALUE,
                denial = denial.copy(roleId = (if (prefix == TestTerminalDenialPrefixV1.ORDINARY) "o" else "t").repeat(64)),
            )
        } }
        val read = read().copy(
            desiredGeneration = Long.MAX_VALUE, fencingToken = Long.MAX_VALUE,
            startedAtEpochSecond = 6_000_000_000_000_000_000, completedAtEpochSecond = 7_000_000_000_000_000_000,
            sourceHighWater = TestTerminalSourceHighWaterV1(2_048_000, 2_048_000, uuid(99), "e".repeat(64), Long.MAX_VALUE),
            installationCount = 2_048_000, retiredCount = 1_000_000, deletedCount = 1_048_000, chunkCount = 4096,
            installationsFramedBytes = Long.MAX_VALUE, chunkSetFramedBytes = Long.MAX_VALUE,
        )
        return progress(cuts, listOf(read, read.copy(
            startedAtEpochSecond = 8_000_000_000_000_000_000, completedAtEpochSecond = 9_000_000_000_000_000_000,
        )), f.run.copy(activationCatalogGeneration = 65536))
    }

    private fun json(
        decoder: JournalDecoderLimitsV1 = f.journal.declaration().limits.decoder,
        versions: Long = f.journal.declaration().limits.capacity.maximumRetainedVersions,
        framedBytes: Long = f.journal.declaration().limits.capacity.maximumScanStagingBytes,
    ): TestTerminalJsonV1 {
        val declaration = f.journal.declaration()
        return TestTerminalJsonV1(TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(limits = declaration.limits.copy(
            decoder = decoder, capacity = declaration.limits.capacity.copy(maximumRetainedVersions = versions, maximumScanStagingBytes = framedBytes),
        ))))
    }

    /** Explicit independent field writer, not the production serializer or CanonicalJson. */
    private fun reference(value: TestTerminalProgressV1): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("documentKind", "TEST_TERMINAL_PROGRESS_V1")
        put("dataScopeId", value.dataScopeId)
        put("activationCatalogGeneration", value.activationCatalogGeneration)
        put("activationCatalogSha256", value.activationCatalogSha256)
        put("configurationSha256", value.configurationSha256)
        put("terminalEncodingSha256", value.terminalEncodingSha256)
        put("completedCuts", JsonArray(value.completedCuts().map { reference(it) }))
        put("installationReads", JsonArray(value.installationReads().map { reference(it) }))
    }

    private fun reference(value: TestTerminalCompletedCutV1): JsonObject = buildJsonObject {
        put("writerGeneration", value.writerGeneration)
        put("prefixKind", value.prefixKind.name)
        put("epochStartInclusive", value.epochStartInclusive)
        put("epochEndInclusive", value.epochEndInclusive)
        put("scanId", value.scanId)
        put("databaseIdentity", value.databaseIdentity)
        put("restoreIdentity", value.restoreIdentity)
        put("desiredGeneration", value.desiredGeneration)
        put("fencingToken", value.fencingToken)
        put("framedByteCount", value.framedByteCount)
        put("denial", reference(value.denial))
    }

    private fun reference(value: TestTerminalDenialCutV1): JsonObject = buildJsonObject {
        put("roleId", value.roleId)
        put("policy", buildJsonObject {
            put("policyId", value.policy.policyId)
            put("version", value.policy.version)
            put("sha256", value.policy.sha256)
        })
        put("denialEffectiveAtEpochSecond", value.denialEffectiveAtEpochSecond)
        put("lastSessionExpiryEpochSecond", value.lastSessionExpiryEpochSecond)
        put("acceptedRequestBoundSeconds", value.acceptedRequestBoundSeconds)
        put("policyEvidence", reference(value.policyEvidence))
        put("boundEvidence", reference(value.boundEvidence))
        put("firstInventory", reference(value.firstInventory))
        put("secondInventory", reference(value.secondInventory))
    }

    private fun reference(value: TestTerminalEvidenceDigestV1): JsonObject = buildJsonObject {
        put("sha256", value.sha256)
        put("byteCount", value.byteCount)
    }

    private fun reference(value: TestTerminalInventoryWitnessV1): JsonObject = buildJsonObject {
        put("startedAtEpochSecond", value.startedAtEpochSecond)
        put("completedAtEpochSecond", value.completedAtEpochSecond)
        put("versionCount", value.versionCount)
        put("byteCount", value.byteCount)
        put("sha256", value.sha256)
    }

    private fun reference(value: TestTerminalInstallationReadV1): JsonObject = buildJsonObject {
        put("databaseIdentity", value.databaseIdentity)
        put("restoreIdentity", value.restoreIdentity)
        put("desiredGeneration", value.desiredGeneration)
        put("fencingToken", value.fencingToken)
        put("startedAtEpochSecond", value.startedAtEpochSecond)
        put("completedAtEpochSecond", value.completedAtEpochSecond)
        put("sourceHighWater", reference(value.sourceHighWater))
        put("installationCount", value.installationCount)
        put("retiredCount", value.retiredCount)
        put("deletedCount", value.deletedCount)
        put("chunkCount", value.chunkCount)
        put("installationsSha256", value.installationsSha256)
        put("installationsFramedBytes", value.installationsFramedBytes)
        put("chunkSetSha256", value.chunkSetSha256)
        put("chunkSetFramedBytes", value.chunkSetFramedBytes)
    }

    private fun reference(value: TestTerminalSourceHighWaterV1): JsonObject = buildJsonObject {
        put("enrolledCount", value.enrolledCount)
        put("reservationCount", value.reservationCount)
        put("greatestReservationId", value.greatestReservationId)
        put("sourceSha256", value.sourceSha256)
        put("framedByteCount", value.framedByteCount)
    }
}
