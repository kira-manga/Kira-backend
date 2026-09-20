package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CATALOG_AUTHOR_TEST_PASSWORD
import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.SyntheticComplaintCounters
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.catalogAuthorTestPasswordBinding
import me.manga.kira.backend.common.infrastructure.persistence.ended
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisAuthorDatabaseV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFilesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxGenesisReleaseFilesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningKeyV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSnapshotPhaseExecutor
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Same SAME_THREAD PostgreSQL/TLS server and original lifecycle fixtures; no new launcher, provider or approval harness. */
internal fun withCatalogGenesisFreeze(tls: VersionBoundPersistenceConnectedFixture, test: (CatalogGenesisFreezeFixture) -> Unit) =
    CatalogGenesisFreezeFixture(tls).use { fixture ->
        fixture.prepare()
        test(fixture)
    }

internal class CatalogGenesisFreezeFixture(
    val tls: VersionBoundPersistenceConnectedFixture,
    registry: OfflineBootstrapRegistryV1 = OfflineTrustBundleFixture.registry(),
    val capacityDigest: ByteArray = ByteArray(32) { (it + 1).toByte() },
) : AutoCloseable {
    val observer = JdbcTemplate(ordinaryCleanupReader(tls.database)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
    val parent: Path = Files.createTempDirectory(
        Path.of(System.getProperty("user.home")).toRealPath(),
        "kira-g1-freeze-fixture-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    val releaseRoot: Path = directory("release")
    private val trustParent = directory("trust")
    private val initialEnvelope = OfflineTrustBundleFixture.signed(OfflineCatalogGenesisFixture.bundleBody(registry))
    private val currentEnvelope = OfflineTrustBundleFixture.signed(
        initialEnvelope.body.copy(version = 9, issuedAtEpochSecond = initialEnvelope.body.issuedAtEpochSecond + 3600),
    )
    val manifest = OfflineCatalogGenesisFixture.manifest(initialEnvelope, registry)
    val intent = OfflineCatalogGenesisFixture.manifestBytes(manifest)
    val initial = OfflineTrustBundleFixture.bytes(initialEnvelope)
    val current = OfflineTrustBundleFixture.bytes(currentEnvelope)
    val approvals = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray()
    private val files = CatalogGenesisFreezeFilesV1(
        writeInput("intent.json", intent),
        writeInput("initial-trust.json", initial),
        writeInput("current-trust.json", current),
        writeInput("approvals.json", approvals),
    )
    private val database = CatalogGenesisAuthorDatabaseV1(
        catalogAuthorTestPasswordBinding(),
        tls.database.host,
        tls.database.port,
        PgLifecycleDatabaseSettings.DATABASE,
        writeInput("database-ca.pem", tls.database.versionBoundTls().publicTrust()),
        trustParent,
    )
    private val key = CatalogSigningKeyV1(
        "catalog-old",
        KEY_ARN,
        OfflineTrustBundleFixture.ALGORITHM,
        OfflineTrustBundleFixture.firstSigner.public.encoded,
        Sha256.hex(OfflineTrustBundleFixture.firstSigner.public.encoded),
    )
    val invocations = mutableListOf<CatalogGenesisFreezeInvocation>()
    private var counters: SyntheticComplaintCounters? = null
    private var originalControl: String? = null
    private var roleCreated = false
    private var ownsToken = false

    fun prepare() {
        // No TARGET graph is started or D installed. End the existing cold peer before any author's shared Timer proof.
        tls.stopWithoutWaiting()
        Flyway.configure().dataSource(checkNotNull(observer.dataSource)).locations("classpath:db/migration").load().migrate()
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        ownsToken = true
        originalControl = control()
        counters = SyntheticComplaintCounters(observer, Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT))
        observer.update(
            "WITH limits AS (SELECT name, CASE WHEN name = 'catalog_mutations' THEN 1::bigint " +
                "WHEN name = 'storage_bytes' THEN ?::bigint ELSE 10000000 END AS units FROM complaint_capacity_counters) " +
                "UPDATE complaint_capacity_counters c SET configuration_hash = ?, configuration_closed = false, hard_limit = l.units, " +
                "creation_limit = l.units, free_units = l.units, actual_units = 0, recovery_reserved_units = 0, test_reserved_units = 0 " +
                "FROM limits l WHERE c.name = l.name",
            CatalogGenesisCapacity.storageBytes,
            capacityDigest,
        )
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Long::class.java, AUTHOR))
        observer.execute("CREATE ROLE $AUTHOR LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '$CATALOG_AUTHOR_TEST_PASSWORD'")
        roleCreated = true
        // Synthetic provisioning only. UPDATE(updated_at) permits the existing LIVE FOR UPDATE, not D/head/finalization writes.
        observer.execute(
            "GRANT USAGE ON SCHEMA public TO $AUTHOR; " +
                "GRANT SELECT ON complaint_journal_control, complaint_catalog_mutations, complaint_capacity_counters TO $AUTHOR; " +
                "GRANT UPDATE (updated_at) ON complaint_journal_control TO $AUTHOR; " +
                "GRANT INSERT (operation_token,catalog_writer_generation,approval_bytes,approval_hash,unsigned_bytes,unsigned_hash," +
                "signer_one_id,signer_one_algorithm,object_key,created_at,operation_type,predecessor_generation,predecessor_hash," +
                "successor_generation,canonicalizer,signer_policy,state) ON complaint_catalog_mutations TO $AUTHOR; " +
                "GRANT UPDATE (signer_one_signature,envelope_bytes,envelope_hash) ON complaint_catalog_mutations TO $AUTHOR; " +
                "GRANT UPDATE (free_units,actual_units,test_reserved_units,updated_at) ON complaint_capacity_counters TO $AUTHOR",
        )
    }

    fun request(independentPin: Path? = null, releaseRoot: Path = this.releaseRoot): CatalogGenesisFreezeRequestV1 = CatalogGenesisFreezeRequestV1(
        database,
        files,
        OfflineCatalogRotationFixture.policy(),
        capacityDigest,
        key,
        releaseRoot,
        independentPin,
    )

    fun invocation(): CatalogGenesisFreezeInvocation = CatalogGenesisFreezeInvocation(this).also(invocations::add)

    fun leafPath(leaf: CatalogGenesisReleaseLeafV1): Path = releaseRoot.resolve("genesis").resolve(leaf.fileName)

    fun read(leaf: CatalogGenesisReleaseLeafV1): ByteArray = Files.readAllBytes(leafPath(leaf))

    fun writeInput(name: String, bytes: ByteArray): Path = parent.resolve(name).also { path ->
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
    }

    fun control(): String = checkNotNull(
        observer.queryForObject(
            "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    fun state(): List<String> =
        observer.queryForList("SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token", String::class.java) +
            observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java) + control()

    fun assertNoAuthorSessions() = awaitLifecycleFact {
        observer.queryForObject(
            "SELECT NOT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND usename = ?)",
            Boolean::class.java,
            AUTHOR,
        ) == true
    }

    override fun close() {
        val retired = invocations.map { runCatching(it::fixtureCleanup) }
        val ready = runCatching {
            requireConnectionFree()
            assertTrue(invocations.all { it.cleanupVerified }, "An original freeze invocation has not completed actual fixture retirement.")
            assertNoAuthorSessions()
        }
        val restored = runCatching {
            ready.getOrThrow() // Never erase fixture rows/files while an actual original owner is unretired.
            originalControl?.let { row ->
                checkNotNull(observer.dataSource).connection.use { connection ->
                    connection.autoCommit = false
                    val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                    try {
                        assertEquals(1, jdbc.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id))
                        assertEquals(
                            1,
                            jdbc.update(
                                "INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)).*",
                                row,
                            ),
                        )
                        if (ownsToken) {
                            jdbc.update(
                                "DELETE FROM complaint_catalog_mutations WHERE operation_token = ?",
                                UUID.fromString(manifest.operationToken),
                            )
                        }
                        connection.commit()
                    } catch (problem: Throwable) {
                        connection.rollback()
                        throw problem
                    }
                }
            }
            counters?.close()
            if (roleCreated) observer.execute("DROP OWNED BY $AUTHOR; DROP ROLE $AUTHOR")
            // Only this protected synthetic fixture tree, after original owners and their actual lock descriptors close.
            Files.walk(parent).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        rethrowFreezeFixtureFailures(retired + listOf(ready, restored))
    }

    private fun directory(name: String): Path = Files.createDirectory(
        parent.resolve(name),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )

    companion object {
        const val AUTHOR = VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME
        const val KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/77777777-7777-4777-8777-777777777777"
    }
}

/** Only raw HTTP and observation around genuine JDBC change. The one real freeze owner still acquires, verifies, persists and closes. */
internal class CatalogGenesisFreezeInvocation(private val fixture: CatalogGenesisFreezeFixture) {
    private val caller = Thread.currentThread()
    val secrets = AwsSecretVersionFixture()
    val signing = AwsJournalKmsFixture()
    val namespace = S3CatalogReadbackFixture()
    val clock = DesiredInstallationTestClock()
    val operator = CatalogGenesisFreezeV1.withHttpFixtures(secrets::httpClient, signing::httpClient, namespace::httpClient, clock)
    private val assembly: Any = poolTestField(operator, "assembly")
    var scope: PgLifecycleTestScope? = null
        private set
    var jdbc: GenesisProbeJdbc? = null
        private set
    val phases = linkedSetOf<PersistencePhaseContext>()
    val producedSignatures = mutableListOf<ByteArray>()
    var beforeSql: (String) -> Unit = {}
    var afterSql: (String) -> Unit = {}
    var beforeSign: () -> Unit = {}
    var beforeNamespace: () -> Unit = {}
    var afterSample: () -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()
    private var observing = false
    private var retired = false
    val cleanupVerified: Boolean get() = retired

    init {
        secrets.respond = { request ->
            preserveAssertions {
                requireConnectionFree()
                val binding = catalogAuthorTestPasswordBinding()
                assertEquals(mapOf("SecretId" to binding.version.resourceArn, "VersionId" to binding.version.versionId), request.fields())
                AwsSecretVersionFixture.reply(binding.version, CATALOG_AUTHOR_TEST_PASSWORD.toByteArray())
            }
        }
        signing.respond = { request ->
            preserveAssertions {
                requireConnectionFree()
                beforeSign()
                val frame = Base64.getDecoder().decode(request.fields()["Message"].textValue())
                assertEquals("TrentService.Sign", request.target())
                assertEquals(CatalogGenesisFreezeFixture.KEY_ARN, request.fields()["KeyId"].textValue())
                assertEquals("RAW", request.fields()["MessageType"].textValue())
                assertArrayEquals(OfflineCatalogGenesisFixture.independentFrame("catalog-old", fixture.intent), frame)
                val signature = Signature.getInstance("RSASSA-PSS").run {
                    setParameter(OfflineTrustBundleFixture.parameters)
                    initSign(OfflineTrustBundleFixture.firstSigner.private)
                    update(frame)
                    sign()
                }
                producedSignatures.add(signature.copyOf())
                JournalKmsHttpReply(
                    """{"KeyId":"${CatalogGenesisFreezeFixture.KEY_ARN}","SigningAlgorithm":"RSASSA_PSS_SHA_256","Signature":"${Base64.getEncoder().encodeToString(
                        signature,
                    )}"}""",
                )
            }
        }
        namespace.respond = { request ->
            preserveAssertions {
                requireConnectionFree()
                beforeNamespace()
                assertEquals("GET", request.method().name)
                assertTrue(request.rawQueryParameters().containsKey("versions"), "This slice must never GET a catalog object or PUT/publicize G1.")
                val location = OfflineTrustBundleFixture.locations.single { request.encodedPath() in setOf("/${it.bucket}", "/${it.bucket}/") }
                namespace.listReply(S3CatalogReadbackFixture.listRequest(location), emptyList())
            }
        }
        clock.onSample = {
            if (caller === Thread.currentThread() && !observing) {
                observing = true
                try {
                    observeOriginalAssembly()
                    PersistencePhaseOwnership.current()?.let(phases::add)
                    afterSample()
                } finally {
                    observing = false
                }
            }
        }
    }

    val coordinator: CatalogCoordinatorPersistence get() = checkNotNull(scope).owner.versionBoundPools!!.catalogCoordinator

    fun execute(resume: Boolean = false, request: CatalogGenesisFreezeRequestV1 = fixture.request()) = try {
        if (resume) {
            operator.resume(
                request,
                AwsSecretVersionFixture.CREDENTIALS,
                AwsJournalKmsFixture.CREDENTIALS,
                S3CatalogReadbackFixture.credentials,
                S3CatalogReadbackFixture.credentials,
            )
        } else {
            operator.freeze(
                request,
                AwsSecretVersionFixture.CREDENTIALS,
                AwsJournalKmsFixture.CREDENTIALS,
                S3CatalogReadbackFixture.credentials,
                S3CatalogReadbackFixture.credentials,
            )
        }
    } finally {
        assertNoLostAssertions()
    }

    private fun observeOriginalAssembly() {
        val owner = ownedCutField(assembly, "owner") as? PersistenceJdbcLifecycleOwner ?: return
        if (scope == null) scope = PgLifecycleTestScope(owner)
        if (jdbc != null) return
        val coordinator = ownedCutField(owner, "catalogResources") as? CatalogCoordinatorPersistence ?: return
        if (ownedCutField(coordinator, "genesisExecutor") == null) return
        val probe = GenesisProbeJdbc(coordinator)
        probe.beforeSql = { step -> preserveAssertions { beforeSql(step) } }
        probe.afterSql = { step -> preserveAssertions { afterSql(step) } }
        // Same concrete executors, original coordinator/manager/ownership and real SQL; no supplied phase, signer or receipt.
        coordinator.javaClass.getDeclaredField("genesisExecutor").also { it.isAccessible = true }
            .set(coordinator, ComplaintCatalogGenesisPersistencePhaseExecutor(coordinator.ownership, probe))
        coordinator.javaClass.getDeclaredField("executor").also { it.isAccessible = true }
            .set(coordinator, ComplaintCatalogSnapshotPhaseExecutor(coordinator.ownership, JdbcCatalogSnapshotReader(probe)))
        jdbc = probe
    }

    fun assertReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(secrets.createdClients, secrets.closedClients)
        assertEquals(signing.createdClients, signing.closedClients)
        assertEquals(namespace.createdClients, namespace.closedClients)
        scope?.let {
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, it.owner.observeShutdown())
            assertTrue(it.actors().all { actor -> actor.termination().ended() && !actor.thread.isAlive })
            assertEquals(PersistencePublicTrustRelease.RELEASED, it.owner.releasePublicTrustAfterShutdown())
            assertFalse(it.owner.snapshot().ordinaryReady || it.owner.snapshot().deletionReady)
        }
        val release = ownedCutField(operator, "release")
        if (release != null) {
            val custody: CatalogGenesisReleaseCustodyV1 = poolTestField(release, "custody")
            assertTrue(poolTestField<LinuxGenesisReleaseFilesV1>(custody, "files").cleanupComplete())
        }
        fixture.assertNoAuthorSessions()
    }

    fun fixtureCleanup() {
        if (retired) return assertNoLostAssertions()
        assertSame(caller, Thread.currentThread())
        clock.onSample = {}
        runCatching(operator::close) // Sticky failure remains failure; fixture retirement cannot issue a freeze result.
        scope?.let {
            it.owner.requestShutdown()
            it.owner.versionBoundPools?.close()
            it.close()
        }
        requireConnectionFree()
        // An expired quarantined phase can prevent the production close from entering file cleanup.
        // Only after the ORIGINAL lease/root retire, close that same retained custody; no replaced owner or renewed budget.
        runCatching { (ownedCutField(operator, "release") as? AutoCloseable)?.close() }
        assertReleased()
        retired = true
        assertNoLostAssertions()
    }

    private fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    private fun assertNoLostAssertions() {
        clock.assertNoLostAssertions()
        assertion.get()?.let { throw it }
    }
}

private fun rethrowFreezeFixtureFailures(results: List<Result<*>>) {
    val failures = results.mapNotNull { it.exceptionOrNull() }
    failures.firstOrNull()?.let { first ->
        failures.drop(1).filterNot { it === first || first.suppressed.any { previous -> previous === it } }.forEach(first::addSuppressed)
        throw first
    }
}
