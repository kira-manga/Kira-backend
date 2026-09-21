package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReplyInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintReportFingerprint
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.ownerCreateTestIngress
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal object TestRegisteredInitialCheckpointCreateCasesV1 {
    fun genuineRepeatedCreateAndReplay(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val providers = f.providerCounts()
        val control = f.checkpoint.sealer.first.controlImage()
        val global = f.checkpoint.sealer.first.globalImage()
        val paid = f.checkpoint.sealer.first.paidImage()
        val before = f.counters()
        val seen = AtomicBoolean()
        f.jdbc.after = { path, sql -> if (path === REGISTERED_CREATE && sql == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate && seen.compareAndSet(false, true)) {
            val connection = (TransactionSynchronizationManager.getResource(f.jdbc.dataSource!!) as ConnectionHolder).connection
            assertTrue(f.checkpoint.sealer.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.checkpoint.sealer.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
        } }
        val first = f.attempt(); val receipt = f.create(first)
        f.jdbc.after = { _, _ -> }; f.assertApplied(receipt, first); f.assertReleased(); assertTrue(seen.get())
        val sql = f.createSql()
        val claim = sql.indexOfFirst { it.startsWith("INSERT INTO complaint_idempotency_receipts") }
        val globalLock = sql.indexOf(TestActiveInitialCheckpointSqlV1.lockGlobal)
        val scopeLock = sql.indexOf(TestActiveInitialCheckpointSqlV1.lockScope)
        val current = sql.indexOf(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate)
        val counters = sql.indexOfFirst { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it }
        assertTrue(claim >= 0 && globalLock > claim && scopeLock > globalLock && current > scopeLock && counters > current)
        assertEquals(5, sql.count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate })
        assertTrue(sql.lastIndexOf(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate) < sql.indexOfLast { "UPDATE complaint_idempotency_receipts" in it })
        f.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
        assertEquals(true, f.observer.queryForObject("SELECT ownership = 'INSTALLATION' AND kind = 'REPORT' AND version = 1 AND status = 'OPEN' " +
            "AND subject = 'Registered subject' AND body = E'Registered body\\nline' FROM complaints WHERE id = ?", Boolean::class.java, first.input.id))
        assertEquals(true, f.observer.queryForObject("SELECT state = 'COMPLETED' AND response_status = 201 AND publication_ref IS NULL " +
            "AND external_event_id IS NULL AND expires_at = completed_at + interval '192 hours' FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?",
            Boolean::class.java, f.actor.id, first.input.key))
        val second = f.attempt(); f.assertApplied(f.create(second), second); f.assertReleased()
        f.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE.scaled(2))
        // The two real SYSTEM/NOTICE activation seeds, reservations and creation audits remain.
        assertEquals(4L, f.observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(4L, f.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(4L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", Long::class.java, f.scope))
        assertEquals(control, f.checkpoint.sealer.first.controlImage()); assertEquals(global, f.checkpoint.sealer.first.globalImage()); assertEquals(paid, f.checkpoint.sealer.first.paidImage())

        // Both born-with global CREATE quota members are spent. Existing exact receipt bypasses it
        // and new-work controls, while current actor authentication is still mandatory.
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", f.scope))
        val closed = f.state(); f.jdbc.calls.clear()
        f.assertApplied(f.create(first), first); f.assertApplied(f.status(first), first)
        assertTrue(f.createSql().isEmpty()); assertEquals(closed, f.state()); assertEquals(providers, f.providerCounts())
        foreignReadIngress(f, first)
        desiredReadIdentity(f, first)
        assertEquals(providers, f.providerCounts())
        assertEquals(1, f.observer.update("UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ?", f.actor.id))
        val revoked = f.state()
        refused(ComplaintOwnerOperationFailure.UNAUTHORIZED) { f.create(first) }
        refused(ComplaintOwnerOperationFailure.UNAUTHORIZED) { f.status(first) }
        assertEquals(revoked, f.state()); f.assertReleased()
    }

    private fun foreignReadIngress(f: TestRegisteredInitialCheckpointCreateFixtureV1, attempt: RegisteredInitialCreateAttemptV1) {
        val before = f.state()
        val foreign = ownerCreateTestIngress(f.process.consumers.capacityPolicy, creates = f.process.consumers.ownerCreatePolicy)
        val adapter = ComplaintOwnerCreateAdapter(f.actor.scope, f.jwt, f.phases, foreign)
        val query = ComplaintOwnerStatusQuery("OWNER_CREATE", attempt.input.key.toString(), attempt.input.id.toString(),
            ComplaintReportFingerprint.of(attempt.candidate.request).encoded)
        f.jdbc.calls.clear()
        foreign.withIngress(f.request()) { context -> refused { adapter.create(context, f.token, attempt.input) } }
        foreign.withIngress(f.request()) { context -> refused { adapter.status(context, f.token, query) } }
        foreign.withIngress(f.request()) { context ->
            foreign.startOwnerCreate(context)
            phaseRefused(PersistenceDatabaseOutcome.NONE) { f.phases.authenticate(f.identity(), context) }
            phaseRefused(PersistenceDatabaseOutcome.NONE) { f.phases.preflight(f.identity(), attempt.candidate.tuple, context) }
        }
        foreign.withIngress(f.request()) { context ->
            foreign.startOwnerStatus(context)
            val read = Any(); foreign.chargeOwnerStatus(context, f.actor, read); foreign.consumeOwnerStatus(context, read)
            phaseRefused(PersistenceDatabaseOutcome.NONE) { f.phases.status(f.identity(), attempt.candidate.tuple, context) }
        }
        phaseRefused(PersistenceDatabaseOutcome.NONE) { f.phases.authenticate(f.identity()) }
        phaseRefused(PersistenceDatabaseOutcome.NONE) { f.phases.preflight(f.identity(), attempt.candidate.tuple) }
        phaseRefused(PersistenceDatabaseOutcome.NONE) { f.phases.status(f.identity(), attempt.candidate.tuple) }
        assertTrue(f.jdbc.calls.isEmpty(), "Foreign/missing contexts refuse before any registered read checkout or SQL.")
        assertEquals(before, f.state()); f.assertReleased()
        f.assertApplied(f.create(attempt), attempt); f.assertApplied(f.status(attempt), attempt)
        assertTrue(f.createSql().isEmpty(), "Retained-ingress exact receipts still precede the closed new-work gate.")
    }

    private fun desiredReadIdentity(f: TestRegisteredInitialCheckpointCreateFixtureV1, attempt: RegisteredInitialCreateAttemptV1) {
        for (scope in listOf(f.scope, UUID(0L, 0L))) {
            val original = f.observer.queryForMap("SELECT desired_generation, desired_configuration_hash FROM complaint_journal_control WHERE data_scope_id = ?", scope)
            for ((column, value) in listOf("desired_generation" to (original.getValue("desired_generation") as Number).toLong() + 1,
                "desired_configuration_hash" to ByteArray(32) { 0x68 })) {
                assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, scope))
                val damaged = f.state(); f.jdbc.calls.clear()
                refused { f.create(attempt) }; refused { f.status(attempt) }
                f.ingress.withIngress(f.request()) { context ->
                    f.ingress.startOwnerCreate(context)
                    phaseRefused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.preflight(f.identity(), attempt.candidate.tuple, context) }
                }
                f.ingress.withIngress(f.request()) { context ->
                    f.ingress.startOwnerStatus(context)
                    val read = Any(); f.ingress.chargeOwnerStatus(context, f.actor, read); f.ingress.consumeOwnerStatus(context, read)
                    phaseRefused(PersistenceDatabaseOutcome.ROLLED_BACK) { f.phases.status(f.identity(), attempt.candidate.tuple, context) }
                }
                assertTrue(f.createSql().isEmpty()); assertEquals(damaged, f.state()); f.assertReleased()
                assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], scope))
            }
        }
        // Only negative fixture drift is restored; the new-work gates remain closed throughout.
        val matching = f.state(); f.jdbc.calls.clear()
        f.assertApplied(f.create(attempt), attempt); f.assertApplied(f.status(attempt), attempt)
        assertTrue(f.createSql().isEmpty()); assertEquals(matching, f.state()); f.assertReleased()
    }

    private fun phaseRefused(outcome: PersistenceDatabaseOutcome, action: () -> Any?) {
        val failure = assertThrows<PersistencePhaseException> { action() }
        assertTrue(failure.cleanupProven); assertEquals(outcome, failure.databaseOutcome)
    }

    fun exactGraphOnly(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val before = f.state(); val calls = f.jdbc.calls.size
        val p = f.process.consumers.capacityPolicy
        assertThrows<Exception> { JdbcComplaintOwnerCreateStore(f.jdbc, JdbcComplaintCapacityStore(f.jdbc, p.digestBytes()),
            f.exchange.service, f.process.desiredSettings()) }
        val lookalike = JdbcTemplate(f.process.pools.ordinary)
        assertThrows<Exception> { JdbcComplaintOwnerCreateStore.registeredInitialCheckpoint(lookalike, f.exchange.service,
            f.exchange.ordinary.ownership, f.registration, f.checkpoint.assembly) }
        // One manager binds one owner: a foreign owner needs its own real manager, not a rebind.
        val foreignOwner = PersistencePhaseOwnership(f.exchange.ordinary.admission,
            GuardedJpaTransactionManager(f.exchange.ordinary.entityManagerFactory, f.exchange.ordinary.pool))
        assertThrows<Exception> { JdbcComplaintOwnerCreateStore.registeredInitialCheckpoint(f.jdbc, f.exchange.service,
            foreignOwner, f.registration, f.checkpoint.assembly) }
        assertEquals(calls, f.jdbc.calls.size)
        val attempt = f.attempt()
        val otherIngress = ownerCreateTestIngress(p, creates = f.process.consumers.ownerCreatePolicy)
        otherIngress.withIngress(f.request()) { context ->
            otherIngress.startOwnerCreate(context)
            val handoff = otherIngress.admitOwnerCreate(context, attempt.candidate.tuple)
            val failure = assertThrows<PersistencePhaseException> { f.phases.create(f.identity(), attempt.candidate, ComplaintPlatform.ANDROID, handoff) }
            assertTrue(failure.cleanupProven); assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
        }
        assertTrue(f.createSql().isEmpty())
        f.ingress.withIngress(f.request()) { context -> refused { f.adapter.reply(context, f.token,
            ComplaintOwnerReplyInput(UUID.randomUUID(), attempt.input.id, attempt.input.key, "Not enabled", attempt.input.metadata)) } }
        f.ingress.withIngress(f.request()) { context -> refused { f.adapter.status(context, f.token,
            ComplaintOwnerStatusQuery("OWNER_REPLY", attempt.input.key.toString(), listOf(UUID.randomUUID().toString(), attempt.input.id.toString()), "A".repeat(43))) } }
        assertEquals(before, f.state()); f.assertReleased()
        f.registration.close()
        assertThrows<Exception> { JdbcComplaintOwnerCreateStore.registeredInitialCheckpoint(f.jdbc, f.exchange.service,
            f.exchange.ordinary.ownership, f.registration, f.checkpoint.assembly) }
        refused { f.create(attempt) }; assertEquals(before, f.state())
    }

    fun missingCheckpoint(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        assertEquals("SEAL_VERIFIED", f.checkpoint.control()["seal_state"])
        val before = f.state(); val counters = f.counters(); val providers = f.providerCounts()
        val attempt = f.attempt()
        refused { f.create(attempt) }; f.assertReleased(); f.assertNoCounterSql()
        refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { f.status(attempt) }
        assertEquals(before, f.state()); assertEquals(counters, f.counters()); assertEquals(providers, f.providerCounts())
        assertTrue(f.checkpoint.raw.order.isEmpty(), "CREATE cannot run the scanner or turn the earlier seal into a checkpoint.")
    }

    fun persistedDrift(f: TestRegisteredInitialCheckpointCreateFixtureV1) {
        val original = f.checkpoint.control()
        val attempt = f.attempt(); val providers = f.providerCounts()
        val hash = ByteArray(32) { 0x79.toByte() }
        val changes = linkedMapOf<String, Any>(
            "checkpoint_generation" to ((original.getValue("checkpoint_generation") as Number).toLong() + 1),
            "checkpoint_fencing_token" to ((original.getValue("checkpoint_fencing_token") as Number).toLong() + 1),
            "checkpoint_catalog_generation" to ((original.getValue("checkpoint_catalog_generation") as Number).toLong() + 1),
            "checkpoint_catalog_hash" to hash, "checkpoint_writer_generation" to UUID.randomUUID(), "checkpoint_cutoff_epoch" to 2L,
            "checkpoint_configuration_hash" to hash, "checkpoint_database_identity" to UUID.randomUUID(), "checkpoint_restore_identity" to UUID.randomUUID(),
            "checkpoint_started_at" to Timestamp.from((original.getValue("checkpoint_started_at") as Timestamp).toInstant().minusNanos(1000)),
            "checkpoint_completed_at" to Timestamp.from((original.getValue("checkpoint_completed_at") as Timestamp).toInstant().plusNanos(1000)),
            "checkpoint_object_count" to 1L, "checkpoint_byte_count" to 1L,
        )
        for ((column, value) in changes) {
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, f.scope))
            val damaged = f.state(); f.jdbc.calls.clear()
            refused { f.create(attempt) }; f.assertReleased(); f.assertNoCounterSql(); assertEquals(damaged, f.state())
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], f.scope))
        }
        // Four columns have narrower SQL invariants too. Do not disable constraints just to fake
        // a reader case: schema/result constants and independent byte/hash drift are database-rejected.
        for ((column, value) in listOf("checkpoint_schema" to 2, "checkpoint_result" to "FAILED",
            "checkpoint_bytes" to "{}".toByteArray(), "checkpoint_hash" to hash)) {
            val before = f.state()
            assertThrows<DataIntegrityViolationException> { f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, f.scope) }
            assertEquals(before, f.state())
        }
        val bytes = original.getValue("checkpoint_bytes") as ByteArray
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        for ((name, value) in listOf("journalConfigurationSha256" to JsonPrimitive("8".repeat(64)), "profile" to JsonPrimitive("HEALTHY"))) {
            val damagedBytes = CanonicalJson.canonicalize(JsonObject(json + (name to value))).toByteArray()
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET checkpoint_bytes = ?, checkpoint_hash = ? WHERE data_scope_id = ?",
                damagedBytes, HexFormat.of().parseHex(Sha256.hex(damagedBytes)), f.scope))
            val damaged = f.state(); f.jdbc.calls.clear()
            refused { f.create(attempt) }; f.assertNoCounterSql(); assertEquals(damaged, f.state())
        }
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET checkpoint_bytes = ?, checkpoint_hash = ? WHERE data_scope_id = ?",
            bytes, original["checkpoint_hash"], f.scope))
        val verification = original.getValue("seal_verification_bytes") as ByteArray
        val proof = Json.parseToJsonElement(verification.decodeToString()).jsonObject
        val damagedProof = CanonicalJson.canonicalize(JsonObject(proof + ("objectVersion" to JsonPrimitive("unrelated-version")))).toByteArray()
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET seal_verification_bytes = ?, seal_verification_hash = ? WHERE data_scope_id = ?",
            damagedProof, HexFormat.of().parseHex(Sha256.hex(damagedProof)), f.scope))
        val damaged = f.state(); f.jdbc.calls.clear()
        refused { f.create(attempt) }; f.assertNoCounterSql(); assertEquals(damaged, f.state())
        assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET seal_verification_bytes = ?, seal_verification_hash = ? WHERE data_scope_id = ?",
            verification, original["seal_verification_hash"], f.scope))
        for ((column, value) in listOf("desired_configuration_hash" to hash, "restore_identity" to UUID.randomUUID(),
            "publication_epoch" to 3L, "scan_requested" to true, "creation_closed" to true)) {
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, f.scope))
            val changed = f.state(); f.jdbc.calls.clear()
            refused { f.create(attempt) }; f.assertNoCounterSql(); assertEquals(changed, f.state())
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], f.scope))
        }
        assertEquals(providers, f.providerCounts()); assertArrayEquals(bytes, f.checkpoint.control()["checkpoint_bytes"] as ByteArray)
        // Restoration is TEST observer teardown of negatives; it cannot authorize anything. The
        // genuine producer must still perform its own complete current check and normal accounting.
        f.assertApplied(f.create(attempt), attempt); f.assertReleased()
    }

    internal fun refused(expected: ComplaintOwnerOperationFailure = ComplaintOwnerOperationFailure.UNAVAILABLE, action: () -> Any?) {
        assertEquals(expected, assertThrows<ComplaintOwnerOperationRejected> { action() }.failure)
    }
}
