package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.ColdFixtureFilesV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.TestOrdinaryDrainConnectedIT
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationCurrentStatePhaseExecutor
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Two actual JVMs over one unchanged owned PostgreSQL/TLS generation. Identity only, never health,
 * content, C's REQUESTED/CAPTURED recovery or an UNKNOWN-original repair. Raw providers are synthetic.
 * NOT_COMPILED / NOT_RUN until the primary qualifies these exact selectors on its ordinary hosted gate.
 */
internal object ComplaintTestColdActiveRegistrationProcessCasesV1 {
    fun qualify(interruptedRelease: Boolean) {
        assumeTrue(System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN") == null,
            "Two-JVM qualification owns a Testcontainers database; the separate strict local-controller gate is unchanged.")
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-active-two-jvm-",
            PosixFilePermissions.asFileAttribute(ColdFixtureFilesV1.directoryMode))
        val children = mutableListOf<Process>()
        // Reuse the existing fixed cold-child attachment recipe; do not broaden its export/endpoint allowlist.
        val database = PgLifecycleDatabaseFixture(TestOrdinaryDrainConnectedIT::class.java)
        try {
            database.start()
            val jdbc = JdbcTemplate(ordinaryCleanupReader(database))
            val generation = ColdSqlObservationV1.generation(jdbc)
            val descriptor = database.exportColdChildAttachment(root)
            val descriptorHash = ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(descriptor))
            val first = ComplaintTestColdProcessSupportV1.runChild(root, descriptorHash, "author", interruptedRelease, children, ColdTestProcessEntryV1.ACTIVE)
            val saved = try { ColdActiveRawHandoffV1.read(root) } catch (problem: Exception) {
                throw AssertionError("Cold ACTIVE author handoff read failed: ${problem.javaClass.name}")
            }
            assertEquals(first.first.pid(), saved.authorPid); assertEquals(first.second.toString(), saved.authorStart)
            assertEquals(interruptedRelease, saved.interruptedRelease)
            ColdSqlObservationV1.noRuntimeSessions(jdbc) // Parent's own observation AFTER actual child exit.
            assertEquals(generation, ColdSqlObservationV1.generation(jdbc))
            assertTrue(saved.image == ColdSqlObservationV1.image(jdbc), "Author exit changed persisted rows; raw row bodies stay private.")
            val immutable = listOf("owned-container.txt", "test-deployment.json", "active-raw-backing.json").associateWith {
                ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve(it)))
            }
            val second = ComplaintTestColdProcessSupportV1.runChild(root, descriptorHash, "recover", interruptedRelease, children, ColdTestProcessEntryV1.ACTIVE)
            assertTrue(first.first.pid() != second.first.pid(), "Distinct actual JVM invocations, not a fresh graph in the old JVM.")
            ColdSqlObservationV1.noRuntimeSessions(jdbc)
            assertEquals(generation, ColdSqlObservationV1.generation(jdbc))
            immutable.forEach { (file, digest) -> assertEquals(digest, ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve(file)))) }
            ColdActiveSqlObservationV1.completed(saved, jdbc) // Never a serialized successful-admission/cleanup flag.
        } finally {
            children.forEach(ComplaintTestColdProcessSupportV1::stopOwnedChild)
            check(children.none(Process::isAlive)) // Forced retirement/timeouts cannot turn a failed history into success.
            database.close()
            Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

/** TEST entrypoint only; no application bootstrap option or caller-supplied registration/phase receipt. */
internal object ComplaintTestColdActiveRegistrationProcessV1 {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4 && args[2] in setOf("author", "recover") && args[3] in setOf("true", "false"))
        val root = Path.of(args[0]); val stage = args[2]; val interruptedRelease = args[3].toBooleanStrict()
        try {
            PgLifecycleDatabaseFixture.attachColdChild(root.resolve("owned-container.txt"), args[1]).use { database ->
                if (stage == "author") author(database, root, interruptedRelease) else recover(database, root, interruptedRelease)
            }
        } catch (problem: Throwable) {
            // No throwable prose, SQL rows, access tokens, input bodies or mock keys leave private scratch.
            runCatching { ColdFixtureFilesV1.write(root.resolve("$stage-failure.txt"), failureLocations(stage, problem)) }
            exitProcess(1)
        }
    }

    /** Passive bounded failure topology, not Throwable.message/toString or a second cleanup attempt. */
    private fun failureLocations(stage: String, problem: Throwable): ByteArray {
        fun symbol(value: String): String = value.take(200).map {
            if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._$<>:-") it else '?'
        }.joinToString("")
        val report = StringBuilder("stage=$stage type-source-tree-v1\n")
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val pending = ArrayDeque<Pair<String, Throwable>>()
        pending.addLast("root" to problem)
        var count = 0
        var truncated = false
        while (pending.isNotEmpty() && count < 16) {
            val (edge, failure) = pending.removeFirst()
            if (seen.put(failure, true) != null) continue
            val frames = failure.stackTrace
            // For an assertAll leaf this is the actual failing fixture lambda/line, not AssertAll's aggregate frame.
            val source = frames.firstOrNull { it.className.startsWith("me.manga.kira.backend.") } ?: frames.firstOrNull()
            val location = source?.let { "${symbol(it.className.substringAfterLast('.'))}.${symbol(it.methodName)}:${it.lineNumber}" } ?: "NO_SOURCE"
            val line = "node=$count via=$edge type=${symbol(failure.javaClass.name)} at=$location\n"
            if (report.length + line.length > 4000) { truncated = true; break }
            report.append(line)
            val parent = count++
            fun enqueue(edgeName: String, child: Throwable) {
                if (count + pending.size < 16) pending.addLast("$parent.$edgeName" to child) else truncated = true
            }
            failure.cause?.let { enqueue("cause", it) }
            val suppressed = failure.suppressed
            suppressed.take(16).forEachIndexed { index, child -> enqueue("suppressed[$index]", child) }
            if (suppressed.size > 16) truncated = true
        }
        if (truncated || pending.isNotEmpty()) report.append("TRUNCATED_TYPE_SOURCE_TREE\n")
        // ASCII-only copied symbols plus fixed labels: stays below the existing parent's 4096-byte read cap.
        return report.toString().toByteArray(Charsets.UTF_8)
    }

    private fun author(database: PgLifecycleDatabaseFixture, root: Path, interruptedRelease: Boolean): Nothing {
        VersionBoundPersistenceConnectedFixture(database, testActivation = true).use { tls ->
            tls.bind()
            withInitialAdmission(tls) { f ->
                val candidate = f.candidate()
                if (interruptedRelease) interruptedInitialRelease(f) else {
                    f.release()
                    f.withExchange { exchange ->
                        assertEquals(InstallationEnrollmentDisposition.CREATED, exchange.enroll(candidate).disposition)
                        exchange.assertReleased()
                    }
                }
                f.assertSqlReleased()
                ColdActiveSqlObservationV1.openPendingScan(f.observer, f.p.scope, if (interruptedRelease) 0L else 1L)
                val saved = ColdActiveRawHandoffV1.capture(f, root, interruptedRelease, candidate.installation.id)
                f.registration.close()
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.registration.requireReleasedIdentityAdmission() }
                f.runtime.closeRegisteredRuntimeForRecovery() // Actual protected assembly, peer, actors, pools and public trust.
                f.p.f.signed.close() // Every earlier real activation/publisher/provider/root owner, not just the final graph.
                f.native.close(); f.native.assertDisposed()
                assertTrue(f.native.nativeFactories.isEmpty(), "Identity never dispatches an ordinary seal.")
                assertEquals(f.p.f.http.read.createdClients, f.p.f.http.read.closedClients)
                ColdSqlObservationV1.noRuntimeSessions(f.observer)
                assertTrue(saved.image == ColdSqlObservationV1.image(f.observer), "Retirement cannot repair, refund or mutate the author state.")
                saved.write(root)
                database.close() // Borrowed view only; the parent still owns the server and server TLS.
                // Bypass only OUTER TEST row teardown. Every actual original runtime/provider/session is already retired.
                exitProcess(0)
            }
        }
        error("Author must terminate only after actual original graph/session retirement.")
    }

    private fun interruptedInitialRelease(f: InitialAdmissionFixture) {
        val original = f.begin()
        val unchanged = f.nonControlImage()
        val preservedControls = f.controls(preserved = true)
        var selected: PersistencePhaseContext? = null
        f.probe.afterSql = { step -> if (step == "test-initial-admission-release") {
            assertNull(selected)
            selected = checkNotNull(PersistencePhaseOwnership.current())
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { throw IOException("Synthetic initial RELEASE completion failure after known native COMMIT.") }
            })
        } }
        try {
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                original.release(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
            }
        } finally { f.probe.afterSql = {} }
        assertNotNull(selected)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
        original.requireActualCleanup(); f.assertSqlReleased(); f.gates(open = true)
        assertTrue(unchanged == f.nonControlImage() && preservedControls == f.controls(preserved = true))
        f.assertNoLatch()
        f.withExchange { exchange ->
            exchange.unavailable(f.candidate())
            assertTrue(exchange.jdbc.calls.isEmpty(), "Visible open flags cannot repair the failed original's local latch.")
            exchange.assertReleased()
        }
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.begin() }
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { original.release(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials) }
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.registration.publishInitialAdmission(original) }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome(), "Not an UNKNOWN or C first-cut scenario.")
    }

    private fun recover(database: PgLifecycleDatabaseFixture, root: Path, interruptedRelease: Boolean) {
        val saved = ColdActiveRawHandoffV1.read(root)
        assertEquals(interruptedRelease, saved.interruptedRelease)
        val old = ProcessHandle.of(saved.authorPid).orElse(null)
        assertFalse(old != null && old.isAlive && old.info().startInstant().orElse(null)?.toString() == saved.authorStart)
        assertTrue(ProcessHandle.current().pid() != saved.authorPid)
        val jdbc = JdbcTemplate(ordinaryCleanupReader(database))
        ColdSqlObservationV1.noRuntimeSessions(jdbc)
        assertTrue(saved.image == ColdSqlObservationV1.image(jdbc))
        val document = Json.decodeFromString(ComplaintTestDeploymentDocumentV1.serializer(),
            ColdFixtureFilesV1.read(root.resolve("test-deployment.json")).toString(Charsets.UTF_8))
        val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(document) // RAW mock expectations only; assembly opens/parses the file itself.
        var boundary: () -> Unit = {}
        val raw = ColdIdentityRawProvidersV1(saved.secrets, saved.catalog) { requireConnectionFree(); boundary() }
        // No lease expiry/reset helper: read-only identity admission neither borrows nor replaces an old lease.
        TestOrdinarySealHttpFixtureV1(Instant.parse(document.retention.lastPreRunRestoreHorizon), document.retention.horizonPolicy,
            protectedIntake = true).use { native ->
            native.prepareIndependent(inputs.journal)
            try {
                ComplaintTestProcessAssemblyV1.withHttpFixture(raw.secrets::httpClient, PersistenceNanoClock(native::nanos), native::now,
                    PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY, native::nativeSts, native::nativeKms, native::nativeS3).use { assembly ->
                    assembly.assemble(root.resolve("test-deployment.json"), AwsSecretVersionFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS)
                    assertEquals(saved.fullDSha256, ColdFixtureFilesV1.sha256(assembly.target.canonicalBytes()))
                    assertEquals(saved.secrets.size, raw.secrets.requests.size)
                    VersionBoundPersistenceConnectedFixture(database, testIntake = assembly).use { runtime ->
                        runtime.bind(); runtime.start()
                        assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
                        assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                        val probe = TestActiveRegistrationSqlProbeV1(ColdSqlObservationV1::advisory, runtime)
                        val executor = runtime.pools.catalogCoordinator.testNamespaceActiveRegistration
                        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                        val prior = field.get(executor) as JdbcTemplate
                        assertSame(prior.dataSource, probe.dataSource); field.set(executor, probe)
                        boundary = probe::assertCommittedAndReleased
                        try {
                            val before = ColdSqlObservationV1.image(jdbc)
                            assertTrue(saved.image == before, "Cold construction cannot migrate/PROJECT/enroll/reset the existing run.")
                            val attempt = ComplaintTestNamespaceActiveRegistrationAttemptV1.withHttpFixture(assembly, raw.catalog::httpClient,
                                SignedActivationObservation.WALL_CLOCK)
                            probe.original = attempt
                            attempt.register(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials).use { registration ->
                                attempt.requireActualCleanup(); registration.requireActiveIdentityTarget(assembly); probe.assertCommittedAndReleased()
                                assertEquals(2, probe.observations.size); assertEquals(2, raw.catalog.createdClients)
                                assertTrue(raw.catalog.requests.isNotEmpty()); raw.assertClosed()
                                assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
                                assertSame(assembly.target, registration.process)
                                assertTrue(before == ColdSqlObservationV1.image(jdbc), "Registration cannot mutate any row/xmin, lease, scan request, counter or run.")
                                ColdActiveSqlObservationV1.openPendingScan(jdbc, inputs.dataScopeId, if (interruptedRelease) 0L else 1L)
                                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByActiveRegistration(attempt) }
                                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                                    ComplaintTestNamespaceActiveRegistrationAttemptV1.withHttpFixture(assembly, raw.catalog::httpClient, SignedActivationObservation.WALL_CLOCK)
                                }
                                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestInitialAdmissionV1.begin(registration, assembly) }
                                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { TestRunSealingV1.begin(registration) }
                                identity(runtime, registration, saved, jdbc)
                            }
                        } finally { field.set(executor, prior); probe.assertPhysicallyReleased(); raw.assertClosed(); boundary = {} }
                    }
                }
            } finally { raw.assertClosed(); native.assertDisposed() }
            assertTrue(native.nativeFactories.isEmpty(), "No post-startup seal or scan authority was borrowed for identity.")
        }
        ColdSqlObservationV1.noRuntimeSessions(jdbc)
        ColdActiveSqlObservationV1.completed(saved, jdbc)
    }

    private fun identity(runtime: VersionBoundPersistenceConnectedFixture, registration: ComplaintTestNamespaceRegistrationV1,
        saved: ColdActiveRawHandoffV1, observer: JdbcTemplate) {
        val candidate = InstallationEnrollmentCredentials.prepare(
            ScopedInstallationId(UUID.fromString(saved.installationId), registration.process.desiredSettings().scope),
            ComplaintPlatform.ANDROID, ByteArray(32) { it.toByte() }) // Original synthetic client request, never an accepted enrollment receipt.
        ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, service ->
            val jdbc = InitialIdentityProbe(ordinary)
            val audit = ComplaintInstallationEnrollmentAudit { scope, allocation, at -> service.recordInstallationEnrollment(scope, allocation, at) }
            val adapter = ComplaintInstallationExchangeAdapter(registration, ordinary.ownership, jdbc, audit)
            val exchange = ColdActiveIdentityExchangeV1(registration, adapter, jdbc, ordinary.ownership)
            try {
                val codec = InstallationJwtCodec(registration.process.consumers.jwt.installationKeyRing, Clock.systemUTC())
                if (saved.interruptedRelease) {
                    val created = exchange.enroll(candidate)
                    assertEquals(InstallationEnrollmentDisposition.CREATED, created.disposition)
                    assertEquals(candidate.installation, codec.verify(created.session.accessToken).installation)
                    exchange.assertReleased()
                }
                val paid = ColdSqlObservationV1.image(observer).getValue("complaint_capacity_counters")
                assertEquals(InstallationEnrollmentDisposition.EXACT_REPLAY, exchange.enroll(candidate).disposition)
                assertEquals(candidate.installation, codec.verify(exchange.session(candidate).accessToken).installation)
                exchange.assertReleased()
                assertTrue(paid == ColdSqlObservationV1.image(observer).getValue("complaint_capacity_counters"), "Replay/session cannot recharge any capacity or daily count.")
                assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED,
                    ComplaintInstallationCurrentStatePhaseExecutor(ordinary.ownership, JdbcInstallationCurrentStateReader(jdbc))
                        .assess(registration.process.desiredSettings(), candidate.installation.scope))
                exchange.assertReleased()
                registration.close()
                val calls = jdbc.calls.size
                exchange.unavailable(candidate)
                assertEquals(calls, jdbc.calls.size, "A closed cold origin cannot revive an already constructed exchange.")
            } finally { jdbc.assertNoLostAssertions(); requireConnectionFree() }
        }
    }
}

/** Only original protected bytes/raw mock objects and passive observations; no acquired value or successful owner is serializable. */
@Serializable
internal data class ColdActiveRawHandoffV1(
    val format: Int, val interruptedRelease: Boolean, val authorPid: Long, val authorStart: String,
    val documentSha256: String, val fullDSha256: String, val installationId: String,
    val secrets: List<ColdSecretObjectV1>, val catalog: List<ColdCatalogObjectV1>,
    val image: Map<String, List<String>>,
) {
    fun write(root: Path) = ColdFixtureFilesV1.write(root.resolve("active-raw-backing.json"),
        Json.encodeToString(serializer(), this).toByteArray())

    companion object {
        fun read(root: Path): ColdActiveRawHandoffV1 = Json.decodeFromString(serializer(),
            ColdFixtureFilesV1.read(root.resolve("active-raw-backing.json")).toString(Charsets.UTF_8)).also {
            check(it.format == 1 && it.authorPid > 0 && Instant.parse(it.authorStart).toString() == it.authorStart)
            check(it.fullDSha256.matches(Regex("[0-9a-f]{64}")))
            check(UUID.fromString(it.installationId).let { id -> id.version() == 4 && id.toString() == it.installationId })
            check(it.secrets.size in 2..64 && it.catalog.size in 2..64)
            check(it.secrets.map { raw -> raw.arn to raw.version }.toSet().size == it.secrets.size)
            check(it.catalog.map { raw -> Triple(raw.bucket, raw.key, raw.version) }.toSet().size == it.catalog.size)
            check(it.documentSha256 == ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve("test-deployment.json"))))
        }

        fun capture(f: InitialAdmissionFixture, root: Path, interruptedRelease: Boolean, installation: UUID): ColdActiveRawHandoffV1 {
            f.assertSqlReleased()
            val evidence = f.p.f.rows.evidence
            val input = evidence.coldInputBytes()
            ColdFixtureFilesV1.write(root.resolve("test-deployment.json"), input)
            val catalog = coldCatalogObjectsV1(f.p.f.http.read)
            check(catalog.values.groupBy { it.bucket }.values.map { it.size }.toSet() == setOf(evidence.prefix.size + 1))
            check(catalog.values.map { it.bucket }.toSet() == OfflineTrustBundleFixture.locations.map { it.bucket }.toSet())
            val process = ProcessHandle.current()
            return ColdActiveRawHandoffV1(1, interruptedRelease, process.pid(), process.info().startInstant().orElseThrow().toString(),
                ColdFixtureFilesV1.sha256(input), ColdFixtureFilesV1.sha256(f.registration.process.canonicalBytes()), installation.toString(),
                evidence.coldSecretObjects(), catalog.values.toList(), ColdSqlObservationV1.image(f.observer))
        }
    }
}

/** Actual-row assertions through V25's constraint change on the existing receipt table; V26 is absent at this base. */
internal object ColdActiveSqlObservationV1 {
    // Independent literal enrollment deltas: two prepaid 16-KiB identity rows, one ordinary 64-KiB audit.
    private val share = ComplaintCapacityVector.units(ComplaintCapacityCounter.APP_INSTALLATIONS, 1)
        .with(ComplaintCapacityCounter.INSTALLATION_IDS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 32_768)
    private val audit = ComplaintCapacityVector.units(ComplaintCapacityCounter.AUDIT_ROWS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 65_536)

    fun openPendingScan(jdbc: JdbcTemplate, scope: UUID, enrolled: Long) {
        assertEquals(true, jdbc.queryForObject("SELECT count(*) = 1 FROM flyway_schema_history WHERE version = '25' AND success", Boolean::class.java))
        assertEquals(true, jdbc.queryForObject("SELECT count(*) = 2 AND bool_and(NOT maintenance_closed AND NOT creation_closed AND pending_projection_token IS NULL) " +
            "FROM complaint_journal_control WHERE data_scope_id IN ('00000000-0000-0000-0000-000000000000'::uuid, ?)", Boolean::class.java, scope))
        assertEquals(true, jdbc.queryForObject("SELECT publication_epoch = 1 AND scan_requested AND rotation_sequence = 0 AND rotation_state IS NULL " +
            "AND rotation_id IS NULL AND seal_epoch IS NULL AND checkpoint_result IS NULL AND checkpoint_completed_at IS NULL " +
            "AND lease_owner IS NULL AND lease_expires_at IS NULL AND lease_token = 0 FROM complaint_journal_control WHERE data_scope_id = ?", Boolean::class.java, scope))
        assertEquals(true, jdbc.queryForObject("SELECT state = 'ACTIVE' AND enrolled_count = ? AND sealed_at IS NULL AND permanent_denial_bytes IS NULL " +
            "AND seal_set_bytes IS NULL FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java, enrolled, scope))
    }

    fun completed(saved: ColdActiveRawHandoffV1, jdbc: JdbcTemplate) {
        val before = saved.image; val after = ColdSqlObservationV1.image(jdbc)
        assertEquals(before.keys, after.keys)
        val run = row(after.getValue("complaint_test_runs").single())
        val scope = UUID.fromString(text(run, "data_scope_id"))
        openPendingScan(jdbc, scope, 1L)
        val changed = if (saved.interruptedRelease) setOf("app_installations", "complaint_installation_ids", "complaint_test_runs", "complaint_capacity_counters", "audit_log")
            else setOf("app_installations")
        (before.keys - changed).forEach { table -> assertTrue(before.getValue(table) == after.getValue(table), "No unrelated row/xmin write: $table") }
        listOf("complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications", "complaint_recovery_capacity_reservations",
            "complaint_deletion_journal_applied", "complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_test_terminal_intents").forEach {
            assertTrue(after.getValue(it).isEmpty(), "Identity cannot produce content/grants/recovery/terminal work: $it")
        }
        val identity = row(after.getValue("complaint_installation_ids").single())
        val credential = row(after.getValue("app_installations").single())
        listOf(identity, credential).forEach {
            assertTrue(text(it, "id") == saved.installationId && text(it, "data_scope_id") == scope.toString())
            assertEquals("ACTIVE", text(it, "state"))
        }
        assertEquals(1L, number(credential, "credential_version")); assertEquals(3L, number(credential, "version"))
        if (!saved.interruptedRelease) {
            val old = row(before.getValue("app_installations").single())
            val mutable = setOf("last_authenticated_at", "version")
            assertTrue(old.filterKeys { it !in mutable } == credential.filterKeys { it !in mutable }, "Replay/session cannot replace credentials or provenance.")
            assertTrue(!Instant.parse(text(credential, "last_authenticated_at")).isBefore(Instant.parse(text(old, "last_authenticated_at"))))
            return
        }
        assertTrue(before.getValue("complaint_installation_ids").isEmpty() && before.getValue("app_installations").isEmpty())
        val oldRun = row(before.getValue("complaint_test_runs").single())
        assertEquals(0L, number(oldRun, "enrolled_count"))
        assertEquals(vector(oldRun, "unused_reserve") - share, vector(run, "unused_reserve"))
        val runMutable = setOf("enrolled_count", "unused_reserve")
        assertTrue(oldRun.filterKeys { it !in runMutable } == run.filterKeys { it !in runMutable }, "No run birth/activation/reserve reset.")
        val counters = before.getValue("complaint_capacity_counters").associateBy { text(row(it), "name") }
        assertEquals(22, counters.size); assertEquals(22, after.getValue("complaint_capacity_counters").size)
        after.getValue("complaint_capacity_counters").forEach { bytes ->
            val current = row(bytes); val name = text(current, "name"); val oldBytes = counters.getValue(name); val old = row(oldBytes)
            val counter = ComplaintCapacityCounter.entries.single { it.storedName == name }
            assertEquals(number(old, "free_units") - audit[counter], number(current, "free_units"))
            assertEquals(number(old, "actual_units") + share[counter] + audit[counter], number(current, "actual_units"))
            assertEquals(number(old, "test_reserved_units") - share[counter], number(current, "test_reserved_units"))
            assertEquals(number(old, "recovery_reserved_units"), number(current, "recovery_reserved_units"))
            assertEquals(number(current, "hard_limit"), listOf("free_units", "actual_units", "test_reserved_units", "recovery_reserved_units").sumOf { number(current, it) })
            val mutable = setOf("free_units", "actual_units", "test_reserved_units", "updated_at") +
                if (counter === ComplaintCapacityCounter.INSTALLATION_IDS) setOf("admission_utc_date", "admission_count") else emptySet()
            assertTrue(old.filterKeys { it !in mutable } == current.filterKeys { it !in mutable }, "D/limits/recovery reserve remain unchanged.")
            if (counter === ComplaintCapacityCounter.INSTALLATION_IDS) {
                assertEquals(0L, number(old, "admission_count")); assertEquals(1L, number(current, "admission_count"))
                assertEquals(Instant.parse(text(credential, "created_at")).atOffset(ZoneOffset.UTC).toLocalDate().toString(), text(current, "admission_utc_date"))
            }
            if (share[counter] == 0L && audit[counter] == 0L) assertTrue(oldBytes == bytes, "Unrelated counters cannot churn.")
        }
        val oldAudits = before.getValue("audit_log"); val audits = after.getValue("audit_log")
        assertTrue(audits.containsAll(oldAudits)); assertEquals(oldAudits.size + 1, audits.size)
        val enrolledAudit = row((audits - oldAudits.toSet()).single())
        assertEquals("COMPLAINT_INSTALLATION_ENROLLED", text(enrolledAudit, "action"))
        assertTrue(text(enrolledAudit, "complaint_data_scope_id") == scope.toString())
    }

    private fun row(bytes: String): JsonObject = Json.parseToJsonElement(bytes).jsonArray[0].jsonObject
    private fun text(row: JsonObject, column: String): String = row.getValue(column).jsonPrimitive.content
    private fun number(row: JsonObject, column: String): Long = row.getValue(column).jsonPrimitive.long
    private fun vector(row: JsonObject, column: String): ComplaintCapacityVector = row.getValue(column).jsonArray.let { array ->
        check(array.size == 22); ComplaintCapacityVector.of(LongArray(22) { array[it].jsonPrimitive.long })
    }
}
