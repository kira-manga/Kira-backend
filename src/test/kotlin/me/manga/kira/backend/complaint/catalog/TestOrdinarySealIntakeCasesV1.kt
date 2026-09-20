package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinarySealResultV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows

internal enum class TestOrdinarySealIntakeCutV1 { NONE, S3_CONSTRUCTION, NATIVE_CLOSE }

/** One new intake-originated connection and two construction/close faults, not a replay of the two-pass matrix. */
internal object TestOrdinarySealIntakeCasesV1 {
    fun connected(tls: VersionBoundPersistenceConnectedFixture, cut: TestOrdinarySealIntakeCutV1) {
        val http = TestOrdinarySealHttpFixtureV1(protectedIntake = true)
        var injected = false
        var reachedRegisteredTarget = false
        var completedAssertions = false
        http.beforeNativeFactory = { kind ->
            if (cut == TestOrdinarySealIntakeCutV1.S3_CONSTRUCTION && kind == "S3") {
                injected = true
                error("Synthetic unreturned S3 factory.")
            }
        }
        http.beforeNativeRequest = { _, remaining ->
            val before = remaining()
            http.offsetNanos += 1_000_000L
            assertTrue(remaining() < before, "Retained native callback must use the original in-flight request budget.")
        }
        val run = {
            withOrdinarySealRun(tls, expireClosedSetupPredecessors = true, httpFixture = http) { f, _ ->
                val assembly = checkNotNull(f.p.f.rows.evidence.intakeAssembly)
                val target = assembly.target
                assertSame(target, f.registration.process, "PROJECT must register the actual intake target, not an independently supplied D/receipt.")
                assertSame(target.pools, f.runtime.pools)
                assertSame(assembly.lifecycleOwner, f.runtime.owner)
                assertEquals(0, f.runtime.acquisitions, "The observer fixture must not acquire/construct a replacement normal graph.")
                assertArrayEquals(f.p.f.rows.evidence.process.canonicalBytes(), target.canonicalBytes())
                val inventory = Json.parseToJsonElement(target.canonicalBytes().decodeToString()).jsonObject.getValue("ordinarySeal").jsonObject
                assertEquals("TEST_FIRST_ORDINARY_EPOCH_SEAL_INDEPENDENT_DECLARATIONS", inventory.getValue("profile").jsonPrimitive.content)
                assertEquals("INDEPENDENT_DECLARATIONS_EXTERNAL_VERIFICATION_REQUIRED", inventory.getValue("externalIntake").jsonPrimitive.content)
                assertTrue(http.nativeFactories.isEmpty() && http.requestBudgets.isEmpty(), "Signing/PROJECT/registration must not open a seal client.")
                reachedRegisteredTarget = true
                if (cut == TestOrdinarySealIntakeCutV1.NATIVE_CLOSE) http.onNativeClose = {
                    assertEquals(1L, target.publicationLanes.activeOwners().totalOwners)
                    if (!injected) { injected = true; error("Synthetic native close did not return.") }
                }
                val original = f.begin()
                if (cut == TestOrdinarySealIntakeCutV1.NONE) {
                    assertEquals(TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, original.seal())
                    f.assertReleased(original)
                    assertEquals(listOf("STS", "STS", "KMS", "S3"), http.nativeFactories)
                    assertEquals(setOf("STS", "KMS", "S3"), http.requestBudgets.map { it.first }.toSet())
                    assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "GENERATE", "LIST", "PUT", "LIST", "GET", "DECRYPT"), http.order)
                    assertEquals("SEAL_VERIFIED", f.control()["seal_state"])
                } else {
                    assertThrows<TestOrdinarySealExceptionV1> { original.seal() }
                    assertTrue(injected)
                    assertEquals(1L, target.publicationLanes.activeOwners().totalOwners)
                    assertTrue(f.probe.calls.none { it.step == TestOrdinarySealStepV1.VERIFY })
                    assertNull(f.control()["seal_verified_at"])
                    assertEquals("SEAL_PREPARED", f.control()["seal_state"])
                    val closes = listOf(http.sts.closedClients, http.kms.closedClients, http.s3Closed)
                    assertThrows<RuntimeException> { checkNotNull(target.ordinarySeal).close() }
                    assertEquals(
                        closes, listOf(http.sts.closedClients, http.kms.closedClients, http.s3Closed),
                        "A failed native return cannot become a second close receipt.",
                    )
                    assertEquals(1L, target.publicationLanes.activeOwners().totalOwners)
                    http.assertDisposed(requireReturnedClose = cut != TestOrdinarySealIntakeCutV1.NATIVE_CLOSE)
                }
                val runRow = f.observer.queryForMap(
                    "SELECT state, purging_at, purged_at, terminal_event_id, terminal_catalog_generation " +
                        "FROM complaint_test_runs WHERE data_scope_id = ?", f.scope,
                )
                assertEquals("SEALED", runRow["state"])
                runRow.filterKeys { it != "state" }.values.forEach { assertNull(it) }
                assertFalse(f.probe.calls.any { "PURGED" in it.sql || "permanent_denial_bytes =" in it.sql })
                f.probe.assertNoLostAssertions()
                completedAssertions = true
            }
        }
        if (cut == TestOrdinarySealIntakeCutV1.NONE) run() else assertThrows<RuntimeException> { run() }
        assertTrue(reachedRegisteredTarget, "A setup failure must not count as the intended native fault specimen.")
        assertTrue(completedAssertions, "All post-call custody and no-settlement assertions must run before the expected cleanup failure.")
        if (cut != TestOrdinarySealIntakeCutV1.NONE) assertTrue(injected)
    }
}
