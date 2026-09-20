package me.manga.kira.backend.complaint.domain.terminal

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

/** Pure storage declarations and byte owners only; no database winner, canonical/KJEV authentication or reserve authority. */
class TestTerminalDurableStorageV1Test {
    @Test
    fun canonicalAndFrozenRowsOwnIndependentCopiesAndWipeOnlyTheirOwnedArrays() {
        val input = byteArrayOf(1, 2, 3, 4)
        val expected = input.copyOf()
        val wireInput = byteArrayOf(5, 6, 7, 8)
        val expectedWire = wireInput.copyOf()
        val declaration = binding()
        TestTerminalDurableRowV1.canonical(declaration, input).use { canonical ->
            val retainedCanonical = owned(canonical, "storedCanonical")
            assertNotSame(input, retainedCanonical)
            input.fill(55)
            val escaped = canonical.canonicalBytes()
            assertNotSame(retainedCanonical, escaped)
            escaped.fill(77)
            assertArrayEquals(expected, canonical.canonicalBytes())
            assertEquals(hash(expected), canonical.canonicalSha256)
            assertEquals(TestTerminalDurableStateV1.CANONICAL, canonical.state)
            assertNull(canonical.wireBytes())
            assertNull(canonical.wireSha256)
            assertNull(canonical.checksumSha256)
            assertNull(canonical.contentType)
            assertNull(canonical.objectLockMode)
            assertNull(canonical.retainUntil)
            assertNull(canonical.metadataBytes())
            assertNull(canonical.metadata())
            assertNull(canonical.metadataSha256)
            assertNull(canonical.frozenAt)

            TestTerminalDurableRowV1.frozen(canonical, wireInput, FLOOR, CREATED.plusSeconds(1)).use { frozen ->
                val frozenCanonical = owned(frozen, "storedCanonical")
                val frozenWire = owned(frozen, "storedWire")
                val frozenMetadata = owned(frozen, "storedMetadata")
                assertNotSame(retainedCanonical, frozenCanonical)
                assertNotSame(wireInput, frozenWire)
                assertEquals(declaration, frozen.binding)
                assertEquals(TestTerminalDurableStateV1.CANONICAL, canonical.state, "A local candidate cannot mutate its source owner")
                wireInput.fill(66)
                frozen.canonicalBytes().fill(88)
                checkNotNull(frozen.wireBytes()).fill(88)
                checkNotNull(frozen.metadataBytes()).fill(88)
                assertArrayEquals(expected, frozen.canonicalBytes())
                assertArrayEquals(expectedWire, frozen.wireBytes())

                val expectedMetadata = checkNotNull(frozen.metadata())
                val escapedMetadata = checkNotNull(frozen.metadata())
                assertNotSame(expectedMetadata, escapedMetadata)
                try {
                    (escapedMetadata as? MutableMap<String, String>)?.set("kira-journal-schema", "changed")
                } catch (_: UnsupportedOperationException) {
                    // A fresh immutable map is also a safe defensive getter.
                }
                assertEquals(expectedMetadata, frozen.metadata())
                assertArrayEquals(metadata(declaration.objectId, hash(expectedWire), FLOOR), frozen.metadataBytes())
                assertTrue(frozenMetadata.any { it != 0.toByte() })

                canonical.close()
                canonical.close()
                assertTrue(retainedCanonical.all { it == 0.toByte() })
                assertArrayEquals(expected, frozen.canonicalBytes(), "The frozen owner survives closure of its independent canonical owner")
                closed(canonical)
                rejected { TestTerminalDurableRowV1.frozen(canonical, expectedWire, FLOOR, CREATED) }

                val diagnostics = frozen.toString() + declaration.toString()
                for (privateValue in listOf(declaration.objectId, declaration.objectKey, hash(expected), hash(expectedWire))) {
                    assertFalse(diagnostics.contains(privateValue))
                }
                frozen.close()
                frozen.close()
                listOf(frozenCanonical, frozenWire, frozenMetadata).forEach { bytes -> assertTrue(bytes.all { it == 0.toByte() }) }
                closed(frozen)
                assertTrue(input.all { it == 55.toByte() })
                assertTrue(wireInput.all { it == 66.toByte() })
                assertTrue(escaped.all { it == 77.toByte() }, "Closing owners must not wipe caller-owned defensive copies")
            }
        }
    }

    @Test
    fun bindingRejectsInvalidIdentitySlotsKeysEpochsAndUtcRetentionDeclarations() {
        assertEquals(listOf("installation-manifest", "test-run-purge", "epoch-seal"), TestTerminalDurableKindV1.entries.map { it.path })
        for ((kind, lastSlot) in listOf(
            TestTerminalDurableKindV1.INSTALLATION_MANIFEST to 4095,
            TestTerminalDurableKindV1.TEST_RUN_PURGE to 0,
            TestTerminalDurableKindV1.EPOCH_SEAL to 15,
        )) {
            for (slot in setOf(0, lastSlot)) {
                val value = binding(kind, slot, routing = "R".repeat(64))
                assertEquals(if (kind == TestTerminalDurableKindV1.EPOCH_SEAL) null else value.objectId, value.publicationRef)
                assertEquals(slot, value.objectOrdinal)
            }
            rejected { binding(kind, -1) }
            rejected { binding(kind, lastSlot + 1) }
        }
        val valid = binding()
        val invalid = listOf<Pair<String, () -> TestTerminalDurableBindingV1>>(
            "operation v4" to { valid.copy(operationToken = "00000000-0000-1000-8000-000000000001") },
            "writer v4" to { valid.copy(writerGeneration = "00000000-0000-0000-0000-000000000000") },
            "journal hash" to { valid.copy(journalConfigurationSha256 = "a".repeat(63)) },
            "opaque length" to { valid.copy(objectId = "A".repeat(42)) },
            "opaque padding" to { valid.copy(objectId = valid.objectId + "=") },
            "opaque padding bits" to { valid.copy(objectId = "A".repeat(42) + "B") },
            "fence positive" to { valid.copy(preparingFencingToken = 0) },
            "epoch positive" to { valid.copy(epochStartInclusive = 0) },
            "epoch order" to { valid.copy(epochStartInclusive = valid.epochEndInclusive + 1) },
            "route agreement" to { valid.copy(routingKeyId = "another") },
            "route grammar" to { binding(routing = "bad/route") },
            "route bound" to { binding(routing = "R".repeat(65)) },
            "scope agreement" to { valid.copy(objectKey = valid.objectKey.replace(SCOPE, OTHER_SCOPE)) },
            "writer agreement" to { valid.copy(objectKey = valid.objectKey.replace(WRITER, OPERATION)) },
            "epoch spelling" to { valid.copy(objectKey = valid.objectKey.replace("/3/", "/03/")) },
            "kind path" to { valid.copy(objectKey = valid.objectKey.replace("/epoch-seal/", "/test-run-purge/")) },
            "ordinary path" to { valid.copy(objectKey = valid.objectKey.replace("/seal-terminal/", "/ordinary/")) },
            "ASCII path" to { valid.copy(objectKey = valid.objectKey.replace("route_1", "routé")) },
            "extension" to { valid.copy(objectKey = valid.objectKey.removeSuffix(".kjev")) },
            "file token" to { valid.copy(objectKey = valid.objectKey.substringBeforeLast('/') + "/" + "A".repeat(42) + ".kjev") },
            "manifest epoch" to { binding(TestTerminalDurableKindV1.INSTALLATION_MANIFEST, end = 2) },
            "purge epoch" to { binding(TestTerminalDurableKindV1.TEST_RUN_PURGE, end = 2) },
            "LIVE scope" to { valid.copy(run = valid.run.copy(dataScopeId = "00000000-0000-0000-0000-000000000000")) },
            "catalog low" to { valid.copy(run = valid.run.copy(activationCatalogGeneration = 0)) },
            "catalog high" to { valid.copy(run = valid.run.copy(activationCatalogGeneration = 65537)) },
            "configuration hash" to { valid.copy(run = valid.run.copy(configurationSha256 = "B".repeat(64))) },
            "encoding binding" to { valid.copy(run = valid.run.copy(terminalEncodingSha256 = "0".repeat(64))) },
            "ten calendar years" to { valid.copy(retentionFloor = FLOOR.minusSeconds(1)) },
        )
        invalid.forEach { (label, factory) -> rejected(label = label) { factory() } }
        for (bad in listOf(Instant.ofEpochSecond(-1), CREATED.plusNanos(1), Instant.ofEpochSecond(253_402_300_800L))) {
            rejected { valid.copy(createdAt = bad) }
            rejected { valid.copy(retentionFloor = bad) }
        }
        assertEquals(FLOOR, valid.retentionFloor, "2024 leap day plus ten UTC calendar years is 2034-02-28, not 3650 days")
        binding(created = Instant.EPOCH, floor = Instant.parse("1980-01-01T00:00:00Z"))
        binding(created = Instant.parse("9989-12-31T23:59:59Z"), floor = Instant.parse("9999-12-31T23:59:59Z"))
    }

    @Test
    fun frozenRowsEnforceByteTimeStateAndMetadataBoundsWithoutAuthenticatingOpaquePayloads() {
        val declaration = binding()
        for (size in listOf(0, 65_537)) {
            rejected(TestTerminalFailureV1.LIMIT_EXCEEDED) { TestTerminalDurableRowV1.canonical(declaration, ByteArray(size)) }
        }
        for (size in listOf(1, 65_536)) {
            val opaqueCanonical = ByteArray(size) { (it * 73 + 11).toByte() }
            TestTerminalDurableRowV1.canonical(declaration, opaqueCanonical).use { canonical ->
                assertEquals(size, canonical.canonicalBytes().size)
                assertEquals(hash(opaqueCanonical), canonical.canonicalSha256)
                for (wireSize in listOf(1, 98_304)) {
                    val opaqueWire = ByteArray(wireSize) { (it * 31 + 9).toByte() }
                    val retainedUntil = Instant.parse("9999-12-31T23:59:59Z")
                    TestTerminalDurableRowV1.frozen(canonical, opaqueWire, retainedUntil, CREATED).use { frozen ->
                        val wireHash = hash(opaqueWire)
                        val expectedMetadata = metadata(declaration.objectId, wireHash, retainedUntil)
                        assertEquals(1, frozen.schemaVersion)
                        assertEquals("kcj-1", frozen.canonicalizer)
                        assertEquals(TestTerminalDurableStateV1.WIRE_FROZEN, frozen.state)
                        assertEquals("application/octet-stream", frozen.contentType)
                        assertEquals("COMPLIANCE", frozen.objectLockMode)
                        assertEquals(CREATED, frozen.frozenAt)
                        assertEquals(retainedUntil, frozen.retainUntil)
                        assertArrayEquals(opaqueCanonical, frozen.canonicalBytes())
                        assertArrayEquals(opaqueWire, frozen.wireBytes())
                        assertEquals(hash(opaqueCanonical), frozen.canonicalSha256)
                        assertEquals(wireHash, frozen.wireSha256)
                        assertEquals(Base64.getEncoder().encodeToString(digest(opaqueWire)), frozen.checksumSha256)
                        assertArrayEquals(expectedMetadata, frozen.metadataBytes())
                        assertEquals(hash(expectedMetadata), frozen.metadataSha256)
                        assertTrue(expectedMetadata.size in 1..512 && expectedMetadata.all { it.toInt() in 0..127 })
                        assertEquals(mapOf(
                            "kira-journal-ciphertext-sha256" to wireHash,
                            "kira-journal-event-id" to declaration.objectId,
                            "kira-journal-retain-until" to retainedUntil.toString(),
                            "kira-journal-schema" to "1",
                        ), frozen.metadata())
                        rejected { TestTerminalDurableRowV1.frozen(frozen, opaqueWire, retainedUntil, CREATED) }
                    }
                }
                for (badSize in listOf(0, 98_305)) {
                    rejected(TestTerminalFailureV1.LIMIT_EXCEEDED) { TestTerminalDurableRowV1.frozen(canonical, ByteArray(badSize), FLOOR, CREATED) }
                }
                for ((until, at) in listOf(
                    FLOOR.minusSeconds(1) to CREATED,
                    FLOOR to CREATED.minusSeconds(1),
                    FLOOR to FLOOR,
                    FLOOR.plusNanos(1) to CREATED,
                    FLOOR to CREATED.plusNanos(1),
                    Instant.ofEpochSecond(253_402_300_800L) to CREATED,
                    FLOOR to Instant.ofEpochSecond(-1),
                )) {
                    rejected { TestTerminalDurableRowV1.frozen(canonical, byteArrayOf(1), until, at) }
                }
                // Independent local candidates are permitted; neither is a committed winner or authenticated envelope.
                TestTerminalDurableRowV1.frozen(canonical, byteArrayOf(21), FLOOR, CREATED).use { first ->
                    TestTerminalDurableRowV1.frozen(canonical, byteArrayOf(22), FLOOR, CREATED).use { second ->
                        assertFalse(first.wireSha256 == second.wireSha256)
                        assertEquals(first.canonicalSha256, second.canonicalSha256)
                        assertEquals(TestTerminalDurableStateV1.CANONICAL, canonical.state)
                    }
                }
                assertArrayEquals(opaqueCanonical, canonical.canonicalBytes(), "Closing either independent frozen candidate must not close its canonical owner")
            }
        }
    }

    @Test
    fun storageOnlyHighWaterPinsCountsBoundsAndLeavesOtherCapacityCountersZero() {
        val p = TestTerminalDurableStorageProfileV1
        assertEquals(22, ComplaintCapacityEncoding.WIDTH)
        assertEquals(22, ComplaintCapacityCounter.entries.size)
        assertEquals(21, ComplaintCapacityCounter.STORAGE_BYTES.storedOrdinal)
        assertEquals(65_536, p.MAX_CANONICAL_BYTES)
        assertEquals(98_304, p.MAX_WIRE_BYTES)
        assertEquals(512, p.MAX_METADATA_BYTES)
        assertEquals(1024, p.MAX_OBJECT_KEY_BYTES)
        assertEquals(64, p.MAX_ROUTING_KEY_ID_BYTES)
        assertEquals(166_208L, p.MAX_HEAP_ROW_BYTES)
        assertEquals(listOf(48L, 96L, 1064L, 80L, 96L), listOf(p.OPERATION_INDEX_BYTES, p.SCOPE_KIND_ORDINAL_INDEX_BYTES, p.OBJECT_KEY_INDEX_BYTES, p.OBJECT_ID_INDEX_BYTES, p.PUBLICATION_INDEX_BYTES))
        assertEquals(1384L, p.MAX_INDEX_BYTES)
        assertEquals(8L, p.SAFETY_MULTIPLIER)
        assertEquals(1_340_736L, p.LIFECYCLE_MAX_STORAGE_BYTES)
        val counts = listOf(0L to 17L, 1L to 18L, 499L to 18L, 500L to 18L, 501L to 19L, 2_048_000L to 4113L)
        for ((installations, expectedCount) in counts) {
            assertEquals(expectedCount, p.maximumIntentCount(installations))
            val highWater = p.sidecarStorageHighWater(installations)
            assertEquals(22, highWater.toLongArray().size)
            for (counter in ComplaintCapacityCounter.entries) {
                assertEquals(if (counter == ComplaintCapacityCounter.STORAGE_BYTES) 1_340_736L else 0L, p.ROW[counter])
                assertEquals(if (counter == ComplaintCapacityCounter.STORAGE_BYTES) 1_340_736L * expectedCount else 0L, highWater[counter])
            }
            highWater.toLongArray().fill(0)
            assertEquals(1_340_736L * expectedCount, highWater[ComplaintCapacityCounter.STORAGE_BYTES])
        }
        assertEquals(22_792_512L, p.sidecarStorageHighWater(0)[ComplaintCapacityCounter.STORAGE_BYTES])
        assertEquals(5_514_447_168L, p.sidecarStorageHighWater(2_048_000)[ComplaintCapacityCounter.STORAGE_BYTES])
        for (bad in listOf(-1L, 2_048_001L, Long.MAX_VALUE)) {
            rejected(TestTerminalFailureV1.LIMIT_EXCEEDED) { p.maximumIntentCount(bad) }
            rejected(TestTerminalFailureV1.LIMIT_EXCEEDED) { p.sidecarStorageHighWater(bad) }
        }
        // A numeric sidecar declaration prices no publication/reservation, catalog, scan, notice, audit, or whole activation.
    }

    private fun binding(
        kind: TestTerminalDurableKindV1 = TestTerminalDurableKindV1.EPOCH_SEAL,
        slot: Int = 0,
        end: Long = if (kind == TestTerminalDurableKindV1.EPOCH_SEAL) 3 else 1,
        routing: String = "route_1",
        created: Instant = CREATED,
        floor: Instant = FLOOR,
    ): TestTerminalDurableBindingV1 = TestTerminalDurableBindingV1(
        operationToken = OPERATION,
        run = TestTerminalRunContextV1(SCOPE, 65536, "a".repeat(64), "b".repeat(64), TestTerminalProfileV1.encodingSha256),
        journalConfigurationSha256 = "c".repeat(64), objectKind = kind, objectOrdinal = slot, objectId = opaque(1),
        objectKey = "complaints/journal/v1/$WRITER/test/$SCOPE/seal-terminal/$end/$routing/${kind.path}/${opaque(2)}.kjev",
        routingKeyId = routing, writerGeneration = WRITER, epochStartInclusive = 1, epochEndInclusive = end,
        preparingFencingToken = 1, retentionFloor = floor, createdAt = created,
    )

    private fun closed(row: TestTerminalDurableRowV1) {
        rejected { row.canonicalBytes() }
        rejected { row.wireBytes() }
        rejected { row.metadataBytes() }
        rejected { row.metadata() }
    }

    // No test seam is added to MAIN: observe its retained arrays solely to verify actual zeroing on close.
    private fun owned(row: TestTerminalDurableRowV1, field: String): ByteArray =
        TestTerminalDurableRowV1::class.java.getDeclaredField(field).apply { isAccessible = true }.get(row) as ByteArray

    private fun rejected(code: TestTerminalFailureV1 = TestTerminalFailureV1.INVALID_INPUT, label: String = "bounded storage input", action: () -> Unit) {
        val failure = assertThrows<TestTerminalExceptionV1> { action() }
        assertEquals(code, failure.code, label)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun opaque(seed: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + seed).toByte() })
    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(digest(bytes))
    private fun metadata(id: String, wireHash: String, until: Instant): ByteArray =
        ("{\"kira-journal-ciphertext-sha256\":\"$wireHash\",\"kira-journal-event-id\":\"$id\"," +
            "\"kira-journal-retain-until\":\"$until\",\"kira-journal-schema\":\"1\"}").toByteArray(Charsets.US_ASCII)

    private companion object {
        const val SCOPE = "00000000-0000-4000-8000-000000000101"
        const val OTHER_SCOPE = "00000000-0000-4000-8000-000000000102"
        const val WRITER = "00000000-0000-4000-8000-000000000103"
        const val OPERATION = "00000000-0000-4000-8000-000000000104"
        val CREATED: Instant = Instant.parse("2024-02-29T12:00:00Z")
        val FLOOR: Instant = Instant.parse("2034-02-28T12:00:00Z")
    }
}
