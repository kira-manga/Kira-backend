package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Necessary comparisons inside the retained fixed operation only. The bound path requires the
 * actual process owner; legacy supplied D/catalog evidence remains diagnostic. Neither equality
 * nor computed D grants restore/runtime authority. Its private snapshot never leaves the phase.
 */
internal class OwnerDeleteAllControlBinding private constructor(
    private val desired: ComplaintInstallationDesiredSettings.Configured,
    routing: VersionBoundComplaintJournalRouting?,
    catalog: CatalogCommonHeadEvidence?,
    private val process: OwnerDeleteAllProcessBinding?,
    internal val testGraph: TestOwnerDeleteLocalGraphV1?,
) {
    constructor(desired: ComplaintInstallationDesiredSettings.Configured, routing: VersionBoundComplaintJournalRouting,
        catalog: CatalogCommonHeadEvidence, process: OwnerDeleteAllProcessBinding? = null) : this(desired, routing, catalog, process, null)
    constructor(graph: TestOwnerDeleteLocalGraphV1) : this(graph.desiredSettings(), null, null, null, graph) {
        require(graph.routing.journalConfiguration.ownerDeleteAll)
    }
    internal val requiresBoundVerification = process != null
    val scope = desired.scope
    private val testControls = testGraph?.let(::TestOwnerDeleteControlBindingV1)
    private val journalWriter = routing?.journalConfiguration?.declaration()?.writer ?: checkNotNull(testGraph).routing.journalConfiguration.declaration().writer
    private val deadlines = routing?.journalConfiguration?.declaration()?.limits?.deadlines ?: checkNotNull(testGraph).routing.journalConfiguration.declaration().limits.deadlines
    val writer: UUID = UUID.fromString(journalWriter.generationId)
    private val catalogGeneration = catalog?.chain?.tail?.generation ?: 0
    private val catalogHash = catalog?.chain?.tail?.envelopeSha256?.let(::digest)
    private val trustHash = catalog?.chain?.trust?.currentBundleEnvelopeSha256?.let(::digest)
    private val catalogWriter = catalog?.chain?.tail?.catalogWriterGenerationId?.let(UUID::fromString)

    init {
        if (testGraph == null) {
            process?.requireInputs(desired, checkNotNull(routing))
            require(desired.mode === ComplaintInstallationMode.LIVE && scope == ComplaintDataScope.LIVE)
            require(catalogGeneration in 1..65536 && catalogGeneration >= checkNotNull(catalog).chain.trust.minimumHeadGeneration)
            require(checkNotNull(catalogWriter).version() == 4 && catalogWriter.variant() == 2)
        } else require(desired.mode === ComplaintInstallationMode.PRE_CUTOVER_TEST && scope == testGraph.routing.journalConfiguration.scope)
        require(desired.databaseIdentity.toString() == journalWriter.databaseIdentity && desired.restoreIdentity.toString() == journalWriter.restoreIdentity)
    }

    fun lockRun(jdbc: JdbcTemplate, authorizing: Boolean) { testControls?.lockRun(jdbc, authorizing) }

    fun lock(jdbc: JdbcTemplate, authorizingPath: Boolean): Locked {
        testControls?.let { val held = it.lock(jdbc, authorizingPath); return Locked(held.epoch, held.sealedEpoch) }
        process?.requireDeletion(jdbc)
        val selected = jdbc.query(
            CONTROL_SQL,
            { row, _ -> read(row) },
            desired.implementationSchema, desired.desiredGeneration, desired.configurationHashBytes(), desired.databaseIdentity, desired.restoreIdentity,
            writer, catalogGeneration, catalogHash, trustHash, catalogWriter,
        ).single()
        // Sample after the locking SELECT returned: a volatile projection can run before a lock wait.
        val now = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
        check(!selected.scanRequested && (!authorizingPath || !selected.maintenanceClosed))
        check(!selected.checkpointCompleted.isAfter(now) && !selected.checkpointStarted.isAfter(selected.checkpointCompleted))
        // Necessary local interval only; neither timestamps nor stored seal bytes prove provider retention.
        check(!selected.sealVerified.isAfter(now) && selected.sealRetainUntil.isAfter(now))
        if (authorizingPath) check(Duration.between(selected.checkpointCompleted, now).toMillis() <= deadlines.checkpointMaxAgeMillis)
        process?.requireDeletion(jdbc)
        return Locked(selected.epoch, selected.sealedEpoch)
    }

    private fun read(row: ResultSet): Observation {
        check(requiredBoolean(row, "binding_matches") && requiredBoolean(row, "checkpoint_matches") && requiredBoolean(row, "seal_matches"))
        val epoch = requiredLong(row, "publication_epoch")
        val seal = requiredLong(row, "seal_epoch")
        check(epoch > seal && seal >= requiredLong(row, "checkpoint_cutoff_epoch"))
        return Observation(
            epoch,
            seal,
            requiredBoolean(row, "maintenance_closed"),
            requiredBoolean(row, "scan_requested"),
            row.getTimestamp("checkpoint_started_at").toInstant(),
            row.getTimestamp("checkpoint_completed_at").toInstant(),
            row.getTimestamp("seal_verified_at").toInstant(),
            row.getTimestamp("seal_retain_until").toInstant(),
        )
    }

    /** Observed while the exact operation still holds fence/control; never externally released authority. */
    class Locked internal constructor(val epoch: Long, private val sealedEpoch: Long) {
        fun requireContinuation(frozenEpoch: Long, prepared: Boolean) {
            check(frozenEpoch in 1..epoch)
            // A coordinator cannot seal a still-PREPARED cutoff. Never add bytes to a sealed epoch.
            if (prepared) check(frozenEpoch > sealedEpoch)
        }

        override fun toString(): String = "OwnerDeleteAllLockedControl(redacted,no-authority)"
    }

    private class Observation(
        val epoch: Long,
        val sealedEpoch: Long,
        val maintenanceClosed: Boolean,
        val scanRequested: Boolean,
        val checkpointStarted: Instant,
        val checkpointCompleted: Instant,
        val sealVerified: Instant,
        val sealRetainUntil: Instant,
    )

    override fun toString(): String = "OwnerDeleteAllControlBinding(comparison-only,redacted)"

    companion object {
        private fun digest(value: String): ByteArray {
            require(value.matches(Regex("[0-9a-f]{64}")))
            return HexFormat.of().parseHex(value)
        }

        private fun requiredBoolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { check(!row.wasNull()) }
        private fun requiredLong(row: ResultSet, name: String): Long = row.getLong(name).also { check(!row.wasNull()) }

        private val CONTROL_SQL = """
            SELECT publication_epoch, maintenance_closed, scan_requested, seal_epoch,
                checkpoint_cutoff_epoch, checkpoint_started_at, checkpoint_completed_at, seal_verified_at, seal_retain_until,
                COALESCE(NOT test_only AND implementation_schema = ? AND desired_generation = ?
                    AND desired_configuration_hash = ? AND database_identity = ? AND restore_identity = ?
                    AND event_writer_generation = ? AND accepted_catalog_generation = ? AND accepted_catalog_hash = ?
                    AND trust_bundle_hash = ? AND catalog_writer_generation = ? AND pending_projection_token IS NULL, false) AS binding_matches,
                COALESCE(checkpoint_generation > 0 AND checkpoint_fencing_token > 0 AND checkpoint_fencing_token <= lease_token
                    AND checkpoint_catalog_generation = accepted_catalog_generation AND checkpoint_catalog_hash = accepted_catalog_hash
                    AND checkpoint_writer_generation = event_writer_generation AND checkpoint_cutoff_epoch > 0
                    AND checkpoint_configuration_hash = desired_configuration_hash AND checkpoint_database_identity = database_identity
                    AND checkpoint_restore_identity = restore_identity AND checkpoint_schema = implementation_schema
                    AND checkpoint_result = 'SUCCESS' AND checkpoint_object_count >= 0 AND checkpoint_byte_count >= 0
                    AND complaint_bytes_match(checkpoint_bytes, checkpoint_hash, 65536)
                    AND isfinite(checkpoint_started_at) AND isfinite(checkpoint_completed_at), false) AS checkpoint_matches,
                COALESCE(seal_state = 'SEAL_VERIFIED' AND seal_writer_generation = event_writer_generation
                    AND seal_epoch > 0 AND complaint_bytes_match(seal_bytes, seal_hash, 65536)
                    AND complaint_bytes_match(seal_verification_bytes, seal_verification_hash, 65536)
                    AND isfinite(seal_verified_at) AND isfinite(seal_retain_until), false) AS seal_matches
            FROM complaint_journal_control
            WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'
            FOR UPDATE
        """.trimIndent()
    }
}
