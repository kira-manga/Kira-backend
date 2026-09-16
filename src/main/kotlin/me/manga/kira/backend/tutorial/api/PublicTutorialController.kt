package me.manga.kira.backend.tutorial.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.tutorial.application.TutorialMediaService
import me.manga.kira.backend.tutorial.application.TutorialService
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class PublicTutorialController(private val tutorials: TutorialService, private val media: TutorialMediaService, private val objectMapper: ObjectMapper) {
    @GetMapping("/tutorial-categories")
    fun categories(request: HttpServletRequest): ResponseEntity<*> = cached(
        tutorials.publicCategories().map(PublicCategoryResponse::of),
        request,
    )

    @GetMapping("/tutorials")
    fun tutorials(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) featured: Boolean?,
        request: HttpServletRequest,
    ): ResponseEntity<*> = cached(tutorials.publicTutorials(category, featured).map(PublicTutorialResponse::of), request)

    @GetMapping("/tutorials/{slug}")
    fun tutorial(@PathVariable slug: String, request: HttpServletRequest): ResponseEntity<*> =
        cached(PublicTutorialResponse.of(tutorials.publicTutorial(slug)), request)

    @GetMapping("/tutorial-media/{id}")
    fun media(@PathVariable id: UUID, request: HttpServletRequest): ResponseEntity<*> {
        val (metadata, path) = media.loadForDelivery(id)
        val etag = "\"${metadata.sha256}\""
        if (matches(request, etag)) {
            return ResponseEntity.status(304).eTag(etag).header(HttpHeaders.CACHE_CONTROL, IMMUTABLE_CACHE).build<Any>()
        }
        return ResponseEntity.ok()
            .eTag(etag)
            .header(HttpHeaders.CACHE_CONTROL, IMMUTABLE_CACHE)
            .contentType(MediaType.parseMediaType(metadata.contentType))
            .contentLength(metadata.byteSize)
            .body(InputStreamResource(Files.newInputStream(path)))
    }

    private fun cached(body: Any, request: HttpServletRequest): ResponseEntity<*> {
        val hash = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(body)).joinToString("") { "%02x".format(it) }
        val etag = "\"$hash\""
        if (matches(request, etag)) {
            return ResponseEntity.status(304).eTag(etag).header(HttpHeaders.CACHE_CONTROL, PUBLIC_CACHE).build<Any>()
        }
        return ResponseEntity.ok().eTag(etag).header(HttpHeaders.CACHE_CONTROL, PUBLIC_CACHE).body(body)
    }

    private fun matches(request: HttpServletRequest, etag: String): Boolean =
        request.getHeader(HttpHeaders.IF_NONE_MATCH)?.split(',')?.map(String::trim)?.any { it == etag || it == "W/$etag" || it == "*" } == true

    companion object {
        const val PUBLIC_CACHE = "public, max-age=60, stale-if-error=86400"
        const val IMMUTABLE_CACHE = "public, max-age=31536000, immutable"
    }
}
