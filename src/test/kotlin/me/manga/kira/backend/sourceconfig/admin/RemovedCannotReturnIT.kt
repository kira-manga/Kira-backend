package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 31 — `RemovedCannotReturnIT`: removed is terminal. Every transition out of `removed` →
 * 409; the stanza never reappears in a later snapshot (PLAN §9). (The "import cannot revive it" half is
 * Phase 8.)
 */
class RemovedCannotReturnIT : AbstractAdminSourceIT() {

    @Test
    fun `a removed source refuses every transition and never reappears`() {
        val api = "Gone"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        disable(api).andExpect { status { isOk() } }
        retire(api).andExpect { status { isOk() } }
        remove(api).andExpect { status { isOk() } }

        // Every transition out of removed → 409.
        disable(api).andExpect { status { isConflict() } }
        enable(api).andExpect { status { isConflict() } }
        retire(api).andExpect { status { isConflict() } }
        remove(api).andExpect { status { isConflict() } }
        // Publishing (even the still-"published" revision 1) is 409 on a removed source (§9 precedence).
        publish(api, 1).andExpect { status { isConflict() } }

        // Publish an unrelated source → a fresh snapshot that still must NOT carry the removed stanza.
        createSource(SourceConfigFixtures.validGenericSource("Other")).andExpect { status { isCreated() } }
        val laterDoc = docRevisionOf(publish("Other", 1).andExpect { status { isOk() } })
        val document = servedDocument(laterDoc)
        assertNull(document.sources.firstOrNull { it.api == api }, "a removed stanza must never reappear")
        assertEquals(listOf("Other"), document.sources.map { it.api })
    }
}
