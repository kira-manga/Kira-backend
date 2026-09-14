package me.manga.kira.backend.sourceconfig.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import jakarta.servlet.ServletInputStream
import me.manga.kira.backend.common.GlobalExceptionHandler
import me.manga.kira.backend.common.exception.PayloadTooLargeException
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverRejected
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverService
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogReceipt
import me.manga.kira.backend.user.domain.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

/** Boundary tests only: actual ADMIN security and durable replay belong to connected ITs. */
class GenericV2CutoverControllerTest {
    private val cutover = mock(GenericV2CutoverService::class.java)
    private val controller = GenericV2CutoverController(cutover)
    private val mapper = ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val admin = AuthenticatedUser(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "admin@example.com",
        Role.ADMIN,
        Instant.EPOCH,
    )

    // Stubbed application output for HTTP serialization, never a persisted COMPLETE fixture.
    private val origin = InitialSourceCatalogReceipt(
        policyId = "app-bundle-v6-initial-catalog-v1",
        referenceSha256 = "a".repeat(64),
        payloadSha256 = "b".repeat(64),
        documentRevision = 100,
        documentChecksum = "c".repeat(64),
        catalogRevision = 100,
        catalogChecksum = "d".repeat(64),
        completedAt = Instant.parse("2026-09-12T00:00:00Z"),
        actorId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
    )
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken(admin, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        SecurityContextHolder.setContext(context)
        mvc =
            MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler())
                .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
                .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
                .build()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `JSON charset does not transcode the body and response preserves all origin fields`() {
        val body = " { \"name\": \"مانجا\", \"revision\": 6 }\r\n".toByteArray(Charsets.UTF_8)
        `when`(cutover.importBundled(body, CONFIRMATION, admin.id)).thenReturn(origin)

        val response = mvc.post(BOOTSTRAP_PATH) {
            contentType = MediaType.parseMediaType("application/json;charset=ISO-8859-1")
            header(CONFIRMATION_HEADER, CONFIRMATION)
            content = body
        }.andExpect { status { isOk() } }.andReturn().response

        val json = mapper.readTree(response.contentAsByteArray)
        assertEquals(
            setOf(
                "policyId",
                "referenceSha256",
                "payloadSha256",
                "documentRevision",
                "documentChecksum",
                "catalogRevision",
                "catalogChecksum",
                "completedAt",
                "actorId",
            ),
            json.fieldNames().asSequence().toSet(),
        )
        assertEquals(origin.policyId, json["policyId"].textValue())
        assertEquals(origin.referenceSha256, json["referenceSha256"].textValue())
        assertEquals(origin.payloadSha256, json["payloadSha256"].textValue())
        assertEquals(origin.documentRevision, json["documentRevision"].longValue())
        assertEquals(origin.documentChecksum, json["documentChecksum"].textValue())
        assertEquals(origin.catalogRevision, json["catalogRevision"].longValue())
        assertEquals(origin.catalogChecksum, json["catalogChecksum"].textValue())
        assertEquals(origin.completedAt.toString(), json["completedAt"].textValue())
        assertEquals(origin.actorId.toString(), json["actorId"].textValue())
        verify(cutover).importBundled(body, CONFIRMATION, admin.id)
        verifyNoMoreInteractions(cutover)
    }

    @Test
    fun `malformed UTF8 reaches the service unchanged rather than replacement decoding at MVC`() {
        val body = byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d)
        `when`(cutover.importBundled(body, CONFIRMATION, admin.id)).thenThrow(GenericV2CutoverRejected("test core rejection"))

        mvc.post(BOOTSTRAP_PATH) {
            contentType = MediaType.APPLICATION_JSON
            header(CONFIRMATION_HEADER, CONFIRMATION)
            content = body
        }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("source-catalog v2 cutover rejected: test core rejection.") }
        }

        verify(cutover).importBundled(body, CONFIRMATION, admin.id)
        verifyNoMoreInteractions(cutover)
    }

    @Test
    fun `missing padded wrong and repeated confirmations are value-free conflicts`() {
        listOf(
            emptyList(),
            listOf(""),
            listOf("$CONFIRMATION "),
            listOf(CONFIRMATION.lowercase()),
            listOf("private-header-sentinel"),
            listOf(CONFIRMATION, CONFIRMATION),
            listOf(CONFIRMATION, "private-header-sentinel"),
        ).forEach { values ->
            val response = mvc.post(BOOTSTRAP_PATH) {
                contentType = MediaType.APPLICATION_JSON
                if (values.isNotEmpty()) header(CONFIRMATION_HEADER, *values.toTypedArray())
                content = "private-body-sentinel"
            }.andExpect {
                status { isConflict() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors[0].code") { value("SOURCE_CATALOG_V2_CUTOVER_REJECTED") }
                jsonPath("$.detail") { value("source-catalog v2 cutover rejected: exact confirmation is required.") }
            }.andReturn().response
            assertFalse(response.contentAsString.contains("private-header-sentinel"))
            assertFalse(response.contentAsString.contains("private-body-sentinel"))
        }
        verifyNoInteractions(cutover)
    }

    @Test
    fun `invalid confirmation is rejected before the controller opens the body stream`() {
        val request = unreadableRequest("wrong", 0)

        val failure = assertThrows(GenericV2CutoverRejected::class.java) { controller.importBundled(request, admin) }

        assertEquals(409, failure.status.value())
        verifyNoInteractions(cutover)
    }

    @Test
    fun `declared oversize is rejected before the controller opens the body stream`() {
        val request = unreadableRequest(CONFIRMATION, GenericV2CutoverService.MAX_BOOTSTRAP_BYTES + 1L)

        val failure = assertThrows(PayloadTooLargeException::class.java) { controller.importBundled(request, admin) }

        assertEquals(413, failure.status.value())
        assertEquals("PAYLOAD_TOO_LARGE", failure.code)
        verifyNoInteractions(cutover)
    }

    @Test
    fun `unknown and understated lengths cannot bypass the controller byte bound`() {
        val body = ByteArray(GenericV2CutoverService.MAX_BOOTSTRAP_BYTES + 2)
        listOf(-1L, 1L).forEach { declaredLength ->
            val request = bodyRequest(body, declaredLength)
            val stream = request.inputStream

            val failure = assertThrows(PayloadTooLargeException::class.java) { controller.importBundled(request, admin) }

            assertEquals(413, failure.status.value())
            assertEquals("PAYLOAD_TOO_LARGE", failure.code)
            assertEquals(1, stream.readAllBytes().size)
        }
        verifyNoInteractions(cutover)
    }

    @Test
    fun `body exactly at the controller limit is passed unchanged`() {
        val body = ByteArray(GenericV2CutoverService.MAX_BOOTSTRAP_BYTES) { (it % 256).toByte() }
        `when`(cutover.importBundled(body, CONFIRMATION, admin.id)).thenReturn(origin)

        val response = controller.importBundled(bodyRequest(body, -1), admin)

        assertEquals(origin.payloadSha256, response.payloadSha256)
        verify(cutover).importBundled(body, CONFIRMATION, admin.id)
        verifyNoMoreInteractions(cutover)
    }

    @Test
    fun `non JSON media types never reach the core`() {
        listOf(MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM).forEach { type ->
            mvc.post(BOOTSTRAP_PATH) {
                contentType = type
                header(CONFIRMATION_HEADER, CONFIRMATION)
                content = "private-body-sentinel"
            }.andExpect {
                status { isUnsupportedMediaType() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors[0].code") { value("UNSUPPORTED_MEDIA_TYPE") }
            }
        }
        verifyNoInteractions(cutover)
    }

    @Test
    fun `old POST is a nonmutating conflict even for its former body or malformed JSON`() {
        listOf(null, "{}", "{\"confirmation\":\"$CONFIRMATION\"}", "private-body-sentinel").forEach { body ->
            val response = mvc.post(CUTOVER_PATH) {
                contentType = MediaType.APPLICATION_JSON
                body?.let { content = it }
            }.andExpect {
                status { isConflict() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.errors[0].code") { value("SOURCE_CATALOG_V2_CUTOVER_REJECTED") }
                jsonPath("$.detail") { value("source-catalog v2 cutover rejected: use POST $BOOTSTRAP_PATH.") }
            }.andReturn().response
            assertFalse(response.contentAsString.contains("private-body-sentinel"))
        }
        verifyNoInteractions(cutover)
    }

    private fun unreadableRequest(confirmation: String, declaredLength: Long) = object : MockHttpServletRequest("POST", BOOTSTRAP_PATH) {
        override fun getContentLengthLong(): Long = declaredLength

        override fun getInputStream(): ServletInputStream = error("body must not be read")
    }.apply { addHeader(CONFIRMATION_HEADER, confirmation) }

    private fun bodyRequest(body: ByteArray, declaredLength: Long) = object : MockHttpServletRequest("POST", BOOTSTRAP_PATH) {
        override fun getContentLength(): Int = declaredLength.toInt()

        override fun getContentLengthLong(): Long = declaredLength
    }.apply {
        addHeader(CONFIRMATION_HEADER, CONFIRMATION)
        setContent(body)
    }

    private companion object {
        const val CUTOVER_PATH = "/api/v1/admin/source-catalog-v2/cutover"
        const val BOOTSTRAP_PATH = "$CUTOVER_PATH/import-bundled"
        const val CONFIRMATION_HEADER = "X-Kira-Bootstrap-Confirmation"
        const val CONFIRMATION = "WITHHOLD_33_LEGACY_SOURCES"
    }
}
