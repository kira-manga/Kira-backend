package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.infrastructure.persistence.ColdFixtureFilesV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.TestOrdinaryDrainConnectedIT
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.ActiveSealRecoveryHistoryCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealFixtureV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryExceptionV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryStepV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOrdinarySealRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveSealRecoveryObservationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveSealRecoveryProbeV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.withActiveSealRecoveryHistory
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.system.exitProcess

/** Two actual JVMs, source-only. Parent observes original exit and NATURAL old lease expiry before new original construction. */
internal object TestActiveSealRecoveryColdProcessCasesV1 {
    fun qualify(existingObject: Boolean) {
        assumeTrue(System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN") == null,
            "Two-JVM qualification retains the existing owned Testcontainers/TLS attachment; no local-controller allowlist expansion.")
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-empty-seal-two-jvm-",
            PosixFilePermissions.asFileAttribute(ColdFixtureFilesV1.directoryMode))
        val children = mutableListOf<Process>()
        val database = PgLifecycleDatabaseFixture(TestOrdinaryDrainConnectedIT::class.java)
        try {
            database.start()
            val jdbc = JdbcTemplate(ordinaryCleanupReader(database))
            val generation = ColdSqlObservationV1.generation(jdbc)
            val descriptor = database.exportColdChildAttachment(root)
            val descriptorHash = ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(descriptor))
            val first = ComplaintTestColdProcessSupportV1.runChild(root, descriptorHash, "author", existingObject, children, ColdTestProcessEntryV1.ACTIVE_EMPTY_SEAL_RECOVERY)
            val saved = ColdActiveSealRecoveryRawV1.read(root)
            assertEquals(first.first.pid(), saved.authorPid); assertEquals(first.second.toString(), saved.authorStart)
            assertEquals(existingObject, saved.existingObject)
            ColdSqlObservationV1.noRuntimeSessions(jdbc)
            assertEquals(generation, ColdSqlObservationV1.generation(jdbc))
            assertTrue(saved.image == TestActiveSealRecoveryObservationV1.image(jdbc), "Original process exit cannot reset/repair paid rows.")
            ColdSqlObservationV1.awaitLeaseExpiry(jdbc, UUID.fromString(saved.scope))
            assertTrue(saved.image == TestActiveSealRecoveryObservationV1.image(jdbc), "No current token, retention or clock reset during natural expiry.")
            val immutable = listOf("owned-container.txt", "test-deployment.json", "empty-seal-recovery-raw.json").associateWith {
                ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve(it)))
            }
            val second = ComplaintTestColdProcessSupportV1.runChild(root, descriptorHash, "recover", existingObject, children, ColdTestProcessEntryV1.ACTIVE_EMPTY_SEAL_RECOVERY)
            assertTrue(first.first.pid() != second.first.pid(), "Distinct actual JVMs, not just fresh assembly in the author JVM.")
            ColdSqlObservationV1.noRuntimeSessions(jdbc)
            assertEquals(generation, ColdSqlObservationV1.generation(jdbc))
            immutable.forEach { (file, digest) -> assertEquals(digest, ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve(file)))) }
            TestActiveSealRecoveryObservationV1.completed(saved.image, jdbc, UUID.fromString(saved.scope))
        } finally {
            children.forEach(ComplaintTestColdProcessSupportV1::stopOwnedChild)
            check(children.none(Process::isAlive))
            database.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

/** TEST-only fixed shared-launcher entry; the fourth boolean chooses existing-object history, NOT a new charge. */
internal object TestActiveSealRecoveryColdProcessV1 {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4 && args[2] in setOf("author", "recover") && args[3] in setOf("true", "false"))
        val root = Path.of(args[0]); val stage = args[2]; val existing = args[3].toBooleanStrict()
        try {
            PgLifecycleDatabaseFixture.attachColdChild(root.resolve("owned-container.txt"), args[1]).use { database ->
                if (stage == "author") author(database, root, existing) else recover(database, root, existing)
            }
        } catch (problem: Throwable) {
            val safe = "stage=$stage type=${problem.javaClass.name}\n" + problem.stackTrace.take(10).joinToString("\n") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            runCatching { ColdFixtureFilesV1.write(root.resolve("$stage-failure.txt"), safe.toByteArray()) }
            exitProcess(1) // No SQL row, provider prose, secret, canonical content or token leaves private scratch.
        }
    }

    private fun author(database: PgLifecycleDatabaseFixture, root: Path, existing: Boolean): Nothing {
        VersionBoundPersistenceConnectedFixture(database, testActivation = true, activeFirstCut = true).use { tls ->
            tls.bind()
            val cut = if (existing) ActiveSealRecoveryHistoryCutV1.EXISTING_WIRE else ActiveSealRecoveryHistoryCutV1.CANONICAL
            withActiveSealRecoveryHistory(tls, cut) { h ->
                h.assertReleased(); h.first.awaitNativeReclaimed()
                assertEquals("SEAL_PREPARED", h.control()["seal_state"])
                val saved = ColdActiveSealRecoveryRawV1.capture(h, root, existing)
                h.close() // Restore passive templates/callbacks; not runtime cleanup.
                h.registration.close()
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { h.registration.requireReleasedIdentityAdmission() }
                h.runtime.closeRegisteredRuntimeForRecovery()
                h.p.f.signed.close() // All earlier actual activation/publisher/provider/root owners also retire.
                h.native.close(); h.native.assertDisposed(); h.ordinary.assertDisposed()
                assertEquals(h.p.f.http.read.createdClients, h.p.f.http.read.closedClients)
                ColdSqlObservationV1.noRuntimeSessions(h.observer)
                assertTrue(saved.image == TestActiveSealRecoveryObservationV1.image(h.observer))
                saved.write(root)
                database.close() // Borrowed attachment only; parent still owns the server and its TLS generation.
                // Only OUTER TEST row teardown is bypassed. No live original native/runtime/session owner remains.
                exitProcess(0)
            }
        }
        error("Author must exit only after actual original graph retirement.")
    }

    private fun recover(database: PgLifecycleDatabaseFixture, root: Path, existing: Boolean) {
        val saved = ColdActiveSealRecoveryRawV1.read(root)
        assertEquals(existing, saved.existingObject)
        val old = ProcessHandle.of(saved.authorPid).orElse(null)
        assertFalse(old != null && old.isAlive && old.info().startInstant().orElse(null)?.toString() == saved.authorStart)
        assertTrue(ProcessHandle.current().pid() != saved.authorPid)
        val jdbc = JdbcTemplate(ordinaryCleanupReader(database))
        ColdSqlObservationV1.noRuntimeSessions(jdbc)
        assertTrue(saved.image == TestActiveSealRecoveryObservationV1.image(jdbc))
        val document = Json.decodeFromString(ComplaintTestDeploymentDocumentV1.serializer(),
            ColdFixtureFilesV1.read(root.resolve("test-deployment.json")).decodeToString())
        val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(document) // Only raw fixture expectations; protected assembly reparses.
        assertEquals(ComplaintTestDeploymentInputsV1.ACTIVE_SEAL_RECOVERY_PROFILE, document.profile)
        var boundary: () -> Unit = {}
        val raw = ColdIdentityRawProvidersV1(saved.secrets, saved.catalog) { requireConnectionFree(); boundary() }
        TestOrdinarySealHttpFixtureV1(Instant.parse(document.retention.lastPreRunRestoreHorizon), document.retention.horizonPolicy, protectedIntake = true).use { native ->
            native.prepareIndependent(inputs.journal)
            native.stored = saved.seal?.raw() // Raw bytes/headers backing, NEVER a codec/registration/verification capability.
            try {
                ComplaintTestProcessAssemblyV1.withHttpFixture(raw.secrets::httpClient, PersistenceNanoClock(native::nanos), native::now,
                    PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY, native::nativeSts, native::nativeKms, native::nativeS3,
                    ordinarySts = { error("EMPTY recovery must not open ordinary STS") }, ordinaryKms = { error("EMPTY recovery must not open ordinary KMS") },
                    ordinaryS3 = { error("EMPTY recovery must not open ordinary S3") }).use { assembly ->
                    assembly.assemble(root.resolve("test-deployment.json"), AwsSecretVersionFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS,
                        TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
                    assertEquals(saved.fullDSha256, ColdFixtureFilesV1.sha256(assembly.target.canonicalBytes()))
                    assertEquals(saved.secrets.size, raw.secrets.requests.size)
                    VersionBoundPersistenceConnectedFixture(database, testIntake = assembly).use { runtime ->
                        runtime.bind(); runtime.start()
                        assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
                        assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                        assertTrue(saved.image == TestActiveSealRecoveryObservationV1.image(jdbc), "Cold assembly cannot migrate/PROJECT/charge or rewrite the history.")
                        val identityProbe = TestActiveRegistrationSqlProbeV1(ColdSqlObservationV1::advisory, runtime)
                        val identityExecutor = runtime.pools.catalogCoordinator.testNamespaceActiveRegistration
                        val identityField = identityExecutor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                        val identityPrior = identityField.get(identityExecutor) as JdbcTemplate
                        assertSame(identityPrior.dataSource, identityProbe.dataSource); identityField.set(identityExecutor, identityProbe)
                        boundary = identityProbe::assertCommittedAndReleased
                        try {
                            val registrationAttempt = ComplaintTestNamespaceActiveRegistrationAttemptV1.withHttpFixture(assembly, raw.catalog::httpClient, SignedActivationObservation.WALL_CLOCK)
                            identityProbe.original = registrationAttempt
                            registrationAttempt.register(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials).use { registration ->
                                registrationAttempt.requireActualCleanup(); identityProbe.assertCommittedAndReleased(); raw.assertClosed()
                                assertEquals(2, raw.catalog.createdClients)
                                assertTrue(saved.image == TestActiveSealRecoveryObservationV1.image(jdbc), "Fresh D registers identity only; it does not recover the seal.")
                                val sealProbe = TestActiveSealRecoveryProbeV1(runtime, ColdSqlObservationV1::advisory)
                                val executor = runtime.pools.catalogCoordinator.testActiveOrdinarySealRecovery
                                val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                                val prior = field.get(executor) as JdbcTemplate
                                assertSame(prior.dataSource, sealProbe.dataSource); field.set(executor, sealProbe)
                                boundary = { identityProbe.assertCommittedAndReleased(); sealProbe.assertCommittedAndReleased() }
                                native.boundary = { boundary() }
                                native.nativeBoundary = { identityProbe.assertPhysicallyReleased(); sealProbe.assertPhysicallyReleased() }
                                var nativeCloses = 0
                                native.onNativeClose = {
                                    assertEquals(1L, assembly.target.publicationLanes.activeOwners().totalOwners)
                                    assertFalse(sealProbe.calls.any { it.step === TestActiveOrdinarySealRecoveryStepV1.VERIFY }); nativeCloses++
                                }
                                sealProbe.before = { call -> if (call.step === TestActiveOrdinarySealRecoveryStepV1.VERIFY) {
                                    assertTrue(nativeCloses > 0); native.assertDisposed()
                                    assertEquals(0L, assembly.target.publicationLanes.activeOwners().totalOwners)
                                } }
                                try {
                                    // Old lease expiry occurred in PARENT, before this JVM/original/deadlines existed.
                                    val original = TestActiveOrdinarySealRecoveryV1.withHttpFixture(registration, assembly, raw.catalog::httpClient, SignedActivationObservation.WALL_CLOCK)
                                    sealProbe.original = original
                                    val result = original.recover(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
                                    sealProbe.assertCommittedAndReleased(); native.assertDisposed(); raw.assertClosed()
                                    assertEquals(4, raw.catalog.createdClients, "Separate original has its own actual dual raw read/cleanup.")
                                    assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
                                    assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, checkNotNull(runtime.pools.epochRotation).observePreparation())
                                    assertEquals(if (existing) 0 else 1, native.order.count { it == "GENERATE" }); assertEquals(1, native.order.count { it == "DECRYPT" })
                                    assertEquals(if (existing) listOf("LIST", "GET") else listOf("LIST", "PUT", "LIST", "GET"), native.requests.map { it.kind })
                                    assertEquals(!existing, sealProbe.calls.any { it.step === TestActiveOrdinarySealRecoveryStepV1.FREEZE })
                                    val stored = checkNotNull(native.stored)
                                    assertEquals(stored.key, result.objectKey); assertEquals(stored.version, result.version)
                                    assertEquals(ColdFixtureFilesV1.sha256(stored.bytes), result.ciphertextSha256)
                                    assertArrayEquals(jdbc.queryForObject("SELECT wire_bytes FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", ByteArray::class.java, inputs.dataScopeId), stored.bytes)
                                    if (existing) {
                                        assertEquals(saved.nativePutSha256, ColdFixtureFilesV1.sha256(stored.bytes))
                                        assertArrayEquals(checkNotNull(saved.seal).raw().bytes, stored.bytes)
                                    } else assertArrayEquals(native.requests.single { it.kind == "PUT" }.body, stored.bytes)
                                    TestActiveSealRecoveryObservationV1.completed(saved.image, jdbc, inputs.dataScopeId)
                                    val after = TestActiveSealRecoveryObservationV1.image(jdbc); val calls = sealProbe.calls.size; val providers = native.order.toList()
                                    assertThrows<TestActiveOrdinarySealRecoveryExceptionV1> { original.recover(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials) }
                                    assertTrue(after == TestActiveSealRecoveryObservationV1.image(jdbc)); assertEquals(calls, sealProbe.calls.size); assertEquals(providers, native.order)
                                } finally {
                                    native.onNativeClose = {}; native.boundary = {}; native.nativeBoundary = {}
                                    sealProbe.before = {}; field.set(executor, prior); sealProbe.assertPhysicallyReleased()
                                    boundary = identityProbe::assertCommittedAndReleased
                                }
                            }
                        } finally { identityField.set(identityExecutor, identityPrior); identityProbe.assertPhysicallyReleased(); raw.assertClosed(); boundary = {} }
                    }
                }
            } finally { raw.assertClosed(); native.assertDisposed() }
        }
        ColdSqlObservationV1.noRuntimeSessions(jdbc)
        TestActiveSealRecoveryObservationV1.completed(saved.image, jdbc, UUID.fromString(saved.scope))
    }
}

/** Bounded private RAW fixture handoff. No fresh/old original, registration, Captured, lease grant, deadline or proof is serialized. */
@Serializable
internal data class ColdActiveSealRecoveryRawV1(
    val format: Int, val existingObject: Boolean, val authorPid: Long, val authorStart: String, val scope: String,
    val documentSha256: String, val fullDSha256: String,
    val secrets: List<ColdSecretObjectV1>, val catalog: List<ColdCatalogObjectV1>,
    val seal: ColdJournalObjectV1?, val nativePutSha256: String?, val image: Map<String, List<String>>,
) {
    fun write(root: Path) = ColdFixtureFilesV1.write(root.resolve("empty-seal-recovery-raw.json"), Json.encodeToString(serializer(), this).toByteArray())
    companion object {
        fun read(root: Path): ColdActiveSealRecoveryRawV1 = Json.decodeFromString(serializer(),
            ColdFixtureFilesV1.read(root.resolve("empty-seal-recovery-raw.json")).decodeToString()).also {
            check(it.format == 1 && it.authorPid > 0 && Instant.parse(it.authorStart).toString() == it.authorStart)
            check(UUID.fromString(it.scope).let { id -> id.version() == 4 && id.toString() == it.scope })
            check(it.fullDSha256.matches(Regex("[0-9a-f]{64}")))
            check(it.documentSha256 == ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve("test-deployment.json"))))
            check(it.secrets.size in 2..64 && it.catalog.size in 2..64)
            check(it.secrets.map { raw -> raw.arn to raw.version }.toSet().size == it.secrets.size)
            check(it.catalog.map { raw -> Triple(raw.bucket, raw.key, raw.version) }.toSet().size == it.catalog.size)
            check(it.existingObject == (it.seal != null) && it.existingObject == (it.nativePutSha256 != null))
            it.seal?.let { raw ->
                check(raw.key.length in 1..1024 && raw.version.length in 1..1024 && raw.metadata.size == 4 && raw.body.length in 1..131072)
                val body = raw.raw()
                check(body.bytes.size in 1..98304 && ColdFixtureFilesV1.sha256(body.bytes) == it.nativePutSha256)
            }
        }

        fun capture(h: TestActiveOrdinarySealFixtureV1, root: Path, existing: Boolean): ColdActiveSealRecoveryRawV1 {
            h.assertReleased()
            val evidence = h.p.f.rows.evidence
            val input = evidence.coldInputBytes()
            ColdFixtureFilesV1.write(root.resolve("test-deployment.json"), input)
            val catalog = coldCatalogObjectsV1(h.p.f.http.read)
            check(catalog.values.groupBy { it.bucket }.values.map { it.size }.toSet() == setOf(evidence.prefix.size + 1))
            check(catalog.values.map { it.bucket }.toSet() == OfflineTrustBundleFixture.locations.map { it.bucket }.toSet())
            val stored = h.native.stored
            check(existing == (stored != null))
            val put = h.native.requests.singleOrNull { it.kind == "PUT" }
            if (existing) {
                check(checkNotNull(put).reply?.status == 500) // Exact object originated in the actual old native PUT, not in the handoff builder.
                assertArrayEquals(put.body, checkNotNull(stored).bytes)
                assertArrayEquals(h.paid()["wire_bytes"] as ByteArray, stored.bytes)
            } else check(put == null)
            val process = ProcessHandle.current()
            return ColdActiveSealRecoveryRawV1(1, existing, process.pid(), process.info().startInstant().orElseThrow().toString(), h.scope.toString(),
                ColdFixtureFilesV1.sha256(input), ColdFixtureFilesV1.sha256(h.process.canonicalBytes()), evidence.coldSecretObjects(), catalog.values.toList(),
                stored?.let { ColdJournalObjectV1(it.key, it.version, Base64.getEncoder().encodeToString(it.bytes), it.lastModified.toString(), it.retainUntil.toString(), it.metadata) },
                put?.let { ColdFixtureFilesV1.sha256(it.body) }, TestActiveSealRecoveryObservationV1.image(h.observer))
        }
    }
}
