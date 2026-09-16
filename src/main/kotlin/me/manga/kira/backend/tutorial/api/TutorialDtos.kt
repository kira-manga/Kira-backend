package me.manga.kira.backend.tutorial.api

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import me.manga.kira.backend.tutorial.application.AdminCategoryView
import me.manga.kira.backend.tutorial.application.AdminTutorialView
import me.manga.kira.backend.tutorial.application.CategoryRevisionView
import me.manga.kira.backend.tutorial.application.PublicCategoryView
import me.manga.kira.backend.tutorial.application.PublicMediaAsset
import me.manga.kira.backend.tutorial.application.PublicMediaSlot
import me.manga.kira.backend.tutorial.application.PublicTutorialView
import me.manga.kira.backend.tutorial.application.ReorderItem
import me.manga.kira.backend.tutorial.application.TutorialRevisionView
import me.manga.kira.backend.tutorial.domain.CategoryContent
import me.manga.kira.backend.tutorial.domain.LocalizedText
import me.manga.kira.backend.tutorial.domain.MediaSlot
import me.manga.kira.backend.tutorial.domain.MediaVariants
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialContent
import me.manga.kira.backend.tutorial.domain.TutorialStep
import java.time.Instant
import java.util.UUID

data class LocalizedTextDto(@field:NotBlank val en: String, @field:NotBlank val ar: String) {
    fun domain() = LocalizedText(en, ar)
    companion object {
        fun of(value: LocalizedText) = LocalizedTextDto(value.en, value.ar)
    }
}

data class CreateIdentityRequest(
    @field:NotBlank val slug: String,
    @field:PositiveOrZero val position: Int? = null,
    @field:PositiveOrZero val featuredPosition: Int? = null,
)

data class CategoryRevisionRequest(@field:Valid val label: LocalizedTextDto, @field:NotBlank val iconCode: String) {
    fun domain() = CategoryContent(label.domain(), iconCode)
}

data class MediaVariantsDto(val enLight: UUID? = null, val enDark: UUID? = null, val arLight: UUID? = null, val arDark: UUID? = null) {
    fun domain() = MediaVariants(enLight, enDark, arLight, arDark)
    companion object {
        fun of(value: MediaVariants) = MediaVariantsDto(value.enLight, value.enDark, value.arLight, value.arDark)
    }
}

data class MediaSlotRequest(
    val defaultMediaId: UUID,
    @field:Valid val alt: LocalizedTextDto,
    @field:Valid val variants: MediaVariantsDto = MediaVariantsDto(),
) {
    fun domain() = MediaSlot(defaultMediaId, alt.domain(), variants.domain())
}

data class TutorialStepRequest(
    @field:NotBlank val id: String,
    @field:Valid val title: LocalizedTextDto,
    @field:Valid val body: LocalizedTextDto,
    @field:Valid val tip: LocalizedTextDto? = null,
    @field:Valid val media: MediaSlotRequest? = null,
) {
    fun domain() = TutorialStep(id, title.domain(), body.domain(), tip?.domain(), media?.domain())
}

data class TutorialRevisionRequest(
    val categoryId: UUID,
    @field:Valid val title: LocalizedTextDto,
    @field:Valid val summary: LocalizedTextDto,
    @field:Valid val introduction: LocalizedTextDto,
    @field:Valid val duration: LocalizedTextDto,
    @field:Valid val level: LocalizedTextDto,
    @field:Valid val cover: MediaSlotRequest,
    @field:Valid val steps: List<TutorialStepRequest>,
) {
    fun domain() = TutorialContent(
        title.domain(),
        summary.domain(),
        introduction.domain(),
        duration.domain(),
        level.domain(),
        cover.domain(),
        steps.map { it.domain() },
    )
}

data class ReorderRequest(@field:Valid val items: List<ReorderItemRequest>)
data class ReorderItemRequest(val id: UUID, @field:PositiveOrZero val position: Int, @field:PositiveOrZero val featuredPosition: Int? = null) {
    fun domain() = ReorderItem(id, position, featuredPosition)
}

data class AdminCategoryResponse(
    val id: UUID,
    val slug: String,
    val status: String,
    val position: Int,
    val publishedRevision: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: AdminCategoryView) = AdminCategoryResponse(
            view.category.id,
            view.category.slug,
            view.category.status.name,
            view.category.position,
            view.published?.revision?.revisionNumber,
            view.category.createdAt,
            view.category.updatedAt,
        )
    }
}

data class CategoryRevisionResponse(
    val id: UUID,
    val revision: Int,
    val label: LocalizedTextDto,
    val iconCode: String,
    val createdBy: UUID?,
    val createdAt: Instant,
) {
    companion object {
        fun of(view: CategoryRevisionView) = CategoryRevisionResponse(
            view.revision.id,
            view.revision.revisionNumber,
            LocalizedTextDto.of(view.content.label),
            view.content.iconCode,
            view.revision.createdBy,
            view.revision.createdAt,
        )
    }
}

data class AdminTutorialResponse(
    val id: UUID,
    val slug: String,
    val status: String,
    val position: Int,
    val featuredPosition: Int?,
    val publishedRevision: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: AdminTutorialView) = AdminTutorialResponse(
            view.tutorial.id,
            view.tutorial.slug,
            view.tutorial.status.name,
            view.tutorial.position,
            view.tutorial.featuredPosition,
            view.published?.revision?.revisionNumber,
            view.tutorial.createdAt,
            view.tutorial.updatedAt,
        )
    }
}

data class TutorialRevisionResponse(
    val id: UUID,
    val revision: Int,
    val categoryId: UUID,
    val title: LocalizedTextDto,
    val summary: LocalizedTextDto,
    val introduction: LocalizedTextDto,
    val duration: LocalizedTextDto,
    val level: LocalizedTextDto,
    val cover: MediaSlotResponse,
    val steps: List<TutorialStepResponse>,
    val createdBy: UUID?,
    val createdAt: Instant,
) {
    companion object {
        fun of(view: TutorialRevisionView) = TutorialRevisionResponse(
            view.revision.id, view.revision.revisionNumber, requireNotNull(view.revision.categoryId),
            LocalizedTextDto.of(view.content.title), LocalizedTextDto.of(view.content.summary), LocalizedTextDto.of(view.content.introduction),
            LocalizedTextDto.of(view.content.duration), LocalizedTextDto.of(view.content.level), MediaSlotResponse.of(view.content.cover),
            view.content.steps.map(TutorialStepResponse::of), view.revision.createdBy, view.revision.createdAt,
        )
    }
}

data class MediaSlotResponse(val defaultMediaId: UUID, val alt: LocalizedTextDto, val variants: MediaVariantsDto) {
    companion object {
        fun of(value: MediaSlot) = MediaSlotResponse(value.defaultMediaId, LocalizedTextDto.of(value.alt), MediaVariantsDto.of(value.variants))
    }
}

data class TutorialStepResponse(
    val id: String,
    val title: LocalizedTextDto,
    val body: LocalizedTextDto,
    val tip: LocalizedTextDto?,
    val media: MediaSlotResponse?,
) {
    companion object {
        fun of(value: TutorialStep) = TutorialStepResponse(
            value.id,
            LocalizedTextDto.of(value.title),
            LocalizedTextDto.of(value.body),
            value.tip?.let(LocalizedTextDto::of),
            value.media?.let(MediaSlotResponse::of),
        )
    }
}

data class PublicCategoryResponse(val id: UUID, val slug: String, val label: LocalizedTextDto, val iconCode: String, val position: Int, val revision: Int) {
    companion object {
        fun of(value: PublicCategoryView) =
            PublicCategoryResponse(value.id, value.slug, LocalizedTextDto.of(value.label), value.iconCode, value.position, value.revision)
    }
}

data class PublicMediaAssetResponse(val id: UUID, val url: String, val contentType: String, val width: Int, val height: Int, val sha256: String) {
    companion object {
        fun of(value: PublicMediaAsset) = PublicMediaAssetResponse(value.id, value.url, value.contentType, value.width, value.height, value.sha256)
    }
}

data class PublicMediaVariantsResponse(
    val enLight: PublicMediaAssetResponse?,
    val enDark: PublicMediaAssetResponse?,
    val arLight: PublicMediaAssetResponse?,
    val arDark: PublicMediaAssetResponse?,
)

data class PublicMediaSlotResponse(val default: PublicMediaAssetResponse, val alt: LocalizedTextDto, val variants: PublicMediaVariantsResponse) {
    companion object {
        fun of(value: PublicMediaSlot) = PublicMediaSlotResponse(
            PublicMediaAssetResponse.of(value.default),
            LocalizedTextDto.of(value.alt),
            PublicMediaVariantsResponse(
                value.variants.enLight?.let(PublicMediaAssetResponse::of),
                value.variants.enDark?.let(PublicMediaAssetResponse::of),
                value.variants.arLight?.let(PublicMediaAssetResponse::of),
                value.variants.arDark?.let(PublicMediaAssetResponse::of),
            ),
        )
    }
}

data class PublicTutorialStepResponse(
    val id: String,
    val title: LocalizedTextDto,
    val body: LocalizedTextDto,
    val tip: LocalizedTextDto?,
    val media: PublicMediaSlotResponse?,
)

data class PublicTutorialResponse(
    val id: UUID,
    val slug: String,
    val category: PublicCategoryResponse,
    val title: LocalizedTextDto,
    val summary: LocalizedTextDto,
    val introduction: LocalizedTextDto,
    val duration: LocalizedTextDto,
    val level: LocalizedTextDto,
    val cover: PublicMediaSlotResponse,
    val steps: List<PublicTutorialStepResponse>,
    val position: Int,
    val featuredPosition: Int?,
    val revision: Int,
) {
    companion object {
        fun of(value: PublicTutorialView) = PublicTutorialResponse(
            value.id, value.slug, PublicCategoryResponse.of(value.category), LocalizedTextDto.of(value.title), LocalizedTextDto.of(value.summary),
            LocalizedTextDto.of(value.introduction), LocalizedTextDto.of(value.duration), LocalizedTextDto.of(value.level),
            PublicMediaSlotResponse.of(value.cover),
            value.steps.map {
                PublicTutorialStepResponse(
                    it.id,
                    LocalizedTextDto.of(it.title),
                    LocalizedTextDto.of(it.body),
                    it.tip?.let(LocalizedTextDto::of),
                    it.media?.let(PublicMediaSlotResponse::of),
                )
            },
            value.position, value.featuredPosition, value.revision,
        )
    }
}

data class TutorialMediaResponse(
    val id: UUID,
    val contentType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val sha256: String,
    val published: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun of(value: StoredMedia) =
            TutorialMediaResponse(value.id, value.contentType, value.byteSize, value.width, value.height, value.sha256, value.published, value.createdAt)
    }
}
