package me.manga.kira.backend.tutorial

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.GlobalExceptionHandler
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.tutorial.api.PublicTutorialController
import me.manga.kira.backend.tutorial.application.TutorialMediaService
import me.manga.kira.backend.tutorial.application.TutorialService
import me.manga.kira.backend.tutorial.domain.MediaReadResult
import me.manga.kira.backend.tutorial.domain.MediaStorageIssue
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorage
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import me.manga.kira.backend.user.domain.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.imageio.ImageIO

/** Real authorization/controller with a verified-byte port fixture, not a real filter chain or database. */
class TutorialMediaCacheControlTest {
    @TempDir
    lateinit var mediaDirectory: Path

    private val repository = mock(TutorialRepository::class.java)
    private val storage = mock(TutorialMediaStorage::class.java)
    private val tutorials = mock(TutorialService::class.java)
    private val audit = mock(AuditService::class.java)
    private val objectMapper = ObjectMapper()
    private val mediaId = UUID(0, 1)
    private val userId = UUID(0, 2)
    private val mediaBytes = ByteArrayOutputStream().use { output ->
        assertTrue(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", output))
        output.toByteArray()
    }
    private val metadata = StoredMedia(
        id = mediaId,
        storageFilename = "$mediaId.png",
        contentType = MediaType.IMAGE_PNG_VALUE,
        byteSize = mediaBytes.size.toLong(),
        width = 2,
        height = 2,
        sha256 = Sha256.hex(mediaBytes),
        published = false,
        createdBy = userId,
        createdAt = Instant.EPOCH,
    )
    private val mediaEtag = "\"${metadata.sha256}\""
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        Files.write(mediaDirectory.resolve(metadata.storageFilename), mediaBytes)
        val media = TutorialMediaService(
            repository,
            storage,
            CurrentUser(),
            audit,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )
        mockMvc = MockMvcBuilders.standaloneSetup(PublicTutorialController(tutorials, media, objectMapper))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `unpublished media is private no-store for ADMIN 200`() {
        assertDelivered(deliver(published = false, role = Role.ADMIN), "private, no-store")
    }

    @Test
    fun `unpublished media revalidation is private no-store for ADMIN 304`() {
        assertNotModified(deliver(published = false, role = Role.ADMIN, conditional = true), "private, no-store")
    }

    @Test
    fun `anonymous matching ETag cannot expose unpublished media`() {
        assertConcealed(deliver(published = false, conditional = true))
    }

    @Test
    fun `USER matching ETag cannot expose unpublished media`() {
        assertConcealed(deliver(published = false, role = Role.USER, conditional = true))
    }

    @Test
    fun `published media stays publicly immutable for anonymous 200`() {
        assertDelivered(deliver(published = true), "public, max-age=31536000, immutable")
    }

    @Test
    fun `published media revalidation stays publicly immutable for anonymous 304`() {
        assertNotModified(deliver(published = true, conditional = true), "public, max-age=31536000, immutable")
    }

    @Test
    fun `200 sends the verified bytes and does not reopen the subsequently changed path`() {
        assertDelivered(
            deliver(published = true, replacePathAfterVerification = true),
            "public, max-age=31536000, immutable",
        )
        assertFalse(Files.readAllBytes(mediaDirectory.resolve(metadata.storageFilename)).contentEquals(mediaBytes))
    }

    @ParameterizedTest
    @EnumSource(MediaStorageIssue::class, names = ["MISSING", "SIZE_MISMATCH", "CHECKSUM_MISMATCH"])
    fun `published matching ETag cannot bypass unavailable bytes`(issue: MediaStorageIssue) {
        assertConcealed(deliver(published = true, conditional = true, readIssue = issue))
    }

    @ParameterizedTest
    @EnumSource(MediaStorageIssue::class, names = ["MISSING", "SIZE_MISMATCH", "CHECKSUM_MISMATCH"])
    fun `ADMIN matching ETag cannot bypass unavailable draft bytes`(issue: MediaStorageIssue) {
        assertConcealed(deliver(published = false, role = Role.ADMIN, conditional = true, readIssue = issue))
    }

    private fun deliver(
        published: Boolean,
        role: Role? = null,
        conditional: Boolean = false,
        readIssue: MediaStorageIssue? = null,
        replacePathAfterVerification: Boolean = false,
    ): MockHttpServletResponse {
        // A known row and existing owned file keep denial cases from passing because an asset is missing.
        val selected = metadata.copy(published = published)
        `when`(repository.findMedia(mediaId)).thenReturn(selected)
        val path = mediaDirectory.resolve(metadata.storageFilename)
        assertTrue(Files.isRegularFile(path))
        assertArrayEquals(mediaBytes, Files.readAllBytes(path))
        `when`(storage.readVerified(selected)).thenAnswer {
            if (readIssue != null) {
                MediaReadResult.Unavailable(readIssue)
            } else {
                val verified = mediaBytes.copyOf()
                if (replacePathAfterVerification) Files.write(path, byteArrayOf(1, 2, 3))
                MediaReadResult.Verified(verified)
            }
        }
        val context = SecurityContextHolder.createEmptyContext()
        if (role != null) {
            val principal = AuthenticatedUser(userId, "fixture@example.test", role, Instant.EPOCH)
            context.authentication = UsernamePasswordAuthenticationToken(
                principal,
                null,
                listOf(SimpleGrantedAuthority("ROLE_${role.name}")),
            )
        }
        SecurityContextHolder.setContext(context)
        return try {
            val response = mockMvc.get("/api/v1/tutorial-media/$mediaId") {
                if (conditional) header(HttpHeaders.IF_NONE_MATCH, mediaEtag)
            }.andReturn().response
            verify(repository).findMedia(mediaId)
            verifyNoMoreInteractions(repository)
            if (published || role == Role.ADMIN) {
                verify(storage).readVerified(selected)
                verifyNoMoreInteractions(storage)
            } else {
                verifyNoInteractions(storage)
            }
            verifyNoInteractions(tutorials, audit)
            response
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun assertDelivered(response: MockHttpServletResponse, cacheControl: String) {
        assertEquals(200, response.status)
        assertMetadata(response, cacheControl)
        assertEquals(MediaType.IMAGE_PNG_VALUE, response.contentType)
        assertEquals(mediaBytes.size.toString(), response.getHeader(HttpHeaders.CONTENT_LENGTH))
        assertArrayEquals(mediaBytes, response.contentAsByteArray)
    }

    private fun assertNotModified(response: MockHttpServletResponse, cacheControl: String) {
        assertEquals(304, response.status)
        assertMetadata(response, cacheControl)
        assertArrayEquals(byteArrayOf(), response.contentAsByteArray)
        assertNull(response.contentType)
        assertFalse(response.containsHeader(HttpHeaders.CONTENT_LENGTH))
    }

    private fun assertMetadata(response: MockHttpServletResponse, cacheControl: String) {
        assertEquals(listOf(cacheControl), response.getHeaders(HttpHeaders.CACHE_CONTROL).toList())
        assertEquals(listOf(mediaEtag), response.getHeaders(HttpHeaders.ETAG).toList())
    }

    private fun assertConcealed(response: MockHttpServletResponse) {
        assertEquals(404, response.status)
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.contentType)
        assertFalse(response.containsHeader(HttpHeaders.ETAG))
        assertFalse(response.contentAsByteArray.contentEquals(mediaBytes))
        assertEquals(
            objectMapper.readTree(
                """
                {
                    "type": "about:blank",
                    "title": "Not Found",
                    "status": 404,
                    "detail": "tutorial media not found.",
                    "errors": [{"code": "TUTORIAL_MEDIA_NOT_FOUND", "message": "tutorial media not found."}]
                }
                """.trimIndent(),
            ),
            objectMapper.readTree(response.contentAsByteArray),
        )
    }
}
