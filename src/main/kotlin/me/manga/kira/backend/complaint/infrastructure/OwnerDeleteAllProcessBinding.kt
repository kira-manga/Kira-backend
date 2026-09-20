package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/** Necessary current-process comparisons only. No matching value grants catalog/restore or runtime authority. */
internal class OwnerDeleteAllProcessBinding(private val process: VersionBoundComplaintProcessConfiguration) {
    val desired = process.desiredSettings()
    private val writer = UUID.fromString(process.consumers.journalConfiguration.declaration().writer.generationId)

    fun requireOrdinary(jdbc: JdbcTemplate) = checked {
        process.requireUnchangedConfiguration()
        check(jdbc.dataSource === process.pools.ordinary)
    }

    fun requireDeletion(jdbc: JdbcTemplate) = checked {
        process.requireUnchangedConfiguration()
        check(jdbc.dataSource === process.pools.deletion)
    }

    fun requireInputs(selected: ComplaintInstallationDesiredSettings.Configured, routing: VersionBoundComplaintJournalRouting) = checked {
        process.requireUnchangedConfiguration()
        check(routing === process.consumers.journalRouting)
        check(selected.mode === desired.mode && selected.scope == desired.scope)
        check(selected.implementationSchema == desired.implementationSchema && selected.desiredGeneration == desired.desiredGeneration)
        check(selected.databaseIdentity == desired.databaseIdentity && selected.restoreIdentity == desired.restoreIdentity)
        check(desired.matchesConfigurationHash(selected.configurationHashBytes()))
    }

    fun requirePolicy(policy: ComplaintCapacityPolicyV1) = checked { check(policy === process.consumers.capacityPolicy) }

    /** These columns come from the SAME original MVCC SELECT as the exact credential and receipt. */
    fun requireSnapshot(row: ResultSet) = checked {
        check(row.getObject("control_scope", UUID::class.java) == desired.scope.id)
        check(row.getBoolean("control_live") && !row.wasNull())
        check(row.getInt("control_schema") == desired.implementationSchema && !row.wasNull())
        check(row.getLong("control_generation") == desired.desiredGeneration && !row.wasNull())
        check(desired.matchesConfigurationHash(row.getBytes("control_configuration_hash")))
        check(row.getObject("control_database", UUID::class.java) == desired.databaseIdentity)
        check(row.getObject("control_restore", UUID::class.java) == desired.restoreIdentity)
        check(row.getObject("control_writer", UUID::class.java) == writer)
        // A pending accepted-projection change is not a current settled binding, even for replay.
        check(row.getBoolean("control_projection_settled") && !row.wasNull())
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Redacted finite configuration refusal, including actual-pool tampering.
    private inline fun checked(action: () -> Unit) {
        try {
            action()
        } catch (failure: RuntimeException) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    override fun toString(): String = "OwnerDeleteAllProcessBinding(comparison-only,redacted)"

    companion object {
        val columns = """
            j.data_scope_id AS control_scope, (NOT j.test_only) AS control_live,
            j.implementation_schema AS control_schema, j.desired_generation AS control_generation,
            CASE WHEN octet_length(j.desired_configuration_hash) = 32 THEN j.desired_configuration_hash END AS control_configuration_hash,
            j.database_identity AS control_database, j.restore_identity AS control_restore,
            j.event_writer_generation AS control_writer, (j.pending_projection_token IS NULL) AS control_projection_settled
        """.trimIndent()
    }
}
