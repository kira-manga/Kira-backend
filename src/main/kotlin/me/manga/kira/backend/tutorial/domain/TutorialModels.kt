package me.manga.kira.backend.tutorial.domain

import java.time.Instant
import java.util.UUID

enum class TutorialLifecycle { DRAFT, PUBLISHED, ARCHIVED }

data class LocalizedText(val en: String, val ar: String)

data class CategoryContent(val label: LocalizedText, val iconCode: String)

data class MediaVariants(val enLight: UUID? = null, val enDark: UUID? = null, val arLight: UUID? = null, val arDark: UUID? = null)

data class MediaSlot(val defaultMediaId: UUID, val alt: LocalizedText, val variants: MediaVariants = MediaVariants()) {
    fun references(): Map<String, UUID> = buildMap {
        put("default", defaultMediaId)
        variants.enLight?.let { put("en-light", it) }
        variants.enDark?.let { put("en-dark", it) }
        variants.arLight?.let { put("ar-light", it) }
        variants.arDark?.let { put("ar-dark", it) }
    }
}

data class TutorialStep(val id: String, val title: LocalizedText, val body: LocalizedText, val tip: LocalizedText? = null, val media: MediaSlot? = null)

data class TutorialContent(
    val title: LocalizedText,
    val summary: LocalizedText,
    val introduction: LocalizedText,
    val duration: LocalizedText,
    val level: LocalizedText,
    val cover: MediaSlot,
    val steps: List<TutorialStep>,
) {
    fun mediaReferences(): Map<String, UUID> = buildMap {
        cover.references().forEach { (variant, id) -> put("cover.$variant", id) }
        steps.forEach { step ->
            step.media?.references()?.forEach { (variant, id) -> put("step.${step.id}.$variant", id) }
        }
    }
}

data class StoredCategory(
    val id: UUID,
    val slug: String,
    val status: TutorialLifecycle,
    val position: Int,
    val publishedRevisionId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StoredTutorial(
    val id: UUID,
    val slug: String,
    val status: TutorialLifecycle,
    val position: Int,
    val featuredPosition: Int?,
    val publishedRevisionId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StoredRevision(
    val id: UUID,
    val ownerId: UUID,
    val revisionNumber: Int,
    val categoryId: UUID?,
    val contentJson: String,
    val createdBy: UUID?,
    val createdAt: Instant,
)

data class StoredMedia(
    val id: UUID,
    val storageFilename: String,
    val contentType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
    val published: Boolean,
    val createdBy: UUID?,
    val createdAt: Instant,
)
