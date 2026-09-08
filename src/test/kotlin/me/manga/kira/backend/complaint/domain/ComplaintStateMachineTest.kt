package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Instant
import java.util.UUID

class ComplaintStateMachineTest {
    @TestFactory
    fun `every non-notice status has the declared transition matrix`(): List<DynamicTest> = listOf(ComplaintKind.REPORT, ComplaintKind.REPLY).flatMap { kind ->
        ComplaintStatus.entries.flatMap { from ->
            ComplaintStatus.entries.map { to ->
                DynamicTest.dynamicTest("$kind $from to $to") {
                    val state = legacyState(from).copy(kind = kind)
                    when {
                        to !in setOf(
                            ComplaintStatus.OPEN,
                            ComplaintStatus.IN_PROGRESS,
                            ComplaintStatus.PLANNED,
                            ComplaintStatus.RESOLVED,
                            ComplaintStatus.NOT_PLANNED,
                        ) -> assertRule(ComplaintRuleCode.INVALID_STATUS_TARGET) { ComplaintStateMachine.transition(state, to) }

                        from == to -> assertRule(ComplaintRuleCode.NO_CHANGE) { ComplaintStateMachine.transition(state, to) }

                        else -> {
                            val result = ComplaintStateMachine.transition(state, to)
                            assertEquals(to, result.status)
                            assertEquals(8L, result.version)
                            assertEquals(kind, result.kind)
                            assertEquals(state.ownership, result.ownership)
                            assertNull(result.closure)
                            assertEquals(from, state.status)
                            assertEquals(7L, state.version)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `every mutation refuses a notice including its existing pinned status`() {
        val notice = ComplaintModerationState(ComplaintKind.NOTICE, ComplaintOwnership.SYSTEM, ComplaintStatus.PINNED, 1)
        for (target in ComplaintStatus.entries) {
            assertRule(ComplaintRuleCode.IMMUTABLE_NOTICE) { ComplaintStateMachine.transition(notice, target) }
        }
        assertRule(ComplaintRuleCode.IMMUTABLE_NOTICE) { ComplaintStateMachine.close(notice, "reason", admin, now) }
        assertRule(ComplaintRuleCode.IMMUTABLE_NOTICE) { ComplaintStateMachine.contentEdited(notice) }
    }

    @Test
    fun `normal close creates a complete normalized admin tuple and exactly one version`() {
        val state = ComplaintModerationState(ComplaintKind.REPORT, ComplaintOwnership.INSTALLATION, ComplaintStatus.OPEN, 4)
        val result = ComplaintStateMachine.close(state, " \tother: first\r\nsecond \n", admin, now)
        assertEquals(ComplaintStatus.CLOSED, result.status)
        assertEquals(5L, result.version)
        assertEquals(ComplaintClosure.Admin("other: first\nsecond", admin, now), result.closure)
        assertEquals(ComplaintStatus.OPEN, state.status)
        assertNull(state.closure)
    }

    @Test
    fun `same normalized admin reason cannot replace the actor time or version`() {
        val state = closedState()
        assertRule(ComplaintRuleCode.NO_CHANGE) {
            ComplaintStateMachine.close(state, " \treason\r\nnext line\n ", otherAdmin, now.plusSeconds(60))
        }
        assertEquals(7L, state.version)
        assertEquals(ComplaintClosure.Admin("reason\nnext line", admin, now), state.closure)
    }

    @Test
    fun `different closed reason replaces the complete tuple once`() {
        val state = closedState()
        val result = ComplaintStateMachine.close(state, " corrected ", otherAdmin, now.plusSeconds(60))
        assertEquals(ComplaintStatus.CLOSED, result.status)
        assertEquals(8L, result.version)
        assertEquals(ComplaintClosure.Admin("corrected", otherAdmin, now.plusSeconds(60)), result.closure)
    }

    @Test
    fun `legacy closure is retained on content edit and normalized on explicit admin closure`() {
        for (oldReason in listOf(null, "reason")) {
            val state = legacyState(ComplaintStatus.CLOSED).copy(closure = ComplaintClosure.Legacy(oldReason, null))
            val edit = ComplaintStateMachine.contentEdited(state)
            assertSame(state.closure, edit.closure)
            assertEquals(ComplaintStatus.CLOSED, edit.status)
            assertEquals(8L, edit.version)
            val closure = ComplaintStateMachine.close(state, " reason ", admin, now)
            assertEquals(ComplaintClosure.Admin("reason", admin, now), closure.closure)
            assertEquals(8L, closure.version)
            val reopened = ComplaintStateMachine.transition(state, ComplaintStatus.OPEN)
            assertNull(reopened.closure)
        }
    }

    @Test
    fun `invalid closure and notice ownership states cannot be constructed`() {
        val open = ComplaintModerationState(ComplaintKind.REPORT, ComplaintOwnership.INSTALLATION, ComplaintStatus.OPEN, 1)
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { open.copy(status = ComplaintStatus.CLOSED) }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { open.copy(closure = ComplaintClosure.Admin("reason", admin, now)) }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) {
            open.copy(status = ComplaintStatus.CLOSED, closure = ComplaintClosure.Legacy(null, null))
        }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { open.copy(kind = ComplaintKind.NOTICE) }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { open.copy(ownership = ComplaintOwnership.SYSTEM) }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { open.copy(status = ComplaintStatus.UNKNOWN) }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { open.copy(version = 0) }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { open.copy(version = -1) }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) {
            ComplaintModerationState(ComplaintKind.NOTICE, ComplaintOwnership.SYSTEM, ComplaintStatus.OPEN, 1)
        }
    }

    @Test
    fun `closures reject missing blank oversized or non-normalized reasons`() {
        assertThrows(ComplaintValidationException::class.java) { ComplaintStateMachine.close(legacyState(ComplaintStatus.OPEN), "  ", admin, now) }
        assertThrows(ComplaintValidationException::class.java) {
            ComplaintStateMachine.close(legacyState(ComplaintStatus.OPEN), "a".repeat(501), admin, now)
        }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { ComplaintClosure.Admin(" reason ", admin, now) }
        assertRule(ComplaintRuleCode.INVALID_MODERATION_STATE) { ComplaintClosure.Legacy(" reason ", null) }
    }

    @Test
    fun `version exhaustion never wraps a mutable row into an old version`() {
        val state = legacyState(ComplaintStatus.OPEN).copy(version = Long.MAX_VALUE)
        assertRule(ComplaintRuleCode.VERSION_EXHAUSTED) { ComplaintStateMachine.transition(state, ComplaintStatus.RESOLVED) }
        assertRule(ComplaintRuleCode.VERSION_EXHAUSTED) { ComplaintStateMachine.close(state, "reason", admin, now) }
        assertRule(ComplaintRuleCode.VERSION_EXHAUSTED) { ComplaintStateMachine.contentEdited(state) }
        assertEquals(Long.MAX_VALUE, state.version)
    }

    @Test
    fun `diagnostic strings do not expose closure prose or actor IDs`() {
        val state = closedState()
        assertFalse(state.toString().contains("reason\nnext line"))
        assertFalse(state.toString().contains(admin.toString()))
        assertFalse(ComplaintClosure.Legacy("private fixture", now).toString().contains("private fixture"))
    }

    private fun closedState(): ComplaintModerationState = ComplaintModerationState(
        ComplaintKind.REPORT,
        ComplaintOwnership.INSTALLATION,
        ComplaintStatus.CLOSED,
        7,
        ComplaintClosure.Admin("reason\nnext line", admin, now),
    )

    private fun legacyState(status: ComplaintStatus): ComplaintModerationState = ComplaintModerationState(
        ComplaintKind.REPORT,
        ComplaintOwnership.LEGACY_UNCLAIMED,
        status,
        7,
        if (status == ComplaintStatus.CLOSED) ComplaintClosure.Legacy(null, null) else null,
    )

    private fun assertRule(code: ComplaintRuleCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintRuleException::class.java, action).code)
    }

    private val admin = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val otherAdmin = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val now = Instant.parse("2026-09-05T00:00:00Z")
}
