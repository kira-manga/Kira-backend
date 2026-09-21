package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCountHashV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestRetainedOrdinaryCanonicalV1
import me.manga.kira.backend.security.TestTerminalFramesV1
import me.manga.kira.backend.security.TestTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestTerminalInventoryFoldV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalRootsV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.util.HexFormat

/** Current typed SQL reads, not recycled staging or a configured-J-sized metadata sort/cache. */
internal object TestRunPurgeInventorySourceV1 {
    data class Roots(val seals: TestTerminalCountHashV1, val inventory: TestTerminalCountHashV1) {
        override fun toString(): String = "TestRunPurgeInventorySourceV1.Roots(comparison-only,redacted)"
    }

    fun read(jdbc: JdbcTemplate, operation: TestRunPurgeOperationV1, run: TestOrdinaryDrainRowsV1.Run): Roots {
        operation.requireInventoryPage(jdbc)
        requirePurge(jdbc.queryForObject("SELECT current_setting('server_encoding') = 'UTF8'", Boolean::class.java) == true)
        val original = operation.original
        val roots = TestTerminalRootsV1(original.routing.journalConfiguration, run.installationLimit, run.plan.manifestChunkCount.toInt())
        val seals = TestTerminalJsonV1(original.routing.journalConfiguration).sealSet(checkNotNull(run.sealSetBytes))
        requirePurge(seals.records() == original.control.ordinarySeals(original.ordinarySeal))
        val fold = roots.preTerminalInventory(original.runContext, seals)
        val sealEntries = seals.records().map { seal -> TestTerminalInventoryEntryV1(original.writer, "EPOCH_SEAL",
            seal.epochStartInclusive, seal.epochEndInclusive, seal.objectRef) }
        repeat(2) { pass ->
            // These are independent actual reads, not a replay of the first fold's entries.
            TestOrdinaryDrainActiveHistoryV1.requireCurrent(jdbc, original.drain, original.control.initialHistory)
            requireNativeCut(jdbc, operation)
            var after: TestOrdinaryDrainPersistenceV1.Applied? = null
            var count = 0L
            var sealIndex = 0
            while (true) {
                operation.requireInventoryPage(jdbc)
                val page = terminalPage(jdbc, operation, after)
                if (page.isEmpty()) {
                    if (count == 0L) resolvePage(jdbc, operation, run, page) // Empty is still a complete family check.
                    break
                }
                val hashes = resolvePage(jdbc, operation, run, page)
                page.forEachIndexed { index, value ->
                    operation.requireInventoryPage(jdbc)
                    requirePurge(value.epoch in original.control.ordinaryStart..original.control.cutoff &&
                        count < original.ordinaryCut.denial.firstInventory.versionCount)
                    val entry = TestTerminalInventoryEntryV1(original.writer, value.kind, value.epoch, value.epoch,
                        TestTerminalObjectRefV1(value.key, value.version, value.ciphertext, hashes[index]))
                    while (sealIndex < sealEntries.size && compare(sealEntries[sealIndex], entry) < 0) add(fold, pass, sealEntries[sealIndex++])
                    add(fold, pass, entry)
                    after = value; count++
                }
            }
            requirePurge(count == original.ordinaryCut.denial.firstInventory.versionCount)
            while (sealIndex < sealEntries.size) add(fold, pass, sealEntries[sealIndex++])
            operation.requireInventoryPage(jdbc)
            if (pass == 0) fold.beginSecondPass()
        }
        return Roots(roots.preTerminalSeals(seals), fold.finish()).also { operation.requireInventoryPage(jdbc) }
    }

    /** One <=50 scalar page plus one primary/family. Re-read primaries; never retain a whole-run alias map. */
    private fun resolvePage(jdbc: JdbcTemplate, operation: TestRunPurgeOperationV1, run: TestOrdinaryDrainRowsV1.Run,
        page: List<TestOrdinaryDrainPersistenceV1.Applied>): List<String> {
        requirePurge(page.size <= 50)
        val original = operation.original
        val facts = TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, original.control.cutoff)
        val hashes = arrayOfNulls<String>(page.size)
        var after: String? = null
        var primaries = 0L
        var applied = 0L
        while (true) {
            operation.requireInventoryPage(jdbc)
            val id = jdbc.query(TestRunPurgeSqlV1.primaryNext, { row, _ -> checkNotNull(row.getString("event_id")) }, original.scope,
                original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%", after, after).singleOrNull() ?: break
            requirePurge(after?.let { it < id } != false && primaries < original.drain.maximumVersions)
            val primary = TestOrdinaryDrainPersistenceV1.closedPrimary(jdbc, facts, id)
            val family = TestOrdinaryDrainPersistenceV1.readFamilyFacts(jdbc, facts, primary, checkNotNull(primary.publication.verifiedAt))
            TestOrdinaryDrainPersistenceV1.requirePrimaryFacts(jdbc, facts, run, primary, family, converted = true, lockDomain = false)
            val routes = original.routing.derive(primary.event.comparison).candidates()
            page.forEachIndexed { index, entry ->
                val route = routes.singleOrNull { it.eventId == entry.eventId }
                if (route != null) {
                    requirePurge(hashes[index] == null && route.objectKey == entry.key)
                    val actual = family.single { it.locator == entry.locator }
                    requirePurge(actual.eventId == entry.eventId && actual.ciphertext == entry.ciphertext && actual.epoch == entry.epoch &&
                        actual.kind == entry.kind && actual.targetCount == entry.targetCount && actual.at == entry.at && actual.stamp == entry.stamp)
                    // Native AEAD/full-tuple comparison at APPLY + exact cut membership are the trust
                    // link. Different retained routes MUST NOT inherit the primary canonical hash.
                    hashes[index] = TestRetainedOrdinaryCanonicalV1.sha256(original.routing, primary.event, route)
                }
            }
            applied = Math.addExact(applied, family.size.toLong())
            requirePurge(applied <= original.ordinaryCut.denial.firstInventory.versionCount)
            primaries++; after = id
            operation.requireInventoryPage(jdbc)
        }
        val total = jdbc.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, original.scope)
        requirePurge(applied == original.ordinaryCut.denial.firstInventory.versionCount && total == applied)
        operation.requireInventoryPage(jdbc)
        return hashes.map { checkNotNull(it) }
    }

    private fun terminalPage(jdbc: JdbcTemplate, operation: TestRunPurgeOperationV1,
        after: TestOrdinaryDrainPersistenceV1.Applied?): List<TestOrdinaryDrainPersistenceV1.Applied> {
        operation.requireInventoryPage(jdbc)
        val original = operation.original
        val facts = TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, original.control.cutoff)
        return jdbc.query(TestRunPurgeSqlV1.inventoryPage, { row, _ ->
            operation.requireInventoryPage(jdbc)
            TestOrdinaryDrainPersistenceV1.Applied.readFacts(row, facts, hasTime = true)
        }, original.scope, original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%",
            after?.epoch, after?.epoch, after?.kind, after?.key, after?.version).also { operation.requireInventoryPage(jdbc) }
    }

    private fun requireNativeCut(jdbc: JdbcTemplate, operation: TestRunPurgeOperationV1) {
        val original = operation.original
        val expected = original.ordinaryCut.denial.firstInventory
        val facts = TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, original.control.cutoff)
        val hash = MessageDigest.getInstance("SHA-256")
        var framed = EpochSealFramesV1.update(hash, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
            original.routing.journalConfiguration.ordinaryPrefix, "TEST", original.scope.toString(), "1", original.control.cutoff.toString(), expected.versionCount.toString()))
        var count = 0L
        var after: Pair<String, String>? = null
        while (true) {
            operation.requireInventoryPage(jdbc)
            val page = jdbc.query(TestOrdinaryDrainSqlV1.appliedPage, { row, _ ->
                operation.requireInventoryPage(jdbc)
                TestOrdinaryDrainPersistenceV1.Applied.readFacts(row, facts, hasTime = true)
            }, original.scope, original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%",
                after?.first, after?.first, after?.second)
            if (page.isEmpty()) break
            page.forEach { value ->
                operation.requireInventoryPage(jdbc)
                requirePurge(value.epoch in original.control.ordinaryStart..original.control.cutoff &&
                    count < expected.versionCount && after?.let { TestOrdinaryDrainRowsV1.compare(it, value.locator) < 0 } != false)
                framed = Math.addExact(framed, EpochSealFramesV1.update(hash, listOf(value.key, value.version, value.ciphertext)))
                requirePurge(framed <= original.drain.maximumFramedBytes)
                count++; after = value.locator
            }
        }
        requirePurge(count == expected.versionCount && framed == original.ordinaryCut.framedByteCount && HexFormat.of().formatHex(hash.digest()) == expected.sha256)
        operation.requireInventoryPage(jdbc)
    }

    private fun add(fold: TestTerminalInventoryFoldV1, pass: Int, entry: TestTerminalInventoryEntryV1) {
        if (pass == 0) fold.firstPass(entry) else fold.secondPass(entry)
    }
    private fun compare(left: TestTerminalInventoryEntryV1, right: TestTerminalInventoryEntryV1): Int {
        requirePurge(left.writerGeneration == right.writerGeneration)
        return sequenceOf(left.epochStartInclusive.compareTo(right.epochStartInclusive), left.epochEndInclusive.compareTo(right.epochEndInclusive),
            left.objectKind.compareTo(right.objectKind), left.objectRef.objectKey.compareTo(right.objectRef.objectKey))
            .firstOrNull { it != 0 } ?: TestTerminalFramesV1.compareUtf8(left.objectRef.objectVersion, right.objectRef.objectVersion)
    }
}
