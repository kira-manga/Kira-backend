package me.manga.kira.backend.sourceconfig.api

import jakarta.validation.Valid
import me.manga.kira.backend.security.AuthenticatedUser
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverResult
import me.manga.kira.backend.sourceconfig.application.GenericV2CutoverService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/source-catalog-v2/cutover")
class GenericV2CutoverController(private val cutover: GenericV2CutoverService) {
    /** Read-only preflight. */
    @GetMapping
    fun dryRun(): GenericV2CutoverResult = cutover.dryRun()

    /** Atomic apply; requires the exact reviewed confirmation phrase. */
    @PostMapping
    fun apply(@Valid @RequestBody request: GenericV2CutoverRequest, @AuthenticationPrincipal admin: AuthenticatedUser): GenericV2CutoverResult =
        cutover.apply(request.confirmation, admin.id)
}

data class GenericV2CutoverRequest(val confirmation: String = "")
