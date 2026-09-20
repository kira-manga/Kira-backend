package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.DESIRED_OPERATOR_TEST_PASSWORD
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustPreparation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFixture
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDTransitionV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredCatalogChainLimitsV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredCatalogSignerRotationInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredCatalogSigningKeyInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationInputFixture
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.admission.SignedGenesisFirstDInvocation
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseTransitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.KeyPair
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** Independent raw D7 inputs. No supplied effective D, accepted head, lease, provider receipt or detached approval protocol. */
internal object CatalogSignerRotationD7Inputs {
    const val NEW_KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/88888888-8888-4888-8888-888888888888"

    fun registry(): OfflineBootstrapRegistryV1 {
        val golden = VersionBoundCatalogReadbackTestFixture.envelope().manifest.initialWriterRegistry
        return golden.copy(catalogWriter = golden.catalogWriter.copy(generationId = OfflineTrustBundleFixture.CATALOG_WRITER))
    }

    private val initial by lazy { OfflineTrustBundleFixture.signed(OfflineCatalogGenesisFixture.bundleBody(registry())) }
    private val current by lazy {
        OfflineTrustBundleFixture.signed(initial.body.copy(version = 9, issuedAtEpochSecond = initial.body.issuedAtEpochSecond + 3600))
    }
    private val genesis by lazy { OfflineCatalogGenesisFixture.signed(OfflineCatalogGenesisFixture.manifest(initial, registry())) }

    /** Cold unit inputs only; connected first-D replaces these with the bytes returned by the actual AUTHOR. */
    fun document(): ComplaintDesiredDeploymentDocumentV1 = document(
        DesiredInstallationInputFixture.document(),
        OfflineTrustBundleFixture.bytes(initial),
        OfflineTrustBundleFixture.bytes(current),
        Sha256.hex(OfflineCatalogGenesisFixture.bytes(genesis)),
        OfflineCatalogRotationFixture.policy(),
    )

    fun document(
        base: ComplaintDesiredDeploymentDocumentV1,
        initial: ByteArray,
        current: ByteArray,
        genesisPin: String,
        chain: OfflineCatalogChainReaderPolicy,
        profile: String = "D7",
        totalAttemptMillis: Long = 30_000,
    ): ComplaintDesiredDeploymentDocumentV1 {
        val trust = chain.trustBundlePolicy
        val limits = chain.limits
        val reader = checkNotNull(base.catalog).copy(
            readerProfile = "G1",
            initialBundleBase64 = base64(initial),
            currentBundleBase64 = base64(current),
            rootPublicKeySpkiBase64 = base64(trust.rootPublicKeySpki),
            rootPublicKeySha256 = trust.rootPublicKeySha256,
            rootKeyId = trust.rootKeyId,
            rootAlgorithmId = trust.rootAlgorithmId,
            expectedEnvironment = trust.expectedEnvironment,
            expectedCatalogLocations = trust.expectedCatalogLocations,
            minimumBundleVersion = trust.minimumBundleVersion,
            currentWriterGenerationIds = chain.currentWriterGenerationIds,
            currentApproverIds = chain.currentApproverIds,
            expectedGenesisEnvelopeSha256 = genesisPin,
            chainLimits = DesiredCatalogChainLimitsV1(
                limits.maximumEnvelopeBytes,
                limits.maximumManifestRecords,
                limits.maximumGenerations,
                limits.maximumEncodedBytes,
            ),
        )
        return base.copy(
            profile = profile,
            catalog = reader,
            catalogSignerRotation = if (profile == "D7") writer(totalAttemptMillis) else null,
        )
    }

    fun writer(totalAttemptMillis: Long = 30_000): DesiredCatalogSignerRotationInputV1 = DesiredCatalogSignerRotationInputV1(
        OfflineTrustBundleFixture.CATALOG_WRITER,
        registry().catalogWriter.signAuthority,
        listOf(
            key("catalog-old", CatalogGenesisFreezeFixture.KEY_ARN, OfflineTrustBundleFixture.firstSigner),
            key("catalog-new", NEW_KEY_ARN, OfflineTrustBundleFixture.secondSigner),
        ),
        totalAttemptMillis,
    )

    fun bytes(document: ComplaintDesiredDeploymentDocumentV1): ByteArray =
        Json.encodeToString(ComplaintDesiredDeploymentDocumentV1.serializer(), document).toByteArray(Charsets.UTF_8)

    private fun key(id: String, arn: String, pair: KeyPair): DesiredCatalogSigningKeyInputV1 = DesiredCatalogSigningKeyInputV1(
        id,
        arn,
        OfflineTrustBundleFixture.ALGORITHM,
        base64(pair.public.encoded),
        Sha256.hex(pair.public.encoded),
    )

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/** Real AUTHOR -> raw parsed first-D -> actual same-D runtime/SDK refresh -> DB-time lease; never installs a synthetic D/head. */
internal class CatalogSignerRotationD7Fixture(val tls: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    val desired = ComplaintDesiredInstallationFixture(tls)
    val observer get() = desired.observer
    val clock = DesiredInstallationTestClock()
    val wallClock = MutableClock(Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT))
    private var originalFreeze: CatalogGenesisFreezeFixture? = null
    val freeze: CatalogGenesisFreezeFixture get() = checkNotNull(originalFreeze)
    private var runtime: ComplaintDesiredProcessAssemblyV1? = null
    private var runtimeRetired = false
    private var originalLease: CatalogCoordinatorLeaseAcquisitionV1? = null
    val lease: CatalogCoordinatorLeaseAcquisitionV1 get() = checkNotNull(originalLease)
    lateinit var document: ComplaintDesiredDeploymentDocumentV1
        private set
    lateinit var rawDocument: ByteArray
        private set
    lateinit var inputs: ComplaintDesiredDeploymentInputsV1
        private set
    lateinit var firstD: SignedGenesisFirstDInvocation
        private set
    lateinit var selectedHash: ByteArray
        private set
    lateinit var process: VersionBoundComplaintProcessConfiguration
        private set
    lateinit var refresh: CurrentAcceptedCatalogRefreshV1.Result
        private set
    lateinit var genesisWire: CatalogSignerRotationReadbackHttpFixture
        private set
    private var frozenFiles = emptyMap<Path, ByteArray>()
    val coordinator get() = process.pools.catalogCoordinator
    val envelope: ByteArray get() = freeze.read(CatalogGenesisReleaseLeafV1.ENVELOPE)
    val retainUntil: Instant get() = Instant.ofEpochSecond(freeze.manifest.creation.createdAtEpochSecond).atOffset(ZoneOffset.UTC).plusYears(10).toInstant()

    fun prepare(profile: String = "D7", totalAttemptMillis: Long = 30_000, beforeLease: (VersionBoundComplaintProcessConfiguration) -> Unit = {}) {
        prepareFirstD(profile, totalAttemptMillis)
        val pin = Sha256.hex(envelope)
        startRuntime()
        genesisWire = CatalogSignerRotationReadbackHttpFixture(this)
        refresh = CurrentAcceptedCatalogRefreshV1.withHttpFixture(
            process,
            S3CatalogReadbackFixture.credentials,
            S3CatalogReadbackFixture.credentials,
            genesisWire::httpClient,
            wallClock,
            clock::nanoTime,
        ).use { it.refresh() }
        genesisWire.assertCompletedReadbacks(1)
        assertEquals(1L, refresh.catalogFor(process).chain.tail.generation)
        assertEquals(pin, refresh.catalogFor(process).chain.tail.envelopeSha256)
        assertEquals("COMPLETED", genesisRow()["state"])
        assertTrue(genesisRow()["projected_at"] != null)
        assertArrayEquals(envelope, genesisRow()["envelope_bytes"] as ByteArray)
        assertEquals(1L, observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        beforeLease(process) // Same concrete executors/JdbcTemplate instrumentation, before any campaign can retain that identity.
        val binding = CatalogCoordinatorLeaseBindingV1.fromRetained(process, refresh)
        val beforeLeaseAt = databaseNow()
        originalLease = coordinator.lease.acquire(binding)
        val afterLeaseAt = databaseNow()
        val receipt = lease.receipt
        assertEquals(CatalogCoordinatorLeaseTransitionV1.ACQUIRED, receipt.transition)
        assertFalse(receipt.sampledAt.isBefore(beforeLeaseAt) || receipt.sampledAt.isAfter(afterLeaseAt))
        assertEquals(Duration.ofSeconds(30), Duration.between(receipt.sampledAt, checkNotNull(receipt.expiresAt)))
        assertEquals(1L, receipt.token)
        assertArrayEquals(selectedHash, process.configurationHashBytes())
        released()
    }

    /** Same genuine AUTHOR/first-D prefix; unlike prepare(), no controlled ordinary runtime, refresh or lease is created. */
    fun assembleInitialAuthor(): ComplaintDesiredProcessAssemblyV1 {
        prepareFirstD("D7", 30_000, epochInventory = true)
        val assembly = ComplaintDesiredProcessAssemblyV1.withClockFixture(clock)
        runtime = assembly // Retain the actual root before its cold construction, including a partial failure.
        assembly.assembleTargetSignerRotationAuthor(
            inputs,
            DesiredInstallationInputFixture.acquired(inputs, PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray(), targetOnly = true),
            null,
        )
        process = assembly.target
        assertArrayEquals(selectedHash, process.configurationHashBytes())
        assertSame(clock, coordinator.ownership.nanoClock)
        assertNull(originalLease)
        return assembly
    }

    private fun prepareFirstD(profile: String, totalAttemptMillis: Long, epochInventory: Boolean = false) {
        desired.prepare()
        val base = desired.document
        val capacity = ComplaintDesiredDeploymentJsonV1.parse(CatalogSignerRotationD7Inputs.bytes(base)).capacity
        val source = CatalogGenesisFreezeFixture(tls, CatalogSignerRotationD7Inputs.registry(), capacity.digestBytes())
        originalFreeze = source
        source.prepare()
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            assertEquals(
                1,
                observer.update(
                    "UPDATE complaint_capacity_counters SET configuration_hash = ?, configuration_closed = false, hard_limit = ?, " +
                        "creation_limit = ?, free_units = ?, actual_units = 0, recovery_reserved_units = 0, test_reserved_units = 0 WHERE name = ?",
                    capacity.digestBytes(),
                    capacity.hardLimit[counter],
                    capacity.creationLimit[counter],
                    capacity.hardLimit[counter],
                    counter.storedName,
                ),
            )
        }
        val before = desired.control()
        val author = source.invocation()
        assertEquals(CatalogGenesisFreezeStateV1.SIGNED_AWAITING_RELEASE, author.execute().state)
        author.assertReleased()
        assertEquals(1, author.signing.requests.size)
        assertEquals(before, desired.control())
        val pin = Sha256.hex(envelope)
        // Existing initial G1 release only. Operation2 below has no independent-pin or additional approval ceremony.
        val releasedPin = source.writeInput("independently-released-genesis-pin", pin.toByteArray(Charsets.US_ASCII))
        val frozenRequest = source.request(releasedPin)
        val released = source.invocation()
        assertEquals(CatalogGenesisFreezeStateV1.FROZEN, released.execute(resume = true, request = frozenRequest).state)
        released.assertReleased()
        assertEquals(0, released.signing.createdClients)
        document = CatalogSignerRotationD7Inputs.document(base, source.initial, source.current, pin, frozenRequest.chainPolicy, profile, totalAttemptMillis)
        if (epochInventory) document = document.copy(epochRotation = true) // Before raw parsing/first-D, never retrofitted onto retained D.
        rawDocument = CatalogSignerRotationD7Inputs.bytes(document)
        inputs = ComplaintDesiredDeploymentJsonV1.parse(rawDocument)
        val release = ComplaintSignedGenesisFirstDInputsV1.fromRaw(source.intent, source.initial, source.current, envelope, pin)
        firstD = SignedGenesisFirstDInvocation(inputs, release, desired::stopRuntimeWithoutWaiting, {}).also(desired.firstDInvocations::add)
        firstD.beforePhase = {
            val target = checkNotNull(firstD.target)
            assertSame(inputs.catalog, target.catalogReadback)
            assertSame(inputs.catalogSignerRotation, target.catalogSignerRotation?.deployment)
            selectedHash = target.configurationHashBytes()
            assertNull(
                observer.queryForObject(
                    "SELECT desired_configuration_hash FROM complaint_journal_control WHERE data_scope_id = ?",
                    ByteArray::class.java,
                    ComplaintDataScope.LIVE.id,
                ),
            )
        }
        assertEquals(ComplaintSignedGenesisFirstDTransitionV1.SELECTED, firstD.execute().transition)
        assertTrue(firstD.cleanupVerified)
        assertSame(inputs, firstD.inputs)
        assertArrayEquals(selectedHash, desiredHash())
        assertEquals("PREPARED", genesisRow()["state"])
        assertNull(genesisRow()["projected_at"])
        frozenFiles = Files.walk(source.releaseRoot.resolve("genesis")).use { entries ->
            entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith(Files::readAllBytes)
        }
    }

    private fun startRuntime() {
        // Same raw acquired D7/D2 graph and D hash; controlled integration is selected before binding, never patched onto retained pools.
        val assembly = ComplaintDesiredProcessAssemblyV1.withControlledIntegrationFixture(clock)
        runtime = assembly
        assembly.assemble(
            inputs,
            DesiredInstallationInputFixture.acquired(
                inputs,
                PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray(),
                DESIRED_OPERATOR_TEST_PASSWORD.toByteArray(),
            ),
            null,
        )
        process = assembly.target
        assertArrayEquals(selectedHash, process.configurationHashBytes())
        assertSame(clock, coordinator.ownership.nanoClock)
        val owner = ownedCutField(assembly, "targetOwner") as PersistenceJdbcLifecycleOwner
        assertSame(process.pools, owner.versionBoundPools)
        assertEquals(PersistencePublicTrustPreparation.READY, owner.preparePublicTrust())
        assertEquals(PersistenceLifecycleActivation.STARTED, process.pools.ordinary.start())
        awaitLifecycleFact { owner.snapshot().ordinaryReady && owner.snapshot().timerReady }
        assertEquals(PersistenceLifecycleObservation.READY, process.pools.deletion.prepareDeletion())
        assertEquals(PersistenceLifecycleObservation.READY, coordinator.prepare())
    }

    fun desiredHash(): ByteArray? = observer.queryForObject(
        "SELECT desired_configuration_hash FROM complaint_journal_control WHERE data_scope_id = ?",
        ByteArray::class.java,
        ComplaintDataScope.LIVE.id,
    )

    fun genesisRow(): Map<String, Any?> = observer.queryForMap(
        "SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?",
        UUID.fromString(freeze.manifest.operationToken),
    )

    fun state(): List<String> = freeze.state()
    fun counters(): List<String> = observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)

    fun released() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, coordinator.activeSnapshotOwners())
        clock.assertNoLostAssertions()
    }

    fun assertFrozenUnchanged() = frozenFiles.forEach { (path, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(path)) }

    /** Fixture retirement only, never a successful freeze result or renewed operation/campaign allowance. */
    fun retireRuntime() {
        if (runtimeRetired) return
        clock.onSample = {}
        originalLease?.campaign?.close()
        val assembly = runtime
        if (assembly != null) {
            val owners = listOf("targetOwner", "operatorOwner").mapNotNull { ownedCutField(assembly, it) as? PersistenceJdbcLifecycleOwner }
            owners.forEach { it.requestShutdown() }
            owners.forEach { PgLifecycleTestScope(it).close() }
            owners.forEach { it.versionBoundPools?.close() }
            assembly.close()
            assembly.requireCleanup(PersistenceTimeBudget.start(10_000))
        }
        runtimeRetired = true
    }

    override fun close() {
        val stopped = runCatching(::retireRuntime)
        val authors = originalFreeze?.invocations.orEmpty()
        val retired = authors.map { runCatching(it::fixtureCleanup) } + desired.firstDInvocations.map { runCatching(it::fixtureCleanup) } +
            desired.invocations.map { runCatching(it::fixtureCleanup) }
        val ready = runCatching {
            stopped.getOrThrow()
            requireConnectionFree()
            assertTrue(runtimeRetired)
            assertTrue(authors.all { it.cleanupVerified } && desired.firstDInvocations.all { it.cleanupVerified })
            assertTrue(desired.invocations.all { it.cleanupVerified })
            awaitLifecycleFact {
                observer.queryForObject(
                    "SELECT NOT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND usename IN (?, ?, ?))",
                    Boolean::class.java,
                    PgLifecycleDatabaseSettings.CANDIDATE,
                    CatalogGenesisFreezeFixture.AUTHOR,
                    ComplaintDesiredInstallationFixture.OPERATOR,
                ) == true
            }
        }
        val sourceClosed = runCatching {
            ready.getOrThrow()
            originalFreeze?.close()
        }
        val desiredClosed = runCatching {
            ready.getOrThrow()
            desired.close()
        }
        rethrowSignerRotationFixtureFailures(listOf(stopped) + retired + listOf(ready, sourceClosed, desiredClosed))
    }

    private fun databaseNow(): Instant = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
}

internal fun rethrowSignerRotationFixtureFailures(results: List<Result<*>>) {
    val failures = results.mapNotNull { it.exceptionOrNull() }
    failures.firstOrNull()?.let { first ->
        failures.drop(1).filterNot { it === first || first.suppressed.any { previous -> previous === it } }.forEach(first::addSuppressed)
        throw first
    }
}
