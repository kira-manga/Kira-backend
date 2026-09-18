package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/** Expected states are test observations only; they are never supplied to production as a recovery selection or capability. */
internal enum class CatalogSignerRotationColdState { PREPARED, PENDING, PROJECTED }

internal class CatalogSignerRotationColdRecoverySql(private val f: CatalogSignerRotationDeliveryFixture) {
    private val rotation = f.freeze.row()
    private val genesis = f.freeze.d7.genesisRow()

    fun observeProviderSeparation(root: CatalogSignerRotationDeliveryRoot) {
        root.jdbc.beforeSql = {
            assertEquals(f.http.read.createdClients, f.http.read.closedClients)
            assertEquals(f.http.put.createdClients, f.http.put.closedClients)
        }
    }

    fun acquired(
        root: CatalogSignerRotationDeliveryRoot,
        before: Map<String, Any?>,
        lower: Instant,
        upper: Instant,
        state: CatalogSignerRotationColdState,
    ) {
        val actual = f.leaseRow()
        val sampled = (actual["lease_expires_at"] as Timestamp).toInstant().minusSeconds(30)
        assertFalse(lower.isBefore((before["lease_expires_at"] as Timestamp).toInstant()))
        assertFalse(sampled.isBefore(lower) || sampled.isAfter(upper))
        assertNotEquals(before["lease_owner"], actual["lease_owner"])
        assertNotEquals(f.historicalLease["lease_owner"], actual["lease_owner"])
        assertEquals((before["lease_token"] as Long) + 1, actual["lease_token"])
        val pending = state === CatalogSignerRotationColdState.PENDING
        val group = fullBinding(state !== CatalogSignerRotationColdState.PREPARED) +
            if (pending) listOf(f.freeze.token) else emptyList()
        val prefix = if (pending) "pending-lease" else "lease"
        val calls = root.jdbc.calls.filter { it.path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE }
        assertEquals(
            listOf("delivery-authenticate", "$prefix-lock", "delivery-gates", "$prefix-acquire", "$prefix-read", "delivery-gates"),
            calls.map { it.step },
        )
        f.core.assertArguments(group, calls.single { it.step == "$prefix-lock" }.arguments)
        f.core.assertArguments(group, calls.single { it.step == "$prefix-read" }.arguments)
        f.core.assertArguments(
            listOf(actual["lease_owner"]) + group +
                listOf(before["lease_owner"], before["lease_token"], before["lease_expires_at"], before["updated_at"]),
            calls.single { it.step == "$prefix-acquire" }.arguments,
        )
        if (state !== CatalogSignerRotationColdState.PREPARED) {
            assertEquals(
                Duration.ofSeconds(30),
                Duration.between((actual["updated_at"] as Timestamp).toInstant(), (actual["lease_expires_at"] as Timestamp).toInstant()),
            )
        }
        assertEquals(0L, root.clock.extraNanos)
        assertEquals(0L, f.freeze.clock.extraNanos)
    }

    fun healthyPhases(root: CatalogSignerRotationDeliveryRoot, original: CatalogSignerRotationDeliveryV1) {
        for ((path, count) in listOf(
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT to 1,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE to 1,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ to 2,
        )) {
            assertEquals(count, root.phases.count { poolTestField<PersistencePhasePath>(it, "path") === path })
        }
        root.phases.forEach { phase ->
            assertSame(original, ownedCutField(phase, "signerRotationDelivery"))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.signerRotationDeliveryCleanupProven(original))
            assertTrue(root.jdbc.observations.getValue(phase).lease.completion.quiescent())
            val work = poolTestField<PersistenceTimeBudget>(phase, "signerRotationDeliveryWork")
            assertSame(original.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
            val calls = root.jdbc.calls.filter { it.phase === phase }
            assertEquals("delivery-authenticate", calls.first().step)
            f.core.assertArguments(
                listOf(PgLifecycleDatabaseSettings.CANDIDATE, PgLifecycleDatabaseSettings.CANDIDATE, PgLifecycleDatabaseSettings.DATABASE),
                calls.first().arguments,
            )
            if (calls.any { it.step.startsWith("final-") }) assertFinalOrder(calls)
        }
        assertTrue(
            root.jdbc.steps.none { it == "prepare" || it == "signature" || it.startsWith("charge:") || it == "lease-relinquish" },
        )
        root.released()
        arguments(root)
    }

    fun effectCounts(root: CatalogSignerRotationDeliveryRoot, completes: Int, projects: Int) {
        assertEquals(completes, root.jdbc.steps.count { it == "final-complete" })
        assertEquals(completes, root.jdbc.steps.count { it == "final-head" })
        assertEquals(projects, root.jdbc.steps.count { it == "final-project" })
        assertEquals(projects, root.jdbc.steps.count { it == "final-clear-pending" })
        assertTrue(
            root.jdbc.calls.filter { it.path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ }
                .none { it.step in setOf("final-complete", "final-head", "final-project", "final-clear-pending") },
        )
    }

    private fun arguments(root: CatalogSignerRotationDeliveryRoot) {
        val lease = f.leaseRow()
        val tail = listOf(lease["lease_owner"], lease["lease_token"], lease["lease_expires_at"])
        val b1 = fullBinding(head2 = false) + tail
        val b2 = fullBinding(head2 = true) + tail
        val row = f.freeze.row()
        val r17 = CatalogSignerRotationDeliveryAssertions.ROTATION_COLUMNS.map(rotation::get)
        val g21 = CatalogSignerRotationDeliveryAssertions.GENESIS_COLUMNS.map(genesis::get)
        val c6 = CatalogSignerRotationDeliveryAssertions.COPY_COLUMNS.map(row::get)
        root.jdbc.calls.forEach { call ->
            val expected = when (call.step) {
                "final-prepared-control", "final-prepared-control-read", "final-prepared-lease" -> b1
                "final-pending-control", "final-pending-control-read", "final-pending-lease" -> b2 + f.freeze.token
                "final-projected-control", "final-projected-control-read", "final-projected-lease" -> b2
                "final-initial-history-lock", "final-initial-history-read" -> r17
                "final-initial-pending-history-lock",
                "final-initial-pending-history-read",
                "final-initial-projected-history-lock",
                "final-initial-projected-history-read",
                -> r17 + c6

                "final-prepared-history-lock", "final-prepared-history-read" -> r17 + g21
                "final-pending-history-lock",
                "final-pending-history-read",
                "final-projected-history-lock",
                "final-projected-history-read",
                -> r17 + g21 + c6

                "final-complete" -> r17 + c6
                "final-head" -> b1 + listOf(f.freeze.token, digest(f.envelope))
                "final-project" -> r17 + c6 + row["completed_at"]
                "final-clear-pending" -> b2 + f.freeze.token
                else -> null
            }
            if (expected != null) f.core.assertArguments(expected, call.arguments)
        }
    }

    private fun assertFinalOrder(calls: List<CatalogSignerRotationSqlCall>) {
        val steps = calls.map { it.step }
        val control = steps[1]
        assertTrue(control in setOf("final-prepared-control", "final-pending-control", "final-projected-control"))
        val lease = control.removeSuffix("-control") + "-lease"
        assertEquals(listOf("delivery-authenticate", control, lease, "catalog"), steps.take(4))
        assertTrue(steps[4].endsWith("history-lock"))
        assertEquals("counters", steps[5])
        for (effect in listOf("final-complete", "final-project")) {
            if (effect in steps) assertEquals(lease, steps[steps.indexOf(effect) - 1])
        }
        assertTrue(steps.last().endsWith("-lease"))
        val finalRead = calls.first().path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ
        if (finalRead) assertEquals(lease, steps.last())
    }

    private fun fullBinding(head2: Boolean): List<Any?> = f.historicalBindingArguments.toMutableList().apply {
        if (head2) {
            this[5] = 2L
            this[6] = digest(f.envelope)
        }
    }

    private fun digest(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
}
