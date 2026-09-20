package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.TestAdminDeleteJournalTupleV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.util.UUID

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Existing PG/TLS/raw-registration/native fixtures only.
 * The historical lower gate comparisons, denial signatures and deliberate restored/damaged rows
 * remain synthetic. Scoped grant, JWT/current ADMIN, AUTH, publication, VERIFY, primary APPLY,
 * native inventories, recovery and strict post-denial seal use their actual original producers.
 * This is not an ACTIVE registered request/HTTP issuer or deployment/restore acceptance.
 */
internal object TestOrdinaryDrainAdminFamiliesCasesV1 {
    fun retainedPrimary(tls: VersionBoundPersistenceConnectedFixture, prepared: Boolean) =
        withAdminRun(tls, prepared = prepared, verifiedOnly = !prepared) { f, admin ->
            assertEquals(if (prepared) "PREPARED" else "VERIFIED", f.history.publicationState(admin.eventId))
            assertEquals("AUTHORIZED_DELETE", f.history.adminReceiptState(admin))
            // Actual earlier AUTH is committed. Later current-role/expiry changes cannot undo it.
            assertEquals(1, update(f, "UPDATE users SET enabled = false, role = 'USER' WHERE id = ?", admin.actor))
            assertEquals(1, update(f, "UPDATE admin_step_up_grants SET expires_at = used_at + interval '1 microsecond' WHERE id = ? AND used_at IS NOT NULL", admin.grant))
            val protected = protectedRows(f, admin)
            val observed = observation(f, admin)
            val before = observed.state()
            var captured: TestOrdinaryDrainAccountingStateV1? = null
            f.probe.before = { call ->
                if (captured == null && call.step === TestOrdinaryDrainStepV1.CAPTURE) {
                    captured = observed.state()
                    assertEquals("APPLIED", scalar(f, "SELECT state FROM complaint_journal_publications WHERE event_id = ?", admin.eventId))
                    assertEquals("COMPLETED", scalar(f, "SELECT state FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", admin.actor, admin.key))
                }
            }
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                val putBefore = f.provider.requests.count { it.kind == "PUT" }
                val original = complete(f, admin)
                val phases = f.jdbc.calls.filter { it.adminPrimaryOriginal != null }.distinctBy { it.phase }
                assertEquals(listOf(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD) +
                    (if (prepared) listOf(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY) else emptyList()) +
                    PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY, phases.map { it.path })
                phases.forEach { call ->
                    val child = checkNotNull(call.adminPrimaryOriginal)
                    assertSame(original.budget, child.budget)
                    assertTrue(call.phase.testRunAdminDeleteCleanupProven(child))
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, call.phase.databaseOutcome())
                }
                val primarySql = f.jdbc.calls.filter { it.adminPrimaryOriginal != null }.map { it.sql }
                assertTrue(primarySql.none { it.contains("admin_step_up_grants") || it.contains("password_hash") || it.contains("enabled") },
                    "The primary may check user existence for audit FK attribution, never current role/grant/expiry.")
                assertTrue(f.jdbc.calls.none { it.path === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE })
                assertEquals(if (prepared) 1 else 0, f.provider.requests.count { it.kind == "PUT" } - putBefore)
                assertEquals("PARTIAL", checkNotNull(captured).recoveryState)
                assertEquals(OwnerDeleteLiteralCharges.ordinaryApply, checkNotNull(captured).used)
                assertEquals(protected, protectedRows(f, admin), "Grant, user and installation credentials are immutable to continuation/drain.")
                val after = observed.state()
                observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                    recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply, recoveryRelease = before.promise - after.used,
                    actualRefund = OwnerDeleteLiteralCharges.content, allowChurn = stagingCounters)
                assertNativeCopies(native, 1)
            }
        }

    fun fourVersions(tls: VersionBoundPersistenceConnectedFixture) = withAdminRun(tls) { f, admin ->
        val observed = observation(f, admin)
        val before = observed.state()
        val primary = primaryImage(f, admin)
        val protected = protectedRows(f, admin)
        val unrelated = strings(f, "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaints c WHERE data_scope_id = ? AND id <> ? ORDER BY id", f.scope, admin.target)
        val objects = retainedVersions(f, admin, sameKeyCopies = 1, aliasKeys = 2)
        assertEquals(4, objects.size); assertEquals(3, objects.map { it.key }.distinct().size)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            complete(f, admin)
            val after = observed.state()
            assertEquals(4L, after.applied)
            assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(4), after.used)
            assertEquals(primary, primaryImage(f, admin), "Exact key/version aliases cannot replace the primary's wire proof or original grant receipt.")
            assertEquals(protected, protectedRows(f, admin))
            assertEquals(unrelated, strings(f, "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaints c WHERE data_scope_id = ? AND id <> ? ORDER BY id", f.scope, admin.target))
            assertEquals(1L, number(f, "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'COMPLAINT_DELETE_AUTHORIZED' AND complaint_actor_kind = 'ADMIN' AND actor_user_id = ?", admin.target.toString(), admin.actor))
            assertEquals(1L, number(f, "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'COMPLAINT_DELETED' AND complaint_actor_kind = 'ADMIN' AND actor_user_id = ?", admin.target.toString(), admin.actor))
            assertEquals(3L, number(f, "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED' AND complaint_actor_kind = 'SYSTEM' AND actor_user_id IS NULL", admin.target.toString()))
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply.scaled(3), recoveryRelease = before.promise - after.used, allowChurn = stagingCounters)
            assertNativeCopies(native, 4)
        }
    }

    fun recoveredIdentity(tls: VersionBoundPersistenceConnectedFixture) = withAdminRun(tls, recovered = true) { f, admin ->
        val observed = observation(f, admin)
        val before = observed.state()
        val primary = primaryImage(f, admin)
        val protected = protectedRows(f, admin)
        assertEquals("RECOVERY_RESERVED", scalar(f, "SELECT state FROM complaint_installation_ids WHERE id = ?", f.history.actor.id))
        assertEquals(0L, number(f, "SELECT count(*) FROM app_installations WHERE id = ?", f.history.actor.id))
        assertEquals(OwnerDeleteLiteralCharges.installation + OwnerDeleteLiteralCharges.resource + OwnerDeleteLiteralCharges.ordinaryApply, before.used)
        assertEquals(0L, number(f, "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'COMPLAINT_DELETED'", admin.target.toString()))
        assertEquals(1L, number(f, "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'COMPLAINT_RECOVERY_APPLIED' AND complaint_actor_kind = 'SYSTEM' AND actor_user_id IS NULL", admin.target.toString()))
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            complete(f, admin)
            val after = observed.state()
            assertEquals(before.used, after.used)
            assertEquals(primary, primaryImage(f, admin)); assertEquals(protected, protectedRows(f, admin))
            assertEquals("RECOVERY_RESERVED", scalar(f, "SELECT state FROM complaint_installation_ids WHERE id = ?", f.history.actor.id))
            assertEquals(0L, number(f, "SELECT count(*) FROM app_installations WHERE id = ?", f.history.actor.id))
            assertEquals(0L, number(f, "SELECT count(*) FROM complaints WHERE owner_id = ?", f.history.actor.id))
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoveryRelease = before.promise - after.used, allowChurn = stagingCounters)
            assertNativeCopies(native, 1)
        }
    }

    fun fifthVersion(tls: VersionBoundPersistenceConnectedFixture) = withAdminRun(tls) { f, admin ->
        val observed = observation(f, admin)
        val before = observed.state()
        val primary = primaryImage(f, admin)
        val protected = protectedRows(f, admin)
        retainedVersions(f, admin, sameKeyCopies = 4, aliasKeys = 0)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            refuse(f)
            val after = observed.state()
            assertNotNull(after.progressHex)
            assertEquals(10L, after.scanEntries); assertEquals(8L, after.appliedScanEntries)
            assertEquals(4L, after.applied); assertEquals("PARTIAL", after.recoveryState)
            assertEquals(before.used + OwnerDeleteLiteralCharges.ordinaryApply.scaled(3), after.used)
            assertEquals(primary, primaryImage(f, admin)); assertEquals(protected, protectedRows(f, admin))
            assertNoConversionOrSeal(f)
            assertEquals(15, native.requests.count { it.kind == "GET" }, "The fifth version is observed, not filtered or given a per-key allowance.")
        }
    }

    enum class Conflict { TARGET, OWNER, GRANT, ACTOR, WIRE }
    fun conflictingObject(tls: VersionBoundPersistenceConnectedFixture, conflict: Conflict) = withAdminRun(tls) { f, admin ->
        val observed = observation(f, admin)
        val before = observed.state()
        val primary = primaryImage(f, admin)
        val protected = protectedRows(f, admin)
        val old = admin.event.adminTuple
        val tuple = TestAdminDeleteJournalTupleV1(old.epoch, if (conflict == Conflict.ACTOR) UUID.randomUUID() else old.actorId,
            old.operationKey, old.fingerprintBytes(), old.scope, if (conflict == Conflict.GRANT) UUID.randomUUID() else old.consumedGrantId,
            if (conflict == Conflict.OWNER) UUID.randomUUID() else old.ownerInstallationId)
        val noKeys = NeverOwnerDeleteAllDataKeys()
        val event = TestOwnerDeleteJournalCodecV1(f.provider.routing, noKeys).canonicalizeAdmin(tuple,
            if (conflict == Conflict.TARGET) UUID.randomUUID() else admin.target, admin.event.route.routingKeyId)
        if (conflict != Conflict.ACTOR) assertEquals(admin.event.route, event.route)
        if (conflict == Conflict.WIRE) assertArrayEquals(admin.event.canonicalBytes(), event.canonicalBytes())
        val bytes = f.provider.envelope(event) // Genuine AEAD under the altered context or a fresh same-canonical data key/nonce.
        try { f.provider.objects.add(f.provider.objectFor(bytes, event, "z-conflicting-version")) } finally { bytes.fill(0) }
        assertEquals(0, noKeys.calls.get())
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use {
            refuse(f)
            val after = observed.state()
            assertEquals("PARTIAL", after.recoveryState); assertEquals(1L, after.applied)
            assertEquals(before.used, after.used)
            assertEquals(primary, primaryImage(f, admin)); assertEquals(protected, protectedRows(f, admin))
            assertNoConversionOrSeal(f)
        }
    }

    enum class Missing { RECEIPT, PUBLICATION, RESERVATION }
    fun missingPrimaryBookkeeping(tls: VersionBoundPersistenceConnectedFixture, missing: Missing) = withAdminRun(tls) { f, admin ->
        val observed = observation(f, admin)
        val protected = protectedRows(f, admin)
        var damaged: Map<String, List<String>>? = null
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val original = f.begin()
            native.beforeRequest = { request ->
                if (damaged == null && request.kind == "GET" && original.step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE) {
                    assertNotNull(observed.state().progressHex)
                    transaction(f) { sql ->
                        if (missing != Missing.RESERVATION) assertEquals(1, sql.update("DELETE FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", admin.actor, admin.key))
                        if (missing != Missing.RECEIPT) assertEquals(1, sql.update("DELETE FROM complaint_recovery_capacity_reservations WHERE event_id = ?", admin.eventId))
                        if (missing == Missing.PUBLICATION) assertEquals(1, sql.update("DELETE FROM complaint_journal_publications WHERE event_id = ?", admin.eventId))
                    }
                    damaged = observed.image()
                }
            }
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(f.approval(original), f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.assertReleased(); f.jdbc.assertReleased(requireCommitted = false); f.probe.assertReleased()
            assertEquals(checkNotNull(damaged), observed.image(), "Registered inventory cannot recreate missing N/P/L from an old-snapshot interpretation.")
            assertEquals(protected, protectedRows(f, admin))
            assertNoConversionOrSeal(f)
        }
    }

    fun recreatedAppliedTarget(tls: VersionBoundPersistenceConnectedFixture) = withAdminRun(tls) { f, admin ->
        val observed = observation(f, admin)
        val primary = primaryImage(f, admin)
        val protected = protectedRows(f, admin)
        var restored: TestOrdinaryDrainAccountingStateV1? = null
        var image: Map<String, List<String>>? = null
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val original = f.begin()
            native.beforeRequest = { request ->
                if (restored == null && request.kind == "GET" && original.step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE) {
                    // Deliberate synthetic paid current data after WITNESS. A previously-applied
                    // single event cannot silently seal over it or invent a second erasure debit.
                    transaction(f) { sql ->
                        charge(sql, OwnerDeleteLiteralCharges.content)
                        assertEquals(1, sql.update("UPDATE complaint_resource_ids SET state = 'LIVE', deleted_at = NULL WHERE id = ? AND data_scope_id = ?", admin.target, f.scope))
                        assertEquals(1, sql.update("INSERT INTO complaints(id,data_scope_id,test_only,owner_id,ownership,kind,type,status,subject,body,platform," +
                            "os_version,manufacturer,device_model,created_at,updated_at,version) VALUES (?, ?, true, ?, 'INSTALLATION','REPORT','CUSTOM','OPEN'," +
                            "'synthetic restored subject','synthetic restored content','ANDROID','','','',clock_timestamp(),clock_timestamp(),1)", admin.target, f.scope, f.history.actor.id))
                    }
                    restored = observed.state(); image = observed.image()
                }
            }
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(f.approval(original), f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.assertReleased(); f.jdbc.assertReleased(requireCommitted = false); f.probe.assertReleased()
            assertEquals(checkNotNull(restored), observed.state()); assertEquals(image, observed.image())
            assertEquals(primary, primaryImage(f, admin)); assertEquals(protected, protectedRows(f, admin))
            assertEquals(1L, number(f, "SELECT count(*) FROM complaints WHERE id = ?", admin.target))
            assertNoConversionOrSeal(f)
        }
    }

    fun auditClosureRefusal(tls: VersionBoundPersistenceConnectedFixture, badAttribution: Boolean) = withAdminRun(tls) { f, admin ->
        val observed = observation(f, admin)
        var damaged: TestOrdinaryDrainAccountingStateV1? = null
        f.probe.before = { call ->
            if (damaged == null && call.step === TestOrdinaryDrainStepV1.ADMIN_PRIMARY_PAGE) {
                if (badAttribution) assertEquals(1, update(f, "UPDATE audit_log SET complaint_actor_kind = 'SYSTEM', actor_user_id = NULL " +
                    "WHERE action = 'COMPLAINT_DELETE_AUTHORIZED' AND complaint_data_scope_id = ? AND entity_id = ?", f.scope, admin.target.toString()))
                else {
                    val use = OwnerDeleteLiteralCharges.ordinaryApply + OwnerDeleteLiteralCharges.audit
                    assertEquals(1, update(f, "UPDATE complaint_recovery_capacity_reservations SET converted_amounts = ?::bigint[] WHERE event_id = ?",
                        use.toLongArray().joinToString(",", "{", "}"), admin.eventId))
                }
                damaged = observed.state()
            }
        }
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use {
            refuse(f)
            f.probe.before = {}
            assertEquals(checkNotNull(damaged), observed.state())
            assertTrue(f.probe.calls.any { it.step === TestOrdinaryDrainStepV1.CONVERT })
            assertNoConversionOrSeal(f)
        }
    }

    fun mixedCompanion(tls: VersionBoundPersistenceConnectedFixture, spoofed: Boolean) = withAdminRun(tls, mixed = true) { f, admin ->
        val owner = TestOrdinaryDrainAccountingObservationV1(f)
        val observed = observation(f, admin)
        val all = TestOrdinaryDrainAccountingObservationV1(f, f.history.allEventId)
        val before = observed.state(); val ownerBefore = owner.state(); val allBefore = all.state()
        val primary = primaryImage(f, admin); val protected = protectedRows(f, admin)
        val ownerPrimary = owner.primaryImage(); val allPrimary = all.allPrimaryImage()
        assertEquals("DELETED", scalar(f, "SELECT state FROM complaint_installation_ids WHERE id = ?", f.history.actor.id))
        assertFalse(admin.actor == f.history.actor.id, "The Admin actor must not be confused with the resolved ALL companion owner.")
        if (spoofed) assertEquals(1, update(f, "UPDATE installation_deletion_receipts SET fingerprint = ? WHERE installation_id = ? AND deletion_key = ?",
            ByteArray(32) { 0x55 }, f.history.actor.id, f.history.allKey))
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            if (spoofed) {
                refuse(f)
                assertEquals("PARTIAL", observed.state().recoveryState); assertEquals("PARTIAL", owner.state().recoveryState)
                assertEquals("PARTIAL", all.state().recoveryState)
                assertNoConversionOrSeal(f)
            } else {
                assertEquals(3, f.provider.objects.size)
                complete(f, admin)
                val after = observed.state()
                assertEquals("CONVERTED", after.recoveryState); assertEquals("CONVERTED", owner.state().recoveryState)
                assertEquals("CONVERTED", all.state().recoveryState)
                assertEquals(before.used, after.used); assertEquals(ownerBefore.used, owner.state().used); assertEquals(allBefore.used, all.state().used)
                assertEquals(ownerPrimary, owner.primaryImage()); assertEquals(allPrimary, all.allPrimaryImage())
                observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                    recoveryRelease = before.promise - before.used + ownerBefore.promise - ownerBefore.used + allBefore.promise - allBefore.used,
                    allowChurn = stagingCounters)
                assertTrue(f.probe.calls.any { it.step === TestOrdinaryDrainStepV1.CONVERT && it.sql.contains("installation_deletion_receipts") })
                assertNativeCopies(native, 3)
            }
            assertEquals(primary, primaryImage(f, admin)); assertEquals(protected, protectedRows(f, admin))
        }
    }

    fun recoveryCompletion(tls: VersionBoundPersistenceConnectedFixture, committed: Boolean) = withAdminRun(tls) { f, admin ->
        val observed = observation(f, admin)
        val before = observed.state(); val primary = primaryImage(f, admin); val protected = protectedRows(f, admin)
        val objects = retainedVersions(f, admin, sameKeyCopies = 1, aliasKeys = 0)
        var selected: PersistencePhaseContext? = null
        var phaseBefore: TestOrdinaryDrainAccountingStateV1? = null
        var imageBefore: Map<String, List<String>>? = null
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            f.jdbc.after = { call ->
                if (selected == null && call.sql == TestOrdinaryDrainSqlV1.markApplied && call.arguments[3] == "z-same-key-1") {
                    selected = call.phase; phaseBefore = observed.state(); imageBefore = observed.image()
                    assertEquals(PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY, call.path)
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) { if (!committed) error("Synthetic Admin recovery rollback.") }
                        override fun afterCommit() { if (committed) error("Synthetic Admin recovery acknowledgment loss.") }
                    })
                }
            }
            val original = f.begin(); val approval = f.approval(original)
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.jdbc.after = {}; f.assertReleased()
            assertEquals(if (committed) PersistenceDatabaseOutcome.COMMITTED else PersistenceDatabaseOutcome.ROLLED_BACK, checkNotNull(selected).databaseOutcome())
            f.jdbc.assertReleased(requireCommitted = committed); f.probe.assertReleased()
            val interrupted = observed.state()
            assertEquals("PARTIAL", interrupted.recoveryState)
            assertEquals(if (committed) 4L else 2L, interrupted.appliedScanEntries)
            if (committed) observed.assertTransfer(checkNotNull(phaseBefore), interrupted, recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply)
            else { assertEquals(checkNotNull(phaseBefore), interrupted); assertEquals(imageBefore, observed.image()) }
            assertTrue(f.sealHttp.order.isEmpty())
            val paid = observed.runBytes("permanent_denial_bytes"); val readCount = native.requests.size
            observed.expireLeaseForRetry(); f.probe.reset(); f.jdbc.reset()
            native.observeExactGetsWithoutPriorList = true
            val retry = f.begin()
            assertArrayEquals(approval, f.approval(retry))
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                retry.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
            released(f)
            val after = observed.state()
            assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(2), after.used)
            assertEquals(primary, primaryImage(f, admin)); assertEquals(protected, protectedRows(f, admin))
            assertArrayEquals(paid, observed.runBytes("permanent_denial_bytes"))
            assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.spendAndProgress }, "Fresh original cannot pay a second witness or alias debit.")
            assertEquals(if (committed) emptyList<String>() else listOf("GET"), native.requests.drop(readCount).map { it.kind })
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply, recoveryRelease = before.promise - after.used, allowChurn = stagingCounters)
            TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, objects, approval, admin.eventId)
        }
    }

    private fun withAdminRun(tls: VersionBoundPersistenceConnectedFixture, prepared: Boolean = false, verifiedOnly: Boolean = false,
        mixed: Boolean = false, recovered: Boolean = false,
        action: (TestRunOrdinaryDrainFixtureV1, TestRunVerifiedOwnerDeleteFixture.AdminHistory) -> Unit) {
        require(!mixed || !prepared && !verifiedOnly && !recovered)
        require(!recovered || !prepared && !verifiedOnly)
        val inputs = TestOrdinaryDrainFixtureInputsV1()
        TestOrdinarySealHttpFixtureV1().use { http ->
            ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, createGlobal = if (mixed) 3 else 2,
                ordinarySealHttp = http, ordinaryDrain = inputs, registeredAdminDelete = true, expireClosedSetupPredecessors = true) { p, runtime, registration, _ ->
                assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                    TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit, expectSubsequentProviderReads = true).use { history ->
                        val previous = mutableListOf<TestOwnerDeleteJournalPublisherFixture>()
                        if (mixed) { history.authorEarlierHistory(verified = true, applied = true); previous.add(history.provider) }
                        val admin = history.authorEarlierAdminHistory(verified = !prepared, applied = !prepared && !verifiedOnly && !recovered, recoverMissingOwner = recovered)
                        if (mixed) { previous.add(history.provider); history.authorEarlierAllHistory(1, verified = true, applied = true) }
                        previous.forEach { mergeProvider(history.provider, it) }
                        assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                        TestRunOrdinaryDrainFixtureV1(history, http, inputs).use { action(it, admin) }
                    }
                }
            }
        }
    }

    private fun mergeProvider(target: TestOwnerDeleteJournalPublisherFixture, previous: TestOwnerDeleteJournalPublisherFixture) {
        target.objects.addAll(previous.objects)
        val contexts = previous.kms.requests.filter { it.target() == AwsJournalKmsFixture.GENERATE_TARGET }.map { it.fields()["EncryptionContext"] }.toSet()
        val originalReply = target.kms.respond
        target.kms.respond = { request ->
            if (request.target() == AwsJournalKmsFixture.DECRYPT_TARGET && request.fields()["EncryptionContext"] in contexts) previous.kms.respond(request)
            else originalReply(request)
        }
    }

    private fun retainedVersions(f: TestRunOrdinaryDrainFixtureV1, admin: TestRunVerifiedOwnerDeleteFixture.AdminHistory,
        sameKeyCopies: Int, aliasKeys: Int): List<JournalPublisherObject> {
        requireConnectionFree()
        val primary = f.provider.objects.single { it.key == admin.event.route.objectKey }
        repeat(sameKeyCopies) { f.provider.objects.add(primary.copy(version = "z-same-key-${it + 1}", bytes = primary.bytes.copyOf())) }
        val noKeys = NeverOwnerDeleteAllDataKeys()
        val codec = TestOwnerDeleteJournalCodecV1(f.provider.routing, noKeys)
        f.provider.journal.declaration().routing.keys.filter { it.keyId != admin.event.route.routingKeyId }.take(aliasKeys).forEach { key ->
            val event = codec.canonicalizeAdmin(admin.event.adminTuple, admin.target, key.keyId)
            val bytes = f.provider.envelope(event)
            try { f.provider.objects.add(f.provider.objectFor(bytes, event, "retained-${key.keyId}-v1")) } finally { bytes.fill(0) }
        }
        assertEquals(0, noKeys.calls.get()); f.provider.assertClientsClosed()
        return f.provider.objects.toList()
    }

    private fun complete(f: TestRunOrdinaryDrainFixtureV1, admin: TestRunVerifiedOwnerDeleteFixture.AdminHistory): TestRunOrdinaryDrainV1 {
        val original = f.begin(); val approval = f.approval(original)
        assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
            original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
        released(f)
        TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, f.provider.objects.toList(), approval, admin.eventId)
        assertEquals(1, f.sealHttp.requests.count { it.kind == "PUT" })
        return original
    }
    private fun refuse(f: TestRunOrdinaryDrainFixtureV1) {
        val original = f.begin()
        assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(f.approval(original), f.rawEvidence, f.primaryCredentials, f.readCredentials) }
        f.assertReleased(); f.probe.assertReleased(requireCommitted = false); f.jdbc.assertReleased(requireCommitted = false)
    }
    private fun released(f: TestRunOrdinaryDrainFixtureV1) { f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased() }
    private fun assertNoConversionOrSeal(f: TestRunOrdinaryDrainFixtureV1) {
        assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.convert }); assertTrue(f.sealHttp.order.isEmpty())
    }
    private fun observation(f: TestRunOrdinaryDrainFixtureV1, admin: TestRunVerifiedOwnerDeleteFixture.AdminHistory) = TestOrdinaryDrainAccountingObservationV1(f, admin.eventId)
    private fun assertNativeCopies(native: TestOrdinaryInventoryHttpFixtureV1, versions: Int) {
        assertEquals(versions * 3, native.requests.count { it.kind == "GET" })
        assertEquals(2 * ((versions + 1) / 2), native.requests.count { it.kind == "LIST" })
    }
    private fun primaryImage(f: TestRunOrdinaryDrainFixtureV1, admin: TestRunVerifiedOwnerDeleteFixture.AdminHistory): List<String> =
        strings(f, "SELECT jsonb_build_array(to_jsonb(p), p.xmin::text)::text FROM complaint_journal_publications p WHERE event_id = ?", admin.eventId) +
            strings(f, "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM complaint_idempotency_receipts r WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", admin.actor, admin.key)
    private fun protectedRows(f: TestRunOrdinaryDrainFixtureV1, admin: TestRunVerifiedOwnerDeleteFixture.AdminHistory): List<String> =
        strings(f, "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM app_installations c WHERE id = ?", f.history.actor.id) +
            strings(f, "SELECT jsonb_build_array(to_jsonb(g), g.xmin::text)::text FROM admin_step_up_grants g WHERE id = ?", admin.grant) +
            strings(f, "SELECT jsonb_build_array(id, enabled, role, credential_version, xmin::text)::text FROM users WHERE id = ?", admin.actor)

    /** Every callback observer uses raw JDBC, never a second Spring-bound holder in an active phase. */
    private fun <T> rows(f: TestRunOrdinaryDrainFixtureV1, sql: String, args: Array<out Any?>, mapper: (ResultSet) -> T): List<T> =
        checkNotNull(f.observer.dataSource).connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { result -> buildList { while (result.next()) add(mapper(result)) } }
            }
        }
    private fun strings(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg args: Any?): List<String> = rows(f, sql, args) { it.getString(1) }
    private fun scalar(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg args: Any?): String = strings(f, sql, *args).single()
    private fun number(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg args: Any?): Long = rows(f, sql, args) { it.getLong(1) }.single()
    private fun update(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg args: Any?): Int = checkNotNull(f.observer.dataSource).connection.use { connection ->
        connection.prepareStatement(sql).use { statement -> args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }; statement.executeUpdate() }
    }
    private fun transaction(f: TestRunOrdinaryDrainFixtureV1, action: (JdbcTemplate) -> Unit) {
        requireConnectionFree()
        checkNotNull(f.observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try { action(JdbcTemplate(SingleConnectionDataSource(connection, true))); connection.commit() }
            catch (problem: Throwable) { connection.rollback(); throw problem }
        }
    }
    private fun charge(jdbc: JdbcTemplate, vector: ComplaintCapacityVector) {
        ComplaintCapacityCounter.entries.filter { vector[it] > 0 }.forEach { counter ->
            assertEquals(1, jdbc.update("UPDATE complaint_capacity_counters SET free_units = free_units - ?, actual_units = actual_units + ? WHERE name = ? AND free_units >= ?",
                vector[counter], vector[counter], counter.storedName, vector[counter]))
        }
    }
    private val stagingCounters = setOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.SCAN_ENTRIES)
}
