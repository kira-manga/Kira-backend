package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.DESIRED_OPERATOR_TEST_PASSWORD
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.SyntheticComplaintCounters
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.CurrentAcceptedCatalogRefreshHttpFixture
import me.manga.kira.backend.complaint.catalog.ProcessBoundCatalogGenesisFixture
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintDesiredInstallPhaseExecutor
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Same SAME_THREAD TLS server, observer and original roots. Role/DDL/P setup below is synthetic fixture work, never a deployment action. */
internal fun withDesiredInstallation(tls: VersionBoundPersistenceConnectedFixture, test: (ComplaintDesiredInstallationFixture) -> Unit) =
    ComplaintDesiredInstallationFixture(tls).use { fixture ->
        fixture.prepare()
        test(fixture)
    }

internal class ComplaintDesiredInstallationFixture(val tls: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    val observer = JdbcTemplate(ordinaryCleanupReader(tls.database)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
    private val trustParent = tls.database.versionBoundTls().publicTrustParent()
    private var parentCreated = false
    private var roleCreated = false
    private var originalControl: String? = null
    private var counters: SyntheticComplaintCounters? = null
    val invocations = mutableListOf<DesiredInstallationInvocation>()
    lateinit var document: ComplaintDesiredDeploymentDocumentV1
        private set

    fun prepare() {
        Flyway.configure().dataSource(checkNotNull(observer.dataSource)).locations("classpath:db/migration").load().migrate()
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM public.complaint_catalog_mutations", Long::class.java))
        originalControl = control()
        counters = SyntheticComplaintCounters(observer, Instant.parse("2026-09-17T00:00:00Z"))
        Files.createDirectory(trustParent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        parentCreated = true
        val base = DesiredInstallationInputFixture.document()
        val runtime = tls.acquired.descriptor
        document = base.copy(
            database = base.database.copy(
                host = tls.database.host,
                port = tls.database.port,
                name = PgLifecycleDatabaseSettings.DATABASE,
                runtimeUsername = PgLifecycleDatabaseSettings.CANDIDATE,
                runtimePassword = DesiredSecretReferenceV1(runtime.logicalKeyId, runtime.version.resourceArn, runtime.version.versionId),
                ordinaryCapacity = 2,
                publicTrustPemBase64 = Base64.getEncoder().encodeToString(tls.database.versionBoundTls().publicTrust()),
                protectedTrustParent = trustParent.toString(),
            ),
        )
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Long::class.java, OPERATOR))
        observer.execute("CREATE ROLE $OPERATOR LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '$DESIRED_OPERATOR_TEST_PASSWORD'")
        roleCreated = true
        observer.execute(
            "GRANT USAGE ON SCHEMA public TO $OPERATOR; " +
                "GRANT SELECT ON public.complaint_journal_control, public.complaint_catalog_mutations, public.complaint_test_runs, " +
                "public.complaint_journal_publications, public.complaint_journal_scan_runs TO $OPERATOR; " +
                "GRANT UPDATE ($DESIRED_COLUMNS) ON public.complaint_journal_control TO $OPERATOR",
        )
        // Existing enrollment/G1 fixtures normally grant table UPDATE. Restrict this test runtime principal
        // without giving the operator catalog/history/identity writers or membership of that runtime role.
        val candidate = PgLifecycleDatabaseSettings.CANDIDATE
        observer.execute(
            "GRANT USAGE ON SCHEMA public TO $candidate; " +
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $candidate; " +
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO $candidate; " +
                "REVOKE UPDATE ON public.complaint_journal_control FROM $candidate",
        )
        val runtimeColumns = observer.queryForList(
            "SELECT attname FROM pg_attribute WHERE attrelid = 'public.complaint_journal_control'::regclass " +
                "AND attnum > 0 AND NOT attisdropped AND attname NOT IN ('desired_configuration_hash', 'desired_generation') ORDER BY attnum",
            String::class.java,
        )
        observer.execute("GRANT UPDATE (${runtimeColumns.joinToString(",")}) ON public.complaint_journal_control TO $candidate")
    }

    fun invocation(
        selected: ComplaintDesiredDeploymentDocumentV1 = document,
        stopRuntimeAtClose: Boolean = false,
        configure: (DesiredInstallationProbeJdbc) -> Unit = {},
    ): DesiredInstallationInvocation = DesiredInstallationInvocation(
        ComplaintDesiredDeploymentInputsV1.fromDecoded(selected),
        if (stopRuntimeAtClose) ({ stopRuntimeWithoutWaiting() }) else ({}),
        configure,
    ).also(invocations::add)

    fun control(): String = checkNotNull(
        observer.queryForObject(
            "SELECT to_jsonb(c)::text FROM public.complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    fun restoreControl(row: String) = independentTransaction { connection, selected ->
        assertEquals(1, selected.update("DELETE FROM public.complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
        assertEquals(
            1,
            selected.update(
                "INSERT INTO public.complaint_journal_control SELECT (jsonb_populate_record(NULL::public.complaint_journal_control, ?::jsonb)).*",
                row,
            ),
        )
        connection.commit()
    }

    fun unrelatedControl(): String = checkNotNull(
        observer.queryForObject(
            "SELECT (to_jsonb(c) - ARRAY['desired_generation','desired_configuration_hash','maintenance_closed','creation_closed'," +
                "'scan_requested','lease_owner','lease_token','lease_expires_at','updated_at'])::text " +
                "FROM public.complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    fun historyAndCounters(): List<String> =
        observer.queryForList("SELECT to_jsonb(m)::text FROM public.complaint_catalog_mutations m ORDER BY operation_token", String::class.java) +
            observer.queryForList("SELECT to_jsonb(c)::text FROM public.complaint_capacity_counters c ORDER BY name", String::class.java)

    fun <T> independentTransaction(work: (Connection, JdbcTemplate) -> T): T = checkNotNull(observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        val selected = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
        try {
            work(connection, selected)
        } finally {
            connection.rollback()
        }
    }

    /** Install D through the real operator, then let the genuine signed G1/SDK factory own identity/head projection. No seeded installed flag. */
    // After restoring the original executor/callback, surface a retained callback assertion instead of its sanitized phase/provider wrapper.
    @Suppress("ThrowingExceptionFromFinally")
    fun genuineGenesis(
        beforeStage: (ProcessBoundCatalogGenesisFixture) -> Unit = {},
        afterSigned: (ProcessBoundCatalogGenesisFixture) -> Unit = {},
        test: (ProcessBoundCatalogGenesisFixture, CurrentAcceptedCatalogRefreshV1.Result) -> Unit,
    ) {
        val installed = invocation()
        assertEquals(ComplaintDesiredInstallationTransitionV1.BOOTSTRAPPED, installed.execute().transition)
        assertScanRequested()
        val target = checkNotNull(installed.target)
        tls.start()
        assertEquals(PersistenceLifecycleObservation.READY, tls.pools.catalogCoordinator.prepare())
        val process = VersionBoundComplaintProcessConfiguration.fromRetained(
            target.consumers,
            tls.pools,
            1,
            1,
            UUID.fromString(document.databaseIdentity),
            UUID.fromString(document.restoreIdentity),
            target.catalogReadback,
        )
        assertArrayEquals(target.configurationHashBytes(), process.configurationHashBytes())
        val capacity = process.consumers.capacityPolicy
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
        ProcessBoundCatalogGenesisFixture(process, observer).use { genesis ->
            beforeStage(genesis)
            val scanPhases = linkedSetOf<PersistencePhasePath>()
            val assertions = AtomicReference<AssertionError?>()
            genesis.jdbc.afterSql = {
                try {
                    val phase = checkNotNull(PersistencePhaseOwnership.current())
                    scanPhases.add(poolTestField(phase, "path"))
                    assertScanRequested() // Read on this exact original Spring holder, not a foreign pooled connection.
                } catch (failure: AssertionError) {
                    assertions.compareAndSet(null, failure)
                    throw failure
                }
            }
            // Same concrete production executor and original coordinator/manager/ownership. Only JDBC observation changes;
            // the genuine SDK owner still performs complete/project and creates its own historical result.
            val original = genesis.coordinator.genesis
            val field = genesis.coordinator.javaClass.getDeclaredField("genesisExecutor").also { it.isAccessible = true }
            field.set(genesis.coordinator, genesis.executor)
            try {
                genesis.stageSigned()
                assertScanRequested()
                afterSigned(genesis) // Connection-free view of actual PREPARED+signed history, never a supplied mutation receipt.
                val wire = CurrentAcceptedCatalogRefreshHttpFixture(genesis)
                wire.afterHttpClose = { assertScanRequested() }
                val refresh = wire.owner().use { it.refresh() }
                wire.assertFullReadback()
                assertScanRequested()
                assertEquals(
                    setOf(
                        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
                        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
                        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
                        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
                    ),
                    scanPhases,
                )
                genesis.jdbc.afterSql = null
                test(genesis, refresh)
            } finally {
                field.set(genesis.coordinator, original)
                genesis.jdbc.afterSql = null
                assertions.get()?.let { throw it }
            }
        }
    }

    fun assertScanRequested() {
        val holder = TransactionSynchronizationManager.getResource(tls.pools.catalogCoordinator.dataSource) as? ConnectionHolder
        if (holder == null) {
            requireConnectionFree()
            assertEquals(
                true,
                observer.queryForObject(
                    "SELECT scan_requested FROM public.complaint_journal_control WHERE data_scope_id = ?",
                    Boolean::class.java,
                    ComplaintDataScope.LIVE.id,
                ),
            )
        } else {
            holder.connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT scan_requested FROM public.complaint_journal_control WHERE data_scope_id = '00000000-0000-0000-0000-000000000000'",
                ).use { row ->
                    assertTrue(row.next() && row.getBoolean(1) && !row.wasNull())
                    assertFalse(row.next())
                }
            }
        }
    }

    /** Stop both kinds of peer actors before the command's shared Timer wait; this is not an operator success receipt. */
    fun stopRuntimeWithoutWaiting() = tls.stopWithoutWaiting()

    override fun close() {
        val stopped = runCatching(::stopRuntimeWithoutWaiting) // Every original peer stops before any installer's shared-Timer proof.
        val retired = invocations.map { runCatching { it.fixtureCleanup() } }
        val restorationReady = runCatching {
            stopped.getOrThrow()
            requireConnectionFree()
            assertTrue(invocations.all { it.cleanupVerified }, "An original installer invocation has not completed fixture retirement.")
        }
        // A preserved test assertion must not strand owned fixture rows; actual unproven retirement still forbids restoration.
        val restored = listOf(
            afterRetirement(restorationReady) { originalControl?.let(::restoreControl) },
            afterRetirement(restorationReady) {
                if (originalControl != null) {
                    observer.update(
                        "DELETE FROM public.complaint_catalog_mutations WHERE operation_token = ?",
                        UUID.fromString(VersionBoundCatalogReadbackTestFixture.envelope().manifest.operationToken),
                    )
                }
            },
            afterRetirement(restorationReady) { counters?.close() },
            afterRetirement(restorationReady) {
                if (roleCreated) observer.execute("DROP OWNED BY $OPERATOR; DROP ROLE $OPERATOR")
            },
            afterRetirement(restorationReady) {
                if (roleCreated) observer.execute("GRANT UPDATE ON public.complaint_journal_control TO ${PgLifecycleDatabaseSettings.CANDIDATE}")
            },
            afterRetirement(restorationReady) {
                if (parentCreated) Files.delete(trustParent) // Never recursively remove a retained trust directory.
            },
            runCatching { requireConnectionFree() },
        )
        rethrowDesiredFixtureFailures(listOf(stopped) + retired + listOf(restorationReady) + restored)
    }

    private fun afterRetirement(ready: Result<Unit>, action: () -> Unit): Result<Unit> = runCatching {
        ready.getOrThrow()
        requireConnectionFree()
        action()
    }

    companion object {
        const val OPERATOR = VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME
        const val DESIRED_COLUMNS = "desired_configuration_hash,desired_generation,maintenance_closed,creation_closed,scan_requested," +
            "lease_owner,lease_token,lease_expires_at,updated_at"
    }
}

/** Only HTTP transport/time and test JDBC observation are substituted. The original one-shot owner creates the exact attempt and runs fixed production SQL. */
internal class DesiredInstallationInvocation(
    val inputs: ComplaintDesiredDeploymentInputsV1,
    private val beforeClose: () -> Unit = {},
    private val configure: (DesiredInstallationProbeJdbc) -> Unit = {},
) {
    private val caller = Thread.currentThread()
    val http = AwsSecretVersionFixture()
    val clock = DesiredInstallationTestClock()
    val operator = ComplaintDesiredInstallationOperatorV1.withSecretHttpFixture(http::httpClient, clock)
    val assembly: ComplaintDesiredProcessAssemblyV1 = poolTestField(operator, "assembly")
    var target: VersionBoundComplaintProcessConfiguration? = null
        private set
    var probe: DesiredInstallationProbeJdbc? = null
        private set
    private var closingObserved = false
    private var betweenObserved = false
    private var clockHookActive = false
    var cleanupVerified = false
        private set
    var betweenPhases: () -> Unit = {}

    init {
        val secrets = DesiredInstallationInputFixture.acquired(
            inputs,
            PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray(),
            DESIRED_OPERATOR_TEST_PASSWORD.toByteArray(),
        )
        http.respond = { request ->
            val fields = request.fields()
            val acquired = secrets.single { it.descriptor.version.resourceArn == fields["SecretId"] && it.descriptor.version.versionId == fields["VersionId"] }
            assertEquals(setOf("SecretId", "VersionId"), fields.keys)
            requireConnectionFree()
            acquired.useMaterial { AwsSecretVersionFixture.reply(acquired.descriptor.version, it) }
        }
        clock.onSample = {
            if (!clockHookActive) {
                clockHookActive = true
                try {
                    observeAssembly()
                    observeBetweenPhases()
                } finally {
                    clockHookActive = false
                }
            }
        }
    }

    fun execute(expectedGeneration: Long? = null): ComplaintDesiredInstallationResultV1 = try {
        val result = if (expectedGeneration == null) {
            operator.bootstrap(inputs, AwsSecretVersionFixture.CREDENTIALS, sealerCredentials())
        } else {
            operator.supersede(inputs, expectedGeneration, AwsSecretVersionFixture.CREDENTIALS, sealerCredentials())
        }
        cleanupVerified = true // The actual one-shot result is returned only after its original cleanup predicate.
        result
    } finally {
        assertNoLostAssertions()
        assertEquals(http.createdClients, http.closedClients)
    }

    private fun sealerCredentials() = if (inputs.sealerMapping == null) null else AwsSecretVersionFixture.CREDENTIALS

    private fun assertNoLostAssertions() = rethrowDesiredFixtureFailures(
        listOf(
            runCatching(clock::assertNoLostAssertions),
            runCatching { probe?.assertNoLostAssertions() },
        ),
    )

    private fun observeBetweenPhases() {
        if (betweenObserved || closingObserved || PersistencePhaseOwnership.current() != null) return
        val attempt = ownedCutField(operator, "attempt") as? ComplaintDesiredInstallAttemptV1 ?: return
        if (attempt.path != PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE) return
        requireConnectionFree()
        betweenObserved = true // This transition exists only after actual first-phase commit AND holder/permit release.
        betweenPhases()
    }

    private fun observeAssembly() {
        if (poolTestField<Boolean>(assembly, "stopping")) {
            if (!closingObserved) {
                closingObserved = true
                beforeClose()
            }
            return
        }
        if (probe != null) return
        val owner = ownedCutField(assembly, "operatorOwner") as? PersistenceJdbcLifecycleOwner ?: return
        val coordinator = ownedCutField(owner, "catalogResources") as? CatalogCoordinatorPersistence ?: return
        if (ownedCutField(coordinator, "desiredExecutor") == null) return
        target = assembly.target
        val observed = DesiredInstallationProbeJdbc(coordinator)
        configure(observed)
        // Cold test instrumentation only: no attempt exists yet and no JDBC/manager/holder/operation is replaced.
        // The replacement is the SAME concrete production executor on the SAME exact owner, with a JdbcTemplate probe.
        coordinator.javaClass.getDeclaredField("desiredExecutor").also { it.isAccessible = true }
            .set(coordinator, ComplaintDesiredInstallPhaseExecutor(coordinator, observed))
        probe = observed
        assertNotSame(checkNotNull(target).pools.catalogCoordinator, coordinator)
    }

    /** After a test removes its own injected quarantine cause, settle the ORIGINAL resources, never retry SQL or turn a failed command into success. */
    fun fixtureCleanup() {
        if (cleanupVerified) {
            assertNoLostAssertions()
            return
        }
        assertSame(caller, Thread.currentThread(), "Only the original invocation caller may retire unverified resources.")
        clock.onSample = {}
        runCatching(operator::close) // A sticky failed command deliberately remains refused.
        beforeClose()
        requireConnectionFree()
        val owners = listOf("targetOwner", "operatorOwner").mapNotNull { ownedCutField(assembly, it) as? PersistenceJdbcLifecycleOwner }
        owners.forEach { it.requestShutdown() }
        owners.forEach { it.versionBoundPools?.close() }
        owners.forEach {
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, it.observeShutdown())
            assertEquals(PersistencePublicTrustRelease.RELEASED, it.releasePublicTrustAfterShutdown())
        }
        cleanupVerified = true // Fixture retirement only, before surfacing both assertion channels; never a failed-command receipt.
        assertNoLostAssertions()
    }
}

private fun rethrowDesiredFixtureFailures(results: List<Result<*>>) {
    val failures = results.mapNotNull { it.exceptionOrNull() }
    failures.firstOrNull()?.let { first ->
        failures.drop(1).forEach { failure ->
            if (failure !== first && first.suppressed.none { it === failure }) first.addSuppressed(failure)
        }
        throw first
    }
}

/** Wall work still consumes the real monotonic clock. Tests may additionally exhaust, but never renew, the original budget. */
internal class DesiredInstallationTestClock : PersistenceNanoClock {
    var extraNanos = 0L
    var onSample: () -> Unit = {}
    private val assertionFailure = AtomicReference<AssertionError?>()
    override fun nanoTime(): Long {
        try {
            onSample()
        } catch (failure: AssertionError) {
            assertionFailure.compareAndSet(null, failure)
            throw failure
        }
        return System.nanoTime() + extraNanos
    }
    fun assertNoLostAssertions() {
        assertionFailure.get()?.let { throw it }
    }
}

internal enum class DesiredInstallationSqlStep { AUTHENTICATE, LOCK_CONTROL, CHECK_PENDING, WRITE_CONTROL, READ_CONTROL }

/** Probe callbacks surround actual named SQL and preserve assertions through the bounded production error boundary. */
internal class DesiredInstallationProbeJdbc(val coordinator: CatalogCoordinatorPersistence) : JdbcTemplate(coordinator.dataSource) {
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val steps = mutableListOf<Pair<PersistencePhasePath, DesiredInstallationSqlStep>>()
    var beforeSql: (PersistencePhasePath, DesiredInstallationSqlStep) -> Unit = { _, _ -> }
    var afterSql: (PersistencePhasePath, DesiredInstallationSqlStep) -> Unit = { _, _ -> }
    private val assertionFailure = AtomicReference<AssertionError?>()
    val phase: PersistencePhaseContext get() = checkNotNull(PersistencePhaseOwnership.current())
    val path: PersistencePhasePath get() = poolTestField(phase, "path")

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, rowMapper) }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) {
        super.query(sql, rowMapper, *args)
    }

    fun retainedOperation(): ComplaintDesiredInstallOperationV1 = ownedCutField(phase.desiredInstallation, "retained") as ComplaintDesiredInstallOperationV1

    fun assertNoLostAssertions() {
        assertionFailure.get()?.let { throw it }
    }

    fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertionFailure.compareAndSet(null, failure)
        throw failure
    }

    private fun <T> observed(sql: String, args: Array<out Any?>, action: () -> T): T = preserveAssertions {
        val current = phase
        val selectedPath = path
        val holder = TransactionSynchronizationManager.getResource(coordinator.dataSource) as ConnectionHolder
        assertSame(coordinator.dataSource, dataSource)
        assertEquals(setOf(coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(1, coordinator.activeSnapshotOwners())
        assertFalse(holder.connection.isReadOnly)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, holder.connection.transactionIsolation)
        assertEquals(sql.count { it == '?' }, args.size)
        if (current !in observations) {
            val identity = holder.connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current(), session_user, current_user").use { row ->
                    assertTrue(row.next())
                    assertEquals(ComplaintDesiredInstallationFixture.OPERATOR, row.getString(3))
                    assertEquals(ComplaintDesiredInstallationFixture.OPERATOR, row.getString(4))
                    val found = row.getInt(1) to row.getLong(2)
                    assertFalse(row.next())
                    found
                }
            }
            observations[current] = StepUpPhaseObservation(current, ownedPoolLease(holder.connection), identity)
        }
        val step = when (sql) {
            AUTHENTICATE_DESIRED_OPERATOR_V1 -> DesiredInstallationSqlStep.AUTHENTICATE
            LOCK_DESIRED_CONTROL_V1 -> DesiredInstallationSqlStep.LOCK_CONTROL
            READ_DESIRED_PENDING_V1, READ_DESIRED_PRISTINE_V1 -> DesiredInstallationSqlStep.CHECK_PENDING
            BOOTSTRAP_DESIRED_V1, CLOSE_DESIRED_GATES_V1, SUPERSEDE_DESIRED_V1 -> DesiredInstallationSqlStep.WRITE_CONTROL
            READ_DESIRED_CONTROL_V1 -> DesiredInstallationSqlStep.READ_CONTROL
            else -> error("Unexpected desired installation SQL.")
        }
        steps.add(selectedPath to step)
        beforeSql(selectedPath, step)
        action().also { afterSql(selectedPath, step) }
    }
}
