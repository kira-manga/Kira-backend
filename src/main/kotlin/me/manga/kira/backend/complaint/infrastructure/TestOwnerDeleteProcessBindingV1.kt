package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutIdentityV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointRowsV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointSqlV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestInitialCheckpointCurrentCodecV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionSqlV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredRecurrentCheckpointDeletionCurrentV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeletePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteReadPhaseExecutor
import me.manga.kira.backend.security.ComplaintAdmittedAdminErasure
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDelete
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDeleteAll
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import java.time.Instant
import java.util.HexFormat
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import org.springframework.jdbc.core.JdbcTemplate

/** Sole registered initial-origin request issuer. It retains resources, never cached current eligibility. */
internal class TestOwnerDeleteProcessBindingV1 private constructor(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val assembly: ComplaintTestProcessAssemblyV1,
    private val ordinaryOwner: PersistencePhaseOwnership,
    private val ordinary: JdbcTemplate,
    private val deletionOwner: PersistencePhaseOwnership,
    private val deletion: JdbcTemplate,
) {
    private val process = registration.process
    internal val policy = checkNotNull(process.initialCheckpointDeletion)
    private val checkpoint = checkNotNull(process.initialCheckpoint)
    private val publication = checkNotNull(process.activeCutoffPublication)
    private val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    private val journal = process.consumers.journalConfiguration
    private val recurrent = if (policy.recurrentCurrent) TestRegisteredRecurrentCheckpointDeletionCurrentV1(registration, deletion) else null
    private var retainedGraph: TestOwnerDeleteLocalGraphV1? = null
    val lower = TestOwnerDeleteLocalGraphV1(ordinary, deletion, process.consumers.ingressAdmission,
        process.consumers.journalRouting, process.consumers.capacityPolicy, process.publicationLanes,
        process.desiredGeneration, initialDeletion = this)
    private val run = TestTerminalRunContextV1(identity.scope.toString(), identity.generation, hex(identity.activationCatalogHash()),
        hex(identity.configurationHash()), identity.sealEncodingSha256)
    private val manifest = MessageDigest.getInstance("SHA-256").let { digest ->
        val bytes = EpochSealFramesV1.update(digest, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", identity.writer.toString(),
            journal.ordinaryPrefix, "TEST", identity.scope.toString(), "1", "1", "0"))
        check(bytes in 1..journal.declaration().limits.capacity.maximumScanStagingBytes)
        HexFormat.of().formatHex(digest.digest()) to bytes
    }

    /** Called once by this binding's own lower construction; a borrowed binding cannot mint another graph. */
    internal fun claimGraph(graph: TestOwnerDeleteLocalGraphV1, ordinary: JdbcTemplate, deletion: JdbcTemplate,
        ingress: ComplaintIngressAdmission, routing: TestOwnerDeleteJournalRoutingV1, policy: ComplaintCapacityPolicyV1,
        lanes: JournalPublicationLanesV1, generation: Long): ComplaintInstallationDesiredSettings.Configured {
        check(retainedGraph == null && ordinary === this.ordinary && deletion === this.deletion &&
            ingress === process.consumers.ingressAdmission && routing === process.consumers.journalRouting &&
            policy === process.consumers.capacityPolicy && lanes === process.publicationLanes && generation == process.desiredGeneration)
        retainedGraph = graph
        return process.desiredSettings()
    }

    internal fun requireGraph(graph: TestOwnerDeleteLocalGraphV1) {
        check(graph === retainedGraph && assembly.target === process && process.initialCheckpointDeletion === policy)
        registration.requireInitialMutationAdmission()
        registration.requireActiveIdentityTarget(assembly)
        policy.requireRetained(process.pools, process.consumers.journalRouting, checkpoint, publication, process.activeRecurrent)
        publication.requireRetained(process.consumers.journalRouting, process.publicationLanes)
    }

    internal fun requirePhaseOwner(selected: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireGraph(lower)
        when (path) {
            in ORDINARY_PATHS -> {
                check(selected === ordinaryOwner)
                registration.requireIdentityAdmissionPhaseResources(selected, ordinary)
                process.pools.ordinary.requireTestInitialCheckpointDeletion(policy)
            }
            in DELETION_PATHS -> {
                check(selected === deletionOwner)
                registration.requireInitialDeletionPhaseResources(selected, deletion)
                process.pools.deletion.requireTestInitialCheckpointDeletion(policy)
            }
            else -> error("Initial deletion path refused") // No direct registered request APPLY.
        }
    }

    internal fun requireEntry(selected: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree(); requirePhaseOwner(selected, path)
    }
    internal fun requireReadAdmission() = ComplaintIngressAdmission.requireInitialDeletionReadOwner(process.consumers.ingressAdmission)
    internal fun requireAdmission(handoff: ComplaintAdmittedOwnerDelete) =
        ComplaintIngressAdmission.requireOwnerDeleteOwner(handoff, process.consumers.ingressAdmission)
    internal fun requireAdmission(handoff: ComplaintAdmittedOwnerDeleteAll) =
        ComplaintIngressAdmission.requireOwnerDeleteAllOwner(handoff, process.consumers.ingressAdmission)
    internal fun requireAdmission(handoff: ComplaintAdmittedAdminErasure) =
        ComplaintIngressAdmission.requireAdminErasureOwner(handoff, process.consumers.ingressAdmission)

    /** Exact cold recipe only. No lower .ordinary() credential or replacement factory enters this branch. */
    internal fun requirePublicationRecipe(selected: VersionBoundTestActiveCutoffPublicationV1) {
        requireGraph(lower); check(selected === publication)
    }
    internal fun ownerPublisher(store: JdbcComplaintOwnerDeleteStore) = publication.ownerPublisher(this, store)
    internal fun allPublisher(store: JdbcComplaintOwnerDeleteAllStore) = publication.allPublisher(this, store)
    internal fun adminPublisher(store: JdbcComplaintAdminDeleteStore) = publication.adminPublisher(this, store)

    internal fun ownerHttpResources(audit: AuditService): OwnerHttpResources = OwnerHttpResources(this, audit)

    /** One fixed original SQL graph. Its publisher stays separately retained by the startup before any listener. */
    internal class OwnerHttpResources(private val original: TestOwnerDeleteProcessBindingV1, audit: AuditService) {
        init {
            original.requireEntry(original.ordinaryOwner, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION)
            original.requireEntry(original.deletionOwner, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE)
        }
        private val graph = original.lower
        private val capacity = JdbcComplaintCapacityStore(original.deletion, graph.policy.digestBytes())
        private val store = JdbcComplaintOwnerDeleteStore(original.deletion, capacity, audit, graph,
            TestOwnerDeleteJournalCodecV1.forCanonicalization(graph.routing))
        private val reads = ComplaintOwnerDeleteReadPhaseExecutor(original.ordinaryOwner, JdbcComplaintOwnerDeleteReceiptStore(original.ordinary, graph))
        private val verification = JdbcComplaintOwnerDeleteVerificationStore(original.deletion, graph, store)
        private val phases = ComplaintOwnerDeletePhaseExecutor(original.deletionOwner, store, reads, verification,
            JdbcComplaintOwnerDeleteApplyStore(original.deletion, capacity, audit, graph, store, verification))

        internal fun publisher(): TestOwnerDeleteJournalPublisherFactoryV1 = original.ownerPublisher(store)

        internal fun adapter(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, jwt: InstallationJwtCodec,
            publisher: TestOwnerDeleteJournalPublisherFactoryV1): ComplaintOwnerDeleteAdapter {
            requireConnectionFree()
            check(registration === original.registration && assembly === original.assembly &&
                ownership === original.ordinaryOwner && jdbc === original.ordinary)
            original.requireEntry(ownership, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION)
            original.requireEntry(original.deletionOwner, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE)
            return ComplaintOwnerDeleteAdapter(graph, jwt, reads, phases, publisher)
        }
    }

    internal fun observationIdentityArguments(): Array<Any?> = identity.arguments()

    /** Early global→scope locks compare identity only. A receipt loser is never freshness-gated here. */
    internal fun lockControls(jdbc: JdbcTemplate): TestOwnerDeleteControlBindingV1.Locked {
        val phase = requireDeletionPhase(jdbc)
        phase.beginInitialDeletionControls(this)
        check(jdbc.query(TestActiveInitialCheckpointSqlV1.lockGlobal, { _, _ -> true }).single())
        check(jdbc.query(TestActiveInitialCheckpointSqlV1.lockScope, { _, _ -> true }, identity.scope).single())
        return jdbc.query(TestRegisteredInitialCheckpointDeletionSqlV1.controls, { row, _ ->
            check(row.getBoolean("matches") && !row.wasNull())
            val epoch = row.getLong("publication_epoch").also { check(!row.wasNull() && it > 0) }
            val sealed = row.getLong("seal_epoch").also { check(!row.wasNull() && it >= 0) }
            TestOwnerDeleteControlBindingV1.Locked(epoch, sealed)
        }, *identity.arguments()).single()
    }

    internal fun lockRun(jdbc: JdbcTemplate, authorizing: Boolean) {
        val phase = requireDeletionPhase(jdbc)
        phase.requireInitialDeletionControls(this)
        check(jdbc.query(TestActiveInitialCheckpointSqlV1.lockRun, { _, _ -> true }, identity.scope).single())
        jdbc.query("SELECT state, test_only, configuration_hash, purging_at, purged_at FROM complaint_test_runs WHERE data_scope_id = ?::uuid", { row, _ ->
            check(row.getBoolean("test_only") && !row.wasNull() && row.getBytes("configuration_hash").contentEquals(identity.configurationHash()))
            check(row.getString("state") in if (authorizing) setOf("ACTIVE") else setOf("ACTIVE", "SEALED"))
            check(row.getTimestamp("purging_at") == null && row.getTimestamp("purged_at") == null)
        }, identity.scope).single()
    }

    internal fun checkCurrent(operation: ComplaintOwnerDeleteAuthorizationOperation): Instant =
        checkCurrent(operation.initialCheckpointArguments(this))
    internal fun checkCurrent(operation: ComplaintAdminDeleteAuthorizationOperation): Instant =
        checkCurrent(operation.initialCheckpointArguments(this))
    internal fun checkCurrent(operation: ComplaintOwnerDeleteAllOperation): Instant =
        checkCurrent(operation.initialCheckpointArguments(this))

    private fun requireDeletionPhase(jdbc: JdbcTemplate): PersistencePhaseContext {
        check(jdbc === deletion)
        val phase = checkNotNull(PersistencePhaseOwnership.current())
        phase.requireRegisteredInitialDeletion(lower, jdbc)
        return phase
    }

    /** No backward locks or new budgets: this holder already owns controls, and the operation proves its own claim/history. */
    private fun checkCurrent(owned: Array<Any?>): Instant {
        val phase = requireDeletionPhase(deletion)
        phase.requireInitialDeletionControls(this)
        registration.requireActiveDeletionGate(phase.initialDeletionGate(this))
        val args = identity.arguments().plus(elements = owned)
        try {
            if (recurrent?.selectInitial() == false) {
                val now = recurrent.readCurrent(owned)
                phase.requireRegisteredInitialDeletion(lower, deletion)
                return now
            }
            return deletion.query(TestRegisteredInitialCheckpointDeletionSqlV1.current, { row, _ -> TestActiveInitialCheckpointRowsV1.Current(row) }, *args).single().use { current ->
                check(current.leaseOwner == null && current.leaseExpiresAt == null && current.leaseToken > current.preparingToken)
                deletion.query(TestActiveInitialCheckpointSqlV1.slot, { row, _ ->
                    TestActiveInitialCheckpointRowsV1.intentComparisons(row, identity, run, journal.sha256, current)
                }, current.operationToken, identity.scope).single().use { frozen ->
                    deletion.query(TestActiveInitialCheckpointSqlV1.sealControl, { row, _ ->
                        TestActiveInitialCheckpointRowsV1.Control.read(row, frozen, current)
                    }, identity.scope).single().use { control ->
                        requireSeal(frozen, current, control)
                        val bytes = deletion.query(CHECKPOINT_BYTES, { row, _ -> checkNotNull(row.getBytes(1)) }, identity.scope).single()
                        try {
                            val document = TestInitialCheckpointCurrentCodecV1.checkpoint(bytes)
                            requireDocument(document, frozen, current, control)
                            val columns = TestActiveInitialCheckpointRowsV1.documentArguments(document, bytes, Sha256.hex(bytes))
                            try {
                                check(deletion.query(TestActiveInitialCheckpointSqlV1.completed, { row, _ ->
                                    TestActiveInitialCheckpointRowsV1.boolean(row, "valid")
                                }, *columns, current.leaseToken, identity.scope).single())
                            } finally { columns.forEach { if (it is ByteArray) it.fill(0) } }
                            val now = checkNotNull(deletion.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
                            check(TestActiveInitialCheckpointDocumentV1.time(now) && !now.isBefore(current.sampledAt) &&
                                !document.completedAt.isAfter(now) && !document.completedAt.plusMillis(journal.declaration().limits.deadlines.checkpointMaxAgeMillis.toLong()).isBefore(now) &&
                                control.retainUntil.isAfter(now.plusMillis(checkpoint.retention.retention.utcUncertainty.maximumMillis)))
                            phase.requireRegisteredInitialDeletion(lower, deletion)
                            now
                        } finally { bytes.fill(0) }
                    }
                }
            }
        } finally { args.forEach { if (it is ByteArray) it.fill(0) } }
    }

    private fun requireSeal(frozen: TestTerminalDurableRowV1, current: TestActiveInitialCheckpointRowsV1.Current,
        control: TestActiveInitialCheckpointRowsV1.Control) {
        val bytes = frozen.canonicalBytes()
        try {
            val seal = TestTerminalJsonV1(journal).epochSeal(bytes)
            check(seal.epochStartInclusive == 1L && seal.epochEndInclusive == 1L && seal.eventCount == 0L &&
                seal.eventManifestSha256 == manifest.first && seal.precedingSealSha256 == "" && seal.sealId == frozen.binding.objectId &&
                seal.preparingFencingToken == current.preparingToken && seal.preparingFencingToken < current.leaseToken)
        } finally { bytes.fill(0) }
        val proof = control.verificationBytes()
        try { TestInitialCheckpointCurrentCodecV1.requireSealVerification(proof, frozen, control, current.sampledAt, checkpoint.retention.retention) }
        finally { proof.fill(0) }
    }

    private fun requireDocument(value: TestActiveInitialCheckpointDocumentV1, frozen: TestTerminalDurableRowV1,
        current: TestActiveInitialCheckpointRowsV1.Current, control: TestActiveInitialCheckpointRowsV1.Control) {
        check(value.scope == identity.scope.toString() && value.desiredGeneration == identity.desiredGeneration && value.fencingToken == current.leaseToken &&
            value.configurationSha256 == run.configurationSha256 && value.journalConfigurationSha256 == journal.sha256 &&
            value.databaseIdentity == identity.databaseIdentity.toString() && value.restoreIdentity == identity.restoreIdentity.toString() &&
            value.catalogGeneration == identity.acceptedCatalogGeneration && value.catalogSha256 == hex(identity.acceptedCatalogHash()) &&
            value.trustBundleSha256 == hex(identity.trustBundleHash()) && value.catalogWriterGeneration == identity.catalogWriter.toString() &&
            value.writerGeneration == identity.writer.toString() && value.sealOperationToken == current.operationToken.toString() &&
            value.sealObjectKey == frozen.binding.objectKey && value.sealObjectVersion == control.version &&
            value.sealCanonicalSha256 == frozen.canonicalSha256 && value.sealCiphertextSha256 == frozen.wireSha256 &&
            value.manifestSha256 == manifest.first && value.manifestFramedBytes == manifest.second &&
            !value.startedAt.isBefore(control.verifiedAt) && !value.completedAt.isAfter(current.sampledAt))
    }

    override fun toString(): String = "TestOwnerDeleteProcessBindingV1(exact-registered-initial,no-cached-eligibility)"

    companion object {
        private val ORDINARY_PATHS = setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT, PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS,
            PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT, PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT)
        private val DELETION_PATHS = setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE, PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY)
        private const val CHECKPOINT_BYTES = "SELECT CASE WHEN complaint_bytes_match(checkpoint_bytes, checkpoint_hash, 65536) THEN checkpoint_bytes END " +
            "FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only"

        internal fun fromRegistered(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ordinaryOwner: PersistencePhaseOwnership, ordinary: JdbcTemplate, deletionOwner: PersistencePhaseOwnership,
            deletion: JdbcTemplate): TestOwnerDeleteProcessBindingV1 {
            requireConnectionFree(); registration.requireUsable(); registration.requireInitialMutationAdmission()
            registration.requireActiveIdentityTarget(assembly)
            checkNotNull(registration.process.initialCheckpointDeletion)
            registration.requireInstallationResources(ordinaryOwner, ordinary)
            registration.requireOwnerDeleteContinuationResources(deletionOwner, deletion)
            return TestOwnerDeleteProcessBindingV1(registration, assembly, ordinaryOwner, ordinary, deletionOwner, deletion).also {
                it.requireEntry(ordinaryOwner, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION)
                it.requireEntry(deletionOwner, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE)
            }
        }
        private fun hex(bytes: ByteArray): String = try { HexFormat.of().formatHex(bytes) } finally { bytes.fill(0) }
    }
}

/**
 * Default lower comparisons are not full D. Closed registered initial and recovery origins are
 * disjoint: only the former may authorize, on its exact retained owners/templates/cold policy.
 * No constructor accepts a supplied full-D hash or an observed row as registration authority.
 */
internal class TestOwnerDeleteLocalGraphV1(
    private val ordinary: JdbcTemplate,
    private val deletion: JdbcTemplate,
    val ingress: ComplaintIngressAdmission,
    val routing: TestOwnerDeleteJournalRoutingV1,
    val policy: ComplaintCapacityPolicyV1,
    val lanes: JournalPublicationLanesV1,
    desiredGeneration: Long = 1,
    internal val recoveryRegistration: ComplaintTestNamespaceRegistrationV1? = null,
    internal val initialDeletion: TestOwnerDeleteProcessBindingV1? = null,
) {
    private val ordinarySource = checkNotNull(ordinary.dataSource)
    private val deletionSource = checkNotNull(deletion.dataSource)
    private val journal = routing.journalConfiguration
    private val declaration = journal.declaration()
    private val deletionPolicy = initialDeletion?.policy ?: recoveryRegistration?.process?.initialCheckpointDeletion
    val writer: UUID = UUID.fromString(declaration.writer.generationId)
    private val desired: ComplaintInstallationDesiredSettings.Configured

    init {
        require(desiredGeneration > 0 && ordinarySource !== deletionSource && (recoveryRegistration == null || initialDeletion == null))
        lanes.retainTestJournal(journal)
        requirePoolPolicy()
        desired = if (initialDeletion != null) {
            initialDeletion.claimGraph(this, ordinary, deletion, ingress, routing, policy, lanes, desiredGeneration)
        } else if (recoveryRegistration == null) lowerSettings(desiredGeneration) else {
            recoveryRegistration.requireUsable()
            val process = recoveryRegistration.process
            check(ordinarySource === process.pools.ordinary && deletionSource === process.pools.deletion)
            check(ingress === process.consumers.ingressAdmission && routing === process.consumers.journalRouting &&
                policy === process.consumers.capacityPolicy && lanes === process.publicationLanes && desiredGeneration == process.desiredGeneration)
            process.desiredSettings() // Only the retained full root derives D; a supplied hash is never accepted.
        }
    }

    private fun lowerSettings(desiredGeneration: Long): ComplaintInstallationDesiredSettings.Configured {
        val fields = listOf(
            "kira-complaint-test-owner-delete-lower-comparison-v1".toByteArray(Charsets.UTF_8),
            ByteBuffer.allocate(8).putLong(desiredGeneration).array(), journal.canonicalBytes(), policy.digestBytes(),
        )
        val frame = ByteBuffer.allocate(fields.sumOf { 4 + it.size }).apply { fields.forEach { putInt(it.size).put(it) } }.array()
        val hash = MessageDigest.getInstance("SHA-256").digest(frame)
        frame.fill(0)
        fields.forEach { it.fill(0) }
        val result = ComplaintInstallationDesiredSettings.Configured(
            ComplaintInstallationMode.PRE_CUTOVER_TEST, 1, desiredGeneration, journal.scope,
            UUID.fromString(declaration.writer.databaseIdentity), UUID.fromString(declaration.writer.restoreIdentity), hash,
        )
        hash.fill(0)
        return result
    }

    fun desiredSettings(): ComplaintInstallationDesiredSettings.Configured {
        requireUnchanged()
        return desired
    }

    fun requireUnchanged() {
        check(ordinary.dataSource === ordinarySource && deletion.dataSource === deletionSource)
        requirePoolPolicy() // A lower graph built before a cold pin cannot survive its later protected declaration.
        check(routing.journalConfiguration === journal)
        lanes.requireTestJournal(journal)
        recoveryRegistration?.process?.requireUnchangedConfiguration()
        initialDeletion?.requireGraph(this)
    }

    fun requireOrdinary(jdbc: JdbcTemplate) {
        requireUnchanged()
        check(jdbc.dataSource === ordinarySource && (initialDeletion == null || jdbc === ordinary))
    }

    fun requireDeletion(jdbc: JdbcTemplate) {
        requireUnchanged()
        check(jdbc.dataSource === deletionSource && (initialDeletion == null || jdbc === deletion))
    }

    private fun requirePoolPolicy() {
        listOf(ordinarySource, deletionSource).forEach { source ->
            if (source is GuardedDataSource) source.requireTestInitialCheckpointDeletion(deletionPolicy)
            else check(deletionPolicy == null)
        }
    }

    override fun toString(): String = "TestOwnerDeleteLocalGraphV1(retained-origin-comparisons)"
}
