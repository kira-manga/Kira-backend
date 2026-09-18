package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.util.UUID

/** First-D leaves on the existing connected IT. All positive signed history originates from real PREPARE/signature with NULL D. */
internal class ComplaintSignedGenesisFirstDCases(private val f: ComplaintDesiredInstallationFixture) {
    fun exactSignedFirstSelectionAndRetry() {
        val genesis = f.firstDGenesis()
        val initial = f.control()
        genesis.stageSigned()
        assertEquals(initial, f.control(), "Actual PREPARE and signature must leave the original NULL-D control unchanged.")
        assertPrivileges()
        f.tls.pools.ordinary.connection.use { connection ->
            val refused = assertThrows<SQLException> {
                connection.createStatement().use {
                    it.executeUpdate("UPDATE public.complaint_journal_control SET desired_configuration_hash = desired_configuration_hash")
                }
            }
            assertEquals("42501", refused.sqlState)
        }
        genesis.released()
        f.stopRuntimeWithoutWaiting()
        val preserved = f.firstDPreservedControl()
        val history = f.historyAndCounters()
        // Pristine bootstrap stays distinct: signed history must not initialize D through the old operation.
        val bootstrap = f.invocation()
        assertThrows<ComplaintDesiredInstallationExceptionV1> { bootstrap.execute() }
        bootstrap.fixtureCleanup()
        assertEquals(initial, f.control())
        assertEquals(history, f.historyAndCounters())
        var beforeCommit = false
        var afterCommit = false
        val selected = f.firstDInvocation { probe ->
            probe.afterSql = { step ->
                if (step === SignedGenesisFirstDSqlStep.REREAD) {
                    val operation = probe.retainedOperation()
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) = probe.preserveAssertions {
                            val early = assertThrows<PersistencePhaseException> { operation.releasedResult() }
                            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                            assertFalse(early.cleanupProven)
                            beforeCommit = true
                        }

                        override fun afterCommit() = probe.preserveAssertions {
                            val early = assertThrows<PersistencePhaseException> { operation.releasedResult() }
                            assertEquals(PersistenceDatabaseOutcome.COMMITTED, early.databaseOutcome)
                            assertFalse(early.cleanupProven)
                            assertEquals(1, probe.coordinator.activeSnapshotOwners())
                            afterCommit = true
                        }
                    })
                }
            }
        }
        assertEquals(ComplaintSignedGenesisFirstDTransitionV1.SELECTED, selected.execute().transition)
        assertTrue(beforeCommit && afterCommit)
        f.assertFirstDSelected(selected)
        assertEquals(firstDSteps(write = true), checkNotNull(selected.probe).steps)
        firstDReleased(checkNotNull(selected.probe).observations.values.single())
        assertEquals(preserved, f.firstDPreservedControl())
        assertEquals(history, f.historyAndCounters(), "No counter settlement, catalog byte, signature or lifecycle change is permitted.")
        val exact = f.control()
        val retry = f.firstDInvocation()
        assertEquals(ComplaintSignedGenesisFirstDTransitionV1.ALREADY_SELECTED, retry.execute().transition)
        assertEquals(firstDSteps(write = false), checkNotNull(retry.probe).steps)
        assertEquals(exact, f.control())
        assertEquals(history, f.historyAndCounters())
        firstDReleased(checkNotNull(retry.probe).observations.values.single())
    }

    private fun assertPrivileges() {
        val operator = ComplaintDesiredInstallationFixture.OPERATOR
        assertEquals(
            true,
            f.observer.queryForObject("SELECT has_table_privilege(?, 'public.complaint_catalog_mutations', 'SELECT')", Boolean::class.java, operator),
        )
        for (right in listOf("INSERT", "UPDATE", "DELETE", "TRUNCATE", "TRIGGER")) {
            assertEquals(
                false,
                f.observer.queryForObject("SELECT has_table_privilege(?, 'public.complaint_catalog_mutations', ?)", Boolean::class.java, operator, right),
            )
        }
        assertEquals(
            false,
            f.observer.queryForObject("SELECT has_any_column_privilege(?, 'public.complaint_catalog_mutations', 'UPDATE')", Boolean::class.java, operator),
        )
        assertEquals(
            true,
            f.observer.queryForObject(
                "SELECT has_column_privilege(?, 'public.complaint_journal_control', 'desired_configuration_hash', 'UPDATE')",
                Boolean::class.java,
                operator,
            ),
        )
        assertEquals(
            false,
            f.observer.queryForObject(
                "SELECT has_column_privilege(?, 'public.complaint_journal_control', 'desired_configuration_hash', 'UPDATE')",
                Boolean::class.java,
                PgLifecycleDatabaseSettings.CANDIDATE,
            ),
        )
        assertEquals(
            false,
            f.observer.queryForObject(
                "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls FROM pg_roles WHERE rolname = ?",
                Boolean::class.java,
                operator,
            ),
        )
    }

    fun unsignedMismatchedAndOversizedHistory() {
        val genesis = f.firstDGenesis()
        genesis.stageSigned()
        f.stopRuntimeWithoutWaiting()
        val initial = f.control()
        val original = f.firstDMutation()
        val changes = listOf(
            "signer_one_signature = NULL, envelope_bytes = NULL, envelope_hash = NULL",
            "envelope_bytes = NULL, envelope_hash = NULL",
            "signer_one_signature = decode(repeat('01', 512), 'hex')",
            "data_scope_id = '${UUID.randomUUID()}'::uuid, test_only = true",
            "catalog_writer_generation = '${UUID.randomUUID()}'::uuid",
            "approval_bytes = decode('5b5d', 'hex'), approval_hash = sha256(decode('5b5d', 'hex'))",
            "unsigned_bytes = decode('7b7d', 'hex'), unsigned_hash = sha256(decode('7b7d', 'hex'))",
            "signer_one_id = 'negative-only-different-signer'",
            "signer_one_algorithm = 'negative-only-different-algorithm'",
            "object_key = 'generations/negative-only-different-key.json'",
            "created_at = created_at + interval '1 second'",
            "unsigned_bytes = decode(repeat('20', ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES + 1}), 'hex'), " +
                "unsigned_hash = sha256(decode(repeat('20', ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES + 1}), 'hex'))",
            "envelope_bytes = decode(repeat('20', ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES + 1}), 'hex'), " +
                "envelope_hash = sha256(decode(repeat('20', ${CatalogGenesisCapacity.MAX_DOCUMENT_BYTES + 1}), 'hex'))",
            "object_version = 'negative-only-copy-version', retain_until = created_at + interval '12 years'",
            "object_version = 'negative-only-copy-version', retain_until = created_at + interval '12 years', " +
                "primary_evidence_bytes = decode('00','hex'), primary_evidence_hash = sha256(decode('00','hex')), " +
                "replica_evidence_bytes = decode('00','hex'), replica_evidence_hash = sha256(decode('00','hex')), " +
                "state = 'COMPLETED', completed_at = created_at",
        )
        for (change in changes) {
            try {
                assertEquals(1, f.observer.update("UPDATE public.complaint_catalog_mutations SET $change WHERE operation_token = ?", genesis.token))
                refusedUnchanged()
            } finally {
                f.firstDRestoreMutation(original)
            }
        }
        val wrongToken = UUID.randomUUID()
        try {
            assertEquals(
                1,
                f.observer.update("UPDATE public.complaint_catalog_mutations SET operation_token = ? WHERE operation_token = ?", wrongToken, genesis.token),
            )
            refusedUnchanged()
        } finally {
            assertEquals(
                1,
                f.observer.update("UPDATE public.complaint_catalog_mutations SET operation_token = ? WHERE operation_token = ?", genesis.token, wrongToken),
            )
        }
        assertEquals(initial, f.control())
        // Even an exact D is not a no-op shortcut around signed/PREPARED checks.
        val selected = f.firstDInvocation()
        assertEquals(ComplaintSignedGenesisFirstDTransitionV1.SELECTED, selected.execute().transition)
        f.observer.update("UPDATE public.complaint_catalog_mutations SET envelope_bytes = NULL, envelope_hash = NULL WHERE operation_token = ?", genesis.token)
        try {
            refusedUnchanged()
        } finally {
            f.firstDRestoreMutation(original)
        }
    }

    fun allHistoryAndNoninitialControlAreRefused() {
        val genesis = f.firstDGenesis()
        genesis.stageSigned()
        f.stopRuntimeWithoutWaiting()
        val original = f.control()
        // Structurally legal hostile head claims only, needed for V14's closed-gate/pending tuple checks.
        // They are NEVER used as a positive projected/head result or as first-D authority.
        val hostileBinding = "desired_configuration_hash = decode(repeat('41', 32), 'hex'), " +
            "database_identity = '${f.document.databaseIdentity}'::uuid, restore_identity = '${f.document.restoreIdentity}'::uuid, " +
            "event_writer_generation = '${f.document.journal.writer.generationId}'::uuid, accepted_catalog_generation = 1, " +
            "accepted_catalog_hash = decode(repeat('42',32),'hex'), trust_bundle_hash = decode(repeat('43',32),'hex'), " +
            "catalog_writer_generation = '${genesis.manifest.catalogWriterGenerationId}'::uuid"
        val changes = listOf(
            "$hostileBinding, maintenance_closed = false", "$hostileBinding, creation_closed = false", "scan_requested = false", "publication_epoch = 2",
            "desired_generation = 2", "desired_configuration_hash = decode(repeat('41', 32), 'hex')",
            "lease_token = 1", "retention_lease_token = 1",
            "database_identity = '${f.document.databaseIdentity}'::uuid, restore_identity = '${f.document.restoreIdentity}'::uuid",
            "event_writer_generation = '${f.document.journal.writer.generationId}'::uuid",
            "$hostileBinding, pending_projection_token = '${genesis.token}'::uuid",
            "seal_state = 'SEAL_PREPARED', seal_epoch = 1, seal_writer_generation = '${f.document.journal.writer.generationId}'::uuid, " +
                "seal_operation_token = '${UUID.randomUUID()}'::uuid, seal_object_key = 'negative-only/epoch-seal.json', " +
                "seal_bytes = decode('00', 'hex'), seal_hash = sha256(decode('00', 'hex'))",
        )
        for (change in changes) {
            try {
                assertEquals(1, f.observer.update("UPDATE public.complaint_journal_control SET $change WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
                refusedUnchanged()
            } finally {
                f.restoreControl(original)
            }
        }
        val extraScope = UUID.randomUUID()
        try {
            assertEquals(
                1,
                f.observer.update(
                    "INSERT INTO public.complaint_journal_control SELECT (jsonb_populate_record(NULL::public.complaint_journal_control, " +
                        "to_jsonb(c) || jsonb_build_object('data_scope_id', ?::text, 'test_only', true))).* " +
                        "FROM public.complaint_journal_control c WHERE data_scope_id = ?",
                    extraScope.toString(),
                    ComplaintDataScope.LIVE.id,
                ),
            )
            refusedUnchanged()
        } finally {
            f.observer.update("DELETE FROM public.complaint_journal_control WHERE data_scope_id = ?", extraScope)
        }
        val extra = UUID.randomUUID()
        try {
            // Only an adversarial obstruction: structurally legal completed/projected extra history, never a success receipt.
            assertEquals(
                1,
                f.observer.update(
                    "INSERT INTO public.complaint_catalog_mutations SELECT (jsonb_populate_record(NULL::public.complaint_catalog_mutations, " +
                        "to_jsonb(m) || jsonb_build_object('operation_token', ?::text, 'operation_type', 'EVENT_WRITER_SUCCESSION', " +
                        "'predecessor_generation', 1, 'predecessor_hash', envelope_hash, 'successor_generation', 2, " +
                        "'object_key', 'generations/negative-only-extra.json', 'state', 'COMPLETED', 'object_version', 'negative-only-version', " +
                        "'retain_until', created_at + interval '12 years', 'completed_at', created_at, 'projected_at', created_at, " +
                        "'primary_evidence_bytes', decode('00','hex'), 'primary_evidence_hash', sha256(decode('00','hex')), " +
                        "'replica_evidence_bytes', decode('00','hex'), 'replica_evidence_hash', sha256(decode('00','hex'))))).* " +
                        "FROM public.complaint_catalog_mutations m WHERE operation_token = ?",
                    extra.toString(),
                    genesis.token,
                ),
            )
            assertEquals(2L, f.observer.queryForObject("SELECT count(*) FROM public.complaint_catalog_mutations", Long::class.java))
            refusedUnchanged()
        } finally {
            f.observer.update("DELETE FROM public.complaint_catalog_mutations WHERE operation_token = ?", extra)
        }
        assertEquals(original, f.control())
    }

    fun epochAndCatalogLocksRefuseBeforeHistory() {
        val genesis = f.firstDGenesis()
        genesis.stageSigned()
        f.stopRuntimeWithoutWaiting()
        for (key in listOf("complaint-journal-epoch", "complaint-catalog-mutation")) {
            val control = f.control()
            val history = f.historyAndCounters()
            f.independentTransaction { _, blocker ->
                blocker.execute("SELECT pg_advisory_xact_lock(hashtextextended('$key', 0))")
                val invocation = f.firstDInvocation()
                try {
                    assertThrows<ComplaintDesiredInstallationExceptionV1> { invocation.execute() }
                    val steps = checkNotNull(invocation.probe).steps
                    val expected = if (key == "complaint-journal-epoch") {
                        emptyList()
                    } else {
                        listOf(
                            SignedGenesisFirstDSqlStep.AUTHENTICATE,
                            SignedGenesisFirstDSqlStep.LIVE,
                            SignedGenesisFirstDSqlStep.CATALOG,
                        )
                    }
                    assertEquals(expected, steps)
                } finally {
                    invocation.fixtureCleanup()
                }
            }
            assertEquals(control, f.control())
            assertEquals(history, f.historyAndCounters())
        }
    }

    private fun refusedUnchanged() {
        val before = f.control()
        val history = f.historyAndCounters()
        val invocation = f.firstDInvocation()
        try {
            val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { invocation.execute() }
            assertEquals(ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED, failure.code)
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty() && failure.stackTrace.isEmpty())
            assertTrue(checkNotNull(invocation.probe).steps.none { it === SignedGenesisFirstDSqlStep.WRITE_D })
            firstDReleased(checkNotNull(invocation.probe).observations.values.single(), PersistenceDatabaseOutcome.ROLLED_BACK)
            assertEquals(before, f.control())
            assertEquals(history, f.historyAndCounters())
        } finally {
            invocation.fixtureCleanup()
        }
    }
}

internal fun firstDSteps(write: Boolean): List<SignedGenesisFirstDSqlStep> = listOf(
    SignedGenesisFirstDSqlStep.AUTHENTICATE,
    SignedGenesisFirstDSqlStep.LIVE,
    SignedGenesisFirstDSqlStep.CATALOG,
    SignedGenesisFirstDSqlStep.HISTORY,
    SignedGenesisFirstDSqlStep.INITIAL,
) + (if (write) listOf(SignedGenesisFirstDSqlStep.WRITE_D) else emptyList()) + listOf(
    SignedGenesisFirstDSqlStep.REREAD,
    SignedGenesisFirstDSqlStep.HISTORY,
    SignedGenesisFirstDSqlStep.INITIAL,
)

internal fun firstDReleased(observed: StepUpPhaseObservation, outcome: PersistenceDatabaseOutcome = PersistenceDatabaseOutcome.COMMITTED) {
    assertEquals(outcome, observed.lease.completion.databaseOutcome())
    assertTrue(observed.lease.completion.logicallyReleased() && observed.lease.completion.quiescent())
    assertFalse(observed.phase.quarantined())
    requireConnectionFree()
}

internal fun ComplaintDesiredInstallationFixture.firstDPreservedControl(): String = checkNotNull(
    observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['desired_configuration_hash','updated_at'])::text FROM public.complaint_journal_control c WHERE data_scope_id = ?",
        String::class.java,
        ComplaintDataScope.LIVE.id,
    ),
)

internal fun ComplaintDesiredInstallationFixture.assertFirstDSelected(invocation: SignedGenesisFirstDInvocation) {
    assertArrayEquals(
        checkNotNull(invocation.target).configurationHashBytes(),
        observer.queryForObject(
            "SELECT desired_configuration_hash FROM public.complaint_journal_control WHERE data_scope_id = ?",
            ByteArray::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )
    assertTrue(invocation.cleanupVerified)
    requireConnectionFree()
}

internal fun ComplaintDesiredInstallationFixture.firstDMutation(): String = checkNotNull(
    observer.queryForObject(
        "SELECT to_jsonb(m)::text FROM public.complaint_catalog_mutations m WHERE operation_token = ?",
        String::class.java,
        UUID.fromString(VersionBoundCatalogReadbackTestFixture.envelope().manifest.operationToken),
    ),
)

internal fun ComplaintDesiredInstallationFixture.firstDRestoreMutation(original: String) = independentTransaction { connection, jdbc ->
    jdbc.update(
        "DELETE FROM public.complaint_catalog_mutations WHERE operation_token = ?",
        UUID.fromString(VersionBoundCatalogReadbackTestFixture.envelope().manifest.operationToken),
    )
    assertEquals(
        1,
        jdbc.update(
            "INSERT INTO public.complaint_catalog_mutations SELECT (jsonb_populate_record(NULL::public.complaint_catalog_mutations, ?::jsonb)).*",
            original,
        ),
    )
    connection.commit()
}
