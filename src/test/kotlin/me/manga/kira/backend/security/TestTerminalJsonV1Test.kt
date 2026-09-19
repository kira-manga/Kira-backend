package me.manga.kira.backend.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEncodingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationEntryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.SCOPE
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.WRITER
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.key
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.opaque
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.uuid
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Closed declaration contracts only, not KJEV authentication, accepted activation, notice seeds or provider evidence. */
class TestTerminalJsonV1Test {
    private val f = TestTerminalTestFixture()

    @Test
    fun closedPayloadsAndSetsMatchIndependentCanonicalGoldens() {
        golden("encoding", f.json.encodeEncoding(TestTerminalEncodingV1("TEST_TERMINAL_V1", 1))) { f.json.encodeEncoding(f.json.encoding(it)) }
        golden("installationManifest", f.json.encodeInstallationManifest(f.manifest)) { f.json.encodeInstallationManifest(f.json.installationManifest(it)) }
        golden("purge", f.json.encodePurge(f.purge)) { f.json.encodePurge(f.json.purge(it)) }
        golden("epochSeal", f.json.encodeEpochSeal(f.ordinarySeal)) { f.json.encodeEpochSeal(f.json.epochSeal(it)) }
        golden("sealSet", f.json.encodeSealSet(f.seals())) { f.json.encodeSealSet(f.json.sealSet(it)) }
        golden("denialSet", f.json.encodeDenialSet(f.denials())) { f.json.encodeDenialSet(f.json.denialSet(it)) }
        assertArrayEquals(f.bytes("encoding"), TestTerminalProfileV1.encodingBytes())
        TestTerminalProfileV1.encodingBytes().fill(0)
        assertEquals(f.literal("encoding").terminalText("sha256"), TestTerminalProfileV1.encodingSha256)
        assertArrayEquals(f.bytes("encoding"), TestTerminalProfileV1.encodingBytes())
        val entries = f.entries.toMutableList()
        val manifest = f.manifest(context = f.manifest.context(), entries = entries)
        entries.clear()
        assertEquals(f.entries, manifest.entries())
        assertNotSame(manifest.entries(), manifest.entries())
        val records = mutableListOf(f.sealRef)
        val seals = f.seals(records)
        records.clear()
        assertEquals(listOf(f.sealRef), seals.records())
        val ranges = mutableListOf(f.denial())
        val denials = f.denials(ranges)
        ranges.clear()
        assertEquals(listOf(f.denial()), denials.ranges())
        val input = f.bytes("installationManifest")
        val decoded = f.json.installationManifest(input)
        input.fill(0)
        assertArrayEquals(f.bytes("installationManifest"), f.json.encodeInstallationManifest(decoded))
        val rendered = listOf(f.run, f.manifest.context(), manifest, f.purge, f.ordinarySeal, f.sealRef, seals, denials, f.json).joinToString()
        for (sensitive in listOf(SCOPE, WRITER, f.manifest.eventId, f.manifestRef.objectKey, f.run.configurationSha256)) assertFalse(rendered.contains(sensitive))
    }

    @Test
    fun strictParsingRejectsUnknownDuplicateNonCanonicalUnicodeAndTrailingInput() {
        val readers: Map<String, (ByteArray) -> Any> = mapOf(
            "encoding" to f.json::encoding, "installationManifest" to f.json::installationManifest, "purge" to f.json::purge,
            "epochSeal" to f.json::epochSeal, "sealSet" to f.json::sealSet, "denialSet" to f.json::denialSet,
            "eventHeader" to f.json::eventHeader, "sealHeader" to f.json::sealHeader,
        )
        readers.forEach { (name, read) ->
            val literal = f.literal(name).terminalText("canonical_utf8")
            val schema = if (name.endsWith("Header")) "envelopeSchemaVersion" else "schemaVersion"
            val wrongOrder = JsonObject(f.value(name).entries.reversed().associate { it.key to it.value }).toString().toByteArray()
            val malformed = listOf(
                f.mutate(name, "unexpected" to JsonPrimitive("not-admitted")), f.mutate(name, schema to JsonNull),
                terminalCanonical(JsonObject(f.value(name) - schema)), wrongOrder, (" " + literal).toByteArray(), (literal + "\n").toByteArray(),
                (literal + "{}").toByteArray(), literal.dropLast(1).toByteArray(), ByteArray(0),
                literal.replace("\"$schema\":1", "\"$schema\":1,\"$schema\":1").toByteArray(),
                byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + literal.toByteArray(),
                byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            )
            malformed.forEachIndexed { index, bytes -> terminalRejected("$name malformed $index") { read(bytes) } }
        }
        val manifest = f.literal("installationManifest").terminalText("canonical_utf8")
        for (number in listOf("01", "+1", "1.0", "1e0", "-0", "-1", "9223372036854775808")) {
            terminalRejected(number) { f.json.installationManifest(manifest.replace("\"activationCatalogGeneration\":4", "\"activationCatalogGeneration\":$number").toByteArray()) }
        }
        val seals = f.literal("sealSet").terminalText("canonical_utf8")
        for (version in listOf("\\uD800", "\\uDC00", "\\u0000", "\\u007f", "null", "")) {
            terminalRejected("invalid version") { f.json.sealSet(seals.replace("version-1", version).toByteArray()) }
        }
        terminalRejected { f.json.encoding(f.bytes("encoding").toString(Charsets.UTF_8).replace("TEST", "\\u0054EST").toByteArray()) }
        terminalRejected { f.json.installationManifest(manifest.replace(WRITER, WRITER.uppercase()).toByteArray()) }
        terminalRejected { f.json.installationManifest(manifest.replace("RETIRED", "ACTIVE").toByteArray()) }
        terminalRejected { f.json.installationManifest(manifest.replace("\"disposition\":\"RETIRED\"", "\"disposition\":\"RETIRED\",\"disposition\":\"RETIRED\"").toByteArray()) }
        terminalRejected { f.json.installationManifest(f.mutate("installationManifest", "entries" to JsonArray(emptyList()))) }
    }

    @Test
    fun legalMaximumRecordsRespectProfileAndActualJournalLimitsWithoutTruncation() {
        assertEquals(listOf(500, 4096, 15, 16, 15, 65536, 98304, 4096, 28636, 6, 32, 8192, 1024), listOf(
            TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK, TestTerminalProfileV1.MAX_MANIFEST_CHUNKS, TestTerminalProfileV1.MAX_PRE_TERMINAL_SEALS,
            TestTerminalProfileV1.MAX_SEALS, TestTerminalProfileV1.MAX_DENIAL_RANGES, TestTerminalProfileV1.MAX_PLAINTEXT_BYTES,
            TestTerminalProfileV1.MAX_ENVELOPE_BYTES, TestTerminalProfileV1.MAX_HEADER_BYTES, TestTerminalProfileV1.MAX_WRAPPED_KEY_BYTES,
            TestTerminalProfileV1.MAX_JSON_DEPTH, TestTerminalProfileV1.MAX_OBJECT_FIELDS, TestTerminalProfileV1.MAX_JSON_TOKENS, TestTerminalProfileV1.MAX_STRING_BYTES,
        ))
        val entries = List(500) { TestTerminalInstallationEntryV1(uuid(it + 1), if (it < 250) TestTerminalDispositionV1.RETIRED else TestTerminalDispositionV1.DELETED) }
        val context = f.context(run = f.run.copy(activationCatalogGeneration = 65536), epoch = Long.MAX_VALUE)
        val maximum = f.manifest(context, entries, root = terminalInstallationHash(context.run, entries))
        val bytes = f.json.encodeInstallationManifest(maximum)
        golden("maximumInstallationManifest", bytes) { f.json.encodeInstallationManifest(f.json.installationManifest(it)) }
        assertTrue(bytes.size <= 41968 && bytes.size <= f.journal.declaration().limits.decoder.maximumPlaintextBytes)
        assertEquals(entries, f.json.installationManifest(bytes).entries())
        assertEquals(250L, maximum.retiredCount)
        assertEquals(250L, maximum.deletedCount)
        assertEquals(1, f.json.installationManifest(bytes).chunkCount)
        terminalRejected { f.manifest(entries = entries + entries.first()) }
        val records = maximumSeals()
        val sealBytes = f.json.encodeSealSet(f.seals(records))
        golden("maximumSealSet", sealBytes) { f.json.encodeSealSet(f.json.sealSet(it)) }
        assertTrue(sealBytes.size <= 57699)
        assertEquals(records, f.json.sealSet(sealBytes).records())
        terminalRejected { f.seals(records + records.last()) }
        terminalRejected { f.seals(records.map { it.copy(role = TestTerminalSealRoleV1.ORDINARY) }) }
        val ranges = (1..15).map { index ->
            val witness = TestTerminalInventoryWitnessV1(Long.MAX_VALUE - 10, Long.MAX_VALUE - 9, Long.MAX_VALUE, Long.MAX_VALUE, "a".repeat(64))
            val cut = f.cut("ordinary").copy(roleId = "o".repeat(64), policy = TestTerminalPolicyRefV1("p".repeat(64), Long.MAX_VALUE, "b".repeat(64)),
                denialEffectiveAtEpochSecond = Long.MAX_VALUE - 10, lastSessionExpiryEpochSecond = Long.MAX_VALUE - 10,
                policyEvidence = TestTerminalEvidenceDigestV1("c".repeat(64), Long.MAX_VALUE), boundEvidence = TestTerminalEvidenceDigestV1("d".repeat(64), Long.MAX_VALUE),
                firstInventory = witness, secondInventory = witness.copy(startedAtEpochSecond = Long.MAX_VALUE - 4, completedAtEpochSecond = Long.MAX_VALUE - 3))
            f.denial(uuid(index)).copy(ordinary = cut, terminal = cut.copy(roleId = "t".repeat(64)))
        }
        val denialBytes = f.json.encodeDenialSet(f.denials(ranges))
        golden("maximumDenialSet", denialBytes) { f.json.encodeDenialSet(f.json.denialSet(it)) }
        assertTrue(denialBytes.size <= 40861)
        assertEquals(ranges, f.json.denialSet(denialBytes).ranges())
        terminalRejected { f.denials(ranges + f.denial()) }
        val limits = f.journal.declaration().limits.decoder
        assertEquals(4096, limits.maximumJsonTokens) // Do not widen the actual J to the profile's 8192 tokens.
        assertEquals(3041, f.literal("maximumInstallationManifest").terminalText("json_tokens").toInt())
        assertEquals(entries, limitedJson(limits.copy(maximumJsonTokens = 3041)).installationManifest(bytes).entries())
        for (limited in listOf(limits.copy(maximumPlaintextBytes = bytes.size - 1), limits.copy(maximumJsonTokens = 3040),
            limits.copy(maximumObjectFields = 18), limits.copy(maximumJsonDepth = 2), limits.copy(maximumStringUtf8Bytes = 63))) {
            assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { limitedJson(limited).encodeInstallationManifest(maximum) }.code)
        }
        val unicodeRef = f.sealRef.copy(objectRef = f.sealRef.objectRef.copy(objectVersion = "é".repeat(512)))
        assertEquals(unicodeRef, f.json.sealSet(f.json.encodeSealSet(f.seals(listOf(unicodeRef)))).records().single())
        terminalRejected { f.sealRef.objectRef.copy(objectVersion = "é".repeat(513)) }
        terminalRejected { f.json.installationManifest(ByteArray(65537) { 32 }) }
        val maxVersions = f.journal.declaration().limits.capacity.maximumRetainedVersions
        terminalRejected { f.json.encodeEpochSeal(f.ordinarySeal.copy(eventCount = maxVersions + 1)) }
        terminalRejected { f.json.encodePurge(f.purge(inventory = f.countHash("preTerminalInventory").copy(count = maxVersions + 1))) }
        for ((count, chunks) in listOf(0L to 0, 1L to 1, 500L to 1, 501L to 2, 2048000L to 4096)) assertEquals(chunks, TestTerminalSyntaxV1.chunkCount(count))
        terminalRejected { TestTerminalSyntaxV1.chunkCount(2048001) }
    }

    @Test
    fun countsOrderEpochArithmeticAndScopeBindingsRejectConsistentLookingForgeries() {
        for ((field, value) in listOf("schemaVersion" to 2, "installationCount" to 3, "retiredCount" to 2, "deletedCount" to 2,
            "chunkIndex" to 1, "chunkCount" to 0, "chunkCount" to 4097, "activationCatalogGeneration" to 0, "activationCatalogGeneration" to 65537)) {
            terminalRejected(field) { f.json.installationManifest(f.mutate("installationManifest", field to JsonPrimitive(value))) }
        }
        for ((field, value) in listOf("entriesSha256" to "f".repeat(64), "terminalEncodingSha256" to "f".repeat(64), "dataScopeKind" to "LIVE", "eventKind" to "OWNER_DELETE")) {
            terminalRejected(field) { f.json.installationManifest(f.mutate("installationManifest", field to JsonPrimitive(value))) }
        }
        for (entries in listOf(emptyList(), f.entries.reversed(), listOf(f.entries.first(), f.entries.first()))) terminalRejected { f.manifest(entries = entries) }
        terminalRejected { f.manifest(count = 2) } // A non-final chunk cannot be short, even with self-consistent local counts/hash.
        terminalRejected { f.json.purge(f.mutate("purge", "publicationEpoch" to JsonPrimitive(4))) }
        terminalRejected { f.purge(finalEpoch = 1) }
        terminalRejected { f.purge(finalEpoch = Long.MAX_VALUE) }
        terminalRejected { f.purge(seals = f.countHash("preTerminalSeals").copy(count = 0)) }
        terminalRejected { f.purge(seals = f.countHash("preTerminalSeals").copy(count = 16)) }
        terminalRejected { f.purge(inventory = f.countHash("preTerminalInventory").copy(count = 0)) }
        terminalRejected { f.summary.copy(retiredCount = Long.MAX_VALUE, deletedCount = 1) }
        terminalRejected { TestTerminalSyntaxV1.nextEpoch(Long.MAX_VALUE) }
        terminalRejected { TestTerminalSyntaxV1.add(-1, 1) }
        val next = f.sealRef.copy(epochStartInclusive = 3, epochEndInclusive = 3, precedingSealSha256 = f.sealRef.objectRef.canonicalSha256,
            objectRef = f.sealRef.objectRef.copy(objectKey = key(3, "epoch-seal")))
        terminalRejected { f.seals(listOf(next, f.sealRef)) }
        terminalRejected { f.seals(listOf(f.sealRef, f.sealRef)) }
        terminalRejected { f.seals(listOf(f.sealRef, next.copy(precedingSealSha256 = "f".repeat(64)))) }
        val cut = f.cut("ordinary")
        for (change in listOf<() -> Unit>(
            { cut.copy(firstInventory = cut.firstInventory.copy(startedAtEpochSecond = 94)) },
            { cut.copy(secondInventory = cut.secondInventory.copy(startedAtEpochSecond = 105)) },
            { cut.copy(secondInventory = cut.secondInventory.copy(versionCount = 7)) },
            { cut.copy(secondInventory = cut.secondInventory.copy(byteCount = 4097)) },
            { cut.copy(secondInventory = cut.secondInventory.copy(sha256 = "f".repeat(64))) },
            { cut.copy(firstInventory = cut.firstInventory.copy(startedAtEpochSecond = Long.MAX_VALUE - 1, completedAtEpochSecond = Long.MAX_VALUE)) },
            { f.denial().copy(terminal = cut) }, { f.denials(listOf(f.denial(), f.denial())) },
            { f.denials(listOf(f.denial().copy(ordinaryPrefix = f.denial().sealTerminalPrefix))) },
        )) terminalRejected(action = change)
        val otherScope = ComplaintDataScope.of(UUID.fromString(uuid(99)))
        val other = TestTerminalJsonV1(ownerDeleteTestJournal(scope = otherScope))
        terminalRejected { other.installationManifest(f.bytes("installationManifest")) }
        terminalRejected { other.purge(f.bytes("purge")) }
        terminalRejected { other.epochSeal(f.bytes("epochSeal")) }
        terminalRejected { other.sealSet(f.bytes("sealSet")) }
        terminalRejected { other.denialSet(f.bytes("denialSet")) }
        terminalRejected { f.json.encodeInstallationManifest(f.manifest(context = f.context(writer = uuid(99)))) }
        terminalRejected { f.purge(seal = f.sealRef.copy(writerGeneration = uuid(99), objectRef = f.sealRef.objectRef.copy(objectKey = key(2, "epoch-seal", writer = uuid(99))))) }
        terminalRejected { f.run.copy(configurationSha256 = "B".repeat(64)) }
        terminalRejected { f.entries.first().copy(installationId = "00000000-0000-3000-8000-000000000002") }
        terminalRejected { f.context(id = "A".repeat(42) + "B") } // Nonzero unused base64url bits.
    }

    @Test
    fun typedHeadersBindTheTestTerminalFamilyWithoutLiveOrOrdinaryAliases() {
        golden("eventHeader", f.json.encodeEventHeader(f.eventHeader())) { f.json.encodeEventHeader(f.json.eventHeader(it)) }
        golden("purgeHeader", f.json.encodeEventHeader(f.eventHeader("TEST_RUN_PURGE"))) { f.json.encodeEventHeader(f.json.eventHeader(it)) }
        golden("sealHeader", f.json.encodeSealHeader(f.sealHeader())) { f.json.encodeSealHeader(f.json.sealHeader(it)) }
        val event = f.eventHeader()
        for (change in listOf<() -> Unit>(
            { event.copy(objectKind = "OWNER_DELETE") }, { event.copy(dataScopeKind = "LIVE") }, { event.copy(envelopeSchemaVersion = 2) },
            { event.copy(payloadSchemaVersion = 2) }, { event.copy(canonicalizerId = "kcj-2") }, { event.copy(encryptionAlgorithm = "AES-128-GCM") },
            { event.copy(dataKeyMode = "REUSED") }, { event.copy(objectKey = f.inventory.first().objectRef.objectKey) },
            { event.copy(objectKey = event.objectKey.replace("/3/", "/03/")) }, { event.copy(objectKind = "TEST_RUN_PURGE") },
            { event.copy(sealTerminalPrefix = TestTerminalTestFixture.ordinaryPrefix()) }, { event.copy(nonce = "A".repeat(15)) },
            { f.json.encodeEventHeader(event.copy(bucket = "other-valid-bucket")) }, { f.json.encodeEventHeader(event.copy(kmsKeyId = "other-kms")) },
            { f.json.encodeEventHeader(event.copy(kmsKeyArn = event.kmsKeyArn.replace("66666666-6666-4666-8666-666666666666", uuid(8)))) },
            { f.json.encodeEventHeader(event.copy(routingKeyId = "unknown", objectKey = event.objectKey.replace("test-route-01", "unknown"))) },
            { f.json.sealHeader(f.bytes("eventHeader")) }, { f.json.eventHeader(f.bytes("sealHeader")) },
            { f.sealHeader().copy(objectKind = "INSTALLATION_MANIFEST") }, { f.sealHeader().copy(dataScopeKind = "LIVE") },
        )) terminalRejected(action = change)
        terminalRejected { f.json.eventHeader(terminalCanonical(JsonObject((f.value("eventHeader") - "sealTerminalPrefix") + ("ordinaryPrefix" to JsonPrimitive(TestTerminalTestFixture.ordinaryPrefix()))))) }
        val size = f.bytes("eventHeader").size - 1
        val limits = f.journal.declaration().limits.decoder.copy(maximumPlaintextBytes = size, maximumJsonTokens = size, maximumStringUtf8Bytes = size)
        terminalRejected { limitedJson(limits).encodeEventHeader(event) }
        terminalRejected { f.json.eventHeader(ByteArray(4097) { 32 }) }
        assertEquals(98304, 20 + 4096 + 65536 + 28636 + 16) // Structural budget only; no KJEV/AEAD/KMS adapter exists in this slice.
        assertTrue(event.toString().contains("unauthenticated"))
    }

    private fun golden(name: String, actual: ByteArray, roundTrip: (ByteArray) -> ByteArray) {
        val expected = f.bytes(name)
        assertEquals(f.literal(name).terminalText("byte_count").toInt(), expected.size, name)
        assertEquals(f.literal(name).terminalText("sha256"), terminalHash(expected), name)
        assertArrayEquals(expected, actual, name)
        assertArrayEquals(expected, roundTrip(expected), name)
    }

    private fun limitedJson(limits: JournalDecoderLimitsV1): TestTerminalJsonV1 = TestTerminalJsonV1(ownerDeleteTestJournal(scope = f.journal.scope, decoder = limits))

    private fun maximumSeals(): List<TestTerminalSealRefV1> {
        val records = ArrayList<TestTerminalSealRefV1>()
        repeat(16) { index ->
            val end = Long.MAX_VALUE - 15 + index
            records.add(f.sealRef.copy(role = if (index == 15) TestTerminalSealRoleV1.TERMINAL else TestTerminalSealRoleV1.ORDINARY,
                epochStartInclusive = if (index == 0) 1 else end, epochEndInclusive = end, sealId = opaque(index),
                precedingSealSha256 = records.lastOrNull()?.objectRef?.canonicalSha256 ?: "", objectRef = f.sealRef.objectRef.copy(
                    objectKey = key(end, "epoch-seal", opaque(index), routing = "r".repeat(64)), objectVersion = "\\".repeat(1024),
                    canonicalSha256 = terminalHash("max-seal-$index".toByteArray()))))
        }
        return records
    }
}
