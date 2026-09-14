package me.manga.kira.backend.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.file.Path

@Validated
@ConfigurationProperties(prefix = "kira.tutorial")
data class KiraTutorialProperties(
    val mediaDirectory: Path = Path.of("./data/tutorial-media"),
    val seedEnabled: Boolean = true,
    @field:Min(1)
    @field:Max(100)
    val maximumSteps: Int = 24,
    @field:Min(1)
    @field:Max(100_000)
    val mediaInspectionLimit: Int = 10_000,
    @field:Min(1)
    @field:Max(30_000)
    val mediaLockTimeoutMillis: Long = 5_000,
    // Owner enablement is not evidence that old/noncooperating writers have been drained.
    val mediaReconciliationEnabled: Boolean = false,
    @field:Min(1_000)
    @field:Max(3_600_000)
    val mediaReconciliationIntervalMillis: Long = 300_000,
)
