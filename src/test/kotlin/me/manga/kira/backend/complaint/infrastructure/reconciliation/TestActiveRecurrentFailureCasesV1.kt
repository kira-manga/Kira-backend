package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class RecurrentListingCut {
    MISSING_FIRST, SECOND_DISAPPEARED, EXTRA_VERSION, DUPLICATE_VERSION, DELETE_MARKER,
    TRUNCATED, CONTINUATION, FOREIGN_SCOPE, HIGHER_KEY_TAMPER,
}
internal enum class RecurrentNativeCut { PRINCIPAL, VERSION, WIRE, METADATA, RETENTION, LOCK_MODE }
internal enum class RecurrentBindingCut { DESIRED_HASH, RESTORE, DATABASE, CATALOG, LEASE_EXPIRED, LEASE_REPLACED, CANCELLATION, BACKWARD_CLOCK, LATE_NATIVE_CLOSE }
internal enum class RecurrentCommitCut { BEFORE_COMMIT, AFTER_COMMIT, DEFERRED_COMMIT_UNKNOWN, UNRESOLVED_RELEASE }

/** Negative raw/SQL boundaries only. No fabricated positive event, APPLY row, time or success receipt. */
internal object TestActiveRecurrentFailureCasesV1 {
    fun inventory(tls: VersionBoundPersistenceConnectedFixture, cut: RecurrentListingCut) = withRecurrentFixture(tls) { f ->
        val before = f.counters(); val domain = f.domainImage()
        val pass = if (cut === RecurrentListingCut.SECOND_DISAPPEARED) 2 else 1
        f.raw.listing = { number, values -> if (number != pass) values else when (cut) {
            RecurrentListingCut.MISSING_FIRST, RecurrentListingCut.SECOND_DISAPPEARED -> emptyList()
            RecurrentListingCut.EXTRA_VERSION -> values + values.single().copy(version = "synthetic-extra-version")
            RecurrentListingCut.DUPLICATE_VERSION -> values + values.single()
            RecurrentListingCut.FOREIGN_SCOPE -> listOf(values.single().let { value ->
                value.copy(key = value.key.replace(f.scope.toString(), UUID.randomUUID().toString())).also { assertNotEquals(value.key, it.key) }
            })
            RecurrentListingCut.HIGHER_KEY_TAMPER -> listOf(values.single().let { value ->
                // NEGATIVE ONLY: existing ciphertext cannot be relabelled as a successful newer-epoch write.
                value.copy(key = value.key.replace("/epoch/0000000000000000002/", "/epoch/0000000000000000003/"))
                    .also { assertNotEquals(value.key, it.key) }
            })
            else -> values
        } }
        f.raw.listDocument = { number, xml -> if (number != pass) xml else when (cut) {
            RecurrentListingCut.DELETE_MARKER -> xml.replace("</ListVersionsResult>",
                "<DeleteMarker><Key>${OwnerDeleteAllJournalPublisherFixture.encoded(f.record.stored.key)}</Key><VersionId>deleted-version</VersionId><IsLatest>true</IsLatest>" +
                    "<LastModified>${f.record.stored.lastModified}</LastModified></DeleteMarker></ListVersionsResult>")
            RecurrentListingCut.TRUNCATED -> xml.replace("<IsTruncated>false</IsTruncated>", "<IsTruncated>true</IsTruncated>")
            RecurrentListingCut.CONTINUATION -> xml.replace("</ListVersionsResult>", "<NextKeyMarker>foreign</NextKeyMarker></ListVersionsResult>")
            else -> xml
        } }
        val original = f.begin()
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
        f.assertReleased(); assertNull(f.control()["checkpoint_result"])
        assertEquals(pass, f.scans().size)
        assertEquals("SCANNING", f.scans().last()["state"])
        assertNull(f.history().last()["checkpoint_bytes"])
        assertEquals(domain, f.domainImage())
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
            TestActiveRecurrentStorageV1.scanCharge(f.scans().size.toLong(), f.entries().size.toLong()))
        assertFalse(f.probe.calls.any { it.step === TestActiveRecurrentStepV1.SUCCESS })
        assertPoisoned(f, original)
    }

    fun native(tls: VersionBoundPersistenceConnectedFixture, cut: RecurrentNativeCut) = withRecurrentFixture(tls) { f ->
        val domain = f.domainImage(); val before = f.counters()
        f.raw.changeSts = { reply -> if (cut === RecurrentNativeCut.PRINCIPAL) {
            val changed = reply.bytes.decodeToString().replace("AROA" + "C".repeat(17), "AROA" + "D".repeat(17)).toByteArray()
            assertEquals(reply.bytes.size, changed.size); changed.copyInto(reply.bytes)
        } }
        f.raw.changeS3 = { request, reply -> if (request.kind == "GET") {
            val changed = when (cut) {
                RecurrentNativeCut.VERSION -> "x-amz-version-id" to "foreign-version"
                RecurrentNativeCut.METADATA -> "x-amz-meta-kira-journal-event-id" to "A".repeat(43)
                RecurrentNativeCut.RETENTION -> "x-amz-object-lock-retain-until-date" to Instant.now().minusSeconds(1).toString()
                RecurrentNativeCut.LOCK_MODE -> "x-amz-object-lock-mode" to "GOVERNANCE"
                else -> null
            }
            changed?.let { (key, value) -> reply.headers = reply.headers.filterKeys { !it.equals(key, true) } + (key to listOf(value)) }
            if (cut === RecurrentNativeCut.WIRE) reply.bytes[reply.bytes.lastIndex] = (reply.bytes.last().toInt() xor 1).toByte()
        } }
        val original = f.begin()
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
        f.assertReleased(); assertNull(f.control()["checkpoint_result"]); assertTrue(f.entries().isEmpty())
        assertEquals(if (cut === RecurrentNativeCut.PRINCIPAL) 0 else 1, f.scans().size)
        assertEquals(domain, f.domainImage())
        if (cut === RecurrentNativeCut.METADATA) {
            assertTrue(f.raw.order.contains("DECRYPT"), "A structurally valid metadata event-id is compared with the authenticated decoded event after KMS.")
        } else {
            assertFalse(f.raw.order.contains("DECRYPT"), "Cheap exact-object checks precede KMS.")
        }
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) +
            TestActiveRecurrentStorageV1.scanCharge(f.scans().size.toLong(), 0))
        assertPoisoned(f, original)
    }

    fun currentBinding(tls: VersionBoundPersistenceConnectedFixture, cut: RecurrentBindingCut) = withRecurrentFixture(tls) { f ->
        var reached = false
        val offset = f.first.native.offsetNanos
        f.raw.beforeS3 = { request -> if (!reached && request.kind == "LIST") {
            reached = true
            when (cut) {
                RecurrentBindingCut.DESIRED_HASH -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET desired_configuration_hash=? WHERE data_scope_id=?", ByteArray(32) { 7 }, f.scope))
                RecurrentBindingCut.RESTORE -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET restore_identity=? WHERE data_scope_id=?", UUID.randomUUID(), f.scope))
                RecurrentBindingCut.DATABASE -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET database_identity=? WHERE data_scope_id=?", UUID.randomUUID(), f.scope))
                RecurrentBindingCut.CATALOG -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET accepted_catalog_hash=? WHERE data_scope_id=?", ByteArray(32) { 8 }, UUID(0, 0)))
                RecurrentBindingCut.LEASE_EXPIRED -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET lease_expires_at=clock_timestamp()-interval '1 millisecond' WHERE data_scope_id=?", f.scope))
                RecurrentBindingCut.LEASE_REPLACED -> assertEquals(1, f.observer.update(
                    "UPDATE complaint_journal_control SET lease_owner=?,lease_token=lease_token+1 WHERE data_scope_id=?", UUID.randomUUID(), f.scope))
                RecurrentBindingCut.CANCELLATION -> throw CancellationException("Synthetic recurrent scan cancellation.")
                RecurrentBindingCut.BACKWARD_CLOCK -> f.first.native.offsetNanos -= 11_000_000_000L
                RecurrentBindingCut.LATE_NATIVE_CLOSE -> Unit
            }
        } }
        var delayed = false
        f.raw.onNativeClose = { if (cut === RecurrentBindingCut.LATE_NATIVE_CLOSE && f.passNumber() == 1 && !delayed) {
            delayed = true; f.first.native.offsetNanos += 11_000_000_000L
        } }
        val original = f.begin()
        try {
            if (cut === RecurrentBindingCut.CANCELLATION) {
                assertThrows<CancellationException> { f.checkpoint(original) }
                assertThrows<CancellationException> { f.checkpoint(original) }
            } else {
                assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
                assertPoisoned(f, original)
            }
            assertTrue(reached); if (cut === RecurrentBindingCut.LATE_NATIVE_CLOSE) assertTrue(delayed)
            f.raw.resetFaults(); f.assertReleased(); assertNull(f.control()["checkpoint_result"])
            assertEquals(1, f.scans().size)
            assertFalse(f.probe.calls.any { it.step === TestActiveRecurrentStepV1.SUCCESS })
        } finally { f.first.native.offsetNanos = offset } // Teardown only; never restart this failed original's clock.
    }

    fun rawCatalogCannotBeReplacedByHistoricalSuccess(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        val read = f.first.p.f.http.read; val respond = read.respond
        val before = f.image(); val counters = f.counters(); var reached = false
        read.respond = { request ->
            reached = true
            val reply = respond(request)
            S3CatalogReply(reply.bytes.copyOf().also { if (it.isNotEmpty()) it[0] = 0 }).apply {
                status = reply.status; headers = reply.headers; bodyPresent = reply.bodyPresent; chunkSize = reply.chunkSize
                beforeCall = reply.beforeCall; beforeRead = reply.beforeRead; onClose = reply.onClose; onAbort = reply.onAbort
            }
        }
        try { assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint() } } finally { read.respond = respond }
        assertTrue(reached); f.assertReleased(); assertTrue(f.raw.order.isEmpty())
        assertEquals(before, f.image()); assertEquals(counters, f.counters())
    }

    fun nativeCountLimitIncludesEveryListedVersion(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls, maximumVersions = 1) { f ->
        val before = f.counters()
        f.raw.listing = { _, values -> values + values.single().copy(version = "over-limit-version") }
        val original = f.begin()
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
        f.assertReleased(); assertNull(f.control()["checkpoint_result"])
        assertEquals(1, f.scans().size); assertTrue(f.entries().isEmpty()); assertFalse(f.raw.order.contains("GET1"))
        f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY.scaled(2) + TestActiveRecurrentStorageV1.SCAN_RUN)
        assertPoisoned(f, original)
    }

    fun framedByteLimitDoesNotBecomeCiphertextOrAnEntryOnlyAllowance(tls: VersionBoundPersistenceConnectedFixture) =
        withRecurrentFixture(tls, maximumBytes = 384) { f ->
            val before = f.counters()
            val empty = TestActiveRecurrentCasesV1.manifest(f, 2, 2, emptyList()).second
            val full = TestActiveRecurrentCasesV1.manifest(f, 2, 2, listOf(f.record.stored.key to f.record.stored.version)).second
            assertTrue(empty <= 384 && full > 384, "Independent LP32 header plus exact primary triple crosses the pre-D bound.")
            val original = f.begin()
            assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
            f.assertReleased(); assertNull(f.control()["checkpoint_result"])
            assertEquals("RESERVED", f.intents().single()["state"]); assertEquals(1, f.history().size)
            assertTrue(f.scans().isEmpty() && f.entries().isEmpty() && f.raw.order.isEmpty())
            f.assertCharge(before, TestActiveRecurrentStorageV1.INTENT + TestActiveRecurrentStorageV1.HISTORY)
            assertPoisoned(f, original)
        }

    fun insufficientOrdinaryHeadroomCannotBorrowTerminalReserve(tls: VersionBoundPersistenceConnectedFixture) = withRecurrentFixture(tls) { f ->
        assertEquals(1, f.observer.update("UPDATE complaint_capacity_counters SET actual_units=actual_units+free_units-4194303,free_units=4194303 " +
            "WHERE name='storage_bytes' AND free_units>4194304"))
        val before = f.counters(); val checkpoint = (f.control()["checkpoint_bytes"] as ByteArray).copyOf()
        val original = f.begin()
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
        f.assertReleased(); assertEquals(before, f.counters())
        assertTrue(f.history().isEmpty() && f.intents().isEmpty() && f.scans().isEmpty())
        assertArrayEquals(checkpoint, f.control()["checkpoint_bytes"] as ByteArray)
        assertTrue(f.raw.order.isEmpty()); assertPoisoned(f, original)
    }

    /** Actual Spring/PG completion cuts, NOT an actually dropped TLS COMMIT response qualification. */
    fun successCommit(tls: VersionBoundPersistenceConnectedFixture, cut: RecurrentCommitCut) {
        var bodyCompleted = false
        val run = { withRecurrentFixture(tls) { f ->
            var selected: PersistencePhaseContext? = null
            var before: Map<String, List<String>>? = null
            val resource = Any(); val sentinel = Any(); var bound = false
            f.probe.before = { call -> if (call.step === TestActiveRecurrentStepV1.SUCCESS && before == null) {
                before = successImage(JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource), f.scope)
            } }
            f.probe.after = { call -> if (selected == null && call.step === TestActiveRecurrentStepV1.SUCCESS && call.sql == TestActiveRecurrentSqlV1.success) {
                selected = call.phase
                if (cut === RecurrentCommitCut.DEFERRED_COMMIT_UNKNOWN) {
                    val jdbc = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                    jdbc.execute("CREATE TEMP TABLE kira_recurrent_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_recurrent_commit_cut VALUES (1),(1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        if (cut === RecurrentCommitCut.BEFORE_COMMIT) error("Synthetic recurrent before-COMMIT cut.")
                    }
                    override fun afterCommit() {
                        if (cut === RecurrentCommitCut.UNRESOLVED_RELEASE) {
                            TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                        }
                        if (cut !== RecurrentCommitCut.BEFORE_COMMIT) error("Synthetic recurrent after-COMMIT cut, not a dropped TLS response.")
                    }
                })
            } }
            val original = f.begin()
            try {
                assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
                if (cut === RecurrentCommitCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current()); assertTrue(checkNotNull(selected).quarantined())
                }
            } finally {
                f.probe.before = {}; f.probe.after = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
                requireConnectionFree() // Reclaims only the real quarantine, not the original completion result.
            }
            f.assertReleased()
            val outcome = when (cut) {
                RecurrentCommitCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                RecurrentCommitCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            assertTrue(f.scans().isEmpty() && f.entries().isEmpty(), "Recurrent staging was physically refunded BEFORE the final SUCCESS phase.")
            if (outcome === PersistenceDatabaseOutcome.COMMITTED) {
                assertEquals("SUCCESS", f.control()["checkpoint_result"])
                assertArrayEquals(f.control()["checkpoint_bytes"] as ByteArray, f.history().last()["checkpoint_bytes"] as ByteArray)
            } else {
                assertEquals(before, successImage(f.observer, f.scope)); assertNull(f.control()["checkpoint_result"]); assertNull(f.history().last()["checkpoint_bytes"])
            }
            assertPoisoned(f, original)
            bodyCompleted = true
        } }
        if (cut === RecurrentCommitCut.UNRESOLVED_RELEASE) assertThrows<RuntimeException> { run() } else run()
        assertTrue(bodyCompleted, "A sticky teardown failure is expected only after the exact held-close assertions.")
    }

    fun nativeCloseFailureCannotInventAReleaseOrRetryAuthority(tls: VersionBoundPersistenceConnectedFixture) {
        var reached = false; var bodyCompleted = false
        assertThrows<RuntimeException> { withRecurrentFixture(tls) { f ->
            f.raw.onNativeClose = { if (!reached) { reached = true; error("Synthetic recurrent native close return failure.") } }
            val original = f.begin()
            assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
            assertTrue(reached); f.assertSqlReleased(); f.raw.assertDisposed(returned = false)
            assertNull(f.control()["checkpoint_result"]); assertTrue(f.scans().isEmpty())
            val image = f.image(); val providers = f.raw.order.toList(); val closes = f.raw.sts.closedClients
            assertPoisoned(f, original)
            assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(f.begin()) }
            assertEquals(image, f.image()); assertEquals(providers, f.raw.order); assertEquals(closes, f.raw.sts.closedClients)
            bodyCompleted = true
        } }
        assertTrue(reached && bodyCompleted, "The outer exception must be the deliberately sticky close, not unrelated setup.")
    }

    private fun assertPoisoned(f: TestActiveRecurrentFixtureV1, original: TestActiveRecurrentV1) {
        val image = f.image(); val providers = f.raw.order.toList(); val sql = f.probe.calls.size; val counters = f.counters()
        assertThrows<TestActiveRecurrentExceptionV1> { f.checkpoint(original) }
        assertEquals(image, f.image()); assertEquals(providers, f.raw.order); assertEquals(sql, f.probe.calls.size); assertEquals(counters, f.counters())
    }

    private fun successImage(jdbc: JdbcTemplate, scope: UUID): Map<String, List<String>> = listOf(
        "complaint_journal_control", "complaint_test_active_seal_intents", "complaint_test_active_recurrent_seal_intents",
        "complaint_test_active_checkpoint_history", "complaint_journal_scan_runs", "complaint_journal_scan_entries",
    ).associateWith { table -> jdbc.queryForList("SELECT to_jsonb(t)::text FROM $table t WHERE data_scope_id=? ORDER BY to_jsonb(t)::text", String::class.java, scope) }
}
