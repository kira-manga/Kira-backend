package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestGate
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.ProcessBoundCatalogGenesisFixture
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real protocol writers and authenticated first-D contenders; no pre-lock/RR snapshot is accepted as visibility evidence. */
internal class ComplaintSignedGenesisFirstDConcurrencyCases(private val f: ComplaintDesiredInstallationFixture) {
    fun signatureCommitVisibleAfterControlWait() {
        val genesis = f.firstDGenesis()
        val initial = f.control()
        val prepared = genesis.firstDPrepare()
        assertEquals(initial, f.control())
        val preserved = f.firstDPreservedControl()
        val counters = f.observer.queryForList("SELECT to_jsonb(c)::text FROM public.complaint_capacity_counters c ORDER BY name", String::class.java)
        val invocationRef = AtomicReference<SignedGenesisFirstDInvocation?>()
        val writerIdentity = AtomicReference<Pair<Int, Long>?>()
        val writerAssertions = AtomicReference<AssertionError?>()
        val actualResetObserved = AtomicBoolean()
        f.observer.execute("ALTER ROLE ${ComplaintDesiredInstallationFixture.OPERATOR} SET default_transaction_isolation = 'repeatable read'")
        try {
            OwnedCallerTestScope().use { callers ->
                val ready = callers.gate()
                val signedButUncommitted = callers.gate()
                val beforeControl = callers.gate()
                val writerReleased = callers.gate()
                val installer = callers.launch {
                    val invocation = f.firstDInvocation(beforeClose = {
                        writerReleased.awaitEntered() // Sequence peer stop only AFTER actual signature commit AND holder release.
                        f.stopRuntimeWithoutWaiting()
                        writerReleased.release()
                    }) { probe ->
                        observeInstallerIsolation(probe, beforeControl, actualResetObserved)
                    }.also(invocationRef::set)
                    invocation.beforePhase = { ready.hold() }
                    try {
                        invocation.execute()
                    } finally {
                        invocation.fixtureCleanup()
                    }
                }
                ready.awaitEntered() // Actual operator prepared and raw release verified before the writer's 2s phase cap starts.
                observeSignatureBeforeCommit(genesis, initial, writerIdentity, writerAssertions, signedButUncommitted)
                val writer = callers.launch {
                    try {
                        genesis.firstDSign(prepared)
                    } finally {
                        genesis.released()
                        writerReleased.hold()
                    }
                }
                signedButUncommitted.awaitEntered()
                ready.release()
                beforeControl.awaitEntered()
                val observed = checkNotNull(checkNotNull(invocationRef.get()).probe).observations.values.single()
                val signer = checkNotNull(writerIdentity.get())
                assertNotEquals(signer.first, observed.identity.first)
                assertNotEquals(signer.second, observed.identity.second)
                beforeControl.release()
                assertBlocking(signer.first, observed.identity.first)
                signedButUncommitted.release()
                val result = installer.value()
                val signed: UnverifiedGenesisPreparation = writer.value()
                writerAssertions.get()?.let { throw it }
                assertEquals(ComplaintSignedGenesisFirstDTransitionV1.SELECTED, result.transition)
                assertArrayEquals(genesis.genesisBytes, signed.mutation.signedEnvelopeBytes)
            }
            val invocation = checkNotNull(invocationRef.get())
            assertTrue(actualResetObserved.get())
            f.assertFirstDSelected(invocation)
            assertEquals(firstDSteps(write = true), checkNotNull(invocation.probe).steps)
            firstDReleased(checkNotNull(invocation.probe).observations.values.single())
            assertEquals(preserved, f.firstDPreservedControl())
            assertEquals(
                counters,
                f.observer.queryForList("SELECT to_jsonb(c)::text FROM public.complaint_capacity_counters c ORDER BY name", String::class.java),
            )
            assertArrayEquals(
                genesis.genesisBytes,
                f.observer.queryForObject(
                    "SELECT envelope_bytes FROM public.complaint_catalog_mutations WHERE operation_token = ?",
                    ByteArray::class.java,
                    genesis.token,
                ),
            )
        } finally {
            genesis.jdbc.afterSql = null
            f.observer.execute("ALTER ROLE ${ComplaintDesiredInstallationFixture.OPERATOR} RESET default_transaction_isolation")
        }
    }

    private fun observeInstallerIsolation(probe: SignedGenesisFirstDProbeJdbc, beforeControl: OwnedCallerTestGate, actualResetObserved: AtomicBoolean) {
        probe.beforeSql = { step ->
            if (step === SignedGenesisFirstDSqlStep.AUTHENTICATE) {
                probe.holder.connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT reset_val, current_setting('transaction_isolation') FROM pg_settings " +
                            "WHERE name = 'default_transaction_isolation'",
                    ).use { row ->
                        assertTrue(row.next())
                        assertEquals("repeatable read", row.getString(1))
                        assertEquals("read committed", row.getString(2))
                        assertFalse(row.next())
                    }
                }
                actualResetObserved.set(true)
            }
            if (step === SignedGenesisFirstDSqlStep.LIVE) beforeControl.hold()
        }
    }

    private fun observeSignatureBeforeCommit(
        genesis: ProcessBoundCatalogGenesisFixture,
        initial: String,
        writerIdentity: AtomicReference<Pair<Int, Long>?>,
        writerAssertions: AtomicReference<AssertionError?>,
        signedButUncommitted: OwnedCallerTestGate,
    ) {
        genesis.jdbc.afterSql = { step ->
            if (step == "signature") {
                try {
                    writerIdentity.set(signatureWriterIdentity(genesis, initial))
                    signedButUncommitted.hold()
                } catch (problem: AssertionError) {
                    writerAssertions.compareAndSet(null, problem)
                    throw problem
                }
            }
        }
    }

    private fun signatureWriterIdentity(genesis: ProcessBoundCatalogGenesisFixture, initial: String): Pair<Int, Long> {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE, poolTestField(phase, "path"))
        val holder = TransactionSynchronizationManager.getResource(genesis.coordinator.dataSource) as ConnectionHolder
        val identity = holder.connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                assertTrue(row.next())
                (row.getInt(1) to row.getLong(2)).also { assertFalse(row.next()) }
            }
        }
        holder.connection.createStatement().use { statement ->
            statement.executeQuery("SELECT to_jsonb(c)::text FROM public.complaint_journal_control c").use { row ->
                assertTrue(row.next())
                assertEquals(initial, row.getString(1), "The real signature writer changes history, NOT the locked control preimage.")
                assertFalse(row.next())
            }
        }
        return identity
    }

    fun authenticatedContenders(identical: Boolean) {
        val genesis = f.firstDGenesis()
        genesis.stageSigned()
        f.stopRuntimeWithoutWaiting()
        val history = f.historyAndCounters()
        val preserved = f.firstDPreservedControl()
        val firstRef = AtomicReference<SignedGenesisFirstDInvocation?>()
        val secondRef = AtomicReference<SignedGenesisFirstDInvocation?>()
        val secondInput = if (identical) {
            f.document
        } else {
            f.document.copy(
                admission = f.document.admission.copy(
                    concurrentLimit =
                    f.document.admission.concurrentLimit + 1,
                ),
            )
        }
        OwnedCallerTestScope().use { callers ->
            val firstReady = callers.gate()
            val secondReady = callers.gate()
            val firstLocked = callers.gate()
            val secondControl = callers.gate()
            val first = callers.launch {
                val invocation = f.firstDInvocation { probe ->
                    probe.afterSql = { step ->
                        if (step === SignedGenesisFirstDSqlStep.INITIAL && probe.steps.count { it === step } == 1) firstLocked.hold()
                    }
                }.also(firstRef::set)
                invocation.beforePhase = { firstReady.hold() }
                try {
                    invocation.execute()
                } finally {
                    invocation.fixtureCleanup()
                }
            }
            val second = callers.launch {
                val invocation = f.firstDInvocation(secondInput) { probe ->
                    probe.beforeSql = { if (it === SignedGenesisFirstDSqlStep.LIVE) secondControl.hold() }
                }.also(secondRef::set)
                invocation.beforePhase = { secondReady.hold() }
                try {
                    runCatching { invocation.execute() }
                } finally {
                    invocation.fixtureCleanup()
                }
            }
            firstReady.awaitEntered()
            secondReady.awaitEntered()
            firstReady.release()
            firstLocked.awaitEntered()
            secondReady.release()
            secondControl.awaitEntered()
            val firstIdentity = checkNotNull(checkNotNull(firstRef.get()).probe).observations.values.single().identity
            val secondIdentity = checkNotNull(checkNotNull(secondRef.get()).probe).observations.values.single().identity
            assertNotEquals(firstIdentity.first, secondIdentity.first)
            assertNotEquals(firstIdentity.second, secondIdentity.second)
            secondControl.release()
            assertBlocking(firstIdentity.first, secondIdentity.first)
            firstLocked.release()
            assertEquals(ComplaintSignedGenesisFirstDTransitionV1.SELECTED, first.value().transition)
            val resumed = second.value()
            if (identical) {
                assertEquals(ComplaintSignedGenesisFirstDTransitionV1.ALREADY_SELECTED, resumed.getOrThrow().transition)
            } else {
                assertEquals(
                    ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED,
                    assertThrows<ComplaintDesiredInstallationExceptionV1> {
                        resumed.getOrThrow()
                    }.code,
                )
            }
        }
        val first = checkNotNull(firstRef.get())
        val second = checkNotNull(secondRef.get())
        f.assertFirstDSelected(first)
        firstDReleased(checkNotNull(first.probe).observations.values.single())
        firstDReleased(
            checkNotNull(second.probe).observations.values.single(),
            if (identical) PersistenceDatabaseOutcome.COMMITTED else PersistenceDatabaseOutcome.ROLLED_BACK,
        )
        assertTrue(checkNotNull(second.probe).steps.none { it === SignedGenesisFirstDSqlStep.WRITE_D })
        assertEquals(preserved, f.firstDPreservedControl())
        assertEquals(history, f.historyAndCounters())
    }

    private fun assertBlocking(holder: Int, waiter: Int) {
        requireConnectionFree()
        awaitLifecycleFact(1_000) {
            f.observer.queryForObject(
                "SELECT ? = ANY(pg_blocking_pids(?)) AND EXISTS " +
                    "(SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted AND locktype = 'transactionid')",
                Boolean::class.java,
                holder,
                waiter,
                waiter,
            ) == true
        }
    }
}
