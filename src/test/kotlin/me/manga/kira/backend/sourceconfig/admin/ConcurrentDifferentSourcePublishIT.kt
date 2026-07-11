package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * PLAN §11 test 21 — `ConcurrentDifferentSourcePublishIT`: no lost document updates. Two concurrent
 * publications to two different sources (real parallel threads/transactions) → the final latest snapshot
 * contains BOTH stanzas (PLAN §9 global publication lock serializes the assembly, so the later writer
 * assembles from a state that already includes the earlier writer's change).
 */
class ConcurrentDifferentSourcePublishIT : AbstractAdminSourceIT() {

    @Test
    fun `two concurrent publishes to different sources both survive in the final snapshot`() {
        sourceAdminService.createSource(toJson(SourceConfigFixtures.validGenericSource("Aaa")), admin.id)
        sourceAdminService.createSource(toJson(SourceConfigFixtures.validGenericSource("Bbb")), admin.id)

        val results = CopyOnWriteArrayList<Result<Unit>>()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks =
                listOf("Aaa", "Bbb").map { api ->
                    Callable {
                        barrier.await()
                        results.add(runCatching { sourceAdminService.publish(api, 1, admin.id) }.map { })
                    }
                }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        assertTrue(results.all { it.isSuccess }, "both first-publishes must succeed: $results")
        val latest = servedDocument(latestPointer()!!)
        assertEquals(setOf("Aaa", "Bbb"), latest.sources.map { it.api }.toSet(), "the final snapshot must carry both")
    }
}
