package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationSourceSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealResultV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

internal enum class TestOrdinarySealProviderCutV1 { WRONG_ROLE, VERSION, WIRE, METADATA, LOCK_MODE, RETENTION }
internal enum class TestOrdinarySealLifetimeCutV1 { CANCELLATION, CLOSED_REGISTRATION, LATE_NATIVE_CLOSE, DATABASE_FENCE }

/** Failure-only injection at existing JDBC/transaction/raw-HTTP seams; no substitute operation or release receipt. */
internal object TestOrdinarySealFailureCasesV1 {
    fun completion(tls: VersionBoundPersistenceConnectedFixture, step: TestOrdinarySealStepV1, cut: TestRegistrationCompletionCut) = withOrdinarySealRun(tls, expireClosedSetupPredecessors = true) { f, _ ->
        require(step in setOf(TestOrdinarySealStepV1.PREPARE, TestOrdinarySealStepV1.FREEZE, TestOrdinarySealStepV1.VERIFY))
        val before = f.image()
        val counters = f.p.counters()
        val unused = TestOrdinarySealCasesV1.unused(f)
        val resource = Any(); val sentinel = Any()
        var bound = false
        var selected: PersistencePhaseContext? = null
        var stageBefore: Map<String, List<String>>? = null
        f.probe.before = { call -> if (call.step === step && stageBefore == null) stageBefore = f.image() }
        f.probe.after = { call ->
            val statement = when (step) {
                TestOrdinarySealStepV1.PREPARE -> TestOrdinarySealSqlV1.prepareControl
                TestOrdinarySealStepV1.FREEZE -> TestOrdinarySealSqlV1.freeze
                else -> TestOrdinarySealSqlV1.verifyControl
            }
            if (selected == null && call.step === step && call.sql == statement) {
                selected = call.phase
                if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                    val jdbc = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                    jdbc.execute("CREATE TEMP TABLE kira_test_seal_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_test_seal_commit_cut VALUES (1), (1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        assertThrows<TestOrdinarySealExceptionV1> { checkNotNull(f.probe.original).localInstallationObservation() }
                        if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic TEST seal beforeCommit refusal.")
                    }
                    override fun afterCommit() {
                        assertThrows<TestOrdinarySealExceptionV1> { checkNotNull(f.probe.original).localInstallationObservation() }
                        if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                            TransactionSynchronizationManager.bindResource(resource, sentinel)
                            bound = true
                        }
                        if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic TEST seal lost afterCommit acknowledgment.")
                    }
                })
            }
        }
        val original = f.begin()
        try {
            assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
            assertThrows<TestOrdinarySealExceptionV1> { original.localInstallationObservation() }
            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                assertTrue(bound)
                assertSame(selected, PersistencePhaseOwnership.current())
                assertTrue(checkNotNull(selected).quarantined())
                assertThrows<RuntimeException> { f.begin() }
                if (step === TestOrdinarySealStepV1.FREEZE) {
                    assertEquals(1L, f.registration.process.publicationLanes.activeOwners().totalOwners,
                        "Unresolved JDBC custody prevents native disposal; no synthetic lane release.")
                    assertEquals(0, f.http.kms.closedClients)
                }
            }
        } finally {
            f.probe.before = {}; f.probe.after = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
            requireConnectionFree() // Same original quarantine cleanup, never rehabilitation of this failed invocation.
            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) f.http.close()
        }
        f.assertNativeCloseBoundary()
        f.http.assertDisposed()
        f.probe.assertNoLostAssertions()
        val outcome = when (cut) {
            TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
            TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            else -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(outcome, checkNotNull(selected).databaseOutcome())
        assertThrows<TestOrdinarySealExceptionV1> { original.localInstallationObservation() }
        assertEquals(if (step === TestOrdinarySealStepV1.VERIFY) 2 else 0, f.probe.calls.count { it.sql == TestInstallationSourceSqlV1.page },
            "Two completed SQL observations cannot escape UNKNOWN/lost acknowledgment or unresolved original release.")
        assertFalse(f.probe.calls.any { it.step.ordinal > step.ordinal })
        if (step === TestOrdinarySealStepV1.PREPARE) assertTrue(f.http.order.isEmpty(), "PREPARE must positively commit and release before even STS.")
        if (step !== TestOrdinarySealStepV1.VERIFY) assertTrue(f.http.requests.isEmpty(), "No LIST/PUT/GET after failed, unknown or unreleased FREEZE.")
        if (outcome !== PersistenceDatabaseOutcome.COMMITTED) assertEquals(stageBefore, f.image(), "All writes of the selected failed phase roll back together.")
        if (step === TestOrdinarySealStepV1.PREPARE && outcome !== PersistenceDatabaseOutcome.COMMITTED) {
            assertEquals(counters, f.p.counters()); assertEquals(unused, TestOrdinarySealCasesV1.unused(f)); assertTrue(f.sidecarImage().isEmpty())
        } else TestOrdinarySealCasesV1.assertOnlyOneSidecarSpend(f, counters, unused)
        assertEquals(before.filterKeys { it !in changed }, f.image().filterKeys { it !in changed })
        val failedImage = f.image(); val callCount = f.probe.calls.size; val providerCount = f.http.order.size
        assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
        assertEquals(failedImage, f.image()); assertEquals(callCount, f.probe.calls.size); assertEquals(providerCount, f.http.order.size)

        if (cut === TestRegistrationCompletionCut.AFTER_COMMIT) {
            val frozen = f.sidecarImage()
            val encrypted = f.http.order.count { it == "GENERATE" }
            f.expireLeaseForRetry()
            val retry = f.begin()
            assertEquals(TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, retry.seal())
            f.assertReleased(retry)
            TestOrdinarySealCasesV1.assertOnlyOneSidecarSpend(f, counters, unused)
            if (step !== TestOrdinarySealStepV1.PREPARE) {
                assertEquals(frozen, f.sidecarImage())
                assertEquals(encrypted, f.http.order.count { it == "GENERATE" }, "Reload durable winner after lost acknowledgment; never re-encrypt it.")
            }
            assertEquals(1, f.http.requests.count { it.kind == "PUT" })
            TestOrdinarySealCasesV1.assertVerifiedLocalOnly(f, 1, 0)
            TestOrdinarySealCasesV1.assertInstallationObservation(f, retry, emptyList())
        }
    }

    fun lostPutAcknowledgment(tls: VersionBoundPersistenceConnectedFixture) = withOrdinarySealRun(tls) { f, _ ->
        f.http.lostPutAcknowledgment = true
        val original = f.begin()
        assertEquals(TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, original.seal())
        f.assertReleased(original)
        assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.http.requests.map { it.kind })
        assertEquals(500, f.http.requests.single { it.kind == "PUT" }.reply?.status)
        assertEquals(1, f.http.order.count { it == "GENERATE" })
        TestOrdinarySealCasesV1.assertVerifiedLocalOnly(f, 1, 0)
    }

    fun badProvider(tls: VersionBoundPersistenceConnectedFixture, cut: TestOrdinarySealProviderCutV1) = withOrdinarySealRun(tls) { f, _ ->
        f.http.changeSts = { stage, reply ->
            if (stage == 3 && cut === TestOrdinarySealProviderCutV1.WRONG_ROLE) {
                val replaced = reply.bytes.toString(Charsets.UTF_8).replace("AROA" + "B".repeat(17), "AROA" + "C".repeat(17)).toByteArray()
                assertEquals(reply.bytes.size, replaced.size); replaced.copyInto(reply.bytes)
            }
        }
        f.http.beforeS3 = { request ->
            if (request.kind == "GET" && cut === TestOrdinarySealProviderCutV1.WIRE) {
                val objectValue = checkNotNull(f.http.stored)
                val changed = objectValue.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                f.http.stored = objectValue.copy(bytes = changed) // Coherent raw SDK checksum, but not the durable frozen winner.
            }
        }
        f.http.changeS3 = { request, reply ->
            if (request.kind == "GET") {
                val change = when (cut) {
                    TestOrdinarySealProviderCutV1.VERSION -> "x-amz-version-id" to "foreign-version"
                    TestOrdinarySealProviderCutV1.METADATA -> "x-amz-meta-kira-journal-ciphertext-sha256" to "f".repeat(64)
                    TestOrdinarySealProviderCutV1.LOCK_MODE -> "x-amz-object-lock-mode" to "GOVERNANCE"
                    TestOrdinarySealProviderCutV1.RETENTION -> "x-amz-object-lock-retain-until-date" to Instant.parse("2037-01-01T00:00:00Z").toString()
                    else -> null
                }
                change?.let { reply.headers = reply.headers + (it.first to listOf(it.second)) }
            }
        }
        val original = f.begin()
        assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
        f.assertNativeCloseBoundary(); f.http.assertDisposed(); f.probe.assertNoLostAssertions()
        assertEquals(0L, f.registration.process.publicationLanes.activeOwners().totalOwners)
        assertEquals("SEAL_PREPARED", f.control()["seal_state"])
        assertNull(f.control()["seal_verification_bytes"])
        assertTrue(f.probe.calls.none { it.step === TestOrdinarySealStepV1.VERIFY })
        if (cut === TestOrdinarySealProviderCutV1.WRONG_ROLE) {
            assertTrue(f.http.kms.requests.isEmpty() && f.http.requests.isEmpty())
            assertEquals("CANONICAL", f.sidecar()["state"])
        } else {
            assertEquals("WIRE_FROZEN", f.sidecar()["state"])
            assertEquals(1, f.http.requests.count { it.kind == "PUT" })
            assertEquals(0, f.http.order.count { it == "DECRYPT" }, "Cheap raw version/winner/metadata/retention checks precede KMS.")
        }
        val calls = f.http.order.size
        assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
        assertEquals(calls, f.http.order.size)
    }

    fun lifetime(tls: VersionBoundPersistenceConnectedFixture, cut: TestOrdinarySealLifetimeCutV1) = withOrdinarySealRun(tls) { f, _ ->
        var observed = false
        f.http.beforeS3 = { request ->
            if (request.kind == "GET") when (cut) {
                TestOrdinarySealLifetimeCutV1.CANCELLATION -> { observed = true; throw CancellationException("Synthetic TEST seal cancellation.") }
                TestOrdinarySealLifetimeCutV1.DATABASE_FENCE -> {
                    assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?", UUID.randomUUID(), f.scope))
                    observed = true
                }
                else -> Unit
            }
        }
        f.http.changeS3 = { request, _ ->
            if (request.kind == "PUT" && cut === TestOrdinarySealLifetimeCutV1.CLOSED_REGISTRATION) { observed = true; f.registration.close() }
        }
        f.http.onNativeClose = {
            assertEquals(1L, f.registration.process.publicationLanes.activeOwners().totalOwners, "Shared routine J remains held until every native close actually returns.")
            if (cut === TestOrdinarySealLifetimeCutV1.LATE_NATIVE_CLOSE && !observed) {
                observed = true
                f.http.offsetNanos += (f.registration.process.consumers.journalConfiguration.declaration().limits.deadlines.epochSealMillis.toLong() + 1) * 1_000_000
            }
        }
        val original = f.begin()
        if (cut === TestOrdinarySealLifetimeCutV1.CANCELLATION) assertThrows<CancellationException> { original.seal() }
        else assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
        if (cut === TestOrdinarySealLifetimeCutV1.CANCELLATION) assertThrows<CancellationException> { original.localInstallationObservation() }
        else assertThrows<TestOrdinarySealExceptionV1> { original.localInstallationObservation() }
        f.http.onNativeClose = {}
        assertTrue(observed)
        f.assertNativeCloseBoundary(); f.http.assertDisposed(); f.probe.assertNoLostAssertions()
        assertEquals(0L, f.registration.process.publicationLanes.activeOwners().totalOwners)
        assertEquals("SEAL_PREPARED", f.control()["seal_state"])
        assertNull(f.control()["seal_verification_bytes"])
        val verify = f.probe.observations.keys.filter { phase -> f.probe.calls.any { it.phase === phase && it.step === TestOrdinarySealStepV1.VERIFY } }
        if (cut === TestOrdinarySealLifetimeCutV1.DATABASE_FENCE) assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, verify.single().databaseOutcome())
        else assertTrue(verify.isEmpty())
        val image = f.image(); val calls = f.http.order.size
        assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
        assertEquals(image, f.image()); assertEquals(calls, f.http.order.size)
    }

    fun failedNativeCloseKeepsLane(tls: VersionBoundPersistenceConnectedFixture) {
        var checked = false
        // The enclosing owner shutdown also retains the same failed close. It must not hide or retry that failure.
        assertThrows<RuntimeException> { withOrdinarySealRun(tls) { f, _ ->
            var failed = false
            f.http.onNativeClose = {
                assertEquals(1L, f.registration.process.publicationLanes.activeOwners().totalOwners)
                if (!failed) { failed = true; error("Synthetic native close did not return.") }
            }
            val original = f.begin()
            assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
            assertTrue(failed)
            assertEquals(1L, f.registration.process.publicationLanes.activeOwners().totalOwners)
            assertTrue(f.probe.calls.none { it.step === TestOrdinarySealStepV1.VERIFY })
            assertEquals("SEAL_PREPARED", f.control()["seal_state"])
            assertNull(f.control()["seal_verified_at"])
            f.http.assertDisposed(requireReturnedClose = false)
            val closes = listOf(f.http.sts.closedClients, f.http.kms.closedClients, f.http.s3Closed)
            assertThrows<RuntimeException> { f.http.close() }
            assertEquals(closes, listOf(f.http.sts.closedClients, f.http.kms.closedClients, f.http.s3Closed), "No second native close manufactures a receipt.")
            assertEquals(1L, f.registration.process.publicationLanes.activeOwners().totalOwners)
            f.probe.assertNoLostAssertions()
            checked = true
        } }
        assertTrue(checked, "Failure must be the intentionally retained close, not a setup failure.")
    }

    private val changed = setOf("complaint_journal_control", "complaint_test_runs", "counters", "complaint_test_terminal_intents")
}
