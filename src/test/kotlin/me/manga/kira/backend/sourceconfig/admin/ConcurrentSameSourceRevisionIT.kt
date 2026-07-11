package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.SourceMutationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * PLAN §11 test 22 — `ConcurrentSameSourceRevisionIT`: per-source revision numbers are unique and
 * increasing under concurrency. N concurrent revision creations for one source → N distinct consecutive
 * numbers, no constraint violation surfaced to the caller (PLAN §5 source-row `FOR UPDATE` lock).
 */
class ConcurrentSameSourceRevisionIT : AbstractAdminSourceIT() {

    @Test
    fun `concurrent revision creations get distinct consecutive numbers`() {
        val api = "Concurrent"
        sourceAdminService.createSource(toJson(SourceConfigFixtures.validGenericSource(api)), admin.id) // r1

        val n = 8
        val json = toJson(SourceConfigFixtures.validGenericSource(api))
        val results = CopyOnWriteArrayList<Result<SourceMutationResult>>()
        val barrier = CyclicBarrier(n)
        val pool = Executors.newFixedThreadPool(n)
        try {
            val tasks =
                (1..n).map {
                    Callable {
                        barrier.await()
                        results.add(runCatching { sourceAdminService.createRevision(api, json, admin.id) })
                    }
                }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        assertTrue(results.all { it.isSuccess }, "no revision creation may fail: ${results.filter { it.isFailure }}")
        val numbers = results.mapNotNull { it.getOrNull()?.revisionNumber }.sorted()
        // r1 was created up front; the N concurrent creations must be exactly 2..N+1, all distinct.
        assertEquals((2..(n + 1)).toList(), numbers, "revision numbers must be distinct and consecutive")
    }
}
