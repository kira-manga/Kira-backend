package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.security.EpochSealTestFixtureV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecTestFixtureV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Focused affected seam only: old default/LIVE callers, plus the original terminal budget. */
class JournalKmsTerminalBudgetCompatibilityTest {
    @Test
    fun defaultNullAndLiveCallsKeepTheirSemanticsWhileTerminalBudgetShrinksOnEverySample() {
        var localNanos = 0L
        // The original eight-position source call and explicit nulls retain sub-millisecond precision.
        val oldDefault = JournalKmsCall(JournalKmsOperation.GENERATE, AwsJournalKmsFixture.ARN, "synthetic-context", 6144, 1000, null, 0, { localNanos })
        val explicitNull = JournalKmsCall(JournalKmsOperation.GENERATE, AwsJournalKmsFixture.ARN, "synthetic-context", 6144, 1000, null, 0, { localNanos }, null, null)
        assertEquals(1_000_000_000L, oldDefault.remainingNanos())
        assertEquals(oldDefault.remainingNanos(), explicitNull.remainingNanos())
        localNanos = 123_456_789
        assertEquals(876_543_211L, oldDefault.remainingNanos())
        assertEquals(oldDefault.remainingNanos(), explicitNull.remainingNanos())

        val live = EpochSealTestFixtureV1()
        var liveOuterNanos = 0L
        var liveCallNanos = 0L
        val liveBudget = PersistenceTimeBudget.start(750, PersistenceNanoClock { liveOuterNanos })
        val liveAttempt = live.codec.startAttempt(liveBudget)
        // Existing ninth-position LIVE attempt remains valid with the new terminal argument omitted.
        val liveCall = JournalKmsCall(JournalKmsOperation.GENERATE, AwsJournalKmsFixture.ARN, "synthetic-context", 6144, 1000, null, 0, { liveCallNanos }, liveAttempt)
        assertEquals(750_000_000L, liveCall.remainingNanos())
        liveOuterNanos = 123_456_789
        assertEquals(626_000_000L, liveCall.remainingNanos())
        live.clock.nanos = (live.journal.declaration().limits.deadlines.epochSealMillis - 501L) * 1_000_000 + 500_000
        assertEquals(500_000_000L, liveCall.remainingNanos())
        liveOuterNanos = 700_000_001
        assertEquals(49_000_000L, liveCall.remainingNanos())
        liveCallNanos = 960_000_001
        assertEquals(39_999_999L, liveCall.remainingNanos())

        val terminal = TestTerminalCodecTestFixtureV1()
        var terminalOuterNanos = 0L
        var terminalCallNanos = 0L
        val terminalBudget = PersistenceTimeBudget.start(750, PersistenceNanoClock { terminalOuterNanos })
        val terminalAttempt = terminal.attempt(TestTerminalCodecKindV1.EPOCH_SEAL, terminalBudget)
        val terminalCall = JournalKmsCall(JournalKmsOperation.GENERATE, terminal.journal.declaration().encryption.keyArn,
            "synthetic-context", 6144, 1000, null, 0, { terminalCallNanos }, terminalAttempt = terminalAttempt)
        assertEquals(750_000_000L, terminalCall.remainingNanos())
        // Only the independent enclosing clock changes: a captured/local-only budget would incorrectly stay 750ms.
        terminalOuterNanos = 123_456_789
        assertEquals(626_000_000L, terminalCall.remainingNanos())
        terminalOuterNanos = 234_567_890
        assertEquals(515_000_000L, terminalCall.remainingNanos())
        terminal.nanos = (terminal.journal.declaration().limits.deadlines.epochSealMillis - 251L) * 1_000_000 + 1
        assertEquals(250_000_000L, terminalCall.remainingNanos())
        terminalOuterNanos = 700_100_000
        assertEquals(49_000_000L, terminalCall.remainingNanos())
        terminalCallNanos = 975_500_123
        assertEquals(24_499_877L, terminalCall.remainingNanos())
    }
}
