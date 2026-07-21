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
)
