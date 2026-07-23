package me.manga.kira.backend.sourceconfig.domain

import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus.ACTIVE
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus.DISABLED
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus.DRAFT
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus.REMOVED
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus.RETIRED
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus.WITHHELD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 4 — `LifecycleStateMachineTest`. Every allowed transition succeeds; every disallowed
 * one throws (esp. `active -> removed` skipping retired, and anything from `removed`). Also pins the
 * publish × status rules and the engine-gated un-retire (PLAN §9). Pure unit test — no Spring context.
 */
class LifecycleStateMachineTest {

    // --- Allowed lifecycle transitions ---

    @Test
    fun `active disable to disabled`() {
        assertEquals(DISABLED, LifecycleStateMachine.transition(ACTIVE, LifecycleAction.DISABLE, "generic"))
        assertEquals(DISABLED, LifecycleStateMachine.transition(ACTIVE, LifecycleAction.DISABLE, "legacy"))
    }

    @Test
    fun `disabled enable to active`() {
        assertEquals(ACTIVE, LifecycleStateMachine.transition(DISABLED, LifecycleAction.ENABLE, "legacy"))
    }

    @Test
    fun `disabled retire to retired`() {
        assertEquals(RETIRED, LifecycleStateMachine.transition(DISABLED, LifecycleAction.RETIRE, "generic"))
    }

    @Test
    fun `retired remove to removed`() {
        assertEquals(REMOVED, LifecycleStateMachine.transition(RETIRED, LifecycleAction.REMOVE, "legacy"))
    }

    @Test
    fun `retired enable un-retires only for generic engine`() {
        assertEquals(ACTIVE, LifecycleStateMachine.transition(RETIRED, LifecycleAction.ENABLE, "generic"))
        assertThrows(UnretireNotAllowedForEngineException::class.java) {
            LifecycleStateMachine.transition(RETIRED, LifecycleAction.ENABLE, "legacy")
        }
        assertThrows(UnretireNotAllowedForEngineException::class.java) {
            LifecycleStateMachine.transition(RETIRED, LifecycleAction.ENABLE, "kotlin:dilar")
        }
    }

    @Test
    fun `canUnretire is generic-only`() {
        assert(LifecycleStateMachine.canUnretire("generic"))
        assert(!LifecycleStateMachine.canUnretire("legacy"))
        assert(!LifecycleStateMachine.canUnretire("kotlin:promanga"))
    }

    // --- Disallowed transitions (throw) ---

    @Test
    fun `active to retired is rejected - soft-disable is mandatory`() {
        assertThrows(IllegalLifecycleTransitionException::class.java) {
            LifecycleStateMachine.transition(ACTIVE, LifecycleAction.RETIRE, "generic")
        }
    }

    @Test
    fun `active to removed directly is rejected`() {
        assertThrows(IllegalLifecycleTransitionException::class.java) {
            LifecycleStateMachine.transition(ACTIVE, LifecycleAction.REMOVE, "generic")
        }
    }

    @Test
    fun `repeating disable or enable is rejected (strict state machine)`() {
        assertThrows(IllegalLifecycleTransitionException::class.java) {
            LifecycleStateMachine.transition(DISABLED, LifecycleAction.DISABLE, "generic")
        }
        assertThrows(IllegalLifecycleTransitionException::class.java) {
            LifecycleStateMachine.transition(ACTIVE, LifecycleAction.ENABLE, "generic")
        }
    }

    @Test
    fun `retire is only from disabled`() {
        assertThrows(IllegalLifecycleTransitionException::class.java) {
            LifecycleStateMachine.transition(RETIRED, LifecycleAction.RETIRE, "generic")
        }
    }

    @Test
    fun `remove is only from retired`() {
        assertThrows(IllegalLifecycleTransitionException::class.java) {
            LifecycleStateMachine.transition(DISABLED, LifecycleAction.REMOVE, "generic")
        }
    }

    @Test
    fun `everything from removed is rejected (terminal)`() {
        for (action in LifecycleAction.entries) {
            assertThrows(RuntimeException::class.java) {
                LifecycleStateMachine.transition(REMOVED, action, "generic")
            }
        }
    }

    @Test
    fun `draft takes no lifecycle action (only leaves via publish)`() {
        for (action in LifecycleAction.entries) {
            assertThrows(IllegalLifecycleTransitionException::class.java) {
                LifecycleStateMachine.transition(DRAFT, action, "generic")
            }
        }
    }

    // --- Publish × status ---

    @Test
    fun `publish makes a draft active and never re-enables`() {
        assertEquals(ACTIVE, LifecycleStateMachine.statusAfterPublish(DRAFT))
        assertEquals(ACTIVE, LifecycleStateMachine.statusAfterPublish(ACTIVE))
        assertEquals(DISABLED, LifecycleStateMachine.statusAfterPublish(DISABLED))
        assertEquals(WITHHELD, LifecycleStateMachine.statusAfterPublish(WITHHELD))
    }

    @Test
    fun `withheld activation requires a generic published engine`() {
        assertEquals(ACTIVE, LifecycleStateMachine.transition(WITHHELD, LifecycleAction.ENABLE, "generic"))
        assertThrows(UnretireNotAllowedForEngineException::class.java) {
            LifecycleStateMachine.transition(WITHHELD, LifecycleAction.ENABLE, "legacy")
        }
    }

    @Test
    fun `publish is forbidden on retired or removed`() {
        assertThrows(IllegalLifecycleTransitionException::class.java) {
            LifecycleStateMachine.statusAfterPublish(RETIRED)
        }
        assertThrows(IllegalLifecycleTransitionException::class.java) {
            LifecycleStateMachine.statusAfterPublish(REMOVED)
        }
    }
}
