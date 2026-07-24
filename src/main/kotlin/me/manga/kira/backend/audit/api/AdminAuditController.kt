package me.manga.kira.backend.audit.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.audit.domain.AuditEntry
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.common.PageResponse
import me.manga.kira.backend.common.exception.BadRequestException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/audit")
class AdminAuditController(private val audit: AuditRepository, private val objectMapper: ObjectMapper) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int): PageResponse<AuditEntryResponse> {
        if (page < 0 || size !in 1..100) {
            throw BadRequestException("page must be non-negative and size must be between 1 and 100.", code = "INVALID_PAGE")
        }
        val result = audit.findPage(page, size)
        return PageResponse(result.items.map(::response), page, size, result.total)
    }

    private fun response(entry: AuditEntry) = AuditEntryResponse(
        id = entry.id,
        actorUserId = entry.actorUserId,
        action = entry.action,
        entityType = entry.entityType,
        entityId = entry.entityId,
        detail = objectMapper.readTree(entry.detailJson),
        createdAt = entry.createdAt,
    )
}

data class AuditEntryResponse(
    val id: Long,
    val actorUserId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: String,
    val detail: JsonNode,
    val createdAt: Instant,
)
