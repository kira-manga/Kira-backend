package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.common.infrastructure.persistence.ColdFixtureFilesV1
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.TestOrdinaryDrainConnectedIT
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupFactory
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRecoveryRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryHttpFixtureV1
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.CurrentUser
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
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Exactly two normal TEST JVMs per history; the JUnit coordinator owns one actual container/TLS.
 * Synthetic raw providers/policy declarations are not deployed-provider or production qualification.
 * NOT_COMPILED / NOT_RUN until the parent executes these two selectors on its ordinary hosted gate.
 */
internal object ComplaintTestColdRecoveryProcessCasesV1 {
    fun qualify(paidCut: Boolean) {
        assumeTrue(System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN") == null,
            "Two-JVM qualification owns a Testcontainers database; the separate strict local-controller gate is unchanged.")
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-cold-two-jvm-",
            PosixFilePermissions.asFileAttribute(ColdFixtureFilesV1.directoryMode))
        val children = mutableListOf<Process>()
        val database = PgLifecycleDatabaseFixture(TestOrdinaryDrainConnectedIT::class.java)
        try {
            database.start()
            val jdbc = JdbcTemplate(ordinaryCleanupReader(database))
            val generation = ColdSqlObservationV1.generation(jdbc)
            val descriptor = database.exportColdChildAttachment(root)
            val descriptorHash = ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(descriptor))
            val first = runChild(root, descriptorHash, "author", paidCut, children)
            val saved = try { ColdRawHandoffV1.read(root) } catch (problem: Exception) {
                // Serializer diagnostics may contain input snippets; never send raw mock keys to the JUnit report.
                throw AssertionError("Cold author handoff read failed: ${problem.javaClass.name}")
            }
            assertEquals(first.first.pid(), saved.authorPid); assertEquals(first.second.toString(), saved.authorStart)
            assertEquals(paidCut, saved.paidCut)
            ColdSqlObservationV1.noRuntimeSessions(jdbc) // Parent's own observation AFTER actual child exit, never a serialized cleanup flag.
            assertEquals(generation, ColdSqlObservationV1.generation(jdbc))
            assertTrue(saved.image == ColdSqlObservationV1.image(jdbc), "Persisted rows changed after author exit; raw row bodies stay private.")
            val immutable = listOf("owned-container.txt", "test-deployment.json", "raw-backing.json").associateWith {
                ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve(it)))
            }
            val second = runChild(root, descriptorHash, "recover", paidCut, children)
            assertTrue(first.first.pid() != second.first.pid(), "Distinct actual JVM invocations, not a fresh graph in the old JVM.")
            ColdSqlObservationV1.noRuntimeSessions(jdbc)
            assertEquals(generation, ColdSqlObservationV1.generation(jdbc))
            immutable.forEach { (file, digest) -> assertEquals(digest, ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve(file)))) }
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM complaint_recovery_capacity_reservations WHERE state = 'CONVERTED'", Long::class.java))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaint_journal_scan_entries", Long::class.java))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaint_journal_scan_runs", Long::class.java))
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM complaint_test_terminal_intents", Long::class.java))
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM complaint_test_runs WHERE state = 'SEALED' AND permanent_denial_bytes IS NOT NULL AND seal_set_bytes IS NOT NULL", Long::class.java))
        } finally {
            // Forced disposal cannot turn any timeout/failed assertion into success. Keep raw files if process death is unproven.
            children.forEach(::stopOwnedChild)
            check(children.none(Process::isAlive))
            database.close() // Owning parent alone stops its container, then disposes server TLS.
            Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun runChild(root: Path, descriptorHash: String, stage: String, paid: Boolean,
        children: MutableList<Process>): Pair<Process, Instant> {
        val builder = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms32m", "-Xmx384m", "-XX:MaxMetaspaceSize=192m", "-XX:ActiveProcessorCount=1", "-XX:+ExitOnOutOfMemoryError",
            "-Djava.io.tmpdir=$root", "-Duser.home=$root", "-cp", runtimeClasspath(), ComplaintTestColdRecoveryProcessV1::class.java.name,
            root.toString(), descriptorHash, stage, paid.toString()).directory(root.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().clear() // No JAVA_OPTIONS/agents/ambient provider credentials/controller endpoint.
        val process = builder.start().also(children::add)
        val started = process.info().startInstant().orElseThrow()
        process.outputStream.close()
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Cold $stage JVM exceeded its bound; forced disposal is failure.")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt(); throw interrupted
        }
        assertFalse(process.isAlive)
        val diagnostic = root.resolve("$stage-failure.txt").let { if (Files.exists(it)) ColdFixtureFilesV1.read(it, 4096).toString(Charsets.UTF_8) else "no child diagnostic" }
        assertEquals(0, process.exitValue(), "Cold $stage JVM failed: $diagnostic")
        return process to started
    }

    private fun runtimeClasspath(): String {
        // Identical ordinary-runtime recipe to CatalogAuthorProcessTest; never a substitute dependency overlay.
        val entries = linkedSetOf<Path>()
        generateSequence(Thread.currentThread().contextClassLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.getURLs().forEach { url -> check(url.protocol == "file"); entries.add(Path.of(url.toURI()).toAbsolutePath().normalize()) }
        }
        listOf(ComplaintTestColdRecoveryProcessV1::class.java, ComplaintTestProcessAssemblyV1::class.java, Unit::class.java).forEach {
            entries.add(Path.of(it.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize())
        }
        System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotEmpty).forEach {
            entries.add(Path.of(it).toAbsolutePath().normalize())
        }
        return entries.joinToString(File.pathSeparator)
    }

    private fun stopOwnedChild(child: Process) {
        var interrupted = Thread.interrupted()
        try {
            if (child.isAlive) child.destroyForcibly()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child.isAlive && System.nanoTime() < deadline) {
                try { child.waitFor(25, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { interrupted = true }
            }
            check(!child.isAlive) { "Keep private raw backing while child retirement is unproven." }
            child.outputStream.close(); child.inputStream.close(); child.errorStream.close()
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}

/** TEST entrypoint only. Never reachable from application bootstrap, a deployment option or a registration issuer. */
internal object ComplaintTestColdRecoveryProcessV1 {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4 && args[2] in setOf("author", "recover") && args[3] in setOf("true", "false"))
        val root = Path.of(args[0]); val stage = args[2]; val paid = args[3].toBooleanStrict()
        try {
            PgLifecycleDatabaseFixture.attachColdChild(root.resolve("owned-container.txt"), args[1]).use { database ->
                if (stage == "author") author(database, root, paid) else recover(database, root, paid)
            }
        } catch (problem: Throwable) {
            // Bounded class/frame-only diagnostic: no exception messages, SQL rows, inputs or mock keys leave scratch.
            val safe = "stage=$stage type=${problem.javaClass.name}\n" + problem.stackTrace.take(10).joinToString("\n") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            runCatching { ColdFixtureFilesV1.write(root.resolve("$stage-failure.txt"), safe.toByteArray()) }
            exitProcess(1)
        }
    }

    private fun author(database: PgLifecycleDatabaseFixture, root: Path, paidCut: Boolean): Nothing {
        VersionBoundPersistenceConnectedFixture(database, testActivation = true).use { tls ->
            tls.bind()
            withOrdinaryDrainRun(tls, inputs = TestOrdinaryDrainFixtureInputsV1(scanMillis = 30_000),
                expireClosedSetupPredecessors = true, protectedIntake = true) { f ->
                TestOrdinaryDrainAccountingCasesV1.addRetainedAliases(f)
                val observed = TestOrdinaryDrainAccountingObservationV1(f)
                val initial = observed.state()
                val original = f.begin(); val approval = f.approval(original)
                if (paidCut) TestOrdinaryInventoryHttpFixtureV1(f.provider).use { native ->
                    var witness: PersistencePhaseContext? = null
                    f.probe.after = { call -> if (witness == null && call.sql == TestOrdinaryDrainSqlV1.spendAndProgress) {
                        witness = call.phase
                        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit() { error("Synthetic stop after actual committed WITNESS.") }
                        })
                    } }
                    try {
                        assertThrows<TestOrdinaryDrainExceptionV1> { original.drain(approval, f.rawEvidence, f.primaryCredentials, f.readCredentials) }
                    } finally { f.probe.after = {} }
                    f.assertReleased() // Actual physical retirement; a failed invocation is not a successful-cleanup receipt.
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(witness).databaseOutcome())
                    val state = observed.state()
                    assertNotNull(state.progressHex); assertEquals("PARTIAL", state.recoveryState)
                    assertEquals(initial.promise, state.promise); assertEquals(initial.used, state.used)
                    assertEquals(2L, state.scanRuns); assertEquals(8L, state.scanEntries); assertEquals(0L, state.appliedScanEntries)
                    assertTrue(native.requests.any { it.kind == "LIST" }); assertTrue(f.sealHttp.order.isEmpty())
                } else {
                    assertNull(initial.progressHex); assertEquals(0L, initial.scanRuns); assertEquals(0L, initial.scanEntries)
                }
                val saved = ColdRawHandoffV1.capture(f, paidCut, approval, root)
                f.history.closePersistenceFactoryBeforeProcessExit()
                f.registration.close()
                f.history.runtime.closeRegisteredRuntimeForRecovery() // Actual assembly/root, peer, actors, public trust and sessions.
                f.history.p.f.signed.close() // Also dispose actual old activation custody/files and every earlier provider/root owner.
                f.provider.assertClientsClosed(); f.sealHttp.assertDisposed()
                ColdSqlObservationV1.noRuntimeSessions(f.observer)
                assertEquals(saved.image, ColdSqlObservationV1.image(f.observer))
                saved.write(root)
                database.close() // Borrowed view only. Parent still owns the running synthetic server/material.
                // Deliberately bypass only OUTER TEST row teardown. No live runtime/provider/pool/session is carried across this exit.
                exitProcess(0)
            }
        }
        error("Author must terminate only after its actual original graph and sessions ended.")
    }

    private fun recover(database: PgLifecycleDatabaseFixture, root: Path, paidCut: Boolean) {
        val saved = ColdRawHandoffV1.read(root)
        assertEquals(paidCut, saved.paidCut)
        val old = ProcessHandle.of(saved.authorPid).orElse(null)
        assertFalse(old != null && old.isAlive && old.info().startInstant().orElse(null)?.toString() == saved.authorStart)
        assertTrue(ProcessHandle.current().pid() != saved.authorPid)
        val jdbc = JdbcTemplate(ordinaryCleanupReader(database))
        ColdSqlObservationV1.noRuntimeSessions(jdbc)
        assertEquals(saved.image, ColdSqlObservationV1.image(jdbc))
        val bytes = ColdFixtureFilesV1.read(root.resolve("test-deployment.json"))
        val document = Json.decodeFromString(ComplaintTestDeploymentDocumentV1.serializer(), bytes.toString(Charsets.UTF_8))
        // This decoding configures RAW mock expectations only; the real assembly separately opens/parses the original file.
        val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(document)
        val raw = ColdRawProvidersV1(saved, inputs.journal)
        ColdSqlObservationV1.awaitLeaseExpiry(jdbc, UUID.fromString(document.dataScopeId))
        TestOrdinarySealHttpFixtureV1(Instant.parse(document.retention.lastPreRunRestoreHorizon), document.retention.horizonPolicy,
            protectedIntake = true).use { seal ->
            seal.prepareIndependent(inputs.journal)
            ComplaintTestProcessAssemblyV1.withHttpFixture(raw.secrets::httpClient, PersistenceNanoClock(seal::nanos), seal::now,
                PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY, seal::nativeSts, seal::nativeKms, seal::nativeS3).use { assembly ->
                assembly.assemble(root.resolve("test-deployment.json"), AwsSecretVersionFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS)
                assertEquals(saved.fullDSha256, ColdFixtureFilesV1.sha256(assembly.target.canonicalBytes()))
                assertEquals(saved.secrets.size, raw.secrets.requests.size)
                VersionBoundPersistenceConnectedFixture(database, testIntake = assembly).use { runtime ->
                    runtime.bind(); runtime.start()
                    assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
                    assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                    val probe = TestRecoveryRegistrationSqlProbeV1(ColdSqlObservationV1::advisory, runtime)
                    val executor = runtime.pools.catalogCoordinator.testNamespaceRecoveryRegistration
                    val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                    val prior = field.get(executor) as JdbcTemplate
                    assertSame(prior.dataSource, probe.dataSource); field.set(executor, probe)
                    raw.boundary = probe::assertCommittedAndReleased
                    try {
                        val attempt = ComplaintTestNamespaceRecoveryRegistrationAttemptV1.withHttpFixture(assembly, raw.catalog::httpClient, Clock.systemUTC())
                        probe.original = attempt
                        attempt.register(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials).use { registration ->
                            attempt.requireActualCleanup(); probe.assertCommittedAndReleased()
                            assertEquals(2, probe.observations.size); assertEquals(2, raw.catalog.createdClients)
                            assertEquals(saved.image, ColdSqlObservationV1.image(jdbc), "Fresh registration cannot PROJECT, enroll, repair or charge anything.")
                            assertEquals(saved.fence, ColdSqlObservationV1.fence(jdbc, inputs.dataScopeId))
                            assertSame(assembly.target, registration.process)
                            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { registration.bootstrapExpectedArguments() }
                            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { TestRunSealingV1.begin(registration) }
                            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { ComplaintTestNamespaceRegistrationV1.issuedByRecovery(attempt) }
                            drain(runtime, registration, probe, raw, seal, saved, jdbc)
                        }
                    } finally { field.set(executor, prior); probe.assertPhysicallyReleased(); raw.assertClosed(); raw.boundary = {} }
                }
            }
            raw.assertClosed(); seal.assertDisposed()
        }
        ColdSqlObservationV1.noRuntimeSessions(jdbc)
    }

    private fun drain(runtime: VersionBoundPersistenceConnectedFixture, registration: ComplaintTestNamespaceRegistrationV1,
        registrationProbe: TestRecoveryRegistrationSqlProbeV1, raw: ColdRawProvidersV1, seal: TestOrdinarySealHttpFixtureV1,
        saved: ColdRawHandoffV1, observer: JdbcTemplate) {
        // Same real JPA/audit recipe as withOrdinaryAudit, WITHOUT that fixture's synthetic user INSERT/DELETE.
        val factory = ordinaryCleanupFactory(runtime.pools.ordinary, includeAuditEntities = true)
        try {
            factory.afterPropertiesSet()
            val emf = checkNotNull(factory.`object`)
            val ordinaryOwner = PersistencePhaseOwnership(OrdinaryPersistenceAdmission(2), GuardedJpaTransactionManager(emf, runtime.pools.ordinary))
            val repository = JpaAuditRepositoryAdapter(JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
                .getRepository(SpringDataAuditLogRepository::class.java))
            val audit = AuditService(repository, CurrentUser(), Clock.systemUTC())
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                ComplaintInstallationExchangeAdapter(registration, ordinaryOwner, JdbcTemplate(runtime.pools.ordinary),
                    ComplaintInstallationEnrollmentAudit { scope, allocation, at -> audit.recordInstallationEnrollment(scope, allocation, at) })
            }
            assertEquals(saved.image, ColdSqlObservationV1.image(observer), "Fresh audit graph and refused enrollment do not seed any rows.")
            val owner = PersistencePhaseOwnership.deletion(DeletionPersistenceAdmission(), GuardedJdbcTransactionManager(runtime.pools.deletion))
            val coordinator = TestOrdinaryDrainSqlProbeV1(ColdSqlObservationV1::advisory, runtime)
            val deletion = TestOrdinaryDrainSqlProbeV1(ColdSqlObservationV1::advisory, runtime, deletion = true)
            val templates = listOf<Any>(runtime.pools.catalogCoordinator.testOrdinaryDrain, runtime.pools.catalogCoordinator.testOrdinarySeal).map { executor ->
                val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                Triple(executor, field, field.get(executor) as JdbcTemplate).also { assertSame(it.third.dataSource, coordinator.dataSource); field.set(executor, coordinator) }
            }
            fun physical() { registrationProbe.assertCommittedAndReleased(); coordinator.assertPhysicallyReleased(); deletion.assertPhysicallyReleased() }
            fun released() { physical(); coordinator.assertReleased(); deletion.assertReleased() }
            raw.boundary = ::released; seal.boundary = ::released; seal.nativeBoundary = ::physical
            try {
                val original = TestRunOrdinaryDrainV1.withHttpFixture(registration, owner, deletion, audit, Clock.systemUTC(), System::nanoTime,
                    raw::ordinaryClient, raw.kms::httpClient)
                coordinator.original = original; deletion.original = original
                assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                    original.drain(unbase64(saved.approval), saved.evidence.map(::unbase64), TestOwnerDeleteJournalPublisherFixture.CREDENTIALS,
                        TestOwnerDeleteJournalPublisherFixture.CREDENTIALS))
                released()
                if (saved.paidCut) {
                    assertTrue(raw.requests.none { it.kind == "LIST" })
                    assertTrue(coordinator.calls.none { it.sql in setOf(TestOrdinaryDrainSqlV1.spendAndProgress, TestOrdinaryDrainSqlV1.insertRun, TestOrdinaryDrainSqlV1.insertEntry) })
                    assertEquals(4, raw.requests.count { it.kind == "GET" })
                } else {
                    assertEquals(1, coordinator.calls.count { it.sql == TestOrdinaryDrainSqlV1.spendAndProgress })
                    assertEquals(2, coordinator.calls.count { it.sql == TestOrdinaryDrainSqlV1.insertRun })
                    assertEquals(4, raw.requests.count { it.kind == "LIST" }); assertEquals(12, raw.requests.count { it.kind == "GET" })
                }
                assertEquals(if (saved.paidCut) 4 else 12, raw.kms.requests.size)
                ColdSqlObservationV1.completed(saved, observer, registration.process.consumers.journalConfiguration, seal)
                raw.assertClosed(); seal.assertDisposed()
            } finally {
                raw.boundary = registrationProbe::assertCommittedAndReleased; seal.boundary = {}; seal.nativeBoundary = {}
                templates.forEach { (executor, field, prior) -> field.set(executor, prior) }
            }
        } finally { factory.destroy() }
    }
}
