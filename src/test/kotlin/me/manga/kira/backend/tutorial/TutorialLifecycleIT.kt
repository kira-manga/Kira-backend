package me.manga.kira.backend.tutorial

import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.tutorial.application.TutorialMediaInUseException
import me.manga.kira.backend.tutorial.application.TutorialMediaService
import me.manga.kira.backend.tutorial.application.TutorialService
import me.manga.kira.backend.tutorial.domain.CategoryContent
import me.manga.kira.backend.tutorial.domain.LocalizedText
import me.manga.kira.backend.tutorial.domain.MediaSlot
import me.manga.kira.backend.tutorial.domain.TutorialContent
import me.manga.kira.backend.tutorial.domain.TutorialStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

class TutorialLifecycleIT : AbstractIntegrationTest() {
    @Autowired lateinit var tutorials: TutorialService

    @Autowired lateinit var media: TutorialMediaService

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var tutorialProperties: KiraTutorialProperties

    @Test
    fun `seed media import restores a missing file`() {
        val bytes = png()
        val asset = media.importSeedAsset(bytes)
        val path = tutorialProperties.mediaDirectory.resolve(asset.storageFilename)
        Files.delete(path)

        val restored = media.importSeedAsset(bytes)

        assertEquals(asset.id, restored.id)
        assertTrue(Files.isRegularFile(path))
    }

    @Test
    fun `publish rollback archive and immutable referenced media follow public contract`() {
        val text = LocalizedText("English", "العربية")
        val category = tutorials.createCategory("basics")
        val categoryRevision = tutorials.createCategoryRevision(category.category.id, CategoryContent(text, "book"))
        tutorials.publishCategory(category.category.id, categoryRevision.revision.revisionNumber)

        val asset = media.importSeedAsset(png())
        val tutorial = tutorials.createTutorial("dynamic-guide", featuredPosition = 0)
        val revision = tutorials.createTutorialRevision(
            tutorial.tutorial.id,
            category.category.id,
            TutorialContent(text, text, text, text, text, MediaSlot(asset.id, text), listOf(TutorialStep("first", text, text))),
        )

        mockMvc.get("/api/v1/tutorials/dynamic-guide").andExpect { status { isNotFound() } }
        tutorials.publishTutorial(tutorial.tutorial.id, revision.revision.revisionNumber)

        val response = mockMvc.get("/api/v1/tutorials/dynamic-guide").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.slug") { value("dynamic-guide") }
            header { string("Cache-Control", "public, max-age=60, stale-if-error=86400") }
            header { exists("ETag") }
        }.andReturn().response
        mockMvc.get("/api/v1/tutorials/dynamic-guide") { header("If-None-Match", requireNotNull(response.getHeader("ETag"))) }
            .andExpect { status { isNotModified() } }

        assertThrows(TutorialMediaInUseException::class.java) { media.delete(asset.id) }
        tutorials.rollbackTutorial(tutorial.tutorial.id, 1)
        tutorials.archiveTutorial(tutorial.tutorial.id)
        mockMvc.get("/api/v1/tutorials/dynamic-guide").andExpect { status { isNotFound() } }
        tutorials.restoreTutorial(tutorial.tutorial.id)
        mockMvc.get("/api/v1/tutorials/dynamic-guide").andExpect { status { isOk() } }
    }

    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", output)
        output.toByteArray()
    }
}
