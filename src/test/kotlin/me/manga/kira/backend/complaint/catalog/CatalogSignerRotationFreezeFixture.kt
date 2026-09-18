package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogSignerRotationPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCoordinatorLeasePersistencePhaseExecutor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal fun withCatalogSignerRotationFreeze(
    tls: VersionBoundPersistenceConnectedFixture,
    profile: String = "D7",
    test: (CatalogSignerRotationFreezeFixture) -> Unit,
) = CatalogSignerRotationFreezeFixture(tls).use { fixture ->
    fixture.prepare(profile)
    test(fixture)
}

/** Owns only the explicit operation2 token/files after original runtime retirement; G1/desired fixtures retain their own cleanup gates. */
internal class CatalogSignerRotationFreezeFixture(tls: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    val d7 = CatalogSignerRotationD7Fixture(tls)
    val observer get() = d7.observer
    val process get() = d7.process
    val coordinator get() = d7.coordinator
    val clock get() = d7.clock
    val campaign get() = d7.lease.campaign
    val invocations = mutableListOf<CatalogSignerRotationFreezeInvocation>()
    lateinit var jdbc: CatalogSignerRotationProbeJdbc
        private set
    lateinit var manifest: OfflineCatalogRotationManifestV1
        private set
    lateinit var request: CatalogSignerRotationFreezeRequestV1
        private set
    lateinit var releaseRoot: Path
        private set
    private var ownsToken = false
    private var requestIndex = 0
    val intent: ByteArray get() = OfflineCatalogRotationFixture.manifestBytes(manifest)
    val approvals: ByteArray get() = approvalBytes(manifest.approvals)
    val token: UUID get() = UUID.fromString(manifest.operationToken)

    fun prepare(profile: String) {
        d7.prepare(profile) { process ->
            val coordinator = process.pools.catalogCoordinator
            val observed = CatalogSignerRotationProbeJdbc(coordinator)
            // One actual JdbcTemplate is retained by BOTH the real lease campaign and the real rotation executor.
            coordinator.javaClass.getDeclaredField("leaseExecutor").also { it.isAccessible = true }
                .set(coordinator, ComplaintCoordinatorLeasePersistencePhaseExecutor(coordinator, observed))
            coordinator.javaClass.getDeclaredField("signerRotationExecutor").also { it.isAccessible = true }
                .set(coordinator, ComplaintCatalogSignerRotationPersistencePhaseExecutor(coordinator, observed))
            jdbc = observed
        }
        val genesis = Json.decodeFromString(OfflineCatalogGenesisEnvelopeV1.serializer(), d7.envelope.decodeToString())
        manifest = OfflineCatalogRotationFixture.manifest(genesis, 2, d7.envelope, "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new"))
        assertEquals(
            0L,
            observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations WHERE operation_token = ?", Long::class.java, token),
        )
        ownsToken = true
        releaseRoot = Files.createDirectory(
            d7.freeze.parent.resolve("rotation-release"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        request = request(manifest)
        jdbc.resetObservations() // Do not count the genuine prerequisite lease as a rotation READ/PREPARE/CAS.
    }

    fun invocation(): CatalogSignerRotationFreezeInvocation = CatalogSignerRotationFreezeInvocation(this).also(invocations::add)

    fun request(
        candidate: OfflineCatalogRotationManifestV1,
        approved: ByteArray = approvalBytes(candidate.approvals),
        root: Path = releaseRoot,
    ): CatalogSignerRotationFreezeRequestV1 {
        val index = ++requestIndex
        return CatalogSignerRotationFreezeRequestV1(
            d7.freeze.writeInput("rotation-intent-$index.json", OfflineCatalogRotationFixture.manifestBytes(candidate)),
            d7.freeze.writeInput("rotation-approvals-$index.json", approved),
            root,
        )
    }

    fun path(leaf: CatalogSignerRotationReleaseLeafV1): Path = releaseRoot.resolve("rotation-overlap-2").resolve(leaf.fileName)
    fun marker(leaf: CatalogSignerRotationReleaseLeafV1): Path = path(leaf).resolveSibling("${leaf.fileName}.complete")
    fun exists(leaf: CatalogSignerRotationReleaseLeafV1): Boolean = Files.exists(path(leaf), NOFOLLOW_LINKS)
    fun read(leaf: CatalogSignerRotationReleaseLeafV1): ByteArray = Files.readAllBytes(path(leaf))

    fun complete(leaf: CatalogSignerRotationReleaseLeafV1): Boolean {
        if (!exists(leaf) || !Files.isRegularFile(marker(leaf), NOFOLLOW_LINKS)) return false
        val bytes = read(leaf)
        val expected = ByteBuffer.allocate(68).putInt(bytes.size).put(Sha256.hex(bytes).toByteArray(Charsets.US_ASCII)).array()
        return Files.readAllBytes(marker(leaf)).contentEquals(expected)
    }

    fun row(): Map<String, Any?> = observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", token)
    fun state(): List<String> = d7.state()
    fun counters(): List<String> = d7.counters()

    /** No descriptor is opened on the permanent lock inode; leaf identity/bytes are read only after the real owner closes. */
    fun snapshotLeaves(): Map<Path, Pair<Map<String, Any>, ByteArray>> = Files.walk(releaseRoot.resolve("rotation-overlap-2")).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith { path ->
            Files.readAttributes(path, "unix:dev,ino,uid,gid,mode,nlink", NOFOLLOW_LINKS) to Files.readAllBytes(path)
        }
    }

    fun assertLeavesUnchanged(snapshot: Map<Path, Pair<Map<String, Any>, ByteArray>>, allowAdditionalLeaves: Boolean = false) {
        val current = snapshotLeaves()
        if (allowAdditionalLeaves) assertTrue(current.keys.containsAll(snapshot.keys)) else assertEquals(snapshot.keys, current.keys)
        snapshot.forEach { (path, fact) ->
            assertEquals(fact.first, current.getValue(path).first)
            assertArrayEquals(fact.second, current.getValue(path).second)
        }
    }

    override fun close() {
        if (::jdbc.isInitialized) {
            jdbc.beforeSql = {}
            jdbc.afterSql = {}
        }
        val stopped = runCatching(d7::retireRuntime)
        val retired = invocations.map { runCatching(it::fixtureCleanup) }
        val ready = runCatching {
            stopped.getOrThrow()
            assertTrue(invocations.all { it.cleanupVerified }, "Original rotation locks/provider owners must retire before fixture rows/files.")
        }
        val mutationRemoved = runCatching {
            ready.getOrThrow()
            if (ownsToken) observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", token)
        }
        val originalClosed = runCatching {
            ready.getOrThrow()
            mutationRemoved.getOrThrow()
            d7.close()
        }
        val assertions = runCatching { if (::jdbc.isInitialized) jdbc.assertNoLostAssertions() }
        rethrowSignerRotationFixtureFailures(listOf(stopped) + retired + listOf(ready, mutationRemoved, originalClosed, assertions))
    }

    private fun approvalBytes(values: List<OfflineCatalogGenesisApprovalV1>): ByteArray =
        CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), values).toByteArray(Charsets.UTF_8)
}

/** Raw G1-only HTTP for both genuine refresh and non-G1 authoring; real SDK/crypto/local snapshot/cleanup remain in place. */
internal class CatalogSignerRotationReadbackHttpFixture(private val d7: CatalogSignerRotationD7Fixture) {
    val http = S3CatalogReadbackFixture()
    var beforeRequest: (SdkHttpRequest) -> Unit = {}
    var afterClientClose: (Int) -> Unit = {}
    var returnedCloses = 0
        private set
    private val assertion = AtomicReference<AssertionError?>()
    private val settings = checkNotNull(d7.process.catalogReadback)

    init {
        http.respond = { request ->
            observed {
                beforeRequest(request)
                reply(request)
            }
        }
    }

    fun httpClient(): SdkHttpClient = observed {
        val delegate = http.httpClient()
        val ordinal = http.createdClients
        object : SdkHttpClient by delegate {
            override fun prepareRequest(request: HttpExecuteRequest) = observed { delegate.prepareRequest(request) }
            override fun close() = observed {
                assertTrue(http.replies.all { it.closes == 1 })
                delegate.close()
                afterClientClose(ordinal)
                returnedCloses++
            }
        }
    }

    fun assertCompletedReadbacks(attempts: Int) {
        assertCleaned()
        assertEquals(attempts * 2, http.createdClients)
        assertEquals(http.createdClients, returnedCloses)
        assertEquals(attempts * 4, http.requests.size)
        assertEquals(attempts * 2, http.requests.count { it.rawQueryParameters().containsKey("versions") })
        assertEquals(http.requests.size, http.replies.size)
        http.replies.forEach { assertTrue(it.eofProbes > 0) }
    }

    fun assertCleaned() {
        assertNoLostAssertions()
        assertTransportDisposed()
    }

    fun assertNoLostAssertions() {
        assertion.get()?.let { throw it }
    }

    /** Actual synthetic resource disposal, separate from preserved assertions so teardown can finish before surfacing them. */
    fun assertTransportDisposed() {
        assertEquals(http.createdClients, http.closedClients)
        http.replies.forEach {
            assertEquals(1, it.calls)
            assertEquals(1, it.aborts)
            assertEquals(1, it.closes)
        }
        assertTrue(http.requests.all { it.method() === SdkHttpMethod.GET })
    }

    private fun reply(request: SdkHttpRequest): S3CatalogReply {
        val location = settings.chainPolicy.trustBundlePolicy.expectedCatalogLocations.single {
            request.encodedPath() == "/${it.bucket}" || request.encodedPath().startsWith("/${it.bucket}/")
        }
        assertEquals("https", request.protocol())
        assertEquals("s3.${location.region}.amazonaws.com", request.host())
        assertEquals(location.accountId, request.firstMatchingHeader("x-amz-expected-bucket-owner").orElseThrow())
        assertTrue(request.firstMatchingHeader("Authorization").orElseThrow().contains("/${location.region}/s3/aws4_request"))
        assertEquals(S3CatalogReadbackFixture.credentials.sessionToken(), request.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val key = CatalogReadbackProtocol.key(1)
        val reply = if (request.rawQueryParameters().containsKey("versions")) {
            assertEquals(settings.pageSize.toString(), request.firstMatchingRawQueryParameter("max-keys").orElseThrow())
            assertFalse(request.rawQueryParameters().containsKey("key-marker") || request.rawQueryParameters().containsKey("version-id-marker"))
            http.listReply(
                CatalogListRequest(location, CatalogReadbackProtocol.PREFIX, null, settings.pageSize),
                listOf(CatalogListedVersion(key, S3CatalogReadbackFixture.VERSION, d7.envelope.size.toLong())),
            )
        } else {
            assertEquals("/${location.bucket}/$key", request.encodedPath())
            assertEquals(S3CatalogReadbackFixture.VERSION, request.firstMatchingRawQueryParameter("versionId").orElseThrow())
            http.getReply(CatalogGetRequest(location, key, S3CatalogReadbackFixture.VERSION), d7.envelope).apply {
                headers = headers + ("x-amz-object-lock-retain-until-date" to listOf(d7.retainUntil.toString()))
            }
        }
        return reply.apply {
            beforeCall = { observed {} }
            beforeRead = { observed {} }
            onAbort = { observed {} }
            onClose = { observed {} }
        }
    }

    private fun <T> observed(action: () -> T): T = try {
        d7.released()
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        action()
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }
}
