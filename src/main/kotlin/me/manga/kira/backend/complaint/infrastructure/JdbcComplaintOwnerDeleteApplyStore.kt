package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAuditEntry
import me.manga.kira.backend.audit.domain.OwnerDeleteAuditOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.InstallationCredentialSnapshot
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.InstallationRecoveryDecision
import me.manga.kira.backend.complaint.domain.InstallationRecoveryEvidence
import me.manga.kira.backend.complaint.domain.InstallationRecoveryReducer
import me.manga.kira.backend.complaint.domain.InstallationRecoverySnapshot
import me.manga.kira.backend.complaint.domain.InstallationReservationSnapshot
import me.manga.kira.backend.complaint.domain.OrdinaryInstallationDeletion
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.RecoveryContentEffect
import me.manga.kira.backend.complaint.domain.RecoveryCredentialEffect
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationRecordV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Fixed erasure/reconstruction consumer. Neither a raw event, another SQL issuer nor an alias completes a primary receipt. */
internal class JdbcComplaintOwnerDeleteApplyStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val authorization: JdbcComplaintOwnerDeleteStore,
    private val verification: JdbcComplaintOwnerDeleteVerificationStore,
) {
    private val issuer = Any()
    private val codec = TestOwnerDeleteVerificationCodecV1(graph.routing)
    init { authorization.requireBinding(graph, jdbc); verification.requireBinding(authorization) }
    fun capture(work: CommittedTestOwnerDeleteWork, proof: CommittedTestOwnerDeleteVerificationV1): TestOwnerDeleteApplyInputV1 {
        requireConnectionFree()
        val event = authorization.ownedEvent(work)
        val verified = verification.verifiedEvent(proof)
        check(event.route == verified.route && event.canonicalBytes().contentEquals(verified.canonicalBytes()))
        val bytes = proof.verificationBytes()
        check(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), proof.verificationHash()))
        return CapturedTestDeleteApply(issuer, event, codec.parse(bytes, event), bytes, recovery = false)
    }
    fun captureRecovery(readback: TestOwnerDeleteJournalReadbackV1): TestOwnerDeleteApplyInputV1 {
        requireConnectionFree()
        check(graph.recoveryRegistration == null) // The registered continuation cannot acquire reconstruction/alias authority.
        val record = codec.observed(readback)
        return CapturedTestDeleteApply(issuer, readback.event, record, codec.canonicalBytes(record), recovery = true)
    }
    internal fun captureRegisteredInventoryRecovery(original: TestRunOrdinaryDrainV1): TestOwnerDeleteApplyInputV1 {
        requireConnectionFree()
        check(graph.recoveryRegistration === original.registration)
        val readback = original.ownedRecoveryReadback(this, graph, jdbc)
        readback.requireOriginal(original)
        val record = codec.observed(readback)
        return CapturedTestDeleteApply(issuer, readback.event, record, codec.canonicalBytes(record), recovery = true, registeredInventory = original)
    }
    fun apply(input: TestOwnerDeleteApplyInputV1): ComplaintOwnerDeleteApplyOperation = ComplaintOwnerDeleteApplyOperation.capture(jdbc, capacity, audit, graph, codec, issuer, input)
}
internal sealed interface TestOwnerDeleteApplyInputV1
private class CapturedTestDeleteApply(val issuer: Any, val event: TestOwnerDeleteJournalEventV1, val record: TestOwnerDeleteVerificationRecordV1,
    bytes: ByteArray, val recovery: Boolean, val registeredInventory: TestRunOrdinaryDrainV1? = null) : TestOwnerDeleteApplyInputV1 {
    val bytes = bytes.copyOf()
    val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    val ciphertext: ByteArray = HexFormat.of().parseHex(record.ciphertextSha256)
}
internal class TestOwnerDeleteMaterializedCounts(val installation: Int, val resource: Int, val removed: Int, val applied: Int, val summary: Int) {
    init { check(listOf(installation, resource, removed, applied, summary).all { it in 0..1 } && summary <= applied) }
}

internal class ComplaintOwnerDeleteApplyOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val codec: TestOwnerDeleteVerificationCodecV1,
    private val input: CapturedTestDeleteApply,
) : ComplaintOwnerDeletePhaseOperation {
    private val event = input.event
    private val scope = event.tuple.scope
    private val installation = ScopedInstallationId(event.tuple.actorId, scope)
    private val target = event.complaintIds().single()
    private val tuple = ComplaintOwnerDeleteTuple(installation, event.tuple.operationKey, target, event.tuple.fingerprintBytes())
    private var stage = Stage.RETAINED
    private var allocation: JdbcComplaintCapacityStore.LockedOwnerDeleteApply? = null
    private var primary: TestOwnerDeleteJournalEventV1 = event
    private var publication: OwnerDeleteRows.Publication? = null
    private var receipt: OwnerDeleteRows.Receipt? = null
    private var reserve: OwnerDeleteRows.Recovery? = null
    private var missingN = false
    private var missingP = false
    private var missingL = false
    private var exactApplied = false
    private var familyApplied = 0
    private var counts: TestOwnerDeleteMaterializedCounts? = null
    private val auditOutcomes = ArrayList<OwnerDeleteAuditOutcome>(2)
    private var appliedAt: Instant? = null
    private var primaryCompleted = false
    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && expected === PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) && stage === Stage.COMPLETE &&
        allocation?.completedFor(this) == true && (input.recovery || primaryCompleted)
    val result: ComplaintOwnerDeleteReceipt get() {
        phase.ownerDelete.requireCommitted(this); requireConnectionFree(); check(!input.recovery && primaryCompleted); return ComplaintOwnerDeleteReceipt.Applied
    }
    fun requireRecovered() { phase.ownerDelete.requireCommitted(this); requireConnectionFree(); check(input.recovery) }
    internal fun requireRegisteredContinuation(original: TestRunOwnerDeleteContinuationV1) {
        check(!input.recovery)
        original.requireApplyInput(input)
    }
    internal fun requireRegisteredInventoryRecovery(original: TestRunOrdinaryDrainV1) {
        check(input.recovery && input.registeredInventory === original && graph.recoveryRegistration === original.registration)
        original.requireRecoveryInput(input)
    }
    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        retained()
        val controls = TestOwnerDeleteControlBindingV1(graph)
        controls.lock(jdbc, false).requireContinuation(event.tuple.epoch, prepared = false)
        stage = Stage.RECEIPT
        receipt = jdbc.query(OwnerDeletePersistenceSql.LOCK_RECEIPT, { row, _ -> OwnerDeleteRows.Receipt(row) }, installation.id, tuple.key).singleOrNull()
        receipt?.let { check(it.matches(tuple) && it.valid && it.state in setOf("AUTHORIZED_DELETE", "COMPLETED")); if (it.state == "COMPLETED") check(it.completed() === ComplaintOwnerDeleteReceipt.Applied) }
        missingN = receipt == null
        check(input.recovery || !missingN)
        stage = Stage.PUBLICATION
        val routes = graph.routing.derive(event.tuple).candidates()
        val publications = routes.sortedBy { it.eventId }.mapNotNull { route ->
            jdbc.query(OwnerDeletePersistenceSql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteRows.Publication(row) }, route.eventId).singleOrNull()?.also { row ->
                val canonical = TestOwnerDeleteJournalCodecV1.restoreCanonical(graph.routing, row.bytes, row.routingKey)
                row.requireEvent(canonical)
                ComplaintOwnerDeleteAuthorizationOperation.requireTuple(canonical, tuple)
                check(canonical.route == route && canonical.tuple.epoch == event.tuple.epoch && canonical.tuple.credentialVersion == event.tuple.credentialVersion && row.writer == graph.writer)
            }
        }
        check(publications.size <= 1) // One primary per logical tuple; aliases have applied rows only.
        publication = publications.singleOrNull()
        missingP = publication == null
        check(input.recovery || !missingP)
        publication?.let { row -> primary = TestOwnerDeleteJournalCodecV1.restoreCanonical(graph.routing, row.bytes, row.routingKey) }
        receipt?.let { check(it.publication == primary.route.eventId && (!missingP || input.recovery)) }
        if (receipt != null && missingP) error("Receipt cannot reference absent primary")
        val isPrimary = primary.route == event.route
        check(input.recovery || isPrimary)
        publication?.let { row ->
            if (isPrimary && row.state != "PREPARED") requireProof(row, exactLocal = !input.recovery)
            if (!input.recovery) check(row.state in setOf("VERIFIED", "APPLIED"))
            receipt?.let { check(it.authorizedAt == row.createdAt) }
        }
        stage = Stage.RESERVATION
        reserve = jdbc.query(OwnerDeletePersistenceSql.LOCK_RECOVERY, { row, _ -> OwnerDeleteRows.Recovery(row, scope, primary.route.eventId) }, primary.route.eventId).singleOrNull()
        missingL = reserve == null
        check(input.recovery || !missingL)
        if (input.registeredInventory != null) {
            // A native inventory is not permission to manufacture another primary/N/P/L from free
            // capacity. Only aliases (or exact primary replay) of the actual retained promise apply.
            check(!missingN && !missingP && !missingL && publication?.state == "APPLIED" && receipt?.state == "COMPLETED")
        }
        val appliedRows = jdbc.query(OwnerDeletePersistenceSql.READ_APPLIED_FAMILY, { row, _ ->
            check(row.getObject("data_scope_id", UUID::class.java) == scope.id && row.getBoolean("test_only") && row.getObject("writer_generation", UUID::class.java) == graph.writer &&
                row.getLong("journal_epoch") == event.tuple.epoch && row.getString("event_kind") == "OWNER_DELETE" && row.getInt("target_count") == 1 && row.getBoolean("finite"))
            val route = routes.single { it.eventId == row.getString("event_id") }
            check(row.getString("object_key") == route.objectKey)
            val version = requireJournalVersion(row.getString("object_version"))
            val hash = checkNotNull(row.getBytes("ciphertext_hash")).also { check(it.size == 32) }
            if (route == event.route) { check(version == input.record.objectVersion && hash.contentEquals(input.ciphertext)); exactApplied = true }
            route.eventId
        }, routes.joinToString(",", "{", "}") { it.eventId })
        check(appliedRows.size <= OwnerDeleteCapacityCharges.MAX_RETAINED_CANDIDATES && appliedRows.distinct().size == appliedRows.size)
        familyApplied = appliedRows.size
        // Each retained first application spends exactly one logical E in the same transaction.
        check((reserve?.used ?: ComplaintCapacityVector.ZERO)[ComplaintCapacityCounter.JOURNAL_APPLIED] == familyApplied.toLong())
        // An orphaned partial history cannot reset cumulative U or replenish a spent alias/identity slot.
        check((!missingL && !missingP) || familyApplied == 0)
        check(exactApplied || familyApplied < OwnerDeleteCapacityCharges.MAX_RETAINED_CANDIDATES)
        if (publication?.state == "APPLIED" && isPrimary) check(exactApplied)
        stage = Stage.COUNTERS_READY
        val paid = capacity.lockForOwnerDeleteApply(this)
        stage = Stage.BOOKKEEPING
        reconstructBookkeeping(isPrimary)
        stage = Stage.DOMAIN
        controls.lockRun(jdbc, false)
        val ownerState = jdbc.query(OwnerDeletePersistenceSql.LOCK_INSTALLATION, { row, _ ->
            requireScope(row.getObject("data_scope_id", UUID::class.java), row.getBoolean("test_only")); InstallationIdentityState.valueOf(row.getString("state"))
        }, installation.id).singleOrNull()
        val credentialState = jdbc.query(OwnerDeletePersistenceSql.LOCK_CREDENTIAL, { row, _ ->
            requireScope(row.getObject("data_scope_id", UUID::class.java), row.getBoolean("test_only")); InstallationCredentialState.valueOf(row.getString("state"))
        }, installation.id).singleOrNull()
        val hasContent = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM complaints WHERE owner_id = ?)", Boolean::class.java, installation.id) == true
        val disposition = InstallationRecoveryReducer.reduce(InstallationRecoverySnapshot(installation,
            ownerState?.let { InstallationReservationSnapshot(installation, it) }, credentialState?.let { InstallationCredentialSnapshot(installation, it) }, hasContent),
            InstallationRecoveryEvidence.OrdinaryDeletion(installation, OrdinaryInstallationDeletion.OWNER_REPORT)) as? InstallationRecoveryDecision.Apply ?: error("Ordinary deletion disposition required")
        check(disposition.credentialEffect === RecoveryCredentialEffect.PRESERVE && disposition.contentEffect === RecoveryContentEffect.ERASE_AUTHORIZED_RESOURCES)
        var reconstructedOwner = 0
        if (disposition.reserveIdentityCapacity) {
            check(input.recovery && !exactApplied && disposition.identityState === InstallationIdentityState.RECOVERY_RESERVED)
            checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.RECONSTRUCT_INSTALLATION, installation.id, scope.id) == 1); reconstructedOwner = 1
        }
        val resourceState = jdbc.query(OwnerDeletePersistenceSql.LOCK_RESOURCE, { row, _ ->
            requireScope(row.getObject("data_scope_id", UUID::class.java), row.getBoolean("test_only")); row.getString("state")
        }, target).singleOrNull()
        val version = jdbc.query(OwnerDeletePersistenceSql.LOCK_CONTENT, { row, _ ->
            requireScope(row.getObject("data_scope_id", UUID::class.java), row.getBoolean("test_only"))
            check(row.getObject("owner_id", UUID::class.java) == installation.id && row.getString("ownership") == "INSTALLATION" && row.getString("kind") in setOf("REPORT", "REPLY"))
            OwnerDeleteRows.positive(row, "version")
        }, target).singleOrNull()
        if (familyApplied > 0) check(version == null && resourceState == "DELETED")
        if (exactApplied) check(ownerState != null && resourceState == "DELETED" && version == null)
        appliedAt = jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() })!!
        check(Instant.parse(input.record.retainUntil).isAfter(checkNotNull(appliedAt)))
        var reconstructedResource = 0
        var removed = 0
        if (!exactApplied) {
            check(resourceState in setOf(null, "LIVE", "DELETION_PENDING", "DELETED"))
            if (version != null) {
                check(resourceState == "LIVE" || resourceState == "DELETION_PENDING")
                checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.DELETE_CONTENT, target, scope.id, installation.id, version) == 1)
                removed = 1
                auditOutcomes.add(OwnerDeleteAuditOutcome.Removed(scope, target, version))
            }
            if (resourceState == null) {
                check(input.recovery && version == null)
                checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.RECONSTRUCT_RESOURCE, target, scope.id) == 1); reconstructedResource = 1
            } else if (resourceState != "DELETED") { checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.DELETE_RESOURCE, target, scope.id) == 1) }
            checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.INSERT_APPLIED, event.route.objectKey, input.record.objectVersion, event.route.eventId, input.ciphertext,
                graph.writer, event.tuple.epoch, scope.id) == 1)
            if (input.recovery) auditOutcomes.add(OwnerDeleteAuditOutcome.RecoveryApplied(scope, target))
        }
        counts = TestOwnerDeleteMaterializedCounts(reconstructedOwner, reconstructedResource, removed, if (exactApplied) 0 else 1, if (!exactApplied && input.recovery) 1 else 0)
        stage = Stage.SETTLING
        paid.settle(this)
        stage = Stage.AUDIT
        auditOutcomes.forEach { audit.recordOwnerDelete(it, paid, checkNotNull(appliedAt)) }
        check(paid.completedFor(this))
        stage = Stage.COMPLETING
        if (isPrimary) completePrimary()
        // An alias has only its applied row and (first recovery) summary; never rewrite primary object/proof/receipt.
        input.registeredInventory?.recordRecoveredVersion(this, jdbc)
        retained(); stage = Stage.COMPLETE
    }
    private fun reconstructBookkeeping(isPrimary: Boolean) {
        val event = primary
        if (missingP) {
            check(input.recovery && isPrimary && missingL)
            checkWrite()
            jdbc.queryForObject(OwnerDeletePersistenceSql.INSERT_PUBLICATION, { row, _ -> row.getTimestamp(1).toInstant() }, event.route.eventId, scope.id,
                graph.writer, event.tuple.epoch, event.route.routingKeyId, event.route.objectKey, event.canonicalBytes(), HexFormat.of().parseHex(event.semanticSha256))!!
            publication = jdbc.query(OwnerDeletePersistenceSql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteRows.Publication(row) }, event.route.eventId).single()
        }
        if (isPrimary && publication?.state == "PREPARED") {
            check(input.recovery)
            checkWrite()
            publication = jdbc.query(OwnerDeletePersistenceSql.RECORD_VERIFIED, { row, _ -> OwnerDeleteRows.Publication(row) }, input.record.objectVersion, input.ciphertext,
                Timestamp.from(Instant.parse(input.record.objectCreatedAt)), Timestamp.from(Instant.parse(input.record.retainUntil)), Timestamp.from(Instant.parse(input.record.verifiedAt)),
                input.bytes, input.hash, event.route.eventId, scope.id, HexFormat.of().parseHex(event.semanticSha256)).single()
            requireProof(checkNotNull(publication), exactLocal = true)
        }
        if (missingL) {
            checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.INSERT_RECOVERY, event.route.eventId, scope.id, event.route.eventId, OwnerDeleteRows.array(OwnerDeleteCapacityCharges.RECOVERY)) == 1)
        }
        if (missingN) {
            check(input.recovery)
            checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.INSERT_CLAIM, installation.id, tuple.key, tuple.fingerprintBytes(), target, scope.id) == 1)
            checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.AUTHORIZE_RECEIPT, event.route.eventId, Timestamp.from(checkNotNull(publication).createdAt), installation.id,
                tuple.key, scope.id, tuple.fingerprintBytes(), target) == 1)
        }
    }
    private fun completePrimary() {
        val row = checkNotNull(publication)
        requireProof(row, exactLocal = !input.recovery)
        if (row.state == "VERIFIED") {
            checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.MARK_APPLIED, row.eventId, scope.id, row.objectVersion, row.ciphertextHash, row.verificationHash) == 1)
        } else check(row.state == "APPLIED" && exactApplied)
        val old = receipt
        if (old == null || old.state == "AUTHORIZED_DELETE") {
            checkWrite(); check(jdbc.update(OwnerDeletePersistenceSql.COMPLETE_RECEIPT, event.tuple.epoch, row.objectVersion, row.ciphertextHash, installation.id, tuple.key,
                scope.id, row.eventId, tuple.fingerprintBytes(), target) == 1)
        } else {
            check(old.state == "COMPLETED" && old.externalEvent == row.eventId && old.externalEpoch == event.tuple.epoch && old.externalVersion == row.objectVersion &&
                old.externalHash.contentEquals(row.ciphertextHash))
        }
        primaryCompleted = true
    }
    private fun requireProof(row: OwnerDeleteRows.Publication, exactLocal: Boolean) {
        row.requireEvent(event)
        val record = codec.parse(checkNotNull(row.verificationBytes), event)
        ComplaintOwnerDeleteVerificationOperation.requireColumns(record, row)
        check(record.objectVersion == input.record.objectVersion && record.ciphertextSha256 == input.record.ciphertextSha256 && record.objectCreatedAt == input.record.objectCreatedAt)
        check(!Instant.parse(input.record.retainUntil).isBefore(Instant.parse(record.retainUntil)))
        if (exactLocal) check(row.verificationBytes.contentEquals(input.bytes) && row.verificationHash.contentEquals(input.hash))
    }
    internal fun beginCounterLock(selected: JdbcTemplate) { requireSelected(selected); check(stage === Stage.COUNTERS_READY && allocation == null); stage = Stage.COUNTERS }
    internal fun requireCapacityPolicy(ledger: ComplaintCapacityLedger, selected: JdbcTemplate) {
        requireSelected(selected); check(stage === Stage.COUNTERS && graph.policy.digestBytes().contentEquals(ledger.configuration.digestBytes()))
        check(graph.policy.hardLimit == ledger.balance.hardLimit && graph.policy.creationLimit == ledger.balance.creationLimit)
        check((reserve?.remaining ?: ComplaintCapacityVector.ZERO).fitsWithin(ledger.balance.recoveryReserved))
        phase.ownerDelete.checkCapacity(this, selected, ledger)
    }
    internal fun retainCapacity(paid: JdbcComplaintCapacityStore.LockedOwnerDeleteApply, selected: JdbcTemplate) { requireSelected(selected); check(stage === Stage.COUNTERS && allocation == null && paid.belongsTo(this)); allocation = paid }
    internal fun missingBookkeeping(paid: JdbcComplaintCapacityStore.LockedOwnerDeleteApply, selected: JdbcTemplate): Pair<ComplaintCapacityVector, ComplaintCapacityVector> {
        requireSelected(selected); check(stage === Stage.COUNTERS && allocation === paid)
        val charge = OwnerDeleteCapacityCharges.RECEIPT.scaled(if (missingN) 1L else 0L) + OwnerDeleteCapacityCharges.PUBLICATION.scaled(if (missingP) 1L else 0L) +
            OwnerDeleteCapacityCharges.RESERVATION.scaled(if (missingL) 1L else 0L)
        return charge to (if (missingL) OwnerDeleteCapacityCharges.RECOVERY else ComplaintCapacityVector.ZERO)
    }
    internal fun materializedCounts(paid: JdbcComplaintCapacityStore.LockedOwnerDeleteApply, selected: JdbcTemplate): TestOwnerDeleteMaterializedCounts {
        requireSelected(selected); check(stage === Stage.SETTLING && allocation === paid); return checkNotNull(counts)
    }
    internal fun remainingReserve(paid: JdbcComplaintCapacityStore.LockedOwnerDeleteApply, selected: JdbcTemplate): ComplaintCapacityVector {
        requireSelected(selected); check(stage === Stage.SETTLING && allocation === paid); return reserve?.remaining ?: OwnerDeleteCapacityCharges.RECOVERY
    }
    internal fun recordProgress(paid: JdbcComplaintCapacityStore.LockedOwnerDeleteApply, selected: JdbcTemplate, use: ComplaintCapacityVector) {
        requireSelected(selected); check(stage === Stage.SETTLING && allocation === paid)
        val prior = reserve?.used ?: ComplaintCapacityVector.ZERO
        val total = prior + use
        check(total != OwnerDeleteCapacityCharges.RECOVERY && total.fitsWithin(OwnerDeleteCapacityCharges.RECOVERY))
        checkWrite()
        check(jdbc.update(OwnerDeletePersistenceSql.SPEND_RECOVERY, OwnerDeleteRows.array(total), primary.route.eventId, scope.id, OwnerDeleteRows.array(OwnerDeleteCapacityCharges.RECOVERY),
            if (prior.isZero()) null else OwnerDeleteRows.array(prior), reserve?.convertedAt?.let(Timestamp::from)) == 1)
    }
    internal fun requireCapacityWrite(paid: JdbcComplaintCapacityStore.LockedOwnerDeleteApply, selected: JdbcTemplate) { requireSelected(selected); check(allocation === paid && stage in setOf(Stage.COUNTERS, Stage.SETTLING)); checkWrite() }
    internal fun auditConnection(paid: JdbcComplaintCapacityStore.LockedOwnerDeleteApply, selected: JdbcTemplate, entry: CountedOwnerDeleteAuditEntry, index: Int): Connection {
        requireAuditWrite(paid, selected); check(entry.outcome === auditOutcomes[index] && entry.createdAt == appliedAt); return phase.ownerDelete.connection(this, selected)
    }
    internal fun requireAuditWrite(paid: JdbcComplaintCapacityStore.LockedOwnerDeleteApply, selected: JdbcTemplate) { requireSelected(selected); check(stage === Stage.AUDIT && allocation === paid && paid.settledFor(this)); checkWrite() }
    private fun requireScope(id: UUID, test: Boolean) { check(id == scope.id && test) }
    private fun retained() { phase.ownerDelete.requireRetained(this, jdbc); graph.requireDeletion(jdbc) }
    private fun requireSelected(jdbc: JdbcTemplate) { retained(); check(this.jdbc === jdbc) }
    private fun checkWrite() = phase.ownerDelete.checkWrite(this, jdbc)
    internal fun failed(problem: Throwable): Nothing { stage = Stage.FAILED; phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
    private enum class Stage { RETAINED, RECEIPT, PUBLICATION, RESERVATION, COUNTERS_READY, COUNTERS, BOOKKEEPING, DOMAIN, SETTLING, AUDIT, COMPLETING, COMPLETE, FAILED }
    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService, graph: TestOwnerDeleteLocalGraphV1,
            codec: TestOwnerDeleteVerificationCodecV1, issuer: Any, input: TestOwnerDeleteApplyInputV1): ComplaintOwnerDeleteApplyOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerDelete.requireOperation(jdbc, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY)
                phase.requireTestRunOwnerDeleteApply(graph, jdbc, input)
                val selected = input as? CapturedTestDeleteApply ?: error("Original verified input required")
                check(selected.issuer === issuer && selected.event.belongsTo(graph.routing))
                return ComplaintOwnerDeleteApplyOperation(phase, jdbc, graph, codec, selected).also { phase.ownerDelete.retain(it, jdbc); it.execute(capacity, audit) }
            } catch (problem: Throwable) { phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
    }
}
