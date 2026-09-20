package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestInstallationManifestV1 {
    fun requireOperation(original: TestRunInstallationManifestV1, jdbc: JdbcTemplate)
    fun retain(operation: TestInstallationManifestOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestInstallationManifestOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestInstallationManifestOperationV1)
    fun completed(): Boolean
}
