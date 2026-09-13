package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import me.manga.kira.backend.security.SourceGrantCleanup
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.lang.reflect.Field
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real acquisition/epochs and stock Hikari. Injected fatal SQLExceptions are MODEL creator routes, not PG commit evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class PoolLeaseDispatchCreatorIntegrationTest {
    private val database = lazy { PgLifecycleDatabaseFixture().also { it.start() } }

    @TempDir
    lateinit var pendingProbeRoot: Path

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `MODEL fatal connection commit creates the first real Hikari close Worker and drains its task`() = fatalRoute(statementRoute = false)

    @Test
    fun `MODEL fatal Hikari statement execution creates the first real close Worker and drains its task`() = fatalRoute(statementRoute = true)

    private fun fatalRoute(statementRoute: Boolean) = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        val lease = ownedPoolLease(connection)
        val entry = f.entry(connection)
        val handle = ownedCutField(lease, "handle") as Connection
        val hikari = ownedCutField(f.pool, "pool") as HikariDataSource
        val hikariPool = requireNotNull(ownedCutField(hikari, "pool"))
        val closer = ownedCutField(hikariPool, "closeConnectionExecutor") as ThreadPoolExecutor
        val poolEntry = requireNotNull(ownedCutField(handle, "poolEntry"))
        val lower = ownedCutField(handle, "delegate") as Connection
        val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
        assertTrue(lower is PhysicalJdbcFacade)
        assertSame(lower, ownedCutField(poolEntry, "connection"))
        requireInitialCloseWorkerState(f.lifecycle, closer)

        OwnedCallerTestScope().use { calls ->
            val closeTail = calls.gate()
            val closeThread = AtomicReference<Thread?>()
            val closeCalls = AtomicInteger()
            val workerFailure = AtomicReference<Throwable?>()
            // The exact PoolEntry still supplies the real lower connection. Only close observation
            // is interposed; neither executor/factory, queue, worker count nor receipt is replaced.
            val observedLower = object : Connection by lower {
                override fun close() {
                    try {
                        assertEquals(1, closeCalls.incrementAndGet())
                        closeThread.set(Thread.currentThread())
                        assertTrue(PoolActorCustody.currentThreadOwnsActorFrame())
                        assertTrue(f.lifecycle.isAuthenticPoolCaller())
                        assertNull(PoolCallFrames.current(), "The executing close task is a real Worker, not borrower CallerRuns.")
                        closeTail.hold()
                        lower.close()
                    } catch (failure: Throwable) {
                        workerFailure.set(failure)
                        throw failure
                    }
                }
            }
            val connectionCell = creatorField(poolEntry, "connection")
            connectionCell.set(poolEntry, observedLower)
            var statement: Statement? = null
            var statementDelegate: Field? = null
            var hikariStatement: Any? = null
            var originalStatement: Statement? = null
            val connectionDelegate = creatorField(handle, "delegate")
            val injected = SQLException("Controlled creator-route failure, not a native PG result.", "57P01")
            try {
                if (statementRoute) {
                    statement = connection.createStatement()
                    val actualHikariStatement = ownedCutNative(requireNotNull(statement))
                    hikariStatement = actualHikariStatement
                    assertTrue(actualHikariStatement.javaClass.name.startsWith("com.zaxxer.hikari.pool.HikariProxy"))
                    val delegateField = creatorField(actualHikariStatement, "delegate")
                    statementDelegate = delegateField
                    val actual = delegateField.get(actualHikariStatement) as Statement
                    originalStatement = actual
                    delegateField.set(
                        actualHikariStatement,
                        object : Statement by actual {
                            override fun execute(sql: String): Boolean = throw injected
                        },
                    )
                } else {
                    connectionDelegate.set(
                        handle,
                        object : Connection by lower {
                            override fun commit(): Unit = throw injected
                        },
                    )
                }

                val failure = assertThrows<SQLException> {
                    if (statementRoute) requireNotNull(statement).execute("SELECT 1") else connection.commit()
                }
                assertSame(injected, failure, "This is the real Hikari fatal handler around a controlled delegate failure.")
                closeTail.awaitEntered()
                workerFailure.get()?.let { throw it }
                assertNotSame(Thread.currentThread(), closeThread.get())
                assertEquals(1, closer.poolSize)
                assertEquals(1, closer.largestPoolSize)
                assertEquals(1, closer.activeCount)
                assertEquals(1, closeCalls.get())
                assertTrue(closer.queue.isEmpty())
                assertEquals(0L, closer.completedTaskCount)
                assertNull(f.lifecycle.actorSnapshot().firstFailure)
                assertFalse(f.lifecycle.actorSnapshot().factorySealed)
                assertEquals(1L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
                assertNull(ownedCutField(entitlement, "prepared"), "Implicit eviction did not spend or reserve RETURN.")
                assertTrue(lease.state.epoch.outerTailsEnded())
                assertTrue(handle.isClosed)
                assertNull(connectionCell.get(poolEntry), "Hikari itself invalidated the captured PoolEntry.")

                // Release the real facade child before authentic RETURN; native terminal cleanup
                // is still owned by the original physical record, not by this MODEL SQLException.
                statementDelegate?.set(hikariStatement, originalStatement)
                statementDelegate = null
                statement?.close()
                statement = null
                assertThrows<SQLException> { connection.close() } // Closed Hikari proxy cannot supply RETURN consent.
                val returning = ownedCutField(entitlement, "prepared") as PoolLifecycle.Operation
                assertTrue(returning.actualFrameEnded())
                assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                assertFalse((ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer).consented())
                closeTail.release()
                awaitLifecycleFact { closer.completedTaskCount == 1L && closer.activeCount == 0 && closer.queue.isEmpty() }
                workerFailure.get()?.let { throw it }
                assertNull(f.lifecycle.actorSnapshot().firstFailure)
                awaitLifecycleFact { f.scope.entries().none { it === entry } }
                assertTrue(lease.completion.quiescent())
            } finally {
                closeTail.release()
                statementDelegate?.set(hikariStatement, originalStatement)
                statement?.close()
                // Restore a test shim only if Hikari has NOT invalidated its real cell. Never
                // replace CLOSED_CONNECTION/null after the fatal route or repair product state.
                if (!handle.isClosed) connectionDelegate.set(handle, lower)
                if (connectionCell.get(poolEntry) === observedLower) connectionCell.set(poolEntry, lower)
                connection.close()
            }
        }
    }

    private fun requireInitialCloseWorkerState(lifecycle: PoolLifecycle, closer: ThreadPoolExecutor) {
        assertEquals(0L, lifecycle.activeAcquisitions())
        assertNull(PoolCallFrames.current())
        assertFalse(PoolActorCustody.currentThreadOwnsActorFrame())
        assertEquals(1, closer.corePoolSize)
        assertEquals(1, closer.maximumPoolSize)
        assertEquals(5L, closer.getKeepAliveTime(TimeUnit.SECONDS))
        assertTrue(closer.allowsCoreThreadTimeOut())
        assertEquals(0, closer.poolSize)
        assertEquals(0, closer.largestPoolSize)
        assertEquals(0, closer.activeCount)
        assertTrue(closer.queue.isEmpty())
        assertEquals(0L, closer.completedTaskCount)
    }

    @Test
    fun `repeated nested admitted dispatches retain one future RETURN and no lower native fallback`() = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        val lease = ownedPoolLease(connection)
        val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
        val lower = ownedCutField(lease, "lower") as Connection
        try {
            repeat(2) {
                withCreatorCall(lease) { outer ->
                    assertNull(outer.call.budget, "Unscoped calls remain genuinely unbudgeted.")
                    assertNull(outer.creator.frame.admittedBudget)
                    assertEquals(1L, f.lifecycle.actorSnapshot().activeOperations)
                    assertEquals(1L, creatorTailCount(lease))
                    assertFalse(f.lifecycle.isAuthenticPoolCaller())
                    assertFalse(outer.creator.enter())
                    assertFalse(outer.creator.end())
                    assertNull(lease.prepareCreator(outer.dispatch, outer.call))
                    withCreatorCall(lease) { inner ->
                        assertSame(outer.creator.frame, inner.creator.frame.previous())
                        assertEquals(2L, f.lifecycle.actorSnapshot().activeOperations)
                        assertEquals(2L, creatorTailCount(lease))
                        assertEquals(1L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                        assertNull(ownedCutField(entitlement, "prepared"))
                        assertFalse(outer.creator.end())
                    }
                    // An ordinary lower nested guard shares the actual dispatch but must never
                    // independently mint another upper creator for that invocation.
                    val lowerCall = lease.state.context.enter(lease.identity, PersistenceJdbcGuardCallKind.BUSINESS)
                    try {
                        assertNull(lease.prepareCreator(outer.dispatch, lowerCall))
                    } finally {
                        lowerCall.finish()
                    }
                    outer.finishGuard()
                    assertNull(PersistenceJdbcDispatch.current())
                    assertFalse(lease.state.context.hasCurrentFrame())
                    assertFalse(lease.state.epoch.foregroundActive())
                    assertEquals(0L, lease.state.epoch.activeCancellations())
                    assertSame(outer.creator.frame, PoolCallFrames.current())
                    assertFalse(lease.state.epoch.outerTailsEnded())
                    assertFalse(f.lifecycle.isAuthenticPoolCaller(), "Only creator provenance remains after JDBC/core TL restoration.")
                    assertThrows<SQLException> { lower.autoCommit }
                    assertEquals(1L, creatorTailCount(lease))
                    assertFalse(lease.completion.quiescent())
                    assertTrue(outer.creator.end())
                    assertFalse(outer.creator.end())
                    assertFalse(outer.creator.enter())
                    assertFalse(outer.creator.failBeforeEnd())
                    assertFalse(outer.call.creatorGuardEnded(outer.creator))
                    assertNull(lease.prepareCreator(outer.dispatch, outer.call))
                }
                assertNull(PoolCallFrames.current())
                assertTrue(lease.state.epoch.outerTailsEnded())
                assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
                assertEquals(1L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                assertNull(ownedCutField(entitlement, "prepared"))
            }
            connection.close()
            val returning = ownedCutField(entitlement, "prepared") as PoolLifecycle.Operation
            assertTrue(returning.actualFrameEnded())
            assertTrue((ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer).consented())
            assertTrue(lease.state.epoch.sealedAndEnded())
            assertTrue(lease.completion.quiescent())
            assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
            assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
            assertFalse(returning.enter())
            assertFalse(returning.end())
            assertFalse(entitlement.revoke())
            assertNull(entitlement.prepareReturn(PersistenceTimeBudget.start(1_000)))
            assertNull(entitlement.prepareEviction(PersistenceTimeBudget.start(1_000)))
            connection.close()
            assertSame(returning, ownedCutField(entitlement, "prepared"))
            assertThrows<SQLException> { lease.enterDispatch() }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `only exact admitted guard dispatch lease epoch and issuer can enter a creator`() = withOwnedCutPool(database.value) { f ->
        f.pool.connection.use { connection ->
            val lease = ownedPoolLease(connection)
            val context = lease.state.context
            val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
            val forgedToken = PersistenceProducerEpoch.Call.prepare(
                lease.state.epoch,
                Any(),
                Thread.currentThread(),
                PersistenceProducerEpoch.Kind.FOREGROUND,
                null,
            )
            val forgedCall = PersistenceJdbcGuardCall.prepare(context, lease.identity, forgedToken, PersistenceJdbcGuardCallKind.BUSINESS, null)
            assertFalse(forgedToken.actualAdmitted())
            withCreatorCall(lease, enterCreator = false) { actual ->
                assertNull(lease.prepareCreator(actual.dispatch, forgedCall))
                val fabricatedDispatch = PersistenceJdbcDispatch.Frame(lease, Any(), false, PersistenceJdbcGuardCallKind.BUSINESS, null)
                assertFalse(fabricatedDispatch.actualUpper(lease))
                assertNull(lease.prepareCreator(fabricatedDispatch, actual.call))
                assertThrows<SQLException> { PersistenceJdbcDispatch.enter(lease, Any(), returning = false) }
                val forgedFrame = PoolCallFrame.prepareLeaseDispatch(f.lifecycle, Any(), Thread.currentThread(), null, lease.state.epoch)
                val fabricated = PoolLifecycle.LeaseDispatchCreator.prepare(f.lifecycle, forgedFrame, entitlement, lease, actual.dispatch, actual.call)
                assertFalse(fabricated.enter())
                assertFalse(fabricated.end())
                assertFalse(fabricated.failBeforeEnd())
                val wrongEntitlement = PoolLifecycle.LeaseEntitlement.prepare(f.lifecycle, Any(), Thread.currentThread())
                assertFalse(wrongEntitlement.bindLease(lease))
                assertNull(wrongEntitlement.prepareDispatch(lease, actual.dispatch, actual.call))

                val otherIdentity = PersistenceJdbcGuardIdentity.prepare(context, lease.state.epoch, lease.cleanup)
                val sameOwnerNotUpper = context.enter(otherIdentity, PersistenceJdbcGuardCallKind.BUSINESS)
                try {
                    assertTrue(context.sameOwner(lease.identity, otherIdentity))
                    assertNull(lease.prepareCreator(actual.dispatch, sameOwnerNotUpper), "Same owner is not the actual upper identity.")
                } finally {
                    sameOwnerNotUpper.finish()
                }
                val ownership = ownedCutField(lease, "ownership") as PersistenceOwnership
                val otherEpoch = ownership.prepareEpoch()
                val wrongEpochIdentity = PersistenceJdbcGuardIdentity.prepare(context, otherEpoch, otherEpoch.preparedCleanup())
                assertThrows<SQLException> { context.enter(wrongEpochIdentity, PersistenceJdbcGuardCallKind.BUSINESS) }
                assertFalse(entitlement.bindLease(lease), "Acquisition association is one-time and cannot be rebound after exposure.")
                assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
                assertEquals(0L, creatorTailCount(lease))
                actual.enterCreator()
                assertTrue(actual.creator.frame.active())
                assertEquals(1L, creatorTailCount(lease))
                assertNull(f.lifecycle.actorSnapshot().firstFailure)
            }
        }
    }

    @Test
    fun `mixed pool creator nesting refuses wrong top fallback and unrelated lease use`() = withOwnedCutPool(database.value) { first ->
        withOwnedCutPool(database.value, companion = first) { second ->
            first.pool.connection.use { firstConnection ->
                second.pool.connection.use { secondConnection ->
                    val firstLease = ownedPoolLease(firstConnection)
                    val secondLease = ownedPoolLease(secondConnection)
                    val firstLower = ownedCutField(firstLease, "lower") as Connection
                    withCreatorCall(firstLease) { outer ->
                        withCreatorCall(secondLease) { inner ->
                            assertSame(outer.creator.frame, inner.creator.frame.previous())
                            assertNull(firstLease.prepareCreator(inner.dispatch, inner.call))
                            assertNull(secondLease.prepareCreator(outer.dispatch, outer.call))
                            val firstGate = requireNotNull(ownedCutField(first.lifecycle, "gate"))
                            val secondGate = requireNotNull(ownedCutField(second.lifecycle, "gate"))
                            synchronized(firstGate) { assertNull(first.lifecycle.creatorCompletionLocked()) }
                            synchronized(secondGate) { assertSame(inner.creator.frame.completion, second.lifecycle.creatorCompletionLocked()) }
                            assertFalse(first.lifecycle.isAuthenticPoolCaller())
                            assertFalse(second.lifecycle.isAuthenticPoolCaller())
                            assertThrows<SQLException> { firstLower.autoCommit }
                            assertEquals(PoolShutdownInvocation.ACTIVE_POOL_FRAME, first.pool.shutdownInvocation())
                            assertEquals(PoolShutdownInvocation.ACTIVE_POOL_FRAME, second.pool.shutdownInvocation())
                        }
                        val gate = requireNotNull(ownedCutField(first.lifecycle, "gate"))
                        synchronized(gate) { assertSame(outer.creator.frame.completion, first.lifecycle.creatorCompletionLocked()) }
                        assertEquals(1L, first.lifecycle.actorSnapshot().futureLeaseEntries)
                        assertEquals(1L, second.lifecycle.actorSnapshot().futureLeaseEntries)
                    }
                }
            }
        }
    }

    @Test
    fun `present returned dispatch cannot upgrade through pool authority and a revoked lease cannot enter a prepared creator`() =
        withOwnedCutPool(database.value) { f ->
            val connection = f.pool.connection
            val lease = ownedPoolLease(connection)
            val lower = ownedCutField(lease, "lower") as Connection
            val stale = lease.enterDispatch() // A dispatch alone is not an admitted guard or creator.
            try {
                connection.close()
                assertTrue(lease.completion.quiescent())
                assertSame(stale, PersistenceJdbcDispatch.current())
                val acquisition = f.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(1_000))
                assertTrue(acquisition.enter())
                try {
                    assertTrue(f.lifecycle.isAuthenticPoolCaller())
                    assertThrows<SQLException> { lower.autoCommit }
                    assertThrows<SQLException> { lease.state.context.enter(lease.identity, PersistenceJdbcGuardCallKind.BUSINESS) }
                    stale.end()
                    assertTrue(lower.autoCommit, "Absent stale dispatch, this actual pool caller may select only the genuine unleased pool epoch.")
                } finally {
                    assertTrue(acquisition.end())
                }
            } finally {
                if (!stale.actualEnded()) stale.end()
                connection.close()
            }

            val revokedConnection = f.pool.connection
            val revokedLease = ownedPoolLease(revokedConnection)
            val entitlement = ownedCutField(revokedLease, "entitlement") as PoolLifecycle.LeaseEntitlement
            val entry = f.entry(revokedConnection)
            try {
                withCreatorCall(revokedLease, enterCreator = false) { admitted ->
                    val prepared = requireNotNull(revokedLease.prepareCreator(admitted.dispatch, admitted.call))
                    assertTrue(entitlement.revoke())
                    assertFalse(entitlement.revoke())
                    assertFalse(prepared.enter())
                    assertNull(revokedLease.prepareCreator(admitted.dispatch, admitted.call))
                    assertEquals(0L, creatorTailCount(revokedLease))
                    assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
                    admitted.finishGuard()
                    assertTrue(prepared.end(), "Only its genuinely unadmitted refusal may settle; no tail/count was granted.")
                    assertFalse(prepared.enter())
                    assertFalse(prepared.end())
                    assertNull(ownedCutField(entitlement, "prepared"))
                }
                assertThrows<SQLException> { revokedConnection.close() }
                awaitLifecycleFact { f.scope.entries().none { it === entry } }
                assertTrue(revokedLease.completion.quiescent())
                assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
            } finally {
                revokedConnection.close()
            }
        }

    @Test
    fun `foreign actual statement cancellation keeps only its own outer tail while original RETURN waits for it`() = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        val lease = ownedPoolLease(connection)
        val entry = f.entry(connection)
        val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
        val statement = connection.createStatement()
        try {
            OwnedCallerTestScope().use { callers ->
                val tail = callers.gate()
                val cancellation = callers.launch {
                    assertThrows<SQLException> { connection.autoCommit }
                    assertThrows<SQLException> { lease.state.context.enter(lease.identity, PersistenceJdbcGuardCallKind.BUSINESS) }
                    assertNull(entitlement.prepareReturn(PersistenceTimeBudget.start(1_000)))
                    assertNull(entitlement.prepareEviction(PersistenceTimeBudget.start(1_000)))
                    CreatorOuterEndBarrier(lease, PersistenceJdbcGuardCallKind.CANCELLATION) {
                        assertEquals(0L, lease.state.epoch.activeCancellations())
                        assertFalse(f.lifecycle.isAuthenticPoolCaller())
                        assertEquals(PoolShutdownInvocation.ACTIVE_POOL_FRAME, f.pool.shutdownInvocation())
                        assertNull(entitlement.prepareReturn(PersistenceTimeBudget.start(1_000)))
                        tail.hold()
                    }.use { barrier ->
                        statement.cancel() // Actual upper -> Hikari -> lower descendant cancellation, not a fabricated token.
                        assertEquals(1, barrier.holds)
                    }
                    true
                }
                tail.awaitEntered()
                assertFalse(lease.state.context.hasCurrentFrame())
                assertFalse(lease.state.epoch.foregroundActive())
                assertEquals(0L, lease.state.epoch.activeCancellations())
                assertEquals(1L, creatorTailCount(lease))
                assertEquals(1L, f.lifecycle.actorSnapshot().activeOperations)
                assertEquals(1L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                statement.close() // Real original-caller child cleanup while the foreign outer tail remains held.
                val observer = callers.launch {
                    try {
                        awaitLifecycleFact {
                            val state = creatorEpochState(lease) // The genuine seal publishes the earlier transfer association.
                            val transfer = ownedCutField(lease, "transfer") as? PersistenceJdbcPoolTransfer
                            transfer != null && !lease.state.epoch.foregroundActive() &&
                                ownedCutField(state, "sealed") == true
                        }
                        val transfer = ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer
                        assertFalse(transfer.consented())
                        assertFalse(transfer.actualEnded())
                        assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                        assertEquals(2L, f.lifecycle.actorSnapshot().activeOperations)
                        assertEquals(1L, creatorTailCount(lease))
                        assertFalse(lease.state.epoch.sealedAndEnded())
                        assertFalse(lease.completion.quiescent())
                        assertFalse(entry.jdbc.postOpeningCallsEnded())
                    } finally {
                        tail.release() // Within the ORIGINAL one-second RETURN allowance; no replacement budget.
                    }
                    true
                }
                connection.close()
                assertTrue(cancellation.value())
                assertTrue(observer.value())
                assertTrue((ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer).consented())
                assertTrue(lease.state.epoch.sealedAndEnded())
                assertTrue(lease.completion.quiescent())
                assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
            }
        } finally {
            statement.close()
            connection.close()
        }
    }

    @Test
    fun `real post core outer tail refuses reentrant self wait and remains in exact terminal and loan proofs`() = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        val lease = ownedPoolLease(connection)
        val entry = f.entry(connection)
        try {
            CreatorOuterEndBarrier(lease, PersistenceJdbcGuardCallKind.BUSINESS) {
                assertEquals(1L, creatorTailCount(lease))
                assertFalse(lease.state.epoch.foregroundActive())
                assertEquals(0L, lease.state.epoch.activeCancellations())
                assertTrue(PoolCallFrames.retainsLeaseTail(lease.state.epoch))
                assertFalse(f.lifecycle.isAuthenticPoolCaller())
                assertEquals(PoolShutdownInvocation.ACTIVE_POOL_FRAME, f.pool.shutdownInvocation())
                assertThrows<SQLException> { connection.close() }
                val transfer = ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer
                assertFalse(transfer.consented())
                assertTrue(transfer.actualEnded())
                assertTrue(persistenceFactoryRemainingMillis(transfer.budget) > 0L, "Reentrant RETURN must refuse, not spend its allowance waiting for itself.")
                assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                assertEquals(1L, f.lifecycle.actorSnapshot().activeOperations)
                assertFalse(lease.completion.quiescent(), "Ended RETURN/entitlement alone cannot discharge this exact outer tail.")
                assertThrows<PersistencePhaseException> { requireConnectionFree() }
                val receipt = requireNotNull(f.pool.requestShutdown())
                assertEquals(PoolShutdownObservation.ACTIVE_POOL_FRAME, receipt.observe())
                assertEquals(PoolShutdownInvocation.ACTIVE_POOL_FRAME, f.pool.shutdownInvocation())
                assertEquals(PersistenceTerminalCall.NOT_INVOKED, f.lifecycle.firstCloseOutcome())
                awaitLifecycleFact { !entry.jdbc.permitsCleanup(lease.state.epoch) && entry.terminalWork != null }
                assertFalse(lease.state.epoch.sealedAndEnded())
                assertFalse(entry.jdbc.postOpeningCallsEnded())
                assertFalse(requireNotNull(entry.terminalWork).producerDrainProven())
                assertFalse(entry.jdbc.terminalCompletion().reclaimed())
            }.use { barrier ->
                connection.autoCommit
                assertEquals(1, barrier.holds)
            }
            assertTrue(lease.state.epoch.outerTailsEnded())
            assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
            awaitLifecycleFact { f.scope.entries().none { it === entry } }
            assertTrue(lease.completion.quiescent())
            requireConnectionFree()
        } finally {
            connection.close()
        }
    }

    @Test
    fun `MODEL work expiry inside actual afterJdbcCall cannot erase its admitted creator after epoch retirement`() {
        val clock = CreatorTailClock()
        val actorRan = AtomicBoolean()
        val diagnostics = CreatorTailDiagnostics(actorRan)
        diagnostics.withFixture {
            withOrdinarySourceGrantCleanup(database.value, clock) { f ->
                diagnostics.capture {
                    var selected: PersistenceJdbcLease? = null
                    var witnessed = false
                    val actorFailure = AtomicReference<Throwable?>()
                    val store = object : SourceGrantCleanup {
                        override fun deleteEligibleSourceGrants(cutoff: Instant): Int = diagnostics.capture {
                            diagnostics.reached(CreatorTailStage.STORE_ENTERED)
                            val connection = (TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder).connection
                            val lease = ownedPoolLease(connection)
                            selected = lease
                            diagnostics.reached(CreatorTailStage.LEASE_OBTAINED)
                            val phase = requireNotNull(PersistencePhaseOwnership.current())
                            val originalBudget = ownedCutField(phase, "work") as PersistenceTimeBudget
                            val handle = ownedCutField(lease, "handle") as Connection
                            val field = creatorField(handle, "delegate")
                            val lower = field.get(handle) as Connection
                            val entry = f.ownedPool.entry(connection)
                            val hikari = ownedCutField(f.pool, "pool") as HikariDataSource
                            diagnostics.reached(CreatorTailStage.SETUP_OBTAINED)
                            val shim = object : Connection by lower {
                                override fun getClientInfo(name: String): String? = diagnostics.capture {
                                    val result = lower.getClientInfo(name)
                                    diagnostics.reached(CreatorTailStage.LOWER_RETURNED)
                                    clock.nextSample = {
                                        diagnostics.capture {
                                            diagnostics.reached(CreatorTailStage.CLOCK_ENTERED)
                                            val frame = requireNotNull(PoolCallFrames.current())
                                            val ticket = ownedCutField(frame, "leaseCreator") as PoolLifecycle.LeaseDispatchCreator
                                            // The normal kira-backend JVM build mangles this internal method with its module name.
                                            assertTrue(
                                                Thread.currentThread().stackTrace.any {
                                                    it.className == PersistencePhaseContext::class.java.name &&
                                                        it.methodName == "afterJdbcCall\$kira_backend"
                                                },
                                            )
                                            assertSame(lease, ticket.lease)
                                            assertSame(originalBudget, ticket.call.budget)
                                            assertSame(originalBudget, frame.admittedBudget)
                                            assertTrue(ticket.call.creatorGuardEnded(ticket))
                                            assertNull(PersistenceJdbcDispatch.current())
                                            assertFalse(lease.state.context.hasCurrentFrame())
                                            assertEquals(1L, creatorTailCount(lease))
                                            diagnostics.reached(CreatorTailStage.TAIL_WITNESSED)
                                            clock.advance(2_000) // MODEL clock only; never replace/extend the original allowance.
                                            assertEquals(0L, persistenceFactoryRemainingMillis(originalBudget))
                                            diagnostics.reached(CreatorTailStage.MODEL_EXPIRED)
                                            entry.jdbc.requestRetirement(lease.state.epoch)
                                            awaitLifecycleFact { !entry.jdbc.permitsCleanup(lease.state.epoch) && entry.terminalWork != null }
                                            diagnostics.reached(CreatorTailStage.RETIREMENT_OBSERVED)
                                            org.junit.jupiter.api.Assertions.assertThrows(SQLException::class.java) {
                                                lease.enterDispatch()
                                            }
                                            assertFalse(f.ownedPool.lifecycle.isAuthenticPoolCaller())
                                            assertFalse(lease.completion.quiescent())
                                            assertEquals(1, f.admission.activeOwners())
                                            // Rights only, not the first-close-worker oracle: retain the admitted tail's authority.
                                            val actor = requireNotNull(
                                                hikari.threadFactory.newThread {
                                                    try {
                                                        assertTrue(PoolActorCustody.currentThreadOwnsActorFrame())
                                                        assertTrue(f.ownedPool.lifecycle.isAuthenticPoolCaller())
                                                        actorRan.set(true)
                                                    } catch (failure: Throwable) {
                                                        actorFailure.set(failure)
                                                        diagnostics.retain(failure)
                                                        throw failure
                                                    }
                                                },
                                            )
                                            diagnostics.reached(CreatorTailStage.ACTOR_CONSTRUCTED)
                                            actor.start()
                                            diagnostics.reached(CreatorTailStage.ACTOR_STARTED)
                                            awaitLifecycleFact { actor.state === Thread.State.TERMINATED && !actor.isAlive }
                                            diagnostics.reached(CreatorTailStage.ACTOR_TERMINATED)
                                            actorFailure.get()?.let { throw it }
                                            assertTrue(actorRan.get())
                                            assertNull(f.ownedPool.lifecycle.actorSnapshot().firstFailure)
                                            assertFalse(requireNotNull(entry.terminalWork).producerDrainProven())
                                            witnessed = true
                                            diagnostics.reached(CreatorTailStage.WITNESS_COMPLETED)
                                        }
                                    }
                                    diagnostics.reached(CreatorTailStage.CLOCK_ARMED)
                                    result
                                }
                            }
                            try {
                                diagnostics.capture { field.set(handle, shim) }
                                diagnostics.expectExpiry { connection.getClientInfo("ApplicationName") }
                            } finally {
                                diagnostics.restore {
                                    clock.nextSample = null
                                    if (field.get(handle) === shim) field.set(handle, lower)
                                }
                            }
                        }
                    }
                    val failure = assertThrows<PersistencePhaseException> { f.newExecutor(store).cleanupSourceGrants() }
                    diagnostics.phaseFailed(failure)
                    awaitLifecycleFact {
                        runCatching {
                            requireConnectionFree()
                            true
                        }.getOrDefault(false)
                    }
                    diagnostics.throwUnexpected()
                    assertTrue(witnessed, "MODEL callback witness missing: ${diagnostics.describe()}")
                    assertTrue(actorRan.get(), "MODEL authentic actor did not run: ${diagnostics.describe()}")
                    assertTrue(requireNotNull(selected).state.epoch.outerTailsEnded())
                    assertTrue(requireNotNull(selected).completion.quiescent())
                    assertEquals(0, f.admission.activeOwners())
                    assertTrue(diagnostics.expectedExpiryObserved(), diagnostics.describe())
                    assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, failure.code, diagnostics.describe())
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = PgLifecycleDatabaseMode::class,
        names = [
            "POOL_LEASE_CREATOR_ENTRY_BEFORE_TL", "POOL_LEASE_CREATOR_ENTRY_AFTER_TL",
            "POOL_LEASE_CREATOR_END_BEFORE_TL", "POOL_LEASE_CREATOR_END_AFTER_TL",
            "POOL_LEASE_CREATOR_CORE_BEFORE_TL", "POOL_LEASE_CREATOR_CORE_AFTER_TL",
            "POOL_LEASE_CREATOR_CLIENT_INFO_TAIL", "POOL_LEASE_CREATOR_DECLARED_TAIL",
        ],
    )
    fun `actual creator publication restoration and outer adapter faults retain exact obligations through process only exit`(mode: PgLifecycleDatabaseMode) {
        val server = database.value
        check(server.host == "localhost" || server.host == "127.0.0.1") {
            "The existing sanitized child lane requires its documented local database port."
        }
        val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY, mode)
        check(case.poolCreatorFailure && case.poolPendingFailure && case.attempts == 1 && !case.succeeds)
        val directory = pgLifecycleDatabasePrivateDirectory(pendingProbeRoot.resolve(UUID.randomUUID().toString()))
        val child = PgLifecycleDatabaseProbeProcess(directory, case)
        child.use {
            child.start(server.port)
            child.awaitPendingPoolExit()
            val failure = assertThrows<IllegalStateException> { child.awaitVerified() }
            assertEquals("Synthetic database child rejected its scenario.", failure.message)
        }
        val cleanup = requireNotNull(child.cleanupObservation)
        check(cleanup.contains("alive=false forced=false reader_joined=true streams_closed=true"))
        check(cleanup.contains("output_overflow=false output_read_failed=false output_eof=true reader_alive=false reader_state=TERMINATED"))
        println("PG_POOL_PENDING_PROCESS_OBSERVED ${case.label} nonce=${child.nonce} cleanup=PROCESS_ONLY product_end=false")
    }
}

private enum class CreatorTailStage {
    STORE_ENTERED,
    LEASE_OBTAINED,
    SETUP_OBTAINED,
    LOWER_RETURNED,
    CLOCK_ARMED,
    CLOCK_ENTERED,
    TAIL_WITNESSED,
    MODEL_EXPIRED,
    RETIREMENT_OBSERVED,
    ACTOR_CONSTRUCTED,
    ACTOR_STARTED,
    ACTOR_TERMINATED,
    WITNESS_COMPLETED,
}

/** Facts and original failures only: never admission authority, a budget adjustment or a synthetic completion. */
private class CreatorTailDiagnostics(private val actorRan: AtomicBoolean) {
    private val stages = linkedSetOf<CreatorTailStage>() // Caller-only; the actor publishes only its existing atomic facts/failure.
    private val unexpected = AtomicReference<Throwable?>()
    private var callFailure: Throwable? = null
    private var expectedExpiry: Throwable? = null
    private var callReturned = false
    private var phaseFailure: PersistencePhaseException? = null

    fun reached(stage: CreatorTailStage) {
        stages.add(stage)
    }

    fun retain(failure: Throwable) {
        unexpected.compareAndSet(null, failure)
        preserveLater(requireNotNull(unexpected.get()), failure)
    }

    fun <T> capture(action: () -> T): T = try {
        action()
    } catch (failure: Throwable) {
        if (failure !== expectedExpiry) retain(failure)
        throw failure
    }

    fun expectExpiry(call: () -> Unit): Nothing {
        try {
            call()
        } catch (failure: Throwable) {
            callFailure = failure
            // The real afterJdbcCall -> requireWork path throws this bounded phase exception directly.
            // An early refusal or a shim/callback assertion is not the intended post-witness expiry.
            if (
                CreatorTailStage.WITNESS_COMPLETED in stages && failure is PersistencePhaseException &&
                failure.code === PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED
            ) {
                expectedExpiry = failure
            } else {
                retain(failure)
            }
            throw failure
        }
        callReturned = true
        val failure = IllegalStateException("Expired actual afterJdbcCall must refuse.")
        retain(failure)
        throw failure
    }

    fun restore(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            retain(failure)
            throw failure
        }
    }

    fun phaseFailed(failure: PersistencePhaseException) {
        phaseFailure = failure
    }

    fun expectedExpiryObserved(): Boolean = expectedExpiry != null

    fun throwUnexpected() {
        unexpected.get()?.let { throw it }
    }

    fun withFixture(action: () -> Unit) {
        var escaped: Throwable? = null
        try {
            action()
        } catch (failure: Throwable) {
            escaped = failure
        }
        // Kept outside withOrdinarySourceGrantCleanup: its emf.destroy finally cannot replace the retained first failure.
        val first = unexpected.get() ?: escaped
        if (first != null && escaped != null) preserveLater(first, escaped)
        try {
            println(describe())
        } catch (failure: Throwable) {
            if (first == null) throw failure
            preserveLater(first, failure)
        }
        if (first != null) throw first
    }

    fun describe(): String {
        val callPhase = callFailure as? PersistencePhaseException
        return "PG_POOL_CREATOR_MODEL stages=${stages.joinToString(",")} actor_ran=${actorRan.get()} " +
            "call_returned=$callReturned call_failure=${callFailure?.javaClass?.simpleName} expected_expiry=${expectedExpiry != null} " +
            "call_code=${callPhase?.code} call_database=${callPhase?.databaseOutcome} call_cleanup=${callPhase?.cleanupProven} " +
            "phase_code=${phaseFailure?.code} phase_database=${phaseFailure?.databaseOutcome} phase_cleanup=${phaseFailure?.cleanupProven} " +
            "unexpected=${unexpected.get()?.javaClass?.simpleName}"
    }

    private fun preserveLater(first: Throwable, later: Throwable) {
        if (first !== later && first.suppressed.none { it === later }) first.addSuppressed(later)
    }
}

/** The existing phase clock input is a MODEL seam; no product budget or receipt field is written. */
private class CreatorTailClock : PersistenceNanoClock {
    private val caller = Thread.currentThread()
    private val offset = AtomicLong()
    var nextSample: (() -> Unit)? = null

    override fun nanoTime(): Long {
        if (Thread.currentThread() === caller) {
            val selected = nextSample
            nextSample = null
            selected?.invoke()
        }
        return System.nanoTime() + offset.get()
    }

    fun advance(millis: Long) {
        offset.addAndGet(millis * 1_000_000)
    }
}

/** Real already-acquired upper guard protocol only; no fixture installation of a token, epoch or count. */
private fun withCreatorCall(lease: PersistenceJdbcLease, enterCreator: Boolean = true, body: (ActualCreatorCall) -> Unit) {
    val dispatch = lease.enterDispatch()
    val call = try {
        lease.state.context.enter(lease.identity, PersistenceJdbcGuardCallKind.BUSINESS)
    } catch (failure: Throwable) {
        dispatch.end()
        throw failure
    }
    val owned = ActualCreatorCall(lease, dispatch, call)
    try {
        if (enterCreator) owned.enterCreator()
        body(owned)
    } finally {
        owned.close()
    }
}

private class ActualCreatorCall(private val lease: PersistenceJdbcLease, val dispatch: PersistenceJdbcDispatch.Frame, val call: PersistenceJdbcGuardCall) :
    AutoCloseable {
    private var retained: PoolLifecycle.LeaseDispatchCreator? = null
    private var guardEnded = false
    val creator: PoolLifecycle.LeaseDispatchCreator get() = requireNotNull(retained)

    fun enterCreator() {
        check(retained == null)
        val ticket = requireNotNull(lease.prepareCreator(dispatch, call))
        retained = ticket
        check(ticket.enter())
    }

    fun finishGuard() {
        check(!guardEnded)
        call.finishAfterDispatch(dispatch)
        guardEnded = true
    }

    override fun close() {
        try {
            if (!guardEnded) finishGuard()
        } finally {
            retained?.let { if (!it.actualEnded()) check(it.end()) }
        }
    }
}

/** Intercept only one exact pool-TL removal. All real values stay in the original ThreadLocal. */
private class CreatorOuterEndBarrier(private val lease: PersistenceJdbcLease, private val kind: PersistenceJdbcGuardCallKind, private val held: () -> Unit) :
    ThreadLocal<PoolCallFrame?>(),
    AutoCloseable {
    private val caller = Thread.currentThread()
    private val storage = requireNotNull(ownedCutField(PoolCallFrames, "storage"))
    private val field = creatorField(storage, "current")

    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(storage) as ThreadLocal<PoolCallFrame?>
    var holds = 0
        private set

    init {
        check(delegate.get() == null)
        field.set(storage, this)
    }

    override fun get(): PoolCallFrame? = delegate.get()
    override fun set(value: PoolCallFrame?) = delegate.set(value)

    override fun remove() {
        val frame = delegate.get()
        if (Thread.currentThread() === caller && frame?.kind === PoolCallKind.LEASE_DISPATCH && holds == 0) {
            val ticket = ownedCutField(frame, "leaseCreator") as PoolLifecycle.LeaseDispatchCreator
            if (ticket.lease === lease && ticket.dispatch.kind === kind) {
                holds++
                check(ownedCutField(frame, "phase").toString() == "ENDING")
                check(ticket.dispatch.actualEnded() && ticket.call.creatorGuardEnded(ticket))
                check(PersistenceJdbcDispatch.current() == null && !lease.state.context.hasCurrentFrame())
                check(!lease.state.epoch.foregroundActive() && lease.state.epoch.activeCancellations() == 0L)
                check(!lease.state.epoch.outerTailsEnded() && !ticket.actualEnded())
                val stack = Thread.currentThread().stackTrace
                check(stack.any { it.methodName == "invoke" || it.methodName == "invokeClosed" })
                check(stack.none { it.methodName == "invokeConnection" || it.methodName == "invokeGuarded" })
                held()
            }
        }
        delegate.remove()
    }

    override fun close() {
        check(field.get(storage) === this)
        field.set(storage, delegate)
        check(delegate.get() == null) // Restore the field only, never repair an unresolved frame.
    }
}

internal fun creatorEpochState(lease: PersistenceJdbcLease): Any = requireNotNull((ownedCutField(lease.state.epoch, "state") as AtomicReference<*>).get())

internal fun creatorTailCount(lease: PersistenceJdbcLease): Long = ownedCutField(creatorEpochState(lease), "outerTails") as Long

/** Existing assertion reflection style, including inherited Hikari delegate fields. Never used for product receipt/count writes. */
internal fun creatorField(owner: Any, name: String): Field {
    var type: Class<*>? = owner.javaClass
    while (type != null) {
        val field = type.declaredFields.singleOrNull { it.name == name }
        if (field != null) return field.apply { check(trySetAccessible()) }
        type = type.superclass
    }
    error("Required creator fixture field is missing: $name")
}
