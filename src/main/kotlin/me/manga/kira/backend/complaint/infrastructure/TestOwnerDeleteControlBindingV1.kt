package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Necessary locked lower comparisons; never signed activation, a current capability or quiescence evidence. */
internal class TestOwnerDeleteControlBindingV1(private val graph: TestOwnerDeleteLocalGraphV1) {
    private val desired = graph.desiredSettings()
    private val deadlines = graph.routing.journalConfiguration.declaration().limits.deadlines

    fun lock(jdbc: JdbcTemplate, authorizing: Boolean): Locked {
        graph.requireDeletion(jdbc)
        check(!authorizing || graph.recoveryRegistration == null)
        PersistencePhaseOwnership.current()?.registeredInventoryControls(graph, jdbc)?.let { epoch ->
            // Only the retained original's paid, native inventory recovery may use its captured
            // SEALED cut. This does not weaken the historical AUTH/ordinary continuation reader.
            check(!authorizing && graph.recoveryRegistration != null)
            return Locked(epoch, 0)
        }
        val global = jdbc.query(LOCK_CONTROL, { row, _ -> read(row) }, ComplaintDataScope.LIVE.id).single()
        val scoped = jdbc.query(LOCK_CONTROL, { row, _ -> read(row) }, desired.scope.id).single()
        check(!global.test && scoped.test)
        listOf(global, scoped).forEach {
            check(it.database == desired.databaseIdentity && it.restore == desired.restoreIdentity && it.writer == graph.writer)
            check(it.settled && !it.scanRequested && (!authorizing || !it.closed))
        }
        check(scoped.generation == desired.desiredGeneration && scoped.hash.contentEquals(desired.configurationHashBytes()))
        check(global.catalogGeneration == scoped.catalogGeneration && global.catalogHash.contentEquals(scoped.catalogHash))
        check(global.trustHash.contentEquals(scoped.trustHash) && global.catalogWriter == scoped.catalogWriter)
        val now = jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() })!!
        check(scoped.epoch > scoped.sealed && scoped.sealed >= scoped.cutoff && scoped.cutoff > 0)
        check(scoped.checkpointValid && !scoped.checkpoint.isAfter(now) && !scoped.started.isAfter(scoped.checkpoint))
        check(scoped.sealValid && !scoped.verified.isAfter(now) && scoped.retainUntil.isAfter(now))
        if (authorizing) check(Duration.between(scoped.checkpoint, now).toMillis() <= deadlines.checkpointMaxAgeMillis)
        return Locked(scoped.epoch, scoped.sealed)
    }

    /** Run comes AFTER counters, before owner/resource locks, never in the control-lock prefix. */
    fun lockRun(jdbc: JdbcTemplate, authorizing: Boolean) {
        graph.requireDeletion(jdbc)
        if (graph.recoveryRegistration != null) {
            check(!authorizing)
            checkNotNull(PersistencePhaseOwnership.current()).requireTestRunDeletionRun(graph, jdbc)
            return
        }
        jdbc.query("SELECT state, configuration_hash, test_only, purging_at, purged_at FROM complaint_test_runs WHERE data_scope_id = ? FOR UPDATE", { row, _ ->
            check(row.getBoolean("test_only") && !row.wasNull())
            check(row.getBytes("configuration_hash").contentEquals(desired.configurationHashBytes()))
            check(row.getString("state") in if (authorizing) setOf("ACTIVE") else setOf("ACTIVE", "SEALED"))
            check(row.getTimestamp("purging_at") == null && row.getTimestamp("purged_at") == null)
        }, desired.scope.id).single()
    }

    class Locked internal constructor(val epoch: Long, val sealedEpoch: Long) {
        fun requireContinuation(frozen: Long, prepared: Boolean) {
            check(frozen in 1..epoch && (!prepared || frozen > sealedEpoch))
        }
    }

    private class Row(
        val test: Boolean, val database: UUID, val restore: UUID, val writer: UUID, val generation: Long, val hash: ByteArray,
        val settled: Boolean, val scanRequested: Boolean, val closed: Boolean, val catalogGeneration: Long, val catalogHash: ByteArray,
        val trustHash: ByteArray, val catalogWriter: UUID, val epoch: Long, val sealed: Long, val cutoff: Long,
        val checkpointValid: Boolean, val checkpoint: Instant, val started: Instant, val sealValid: Boolean, val verified: Instant, val retainUntil: Instant,
    )

    private fun read(row: ResultSet): Row {
        fun bytes(name: String): ByteArray = checkNotNull(row.getBytes(name)).also { check(it.size == 32) }
        fun id(name: String): UUID = checkNotNull(row.getObject(name, UUID::class.java))
        fun time(name: String): Instant = checkNotNull(row.getTimestamp(name)).toInstant()
        check(row.getInt("implementation_schema") == desired.implementationSchema)
        return Row(
            row.getBoolean("test_only"), id("database_identity"), id("restore_identity"), id("event_writer_generation"), row.getLong("desired_generation"),
            bytes("desired_configuration_hash"), row.getBoolean("settled"), row.getBoolean("scan_requested"), row.getBoolean("maintenance_closed"),
            row.getLong("accepted_catalog_generation").also { check(it in 1..65536) }, bytes("accepted_catalog_hash"), bytes("trust_bundle_hash"), id("catalog_writer_generation"),
            row.getLong("publication_epoch"), row.getLong("seal_epoch"), row.getLong("checkpoint_cutoff_epoch"), row.getBoolean("checkpoint_valid"),
            time("checkpoint_completed_at"), time("checkpoint_started_at"), row.getBoolean("seal_valid"), time("seal_verified_at"), time("seal_retain_until"),
        )
    }

    private companion object {
        val LOCK_CONTROL = """
            SELECT test_only, implementation_schema, database_identity, restore_identity, event_writer_generation, desired_generation,
                desired_configuration_hash, pending_projection_token IS NULL AS settled, scan_requested, maintenance_closed,
                accepted_catalog_generation, accepted_catalog_hash, trust_bundle_hash, catalog_writer_generation,
                publication_epoch, seal_epoch, checkpoint_cutoff_epoch, checkpoint_completed_at, checkpoint_started_at, seal_verified_at, seal_retain_until,
                COALESCE(checkpoint_generation > 0 AND checkpoint_fencing_token > 0 AND checkpoint_fencing_token <= lease_token
                    AND checkpoint_catalog_generation = accepted_catalog_generation AND checkpoint_catalog_hash = accepted_catalog_hash
                    AND checkpoint_writer_generation = event_writer_generation AND checkpoint_configuration_hash = desired_configuration_hash
                    AND checkpoint_database_identity = database_identity AND checkpoint_restore_identity = restore_identity AND checkpoint_schema = implementation_schema
                    AND checkpoint_result = 'SUCCESS' AND checkpoint_object_count >= 0 AND checkpoint_byte_count >= 0
                    AND complaint_bytes_match(checkpoint_bytes, checkpoint_hash, 65536)
                    AND isfinite(checkpoint_started_at) AND isfinite(checkpoint_completed_at), false) AS checkpoint_valid,
                COALESCE(seal_state = 'SEAL_VERIFIED' AND seal_writer_generation = event_writer_generation AND seal_epoch > 0
                    AND complaint_bytes_match(seal_bytes, seal_hash, 65536) AND complaint_bytes_match(seal_verification_bytes, seal_verification_hash, 65536)
                    AND isfinite(seal_verified_at) AND isfinite(seal_retain_until), false) AS seal_valid
            FROM complaint_journal_control WHERE data_scope_id = ? FOR UPDATE
        """.trimIndent()
    }
}
