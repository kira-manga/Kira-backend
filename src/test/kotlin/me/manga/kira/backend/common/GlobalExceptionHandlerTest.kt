package me.manga.kira.backend.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

class GlobalExceptionHandlerTest {
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(ExceptionTestController())
                .setControllerAdvice(GlobalExceptionHandler())
                .build()
    }

    @Test
    fun `unsupported HTTP method is a problem-details 405`() {
        mockMvc
            .post("/test/required")
            .andExpect {
                status { isMethodNotAllowed() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors[0].code") { value("METHOD_NOT_ALLOWED") }
                header { string("Allow", "GET") }
            }
    }

    @Test
    fun `unsupported request media type is a problem-details 415`() {
        mockMvc
            .post("/test/json") {
                contentType = MediaType.TEXT_PLAIN
                content = "not-json"
            }.andExpect {
                status { isUnsupportedMediaType() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors[0].code") { value("UNSUPPORTED_MEDIA_TYPE") }
            }
    }

    @Test
    fun `unacceptable response media type is a problem-details 406`() {
        mockMvc
            .get("/test/json-response") {
                accept = MediaType.TEXT_PLAIN
            }.andExpect {
                status { isNotAcceptable() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors[0].code") { value("NOT_ACCEPTABLE") }
            }
    }

    @Test
    fun `missing required parameter is a problem-details 400`() {
        mockMvc
            .get("/test/required")
            .andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors[0].code") { value("MISSING_PARAMETER") }
                jsonPath("$.errors[0].path") { value("id") }
            }
    }

    @Test
    fun `missing required header is a problem-details 400`() {
        mockMvc
            .get("/test/header")
            .andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors[0].code") { value("REQUEST_BINDING_FAILED") }
            }
    }

    @Test
    fun `data-integrity details are hidden behind a generic 409`() {
        val response = GlobalExceptionHandler().handleDataIntegrity(DataIntegrityViolationException("secret SQL detail"))

        assertEquals(409, response.statusCode.value())
        assertEquals("DATA_INTEGRITY_CONFLICT", response.body?.errors?.single()?.code)
        assertTrue(response.body?.detail?.contains("secret SQL detail") == false)
    }
}

@RestController
@RequestMapping("/test")
private class ExceptionTestController {
    @GetMapping("/required")
    fun required(
        @RequestParam id: Int,
    ): Int = id

    @GetMapping("/header")
    fun header(
        @RequestHeader("X-Required") value: String,
    ): String = value

    @PostMapping("/json", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun json(
        @RequestBody body: Map<String, Any>,
    ): Map<String, Any> = body

    @GetMapping("/json-response", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jsonResponse(): Map<String, Any> = mapOf("ok" to true)
}
