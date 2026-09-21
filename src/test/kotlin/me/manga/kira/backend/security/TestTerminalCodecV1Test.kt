package me.manga.kira.backend.security

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.security.TestTerminalCodecFailureV1.AUTHENTICATION_FAILED
import me.manga.kira.backend.security.TestTerminalCodecFailureV1.DEADLINE_EXHAUSTED
import me.manga.kira.backend.security.TestTerminalCodecFailureV1.INVALID_INPUT
import me.manga.kira.backend.security.TestTerminalCodecFailureV1.KEY_CLEANUP_FAILURE
import me.manga.kira.backend.security.TestTerminalCodecFailureV1.KEY_FAILURE
import me.manga.kira.backend.security.TestTerminalCodecFailureV1.LIMIT_EXCEEDED
import me.manga.kira.backend.security.TestTerminalCryptoReferenceV1.PRIVATE_TEXT
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.opaque
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.uuid
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** Local TEST crypto only. Decoding never establishes S3 custody, producer completion or purge authority. */
class TestTerminalCodecV1Test {
    @Test
    fun actualRetainedConsumerCanonicalizesAndRestoresAllThreeFixedFamiliesWithoutRekey() {
        val f = TestTerminalTestFixture()
        val original = TestOwnerDeleteJournalRoutingV1.fromAcquired(f.journal, f.acquired())
        val codec = TestTerminalCodecV1.fromRetained(original) { 0L }
        for (kind in TestTerminalCodecKindV1.entries) {
            val attempt = codec.startAttempt(kind)
            repeat(4) { index ->
                val route = f.route(kind.name, index)
                val selected = route.terminalText("routing_key_id")
                val requested = selected.takeIf { index != 0 }
                val idField = if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) "sealId" else "eventId"
                val expected = terminalCanonical(JsonObject(f.value(kind.documentName()) +
                    (idField to JsonPrimitive(route.terminalText("journal_id")))))
                val content = when (kind) {
                    TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> codec.canonicalizeInstallationManifest(f.manifest, attempt, requested)
                    TestTerminalCodecKindV1.TEST_RUN_PURGE -> codec.canonicalizePurge(f.purge, attempt, requested)
                    TestTerminalCodecKindV1.EPOCH_SEAL -> codec.canonicalizeEpochSeal(f.ordinarySeal, attempt, requested)
                }
                content.use {
                    assertEquals(route.terminalText("journal_id"), content.route.journalId)
                    assertEquals(route.terminalText("object_key"), content.route.objectKey)
                    assertEquals(selected, content.route.routingKeyId)
                    assertArrayEquals(expected, content.canonicalBytes())
                    assertEquals(terminalHash(expected), content.canonicalSha256)
                    codec.restoreCanonical(kind, expected, selected, content.route.objectKey, content.canonicalSha256, attempt).use { restored ->
                        assertEquals(content.route, restored.route)
                        assertArrayEquals(expected, restored.canonicalBytes())
                    }
                    val wrongKey = f.route(kind.name, (index + 1) % 4).terminalText("routing_key_id")
                    terminalCodecRejected(INVALID_INPUT) {
                        codec.restoreCanonical(kind, expected, wrongKey, content.route.objectKey, content.canonicalSha256, attempt)
                    }
                }
            }
        }
        terminalCodecRejected(INVALID_INPUT) {
            codec.canonicalizeInstallationManifest(f.manifest, codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL))
        }
    }

    @Test
    fun threeFamiliesMatchIndependentAeadContextAndWireGoldens() {
        for (kind in TestTerminalCodecKindV1.entries) {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val keys = TestTerminalCodecKeysV1(attempt)
            val content = f.content(kind, attempt)
            val golden = TestTerminalCryptoReferenceV1.golden(kind)
            val canonical = golden.terminalText("canonical_utf8").toByteArray(Charsets.UTF_8)
            val header = TestTerminalCryptoReferenceV1.header(kind)
            val wire = TestTerminalCryptoReferenceV1.wire(kind)
            val parts = TestTerminalCryptoReferenceV1.parts(wire)
            assertArrayEquals(f.source.bytes(kind.documentName()), canonical)
            assertArrayEquals(canonical, content.canonicalBytes())
            assertEquals(golden.terminalText("canonical_sha256"), content.canonicalSha256)
            assertEquals(canonical.size, content.byteCount)
            assertEquals(TestTerminalCryptoReferenceV1.order(kind).toSet(), header.keys)
            assertEquals(if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) 19 else 18, header.size)
            assertFalse(header.containsKey("ordinaryPrefix") || header.containsKey("precedingSealSha256"))
            assertArrayEquals(TestTerminalCryptoReferenceV1.hex(golden.terminalText("aad_frame_hex")),
                TestTerminalCryptoReferenceV1.aad(kind, header, parts.wrapped, parts.encrypted.size))
            assertArrayEquals(TestTerminalCryptoReferenceV1.hex(golden.terminalText("context_frame_hex")),
                terminalFrame(listOf("kira-complaint-journal-kms-context-v1", "1") + TestTerminalCryptoReferenceV1.values(kind, header)))
            assertArrayEquals(wire, TestTerminalCryptoReferenceV1.encrypt(kind, header, canonical))
            val envelope = f.codec.seal(content, attempt, keys)
            assertArrayEquals(wire, envelope.wireBytes())
            assertEquals(golden.terminalText("wire_sha256"), envelope.wireSha256)
            assertEquals(wire.size, envelope.byteCount)
            assertEquals(mapOf(TestTerminalCryptoReferenceV1.CONTEXT_KEY to golden.terminalText("context_base64url")), keys.requests.single().encryptionContext())
            assertEquals(6144, keys.requests.single().maximumWrappedKeyBytes)
            val decoded = f.codec.open(f.bucket, content.route.objectKey, content, wire, attempt, keys)
            assertSame(content, decoded.content)
            assertSame(content, envelope.content)
            assertEquals(envelope.wireSha256, decoded.wireSha256)
            assertArrayEquals(canonical, decoded.content.canonicalBytes())
            content.canonicalBytes().fill(0)
            envelope.wireBytes().fill(0)
            assertArrayEquals(canonical, content.canonicalBytes())
            assertArrayEquals(wire, envelope.wireBytes())
            val next = f.codec.seal(content, attempt, keys)
            assertNotEquals(envelope.wireSha256, next.wireSha256)
            assertEquals(2, keys.generations)
            next.close()
            envelope.close()
            envelope.close()
            terminalCodecRejected { envelope.wireBytes() }
            assertArrayEquals(canonical, content.canonicalBytes(), "wire owner does not close borrowed content")
            assertEquals(3, keys.leaseCloses)
            keys.assertCleared()
            f.nonces.destinations.forEach(::terminalCodecZero)
            val rendered = listOf(f.codec, attempt, content, envelope, decoded, content.route).joinToString()
            for (privateValue in listOf(f.bucket, TestTerminalTestFixture.SCOPE, content.route.journalId, content.route.objectKey)) {
                assertFalse(rendered.contains(privateValue))
            }
            content.close()
            content.close()
            terminalCodecRejected { content.canonicalBytes() }
        }
    }

    @Test
    fun frozenCanonicalAndRetainedRoutingReopenWithoutRepairOrRekey() {
        for (kind in TestTerminalCodecKindV1.entries) {
            val first = TestTerminalCodecTestFixtureV1()
            val attempt = first.attempt(kind)
            val retained = first.source.route(kind.name, 1)
            val selected = retained.terminalText("routing_key_id")
            assertNotEquals(first.journal.declaration().routing.activeKeyId, selected)
            val content = first.content(kind, attempt, selected)
            val idField = if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) "sealId" else "eventId"
            val expected = terminalCanonical(JsonObject(first.source.value(kind.documentName()) +
                (idField to JsonPrimitive(retained.terminalText("journal_id")))))
            assertArrayEquals(expected, content.canonicalBytes())
            assertEquals(retained.terminalText("object_key"), content.route.objectKey)
            val originalKeys = TestTerminalCodecKeysV1(attempt)
            val envelope = first.codec.seal(content, attempt, originalKeys)
            val savedWire = envelope.wireBytes()
            assertArrayEquals(TestTerminalCryptoReferenceV1.encrypt(kind, TestTerminalCryptoReferenceV1.header(kind, retained), expected), savedWire)
            val savedCanonical = content.canonicalBytes()
            val objectKey = content.route.objectKey
            val canonicalHash = content.canonicalSha256
            envelope.close()
            content.close()

            val resumed = TestTerminalCodecTestFixtureV1(TestTerminalTestFixture(first.journal))
            val resumedAttempt = resumed.attempt(kind)
            val resumedKeys = TestTerminalCodecKeysV1(resumedAttempt)
            val restored = resumed.codec.restoreCanonical(kind, savedCanonical, selected, objectKey, canonicalHash, resumedAttempt)
            savedCanonical.fill(0)
            assertArrayEquals(expected, restored.canonicalBytes())
            assertEquals(selected, restored.route.routingKeyId)
            assertEquals(0, resumedKeys.generations + resumedKeys.unwraps)
            val decoded = resumed.codec.open(resumed.bucket, objectKey, restored, savedWire, resumedAttempt, resumedKeys)
            assertSame(restored, decoded.content)
            assertEquals(terminalHash(savedWire), decoded.wireSha256)
            assertEquals(0, resumedKeys.generations)
            assertEquals(1, resumedKeys.unwraps)
            terminalCodecRejected { resumed.codec.restoreCanonical(kind, expected, "test-route-01", objectKey, canonicalHash, resumedAttempt) }
            terminalCodecRejected { resumed.codec.restoreCanonical(kind, expected, selected, objectKey + "x", canonicalHash, resumedAttempt) }
            terminalCodecRejected { resumed.codec.restoreCanonical(kind, expected, selected, objectKey, "f".repeat(64), resumedAttempt) }
            terminalCodecRejected { resumed.codec.restoreCanonical(kind, expected + 0.toByte(), selected, objectKey, canonicalHash, resumedAttempt) }
            assertEquals(1, resumedKeys.unwraps, "restoration failures are local")
            assertEquals(1, originalKeys.generations, "persisted bytes were encrypted once, never regenerated for reopen")
            originalKeys.assertCleared()
            resumedKeys.assertCleared()
            restored.close()
        }
    }

    @Test
    fun substitutedHeadersAndUnauthenticatedPlaintextNeverBecomeFrozenContent() {
        for (kind in TestTerminalCodecKindV1.entries) {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val content = f.content(kind, attempt)
            val keys = TestTerminalCodecKeysV1(attempt)
            val wire = TestTerminalCryptoReferenceV1.wire(kind)
            val original = TestTerminalCryptoReferenceV1.parts(wire)
            val header = TestTerminalCryptoReferenceV1.header(kind)
            fun open(bytes: ByteArray) = f.codec.open(f.bucket, content.route.objectKey, content, bytes, attempt, keys)
            for (name in header.keys - "nonce") {
                val changed = when (name) {
                    "envelopeSchemaVersion", "payloadSchemaVersion" -> JsonPrimitive(2)
                    "publicationEpoch", "epochEndInclusive" -> JsonPrimitive(4)
                    "epochStartInclusive" -> JsonPrimitive(2)
                    "eventId", "sealId" -> JsonPrimitive(opaque(99))
                    "dataScopeKind" -> JsonPrimitive("LIVE")
                    "dataScopeId", "writerGeneration" -> JsonPrimitive(uuid(99))
                    "routingKeyId" -> JsonPrimitive("test-route-02")
                    "objectKind" -> JsonPrimitive(if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) "OWNER_DELETE" else "EPOCH_SEAL")
                    else -> JsonPrimitive(header.terminalText(name) + "-different")
                }
                val before = keys.unwraps
                terminalCodecRejected { open(TestTerminalCryptoReferenceV1.pack(terminalCanonical(JsonObject(header + (name to changed))), original.wrapped, original.encrypted)) }
                assertEquals(before, keys.unwraps, "$kind $name must reject before KMS")
            }
            terminalCodecRejected { f.codec.open("different-bucket", content.route.objectKey, content, wire, attempt, keys) }
            terminalCodecRejected { f.codec.open(f.bucket, content.route.objectKey + "x", content, wire, attempt, keys) }
            assertEquals(0, keys.unwraps)
            val badNonce = terminalCanonical(JsonObject(header + ("nonce" to JsonPrimitive(TestTerminalCryptoReferenceV1.url(TestTerminalCryptoReferenceV1.nonce(1))))))
            val corrupted = listOf(
                TestTerminalCryptoReferenceV1.pack(badNonce, original.wrapped, original.encrypted),
                TestTerminalCryptoReferenceV1.pack(original.header, original.wrapped.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, original.encrypted),
                TestTerminalCryptoReferenceV1.pack(original.header, original.wrapped + 0.toByte(), original.encrypted),
            ) + listOf(0, original.encrypted.size - 16, original.encrypted.lastIndex).map { index ->
                TestTerminalCryptoReferenceV1.pack(original.header, original.wrapped,
                    original.encrypted.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() })
            }
            for (bytes in corrupted) {
                val before = keys.unwraps
                terminalCodecRejected(AUTHENTICATION_FAILED) { open(bytes) }
                assertEquals(before + 1, keys.unwraps, "permissive key port cannot authenticate $kind substitutions")
            }
            // Same outer length as the genuine payload: only authentication may run before its invalid JSON parser.
            val invalidPlaintext = ByteArray(content.byteCount) { 'x'.code.toByte() }
            val signedInvalid = TestTerminalCryptoReferenceV1.encrypt(kind, header, invalidPlaintext)
            val invalidTag = signedInvalid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            terminalCodecRejected(AUTHENTICATION_FAILED) { open(invalidTag) }
            assertNotEquals(AUTHENTICATION_FAILED, terminalCodecRejected { open(signedInvalid) }.code)
            val id = if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) "sealId" else "eventId"
            val substituted = terminalCanonical(JsonObject(f.source.value(kind.documentName()) + (id to JsonPrimitive(opaque(99)))))
            val before = keys.unwraps
            assertNotEquals(AUTHENTICATION_FAILED,
                terminalCodecRejected { open(TestTerminalCryptoReferenceV1.encrypt(kind, header, substituted)) }.code)
            assertEquals(before + 1, keys.unwraps, "valid tag still cannot replace frozen content")
            assertArrayEquals(f.source.bytes(kind.documentName()), content.canonicalBytes())
            assertEquals(keys.unwraps, keys.leaseCloses)
            keys.assertCleared()
            content.close()
        }
    }

    @Test
    fun unsignedWireLengthsAndActualJournalLimitsRejectBeforeKeyAcquisition() {
        for (kind in TestTerminalCodecKindV1.entries) {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val content = f.content(kind, attempt)
            val keys = TestTerminalCodecKeysV1(attempt)
            val wire = TestTerminalCryptoReferenceV1.wire(kind)
            val parts = TestTerminalCryptoReferenceV1.parts(wire)
            val offsets = listOf(8, 12 + parts.header.size, 16 + parts.header.size + parts.wrapped.size)
            val malformed = listOf(
                ByteArray(0), ByteArray(19), ByteArray(98_305), wire + 0.toByte(), wire.copyOf(wire.size - 1),
                wire.copyOf().also { ByteBuffer.wrap(it).putInt(0, 0x4b4a4557) },
                wire.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) },
                TestTerminalCryptoReferenceV1.pack(ByteArray(4097), parts.wrapped, parts.encrypted),
                TestTerminalCryptoReferenceV1.pack(parts.header, ByteArray(6145), parts.encrypted),
                TestTerminalCryptoReferenceV1.pack(parts.header, parts.wrapped, ByteArray(65_536 + 17)),
            ) + offsets.flatMap { offset -> listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE).map { length ->
                wire.copyOf().also { ByteBuffer.wrap(it).putInt(offset, length) }
            } }
            malformed.forEach { bytes -> terminalCodecRejected { f.codec.open(f.bucket, content.route.objectKey, content, bytes, attempt, keys) } }
            assertEquals(0, keys.generations + keys.unwraps)
            val d = f.journal.declaration().limits.decoder
            val smallPlain = content.byteCount - 1
            val boundedPlain = f.limited(d.copy(maximumPlaintextBytes = smallPlain,
                maximumJsonTokens = minOf(d.maximumJsonTokens, smallPlain), maximumStringUtf8Bytes = minOf(d.maximumStringUtf8Bytes, smallPlain)))
            terminalCodecRejected(LIMIT_EXCEEDED) { boundedPlain.content(kind, boundedPlain.attempt(kind)) }
            val envelopePlain = wire.size - 2
            for (limits in listOf(
                d.copy(maximumWrappedKeyBytes = 63),
                d.copy(maximumEnvelopeBytes = wire.size - 1, maximumPlaintextBytes = envelopePlain,
                    maximumJsonTokens = minOf(d.maximumJsonTokens, envelopePlain), maximumStringUtf8Bytes = minOf(d.maximumStringUtf8Bytes, envelopePlain),
                    maximumWrappedKeyBytes = 64),
            )) {
                val bounded = f.limited(limits)
                val boundedAttempt = bounded.attempt(kind)
                val boundedContent = bounded.content(kind, boundedAttempt)
                val boundedKeys = TestTerminalCodecKeysV1(boundedAttempt)
                terminalCodecRejected(LIMIT_EXCEEDED) { bounded.codec.open(bounded.bucket, boundedContent.route.objectKey, boundedContent, wire, boundedAttempt, boundedKeys) }
                assertEquals(0, boundedKeys.generations + boundedKeys.unwraps)
                boundedContent.close()
            }
            content.close()
        }
        val f = TestTerminalCodecTestFixtureV1()
        val d = f.journal.declaration().limits.decoder
        assertEquals(4096, d.maximumJsonTokens)
        val maximum = f.source.json.installationManifest(f.source.bytes("maximumInstallationManifest"))
        val attempt = f.attempt(TestTerminalCodecKindV1.INSTALLATION_MANIFEST)
        val content = f.codec.canonicalizeInstallationManifest(maximum, attempt)
        assertEquals(500, maximum.entries().size)
        assertEquals(41_870, content.byteCount)
        val keys = TestTerminalCodecKeysV1(attempt)
        val envelope = f.codec.seal(content, attempt, keys)
        assertSame(content, f.codec.open(f.bucket, content.route.objectKey, content, envelope.wireBytes(), attempt, keys).content)
        keys.assertCleared()
        envelope.close()
        content.close()
        val tooFewTokens = f.limited(d.copy(maximumJsonTokens = 3040))
        terminalCodecRejected(LIMIT_EXCEEDED) {
            tooFewTokens.codec.canonicalizeInstallationManifest(maximum, tooFewTokens.attempt(TestTerminalCodecKindV1.INSTALLATION_MANIFEST))
        }
        val headerLimit = 978 // TEST seal payload is 436 bytes, but its complete fixed header is 979 bytes.
        val boundedHeader = f.limited(d.copy(maximumPlaintextBytes = headerLimit, maximumJsonTokens = headerLimit, maximumStringUtf8Bytes = headerLimit))
        val sealAttempt = boundedHeader.attempt(TestTerminalCodecKindV1.EPOCH_SEAL)
        val seal = boundedHeader.content(TestTerminalCodecKindV1.EPOCH_SEAL, sealAttempt)
        val noKeys = TestTerminalCodecKeysV1(sealAttempt)
        terminalCodecRejected(LIMIT_EXCEEDED) { boundedHeader.codec.seal(seal, sealAttempt, noKeys) }
        assertEquals(0, noKeys.generations)
        seal.close()
    }

    @Test
    fun originalKindAndEnclosingBudgetsCannotBeRenewedOrBorrowedByAnotherOwner() {
        for (kind in TestTerminalCodecKindV1.entries) {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val content = f.content(kind, attempt)
            val keys = TestTerminalCodecKeysV1(attempt)
            val sameOwnerOtherAttempt = f.attempt(kind)
            val mismatchedPort = TestTerminalCodecKeysV1(sameOwnerOtherAttempt)
            terminalCodecRejected(INVALID_INPUT) { f.codec.seal(content, attempt, mismatchedPort) }
            assertEquals(0, mismatchedPort.generations)
            val foreign = TestTerminalCodecTestFixtureV1(TestTerminalTestFixture(f.journal))
            val foreignAttempt = foreign.attempt(kind)
            val foreignKeys = TestTerminalCodecKeysV1(foreignAttempt)
            terminalCodecRejected(INVALID_INPUT) { foreign.codec.seal(content, foreignAttempt, foreignKeys) }
            terminalCodecRejected(INVALID_INPUT) { f.codec.seal(content, foreignAttempt, foreignKeys) }
            assertEquals(0, foreignKeys.generations)
            val otherKind = if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) TestTerminalCodecKindV1.TEST_RUN_PURGE else TestTerminalCodecKindV1.EPOCH_SEAL
            terminalCodecRejected(INVALID_INPUT) { f.content(kind, f.attempt(otherKind)) }
            val deadlines = f.journal.declaration().limits.deadlines
            val total = if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) deadlines.epochSealMillis else deadlines.publicationAttemptMillis
            f.nanos = (total - 500L) * 1_000_000
            val envelope = f.codec.seal(content, attempt, keys)
            assertEquals(500, keys.requests.last().timeoutMillis)
            f.nanos = (total - 200L) * 1_000_000
            f.codec.open(f.bucket, content.route.objectKey, content, envelope.wireBytes(), attempt, keys)
            assertEquals(200, keys.requests.last().timeoutMillis)
            f.nanos = total * 1_000_000L
            terminalCodecRejected(DEADLINE_EXHAUSTED) { f.codec.seal(content, attempt, keys) }
            f.nanos = 0
            terminalCodecRejected(DEADLINE_EXHAUSTED) { attempt.remainingMillis(1) }
            assertEquals(1, keys.generations)
            assertEquals(1, keys.unwraps)
            keys.assertCleared()
            envelope.close()
            content.close()
        }
        for (unwrap in listOf(false, true)) {
            val f = TestTerminalCodecTestFixtureV1()
            val kind = TestTerminalCodecKindV1.EPOCH_SEAL
            val parent = PersistenceTimeBudget.start(100, PersistenceNanoClock { f.nanos })
            val attempt = f.attempt(kind, parent)
            val content = f.content(kind, attempt)
            val keys = TestTerminalCodecKeysV1(attempt)
            f.nanos = 99_000_000
            if (unwrap) keys.onUnwrap = { f.nanos = 100_000_000 } else keys.onGenerate = { f.nanos = 100_000_000 }
            terminalCodecRejected(DEADLINE_EXHAUSTED) {
                if (unwrap) f.codec.open(f.bucket, content.route.objectKey, content, TestTerminalCryptoReferenceV1.wire(kind), attempt, keys)
                else f.codec.seal(content, attempt, keys)
            }
            assertEquals(1, keys.requests.single().timeoutMillis)
            assertEquals(1, keys.leaseCloses)
            keys.assertCleared()
            terminalCodecRejected(DEADLINE_EXHAUSTED) { f.attempt(kind, parent) }
            content.close()
        }
        val f = TestTerminalCodecTestFixtureV1()
        val kind = TestTerminalCodecKindV1.EPOCH_SEAL
        val backward = f.attempt(kind)
        f.nanos = 1_000_000
        backward.remainingMillis(1000)
        f.nanos = 0
        terminalCodecRejected(DEADLINE_EXHAUSTED) { backward.remainingMillis(1000) }
        f.nanos = 2_000_000
        terminalCodecRejected(DEADLINE_EXHAUSTED) { backward.remainingMillis(1000) }
        val attempt = f.attempt(kind)
        val content = f.content(kind, attempt)
        val keys = TestTerminalCodecKeysV1(attempt)
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows<PersistencePhaseException> { f.content(kind, attempt) }
            assertThrows<PersistencePhaseException> { f.codec.seal(content, attempt, keys) }
            assertThrows<PersistencePhaseException> { f.codec.open(f.bucket, content.route.objectKey, content, TestTerminalCryptoReferenceV1.wire(kind), attempt, keys) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
            content.close()
        }
        assertEquals(0, keys.generations + keys.unwraps)
    }

    @Test
    fun transferredLeaseCleanupPreservesSanitizedCancellationInterruptionAndFatalSignals() {
        val invalidKeys: List<(TestTerminalCodecKeysV1) -> Unit> = listOf(
            { it.keyWidth = 0 }, { it.keyWidth = 31 }, { it.keyWidth = 33 },
            { it.wrappedWidth = 0 }, { it.wrappedWidth = 6145 }, { it.wrongArn = true },
            { it.arnFailure = IllegalStateException(PRIVATE_TEXT) },
        )
        for (configure in invalidKeys) {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(TestTerminalCodecKindV1.INSTALLATION_MANIFEST)
            val content = f.content(attempt.kind, attempt)
            val keys = TestTerminalCodecKeysV1(attempt).also(configure)
            terminalCodecRejected(KEY_FAILURE) { f.codec.seal(content, attempt, keys) }
            assertEquals(1, keys.keyReads)
            assertEquals(1, keys.wrappedReads)
            assertEquals(1, keys.leaseCloses)
            keys.assertCleared()
            f.nonces.destinations.forEach(::terminalCodecZero)
            content.close()
        }
        // Signals use separate original attempts; cancellation is not assumed to authorize later work.
        fun isolated(configure: (TestTerminalCodecKeysV1) -> Unit, closes: Int = 1, expect: (() -> Unit) -> Unit) {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(TestTerminalCodecKindV1.INSTALLATION_MANIFEST)
            val content = f.content(attempt.kind, attempt)
            val keys = TestTerminalCodecKeysV1(attempt).also(configure)
            try {
                expect { f.codec.seal(content, attempt, keys) }
                assertEquals(closes, keys.leaseCloses)
                keys.assertCleared()
                f.nonces.destinations.forEach(::terminalCodecZero)
            } finally {
                Thread.interrupted()
                content.close()
            }
        }
        isolated({ it.generateFailure = IllegalStateException(PRIVATE_TEXT) }, closes = 0) { call -> terminalCodecRejected(KEY_FAILURE, call) }
        isolated({ it.closeFailure = IllegalStateException(PRIVATE_TEXT) }) { call -> terminalCodecRejected(KEY_CLEANUP_FAILURE, call) }
        isolated({ it.keyWidth = 31; it.closeFailure = CancellationException(PRIVATE_TEXT) }) { call -> sanitized(assertThrows<CancellationException> { call() }) }
        isolated({ it.arnFailure = CancellationException(PRIVATE_TEXT); it.closeFailure = IllegalStateException(PRIVATE_TEXT) }) { call ->
            sanitized(assertThrows<CancellationException> { call() })
        }
        isolated({ it.arnFailure = InterruptedException(PRIVATE_TEXT); it.closeFailure = IllegalStateException(PRIVATE_TEXT) }) { call ->
            sanitized(assertThrows<InterruptedException> { call() })
            assertTrue(Thread.currentThread().isInterrupted)
        }
        val fatal = AssertionError(PRIVATE_TEXT)
        isolated({ it.arnFailure = fatal; it.closeFailure = CancellationException(PRIVATE_TEXT) }) { call ->
            assertSame(fatal, assertThrows<AssertionError> { call() })
        }
        val f = TestTerminalCodecTestFixtureV1()
        val attempt = f.attempt(TestTerminalCodecKindV1.INSTALLATION_MANIFEST)
        val content = f.content(attempt.kind, attempt)
        val nonceFailureKeys = TestTerminalCodecKeysV1(attempt)
        f.nonces.afterWrite = { throw IllegalStateException(PRIVATE_TEXT) }
        terminalCodecRejected { f.codec.seal(content, attempt, nonceFailureKeys) }
        assertEquals(0, nonceFailureKeys.generations)
        f.nonces.destinations.forEach(::terminalCodecZero)
        content.close()
    }

    private fun sanitized(failure: Throwable) {
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains(PRIVATE_TEXT))
    }
}
