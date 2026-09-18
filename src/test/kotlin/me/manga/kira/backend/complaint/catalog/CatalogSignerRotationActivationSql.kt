package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationActivationAssertions.Companion.hash
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationObservationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Timestamp
import java.time.Instant

/** Actual TLS transactions and exact independent B12/O17/A14/C6 argument oracles; no injected holder, phase or accepted result. */
internal class CatalogSignerRotationActivationSql(private val f: CatalogSignerRotationActivationFixture) {
    private val genesis = f.freeze.d7.genesisRow()
    private val overlap = f.freeze.row()

    fun calls(root: CatalogSignerRotationActivationRoot, original: CatalogSignerRotationActivationV1): List<CatalogSignerRotationSqlCall> =
        root.jdbc.calls.filter { ownedCutField(it.phase, "signerRotationActivation") === original }

    fun acquired(
        root: CatalogSignerRotationActivationRoot,
        original: CatalogSignerRotationActivationV1,
        before: Map<String, Any?>,
        lower: Instant,
        upper: Instant,
        head3: Boolean = false,
        pending: Boolean = false,
    ) {
        val actual = f.delivery.leaseRow()
        val sampled = (actual["lease_expires_at"] as Timestamp).toInstant().minusSeconds(30)
        assertFalse(lower.isBefore((before["lease_expires_at"] as Timestamp).toInstant()))
        assertFalse(sampled.isBefore(lower) || sampled.isAfter(upper))
        assertNotEquals(before["lease_owner"], actual["lease_owner"])
        assertEquals((before["lease_token"] as Long) + 1, actual["lease_token"])
        val group = fullBinding(head3) + if (pending) listOf(f.token) else emptyList()
        val prefix = if (pending) "activation-pending-lease" else "lease"
        val calls = calls(root, original).filter { it.path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE }
        assertEquals(
            listOf("activation-authenticate", "$prefix-lock", "activation-gates", "$prefix-acquire", "$prefix-read", "activation-gates"),
            calls.map { it.step },
        )
        f.core.assertArguments(group, calls.single { it.step == "$prefix-lock" }.arguments)
        f.core.assertArguments(group, calls.single { it.step == "$prefix-read" }.arguments)
        f.core.assertArguments(
            listOf(actual["lease_owner"]) + group + listOf(before["lease_owner"], before["lease_token"], before["lease_expires_at"], before["updated_at"]),
            calls.single { it.step == "$prefix-acquire" }.arguments,
        )
        assertEquals(0L, root.clock.extraNanos)
        assertEquals(0L, f.freeze.clock.extraNanos)
    }

    fun effects(
        root: CatalogSignerRotationActivationRoot,
        original: CatalogSignerRotationActivationV1,
        prepares: Int,
        signatures: Int,
        completes: Int,
        projects: Int,
    ) {
        val steps = calls(root, original).map { it.step }
        assertEquals(prepares, steps.count { it == "activation-prepare" })
        assertEquals(signatures, steps.count { it == "activation-signature" })
        assertEquals(completes, steps.count { it == "activation-complete" })
        assertEquals(completes, steps.count { it == "activation-head" })
        assertEquals(projects, steps.count { it == "activation-project" })
        assertEquals(projects, steps.count { it == "activation-clear-pending" })
        assertEquals(prepares * 2, steps.count { it.startsWith("charge:") })
        assertTrue(steps.none { it == "lease-relinquish" || it == "prepare" || it == "signature" || it.startsWith("final-") })
        val readCalls = calls(root, original).filter { it.path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ }
        assertTrue(readCalls.none { it.step in EFFECTS || it.step.startsWith("charge:") })
    }

    fun healthy(root: CatalogSignerRotationActivationRoot, original: CatalogSignerRotationActivationV1, initiallySigned: Boolean) {
        assertSame(root.clock, poolTestField<Any>(original.budget, "clock"))
        assertEquals(30_000_000_000L, poolTestField<Long>(original.budget, "allowanceNanos"))
        val calls = calls(root, original)
        val phases = calls.map { it.phase }.distinct()
        assertTrue(phases.isNotEmpty())
        phases.forEach { phase ->
            assertSame(original, ownedCutField(phase, "signerRotationActivation"))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(phase.signerRotationActivationCleanupProven(original))
            assertTrue(root.jdbc.observations.getValue(phase).lease.completion.quiescent())
            val work = poolTestField<PersistenceTimeBudget>(phase, "signerRotationActivationWork")
            assertSame(original.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
            val local = calls.filter { it.phase === phase }
            assertEquals("activation-authenticate", local.first().step)
            f.core.assertArguments(
                listOf(PgLifecycleDatabaseSettings.CANDIDATE, PgLifecycleDatabaseSettings.CANDIDATE, PgLifecycleDatabaseSettings.DATABASE),
                local.first().arguments,
            )
            if (local.any { it.step in DATA_CONTROLS }) assertOrder(local)
            local.filter { it.step.endsWith("history-lock") || it.step.endsWith("history-read") }.forEach { call ->
                assertEquals(if (call.step.startsWith("activation-head-history")) 2 else 3, root.jdbc.returnedRowCounts.getValue(call))
            }
        }
        arguments(calls, initiallySigned)
        for (field in listOf("retainedHistory", "completedHistory")) {
            (ownedCutField(original, field) as? CatalogSignerRotationActivationObservationV1)?.let { history ->
                val actual = history.historyArguments()
                assertTrue(actual.size in 2..3)
                actual.forEach { assertEquals(33, it.size) }
                f.core.assertArguments(FULL_COLUMNS.map(genesis::get), actual[0].toList())
                f.core.assertArguments(FULL_COLUMNS.map(overlap::get), actual[1].toList())
            }
        }
        root.released()
    }

    private fun assertOrder(calls: List<CatalogSignerRotationSqlCall>) {
        val steps = calls.map { it.step }
        val control = steps[1]
        assertTrue(control in DATA_CONTROLS)
        val lease = control.removeSuffix("-control") + "-lease"
        assertEquals(listOf("activation-authenticate", control, lease, "catalog"), steps.take(4))
        assertTrue(steps[4].endsWith("history-lock"))
        assertEquals("counters", steps[5])
        for (effect in listOf("activation-prepare", "activation-signature", "activation-complete", "activation-project")) {
            if (effect in steps) assertEquals(lease, steps[steps.indexOf(effect) - 1])
        }
        assertTrue(steps.last().endsWith("-lease"))
        assertTrue(steps[steps.lastIndex - 1].endsWith("-control-read"))
    }

    private fun arguments(calls: List<CatalogSignerRotationSqlCall>, initiallySigned: Boolean) {
        val lease = f.delivery.leaseRow()
        val tail = listOf(lease["lease_owner"], lease["lease_token"], lease["lease_expires_at"])
        val b2 = fullBinding(head3 = false) + tail
        val b3 = if (f.exists(CatalogSignerRotationReleaseLeafV1.ENVELOPE)) fullBinding(head3 = true) + tail else emptyList()
        val row = f.row()
        val o17 = CatalogSignerRotationDeliveryAssertions.ROTATION_COLUMNS.map(overlap::get)
        val a11 = ACTIVATION_COLUMNS.map(row::get)
        val unsigned = a11 + listOf(null, null, null)
        val s3 = if (f.exists(CatalogSignerRotationReleaseLeafV1.ENVELOPE)) {
            val envelope = f.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE)
            listOf(f.signatures.single(), envelope, hash(envelope))
        } else {
            listOf(null, null, null)
        }
        val signed = a11 + s3
        val c6 = CatalogSignerRotationDeliveryAssertions.COPY_COLUMNS.map(row::get)
        var seenSignature = initiallySigned
        calls.forEach { call ->
            val a14 = if (seenSignature) signed else unsigned
            val expected = when (call.step) {
                "activation-head-control", "activation-head-control-read", "activation-head-lease" -> b2
                "activation-pending-control", "activation-pending-control-read", "activation-pending-lease" -> b3 + f.token
                "activation-projected-control", "activation-projected-control-read", "activation-projected-lease" -> b3
                "activation-head-history-lock", "activation-head-history-read" -> o17
                "activation-prepared-history-lock", "activation-prepared-history-read" -> o17 + a14
                "activation-pending-history-lock", "activation-pending-history-read",
                "activation-projected-history-lock", "activation-projected-history-read",
                -> o17 + a14 + c6

                "activation-prepare" -> a11
                "activation-signature" -> unsigned + s3
                "activation-complete" -> signed + c6
                "activation-head" -> b2 + listOf(f.token, hash(f.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE)))
                "activation-project" -> signed + c6 + row["completed_at"]
                "activation-clear-pending" -> b3 + f.token
                else -> null
            }
            if (expected != null) f.core.assertArguments(expected, call.arguments)
            if (call.step == "activation-signature") seenSignature = true
        }
    }

    private fun fullBinding(head3: Boolean): List<Any?> = f.delivery.historicalBindingArguments.toMutableList().apply {
        this[5] = if (head3) 3L else 2L
        this[6] = hash(if (head3) f.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE) else f.delivery.envelope)
    }

    companion object {
        private val EFFECTS = setOf(
            "activation-prepare", "activation-signature", "activation-complete", "activation-head", "activation-project", "activation-clear-pending",
        )
        private val DATA_CONTROLS = setOf("activation-head-control", "activation-pending-control", "activation-projected-control")
        private val ACTIVATION_COLUMNS = listOf(
            "operation_token", "catalog_writer_generation", "approval_bytes", "approval_hash", "unsigned_bytes", "unsigned_hash",
            "signer_one_id", "signer_one_algorithm", "object_key", "created_at", "predecessor_hash",
        )
        val FULL_COLUMNS = listOf(
            "operation_token", "operation_type", "data_scope_id", "test_only", "predecessor_generation", "predecessor_hash", "successor_generation",
            "catalog_writer_generation", "approval_bytes", "approval_hash", "canonicalizer", "unsigned_bytes", "unsigned_hash", "signer_policy",
            "signer_one_id", "signer_one_algorithm", "signer_one_signature", "signer_two_id", "signer_two_algorithm", "signer_two_signature",
            "envelope_bytes", "envelope_hash", "object_key", "object_version", "retain_until", "primary_evidence_bytes", "primary_evidence_hash",
            "replica_evidence_bytes", "replica_evidence_hash", "state", "created_at", "completed_at", "projected_at",
        )
    }
}
