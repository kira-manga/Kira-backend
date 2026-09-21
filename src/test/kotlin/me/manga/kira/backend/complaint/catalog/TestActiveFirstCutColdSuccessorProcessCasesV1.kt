package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.backend.common.infrastructure.persistence.ColdFixtureFilesV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceEpochRotationSession
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.TestOrdinaryDrainConnectedIT
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSuccessorV1
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Two real JVM histories, source-authored only. Requires frozen D20/TEST5 helpers and the separate
 * closed ACTIVE_FIRST_CUT_RESERVED enum join. No registration/Captured/deadline/cleanup receipt
 * crosses processes. No A seal/checkpoint/healthy outcome is claimed. NOT_COMPILED / NOT_RUN.
 */
internal object TestActiveFirstCutColdSuccessorProcessCasesV1 {
    fun qualify(requested: Boolean) {
        assumeTrue(System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN") == null,
            "Two JVMs use the existing owned Testcontainers attachment; local-controller allowlist stays unchanged.")
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-first-cut-two-jvm-",
            PosixFilePermissions.asFileAttribute(ColdFixtureFilesV1.directoryMode))
        val children = mutableListOf<Process>()
        val database = PgLifecycleDatabaseFixture(TestOrdinaryDrainConnectedIT::class.java) // Exact existing attachment recipe.
        try {
            database.start()
            val jdbc = JdbcTemplate(ordinaryCleanupReader(database))
            val generation = ColdSqlObservationV1.generation(jdbc)
            val descriptor = database.exportColdChildAttachment(root)
            val descriptorHash = ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(descriptor))
            val first = ComplaintTestColdProcessSupportV1.runChild(root, descriptorHash, "author", requested, children,
                ColdTestProcessEntryV1.ACTIVE_FIRST_CUT_RESERVED)
            val saved = ColdReservedFirstCutRawV1.read(root)
            assertEquals(first.first.pid(), saved.authorPid); assertEquals(first.second.toString(), saved.authorStart)
            assertEquals(requested, saved.requested)
            ColdSqlObservationV1.noRuntimeSessions(jdbc)
            assertEquals(generation, ColdSqlObservationV1.generation(jdbc))
            assertTrue(saved.image == ColdReservedFirstCutObservationV1.image(jdbc), "Old process exit cannot mutate the paid history.")
            if (requested) {
                // The ORIGINAL C30s lease must expire naturally; no successor JVM/owner/J deadline exists yet.
                ColdSqlObservationV1.awaitLeaseExpiry(jdbc, UUID.fromString(saved.scope))
                assertTrue(saved.image == ColdReservedFirstCutObservationV1.image(jdbc), "Natural time passage cannot reset a row, charge or fingerprint.")
            }
            val immutable = listOf("owned-container.txt", "test-deployment.json", "reserved-first-cut-raw.json").associateWith {
                ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve(it)))
            }
            val second = ComplaintTestColdProcessSupportV1.runChild(root, descriptorHash, "recover", requested, children,
                ColdTestProcessEntryV1.ACTIVE_FIRST_CUT_RESERVED)
            assertTrue(first.first.pid() != second.first.pid())
            ColdSqlObservationV1.noRuntimeSessions(jdbc)
            assertEquals(generation, ColdSqlObservationV1.generation(jdbc))
            immutable.forEach { (file, hash) -> assertEquals(hash, ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve(file)))) }
            ColdReservedFirstCutObservationV1.completed(saved, jdbc)
        } finally {
            children.forEach(ComplaintTestColdProcessSupportV1::stopOwnedChild)
            check(children.none(Process::isAlive))
            database.close()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

/** Fixed TEST-only entry, using the shared unchanged runtime/classpath/limits. Never an application bootstrap switch. */
internal object TestActiveFirstCutColdSuccessorProcessV1 {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4 && args[2] in setOf("author", "recover") && args[3] in setOf("true", "false"))
        val root = Path.of(args[0]); val stage = args[2]; val requested = args[3].toBooleanStrict()
        try {
            PgLifecycleDatabaseFixture.attachColdChild(root.resolve("owned-container.txt"), args[1]).use { database ->
                if (stage == "author") author(database, root, requested) else recover(database, root, requested)
            }
        } catch (problem: Throwable) {
            val safe = "stage=$stage type=${problem.javaClass.name}\n" + problem.stackTrace.take(10).joinToString("\n") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            runCatching { ColdFixtureFilesV1.write(root.resolve("$stage-failure.txt"), safe.toByteArray()) }
            exitProcess(1) // No provider messages, SQL rows, secrets, tokens or private input bodies leave scratch.
        }
    }

    private fun author(database: PgLifecycleDatabaseFixture, root: Path, requested: Boolean): Nothing {
        VersionBoundPersistenceConnectedFixture(database, testActivation = true, activeFirstCut = true).use { tls ->
            tls.bind()
            withTestActiveFirstCutSuccessor(tls) { successor ->
                val f = successor.first
                if (requested) successor.requestedAfterRealTimeout() else f.capture()
                f.awaitNativeReclaimed(); f.assertSqlReleased()
                ColdReservedFirstCutObservationV1.current(f.observer, f.scope, requested)
                val saved = ColdReservedFirstCutRawV1.capture(f, root, requested)
                f.registration.close()
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.registration.requireReleasedIdentityAdmission() }
                f.runtime.closeRegisteredRuntimeForRecovery() // Actual protected assembly, native session/root, peer, pools and trust.
                f.p.f.signed.close() // All earlier named activation/publication/provider/root owners also retire.
                f.native.close(); f.native.assertDisposed()
                assertTrue(f.native.nativeFactories.isEmpty(), "No ordinary seal/provider authority was used.")
                assertEquals(f.p.f.http.read.createdClients, f.p.f.http.read.closedClients)
                ColdSqlObservationV1.noRuntimeSessions(f.observer)
                assertTrue(saved.image == ColdReservedFirstCutObservationV1.image(f.observer))
                saved.write(root)
                database.close() // Borrowed child view only; parent retains the one server/TLS generation.
                // Bypass only outer TEST row teardown, after all actual owners/sessions have retired.
                exitProcess(0)
            }
        }
        error("The author exits only after actual full original retirement.")
    }

    private fun recover(database: PgLifecycleDatabaseFixture, root: Path, requested: Boolean) {
        val saved = ColdReservedFirstCutRawV1.read(root)
        assertEquals(requested, saved.requested)
        val old = ProcessHandle.of(saved.authorPid).orElse(null)
        assertFalse(old != null && old.isAlive && old.info().startInstant().orElse(null)?.toString() == saved.authorStart)
        assertTrue(ProcessHandle.current().pid() != saved.authorPid)
        val jdbc = JdbcTemplate(ordinaryCleanupReader(database))
        ColdSqlObservationV1.noRuntimeSessions(jdbc)
        assertTrue(saved.image == ColdReservedFirstCutObservationV1.image(jdbc))
        val document = Json.decodeFromString(ComplaintTestDeploymentDocumentV1.serializer(),
            ColdFixtureFilesV1.read(root.resolve("test-deployment.json")).toString(Charsets.UTF_8))
        val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(document) // Raw HTTP expectation setup only; assembly reparses protected bytes.
        assertEquals(ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_SUCCESSOR_PROFILE, document.profile)
        var boundary: () -> Unit = {}
        val raw = ColdIdentityRawProvidersV1(saved.secrets, saved.catalog) { requireConnectionFree(); boundary() }
        TestOrdinarySealHttpFixtureV1(Instant.parse(document.retention.lastPreRunRestoreHorizon), document.retention.horizonPolicy,
            protectedIntake = true).use { native ->
            native.prepareIndependent(inputs.journal)
            try {
                ComplaintTestProcessAssemblyV1.withHttpFixture(raw.secrets::httpClient, PersistenceNanoClock(native::nanos), native::now,
                    PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY, native::nativeSts, native::nativeKms, native::nativeS3,
                    ordinarySts = { error("Premature ordinary STS") }, ordinaryKms = { error("Premature ordinary KMS") },
                    ordinaryS3 = { error("Premature ordinary S3") }).use { assembly ->
                    assembly.assemble(root.resolve("test-deployment.json"), AwsSecretVersionFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS,
                        TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
                    assertEquals(saved.fullDSha256, ColdFixtureFilesV1.sha256(assembly.target.canonicalBytes()))
                    assertEquals(saved.secrets.size, raw.secrets.requests.size)
                    VersionBoundPersistenceConnectedFixture(database, testIntake = assembly).use { runtime ->
                        runtime.bind(); runtime.start()
                        assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
                        assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
                        assertTrue(saved.image == ColdReservedFirstCutObservationV1.image(jdbc), "No startup migration, activation, reset, enrollment or recharge.")
                        val probe = TestActiveRegistrationSqlProbeV1(ColdSqlObservationV1::advisory, runtime)
                        val executor = runtime.pools.catalogCoordinator.testNamespaceActiveRegistration
                        val field = executor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                        val previous = field.get(executor) as JdbcTemplate
                        assertSame(previous.dataSource, probe.dataSource); field.set(executor, probe)
                        boundary = probe::assertCommittedAndReleased
                        try {
                            val registrationAttempt = ComplaintTestNamespaceActiveRegistrationAttemptV1.withHttpFixture(assembly,
                                raw.catalog::httpClient, SignedActivationObservation.WALL_CLOCK)
                            probe.original = registrationAttempt
                            registrationAttempt.register(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials).use { registration ->
                                registrationAttempt.requireActualCleanup(); probe.assertCommittedAndReleased(); raw.assertClosed()
                                assertEquals(2, probe.observations.size); assertEquals(2, raw.catalog.createdClients)
                                assertTrue(saved.image == ColdReservedFirstCutObservationV1.image(jdbc), "D re-admits identity only; it does not recover C or rewrite V26.")
                                registration.requireActiveIdentityTarget(assembly)
                                val resource = checkNotNull(runtime.pools.epochRotation)
                                assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, resource.observePreparation())
                                if (requested) assertEquals(PersistenceLifecycleObservation.READY, resource.prepare())
                                val successorProbe = CatalogSignerRotationProbeJdbc(runtime.pools.catalogCoordinator)
                                val successorExecutor = runtime.pools.catalogCoordinator.testActiveFirstCutSuccessor
                                val successorField = successorExecutor.javaClass.getDeclaredField("jdbc").apply { check(trySetAccessible()) }
                                val successorPrevious = successorField.get(successorExecutor) as JdbcTemplate
                                assertSame(successorPrevious.dataSource, successorProbe.dataSource); successorField.set(successorExecutor, successorProbe)
                                val nativeSession = AtomicReference<PersistenceEpochRotationSession?>()
                                fun released() {
                                    requireConnectionFree(); probe.assertCommittedAndReleased()
                                    successorProbe.observations.forEach { (phase, observed) ->
                                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
                                        assertTrue(observed.lease.completion.quiescent())
                                    }
                                    successorProbe.assertNoLostAssertions()
                                }
                                boundary = ::released
                                native.onNanoSample = {
                                    val call = (ownedCutField(resource, "active") as AtomicReference<*>).get()
                                    val session = call?.let { ownedCutField(it, "session") as? PersistenceEpochRotationSession }
                                    if (session != null) {
                                        val previousSession = nativeSession.get()
                                        if (previousSession == null) assertTrue(nativeSession.compareAndSet(null, session)) else assertSame(previousSession, session)
                                    }
                                }
                                try {
                                    val original = TestActiveFirstCutSuccessorV1.withHttpFixture(registration, assembly,
                                        raw.catalog::httpClient, SignedActivationObservation.WALL_CLOCK)
                                    val recovered = original.recover(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
                                    original.requireActualCleanup(); released(); raw.assertClosed()
                                    assertEquals(4, raw.catalog.createdClients, "New successor performs its own dual raw read, not a D-proof reuse.")
                                    val handoff = recovered.claimSeal(registration, assembly)
                                    handoff.requireRetained()
                                    assertSame(registration, handoff.registration); assertSame(assembly, handoff.assembly)
                                    assertEquals(UUID.fromString(saved.scope), handoff.slot.identity.scope)
                                    val fingerprint = checkNotNull(jdbc.queryForObject(
                                        "SELECT sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin', i.xmin::text))::text, 'UTF8')) " +
                                            "FROM complaint_test_active_seal_intents i WHERE data_scope_id = ?", ByteArray::class.java, inputs.dataScopeId))
                                    assertArrayEquals(fingerprint, handoff.slot.fingerprint())
                                    assertThrows<RuntimeException> { recovered.claimSeal(registration, assembly) }
                                    assertThrows<RuntimeException> { TestActiveFirstCutSuccessorV1.Recovered.issue(original) }
                                    assertThrows<RuntimeException> { original.recover(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials) }
                                    assertNull(SignedActivationObservation.active(runtime.pools.catalogCoordinator))
                                    assertEquals(if (requested) listOf(FIRST_CUT_SUCCESSOR_READ, FIRST_CUT_SUCCESSOR_LEASE)
                                        else listOf(FIRST_CUT_SUCCESSOR_READ, FIRST_CUT_SUCCESSOR_LEASE, FIRST_CUT_SUCCESSOR_RELEASE),
                                        successorProbe.calls.map { it.path }.distinct())
                                    assertFalse(successorProbe.steps.any { it.startsWith("charge:") || it == "test-first-cut-request" || it == "test-first-cut-insert" })
                                    if (requested) {
                                        val session = checkNotNull(nativeSession.get())
                                        assertEquals(PersistenceDatabaseOutcome.COMMITTED, session.failure().databaseOutcome)
                                        assertTrue(session.failure().cleanupProven)
                                        assertTrue(poolTestField<PersistencePhysicalEntry>(session, "entry").jdbc.terminalCompletion().reclaimed())
                                    } else {
                                        assertNull(nativeSession.get())
                                        assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, resource.observePreparation())
                                    }
                                    ColdReservedFirstCutObservationV1.completed(saved, jdbc)
                                } finally {
                                    native.onNanoSample = null
                                    successorField.set(successorExecutor, successorPrevious)
                                    successorProbe.assertNoLostAssertions(); boundary = probe::assertCommittedAndReleased
                                }
                            }
                        } finally { field.set(executor, previous); probe.assertPhysicallyReleased(); raw.assertClosed(); boundary = {} }
                    }
                }
            } finally { raw.assertClosed(); native.assertDisposed() }
            assertTrue(native.nativeFactories.isEmpty())
        }
        ColdSqlObservationV1.noRuntimeSessions(jdbc)
        ColdReservedFirstCutObservationV1.completed(saved, jdbc)
    }
}

/** Separate ACTIVE raw envelope: no SEALED approval/evidence/object requirements are reused or weakened. */
@Serializable
internal data class ColdReservedFirstCutRawV1(
    val format: Int, val requested: Boolean, val authorPid: Long, val authorStart: String, val scope: String,
    val documentSha256: String, val fullDSha256: String,
    val secrets: List<ColdSecretObjectV1>, val catalog: List<ColdCatalogObjectV1>, val image: Map<String, List<String>>,
) {
    fun write(root: Path) = ColdFixtureFilesV1.write(root.resolve("reserved-first-cut-raw.json"), Json.encodeToString(serializer(), this).toByteArray())
    companion object {
        fun read(root: Path): ColdReservedFirstCutRawV1 = Json.decodeFromString(serializer(),
            ColdFixtureFilesV1.read(root.resolve("reserved-first-cut-raw.json")).toString(Charsets.UTF_8)).also {
            check(it.format == 1 && it.authorPid > 0 && Instant.parse(it.authorStart).toString() == it.authorStart)
            check(UUID.fromString(it.scope).let { id -> id.version() == 4 && id.toString() == it.scope })
            check(it.fullDSha256.matches(Regex("[0-9a-f]{64}")))
            check(it.secrets.size in 2..64 && it.catalog.size in 2..64)
            check(it.secrets.map { raw -> raw.arn to raw.version }.toSet().size == it.secrets.size)
            check(it.catalog.map { raw -> Triple(raw.bucket, raw.key, raw.version) }.toSet().size == it.catalog.size)
            check(it.documentSha256 == ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve("test-deployment.json"))))
            check(it.image.getValue("complaint_test_active_seal_intents").size == 1)
        }
        fun capture(f: TestActiveFirstCutFixtureV1, root: Path, requested: Boolean): ColdReservedFirstCutRawV1 {
            f.assertSqlReleased()
            val evidence = f.p.f.rows.evidence
            val input = evidence.coldInputBytes()
            ColdFixtureFilesV1.write(root.resolve("test-deployment.json"), input)
            val catalog = coldCatalogObjectsV1(f.p.f.http.read)
            check(catalog.values.groupBy { it.bucket }.values.map { it.size }.toSet() == setOf(evidence.prefix.size + 1))
            check(catalog.values.map { it.bucket }.toSet() == OfflineTrustBundleFixture.locations.map { it.bucket }.toSet())
            val process = ProcessHandle.current()
            return ColdReservedFirstCutRawV1(1, requested, process.pid(), process.info().startInstant().orElseThrow().toString(), f.scope.toString(),
                ColdFixtureFilesV1.sha256(input), ColdFixtureFilesV1.sha256(f.process.canonicalBytes()), evidence.coldSecretObjects(),
                catalog.values.toList(), ColdReservedFirstCutObservationV1.image(f.observer))
        }
    }
}

/** Fixed extra V26 observation; the existing shared SEALED/ACTIVE observation inventory remains untouched. */
internal object ColdReservedFirstCutObservationV1 {
    fun image(jdbc: JdbcTemplate): Map<String, List<String>> = ColdSqlObservationV1.image(jdbc) + mapOf(
        "complaint_test_active_seal_intents" to jdbc.queryForList(
            "SELECT jsonb_build_array(to_jsonb(i), i.xmin::text)::text FROM complaint_test_active_seal_intents i ORDER BY i.operation_token", String::class.java),
    )
    fun current(jdbc: JdbcTemplate, scope: UUID, requested: Boolean) {
        assertEquals(true, jdbc.queryForObject("SELECT count(*) = 2 FROM flyway_schema_history WHERE version IN ('25','26') AND success", Boolean::class.java))
        assertEquals(true, jdbc.queryForObject("SELECT state = 'ACTIVE' AND enrolled_count = 0 AND sealed_at IS NULL AND permanent_denial_bytes IS NULL " +
            "AND seal_set_bytes IS NULL FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java, scope))
        val c = jdbc.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", scope)
        val i = jdbc.queryForMap("SELECT * FROM complaint_test_active_seal_intents WHERE data_scope_id = ?", scope)
        assertEquals(1L, c["rotation_sequence"]); assertEquals(1L, c["rotation_epoch_before"])
        assertEquals(if (requested) "REQUESTED" else "CAPTURED", c["rotation_state"])
        assertEquals(if (requested) 1L else 2L, c["publication_epoch"])
        assertEquals(requested, c["scan_requested"]); assertEquals(false, c["maintenance_closed"]); assertEquals(false, c["creation_closed"])
        assertEquals("RESERVED", i["state"]); assertEquals(2_097_152L, i["charged_storage_bytes"])
        assertEquals(c["rotation_id"], i["operation_token"]); assertEquals(c["rotation_request_token"], i["request_token"])
        assertEquals(c["rotation_capture_token"], i["capture_token"])
        listOf("seal_state", "seal_epoch", "seal_bytes", "checkpoint_result", "checkpoint_completed_at", "checkpoint_bytes").forEach { assertNull(c[it]) }
        listOf("canonical_bytes", "wire_bytes", "object_key", "preparing_fencing_token").forEach { assertNull(i[it]) }
        if (requested) assertTrue(c["lease_owner"] != null && i["capture_owner"] == null)
        else { assertNull(c["lease_owner"]); assertNull(c["lease_expires_at"]); assertEquals(2L, i["epoch_after"]) }
    }
    fun completed(saved: ColdReservedFirstCutRawV1, jdbc: JdbcTemplate) {
        val before = saved.image; val after = image(jdbc); val scope = UUID.fromString(saved.scope)
        current(jdbc, scope, requested = false)
        assertEquals(before.keys, after.keys)
        val mutableTables = setOf("complaint_journal_control", "complaint_test_active_seal_intents")
        (before.keys - mutableTables).forEach { assertTrue(before.getValue(it) == after.getValue(it), "No unrelated row/xmin, P22, reserve or identity change: $it") }
        val priorControls = before.getValue("complaint_journal_control").associateBy { text(row(it), "data_scope_id") }
        after.getValue("complaint_journal_control").forEach { bytes ->
            val c = row(bytes); val oldBytes = priorControls.getValue(text(c, "data_scope_id")); val old = row(oldBytes)
            if (text(c, "data_scope_id") != saved.scope) assertTrue(oldBytes == bytes, "Global/foreign controls cannot churn.")
            else {
                val allowed = setOf("lease_owner", "lease_token", "lease_expires_at", "updated_at") + if (saved.requested)
                    setOf("publication_epoch", "scan_requested", "rotation_state", "rotation_capture_owner", "rotation_capture_token", "rotation_captured_at", "rotation_epoch_after") else emptySet()
                assertTrue(old.filterKeys { it !in allowed } == c.filterKeys { it !in allowed }, "Original full identity/request/other control fields stay exact.")
                assertEquals(number(old, "lease_token") + 1, number(c, "lease_token"))
                assertEquals(JsonNull, c["lease_owner"]); assertEquals(JsonNull, c["lease_expires_at"])
            }
        }
        val oldSlot = before.getValue("complaint_test_active_seal_intents").single()
        val slot = after.getValue("complaint_test_active_seal_intents").single()
        if (!saved.requested) assertTrue(oldSlot == slot, "Already CAPTURED immutable paid slot/xmin cannot be rewritten.")
        else {
            val mutable = setOf("capture_owner", "capture_token", "captured_at", "epoch_after")
            assertTrue(row(oldSlot).filterKeys { it !in mutable } == row(slot).filterKeys { it !in mutable })
            assertTrue(number(row(slot), "capture_token") > number(row(slot), "request_token"))
        }
        listOf("complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_test_terminal_intents", "complaint_journal_publications").forEach {
            assertTrue(after.getValue(it).isEmpty(), "Recovered historical handoff is not A seal/checkpoint/content authority: $it")
        }
    }
    private fun row(value: String): JsonObject = Json.parseToJsonElement(value).jsonArray[0].jsonObject
    private fun text(row: JsonObject, name: String): String = row.getValue(name).jsonPrimitive.content
    private fun number(row: JsonObject, name: String): Long = row.getValue(name).jsonPrimitive.long
}
