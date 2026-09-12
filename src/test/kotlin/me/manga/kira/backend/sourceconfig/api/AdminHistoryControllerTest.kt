package me.manga.kira.backend.sourceconfig.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import me.manga.kira.backend.common.GlobalExceptionHandler
import me.manga.kira.backend.security.AdminStepUpService
import me.manga.kira.backend.sourceconfig.application.BundledImportService
import me.manga.kira.backend.sourceconfig.application.SourceAdminService
import me.manga.kira.backend.sourceconfig.application.SourceNotFoundException
import me.manga.kira.backend.sourceconfig.application.SourceOperationalModeService
import me.manga.kira.backend.sourceconfig.domain.HistoryWindow
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentSummary
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.SourceRevisionSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/** Real MVC multiplicity/conversion and wire behavior, not a duplicate parser-unit matrix. */
class AdminHistoryControllerTest {
    private val service = mock(SourceAdminService::class.java)
    private val mapper = ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val actor = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val createdAt = Instant.parse("2026-09-11T00:00:00Z")
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc =
            MockMvcBuilders.standaloneSetup(
                AdminDocumentsController(service, DocumentResponseWriter()),
                AdminSourcesController(
                    service,
                    mock(SourceOperationalModeService::class.java),
                    mock(BundledImportService::class.java),
                    mock(AdminStepUpService::class.java),
                ),
            ).setControllerAdvice(GlobalExceptionHandler())
                .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
                .build()
    }

    @Test
    fun `default windows retain arrays metadata and false versus absent validity`() {
        val revisions = listOf(revision(2, null), revision(5, false), revision(9, true).copy(publishedAt = createdAt))
        `when`(service.listRevisions("History", 20, null)).thenReturn(HistoryWindow(revisions, 2))
        `when`(service.listDocuments(20, null)).thenReturn(HistoryWindow(listOf(document(14), document(20)), 14))

        val sourceResponse = mvc.get(SOURCES).andExpect { status { isOk() } }.andReturn().response
        val sourceBody = mapper.readTree(sourceResponse.contentAsByteArray)
        assertTrue(sourceBody.isArray)
        assertEquals(listOf(2, 5, 9), sourceBody.map { it["revisionNumber"].intValue() })
        assertEquals(setOf("revisionNumber", "status", "checksum", "createdBy", "createdAt"), sourceBody[0].fieldNames().asSequence().toSet())
        assertEquals("draft", sourceBody[0]["status"].textValue())
        assertFalse(sourceBody[0].has("valid"))
        assertFalse(sourceBody[0].has("publishedAt"))
        assertEquals(false, sourceBody[1]["valid"].booleanValue())
        assertEquals(true, sourceBody[2]["valid"].booleanValue())
        assertEquals(createdAt.toString(), sourceBody[2]["publishedAt"].textValue())
        assertEquals(listOf("2"), sourceResponse.getHeaders(HEADER).toList())

        val documentResponse = mvc.get(DOCUMENTS).andExpect { status { isOk() } }.andReturn().response
        val documentBody = mapper.readTree(documentResponse.contentAsByteArray)
        assertTrue(documentBody.isArray)
        assertEquals(listOf(14L, 20L), documentBody.map { it["documentRevision"].longValue() })
        assertEquals(
            setOf("documentRevision", "schemaVersion", "checksum", "sourceCount", "createdBy", "createdAt"),
            documentBody[0].fieldNames().asSequence().toSet(),
        )
        assertEquals(listOf("14"), documentResponse.getHeaders(HEADER).toList())
        verify(service).listRevisions("History", 20, null)
        verify(service).listDocuments(20, null)
        verifyNoMoreInteractions(service)
    }

    @TestFactory
    fun `valid boundaries and leading zeros reach the service without truncation`(): List<DynamicTest> = listOf(
        ValidQuery(SOURCES, "1", null, 1, null),
        ValidQuery(DOCUMENTS, "1", null, 1, null),
        ValidQuery(SOURCES, "100", "2147483647", 100, Int.MAX_VALUE.toLong()),
        ValidQuery(DOCUMENTS, "100", "9223372036854775807", 100, Long.MAX_VALUE),
        ValidQuery(DOCUMENTS, "20", "2147483648", 20, 2147483648),
        ValidQuery(SOURCES, "020", "0001", 20, 1),
        ValidQuery(DOCUMENTS, "020", "0001", 20, 1),
    ).map { query ->
        DynamicTest.dynamicTest("${query.path}?size=${query.size}&beforeRevision=${query.cursor}") {
            clearInvocations(service)
            if (query.path == SOURCES) {
                `when`(service.listRevisions("History", query.expectedSize, query.expectedCursor?.toInt()))
                    .thenReturn(HistoryWindow(emptyList(), null))
            } else {
                `when`(service.listDocuments(query.expectedSize, query.expectedCursor)).thenReturn(HistoryWindow(emptyList(), null))
            }
            mvc.get(query.path) {
                param("size", query.size)
                query.cursor?.let { param("beforeRevision", it) }
            }.andExpect {
                status { isOk() }
                content { json("[]") }
                header { doesNotExist(HEADER) }
            }
            if (query.path == SOURCES) {
                verify(service).listRevisions("History", query.expectedSize, query.expectedCursor?.toInt())
            } else {
                verify(service).listDocuments(query.expectedSize, query.expectedCursor)
            }
            verifyNoMoreInteractions(service)
        }
    }

    @TestFactory
    fun `invalid recognized parameters fail value-free before the history service`(): List<DynamicTest> {
        val malformed = listOf("", "0", "0000", "-1", "+1", "1.0", "1e1", " 1", "1 ", "١", "１", "private-query-sentinel", "9223372036854775808")
        val invalid =
            listOf(SOURCES, DOCUMENTS).flatMap { path ->
                listOf("size", "beforeRevision").flatMap { name ->
                    malformed.map { InvalidQuery(path, name, listOf(it)) } +
                        listOf(InvalidQuery(path, name, listOf("20", "20")), InvalidQuery(path, name, listOf("20", "1")))
                } + InvalidQuery(path, "size", listOf("101"))
            } + InvalidQuery(SOURCES, "beforeRevision", listOf("2147483648"))
        return invalid.map { query ->
            DynamicTest.dynamicTest("${query.path} ${query.name}=${query.values}") {
                val response = mvc.get(query.path) { param(query.name, *query.values.toTypedArray()) }.andExpect {
                    status { isBadRequest() }
                    content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                    jsonPath("$.detail") { value("Invalid history pagination parameters.") }
                    jsonPath("$.errors[0].code") { value("INVALID_HISTORY_PAGE") }
                    jsonPath("$.errors[0].message") { value("Invalid history pagination parameters.") }
                    header { doesNotExist(HEADER) }
                }.andReturn().response
                assertFalse(response.contentAsString.contains("private-query-sentinel"))
                verifyNoInteractions(service)
            }
        }
    }

    @Test
    fun `leading-zero query inputs emit a canonical decimal continuation`() {
        `when`(service.listRevisions("History", 2, 21)).thenReturn(HistoryWindow(listOf(revision(14, false), revision(20, true)), 14))
        mvc.get(SOURCES) {
            param("size", "002")
            param("beforeRevision", "00021")
        }.andExpect {
            status { isOk() }
            header { string(HEADER, "14") }
        }
        verify(service).listRevisions("History", 2, 21)
    }

    @Test
    fun `unknown source retains its problem-details 404`() {
        `when`(service.listRevisions("History", 20, null)).thenThrow(SourceNotFoundException("History"))
        mvc.get(SOURCES).andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            header { doesNotExist(HEADER) }
        }
    }

    private fun revision(number: Int, valid: Boolean?) = SourceRevisionSummary(number, RevisionStatus.DRAFT, "a".repeat(64), actor, createdAt, null, valid)

    private fun document(number: Long) = PublishedDocumentSummary(number, 1, "b".repeat(64), 3, actor, createdAt)

    private data class ValidQuery(val path: String, val size: String, val cursor: String?, val expectedSize: Int, val expectedCursor: Long?)

    private data class InvalidQuery(val path: String, val name: String, val values: List<String>)

    private companion object {
        const val SOURCES = "/api/v1/admin/sources/History/revisions"
        const val DOCUMENTS = "/api/v1/admin/documents"
        const val HEADER = "X-Kira-History-Next-Before"
    }
}
