package me.manga.kira.backend.config

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties(prefix = "kira.admin-studio")
data class KiraAdminStudioProperties(
    @field:NotNull
    val stepUpTtl: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!stepUpTtl.isZero && !stepUpTtl.isNegative && stepUpTtl <= Duration.ofMinutes(15)) {
            "kira.admin-studio.step-up-ttl must be greater than zero and no more than 15 minutes"
        }
    }
}
