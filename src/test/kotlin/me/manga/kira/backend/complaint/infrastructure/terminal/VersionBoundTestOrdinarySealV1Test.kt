package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.catalog.FullTestCatalogInputs
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHttpFixtureV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import me.manga.kira.backend.security.TestTerminalTestFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Arithmetic over explicitly synthetic cold declarations, never authenticated deployment/retention intake. */
class VersionBoundTestOrdinarySealV1Test {
    @Test
    fun newRetentionKeepsCalendarYearsAndConservativeAttemptUtcAndLateArrivalMargins() {
        assertEquals(Instant.parse("2030-02-28T12:34:56.123456Z"),
            VersionBoundTestOrdinarySealV1.tenYears(Instant.parse("2020-02-29T12:34:56.123456Z")))
        withOwner(Instant.parse("2000-01-01T00:00:00Z")) { routing, owner, http ->
            val attempt = TestTerminalCodecV1.fromRetained(routing) { 0L }.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL)
            val created = http.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
            // Fixture declares 1,000ms each for UTC uncertainty and accepted-request late arrival.
            val margin = routing.journalConfiguration.declaration().limits.deadlines.epochSealMillis.toLong() + 1 + 2_000
            val before = http.now()
            val retained = owner.newRetention(attempt, created)
            val after = http.now()
            assertEquals(0, retained.nano)
            assertFalse(retained.isBefore(ceilingSecond(calendarTenYears(before.plusMillis(margin)))))
            assertFalse(retained.isAfter(ceilingSecond(calendarTenYears(after.plusMillis(margin)))))
        }
        val horizon = Instant.parse("2090-01-01T00:00:00Z") // Independent synthetic declaration, not derived from J or accepted intake.
        withOwner(horizon) { routing, owner, http ->
            val attempt = TestTerminalCodecV1.fromRetained(routing) { 0L }.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL)
            assertEquals(horizon.plusSeconds(31 * 86_400L), owner.newRetention(attempt, http.now().truncatedTo(ChronoUnit.SECONDS)))
        }
    }

    @Test
    fun verificationRequiresBothIndependentFloorsAndCannotDowngradeRequestedRetention() {
        val created = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
        for (horizon in listOf(Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2090-01-01T00:00:00Z"))) {
            withOwner(horizon) { _, owner, http ->
                val floor = maxOf(calendarTenYears(created), horizon.plusSeconds(31 * 86_400L))
                val requested = created.plusSeconds(1)
                val observed = owner.verifyRetention(created, floor, requested)
                assertFalse(observed.isBefore(created) || observed.isAfter(http.now()))
                assertThrows<TestOrdinarySealExceptionV1> { owner.verifyRetention(created, floor.minusSeconds(1), requested) }
                assertThrows<TestOrdinarySealExceptionV1> { owner.verifyRetention(created, floor, floor.plusSeconds(1)) }
            }
        }
    }

    private fun withOwner(horizon: Instant, action: (TestOwnerDeleteJournalRoutingV1, VersionBoundTestOrdinarySealV1, TestOrdinarySealHttpFixtureV1) -> Unit) {
        val source = TestTerminalTestFixture()
        val routing = TestOwnerDeleteJournalRoutingV1.fromAcquired(source.journal, source.acquired())
        JournalPublicationLanesV1(source.journal).use { lanes ->
            TestOrdinarySealHttpFixtureV1(horizon = horizon).use { http ->
                val owner = http.owner(routing, lanes, "test-ordinary-seal-unit", FullTestCatalogInputs.registry().catalogWriter)
                action(routing, owner, http)
                assertEquals(0, http.sts.createdClients + http.kms.createdClients + http.s3Created)
                assertTrue(http.sts.requests.isEmpty() && http.kms.requests.isEmpty() && http.requests.isEmpty())
                assertEquals(0L, lanes.activeOwners().totalOwners)
            }
        }
    }

    private fun calendarTenYears(at: Instant): Instant = at.atOffset(ZoneOffset.UTC).plusYears(10).toInstant()
    private fun ceilingSecond(at: Instant): Instant = Instant.ofEpochSecond(at.epochSecond + if (at.nano == 0) 0 else 1)
}
