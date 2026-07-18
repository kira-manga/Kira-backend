package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.completion.api.CompletionController
import me.manga.kira.backend.completion.api.dto.CompletionRequestDto
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.user.domain.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.time.Instant
import java.util.UUID

class CompletionControllerTest {
    @Test
    fun `model over 128 characters is a 400 boundary error before service invocation`() {
        val service = mock(CompletionService::class.java)
        val controller = CompletionController(service, KiraCompletionProperties())
        val user = AuthenticatedUser(UUID.randomUUID(), "reader@example.com", Role.USER, Instant.EPOCH)

        val error =
            assertThrows(BadRequestException::class.java) {
                controller.create(CompletionRequestDto(prompt = "hello", model = "m".repeat(129)), user)
            }

        assertEquals("MODEL_TOO_LONG", error.code)
        assertEquals(400, error.status.value())
        verifyNoInteractions(service)
    }
}
