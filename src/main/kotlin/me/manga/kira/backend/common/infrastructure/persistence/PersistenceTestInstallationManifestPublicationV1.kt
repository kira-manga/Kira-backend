package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
import org.springframework.jdbc.core.JdbcTemplate

internal interface PersistenceTestInstallationManifestPublicationV1 {
    fun requireOperation(original: TestRunInstallationManifestPublicationV1, jdbc: JdbcTemplate)
    fun retain(operation: TestInstallationManifestPublicationOperationV1, jdbc: JdbcTemplate)
    fun requireRetained(operation: TestInstallationManifestPublicationOperationV1, jdbc: JdbcTemplate)
    fun requireCommitted(operation: TestInstallationManifestPublicationOperationV1)
    fun completed(): Boolean
}
