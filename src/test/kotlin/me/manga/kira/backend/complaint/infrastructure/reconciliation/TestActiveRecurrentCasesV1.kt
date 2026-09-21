package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllApplyStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateCasesV1.refused
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFailureCasesV1.ownerAttempt
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionFailureCasesV1.phaseRefused
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllApplyPhaseExecutor
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class RecurrentConsumerPath { CREATE, REPLY, EDIT }

/** Actual PG/native producer cases when selected by the parent; SOURCE_ONLY / NOT_RUN here. */
internal object TestActiveRecurrentCasesV1 {
    /** Four new request families, not a relabelled precursor. OWNER additionally proves B -> C -> A. */
    fun genuineRecurrentDeletion(tls: VersionBoundPersistenceConnectedFixture, family: ComplaintJournalDeletionKindV1) =
        withCurrentDeletion(tls, family) { f, d ->
            val before = d.counters()
            withCreationClosed(d) { currentDeletionPublication(f, d) }
            val authorized = d.counters()
            if (family == ComplaintJournalDeletionKindV1.OWNER_DELETE) {
                val checkpoint = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
                val checkpointHash = (f.control().getValue("checkpoint_hash") as ByteArray).copyOf()
                val history = f.immutableImage(); val prior = deletionEvidence(f.precursor)
                val token = (f.control().getValue("lease_token") as Number).toLong()
                f.withFreshQueue(d, before, authorized, listOf(f.record, checkNotNull(d.record))) { b ->
                    val paid = b.counters()
                    val completed = b.poll()
                    assertEquals(1, completed.primaryAcknowledged); assertEquals(0, completed.dlqAcknowledged)
                    b.assertReleased(); b.assertNoAuthority(); b.assertExpectedAppliedObjects(); b.assertOnlyAuthorizedReportsErased()
                    assertEquals(2L, b.count("complaint_deletion_journal_applied"))
                    assertEquals("APPLIED", d.publication()["state"]); assertEquals("COMPLETED", b.receipt()["state"])
                    assertEquals("APPLIED", b.receipt()["outcome"])
                    assertEquals(3L, b.receipt()["external_epoch"])
                    assertEquals(checkNotNull(d.record).stored.version, b.receipt()["external_object_version"])
                    assertEquals(b.receiptBeforeQueue, b.receiptIdentity()); assertEquals(b.proofBeforeQueue, b.publicationProof())
                    assertEquals(b.identitiesBeforeQueue, b.identityImage())
                    val used = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit.scaled(2)
                    paid.forEach { (counter, old) ->
                        val observation = if (b.countsBeforeQueue.getValue("complaint_test_active_queue_observations") == 0L &&
                            counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
                        val refund = OwnerDeleteLiteralCharges.content[counter]
                        assertEquals(old.copy(free = old.free + refund - observation, actual = old.actual + used[counter] - refund + observation,
                            recovery = old.recovery - used[counter]), b.counters().getValue(counter), counter.name)
                    }
                    b.assertSameOriginalRefused(checkNotNull(b.original))
                }
                assertTrue((f.control().getValue("lease_token") as Number).toLong() > token, "The actual B owner advanced the fence; do not reset it.")
                assertArrayEquals(checkpoint, f.control()["checkpoint_bytes"] as ByteArray)
                assertArrayEquals(checkpointHash, f.control()["checkpoint_hash"] as ByteArray)
                assertEquals(history, f.immutableImage()); assertEquals(prior, deletionEvidence(f.precursor))
                assertOwnerAppliedReplay(d)

                // The target does not exist before B. C must accept the same checkpoint under the
                // honestly advanced fence, then this next A must validate both completed primaries.
                val creator = f.retainedCreator()
                val providers = f.providerCounts(); val createCounters = f.counters()
                val nextReport = creator.attempt()
                creator.assertApplied(creator.create(nextReport), nextReport); creator.assertReleased()
                assertConsumerSql(creator.createSql(), 5)
                f.assertCharge(createCounters, ComplaintCapacityCharges.OWNER_CREATE)
                assertEquals(providers, f.providerCounts())
                assertArrayEquals(checkpoint, f.control()["checkpoint_bytes"] as ByteArray)
                assertEquals(history, f.immutableImage())
                TestRegisteredInitialCheckpointDeletionFixtureV1(d.checkpoint, d.exchange, listOf(creator), listOf(nextReport), d.native,
                    ComplaintJournalDeletionKindV1.OWNER_DELETE, retained = f.precursor, expectedEpoch = 3).use { next ->
                    assertNotSame(d, next); assertNotEquals(d.key, next.key)
                    assertSame(d.ownerStore, next.ownerStore); assertSame(d.binding, next.binding)
                    withCreationClosed(next) { currentDeletionPublication(f, next, listOf(f.precursor, d)) }
                    assertNotSame(d.ownerWork, next.ownerWork); assertNotSame(d.ownerLane, next.ownerLane)
                    assertNotSame(d.readback, next.readback); assertNotSame(d.record, next.record)
                    assertEquals(3L, f.control()["publication_epoch"]); assertEquals(2L, f.control()["rotation_sequence"])
                }
            }
        }

    fun reconstructedPriorReceiptFeedsANewCurrentDeletionWithoutReauthorization(tls: VersionBoundPersistenceConnectedFixture) =
        withCurrentDeletion(tls, reconstructPriorReceipt = true) { f, d -> currentDeletionPublication(f, d) }

    fun olderDeletionProfileRemainsInitialOnly(tls: VersionBoundPersistenceConnectedFixture) =
        withCurrentDeletion(tls, deletionProfile = VersionBoundTestInitialCheckpointDeletionV1.PROFILE) { f, d ->
            val start = d.deletion.calls.size
            assertNewDeletionRefused(f, d)
            val sql = d.deletion.calls.drop(start).map { it.sql }
            assertEquals(1, sql.count { it == TestRegisteredInitialCheckpointDeletionSqlV1.current })
            assertFalse(sql.any { it == TestRegisteredRecurrentCheckpointDeletionSqlV1.branch || it == TestRegisteredRecurrentCheckpointDeletionSqlV1.current })
            assertFalse(sql.any { "complaint_capacity_counters" in it })
            assertOwnerAppliedReplay(f.precursor)
        }

    fun currentDeletionRejectsCheckpointAndPrivacyGateDrift(tls: VersionBoundPersistenceConnectedFixture) = withCurrentDeletion(tls) { f, d ->
        for (scope in listOf(UUID(0, 0), d.scope)) {
            val original = d.observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
            val changes = linkedMapOf<String, Any>("maintenance_closed" to true, "scan_requested" to true,
                "desired_generation" to (original.getValue("desired_generation") as Number).toLong() + 1,
                "database_identity" to UUID.randomUUID(), "restore_identity" to UUID.randomUUID())
            if (scope == d.scope) changes.putAll(linkedMapOf(
                "checkpoint_generation" to (original.getValue("checkpoint_generation") as Number).toLong() + 1,
                "checkpoint_fencing_token" to (original.getValue("lease_token") as Number).toLong() + 1,
                "checkpoint_catalog_generation" to (original.getValue("checkpoint_catalog_generation") as Number).toLong() + 1,
                "checkpoint_writer_generation" to UUID.randomUUID(), "checkpoint_cutoff_epoch" to 1L,
                "checkpoint_configuration_hash" to ByteArray(32) { 0x71 },
                "checkpoint_started_at" to Timestamp.from((original.getValue("checkpoint_started_at") as Timestamp).toInstant().minusNanos(1000)),
                "checkpoint_completed_at" to Timestamp.from((original.getValue("checkpoint_completed_at") as Timestamp).toInstant().plusNanos(1000)),
                "checkpoint_object_count" to (original.getValue("checkpoint_object_count") as Number).toLong() + 1,
                "checkpoint_byte_count" to (original.getValue("checkpoint_byte_count") as Number).toLong() + 1,
                "seal_object_version" to "foreign-recurrent-deletion-version"))
            for ((column, bad) in changes) {
                assertEquals(1, d.foreignUpdate("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", bad, scope))
                try {
                    val start = d.deletion.calls.size
                    assertNewDeletionRefused(f, d)
                    assertFalse(d.deletion.calls.drop(start).any { it.sql == TestRegisteredInitialCheckpointDeletionSqlV1.current ||
                        "complaint_capacity_counters" in it.sql }, column)
                } finally { assertEquals(1, d.foreignUpdate("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], scope)) }
            }
        }
        withCreationClosed(d) { currentDeletionPublication(f, d) }
    }

    fun currentDeletionRequiresExactPriorPNLAndNativeApply(tls: VersionBoundPersistenceConnectedFixture) = withCurrentDeletion(tls) { f, d ->
        val prior = f.precursor; val id = checkNotNull(prior.event).route.eventId
        val p = prior.publication()
        val n = d.observer.queryForMap("SELECT * FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND publication_ref = ?", d.scope, id)
        val l = d.observer.queryForMap("SELECT reserved_amounts::text, converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ? AND event_id = ?", d.scope, id)
        val e = d.observer.queryForMap("SELECT * FROM complaint_deletion_journal_applied WHERE data_scope_id = ? AND event_id = ?", d.scope, id)
        val damagedUsed = l.getValue("converted_amounts").toString().removeSurrounding("{", "}").split(',').map(String::toLong).toLongArray()
        val storage = ComplaintCapacityCounter.STORAGE_BYTES.ordinal
        assertTrue(damagedUsed[storage] > 0); damagedUsed[storage]--
        // Ordinary valid negative field drift only; restore the actual captured column, never
        // insert an invented completed N/proof/APPLY or regenerate native producer history.
        val changes = listOf(
            Triple("complaint_journal_publications", "object_version", "not-the-native-version" to p["object_version"]),
            Triple("complaint_journal_publications", "journal_epoch", 3L to p["journal_epoch"]),
            Triple("complaint_idempotency_receipts", "external_object_version", "not-the-primary-version" to n["external_object_version"]),
            Triple("complaint_idempotency_receipts", "fingerprint", ByteArray(32) { 0x72 } to n["fingerprint"]),
            Triple("complaint_recovery_capacity_reservations", "converted_amounts", damagedUsed.joinToString(",", "{", "}") to l["converted_amounts"]),
            Triple("complaint_deletion_journal_applied", "ciphertext_hash", ByteArray(32) { 0x73 } to e["ciphertext_hash"]),
            Triple("complaint_deletion_journal_applied", "event_kind", "ADMIN_DELETE" to e["event_kind"]),
        )
        for ((table, column, values) in changes) {
            val key = if (table == "complaint_idempotency_receipts") "publication_ref" else "event_id"
            val cast = if (column == "converted_amounts") "::bigint[]" else ""
            val sql = "UPDATE $table SET $column = ?$cast WHERE data_scope_id = ? AND $key = ?"
            assertEquals(1, d.foreignUpdate(sql, values.first, d.scope, id))
            try { assertNewDeletionRefused(f, d) } finally { assertEquals(1, d.foreignUpdate(sql, values.second, d.scope, id)) }
        }
        assertOwnerAppliedReplay(prior)
        // Missing actual E is not a reason to synthesize one from P/N or a SUCCESS document.
        assertEquals(1, d.foreignUpdate("DELETE FROM complaint_deletion_journal_applied WHERE data_scope_id = ? AND event_id = ?", d.scope, id))
        assertNewDeletionRefused(f, d)
        assertEquals("APPLIED", prior.publication()["state"])
        assertEquals(0L, d.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, d.scope))
    }

    fun currentDeletionOwnClaimCannotBorrowPriorRows(tls: VersionBoundPersistenceConnectedFixture) = withCurrentDeletion(tls) { f, d ->
        val prior = deletionEvidence(f.precursor); val priorId = f.record.event.route.eventId
        val actualBefore = d.image(); val counters = d.counters(); val providers = f.providerCounts()
        for (at in listOf(OwnerDeletePersistenceSql.INSERT_CLAIM, OwnerDeletePersistenceSql.INSERT_RECOVERY, OwnerDeletePersistenceSql.INSERT_PUBLICATION)) {
            val reached = AtomicBoolean(); val old = d.deletion.after
            d.deletion.after = { call -> old(call); if (call.sql == at && reached.compareAndSet(false, true)) {
                val holder = TransactionSynchronizationManager.getResource(d.deletion.dataSource!!) as ConnectionHolder
                // Damage this request's provisional row inside its real transaction, after the
                // actual statement. No supplied result and no foreign JDBC owner is installed.
                val sql = when (at) {
                    OwnerDeletePersistenceSql.INSERT_CLAIM -> "UPDATE complaint_idempotency_receipts SET fingerprint = ? WHERE data_scope_id = ? AND idempotency_key = ?"
                    OwnerDeletePersistenceSql.INSERT_RECOVERY -> "UPDATE complaint_recovery_capacity_reservations SET reserved_amounts[21] = reserved_amounts[21] - 1 WHERE data_scope_id = ? AND event_id <> ?"
                    else -> "UPDATE complaint_journal_publications SET journal_epoch = 2 WHERE data_scope_id = ? AND event_id <> ?"
                }
                holder.connection.prepareStatement(sql).use { statement ->
                    statement.queryTimeout = 1
                    if (at == OwnerDeletePersistenceSql.INSERT_CLAIM) {
                        statement.setBytes(1, f.record.event.comparison.fingerprintBytes()); statement.setObject(2, d.scope); statement.setObject(3, d.key)
                    } else { statement.setObject(1, d.scope); statement.setString(2, priorId) }
                    assertEquals(1, statement.executeUpdate())
                }
            } }
            try { phaseRefused { ownerAttempt(d) } } finally { d.deletion.after = old }
            assertTrue(reached.get()); d.assertReleased()
            assertEquals(actualBefore, d.image()); assertEquals(counters, d.counters()); assertEquals(providers, f.providerCounts())
            assertEquals(prior, deletionEvidence(f.precursor))
        }
        currentDeletionPublication(f, d)
    }

    fun pendingPriorAndMissingHistoryNeverBlockExactDeletionContinuation(tls: VersionBoundPersistenceConnectedFixture) = withCurrentDeletion(tls) { f, d ->
        val counters = d.counters(); d.authorize(); d.assertCharge(counters)
        val next = d.ownerCandidate(operationKey = UUID.randomUUID())
        assertNewDeletionRefused(f, d, next)
        val calls = d.deletion.calls.size; val prepared = d.image(); val paid = d.counters()
        d.assertReload(verified = false)
        assertNoCurrentDeletionGate(d.deletion.calls.drop(calls)); assertEquals(prepared, d.image()); assertEquals(paid, d.counters())
        d.publish(); d.verify()
        assertNewDeletionRefused(f, d, next) // Actual VERIFIED primary still has no APPLY.
        val archived = f.history(); val checkpoint = f.control().getValue("checkpoint_bytes") as ByteArray
        assertThrows<DataIntegrityViolationException> {
            d.observer.update("DELETE FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ? AND ordinal = 2", d.scope)
        }
        // Existing C's guard-first disposable restore-damage pattern. Reenable before COMMIT;
        // never restore this missing row by inserting a fabricated SUCCESS or proof.
        d.raw { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_active_checkpoint_history DISABLE TRIGGER complaint_test_active_history_immutable") }
                connection.prepareStatement("DELETE FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ? AND ordinal = 2").use {
                    it.setObject(1, d.scope); assertEquals(1, it.executeUpdate())
                }
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_active_checkpoint_history ENABLE TRIGGER complaint_test_active_history_immutable") }
                connection.commit()
            } finally { connection.rollback() }
        }
        assertEquals(1, f.history().size); assertArrayEquals(archived.first()["checkpoint_bytes"] as ByteArray, f.history().single()["checkpoint_bytes"] as ByteArray)
        assertArrayEquals(checkpoint, f.control()["checkpoint_bytes"] as ByteArray)
        assertNewDeletionRefused(f, d, next)
        assertEquals(2, d.foreignUpdate("UPDATE complaint_journal_control SET maintenance_closed = true, scan_requested = true WHERE data_scope_id IN (?, ?)", UUID(0, 0), d.scope))
        val beforeReload = d.deletion.calls.size; val rows = d.image(); val native = f.providerCounts()
        d.assertReload(verified = true); assertOwnerAppliedReplay(f.precursor)
        assertNoCurrentDeletionGate(d.deletion.calls.drop(beforeReload))
        assertEquals(rows, d.image()); assertEquals(paid, d.counters()); assertEquals(native, f.providerCounts())
    }

    fun currentDeletionClaimLoser(tls: VersionBoundPersistenceConnectedFixture) = withCurrentDeletion(tls) { _, d ->
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.receiptClaimLoser(d)
    }
    fun currentDeletionWaitedCredential(tls: VersionBoundPersistenceConnectedFixture) = withCurrentDeletion(tls) { _, d ->
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.credentialWait(d)
    }
    fun currentDeletionNaturalFreshness(tls: VersionBoundPersistenceConnectedFixture) = withCurrentDeletion(tls, shortFreshness = true) { _, d ->
        TestRegisteredInitialCheckpointDeletionRaceCasesV1.naturalFreshnessAfterCredential(d)
    }
    fun currentDeletionExactResources(tls: VersionBoundPersistenceConnectedFixture) = withCurrentDeletion(tls) { _, d ->
        TestRegisteredInitialCheckpointDeletionFailureCasesV1.exactResourcesAndHandoff(d)
    }

    private fun assertNewDeletionRefused(f: TestActiveRecurrentFixtureV1, d: TestRegisteredInitialCheckpointDeletionFixtureV1,
        candidate: me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteCandidate = d.ownerCandidate) {
        val rows = d.image(); val counters = d.counters(); val audits = d.audits(); val history = f.immutableImage()
        val providers = f.providerCounts(); val lanes = d.process.publicationLanes.activeOwners().totalOwners
        phaseRefused { ownerAttempt(d, candidate) }
        d.assertSqlReleased()
        assertEquals(rows, d.image()); assertEquals(counters, d.counters()); assertEquals(audits, d.audits()); assertEquals(history, f.immutableImage())
        assertEquals(providers, f.providerCounts()); assertEquals(lanes, d.process.publicationLanes.activeOwners().totalOwners)
    }

    /** The consumed original stays untouched. Only a new request fixture shares its retained stores. */
    private fun withCurrentDeletion(tls: VersionBoundPersistenceConnectedFixture,
        family: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE,
        deletionProfile: String = VersionBoundTestInitialCheckpointDeletionV1.RECURRENT_PROFILE,
        shortFreshness: Boolean = false, reconstructPriorReceipt: Boolean = false,
        action: (TestActiveRecurrentFixtureV1, TestRegisteredInitialCheckpointDeletionFixtureV1) -> Unit) {
        var stage = "INITIAL_SETUP"
        var observedFixture: TestActiveRecurrentFixtureV1? = null
        var observedDeletion: TestRegisteredInitialCheckpointDeletionFixtureV1? = null
        var reportedFailure: Throwable? = null
        fun report(failure: Throwable) {
            reportedFailure = failure
            try {
                // Retained prefix only, not attribution to a failing SQL call. Native proof work
                // can occur after the last SQL phase; cleanup may also produce a distinct failure.
                val calls = observedFixture?.precursor?.deletion?.calls
                val last = calls?.lastOrNull()
                val ordinal = if (last == null) 0 else calls?.count { it.phase === last.phase } ?: 0
                println("TEST_ACTIVE_RECURRENT_DELETION_CASE_FAILURE stage=$stage kind=${testDeletionFailureKind(failure)} " +
                    "fixtureReady=${observedFixture != null} consumerReady=${observedDeletion != null} " +
                    "observedStep=${observedFixture?.original?.step?.name ?: "NONE"} " +
                    "lastAttemptedPath=${last?.path?.name ?: "NONE"} lastAttemptedSqlStep=${last?.sqlStep ?: "NONE"} lastPhaseCallOrdinal=$ordinal " +
                    "eventRetained=${observedDeletion?.event != null} nativeRecordRetained=${observedDeletion?.record != null} readbackRetained=${observedDeletion?.readback != null}")
            } catch (_: Throwable) { /* Diagnostics must not replace the original failure. */ }
        }
        try {
            withRecurrentFixture(tls, initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.RECURRENT_PROFILE),
                initialCheckpointDeletion = TestInitialCheckpointDeletionInputV1(1, deletionProfile), shortFreshness = shortFreshness,
                reserveSecondConsumerCreator = family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) { f ->
                observedFixture = f
                try {
                    stage = "PRIOR_HISTORY"
                    val precursor = f.precursor
                    val event = checkNotNull(precursor.event); val work = precursor.ownerWork; val lane = precursor.ownerLane
                    val readback = precursor.readback; val record = precursor.record
                    if (reconstructPriorReceipt) {
                        stage = "PRIOR_B_RECONSTRUCTION"
                        reconstructOwnerReceiptThroughB(f)
                        stage = "PRIOR_HISTORY"
                    }
                    val prior = deletionEvidence(precursor)
                    val initial = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
                    val paid = f.counters()
                    stage = "RECURRENT_CHECKPOINT"
                    val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
                    stage = "CHECKPOINT_ASSERTIONS"
                    f.assertSuccessful()
                    assertEquals(2L, completed.cutoffEpoch); assertEquals(3L, f.control()["publication_epoch"])
                    assertEquals(1L, f.document().objectCount); assertEquals(f.record.stored.bytes.size.toLong(), f.document().byteCount)
                    assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), f.raw.order)
                    assertEquals(2, f.history().size)
                    assertArrayEquals(initial, f.history().first()["checkpoint_bytes"] as ByteArray)
                    assertArrayEquals(f.control()["checkpoint_bytes"] as ByteArray, f.history().last()["checkpoint_bytes"] as ByteArray)
                    f.assertCharge(paid, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2))
                    assertEquals(prior, deletionEvidence(precursor))
                    stage = "CURRENT_CREATE"
                    val creators = if (family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) f.retainedConsumerCreators else listOf(f.retainedCreator())
                    assertEquals(if (family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) 2 else 1, creators.size)
                    val providers = f.providerCounts(); val beforeCreate = f.counters()
                    val reports = creators.map { creator ->
                        assertSame(f.process, creator.process); assertSame(f.registration, creator.registration)
                        creator.attempt().also { creator.assertApplied(creator.create(it), it); creator.assertReleased() }
                    }
                    f.assertCharge(beforeCreate, ComplaintCapacityCharges.OWNER_CREATE.scaled(creators.size.toLong()))
                    assertEquals(providers, f.providerCounts(), "The actual recurrent CREATE does not call any native consumer.")
                    stage = "CURRENT_DELETION_CONSTRUCTION"
                    f.withIdleDeletionHooks {
                        TestRegisteredInitialCheckpointDeletionFixtureV1(precursor.checkpoint, precursor.exchange, creators, reports, precursor.native,
                            family, retained = precursor, expectedEpoch = 3).use { d ->
                            observedDeletion = d
                            try {
                                stage = "CURRENT_DELETION_ACTION"
                                assertEquals(if (deletionProfile == VersionBoundTestInitialCheckpointDeletionV1.RECURRENT_PROFILE)
                                    TestRegisteredRecurrentCheckpointDeletionSqlV1.current else TestRegisteredInitialCheckpointDeletionSqlV1.current, d.currentCheckpointSql())
                                action(f, d)
                            } catch (failure: Throwable) {
                                report(failure)
                                throw failure
                            } finally { stage = "CONSUMER_CLEANUP" }
                        }
                    }
                    stage = "FINAL_ASSERTIONS"
                    assertSame(event, precursor.event); assertSame(work, precursor.ownerWork); assertSame(lane, precursor.ownerLane)
                    assertSame(readback, precursor.readback); assertSame(record, precursor.record)
                    f.assertReleased()
                } catch (failure: Throwable) {
                    if (reportedFailure !== failure) report(failure)
                    throw failure
                } finally { stage = "OUTER_CLEANUP" }
            }
        } catch (failure: Throwable) {
            if (reportedFailure !== failure) report(failure)
            throw failure
        }
    }

    private fun reconstructOwnerReceiptThroughB(f: TestActiveRecurrentFixtureV1) {
        val p = f.precursor
        val original = p.publication(); val originalProof = original.getValue("verification_bytes") as ByteArray
        val originalEvidence = deletionEvidence(p)
        val audits = p.audits(); val native = p.native.counts()
        f.withFreshQueue(expectedPublicationEpoch = 2) { b ->
            val paid = b.counters()
            assertEquals(1L, b.countsBeforeQueue.getValue("complaint_test_active_queue_observations"))
            // Explicit missing-row NEGATIVE, not a refund. The actual B consumer alone may
            // reconstruct the original immutable request from its authenticated native event.
            assertEquals(1, p.foreignUpdate("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ? AND idempotency_key = ?",
                p.scope, p.actor.id, p.key))
            assertEquals(paid, b.counters(), "Deleting the damaged row creates no refund or recovery credit.")
            val completed = b.poll()
            assertEquals(1, completed.primaryAcknowledged); assertEquals(0, completed.dlqAcknowledged)
            b.assertReleased(); b.assertNoAuthority(); b.assertExpectedAppliedObjects()
            assertEquals("COMPLETED", b.receipt()["state"]); assertEquals("APPLIED", b.receipt()["outcome"])
            assertEquals(p.key, b.receipt()["idempotency_key"]); assertEquals(p.actor.id, b.receipt()["actor_id"])
            assertTrue((b.receipt().getValue("created_at") as Timestamp).after(original.getValue("created_at") as Timestamp))
            assertTrue(!(b.receipt().getValue("completed_at") as Timestamp).before(original.getValue("applied_at") as Timestamp))
            assertEquals(original["created_at"], b.receipt()["authorized_at"])
            assertEquals(checkNotNull(p.event).route.eventId, b.receipt()["external_event_id"])
            assertEquals(checkNotNull(p.record).stored.version, b.receipt()["external_object_version"])
            assertArrayEquals(originalProof, p.publication()["verification_bytes"] as ByteArray)
            val recoveredEvidence = deletionEvidence(p)
            for (table in listOf("complaint_journal_publications", "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied"))
                assertEquals(originalEvidence.getValue(table), recoveredEvidence.getValue(table), "Zero-use replay preserves $table including xmin.")
            paid.forEach { (counter, old) ->
                val charge = OwnerDeleteLiteralCharges.receipt[counter]
                assertEquals(old.copy(free = old.free - charge, actual = old.actual + charge), b.counters().getValue(counter),
                    "Only the actual reconstructed N is charged from free capacity; no new reserve/use/refund: ${counter.name}")
            }
            assertFalse(b.calls.any { it.sql == OwnerDeletePersistenceSql.SPEND_RECOVERY })
            b.assertOriginalContentUnchanged(); assertEquals(b.identitiesBeforeQueue, b.identityImage())
            assertEquals(audits, p.audits())
            assertEquals(native, p.native.counts(), "No new AUTH or native PUT is used to recover N.")
        }
        assertOwnerAppliedReplay(p)
    }

    private fun withCreationClosed(d: TestRegisteredInitialCheckpointDeletionFixtureV1, action: () -> Unit) {
        val scopes = listOf(UUID(0, 0), d.scope)
        val old = scopes.associateWith { scope -> d.observer.queryForObject("SELECT creation_closed FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, scope) }
        assertEquals(2, d.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true WHERE data_scope_id IN (?, ?)", UUID(0, 0), d.scope))
        assertEquals(2L, d.observer.queryForObject("SELECT count(*) FROM complaint_journal_control WHERE data_scope_id IN (?, ?) " +
            "AND creation_closed AND NOT maintenance_closed AND NOT scan_requested", Long::class.java, UUID(0, 0), d.scope))
        try { action() } finally { old.forEach { (scope, closed) ->
            assertEquals(1, d.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = ? WHERE data_scope_id = ?", closed, scope))
        } }
    }

    private fun currentDeletionPublication(f: TestActiveRecurrentFixtureV1, d: TestRegisteredInitialCheckpointDeletionFixtureV1,
        priors: List<TestRegisteredInitialCheckpointDeletionFixtureV1> = listOf(f.precursor)) {
        val before = d.counters(); val beforeRows = d.image(); val beforeAudits = d.audits(); val providers = f.providerCounts()
        val history = f.immutableImage(); val control = f.first.controlImage(); val global = f.first.globalImage()
        val priorImages = priors.associateWith(::deletionEvidence)
        val start = d.deletion.calls.size
        val event = d.authorize()
        assertEquals(3L, event.comparison.epoch); assertEquals(d.family, event.comparison.eventKind)
        assertEquals(d.key, event.comparison.operationKey)
        assertEquals(d.reports.map { it.input.id }.sortedBy(UUID::toString), event.complaintIds())
        assertTrue(priors.none { it.event === event || it.key == d.key || checkNotNull(it.event).route.eventId == event.route.eventId })
        d.assertCharge(before)
        assertCurrentPrimary(d, priors, "PREPARED")
        assertEquals(beforeRows.getValue("complaints"), d.image().getValue("complaints"))
        assertEquals(providers, f.providerCounts(), "AUTH holds only its actual SQL phase; a reserved native lane calls no provider.")
        assertEquals(1L, d.process.publicationLanes.activeOwners().totalOwners)
        assertOwnedDeletionSql(d, d.deletion.calls.drop(start))
        val action = if (d.family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) "COMPLAINT_INSTALLATION_DELETE_AUTHORIZED" else "COMPLAINT_DELETE_AUTHORIZED"
        val delta = if (d.family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) 2L else 1L
        assertEquals(beforeAudits + (action to (beforeAudits.getOrDefault(action, 0L) + delta)), d.audits())
        d.proof?.let { proof -> assertEquals(true, d.observer.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE id = ?", Boolean::class.java, proof.grantId)) }
        val authorized = d.image(); val paid = d.counters(); val paidAudits = d.audits()
        var calls = d.deletion.calls.size
        d.assertReload(verified = false)
        assertNoCurrentDeletionGate(d.deletion.calls.drop(calls))
        assertEquals(authorized, d.image()); assertEquals(paid, d.counters()); assertEquals(paidAudits, d.audits())
        val record = d.publish()
        assertSame(event, record.event); assertSame(record.stored, d.native.publisher(d).objects.single())
        assertNotSame(f.record, record); assertNotSame(f.precursor.native.publisher, d.native.publisher(d))
        assertEquals(authorized, d.image(), "Native ciphertext and its real readback are not SQL VERIFY.")
        val proofCalls = d.deletion.calls.size
        assertForeignDeletionProofsRefused(d, priors)
        assertEquals(proofCalls, d.deletion.calls.size, "Foreign work/readback/lane fails before the short SQL VERIFY.")
        calls = d.deletion.calls.size
        verifyAndRefuseDirectApply(d)
        assertCurrentPrimary(d, priors, "VERIFIED")
        assertNoCurrentDeletionGate(d.deletion.calls.drop(calls))
        assertTrue(d.deletion.calls.drop(calls).isNotEmpty())
        assertFalse(d.deletion.calls.drop(calls).any { "complaint_journal_control" in it.sql || "complaint_capacity_counters" in it.sql || "complaint_test_runs" in it.sql },
            "Short VERIFY never reacquires controls/counters/run locks.")
        assertTrue(d.deletion.calls.drop(calls).all { it.phase.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        calls = d.deletion.calls.size
        val verified = d.image(); val published = f.providerCounts()
        d.assertReload(verified = true)
        assertNoCurrentDeletionGate(d.deletion.calls.drop(calls))
        assertEquals(verified, d.image()); assertEquals(paid, d.counters()); assertEquals(paidAudits, d.audits())
        assertEquals(published, f.providerCounts()); assertEquals(beforeRows.getValue("complaints"), d.image().getValue("complaints"))
        priorImages.forEach { (prior, image) -> assertEquals(image, deletionEvidence(prior), "Earlier P/N/L/APPLY and xmin stay exact.") }
        assertEquals(history, f.immutableImage()); assertEquals(control, f.first.controlImage()); assertEquals(global, f.first.globalImage())
        d.assertReleased()
    }

    private fun assertOwnedDeletionSql(d: TestRegisteredInitialCheckpointDeletionFixtureV1, calls: List<TestRegisteredInitialDeletionSqlCallV1>) {
        val event = checkNotNull(d.event); val sql = calls.map { it.sql }
        val current = TestRegisteredRecurrentCheckpointDeletionSqlV1.current
        val global = sql.indexOf(TestActiveInitialCheckpointSqlV1.lockGlobal); val scope = sql.indexOf(TestActiveInitialCheckpointSqlV1.lockScope)
        val claim = sql.indexOfFirst { it.startsWith("INSERT INTO complaint_idempotency_receipts") || it.startsWith("INSERT INTO installation_deletion_receipts") }
        val read = sql.indexOf(current); val counters = sql.indexOfFirst { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it }
        assertTrue(global >= 0 && scope > global && claim > scope && read > claim && counters > read)
        assertEquals(1, sql.count { it == TestActiveInitialCheckpointSqlV1.lockGlobal }); assertEquals(1, sql.count { it == TestActiveInitialCheckpointSqlV1.lockScope })
        val owned = calls.filter { it.sql == TestRegisteredRecurrentCheckpointDeletionSqlV1.owned }
        assertTrue(owned.size >= 4)
        assertEquals(owned.size, sql.count { it == current }); assertEquals(owned.size, sql.count { it == TestRegisteredRecurrentCheckpointDeletionSqlV1.branch })
        assertEquals(owned.size, sql.count { it == TestRegisteredRecurrentCheckpointDeletionSqlV1.historyCounts })
        assertFalse(sql.contains(TestRegisteredInitialCheckpointDeletionSqlV1.current))
        assertFalse(sql.contains(TestRegisteredRecurrentCheckpointCurrentSqlV1.current), "Deletion never borrows C's creation-only eligibility.")
        val expectedBytes = event.canonicalBytes(); val fingerprint = event.comparison.fingerprintBytes()
        try { owned.forEach { call ->
            val args = call.ownedArguments
            assertEquals(20, args.size, "Three current identity arguments plus exactly17 operation-owned comparisons.")
            assertEquals(d.scope, args[0]); assertEquals(d.graph.writer, args[1]); assertEquals(3L, args[2])
            val o = args.drop(3)
            assertEquals(d.family.name, o[0]); assertEquals(event.comparison.actorId, o[1]); assertEquals(d.key, o[2])
            assertArrayEquals(fingerprint, o[3] as ByteArray)
            if (d.proof == null) assertEquals(event.comparison.credentialVersion, o[4]) else assertNull(o[4])
            if (o[9] != null) {
                assertEquals(event.complaintIds().joinToString(",", "{", "}"), o[5])
                assertArrayEquals(expectedBytes, o[9] as ByteArray)
                assertArrayEquals(HexFormat.of().parseHex(event.semanticSha256), o[10] as ByteArray)
            }
            if (o[6] != null) { assertEquals(event.route.eventId, o[6]); assertEquals(event.route.objectKey, o[7]); assertEquals(event.route.routingKeyId, o[8]) }
            assertEquals(d.recoveryCharge.toLongArray().joinToString(",", "{", "}"), o[11])
            if (o[14] != null) assertEquals(checkNotNull(d.proof).grantId, o[14])
            assertNull(o[15]); assertNull(o[16])
        } } finally { expectedBytes.fill(0); fingerprint.fill(0) }
        val last = owned.last().ownedArguments.drop(3)
        assertEquals(true, last[12]); assertEquals(d.publication()["created_at"], last[13]); assertEquals(d.proof?.grantId, last[14])
        val lastHistory = sql.lastIndexOf(TestRegisteredRecurrentCheckpointDeletionSqlV1.historyCounts)
        assertTrue(sql.drop(lastHistory + 1).contains("SELECT clock_timestamp()"), "Fresh database time follows all bounded history reads.")
        assertEquals(1, calls.map { it.phase }.distinct().size)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, calls.first().phase.databaseOutcome())
    }

    private fun assertCurrentPrimary(d: TestRegisteredInitialCheckpointDeletionFixtureV1, priors: List<TestRegisteredInitialCheckpointDeletionFixtureV1>, state: String) {
        val event = checkNotNull(d.event); val p = d.publication()
        assertEquals(event.route.eventId, p["event_id"]); assertEquals(event.route.objectKey, p["object_key"])
        assertEquals(3L, p["journal_epoch"]); assertEquals(d.family.name, p["event_kind"]); assertEquals(d.reports.size, p["target_count"])
        assertEquals(state, p["state"]); assertNull(p["applied_at"])
        assertArrayEquals(event.canonicalBytes(), p["event_bytes"] as ByteArray)
        if (state == "PREPARED") { assertNull(p["verification_bytes"]); assertNull(p["object_version"]) }
        else { assertNotNull(p["verification_bytes"]); assertEquals(checkNotNull(d.record).stored.version, p["object_version"]) }
        for (table in listOf("complaint_journal_publications", "complaint_recovery_capacity_reservations"))
            assertEquals(priors.size.toLong() + 1, d.observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, d.scope))
        assertEquals(1L, d.observer.queryForObject("SELECT count(*) FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ? AND event_id = ? " +
            "AND publication_ref = event_id AND test_only AND state = 'RESERVED' AND accounting_version = 1 AND reserved_amounts = ?::bigint[] " +
            "AND converted_amounts IS NULL AND converted_at IS NULL", Long::class.java, d.scope, event.route.eventId, d.recoveryCharge.toLongArray().joinToString(",", "{", "}")))
        val receipt = if (d.family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL) "installation_deletion_receipts" else "complaint_idempotency_receipts"
        assertEquals((priors + d).count { it.family != ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL }.toLong(), d.observer.queryForObject(
            "SELECT count(*) FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND operation IN ('OWNER_DELETE','ADMIN_DELETE','ADMIN_BATCH_DELETE')", Long::class.java, d.scope))
        assertEquals((priors + d).count { it.family == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL }.toLong(), d.observer.queryForObject(
            "SELECT count(*) FROM installation_deletion_receipts WHERE data_scope_id = ?", Long::class.java, d.scope))
        assertEquals(1L, d.observer.queryForObject("SELECT count(*) FROM $receipt WHERE data_scope_id = ? AND publication_ref = ? AND test_only " +
            "AND state = 'AUTHORIZED_DELETE' AND authorized_at = ? AND outcome IS NULL AND response_status IS NULL AND completed_at IS NULL " +
            "AND expires_at IS NULL AND external_event_id IS NULL AND external_epoch IS NULL AND external_object_version IS NULL AND external_ciphertext_hash IS NULL",
            Long::class.java, d.scope, event.route.eventId, p["created_at"]))
        assertEquals(0L, d.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ? AND event_id = ?", Long::class.java, d.scope, event.route.eventId))
        assertEquals(0L, d.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_retirements WHERE data_scope_id = ?", Long::class.java, d.scope))
    }

    private fun assertForeignDeletionProofsRefused(d: TestRegisteredInitialCheckpointDeletionFixtureV1,
        priors: List<TestRegisteredInitialCheckpointDeletionFixtureV1>) {
        for (prior in priors) when (d.family) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> {
                assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE, prior.family)
                assertThrows<Exception> { d.ownerPhases.verify(checkNotNull(d.readback)) }
                assertThrows<Exception> { d.ownerPhases.verify(checkNotNull(d.ownerWork), checkNotNull(prior.readback), checkNotNull(d.ownerLane)) }
                assertThrows<Exception> { d.ownerPhases.verify(checkNotNull(prior.ownerWork), checkNotNull(d.readback), checkNotNull(prior.ownerLane)) }
                assertThrows<Exception> { d.ownerPhases.verify(checkNotNull(d.ownerWork), checkNotNull(d.readback), checkNotNull(prior.ownerLane)) }
                d.ownerPublisher.reserve().use { foreign -> assertThrows<Exception> { d.ownerPhases.verify(checkNotNull(d.ownerWork), checkNotNull(d.readback), foreign) } }
            }
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> {
                assertThrows<Exception> { d.allVerifyPhases.verify(checkNotNull(d.readback)) }
                assertThrows<Exception> { d.allVerifyPhases.verify(checkNotNull(d.allWork), checkNotNull(prior.readback), checkNotNull(d.allLane)) }
                d.allPublisher.reserve().use { foreign -> assertThrows<Exception> { d.allVerifyPhases.verify(checkNotNull(d.allWork), checkNotNull(d.readback), foreign) } }
            }
            else -> {
                assertThrows<Exception> { d.adminPhases.verify(checkNotNull(d.readback)) }
                assertThrows<Exception> { d.adminPhases.verify(checkNotNull(d.adminWork), checkNotNull(prior.readback), checkNotNull(d.adminLane)) }
                d.adminPublisher.reserve().use { foreign -> assertThrows<Exception> { d.adminPhases.verify(checkNotNull(d.adminWork), checkNotNull(d.readback), foreign) } }
            }
        }
    }

    private fun verifyAndRefuseDirectApply(d: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        when (d.family) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> {
                val proof = d.ownerPhases.verify(checkNotNull(d.ownerWork), checkNotNull(d.readback), checkNotNull(d.ownerLane))
                val calls = d.deletion.calls.size
                assertThrows<Exception> { d.ownerPhases.apply(checkNotNull(d.ownerWork), proof) }
                assertEquals(calls, d.deletion.calls.size)
            }
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> {
                val proof = d.allVerifyPhases.verify(checkNotNull(d.allWork), checkNotNull(d.readback), checkNotNull(d.allLane))
                val calls = d.deletion.calls.size
                val direct = ComplaintOwnerDeleteAllApplyPhaseExecutor(d.deletionOwner,
                    JdbcComplaintOwnerDeleteAllApplyStore(d.deletion, d.capacity, d.audit, d.graph, d.allVerification))
                assertThrows<Exception> { direct.apply(checkNotNull(d.allWork), proof) }
                assertEquals(calls, d.deletion.calls.size)
            }
            else -> {
                val proof = d.adminPhases.verify(checkNotNull(d.adminWork), checkNotNull(d.readback), checkNotNull(d.adminLane))
                val calls = d.deletion.calls.size
                assertThrows<Exception> { d.adminPhases.apply(checkNotNull(d.adminWork), proof) }
                assertEquals(calls, d.deletion.calls.size)
            }
        }
        d.assertReleased()
    }

    private fun assertNoCurrentDeletionGate(calls: List<TestRegisteredInitialDeletionSqlCallV1>) = assertFalse(calls.any {
        it.sql == TestRegisteredInitialCheckpointDeletionSqlV1.current || it.sql == TestRegisteredRecurrentCheckpointDeletionSqlV1.branch ||
            it.sql == TestRegisteredRecurrentCheckpointDeletionSqlV1.current || it.sql == TestRegisteredRecurrentCheckpointDeletionSqlV1.owned ||
            it.sql == TestRegisteredRecurrentCheckpointDeletionSqlV1.historyCounts
    })

    private fun assertOwnerAppliedReplay(d: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val rows = d.image(); val counters = d.counters(); val providers = d.native.counts(); val calls = d.deletion.calls.size
        d.ingress.withIngress(d.request()) { context ->
            d.ingress.startOwnerDelete(context)
            val identity = d.creators.single().identity()
            val preflight = d.ownerReads.preflight(identity, d.ownerCandidate.tuple)
            assertNull(preflight.failure); assertSame(ComplaintOwnerDeleteReceipt.Applied, preflight.receipt)
            val status = d.ownerReads.status(identity, d.ownerCandidate.tuple)
            assertNull(status.failure); assertSame(ComplaintOwnerDeleteReceipt.Applied, status.receipt)
        }
        assertEquals(calls, d.deletion.calls.size); assertEquals(rows, d.image()); assertEquals(counters, d.counters()); assertEquals(providers, d.native.counts())
    }

    private fun deletionEvidence(d: TestRegisteredInitialCheckpointDeletionFixtureV1): Map<String, List<String>> {
        val event = checkNotNull(d.event).route.eventId
        return linkedMapOf("complaint_journal_publications" to "event_id", "complaint_idempotency_receipts" to "publication_ref",
            "installation_deletion_receipts" to "publication_ref", "complaint_recovery_capacity_reservations" to "event_id",
            "complaint_deletion_journal_applied" to "event_id").mapValues { (table, column) -> d.observer.queryForList(
                "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM $table r WHERE data_scope_id = ? AND $column = ? ORDER BY to_jsonb(r)::text",
                String::class.java, d.scope, event) }
    }

    fun genuineCurrentCreateReplyEdit(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { f, c ->
        assertEquals(3, c.process.consumers.ownerCreatePolicy.globalPerHour, "Exactly one precursor CREATE plus this CREATE and REPLY were declared before D.")
        val before = c.counters()
        val immutable = f.immutableImage()
        val control = f.first.controlImage(); val global = f.first.globalImage()
        val notices = c.observer.queryForList("SELECT to_jsonb(c)::text FROM complaints c WHERE data_scope_id = ? AND ownership = 'SYSTEM' ORDER BY id",
            String::class.java, c.scope)
        val reports = checkNotNull(c.observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, c.scope))
        val resources = checkNotNull(c.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ?", Long::class.java, c.scope))
        val createdAudits = checkNotNull(c.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", Long::class.java, c.scope))
        val editedAudits = checkNotNull(c.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CONTENT_EDITED'", Long::class.java, c.scope))
        val seen = mutableSetOf<PersistencePhasePath>()
        c.jdbc.after = { path, sql -> if (path in setOf(REGISTERED_CREATE, REGISTERED_REPLY, REGISTERED_EDIT) &&
            sql == TestRegisteredRecurrentCheckpointCurrentSqlV1.current) {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val holder = TransactionSynchronizationManager.getResource(c.jdbc.dataSource!!) as ConnectionHolder
            val observed = c.jdbc.observations.getValue(phase)
            assertSame(observed.lease, ownedPoolLease(holder.connection))
            assertTrue(f.first.p.advisory(holder.connection, "complaint-maintenance-v1", "ShareLock"))
            assertFalse(f.first.p.advisory(holder.connection, "complaint-journal-epoch", "ShareLock"))
            assertEquals(0, f.runtime.pools.catalogCoordinator.activeSnapshotOwners())
            if (path === REGISTERED_EDIT) {
                assertTrue(poolTestField<Any?>(phase.ownerEdit, "retained") != null)
                assertNull(poolTestField<Any?>(phase.ownerOperation, "retained"), "EDIT never borrows CREATE's operation boundary.")
            } else {
                assertTrue(poolTestField<Any?>(phase.ownerOperation, "retained") != null)
                assertNull(poolTestField<Any?>(phase.ownerEdit, "retained"), "CREATE/REPLY never borrow EDIT's handoff.")
            }
            seen.add(path)
        } }
        val report = c.attempt()
        val reply = registeredReplyAttempt(c.actor, report.input.id)
        val edit = registeredEditAttempt(c.actor, report.input.id)
        try {
            c.assertApplied(c.create(report), report); c.assertReleased()
            c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
            c.assertApplied(c.reply(reply), reply); c.assertReleased()
            c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE.scaled(2))
            c.assertApplied(c.edit(edit), edit); c.assertReleased()
            c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE.scaled(2) + ComplaintCapacityCharges.OWNER_EDIT)
        } finally { c.jdbc.after = { _, _ -> } }
        assertEquals(setOf(REGISTERED_CREATE, REGISTERED_REPLY, REGISTERED_EDIT), seen)
        assertConsumerSql(c.createSql(), 5); assertConsumerSql(c.replySql(), 7); assertConsumerSql(c.editSql(), 6)
        assertTrue((c.createPhases() + c.replyPhases() + c.editPhases()).all { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        assertEquals(3, c.createPhases().size + c.replyPhases().size + c.editPhases().size)
        assertEquals(reports + 2, c.observer.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ?", Long::class.java, c.scope))
        assertEquals(resources + 2, c.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE data_scope_id = ?", Long::class.java, c.scope))
        assertEquals(createdAudits + 2, c.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", Long::class.java, c.scope))
        assertEquals(editedAudits + 1, c.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CONTENT_EDITED'", Long::class.java, c.scope))
        assertEquals(true, c.observer.queryForObject("SELECT owner_id = ? AND kind = 'REPORT' AND version = 2 AND subject = 'Registered edited subject' " +
            "AND body = E'Registered edited body\\nline' FROM complaints WHERE data_scope_id = ? AND id = ?", Boolean::class.java, c.actor.id, c.scope, report.input.id))
        assertEquals(true, c.observer.queryForObject("SELECT owner_id = ? AND parent_resource_id = ? AND kind = 'REPLY' AND version = 1 " +
            "AND body = E'Registered reply\\nline' FROM complaints WHERE data_scope_id = ? AND id = ?", Boolean::class.java, c.actor.id, report.input.id, c.scope, reply.input.id))
        assertEquals(notices, c.observer.queryForList("SELECT to_jsonb(c)::text FROM complaints c WHERE data_scope_id = ? AND ownership = 'SYSTEM' ORDER BY id",
            String::class.java, c.scope))
        assertEquals(immutable, f.immutableImage()); assertEquals(control, f.first.controlImage()); assertEquals(global, f.first.globalImage())

        // All three born-with CREATE quota members are now spent. Historical CREATE's v1 receipt
        // still replays after EDIT's v2, and every exact receipt bypasses closed new-work controls.
        assertEquals(1, c.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", c.scope))
        val closed = c.state(); val counters = c.counters(); val history = f.immutableImage()
        c.jdbc.calls.clear()
        c.assertApplied(c.create(report), report); c.assertApplied(c.status(report), report)
        c.assertApplied(c.reply(reply), reply); c.assertApplied(c.replyStatus(reply), reply)
        c.assertApplied(c.edit(edit), edit); c.assertApplied(c.editStatus(edit), edit)
        assertTrue(c.createSql().isEmpty() && c.replySql().isEmpty() && c.editSql().isEmpty())
        assertFalse(c.jdbc.calls.any { it.second == TestRegisteredRecurrentCheckpointCurrentSqlV1.branch ||
            it.second == TestRegisteredRecurrentCheckpointCurrentSqlV1.current || "complaint_capacity_counters" in it.second })
        assertEquals(closed, c.state()); assertEquals(counters, c.counters()); assertEquals(history, f.immutableImage())
    }

    fun currentConsumerCheckpointDrift(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { f, c ->
        val original = f.control()
        val attempt = c.attempt()
        val before = c.counters(); val history = f.immutableImage()
        val hash = ByteArray(32) { 0x79.toByte() }
        val changes = linkedMapOf<String, Any>(
            "checkpoint_generation" to (original.getValue("checkpoint_generation") as Number).toLong() + 1,
            "checkpoint_fencing_token" to (original.getValue("checkpoint_fencing_token") as Number).toLong() + 1,
            "checkpoint_catalog_generation" to (original.getValue("checkpoint_catalog_generation") as Number).toLong() + 1,
            "checkpoint_catalog_hash" to hash, "checkpoint_writer_generation" to UUID.randomUUID(),
            "checkpoint_cutoff_epoch" to 1L, // Genuine recurrent state cannot fall back to the initial parser.
            "checkpoint_configuration_hash" to hash, "checkpoint_database_identity" to UUID.randomUUID(), "checkpoint_restore_identity" to UUID.randomUUID(),
            "checkpoint_started_at" to Timestamp.from((original.getValue("checkpoint_started_at") as Timestamp).toInstant().minusNanos(1000)),
            "checkpoint_completed_at" to Timestamp.from((original.getValue("checkpoint_completed_at") as Timestamp).toInstant().plusNanos(1000)),
            "checkpoint_object_count" to (original.getValue("checkpoint_object_count") as Number).toLong() + 1,
            "checkpoint_byte_count" to (original.getValue("checkpoint_byte_count") as Number).toLong() + 1,
            "seal_object_version" to "foreign-recurrent-version",
            "seal_retain_until" to Timestamp.from((original.getValue("seal_retain_until") as Timestamp).toInstant().minusSeconds(1)),
        )
        for ((column, value) in changes) {
            assertEquals(1, c.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, c.scope))
            try {
                val damaged = c.state(); c.jdbc.calls.clear()
                refused { c.create(attempt) }; c.assertReleased()
                assertEquals(1, c.createSql().count { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.current }, column)
                assertFalse(c.createSql().contains(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate), column)
                c.assertNoCounterSql()
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, c.createPhases().last().databaseOutcome())
                assertEquals(damaged, c.state()); assertEquals(before, c.counters()); assertEquals(history, f.immutableImage())
                refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.status(attempt) }
            } finally {
                // Restore only the negative column drift to its captured actual producer value.
                assertEquals(1, c.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], c.scope))
            }
        }
        c.assertApplied(c.create(attempt), attempt); c.assertApplied(c.status(attempt), attempt)
        c.assertCharge(before, ComplaintCapacityCharges.OWNER_CREATE)
    }

    fun currentConsumerMissingHistory(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { f, c ->
        val completed = c.attempt(); c.assertApplied(c.create(completed), completed); c.assertReleased()
        val current = f.control(); val archived = f.history()
        assertEquals(2, archived.size)
        assertThrows<DataIntegrityViolationException> {
            c.observer.update("DELETE FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ? AND ordinal = 2", c.scope)
        }
        // Explicit NEGATIVE disposable-DB restore damage. Ordinary immutable DELETE remains
        // forbidden above. Never insert/repair a checkpoint or proof; reenable before committing.
        c.raw { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_active_checkpoint_history DISABLE TRIGGER complaint_test_active_history_immutable") }
                connection.prepareStatement("DELETE FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ? AND ordinal = 2").use {
                    it.setObject(1, c.scope); assertEquals(1, it.executeUpdate())
                }
                connection.createStatement().use { it.execute("ALTER TABLE complaint_test_active_checkpoint_history ENABLE TRIGGER complaint_test_active_history_immutable") }
                connection.commit()
            } finally { connection.rollback() } // Any pre-COMMIT error also rolls back the trigger change.
        }
        assertEquals(1, f.history().size)
        assertArrayEquals(archived.first()["entry_bytes"] as ByteArray, f.history().single()["entry_bytes"] as ByteArray)
        assertArrayEquals(archived.first()["checkpoint_bytes"] as ByteArray, f.history().single()["checkpoint_bytes"] as ByteArray)
        assertArrayEquals(current["checkpoint_bytes"] as ByteArray, f.control()["checkpoint_bytes"] as ByteArray)
        assertArrayEquals(current["checkpoint_hash"] as ByteArray, f.control()["checkpoint_hash"] as ByteArray)
        val damaged = c.state(); val history = f.immutableImage(); val counters = c.counters()
        val next = c.attempt(); c.jdbc.calls.clear()
        refused { c.create(next) }; c.assertReleased(); c.assertNoCounterSql()
        assertEquals(1, c.createSql().count { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.current })
        assertFalse(c.createSql().contains(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate))
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, c.createPhases().last().databaseOutcome())
        assertEquals(damaged, c.state()); assertEquals(history, f.immutableImage()); assertEquals(counters, c.counters())
        c.jdbc.calls.clear()
        c.assertApplied(c.create(completed), completed); c.assertApplied(c.status(completed), completed)
        refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.status(next) }
        assertTrue(c.createSql().isEmpty(), "A missing current history row is not a reason to withhold an exact committed receipt.")
        assertEquals(damaged, c.state()); assertEquals(history, f.immutableImage()); assertEquals(counters, c.counters())
    }

    fun oldInitialProfileRemainsInitialOnly(tls: VersionBoundPersistenceConnectedFixture, path: RecurrentConsumerPath) {
        val profile = when (path) {
            RecurrentConsumerPath.CREATE -> VersionBoundTestInitialCheckpointCreateV1.PROFILE
            RecurrentConsumerPath.REPLY -> VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE
            RecurrentConsumerPath.EDIT -> VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE
        }
        withCurrentConsumer(tls, profile = profile) { f, c ->
            assertEquals(2, c.process.consumers.ownerCreatePolicy.globalPerHour)
            val before = c.state(); val counters = c.counters(); val history = f.immutableImage()
            when (path) {
                RecurrentConsumerPath.CREATE -> c.attempt().let { attempt ->
                    refused { c.create(attempt) }; refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.status(attempt) }
                }
                RecurrentConsumerPath.REPLY -> registeredReplyAttempt(c.actor, c.notice()).let { attempt ->
                    refused { c.reply(attempt) }; refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.replyStatus(attempt) }
                }
                RecurrentConsumerPath.EDIT -> registeredEditAttempt(c.actor, f.precursor.reports.single().input.id).let { attempt ->
                    refused { c.edit(attempt) }; refused(ComplaintOwnerOperationFailure.OPERATION_NOT_FOUND) { c.editStatus(attempt) }
                }
            }
            val sql = c.createSql() + c.replySql() + c.editSql()
            assertEquals(1, sql.count { it == TestActiveInitialCheckpointSqlV1.currentForOwnerCreate })
            assertFalse(sql.any { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.branch || it == TestRegisteredRecurrentCheckpointCurrentSqlV1.current })
            assertFalse(sql.any { "complaint_capacity_counters" in it })
            assertEquals(before, c.state()); assertEquals(counters, c.counters()); assertEquals(history, f.immutableImage())
        }
    }

    fun currentConsumerClaimLoser(tls: VersionBoundPersistenceConnectedFixture, path: RecurrentConsumerPath) =
        withCurrentConsumer(tls, ordinaryPoolSize = 3) { _, c ->
            // The unchanged P-1 bound needs P=3 for two concurrent originals, not a relabelled admission.
            val ordinary = c.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.ORDINARY }
            assertEquals(3, ordinary.hikari.sizing.maximumPoolSize)
            assertEquals(2, c.exchange.ordinary.admission.ownerLimit)
            when (path) {
                RecurrentConsumerPath.CREATE -> TestRegisteredInitialCheckpointCreateRaceCasesV1.claimLoser(c)
                RecurrentConsumerPath.REPLY -> TestRegisteredInitialCheckpointCreateRaceCasesV1.replyClaimLoser(c)
                RecurrentConsumerPath.EDIT -> TestRegisteredInitialCheckpointCreateRaceCasesV1.editClaimLoser(c)
            }
        }

    fun currentConsumerExactGraph(tls: VersionBoundPersistenceConnectedFixture, path: RecurrentConsumerPath) = withCurrentConsumer(tls) { _, c ->
        when (path) {
            RecurrentConsumerPath.CREATE -> TestRegisteredInitialCheckpointCreateCasesV1.exactGraphOnly(c)
            RecurrentConsumerPath.REPLY -> TestRegisteredInitialCheckpointCreateRaceCasesV1.replyExactGraph(c)
            RecurrentConsumerPath.EDIT -> TestRegisteredInitialCheckpointCreateRaceCasesV1.editExactGraph(c)
        }
    }

    fun currentConsumerNarrowFactories(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { _, c ->
        TestRegisteredInitialCheckpointCreateRaceCasesV1.narrowerFactoriesStayNarrow(c)
    }

    fun currentConsumerNaturalFreshness(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls, shortFreshness = true) { _, c ->
        TestRegisteredInitialCheckpointCreateRaceCasesV1.naturalFreshness(c)
    }

    fun currentConsumerWaitedExpiry(tls: VersionBoundPersistenceConnectedFixture, path: RecurrentConsumerPath, resource: Boolean) =
        withCurrentConsumer(tls, shortFreshness = true) { _, c ->
            when (path) {
                RecurrentConsumerPath.REPLY -> TestRegisteredInitialCheckpointCreateRaceCasesV1.replyWaitedCheckpointExpiry(c, resource)
                RecurrentConsumerPath.EDIT -> TestRegisteredInitialCheckpointCreateRaceCasesV1.editWaitedCheckpointExpiry(c, resource)
                RecurrentConsumerPath.CREATE -> error("CREATE has its separate actual credential wait and natural-expiry cut.")
            }
        }

    fun currentConsumerCredentialWait(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { _, c ->
        TestRegisteredInitialCheckpointCreateRaceCasesV1.credentialWait(c)
    }

    fun currentConsumerCompletionFailures(tls: VersionBoundPersistenceConnectedFixture) = withCurrentConsumer(tls) { _, c ->
        TestRegisteredInitialCheckpointCreateRaceCasesV1.completionFailures(c)
    }

    /** Thin reuse of the actual nonempty producer. The Completed observation is never a consumer argument. */
    private fun withCurrentConsumer(tls: VersionBoundPersistenceConnectedFixture, shortFreshness: Boolean = false,
        profile: String = VersionBoundTestInitialCheckpointCreateV1.RECURRENT_PROFILE,
        ordinaryPoolSize: Int = 2,
        action: (TestActiveRecurrentFixtureV1, TestRegisteredInitialCheckpointCreateFixtureV1) -> Unit) =
        withRecurrentFixture(tls, initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, profile), shortFreshness = shortFreshness,
            ordinaryPoolSize = ordinaryPoolSize) { f ->
            val initial = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
            val before = f.counters()
            val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
            f.assertSuccessful()
            assertEquals(2L, completed.cutoffEpoch); assertEquals(3L, f.control()["publication_epoch"])
            assertEquals(1L, f.document().objectCount); assertEquals(f.record.stored.bytes.size.toLong(), f.document().byteCount)
            assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), f.raw.order)
            assertEquals(2, f.history().size)
            assertArrayEquals(initial, f.history().first()["checkpoint_bytes"] as ByteArray)
            assertArrayEquals(f.control()["checkpoint_bytes"] as ByteArray, f.history().last()["checkpoint_bytes"] as ByteArray)
            f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2))
            val c = f.retainedCreator()
            assertEquals(if (profile == VersionBoundTestInitialCheckpointCreateV1.RECURRENT_PROFILE) TestRegisteredRecurrentCheckpointCurrentSqlV1.current
                else TestActiveInitialCheckpointSqlV1.currentForOwnerCreate, c.currentCheckpointSql())
            val providers = f.providerCounts()
            action(f, c)
            c.assertReleased(); f.assertReleased()
            assertEquals(providers, f.providerCounts(), "Registered current consumers cannot run any catalog, seal, queue or ordinary native provider.")
        }

    private fun assertConsumerSql(sql: List<String>, checks: Int) {
        val claim = sql.indexOfFirst { it.startsWith("INSERT INTO complaint_idempotency_receipts") }
        val global = sql.indexOf(TestActiveInitialCheckpointSqlV1.lockGlobal)
        val scope = sql.indexOf(TestActiveInitialCheckpointSqlV1.lockScope)
        val current = sql.indexOf(TestRegisteredRecurrentCheckpointCurrentSqlV1.current)
        val counters = sql.indexOfFirst { "FROM complaint_capacity_counters" in it && "FOR UPDATE" in it }
        assertTrue(claim >= 0 && global > claim && scope > global && current > scope && counters > current)
        assertEquals(1, sql.count { it == TestActiveInitialCheckpointSqlV1.lockGlobal })
        assertEquals(1, sql.count { it == TestActiveInitialCheckpointSqlV1.lockScope })
        assertEquals(checks, sql.count { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.current })
        assertEquals(checks, sql.count { it == TestRegisteredRecurrentCheckpointCurrentSqlV1.branch })
        assertFalse(sql.contains(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate), "A recurrent reader never falls back to initial eligibility.")
        val completion = sql.indexOfLast { "UPDATE complaint_idempotency_receipts" in it }
        val lastRead = sql.lastIndexOf(TestRegisteredRecurrentCheckpointCurrentSqlV1.current)
        assertTrue(lastRead > counters && lastRead < completion)
        assertTrue(sql.subList(lastRead + 1, completion).contains("SELECT clock_timestamp()"), "Final freshness is sampled after the last bounded history read.")
    }

    fun genuineNonempty(tls: VersionBoundPersistenceConnectedFixture, family: ComplaintJournalDeletionKindV1, maximumVersions: Long = 10_000) {
        var stage = "SETUP"
        var observedFixture: TestActiveRecurrentFixtureV1? = null
        var reportedFailure: TestActiveRecurrentExceptionV1? = null
        fun report(failure: TestActiveRecurrentExceptionV1) {
            reportedFailure = failure
            try {
                // Retained observations only: these are NOT an attribution to a failing SQL call.
                // Product checkpoint cleanup may already have run before the same exception escapes.
                val calls = observedFixture?.probe?.calls
                val last = calls?.lastOrNull()
                val ordinal = if (last == null) 0 else calls?.count { it.phase === last.phase } ?: 0
                println("TEST_ACTIVE_RECURRENT_CASE_FAILURE stage=$stage code=RECURRENT_REFUSED " +
                    "fixtureReady=${observedFixture != null} observedStep=${observedFixture?.original?.step?.name ?: "NONE"} " +
                    "lastAttemptedSqlStep=${last?.step?.name ?: "NONE"} lastPhaseCallOrdinal=$ordinal")
            } catch (_: Throwable) { /* Diagnostics must not replace the original failure. */ }
        }
        try {
            withRecurrentFixture(tls, family, maximumVersions = maximumVersions) { f ->
                observedFixture = f
                try {
                    val initial = f.control()
                    val initialBytes = (initial.getValue("checkpoint_bytes") as ByteArray).copyOf()
                    val initialHash = (initial.getValue("checkpoint_hash") as ByteArray).copyOf()
                    val before = f.counters()
                    val domain = f.domainImage()
                    val oldSeal = f.immutableImage().getValue("complaint_test_active_seal_intents")
                    var archivedBeforeClear = false
                    val staged = mutableSetOf<Int>()
                    f.probe.before = { call -> if (call.sql == TestActiveRecurrentSqlV1.request) {
                        val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                        val archive = holder.queryForMap("SELECT checkpoint_bytes,checkpoint_hash FROM complaint_test_active_checkpoint_history WHERE data_scope_id=? AND ordinal=1", f.scope)
                        assertArrayEquals(initialBytes, archive["checkpoint_bytes"] as ByteArray)
                        assertArrayEquals(initialHash, archive["checkpoint_hash"] as ByteArray)
                        assertArrayEquals(initialBytes, holder.queryForMap("SELECT checkpoint_bytes FROM complaint_journal_control WHERE data_scope_id=?", f.scope)["checkpoint_bytes"] as ByteArray)
                        archivedBeforeClear = true // Exact physical archive already exists in this actual REQUEST transaction.
                    } }
                    f.probe.after = { call -> if (call.sql == TestActiveRecurrentScanSqlV1.completeRun) {
                        val holder = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                        val pass = f.passNumber(); staged.add(pass)
                        val run = holder.queryForMap("SELECT * FROM complaint_journal_scan_runs WHERE data_scope_id=? AND pass=?", f.scope, pass)
                        assertEquals(1L, run["entry_count"]); assertEquals("COMPLETE", run["state"])
                        assertEquals(4_416L, run["active_recurrent_storage_bytes"])
                        assertNull(run["active_initial_seal_token"])
                        assertEquals(1L, holder.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries WHERE data_scope_id=? AND pass=? AND replay_state='APPLIED'",
                            Long::class.java, f.scope, pass))
                    } }
                    stage = "BEGIN"
                    val original = f.begin() // Same single begin formerly evaluated as checkpoint's default argument.
                    stage = "CHECKPOINT"
                    val result = f.checkpoint(original)
                    stage = "ASSERTIONS"
                    val completed = assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, result)
                    f.assertSuccessful()
                    assertTrue(archivedBeforeClear); assertEquals(setOf(1, 2), staged)
                    assertEquals(2L, completed.cutoffEpoch); assertEquals(f.scope, completed.scope)
                    assertEquals(3L, f.control()["publication_epoch"])
                    val history = f.history()
                    assertEquals(2, history.size)
                    assertArrayEquals(initialBytes, history[0]["checkpoint_bytes"] as ByteArray)
                    assertArrayEquals(initialHash, history[0]["checkpoint_hash"] as ByteArray)
                    assertArrayEquals(f.control()["checkpoint_bytes"] as ByteArray, history[1]["checkpoint_bytes"] as ByteArray)
                    assertEquals(oldSeal, f.immutableImage().getValue("complaint_test_active_seal_intents"))
                    assertEquals(domain, f.domainImage())
                    f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2))
                    val doc = f.document()
                    assertEquals(1L, doc.objectCount); assertEquals(f.record.stored.bytes.size.toLong(), doc.byteCount)
                    assertEquals(completed.checkpointSha256, Sha256.hex(doc.canonicalBytes()))
                    assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), f.raw.order)
                    assertEquals(listOf(0L, 1L), doc.ranges.map { it.eventCount })
                    assertEquals(manifest(f, 2, 2, listOf(f.record.stored.key to f.record.stored.version)).first, doc.ranges[1].manifestSha256)
                    assertEquals(manifest(f, 1, 2, listOf(f.record.stored.key to f.record.stored.version)).first, doc.first.manifestSha256)
                    val beforeRepeat = f.image(); val calls = f.probe.calls.size
                    assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(checkNotNull(f.original)) }
                    assertEquals(beforeRepeat, f.image()); assertEquals(calls, f.probe.calls.size)
                } catch (failure: TestActiveRecurrentExceptionV1) {
                    report(failure) // Before fixture unwinding; expected refusals stay inside assertThrows.
                    throw failure
                } finally { stage = "TEARDOWN" }
            }
        } catch (failure: TestActiveRecurrentExceptionV1) {
            if (reportedFailure !== failure) report(failure)
            throw failure
        }
    }

    fun nextRangeStillReadsEveryPriorNonemptyRange(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint()); f.assertSuccessful()
        val priorBytes = (f.control().getValue("checkpoint_bytes") as ByteArray).copyOf()
        val priorHistory = f.history().map { it.getValue("entry_bytes") as ByteArray }
        val before = f.counters(); val domain = f.domainImage()
        val firstToken = f.intents().single().getValue("operation_token")
        val nativePuts = f.first.native.requests.count { it.kind == "PUT" }
        assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint()); f.assertSuccessful()
        val doc = f.document()
        assertEquals(3L, doc.cutoffEpoch); assertEquals(4L, f.control()["publication_epoch"])
        assertEquals(listOf(0L, 1L, 0L), doc.ranges.map { it.eventCount })
        assertEquals(1L, doc.objectCount)
        assertEquals(Sha256.hex(priorBytes), doc.predecessorCheckpointSha256)
        assertEquals(firstToken, f.intents().first().getValue("operation_token"))
        assertEquals(3, f.history().size)
        priorHistory.forEachIndexed { index, bytes -> assertArrayEquals(bytes, f.history()[index]["entry_bytes"] as ByteArray) }
        assertArrayEquals(priorBytes, f.history()[1]["checkpoint_bytes"] as ByteArray)
        assertEquals(nativePuts + 1, f.first.native.requests.count { it.kind == "PUT" })
        assertEquals(4, f.raw.requests.count { it.kind == "GET" }, "Two full nonempty reads on BOTH rotations, not just the new empty range.")
        assertEquals(domain, f.domainImage())
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY)
    }

    fun missingApplyIsOnlyPrivateRecoveryInput(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls, applied = false) { f ->
        val before = f.counters(); val domain = f.domainImage()
        val original = f.begin()
        val result = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint(original))
        f.assertReleased()
        val input = result.input()
        assertSame(f.registration, input.registration); assertSame(f.assembly, input.assembly)
        assertSame(f.process.pools, input.resources); input.requireCurrentUse(f.registration, f.assembly, f.process.consumers.journalRouting)
        assertEquals(f.record.stored.version, input.versionId)
        assertEquals(Sha256.hex(f.record.stored.bytes), input.wireSha256)
        assertEquals(f.record.event.semanticSha256, input.event.semanticSha256)
        assertEquals(listOf(1), f.scans().map { (it["pass"] as Number).toInt() })
        assertEquals("PENDING", f.entries().single()["replay_state"])
        assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
        assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
        assertEquals(domain, f.domainImage())
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
            TestActiveRecurrentStorageV1.SCAN_RUN + TestActiveRecurrentStorageV1.SCAN_ENTRY)
        val native = f.raw.order.toList()
        assertSame(result, result.resume(), "No Boolean or the input itself can become APPLY completion.")
        assertEquals(native, f.raw.order); assertNull(f.control()["checkpoint_result"])
        assertThrows<TestActiveRecurrentExceptionV1> { original.close() }
        assertThrows<TestActiveRecurrentExceptionV1> { result.input() }
        assertThrows<TestActiveRecurrentExceptionV1> { result.resume() }
        assertEquals(domain, f.domainImage())
    }

    fun genuinePreparedPublicationIsResolvedWithoutInventingApply(tls: VersionBoundPersistenceConnectedFixture) =
        withRecurrentFixture(tls, applied = false, verified = false) { f ->
            fun immutablePublication() = f.observer.queryForObject(
                "SELECT (to_jsonb(p)-ARRAY['state','object_version','ciphertext_hash','object_created_at','retain_until','verified_at','verification_bytes','verification_hash'])::text " +
                    "FROM complaint_journal_publications p WHERE data_scope_id=? AND event_id=?", String::class.java, f.scope, f.record.event.route.eventId)
            val immutable = immutablePublication()
            val domain = f.domainImage() - "complaint_journal_publications"
            val before = f.counters()
            val publisher = f.raw.deletion.publisher
            val gets = publisher.requests.count { it.kind == "GET" }
            val puts = publisher.requests.count { it.kind == "PUT" }
            val generated = publisher.generated()
            assertEquals("PREPARED", f.precursor.publication()["state"])
            assertNull(f.precursor.publication()["verification_bytes"])
            val result = assertInstanceOf(TestActiveRecurrentV1.RecoveryRequired::class.java, f.checkpoint())
            f.assertReleased()
            val publication = f.precursor.publication()
            assertEquals("VERIFIED", publication["state"])
            assertEquals(immutable, immutablePublication())
            assertEquals(f.record.stored.version, publication["object_version"])
            assertArrayEquals(recurrentBytes(Sha256.hex(f.record.stored.bytes)), publication["ciphertext_hash"] as ByteArray)
            val proof = publication["verification_bytes"] as ByteArray
            assertArrayEquals(recurrentBytes(Sha256.hex(proof)), publication["verification_hash"] as ByteArray)
            assertNull(publication["applied_at"])
            assertEquals(puts, publisher.requests.count { it.kind == "PUT" }, "The genuine preexisting object is reread rather than re-encrypted.")
            assertTrue(publisher.requests.count { it.kind == "GET" } > gets)
            assertEquals(generated, publisher.generated())
            assertTrue(f.probe.calls.any { it.step === TestActiveRecurrentStepV1.EVIDENCE && it.sql == TestActiveCutoffPublicationSqlV1.verified })
            assertEquals(0L, f.queue.count("complaint_deletion_journal_applied"))
            assertTrue(f.raw.queue.sqs.requests.isEmpty(), "Local PREPARED evidence is not a forged queue delivery.")
            assertEquals("PENDING", f.entries().single()["replay_state"])
            assertEquals(f.record.event.semanticSha256, result.input().event.semanticSha256)
            assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
            assertEquals(domain, f.domainImage() - "complaint_journal_publications")
            f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
                TestActiveRecurrentStorageV1.SCAN_RUN + TestActiveRecurrentStorageV1.SCAN_ENTRY)
            assertThrows<TestActiveRecurrentExceptionV1> { checkNotNull(f.original).close() }
            assertThrows<TestActiveRecurrentExceptionV1> { result.resume() }
        }

    fun captureWaitUsesFreshDatabaseTimeAndNoPooledHolderAcrossExclusiveEpoch(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        val ready = CountDownLatch(1)
        val stop = AtomicBoolean()
        OwnedCallerTestScope().use { callers ->
            val worker = callers.launch { f.first.independentTransaction { blocker, jdbc, holderPid ->
                jdbc.execute("SELECT pg_advisory_xact_lock_shared(hashtextextended('complaint-journal-epoch',0))")
                ready.countDown()
                var waitingPid: Int? = null
                awaitLifecycleFact(6_000) {
                    if (!stop.get()) {
                        jdbc.execute("SELECT pg_stat_clear_snapshot()") // This same blocker transaction must see the newly opened NONPOOLED waiter.
                        waitingPid = jdbc.query("SELECT a.pid FROM pg_stat_activity a WHERE a.datname=current_database() AND ?=ANY(pg_blocking_pids(a.pid)) " +
                            "AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid=a.pid AND l.locktype='advisory' AND l.mode='ExclusiveLock' AND NOT l.granted)",
                            { row, _ -> row.getInt(1) }, holderPid).singleOrNull()
                    }
                    stop.get() || waitingPid != null
                }
                check(!stop.get()) { "Recurrent producer stopped before its actual exclusive epoch wait." }
                assertEquals(0, f.runtime.pools.catalogCoordinator.activeSnapshotOwners())
                assertEquals("REQUESTED", f.control()["rotation_state"])
                assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE pid=? AND locktype='tuple'", Long::class.java, waitingPid))
                assertFalse(checkNotNull(waitingPid) in f.probe.observations.values.map { it.identity.first })
                val releasedAt = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
                blocker.rollback()
                releasedAt
            } }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, f.checkpoint())
                val releasedAt = worker.value()
                f.assertSuccessful()
                assertTrue((f.intents().single()["captured_at"] as Timestamp).toInstant() >= releasedAt)
                assertTrue(f.nativeSessions.isNotEmpty())
            } finally { stop.set(true) }
        }
    }

    /** Independent LP32 oracle, not the producer manifest/fold implementation. */
    internal fun manifest(f: TestActiveRecurrentFixtureV1, start: Long, end: Long, objects: List<Pair<String, String>>): Pair<String, Long> {
        val fields = listOf("kira-complaint-journal-epoch-seal-v1", "1", "manifest",
            f.process.consumers.journalConfiguration.declaration().writer.generationId,
            f.process.consumers.journalConfiguration.ordinaryPrefix, "TEST", f.scope.toString(), start.toString(), end.toString(), objects.size.toString()) +
            objects.flatMap { listOf(it.first, it.second, Sha256.hex(f.record.stored.bytes)) }
        val bytes = ByteArrayOutputStream().use { stream ->
            DataOutputStream(stream).use { output -> fields.forEach { value -> val utf8 = value.toByteArray(); output.writeInt(utf8.size); output.write(utf8) } }
            stream.toByteArray()
        }
        return Sha256.hex(bytes) to bytes.size.toLong()
    }
}
