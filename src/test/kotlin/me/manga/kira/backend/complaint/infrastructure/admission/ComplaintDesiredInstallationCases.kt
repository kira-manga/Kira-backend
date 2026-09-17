package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.CoordinatorLeaseTestFixture
import me.manga.kira.backend.complaint.catalog.CurrentAcceptedCatalogRefreshHttpFixture
import me.manga.kira.backend.complaint.catalog.ProcessBoundCatalogGenesisFixture
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.catalog.assertReceipt
import me.manga.kira.backend.complaint.catalog.refused
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseTransitionV1
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Existing TLS/controller/root harness only. Every selected D and successful transition comes from the actual CLI owner. */
// Keep causal same-owner phase and cleanup cases together rather than obscure their boundaries to satisfy a source-size heuristic.
@Suppress("LargeClass")
internal class ComplaintDesiredInstallationCases(private val f: ComplaintDesiredInstallationFixture) {
    fun authenticationBootstrapAndReadOnlyRetry() {
        runtimeCannotInstallDesired()
        f.stopRuntimeWithoutWaiting()
        wrongOperatorPassword()
        val before = bootstrapPreserved()
        val history = f.historyAndCounters()
        var beforeCommit = false
        var afterCommit = false
        val installed = f.invocation { probe ->
            probe.afterSql = { _, step ->
                if (step == DesiredInstallationSqlStep.READ_CONTROL) {
                    val original = probe.retainedOperation()
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) = probe.preserveAssertions {
                            val early = assertThrows<PersistencePhaseException> { original.releasedResult() }
                            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                            assertFalse(early.cleanupProven)
                            beforeCommit = true
                        }

                        override fun afterCommit() = probe.preserveAssertions {
                            val retained = assertThrows<PersistencePhaseException> { original.releasedResult() }
                            assertEquals(PersistenceDatabaseOutcome.COMMITTED, retained.databaseOutcome)
                            assertFalse(retained.cleanupProven)
                            assertEquals(1, probe.coordinator.activeSnapshotOwners())
                            afterCommit = true
                        }
                    })
                }
            }
        }
        assertEquals(ComplaintDesiredInstallationTransitionV1.BOOTSTRAPPED, installed.execute().transition)
        assertTrue(beforeCommit && afterCommit)
        assertSelected(installed, 1)
        assertEquals(before, bootstrapPreserved(), "Bootstrap changes only its computed D and server updated_at.")
        assertEquals(history, f.historyAndCounters())
        released(checkNotNull(installed.probe).observations.values.single())
        val exact = f.control()
        val retry = f.invocation()
        assertEquals(ComplaintDesiredInstallationTransitionV1.ALREADY_SELECTED, retry.execute().transition)
        assertNoWrite(retry)
        assertEquals(exact, f.control(), "Identical bootstrap preserves every control field, including updated_at.")
        val preGenesis = f.invocation(f.document.copy(desiredGeneration = 2))
        refused(preGenesis, 1)
        assertNoWrite(preGenesis)
        assertEquals(exact, f.control(), "A pre-G1 database cannot use supersession to evade the initial-reader bootstrap profile.")
    }

    private fun runtimeCannotInstallDesired() {
        val operator = ComplaintDesiredInstallationFixture.OPERATOR
        val role = f.observer.queryForMap(
            "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, " +
                "EXISTS (SELECT 1 FROM pg_auth_members WHERE member = r.oid) AS membership, " +
                "(SELECT rolpassword LIKE 'SCRAM-SHA-256${'$'}%' FROM pg_authid WHERE oid = r.oid) AS scram " +
                "FROM pg_roles r WHERE rolname = ?",
            operator,
        )
        assertEquals(true, role["rolcanlogin"])
        assertEquals(true, role["scram"])
        for (name in listOf("rolsuper", "rolcreatedb", "rolcreaterole", "rolbypassrls", "membership")) assertEquals(false, role[name])
        for (column in listOf("desired_configuration_hash", "desired_generation")) {
            assertEquals(
                false,
                f.observer.queryForObject(
                    "SELECT has_column_privilege(?, 'public.complaint_journal_control', ?, 'UPDATE')",
                    Boolean::class.java,
                    PgLifecycleDatabaseSettings.CANDIDATE,
                    column,
                ),
            )
            assertEquals(
                true,
                f.observer.queryForObject(
                    "SELECT has_column_privilege(?, 'public.complaint_journal_control', ?, 'UPDATE')",
                    Boolean::class.java,
                    operator,
                    column,
                ),
            )
        }
        f.tls.start()
        f.tls.pools.ordinary.connection.use { connection ->
            f.tls.tlsPid(connection)
            for (column in listOf("desired_configuration_hash", "desired_generation")) {
                val denied = assertThrows<SQLException> {
                    connection.createStatement().use { it.executeUpdate("UPDATE public.complaint_journal_control SET $column = $column") }
                }
                assertEquals("42501", denied.sqlState, "The authenticated runtime principal has no desired-column UPDATE privilege.")
            }
        }
        requireConnectionFree()
    }

    private fun wrongOperatorPassword() {
        val before = f.control()
        val wrong = f.invocation()
        val response = wrong.http.respond
        wrong.http.respond = { request ->
            if (request.fields()["SecretId"] == wrong.inputs.operatorPassword.version.resourceArn) {
                AwsSecretVersionFixture.reply(wrong.inputs.operatorPassword.version, "synthetic-wrong-operator-password".toByteArray())
            } else {
                response(request)
            }
        }
        assertThrows<ComplaintDesiredInstallationExceptionV1> { wrong.execute() }
        assertTrue(checkNotNull(wrong.probe).observations.isEmpty(), "Failed real authentication must not reach any desired SQL phase.")
        assertEquals(before, f.control())
        wrong.fixtureCleanup()
    }

    fun genuineGenesisKeepsScanRequestedAndGuardsInitialState() = f.genuineGenesis(beforeStage = ::genesisGuards) { genesis, refresh ->
        refresh.catalogFor(genesis.process) // The genuine SDK/projection result is bound to this exact runtime owner, not supplied evidence.
        val row = control()
        assertEquals(1L, row["accepted_catalog_generation"])
        assertEquals(UUID.fromString(f.document.databaseIdentity), row["database_identity"])
        assertEquals(UUID.fromString(f.document.restoreIdentity), row["restore_identity"])
        assertEquals(UUID.fromString(f.document.journal.writer.generationId), row["event_writer_generation"])
        assertNull(row["pending_projection_token"])
        assertEquals(true, row["scan_requested"])
        assertEquals(true, row["maintenance_closed"])
        assertEquals(true, row["creation_closed"])
        f.stopRuntimeWithoutWaiting()
        val before = f.control()
        val retry = f.invocation()
        assertEquals(ComplaintDesiredInstallationTransitionV1.ALREADY_SELECTED, retry.execute().transition)
        assertNoWrite(retry)
        assertEquals(before, f.control(), "A completed G1 never needs bootstrap to clear scan or to rewrite its head/identity.")
    }

    private fun genesisGuards(genesis: ProcessBoundCatalogGenesisFixture) {
        val original = f.control()
        val histories = f.historyAndCounters()
        val changes = listOf(
            "publication_epoch = 2",
            "database_identity = '${f.document.databaseIdentity}'::uuid, restore_identity = '${f.document.restoreIdentity}'::uuid",
            "event_writer_generation = '${f.document.journal.writer.generationId}'::uuid",
        )
        for (change in changes) {
            try {
                assertEquals(1, f.observer.update("UPDATE public.complaint_journal_control SET $change WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
                val invalid = f.control()
                genesis.jdbc.steps.clear()
                val refused = assertThrows<PersistencePhaseException> { prepareGenesis(genesis) }
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, refused.databaseOutcome)
                assertTrue(refused.cleanupProven)
                assertEquals(listOf("control"), genesis.jdbc.steps)
                assertEquals(invalid, f.control())
                assertEquals(histories, f.historyAndCounters())
            } finally {
                f.restoreControl(original)
            }
        }
        // Compatibility-only adversarial branch: scan=false historically permits noninitial identities at PREPARE.
        // Intentionally roll back AFTER the real INSERT so this creates no installed/projected/signed-success state.
        var inserted = false
        try {
            f.observer.update(
                "UPDATE public.complaint_journal_control SET scan_requested = false, database_identity = ?::uuid, " +
                    "restore_identity = ?::uuid, event_writer_generation = ?::uuid WHERE data_scope_id = ?",
                f.document.databaseIdentity,
                f.document.restoreIdentity,
                f.document.journal.writer.generationId,
                ComplaintDataScope.LIVE.id,
            )
            genesis.jdbc.afterSql = { step ->
                if (step == "insert") {
                    inserted = true
                    error("Synthetic compatibility preparation rollback.")
                }
            }
            val refused = assertThrows<PersistencePhaseException> { prepareGenesis(genesis) }
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, refused.databaseOutcome)
            assertTrue(refused.cleanupProven && inserted)
            assertEquals(histories, f.historyAndCounters())
        } finally {
            genesis.jdbc.afterSql = null
            f.restoreControl(original)
        }
        f.assertScanRequested()
        genesis.released()
    }

    private fun prepareGenesis(genesis: ProcessBoundCatalogGenesisFixture) = genesis.executor.prepareGenesis(
        VersionBoundCatalogReadbackTestFixture.manifestBytes(),
        genesis.initial,
        genesis.current,
        genesis.policy.chain,
        genesis.process.consumers.capacityPolicy.digestBytes(),
    )

    fun supersessionFencesLiveCampaignAndRetryPreservesLaterLease() = f.genuineGenesis { genesis, refresh ->
        val old = CoordinatorLeaseTestFixture(genesis, refresh)
        val acquired = old.phases.acquire(old.binding)
        old.assertReceipt(acquired.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
        val leased = old.row()
        val oldBinding = old.unchangedOutsideLease()
        var liveCampaign = acquired.campaign
        var fencedLease = leased
        val outside = f.unrelatedControl()
        val history = f.historyAndCounters()
        val selected = f.document.copy(desiredGeneration = 2)
        var staleChecked = false
        val installed = OwnedCallerTestScope().use { callers ->
            val invocation = f.invocation(selected, stopRuntimeAtClose = true) { probe ->
                probe.afterSql = { path, step ->
                    if (path == PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE && step == DesiredInstallationSqlStep.READ_CONTROL) {
                        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit() = probe.preserveAssertions {
                                assertEquals(PersistenceDatabaseOutcome.COMMITTED, probe.phase.databaseOutcome())
                                assertEquals(1, probe.coordinator.activeSnapshotOwners())
                                // The operator still owns its actual Spring holder. A separate registered caller exercises
                                // the live old runtime against the COMMITTED new B, then stops that peer before Timer cleanup.
                                callers.launch {
                                    old.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { old.phases.renew(liveCampaign) }
                                    old.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { old.phases.relinquish(liveCampaign) }
                                    old.refused(PersistenceDatabaseOutcome.ROLLED_BACK) { old.phases.acquire(old.binding) }
                                    f.stopRuntimeWithoutWaiting()
                                    true
                                }.value()
                                staleChecked = true
                            }
                        })
                    }
                }
            }
            invocation.betweenPhases = {
                val observed = checkNotNull(invocation.probe)
                released(observed.observations.values.single())
                assertEquals(0, observed.coordinator.activeSnapshotOwners())
                assertEquals(leased, old.row().copy(updatedAt = leased.updatedAt), "Gate closure preserves the current owner/token/expiry.")
                // A real successor raises CURRENT high-water after phase1 while the expected old D/head stay unchanged.
                old.assertReceipt(old.phases.relinquish(acquired.campaign), CatalogCoordinatorLeaseTransitionV1.RELINQUISHED)
                val successor = old.phases.acquire(old.binding)
                liveCampaign = successor.campaign
                old.assertReceipt(successor.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
                fencedLease = old.row()
                assertEquals(leased.token + 1, fencedLease.token)
                assertNotEquals(leased.owner, fencedLease.owner)
                assertEquals(oldBinding, old.unchangedOutsideLease(), "A later campaign changes only lease fields, not the expected old D/head.")
            }
            try {
                assertEquals(ComplaintDesiredInstallationTransitionV1.SUPERSEDED, invocation.execute(1).transition)
                invocation
            } finally {
                acquired.campaign.close()
                liveCampaign.close()
            }
        }
        assertTrue(staleChecked)
        assertSelected(installed, 2)
        val row = control()
        assertEquals(leased.token + 1, fencedLease.token)
        assertEquals(fencedLease.token + 1, row["lease_token"], "Phase2 fences the current successor token, never the stale phase1 high-water.")
        assertNull(row["lease_owner"])
        assertNull(row["lease_expires_at"])
        assertTrue(
            checkNotNull(fencedLease.expiresAt).isAfter((row["updated_at"] as Timestamp).toInstant()),
            "The fenced real successor campaign was still unexpired at the DB update.",
        )
        assertEquals(outside, f.unrelatedControl())
        assertEquals(history, f.historyAndCounters())
        val phases = checkNotNull(installed.probe).observations.values.toList()
        assertEquals(2, phases.size)
        phases.forEach { released(it) }
        assertNotSamePhaseOrTransaction(phases)
        withLaterLease(genesis, checkNotNull(installed.target), selected) { peer, later ->
            val current = later.phases.acquire(later.binding)
            later.assertReceipt(current.receipt, CatalogCoordinatorLeaseTransitionV1.ACQUIRED)
            val exact = f.control()
            val retry = DesiredInstallationInvocation(
                ComplaintDesiredDeploymentInputsV1.fromDecoded(selected),
                beforeClose = {
                    requireConnectionFree()
                    peer.owner.requestShutdown()
                    peer.pools.close()
                },
            ).also(f.invocations::add)
            try {
                assertEquals(ComplaintDesiredInstallationTransitionV1.ALREADY_SELECTED, retry.execute(1).transition)
                assertNoWrite(retry)
                assertEquals(1, checkNotNull(retry.probe).observations.size)
                assertEquals(exact, f.control(), "Exact retry may not increment the token or clear a genuinely acquired later lease.")
            } finally {
                current.campaign.close()
                retry.fixtureCleanup()
            }
        }
        val exact = f.control()
        val different = selected.copy(admission = selected.admission.copy(concurrentLimit = selected.admission.concurrentLimit + 1))
        val stale = f.invocation(different)
        refused(stale, 1)
        assertNoWrite(stale)
        assertEquals(exact, f.control(), "A different target cannot borrow an old expected generation or clear the later lease.")
    }

    private fun withLaterLease(
        genesis: ProcessBoundCatalogGenesisFixture,
        target: VersionBoundComplaintProcessConfiguration,
        selected: ComplaintDesiredDeploymentDocumentV1,
        work: (VersionBoundPersistenceConnectedFixture, CoordinatorLeaseTestFixture) -> Unit,
    ) {
        val peer = VersionBoundPersistenceConnectedFixture(f.tls.database)
        try {
            peer.bind()
            peer.start()
            assertEquals(PersistenceLifecycleObservation.READY, peer.pools.catalogCoordinator.prepare())
            val process = VersionBoundComplaintProcessConfiguration.fromRetained(
                target.consumers,
                peer.pools,
                selected.implementationSchema,
                selected.desiredGeneration,
                UUID.fromString(selected.databaseIdentity),
                UUID.fromString(selected.restoreIdentity),
                target.catalogReadback,
            )
            assertArrayEquals(target.configurationHashBytes(), process.configurationHashBytes())
            val wire = CurrentAcceptedCatalogRefreshHttpFixture(genesis)
            val refreshed = wire.owner(process).use { it.refresh() }
            wire.assertFullReadback()
            val lease = CoordinatorLeaseTestFixture(genesis, refreshed, process)
            try {
                work(peer, lease)
            } finally {
                lease.released()
            }
        } finally {
            peer.closeWith(f.tls) // Both actual runtime roots, not a hidden peer excluded from the shared Timer/session proof.
        }
    }

    fun twoAuthenticatedContenders(identical: Boolean) = f.genuineGenesis { _, _ ->
        f.stopRuntimeWithoutWaiting()
        val outside = f.unrelatedControl()
        val history = f.historyAndCounters()
        val token = control().getValue("lease_token")
        val firstInput = f.document.copy(desiredGeneration = 2)
        val secondInput = if (identical) {
            firstInput
        } else {
            firstInput.copy(
                admission = firstInput.admission.copy(
                    concurrentLimit =
                    firstInput.admission.concurrentLimit + 1,
                ),
            )
        }
        val firstRef = AtomicReference<DesiredInstallationInvocation?>()
        val secondRef = AtomicReference<DesiredInstallationInvocation?>()
        OwnedCallerTestScope().use { callers ->
            val between = callers.gate()
            val first = callers.launch {
                val invocation = f.invocation(firstInput).also(firstRef::set)
                invocation.betweenPhases = {
                    val probe = checkNotNull(invocation.probe)
                    released(probe.observations.values.single())
                    assertEquals(0, probe.coordinator.activeSnapshotOwners())
                    between.hold() // No owned phase or Spring holder exists while the contender acquires its own root.
                }
                try {
                    runCatching { invocation.execute(1) }
                } finally {
                    invocation.fixtureCleanup() // Original caller owns even a failed command's eventual cleanup.
                }
            }
            between.awaitEntered()
            val second = callers.launch {
                val invocation = f.invocation(secondInput) { probe ->
                    probe.afterSql = { path, step ->
                        if (path == PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE && step == DesiredInstallationSqlStep.READ_CONTROL) {
                            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                                override fun afterCommit() = probe.preserveAssertions {
                                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, probe.phase.databaseOutcome())
                                    between.release()
                                }
                            })
                        }
                    }
                }.also(secondRef::set)
                try {
                    invocation.execute(1)
                } finally {
                    between.release()
                    invocation.fixtureCleanup()
                }
            }
            val winning = second.value()
            assertEquals(ComplaintDesiredInstallationTransitionV1.SUPERSEDED, winning.transition)
            val resumed = first.value()
            if (identical) {
                assertEquals(ComplaintDesiredInstallationTransitionV1.ALREADY_SELECTED, resumed.getOrThrow().transition)
            } else {
                val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { resumed.getOrThrow() }
                assertEquals(ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED, failure.code)
            }
        }
        val first = checkNotNull(firstRef.get())
        val second = checkNotNull(secondRef.get())
        assertSelected(second, 2)
        val firstProbe = checkNotNull(first.probe)
        val secondProbe = checkNotNull(second.probe)
        assertNotEquals(firstProbe.observations.values.first().identity.first, secondProbe.observations.values.first().identity.first)
        assertEquals(2, firstProbe.observations.size)
        assertEquals(2, secondProbe.observations.size)
        assertTrue(
            firstProbe.steps.none {
                it.first == PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE && it.second == DesiredInstallationSqlStep.WRITE_CONTROL
            },
        )
        assertEquals((token as Long) + 1, control()["lease_token"], "Even a waiting identical contender cannot fence twice.")
        assertEquals(outside, f.unrelatedControl())
        assertEquals(history, f.historyAndCounters())
    }

    private fun assertNotSamePhaseOrTransaction(phases: List<StepUpPhaseObservation>) {
        assertTrue(phases[0].phase !== phases[1].phase)
        assertTrue(phases[0].lease !== phases[1].lease)
        assertEquals(phases[0].identity.first, phases[1].identity.first, "The same single-slot operator pool supplies both distinct holders.")
        assertNotEquals(phases[0].identity.second, phases[1].identity.second)
    }

    fun readCommittedSeesPendingHistoryAfterActualControlWait() {
        var prepared: String? = null
        f.genuineGenesis(afterSigned = { prepared = mutationRow(it.token) }) { genesis, _ ->
            f.stopRuntimeWithoutWaiting()
            val completed = mutationRow(genesis.token)
            val oldHash = (control().getValue("desired_configuration_hash") as ByteArray).copyOf()
            val oldToken = control().getValue("lease_token")
            val reference = AtomicReference<DesiredInstallationInvocation?>()
            var roleDefaults = 0
            var closedControl: String? = null
            f.observer.execute("ALTER ROLE ${ComplaintDesiredInstallationFixture.OPERATOR} SET default_transaction_isolation = 'repeatable read'")
            try {
                OwnedCallerTestScope().use { callers ->
                    val between = callers.gate()
                    val lockEntered = callers.gate()
                    f.independentTransaction { blocker, observer ->
                        val worker = callers.launch {
                            val invocation = f.invocation(f.document.copy(desiredGeneration = 2)) { probe ->
                                probe.beforeSql = { path, step ->
                                    if (step == DesiredInstallationSqlStep.AUTHENTICATE) {
                                        val holder = TransactionSynchronizationManager.getResource(probe.coordinator.dataSource) as ConnectionHolder
                                        holder.connection.createStatement().use { statement ->
                                            statement.executeQuery("SHOW default_transaction_isolation").use { row ->
                                                assertTrue(row.next())
                                                assertEquals("repeatable read", row.getString(1))
                                                assertFalse(row.next())
                                            }
                                        }
                                        roleDefaults++ // Actual role default remains RR; the original desired phase explicitly pins RC.
                                    }
                                    if (path == PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE &&
                                        step == DesiredInstallationSqlStep.LOCK_CONTROL
                                    ) {
                                        lockEntered.hold()
                                    }
                                }
                            }.also(reference::set)
                            invocation.betweenPhases = {
                                released(checkNotNull(invocation.probe).observations.values.single())
                                between.hold()
                            }
                            try {
                                assertThrows<ComplaintDesiredInstallationExceptionV1> { invocation.execute(1) }
                            } finally {
                                invocation.fixtureCleanup()
                            }
                        }
                        between.awaitEntered()
                        closedControl = f.control()
                        val holder =
                            checkNotNull(observer.queryForObject("SELECT pg_backend_pid(), txid_current()", { row, _ -> row.getInt(1) to row.getLong(2) }))
                        assertEquals(
                            listOf(ComplaintDataScope.LIVE.id),
                            observer.queryForList(
                                "SELECT data_scope_id FROM public.complaint_journal_control WHERE data_scope_id = ? FOR UPDATE",
                                UUID::class.java,
                                ComplaintDataScope.LIVE.id,
                            ),
                        )
                        between.release()
                        lockEntered.awaitEntered()
                        val probe = checkNotNull(checkNotNull(reference.get()).probe)
                        val waiter = probe.observations.values.last().identity
                        assertNotEquals(holder.first, waiter.first)
                        assertNotEquals(holder.second, waiter.second)
                        lockEntered.release()
                        awaitLifecycleFact(1_000) {
                            observer.queryForObject(
                                "SELECT ? = ANY(pg_blocking_pids(?)) AND EXISTS " +
                                    "(SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted AND locktype = 'transactionid')",
                                Boolean::class.java,
                                holder.first,
                                waiter.first,
                                waiter.first,
                            ) == true
                        }
                        // Restore the EXACT previously produced PREPARED+signed G1 row under the actual control lock,
                        // without updating that control tuple. This is obstruction-only fixture history, not installed success.
                        replaceMutationRow(observer, genesis.token, prepared)
                        blocker.commit()
                        val failure = worker.value()
                        assertEquals(ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED, failure.code)
                    }
                }
                val invocation = checkNotNull(reference.get())
                val probe = checkNotNull(invocation.probe)
                assertEquals(2, roleDefaults)
                assertPendingWaitRefused(probe, closedControl)
                assertArrayEquals(oldHash, control()["desired_configuration_hash"] as ByteArray)
                assertEquals(oldToken, control()["lease_token"])
                assertEquals(prepared, mutationRow(genesis.token))
            } finally {
                f.observer.execute("ALTER ROLE ${ComplaintDesiredInstallationFixture.OPERATOR} RESET default_transaction_isolation")
                f.independentTransaction { connection, observer ->
                    replaceMutationRow(observer, genesis.token, completed)
                    connection.commit()
                }
            }
        }
    }

    private fun replaceMutationRow(observer: JdbcTemplate, token: UUID, snapshot: String?) {
        assertEquals(1, observer.update("DELETE FROM public.complaint_catalog_mutations WHERE operation_token = ?", token))
        assertEquals(
            1,
            observer.update(
                "INSERT INTO public.complaint_catalog_mutations " +
                    "SELECT (jsonb_populate_record(NULL::public.complaint_catalog_mutations, ?::jsonb)).*",
                checkNotNull(snapshot),
            ),
        )
    }

    private fun assertPendingWaitRefused(probe: DesiredInstallationProbeJdbc, closedControl: String?) {
        val observations = probe.observations.values.toList()
        assertEquals(2, observations.size)
        released(observations.first())
        released(observations.last(), PersistenceDatabaseOutcome.ROLLED_BACK)
        assertEquals(
            listOf(DesiredInstallationSqlStep.AUTHENTICATE, DesiredInstallationSqlStep.LOCK_CONTROL, DesiredInstallationSqlStep.CHECK_PENDING),
            probe.steps.filter { it.first == PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE }.map { it.second },
        )
        assertEquals(
            closedControl,
            f.control(),
            "Phase2 observes the newly committed pending row even though its locked control preimage did not change.",
        )
    }

    fun phaseOneInterruptionStaleOldBindingAndFreshRetry() = f.genuineGenesis { _, _ ->
        f.stopRuntimeWithoutWaiting()
        val target = f.document.copy(desiredGeneration = 2)
        val oldHash = (control().getValue("desired_configuration_hash") as ByteArray).copyOf()
        val oldToken = control().getValue("lease_token")
        val outside = f.unrelatedControl()
        var durable: String? = null
        val interrupted = f.invocation(target)
        interrupted.betweenPhases = {
            val probe = checkNotNull(interrupted.probe)
            released(probe.observations.values.single())
            assertEquals(0, probe.coordinator.activeSnapshotOwners())
            durable = f.control()
            error("Synthetic stop after durable gate closure and actual release.")
        }
        assertThrows<ComplaintDesiredInstallationExceptionV1> { interrupted.execute(1) }
        interrupted.fixtureCleanup()
        assertNotNull(durable)
        assertEquals(durable, f.control())
        assertEquals(1, checkNotNull(interrupted.probe).observations.size)
        assertArrayEquals(oldHash, control()["desired_configuration_hash"] as ByteArray)
        assertEquals(oldToken, control()["lease_token"])
        assertEquals(outside, f.unrelatedControl())
        val stale = f.invocation(target)
        stale.betweenPhases = {
            // An adversarial full-B drift, not an invented accepted-generation receipt. Only refusal is asserted.
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE public.complaint_journal_control SET accepted_catalog_generation = 2 WHERE data_scope_id = ?",
                    ComplaintDataScope.LIVE.id,
                ),
            )
        }
        try {
            refused(stale, 1)
            val observations = checkNotNull(stale.probe).observations.values.toList()
            assertEquals(2, observations.size)
            released(observations[0])
            released(observations[1], PersistenceDatabaseOutcome.ROLLED_BACK)
            assertTrue(
                checkNotNull(stale.probe).steps.none {
                    it.first == PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE &&
                        it.second == DesiredInstallationSqlStep.WRITE_CONTROL
                },
            )
            assertArrayEquals(oldHash, control()["desired_configuration_hash"] as ByteArray)
            assertEquals(oldToken, control()["lease_token"])
        } finally {
            f.restoreControl(checkNotNull(durable))
        }
        val recovered = f.invocation(target)
        assertEquals(ComplaintDesiredInstallationTransitionV1.SUPERSEDED, recovered.execute(1).transition)
        assertSelected(recovered, 2)
        assertEquals((oldToken as Long) + 1, control()["lease_token"])
        val selected = f.control()
        val requests = interrupted.http.requests.size
        val steps = checkNotNull(interrupted.probe).steps.toList()
        assertThrows<ComplaintDesiredInstallationExceptionV1> { interrupted.execute(1) }
        assertEquals(requests, interrupted.http.requests.size)
        assertEquals(steps, checkNotNull(interrupted.probe).steps)
        assertEquals(selected, f.control(), "Fresh authenticated recovery does not revive the stopped original operation.")
    }

    private fun mutationRow(token: UUID): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT to_jsonb(m)::text FROM public.complaint_catalog_mutations m WHERE operation_token = ?",
            String::class.java,
            token,
        ),
    )

    private fun control(): Map<String, Any> =
        f.observer.queryForMap("SELECT * FROM public.complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)

    private fun bootstrapPreserved(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(c) - ARRAY['desired_configuration_hash','updated_at'])::text FROM public.complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    private fun assertSelected(invocation: DesiredInstallationInvocation, generation: Long) {
        val row = control()
        assertEquals(generation, row["desired_generation"])
        assertArrayEquals(checkNotNull(invocation.target).configurationHashBytes(), row["desired_configuration_hash"] as ByteArray)
        assertEquals(true, row["maintenance_closed"])
        assertEquals(true, row["creation_closed"])
        assertEquals(true, row["scan_requested"])
        requireConnectionFree()
    }

    private fun assertNoWrite(invocation: DesiredInstallationInvocation) {
        assertTrue(checkNotNull(invocation.probe).steps.none { it.second == DesiredInstallationSqlStep.WRITE_CONTROL })
        checkNotNull(invocation.probe).observations.values.forEach { released(it, it.phase.databaseOutcome()) }
    }

    private fun refused(invocation: DesiredInstallationInvocation, expectedGeneration: Long) {
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { invocation.execute(expectedGeneration) }
        assertEquals(ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED, failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        checkNotNull(invocation.probe).observations.values.forEach { released(it, it.phase.databaseOutcome()) }
        invocation.fixtureCleanup()
    }

    private fun released(observation: StepUpPhaseObservation, outcome: PersistenceDatabaseOutcome = PersistenceDatabaseOutcome.COMMITTED) {
        assertEquals(outcome, observation.phase.databaseOutcome())
        assertTrue(observation.lease.completion.quiescent())
        assertTrue(observation.phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        requireConnectionFree()
    }
}
