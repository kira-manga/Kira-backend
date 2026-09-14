package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.observability.KiraMetrics
import me.manga.kira.backend.sourceconfig.application.DocumentAssemblyService
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import me.manga.kira.backend.sourceconfig.application.SourceNotFoundException
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentSummary
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.SourceRevisionSummary
import me.manga.kira.backend.sourceconfig.domain.ValidationResultRepository
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.util.UUID

class SourceAdminHistoryTest {
    private val sources = mock(SourceConfigRepository::class.java)
    private val revisions = mock(RevisionRepository::class.java)
    private val documents = mock(PublishedDocumentRepository::class.java)
    private val validations = mock(ValidationResultRepository::class.java)
    private val assembly = mock(DocumentAssemblyService::class.java)
    private val validator = mock(SourceConfigValidator::class.java)
    private val audit = mock(AuditService::class.java)
    private val metrics = mock(KiraMetrics::class.java)
    private val service = SourceAdminService(sources, revisions, validations, documents, assembly, validator, audit, Clock.systemUTC(), metrics)
    private val head =
        SourceConfigHead(
            id = UUID.randomUUID(),
            api = "History",
            displayName = "History",
            language = "en",
            engine = "generic",
            status = SourceLifecycleStatus.DRAFT,
            position = 0,
            baseUrl = "https://example.invalid",
            adult = false,
            currentPublishedRevisionId = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            publishedAt = null,
        )

    @AfterEach
    fun noValidationOrMutationWork() {
        verifyNoInteractions(validations, assembly, validator, audit, metrics)
    }

    @Test
    fun `source history trims descending lookahead before reversing and uses retained gapped cursor`() {
        `when`(sources.findByApi(head.api)).thenReturn(head)
        val descending = listOf(revision(20, true), revision(14, false), revision(9, null))
        // 21 does not identify a row; it is only an exclusive bound.
        `when`(revisions.findSummaryWindow(head.id, 21, 3)).thenReturn(descending)

        val result = service.listRevisions(head.api, 2, 21)

        assertEquals(listOf(descending[1], descending[0]), result.items)
        assertEquals(14L, result.nextBeforeRevision)
        assertEquals(listOf(false, true), result.items.map { it.valid })
        verify(sources).findByApi(head.api)
        verify(revisions).findSummaryWindow(head.id, 21, 3)
        verifyNoMoreInteractions(sources, revisions)
        verifyNoInteractions(documents)
    }

    @Test
    fun `document history uses one summary read and never includes lookahead`() {
        val descending = listOf(document(20), document(14), document(9))
        `when`(documents.findSummaryWindow(null, 3)).thenReturn(descending)

        val result = service.listDocuments(2)

        assertEquals(listOf(descending[1], descending[0]), result.items)
        assertEquals(14L, result.nextBeforeRevision)
        verify(documents).findSummaryWindow(null, 3)
        verifyNoMoreInteractions(documents)
        verifyNoInteractions(sources, revisions)
    }

    @Test
    fun `exact-size and empty windows are terminal without cursor arithmetic`() {
        val descending = listOf(document(Long.MAX_VALUE), document(Long.MAX_VALUE - 3))
        `when`(documents.findSummaryWindow(null, 3)).thenReturn(descending)
        `when`(documents.findSummaryWindow(1, 3)).thenReturn(emptyList())

        val exact = service.listDocuments(2)
        assertEquals(descending.reversed(), exact.items)
        assertNull(exact.nextBeforeRevision)
        val empty = service.listDocuments(2, 1)
        assertEquals(emptyList<PublishedDocumentSummary>(), empty.items)
        assertNull(empty.nextBeforeRevision)
        verify(documents).findSummaryWindow(null, 3)
        verify(documents).findSummaryWindow(1, 3)
        verifyNoMoreInteractions(documents)
        verifyNoInteractions(sources, revisions)
    }

    @Test
    fun `maximum page size requests at most 101 metadata rows`() {
        `when`(sources.findByApi(head.api)).thenReturn(head)
        `when`(revisions.findSummaryWindow(head.id, Int.MAX_VALUE, 101)).thenReturn(listOf(revision(5, null)))
        `when`(documents.findSummaryWindow(Long.MAX_VALUE, 101)).thenReturn(emptyList())

        val source = service.listRevisions(head.api, 100, Int.MAX_VALUE)
        assertNull(source.items.single().valid)
        assertNull(source.nextBeforeRevision)
        assertNull(service.listDocuments(100, Long.MAX_VALUE).nextBeforeRevision)
        verify(sources).findByApi(head.api)
        verify(revisions).findSummaryWindow(head.id, Int.MAX_VALUE, 101)
        verify(documents).findSummaryWindow(Long.MAX_VALUE, 101)
        verifyNoMoreInteractions(sources, revisions, documents)
    }

    @Test
    fun `unknown source short-circuits before summary or validation reads`() {
        assertThrows(SourceNotFoundException::class.java) { service.listRevisions("Missing") }
        verify(sources).findByApi("Missing")
        verifyNoMoreInteractions(sources)
        verifyNoInteractions(revisions, documents)
    }

    private fun revision(number: Int, valid: Boolean?) =
        SourceRevisionSummary(number, RevisionStatus.DRAFT, "a".repeat(64), head.id, Instant.EPOCH, null, valid)

    private fun document(number: Long) = PublishedDocumentSummary(number, 1, "b".repeat(64), 3, head.id, Instant.EPOCH)
}
