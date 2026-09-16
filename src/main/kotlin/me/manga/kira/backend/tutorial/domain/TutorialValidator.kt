package me.manga.kira.backend.tutorial.domain

import me.manga.kira.backend.common.ApiFieldError
import me.manga.kira.backend.common.exception.ValidationFailedException
import java.util.UUID

class TutorialValidator(private val maximumSteps: Int) {
    fun slug(value: String, path: String = "slug") {
        val errors = mutableListOf<ApiFieldError>()
        if (!SLUG.matches(value) || value.length > 96) errors += error(path, "INVALID_SLUG", "must be a lowercase kebab-case slug")
        fail(errors)
    }

    fun category(content: CategoryContent) {
        val errors = mutableListOf<ApiFieldError>()
        localized(content.label, "label", 120, errors)
        if (content.iconCode !in ICONS) errors += error("iconCode", "INVALID_ICON", "must be one of ${ICONS.joinToString()}")
        fail(errors)
    }

    fun tutorial(content: TutorialContent, existingMedia: Set<UUID>) {
        val errors = mutableListOf<ApiFieldError>()
        localized(content.title, "title", 160, errors)
        localized(content.summary, "summary", 400, errors)
        localized(content.introduction, "introduction", 1_200, errors)
        localized(content.duration, "duration", 32, errors)
        localized(content.level, "level", 64, errors)
        mediaSlot(content.cover, "cover", existingMedia, errors)
        if (content.steps.isEmpty() || content.steps.size > maximumSteps) {
            errors += error("steps", "INVALID_STEP_COUNT", "must contain between 1 and $maximumSteps steps")
        }
        val ids = mutableSetOf<String>()
        content.steps.forEachIndexed { index, step ->
            val path = "steps[$index]"
            if (!STEP_ID.matches(step.id) || step.id.length > 64) errors += error("$path.id", "INVALID_STEP_ID", "must be a lowercase kebab-case id")
            if (!ids.add(step.id)) errors += error("$path.id", "DUPLICATE_STEP_ID", "must be unique within the tutorial")
            localized(step.title, "$path.title", 160, errors)
            localized(step.body, "$path.body", 3_000, errors)
            step.tip?.let { localized(it, "$path.tip", 1_000, errors) }
            step.media?.let { mediaSlot(it, "$path.media", existingMedia, errors) }
        }
        fail(errors)
    }

    private fun mediaSlot(slot: MediaSlot, path: String, existingMedia: Set<UUID>, errors: MutableList<ApiFieldError>) {
        localized(slot.alt, "$path.alt", 200, errors)
        slot.references().forEach { (variant, id) ->
            if (id !in existingMedia) errors += error("$path.$variant", "UNKNOWN_MEDIA", "references an unknown media asset")
        }
    }

    private fun localized(value: LocalizedText, path: String, max: Int, errors: MutableList<ApiFieldError>) {
        text(value.en, "$path.en", max, errors)
        text(value.ar, "$path.ar", max, errors)
    }

    private fun text(value: String, path: String, max: Int, errors: MutableList<ApiFieldError>) {
        if (value.isBlank()) errors += error(path, "REQUIRED", "must not be blank")
        if (value.length > max) errors += error(path, "TOO_LONG", "must be at most $max characters")
        if (PLAIN_TEXT_FORBIDDEN.containsMatchIn(value)) {
            errors +=
                error(path, "RICH_TEXT_NOT_ALLOWED", "must be structured plain text without Markdown or HTML")
        }
    }

    private fun fail(errors: List<ApiFieldError>) {
        if (errors.isNotEmpty()) throw ValidationFailedException(errors)
    }

    private fun error(path: String, code: String, message: String) = ApiFieldError(code = code, path = path, message = message)

    companion object {
        private val SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val STEP_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val PLAIN_TEXT_FORBIDDEN = Regex("<[^>]*>|\\*\\*|__|```|\\[[^]]+]\\(|(?m)^\\s{0,3}#{1,6}\\s")
        val ICONS = setOf("book", "search", "download", "settings")
    }
}
