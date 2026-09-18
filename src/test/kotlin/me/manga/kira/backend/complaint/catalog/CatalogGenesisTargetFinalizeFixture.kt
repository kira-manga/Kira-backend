package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipant
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.ended
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFixture
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDTransitionV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredCatalogChainLimitsV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationInputFixture
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredInstallationTestClock
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.admission.firstDInvocation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeReleaseV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxGenesisReleaseFilesV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSnapshotPhaseExecutor
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.support.MutableClock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpRequest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Existing SAME_THREAD TLS/PG, freeze and first-D owners only. No supplied-port projection or test-installed D. */
internal fun withCatalogGenesisTargetFinalize(
    tls: VersionBoundPersistenceConnectedFixture,
    profile: String = "D2",
    test: (CatalogGenesisTargetFinalizeFixture) -> Unit,
) = CatalogGenesisTargetFinalizeFixture(tls).use { fixture ->
    fixture.prepare(profile)
    test(fixture)
}

/**
 * Acquired descriptors are defensive copies with identity equality.
 * Compare every public field, never secret material or owner identity.
 */
internal fun targetFinalizerBindingFields(binding: VersionedSecretBinding): List<Any> =
    listOf(binding.family, binding.purpose, binding.logicalKeyId, binding.version)

internal class CatalogGenesisTargetFinalizeFixture(val tls: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    val desired = ComplaintDesiredInstallationFixture(tls)
    val observer get() = desired.observer
    private var frozen: CatalogGenesisFreezeFixture? = null
    val freeze: CatalogGenesisFreezeFixture get() = checkNotNull(frozen)
    val invocations = mutableListOf<CatalogGenesisTargetFinalizeInvocation>()
    lateinit var document: ComplaintDesiredDeploymentDocumentV1
        private set
    lateinit var inputs: ComplaintDesiredDeploymentInputsV1
        private set
    lateinit var request: CatalogGenesisFinalizeRequestV1
        private set
    lateinit var selectedCanonical: ByteArray
        private set
    lateinit var selectedHash: ByteArray
        private set
    lateinit var selectedControl: String
        private set
    lateinit var frozenFiles: Map<Path, ByteArray>
        private set
    val envelope: ByteArray get() = freeze.read(CatalogGenesisReleaseLeafV1.ENVELOPE)
    val token: UUID get() = UUID.fromString(freeze.manifest.operationToken)
    val retainUntil: Instant get() = Instant.ofEpochSecond(freeze.manifest.creation.createdAtEpochSecond).atOffset(ZoneOffset.UTC).plusYears(10).toInstant()

    fun prepare(profile: String) {
        desired.prepare()
        val base = DesiredInstallationInputFixture.document(profile).copy(database = desired.document.database)
        val capacity = ComplaintDesiredDeploymentInputsV1.fromDecoded(base).capacity
        val goldenRegistry = VersionBoundCatalogReadbackTestFixture.envelope().manifest.initialWriterRegistry
        val registry = goldenRegistry.copy(catalogWriter = goldenRegistry.catalogWriter.copy(generationId = OfflineTrustBundleFixture.CATALOG_WRITER))
        val source = CatalogGenesisFreezeFixture(tls, registry, capacity.digestBytes())
        frozen = source // Retain the original fixture before any preparation or owner can fail.
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
        assertEquals(before, desired.control(), "The real AUTHOR neither selects D nor completes/projects G1.")
        assertEquals(1, author.signing.requests.size)
        val pin = Sha256.hex(envelope)
        // Explicit synthetic independent release input, not an assertion of real human/cloud approval.
        val releasedPin = source.writeInput("independently-released-target-pin", pin.toByteArray(Charsets.US_ASCII))
        val frozenRequest = source.request(releasedPin)
        val resume = source.invocation()
        assertEquals(CatalogGenesisFreezeStateV1.FROZEN, resume.execute(resume = true, request = frozenRequest).state)
        resume.assertReleased()
        assertEquals(0, resume.signing.createdClients)
        document = targetDocument(base, frozenRequest, pin)
        inputs = ComplaintDesiredDeploymentInputsV1.fromDecoded(document)
        val targetPath = source.writeInput("actual-target.json", Json.encodeToString(ComplaintDesiredDeploymentDocumentV1.serializer(), document).toByteArray())
        request = CatalogGenesisFinalizeRequestV1(frozenRequest, targetPath)
        frozenFiles = snapshotFrozenFiles()
        val release = ComplaintSignedGenesisFirstDInputsV1.fromRaw(source.intent, source.initial, source.current, envelope, pin)
        val firstD = desired.firstDInvocation(selected = document, release = release)
        firstD.beforePhase = {
            val target = checkNotNull(firstD.target)
            selectedCanonical = target.canonicalBytes()
            selectedHash = target.configurationHashBytes()
        }
        assertEquals(ComplaintSignedGenesisFirstDTransitionV1.SELECTED, firstD.execute().transition)
        assertTrue(firstD.cleanupVerified)
        assertArrayEquals(selectedHash, desiredHash())
        selectedControl = desired.control()
        assertFrozenUnchanged()
    }

    fun invocation(): CatalogGenesisTargetFinalizeInvocation = CatalogGenesisTargetFinalizeInvocation(this).also(invocations::add)

    fun desiredHash(): ByteArray? = observer.queryForObject(
        "SELECT desired_configuration_hash FROM complaint_journal_control WHERE data_scope_id = ?",
        ByteArray::class.java,
        ComplaintDataScope.LIVE.id,
    )

    fun counters(): List<String> = observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)
    fun state(): List<String> = freeze.state()
    fun exists(leaf: CatalogGenesisReleaseLeafV1): Boolean = Files.exists(freeze.leafPath(leaf), NOFOLLOW_LINKS)
    fun completeness(leaf: CatalogGenesisReleaseLeafV1): Path = freeze.leafPath(leaf).resolveSibling("${leaf.fileName}.complete")

    fun assertFrozenUnchanged() {
        frozenFiles.forEach { (path, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(path), "Original freeze bytes changed at ${path.fileName}.") }
        for (leaf in listOf(
            CatalogGenesisReleaseLeafV1.FIRST_D_ARMED,
            CatalogGenesisReleaseLeafV1.FIRST_D_OUTCOME,
            CatalogGenesisReleaseLeafV1.PUBLISH_ARMED,
            CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME,
        )) {
            assertFalse(exists(leaf), "This programmatic finalizer must not manufacture another stage's records.")
        }
    }

    fun assertNoTargetSessions() = awaitLifecycleFact {
        observer.queryForObject(
            "SELECT NOT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND usename IN (?, ?, ?))",
            Boolean::class.java,
            PgLifecycleDatabaseSettings.CANDIDATE,
            CatalogGenesisFreezeFixture.AUTHOR,
            ComplaintDesiredInstallationFixture.OPERATOR,
        ) == true
    }

    override fun close() {
        val stopped = runCatching(desired::stopRuntimeWithoutWaiting)
        val authors = frozen?.invocations.orEmpty()
        val retired = invocations.map { runCatching(it::fixtureCleanup) } + authors.map { runCatching(it::fixtureCleanup) } +
            desired.invocations.map { runCatching(it::fixtureCleanup) } + desired.firstDInvocations.map { runCatching(it::fixtureCleanup) }
        val ready = runCatching {
            stopped.getOrThrow()
            requireConnectionFree()
            assertTrue(invocations.all { it.cleanupVerified }, "Original finalizer owners must retire before fixture restoration.")
            assertTrue(authors.all { it.cleanupVerified }, "Original author owners must retire before fixture restoration.")
            assertTrue(desired.invocations.all { it.cleanupVerified } && desired.firstDInvocations.all { it.cleanupVerified })
            assertNoTargetSessions()
        }
        val sourceClosed = runCatching {
            ready.getOrThrow()
            frozen?.close() // Nested control/counter snapshots restore in reverse order.
        }
        val desiredClosed = runCatching {
            ready.getOrThrow()
            // A retained assertion from the already-retired source must not strand the outer fixture's rows/role.
            desired.close()
        }
        rethrowTargetFinalizeFixtureFailures(listOf(stopped) + retired + listOf(ready, sourceClosed, desiredClosed))
    }

    private fun snapshotFrozenFiles(): Map<Path, ByteArray> = Files.walk(freeze.releaseRoot.resolve("genesis")).use { entries ->
        entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith(Files::readAllBytes)
        // Deliberately never open another descriptor on the permanent .custody.lock.
    }

    private fun targetDocument(
        base: ComplaintDesiredDeploymentDocumentV1,
        frozenRequest: CatalogGenesisFreezeRequestV1,
        pin: String,
    ): ComplaintDesiredDeploymentDocumentV1 {
        val chain = frozenRequest.chainPolicy
        val trust = chain.trustBundlePolicy
        val limits = chain.limits
        val reader = checkNotNull(base.catalog).copy(
            readerProfile = "G1",
            initialBundleBase64 = base64(freeze.initial),
            currentBundleBase64 = base64(freeze.current),
            rootPublicKeySpkiBase64 = base64(trust.rootPublicKeySpki),
            rootPublicKeySha256 = trust.rootPublicKeySha256,
            rootKeyId = trust.rootKeyId,
            rootAlgorithmId = trust.rootAlgorithmId,
            expectedEnvironment = trust.expectedEnvironment,
            expectedCatalogLocations = trust.expectedCatalogLocations,
            minimumBundleVersion = trust.minimumBundleVersion,
            currentWriterGenerationIds = chain.currentWriterGenerationIds,
            currentApproverIds = chain.currentApproverIds,
            expectedGenesisEnvelopeSha256 = pin,
            chainLimits = DesiredCatalogChainLimitsV1(
                limits.maximumEnvelopeBytes,
                limits.maximumManifestRecords,
                limits.maximumGenerations,
                limits.maximumEncodedBytes,
            ),
        )
        return base.copy(catalog = reader)
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/** Only raw transport/time and same-coordinator JDBC observation differ. The finalizer retains every actual production owner. */
internal class CatalogGenesisTargetFinalizeInvocation(private val fixture: CatalogGenesisTargetFinalizeFixture) {
    private val caller = Thread.currentThread()
    val secrets = AwsSecretVersionFixture()
    val http = S3CatalogReadbackFixture()
    val clock = DesiredInstallationTestClock()
    val wallClock = MutableClock(VersionBoundCatalogReadbackTestFixture.evaluatedAt)
    val operator = CatalogGenesisFinalizeV1.withHttpFixtures(secrets::httpClient, ::httpClient, clock, wallClock)
    val assembly: ComplaintDesiredProcessAssemblyV1 = poolTestField(operator, "assembly")
    var scope: PgLifecycleTestScope? = null
        private set
    var target: VersionBoundComplaintProcessConfiguration? = null
        private set
    var jdbc: GenesisProbeJdbc? = null
        private set
    val phases = linkedSetOf<PersistencePhaseContext>()
    val sql = mutableListOf<Pair<PersistencePhasePath, String>>()
    var beforeSql: (String) -> Unit = {}
    var afterSql: (String) -> Unit = {}
    var afterSample: () -> Unit = {}
    var afterHttpClose: (Int) -> Unit = {}
    var returnedHttpCloses = 0
        private set
    private val assertion = AtomicReference<AssertionError?>()
    private var observing = false
    var cleanupVerified = false
        private set
    val coordinator: CatalogCoordinatorPersistence get() = checkNotNull(target).pools.catalogCoordinator
    val attempt: CatalogGenesisFinalizeAttemptV1 get() = poolTestField(operator, "attempt")
    val release: CatalogGenesisFinalizeReleaseV1 get() = poolTestField(operator, "release")

    init {
        val acquired = DesiredInstallationInputFixture.acquired(
            fixture.inputs,
            PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray(),
        ).filter {
            targetFinalizerBindingFields(it.descriptor) != targetFinalizerBindingFields(fixture.inputs.operatorPassword)
        }
        secrets.respond = { request ->
            preserveAssertions {
                requireConnectionFree()
                val fields = request.fields()
                assertEquals(setOf("SecretId", "VersionId"), fields.keys)
                val selected = acquired.single {
                    it.descriptor.version.resourceArn == fields["SecretId"] &&
                        it.descriptor.version.versionId == fields["VersionId"]
                }
                assertTrue(
                    fixture.inputs.targetBindings().map(::targetFinalizerBindingFields)
                        .contains(targetFinalizerBindingFields(selected.descriptor)),
                )
                selected.useMaterial { AwsSecretVersionFixture.reply(selected.descriptor.version, it) }
            }
        }
        http.respond = { request -> connectionFree { reply(request) } }
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

    fun execute(request: CatalogGenesisFinalizeRequestV1 = fixture.request) = try {
        operator.finalize(
            request,
            AwsSecretVersionFixture.CREDENTIALS,
            S3CatalogReadbackFixture.credentials,
            S3CatalogReadbackFixture.credentials,
            if (fixture.inputs.sealerMapping == null) null else AwsSecretVersionFixture.CREDENTIALS,
        )
    } finally {
        assertNoLostAssertions()
    }

    fun assertFullReadback() {
        assertNoLostAssertions()
        assertEquals(2, http.createdClients)
        assertEquals(2, http.closedClients)
        assertEquals(2, returnedHttpCloses)
        assertEquals(4, http.requests.size)
        assertEquals(2, http.requests.count { it.rawQueryParameters().containsKey("versions") })
        assertEquals(http.requests.size, http.replies.size)
        http.replies.forEach {
            assertEquals(1, it.calls)
            assertTrue(it.eofProbes > 0)
            assertEquals(1, it.aborts)
            assertEquals(1, it.closes)
        }
    }

    fun assertReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(secrets.createdClients, secrets.closedClients)
        assertEquals(http.createdClients, http.closedClients) // Attempts only; returnedHttpCloses separately proves successful callbacks.
        scope?.let {
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, it.owner.observeShutdown())
            val rotation = ownedCutField(it.root, "epochRotationParticipant") as? PersistenceJdbcParticipant
            val actors = it.actors() + rotation?.let(it::actors).orEmpty()
            assertTrue(actors.all { actor -> actor.termination().ended() && !actor.thread.isAlive })
            assertEquals(PersistencePublicTrustRelease.RELEASED, it.owner.releasePublicTrustAfterShutdown())
            assertFalse(it.owner.snapshot().ordinaryReady || it.owner.snapshot().deletionReady)
        }
        (ownedCutField(operator, "release") as? CatalogGenesisFinalizeReleaseV1)?.let {
            val custody: CatalogGenesisReleaseCustodyV1 = poolTestField(it, "custody")
            assertTrue(poolTestField<LinuxGenesisReleaseFilesV1>(custody, "files").cleanupComplete())
        }
        fixture.assertNoTargetSessions()
    }

    fun fixtureCleanup() {
        if (cleanupVerified) return assertNoLostAssertions()
        assertSame(caller, Thread.currentThread())
        clock.onSample = {}
        runCatching(operator::close) // Failed command cleanup is sticky; fixture retirement cannot return its result.
        scope?.let {
            it.owner.requestShutdown()
            it.owner.versionBoundPools?.close()
            it.close()
        }
        requireConnectionFree()
        // An original quarantined phase may have prevented dispatch. Only after its real root/lease retirement,
        // close the SAME retained custody, without another descriptor, replacement owner or renewed command budget.
        runCatching { (ownedCutField(operator, "release") as? AutoCloseable)?.close() }
        assertReleased()
        cleanupVerified = true
        assertNoLostAssertions()
    }

    fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    private fun observeOriginalAssembly() {
        val owner = ownedCutField(assembly, "targetOwner") as? PersistenceJdbcLifecycleOwner ?: return
        if (scope == null) scope = PgLifecycleTestScope(owner)
        if (!poolTestField<Boolean>(assembly, "stopping")) {
            target = ownedCutField(assembly, "assembled") as? VersionBoundComplaintProcessConfiguration
        }
        if (jdbc != null) return
        val coordinator = ownedCutField(owner, "catalogResources") as? CatalogCoordinatorPersistence ?: return
        if (ownedCutField(coordinator, "genesisExecutor") == null) return
        val probe = GenesisProbeJdbc(coordinator)
        probe.beforeSql = { step ->
            preserveAssertions {
                val phase = checkNotNull(PersistencePhaseOwnership.current())
                phases.add(phase)
                sql.add(poolTestField<PersistencePhasePath>(phase, "path") to step)
                beforeSql(step)
            }
        }
        probe.afterSql = { step -> preserveAssertions { afterSql(step) } }
        coordinator.javaClass.getDeclaredField("genesisExecutor").also { it.isAccessible = true }
            .set(coordinator, ComplaintCatalogGenesisPersistencePhaseExecutor(coordinator.ownership, probe))
        coordinator.javaClass.getDeclaredField("executor").also { it.isAccessible = true }
            .set(coordinator, ComplaintCatalogSnapshotPhaseExecutor(coordinator.ownership, JdbcCatalogSnapshotReader(probe)))
        jdbc = probe
    }

    private fun httpClient(): SdkHttpClient = connectionFree {
        val delegate = http.httpClient()
        val ordinal = http.createdClients
        object : SdkHttpClient by delegate {
            override fun prepareRequest(request: HttpExecuteRequest) = connectionFree { delegate.prepareRequest(request) }
            override fun close() {
                connectionFree {
                    assertTrue(http.replies.all { it.closes == 1 }, "Actual response bodies close before their HTTP clients.")
                    delegate.close()
                    afterHttpClose(ordinal)
                    returnedHttpCloses++
                }
            }
        }
    }

    private fun reply(request: SdkHttpRequest): S3CatalogReply {
        val settings = checkNotNull(fixture.inputs.catalog)
        val location = settings.chainPolicy.trustBundlePolicy.expectedCatalogLocations.single {
            request.encodedPath() == "/${it.bucket}" || request.encodedPath().startsWith("/${it.bucket}/")
        }
        assertEquals("GET", request.method().name)
        assertEquals("https", request.protocol())
        assertEquals("s3.${location.region}.amazonaws.com", request.host())
        assertEquals(location.accountId, request.firstMatchingHeader("x-amz-expected-bucket-owner").orElseThrow())
        assertTrue(request.firstMatchingHeader("Authorization").orElseThrow().contains("/${location.region}/s3/aws4_request"))
        val key = CatalogReadbackProtocol.key(1)
        val bytes = fixture.envelope
        val response = if (request.rawQueryParameters().containsKey("versions")) {
            assertEquals(settings.pageSize.toString(), request.firstMatchingRawQueryParameter("max-keys").orElseThrow())
            assertFalse(request.rawQueryParameters().containsKey("key-marker"))
            assertFalse(request.rawQueryParameters().containsKey("version-id-marker"))
            http.listReply(
                CatalogListRequest(location, CatalogReadbackProtocol.PREFIX, null, settings.pageSize),
                listOf(CatalogListedVersion(key, S3CatalogReadbackFixture.VERSION, bytes.size.toLong())),
            )
        } else {
            assertEquals("/${location.bucket}/$key", request.encodedPath())
            assertEquals(S3CatalogReadbackFixture.VERSION, request.firstMatchingRawQueryParameter("versionId").orElseThrow())
            http.getReply(CatalogGetRequest(location, key, S3CatalogReadbackFixture.VERSION), bytes).apply {
                headers = headers + ("x-amz-object-lock-retain-until-date" to listOf(fixture.retainUntil.toString()))
            }
        }
        return response.apply {
            beforeCall = { connectionFree {} }
            beforeRead = { connectionFree {} }
            onAbort = { connectionFree {} }
            onClose = { connectionFree {} }
        }
    }

    private fun <T> connectionFree(action: () -> T): T = preserveAssertions {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive())
        action()
    }

    private fun assertNoLostAssertions() {
        clock.assertNoLostAssertions()
        assertion.get()?.let { throw it }
    }
}

internal fun rethrowTargetFinalizeFixtureFailures(results: List<Result<*>>) {
    val failures = results.mapNotNull { it.exceptionOrNull() }
    failures.firstOrNull()?.let { first ->
        failures.drop(1).filterNot { it === first || first.suppressed.any { previous -> previous === it } }.forEach(first::addSuppressed)
        throw first
    }
}
