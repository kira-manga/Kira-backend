package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.DESIRED_OPERATOR_TEST_PASSWORD
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.ProcessBoundCatalogGenesisFixture
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintSignedGenesisFirstDPhaseExecutor
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** Reuses the existing SAME_THREAD TLS/role/counter fixture; no bootstrap, D reset, synthetic install or new DB harness. */
internal fun ComplaintDesiredInstallationFixture.firstDGenesis(): ProcessBoundCatalogGenesisFixture {
    assertEquals(0L, observer.queryForObject("SELECT count(*) FROM public.complaint_catalog_mutations", Long::class.java))
    assertEquals(
        true,
        observer.queryForObject(
            "SELECT desired_configuration_hash IS NULL FROM public.complaint_journal_control WHERE data_scope_id = ?",
            Boolean::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )
    val before = control()
    val inputs = ComplaintDesiredDeploymentInputsV1.fromDecoded(document)
    tls.start()
    assertEquals(PersistenceLifecycleObservation.READY, tls.pools.catalogCoordinator.prepare())
    val consumers = BoundComplaintConsumerFixture().configuration()
    val process = VersionBoundComplaintProcessConfiguration.fromRetained(
        consumers,
        tls.pools,
        1,
        1,
        inputs.databaseIdentity,
        inputs.restoreIdentity,
        inputs.catalog,
    )
    assertEquals(inputs.journal.sha256, process.consumers.journalConfiguration.sha256)
    val capacity = inputs.capacity
    for (counter in ComplaintCapacityEncoding.lockOrder()) {
        assertEquals(
            1,
            observer.update(
                "UPDATE public.complaint_capacity_counters SET configuration_hash = ?, configuration_closed = false, hard_limit = ?, " +
                    "creation_limit = ?, free_units = ?, actual_units = 0, recovery_reserved_units = 0, test_reserved_units = 0 WHERE name = ?",
                capacity.digestBytes(),
                capacity.hardLimit[counter],
                capacity.creationLimit[counter],
                capacity.hardLimit[counter],
                counter.storedName,
            ),
        )
    }
    assertEquals(before, control(), "Only synthetic capacity setup occurs; initial control and NULL D have not been written.")
    // This wrapper owns no new resources. The outer fixture already owns exact control/counter restoration and this sole G1 token.
    return ProcessBoundCatalogGenesisFixture(process, observer)
}

internal fun ProcessBoundCatalogGenesisFixture.firstDPrepare(): UnverifiedGenesisPreparation = executor.prepareGenesis(
    VersionBoundCatalogReadbackTestFixture.manifestBytes(),
    initial,
    current,
    policy.chain,
    process.consumers.capacityPolicy.digestBytes(),
)

internal fun ProcessBoundCatalogGenesisFixture.firstDSign(prepared: UnverifiedGenesisPreparation): UnverifiedGenesisPreparation =
    executor.persistGenesisSignature(
        prepared,
        VersionBoundCatalogReadbackTestFixture.manifestBytes(),
        initial,
        current,
        policy.chain,
        process.consumers.capacityPolicy.digestBytes(),
        Base64.getDecoder().decode(VersionBoundCatalogReadbackTestFixture.envelope().signatures.single().signatureBase64),
    )

internal fun firstDRelease(): ComplaintSignedGenesisFirstDInputsV1 = ComplaintSignedGenesisFirstDInputsV1.fromRaw(
    VersionBoundCatalogReadbackTestFixture.manifestBytes(),
    VersionBoundCatalogReadbackTestFixture.initialBundleBytes(),
    VersionBoundCatalogReadbackTestFixture.currentBundleBytes(),
    VersionBoundCatalogReadbackTestFixture.genesisBytes(),
    VersionBoundCatalogReadbackTestFixture.EXPECTED_GENESIS_SHA256,
)

internal fun ComplaintDesiredInstallationFixture.firstDInvocation(
    selected: ComplaintDesiredDeploymentDocumentV1 = document,
    release: ComplaintSignedGenesisFirstDInputsV1 = firstDRelease(),
    beforeClose: () -> Unit = { stopRuntimeWithoutWaiting() },
    configure: (SignedGenesisFirstDProbeJdbc) -> Unit = {},
): SignedGenesisFirstDInvocation = SignedGenesisFirstDInvocation(
    ComplaintDesiredDeploymentInputsV1.fromDecoded(selected),
    release,
    beforeClose,
    configure,
).also { synchronized(firstDInvocations) { firstDInvocations.add(it) } }

/** Original production owner with actual SDK acquisition/assembly/phase. Test substitutions are raw HTTP, clock observation and JdbcTemplate only. */
internal class SignedGenesisFirstDInvocation(
    val inputs: ComplaintDesiredDeploymentInputsV1,
    private val release: ComplaintSignedGenesisFirstDInputsV1,
    private val beforeClose: () -> Unit,
    private val configure: (SignedGenesisFirstDProbeJdbc) -> Unit,
) {
    private val caller = Thread.currentThread()
    val http = AwsSecretVersionFixture()
    val clock = DesiredInstallationTestClock()
    val operator = ComplaintDesiredInstallationOperatorV1.withSecretHttpFixture(http::httpClient, clock)
    val assembly: ComplaintDesiredProcessAssemblyV1 = poolTestField(operator, "assembly")
    var target: VersionBoundComplaintProcessConfiguration? = null
        private set
    var probe: SignedGenesisFirstDProbeJdbc? = null
        private set
    var beforePhase: () -> Unit = {}
    private var phaseObserved = false
    private var closeObserved = false
    private var observing = false
    var cleanupVerified = false
        private set

    init {
        val secrets = DesiredInstallationInputFixture.acquired(
            inputs,
            PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray(),
            DESIRED_OPERATOR_TEST_PASSWORD.toByteArray(),
        )
        http.respond = { request ->
            val fields = request.fields()
            assertEquals(setOf("SecretId", "VersionId"), fields.keys)
            val acquired = secrets.single { it.descriptor.version.resourceArn == fields["SecretId"] && it.descriptor.version.versionId == fields["VersionId"] }
            requireConnectionFree()
            acquired.useMaterial { AwsSecretVersionFixture.reply(acquired.descriptor.version, it) }
        }
        clock.onSample = {
            if (!observing && caller === Thread.currentThread()) {
                observing = true
                try {
                    observeAssembly()
                    val firstPhasePending = !phaseObserved && !closeObserved
                    if (firstPhasePending && ownedCutField(operator, "firstDAttempt") != null && PersistencePhaseOwnership.current() == null) {
                        phaseObserved = true
                        requireConnectionFree()
                        beforePhase() // The real operator pool is prepared, but no phase/permit/work cap has started yet.
                    }
                } finally {
                    observing = false
                }
            }
        }
    }

    fun execute(): ComplaintSignedGenesisFirstDResultV1 = try {
        operator.selectSignedGenesisFirst(
            inputs,
            release,
            AwsSecretVersionFixture.CREDENTIALS,
            if (inputs.sealerMapping ==
                null
            ) {
                null
            } else {
                AwsSecretVersionFixture.CREDENTIALS
            },
        )
            .also { cleanupVerified = true }
    } finally {
        assertNoLostAssertions()
        assertEquals(http.createdClients, http.closedClients)
    }

    private fun observeAssembly() {
        if (poolTestField<Boolean>(assembly, "stopping")) {
            if (!closeObserved) {
                closeObserved = true
                beforeClose()
            }
            return
        }
        if (probe != null) return
        val owner = ownedCutField(assembly, "operatorOwner") as? PersistenceJdbcLifecycleOwner ?: return
        val coordinator = ownedCutField(owner, "catalogResources") as? CatalogCoordinatorPersistence ?: return
        if (ownedCutField(coordinator, "firstDesiredExecutor") == null) return
        target = assembly.target
        val observed = SignedGenesisFirstDProbeJdbc(coordinator)
        configure(observed)
        coordinator.javaClass.getDeclaredField("firstDesiredExecutor").also { it.isAccessible = true }
            .set(coordinator, ComplaintSignedGenesisFirstDPhaseExecutor(coordinator, observed))
        probe = observed // Same concrete executor, same actual operator coordinator/manager/ownership; no fabricated attempt.
        assertNotSame(checkNotNull(target).pools.catalogCoordinator, coordinator)
    }

    fun fixtureCleanup() {
        if (cleanupVerified) {
            assertNoLostAssertions()
            return
        }
        assertSame(caller, Thread.currentThread(), "Only the original caller may retire an unverified first-D owner.")
        clock.onSample = {}
        runCatching(operator::close)
        beforeClose()
        requireConnectionFree()
        val owners = listOf("targetOwner", "operatorOwner").mapNotNull { ownedCutField(assembly, it) as? PersistenceJdbcLifecycleOwner }
        owners.forEach { it.requestShutdown() }
        owners.forEach { it.versionBoundPools?.close() }
        owners.forEach {
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, it.observeShutdown())
            assertEquals(PersistencePublicTrustRelease.RELEASED, it.releasePublicTrustAfterShutdown())
        }
        cleanupVerified = true // Fixture retirement, never resurrection of the failed command or its historical result.
        assertNoLostAssertions()
    }

    private fun assertNoLostAssertions() {
        val problems = listOf(runCatching(clock::assertNoLostAssertions), runCatching { probe?.assertNoLostAssertions() }).mapNotNull { it.exceptionOrNull() }
        problems.firstOrNull()?.let { first ->
            problems.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
            throw first
        }
    }
}

internal enum class SignedGenesisFirstDSqlStep { AUTHENTICATE, LIVE, CATALOG, HISTORY, INITIAL, WRITE_D, REREAD }

/** Actual holder/login/RC/fence observations around ONLY the fixed first-D SQL; no statement or returned value is replaced. */
internal class SignedGenesisFirstDProbeJdbc(val coordinator: CatalogCoordinatorPersistence) : JdbcTemplate(coordinator.dataSource) {
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val steps = mutableListOf<SignedGenesisFirstDSqlStep>()
    var beforeSql: (SignedGenesisFirstDSqlStep) -> Unit = {}
    var afterSql: (SignedGenesisFirstDSqlStep) -> Unit = {}
    private val assertionFailure = AtomicReference<AssertionError?>()
    val phase: PersistencePhaseContext get() = checkNotNull(PersistencePhaseOwnership.current())
    val holder: ConnectionHolder get() = TransactionSynchronizationManager.getResource(coordinator.dataSource) as ConnectionHolder

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = observe(sql, emptyArray()) { super.query(sql, rowMapper) }
    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = observe(sql, args) { super.query(sql, rowMapper, *args) }

    fun retainedOperation(): ComplaintSignedGenesisFirstDOperationV1 =
        ownedCutField(phase.signedGenesisFirstDesired, "retained") as ComplaintSignedGenesisFirstDOperationV1
    fun assertNoLostAssertions() {
        assertionFailure.get()?.let { throw it }
    }

    fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertionFailure.compareAndSet(null, failure)
        throw failure
    }

    private fun <T> observe(sql: String, arguments: Array<out Any?>, action: () -> T): T = preserveAssertions {
        val current = phase
        assertSame(coordinator.dataSource, dataSource)
        assertEquals(PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST, poolTestField(current, "path"))
        assertEquals(setOf(coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(1, coordinator.activeSnapshotOwners())
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, holder.connection.transactionIsolation)
        assertFalse(holder.connection.isReadOnly)
        assertEquals(sql.count { it == '?' }, arguments.size)
        if (current !in observations) {
            val identity = holder.connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT pg_backend_pid(), txid_current(), session_user, current_user, " +
                        "(SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid())",
                ).use { row ->
                    assertTrue(row.next())
                    assertEquals(ComplaintDesiredInstallationFixture.OPERATOR, row.getString(3))
                    assertEquals(ComplaintDesiredInstallationFixture.OPERATOR, row.getString(4))
                    assertTrue(row.getBoolean(5) && !row.wasNull())
                    (row.getInt(1) to row.getLong(2)).also { assertFalse(row.next()) }
                }
            }
            observations[current] = StepUpPhaseObservation(current, ownedPoolLease(holder.connection), identity)
            assertAdvisory("complaint-journal-epoch", "ShareLock")
        }
        val step = when (sql) {
            AUTHENTICATE_DESIRED_OPERATOR_V1 -> SignedGenesisFirstDSqlStep.AUTHENTICATE
            LOCK_DESIRED_CONTROL_V1 -> SignedGenesisFirstDSqlStep.LIVE
            TRY_CATALOG_LOCK -> SignedGenesisFirstDSqlStep.CATALOG
            READ_SIGNED_GENESIS_FIRST_HISTORY_V1 -> SignedGenesisFirstDSqlStep.HISTORY
            READ_SIGNED_GENESIS_FIRST_OTHER_STATE_V1 -> SignedGenesisFirstDSqlStep.INITIAL
            SELECT_SIGNED_GENESIS_FIRST_D_V1 -> SignedGenesisFirstDSqlStep.WRITE_D
            READ_DESIRED_CONTROL_V1 -> SignedGenesisFirstDSqlStep.REREAD
            else -> error("Unexpected first-D SQL.")
        }
        steps.add(step)
        if (step === SignedGenesisFirstDSqlStep.HISTORY) assertAdvisory("complaint-catalog-mutation", "ExclusiveLock")
        beforeSql(step)
        action().also { afterSql(step) }
    }

    private fun assertAdvisory(key: String, mode: String) {
        holder.connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = ? AND granted " +
                "AND classid = ((hashtextextended(?, 0) >> 32) & 4294967295)::oid AND objid = (hashtextextended(?, 0) & 4294967295)::oid AND objsubid = 1)",
        ).use { statement ->
            statement.setString(1, mode)
            statement.setString(2, key)
            statement.setString(3, key)
            statement.executeQuery().use { row ->
                assertTrue(row.next() && row.getBoolean(1) && !row.wasNull(), "The actual fixed transaction advisory lock must already be held.")
                assertFalse(row.next())
            }
        }
    }
}
