package me.manga.kira.backend.tutorial

import me.manga.kira.backend.common.exception.ValidationFailedException
import me.manga.kira.backend.tutorial.domain.CategoryContent
import me.manga.kira.backend.tutorial.domain.LocalizedText
import me.manga.kira.backend.tutorial.domain.MediaSlot
import me.manga.kira.backend.tutorial.domain.TutorialContent
import me.manga.kira.backend.tutorial.domain.TutorialStep
import me.manga.kira.backend.tutorial.domain.TutorialValidator
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class TutorialValidatorTest {
    private val validator = TutorialValidator(3)
    private val text = LocalizedText("English", "العربية")
    private val media = UUID.randomUUID()

    @Test
    fun `accepts complete bilingual structured content`() {
        assertDoesNotThrow { validator.category(CategoryContent(text, "book")) }
        assertDoesNotThrow {
            validator.tutorial(
                TutorialContent(text, text, text, text, text, MediaSlot(media, text), listOf(TutorialStep("first-step", text, text))),
                setOf(media),
            )
        }
    }

    @Test
    fun `rejects missing translation rich text duplicate steps and unknown media`() {
        val invalid = TutorialContent(
            LocalizedText("**Markdown**", ""),
            text,
            text,
            text,
            text,
            MediaSlot(UUID.randomUUID(), text),
            listOf(TutorialStep("same", text, text), TutorialStep("same", text, text)),
        )
        assertThrows(ValidationFailedException::class.java) { validator.tutorial(invalid, setOf(media)) }
    }

    @Test
    fun `rejects invalid slugs and excessive steps`() {
        assertThrows(ValidationFailedException::class.java) { validator.slug("Not Valid") }
        val content = TutorialContent(
            text,
            text,
            text,
            text,
            text,
            MediaSlot(media, text),
            (1..4).map { TutorialStep("step-$it", text, text) },
        )
        assertThrows(ValidationFailedException::class.java) { validator.tutorial(content, setOf(media)) }
    }
}
