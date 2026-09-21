package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalFixtureV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogQueueHistoryV1
import me.manga.kira.backend.complaint.catalog.withActiveHistoryTerminalCatalogRun
import me.manga.kira.backend.complaint.catalog.withNonemptyActiveHistoryTerminalCatalogRun
import me.manga.kira.backend.complaint.catalog.withTerminalCatalogRun
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalResultV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureRequestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.SdkHttpClient
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Source-authored only. Genuine producers over synthetic raw HTTP; NOT_COMPILED / NOT_RUN. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRunErasureIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRunErasureIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun genuineNonemptyRegisteredADeletionThroughDAndEPurgesOnlyItsScopeAndRefundsExactRows() = withFixture { tls ->
        withNonemptyActiveHistoryTerminalCatalogRun(tls) { active ->
            TestRunErasureFixtureV1(active.catalog).use { h -> h.successful(active = true, queue = false, nonempty = true) }
        }
    }

    @Test fun genuineSettledBNonemptyHistoryPurgesWithoutDeletingOrRefundingItsPermanent8192Observation() = withFixture { tls ->
        withNonemptyActiveHistoryTerminalCatalogRun(tls, TerminalCatalogQueueHistoryV1.SETTLED) { active ->
            TestRunErasureFixtureV1(active.catalog).use { h -> h.successful(active = true, queue = true, nonempty = true) }
        }
    }

    @Test fun genuineRegisteredCreateWithEmptyOrdinaryHistoryPurgesContentAndRetainsManifestIdentity() = withFixture { tls ->
        withActiveHistoryTerminalCatalogRun(tls) { active ->
            TestRunErasureFixtureV1(active.catalog).use { h ->
                assertTrue(h.count("complaints", "kind <> 'NOTICE'") > 0)
                h.successful(active = true, queue = false, nonempty = false)
            }
        }
    }

    @Test fun genuineUnusedTwoSealNoANoBHistoryPurgesWithoutInventedRowsOrDispositionAudit() = withFixture { tls ->
        withTerminalCatalogRun(tls) { f -> TestRunErasureFixtureV1(f).use { h ->
            assertEquals(0L, h.count("complaint_installation_ids")); h.successful(active = false, queue = false, nonempty = false)
        } }
    }

    @Test fun genuineEnrolledTwoSealHistoryPurgesAndRecordsExactlyOneInstallationDisposition() = withFixture { tls ->
        withTerminalCatalogRun(tls, enrolled = true) { f -> TestRunErasureFixtureV1(f).use { h ->
            assertEquals(1L, h.count("complaint_installation_ids")); h.successful(active = false, queue = false, nonempty = false)
        } }
    }

    @Test fun unstartedAndPreparedOnlyEHaveNoErasureChildOrAdditionalSqlNativeAndCapacityEffects() = withFixture { tls ->
        withTerminalCatalogRun(tls) { f -> TestRunErasureFixtureV1(f).use { h ->
            val e = f.begin(); val before = h.image(); val native = h.nativeImage()
            assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
            assertEquals(before, h.image()); assertEquals(native, h.nativeImage()); assertTrue(h.jdbc.calls.isEmpty())
            assertEquals(CatalogTestRunTerminalResultV1.EXACT_PREPARED_AWAITING_DUAL_COPY, e.publish(f.unsigned, f.request()))
            e.requireActualCleanup(); val prepared = h.image(); val afterNative = h.nativeImage()
            assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
            assertEquals(prepared, h.image()); assertEquals(afterNative, h.nativeImage()); assertTrue(h.jdbc.calls.isEmpty())
        } }
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}

/** One retained deletion admission/root/template per fixture, never a new admission per erase call. */
internal class TestRunErasureFixtureV1(
    val catalog: CatalogTestRunTerminalFixtureV1,
    val runtime: VersionBoundPersistenceConnectedFixture = catalog.f.runtime,
    private val recoveryProcess: VersionBoundTestNamespaceProcessV1? = null,
) : AutoCloseable {
    val admission = DeletionPersistenceAdmission()
    val ownership = PersistencePhaseOwnership.deletion(admission, GuardedJdbcTransactionManager(runtime.pools.deletion))
    val jdbc = TestRunErasureSqlProbeV1(this)
    val observer = catalog.observer
    val scope = catalog.scope
    private val freshProcess by lazy { recoveryProcess ?: catalog.evidence.processOn(runtime.pools) }
    private val originals = arrayListOf<TestRunErasureV1>()
    private val emptyRequests = arrayListOf<JournalPublisherHttpRequest>()
    private var emptyCreated = 0
    private var emptyClosed = 0
    private val oldRead = catalog.http.beforeRead
    private val oldReadClose = catalog.http.afterReadClientClose
    private val oldBoundary = catalog.f.sealHttp.boundary
    private val oldNative = catalog.f.sealHttp.nativeBoundary

    init {
        requireConnectionFree()
        assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
        catalog.http.beforeRead = { oldRead(); assertReleased(false) }
        catalog.http.afterReadClientClose = { oldReadClose(); assertReleased(false) }
        catalog.f.sealHttp.boundary = { oldBoundary(); assertReleased(false) }
        catalog.f.sealHttp.nativeBoundary = { oldNative(); assertReleased(false) }
    }

    fun projectE(): CatalogTestRunTerminalV1 {
        catalog.http.replicateOnPut = true
        return catalog.begin().also { e ->
            assertEquals(CatalogTestRunTerminalResultV1.DUAL_COPY_ACCEPTED_AND_PURGING_PROJECTED, e.publish(catalog.unsigned, catalog.request()))
            e.requireActualCleanup(); catalog.probe.assertReleased(); assertEquals("PURGING", state())
        }
    }

    fun child(e: CatalogTestRunTerminalV1): TestRunErasureV1 = configure(e.beginErasure(ownership, jdbc))
    fun fresh(): TestRunErasureV1 = configure(TestRunErasureV1.beginFresh(freshProcess, ownership, jdbc, catalog.limit))
    private fun configure(value: TestRunErasureV1): TestRunErasureV1 {
        assertTrue(originals.isEmpty(), "A fixture keeps one exact original, not a replayable root/result shortcut.")
        originals.add(value); jdbc.original = value
        return value.withHttpFixtures(catalog.http::readClient, ::ordinaryClient, catalog.ordinaryKeys::httpClient, Clock.systemUTC())
    }

    fun request(ordinaryRaw: List<ByteArray>? = null, terminalRaw: List<ByteArray>? = null): TestRunErasureRequestV1 {
        val raw = catalog.request()
        return TestRunErasureRequestV1(raw.ordinaryApproval, ordinaryRaw ?: raw.ordinaryRawEvidence, raw.terminalApproval,
            terminalRaw ?: raw.terminalRawEvidence, raw.ordinaryReadCredentials, raw.primaryReadCredentials, raw.replicaReadCredentials)
    }

    fun successful(active: Boolean, queue: Boolean, nonempty: Boolean) {
        val e = projectE()
        assertTrue(observer.queryForObject("SELECT recurrent_erasure_history_hash IS NULL FROM complaint_test_runs WHERE data_scope_id = ?",
            Boolean::class.java, scope) == true, "N0 and unarchived N1 acquire no invented recurrent erasure commitment.")
        assertEquals(if (active) 1L else 0L, count("complaint_test_active_seal_intents"))
        assertEquals(if (queue) 1L else 0L, count("complaint_test_active_queue_observations"))
        assertEquals(if (nonempty) 1L else 0L, count("complaint_journal_publications", "event_kind = 'OWNER_DELETE'"))
        assertEquals(if (nonempty) 1L else 0L, count("complaint_deletion_journal_applied"))
        if (queue) assertEquals(8192L, observer.queryForObject("SELECT storage_bytes FROM complaint_test_active_queue_observations WHERE data_scope_id = ?", Long::class.java, scope))
        val before = baseline(); val untouched = image(); val native = nativeImage()
        assertEquals(nonempty, !before.ordinaryPreviouslyReleased.isZero())
        val original = child(e) // Claims the actual retained successful E; construction performs no I/O.
        assertEquals(untouched, image()); assertEquals(native, nativeImage()); assertTrue(jdbc.calls.isEmpty())
        assertThrows<RuntimeException> { e.beginErasure(ownership, jdbc) }
        assertEquals(TestRunErasureResultV1.PURGED, original.erase(request()))
        assertReleased(); jdbc.assertOrder(); assertPurged(before)
        val ended = image(); val observedNative = nativeImage()
        assertThrows<RuntimeException> { original.erase(request()) }
        assertThrows<RuntimeException> { e.beginErasure(ownership, jdbc) }
        assertEquals(ended, image()); assertEquals(observedNative, nativeImage())
        assertEquals(native.signs, observedNative.signs); assertEquals(native.puts, observedNative.puts)
        assertEquals(native.journalWrites, observedNative.journalWrites)
        assertEquals(2 * catalog.d.targets.size, observedNative.terminalGets - native.terminalGets)
        assertEquals(if (nonempty) 2 else 0, observedNative.ordinaryGets - native.ordinaryGets)
    }

    fun state(): String? = observer.query("SELECT state FROM complaint_test_runs WHERE data_scope_id = ?", { row, _ -> row.getString(1) }, scope).singleOrNull()
    fun count(table: String, extra: String = "TRUE"): Long {
        require(table in TABLES)
        require(extra in setOf("TRUE", "kind <> 'NOTICE'", "kind = 'NOTICE'", "event_kind = 'OWNER_DELETE'", "state IN ('ACTIVE','RECOVERY_RESERVED')"))
        return checkNotNull(observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ? AND $extra", Long::class.java, scope))
    }
    fun rows(table: String): List<String> {
        require(table in TABLES)
        return observer.queryForList("SELECT encode(sha256(convert_to(jsonb_build_array(to_jsonb(t), t.xmin::text)::text,'UTF8')),'hex') " +
            "FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, scope)
    }
    fun image(): Map<String, List<String>> = TABLES.associateWith(::rows) + mapOf(
        "audit" to auditRows(), "counters" to counters().values.map { it.full }, "outside" to outsideRows())

    /** One comparison query on an actual FINAL holder, avoiding many observer round trips inside its bound. */
    fun allPhysicalRows(jdbc: JdbcTemplate = observer): List<String> = jdbc.queryForList(
        (TABLES + listOf("audit_log", "complaint_capacity_counters", "users", "admin_step_up_grants")).joinToString(" UNION ALL ") { table ->
            "SELECT '$table:' || encode(sha256(convert_to(jsonb_build_array(to_jsonb(t),t.xmin::text)::text,'UTF8')),'hex') AS physical FROM $table t"
        } + " ORDER BY physical", String::class.java)

    private fun outsideRows(): List<String> = TABLES.flatMap { table -> observer.queryForList(
        "SELECT encode(sha256(convert_to(jsonb_build_array(to_jsonb(t), t.xmin::text)::text,'UTF8')),'hex') FROM $table t " +
            "WHERE data_scope_id IS DISTINCT FROM ? ORDER BY to_jsonb(t)::text", String::class.java, scope) } +
        listOf("users", "admin_step_up_grants").flatMap { table -> observer.queryForList(
            "SELECT encode(sha256(convert_to(jsonb_build_array(to_jsonb(t), t.xmin::text)::text,'UTF8')),'hex') FROM $table t ORDER BY to_jsonb(t)::text", String::class.java) } +
        observer.queryForList("SELECT encode(sha256(convert_to(jsonb_build_array(to_jsonb(t),t.xmin::text)::text,'UTF8')),'hex') FROM audit_log t " +
            "WHERE complaint_data_scope_id IS DISTINCT FROM ? ORDER BY id", String::class.java, scope)
    private fun auditRows(): List<String> = observer.queryForList(
        "SELECT encode(sha256(convert_to(jsonb_build_array(to_jsonb(t),t.xmin::text)::text,'UTF8')),'hex') FROM audit_log t WHERE complaint_data_scope_id = ? ORDER BY id",
        String::class.java, scope)
    private fun immutableRun(): String = checkNotNull(observer.queryForObject(
        "SELECT encode(sha256(convert_to((to_jsonb(r)-ARRAY['state','unused_reserve','purged_at'])::text,'UTF8')),'hex') FROM complaint_test_runs r WHERE data_scope_id = ?",
        String::class.java, scope))
    private fun immutableIds(): List<String> = observer.queryForList(
        "SELECT encode(sha256(convert_to((to_jsonb(i)-ARRAY['state','terminal_at'])::text,'UTF8')),'hex') FROM complaint_installation_ids i WHERE data_scope_id = ? ORDER BY id",
        String::class.java, scope)
    fun unused(): ComplaintCapacityVector = observer.query("SELECT unused_reserve FROM complaint_test_runs WHERE data_scope_id = ?",
        { row, _ -> erasureTestVector(row, "unused_reserve") }, scope).single()
    fun counters(): Map<String, TestRunErasureCounterV1> = observer.query(
        "SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units, " +
            "encode(sha256(convert_to(jsonb_build_array(to_jsonb(c),c.xmin::text)::text,'UTF8')),'hex') AS full_hash, " +
            "encode(sha256(convert_to((to_jsonb(c)-ARRAY['free_units','actual_units','recovery_reserved_units','test_reserved_units','updated_at'])::text,'UTF8')),'hex') AS metadata " +
            "FROM complaint_capacity_counters c ORDER BY ordinal", { row, _ -> row.getString("name") to TestRunErasureCounterV1(
                row.getLong("free_units"), row.getLong("actual_units"), row.getLong("recovery_reserved_units"), row.getLong("test_reserved_units"),
                row.getString("metadata"), row.getString("full_hash")) }).toMap()

    /** Independent literal V14/V21/V26/V31 logical prices; no call to the eraser's count/price implementation. */
    fun removablePrice(): ComplaintCapacityVector {
        var total = ComplaintCapacityVector.ZERO
        fun add(table: String, counter: ComplaintCapacityCounter, bytes: Long, extra: String = "TRUE") {
            val n = count(table, extra)
            total += ComplaintCapacityVector.units(counter, n) + ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, n * bytes)
        }
        add("app_installations", ComplaintCapacityCounter.APP_INSTALLATIONS, 16384)
        add("complaint_resource_ids", ComplaintCapacityCounter.RESOURCE_IDS, 16384)
        add("complaints", ComplaintCapacityCounter.COMPLAINT_ROWS, 262144, "kind <> 'NOTICE'")
        add("complaints", ComplaintCapacityCounter.COMPLAINT_ROWS, 16384, "kind = 'NOTICE'")
        add("complaint_idempotency_receipts", ComplaintCapacityCounter.NORMAL_RECEIPTS, 131072)
        add("installation_deletion_receipts", ComplaintCapacityCounter.INSTALLATION_RECEIPTS, 32768)
        add("complaint_deletion_journal_applied", ComplaintCapacityCounter.JOURNAL_APPLIED, 32768)
        add("complaint_journal_publications", ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, 262144)
        add("complaint_recovery_capacity_reservations", ComplaintCapacityCounter.RECOVERY_RESERVATIONS, 16384)
        add("complaint_journal_control", ComplaintCapacityCounter.JOURNAL_CONTROL, 1599808)
        return total + ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES,
            count("complaint_test_terminal_intents") * 1340736L +
                (count("complaint_test_active_seal_intents") + count("complaint_test_active_recurrent_seal_intents") +
                    count("complaint_test_active_checkpoint_history")) * 2097152L)
    }
    private fun ordinaryPreviouslyReleased(): ComplaintCapacityVector = observer.query(
        "SELECT l.reserved_amounts, l.converted_amounts FROM complaint_recovery_capacity_reservations l " +
            "JOIN complaint_journal_publications p ON p.event_id = l.publication_ref WHERE l.data_scope_id = ? AND p.state = 'APPLIED' AND l.state = 'CONVERTED'",
        { row, _ -> erasureTestVector(row, "reserved_amounts") - erasureTestVector(row, "converted_amounts") }, scope)
        .fold(ComplaintCapacityVector.ZERO, ComplaintCapacityVector::plus)

    fun baseline() = TestRunErasureBaselineV1(counters(), removablePrice(), ordinaryPreviouslyReleased(), unused(),
        count("complaint_installation_ids", "state IN ('ACTIVE','RECOVERY_RESERVED')"), count("complaint_installation_ids"),
        immutableRun(), immutableIds(), auditRows(), rows("complaint_test_active_queue_observations"), rows("complaint_catalog_mutations"), outsideRows(), catalog.files())

    fun assertPurged(before: TestRunErasureBaselineV1) {
        assertEquals("PURGED", state()); assertEquals(ComplaintCapacityVector.ZERO, unused()); assertEquals(ComplaintCapacityVector.ZERO, removablePrice())
        assertEquals(before.identities, count("complaint_installation_ids")); assertEquals(before.run, immutableRun()); assertEquals(before.ids, immutableIds())
        assertEquals(before.queue, rows("complaint_test_active_queue_observations")); assertEquals(before.catalogs, rows("complaint_catalog_mutations"))
        assertEquals(before.outside, outsideRows()); assertEquals(before.files, catalog.files())
        val audits = auditRows(); assertEquals(before.audits.size + before.transitions.toInt() + 1, audits.size); assertTrue(audits.containsAll(before.audits))
        assertTrue(observer.queryForObject("SELECT count(*) = 1 AND bool_and(a.actor_user_id IS NULL AND a.complaint_actor_kind = 'SYSTEM' " +
            "AND a.entity_type = 'complaint_scope' AND a.entity_id = r.data_scope_id::text AND a.created_at = r.purged_at " +
            "AND a.detail = jsonb_build_object('erasure','TEST_RUN_ERASURE_V1','operationToken',?::text,'generation',r.terminal_catalog_generation,'transition','PURGED')) " +
            "FROM complaint_test_runs r JOIN audit_log a ON a.complaint_data_scope_id = r.data_scope_id AND a.action = 'COMPLAINT_TEST_RUN_PURGED' " +
            "WHERE r.data_scope_id = ?", Boolean::class.java, catalog.token.toString(), scope) == true)
        val use = ERASURE_AUDIT.scaled(before.transitions + 1)
        // Genuine drain CONVERT already released ordinary P-U. Retained CONVERTED L columns
        // describe historical amounts, never a second release entitlement. Purge FUTURE is AUDIT only.
        val recoveryRelease = ERASURE_AUDIT
        val after = counters(); assertEquals(22, after.size)
        ComplaintCapacityCounter.entries.forEach { counter ->
            val a = before.counters.getValue(counter.storedName); val b = after.getValue(counter.storedName)
            assertEquals(a.metadata, b.metadata, counter.storedName)
            assertEquals(a.actual - before.removable[counter] + use[counter], b.actual, counter.storedName)
            assertEquals(a.recovery - recoveryRelease[counter], b.recovery, counter.storedName)
            assertEquals(a.reserved - before.unused[counter], b.reserved, counter.storedName)
            assertEquals(a.free + before.removable[counter] + recoveryRelease[counter] + before.unused[counter] - use[counter], b.free, counter.storedName)
            if (a.free == b.free && a.actual == b.actual && a.recovery == b.recovery && a.reserved == b.reserved) assertEquals(a.full, b.full, counter.storedName)
        }
    }

    fun nativeImage() = TestRunErasureNativeImageV1(catalog.signing.requests.size, catalog.http.bodies.size,
        catalog.http.read.requests.size, catalog.f.sealHttp.terminalInventoryRequests.count { it.kind == "GET" },
        catalog.ordinaryRequests.count { it.kind == "GET" }, catalog.f.sealHttp.order.count { it == "GENERATE" || it == "PUT" })

    fun assertReleased(committed: Boolean = true) {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current()); assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, admission.activeOwners().totalOwners); jdbc.assertReleased(committed)
    }

    private fun ordinaryClient(): SdkHttpClient {
        assertReleased(false)
        catalog.originalOrdinary?.let { return it.client() }
        emptyCreated++
        return journalPublisherRawHttpClient(emptyRequests, { assertReleased(false) }, {}, { emptyClosed++; assertReleased(false) }) { request ->
            val journal = catalog.process.consumers.journalConfiguration; val location = journal.declaration().journalLocation
            journalPublisherRawAssertSigned(request, location.region, location.accountId, AwsJournalKmsFixture.CREDENTIALS)
            assertEquals("LIST", request.kind); assertTrue(request.body.isEmpty())
            assertEquals(listOf(journal.ordinaryPrefix), request.http.rawQueryParameters()["prefix"])
            assertEquals(listOf("2"), request.http.rawQueryParameters()["max-keys"])
            assertTrue(request.http.rawQueryParameters().keys.none { it in setOf("key-marker", "version-id-marker") })
            OwnerDeleteAllJournalPublisherFixture.xmlReply(journalPublisherRawListDocument(location.bucket, journal.ordinaryPrefix, emptyList()))
        }
    }

    override fun close() {
        jdbc.before = {}; jdbc.after = {}
        try {
            originals.forEach { runCatching(it::close) } // Negative originals stay failed; only physical release is asserted here.
            assertReleased(false); assertEquals(emptyCreated, emptyClosed)
            emptyRequests.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(1, checkNotNull(it.reply).closes) }
        } finally {
            catalog.http.beforeRead = oldRead; catalog.http.afterReadClientClose = oldReadClose
            catalog.f.sealHttp.boundary = oldBoundary; catalog.f.sealHttp.nativeBoundary = oldNative
        }
    }

    companion object {
        val ERASURE_AUDIT = ComplaintCapacityVector.units(ComplaintCapacityCounter.AUDIT_ROWS, 1) + ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, 65536)
        private val TABLES = listOf("complaint_test_runs", "complaint_journal_control", "complaint_catalog_mutations", "complaint_installation_ids",
            "app_installations", "complaint_resource_ids", "complaints", "complaint_idempotency_receipts", "installation_deletion_receipts",
            "complaint_deletion_journal_applied", "complaint_recovery_capacity_reservations", "complaint_journal_publications",
            "complaint_test_terminal_intents", "complaint_test_active_seal_intents", "complaint_test_active_queue_observations",
            "complaint_test_active_recurrent_seal_intents", "complaint_test_active_checkpoint_history",
            "complaint_journal_scan_runs", "complaint_journal_scan_entries", "complaint_deletion_journal_retirements")
    }
}

internal data class TestRunErasureCounterV1(val free: Long, val actual: Long, val recovery: Long, val reserved: Long, val metadata: String, val full: String)
internal data class TestRunErasureNativeImageV1(val signs: Int, val puts: Int, val catalogRequests: Int, val terminalGets: Int, val ordinaryGets: Int, val journalWrites: Int)
internal data class TestRunErasureBaselineV1(val counters: Map<String, TestRunErasureCounterV1>, val removable: ComplaintCapacityVector,
    val ordinaryPreviouslyReleased: ComplaintCapacityVector, val unused: ComplaintCapacityVector, val transitions: Long, val identities: Long,
    val run: String, val ids: List<String>, val audits: List<String>, val queue: List<String>, val catalogs: List<String>, val outside: List<String>, val files: Map<String, String>)
internal fun erasureTestVector(row: ResultSet, name: String): ComplaintCapacityVector {
    val array = checkNotNull(row.getArray(name))
    return try { ComplaintCapacityVector.of((array.array as Array<*>).map { (it as Number).toLong() }.toLongArray()) } finally { array.free() }
}

/** SQL observer only: calls the real JDBC implementation and never substitutes rows/outcomes. */
internal class TestRunErasureSqlProbeV1(private val f: TestRunErasureFixtureV1) : JdbcTemplate(f.runtime.pools.deletion) {
    lateinit var original: TestRunErasureV1
    var before: (Call) -> Unit = {}
    var after: (Call) -> Unit = {}
    val calls = arrayListOf<Call>()
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    private val failure = AtomicReference<AssertionError?>()
    private var dispatching = false
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = once(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = once(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>): T? = once(sql, emptyArray()) { super.query(sql, extractor) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = once(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = once(sql, args) { super.update(sql, *args) }
    private fun <T> once(sql: String, args: Array<out Any?>, action: () -> T): T {
        if (dispatching) return action()
        dispatching = true
        return try {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val path = ownedCutField(phase, "path") as PersistencePhasePath
            assertTrue(path.testRunErasure); assertSame(original, ownedCutField(phase, "testRunErasure"))
            assertNull(ownedCutField(phase, "testRunTerminalCatalog")); assertEquals(sql.count { it == '?' }, args.size)
            assertEquals(1, f.admission.activeOwners().routineOwners); assertEquals(0, f.admission.activeOwners().privacyOwners)
            val source = f.runtime.pools.deletion
            assertEquals(setOf(source), TransactionSynchronizationManager.getResourceMap().keys)
            val connection = (TransactionSynchronizationManager.getResource(source) as ConnectionHolder).connection
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(f.catalog.f.p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertTrue(f.catalog.f.p.advisory(connection, "complaint-journal-epoch", "ShareLock"))
            assertFalse(f.catalog.f.p.advisory(connection, "complaint-maintenance-v1", "ExclusiveLock"))
            assertFalse(f.catalog.f.p.advisory(connection, "complaint-journal-epoch", "ExclusiveLock"))
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) {
                val identity = connection.createStatement().use { statement -> statement.executeQuery(
                    "SELECT pg_backend_pid(),txid_current(),current_setting('statement_timeout'),current_setting('transaction_timeout')").use { row ->
                    assertTrue(row.next()); assertTrue(timeout(row.getString(3)) in 1..2000); assertTrue(timeout(row.getString(4)) in 1..2000)
                    row.getInt(1) to row.getLong(2)
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(observed.lease, lease); assertFalse(lease.completion.quiescent())
            val call = Call(phase, path, sql, args.map { if (it is ByteArray) Sha256.hex(it) else it })
            calls.add(call); before(call); action().also { after(call) }
        } catch (problem: AssertionError) { failure.compareAndSet(null, problem); throw problem }
        finally { dispatching = false }
    }
    fun assertReleased(committed: Boolean) {
        failure.get()?.let { throw it }
        observations.forEach { (phase, observed) ->
            assertTrue(observed.lease.completion.quiescent()); assertTrue(phase.testRunErasureResourcesRetired(original))
            if (committed) { assertTrue(phase.testRunErasureCleanupProven(original)); assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome()) }
        }
    }
    fun assertOrder() {
        calls.groupBy { it.phase }.values.forEach { list ->
            val sql = list.map { it.sql }; val controls = sql.withIndex().filter { it.value == TestRunErasureSqlV1.lockControl }.map { it.index }
            assertEquals(2, controls.size)
            val run = sql.indexOf(TestRunErasureSqlV1.lockRun)
            val counter = sql.indexOfFirst { it.contains("complaint_capacity_counters") && it.contains("FOR UPDATE") }
            assertTrue(counter > controls.last()); assertTrue(run > counter)
            val primary = setOf(TestRunErasureSqlV1.lockReceipt, TestRunErasureSqlV1.lockDeletionReceipt,
                TestRunErasureSqlV1.lockPublication, TestRunErasureSqlV1.lockRecovery, TestRunErasureSqlV1.lockApplied)
            sql.withIndex().filter { it.value in primary }.forEach { assertTrue(it.index > controls.last() && it.index < counter) }
            val lower = setOf(TestRunErasureSqlV1.lockInstallation, TestRunErasureSqlV1.lockCredential, TestRunErasureSqlV1.lockResource, TestRunErasureSqlV1.lockContent)
            sql.withIndex().filter { it.value in lower }.forEach { assertTrue(it.index > run) }
            val mutable = sql.any { it == TestRunErasureSqlV1.acquireLease }
            if (mutable) assertTrue(sql.indexOf(TestRunErasureSqlV1.acquireLease) > run)
            if (list.first().path === PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL) {
                assertTrue(sql.indexOf(TestRunErasureSqlV1.deleteSidecar) < sql.indexOf(TestRunErasureSqlV1.deleteRecovery))
                assertTrue(sql.indexOf(TestRunErasureSqlV1.deleteControl) > sql.indexOfLast { it == TestRunErasureSqlV1.deletePublication })
                assertTrue(sql.indexOf(TestRunErasureSqlV1.purgeRun) > sql.indexOf(TestRunErasureSqlV1.deleteControl))
            }
        }
    }
    class Call(val phase: PersistencePhaseContext, val path: PersistencePhasePath, val sql: String, val arguments: List<Any?>)
    companion object {
        private fun timeout(value: String) = when {
            value.endsWith("ms") -> value.removeSuffix("ms").toLong()
            value.endsWith("s") -> value.removeSuffix("s").toLong() * 1000
            else -> value.toLong()
        }
    }
}
