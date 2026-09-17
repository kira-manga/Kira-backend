package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Called by the existing connected IT. Every positive projection comes from the real bootstrap and signed G1/SDK path. */
internal class ComplaintDesiredInstallationRefusalCases(private val f: ComplaintDesiredInstallationFixture) {
    fun pendingMutationProjectionAndSlots() = f.genuineGenesis { genesis, refresh ->
        refresh.catalogFor(genesis.process)
        f.stopRuntimeWithoutWaiting()
        val original = snapshot(genesis.token)

        for (prepared in listOf(true, false)) {
            restored(original) {
                adversarialOpenGates()
                val pending = if (prepared) "state = 'PREPARED', completed_at = NULL, projected_at = NULL" else "projected_at = NULL"
                // Withdraw only the genuine row's completion/projection state; never fabricate a successful mutation.
                assertEquals(1, f.observer.update("UPDATE public.complaint_catalog_mutations SET $pending WHERE operation_token = ?", genesis.token))
                supersedeRefusedAfterClosure(checkPending = true)
            }
        }

        restored(original) {
            // A token independently obstructs supersession even though its genuine history row is already projected.
            // V14 requires closed gates while this token is populated; scan=false is an adversarial starting bit, not readiness.
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE public.complaint_journal_control SET pending_projection_token = ?, scan_requested = false WHERE data_scope_id = ?",
                    genesis.token,
                    ComplaintDataScope.LIVE.id,
                ),
            )
            supersedeRefusedAfterClosure(checkPending = false)
        }

        for (captured in listOf(false, true)) {
            restored(original) {
                adversarialOpenGates()
                adversarialRotationSlot(captured)
                supersedeRefusedAfterClosure(checkPending = false)
            }
        }

        restored(original) {
            adversarialOpenGates()
            val bytes = "negative-fixture-only-not-a-canonical-seal".toByteArray(Charsets.UTF_8)
            // Structurally valid legacy V14 PREPARED obstruction only. Null V19 linkage authenticates no seal or receipt.
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE public.complaint_journal_control SET seal_state = 'SEAL_PREPARED', seal_epoch = publication_epoch, " +
                        "seal_writer_generation = event_writer_generation, seal_operation_token = ?, " +
                        "seal_object_key = 'negative-fixture-only/prepared-seal.json', seal_bytes = ?::bytea, seal_hash = sha256(?::bytea) " +
                        "WHERE data_scope_id = ?",
                    UUID.randomUUID(),
                    bytes,
                    bytes,
                    ComplaintDataScope.LIVE.id,
                ),
            )
            supersedeRefusedAfterClosure(checkPending = false)
        }
    }

    fun maximaAndProjectedNullD() = f.genuineGenesis { genesis, refresh ->
        refresh.catalogFor(genesis.process)
        f.stopRuntimeWithoutWaiting()
        val original = snapshot(genesis.token)

        restored(original) {
            adversarialOpenGates()
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE public.complaint_journal_control SET lease_token = ? WHERE data_scope_id = ?",
                    Long.MAX_VALUE,
                    ComplaintDataScope.LIVE.id,
                ),
            )
            supersedeRefusedAfterClosure(checkPending = true)
            assertEquals(
                Long.MAX_VALUE,
                f.observer.queryForObject(
                    "SELECT lease_token FROM public.complaint_journal_control WHERE data_scope_id = ?",
                    Long::class.java,
                    ComplaintDataScope.LIVE.id,
                ),
            )
        }

        restored(original) {
            // This maximum is hostile stored state, not an installed generation manufactured by the fixture.
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE public.complaint_journal_control SET desired_generation = ? WHERE data_scope_id = ?",
                    Long.MAX_VALUE,
                    ComplaintDataScope.LIVE.id,
                ),
            )
            generationOverflowBeforeAcquisition()
        }

        restored(original) {
            val projectedIdentity = f.unrelatedControl()
            // The genuine accepted head/identity/history stays intact. Removing D models an adversarial legacy database, not a reset.
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE public.complaint_journal_control SET desired_configuration_hash = NULL WHERE data_scope_id = ?",
                    ComplaintDataScope.LIVE.id,
                ),
            )
            assertEquals(projectedIdentity, f.unrelatedControl())
            refusedBeforeAnyWrite(f.document, null, PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP)
            refusedBeforeAnyWrite(f.document.copy(desiredGeneration = 2), 1, PersistencePhasePath.COMPLAINT_DESIRED_CLOSE)
        }
    }

    private fun supersedeRefusedAfterClosure(checkPending: Boolean) {
        val unchanged = outsideClosure()
        val history = f.historyAndCounters()
        var witnessedClosure = false
        val invocation = f.invocation(f.document.copy(desiredGeneration = 2)) { probe ->
            probe.beforeSql = { path, step ->
                if (path === PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE && step === DesiredInstallationSqlStep.AUTHENTICATE) {
                    assertTrue(witnessedClosure)
                    val phases = probe.observations.values.toList()
                    assertEquals(2, phases.size)
                    completed(phases.first(), PersistenceDatabaseOutcome.COMMITTED)
                    assertNotSame(phases.first().phase, phases.last().phase)
                    assertNotSame(phases.first().lease, phases.last().lease)
                    assertNotEquals(phases.first().identity.second, phases.last().identity.second)
                }
            }
        }
        invocation.betweenPhases = {
            requireConnectionFree()
            val probe = checkNotNull(invocation.probe)
            completed(probe.observations.values.single(), PersistenceDatabaseOutcome.COMMITTED)
            assertEquals(0, probe.coordinator.activeSnapshotOwners())
            assertEquals(false, witnessedClosure)
            assertClosed()
            assertEquals(unchanged, outsideClosure())
            assertEquals(history, f.historyAndCounters())
            witnessedClosure = true
        }
        try {
            refused(ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED) { invocation.execute(1) }
            val probe = checkNotNull(invocation.probe)
            assertTrue(witnessedClosure, "The actual first commit and release must precede the distinct refused second phase.")
            val closePath = PersistencePhasePath.COMPLAINT_DESIRED_CLOSE
            val supersedePath = PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE
            val expected = listOf(
                closePath to DesiredInstallationSqlStep.AUTHENTICATE,
                closePath to DesiredInstallationSqlStep.LOCK_CONTROL,
                closePath to DesiredInstallationSqlStep.WRITE_CONTROL,
                closePath to DesiredInstallationSqlStep.READ_CONTROL,
                supersedePath to DesiredInstallationSqlStep.AUTHENTICATE,
                supersedePath to DesiredInstallationSqlStep.LOCK_CONTROL,
            ) + if (checkPending) listOf(supersedePath to DesiredInstallationSqlStep.CHECK_PENDING) else emptyList()
            assertEquals(expected, probe.steps)
            val phases = probe.observations.values.toList()
            assertEquals(2, phases.size)
            completed(phases.first(), PersistenceDatabaseOutcome.COMMITTED)
            completed(phases.last(), PersistenceDatabaseOutcome.ROLLED_BACK)
            assertEquals(0, probe.coordinator.activeSnapshotOwners())
            requireConnectionFree()
            assertClosed()
            assertEquals(unchanged, outsideClosure(), "Only gates, scan request and updated_at may change after a refused second phase.")
            assertEquals(history, f.historyAndCounters())
        } finally {
            invocation.fixtureCleanup()
        }
    }

    private fun generationOverflowBeforeAcquisition() {
        val before = f.control()
        val history = f.historyAndCounters()
        val invocation = f.invocation(f.document.copy(desiredGeneration = Long.MAX_VALUE))
        try {
            refused(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED) { invocation.execute(Long.MAX_VALUE) }
            assertTrue(invocation.http.requests.isEmpty())
            assertEquals(0, invocation.http.createdClients)
            assertEquals(0, invocation.http.closedClients)
            assertNull(invocation.target)
            assertNull(invocation.probe)
            assertEquals(before, f.control())
            assertEquals(history, f.historyAndCounters())
            requireConnectionFree()
        } finally {
            invocation.fixtureCleanup()
        }
    }

    private fun refusedBeforeAnyWrite(document: ComplaintDesiredDeploymentDocumentV1, expectedGeneration: Long?, path: PersistencePhasePath) {
        val before = f.control()
        val history = f.historyAndCounters()
        val invocation = f.invocation(document)
        try {
            refused(ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED) { invocation.execute(expectedGeneration) }
            val probe = checkNotNull(invocation.probe)
            assertEquals(listOf(path to DesiredInstallationSqlStep.AUTHENTICATE, path to DesiredInstallationSqlStep.LOCK_CONTROL), probe.steps)
            completed(probe.observations.values.single(), PersistenceDatabaseOutcome.ROLLED_BACK)
            assertEquals(0, probe.coordinator.activeSnapshotOwners())
            assertTrue(invocation.http.requests.isNotEmpty())
            assertEquals(before, f.control(), "A projected NULL-D database cannot become a bootstrap or supersession success.")
            assertEquals(history, f.historyAndCounters())
            requireConnectionFree()
        } finally {
            invocation.fixtureCleanup()
        }
    }

    private fun adversarialRotationSlot(captured: Boolean) {
        // Structurally valid hostile slot, copied from the genuine old B solely to test refusal. No request/capture receipt is constructed.
        assertEquals(
            1,
            f.observer.update(
                "UPDATE public.complaint_journal_control SET rotation_sequence = 1, rotation_id = ?, rotation_state = 'REQUESTED', " +
                    "rotation_epoch_before = publication_epoch, rotation_implementation_schema = implementation_schema, " +
                    "rotation_desired_generation = desired_generation, rotation_desired_configuration_hash = desired_configuration_hash, " +
                    "rotation_database_identity = database_identity, rotation_restore_identity = restore_identity, " +
                    "rotation_event_writer_generation = event_writer_generation, rotation_accepted_catalog_generation = accepted_catalog_generation, " +
                    "rotation_accepted_catalog_hash = accepted_catalog_hash, rotation_trust_bundle_hash = trust_bundle_hash, " +
                    "rotation_catalog_writer_generation = catalog_writer_generation, rotation_request_owner = ?, rotation_request_token = 1, " +
                    "rotation_requested_at = clock_timestamp(), scan_requested = true WHERE data_scope_id = ?",
                UUID.randomUUID(),
                UUID.randomUUID(),
                ComplaintDataScope.LIVE.id,
            ),
        )
        if (captured) {
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE public.complaint_journal_control SET rotation_state = 'CAPTURED', rotation_capture_owner = rotation_request_owner, " +
                        "rotation_capture_token = 1, rotation_captured_at = clock_timestamp(), rotation_epoch_after = publication_epoch + 1, " +
                        "publication_epoch = publication_epoch + 1, scan_requested = false WHERE data_scope_id = ?",
                    ComplaintDataScope.LIVE.id,
                ),
            )
        }
    }

    private fun adversarialOpenGates() {
        // Deliberately unqualified database bits, not a readiness/activation result; the real first installer phase must close them.
        assertEquals(
            1,
            f.observer.update(
                "UPDATE public.complaint_journal_control SET maintenance_closed = false, creation_closed = false, " +
                    "scan_requested = false WHERE data_scope_id = ?",
                ComplaintDataScope.LIVE.id,
            ),
        )
    }

    private fun assertClosed() = assertEquals(
        true,
        f.observer.queryForObject(
            "SELECT maintenance_closed AND creation_closed AND scan_requested FROM public.complaint_journal_control WHERE data_scope_id = ?",
            Boolean::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    private fun outsideClosure(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(c) - ARRAY['maintenance_closed','creation_closed','scan_requested','updated_at'])::text " +
                "FROM public.complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    private fun snapshot(token: UUID): ProjectedState {
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT state = 'COMPLETED' AND completed_at IS NOT NULL AND projected_at IS NOT NULL " +
                    "FROM public.complaint_catalog_mutations WHERE operation_token = ?",
                Boolean::class.java,
                token,
            ),
        )
        val mutation = checkNotNull(
            f.observer.queryForObject(
                "SELECT to_jsonb(m)::text FROM public.complaint_catalog_mutations m WHERE operation_token = ?",
                String::class.java,
                token,
            ),
        )
        return ProjectedState(f.control(), mutation, f.historyAndCounters())
    }

    private fun restored(original: ProjectedState, work: () -> Unit) {
        try {
            work()
        } finally {
            requireConnectionFree()
            f.restoreControl(original.control)
            assertEquals(
                1,
                f.observer.update(
                    "WITH original AS (SELECT (jsonb_populate_record(NULL::public.complaint_catalog_mutations, ?::jsonb)).*) " +
                        "UPDATE public.complaint_catalog_mutations m SET state = original.state, completed_at = original.completed_at, " +
                        "projected_at = original.projected_at FROM original WHERE m.operation_token = original.operation_token",
                    original.mutation,
                ),
            )
            assertEquals(original.control, f.control())
            assertEquals(original.history, f.historyAndCounters())
        }
    }

    private fun completed(observed: StepUpPhaseObservation, outcome: PersistenceDatabaseOutcome) {
        assertEquals(outcome, observed.lease.completion.databaseOutcome())
        assertTrue(observed.lease.completion.logicallyReleased())
        assertTrue(observed.lease.completion.quiescent())
    }

    private fun refused(code: ComplaintDesiredInstallationFailureV1, work: () -> Unit) {
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { work() }
        assertEquals(code, failure.code)
        assertEquals("Desired configuration installation refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(failure.stackTrace.isEmpty())
    }

    /** Test-only exact cleanup bytes, never supplied to the command as target D, old-B authority or a projection result. */
    private class ProjectedState(val control: String, val mutation: String, val history: List<String>)
}
