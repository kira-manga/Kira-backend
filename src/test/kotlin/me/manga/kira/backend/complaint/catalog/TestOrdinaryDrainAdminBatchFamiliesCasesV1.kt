package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.ResultSet

/** Existing registered/native fixture and genuine batch producer, never inserted work/receipt/P/U or a successful cut. */
internal object TestOrdinaryDrainAdminBatchFamiliesCasesV1 {
    fun retainedPrimaries(tls: VersionBoundPersistenceConnectedFixture) {
        for (prepared in listOf(true, false)) withBatchRun(tls, prepared = prepared, verifiedOnly = !prepared) { f, batch ->
            assertEquals(if (prepared) "PREPARED" else "VERIFIED", f.history.publicationState(batch.eventId))
            assertEquals("AUTHORIZED_DELETE", f.history.adminReceiptState(batch))
            assertEquals(1, update(f, "UPDATE users SET enabled = false, role = 'USER' WHERE id = ?", batch.actor))
            assertEquals(1, update(f, "UPDATE admin_step_up_grants SET expires_at = used_at + interval '1 microsecond' WHERE id = ? AND used_at IS NOT NULL", batch.grant))
            val protected = protectedRows(f, batch)
            val observed = TestOrdinaryDrainAccountingObservationV1(f, batch.eventId)
            val before = observed.state()
            assertEquals(promise, before.promise)
            var captured: TestOrdinaryDrainAccountingStateV1? = null
            f.probe.before = { call -> if (captured == null && call.step === TestOrdinaryDrainStepV1.CAPTURE) {
                captured = observed.state()
                assertEquals("APPLIED", scalar(f, "SELECT state FROM complaint_journal_publications WHERE event_id = ?", batch.eventId))
                assertEquals("COMPLETED", scalar(f, "SELECT state FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", batch.actor, batch.key))
            } }
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                val puts = f.provider.requests.count { it.kind == "PUT" }
                val drain = complete(f, batch)
                val phases = f.jdbc.calls.filter { it.adminPrimaryOriginal != null }.distinctBy { it.phase }
                assertEquals(listOf(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD) +
                    (if (prepared) listOf(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY) else emptyList()) +
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY, phases.map { it.path })
                phases.forEach { call ->
                    val child = checkNotNull(call.adminPrimaryOriginal)
                    assertSame(drain.budget, child.budget)
                    assertTrue(call.phase.testRunAdminDeleteCleanupProven(child))
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, call.phase.databaseOutcome())
                }
                val primarySql = f.jdbc.calls.filter { it.adminPrimaryOriginal != null }.map { it.sql }
                assertTrue(primarySql.none { it.contains("admin_step_up_grants") || it.contains("password_hash") || it.contains("enabled") })
                assertTrue(f.jdbc.calls.none { it.path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE })
                assertEquals(if (prepared) 1 else 0, f.provider.requests.count { it.kind == "PUT" } - puts)
                assertEquals("PARTIAL", checkNotNull(captured).recoveryState)
                assertEquals(normalUse, checkNotNull(captured).used)
                assertEquals(protected, protectedRows(f, batch))
                assertEquals(batch.targets.map { it.toString() }, strings(f,
                    "SELECT unnest(ack_ids)::text FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", batch.actor, batch.key))
                assertEquals("200", scalar(f, "SELECT response_status::text FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", batch.actor, batch.key))
                val after = observed.state()
                observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                    recoverySpend = normalUse, recoveryRelease = before.promise - after.used,
                    actualRefund = literal.content.scaled(2), allowChurn = stagingCounters)
                assertNativeCopies(native, 1)
            }
        }
    }

    fun fourTotalVersions(tls: VersionBoundPersistenceConnectedFixture) = withBatchRun(tls) { f, batch ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f, batch.eventId)
        val before = observed.state()
        assertEquals(promise, before.promise); assertEquals(normalUse, before.used)
        val primary = primaryImage(f, batch); val protected = protectedRows(f, batch)
        retainedVersions(f, batch, sameKeyCopies = 1, aliasKeys = 2)
        assertEquals(4, f.provider.objects.size); assertEquals(3, f.provider.objects.map { it.key }.distinct().size)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            complete(f, batch)
            val after = observed.state()
            assertEquals("CONVERTED", after.recoveryState); assertEquals(4L, after.applied)
            assertEquals(normalUse + aliasUse.scaled(3), after.used)
            assertEquals(primary, primaryImage(f, batch), "Aliases retain the original primary key/version/wire/proof/grant/receipt byte and xmin.")
            assertEquals(protected, protectedRows(f, batch))
            assertEquals(2L, number(f, "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_DELETE_AUTHORIZED' AND complaint_actor_kind = 'ADMIN' AND actor_user_id = ?", f.scope, batch.actor))
            assertEquals(2L, number(f, "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_DELETED' AND complaint_actor_kind = 'ADMIN' AND actor_user_id = ?", f.scope, batch.actor))
            assertEquals(3L, number(f, "SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND entity_type = 'complaint_scope' AND entity_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED' AND complaint_actor_kind = 'SYSTEM' AND actor_user_id IS NULL AND detail->>'removed' = '0' AND detail->>'reconstructed' = '0' AND detail->>'installation' = '0'", f.scope, f.scope.toString()))
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = aliasUse.scaled(3), recoveryRelease = before.promise - after.used, allowChurn = stagingCounters)
            assertNativeCopies(native, 4)
        }
    }

    fun fifthVersion(tls: VersionBoundPersistenceConnectedFixture) = withBatchRun(tls) { f, batch ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f, batch.eventId)
        val before = observed.state(); val primary = primaryImage(f, batch); val protected = protectedRows(f, batch)
        retainedVersions(f, batch, sameKeyCopies = 4, aliasKeys = 0)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val drain = f.begin()
            assertThrows<TestOrdinaryDrainExceptionV1> { drain.drain(f.approval(drain), f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.assertReleased(); f.probe.assertReleased(requireCommitted = false); f.jdbc.assertReleased(requireCommitted = false)
            val after = observed.state()
            assertNotNull(after.progressHex)
            assertEquals(10L, after.scanEntries); assertEquals(8L, after.appliedScanEntries)
            assertEquals(4L, after.applied); assertEquals("PARTIAL", after.recoveryState)
            assertEquals(before.used + aliasUse.scaled(3), after.used)
            assertEquals(primary, primaryImage(f, batch)); assertEquals(protected, protectedRows(f, batch))
            assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.convert }); assertTrue(f.sealHttp.order.isEmpty())
            assertEquals(15, native.requests.count { it.kind == "GET" }, "Fifth native version is observed, not hidden by a per-key four-version allowance.")
        }
    }

    private fun withBatchRun(tls: VersionBoundPersistenceConnectedFixture, prepared: Boolean = false, verifiedOnly: Boolean = false,
        action: (TestRunOrdinaryDrainFixtureV1, TestRunVerifiedOwnerDeleteFixture.AdminHistory) -> Unit) {
        val inputs = TestOrdinaryDrainFixtureInputsV1()
        TestOrdinarySealHttpFixtureV1().use { http ->
            ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ordinarySealHttp = http, ordinaryDrain = inputs,
                registeredAdminBatchDelete = true, expireClosedSetupPredecessors = true) { p, runtime, registration, _ ->
                assertEquals("REGISTERED_TEST_ADMIN_BATCH_ERASURE", registration.process.consumers.journalConfiguration.profile)
                assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                    TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit, expectSubsequentProviderReads = true).use { history ->
                        val batch = history.authorEarlierAdminHistory(verified = !prepared, applied = !prepared && !verifiedOnly, batchTargets = 2)
                        assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                        TestRunOrdinaryDrainFixtureV1(history, http, inputs).use { action(it, batch) }
                    }
                }
            }
        }
    }

    private fun retainedVersions(f: TestRunOrdinaryDrainFixtureV1, batch: TestRunVerifiedOwnerDeleteFixture.AdminHistory, sameKeyCopies: Int, aliasKeys: Int) {
        requireConnectionFree()
        val primary = f.provider.objects.single { it.key == batch.event.route.objectKey }
        repeat(sameKeyCopies) { f.provider.objects.add(primary.copy(version = "z-batch-same-key-${it + 1}", bytes = primary.bytes.copyOf())) }
        val noKeys = NeverOwnerDeleteAllDataKeys()
        val codec = TestOwnerDeleteJournalCodecV1(f.provider.routing, noKeys)
        f.provider.journal.declaration().routing.keys.filter { it.keyId != batch.event.route.routingKeyId }.take(aliasKeys).forEach { key ->
            val event = codec.canonicalizeAdminBatch(batch.event.adminBatchTuple, batch.targets, key.keyId)
            val bytes = f.provider.envelope(event)
            try { f.provider.objects.add(f.provider.objectFor(bytes, event, "retained-batch-${key.keyId}")) } finally { bytes.fill(0) }
        }
        assertEquals(0, noKeys.calls.get()); f.provider.assertClientsClosed()
    }

    private fun complete(f: TestRunOrdinaryDrainFixtureV1, batch: TestRunVerifiedOwnerDeleteFixture.AdminHistory): TestRunOrdinaryDrainV1 {
        val drain = f.begin(); val approval = f.approval(drain)
        assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
            drain.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
        f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased()
        TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, f.provider.objects.toList(), approval, batch.eventId)
        return drain
    }

    private fun primaryImage(f: TestRunOrdinaryDrainFixtureV1, batch: TestRunVerifiedOwnerDeleteFixture.AdminHistory) =
        strings(f, "SELECT jsonb_build_array(to_jsonb(p), p.xmin::text)::text FROM complaint_journal_publications p WHERE event_id = ?", batch.eventId) +
            strings(f, "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM complaint_idempotency_receipts r WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", batch.actor, batch.key)
    private fun protectedRows(f: TestRunOrdinaryDrainFixtureV1, batch: TestRunVerifiedOwnerDeleteFixture.AdminHistory) =
        strings(f, "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM app_installations c WHERE id = ?", f.history.actor.id) +
            strings(f, "SELECT jsonb_build_array(to_jsonb(g), g.xmin::text)::text FROM admin_step_up_grants g WHERE id = ?", batch.grant) +
            strings(f, "SELECT jsonb_build_array(id, enabled, role, credential_version, xmin::text)::text FROM users WHERE id = ?", batch.actor)
    private fun <T> rows(f: TestRunOrdinaryDrainFixtureV1, sql: String, args: Array<out Any?>, read: (ResultSet) -> T): List<T> =
        checkNotNull(f.observer.dataSource).connection.use { c -> c.prepareStatement(sql).use { s ->
            args.forEachIndexed { index, value -> s.setObject(index + 1, value) }
            s.executeQuery().use { r -> buildList { while (r.next()) add(read(r)) } }
        } }
    private fun strings(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg args: Any?) = rows(f, sql, args) { it.getString(1) }
    private fun scalar(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg args: Any?) = strings(f, sql, *args).single()
    private fun number(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg args: Any?) = rows(f, sql, args) { it.getLong(1) }.single()
    private fun update(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg args: Any?) =
        checkNotNull(f.observer.dataSource).connection.use { c -> c.prepareStatement(sql).use { s ->
            args.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate()
        } }
    private fun assertNativeCopies(native: TestOrdinaryInventoryHttpFixtureV1, versions: Int) {
        assertEquals(versions * 3, native.requests.count { it.kind == "GET" })
        assertEquals(2 * ((versions + 1) / 2), native.requests.count { it.kind == "LIST" })
    }
    private val literal = OwnerDeleteLiteralCharges
    private val promise = literal.installation + literal.resource.scaled(2) + literal.audit.scaled(6) + literal.appliedOnly.scaled(4)
    private val normalUse = literal.audit.scaled(2) + literal.appliedOnly
    private val aliasUse = literal.audit + literal.appliedOnly
    private val stagingCounters = setOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.SCAN_ENTRIES)
}
