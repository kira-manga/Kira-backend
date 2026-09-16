package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CatalogReadbackPolicyTest {
    @Test
    fun `independent genesis pin must be one lowercase SHA256 digest`() {
        listOf("", "a".repeat(63), "A".repeat(64), "0".repeat(65)).forEach { hash ->
            invalid { policy(hash = hash) }
        }
    }

    @Test
    fun `evaluation and retention requirements are finite and retention must be strictly after evaluation`() {
        invalid { policy(evaluatedAt = -1) }
        invalid { policy(evaluatedAt = Long.MAX_VALUE) }
        invalid { policy(retainUntil = -1) }
        invalid { policy(retainUntil = Long.MAX_VALUE) }
        invalid { policy(retainUntil = CatalogReadbackFixture.EVALUATED_AT) }
        invalid { policy(retainUntil = CatalogReadbackFixture.EVALUATED_AT - 1) }
        val last = CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND
        assertEquals(last, policy(evaluatedAt = last - 1, retainUntil = last).requiredRetainUntilEpochSecond)
    }

    @Test
    fun `page sizes and budgets are finite positive and never exceed protocol ceilings`() {
        listOf(0, -1, 1001, Int.MAX_VALUE).forEach { invalid { policy(pageSize = it) } }
        listOf(0, -1, 65537, Int.MAX_VALUE).forEach { invalid { policy(pages = it) } }
        assertEquals(1000, policy(pageSize = 1000).pageSize)
        assertEquals(65536, policy(pages = 65536).maximumPagesPerLocation)
    }

    private fun policy(
        hash: String = "a".repeat(64),
        evaluatedAt: Long = CatalogReadbackFixture.EVALUATED_AT,
        retainUntil: Long = CatalogReadbackFixture.RETAIN_UNTIL,
        pageSize: Int = 1,
        pages: Int = 1,
    ): CatalogReadbackPolicy = CatalogReadbackPolicy(OfflineCatalogRotationFixture.policy(), hash, evaluatedAt, retainUntil, pageSize, pages)

    private fun invalid(action: () -> Unit) {
        val failure = assertThrows(CatalogReadbackException::class.java) { action() }
        assertEquals(CatalogReadbackFailure.INVALID_POLICY, failure.code)
    }
}
