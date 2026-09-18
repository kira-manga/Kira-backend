package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.ACQUIRE_COORDINATOR_LEASE
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationInitialAuthorV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.INSERT_SIGNER_ROTATION_PREPARED
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_COORDINATOR_LEASE_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_GENESIS_FINALIZATION
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_GENESIS_FINAL_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxSignerRotationReleaseFilesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_COORDINATOR_LEASE_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_GENESIS_FINALIZATION
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_GENESIS_FINAL_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_CURRENT_LEASE
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.RELINQUISH_COORDINATOR_LEASE
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_GENESIS_COMPLETION
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_GENESIS_HEAD
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_GENESIS_INITIAL_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_GENESIS_PROJECTION
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_SIGNER_ROTATION_SIGNATURE
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.security.Signature
import java.sql.Connection
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** Actual one-shot owner and two real SDK responses; only raw HTTP is synthetic, never a Sign result/phase/campaign. */
internal class CatalogSignerRotationFreezeInvocation(
    private val f: CatalogSignerRotationFreezeFixture,
    private val initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
) {
    private val caller = Thread.currentThread()
    val signing = AwsJournalKmsFixture()
    val readback = CatalogSignerRotationReadbackHttpFixture(f.d7)
    val operator = initialAuthor?.beginFreeze() ?: CatalogSignerRotationFreezeV1.withHttpFixtures(
        f.process,
        f.campaign,
        signing::httpClient,
        readback::httpClient,
        f.d7.wallClock,
    )
    val attempt: CatalogSignerRotationFreezeAttemptV1 = poolTestField(operator, "attempt")
    private val assembly: AutoCloseable = poolTestField(operator, "assembly")
    private val firstPhase = f.jdbc.observations.size
    private var lastPhase: Int? = null
    val phases: List<PersistencePhaseContext> get() = f.jdbc.observations.keys.take(lastPhase ?: f.jdbc.observations.size).drop(firstPhase)
    val producedSignatures = mutableListOf<ByteArray>()
    var beforeSign: (Int) -> Unit = {}
    var rejectSecondSignResponse = false
    private var firstSignerSlot = 0
    private val assertion = AtomicReference<AssertionError?>()
    private var retired = false
    val cleanupVerified: Boolean get() = retired

    init {
        signing.respond = { request ->
            preserveAssertions {
                f.d7.released()
                val slot = firstSignerSlot + producedSignatures.size
                assertTrue(slot in 0..1, "A third Sign or a retry is never authorized.")
                beforeSign(slot)
                val keyId = listOf("catalog-old", "catalog-new")[slot]
                val arn = listOf(CatalogGenesisFreezeFixture.KEY_ARN, CatalogSignerRotationD7Inputs.NEW_KEY_ARN)[slot]
                val fields = request.fields()
                assertEquals("TrentService.Sign", request.target())
                assertEquals(setOf("KeyId", "Message", "MessageType", "SigningAlgorithm"), fields.fieldNames().asSequence().toSet())
                assertEquals(arn, fields["KeyId"].textValue())
                assertEquals("RAW", fields["MessageType"].textValue())
                assertEquals("RSASSA_PSS_SHA_256", fields["SigningAlgorithm"].textValue())
                assertEquals("https", request.http.protocol())
                assertEquals("kms.us-east-1.amazonaws.com", request.http.host())
                assertTrue(request.http.firstMatchingHeader("Authorization").orElseThrow().contains("/us-east-1/kms/aws4_request"))
                val credentials = if (slot == 0) AwsJournalKmsFixture.CREDENTIALS else NEW_SIGNING_CREDENTIALS
                assertTrue(request.http.firstMatchingHeader("Authorization").orElseThrow().contains("Credential=${credentials.accessKeyId()}/"))
                assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
                val frame = Base64.getDecoder().decode(fields["Message"].textValue())
                assertArrayEquals(OfflineCatalogGenesisFixture.independentFrame(keyId, f.intent), frame)
                signingReply(slot, arn, frame).apply {
                    beforeCall = { preserveAssertions(f.d7::released) }
                    beforeRead = { preserveAssertions(f.d7::released) }
                    onClose = { preserveAssertions(f.d7::released) }
                    onAbort = { preserveAssertions(f.d7::released) }
                }
            }
        }
    }

    fun execute(resume: Boolean = false, request: CatalogSignerRotationFreezeRequestV1 = f.request) = try {
        if (resume) {
            operator.resume(
                request,
                AwsJournalKmsFixture.CREDENTIALS,
                NEW_SIGNING_CREDENTIALS,
                S3CatalogReadbackFixture.credentials,
                S3CatalogReadbackFixture.credentials,
            )
        } else {
            operator.freeze(
                request,
                AwsJournalKmsFixture.CREDENTIALS,
                NEW_SIGNING_CREDENTIALS,
                S3CatalogReadbackFixture.credentials,
                S3CatalogReadbackFixture.credentials,
            )
        }
    } finally {
        if (lastPhase == null) lastPhase = f.jdbc.observations.size
        assertNoLostAssertions()
    }

    fun continueSecondSign(previous: CatalogSignerRotationFreezeInvocation, request: CatalogSignerRotationFreezeRequestV1 = f.request) = try {
        firstSignerSlot = 1 // Expected request order, not a fabricated first response or inference from the request's ARN.
        operator.continueSecondSign(
            request,
            previous.operator,
            NEW_SIGNING_CREDENTIALS,
            S3CatalogReadbackFixture.credentials,
            S3CatalogReadbackFixture.credentials,
        )
    } finally {
        if (lastPhase == null) lastPhase = f.jdbc.observations.size
        assertNoLostAssertions()
    }

    /** On-time invocation cleanup only; deliberately keeps the already accepted process and original lease campaign alive. */
    fun assertReleased() = assertCleaned(reserved = true)

    fun assertCleanedWithoutReservation() = assertCleaned(reserved = false)

    private fun assertCleaned(reserved: Boolean) {
        f.d7.released()
        assertOriginalFilesClosed()
        assertEquals(signing.createdClients, signing.returnedClientCloses)
        assertSyntheticTransportsDisposed()
        val active = poolTestField<AtomicReference<Any?>>(f.coordinator.catalogRefreshCustody, "active").get()
        if (initialAuthor == null) assertNull(active) else assertSame(initialAuthor, active)
        assertEquals(reserved, poolTestField<Boolean>(attempt, "reserved"))
        assertEquals(reserved, poolTestField<Boolean>(attempt, "released"))
        assertTrue(poolTestField<Boolean>(operator, "closed"))
        assertTrue(poolTestField<Boolean>(operator, "cleanupProven"))
        assertNull(ownedCutField(operator, "closeFailure"))
        assertNoLostAssertions()
    }

    /** Enclosing-fixture disposal, never a command receipt or repair of a sticky attempt/phase/shared slot. */
    fun fixtureCleanup() {
        if (retired) return assertNoLostAssertions()
        assertSame(caller, Thread.currentThread())
        // The outer fixture retires the original campaign/process first. A quarantined command may
        // have been unable to dispatch file cleanup; only these same retained owners may now close.
        runCatching(operator::close)
        runCatching(assembly::close)
        runCatching { (ownedCutField(operator, "release") as? AutoCloseable)?.close() }
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, f.coordinator.activeSnapshotOwners())
        assertOriginalFilesClosed()
        assertSyntheticTransportsDisposed()
        retired = true // Actual fixture resource disposal precedes preserved test assertions; not successful invocation cleanup.
        assertNoLostAssertions()
    }

    private fun assertOriginalFilesClosed() {
        val inputs: Any = poolTestField(assembly, "files")
        assertNull(ownedCutField(inputs, "channel"))
        assertFalse(poolTestField<Boolean>(inputs, "opening"))
        assertTrue(poolTestField<Boolean>(inputs, "closed"))
        ownedCutField(operator, "release")?.let { release ->
            val custody: CatalogSignerRotationReleaseCustodyV1 = poolTestField(release, "custody")
            assertTrue(poolTestField<LinuxSignerRotationReleaseFilesV1>(custody, "files").cleanupComplete())
        }
    }

    private fun assertSyntheticTransportsDisposed() {
        // A throwing synthetic raw-client close owns no socket/thread. Count the actual close,
        // but only assertReleased additionally requires its returned receipt; never reset its failure.
        assertEquals(signing.createdClients, signing.closedClients)
        signing.replies.forEach {
            assertEquals(1, it.calls)
            assertEquals(1, it.aborts)
            assertEquals(1, it.closes)
        }
        readback.assertTransportDisposed()
    }

    private fun signingReply(slot: Int, arn: String, frame: ByteArray): JournalKmsHttpReply {
        if (slot == 1 && rejectSecondSignResponse) {
            // Raw error only after the real request checks: prepare returns its executable, but no second signature is generated.
            return JournalKmsHttpReply("""{"__type":"KMSInternalException"}""").apply { status = 500 }
        }
        val pair = listOf(OfflineTrustBundleFixture.firstSigner, OfflineTrustBundleFixture.secondSigner)[slot]
        val signature = Signature.getInstance("RSASSA-PSS").run {
            setParameter(OfflineTrustBundleFixture.parameters)
            initSign(pair.private)
            update(frame)
            sign()
        }
        producedSignatures.add(signature.copyOf())
        return JournalKmsHttpReply(
            """{"KeyId":"$arn","SigningAlgorithm":"RSASSA_PSS_SHA_256","Signature":"${Base64.getEncoder().encodeToString(signature)}"}""",
        )
    }

    private fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    private fun assertNoLostAssertions() = rethrowSignerRotationFixtureFailures(
        listOf(
            runCatching(f.clock::assertNoLostAssertions),
            runCatching(f.jdbc::assertNoLostAssertions),
            runCatching(readback::assertNoLostAssertions),
            runCatching { assertion.get()?.let { throw it } },
        ),
    )

    companion object {
        private val NEW_SIGNING_CREDENTIALS = AwsSessionCredentials.create(
            "SYNTHETICNEWKMSACCESS",
            "synthetic-new-kms-secret-not-real",
            "synthetic-new-kms-session",
        )
    }
}

internal class CatalogSignerRotationSqlCall(val phase: PersistencePhaseContext, val step: String, arguments: Array<out Any?>) {
    val path: PersistencePhasePath = poolTestField(phase, "path")
    val arguments: List<Any?> = arguments.map { if (it is ByteArray) it.copyOf() else it }
}

/** Same concrete snapshot/G1/lease/rotation executors and original coordinator; all SQL/results/transaction ownership are real. */
internal class CatalogSignerRotationProbeJdbc(private val coordinator: CatalogCoordinatorPersistence) : JdbcTemplate(coordinator.dataSource) {
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<CatalogSignerRotationSqlCall>()
    val steps: List<String> get() = calls.map { it.step }
    var beforeSql: (String) -> Unit = {}
    var afterSql: (String) -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, rowMapper) }

    override fun <T : Any?> query(sql: String, rse: ResultSetExtractor<T>): T? {
        val path = PersistencePhaseOwnership.current()?.let { poolTestField<PersistencePhasePath>(it, "path") }
        return if (path === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT) {
            observed(sql, emptyArray()) { super.query(sql, rse) }
        } else {
            super.query(sql, rse) // RowMapper's no-argument overload already passes through observed; never double-count that dispatch.
        }
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) {
        super.query(sql, rowMapper, *args)
    }

    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }

    fun resetObservations() {
        assertNoLostAssertions()
        observations.clear()
        calls.clear()
    }

    fun assertNoLostAssertions() {
        assertion.get()?.let { throw it }
    }

    private fun <T> observed(sql: String, arguments: Array<out Any?>, action: () -> T): T = try {
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        val path: PersistencePhasePath = poolTestField(phase, "path")
        val holder = TransactionSynchronizationManager.getResource(coordinator.dataSource) as ConnectionHolder
        assertSame(coordinator.dataSource, dataSource)
        assertEquals(setOf(coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertEquals(1, coordinator.activeSnapshotOwners())
        assertEquals(path === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT, holder.connection.isReadOnly)
        // This fixture leaves the role default READ COMMITTED unchanged; G1/lease do not acquire rotation's explicit override.
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, holder.connection.transactionIsolation)
        assertEquals(sql.count { it == '?' }, arguments.size)
        if (phase !in observations) {
            val identity = holder.connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT pg_backend_pid(), txid_current(), session_user, current_user, " +
                        "(SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()), " +
                        "EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = 'ShareLock' AND granted)",
                ).use { row ->
                    assertTrue(row.next())
                    assertEquals(PgLifecycleDatabaseSettings.CANDIDATE, row.getString(3))
                    assertEquals(PgLifecycleDatabaseSettings.CANDIDATE, row.getString(4))
                    assertTrue(row.getBoolean(5) && !row.wasNull())
                    // Snapshot is read-only and leases stay row-only; original G1 and rotation data phases take the shared epoch fence.
                    val sharedFence = expectedSharedFence(path)
                    assertEquals(sharedFence, row.getBoolean(6))
                    val found = row.getInt(1) to row.getLong(2)
                    assertFalse(row.next())
                    found
                }
            }
            observations[phase] = StepUpPhaseObservation(phase, ownedPoolLease(holder.connection), identity)
        }
        val step = if (path === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT) {
            assertTrue(sql.trimStart().startsWith("WITH control AS ("))
            "snapshot"
        } else {
            step(sql, arguments)
        }
        calls.add(CatalogSignerRotationSqlCall(phase, step, arguments))
        beforeSql(step)
        action().also {
            if (step == "catalog") assertCatalogLock(holder.connection)
            afterSql(step)
        }
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    private fun expectedSharedFence(path: PersistencePhasePath): Boolean = when (path) {
        PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
        PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
        -> false

        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
        PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
        PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
        -> true

        else -> error("Unexpected signer rotation/lease phase.")
    }

    private fun assertCatalogLock(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND mode = 'ExclusiveLock' AND granted)",
            ).use { row ->
                assertTrue(row.next() && row.getBoolean(1))
                assertFalse(row.next())
            }
        }
    }

    private fun step(sql: String, arguments: Array<out Any?>): String = when (sql) {
        LOCK_COORDINATOR_LEASE_CONTROL -> "lease-lock"

        READ_COORDINATOR_LEASE_CONTROL -> "lease-read"

        ACQUIRE_COORDINATOR_LEASE -> "lease-acquire"

        RELINQUISH_COORDINATOR_LEASE -> "lease-relinquish"

        LOCK_SIGNER_ROTATION_CONTROL -> "control"

        READ_SIGNER_ROTATION_CURRENT_LEASE -> "current-lease"

        TRY_CATALOG_LOCK -> "catalog"

        LOCK_SIGNER_ROTATION_HISTORY -> "history-lock"

        READ_SIGNER_ROTATION_HISTORY -> "history-read"

        INSERT_SIGNER_ROTATION_PREPARED -> "prepare"

        WRITE_SIGNER_ROTATION_SIGNATURE -> "signature"

        LOCK_GENESIS_FINAL_CONTROL -> "genesis-control"

        READ_GENESIS_FINAL_CONTROL -> "genesis-control-read"

        LOCK_GENESIS_FINALIZATION -> "genesis-history-lock"

        READ_GENESIS_FINALIZATION -> "genesis-history-read"

        WRITE_GENESIS_COMPLETION -> "genesis-complete"

        WRITE_GENESIS_HEAD -> "genesis-head"

        WRITE_GENESIS_PROJECTION -> "genesis-project"

        WRITE_GENESIS_INITIAL_CONTROL -> "genesis-initial"

        else -> when {
            sql.contains("FROM complaint_capacity_counters") -> "counters"
            sql.contains("UPDATE complaint_capacity_counters") -> "charge:${arguments[3]}"
            else -> error("Unexpected signer rotation/lease SQL.")
        }
    }
}
