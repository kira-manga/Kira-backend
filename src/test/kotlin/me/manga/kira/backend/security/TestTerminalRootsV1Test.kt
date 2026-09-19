package me.manga.kira.backend.security

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationEntryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.SCOPE
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.key
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.opaque
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.uuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Complete means the two supplied local passes only; no DB barrier, remote listing or exact-version readback authority. */
class TestTerminalRootsV1Test {
    private val f = TestTerminalTestFixture()

    @Test
    fun independentLp32RootsBindEmptyAndCompleteInstallationInventories() {
        val owner = TestTerminalRootsV1(f.journal, 500, 1)
        val empty = f.installationRoot(owner, emptyList())
        framedGolden("emptyInstallations", empty.sha256)
        assertEquals(0L, empty.installationCount)
        val emptyChunks = owner.chunks(empty, 3).finish()
        framedGolden("emptyChunks", emptyChunks.chunksSha256)
        assertEquals(0, emptyChunks.chunkCount)
        val full = f.installationRoot(owner)
        framedGolden("installations", full.sha256)
        assertEquals(terminalInstallationHash(f.run, f.entries), full.sha256)
        assertEquals(f.run, full.context)
        assertEquals(listOf(2L, 1L, 1L), listOf(full.installationCount, full.retiredCount, full.deletedCount))
        for (context in listOf(f.run.copy(activationCatalogGeneration = 5), f.run.copy(activationCatalogSha256 = "c".repeat(64)), f.run.copy(configurationSha256 = "c".repeat(64)))) {
            val fold = owner.installations(context)
            f.entries.forEach(fold::firstPass)
            fold.beginSecondPass()
            f.entries.forEach(fold::secondPass)
            val changed = fold.finish()
            assertEquals(terminalInstallationHash(context, f.entries), changed.sha256)
            assertNotEquals(full.sha256, changed.sha256)
        }
        terminalRejected { owner.installations(f.run.copy(dataScopeId = uuid(9))) }
        terminalRejected { TestTerminalRootsV1(f.journal, 500, 1).chunks(full, 3) } // Equal J is not the same fold owner.
        terminalRejected { owner.chunks(full, 0) }
        for (order in listOf(listOf(f.entries[1], f.entries[0]), listOf(f.entries[0], f.entries[0]))) {
            val fold = owner.installations(f.run)
            terminalRejected { order.forEach(fold::firstPass) }
            terminalRejected { fold.beginSecondPass() }
        }
        val wrongPhase = owner.installations(f.run)
        terminalRejected { wrongPhase.finish() }
        terminalRejected { wrongPhase.firstPass(f.entries.first()) }
        val earlySecond = owner.installations(f.run)
        terminalRejected { earlySecond.secondPass(f.entries.first()) }
        terminalRejected { earlySecond.beginSecondPass() }
        for (second in listOf(f.entries.take(1), f.entries.map { it.copy(disposition = TestTerminalDispositionV1.RETIRED) })) {
            val fold = owner.installations(f.run)
            f.entries.forEach(fold::firstPass)
            fold.beginSecondPass()
            second.forEach(fold::secondPass)
            terminalRejected { fold.finish() }
            terminalRejected { fold.finish() }
        }
        val completed = owner.installations(f.run)
        completed.beginSecondPass()
        completed.finish()
        terminalRejected { completed.beginSecondPass() }
        val limited = TestTerminalRootsV1(f.journal, 1, 1).installations(f.run)
        limited.firstPass(f.entries.first())
        assertEquals(TestTerminalFailureV1.LIMIT_EXCEEDED, terminalRejected { limited.firstPass(f.entries.last()) }.code)
        terminalRejected { limited.finish() }
        val frameBytes = f.root("installations").terminalText("framed_bytes").toLong()
        assertEquals(full.sha256, f.installationRoot(limitedOwner(bytes = frameBytes)).sha256)
        terminalRejected { f.installationRoot(limitedOwner(bytes = frameBytes - 1)) }
        terminalRejected { TestTerminalRootsV1(f.journal, 501, 1) }
        terminalRejected { TestTerminalRootsV1(f.journal, 1, 4097) }
    }

    @Test
    fun verifiedChunkAndPreterminalRootsRejectReorderingSubstitutionAndTerminalCycles() {
        val owner = TestTerminalRootsV1(f.journal, 500, 1)
        val installations = f.installationRoot(owner)
        val chunks = owner.chunks(installations, 3)
        chunks.add(f.manifest, f.manifestRef)
        val summary = chunks.finish()
        assertEquals(f.summary, summary)
        framedGolden("chunks", summary.chunksSha256)
        terminalRejected { chunks.add(f.manifest, f.manifestRef) }
        terminalRejected { chunks.finish() }
        assertEquals(f.countHash("preTerminalSeals"), owner.preTerminalSeals(f.seals()))
        assertEquals(f.rootHash("preTerminalSeals"), terminalHash(f.root("preTerminalSeals").terminalText("canonical_utf8").toByteArray()))
        val inventory = f.inventoryRoot(owner)
        framedGolden("preTerminalInventory", inventory.sha256)
        assertEquals(6L, inventory.count)
        assertEquals(4, f.inventory.take(4).map { it.objectRef.objectVersion }.distinct().size)
        assertEquals(1, f.inventory.take(4).map { it.objectRef.objectKey }.distinct().size)
        assertEquals(listOf("\ue000", "\uD800\uDC00"), f.inventory.slice(2..3).map { it.objectRef.objectVersion })
        val badChunks = listOf(
            f.manifest to f.manifestRef.copy(canonicalSha256 = "f".repeat(64)),
            f.manifest to f.manifestRef.copy(objectKey = key(3, "test-run-purge")),
            f.manifest(context = f.manifest.context(), root = "f".repeat(64)) to f.manifestRef,
            f.manifest(context = f.manifest.context().copy(publicationEpoch = 4)) to f.manifestRef,
            f.manifest(context = f.manifest.context().copy(run = f.run.copy(configurationSha256 = "c".repeat(64)))) to f.manifestRef,
            f.manifest(context = f.manifest.context(), index = 1, count = 2) to f.manifestRef,
        )
        for ((manifest, ref) in badChunks) {
            val fold = owner.chunks(installations, 3)
            terminalRejected { fold.add(manifest, ref) }
            terminalRejected { fold.finish() }
        }
        val swapped = f.manifest(context = f.manifest.context(), entries = f.entries.map { it.copy(
            disposition = if (it.disposition == TestTerminalDispositionV1.RETIRED) TestTerminalDispositionV1.DELETED else TestTerminalDispositionV1.RETIRED,
        ) })
        val changed = owner.chunks(installations, 3)
        changed.add(swapped, refFor(swapped, f.manifestRef.objectKey)) // Correct local canonical hash and counts still cannot replace the installation root.
        terminalRejected { changed.finish() }
        twoChunkBoundaries()
        val badInventories = listOf(
            listOf(f.inventory[1], f.inventory[0]) + f.inventory.drop(2),
            listOf(f.inventory[0], f.inventory[0]) + f.inventory.drop(1),
            listOf(f.inventory[0].copy(objectKind = "ADMIN_DELETE")) + f.inventory, // Sorted cross-kind reuse of the same (key,version).
            f.inventory.take(2) + listOf(f.inventory[3], f.inventory[2]) + f.inventory.drop(4), // UTF8/codepoint, not UTF16 order.
            f.inventory.filterIndexed { index, _ -> index != 4 }, // Extra seal version is counted but cannot replace the declared exact version.
            f.inventory.mapIndexed { index, entry -> if (index == 4) entry.copy(objectRef = entry.objectRef.copy(ciphertextSha256 = "f".repeat(64))) else entry },
            listOf(f.inventory.first().copy(writerGeneration = uuid(8))) + f.inventory.drop(1),
            listOf(f.inventory.first().copy(objectRef = f.inventory.first().objectRef.copy(objectKey = f.inventory.first().objectRef.objectKey.replace(SCOPE, uuid(8))))) + f.inventory.drop(1),
        )
        for (entries in badInventories) {
            val fold = owner.preTerminalInventory(f.run, f.seals())
            terminalRejected { entries.forEach(fold::firstPass); fold.beginSecondPass() }
            terminalRejected { fold.finish() }
        }
        val changedPass = owner.preTerminalInventory(f.run, f.seals())
        f.inventory.forEach(changedPass::firstPass)
        changedPass.beginSecondPass()
        f.inventory.mapIndexed { index, entry -> if (index == 0) entry.copy(objectRef = entry.objectRef.copy(canonicalSha256 = "f".repeat(64))) else entry }.forEach(changedPass::secondPass)
        terminalRejected { changedPass.finish() }
        terminalRejected { changedPass.beginSecondPass() }
        val missingSecond = owner.preTerminalInventory(f.run, f.seals())
        f.inventory.forEach(missingSecond::firstPass)
        missingSecond.beginSecondPass()
        f.inventory.dropLast(1).forEach(missingSecond::secondPass)
        terminalRejected { missingSecond.finish() }
        terminalRejected { owner.preTerminalInventory(f.run.copy(activationCatalogGeneration = 5), f.seals()) }
        terminalRejected { owner.preTerminalInventory(f.run.copy(activationCatalogSha256 = "e".repeat(64)), f.seals()) }
        assertEquals(inventory, f.inventoryRoot(limitedOwner(versions = 6)))
        terminalRejected { f.inventoryRoot(limitedOwner(versions = 5)) }
        val bytes = f.root("preTerminalInventory").terminalText("framed_bytes").toLong()
        assertEquals(inventory, f.inventoryRoot(limitedOwner(bytes = bytes)))
        terminalRejected { f.inventoryRoot(limitedOwner(bytes = bytes - 1)) }
        val noPurgeSlot = limitedOwner(versions = 1)
        terminalRejected { noPurgeSlot.chunks(f.installationRoot(noPurgeSlot), 3) }
    }

    @Test
    fun sealAndPurgeBoundariesExcludeOnlyFutureTerminalProof() {
        val owner = TestTerminalRootsV1(f.journal, 500, 1)
        val terminal = f.sealRef.copy(role = TestTerminalSealRoleV1.TERMINAL, epochStartInclusive = 3, epochEndInclusive = 3,
            precedingSealSha256 = f.sealRef.objectRef.canonicalSha256, objectRef = f.sealRef.objectRef.copy(objectKey = key(3, "epoch-seal")))
        val completeSet = f.seals(listOf(f.sealRef, terminal))
        assertEquals(2, f.json.sealSet(f.json.encodeSealSet(completeSet)).records().size)
        terminalRejected { owner.preTerminalSeals(completeSet) }
        terminalRejected { owner.preTerminalInventory(f.run, completeSet) }
        terminalRejected { owner.preTerminalInventory(f.run, f.seals()).firstPass(TestTerminalInventoryEntryV1(
            terminal.writerGeneration, "EPOCH_SEAL", 3, 3, terminal.objectRef,
        )) }
        terminalRejected { f.purge(context = f.context(epoch = 4), finalEpoch = 3, seal = terminal) }
        terminalRejected { f.seals(listOf(f.sealRef, terminal.copy(epochStartInclusive = 4, epochEndInclusive = 4, objectRef = terminal.objectRef.copy(objectKey = key(4, "epoch-seal"))))) }
        for (kind in listOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE", "DELETE_MARKER", "UNKNOWN")) {
            terminalRejected { TestTerminalInventoryEntryV1(f.sealRef.writerGeneration, kind, 3, 3, f.manifestRef) }
        }
        val futureEvent = f.inventory.first().copy(epochStartInclusive = 3, epochEndInclusive = 3,
            objectRef = f.inventory.first().objectRef.copy(objectKey = f.inventory.first().objectRef.objectKey.replace("0000000000000000001", "0000000000000000003")))
        terminalRejected { owner.preTerminalInventory(f.run, f.seals()).firstPass(futureEvent) }
        val secondWriter = f.sealRef.copy(writerGeneration = uuid(9), sealId = opaque(9),
            objectRef = f.sealRef.objectRef.copy(objectKey = key(2, "epoch-seal", writer = uuid(9)), canonicalSha256 = "f".repeat(64)))
        val lineage = f.seals(listOf(f.sealRef, secondWriter)) // Captured c3... then 10..., deliberately not UUID lexical order.
        val lineageInventory = lineage.records().map { TestTerminalInventoryEntryV1(it.writerGeneration, "EPOCH_SEAL", it.epochStartInclusive, it.epochEndInclusive, it.objectRef) }
        val root = f.inventoryRoot(owner, lineageInventory, lineage)
        assertEquals(2L, root.count)
        assertEquals(terminalHash(terminalFrame(listOf("kira-test-preterminal-inventory-v1", SCOPE, "4", f.run.activationCatalogSha256, "2")),
            *lineageInventory.map { terminalFrame(listOf(it.writerGeneration, it.objectKind, "1", "2", it.objectRef.objectKey, it.objectRef.objectVersion,
                it.objectRef.ciphertextSha256, it.objectRef.canonicalSha256)) }.toTypedArray()), root.sha256)
        terminalRejected { f.inventoryRoot(owner, lineageInventory.reversed(), lineage) }
        terminalRejected { f.seals(listOf(f.sealRef, secondWriter, f.sealRef)) }
        val empty = f.installationRoot(owner, emptyList())
        val zeroChunks = owner.chunks(empty, 3).finish()
        val zeroPurge = f.purge(summary = zeroChunks)
        assertEquals(0L, f.json.purge(f.json.encodePurge(zeroPurge)).installationManifest.installationCount)
        assertEquals(0, zeroPurge.installationManifest.chunkCount)
        terminalRejected { f.manifest(entries = emptyList()) }
        // Final-seal membership, K+1 authenticated event coverage, complete provider versions and drain authority need the deferred producer/reader.
        assertTrue(owner.toString().contains("no-completeness-or-provider-authority"))
    }

    private fun twoChunkBoundaries() {
        val owner = TestTerminalRootsV1(f.journal, 501, 2)
        val entries = (1..501).map { TestTerminalInstallationEntryV1(uuid(it), TestTerminalDispositionV1.RETIRED) }
        val root = f.installationRoot(owner, entries)
        assertEquals(terminalInstallationHash(f.run, entries), root.sha256)
        val first = f.manifest(entries = entries.take(500), count = 2, root = root.sha256)
        val last = f.manifest(context = f.context(id = opaque(1)), entries = entries.takeLast(1), index = 1, count = 2, root = root.sha256)
        val firstRef = refFor(first, key(3, "installation-manifest", opaque(10)))
        val lastRef = refFor(last, key(3, "installation-manifest", opaque(11)))
        val valid = owner.chunks(root, 3)
        valid.add(first, firstRef)
        valid.add(last, lastRef)
        assertEquals(501L, valid.finish().installationCount)
        terminalRejected { owner.chunks(root, 3).add(last, lastRef) }
        val duplicateId = f.manifest(context = first.context(), entries = entries.takeLast(1), index = 1, count = 2, root = root.sha256)
        val duplicateEntry = f.manifest(context = last.context(), entries = listOf(entries[499]), index = 1, count = 2, root = root.sha256)
        for ((manifest, ref) in listOf(last to lastRef.copy(objectKey = firstRef.objectKey),
            duplicateId to refFor(duplicateId, lastRef.objectKey), duplicateEntry to refFor(duplicateEntry, lastRef.objectKey))) {
            val fold = owner.chunks(root, 3)
            fold.add(first, firstRef)
            terminalRejected { fold.add(manifest, ref) }
            terminalRejected { fold.finish() }
        }
        val incomplete = owner.chunks(root, 3)
        incomplete.add(first, firstRef)
        terminalRejected { incomplete.finish() }
    }

    private fun refFor(manifest: TestTerminalInstallationManifestV1, objectKey: String): TestTerminalObjectRefV1 =
        f.manifestRef.copy(objectKey = objectKey, canonicalSha256 = terminalHash(f.json.encodeInstallationManifest(manifest)))

    private fun limitedOwner(versions: Long = 1000000, bytes: Long = 268435456): TestTerminalRootsV1 {
        val d = f.journal.declaration()
        val journal = TestOwnerDeleteJournalConfigurationV1.of(d.copy(limits = d.limits.copy(
            capacity = d.limits.capacity.copy(maximumRetainedVersions = versions, maximumScanStagingBytes = bytes),
        )))
        return TestTerminalRootsV1(journal, 500, 1)
    }

    private fun framedGolden(name: String, actual: String) {
        val literal = f.root(name)
        val frames = literal.getValue("frames").jsonArray.map { fields -> terminalFrame(fields.jsonArray.map { it.jsonPrimitive.content }) }
        assertEquals(literal.terminalText("framed_bytes").toInt(), frames.sumOf { it.size }, name)
        assertEquals(literal.terminalText("sha256"), terminalHash(*frames.toTypedArray()), name)
        assertEquals(literal.terminalText("sha256"), actual, name)
    }
}
