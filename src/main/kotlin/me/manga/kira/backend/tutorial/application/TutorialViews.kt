package me.manga.kira.backend.tutorial.application

import me.manga.kira.backend.tutorial.domain.CategoryContent
import me.manga.kira.backend.tutorial.domain.LocalizedText
import me.manga.kira.backend.tutorial.domain.StoredCategory
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.StoredRevision
import me.manga.kira.backend.tutorial.domain.StoredTutorial
import me.manga.kira.backend.tutorial.domain.TutorialContent
import java.util.UUID

data class CategoryRevisionView(val revision: StoredRevision, val content: CategoryContent)
data class TutorialRevisionView(val revision: StoredRevision, val content: TutorialContent)
data class AdminCategoryView(val category: StoredCategory, val published: CategoryRevisionView?)
data class AdminTutorialView(val tutorial: StoredTutorial, val published: TutorialRevisionView?)

data class PublicMediaAsset(val id: UUID, val url: String, val contentType: String, val width: Int, val height: Int, val sha256: String)

data class PublicMediaVariants(
    val enLight: PublicMediaAsset? = null,
    val enDark: PublicMediaAsset? = null,
    val arLight: PublicMediaAsset? = null,
    val arDark: PublicMediaAsset? = null,
)

data class PublicMediaSlot(val default: PublicMediaAsset, val alt: LocalizedText, val variants: PublicMediaVariants)

data class PublicTutorialStep(val id: String, val title: LocalizedText, val body: LocalizedText, val tip: LocalizedText?, val media: PublicMediaSlot?)

data class PublicCategoryView(val id: UUID, val slug: String, val label: LocalizedText, val iconCode: String, val position: Int, val revision: Int)

data class PublicTutorialView(
    val id: UUID,
    val slug: String,
    val category: PublicCategoryView,
    val title: LocalizedText,
    val summary: LocalizedText,
    val introduction: LocalizedText,
    val duration: LocalizedText,
    val level: LocalizedText,
    val cover: PublicMediaSlot,
    val steps: List<PublicTutorialStep>,
    val position: Int,
    val featuredPosition: Int?,
    val revision: Int,
)

data class ReorderItem(val id: UUID, val position: Int, val featuredPosition: Int? = null)

internal fun StoredMedia.toPublic(baseUrl: String?): PublicMediaAsset = PublicMediaAsset(
    id = id,
    url = (baseUrl?.trimEnd('/') ?: "") + "/api/v1/tutorial-media/$id",
    contentType = contentType,
    width = width,
    height = height,
    sha256 = sha256,
)
