package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.util.concurrent.CancellationException

internal enum class TestActivationSignedUnreturnedCut { CONSTRUCTION, LOST_REPLY, NATIVE_CLOSE }
internal enum class TestActivationSignedSqlCut { RETURNED_BEFORE_SQL_ARM, DEFERRED_COMMIT, AFTER_COMMIT, ORIGINAL_RELEASE }
internal enum class TestActivationSignedLifecycleCut { BEGIN_BUDGET, SIGN_BUDGET, CANCELLATION, FILE_CLOSE }

/** Real durable arms and real SQL/SDK cleanup gaps only; fresh recovery never claims the failed original succeeded. */
internal object CatalogTestRunActivationSignedRecoveryCases {
    fun unreturnedArmCannotSignAgain(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationSignedUnreturnedCut) = withSignedActivationRows(tls) { f ->
        val before = f.rows.counters.snapshot()
        var injected = false
        lateinit var original: CatalogTestRunActivationV1
        original = if (cut == TestActivationSignedUnreturnedCut.CONSTRUCTION) f.begin(signingFactory = {
            f.releasedSql()
            assertTrue(f.complete(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED))
            assertTrue(poolTestField<Boolean>(original, "signArmed"))
            assertTrue(poolTestField<Boolean>(original, "signConstructionIssued"))
            injected = true
            throw IOException("Synthetic raw signer construction did not return an original client.")
        }) else f.begin()
        if (cut == TestActivationSignedUnreturnedCut.LOST_REPLY) {
            val respond = f.signing.respond
            f.signing.respond = { request -> respond(request).also { reply ->
                reply.beforeRead = {
                    f.releasedSql()
                    injected = true
                    throw IOException("Synthetic original Sign dispatched but its response body was not returned.")
                }
            } }
        }
        if (cut == TestActivationSignedUnreturnedCut.NATIVE_CLOSE) f.signing.onClientClose = {
            f.releasedSql()
            injected = true
            throw IOException("Synthetic original raw signer close receipt lost after physical close.")
        }
        val budget = original.budget
        assertThrows<CatalogTestRunActivationExceptionV1> { f.freeze(original) }
        f.assertNoLostAssertions()
        assertTrue(injected)
        assertTrue(f.complete(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED))
        assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED))
        assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.SIGNATURE))
        assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.ENVELOPE))
        assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED))
        assertNull(f.row()["signer_one_signature"])
        assertNull(f.row()["envelope_bytes"])
        f.rows.assertPrepareCharge(before)
        if (cut == TestActivationSignedUnreturnedCut.LOST_REPLY) {
            original.requireActualCleanup()
            assertEquals(1, f.signing.returnedClientCloses)
            assertNull(SignedActivationObservation.active(tls.pools.catalogCoordinator))
        } else assertSticky(original, tls)
        val row = f.rows.preparedRow()
        val counters = f.rows.counters.snapshot()
        val leaves = f.leaves()
        val signs = f.signing.requests.size
        assertEquals(if (cut == TestActivationSignedUnreturnedCut.CONSTRUCTION) 0 else 1, signs)
        val calls = f.probe().calls.size
        assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(f.begin()) }
        if (cut != TestActivationSignedUnreturnedCut.LOST_REPLY) assertEquals(calls, f.probe().calls.size)
        f.withFreshOwner { fresh ->
            assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(f.begin(fresh)) }
            f.assertNoLostAssertions()
            assertEquals(row, f.rows.preparedRow())
            assertEquals(counters, f.rows.counters.snapshot())
            assertEquals(leaves, f.leaves())
            assertEquals(signs, f.signing.requests.size)
            assertSame(budget, original.budget)
            if (cut != TestActivationSignedUnreturnedCut.LOST_REPLY) assertSticky(original, tls)
            else assertNull(SignedActivationObservation.active(tls.pools.catalogCoordinator))
            f.rows.assertClosedAndHeadUnchanged()
        }
    }

    fun savedBytesRecoverWithoutResign(
        tls: VersionBoundPersistenceConnectedFixture,
        clock: DesiredInstallationTestClock,
        cut: TestActivationSignedSqlCut,
    ) = withSignedActivationRows(tls) { f ->
        val before = f.rows.counters.snapshot()
        val original = f.begin()
        val budget = original.budget
        var injected = false
        var afterCommit = false
        var phase: PersistencePhaseContext? = null
        var unsignedRow: String? = null
        val resourceKey = Any()
        val sentinel = Any()
        var sentinelBound = false
        f.beforeSign = { unsignedRow = f.rows.preparedRow() }
        if (cut == TestActivationSignedSqlCut.RETURNED_BEFORE_SQL_ARM) clock.onSample = {
            // Arm entry follows the returned leaf's actual seal/force/close/reread, not merely visible sidecar bytes.
            if (!injected && PersistencePhaseOwnership.current() == null && poolTestField<Boolean>(original, "signatureSqlArmIssued") &&
                f.complete(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED) &&
                !f.exists(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED)) {
                injected = true
                throw IOException("Synthetic cut after durable returned signature and before SQL arm.")
            }
        }
        f.probe().afterSql = { step ->
            if (step == "test-signature") {
                assertFalse(injected)
                phase = checkNotNull(PersistencePhaseOwnership.current())
                assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE, poolTestField<PersistencePhasePath>(phase!!, "path"))
                injected = true
                when (cut) {
                    TestActivationSignedSqlCut.DEFERRED_COMMIT -> {
                        val jdbc = JdbcTemplate(tls.pools.catalogCoordinator.dataSource)
                        jdbc.execute("CREATE TEMP TABLE kira_test_signature_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_test_signature_commit_cut VALUES (1), (1)"))
                    }
                    TestActivationSignedSqlCut.AFTER_COMMIT, TestActivationSignedSqlCut.ORIGINAL_RELEASE ->
                        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit() {
                                afterCommit = true
                                if (cut == TestActivationSignedSqlCut.ORIGINAL_RELEASE) {
                                    TransactionSynchronizationManager.bindResource(resourceKey, sentinel)
                                    sentinelBound = true
                                }
                                error("Synthetic TEST signature committed completion-tail failure.")
                            }
                        })
                    TestActivationSignedSqlCut.RETURNED_BEFORE_SQL_ARM -> error("Cut must precede actual signature dispatch.")
                }
            }
        }
        try {
            assertThrows<CatalogTestRunActivationExceptionV1> { f.freeze(original) }
            if (cut == TestActivationSignedSqlCut.ORIGINAL_RELEASE) {
                assertTrue(sentinelBound)
                assertSame(phase, PersistencePhaseOwnership.current())
                assertTrue(checkNotNull(phase).quarantined())
                assertSticky(original, tls)
            }
        } finally {
            clock.onSample = {}
            f.probe().afterSql = {}
            if (sentinelBound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resourceKey))
            requireConnectionFree() // Truthfully reconcile the same physical phase; do not clear its failed logical owner.
        }
        clock.assertNoLostAssertions()
        f.assertNoLostAssertions()
        assertTrue(injected)
        assertEquals(cut in setOf(TestActivationSignedSqlCut.AFTER_COMMIT, TestActivationSignedSqlCut.ORIGINAL_RELEASE), afterCommit)
        assertEquals(1, f.signing.requests.size)
        assertTrue(f.complete(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED))
        assertTrue(f.complete(CatalogTestRunActivationReleaseLeafV1.SIGNATURE))
        assertTrue(f.complete(CatalogTestRunActivationReleaseLeafV1.ENVELOPE))
        assertEquals(cut != TestActivationSignedSqlCut.RETURNED_BEFORE_SQL_ARM, f.complete(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED))
        assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_PERSISTED))
        assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.FREEZE_OUTCOME))
        val sqlAlreadySigned = cut in setOf(TestActivationSignedSqlCut.AFTER_COMMIT, TestActivationSignedSqlCut.ORIGINAL_RELEASE)
        if (sqlAlreadySigned) f.assertSigned() else {
            assertEquals(unsignedRow, f.rows.preparedRow(), "Actual deferred PG failure rolled back; no simulated lost COMMIT acknowledgement.")
            assertNull(f.row()["signer_one_signature"])
        }
        if (cut == TestActivationSignedSqlCut.DEFERRED_COMMIT) {
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, checkNotNull(phase).databaseOutcome())
            assertSame(phase, ownedCutField(original, "originalPhase"))
            assertTrue(poolTestField<Boolean>(original, "outcomeUncertain"))
            assertSticky(original, tls)
        } else if (sqlAlreadySigned) assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(phase).databaseOutcome())
        if (cut == TestActivationSignedSqlCut.ORIGINAL_RELEASE) {
            assertTrue(checkNotNull(phase).failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
            assertTrue(poolTestField<Boolean>(original, "sqlCleanupUnproven"))
            assertSticky(original, tls)
            f.closeUnissuedCustodyAfterSqlReconciliation(original, tls, checkNotNull(phase))
        }
        f.rows.assertPrepareCharge(before)
        val originalPhase = ownedCutField(original, "originalPhase")
        val oldSlot = SignedActivationObservation.active(tls.pools.catalogCoordinator)
        val row = f.rows.preparedRow()
        val counters = f.rows.counters.snapshot()
        val signature = f.read(CatalogTestRunActivationReleaseLeafV1.SIGNATURE)
        val envelope = f.read(CatalogTestRunActivationReleaseLeafV1.ENVELOPE)
        val leaves = f.leaves()
        f.withFreshOwner { fresh ->
            f.probe(fresh).beforeSql = { step -> if (step == "test-signature") {
                assertTrue(f.complete(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED))
            } }
            val recovered = f.begin(fresh)
            f.assertSigned(f.recover(recovered))
            f.assertReleased(recovered, fresh)
            assertEquals(1, f.signing.requests.size)
            assertEquals(counters, f.rows.counters.snapshot())
            assertArrayEquals(signature, f.read(CatalogTestRunActivationReleaseLeafV1.SIGNATURE))
            assertArrayEquals(envelope, f.read(CatalogTestRunActivationReleaseLeafV1.ENVELOPE))
            assertTrue(f.complete(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED))
            val recoveredLeaves = f.leaves()
            leaves.forEach { (path, image) -> assertEquals(image, recoveredLeaves[path], "Original custody cannot be overwritten by recovery.") }
            val newSqlArm = cut == TestActivationSignedSqlCut.RETURNED_BEFORE_SQL_ARM
            assertEquals(newSqlArm, f.complete(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_PERSISTED),
                "Only a genuinely new current-lease SQL arm can receive its own acknowledgement, never an old arm.")
            assertEquals(newSqlArm, f.complete(CatalogTestRunActivationReleaseLeafV1.FREEZE_OUTCOME),
                "Current SQL-only completion is distinct from inventing the interrupted old SQL arm's outcome.")
            assertTrue(f.probe(fresh).steps.none { it == "test-insert-prepared" || it.startsWith("charge:") })
            assertEquals(if (sqlAlreadySigned) 0 else 1, f.probe(fresh).steps.count { it == "test-signature" })
            if (sqlAlreadySigned) assertEquals(row, f.rows.preparedRow())
            assertSame(budget, original.budget)
            assertSame(originalPhase, ownedCutField(original, "originalPhase"))
            assertSame(oldSlot, SignedActivationObservation.active(tls.pools.catalogCoordinator))
        }
    }

    fun originalLifecycleCannotRevive(
        tls: VersionBoundPersistenceConnectedFixture,
        clock: DesiredInstallationTestClock,
        cut: TestActivationSignedLifecycleCut,
    ) = withSignedActivationRows(tls) { f ->
        val before = f.rows.counters.snapshot()
        val history = f.rows.history()
        val original = f.begin()
        val budget = original.budget
        var injected = false
        var fileClosed: (() -> Boolean)? = null
        when (cut) {
            TestActivationSignedLifecycleCut.BEGIN_BUDGET -> { clock.extraNanos += 30_000_000_000L; injected = true }
            TestActivationSignedLifecycleCut.SIGN_BUDGET -> f.beforeSign = { clock.extraNanos += 30_000_000_000L; injected = true }
            TestActivationSignedLifecycleCut.CANCELLATION -> f.probe().afterSql = { step -> if (step == "test-signature") {
                injected = true
                throw CancellationException("Synthetic original TEST signature cancellation after actual SQL update.")
            } }
            TestActivationSignedLifecycleCut.FILE_CLOSE -> f.signing.onClientClose = {
                assertFalse(injected)
                fileClosed = f.failOriginalFileClose(original)
                injected = true
            }
        }
        if (cut == TestActivationSignedLifecycleCut.CANCELLATION) assertThrows<CancellationException> { f.freeze(original) }
        else assertThrows<CatalogTestRunActivationExceptionV1> { f.freeze(original) }
        f.probe().afterSql = {}
        clock.assertNoLostAssertions()
        f.assertNoLostAssertions()
        assertTrue(injected)
        assertSame(budget, original.budget)
        if (cut in setOf(TestActivationSignedLifecycleCut.BEGIN_BUDGET, TestActivationSignedLifecycleCut.SIGN_BUDGET)) {
            assertThrows<PersistenceBoundaryException> { budget.remainingMillis(1) }
        }
        if (cut == TestActivationSignedLifecycleCut.BEGIN_BUDGET) {
            assertEquals(before, f.rows.counters.snapshot())
            assertEquals(history, f.rows.history())
            assertTrue(f.probe().calls.isEmpty())
            assertEquals(0, f.signing.createdClients)
            assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED))
        } else {
            f.rows.assertPrepareCharge(before)
            f.rows.assertClosedAndHeadUnchanged()
            assertSticky(original, tls)
            if (cut == TestActivationSignedLifecycleCut.FILE_CLOSE) {
                assertTrue(checkNotNull(fileClosed).invoke())
                f.assertSigned() // Durable SQL is not an original close receipt or returned success.
            } else {
                assertNull(f.row()["signer_one_signature"])
                assertNull(f.row()["envelope_bytes"])
            }
        }
        val calls = f.probe().calls.size
        val signs = f.signing.requests.size
        assertThrows<Exception> { f.freeze(original) }
        assertThrows<Exception> { f.recover(original) }
        assertEquals(calls, f.probe().calls.size)
        assertEquals(signs, f.signing.requests.size)
        assertSame(budget, original.budget)
    }

    private fun assertSticky(original: CatalogTestRunActivationV1, tls: VersionBoundPersistenceConnectedFixture) {
        assertSame(original, SignedActivationObservation.active(tls.pools.catalogCoordinator))
        assertFalse(poolTestField<Boolean>(original, "released"))
        assertFalse(poolTestField<Boolean>(original, "cleanupProven"))
        assertFalse(poolTestField<Boolean>(original, "allowedResult"))
        assertFalse(poolTestField<Boolean>(original, "allowedSignedResult"))
    }
}
