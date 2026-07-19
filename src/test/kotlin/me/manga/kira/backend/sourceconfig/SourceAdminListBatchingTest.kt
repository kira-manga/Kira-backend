package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.observability.KiraMetrics
import me.manga.kira.backend.sourceconfig.application.DocumentAssemblyService
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import me.manga.kira.backend.sourceconfig.domain.AdminSourceListing
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.ValidationResultRepository
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SourceAdminListBatchingTest {
    @Test
    fun `admin list consumes one batched repository view and performs no revision lookups`() {
        val sources = mock(SourceConfigRepository::class.java)
        val revisions = mock(RevisionRepository::class.java)
        val head =
            SourceConfigHead(
                id = UUID.randomUUID(),
                api = "Batched",
                displayName = "Batched",
                language = "en",
                engine = "generic",
                status = SourceLifecycleStatus.ACTIVE,
                position = 0,
                baseUrl = "https://example.test",
                adult = false,
                currentPublishedRevisionId = UUID.randomUUID(),
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                publishedAt = Instant.EPOCH,
            )
        `when`(sources.findAllWithRevisionNumbers(null)).thenReturn(listOf(AdminSourceListing(head, 7, 9)))
        val service =
            SourceAdminService(
                sources,
                revisions,
                mock(ValidationResultRepository::class.java),
                mock(PublishedDocumentRepository::class.java),
                mock(DocumentAssemblyService::class.java),
                SourceConfigValidator(),
                mock(AuditService::class.java),
                Clock.systemUTC(),
                mock(KiraMetrics::class.java),
            )

        val result = service.listSources(null).single()

        assertEquals(7, result.currentPublishedRevisionNumber)
        assertEquals(9, result.latestRevisionNumber)
        verify(sources).findAllWithRevisionNumbers(null)
        verifyNoInteractions(revisions)
    }
}
