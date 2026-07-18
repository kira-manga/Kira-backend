package me.manga.kira.backend.completion.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.InvalidProviderResponseException
import me.manga.kira.backend.completion.domain.ProviderUnavailableException
import me.manga.kira.backend.config.KiraCompletionProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Production provider adapter for a small HTTPS JSON contract: POST `{model,prompt}` and accept a
 * 2xx `{result}` response. Credentials and payloads are never logged. Transport/5xx/429 failures are
 * retryable provider-unavailable outcomes; other non-2xx responses are sanitized refusals.
 */
@Component
@ConditionalOnProperty(prefix = "kira.completion", name = ["provider"], havingValue = "http", matchIfMissing = true)
class HttpCompletionProvider(private val properties: KiraCompletionProperties, private val objectMapper: ObjectMapper) : CompletionProvider {
    override val name: String = "http"
    private val client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()

    override fun complete(prompt: String, model: String): CompletionOutcome {
        val endpoint = URI(requireNotNull(properties.endpoint) { "kira.completion.endpoint is required" })
        val apiKey = requireNotNull(properties.apiKey) { "kira.completion.api-key is required" }
        val body = objectMapper.writeValueAsBytes(mapOf("model" to model, "prompt" to prompt))
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(properties.timeout)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        val started = System.nanoTime()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProviderUnavailableException("provider call interrupted", ex)
        } catch (ex: java.io.IOException) {
            throw ProviderUnavailableException("provider transport unavailable", ex)
        }
        response.body().use { stream ->
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw ProviderUnavailableException("provider temporarily unavailable")
            }
            if (response.statusCode() !in 200..299) {
                return CompletionOutcome.Failure("provider rejected the request")
            }
            val bounded = stream.readNBytes(maxResponseBytes() + 1)
            if (bounded.size > maxResponseBytes()) throw InvalidProviderResponseException("provider response exceeded the configured bound")
            val result = runCatching { objectMapper.readTree(bounded).path("result").takeIf { it.isTextual }?.asText() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: throw InvalidProviderResponseException("provider response did not contain a textual result")
            return CompletionOutcome.Success(result, elapsedMs(started))
        }
    }

    private fun maxResponseBytes(): Int = (properties.maxResultLength.toLong() * UTF8_MAX_BYTES_PER_CHAR + JSON_OVERHEAD_BYTES)
        .coerceAtMost(MAX_RESPONSE_BYTES.toLong())
        .toInt()

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        const val UTF8_MAX_BYTES_PER_CHAR = 4L
        const val JSON_OVERHEAD_BYTES = 65_536L
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        fun elapsedMs(started: Long): Int = ((System.nanoTime() - started) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
