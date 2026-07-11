package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.common.exception.ApiException
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.PublishOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * PLAN §11 test 48 — `ConcurrentSameSourcePublishIT`: concurrent publishes for one source serialize with
 * one deterministic winner. Two truly concurrent publish calls for two drafts of the same source → both
 * serialize on the §9 locks, the `uq_one_published_per_source` partial unique index is never violated
 * (no DB constraint error surfaces), exactly one published revision remains, and the final served
 * document reflects the winner (PLAN §9 in-transaction supersede-then-publish ordering).
 */
class ConcurrentSameSourcePublishIT : AbstractAdminSourceIT() {

    @Test
    fun `two concurrent publishes of the same source never violate the one-published index`() {
        val api = "Race"
        sourceAdminService.createSource(toJson(SourceConfigFixtures.validGenericSource(api)), admin.id) // r1 draft
        sourceAdminService.createRevision(api, toJson(SourceConfigFixtures.validGenericSource(api)), admin.id) // r2 draft

        val results = CopyOnWriteArrayList<Result<PublishOutcome>>()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks =
                listOf(1, 2).map { number ->
                    Callable {
                        barrier.await()
                        results.add(runCatching { sourceAdminService.publish(api, number, admin.id) })
                    }
                }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        // Any failure must be a deterministic 409 (older-than-published or superseded) — NEVER a raw DB
        // constraint violation surfacing from the partial unique index.
        val failures = results.filter { it.isFailure }.map { it.exceptionOrNull()!! }
        for (failure in failures) {
            assertTrue(failure is ApiException, "a losing publish must be a mapped ApiException, was $failure")
            assertEquals(HttpStatus.CONFLICT, (failure as ApiException).status, "the loser must be a 409")
        }
        assertTrue(results.any { it.isSuccess }, "at least one publish must succeed")

        // Exactly one published revision, and the served document reflects the published winner.
        assertEquals(1L, publishedRevisionCount(api), "the one-published-per-source index must hold")
        val served = servedDocument(latestPointer()!!)
        assertEquals(listOf(api), served.sources.map { it.api })
    }
}
