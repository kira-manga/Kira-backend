package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalTestFixture
import me.manga.kira.backend.security.terminalCanonical
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** Synthetic source specimens only, never retirement/recovery evidence or a completed database read. */
class TestInstallationSourceV1Test {
    private val f = TestTerminalTestFixture()
    private val binding = TestInstallationSourceV1.Binding(TestTerminalTestFixture.uuid(8001), TestTerminalTestFixture.uuid(8002), 3, 17)
    private val at = Instant.parse("2026-09-20T00:00:00Z")

    @Test
    fun emptyAndMixedRawSourcesMatchIndependentLocalAndExistingInstallationFrames() {
        val deleted = row(5, InstallationIdentityState.DELETED).let { value -> value.copy(credential = credential(value.id, InstallationCredentialState.DELETED)) }
        for (rows in listOf(emptyList(), listOf(row(1), row(2, InstallationIdentityState.RECOVERY_RESERVED),
            row(3, InstallationIdentityState.RETIRED), row(4, InstallationIdentityState.DELETED), deleted))) {
            val progress = fold(rows)
            assertEquals(f.run, progress.context())
            assertEquals(emptyList<Any>(), progress.completedCuts())
            assertEquals(2, progress.installationReads().size)
            val first = progress.installationReads().first()
            assertEquals(first.copy(startedAtEpochSecond = 102, completedAtEpochSecond = 103), progress.installationReads().last())
            assertEquals(binding.databaseIdentity, first.databaseIdentity)
            assertEquals(binding.restoreIdentity, first.restoreIdentity)
            assertEquals(binding.desiredGeneration, first.desiredGeneration)
            assertEquals(binding.fencingToken, first.fencingToken)
            assertEquals(rows.size.toLong(), first.installationCount)
            assertEquals(rows.count { it.state == InstallationIdentityState.DELETED }.toLong(), first.deletedCount)
            assertEquals(rows.size.toLong(), first.sourceHighWater.enrolledCount)
            assertEquals(rows.lastOrNull()?.id.orEmpty(), first.sourceHighWater.greatestReservationId)
            assertIndependentFrames(rows, progress)
            val json = TestTerminalJsonV1(f.journal)
            val canonical = json.encodeProgress(progress)
            assertArrayEquals(canonical, json.encodeProgress(json.progress(canonical)))
            assertEquals(progress.installationReads(), json.progress(canonical).installationReads())
        }
        val active = row(1)
        val recovered = active.copy(state = InstallationIdentityState.RECOVERY_RESERVED, credential = null)
        val before = fold(listOf(active)).installationReads().first()
        val after = fold(listOf(recovered)).installationReads().first()
        assertEquals(before.installationsSha256, after.installationsSha256)
        assertEquals(before.chunkSetSha256, after.chunkSetSha256)
        assertNotEquals(before.sourceHighWater.sourceSha256, after.sourceHighWater.sourceSha256)
        for (value in listOf(active, active.credential, binding, builder(1), fold(listOf(active)))) {
            assertFalse(value.toString().contains(active.id)); assertFalse(value.toString().contains(f.run.dataScopeId))
        }
    }

    @Test
    fun fiveHundredAndFiveHundredOneEntriesHaveExactPlaintextGroupingWithoutEnrollmentFixtures() {
        for (size in listOf(500, 501)) {
            val rows = (1..size).map { row(it, if (it % 2 == 0) InstallationIdentityState.DELETED else InstallationIdentityState.RECOVERY_RESERVED) }
            val progress = fold(rows)
            assertEquals(if (size == 500) 1 else 2, progress.installationReads().first().chunkCount)
            assertIndependentFrames(rows, progress)
        }
        val low = row(1).copy(id = "7fffffff-ffff-4fff-8fff-ffffffffffff", credential = null, state = InstallationIdentityState.RECOVERY_RESERVED)
        val high = low.copy(id = "80000000-0000-4000-8000-000000000000")
        assertIndependentFrames(listOf(low, high), fold(listOf(low, high))) // PostgreSQL unsigned order, not UUID.compareTo.
        assertThrows<TestOrdinarySealExceptionV1> { fold(listOf(high, low)) }
    }

    @Test
    fun differingRawStatePairMvccMembershipOrPassOrderPermanentlyPoisonsObservation() {
        val original = row(1)
        val changes = listOf(
            original.copy(state = InstallationIdentityState.RECOVERY_RESERVED, credential = null),
            original.copy(state = InstallationIdentityState.RETIRED, terminalAt = at, credential = null),
            original.copy(createdAt = at.plusSeconds(1)), original.copy(stamp = "19"),
            original.copy(credential = checkNotNull(original.credential).copy(stamp = "20")),
            original.copy(credential = checkNotNull(original.credential).copy(rowVersion = 2)),
        )
        for (changed in changes) {
            val source = builder(1)
            first(source, listOf(original))
            source.beginPass(binding, 102); source.entry(changed)
            assertThrows<TestOrdinarySealExceptionV1> { source.endPass(binding, 103) }
            assertThrows<TestOrdinarySealExceptionV1> { source.finish() }
            assertThrows<TestOrdinarySealExceptionV1> { source.entry(original) }
        }
        val omitted = builder(1)
        first(omitted, listOf(original)); omitted.beginPass(binding, 102)
        assertThrows<TestOrdinarySealExceptionV1> { omitted.endPass(binding, 103) }
        val addedBehindCursor = builder(1)
        first(addedBehindCursor, listOf(row(2))); addedBehindCursor.beginPass(binding, 102); addedBehindCursor.entry(row(1))
        assertThrows<TestOrdinarySealExceptionV1> { addedBehindCursor.entry(row(2)) }
        val duplicate = builder(2)
        duplicate.beginPass(binding, 100); duplicate.entry(original)
        assertThrows<TestOrdinarySealExceptionV1> { duplicate.entry(original) }
        assertThrows<TestOrdinarySealExceptionV1> { duplicate.endPass(binding, 101) }
        val unstarted = builder(0)
        assertThrows<TestOrdinarySealExceptionV1> { unstarted.finish() }
        assertThrows<TestOrdinarySealExceptionV1> { unstarted.beginPass(binding, 100) }
    }

    @Test
    fun pendingOrInvalidPairsScopeBindingsAndObservationTimesCannotSupplyTargets() {
        val active = row(1)
        val c = checkNotNull(active.credential)
        val deleted = row(1, InstallationIdentityState.DELETED)
        val invalid = listOf(
            active.copy(state = InstallationIdentityState.DELETION_PENDING, credential = c.copy(state = InstallationCredentialState.DELETION_PENDING)),
            active.copy(credential = null), active.copy(terminalAt = at), active.copy(testOnly = false), active.copy(scope = TestTerminalTestFixture.uuid(99)),
            active.copy(state = InstallationIdentityState.RECOVERY_RESERVED), deleted.copy(terminalAt = null), deleted.copy(credential = c),
            active.copy(credential = c.copy(id = TestTerminalTestFixture.uuid(99))), active.copy(credential = c.copy(scope = TestTerminalTestFixture.uuid(99))),
            active.copy(credential = c.copy(testOnly = false)), active.copy(credential = c.copy(credentialVersion = 0)),
            active.copy(credential = c.copy(rowVersion = 0)), active.copy(stamp = "4294967296"), active.copy(credential = c.copy(stamp = "not-xmin")),
        )
        invalid.forEach { row -> assertThrows<TestOrdinarySealExceptionV1> { fold(listOf(row)) } }
        val changedBinding = builder(1)
        changedBinding.beginPass(binding, 100); changedBinding.entry(active)
        assertThrows<TestOrdinarySealExceptionV1> { changedBinding.endPass(binding.copy(fencingToken = 18), 101) }
        val wrongRestore = builder(0)
        assertThrows<TestOrdinarySealExceptionV1> { wrongRestore.beginPass(binding.copy(restoreIdentity = TestTerminalTestFixture.uuid(99)), 100) }
        val clockReversed = builder(0)
        first(clockReversed, emptyList())
        assertThrows<TestOrdinarySealExceptionV1> { clockReversed.beginPass(binding, 100) }
        val premature = builder(0)
        premature.beginPass(binding, 100)
        assertThrows<TestOrdinarySealExceptionV1> { premature.endPass(binding, 99) }
    }

    @Test
    fun actualJByteCeilingsEnrollmentLimitAndTerminalObjectHeadroomAreEnforced() {
        val rows = listOf(row(1), row(2, InstallationIdentityState.DELETED))
        val read = fold(rows).installationReads().first()
        val exact = maxOf(read.sourceHighWater.framedByteCount, read.installationsFramedBytes, read.chunkSetFramedBytes)
        assertEquals(read.sourceHighWater, fold(rows, journal(maximumBytes = exact)).installationReads().first().sourceHighWater)
        assertThrows<TestOrdinarySealExceptionV1> { fold(rows, journal(maximumBytes = exact - 1)) }
        assertThrows<TestOrdinarySealExceptionV1> { builder(2, installationLimit = 1) }
        assertThrows<TestOrdinarySealExceptionV1> { builder(1, journal = journal(maximumVersions = 1)) }
        val completed = builder(0)
        first(completed, emptyList()); completed.beginPass(binding, 102); completed.endPass(binding, 103); completed.finish()
        assertThrows<TestOrdinarySealExceptionV1> { completed.finish() }
    }

    private fun builder(count: Int, installationLimit: Long = maxOf(1, count).toLong(), journal: TestOwnerDeleteJournalConfigurationV1 = f.journal) =
        TestInstallationSourceV1(journal, f.run, binding, installationLimit, count.toLong())

    private fun first(source: TestInstallationSourceV1, rows: List<TestInstallationSourceV1.Row>) {
        source.beginPass(binding, 100); rows.forEach(source::entry); source.endPass(binding, 101)
    }

    private fun fold(rows: List<TestInstallationSourceV1.Row>, journal: TestOwnerDeleteJournalConfigurationV1 = f.journal): TestTerminalProgressV1 {
        val source = builder(rows.size, journal = journal)
        first(source, rows); source.beginPass(binding, 102); rows.forEach(source::entry); source.endPass(binding, 103)
        return source.finish()
    }

    private fun row(index: Int, state: InstallationIdentityState = InstallationIdentityState.ACTIVE): TestInstallationSourceV1.Row {
        val id = TestTerminalTestFixture.uuid(index)
        return TestInstallationSourceV1.Row(id, f.run.dataScopeId, true, state, at,
            if (state in setOf(InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED)) at else null, "7",
            if (state == InstallationIdentityState.ACTIVE) credential(id, InstallationCredentialState.ACTIVE) else null)
    }
    private fun credential(id: String, state: InstallationCredentialState) = TestInstallationSourceV1.Credential(id, f.run.dataScopeId, true, state, 1, 1, "8")
    private fun target(row: TestInstallationSourceV1.Row): String = if (row.state == InstallationIdentityState.DELETED) "DELETED" else "RETIRED"

    private fun assertIndependentFrames(rows: List<TestInstallationSourceV1.Row>, progress: TestTerminalProgressV1) {
        val read = progress.installationReads().first()
        val context = listOf(f.run.dataScopeId, f.run.activationCatalogGeneration.toString(), f.run.activationCatalogSha256,
            f.run.configurationSha256, f.run.terminalEncodingSha256)
        val rootFrames = listOf(terminalFrame(listOf("kira-test-installations-v1") + context +
            listOf(rows.size.toString(), rows.count { target(it) == "RETIRED" }.toString(), rows.count { target(it) == "DELETED" }.toString()))) +
            rows.map { terminalFrame(listOf(it.id, target(it))) }
        assertEquals(terminalHash(*rootFrames.toTypedArray()), read.installationsSha256)
        assertEquals(rootFrames.sumOf { it.size.toLong() }, read.installationsFramedBytes)
        val sourceFrames = listOf(terminalFrame(listOf("kira-local-test-installation-source-v1") + context + listOf(binding.databaseIdentity,
            binding.restoreIdentity, "3", "17", rows.size.toString()))) + rows.map { row ->
            val c = row.credential
            terminalFrame(listOf(row.id, row.scope, row.testOnly.toString(), row.state.name, row.createdAt.toString(), row.terminalAt?.toString().orEmpty(), row.stamp,
                if (c == null) "ABSENT" else "PRESENT", c?.id.orEmpty(), c?.scope.orEmpty(), c?.testOnly?.toString().orEmpty(), c?.state?.name.orEmpty(),
                c?.credentialVersion?.toString().orEmpty(), c?.rowVersion?.toString().orEmpty(), c?.stamp.orEmpty()))
        }
        assertEquals(terminalHash(*sourceFrames.toTypedArray()), read.sourceHighWater.sourceSha256)
        assertEquals(sourceFrames.sumOf { it.size.toLong() }, read.sourceHighWater.framedByteCount)
        val chunks = rows.chunked(500)
        val grouping = listOf(terminalFrame(listOf("kira-local-test-installation-groups-v1") + context + listOf(rows.size.toString(), chunks.size.toString()))) +
            chunks.mapIndexed { index, chunk ->
                val plaintext = terminalCanonical(JsonArray(chunk.map { JsonObject(mapOf("installationId" to JsonPrimitive(it.id), "disposition" to JsonPrimitive(target(it)))) }))
                terminalFrame(listOf(index.toString(), chunk.size.toString(), terminalHash(plaintext)))
            }
        assertEquals(terminalHash(*grouping.toTypedArray()), read.chunkSetSha256)
        assertEquals(grouping.sumOf { it.size.toLong() }, read.chunkSetFramedBytes)
    }

    private fun journal(maximumBytes: Long = f.journal.declaration().limits.capacity.maximumScanStagingBytes,
        maximumVersions: Long = f.journal.declaration().limits.capacity.maximumRetainedVersions): TestOwnerDeleteJournalConfigurationV1 {
        val original = f.journal.declaration()
        return TestOwnerDeleteJournalConfigurationV1.of(original.copy(limits = original.limits.copy(capacity = original.limits.capacity.copy(
            maximumRetainedVersions = maximumVersions, maximumScanStagingBytes = maximumBytes))))
    }
}
