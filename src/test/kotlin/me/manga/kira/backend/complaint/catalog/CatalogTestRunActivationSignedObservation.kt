package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationEnvelopeV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunSignedPreparedV1
import me.manga.kira.backend.complaint.infrastructure.catalog.LinuxTestRunActivationReleaseFilesV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogTestRunActivationPhaseExecutorV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Composition of the existing ConnectedIT rows, raw KMS and JDBC probe; no new service/provider harness. */
internal fun withSignedActivationRows(
    tls: VersionBoundPersistenceConnectedFixture,
    prefix: ActivationEvidencePrefix = ActivationEvidencePrefix.INVENTORY_ROTATED,
    selectedSigner: String = if (prefix == ActivationEvidencePrefix.GENESIS) "catalog-old" else "catalog-new",
    createGlobal: Int = 2,
    ordinarySealHttp: TestOrdinarySealHttpFixtureV1? = null,
    ownerDeleteAll: Boolean = false,
    ordinaryDrain: TestOrdinaryDrainFixtureInputsV1? = null,
    registeredAdminDelete: Boolean = false,
    action: (SignedActivationObservation) -> Unit,
) = withPreparedActivationRows(tls, prefix, selectedSigner, createGlobal = createGlobal, ordinarySealHttp = ordinarySealHttp, ownerDeleteAll = ownerDeleteAll, ordinaryDrain = ordinaryDrain, registeredAdminDelete = registeredAdminDelete) { rows ->
    SignedActivationObservation(tls, rows).use { observed ->
        observed.probe(tls) // The real template is installed before any owner/input exists.
        tls.startCatalogTestRunActivation()
        action(observed)
    }
}

/** Only test observations and strictly owned temporary paths. Historical predecessor inputs remain synthetic. */
internal class SignedActivationObservation(
    val tls: VersionBoundPersistenceConnectedFixture,
    val rows: PreparedActivationRows,
) : AutoCloseable {
    val signing = AwsJournalKmsFixture()
    val signatures = mutableListOf<ByteArray>()
    var beforeSign: () -> Unit = {}
    var additionalCleanupObservation: () -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()
    private val probes = linkedMapOf<VersionBoundPersistenceConnectedFixture, CatalogSignerRotationProbeJdbc>()
    private val originals = mutableListOf<CatalogTestRunActivationV1>()
    private val contenders = mutableListOf<Thread>()
    private val physicallyClosedWithoutReceipt = mutableSetOf<Any>()
    private val parent = Files.createTempDirectory(
        Path.of(System.getProperty("user.home")).toRealPath(), "kira-test-signed-prepared-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    val root: Path = Files.createDirectory(parent.resolve("release"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val allocation: Path get() = root.resolve("test-run-activation")
    val token: UUID = UUID.fromString(rows.evidence.token)

    init {
        signing.respond = { request -> observed {
            releasedSql()
            assertTrue(signatures.isEmpty(), "Only the original SINGLE Sign, never a retry or another signer.")
            val fields = request.fields()
            assertEquals("TrentService.Sign", request.target())
            assertEquals(setOf("KeyId", "Message", "MessageType", "SigningAlgorithm"), fields.fieldNames().asSequence().toSet())
            assertEquals(FullTestCatalogInputs.KEY_ARN, fields["KeyId"].textValue())
            assertEquals("RAW", fields["MessageType"].textValue())
            assertEquals("RSASSA_PSS_SHA_256", fields["SigningAlgorithm"].textValue())
            assertEquals(SdkHttpMethod.POST, request.http.method())
            assertEquals("https", request.http.protocol())
            assertEquals("kms.us-east-1.amazonaws.com", request.http.host())
            val authorization = request.http.firstMatchingHeader("Authorization").orElseThrow()
            assertTrue(authorization.contains("Credential=${AwsJournalKmsFixture.CREDENTIALS.accessKeyId()}/"))
            assertTrue(authorization.contains("/us-east-1/kms/aws4_request"))
            assertEquals(AwsJournalKmsFixture.CREDENTIALS.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
            val frame = Base64.getDecoder().decode(fields["Message"].textValue())
            assertArrayEquals(OfflineCatalogGenesisFixture.independentFrame(rows.evidence.signerId, rows.intent), frame)
            assertTrue(complete(CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED))
            assertTrue(complete(CatalogTestRunActivationReleaseLeafV1.PREPARED))
            assertTrue(complete(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED))
            beforeSign()
            val signature = Signature.getInstance("RSASSA-PSS").run {
                setParameter(OfflineTrustBundleFixture.parameters)
                initSign(key().private)
                update(frame)
                sign()
            }
            signatures.add(signature.copyOf())
            JournalKmsHttpReply(
                """{"KeyId":"${FullTestCatalogInputs.KEY_ARN}","SigningAlgorithm":"RSASSA_PSS_SHA_256","Signature":"${Base64.getEncoder().encodeToString(signature)}"}""",
            ).apply {
                beforeCall = { observed(::releasedSql) }
                beforeRead = { observed(::releasedSql) }
                onAbort = { observed(::releasedSql) }
                onClose = { observed(::releasedSql) }
            }
        } }
    }

    fun probe(selected: VersionBoundPersistenceConnectedFixture = tls): CatalogSignerRotationProbeJdbc = probes.getOrPut(selected) {
        val coordinator = selected.pools.catalogCoordinator
        CatalogSignerRotationProbeJdbc(coordinator, observeTestActivationQueries = true).also { jdbc ->
            coordinator.javaClass.getDeclaredField("testRunActivationExecutor").apply { check(trySetAccessible()) }
                .set(coordinator, ComplaintCatalogTestRunActivationPhaseExecutorV1(coordinator, jdbc))
        }
    }

    fun begin(
        selected: VersionBoundPersistenceConnectedFixture = tls,
        process: VersionBoundTestNamespaceProcessV1 = if (selected === tls) rows.evidence.process else rows.evidence.processOn(selected.pools),
        signingFactory: () -> SdkHttpClient = ::openSigningHttp,
    ): CatalogTestRunActivationV1 {
        probe(selected)
        return CatalogTestRunActivationV1.withHttpFixtures(
            process, CatalogTestRunActivationEvidenceFixture.INSTALLATION_LIMIT, signingFactory, rows.http::httpClient, WALL_CLOCK,
        ).also(originals::add)
    }

    fun beginDelivery(
        selected: VersionBoundPersistenceConnectedFixture,
        putFactory: () -> SdkHttpClient,
        readbackFactory: () -> SdkHttpClient,
        process: VersionBoundTestNamespaceProcessV1 = if (selected === tls) rows.evidence.process else rows.evidence.processOn(selected.pools),
    ): CatalogTestRunActivationV1 {
        probe(selected)
        return CatalogTestRunActivationV1.withDeliveryHttpFixtures(
            process, CatalogTestRunActivationEvidenceFixture.INSTALLATION_LIMIT, putFactory, readbackFactory, WALL_CLOCK,
        ).also(originals::add)
    }

    fun freeze(original: CatalogTestRunActivationV1, bytes: ByteArray = rows.intent, releaseRoot: Path = root): CatalogTestRunSignedPreparedV1 {
        rows.retainPreparedToken() // Before even a failed COMMIT can make this exact operation durable.
        return original.prepareAndFreeze(releaseRoot, bytes, AwsJournalKmsFixture.CREDENTIALS,
            S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
    }

    fun recover(original: CatalogTestRunActivationV1, bytes: ByteArray = rows.intent, releaseRoot: Path = root): CatalogTestRunSignedPreparedV1 =
        original.recoverSignature(releaseRoot, bytes, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun withFreshOwner(
        previous: VersionBoundPersistenceConnectedFixture = tls,
        nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
        action: (VersionBoundPersistenceConnectedFixture) -> Unit,
    ) {
        previous.close() // End the actual original root before opening the new graph.
        rows.awaitRealLeaseExpiry() // No SQL backdating, replacement lease result or old budget revival.
        // The endpoint participates in full D: transport-loss fixtures keep it unchanged from freeze through cold replay.
        VersionBoundPersistenceConnectedFixture(tls.database, testActivation = true, endpointPort = tls.endpointPort).use { fresh ->
            fresh.bind(nanoClock = nanoClock)
            probe(fresh)
            fresh.startCatalogTestRunActivation()
            action(fresh)
        }
    }

    fun assertSigned(receipt: CatalogTestRunSignedPreparedV1? = null) {
        releasedSql()
        val member = rows.evidence.manifest.requiredSignerPolicy.members.single()
        val signature = signatures.single()
        val bytes = CatalogTestRunActivationEvidenceFixture.bytes(OfflineCatalogTestRunActivationEnvelopeV3(
            3, rows.evidence.manifest,
            listOf(OfflineCatalogGenesisSignatureV1(member.keyId, member.algorithmId, Base64.getEncoder().encodeToString(signature))),
        ))
        assertTrue(bytes.size <= OfflineCatalogTestRunActivationProtocol.MAX_DOCUMENT_BYTES)
        val row = row()
        assertEquals("PREPARED", row["state"])
        assertEquals("TEST_RUN_ACTIVATION", row["operation_type"])
        assertEquals(rows.evidence.journal.scope.id, row["data_scope_id"])
        assertEquals(true, row["test_only"])
        assertEquals(rows.evidence.generation, row["successor_generation"])
        assertEquals(rows.evidence.generation - 1, row["predecessor_generation"])
        assertArrayEquals(hash(rows.evidence.prefix.last()), row["predecessor_hash"] as ByteArray)
        assertArrayEquals(rows.intent, row["unsigned_bytes"] as ByteArray)
        assertArrayEquals(hash(rows.intent), row["unsigned_hash"] as ByteArray)
        assertEquals("SINGLE", row["signer_policy"])
        assertEquals(member.keyId, row["signer_one_id"])
        assertEquals(member.algorithmId, row["signer_one_algorithm"])
        assertArrayEquals(signature, row["signer_one_signature"] as ByteArray)
        assertArrayEquals(bytes, row["envelope_bytes"] as ByteArray)
        assertArrayEquals(hash(bytes), row["envelope_hash"] as ByteArray)
        for (column in listOf("signer_two_id", "signer_two_algorithm", "signer_two_signature", "object_version", "retain_until",
            "primary_evidence_bytes", "primary_evidence_hash", "replica_evidence_bytes", "replica_evidence_hash", "completed_at", "projected_at")) {
            assertNull(row[column], column)
        }
        assertArrayEquals(signature, read(CatalogTestRunActivationReleaseLeafV1.SIGNATURE))
        assertArrayEquals(bytes, read(CatalogTestRunActivationReleaseLeafV1.ENVELOPE))
        assertTrue(Signature.getInstance("RSASSA-PSS").run {
            setParameter(OfflineTrustBundleFixture.parameters)
            initVerify(key().public)
            update(OfflineCatalogGenesisFixture.independentFrame(member.keyId, rows.intent))
            verify(signature)
        })
        receipt?.let {
            assertEquals(token, it.operationToken)
            assertEquals(rows.evidence.journal.scope.id, it.dataScopeId)
            assertEquals(rows.evidence.generation, it.generation)
            assertEquals(Sha256.hex(rows.intent), it.unsignedSha256)
            assertEquals(Sha256.hex(bytes), it.envelopeSha256)
        }
        rows.assertClosedAndHeadUnchanged()
    }

    fun row(): Map<String, Any?> = rows.observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", token)
    fun retainContender(thread: Thread) { contenders.add(thread) }
    fun path(leaf: CatalogTestRunActivationReleaseLeafV1): Path = allocation.resolve(leaf.fileName)
    fun marker(leaf: CatalogTestRunActivationReleaseLeafV1): Path = path(leaf).resolveSibling("${leaf.fileName}.complete")
    fun exists(leaf: CatalogTestRunActivationReleaseLeafV1): Boolean = Files.exists(path(leaf), NOFOLLOW_LINKS)
    fun read(leaf: CatalogTestRunActivationReleaseLeafV1): ByteArray = Files.readAllBytes(path(leaf))
    fun complete(leaf: CatalogTestRunActivationReleaseLeafV1): Boolean = exists(leaf) && Files.isRegularFile(marker(leaf), NOFOLLOW_LINKS) &&
        Files.readAllBytes(marker(leaf)).contentEquals(ByteBuffer.allocate(68).putInt(read(leaf).size).put(Sha256.hex(read(leaf)).toByteArray(Charsets.US_ASCII)).array())

    /** Never open/read/close the permanent lock inode while an original custody owner holds it. */
    fun leaves(): Map<Path, Pair<Map<String, Any>, String>> = if (!Files.exists(allocation, NOFOLLOW_LINKS)) emptyMap() else Files.walk(allocation).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith {
            Files.readAttributes(it, "unix:dev,ino,uid,gid,mode,nlink", NOFOLLOW_LINKS) to Base64.getEncoder().encodeToString(Files.readAllBytes(it))
        }
    }

    fun releasedSql() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        probes.keys.forEach { assertEquals(0, it.pools.catalogCoordinator.activeSnapshotOwners()) }
    }

    fun assertReleased(original: CatalogTestRunActivationV1, selected: VersionBoundPersistenceConnectedFixture = tls) {
        original.requireActualCleanup()
        releasedSql()
        assertNull(active(selected.pools.catalogCoordinator))
        assertTrue(poolTestField<Boolean>(original, "released"))
        assertTrue(poolTestField<Boolean>(original, "cleanupProven"))
        assertNull(ownedCutField(original, "closeFailure"))
        probe(selected).observations.keys.forEach {
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, it.databaseOutcome())
            assertTrue(it.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        }
        assertEquals(signing.createdClients, signing.returnedClientCloses)
        rows.assertHttpReleased()
        assertNoLostAssertions()
    }

    /**
     * A synthetic Spring sentinel prevented even issuing file close. After the caller removes that
     * exact sentinel and the same SQL phase physically retires, finish ONLY this retained file owner.
     * This is not a retry of an uncertain native close or a repaired activation cleanup receipt.
     */
    fun closeUnissuedCustodyAfterSqlReconciliation(
        original: CatalogTestRunActivationV1,
        selected: VersionBoundPersistenceConnectedFixture,
        phase: PersistencePhaseContext,
    ) {
        releasedSql()
        assertTrue(originals.any { it === original })
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertFalse(phase.quarantined())
        val budget = original.budget
        val closeFailure = poolTestField<CatalogTestRunActivationExceptionV1>(original, "closeFailure")
        fun assertOriginalStillFailed() {
            assertSame(budget, original.budget)
            assertSame(phase, ownedCutField(original, "originalPhase"))
            assertSame(original, active(selected.pools.catalogCoordinator))
            assertSame(closeFailure, ownedCutField(original, "closeFailure"))
            assertSame(closeFailure, assertThrows<CatalogTestRunActivationExceptionV1> { original.close() })
            for (field in listOf("closed", "failed", "sqlCleanupUnproven")) assertTrue(poolTestField<Boolean>(original, field), field)
            for (field in listOf("outcomeUncertain", "released", "cleanupProven", "allowedResult", "allowedSignedResult", "allowedCompletedResult", "allowedProjectedResult")) {
                assertFalse(poolTestField<Boolean>(original, field), field)
            }
        }
        assertOriginalStillFailed()
        val custody = poolTestField<CatalogTestRunActivationReleaseCustodyV1>(original, "custody")
        val files = poolTestField<LinuxTestRunActivationReleaseFilesV1>(custody, "files")
        val held = poolTestField<List<Any>>(files, "resources").toList()
        val lock = held.mapNotNull { ownedCutField(it, "value") as? FileLock }.single()
        assertSame(Thread.currentThread(), ownedCutField(custody, "caller"))
        assertSame(budget, ownedCutField(custody, "originalBudget"))
        assertFalse(poolTestField<Boolean>(custody, "closeIssued"))
        assertTrue(held.all { !poolTestField<Boolean>(it, "closeIssued") })
        assertTrue(lock.isValid)
        assertSame(custody, rootClaimObservation().first)
        val before = leaves()
        custody.close() // Original caller, handles and budget; no reflection write or replacement owner.
        assertTrue(poolTestField<Boolean>(custody, "closeIssued"))
        assertTrue(held.all { poolTestField<Boolean>(it, "closeIssued") })
        assertTrue(files.cleanupComplete())
        assertFalse(lock.isValid)
        assertNull(rootClaimObservation().first)
        assertEquals(before, leaves(), "Actual file disposal never fills an old outcome or changes custody bytes.")
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertOriginalStillFailed()
    }

    /** Actual original FileLock.close first; its lost cleanup receipt remains sticky and is never repaired. */
    fun failOriginalFileClose(original: CatalogTestRunActivationV1): () -> Boolean {
        val custody = checkNotNull(ownedCutField(original, "custody"))
        val files: Any = poolTestField(custody, "files")
        val held = poolTestField<List<Any>>(files, "resources").single { ownedCutField(it, "value") is FileLock }
        val lock = ownedCutField(held, "value") as FileLock
        var closed = false
        val wrapper = AutoCloseable {
            lock.close()
            assertFalse(lock.isValid)
            closed = true
            physicallyClosedWithoutReceipt.add(held)
            throw IOException("Synthetic TEST original file close receipt lost after actual close.")
        }
        held.javaClass.getDeclaredField("value").apply { check(trySetAccessible()) }.set(held, wrapper)
        return { closed }
    }

    fun assertNoLostAssertions() {
        probes.values.forEach(CatalogSignerRotationProbeJdbc::assertNoLostAssertions)
        assertion.get()?.let { throw it }
    }

    /** Bounded observations only; do not expose roots, inode keys or the discarded provider/SQL failure. */
    fun setupCustodyObservation(original: CatalogTestRunActivationV1): String = runCatching {
        val custody = ownedCutField(original, "custody")
        val (claim, count) = rootClaimObservation()
        "custody_retained=${custody != null} release_retained=${ownedCutField(original, "release") != null} " +
            "root_claimed=${claim != null} root_claimed_by_setup=${claim != null && claim === custody} active_root_claims=$count"
    }.getOrDefault("custody_observation_available=false")

    private fun rootClaimObservation(): Pair<Any?, Int> {
        val key = Files.readAttributes(root, "unix:fileKey", NOFOLLOW_LINKS).getValue("fileKey")
        val claims = CatalogTestRunActivationReleaseCustodyV1::class.java.getDeclaredField("claims")
            .apply { check(trySetAccessible()) }.get(null) as Map<*, *>
        return synchronized(claims) { claims[key] to claims.size }
    }

    override fun close() {
        probes.values.forEach { it.beforeSql = {}; it.afterSql = {} }
        val threadsStopped = runCatching {
            contenders.forEach {
                if (it.isAlive) { it.interrupt(); it.join(5_000) }
                assertFalse(it.isAlive, "Original contender must retire before fixture paths or rows.")
            }
        }
        val stopped = probes.keys.map { runCatching(it::close) }
        val disposed = originals.map { original -> runCatching {
            runCatching(original::close)
            (ownedCutField(original, "assembly") as? AutoCloseable)?.let { runCatching(it::close) }
            (ownedCutField(original, "deliveryAssembly") as? AutoCloseable)?.let { runCatching(it::close) }
            (ownedCutField(original, "custody") as? AutoCloseable)?.let { custody ->
                runCatching(custody::close) // Only its actual retained handles; no state/receipt reset or replacement owner.
                val files: Any = poolTestField(custody, "files")
                val remaining: List<Any> = poolTestField(files, "resources")
                assertTrue(remaining.all { it in physicallyClosedWithoutReceipt && poolTestField<Boolean>(it, "closeIssued") })
            }
        } }
        val ready = runCatching {
            threadsStopped.getOrThrow()
            stopped.forEach { it.getOrThrow() }
            disposed.forEach { it.getOrThrow() }
            releasedSql()
            assertEquals(signing.createdClients, signing.closedClients)
            assertEquals(rows.http.createdClients, rows.http.closedClients)
            additionalCleanupObservation()
            assertNoLostAssertions()
        }
        val removed = runCatching {
            ready.getOrThrow()
            val claim = rootClaimObservation().first
            Files.walk(parent).use { entries ->
                entries.sorted(Comparator.reverseOrder()).filter { claim == null || (it != root && it != parent) }.forEach(Files::delete)
            }
            if (claim != null) {
                // Keep only the empty claimed directory and its parent until normal JVM exit. Deleting
                // this inode now would let another fixture alias the original sticky (device,inode) claim.
                assertTrue(rootClaimObservation().first === claim, "Fixture cleanup must not clear or replace an original claim.")
                parent.toFile().deleteOnExit() // Reverse registration order deletes root before parent.
                root.toFile().deleteOnExit()
            }
        }
        rethrowSignerRotationFixtureFailures(listOf(threadsStopped) + stopped + disposed + listOf(ready, removed))
    }

    private fun openSigningHttp(): SdkHttpClient {
        releasedSql()
        assertTrue(complete(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED), "Durable arm precedes the actual raw SDK-client factory.")
        return signing.httpClient()
    }

    private fun key() = if (rows.evidence.signerId == "catalog-old") OfflineTrustBundleFixture.firstSigner else OfflineTrustBundleFixture.secondSigner
    private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
    private fun <T> observed(action: () -> T): T = try { action() } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    companion object {
        val WALL_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT), ZoneOffset.UTC)
        fun active(coordinator: CatalogCoordinatorPersistence): Any? = poolTestField<AtomicReference<Any?>>(coordinator.catalogRefreshCustody, "active").get()
    }
}
