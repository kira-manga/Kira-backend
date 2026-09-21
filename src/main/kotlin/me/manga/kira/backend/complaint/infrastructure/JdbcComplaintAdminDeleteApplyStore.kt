package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.CountedAdminDeleteAuditEntry
import me.manga.kira.backend.audit.domain.AdminDeleteAuditOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFamily
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
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
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentApplyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunAdminDeleteContinuationV1
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
internal class JdbcComplaintAdminDeleteApplyStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val authorization: JdbcComplaintAdminDeleteStore,
    private val verification: JdbcComplaintAdminDeleteVerificationStore,
) {
    private val issuer = Any()
    private val codec = TestOwnerDeleteVerificationCodecV1.forAdminErasure(graph.routing)
    init { authorization.requireBinding(graph, jdbc); verification.requireBinding(authorization); check(graph.routing.journalConfiguration.adminDelete) }
    fun capture(work: CommittedTestAdminDeleteWork, proof: CommittedTestAdminDeleteVerificationV1): TestAdminDeleteApplyInputV1 {
        requireConnectionFree()
        val event = authorization.ownedEvent(work)
        val verified = verification.verifiedEvent(proof)
        check(event.route == verified.route && event.canonicalBytes().contentEquals(verified.canonicalBytes()))
        val bytes = proof.verificationBytes()
        check(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes), proof.verificationHash()))
        return CapturedTestAdminDeleteApply(issuer, event, codec.parse(bytes, event), bytes, recovery = false)
    }
    fun captureRecovery(readback: TestOwnerDeleteJournalReadbackV1): TestAdminDeleteApplyInputV1 {
        requireConnectionFree()
        check(graph.recoveryRegistration == null) // The registered continuation cannot acquire reconstruction/alias authority.
        val record = codec.observed(readback)
        return CapturedTestAdminDeleteApply(issuer, readback.event, record, codec.canonicalBytes(record), recovery = true)
    }
    internal fun captureRegisteredInventoryRecovery(original: TestRunOrdinaryDrainV1): TestAdminDeleteApplyInputV1 {
        requireConnectionFree()
        check(graph.recoveryRegistration === original.registration && graph.routing.journalConfiguration.registeredAdminDelete)
        val readback = original.ownedRecoveryReadback(this, graph, jdbc)
        readback.requireOriginal(original)
        val record = codec.observed(readback)
        return CapturedTestAdminDeleteApply(issuer, readback.event, record, codec.canonicalBytes(record), recovery = true, registeredInventory = original)
    }
    /** Only the retained ACTIVE original's freshly authenticated native delivery, never a terminal permission. */
    internal fun captureRegisteredQueueRecovery(original: TestActiveOwnerDeleteQueueV1): TestAdminDeleteApplyInputV1 {
        requireConnectionFree()
        check(graph.recoveryRegistration === original.registration && graph.routing.journalConfiguration.registeredAdminDelete)
        val readback = original.ownedRecoveryReadback(this, graph, jdbc)
        readback.requireOriginal(original)
        val record = codec.observed(readback)
        return CapturedTestAdminDeleteApply(issuer, readback.event, record, codec.canonicalBytes(record), recovery = true, registeredQueue = original)
    }
    internal fun captureRegisteredRecurrentRecovery(original: TestActiveRecurrentApplyV1): TestAdminDeleteApplyInputV1 {
        requireConnectionFree()
        check(graph.recoveryRegistration === original.registration)
        val readback = original.ownedRecoveryReadback(this, graph, jdbc)
        val record = codec.observed(readback)
        return CapturedTestAdminDeleteApply(issuer, readback.event, record, codec.canonicalBytes(record), recovery = true, registeredRecurrent = original)
    }
    fun apply(input: TestAdminDeleteApplyInputV1): ComplaintAdminDeleteApplyOperation = ComplaintAdminDeleteApplyOperation.capture(jdbc, capacity, audit, graph, codec, issuer, input)
}
internal sealed interface TestAdminDeleteApplyInputV1
private class CapturedTestAdminDeleteApply(val issuer: Any, val event: TestOwnerDeleteJournalEventV1, val record: TestOwnerDeleteVerificationRecordV1,
    bytes: ByteArray, val recovery: Boolean, val registeredInventory: TestRunOrdinaryDrainV1? = null,
    val registeredQueue: TestActiveOwnerDeleteQueueV1? = null, val registeredRecurrent: TestActiveRecurrentApplyV1? = null) : TestAdminDeleteApplyInputV1 {
    val bytes = bytes.copyOf()
    val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    val ciphertext: ByteArray = HexFormat.of().parseHex(record.ciphertextSha256)
}
internal class TestAdminDeleteMaterializedCounts(val installation: Int, val resource: Int, val removed: Int, val applied: Int, val summary: Int,
    ownerCount: Int, targetCount: Int) {
    init {
        check(targetCount in 1..50 && ownerCount in 1..targetCount && installation in 0..ownerCount &&
            resource in 0..targetCount && removed in 0..targetCount && applied in 0..1 && summary in 0..applied)
    }
}
internal class ComplaintAdminDeleteApplyOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val codec: TestOwnerDeleteVerificationCodecV1,
    private val input: CapturedTestAdminDeleteApply,
) : ComplaintAdminDeletePhaseOperation {
    private val event = input.event
    private val scope = event.adminComparison.scope
    private val installations = event.adminComparison.ownerInstallationIds().map { ScopedInstallationId(it, scope) }
    private val targets = event.complaintIds()
    private val tuple = AdminDeleteRows.tuple(event)
    private val promised = AdminDeleteRows.recovery(event)
    private var stage = Stage.RETAINED
    private var allocation: JdbcComplaintCapacityStore.LockedAdminDeleteApply? = null
    private var primary: TestOwnerDeleteJournalEventV1 = event
    private var publication: OwnerDeleteRows.Publication? = null
    private var receipt: AdminDeleteRows.Receipt? = null
    private var reserve: OwnerDeleteRows.Recovery? = null
    private var missingN = false
    private var missingP = false
    private var missingL = false
    private var exactApplied = false
    private var familyApplied = 0
    private var counts: TestAdminDeleteMaterializedCounts? = null
    private val auditOutcomes = ArrayList<AdminDeleteAuditOutcome>(targets.size + 1)
    private var appliedAt: Instant? = null
    private var primaryCompleted = false
    override fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && expected === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY
    override fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) && stage === Stage.COMPLETE &&
        allocation?.completedFor(this) == true && (input.recovery || primaryCompleted)
    val result: ComplaintAdminDeleteReceipt get() {
        phase.adminDelete.requireCommitted(this); requireConnectionFree(); check(!input.recovery && primaryCompleted); return AdminDeleteRows.completed(event)
    }
    fun requireRecovered() { phase.adminDelete.requireCommitted(this); requireConnectionFree(); check(input.recovery) }
    internal fun requireRegisteredContinuation(original: TestRunAdminDeleteContinuationV1) {
        check(!input.recovery)
        original.requireApplyInput(input)
    }
    internal fun requireRegisteredInventoryRecovery(original: TestRunOrdinaryDrainV1) {
        check(input.recovery && input.registeredInventory === original && input.registeredQueue == null && input.registeredRecurrent == null &&
            graph.recoveryRegistration === original.registration)
        original.requireRecoveryInput(input)
    }
    internal fun requireRegisteredQueueRecovery(original: TestActiveOwnerDeleteQueueV1) {
        check(input.recovery && input.registeredQueue === original && input.registeredInventory == null && input.registeredRecurrent == null &&
            graph.recoveryRegistration === original.registration)
        original.requireRecoveryInput(input)
    }
    internal fun requireRegisteredRecurrentRecovery(original: TestActiveRecurrentApplyV1) {
        check(input.recovery && input.registeredRecurrent === original && input.registeredInventory == null && input.registeredQueue == null &&
            graph.recoveryRegistration === original.registration)
        original.requireRecoveryInput(input)
    }
    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        retained()
        val controls = TestOwnerDeleteControlBindingV1(graph)
        controls.lock(jdbc, false).requireContinuation(event.adminComparison.epoch, prepared = false)
        stage = Stage.RECEIPT
        receipt = jdbc.query(AdminDeletePersistenceSql.LOCK_RECEIPT, { row, _ -> AdminDeleteRows.Receipt(row) }, tuple.actor, tuple.key).singleOrNull()
        if (receipt == null && input.recovery && input.registeredInventory == null && input.registeredRecurrent == null) {
            // Claim the absent historical receipt before any publication/counter/domain lock. A
            // concurrent first use must settle here, never below a later-class lock. Capacity is
            // charged later in this same transaction; any failure rolls this provisional claim back.
            phase.adminDelete.checkReceiptWrite(this, jdbc)
            missingN = jdbc.update(AdminDeletePersistenceSql.INSERT_CLAIM, tuple.actor, tuple.key, tuple.operation, tuple.fingerprintBytes(), AdminDeleteRows.array(targets), scope.id) == 1
            if (!missingN) receipt = jdbc.query(AdminDeletePersistenceSql.LOCK_RECEIPT, { row, _ -> AdminDeleteRows.Receipt(row) }, tuple.actor, tuple.key).single()
        }
        receipt?.let { check(it.consumedGrantId == event.adminComparison.consumedGrantId && it.matches(tuple) && it.valid && it.state in setOf("AUTHORIZED_DELETE", "COMPLETED")); if (it.state == "COMPLETED") AdminDeleteRows.requireApplied(it.completed(), event) }
        check(input.recovery || receipt != null)
        stage = Stage.PUBLICATION
        val routes = graph.routing.derive(event.adminComparison).candidates()
        val publications = routes.sortedBy { it.eventId }.mapNotNull { route ->
            jdbc.query(AdminDeletePersistenceSql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteRows.Publication.adminErasure(row) }, route.eventId).singleOrNull()?.also { row ->
                val canonical = TestOwnerDeleteJournalCodecV1.restoreAdminErasureCanonical(graph.routing, row.bytes, row.routingKey, row.kind)
                row.requireAdminErasureEvent(canonical)
                ComplaintAdminDeleteAuthorizationOperation.requireTuple(canonical, tuple)
                check(canonical.route == route && canonical.adminComparison.epoch == event.adminComparison.epoch && canonical.adminComparison.ownerInstallationIds() == event.adminComparison.ownerInstallationIds() && canonical.adminComparison.consumedGrantId == event.adminComparison.consumedGrantId && row.writer == graph.writer)
            }
        }
        check(publications.size <= 1) // One primary per logical tuple; aliases have applied rows only.
        publication = publications.singleOrNull()
        missingP = publication == null
        check(input.recovery || !missingP)
        publication?.let { row -> primary = TestOwnerDeleteJournalCodecV1.restoreAdminErasureCanonical(graph.routing, row.bytes, row.routingKey, row.kind) }
        receipt?.let { check(it.publication == primary.route.eventId && (!missingP || input.recovery)) }
        if (receipt != null && missingP) error("Receipt cannot reference absent primary")
        val isPrimary = primary.route == event.route &&
            (publication?.objectVersion == null || publication?.objectVersion == input.record.objectVersion)
        check(input.recovery || isPrimary)
        publication?.let { row ->
            if (isPrimary && row.state != "PREPARED") requireProof(row, exactLocal = !input.recovery)
            if (input.registeredQueue != null && primary.route == event.route) {
                // ACTIVE recovery has one exact version per retained key. It does not borrow the
                // terminal native inventory's same-key opaque-version alias right.
                check(primary.canonicalBytes().contentEquals(event.canonicalBytes()) &&
                    (row.objectVersion == null || row.objectVersion == input.record.objectVersion))
            }
            if (input.registeredInventory != null && primary.route == event.route) {
                // A second opaque version at the primary key cannot replace its canonical/wire bytes.
                check(primary.canonicalBytes().contentEquals(event.canonicalBytes()) && row.ciphertextHash.contentEquals(input.ciphertext))
            }
            if (!input.recovery) check(row.state in setOf("VERIFIED", "APPLIED"))
            receipt?.let { check(it.authorizedAt == row.createdAt) }
        }
        stage = Stage.RESERVATION
        reserve = jdbc.query(AdminDeletePersistenceSql.LOCK_RECOVERY, { row, _ -> OwnerDeleteRows.Recovery(row, scope, primary.route.eventId, promised) }, primary.route.eventId).singleOrNull()
        missingL = reserve == null
        check(input.recovery || !missingL)
        if (input.registeredInventory != null) {
            // A native inventory is not an old-snapshot reconstruction issuer for N/P/L. Retain
            // the actual paid primary, original grant and separately committed verification.
            check(receipt != null && !missingN && !missingP && !missingL && publication?.state == "APPLIED" && receipt?.state == "COMPLETED")
        }
        if (input.registeredRecurrent != null) {
            // This bounded bridge repairs first APPLY, not missing bookkeeping or routing aliases.
            val p = checkNotNull(publication); val n = checkNotNull(receipt)
            check(!missingN && !missingP && !missingL && isPrimary && p.objectVersion == input.record.objectVersion &&
                primary.canonicalBytes().contentEquals(event.canonicalBytes()))
            check(p.state == "VERIFIED" && n.state == "AUTHORIZED_DELETE" || p.state == "APPLIED" && n.state == "COMPLETED")
        }
        val appliedRows = jdbc.query(AdminDeletePersistenceSql.READ_APPLIED_FAMILY, { row, _ ->
            check(row.getObject("data_scope_id", UUID::class.java) == scope.id && row.getBoolean("test_only") && row.getObject("writer_generation", UUID::class.java) == graph.writer &&
                row.getLong("journal_epoch") == event.adminComparison.epoch && row.getString("event_kind") == tuple.operation && row.getInt("target_count") == targets.size && row.getBoolean("finite"))
            val route = routes.single { it.eventId == row.getString("event_id") }
            check(row.getString("object_key") == route.objectKey)
            val version = requireJournalVersion(row.getString("object_version"))
            val hash = checkNotNull(row.getBytes("ciphertext_hash")).also { check(it.size == 32) }
            if (route == event.route) {
                if (input.registeredInventory != null || input.registeredQueue != null || input.registeredRecurrent != null) check(hash.contentEquals(input.ciphertext))
                if (input.registeredQueue != null || input.registeredRecurrent != null) check(version == input.record.objectVersion)
            }
            if (route == event.route && version == input.record.objectVersion) { check(hash.contentEquals(input.ciphertext)); exactApplied = true }
            route.objectKey to version
        }, routes.joinToString(",", "{", "}") { it.eventId })
        check(appliedRows.size <= OwnerDeleteCapacityCharges.MAX_RETAINED_CANDIDATES && appliedRows.distinct().size == appliedRows.size)
        if (input.registeredQueue != null || input.registeredRecurrent != null) check(appliedRows.map { it.first }.distinct().size == appliedRows.size)
        familyApplied = appliedRows.size
        // Each retained first application spends exactly one logical E in the same transaction.
        check((reserve?.used ?: ComplaintCapacityVector.ZERO)[ComplaintCapacityCounter.JOURNAL_APPLIED] == familyApplied.toLong())
        // An orphaned partial history cannot reset cumulative U or replenish a spent alias/identity slot.
        check((!missingL && !missingP) || familyApplied == 0)
        check(exactApplied || familyApplied < OwnerDeleteCapacityCharges.MAX_RETAINED_CANDIDATES)
        if (publication?.state == "APPLIED" && isPrimary) check(exactApplied)
        if (input.registeredRecurrent != null) {
            check(appliedRows.size == if (exactApplied) 1 else 0)
            check((publication?.state == "APPLIED") == exactApplied && (receipt?.state == "COMPLETED") == exactApplied)
        }
        stage = Stage.COUNTERS_READY
        val paid = capacity.lockForAdminDeleteApply(this)
        stage = Stage.BOOKKEEPING
        reconstructBookkeeping(isPrimary)
        stage = Stage.DOMAIN
        // User existence is only an audit FK association, never later ADMIN/grant authority.
        // Old-backup recovery may use SYSTEM for a genuinely absent user; it creates no user or credential.
        val auditActor = jdbc.query("SELECT id FROM users WHERE id = ? FOR KEY SHARE", { row, _ -> row.getObject(1, UUID::class.java) }, tuple.actor).singleOrNull()
        controls.lockRun(jdbc, false)
        // Every owner pair precedes every resource, then every content lock. No reconstruction or
        // erasure write occurs until all authenticated owners/targets have been checked together.
        val owners = installations.map { installation ->
            val ownerState = jdbc.query(AdminDeletePersistenceSql.LOCK_INSTALLATION, { row, _ ->
                requireScope(row.getObject("data_scope_id", UUID::class.java), row.getBoolean("test_only")); InstallationIdentityState.valueOf(row.getString("state"))
            }, installation.id).singleOrNull()
            val credentialState = jdbc.query(AdminDeletePersistenceSql.LOCK_CREDENTIAL, { row, _ ->
                requireScope(row.getObject("data_scope_id", UUID::class.java), row.getBoolean("test_only")); InstallationCredentialState.valueOf(row.getString("state"))
            }, installation.id).singleOrNull()
            val hasContent = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM complaints WHERE owner_id = ?)", Boolean::class.java, installation.id) == true
            val disposition = InstallationRecoveryReducer.reduce(InstallationRecoverySnapshot(installation,
                ownerState?.let { InstallationReservationSnapshot(installation, it) }, credentialState?.let { InstallationCredentialSnapshot(installation, it) }, hasContent),
                InstallationRecoveryEvidence.OrdinaryDeletion(installation, OrdinaryInstallationDeletion.ADMIN_REPORT)) as? InstallationRecoveryDecision.Apply
                ?: error("Ordinary deletion disposition required")
            check(disposition.credentialEffect === RecoveryCredentialEffect.PRESERVE && disposition.contentEffect === RecoveryContentEffect.ERASE_AUTHORIZED_RESOURCES)
            if (disposition.reserveIdentityCapacity) check(input.recovery && !exactApplied && disposition.identityState === InstallationIdentityState.RECOVERY_RESERVED)
            if (exactApplied) check(ownerState != null)
            installation to disposition.reserveIdentityCapacity
        }
        val resources = targets.associateWith { target ->
            jdbc.query(AdminDeletePersistenceSql.LOCK_RESOURCE, { row, _ ->
                requireScope(row.getObject("data_scope_id", UUID::class.java), row.getBoolean("test_only")); row.getString("state")
            }, target).singleOrNull()
        }
        val ownerIds = installations.map { it.id }.toSet()
        val content = targets.associateWith { target ->
            jdbc.query(AdminDeletePersistenceSql.LOCK_CONTENT, { row, _ ->
                requireScope(row.getObject("data_scope_id", UUID::class.java), row.getBoolean("test_only"))
                val owner = row.getObject("owner_id", UUID::class.java)
                check(owner in ownerIds && row.getString("ownership") == "INSTALLATION" && row.getString("kind") in setOf("REPORT", "REPLY"))
                checkNotNull(owner) to OwnerDeleteRows.positive(row, "version")
            }, target).singleOrNull()
        }
        targets.forEach { target ->
            val state = resources[target]
            val row = content[target]
            if (familyApplied > 0) check(row == null && state == "DELETED")
            if (exactApplied) check(state == "DELETED" && row == null)
            if (!input.recovery && familyApplied == 0) check(state == "DELETION_PENDING" && row != null)
            check(state in setOf(null, "LIVE", "DELETION_PENDING", "DELETED"))
            if (row != null) check(state == "LIVE" || state == "DELETION_PENDING")
            if (state == null) check(input.recovery && row == null && !exactApplied)
        }
        appliedAt = jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() })!!
        check(Instant.parse(input.record.retainUntil).isAfter(checkNotNull(appliedAt)))
        var reconstructedOwner = 0
        var reconstructedResource = 0
        var removed = 0
        if (!exactApplied) {
            owners.filter { it.second }.forEach { (installation, _) ->
                checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.RECONSTRUCT_INSTALLATION, installation.id, scope.id) == 1); reconstructedOwner++
            }
            targets.forEach { target ->
                val state = resources[target]
                val row = content[target]
                if (row != null) {
                    checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.DELETE_CONTENT, target, scope.id, row.first, row.second) == 1)
                    removed++
                    auditOutcomes.add(AdminDeleteAuditOutcome.Removed(scope, target, row.second, auditActor))
                }
                if (state == null) {
                    checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.RECONSTRUCT_RESOURCE, target, scope.id) == 1); reconstructedResource++
                } else if (state != "DELETED") { checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.DELETE_RESOURCE, target, scope.id) == 1) }
            }
            checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.INSERT_APPLIED, event.route.objectKey, input.record.objectVersion, event.route.eventId, input.ciphertext,
                graph.writer, event.adminComparison.epoch, tuple.operation, targets.size, scope.id) == 1)
            if (input.recovery) auditOutcomes.add(when (tuple.family) {
                ComplaintAdminDeleteFamily.SINGLE -> AdminDeleteAuditOutcome.RecoveryApplied(scope, targets.single())
                ComplaintAdminDeleteFamily.BATCH -> AdminDeleteAuditOutcome.BatchRecoveryApplied(scope, event.route.eventId, removed, reconstructedResource, reconstructedOwner)
            })
        }
        counts = TestAdminDeleteMaterializedCounts(reconstructedOwner, reconstructedResource, removed, if (exactApplied) 0 else 1, if (!exactApplied && input.recovery) 1 else 0,
            installations.size, targets.size)
        stage = Stage.SETTLING
        paid.settle(this)
        stage = Stage.AUDIT
        auditOutcomes.forEach { audit.recordAdminDelete(it, paid, checkNotNull(appliedAt)) }
        check(paid.completedFor(this))
        stage = Stage.COMPLETING
        if (isPrimary) completePrimary()
        // An alias has only its applied row and (first recovery) summary; never rewrite primary object/proof/receipt.
        input.registeredInventory?.recordRecoveredVersion(this, jdbc)
        input.registeredQueue?.requireAppliedCurrent(this, jdbc)
        input.registeredRecurrent?.requireAppliedCurrent(this, jdbc)
        retained(); stage = Stage.COMPLETE
    }
    private fun reconstructBookkeeping(isPrimary: Boolean) {
        val event = primary
        if (missingP) {
            check(input.recovery && isPrimary && missingL)
            checkWrite()
            jdbc.queryForObject(AdminDeletePersistenceSql.INSERT_PUBLICATION, { row, _ -> row.getTimestamp(1).toInstant() }, event.route.eventId, scope.id,
                graph.writer, event.adminComparison.epoch, tuple.operation, targets.size, event.route.routingKeyId, event.route.objectKey, event.canonicalBytes(), HexFormat.of().parseHex(event.semanticSha256))!!
            publication = jdbc.query(AdminDeletePersistenceSql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteRows.Publication.adminErasure(row) }, event.route.eventId).single()
        }
        if (isPrimary && publication?.state == "PREPARED") {
            check(input.recovery)
            checkWrite()
            publication = jdbc.query(AdminDeletePersistenceSql.RECORD_VERIFIED, { row, _ -> OwnerDeleteRows.Publication.adminErasure(row) }, input.record.objectVersion, input.ciphertext,
                Timestamp.from(Instant.parse(input.record.objectCreatedAt)), Timestamp.from(Instant.parse(input.record.retainUntil)), Timestamp.from(Instant.parse(input.record.verifiedAt)),
                input.bytes, input.hash, event.route.eventId, scope.id, HexFormat.of().parseHex(event.semanticSha256)).single()
            requireProof(checkNotNull(publication), exactLocal = true)
        }
        if (missingL) {
            checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.INSERT_RECOVERY, event.route.eventId, scope.id, event.route.eventId, OwnerDeleteRows.array(promised)) == 1)
        }
        if (missingN) {
            check(input.recovery)
            checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.AUTHORIZE_RECEIPT, event.route.eventId, Timestamp.from(checkNotNull(publication).createdAt), event.adminComparison.consumedGrantId, tuple.actor,
                tuple.key, scope.id, tuple.operation, tuple.fingerprintBytes(), AdminDeleteRows.array(targets)) == 1)
        }
    }
    private fun completePrimary() {
        val row = checkNotNull(publication)
        requireProof(row, exactLocal = !input.recovery)
        if (row.state == "VERIFIED") {
            checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.MARK_APPLIED, row.eventId, scope.id, row.objectVersion, row.ciphertextHash, row.verificationHash) == 1)
        } else check(row.state == "APPLIED" && exactApplied)
        val old = receipt
        if (old == null || old.state == "AUTHORIZED_DELETE") {
            checkWrite(); check(jdbc.update(AdminDeletePersistenceSql.COMPLETE_RECEIPT, event.adminComparison.epoch, row.objectVersion, row.ciphertextHash, tuple.actor, tuple.key,
                scope.id, tuple.operation, row.eventId, tuple.fingerprintBytes(), AdminDeleteRows.array(targets)) == 1)
        } else {
            check(old.state == "COMPLETED" && old.externalEvent == row.eventId && old.externalEpoch == event.adminComparison.epoch && old.externalVersion == row.objectVersion &&
                old.externalHash.contentEquals(row.ciphertextHash))
        }
        primaryCompleted = true
    }
    private fun requireProof(row: OwnerDeleteRows.Publication, exactLocal: Boolean) {
        row.requireAdminErasureEvent(event)
        val record = codec.parse(checkNotNull(row.verificationBytes), event)
        ComplaintAdminDeleteVerificationOperation.requireColumns(record, row)
        check(record.objectVersion == input.record.objectVersion && record.ciphertextSha256 == input.record.ciphertextSha256 && record.objectCreatedAt == input.record.objectCreatedAt)
        check(!Instant.parse(input.record.retainUntil).isBefore(Instant.parse(record.retainUntil)))
        if (exactLocal) check(row.verificationBytes.contentEquals(input.bytes) && row.verificationHash.contentEquals(input.hash))
    }
    internal fun requireRecoveryReceiptClaim(selected: JdbcTemplate) {
        requireSelected(selected)
        check(input.recovery && input.registeredInventory == null && input.registeredRecurrent == null && stage === Stage.RECEIPT && receipt == null && !missingN && allocation == null)
    }
    internal fun beginCounterLock(selected: JdbcTemplate) { requireSelected(selected); check(stage === Stage.COUNTERS_READY && allocation == null); stage = Stage.COUNTERS }
    internal fun requireCapacityPolicy(ledger: ComplaintCapacityLedger, selected: JdbcTemplate) {
        requireSelected(selected); check(stage === Stage.COUNTERS && graph.policy.digestBytes().contentEquals(ledger.configuration.digestBytes()))
        check(graph.policy.hardLimit == ledger.balance.hardLimit && graph.policy.creationLimit == ledger.balance.creationLimit)
        check((reserve?.remaining ?: ComplaintCapacityVector.ZERO).fitsWithin(ledger.balance.recoveryReserved))
        phase.adminDelete.checkCapacity(this, selected, ledger)
    }
    internal fun retainCapacity(paid: JdbcComplaintCapacityStore.LockedAdminDeleteApply, selected: JdbcTemplate) { requireSelected(selected); check(stage === Stage.COUNTERS && allocation == null && paid.belongsTo(this)); allocation = paid }
    internal fun missingBookkeeping(paid: JdbcComplaintCapacityStore.LockedAdminDeleteApply, selected: JdbcTemplate): Pair<ComplaintCapacityVector, ComplaintCapacityVector> {
        requireSelected(selected); check(stage === Stage.COUNTERS && allocation === paid)
        val charge = OwnerDeleteCapacityCharges.RECEIPT.scaled(if (missingN) 1L else 0L) + OwnerDeleteCapacityCharges.PUBLICATION.scaled(if (missingP) 1L else 0L) +
            OwnerDeleteCapacityCharges.RESERVATION.scaled(if (missingL) 1L else 0L)
        return charge to (if (missingL) promised else ComplaintCapacityVector.ZERO)
    }
    internal fun materializedCounts(paid: JdbcComplaintCapacityStore.LockedAdminDeleteApply, selected: JdbcTemplate): TestAdminDeleteMaterializedCounts {
        requireSelected(selected); check(stage === Stage.SETTLING && allocation === paid); return checkNotNull(counts)
    }
    internal fun remainingReserve(paid: JdbcComplaintCapacityStore.LockedAdminDeleteApply, selected: JdbcTemplate): ComplaintCapacityVector {
        requireSelected(selected); check(stage === Stage.SETTLING && allocation === paid); return reserve?.remaining ?: promised
    }
    internal fun recordProgress(paid: JdbcComplaintCapacityStore.LockedAdminDeleteApply, selected: JdbcTemplate, use: ComplaintCapacityVector) {
        requireSelected(selected); check(stage === Stage.SETTLING && allocation === paid)
        val prior = reserve?.used ?: ComplaintCapacityVector.ZERO
        val total = prior + use
        check(total != promised && total.fitsWithin(promised))
        checkWrite()
        check(jdbc.update(AdminDeletePersistenceSql.SPEND_RECOVERY, OwnerDeleteRows.array(total), primary.route.eventId, scope.id, OwnerDeleteRows.array(promised),
            if (prior.isZero()) null else OwnerDeleteRows.array(prior), reserve?.convertedAt?.let(Timestamp::from)) == 1)
    }
    internal fun requireCapacityWrite(paid: JdbcComplaintCapacityStore.LockedAdminDeleteApply, selected: JdbcTemplate) { requireSelected(selected); check(allocation === paid && stage in setOf(Stage.COUNTERS, Stage.SETTLING)); checkWrite() }
    internal fun auditConnection(paid: JdbcComplaintCapacityStore.LockedAdminDeleteApply, selected: JdbcTemplate, entry: CountedAdminDeleteAuditEntry, index: Int): Connection {
        requireAuditWrite(paid, selected); check(entry.outcome === auditOutcomes[index] && entry.createdAt == appliedAt); return phase.adminDelete.connection(this, selected)
    }
    internal fun requireAuditWrite(paid: JdbcComplaintCapacityStore.LockedAdminDeleteApply, selected: JdbcTemplate) { requireSelected(selected); check(stage === Stage.AUDIT && allocation === paid && paid.settledFor(this)); checkWrite() }
    private fun requireScope(id: UUID, test: Boolean) { check(id == scope.id && test) }
    private fun retained() { phase.adminDelete.requireRetained(this, jdbc); graph.requireDeletion(jdbc) }
    private fun requireSelected(jdbc: JdbcTemplate) { retained(); check(this.jdbc === jdbc) }
    private fun checkWrite() = phase.adminDelete.checkWrite(this, jdbc)
    internal fun failed(problem: Throwable): Nothing { stage = Stage.FAILED; phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
    private enum class Stage { RETAINED, RECEIPT, PUBLICATION, RESERVATION, COUNTERS_READY, COUNTERS, BOOKKEEPING, DOMAIN, SETTLING, AUDIT, COMPLETING, COMPLETE, FAILED }
    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService, graph: TestOwnerDeleteLocalGraphV1,
            codec: TestOwnerDeleteVerificationCodecV1, issuer: Any, input: TestAdminDeleteApplyInputV1): ComplaintAdminDeleteApplyOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.adminDelete.requireOperation(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY)
                phase.requireTestRunAdminDeleteApply(graph, jdbc, input)
                val selected = input as? CapturedTestAdminDeleteApply ?: error("Original verified input required")
                check(selected.issuer === issuer && selected.event.belongsTo(graph.routing))
                return ComplaintAdminDeleteApplyOperation(phase, jdbc, graph, codec, selected).also { phase.adminDelete.retain(it, jdbc); it.execute(capacity, audit) }
            } catch (problem: Throwable) { phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
    }
}
