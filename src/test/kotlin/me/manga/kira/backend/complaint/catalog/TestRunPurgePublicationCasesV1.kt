package me.manga.kira.backend.complaint.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeStepV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.terminalCanonical
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalHash
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat

/** Authored connected mechanics only. No full purge, historical-lineage or deployed-denial claim. */
internal object TestRunPurgePublicationCasesV1 {
    fun unusedAndReplay(tls: VersionBoundPersistenceConnectedFixture, unknownPut: Boolean = false) = withUnusedPurgeRun(tls) { f, manifest, probe ->
        val beforeCounters = f.p.counters()
        val beforeUnused = unused(f)
        val unchanged = preserved(f)
        val order = f.sealHttp.order.size
        val staged = linkedMapOf<PersistencePhaseContext, Map<String, List<String>>>()
        val committed = linkedMapOf<PersistencePhaseContext, Map<String, List<String>>>()
        probe.before = { call ->
            if (call.sql in setOf(TestRunPurgeSqlV1.insertPublication, TestRunPurgeSqlV1.freeze, TestRunPurgeSqlV1.verify)) {
                staged[call.phase] = rows(f)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() { committed[call.phase] = rows(f) }
                })
            }
        }
        probe.after = { call -> staged[call.phase]?.let { assertEquals(it, rows(f), "An independent connection cannot see a partial PREPARE/freeze/VERIFY.") } }
        f.sealHttp.beforeS3 = {
            probe.assertReleased()
            assertEquals(TestRunPurgeStepV1.FREEZE, probe.calls.last().step)
            assertEquals(listOf("PREPARED" to "WIRE_FROZEN"), states(f))
            assertEquals(1, f.registration.process.publicationLanes.activeOwners().routineOwners)
            assertEquals(beforeUnused - ACTUAL - AUDIT, unused(f), "Physical bytes and future AUDIT are durable before any own S3.")
        }
        f.sealHttp.lostPutAcknowledgment = unknownPut
        val original = publish(manifest, probe)
        f.sealHttp.lostPutAcknowledgment = false; f.sealHttp.beforeS3 = {}; probe.before = {}; probe.after = {}
        probe.assertReleased(); f.assertReleased()
        assertEquals(TestRunPurgeStepV1.entries, probe.calls.map { it.step }.distinct())
        assertEquals(5, probe.observations.size)
        assertEquals(3, staged.size); assertEquals(staged.keys, committed.keys)
        staged.forEach { (phase, image) -> assertFalse(image == committed.getValue(phase)) }
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "GENERATE", "LIST", "PUT", "LIST", "GET", "DECRYPT"), f.sealHttp.order.drop(order))
        assertEquals(if (unknownPut) 500 else 200, f.sealHttp.requests.last { it.kind == "PUT" }.reply?.status)
        assertEquals(beforeUnused - ACTUAL - AUDIT, unused(f))
        val afterCounters = f.p.counters()
        ComplaintCapacityCounter.entries.forEach { counter ->
            val before = beforeCounters.getValue(counter.storedName); val after = afterCounters.getValue(counter.storedName)
            assertEquals(before.free, after.free, counter.storedName)
            assertEquals(before.actual + ACTUAL[counter], after.actual, counter.storedName)
            assertEquals(before.recovery + AUDIT[counter], after.recovery, counter.storedName)
            assertEquals(before.reserved - ACTUAL[counter] - AUDIT[counter], after.reserved, counter.storedName)
            assertEquals(after.hard, after.free + after.actual + after.recovery + after.reserved)
        }
        assertEquals(unchanged, preserved(f)); assertPublished(f, original)
        val exact = rows(f)
        val ref = original.authenticatedPurge()
        val beforeRetry = f.sealHttp.order.size
        probe.reset()
        val retry = publish(manifest, probe)
        assertNotSame(original, retry); assertEquals(original.leaseToken + 1, retry.leaseToken)
        assertEquals(ref, retry.authenticatedPurge()); assertEquals(exact, rows(f))
        assertEquals(afterCounters, f.p.counters()); assertEquals(beforeUnused - ACTUAL - AUDIT, unused(f))
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "LIST", "GET", "DECRYPT"), f.sealHttp.order.drop(beforeRetry))
        assertTrue(probe.calls.none { it.sql in setOf(TestRunPurgeSqlV1.insertPublication, TestRunPurgeSqlV1.insertRecovery,
            TestRunPurgeSqlV1.insertSidecar, TestRunPurgeSqlV1.freeze, TestRunPurgeSqlV1.verify) })
        val calls = probe.calls.size
        assertThrows<TestRunPurgeExceptionV1> { original.publish() }
        assertEquals(calls, probe.calls.size)
    }

    fun predecessorAndReferenceRefusals(tls: VersionBoundPersistenceConnectedFixture) =
        withUnusedPurgeRun(tls, checkUnstartedManifest = true) { f, manifest, probe ->
        val providers = f.sealHttp.order.toList()
        assertTrue(probe.calls.isEmpty())
        // A syntactically valid changed seal reference, even with freshly matching SQL hashes/root,
        // cannot replace the real predecessor's authenticated exact object version.
        val old = checkNotNull(f.observer.queryForObject("SELECT seal_set_bytes FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, f.scope))
        val document = Json.parseToJsonElement(old.toString(Charsets.UTF_8)).jsonObject
        val record = document.getValue("records").jsonArray.single().jsonObject
        val reference = record.getValue("object").jsonObject
        val changedRecord = JsonObject(record + ("object" to JsonObject(reference + ("objectVersion" to JsonPrimitive("wrong-current-version")))))
        val records = JsonArray(listOf(changedRecord))
        val changed = terminalCanonical(JsonObject(document + ("records" to records)))
        assertEquals(1, f.observer.update("UPDATE complaint_test_runs SET seal_set_bytes = ?, seal_set_hash = ?, generation_seal_root = ? WHERE data_scope_id = ?",
            changed, hex(Sha256.hex(changed)), hex(terminalHash(terminalCanonical(records))), f.scope))
        val image = rows(f); val counters = f.p.counters(); val reserve = unused(f)
        val original = manifest.beginPurgePublication().also { probe.original = it }
        assertThrows<TestRunPurgeExceptionV1> { original.publish() }
        probe.assertReleased(requireCommitted = false)
        assertEquals(image, rows(f)); assertEquals(counters, f.p.counters()); assertEquals(reserve, unused(f))
        assertEquals(providers, f.sealHttp.order)
        assertTrue(probe.calls.all { it.step === TestRunPurgeStepV1.CAPTURE })
        assertThrows<TestRunPurgeExceptionV1> { original.authenticatedPurge() }
    }

    fun historicalFixtureIsNotLaundered(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        val manifest = TestInstallationManifestPublicationCasesV1.publish(preparation, probe)
        assertTrue(manifest.control.previousSealEpoch > 0)
        val image = manifestRowsImage(f); val providers = f.sealHttp.order.toList()
        assertThrows<TestRunPurgeExceptionV1> { manifest.beginPurgePublication() }
        assertEquals(image, manifestRowsImage(f)); assertEquals(providers, f.sealHttp.order)
        assertTrue(f.observer.queryForObject("SELECT checkpoint_generation IS NOT NULL AND seal_epoch > 0 FROM complaint_journal_control WHERE data_scope_id = ?",
            Boolean::class.java, f.scope) == true, "Historical rows stay present; no waiver or erasure makes the fixture initial.")
    }

    fun completion(tls: VersionBoundPersistenceConnectedFixture, step: TestRunPurgeStepV1, cut: TestRegistrationCompletionCut,
        preconditionOnRetry: Boolean = false) = withUnusedPurgeRun(tls, shortHorizon = preconditionOnRetry) { f, manifest, probe ->
            require(step in setOf(TestRunPurgeStepV1.PREPARE, TestRunPurgeStepV1.VERIFY))
            require(!preconditionOnRetry || step === TestRunPurgeStepV1.VERIFY && cut === TestRegistrationCompletionCut.BEFORE_COMMIT)
            val beforeUnused = unused(f)
            val beforeCounters = f.p.counters()
            val unchanged = preserved(f)
            val providers = f.sealHttp.order.toList()
            var selected: PersistencePhaseContext? = null
            val resource = Any(); val sentinel = Any(); var bound = false
            val statement = if (step === TestRunPurgeStepV1.PREPARE) TestRunPurgeSqlV1.insertSidecar else TestRunPurgeSqlV1.verify
            probe.after = { call ->
                if (selected == null && call.step === step && call.sql == statement) {
                    selected = call.phase
                    if (cut === TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                        val jdbc = JdbcTemplate(checkNotNull(probe.dataSource))
                        jdbc.execute("CREATE TEMP TABLE kira_purge_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, jdbc.update("INSERT INTO kira_purge_commit_cut VALUES (1), (1)"))
                    } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            if (cut === TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic purge beforeCommit refusal.")
                        }
                        override fun afterCommit() {
                            if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                                TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                            }
                            if (cut !== TestRegistrationCompletionCut.BEFORE_COMMIT) error("Synthetic purge acknowledgment loss.")
                        }
                    })
                }
            }
            val original = manifest.beginPurgePublication().also { probe.original = it }
            try {
                assertThrows<TestRunPurgeExceptionV1> { original.publish() }
                assertTrue(selected != null, "The actual owned mutation reached the selected failure cut.")
                if (cut === TestRegistrationCompletionCut.UNRESOLVED_RELEASE) {
                    assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current())
                    assertTrue(checkNotNull(selected).quarantined())
                    assertFalse(checkNotNull(selected).testRunPurgeResourcesRetired(original))
                    assertThrows<RuntimeException> { manifest.beginPurgePublication() }
                }
            } finally {
                probe.after = {}
                if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
                requireConnectionFree()
            }
            probe.assertReleased(requireCommitted = false)
            val outcome = when (cut) {
                TestRegistrationCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
                TestRegistrationCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
                else -> PersistenceDatabaseOutcome.COMMITTED
            }
            assertEquals(outcome, checkNotNull(selected).databaseOutcome())
            val paid = step === TestRunPurgeStepV1.VERIFY || outcome === PersistenceDatabaseOutcome.COMMITTED
            val verified = step === TestRunPurgeStepV1.VERIFY && outcome === PersistenceDatabaseOutcome.COMMITTED
            assertEquals(if (!paid) emptyList() else listOf((if (verified) "VERIFIED" else "PREPARED") to
                (if (step === TestRunPurgeStepV1.VERIFY) "WIRE_FROZEN" else "CANONICAL")), states(f))
            assertEquals(if (paid) beforeUnused - ACTUAL - AUDIT else beforeUnused, unused(f))
            if (!paid) assertEquals(beforeCounters, f.p.counters())
            if (step === TestRunPurgeStepV1.PREPARE) assertEquals(providers, f.sealHttp.order)
            assertEquals(unchanged, preserved(f))
            assertTrue(probe.calls.none { it.step === TestRunPurgeStepV1.COMPLETE })
            assertThrows<TestRunPurgeExceptionV1> { original.authenticatedPurge() }
            val image = rows(f)
            val beforeRetry = f.sealHttp.order.size
            var retryLock: Instant? = null
            if (preconditionOnRetry) {
                val stored = f.sealHttp.purgeObjects.values.single()
                val minimum = Instant.parse(stored.metadata.getValue("kira-journal-retain-until"))
                assertEquals(0L, f.sealHttp.offsetNanos)
                val started = System.nanoTime()
                Thread.sleep(1_100) // Bounded real time separates the later PUT floor; this is not lease-expiry evidence.
                assertTrue(System.nanoTime() - started >= 1_100_000_000L)
                var lists = 0
                f.sealHttp.beforeS3 = { request ->
                    if (request.kind == "LIST") f.sealHttp.hideObject = ++lists == 1
                    if (request.kind == "PUT") {
                        assertTrue(retryLock == null)
                        retryLock = Instant.parse(request.header("x-amz-object-lock-retain-until-date"))
                        assertTrue(checkNotNull(retryLock) > stored.retainUntil)
                        assertTrue(stored.retainUntil >= maxOf(minimum,
                            stored.lastModified.atOffset(ZoneOffset.UTC).plusYears(10).toInstant(), f.sealHttp.horizon.plusSeconds(31 * 86_400L)))
                        assertEquals(minimum.toString(), request.header("x-amz-meta-kira-journal-retain-until"))
                        assertArrayEquals(stored.bytes, request.body)
                    }
                }
            }
            f.expireLeaseForRetry(); probe.reset()
            val retry = try { publish(manifest, probe) }
            finally { f.sealHttp.beforeS3 = {}; f.sealHttp.hideObject = false }
            assertEquals(beforeUnused - ACTUAL - AUDIT, unused(f), "Retry never refunds/re-spends Delta or charges a second physical winner.")
            if (step === TestRunPurgeStepV1.VERIFY) {
                assertEquals(image.getValue("sidecar"), rows(f).getValue("sidecar"))
                assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET") +
                    (if (preconditionOnRetry) listOf("LIST", "PUT", "LIST", "GET", "DECRYPT") else listOf("LIST", "GET", "DECRYPT")),
                    f.sealHttp.order.drop(beforeRetry))
            }
            if (preconditionOnRetry) {
                assertTrue(retryLock != null)
                assertEquals(412, f.sealHttp.requests.last { it.kind == "PUT" }.reply?.status)
                assertTrue(f.sealHttp.purgeObjects.values.single().retainUntil < checkNotNull(retryLock),
                    "412 validates actual C/M/calendar/horizon, never claims the rejected proposed lock was installed.")
            }
            if (verified) assertEquals(image, rows(f))
            assertPublished(f, retry)
            assertThrows<TestRunPurgeExceptionV1> { original.authenticatedPurge() }
            assertThrows<TestRunPurgeExceptionV1> { original.publish() }
        }

    private fun publish(manifest: TestRunInstallationManifestPublicationV1, probe: TestRunPurgeSqlProbeV1): TestRunPurgePublicationV1 =
        manifest.beginPurgePublication().also { original ->
            probe.original = original
            assertEquals(TestRunPurgePublicationResultV1.PURGE_AUTHENTICATED_AND_VERIFIED, original.publish())
        }

    private fun assertPublished(f: TestRunPurgeFixtureV1, original: TestRunPurgePublicationV1) {
        f.assertNoPreviousHistory()
        assertEquals(listOf("VERIFIED" to "WIRE_FROZEN"), states(f))
        val row = f.observer.queryForMap("SELECT p.event_bytes, p.verification_bytes, p.object_version, p.ciphertext_hash, p.retain_until, p.applied_at, " +
            "r.reserved_amounts::text AS promise, r.state AS recovery_state, r.converted_amounts, i.wire_bytes, i.canonical_bytes " +
            "FROM complaint_journal_publications p JOIN complaint_recovery_capacity_reservations r ON r.publication_ref = p.event_id " +
            "JOIN complaint_test_terminal_intents i ON i.publication_ref = p.event_id WHERE p.data_scope_id = ? AND p.event_kind = 'TEST_RUN_PURGE'", f.scope)
        val body = row.getValue("event_bytes") as ByteArray
        assertArrayEquals(body, row.getValue("canonical_bytes") as ByteArray)
        val declaration = TestTerminalJsonV1(f.registration.process.consumers.journalConfiguration).purge(body)
        val context = original.runContext
        assertEquals(context, declaration.context().run); assertEquals(original.ordinarySeal, declaration.finalOrdinarySeal)
        assertEquals(1L, declaration.preTerminalSeals.count); assertEquals(1L, declaration.preTerminalInventory.count)
        assertEquals(original.manifest.authenticatedSummary(), declaration.installationManifest)
        val installation = terminalHash(terminalFrame(listOf("kira-test-installations-v1", context.dataScopeId, context.activationCatalogGeneration.toString(),
            context.activationCatalogSha256, context.configurationSha256, context.terminalEncodingSha256, "0", "0", "0")))
        val chunks = terminalHash(terminalFrame(listOf("kira-test-manifest-chunks-v1", context.dataScopeId, context.activationCatalogGeneration.toString(), context.activationCatalogSha256, "0")))
        assertEquals(installation, declaration.installationManifest.installationsSha256); assertEquals(chunks, declaration.installationManifest.chunksSha256)
        val seal = original.ordinarySeal
        val reference = seal.objectRef
        val sealSet = checkNotNull(f.observer.queryForObject("SELECT seal_set_bytes FROM complaint_test_runs WHERE data_scope_id = ?", ByteArray::class.java, f.scope))
        val sealRecords = Json.parseToJsonElement(sealSet.toString(Charsets.UTF_8)).jsonObject.getValue("records").jsonArray
        assertEquals(terminalHash(terminalCanonical(sealRecords)), declaration.preTerminalSeals.sha256)
        val inventory = terminalHash(terminalFrame(listOf("kira-test-preterminal-inventory-v1", context.dataScopeId, context.activationCatalogGeneration.toString(), context.activationCatalogSha256, "1")),
            terminalFrame(listOf(seal.writerGeneration, "EPOCH_SEAL", seal.epochStartInclusive.toString(), seal.epochEndInclusive.toString(),
                reference.objectKey, reference.objectVersion, reference.ciphertextSha256, reference.canonicalSha256)))
        assertEquals(inventory, declaration.preTerminalInventory.sha256)
        val provider = f.sealHttp.purgeObjects.values.single()
        assertArrayEquals(provider.bytes, row.getValue("wire_bytes") as ByteArray)
        val actual = original.authenticatedPurge()
        assertEquals(provider.key, actual.objectKey); assertEquals(provider.version, actual.objectVersion)
        assertEquals(Sha256.hex(provider.bytes), actual.ciphertextSha256); assertEquals(Sha256.hex(body), actual.canonicalSha256)
        val proof = ObjectMapper().readTree(row.getValue("verification_bytes") as ByteArray)
        assertEquals("TEST_RUN_PURGE", proof["objectKind"].textValue())
        assertEquals(provider.metadata.getValue("kira-journal-retain-until"), proof["requestedRetainUntil"].textValue())
        assertEquals(provider.retainUntil.toString(), proof["retainUntil"].textValue())
        assertEquals(AUDIT.toLongArray().joinToString(",", "{", "}"), row["promise"])
        assertEquals("RESERVED", row["recovery_state"]); assertEquals(null, row["converted_amounts"]); assertEquals(null, row["applied_at"])
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_TEST_RUN_PURGED'", Long::class.java, f.scope))
        assertTrue(f.observer.queryForObject("SELECT state = 'SEALED' AND purging_at IS NULL AND purged_at IS NULL AND terminal_event_id IS NULL " +
            "AND terminal_catalog_generation IS NULL AND event_manifest_root IS NULL FROM complaint_test_runs WHERE data_scope_id = ?", Boolean::class.java, f.scope) == true)
        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, f.scope))
        assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND object_kind = 'EPOCH_SEAL'", Long::class.java, f.scope))
    }

    private fun rows(f: TestRunPurgeFixtureV1): Map<String, List<String>> = f.raw { connection ->
        mapOf("sidecar" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_test_terminal_intents t WHERE data_scope_id = ? AND object_kind = 'TEST_RUN_PURGE'",
            "publication" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_journal_publications t WHERE data_scope_id = ? AND event_kind = 'TEST_RUN_PURGE'",
            "reservation" to "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_recovery_capacity_reservations t WHERE data_scope_id = ?").mapValues { (_, sql) ->
            connection.prepareStatement(sql).use { statement -> statement.setObject(1, f.scope); statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1)) } } }
        }
    }
    private fun states(f: TestRunPurgeFixtureV1): List<Pair<String, String>> = f.raw { connection ->
        connection.prepareStatement("SELECT p.state, i.state FROM complaint_journal_publications p JOIN complaint_test_terminal_intents i ON i.publication_ref = p.event_id " +
            "WHERE p.data_scope_id = ? AND p.event_kind = 'TEST_RUN_PURGE'").use { statement ->
            statement.setObject(1, f.scope); statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1) to row.getString(2)) } }
        }
    }
    private fun unused(f: TestRunPurgeFixtureV1): ComplaintCapacityVector = f.raw { connection ->
        connection.prepareStatement("SELECT unused_reserve FROM complaint_test_runs WHERE data_scope_id = ?").use { statement ->
            statement.setObject(1, f.scope); statement.executeQuery().use { row ->
                assertTrue(row.next()); val array = row.getArray(1)
                try { ComplaintCapacityVector.of((array.array as Array<*>).map { (it as Number).toLong() }.toLongArray()) } finally { array.free() }
            }
        }
    }
    private fun preserved(f: TestRunPurgeFixtureV1): Map<String, List<String>> = f.p.image().filterKeys {
        it !in setOf("counters", "complaint_test_runs", "complaint_journal_control")
    } + f.raw { connection ->
        mapOf(
            "run_except_remainder" to "SELECT (to_jsonb(r) - 'unused_reserve')::text FROM complaint_test_runs r WHERE data_scope_id = ?",
            "control_except_lease" to "SELECT (to_jsonb(r) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text FROM complaint_journal_control r WHERE data_scope_id = ?",
            "other_terminal_intents" to "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM complaint_test_terminal_intents r WHERE data_scope_id = ? AND object_kind <> 'TEST_RUN_PURGE' ORDER BY object_kind, object_ordinal",
            "installation_ids" to "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM complaint_installation_ids r WHERE data_scope_id = ? ORDER BY id",
            "credentials" to "SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM app_installations r WHERE data_scope_id = ? ORDER BY id",
        ).mapValues { (_, sql) ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, f.scope); statement.executeQuery().use { row -> buildList { while (row.next()) add(row.getString(1)) } }
            }
        }
    }
    private fun hex(value: String) = HexFormat.of().parseHex(value)
    // Independent fixed22 literals. The already-paid 1,068,608-byte run Delta is NOT charged again.
    private val ACTUAL = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, 1)
        .with(ComplaintCapacityCounter.RECOVERY_RESERVATIONS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 1_619_264)
    private val AUDIT = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.AUDIT_ROWS, 1).with(ComplaintCapacityCounter.STORAGE_BYTES, 65_536)
}
