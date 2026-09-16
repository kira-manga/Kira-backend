package me.manga.kira.backend.tutorial.application

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.tutorial.domain.CategoryContent
import me.manga.kira.backend.tutorial.domain.LocalizedText
import me.manga.kira.backend.tutorial.domain.MediaSlot
import me.manga.kira.backend.tutorial.domain.MediaVariants
import me.manga.kira.backend.tutorial.domain.TutorialContent
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import me.manga.kira.backend.tutorial.domain.TutorialStep
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files

@Service
class TutorialSeedService(
    private val repository: TutorialRepository,
    private val tutorials: TutorialService,
    private val media: TutorialMediaService,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun seedIfEmpty() {
        val assets = mapOf(
            "discover-en-light" to import("tutorial-seed/media/discover-en-light.jpg"),
            "discover-en-dark" to import("tutorial-seed/media/discover-en-dark.jpg"),
            "discover-ar-light" to import("tutorial-seed/media/discover-ar-light.jpg"),
            "discover-ar-dark" to import("tutorial-seed/media/discover-ar-dark.jpg"),
            "mangaDetails" to import("tutorial-seed/media/manga-details.jpg"),
            "settings" to import("tutorial-seed/media/settings-dark.jpg"),
        )
        if (repository.categoryCount() != 0 || repository.tutorialCount() != 0) return

        val discover = MediaSlot(
            defaultMediaId = requireNotNull(assets["discover-en-dark"]),
            alt = LocalizedText("The real Kira Discover screen", "شاشة «اكتشف» الحقيقية في كيرا"),
            variants = MediaVariants(
                enLight = assets["discover-en-light"],
                enDark = assets["discover-en-dark"],
                arLight = assets["discover-ar-light"],
                arDark = assets["discover-ar-dark"],
            ),
        )
        val slots = mapOf(
            "discover" to discover,
            "mangaDetails" to MediaSlot(
                requireNotNull(assets["mangaDetails"]),
                LocalizedText("The real Kira manga details screen", "شاشة تفاصيل المانجا الحقيقية في كيرا"),
            ),
            "settings" to MediaSlot(
                requireNotNull(assets["settings"]),
                LocalizedText("The real Kira Settings screen", "شاشة إعدادات كيرا الحقيقية"),
            ),
        )
        val categories = listOf(
            Triple("basics", LocalizedText("Getting started", "البدء"), "book"),
            Triple("discovery", LocalizedText("Discovery", "الاكتشاف"), "search"),
            Triple("reading", LocalizedText("Reading", "القراءة"), "download"),
            Triple("settings", LocalizedText("Settings", "الإعدادات"), "settings"),
        ).mapIndexed { index, (slug, label, icon) ->
            val category = tutorials.createCategory(slug, index)
            val revision = tutorials.createCategoryRevision(category.category.id, CategoryContent(label, icon))
            tutorials.publishCategory(category.category.id, revision.revision.revisionNumber)
            slug to category.category.id
        }.toMap()

        val seedTutorials: List<SeedTutorial> = ClassPathResource("tutorial-seed/tutorials.json").inputStream.use {
            objectMapper.readValue(it, object : TypeReference<List<SeedTutorial>>() {})
        }
        seedTutorials.forEachIndexed { index, seed ->
            val tutorial = tutorials.createTutorial(seed.slug, index, index)
            val content = TutorialContent(
                seed.title,
                seed.summary,
                seed.intro,
                seed.duration,
                seed.level,
                requireNotNull(slots[seed.cover]) { "unknown tutorial seed media slot '${seed.cover}'" },
                seed.steps.map { step ->
                    TutorialStep(
                        step.id,
                        step.title,
                        step.body,
                        step.tip,
                        step.media?.let { requireNotNull(slots[it]) { "unknown tutorial seed media slot '$it'" } },
                    )
                },
            )
            val revision = tutorials.createTutorialRevision(
                tutorial.tutorial.id,
                requireNotNull(categories[seed.category]),
                content,
            )
            tutorials.publishTutorial(tutorial.tutorial.id, revision.revision.revisionNumber)
        }
    }

    private fun import(resource: String) = ClassPathResource(resource).inputStream.use { media.importSeedAsset(it.readBytes()).id }

    private data class SeedTutorial(
        val slug: String,
        val category: String,
        val title: LocalizedText,
        val summary: LocalizedText,
        val intro: LocalizedText,
        val duration: LocalizedText,
        val level: LocalizedText,
        val cover: String,
        val steps: List<SeedStep>,
    )

    private data class SeedStep(val id: String, val title: LocalizedText, val body: LocalizedText, val tip: LocalizedText? = null, val media: String? = null)
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TutorialSeedRunner(private val properties: KiraTutorialProperties, private val seeder: TutorialSeedService) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (properties.seedEnabled) seeder.seedIfEmpty()
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TutorialStartupValidator(private val repository: TutorialRepository, private val properties: KiraTutorialProperties, private val jdbc: JdbcTemplate) :
    ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val root = properties.mediaDirectory.toAbsolutePath().normalize()
        repository.listMedia().filter { it.published }.forEach { media ->
            val path = root.resolve(media.storageFilename).normalize()
            check(path.parent == root && Files.isRegularFile(path)) { "published tutorial media file is missing: ${media.id}" }
        }
        val brokenPointers = jdbc.queryForObject(
            "SELECT " +
                "(SELECT count(*) FROM tutorial_categories WHERE status = 'PUBLISHED' AND published_revision_id IS NULL) + " +
                "(SELECT count(*) FROM tutorials WHERE status = 'PUBLISHED' AND published_revision_id IS NULL) + " +
                "(SELECT count(*) FROM tutorials t JOIN tutorial_revisions r ON r.id = t.published_revision_id " +
                " JOIN tutorial_categories c ON c.id = r.category_id WHERE t.status = 'PUBLISHED' AND c.status <> 'PUBLISHED') + " +
                "(SELECT count(*) FROM tutorials t JOIN tutorial_revision_media rm ON rm.revision_id = t.published_revision_id " +
                " JOIN tutorial_media m ON m.id = rm.media_id WHERE t.status = 'PUBLISHED' AND NOT m.published)",
            Int::class.java,
        ) ?: 0
        check(brokenPointers == 0) { "invalid published tutorial/category/media references detected" }
    }
}
