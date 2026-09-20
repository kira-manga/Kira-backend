package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Lower old-snapshot/reconstruction cases only, NOT complete restore qualification or TEST activation.
 * Evidence always comes from a genuine committed/released AUTH and/or real bounded S3/KMS readExisting;
 * synthetic SQL inventory loss/corruption below supplies no PREPARED, VERIFIED or settlement capability.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Suppress("LargeClass")
class ComplaintOwnerDeleteRecoveryIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintOwnerDeleteRecoveryIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `all absent old snapshot recharges four hundred KiB bookkeeping and reconstructs only credential free identity and tombstone`() = withFixture { f ->
        val report = report(f)
        val attempt = f.attempt(report.id)
        val wire = f.wire(attempt)
        wire.factory(f.store, f.lanes).use { publishers ->
            val prepared = f.prepared(attempt, publishers)
            publishers.reserve().use { it.publish(prepared) }
            val authorizations = audits(f, "COMPLAINT_DELETE_AUTHORIZED")
            val oldAudits = f.state().getValue("audit")
            f.existing.transaction { sql ->
                // Deliberate lower old-snapshot loss. No deletion/recovery result is inferred from these erasures.
                assertEquals(2, sql.update("DELETE FROM complaint_idempotency_receipts WHERE actor_id = ?", f.creator.actor.id))
                assertEquals(1, sql.update("DELETE FROM complaint_recovery_capacity_reservations WHERE event_id = ?", wire.event.route.eventId))
                assertEquals(1, sql.update("DELETE FROM complaint_journal_publications WHERE event_id = ?", wire.event.route.eventId))
                assertEquals(1, sql.update("DELETE FROM complaints WHERE id = ?", attempt.id))
                assertEquals(1, sql.update("DELETE FROM complaint_resource_ids WHERE id = ?", attempt.id))
                assertEquals(1, sql.update("DELETE FROM app_installations WHERE id = ?", f.creator.actor.id))
                assertEquals(1, sql.update("DELETE FROM complaint_installation_ids WHERE id = ?", f.creator.actor.id))
                forgetPaidRows(sql, OwnerDeleteLiteralCharges.missingBookkeeping + OwnerDeleteLiteralCharges.receipt +
                    OwnerDeleteLiteralCharges.installation + OwnerDeleteLiteralCharges.credential + OwnerDeleteLiteralCharges.resource + OwnerDeleteLiteralCharges.content,
                    OwnerDeleteLiteralCharges.promise)
            }
            val before = f.counters()
            val requests = wire.requests.size
            val observed = read(publishers, wire.event)
            assertEquals(listOf("LIST", "GET"), wire.requests.drop(requests).map { it.kind })
            f.phases.recover(observed)
            f.assertReleased()
            f.assertCounterDelta(before, OwnerDeleteLiteralCharges.missingBookkeeping, OwnerDeleteLiteralCharges.promise, OwnerDeleteLiteralCharges.reconstructAbsent)
            assertEquals("RECOVERY_RESERVED", f.scalar("SELECT state FROM complaint_installation_ids WHERE id = ?", f.creator.actor.id))
            assertEquals(0, f.observer.queryForObject("SELECT count(*) FROM app_installations WHERE id = ?", Int::class.java, f.creator.actor.id))
            assertEquals("DELETED", f.scalar("SELECT state FROM complaint_resource_ids WHERE id = ?", attempt.id))
            assertEquals(0, f.observer.queryForObject("SELECT count(*) FROM complaints WHERE id = ?", Int::class.java, attempt.id))
            assertEquals(authorizations, audits(f, "COMPLAINT_DELETE_AUTHORIZED"), "Recovery cannot invent a past authorization version")
            assertEquals(0, audits(f, "COMPLAINT_DELETED").size, "No content row was actually removed by this recovery")
            assertEquals(oldAudits.size + 1, f.state().getValue("audit").size)
            recoverySummary(f, attempt.id, expected = 1)
            assertReserve(f, wire.event.route.eventId, OwnerDeleteLiteralCharges.reconstructAbsent, 360448)
            assertCompleted(f, attempt, wire.event, TestOwnerDeleteJournalPublisherFixture.VERSION)
            val complete = f.state()
            f.phases.recover(read(publishers, wire.event))
            assertEquals(complete, f.state(), "Exact recovery replay has zero charge, refund, spend, audit and domain deltas")
            f.creator.problem(f.status(attempt, f.http(publishers)), 401, "UNAUTHORIZED")
            assertEquals(complete, f.state(), "Background evidence never turns a removed credential into normal Bearer authority")
        }
        wire.assertClientsClosed()
        assertFalse(wire.requests.any { it.http.method().name == "DELETE" })
    }

    @Test
    fun `recovery preserves an existing credential pair and audits only an actual removal or a versionless summary`() {
        for (missingTarget in listOf(false, true)) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            wire.factory(f.store, f.lanes).use { publishers ->
                val prepared = f.prepared(attempt, publishers)
                publishers.reserve().use { it.publish(prepared) }
                if (missingTarget) f.existing.transaction { sql ->
                    assertEquals(1, sql.update("DELETE FROM complaints WHERE id = ?", attempt.id))
                    assertEquals(1, sql.update("DELETE FROM complaint_resource_ids WHERE id = ?", attempt.id))
                    forgetPaidRows(sql, OwnerDeleteLiteralCharges.content + OwnerDeleteLiteralCharges.resource)
                }
                val identities = f.rows("complaint_installation_ids")
                val credentials = f.rows("app_installations")
                val authorized = audits(f, "COMPLAINT_DELETE_AUTHORIZED")
                val before = f.counters()
                val spent = OwnerDeleteLiteralCharges.audit + OwnerDeleteLiteralCharges.appliedOnly +
                    if (missingTarget) OwnerDeleteLiteralCharges.resource else OwnerDeleteLiteralCharges.audit
                f.phases.recover(read(publishers, wire.event))
                f.assertReleased()
                f.assertCounterDelta(before, spent = spent, refund = if (missingTarget) ComplaintCapacityVector.ZERO else OwnerDeleteLiteralCharges.content)
                assertEquals(identities, f.rows("complaint_installation_ids"))
                assertEquals(credentials, f.rows("app_installations"))
                assertEquals(authorized, audits(f, "COMPLAINT_DELETE_AUTHORIZED"))
                assertEquals(if (missingTarget) 0 else 1, audits(f, "COMPLAINT_DELETED").size)
                recoverySummary(f, attempt.id, 1)
                assertReserve(f, wire.event.route.eventId, spent, if (missingTarget) 376832 else 327680)
                assertCompleted(f, attempt, wire.event, TestOwnerDeleteJournalPublisherFixture.VERSION)
                val result = f.status(attempt, f.http(publishers))
                assertEquals(200, result.status)
                assertEquals("{\"outcome\":\"APPLIED\",\"originalStatus\":204}", result.contentAsString)
                val complete = f.state()
                f.phases.recover(read(publishers, wire.event))
                assertEquals(complete, f.state())
            }
        }
    }

    @Test
    fun `four retained candidates share one primary obligation and an alias never completes or overwrites the primary receipt`() = withFixture { f ->
        val attempt = f.attempt(report(f).id)
        val wire = f.wire(attempt)
        wire.factory(f.store, f.lanes).use { publishers ->
            f.prepared(attempt, publishers)
            val candidates = f.routing.derive(wire.event.tuple).candidates()
            assertEquals(4, candidates.size)
            val aliases = candidates.filter { it != wire.event.route }.map { route ->
                f.codec.canonicalize(wire.event.tuple, listOf(attempt.id), route.routingKeyId)
            }
            aliases.forEachIndexed { index, alias ->
                // Authentic content bytes only; no SQL work or verification object is fabricated for an alias.
                wire.objects.add(wire.objectFor(wire.envelope(alias), alias, "retained-alias-${index + 1}"))
            }
            val primaryBefore = f.rows("complaint_journal_publications")
            val receiptBefore = f.rows("complaint_idempotency_receipts")
            val before = f.counters()
            f.phases.recover(read(publishers, aliases.first()))
            f.assertReleased()
            val firstSpend = OwnerDeleteLiteralCharges.ordinaryApply + OwnerDeleteLiteralCharges.audit
            f.assertCounterDelta(before, spent = firstSpend, refund = OwnerDeleteLiteralCharges.content)
            assertEquals(primaryBefore, f.rows("complaint_journal_publications"))
            assertEquals(receiptBefore, f.rows("complaint_idempotency_receipts"))
            assertEquals(1, f.rows("complaint_recovery_capacity_reservations").size)
            assertReserve(f, wire.event.route.eventId, firstSpend, 327680)
            val calls = wire.requests.size
            f.creator.problem(f.status(attempt, f.http(publishers)), 404, "OPERATION_NOT_FOUND")
            assertEquals(calls, wire.requests.size)
            assertTrue(wire.requests.none { it.kind == "PUT" }, "readExisting cannot publish aliases")

            val primaryStart = f.counters()
            val original = assertInstanceOf(CommittedTestOwnerDeleteWork.Prepared::class.java, f.reload(attempt))
            assertArrayEquals(wire.event.canonicalBytes(), original.canonicalBytes())
            assertEquals(204, f.delete(attempt, f.http(publishers)).status)
            f.assertCounterDelta(primaryStart, spent = OwnerDeleteLiteralCharges.appliedOnly)
            assertCompleted(f, attempt, wire.event, TestOwnerDeleteJournalPublisherFixture.VERSION)
            val primaryApplied = f.rows("complaint_journal_publications")
            val primaryReceipt = f.rows("complaint_idempotency_receipts")
            for (alias in aliases.drop(1)) {
                val aliasStart = f.counters()
                f.phases.recover(read(publishers, alias))
                f.assertCounterDelta(aliasStart, spent = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit)
                assertEquals(primaryApplied, f.rows("complaint_journal_publications"))
                assertEquals(primaryReceipt, f.rows("complaint_idempotency_receipts"))
            }
            assertEquals(1, f.rows("complaint_journal_publications").size)
            assertEquals(1, f.rows("complaint_recovery_capacity_reservations").size)
            assertEquals(4, f.rows("complaint_deletion_journal_applied").size)
            val totalSpend = OwnerDeleteLiteralCharges.audit.scaled(4) + OwnerDeleteLiteralCharges.appliedOnly.scaled(4)
            assertReserve(f, wire.event.route.eventId, totalSpend, 98304)
            recoverySummary(f, attempt.id, expected = 3)
            assertEquals(1, audits(f, "COMPLAINT_DELETED").size)
            assertEquals(1, audits(f, "COMPLAINT_DELETE_AUTHORIZED").size)
            val settled = f.state()
            (aliases + wire.event).forEach { f.phases.recover(read(publishers, it)) }
            assertEquals(settled, f.state())
            assertEquals(1, wire.requests.count { it.kind == "PUT" })
            assertEquals("/${wire.journal.declaration().journalLocation.bucket}/${wire.event.route.objectKey}", wire.requests.single { it.kind == "PUT" }.http.encodedPath())
            assertTrue(f.rows("complaint_deletion_journal_retirements").isEmpty())
        }
        wire.assertClientsClosed()
    }

    @Test
    fun `presence tested bookkeeping charges only missing rows and reserves Y only for an actually missing obligation`() {
        for (missing in listOf("RECEIPT", "RESERVATION", "ALL")) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            wire.factory(f.store, f.lanes).use { publishers ->
                val work = f.prepared(attempt, publishers)
                publishers.reserve().use { it.publish(work) }
                val actual = when (missing) {
                    "RECEIPT" -> OwnerDeleteLiteralCharges.receipt
                    "RESERVATION" -> OwnerDeleteLiteralCharges.reservation
                    else -> OwnerDeleteLiteralCharges.missingBookkeeping
                }
                val promise = if (missing == "RECEIPT") ComplaintCapacityVector.ZERO else OwnerDeleteLiteralCharges.promise
                f.existing.transaction { sql ->
                    if (missing != "RESERVATION") assertEquals(1, sql.update(
                        "DELETE FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", f.creator.actor.id, attempt.key,
                    ))
                    if (missing != "RECEIPT") assertEquals(1, sql.update("DELETE FROM complaint_recovery_capacity_reservations WHERE event_id = ?", wire.event.route.eventId))
                    if (missing == "ALL") assertEquals(1, sql.update("DELETE FROM complaint_journal_publications WHERE event_id = ?", wire.event.route.eventId))
                    forgetPaidRows(sql, actual, promise)
                }
                val before = f.counters()
                val authorized = audits(f, "COMPLAINT_DELETE_AUTHORIZED")
                val spent = OwnerDeleteLiteralCharges.ordinaryApply + OwnerDeleteLiteralCharges.audit
                f.phases.recover(read(publishers, wire.event))
                f.assertCounterDelta(before, actual, promise, spent, OwnerDeleteLiteralCharges.content)
                assertEquals(authorized, audits(f, "COMPLAINT_DELETE_AUTHORIZED"))
                assertReserve(f, wire.event.route.eventId, spent, 327680)
                assertCompleted(f, attempt, wire.event, TestOwnerDeleteJournalPublisherFixture.VERSION)
                val once = f.state()
                f.phases.recover(read(publishers, wire.event))
                assertEquals(once, f.state())
            }
        }
    }

    @Test
    fun `receipt expiry refunds only its actual row and later reconstruction cannot replenish spent alias or audit slots`() = withFixture { f ->
        val attempt = f.attempt(report(f).id)
        val wire = f.wire(attempt)
        wire.factory(f.store, f.lanes).use { publishers ->
            assertEquals(204, f.delete(attempt, f.http(publishers)).status)
            val reserve = f.rows("complaint_recovery_capacity_reservations")
            val audits = f.state().getValue("audit")
            f.existing.transaction { sql ->
                // Lower expiry simulation deletes one genuinely paid row, not a generic sweep/restore qualification.
                assertEquals(1, sql.update(
                    "UPDATE complaint_idempotency_receipts SET created_at = created_at - interval '9 days', " +
                        "authorized_at = authorized_at - interval '9 days', completed_at = completed_at - interval '9 days', expires_at = expires_at - interval '9 days' " +
                        "WHERE actor_id = ? AND idempotency_key = ?", f.creator.actor.id, attempt.key,
                ))
                assertEquals(1, sql.update(
                    "DELETE FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ? AND expires_at <= clock_timestamp()",
                    f.creator.actor.id, attempt.key,
                ))
                forgetPaidRows(sql, OwnerDeleteLiteralCharges.receipt)
            }
            val before = f.counters()
            f.phases.recover(read(publishers, wire.event))
            f.assertCounterDelta(before, actual = OwnerDeleteLiteralCharges.receipt)
            assertEquals(reserve, f.rows("complaint_recovery_capacity_reservations"))
            assertEquals(audits, f.state().getValue("audit"))
            assertReserve(f, wire.event.route.eventId, OwnerDeleteLiteralCharges.ordinaryApply, 393216)
            assertCompleted(f, attempt, wire.event, TestOwnerDeleteJournalPublisherFixture.VERSION)
            val reconstructed = f.state()
            f.phases.recover(read(publishers, wire.event))
            assertEquals(reconstructed, f.state())
        }
    }

    @Test
    fun `changed epoch credential actor fingerprint or target is not an alias even when the raw bytes have a valid key and checksum`() = withFixture { f ->
        val attempt = f.attempt(report(f).id)
        val wire = f.wire(attempt)
        wire.factory(f.store, f.lanes).use { publishers ->
            f.prepared(attempt, publishers)
            val original = wire.event.tuple
            fun tuple(
                epoch: Long = original.epoch,
                actor: UUID = original.actorId,
                credential: Long = original.credentialVersion,
                fingerprint: ByteArray = original.fingerprintBytes(),
            ) = TestOwnerDeleteJournalTupleV1(epoch, actor, credential, original.operationKey, fingerprint, f.scope)
            val changes = listOf(
                tuple(epoch = original.epoch + 1) to attempt.id,
                tuple(actor = UUID.randomUUID()) to attempt.id,
                tuple(credential = original.credentialVersion + 1) to attempt.id,
                tuple(fingerprint = ByteArray(32) { 73 }) to attempt.id,
                tuple() to UUID.randomUUID(),
            )
            val durable = f.state()
            for ((changed, target) in changes) {
                val alien = f.codec.canonicalize(changed, listOf(target), wire.event.route.routingKeyId)
                val objectValue = wire.objectFor(wire.envelope(alien), alien)
                // Deliberately hostile raw S3 response at the expected key. This is never provider evidence.
                wire.stored = objectValue.copy(
                    key = wire.event.route.objectKey,
                    metadata = objectValue.metadata + ("kira-journal-event-id" to wire.event.route.eventId),
                )
                val failed = assertThrows<RuntimeException> { read(publishers, wire.event) }
                assertTrue(failed is JournalPublicationExceptionV1 || failed is OwnerDeleteAllJournalException)
                assertNull(failed.cause)
                assertTrue(failed.suppressed.isEmpty())
                assertEquals(durable, f.state())
            }
            assertTrue(wire.requests.none { it.kind == "PUT" })
        }
        wire.assertClientsClosed()
    }

    @Test
    fun `multiple local primaries or applied evidence without its original obligation fail closed before reconstruction`() {
        for (corruption in listOf("TWO_PRIMARIES", "MISSING_APPLIED", "UNSPENT_WITH_ALIAS", "ORPHAN_RESERVE", "ORPHAN_PRIMARY")) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            wire.factory(f.store, f.lanes).use { publishers ->
                if (corruption in setOf("TWO_PRIMARIES", "MISSING_APPLIED", "UNSPENT_WITH_ALIAS")) {
                    val work = f.prepared(attempt, publishers)
                    publishers.reserve().use { it.publish(work) }
                    val alias = f.routing.derive(wire.event.tuple).candidates().first { it != wire.event.route }
                    val content = f.codec.canonicalize(wire.event.tuple, listOf(attempt.id), alias.routingKeyId)
                    if (corruption == "TWO_PRIMARIES") {
                        // Corrupt comparison row only. No retained work/result is issued from this synthetic second outbox.
                        assertEquals(1, f.observer.update(
                            "INSERT INTO complaint_journal_publications (event_id, data_scope_id, test_only, writer_generation, journal_epoch, " +
                                "event_kind, target_count, routing_key_id, object_key, canonicalizer, event_bytes, semantic_hash, state, created_at) " +
                                "VALUES (?, ?, true, ?, ?, 'OWNER_DELETE', 1, ?, ?, 'kcj-1', ?, sha256(?::bytea), 'PREPARED', clock_timestamp())",
                            content.route.eventId, f.scope.id, f.graph.writer, content.tuple.epoch, content.route.routingKeyId, content.route.objectKey,
                            content.canonicalBytes(), content.canonicalBytes(),
                        ))
                    } else {
                        wire.objects.add(wire.objectFor(wire.envelope(content), content, "history-alias-version"))
                        f.phases.recover(read(publishers, content))
                        assertEquals("PREPARED", f.scalar("SELECT state FROM complaint_journal_publications WHERE event_id = ?", wire.event.route.eventId))
                        val used = OwnerDeleteLiteralCharges.ordinaryApply + OwnerDeleteLiteralCharges.audit
                        assertReserve(f, wire.event.route.eventId, used, 327680)
                        f.existing.transaction { sql ->
                            if (corruption == "MISSING_APPLIED") {
                                assertEquals(1, sql.update("DELETE FROM complaint_deletion_journal_applied WHERE event_id = ?", content.route.eventId))
                                forgetPaidRows(sql, OwnerDeleteLiteralCharges.appliedOnly)
                            } else {
                                assertEquals(1, sql.update(
                                    "UPDATE complaint_recovery_capacity_reservations SET state = 'RESERVED', converted_amounts = NULL, converted_at = NULL WHERE event_id = ?",
                                    wire.event.route.eventId,
                                ))
                                // Corrupt old ledger claims U=0 although a genuine alias row survives. Restore enough Q
                                // to exercise the family/U consistency guard, not an unrelated aggregate-headroom refusal.
                                for (counter in ComplaintCapacityCounter.entries) if (used[counter] != 0L) {
                                    assertEquals(1, sql.update(
                                        "UPDATE complaint_capacity_counters SET actual_units = actual_units - ?, recovery_reserved_units = recovery_reserved_units + ? " +
                                            "WHERE name = ? AND actual_units >= ?",
                                        used[counter], used[counter], counter.storedName, used[counter],
                                    ))
                                }
                            }
                        }
                    }
                } else {
                    assertEquals(204, f.delete(attempt, f.http(publishers)).status)
                    f.existing.transaction { sql ->
                        var lost = OwnerDeleteLiteralCharges.reservation
                        if (corruption == "ORPHAN_PRIMARY") {
                            assertEquals(1, sql.update("DELETE FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", f.creator.actor.id, attempt.key))
                            lost += OwnerDeleteLiteralCharges.receipt + OwnerDeleteLiteralCharges.publication
                        }
                        assertEquals(1, sql.update("DELETE FROM complaint_recovery_capacity_reservations WHERE event_id = ?", wire.event.route.eventId))
                        if (corruption == "ORPHAN_PRIMARY") assertEquals(1, sql.update("DELETE FROM complaint_journal_publications WHERE event_id = ?", wire.event.route.eventId))
                        forgetPaidRows(sql, lost, OwnerDeleteLiteralCharges.promise - OwnerDeleteLiteralCharges.ordinaryApply)
                        // The genuine exact applied row remains, so U must not be reset by a made-up new reserve.
                    }
                }
                val before = f.state()
                val readback = read(publishers, wire.event)
                val failure = assertThrows<PersistencePhaseException> { f.phases.recover(readback) }
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
                assertTrue(failure.cleanupProven)
                assertNull(failure.cause)
                assertEquals(before, f.state())
                f.assertReleased()
            }
        }
    }

    @Test
    fun `recovery statement and genuine commit failures roll every effect back and a committed tail is acknowledged only by a later read`() {
        for (mode in listOf("VERIFIED_WRITE", "CONTENT", "SPEND", "COMMIT", "TAIL")) withFixture { f ->
            val attempt = f.attempt(report(f).id)
            val wire = f.wire(attempt)
            wire.factory(f.store, f.lanes).use { publishers ->
                val work = f.prepared(attempt, publishers)
                publishers.reserve().use { it.publish(work) }
                val proof = read(publishers, wire.event)
                val before = f.state()
                val counters = f.counters()
                var fired = false
                val point = when (mode) {
                    "VERIFIED_WRITE" -> OwnerDeleteFixtureStep.VERIFY
                    "CONTENT" -> OwnerDeleteFixtureStep.DELETE_CONTENT
                    "SPEND" -> OwnerDeleteFixtureStep.SPEND
                    else -> OwnerDeleteFixtureStep.COMPLETE
                }
                f.afterStep = { step ->
                    if (step == point && !fired) {
                        fired = true
                        when (mode) {
                            "COMMIT" -> {
                                f.jdbc.execute("CREATE TEMP TABLE kira_owner_delete_recovery_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED) ON COMMIT DROP")
                                assertEquals(2, f.jdbc.update("INSERT INTO kira_owner_delete_recovery_commit VALUES (1), (1)"))
                            }
                            "TAIL" -> org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                object : org.springframework.transaction.support.TransactionSynchronization {
                                    override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                                },
                            )
                            else -> throw SyntheticInstallationEnrollmentFailure()
                        }
                    }
                }
                val failure = try {
                    assertThrows<PersistencePhaseException> { f.phases.recover(proof) }
                } finally {
                    f.afterStep = {}
                }
                assertTrue(fired)
                assertTrue(failure.cleanupProven)
                assertNull(failure.cause)
                when (mode) {
                    "TAIL" -> {
                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
                        assertCompleted(f, attempt, wire.event, TestOwnerDeleteJournalPublisherFixture.VERSION)
                        assertEquals(200, f.status(attempt, f.http(publishers)).status)
                    }
                    else -> {
                        assertEquals(if (mode == "COMMIT") PersistenceDatabaseOutcome.UNKNOWN else PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
                        assertEquals(before, f.state())
                        f.creator.problem(f.status(attempt, f.http(publishers)), 404, "OPERATION_NOT_FOUND")
                    }
                }
                f.phases.recover(read(publishers, wire.event))
                val spent = OwnerDeleteLiteralCharges.ordinaryApply + OwnerDeleteLiteralCharges.audit
                f.assertCounterDelta(counters, spent = spent, refund = OwnerDeleteLiteralCharges.content)
                assertReserve(f, wire.event.route.eventId, spent, 327680)
                recoverySummary(f, attempt.id, 1)
                assertEquals(1, wire.requests.count { it.kind == "PUT" })
            }
        }
    }

    @Test
    fun `204 age and a synthetic SEALED comparison never convert PARTIAL and a missing run prerequisite is not reconstructed for free`() = withFixture { f ->
        val attempt = f.attempt(report(f).id)
        val wire = f.wire(attempt)
        wire.factory(f.store, f.lanes).use { publishers ->
            assertEquals(204, f.delete(attempt, f.http(publishers)).status)
            assertReserve(f, wire.event.route.eventId, OwnerDeleteLiteralCharges.ordinaryApply, 393216)
            assertEquals(1, f.rows("complaint_deletion_journal_applied").size)
            assertEquals(4, f.routing.derive(wire.event.tuple).candidates().size)
            assertEquals(1, f.observer.update(
                "UPDATE complaint_idempotency_receipts SET created_at = created_at - interval '9 days', authorized_at = authorized_at - interval '9 days', " +
                    "completed_at = completed_at - interval '9 days', expires_at = expires_at - interval '9 days' WHERE actor_id = ? AND idempotency_key = ?",
                f.creator.actor.id, attempt.key,
            ))
            assertEquals(1, f.observer.update(
                "UPDATE complaint_journal_publications SET created_at = created_at - interval '9 days' WHERE event_id = ?", wire.event.route.eventId,
            ))
            f.creator.problem(f.status(attempt, f.http(publishers)), 404, "OPERATION_NOT_FOUND")
            // Necessary phase comparison only: no signed TEST range closure, inventory or terminal/quiescence proof exists here.
            assertEquals(1, f.observer.update("UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = clock_timestamp() WHERE data_scope_id = ?", f.scope.id))
            val sealed = f.state()
            f.phases.recover(read(publishers, wire.event))
            assertEquals(sealed, f.state())
            assertReserve(f, wire.event.route.eventId, OwnerDeleteLiteralCharges.ordinaryApply, 393216)
            assertTrue(f.rows("complaint_deletion_journal_retirements").isEmpty())
            assertTrue(wire.requests.none { it.http.method().name == "DELETE" })
            assertEquals(1, f.observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", f.scope.id))
            val absentRun = f.state()
            val failure = assertThrows<PersistencePhaseException> { f.phases.recover(read(publishers, wire.event)) }
            assertTrue(failure.cleanupProven)
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
            assertEquals(absentRun, f.state(), "Missing signed activation/run reserve is a prerequisite, not part of the ordinary Y vector")
        }
        wire.assertClientsClosed()
    }

    private fun withFixture(test: (OwnerDeleteFixture) -> Unit) = withOwnerDelete(database.value, test)

    private fun report(f: OwnerDeleteFixture): OwnerCreateFixtureAttempt = f.creator.attempt().also { assertEquals(201, f.creator.create(it).status) }

    private fun read(publishers: TestOwnerDeleteJournalPublisherFactoryV1, event: TestOwnerDeleteJournalEventV1): TestOwnerDeleteJournalReadbackV1 =
        publishers.readExisting(event.tuple, event.complaintIds().single(), event.route.routingKeyId)

    private fun audits(f: OwnerDeleteFixture, action: String): List<String> = f.observer.queryForList(
        "SELECT to_jsonb(a)::text FROM audit_log a WHERE complaint_data_scope_id = ? AND action = ? ORDER BY id", String::class.java, f.scope.id, action,
    )

    private fun recoverySummary(f: OwnerDeleteFixture, id: UUID, expected: Int) {
        assertEquals(expected, f.observer.queryForObject(
            "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED' " +
                "AND actor_user_id IS NULL AND complaint_actor_kind = 'SYSTEM' AND entity_type = 'complaint' AND detail = '{}'::jsonb",
            Int::class.java, f.scope.id, id.toString(),
        ))
        assertEquals(expected, audits(f, "COMPLAINT_RECOVERY_APPLIED").size)
    }

    private fun assertCompleted(f: OwnerDeleteFixture, attempt: OwnerDeleteFixtureAttempt, event: TestOwnerDeleteJournalEventV1, version: String) {
        assertEquals("APPLIED", f.scalar("SELECT state FROM complaint_journal_publications WHERE event_id = ?", event.route.eventId))
        assertEquals(true, f.observer.queryForObject(
            "SELECT r.state = 'COMPLETED' AND r.outcome = 'APPLIED' AND r.response_status = 204 AND r.publication_ref = ? " +
                "AND r.external_event_id = ? AND r.external_epoch = ? AND r.external_object_version = ? " +
                "AND r.external_ciphertext_hash = p.ciphertext_hash AND r.ack_ids IS NULL AND r.ack_versions IS NULL " +
                "AND r.response_etag IS NULL AND r.response_location IS NULL AND r.expires_at = r.completed_at + interval '192 hours' " +
                "FROM complaint_idempotency_receipts r JOIN complaint_journal_publications p ON p.event_id = r.publication_ref " +
                "WHERE r.actor_id = ? AND r.idempotency_key = ?",
            Boolean::class.java, event.route.eventId, event.route.eventId, event.tuple.epoch, version, f.creator.actor.id, attempt.key,
        ))
        f.assertReleased()
    }

    private fun assertReserve(f: OwnerDeleteFixture, eventId: String, used: ComplaintCapacityVector, remainingBytes: Long) {
        val row = f.observer.queryForMap(
            "SELECT state, reserved_amounts::text AS reserved, converted_amounts::text AS used, converted_at IS NOT NULL AS timestamped " +
                "FROM complaint_recovery_capacity_reservations WHERE event_id = ?", eventId,
        )
        assertEquals("PARTIAL", row["state"])
        assertEquals(array(OwnerDeleteLiteralCharges.promise), row["reserved"])
        assertEquals(array(used), row["used"])
        assertEquals(true, row["timestamped"])
        assertEquals(remainingBytes, (OwnerDeleteLiteralCharges.promise - used)[ComplaintCapacityCounter.STORAGE_BYTES])
        assertTrue(f.rows("complaint_deletion_journal_retirements").isEmpty())
    }

    /** Only the scoped synthetic snapshot is changed. Keep the exact logical H=F+B+Q+Z identity in every one of the 22 dimensions. */
    private fun forgetPaidRows(sql: JdbcTemplate, actual: ComplaintCapacityVector, promised: ComplaintCapacityVector = ComplaintCapacityVector.ZERO) {
        for (counter in ComplaintCapacityCounter.entries) {
            if (actual[counter] == 0L && promised[counter] == 0L) continue
            assertEquals(1, sql.update(
                "UPDATE complaint_capacity_counters SET free_units = free_units + ? + ?, actual_units = actual_units - ?, recovery_reserved_units = recovery_reserved_units - ? " +
                    "WHERE name = ? AND actual_units >= ? AND recovery_reserved_units >= ?",
                actual[counter], promised[counter], actual[counter], promised[counter], counter.storedName, actual[counter], promised[counter],
            ))
        }
    }

    private fun array(vector: ComplaintCapacityVector): String = vector.toLongArray().joinToString(",", "{", "}")
}
