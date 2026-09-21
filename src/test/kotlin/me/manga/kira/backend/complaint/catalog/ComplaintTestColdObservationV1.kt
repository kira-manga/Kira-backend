package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.backend.common.infrastructure.persistence.ColdFixtureFilesV1
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Read-only test observations, never restored or submitted to the product as state/proof/authority. */
internal object ColdSqlObservationV1 {
    private val tables = listOf("flyway_schema_history", "users", "admin_step_up_grants", "complaint_journal_control", "complaint_test_runs", "complaint_catalog_mutations",
        "complaint_capacity_counters", "complaint_resource_ids", "complaints", "complaint_installation_ids", "app_installations", "audit_log",
        "complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications",
        "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements",
        "complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_test_terminal_intents")

    fun image(jdbc: JdbcTemplate): Map<String, List<String>> = tables.associateWith { table ->
        jdbc.query("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t ORDER BY to_jsonb(t)::text", { row, _ -> row.getString(1) })
    }
    fun generation(jdbc: JdbcTemplate): Instant = checkNotNull(jdbc.queryForObject("SELECT pg_postmaster_start_time()", Timestamp::class.java)).toInstant()
    fun fence(jdbc: JdbcTemplate, scope: UUID): Long = checkNotNull(jdbc.queryForObject(
        "SELECT lease_token FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, scope))

    fun noRuntimeSessions(jdbc: JdbcTemplate) {
        requireConnectionFree()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND usename IN (?, ?, ?)",
                Long::class.java, PgLifecycleDatabaseSettings.CANDIDATE, VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME,
                VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME) != 0L) {
            assertTrue(System.nanoTime() < deadline, "Old runtime database sessions did not disappear; never terminate backends to qualify recovery.")
            Thread.sleep(25)
        }
    }

    fun awaitLeaseExpiry(jdbc: JdbcTemplate, scope: UUID) {
        requireConnectionFree()
        val deadline = System.nanoTime() + 35_000_000_000L
        while (jdbc.queryForObject("SELECT count(*) = 2 AND bool_and((lease_owner IS NULL AND lease_expires_at IS NULL) OR " +
                "lease_expires_at <= clock_timestamp()) FROM complaint_journal_control WHERE data_scope_id IN (?, ?)",
                Boolean::class.java, ComplaintDataScope.LIVE.id, scope) != true) {
            assertTrue(System.nanoTime() < deadline, "Original leases did not NATURALLY expire within 35 seconds.")
            Thread.sleep(100)
        }
    }

    fun advisory(connection: Connection, name: String, mode: String): Boolean = connection.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = ? AND granted " +
            "AND classid::bigint = (hashtextextended(?, 0) >> 32 & 4294967295) AND objid::bigint = (hashtextextended(?, 0) & 4294967295))",
    ).use { statement ->
        statement.setString(1, mode); statement.setString(2, name); statement.setString(3, name)
        statement.executeQuery().use { row -> check(row.next()); row.getBoolean(1).also { check(!row.next()) } }
    }

    fun completed(saved: ColdRawHandoffV1, jdbc: JdbcTemplate, journal: TestOwnerDeleteJournalConfigurationV1,
        seal: TestOrdinarySealHttpFixtureV1) {
        val current = image(jdbc)
        val changed = setOf("complaint_journal_control", "complaint_test_runs", "complaint_capacity_counters",
            "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "complaint_journal_scan_runs",
            "complaint_journal_scan_entries", "complaint_test_terminal_intents", "audit_log")
        (tables - changed).forEach { table -> assertEquals(saved.image.getValue(table), current.getValue(table), "No PROJECT/enrollment/primary replay: $table") }
        assertTrue(current.getValue("complaint_journal_scan_runs").isEmpty() && current.getValue("complaint_journal_scan_entries").isEmpty())
        assertEquals(4, current.getValue("complaint_deletion_journal_applied").size)
        recoveryAudits(saved, current, journal.scope.id)
        assertEquals(1, current.getValue("complaint_test_terminal_intents").size)
        val oldRecovery = row(saved.image.getValue("complaint_recovery_capacity_reservations").single())
        val recovery = row(current.getValue("complaint_recovery_capacity_reservations").single())
        val used = OwnerDeleteLiteralCharges.ordinaryApply.scaled(4)
        assertEquals("PARTIAL", text(oldRecovery, "state")); assertEquals("CONVERTED", text(recovery, "state"))
        assertEquals(OwnerDeleteLiteralCharges.ordinaryApply, vector(oldRecovery, "converted_amounts"))
        assertEquals(OwnerDeleteLiteralCharges.promise, vector(recovery, "reserved_amounts"))
        assertEquals(oldRecovery["reserved_amounts"], recovery["reserved_amounts"])
        assertEquals(used, vector(recovery, "converted_amounts"))
        val residual = OwnerDeleteLiteralCharges.promise - used
        val spend = TestOrdinaryDrainLiteralV1.sidecar + if (saved.paidCut) ComplaintCapacityVector.ZERO else TestOrdinaryDrainLiteralV1.delta
        val recycled = if (saved.paidCut) TestOrdinaryDrainLiteralV1.scanRun.scaled(2) + TestOrdinaryDrainLiteralV1.scanEntry.scaled(8) else ComplaintCapacityVector.ZERO
        val recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply.scaled(3)
        val beforeCounters = saved.image.getValue("complaint_capacity_counters").associateBy { text(row(it), "name") }
        current.getValue("complaint_capacity_counters").forEach { bytes ->
            val after = row(bytes); val originalBytes = beforeCounters.getValue(text(after, "name")); val before = row(originalBytes)
            val counter = ComplaintCapacityCounter.entries.single { it.storedName == text(after, "name") }
            assertEquals(number(before, "free_units") + residual[counter], number(after, "free_units"), counter.storedName)
            assertEquals(number(before, "actual_units") + spend[counter] + recoverySpend[counter] - recycled[counter], number(after, "actual_units"), counter.storedName)
            assertEquals(number(before, "recovery_reserved_units") - recoverySpend[counter] - residual[counter], number(after, "recovery_reserved_units"), counter.storedName)
            assertEquals(number(before, "test_reserved_units") - spend[counter] + recycled[counter], number(after, "test_reserved_units"), counter.storedName)
            val mutable = setOf("free_units", "actual_units", "recovery_reserved_units", "test_reserved_units", "updated_at")
            assertEquals(before.filterKeys { it !in mutable }, after.filterKeys { it !in mutable }, "Full D, limits and daily enrollment remain exact.")
            assertEquals(number(after, "hard_limit"), listOf("free_units", "actual_units", "recovery_reserved_units", "test_reserved_units").sumOf { number(after, it) })
            if (counter !in setOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.SCAN_ENTRIES) &&
                spend[counter] == 0L && recycled[counter] == 0L && recoverySpend[counter] == 0L && residual[counter] == 0L)
                assertEquals(originalBytes, bytes, "Unrelated counter bytes/xmin cannot churn.")
        }
        val oldRun = row(saved.image.getValue("complaint_test_runs").single()); val run = row(current.getValue("complaint_test_runs").single())
        assertEquals(vector(oldRun, "unused_reserve") - spend + recycled, vector(run, "unused_reserve"))
        if (saved.paidCut) assertEquals(oldRun["permanent_denial_bytes"], run["permanent_denial_bytes"], "Exact committed paid bytes survive the new JVM.")
        assertEquals("SEALED", text(run, "state")); assertEquals(11L, number(run, "final_ordinary_epoch"))
        assertEquals(12L, number(run, "terminal_seal_epoch")); assertEquals(1L, number(run, "generation_seal_count"))
        for (column in listOf("purging_at", "purged_at", "terminal_event_id", "event_manifest_root", "installation_manifest_root"))
            assertEquals(kotlinx.serialization.json.JsonNull, run[column], "No later terminal outcome is claimed.")
        val oldControls = saved.image.getValue("complaint_journal_control").associateBy { text(row(it), "data_scope_id") }
        current.getValue("complaint_journal_control").forEach { bytes ->
            val after = row(bytes); val old = oldControls.getValue(text(after, "data_scope_id"))
            if (text(after, "data_scope_id") == ComplaintDataScope.LIVE.id.toString()) assertEquals(old, bytes)
            else assertEquals(row(old).filterKeys { it.startsWith("seal_") || it.startsWith("checkpoint_") },
                after.filterKeys { it.startsWith("seal_") || it.startsWith("checkpoint_") }, "Old seal/checkpoint are immutable comparison history.")
        }
        val scope = journal.scope.id
        assertTrue(fence(jdbc, scope) > saved.fence, "The new drain must take a fresh fence.")
        val json = TestTerminalJsonV1(journal)
        fun runBytes(column: String) = checkNotNull(jdbc.queryForObject("SELECT $column FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, scope))
        val progress = json.progress(runBytes("permanent_denial_bytes"))
        assertTrue(progress.installationReads().isEmpty())
        val cut = progress.completedCuts().single()
        assertEquals(1L, cut.epochStartInclusive); assertEquals(11L, cut.epochEndInclusive)
        assertEquals(ColdFixtureFilesV1.sha256(unbase64(saved.approval)), cut.denial.policyEvidence.sha256)
        val expected = manifest(journal, saved.ordinary)
        listOf(cut.denial.firstInventory, cut.denial.secondInventory).forEach {
            assertEquals(4L, it.versionCount); assertEquals(saved.ordinary.sumOf { obj -> unbase64(obj.body).size.toLong() }, it.byteCount)
            assertEquals(expected.first, it.sha256)
        }
        assertEquals(expected.second, cut.framedByteCount)
        val stored = checkNotNull(seal.stored)
        val record = json.sealSet(runBytes("seal_set_bytes")).records().single()
        assertEquals(stored.key, record.objectRef.objectKey); assertEquals(stored.version, record.objectRef.objectVersion)
        assertEquals(ColdFixtureFilesV1.sha256(stored.bytes), record.objectRef.ciphertextSha256)
        val canonical = json.epochSeal(checkNotNull(jdbc.queryForObject("SELECT canonical_bytes FROM complaint_test_terminal_intents WHERE data_scope_id = ?", ByteArray::class.java, scope)))
        assertEquals(4L, canonical.eventCount); assertEquals(expected.first, canonical.eventManifestSha256)
        assertEquals(1, seal.requests.count { it.kind == "PUT" })
    }

    /** New aliases require paid recovery summaries, never another primary/enrollment/PROJECT audit. */
    private fun recoveryAudits(saved: ColdRawHandoffV1, current: Map<String, List<String>>, scope: UUID) {
        val originalApplied = saved.image.getValue("complaint_deletion_journal_applied").single()
        val applied = current.getValue("complaint_deletion_journal_applied")
        assertTrue(originalApplied in applied, "The author JVM's primary APPLIED row remains byte/xmin-identical.")
        assertEquals(saved.ordinary.map { it.key to it.version }.toSet(),
            applied.map { row(it).let { value -> text(value, "object_key") to text(value, "object_version") } }.toSet())
        val aliases = (applied - originalApplied).associateBy(::xmin)
        assertEquals(3, aliases.size, "Each of the three new aliases is applied in its own transaction.")

        val originalAudits = saved.image.getValue("audit_log")
        val audits = current.getValue("audit_log")
        assertTrue(audits.containsAll(originalAudits), "Every author JVM audit remains byte/xmin-identical, including unrelated history.")
        assertEquals(originalAudits.size + 3, audits.size, "Only three newly applied aliases may append audits.")
        val summaries = audits - originalAudits.toSet()
        assertEquals(3, summaries.size)
        assertEquals(aliases.keys, summaries.map(::xmin).toSet(), "Exactly one recovery summary shares each new alias's actual transaction.")

        val primary = row(originalApplied)
        val receipt = saved.image.getValue("complaint_idempotency_receipts").map(::row).single {
            it["publication_ref"] == primary["event_id"]
        }
        assertEquals("OWNER_DELETE", text(receipt, "operation")); assertEquals("COMPLETED", text(receipt, "state"))
        val target = receipt.getValue("target_ids").jsonArray.single()
        val expected = mapOf("actor_user_id" to JsonNull, "action" to JsonPrimitive("COMPLAINT_RECOVERY_APPLIED"),
            "entity_type" to JsonPrimitive("complaint"), "entity_id" to target, "detail" to JsonObject(emptyMap()),
            "complaint_data_scope_id" to JsonPrimitive(scope.toString()), "complaint_actor_kind" to JsonPrimitive("SYSTEM"))
        summaries.forEach { bytes ->
            val summary = row(bytes)
            assertEquals(expected, summary.filterKeys { it != "id" && it != "created_at" })
            assertTrue(number(summary, "id") > 0)
            val alias = row(aliases.getValue(xmin(bytes)))
            for (column in listOf("data_scope_id", "test_only", "writer_generation", "journal_epoch", "event_kind", "target_count"))
                assertEquals(primary.getValue(column), alias.getValue(column))
            // The audit uses the APPLY timestamp captured before INSERT_APPLIED's own clock_timestamp().
            assertTrue(!Instant.parse(text(summary, "created_at")).isAfter(Instant.parse(text(alias, "applied_at"))))
        }
    }

    private fun manifest(journal: TestOwnerDeleteJournalConfigurationV1, objects: List<ColdJournalObjectV1>): Pair<String, Long> {
        val bytes = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { stream ->
                fun fields(values: List<String>) = values.forEach { val encoded = it.toByteArray(); stream.writeInt(encoded.size); stream.write(encoded) }
                fields(listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest", journal.declaration().writer.generationId,
                    journal.ordinaryPrefix, "TEST", journal.scope.id.toString(), "1", "11", "4"))
                objects.sortedBy { it.key }.forEach { fields(listOf(it.key, it.version, ColdFixtureFilesV1.sha256(unbase64(it.body)))) }
            }; output.toByteArray()
        }
        return ColdFixtureFilesV1.sha256(bytes) to bytes.size.toLong()
    }
    private fun row(bytes: String): JsonObject = Json.parseToJsonElement(bytes).jsonArray[0].jsonObject
    private fun xmin(bytes: String): String = Json.parseToJsonElement(bytes).jsonArray[1].jsonPrimitive.content
    private fun text(row: JsonObject, column: String): String = row.getValue(column).jsonPrimitive.content
    private fun number(row: JsonObject, column: String): Long = row.getValue(column).jsonPrimitive.long
    private fun vector(row: JsonObject, column: String): ComplaintCapacityVector = row.getValue(column).jsonArray.let { array ->
        check(array.size == 22); ComplaintCapacityVector.of(LongArray(22) { array[it].jsonPrimitive.long })
    }
}
