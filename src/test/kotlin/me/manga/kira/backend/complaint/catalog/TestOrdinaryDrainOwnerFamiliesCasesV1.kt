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
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
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
import java.sql.PreparedStatement
import java.util.UUID

/**
 * NOT_COMPILED / NOT_RUN. Narrow extension of the real registration/native/accounting fixtures.
 * Raw denial signatures, predecessor comparisons and injected restored rows are SYNTHETIC, not
 * deployment/restore authority. AUTH/VERIFY/APPLY, both native inventories, exact recovery and the
 * strict ordinary seal remain the actual producers. No successful scan/proof/result is seeded.
 */
internal object TestOrdinaryDrainOwnerFamiliesCasesV1 {
    fun ownerSameKeyCloseout(tls: VersionBoundPersistenceConnectedFixture) = withOrdinaryDrainRun(tls,
        expireClosedSetupPredecessors = true) { f ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val before = observed.state()
        val primary = observed.primaryImage()
        val objects = retainedVersions(f, sameKeyCopies = 1, aliasKeys = 0)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val phases = watch(f, observed)
            complete(f, objects)
            val after = observed.state()
            assertEquals(2L, after.applied)
            assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(2), after.used)
            assertEquals(primary, observed.primaryImage(), "A different native version cannot rewrite/recomplete the frozen primary.")
            assertRecoveryTransfers(observed, phases, expected = listOf(ComplaintCapacityVector.ZERO, OwnerDeleteLiteralCharges.ordinaryApply))
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply, recoveryRelease = before.promise - after.used,
                allowChurn = stagingCounters)
            assertNativeCopies(native, objects.size)
        }
    }

    fun ownerFourVersionsAcrossRetainedKeys(tls: VersionBoundPersistenceConnectedFixture) = withOrdinaryDrainRun(tls,
        expireClosedSetupPredecessors = true) { f ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val before = observed.state()
        val primary = observed.primaryImage()
        val objects = retainedVersions(f, sameKeyCopies = 1, aliasKeys = 2)
        assertEquals(4, objects.size); assertEquals(3, objects.map { it.key }.distinct().size)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            complete(f, objects)
            val after = observed.state()
            assertEquals(OwnerDeleteLiteralCharges.ordinaryApply.scaled(4), after.used)
            assertEquals(primary, observed.primaryImage())
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply.scaled(3), recoveryRelease = before.promise - after.used,
                allowChurn = stagingCounters)
            assertNativeCopies(native, 4)
        }
    }

    fun preparedEmptyAllCompletesBeforeCapture(tls: VersionBoundPersistenceConnectedFixture) = withAllRun(tls, count = 0, prepared = true) { f ->
        assertEquals("PREPARED", f.history.publicationState(f.history.allEventId))
        assertEquals("AUTHORIZED_DELETE", f.history.allReceiptState())
        val observed = allObservation(f)
        val before = observed.state()
        val phases = watch(f, observed)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val original = f.begin()
            val approval = f.approval(original)
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
            released(f)
            val calls = phases.values.map { it.call }
            val primaryCalls = calls.filter { it.allPrimaryOriginal != null }
            assertEquals(listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
                PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY), primaryCalls.map { it.path })
            primaryCalls.forEach { assertSame(original.budget, checkNotNull(it.allPrimaryOriginal).budget) }
            assertTrue(calls.indexOfFirst { it.step === TestOrdinaryDrainStepV1.CAPTURE } > calls.indexOfLast { it.allPrimaryOriginal != null })
            val witness = phases.values.single { it.call.step === TestOrdinaryDrainStepV1.WITNESS }.after!!
            assertEquals("PARTIAL", witness.recoveryState)
            assertEquals(allPromise, witness.promise)
            assertEquals(allPrimaryUse(0), witness.used, "Completing the genuine primary cannot release any future ALL promise.")
            assertEquals("APPLIED", f.history.publicationState(f.history.allEventId))
            assertEquals("COMPLETED", f.history.allReceiptState())
            assertEquals(1, f.provider.requests.count { it.kind == "PUT" }, "Only the earlier real PREPARED primary can PUT ordinary data.")
            assertRecoveryTransfers(observed, phases, expected = listOf(ComplaintCapacityVector.ZERO))
            val after = observed.state()
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = allPrimaryUse(0), recoveryRelease = allPromise - after.used, allowChurn = stagingCounters)
            TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, f.provider.objects.toList(), approval, f.history.allEventId)
            assertNativeCopies(native, 1)
        }
    }

    fun allFourVersionsAcrossRetainedKeys(tls: VersionBoundPersistenceConnectedFixture) = withAllRun(tls, count = 2) { f ->
        val observed = allObservation(f)
        val before = observed.state()
        val primary = observed.allPrimaryImage()
        val objects = retainedVersions(f, sameKeyCopies = 1, aliasKeys = 2)
        val phases = watch(f, observed)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            complete(f, objects, f.history.allEventId)
            val after = observed.state()
            assertEquals(4L, after.applied)
            assertEquals(allPromise, after.promise)
            assertEquals(allPrimaryUse(2) + OwnerDeleteLiteralCharges.ordinaryApply.scaled(3), after.used)
            assertEquals(0L, after.used[ComplaintCapacityCounter.JOURNAL_RETIREMENTS])
            assertEquals(primary, observed.allPrimaryImage())
            assertRecoveryTransfers(observed, phases, expected = listOf(ComplaintCapacityVector.ZERO) + List(3) { OwnerDeleteLiteralCharges.ordinaryApply })
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply.scaled(3), recoveryRelease = allPromise - after.used,
                allowChurn = stagingCounters)
            assertEquals(0L, count(f, "complaint_deletion_journal_retirements"), "TEST cannot invent production retirement rows to release their promise.")
            assertNativeCopies(native, 4)
        }
    }

    fun mixedOwnerThenAllNeedsRealCompanion(tls: VersionBoundPersistenceConnectedFixture) = withAllRun(tls, count = 1, mixed = true) { f ->
        val owner = TestOrdinaryDrainAccountingObservationV1(f)
        val all = allObservation(f)
        val before = owner.state()
        val allBefore = all.state()
        val ownerPrimary = owner.primaryImage()
        val allPrimary = all.allPrimaryImage()
        assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ?", String::class.java, f.history.actor.id))
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val objects = f.provider.objects.toList()
            assertEquals(2, objects.size)
            complete(f, objects, f.history.allEventId)
            val after = owner.state()
            assertEquals("CONVERTED", after.recoveryState); assertEquals("CONVERTED", all.state().recoveryState)
            assertEquals(before.used, after.used); assertEquals(allBefore.used, all.state().used)
            assertEquals(ownerPrimary, owner.primaryImage()); assertEquals(allPrimary, all.allPrimaryImage())
            assertEquals(2L, after.applied)
            observedCompanionReads(f)
            owner.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoveryRelease = before.promise - before.used + allBefore.promise - allBefore.used, allowChurn = stagingCounters)
            assertNativeCopies(native, 2)
        }
    }

    fun fifthVersionRefuses(tls: VersionBoundPersistenceConnectedFixture, all: Boolean) = withFamily(tls, all) { f ->
        val observed = if (all) allObservation(f) else TestOrdinaryDrainAccountingObservationV1(f)
        val primary = if (all) observed.allPrimaryImage() else observed.primaryImage()
        val before = observed.state()
        val objects = retainedVersions(f, sameKeyCopies = 4, aliasKeys = 0)
        assertEquals(5, objects.size); assertEquals(1, objects.map { it.key }.distinct().size)
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            refuse(f)
            val after = observed.state()
            assertNotNull(after.progressHex, "Both complete sets include the fifth version; it is not silently filtered.")
            assertEquals(10L, after.scanEntries)
            assertEquals(8L, after.appliedScanEntries)
            assertEquals(4L, after.applied, "Four is a TOTAL exact-version promise, not four per retained key.")
            assertEquals(before.used + OwnerDeleteLiteralCharges.ordinaryApply.scaled(3), after.used)
            assertEquals("PARTIAL", after.recoveryState)
            assertEquals(primary, if (all) observed.allPrimaryImage() else observed.primaryImage())
            assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.convert })
            assertTrue(f.sealHttp.order.isEmpty())
            assertEquals(15, native.requests.count { it.kind == "GET" }, "Even the refused fifth version was freshly read natively.")
        }
    }

    fun conflictingSameKeyCanonicalBodyRefuses(tls: VersionBoundPersistenceConnectedFixture, all: Boolean) = withFamily(tls, all) { f ->
        val observed = if (all) allObservation(f) else TestOrdinaryDrainAccountingObservationV1(f)
        val before = observed.state()
        val unchanged = if (all) observed.allPrimaryImage() else observed.primaryImage()
        val primary = f.provider.event
        val noKeys = NeverOwnerDeleteAllDataKeys()
        val conflicting = TestOwnerDeleteJournalCodecV1(f.provider.routing, noKeys)
            .canonicalize(primary.tuple, listOf(UUID.randomUUID()), primary.route.routingKeyId)
        assertEquals(primary.route, conflicting.route)
        assertFalse(primary.canonicalBytes().contentEquals(conflicting.canonicalBytes()))
        val wire = f.provider.envelope(conflicting) // Real AEAD/KMS fixture bytes, not a damaged ciphertext or fake readback.
        try { f.provider.objects.add(f.provider.objectFor(wire, conflicting, "z-conflicting-version")) } finally { wire.fill(0) }
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            refuse(f)
            val after = observed.state()
            assertNotNull(after.progressHex, "The codec must authenticate both native inventories before semantic recovery refuses.")
            assertEquals(4L, after.scanEntries)
            assertEquals(1L, after.applied)
            assertEquals(before.used, after.used)
            assertEquals("PARTIAL", after.recoveryState)
            assertEquals(unchanged, if (all) observed.allPrimaryImage() else observed.primaryImage())
            assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.convert })
            assertTrue(f.sealHttp.order.isEmpty())
            assertEquals(6, native.requests.count { it.kind == "GET" })
        }
    }

    fun allExactReplayErasesNewPaidContent(tls: VersionBoundPersistenceConnectedFixture) = withAllRun(tls, count = 0) { f ->
        val observed = allObservation(f)
        val primary = observed.allPrimaryImage()
        val objects = retainedVersions(f, sameKeyCopies = 1, aliasKeys = 0)
        val primaryVersion = objects.single { !it.version.startsWith("z-") }.version
        val added = UUID.randomUUID()
        var restored: TestOrdinaryDrainAccountingStateV1? = null
        var credentialAfterErasure: String? = null
        val phases = watch(f, observed)
        try {
            TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                val original = f.begin()
                native.beforeRequest = { request ->
                    if (restored == null && request.kind == "GET" && original.step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE) {
                        assertEquals(primaryVersion, request.http.rawQueryParameters().getValue("versionId").single())
                        assertNotNull(observed.state().progressHex)
                        restoreActiveWithContent(f, added)
                        restored = observed.state()
                    }
                }
                f.jdbc.after = { call ->
                    if (call.sql == TestOrdinaryDrainSqlV1.markApplied) TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            val image = credentialImage(f)
                            if (credentialAfterErasure == null) credentialAfterErasure = image
                            else assertEquals(credentialAfterErasure, image, "A later alias preserves the already-DELETED credential byte-for-byte.")
                        }
                    })
                }
                val approval = f.approval(original)
                assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                    original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
                f.jdbc.after = {}; released(f)
                val after = observed.state()
                assertNotNull(restored)
                assertEquals(2L, after.applied)
                val extraUse = OwnerDeleteLiteralCharges.audit.scaled(2) + OwnerDeleteLiteralCharges.ordinaryApply
                assertEquals(allPrimaryUse(0) + extraUse, after.used)
                val recoveries = phases.values.filter { it.call.step === TestOrdinaryDrainStepV1.RECOVERY_APPLY }
                assertEquals(listOf(0L, 1L), recoveries.map { (checkNotNull(it.after).used - it.before.used)[ComplaintCapacityCounter.JOURNAL_APPLIED] })
                assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ?", Long::class.java, f.history.actor.id))
                assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ?", String::class.java, added))
                assertEquals(2L, f.observer.queryForObject("SELECT credential_version FROM app_installations WHERE id = ?", Long::class.java, f.history.actor.id))
                assertEquals(1, f.jdbc.calls.count { it.sql.startsWith("UPDATE app_installations SET state = 'DELETED'") })
                assertEquals(primary, observed.allPrimaryImage(), "New current data does not mutate the original ALL snapshot, receipt or proof.")
                observed.assertTransfer(checkNotNull(restored), after, reserveSpend = TestOrdinaryDrainLiteralV1.sidecar,
                    recoverySpend = extraUse, recoveryRelease = allPromise - after.used, recycled = staging(2),
                    actualRefund = OwnerDeleteLiteralCharges.content)
                TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, objects, approval, f.history.allEventId)
            }
        } finally {
            f.jdbc.after = {}
            // Owned synthetic extra resource, not a production settlement/refund or success oracle.
            f.observer.update("DELETE FROM complaints WHERE id = ? AND data_scope_id = ?", added, f.scope)
            f.observer.update("DELETE FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ?", added, f.scope)
        }
    }

    fun allExactReplayReconstructsOnlyMissingSnapshotResource(tls: VersionBoundPersistenceConnectedFixture) = withAllRun(tls, count = 1) { f ->
        val observed = allObservation(f)
        val primary = observed.allPrimaryImage()
        val credential = credentialImage(f)
        val target = f.history.allTargets.single()
        var missing: TestOrdinaryDrainAccountingStateV1? = null
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            val original = f.begin()
            native.beforeRequest = { request ->
                if (missing == null && request.kind == "GET" && original.step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE) {
                    fixtureTransaction(f) { jdbc ->
                        assertEquals(1, jdbc.update("DELETE FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ?", target, f.scope))
                        fixtureCharge(jdbc, OwnerDeleteLiteralCharges.resource, refund = true)
                    }
                    missing = observed.state()
                }
            }
            val approval = f.approval(original)
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
            released(f)
            val after = observed.state()
            val extraUse = OwnerDeleteLiteralCharges.resource + OwnerDeleteLiteralCharges.audit
            assertEquals(1L, after.applied)
            assertEquals(allPrimaryUse(1) + extraUse, after.used)
            assertEquals(0L, after.used[ComplaintCapacityCounter.INSTALLATION_IDS])
            assertEquals(primary, observed.allPrimaryImage()); assertEquals(credential, credentialImage(f))
            assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ?", String::class.java, target))
            observed.assertTransfer(checkNotNull(missing), after, reserveSpend = TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = extraUse, recoveryRelease = allPromise - after.used, recycled = staging(1))
            TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, f.provider.objects.toList(), approval, f.history.allEventId)
        }
    }

    fun allUnattributedAuditUseRefusesConversion(tls: VersionBoundPersistenceConnectedFixture) = withAllRun(tls, count = 0) { f ->
        val observed = allObservation(f)
        var damaged: TestOrdinaryDrainAccountingStateV1? = null
        f.probe.before = { call ->
            if (damaged == null && call.step === TestOrdinaryDrainStepV1.PRIMARY_PAGE) {
                // Legal fixed-vector grammar, but no corresponding counted audit or counter debit.
                val wrong = allPrimaryUse(0) + OwnerDeleteLiteralCharges.audit
                assertEquals(1, observerUpdate(f, "UPDATE complaint_recovery_capacity_reservations SET converted_amounts = ?::bigint[] WHERE event_id = ?",
                    wrong.toLongArray().joinToString(",", "{", "}"), f.history.allEventId))
                damaged = observed.state()
            }
        }
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use {
            refuse(f)
            f.probe.before = {}
            assertEquals(checkNotNull(damaged), observed.state())
            assertEquals("PARTIAL", observed.state().recoveryState)
            assertTrue(f.probe.calls.any { it.step === TestOrdinaryDrainStepV1.CONVERT })
            assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.convert })
            assertTrue(f.sealHttp.order.isEmpty())
        }
    }

    fun mixedSpoofedCompanionRefusesBothResiduals(tls: VersionBoundPersistenceConnectedFixture) = withAllRun(tls, count = 1, mixed = true) { f ->
        val owner = TestOrdinaryDrainAccountingObservationV1(f)
        val all = allObservation(f)
        var damaged: TestOrdinaryDrainAccountingStateV1? = null
        f.probe.before = { call ->
            if (damaged == null && call.step === TestOrdinaryDrainStepV1.PRIMARY_PAGE) {
                assertEquals(1, observerUpdate(f, "UPDATE installation_deletion_receipts SET fingerprint = ? WHERE installation_id = ? AND deletion_key = ?",
                    ByteArray(32) { 0x55 }, f.history.actor.id, f.history.allKey))
                damaged = owner.state()
            }
        }
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use {
            refuse(f)
            f.probe.before = {}
            assertEquals(checkNotNull(damaged), owner.state())
            assertEquals("PARTIAL", owner.state().recoveryState); assertEquals("PARTIAL", all.state().recoveryState)
            assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.convert }, "DELETED alone, or an inexact companion receipt, cannot release either P−U.")
            assertTrue(f.sealHttp.order.isEmpty())
        }
    }

    fun allRecoveryCompletion(tls: VersionBoundPersistenceConnectedFixture, committed: Boolean) = withAllRun(tls, count = 0) { f ->
        val observed = allObservation(f)
        val before = observed.state()
        val primary = observed.allPrimaryImage()
        val objects = retainedVersions(f, sameKeyCopies = 1, aliasKeys = 0)
        var selected: PersistencePhaseContext? = null
        var phaseBefore: TestOrdinaryDrainAccountingStateV1? = null
        var imageBefore: Map<String, List<String>>? = null
        TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
            f.jdbc.after = { call ->
                if (selected == null && call.sql == TestOrdinaryDrainSqlV1.markApplied && call.arguments[3] == "z-same-key-1") {
                    selected = call.phase; phaseBefore = observed.state(); imageBefore = observed.image()
                    assertEquals(PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, call.path)
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) { if (!committed) error("Synthetic ALL recovery rollback.") }
                        override fun afterCommit() { if (committed) error("Synthetic ALL recovery acknowledgment loss.") }
                    })
                }
            }
            val original = f.begin()
            val approval = f.approval(original)
            assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
            f.jdbc.after = {}; f.assertReleased()
            val expectedOutcome = if (committed) PersistenceDatabaseOutcome.COMMITTED else PersistenceDatabaseOutcome.ROLLED_BACK
            assertEquals(expectedOutcome, checkNotNull(selected).databaseOutcome())
            f.jdbc.assertReleased(requireCommitted = committed); f.probe.assertReleased()
            val interrupted = observed.state()
            assertEquals("PARTIAL", interrupted.recoveryState)
            assertEquals(if (committed) 4L else 2L, interrupted.appliedScanEntries)
            if (!committed) {
                assertEquals(checkNotNull(phaseBefore), interrupted)
                assertEquals(imageBefore, observed.image(), "Domain/U/audits/applied and both staged copies roll back as one privacy transaction.")
            } else observed.assertTransfer(checkNotNull(phaseBefore), interrupted, recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply)
            assertTrue(f.sealHttp.order.isEmpty())
            val paid = observed.runBytes("permanent_denial_bytes")
            val readCount = native.requests.size
            observed.expireLeaseForRetry(); f.probe.reset(); f.jdbc.reset()
            native.observeExactGetsWithoutPriorList = true
            val retry = f.begin()
            assertArrayEquals(approval, f.approval(retry))
            assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                retry.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
            released(f)
            val after = observed.state()
            assertEquals(allPrimaryUse(0) + OwnerDeleteLiteralCharges.ordinaryApply, after.used)
            assertEquals(primary, observed.allPrimaryImage())
            assertArrayEquals(paid, observed.runBytes("permanent_denial_bytes"))
            assertTrue(f.probe.calls.none { it.sql == TestOrdinaryDrainSqlV1.spendAndProgress }, "A fresh original cannot pay WITNESS a second time.")
            assertEquals(if (committed) emptyList<String>() else listOf("GET"), native.requests.drop(readCount).map { it.kind })
            observed.assertTransfer(before, after, reserveSpend = TestOrdinaryDrainLiteralV1.delta + TestOrdinaryDrainLiteralV1.sidecar,
                recoverySpend = OwnerDeleteLiteralCharges.ordinaryApply, recoveryRelease = allPromise - after.used, allowChurn = stagingCounters)
            TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, objects, approval, f.history.allEventId)
        }
    }

    private fun withFamily(tls: VersionBoundPersistenceConnectedFixture, all: Boolean, action: (TestRunOrdinaryDrainFixtureV1) -> Unit) {
        if (all) withAllRun(tls, count = 1, action = action)
        else withOrdinaryDrainRun(tls, expireClosedSetupPredecessors = true, action = action)
    }

    private fun refuse(f: TestRunOrdinaryDrainFixtureV1) {
        val original = f.begin()
        assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(f.approval(original), f.rawEvidence, f.primaryCredentials, f.readCredentials) }
        f.assertReleased(); f.jdbc.assertReleased(requireCommitted = false); f.probe.assertReleased(requireCommitted = false)
    }

    /** Valid old ACTIVE pair + independently paid new resource/content; not an authorized operator restore. */
    private fun restoreActiveWithContent(f: TestRunOrdinaryDrainFixtureV1, id: UUID) = fixtureTransaction(f) { jdbc ->
        assertEquals(1, jdbc.update("UPDATE complaint_installation_ids SET state = 'ACTIVE', terminal_at = NULL WHERE id = ? AND state = 'DELETED'", f.history.actor.id))
        assertEquals(1, jdbc.update("UPDATE app_installations SET state = 'ACTIVE', credential_version = 1, platform = 'ANDROID', owner_reference = ?, " +
            "last_authenticated_at = clock_timestamp(), deleted_at = NULL, verifier_expires_at = NULL WHERE id = ? AND state = 'DELETED'",
            UUID.randomUUID(), f.history.actor.id))
        fixtureCharge(jdbc, OwnerDeleteLiteralCharges.resource + OwnerDeleteLiteralCharges.content, refund = false)
        assertEquals(1, jdbc.update("INSERT INTO complaint_resource_ids(id,data_scope_id,test_only,state,created_at) VALUES (?, ?, true, 'LIVE', clock_timestamp())", id, f.scope))
        assertEquals(1, jdbc.update("INSERT INTO complaints(id,data_scope_id,test_only,owner_id,ownership,kind,type,status,subject,body,platform," +
            "os_version,manufacturer,device_model,created_at,updated_at,version) VALUES (?, ?, true, ?, 'INSTALLATION','REPORT','CUSTOM','OPEN'," +
            "'synthetic restored subject','synthetic new content','ANDROID','','','',clock_timestamp(),clock_timestamp(),1)", id, f.scope, f.history.actor.id))
    }

    private fun fixtureTransaction(f: TestRunOrdinaryDrainFixtureV1, action: (JdbcTemplate) -> Unit) {
        requireConnectionFree()
        checkNotNull(f.observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try { action(JdbcTemplate(SingleConnectionDataSource(connection, true))); connection.commit() }
            catch (failure: Throwable) { connection.rollback(); throw failure }
        }
    }
    private fun fixtureCharge(jdbc: JdbcTemplate, charge: ComplaintCapacityVector, refund: Boolean) {
        ComplaintCapacityCounter.entries.filter { charge[it] > 0 }.forEach { counter ->
            val delta = if (refund) -charge[counter] else charge[counter]
            assertEquals(1, jdbc.update("UPDATE complaint_capacity_counters SET free_units = free_units - ?, actual_units = actual_units + ? " +
                "WHERE name = ? AND free_units >= ? AND actual_units >= ?", delta, delta, counter.storedName,
                if (refund) 0 else charge[counter], if (refund) charge[counter] else 0))
        }
    }
    /** Raw observer statements never enlist another Spring resource in the real phase/callback. */
    private fun <T> observerStatement(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg arguments: Any?, action: (PreparedStatement) -> T): T =
        checkNotNull(f.observer.dataSource).connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                action(statement)
            }
        }
    private fun observerUpdate(f: TestRunOrdinaryDrainFixtureV1, sql: String, vararg arguments: Any?): Int =
        observerStatement(f, sql, *arguments) { it.executeUpdate() }
    private fun credentialImage(f: TestRunOrdinaryDrainFixtureV1): String = observerStatement(f,
        "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM app_installations c WHERE id = ?", f.history.actor.id) { statement ->
        statement.executeQuery().use { rows ->
            assertTrue(rows.next())
            checkNotNull(rows.getString(1)).also { assertFalse(rows.next()) }
        }
    }
    private fun staging(versions: Int) = TestOrdinaryDrainLiteralV1.scanRun.scaled(2) + TestOrdinaryDrainLiteralV1.scanEntry.scaled(2L * versions)

    private fun withAllRun(tls: VersionBoundPersistenceConnectedFixture, count: Int, prepared: Boolean = false, mixed: Boolean = false,
        action: (TestRunOrdinaryDrainFixtureV1) -> Unit) {
        val inputs = TestOrdinaryDrainFixtureInputsV1()
        TestOrdinarySealHttpFixtureV1().use { http ->
            ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ownerDeleteAll = true, ordinarySealHttp = http,
                ordinaryDrain = inputs, expireClosedSetupPredecessors = true) { p, runtime, registration, _ ->
                assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                    TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit,
                        expectSubsequentProviderReads = true).use { history ->
                        val previous = if (mixed) { history.authorEarlierHistory(verified = true, applied = true); history.provider } else null
                        history.authorEarlierAllHistory(count, verified = !prepared, applied = !prepared)
                        previous?.let { ownerProvider ->
                            // Preserve genuine earlier wire bytes AND their original raw KMS responder.
                            // Per-provider synthetic wrapped keys may coincide, but contexts cannot.
                            history.provider.objects.addAll(ownerProvider.objects)
                            val ownerContexts = ownerProvider.kms.requests.filter { it.target() == AwsJournalKmsFixture.GENERATE_TARGET }
                                .map { it.fields()["EncryptionContext"] }.toSet()
                            val allReply = history.provider.kms.respond
                            history.provider.kms.respond = { request ->
                                if (request.target() == AwsJournalKmsFixture.DECRYPT_TARGET && request.fields()["EncryptionContext"] in ownerContexts)
                                    ownerProvider.kms.respond(request) else allReply(request)
                            }
                        }
                        assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                        TestRunOrdinaryDrainFixtureV1(history, http, inputs).use(action)
                    }
                }
            }
        }
    }

    private fun retainedVersions(f: TestRunOrdinaryDrainFixtureV1, sameKeyCopies: Int, aliasKeys: Int): List<JournalPublisherObject> {
        requireConnectionFree()
        val provider = f.provider
        val primary = provider.objects.single { it.key == provider.event.route.objectKey }
        repeat(sameKeyCopies) { provider.objects.add(primary.copy(version = "z-same-key-${it + 1}", bytes = primary.bytes.copyOf())) }
        val noKeys = NeverOwnerDeleteAllDataKeys()
        val codec = TestOwnerDeleteJournalCodecV1(provider.routing, noKeys)
        provider.journal.declaration().routing.keys.filter { it.keyId != provider.event.route.routingKeyId }.take(aliasKeys).forEach { key ->
            val event = codec.canonicalize(provider.event.tuple, provider.event.complaintIds(), key.keyId)
            val wire = provider.envelope(event)
            try { provider.objects.add(provider.objectFor(wire, event, "retained-${key.keyId}-v1")) } finally { wire.fill(0) }
        }
        assertEquals(0, noKeys.calls.get())
        provider.assertClientsClosed()
        return provider.objects.toList()
    }

    private fun complete(f: TestRunOrdinaryDrainFixtureV1, objects: List<JournalPublisherObject>, eventId: String = f.history.eventId) {
        val original = f.begin()
        val approval = f.approval(original)
        assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
            original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials))
        released(f)
        TestOrdinaryDrainAccountingCasesV1.assertPaidOrdinaryResult(f, objects, approval, eventId)
        assertEquals(1, f.sealHttp.requests.count { it.kind == "PUT" })
    }

    private fun allObservation(f: TestRunOrdinaryDrainFixtureV1) = TestOrdinaryDrainAccountingObservationV1(f, f.history.allEventId)
    private fun released(f: TestRunOrdinaryDrainFixtureV1) { f.assertReleased(); f.probe.assertReleased(); f.jdbc.assertReleased() }
    private fun count(f: TestRunOrdinaryDrainFixtureV1, table: String) = f.observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?", Long::class.java, f.scope)
    private fun assertNativeCopies(native: TestOrdinaryInventoryHttpFixtureV1, count: Int) {
        assertEquals(count * 3, native.requests.count { it.kind == "GET" }, "Both inventories and every exact recovered version are natively decrypted.")
        assertEquals(2 * ((count + 1) / 2), native.requests.count { it.kind == "LIST" })
    }
    private fun observedCompanionReads(f: TestRunOrdinaryDrainFixtureV1) {
        assertTrue(f.probe.calls.any { it.step === TestOrdinaryDrainStepV1.CONVERT && it.sql.contains("installation_deletion_receipts") })
        assertEquals(0L, count(f, "complaint_deletion_journal_retirements"))
    }

    private class Phase(val call: TestOrdinaryDrainSqlCallV1, val before: TestOrdinaryDrainAccountingStateV1) {
        var after: TestOrdinaryDrainAccountingStateV1? = null
    }
    private fun watch(f: TestRunOrdinaryDrainFixtureV1, observed: TestOrdinaryDrainAccountingObservationV1): Map<PersistencePhaseContext, Phase> {
        val phases = linkedMapOf<PersistencePhaseContext, Phase>()
        val before: (TestOrdinaryDrainSqlCallV1) -> Unit = { call ->
            if (call.phase !in phases) {
                val phase = Phase(call, observed.state())
                phases[call.phase] = phase
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() { phase.after = observed.state() }
                })
            }
        }
        f.jdbc.before = before; f.probe.before = before
        return phases
    }
    private fun assertRecoveryTransfers(observed: TestOrdinaryDrainAccountingObservationV1, phases: Map<PersistencePhaseContext, Phase>,
        expected: List<ComplaintCapacityVector>) {
        val recoveries = phases.values.filter { it.call.step === TestOrdinaryDrainStepV1.RECOVERY_APPLY }
        assertEquals(expected.size, recoveries.size)
        assertEquals(expected.groupingBy { it }.eachCount(), recoveries.map { checkNotNull(it.after).used - it.before.used }.groupingBy { it }.eachCount())
        recoveries.forEach { phase ->
            val after = checkNotNull(phase.after)
            assertEquals("PARTIAL", after.recoveryState)
            assertEquals(2L, after.appliedScanEntries - phase.before.appliedScanEntries)
            observed.assertTransfer(phase.before, after, recoverySpend = after.used - phase.before.used)
        }
    }
    private val stagingCounters = setOf(ComplaintCapacityCounter.SCAN_RUNS, ComplaintCapacityCounter.SCAN_ENTRIES)
    private val allPromise = OwnerDeleteLiteralCharges.installation + OwnerDeleteLiteralCharges.resource.scaled(100) +
        OwnerDeleteLiteralCharges.audit.scaled(113) + OwnerDeleteLiteralCharges.appliedOnly.scaled(4) +
        ComplaintCapacityVector.units(ComplaintCapacityCounter.JOURNAL_RETIREMENTS, 4).with(ComplaintCapacityCounter.STORAGE_BYTES, 4 * 262_144L)
    private fun allPrimaryUse(count: Int) = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit.scaled(count + 1L)
}
