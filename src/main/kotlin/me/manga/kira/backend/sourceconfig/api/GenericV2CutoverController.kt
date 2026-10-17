package me.manga.kira.backend.sourceconfig.api

import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.common.exception.PayloadTooLargeException
import me.manga.kira.backend.security.AdminStepUpService
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.sourceconfig.api.dto.InitialSourceCatalogReceiptResponse
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverRejected
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverResult
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverService
import me.manga.kira.backend.sourceconfig.application.SourceCatalogReconciliationRejected
import me.manga.kira.backend.sourceconfig.application.SourceCatalogReconciliationService
import me.manga.kira.backend.sourceconfig.domain.SourceCatalogReconciliationReceipt
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/source-catalog-v2/cutover")
class GenericV2CutoverController(
    private val cutover: GenericV2CutoverService,
    private val reconciliation: SourceCatalogReconciliationService,
    private val stepUp: AdminStepUpService,
) {
    /** Advisory only; includes the durable bootstrap phase and immutable origin receipt. */
    @GetMapping
    fun dryRun(): GenericV2CutoverResult = cutover.dryRun()

    /** The former two-request protocol cannot publish, even with its old confirmation body. */
    @PostMapping
    fun apply(): GenericV2CutoverResult = throw GenericV2CutoverRejected("use POST /api/v1/admin/source-catalog-v2/cutover/import-bundled")

    /**
     * ADMIN is enforced by the existing security matrix. Preserve received bytes for origin replay:
     * decoding and current parser/policy checks belong only to the service's still-PENDING branch.
     */
    @PostMapping("/import-bundled", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun importBundled(httpRequest: HttpServletRequest, @AuthenticationPrincipal admin: AuthenticatedUser): InitialSourceCatalogReceiptResponse {
        val confirmation = httpRequest.getHeaders(CONFIRMATION_HEADER).toList()
        if (confirmation != listOf(GenericV2CutoverService.CONFIRMATION)) {
            throw GenericV2CutoverRejected("exact confirmation is required")
        }
        return InitialSourceCatalogReceiptResponse.of(
            cutover.importBundled(httpRequest.readBootstrapBody(), confirmation.single(), admin.id),
        )
    }

    /** New explicit approval, never re-importing content or manufacturing a bootstrap receipt. */
    @PostMapping("/reconcile", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun reconcile(httpRequest: HttpServletRequest, @AuthenticationPrincipal admin: AuthenticatedUser): SourceCatalogReconciliationReceipt {
        if (httpRequest.getHeaders("X-Kira-Reconciliation-Confirmation").toList() != listOf(SourceCatalogReconciliationService.CONFIRMATION)) {
            throw SourceCatalogReconciliationRejected()
        }
        val limit = SourceCatalogReconciliationService.MAX_REQUEST_BYTES
        if (httpRequest.contentLengthLong > limit) throw PayloadTooLargeException("reconciliation request exceeds the 4 KiB limit")
        val raw = httpRequest.inputStream.readNBytes(limit + 1)
        if (raw.size > limit) throw PayloadTooLargeException("reconciliation request exceeds the 4 KiB limit")
        stepUp.requireSourceMutation(admin.id, httpRequest.getHeaders(AdminStepUpService.HEADER).toList().singleOrNull())
        return reconciliation.reconcile(raw, admin.id)
    }

    /** Bound the actual stream too: missing or understated Content-Length is not a size guarantee. */
    private fun HttpServletRequest.readBootstrapBody(): ByteArray {
        val limit = GenericV2CutoverService.MAX_BOOTSTRAP_BYTES
        if (contentLengthLong > limit) throw PayloadTooLargeException(BODY_LIMIT_DETAIL)
        val body = inputStream.readNBytes(limit + 1)
        if (body.size > limit) throw PayloadTooLargeException(BODY_LIMIT_DETAIL)
        return body
    }

    companion object {
        const val CONFIRMATION_HEADER = "X-Kira-Bootstrap-Confirmation"
        private const val BODY_LIMIT_DETAIL = "request body exceeds the 5 MiB import limit."
    }
}
