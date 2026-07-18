package me.manga.kira.backend.completion

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.InvalidProviderResponseException
import me.manga.kira.backend.completion.infrastructure.HttpCompletionProvider
import me.manga.kira.backend.config.KiraCompletionProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class HttpCompletionProviderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `posts bounded JSON with bearer credential and parses result`() {
        val requestBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        server.createContext("/complete") { exchange ->
            requestBody.set(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val response = "{\"result\":\"provider answer\"}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        val provider = provider(maxResultLength = 100)

        val outcome = provider.complete("sensitive prompt", "model-1") as CompletionOutcome.Success

        assertEquals("provider answer", outcome.result)
        assertEquals("Bearer unit-test-provider-key", authorization.get())
        assertEquals("model-1", ObjectMapper().readTree(requestBody.get()).path("model").asText())
        assertFalse(requestBody.get().contains("unit-test-provider-key"))
    }

    @Test
    fun `oversized or malformed provider responses are rejected`() {
        server.createContext("/complete") { exchange ->
            val response = "{\"result\":\"${"x".repeat(70_000)}\"}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        assertThrows<InvalidProviderResponseException> { provider(maxResultLength = 1).complete("prompt", "model") }
    }

    private fun provider(maxResultLength: Int): HttpCompletionProvider = HttpCompletionProvider(
        KiraCompletionProperties(
            endpoint = "http://127.0.0.1:${server.address.port}/complete",
            apiKey = "unit-test-provider-key",
            maxResultLength = maxResultLength,
        ),
        ObjectMapper(),
    )
}
