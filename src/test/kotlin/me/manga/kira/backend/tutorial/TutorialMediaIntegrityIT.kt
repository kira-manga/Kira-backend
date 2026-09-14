package me.manga.kira.backend.tutorial

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.tutorial.api.PublicTutorialController
import me.manga.kira.backend.tutorial.application.TutorialMediaReconciliationService
import me.manga.kira.backend.tutorial.application.TutorialStartupValidator
import me.manga.kira.backend.tutorial.domain.MediaStorageIssue
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** Real Boot web context, production security chain, signed JWT decoder and owned PostgreSQL/media. */
class TutorialMediaIntegrityIT : TutorialMediaIntegrationSupport() {
    @Test
    fun `actual chain serves exact published bytes and only healthy matching validators return immutable 304`() = withMediaApplication { app ->
        val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(), published = true))
        val bytes = Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename))
        assertEquals(asset.sha256, Sha256.hex(bytes))
        val mvc = realChain(app)

        val delivered = request(mvc, asset.id)
        val revalidated = request(mvc, asset.id, etag = etag(asset))

        assertDelivered(delivered, asset, bytes, PublicTutorialController.IMMUTABLE_CACHE)
        assertNotModified(revalidated, asset, PublicTutorialController.IMMUTABLE_CACHE)
        assertArrayEquals(bytes, Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename)))
    }

    @ParameterizedTest
    @EnumSource(ContentFault::class)
    fun `real chain rejects broken published and ADMIN draft bytes with noncacheable 404 even for matching ETag`(fault: ContentFault) =
        withMediaApplication { app ->
            val mvc = realChain(app)
            val admin = bearer(app, Role.ADMIN)
            val published = app.persistedMedia(app.media.importSeedAsset(mediaPng(0xff223344.toInt()), published = true))
            val draft = app.persistedMedia(app.media.importSeedAsset(mediaPng(0xff334455.toInt()), published = false))
            val beforeRows = app.repository.listMedia()
            listOf(published, draft).forEach { asset ->
                val token = if (asset.published) null else admin
                val bytes = Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename))
                assertEquals(200, request(mvc, asset.id, token).status, "healthy control proves the authorized path before corruption")
                corrupt(asset, bytes, fault)

                listOf(null, etag(asset)).forEach { validator ->
                    assertUnavailable(app, request(mvc, asset.id, token, validator), asset)
                }
            }

            assertEquals(beforeRows, app.repository.listMedia(), "delivery never repairs metadata or removes rows")
            assertEquals(2, app.auditCount("TUTORIAL_MEDIA_UPLOADED"))
            assertFalse(Files.exists(mediaDirectory.resolve("quarantine"), NOFOLLOW_LINKS))
        }

    @Test
    fun `real DB role keeps healthy drafts concealed while ADMIN 200 and 304 stay private no-store`() = withMediaApplication { app ->
        val mvc = realChain(app)
        val user = bearer(app, Role.USER, diagnosticRole = "ADMIN")
        val admin = bearer(app, Role.ADMIN)
        val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(), published = false))
        val bytes = Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename))

        val anonymous = request(mvc, asset.id, etag = etag(asset))
        val deniedUser = request(mvc, asset.id, user, etag(asset))
        val absent = request(mvc, UUID.randomUUID(), etag = etag(asset))

        listOf(anonymous, deniedUser, absent).forEach { assertUnavailable(app, it, asset) }
        assertEquals(anonymous.contentAsString, deniedUser.contentAsString)
        assertEquals(anonymous.contentAsString, absent.contentAsString)
        assertDelivered(request(mvc, asset.id, admin), asset, bytes, "private, no-store")
        assertNotModified(request(mvc, asset.id, admin, etag(asset)), asset, "private, no-store")
        assertArrayEquals(bytes, Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename)))
    }

    @Test
    fun `report-only inspection classifies every draft and published failure without changing rows or files`() = withMediaApplication { app ->
        val expected = mutableMapOf<UUID, Pair<MediaStorageIssue, Boolean>>()
        var color = 1
        listOf(false, true).forEach { published ->
            ContentFault.entries.forEach { fault ->
                val asset = app.persistedMedia(app.media.importSeedAsset(mediaPng(0xff000000.toInt() or color++), published))
                val bytes = Files.readAllBytes(mediaDirectory.resolve(asset.storageFilename))
                corrupt(asset, bytes, fault)
                expected[asset.id] = fault.issue to published
            }
            app.media.importSeedAsset(mediaPng(0xff000000.toInt() or color++), published)
        }
        val beforeRows = app.repository.listMedia()
        val beforeFiles = fileChecksums()
        val reconciliation = app.context.getBean(TutorialMediaReconciliationService::class.java)

        val report = reconciliation.inspect()

        assertEquals(8, report.rowsChecked)
        assertEquals(4, report.publishedRowsChecked)
        assertTrue(report.complete, "known content failures can still have a complete diagnostic traversal")
        assertFalse(report.publishedComplete)
        assertFalse(report.clean)
        assertEquals(expected, report.findings.associate { requireNotNull(it.mediaId) to (it.issue to requireNotNull(it.published)) })
        assertEquals(0, report.quarantinedFiles)
        assertEquals(beforeRows, app.repository.listMedia())
        assertEquals(beforeFiles, fileChecksums())
        assertFalse(Files.exists(mediaDirectory.resolve("quarantine"), NOFOLLOW_LINKS))
    }

    @Test
    fun `disabled mutation and enabled inspection both retain draft warnings and unknown extras at startup`() = withMediaApplication { app ->
        val published = app.persistedMedia(app.media.importSeedAsset(mediaPng(0xff112233.toInt()), published = true))
        val draft = app.persistedMedia(app.media.importSeedAsset(mediaPng(0xff223344.toInt()), published = false))
        val bytes = Files.readAllBytes(mediaDirectory.resolve(draft.storageFilename))
        corrupt(draft, bytes, ContentFault.SAME_SIZE)
        Files.write(mediaDirectory.resolve("${UUID.randomUUID()}.png"), byteArrayOf(1, 2, 3))
        Files.write(mediaDirectory.resolve("owner-notes"), byteArrayOf(4, 5, 6))
        val beforeRows = app.repository.listMedia()
        val beforeFiles = fileChecksums()
        val disabled = app.context.getBean(TutorialMediaReconciliationService::class.java)
        val enabled = configuredReconciliation(app, enabled = true)

        assertThrows(IllegalStateException::class.java) { disabled.reconcile() }
        val report = enabled.inspect()

        assertTrue(report.publishedComplete, "all published rows were still verified despite bounded draft/extra diagnostics")
        assertFalse(report.complete)
        assertFalse(report.clean)
        assertTrue(report.findings.any { it.mediaId == draft.id && it.issue == MediaStorageIssue.CHECKSUM_MISMATCH })
        assertTrue(report.findings.any { it.issue == MediaStorageIssue.UNKNOWN_ENTRY })
        assertTrue(report.findings.any { it.issue == MediaStorageIssue.ORPHAN })
        assertDoesNotThrow { TutorialStartupValidator(app.repository, enabled).run(DefaultApplicationArguments()) }
        val refusedSweep = enabled.reconcile()
        assertFalse(refusedSweep.complete)
        assertEquals(0, refusedSweep.quarantinedFiles, "incomplete candidate inventory must not authorize sweeping even known orphans")
        assertEquals(beforeRows, app.repository.listMedia())
        assertEquals(beforeFiles, fileChecksums())
        assertEquals(published, app.repository.findMedia(published.id))
        assertFalse(Files.exists(mediaDirectory.resolve("quarantine"), NOFOLLOW_LINKS))
    }

    @Test
    fun `startup refuses an incomplete published row scan and no prefix can authorize orphan cleanup`() = withMediaApplication { app ->
        app.media.importSeedAsset(mediaPng(0xff112233.toInt()), published = true)
        app.media.importSeedAsset(mediaPng(0xff223344.toInt()), published = true)
        Files.write(mediaDirectory.resolve("${UUID.randomUUID()}.png"), byteArrayOf(1, 2, 3))
        val beforeRows = app.repository.listMedia()
        val beforeFiles = fileChecksums()
        val bounded = configuredReconciliation(app, enabled = true, limit = 1)

        val report = bounded.inspect()
        val failure = assertThrows(IllegalStateException::class.java) {
            // Exercise the production ApplicationRunner gate with an actual bounded DB/filesystem scan.
            TutorialStartupValidator(app.repository, bounded).run(DefaultApplicationArguments())
        }

        assertEquals(1, report.rowsChecked)
        assertFalse(report.publishedComplete)
        assertFalse(report.complete)
        assertTrue(report.findings.any { it.issue == MediaStorageIssue.ROW_LIMIT })
        assertTrue(failure.message.orEmpty().contains("incomplete"))
        assertEquals(0, bounded.reconcile().quarantinedFiles)
        assertEquals(beforeRows, app.repository.listMedia())
        assertEquals(beforeFiles, fileChecksums())
        assertFalse(Files.exists(mediaDirectory.resolve("quarantine"), NOFOLLOW_LINKS))
    }

    private fun realChain(app: MediaTestApplication): MockMvc {
        val web = assertInstanceOf(ServletWebServerApplicationContext::class.java, app.context)
        return MockMvcBuilders.webAppContextSetup(web).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    private fun bearer(app: MediaTestApplication, role: Role, diagnosticRole: String = role.name): String {
        val users = app.context.getBean(UserRepository::class.java)
        val passwords = app.context.getBean(PasswordEncoder::class.java)
        val user = requireNotNull(app.transaction().execute { users.create("${UUID.randomUUID()}@example.test", passwords.encode("media-fixture-only"), role) })
        return JwtTestSupport.mint(user.id, user.email, diagnosticRole, credentialVersion = user.credentialVersion.toString())
    }

    private fun request(mvc: MockMvc, id: UUID, bearer: String? = null, etag: String? = null): MockHttpServletResponse = mvc.get("/api/v1/tutorial-media/$id") {
        if (bearer != null) header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
        if (etag != null) header(HttpHeaders.IF_NONE_MATCH, etag)
    }.andReturn().response

    private fun assertUnavailable(app: MediaTestApplication, response: MockHttpServletResponse, asset: StoredMedia) {
        assertEquals(404, response.status)
        assertTrue(response.getHeader(HttpHeaders.CACHE_CONTROL).orEmpty().split(',').map(String::trim).contains("no-store"))
        assertNull(response.getHeader(HttpHeaders.ETAG))
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"), "actual security headers must be present")
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(requireNotNull(response.contentType))))
        val body = app.context.getBean(ObjectMapper::class.java).readTree(response.contentAsString)
        assertEquals(404, body.path("status").asInt())
        assertEquals("tutorial media not found.", body.path("detail").asText())
        assertEquals("TUTORIAL_MEDIA_NOT_FOUND", body.path("errors").get(0).path("code").asText())
        assertFalse(response.contentAsString.contains(asset.sha256))
        assertFalse(response.contentAsString.contains(asset.storageFilename))
    }

    private fun assertDelivered(response: MockHttpServletResponse, asset: StoredMedia, bytes: ByteArray, cache: String) {
        assertEquals(200, response.status)
        assertEquals(cache, response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals(etag(asset), response.getHeader(HttpHeaders.ETAG))
        assertEquals(asset.contentType, response.contentType)
        assertEquals(asset.byteSize, response.getHeader(HttpHeaders.CONTENT_LENGTH)?.toLong())
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertArrayEquals(bytes, response.contentAsByteArray)
        assertEquals(asset.sha256, Sha256.hex(response.contentAsByteArray))
    }

    private fun assertNotModified(response: MockHttpServletResponse, asset: StoredMedia, cache: String) {
        assertEquals(304, response.status)
        assertEquals(cache, response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals(etag(asset), response.getHeader(HttpHeaders.ETAG))
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertTrue(response.contentAsByteArray.isEmpty())
    }

    private fun corrupt(asset: StoredMedia, bytes: ByteArray, fault: ContentFault) {
        val path = mediaDirectory.resolve(asset.storageFilename)
        when (fault) {
            ContentFault.MISSING -> Files.delete(path)
            ContentFault.TRUNCATED -> Files.write(path, bytes.copyOf(bytes.size - 1))
            ContentFault.SAME_SIZE -> Files.write(path, ByteArray(bytes.size))
        }
    }

    private fun configuredReconciliation(app: MediaTestApplication, enabled: Boolean, limit: Int = app.properties.mediaInspectionLimit) = app.proxy(
        TutorialMediaReconciliationService(
            app.repository,
            app.storage,
            app.properties.copy(mediaReconciliationEnabled = enabled, mediaInspectionLimit = limit),
        ),
        TutorialMediaReconciliationService::class.java,
    )

    private fun fileChecksums(): Map<Path, String> = Files.list(mediaDirectory).use { entries ->
        entries.toList().associateWith { Sha256.hex(Files.readAllBytes(it)) }
    }

    private fun etag(asset: StoredMedia): String = "\"${asset.sha256}\""

    enum class ContentFault(val issue: MediaStorageIssue) {
        MISSING(MediaStorageIssue.MISSING),
        TRUNCATED(MediaStorageIssue.SIZE_MISMATCH),
        SAME_SIZE(MediaStorageIssue.CHECKSUM_MISMATCH),
    }
}
