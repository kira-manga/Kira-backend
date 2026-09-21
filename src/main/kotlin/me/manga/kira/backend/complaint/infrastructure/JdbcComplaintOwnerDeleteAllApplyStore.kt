package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAllAuditEntry
import me.manga.kira.backend.audit.domain.OwnerDeleteAllAuditOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteAllResponse
import me.manga.kira.backend.complaint.domain.InstallationCredentialSnapshot
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.InstallationRecoveryDecision
import me.manga.kira.backend.complaint.domain.InstallationRecoveryEvidence
import me.manga.kira.backend.complaint.domain.InstallationRecoveryReducer
import me.manga.kira.backend.complaint.domain.InstallationRecoverySnapshot
import me.manga.kira.backend.complaint.domain.InstallationReservationSnapshot
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.RecoveryContentEffect
import me.manga.kira.backend.complaint.domain.RecoveryCredentialEffect
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationRecordV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainPersistenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Dormant, authenticated existing-pair continuation only. Neither a controller, scanner nor a
 * full-D/J runtime authority. Provider work and private-proof capture precede the privacy phase.
 */
internal class JdbcComplaintOwnerDeleteAllApplyStore private constructor(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    private val routing: OwnerDeleteAllJournalBindingV1,
    private val policy: ComplaintCapacityPolicyV1,
    private val verification: JdbcComplaintOwnerDeleteAllVerificationStore,
    private val controls: OwnerDeleteAllControlBinding,
) {
    constructor(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService,
        desired: ComplaintInstallationDesiredSettings.Configured, routing: VersionBoundComplaintJournalRouting,
        policy: ComplaintCapacityPolicyV1, catalog: CatalogCommonHeadEvidence, verification: JdbcComplaintOwnerDeleteAllVerificationStore,
        process: OwnerDeleteAllProcessBinding? = null) : this(jdbc, capacity, audit, verification.routing, policy, verification,
            OwnerDeleteAllControlBinding(desired, routing, catalog, process)) {
        this.routing.requireLive(routing)
        process?.requireDeletion(jdbc)
        process?.requirePolicy(policy)
    }
    constructor(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService,
        graph: TestOwnerDeleteLocalGraphV1, verification: JdbcComplaintOwnerDeleteAllVerificationStore) :
        this(jdbc, capacity, audit, verification.routing, graph.policy, verification, OwnerDeleteAllControlBinding(graph)) {
        routing.requireTest(graph.routing)
        graph.requireDeletion(jdbc)
    }
    private val issuer = Any()
    private val codec = OwnerDeleteAllVerificationCodecV1(routing)

    fun capture(work: CommittedOwnerDeleteAllWork, proof: CommittedOwnerDeleteAllVerificationV1): OwnerDeleteAllApplyInputV1 {
        requireConnectionFree()
        val event = verification.verifiedEvent(proof)
        check(event.belongsTo(routing))
        val bytes = proof.verificationBytes()
        val hash = proof.verificationHash()
        check(bytes.size in 1..65536 && hash.size == 32 && MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(bytes)))
        val record = codec.parse(bytes, event)
        check(record.eventId == proof.eventId && record.objectVersion == proof.objectVersion && record.ciphertextSha256 == proof.ciphertextSha256)
        check(Instant.parse(record.objectCreatedAt) == proof.objectCreatedAt)
        check(Instant.parse(record.retainUntil) == proof.retainUntil && Instant.parse(record.verifiedAt) == proof.verifiedAt)
        val verifier = verification.authenticatedVerifier(work, proof)
        return try {
            CapturedOwnerDeleteAllPrimaryApply(issuer, routing, event, record, bytes, hash, verifier, work, proof)
        } finally {
            verifier.fill(0)
        }
    }

    /** Only this registered drain's freshly GET/decrypted, physically released readback can enter. */
    internal fun captureRegisteredInventoryRecovery(original: TestRunOrdinaryDrainV1): OwnerDeleteAllApplyInputV1 {
        requireConnectionFree()
        val graph = checkNotNull(controls.testGraph)
        check(graph.recoveryRegistration === original.registration)
        val readback = original.ownedRecoveryReadback(this, graph, jdbc)
        readback.requireOriginal(original)
        val event = routing.fromTest(readback.event)
        val record = codec.observed(readback)
        val bytes = codec.canonicalBytes(record)
        return CapturedOwnerDeleteAllInventoryApply(issuer, routing, event, record, bytes,
            MessageDigest.getInstance("SHA-256").digest(bytes), original)
    }

    /** Native ACTIVE delivery only. No primary work, verifier or terminal inventory original is fabricated. */
    internal fun captureRegisteredQueueRecovery(original: TestActiveOwnerDeleteQueueV1): OwnerDeleteAllApplyInputV1 {
        requireConnectionFree()
        val graph = checkNotNull(controls.testGraph)
        check(graph.recoveryRegistration === original.registration)
        val readback = original.ownedRecoveryReadback(this, graph, jdbc)
        readback.requireOriginal(original)
        val event = routing.fromTest(readback.event)
        val record = codec.observed(readback)
        val bytes = codec.canonicalBytes(record)
        return CapturedOwnerDeleteAllQueueApply(issuer, routing, event, record, bytes,
            MessageDigest.getInstance("SHA-256").digest(bytes), original)
    }

    fun apply(input: OwnerDeleteAllApplyInputV1): ComplaintOwnerDeleteAllApplyOperation =
        ComplaintOwnerDeleteAllApplyOperation.capture(jdbc, capacity, audit, controls, policy, codec, issuer, routing, input)

    override fun toString(): String = "JdbcComplaintOwnerDeleteAllApplyStore(dormant,redacted)"
}

internal sealed interface OwnerDeleteAllApplyInputV1

/** Discrimination only; neither a caller-set permission nor an HTTP response mapping. */
internal sealed interface OwnerDeleteAllApplyOutcomeV1 : OwnerDeleteAllOutcome

/** Released only after the original caller's known commit AND original-holder cleanup. No content/identity is returned. */
internal sealed interface CommittedOwnerDeleteAllApplyV1 :
    OwnerDeleteAllApplyOutcomeV1,
    ComplaintOwnerDeleteAllResponse.Completed {
    val completedAt: Instant
    val expiresAt: Instant
    val responseStatus: Int get() = 204
}

/** Genuine committed VERIFY custody, but no released APPLY result. Deliberately no erasure/status/proof fields. */
internal sealed interface OwnerDeleteAllReconciliationPendingV1 :
    OwnerDeleteAllApplyOutcomeV1,
    ComplaintOwnerDeleteAllResponse.Pending

private sealed class CapturedOwnerDeleteAllApply(
    private val issuer: Any,
    private val routing: OwnerDeleteAllJournalBindingV1,
    val event: OwnerDeleteAllJournalEventV1,
    val record: OwnerDeleteAllVerificationRecordV1,
    bytes: ByteArray,
    hash: ByteArray,
) : OwnerDeleteAllApplyInputV1 {
    val verificationBytes = bytes.copyOf()
    val verificationHash = hash.copyOf()
    val eventBytes = event.canonicalBytes()
    val semanticHash: ByteArray = HexFormat.of().parseHex(record.semanticSha256)
    val ciphertextHash: ByteArray = HexFormat.of().parseHex(record.ciphertextSha256)
    val fingerprint: ByteArray = Base64.getUrlDecoder().decode(event.tuple.encodedFingerprint())
    val writer: UUID = UUID.fromString(record.writerGeneration)
    val targets: List<UUID> = event.complaintIds()
    val retainedUntil: Instant = Instant.parse(record.retainUntil)
    val verifiedAt: Instant = Instant.parse(record.verifiedAt)
    val credentialVersion = checkNotNull(event.tuple.credentialVersion).also { check(it > 0) }

    fun requireOwned(selectedIssuer: Any, selectedRouting: OwnerDeleteAllJournalBindingV1) {
        check(issuer === selectedIssuer && routing === selectedRouting && event.belongsTo(selectedRouting))
    }

    override fun toString(): String = "OwnerDeleteAllApplyInputV1(private-committed-proof,redacted)"
}

private class CapturedOwnerDeleteAllPrimaryApply(issuer: Any, routing: OwnerDeleteAllJournalBindingV1,
    event: OwnerDeleteAllJournalEventV1, record: OwnerDeleteAllVerificationRecordV1, bytes: ByteArray, hash: ByteArray,
    verifier: ByteArray, val work: CommittedOwnerDeleteAllWork, val proof: CommittedOwnerDeleteAllVerificationV1,
) : CapturedOwnerDeleteAllApply(issuer, routing, event, record, bytes, hash) {
    val verifier = verifier.copyOf().also { check(it.size == 32) }
}

/** No work/proof/verifier is fabricated from an inventory. This is a different, private issuer path. */
private class CapturedOwnerDeleteAllInventoryApply(issuer: Any, routing: OwnerDeleteAllJournalBindingV1,
    event: OwnerDeleteAllJournalEventV1, record: OwnerDeleteAllVerificationRecordV1, bytes: ByteArray, hash: ByteArray,
    val original: TestRunOrdinaryDrainV1,
) : CapturedOwnerDeleteAllApply(issuer, routing, event, record, bytes, hash)

/** Separate private capture: exact retained VERIFIED primary or completed replay, not missing-N/P/L recovery. */
private class CapturedOwnerDeleteAllQueueApply(issuer: Any, routing: OwnerDeleteAllJournalBindingV1,
    event: OwnerDeleteAllJournalEventV1, record: OwnerDeleteAllVerificationRecordV1, bytes: ByteArray, hash: ByteArray,
    val original: TestActiveOwnerDeleteQueueV1,
) : CapturedOwnerDeleteAllApply(issuer, routing, event, record, bytes, hash)

internal class OwnerDeleteAllMaterializedCountsV1(val removed: Int, val resources: Int, val installations: Int,
    val applied: Int, val summary: Int) {
    init { check(removed in 0..100 && resources in 0..100 && installations in 0..1 && applied in 0..1 && summary in 0..1) }
    val audits get() = removed + summary
    val use get() = ComplaintCapacityCharges.RESOURCE_ID.scaled(resources.toLong()) +
        ComplaintCapacityCharges.INSTALLATION_ID.scaled(installations.toLong()) +
        OwnerDeleteAllCapacityCharges.APPLIED.scaled(applied.toLong()) + ComplaintCapacityCharges.AUDIT.scaled(audits.toLong())
}

/** Fixed lock/mutation sequence; there is no caller-provided callback, vector, row list or alternate holder. */
internal class ComplaintOwnerDeleteAllApplyOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val controls: OwnerDeleteAllControlBinding,
    private val policy: ComplaintCapacityPolicyV1,
    private val codec: OwnerDeleteAllVerificationCodecV1,
    private val routing: OwnerDeleteAllJournalBindingV1,
    private val observed: CapturedOwnerDeleteAllApply,
) {
    private val inventory = observed as? CapturedOwnerDeleteAllInventoryApply
    private val queue = observed as? CapturedOwnerDeleteAllQueueApply
    private fun primary() = observed as? CapturedOwnerDeleteAllPrimaryApply ?: error("Committed primary input required")
    private val sql = if (controls.scope.testOnly) OwnerDeleteAllApplySql.test(controls.scope) else OwnerDeleteAllApplySql.live
    private var stage = Stage.RETAINED
    private var replay = false
    private var recovery: OwnerDeleteAllApplyRows.Recovery? = null
    private var allocation: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply? = null
    private var completion: Instant? = null
    private var expiry: Instant? = null
    private var reconstructed = 0
    private var current: List<UUID> = emptyList()
    private var resources: List<OwnerDeleteAllApplyRows.Resource> = emptyList()
    private val removed = ArrayList<OwnerDeleteAllApplyRows.Content>()
    private var released: CommittedOwnerDeleteAllApplyV1? = null
    private var pending: OwnerDeleteAllReconciliationPendingV1? = null
    private var inventoryCounts: OwnerDeleteAllMaterializedCountsV1? = null
    private var inventoryAt: Instant? = null
    private var retainedVerification: OwnerDeleteAllApplyRows.Verification? = null

    fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE &&
        completion != null && expiry != null && allocation?.completedFor(this) == true

    val result: CommittedOwnerDeleteAllApplyV1
        get() {
            check(inventory == null && queue == null)
            phase.ownerDeleteAllApply.requireCommitted(this)
            requireConnectionFree()
            return released ?: Released(checkNotNull(completion), checkNotNull(expiry)).also { released = it }
        }

    val continuationResult: OwnerDeleteAllApplyOutcomeV1
        get() {
            check(inventory == null && queue == null)
            if (!phase.ownerDeleteAllApply.reconciliationPending(this)) return result
            requireConnectionFree()
            return pending ?: ReconciliationPending(primary().work, primary().proof).also { pending = it }
        }

    internal fun requireRegisteredContinuation(original: TestRunOwnerDeleteAllContinuationV1) {
        check(inventory == null && queue == null); original.requireApplyInput(observed)
    }
    internal fun requireRegisteredInventoryRecovery(original: TestRunOrdinaryDrainV1) {
        check(inventory?.original === original && controls.testGraph?.recoveryRegistration === original.registration)
        original.requireRecoveryInput(observed)
    }
    internal fun requireRegisteredQueueRecovery(original: TestActiveOwnerDeleteQueueV1) {
        check(queue?.original === original && inventory == null && controls.testGraph?.recoveryRegistration === original.registration)
        original.requireRecoveryInput(observed)
    }
    internal fun requireRecovered() {
        check(inventory != null || queue != null)
        phase.ownerDeleteAllApply.requireCommitted(this); requireConnectionFree()
    }

    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        if (inventory != null) { executeInventory(capacity, audit); return }
        requireRetained()
        check(stage === Stage.RETAINED)
        stage = Stage.CONTROL
        controls.lock(jdbc, authorizingPath = false).requireContinuation(observed.event.tuple.epoch, prepared = false)
        val receipt = lockProof()
        stage = Stage.RESERVATION
        recovery = jdbc.query(sql.LOCK_RECOVERY, { row, _ -> OwnerDeleteAllApplyRows.recovery(row) }, receipt.reference).single()
        check(checkNotNull(recovery).eventId == observed.record.eventId && checkNotNull(recovery).promise == OwnerDeleteAllCapacityCharges.RECOVERY)
        if (queue != null) requireQueueHistory(receipt)
        stage = Stage.COUNTERS_READY
        val paid = capacity.lockForOwnerDeleteAllApply(this)
        stage = Stage.INSTALLATION
        controls.lockRun(jdbc, false)
        val identity = observed.event.tuple.actorId
        val installation = jdbc.query(sql.LOCK_INSTALLATION, { row, _ -> OwnerDeleteAllApplyRows.installation(row) }, identity).single()
        val credential = jdbc.query(sql.LOCK_CREDENTIAL, { row, _ -> OwnerDeleteAllApplyRows.credential(row) }, identity).single()
        requirePair(installation, credential, receipt)
        current = jdbc.query(sql.OWNER_TARGETS, { row, _ -> row.getObject(1, UUID::class.java) }, identity)
        check(current.size <= 100 && current.distinct().size == current.size && (!replay || current.isEmpty()))
        val now = databaseNow()
        requireRetention(now)
        check(checkNotNull(recovery).convertedAt?.isAfter(now) != true)
        completion = if (replay) checkNotNull(receipt.completedAt) else now
        expiry = if (replay) checkNotNull(receipt.expiresAt) else now.plus(RETRY_RETENTION)
        check(!checkNotNull(completion).isAfter(now) && checkNotNull(expiry).isAfter(now))
        check(!checkNotNull(completion).isBefore(if (queue == null) observed.verifiedAt else checkNotNull(retainedVerification).verifiedAt))
        lockResources()
        val content = lockContent()
        requireReducer(installation, credential, content.isNotEmpty())
        if (replay) {
            requireApplied(checkNotNull(completion))
            stage = Stage.SETTLING
            paid.settle(this)
        } else {
            erase(content, credential)
            stage = Stage.SETTLING
            paid.settle(this)
            complete(receipt)
            stage = Stage.AUDIT
            removed.forEach { audit.recordOwnerDeleteAll(OwnerDeleteAllAuditOutcome.Removed(it.id, it.version), paid, checkNotNull(completion)) }
            audit.recordOwnerDeleteAll(
                OwnerDeleteAllAuditOutcome.InstallationCompleted(nextCredentialVersion(), removed.size, reconstructed),
                paid,
                checkNotNull(completion),
            )
        }
        requireRetained()
        check(paid.completedFor(this))
        requireFinalState()
        queue?.original?.requireAppliedCurrent(this, jdbc)
        stage = Stage.COMPLETE
    }

    private fun lockProof(): OwnerDeleteAllApplyRows.Receipt {
        requireRetained()
        stage = Stage.RECEIPT
        val tuple = observed.event.tuple
        val receipt = jdbc.query(sql.LOCK_RECEIPTS, { row, _ -> OwnerDeleteAllApplyRows.receipt(row) }, tuple.actorId).single()
        check(receipt.key == tuple.operationKey && receipt.version == tuple.credentialVersion && receipt.reference == observed.record.eventId)
        check(MessageDigest.isEqual(receipt.fingerprint, observed.fingerprint))
        stage = Stage.PUBLICATION
        val publication = jdbc.query(
            sql.LOCK_PUBLICATION,
            { row, _ -> OwnerDeleteAllApplyRows.publication(row) },
            receipt.reference,
        ).single()
        requireRetained()
        check(publication.eventId == observed.record.eventId && publication.writer == controls.writer && publication.writer == observed.writer)
        check(
            publication.epoch == tuple.epoch && publication.routingKeyId == observed.record.routingKeyId && publication.objectKey == observed.record.objectKey,
        )
        check(publication.targetCount == observed.targets.size && publication.bytes.contentEquals(observed.eventBytes))
        check(MessageDigest.isEqual(publication.hash, observed.semanticHash) && publication.createdAt == receipt.authorizedAt)
        val proof = publication.verification
        val parsed = codec.parse(proof.bytes, observed.event)
        if (queue == null) {
            check(parsed == observed.record && proof.bytes.contentEquals(observed.verificationBytes))
            check(MessageDigest.isEqual(proof.verificationHash, observed.verificationHash))
            check(proof.retainUntil == observed.retainedUntil && proof.verifiedAt == observed.verifiedAt)
        } else {
            // Fresh GET time is not the earlier committed VERIFY time. Bind the actual native
            // event/version/wire/creation, retain historical proof bytes for the existing CAS,
            // and require retention never to shrink. Stored bytes alone grant no recovery right.
            check(parsed.objectVersion == observed.record.objectVersion && parsed.ciphertextSha256 == observed.record.ciphertextSha256 &&
                parsed.objectCreatedAt == observed.record.objectCreatedAt)
            check(proof.retainUntil == Instant.parse(parsed.retainUntil) && proof.verifiedAt == Instant.parse(parsed.verifiedAt) &&
                !observed.retainedUntil.isBefore(proof.retainUntil) && !observed.verifiedAt.isBefore(proof.verifiedAt))
        }
        check(MessageDigest.isEqual(proof.hash, observed.ciphertextHash))
        check(proof.version == parsed.objectVersion && proof.createdAt == Instant.parse(parsed.objectCreatedAt))
        retainedVerification = proof
        val now = databaseNow()
        requireRetention(now)
        check(!publication.createdAt.isAfter(now) && !publication.createdAt.isAfter(proof.verifiedAt))
        if (queue != null) check(proof.retainUntil.isAfter(now))
        replay = receipt.state == "COMPLETED"
        check(publication.state == if (replay) "APPLIED" else "VERIFIED")
        check(publication.appliedAt == receipt.completedAt)
        if (replay) {
            val external = checkNotNull(receipt.external)
            check(external.eventId == observed.record.eventId && external.epoch == tuple.epoch && external.version == observed.record.objectVersion)
            check(MessageDigest.isEqual(external.hash, observed.ciphertextHash))
        }
        return receipt
    }

    /** Bounded exact-primary grammar. ALL aliases and old-snapshot domain/N/P/L repair remain unfinished. */
    private fun requireQueueHistory(receipt: OwnerDeleteAllApplyRows.Receipt) {
        checkNotNull(queue)
        val reserve = checkNotNull(recovery)
        val routes = routing.derive(observed.event.tuple)
        check(routes.size == 4)
        val family = jdbc.query(sql.QUEUE_APPLIED_FAMILY, { row, _ ->
            OwnerDeleteAllApplyRows.valid(row)
            check(row.getString("event_id") == observed.record.eventId && row.getString("object_key") == observed.record.objectKey &&
                row.getString("object_version") == observed.record.objectVersion &&
                OwnerDeleteAllApplyRows.bytes(row, "ciphertext_hash").contentEquals(observed.ciphertextHash) &&
                row.getObject("writer_generation", UUID::class.java) == observed.writer &&
                OwnerDeleteAllApplyRows.long(row, "journal_epoch") == observed.event.tuple.epoch &&
                OwnerDeleteAllApplyRows.long(row, "target_count") == observed.targets.size.toLong() &&
                OwnerDeleteAllApplyRows.instant(row, "applied_at") == receipt.completedAt)
        }, routes.joinToString(",", "{", "}") { it.eventId })
        if (replay) {
            val resourcesUsed = reserve.used[ComplaintCapacityCounter.RESOURCE_IDS]
            val auditsUsed = reserve.used[ComplaintCapacityCounter.AUDIT_ROWS]
            check(family.size == 1 && reserve.state == "PARTIAL" && resourcesUsed in 0..100 && auditsUsed in 1..101 &&
                reserve.used == OwnerDeleteAllCapacityCharges.APPLIED + ComplaintCapacityCharges.RESOURCE_ID.scaled(resourcesUsed) +
                    ComplaintCapacityCharges.AUDIT.scaled(auditsUsed) &&
                !checkNotNull(reserve.convertedAt).isBefore(checkNotNull(receipt.completedAt)))
        } else {
            check(family.isEmpty() && reserve.state == "RESERVED" && reserve.used.isZero() && reserve.convertedAt == null)
        }
    }

    /** Existing N/P/L only; all native versions are authenticated before this privacy transaction. */
    private fun executeInventory(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        val original = checkNotNull(inventory).original
        val nativeSql = OwnerDeleteAllInventorySqlV1(controls.scope)
        requireRetained()
        stage = Stage.CONTROL
        controls.lock(jdbc, false).requireContinuation(observed.event.tuple.epoch, prepared = false)
        stage = Stage.RECEIPT
        val tuple = observed.event.tuple
        val receipt = jdbc.query(sql.LOCK_RECEIPTS, { row, _ -> OwnerDeleteAllApplyRows.receipt(row) }, tuple.actorId).single()
        check(receipt.state == "COMPLETED" && receipt.key == tuple.operationKey && receipt.version == tuple.credentialVersion &&
            receipt.fingerprint.contentEquals(observed.fingerprint))
        stage = Stage.PUBLICATION
        val routes = routing.derive(tuple)
        check(routes.size == 4)
        val publications = routes.sortedBy { it.eventId }.mapNotNull { route ->
            jdbc.query(sql.LOCK_PUBLICATION, { row, _ -> OwnerDeleteAllApplyRows.publication(row) }, route.eventId).singleOrNull()
        }
        val publication = publications.single()
        val primary = routing.restore(publication.bytes, publication.routingKeyId)
        val p = primary.tuple
        check(p.scope == tuple.scope && p.eventKind == tuple.eventKind && p.actorKind == tuple.actorKind &&
            p.actorId == tuple.actorId && p.credentialVersion == tuple.credentialVersion && p.operationKey == tuple.operationKey &&
            p.epoch == tuple.epoch && p.encodedFingerprint() == tuple.encodedFingerprint() && primary.complaintIds() == observed.targets)
        check(publication.eventId == primary.route.eventId && publication.objectKey == primary.route.objectKey &&
            publication.writer == controls.writer && publication.epoch == tuple.epoch && publication.targetCount == observed.targets.size &&
            publication.hash.contentEquals(HexFormat.of().parseHex(primary.semanticSha256)) && publication.state == "APPLIED" &&
            publication.eventId == receipt.reference && publication.createdAt == receipt.authorizedAt && publication.appliedAt == receipt.completedAt)
        val verified = publication.verification
        val record = codec.parse(verified.bytes, primary)
        check(record.objectVersion == verified.version && record.ciphertextSha256 == HexFormat.of().formatHex(verified.hash) &&
            Instant.parse(record.objectCreatedAt) == verified.createdAt && Instant.parse(record.retainUntil) == verified.retainUntil &&
            Instant.parse(record.verifiedAt) == verified.verifiedAt)
        val external = checkNotNull(receipt.external)
        check(external.eventId == publication.eventId && external.epoch == publication.epoch && external.version == verified.version &&
            external.hash.contentEquals(verified.hash))
        if (primary.route == observed.event.route) check(publication.bytes.contentEquals(observed.eventBytes) && verified.hash.contentEquals(observed.ciphertextHash))
        stage = Stage.RESERVATION
        // Use the native terminal grammar here without widening the unchanged lower PARTIAL reader.
        val strict = jdbc.query(TestOrdinaryDrainSqlV1.recovery, { row, _ ->
            TestOrdinaryDrainRowsV1.Recovery(row, original, publication.eventId, "OWNER_DELETE_ALL")
        }, publication.eventId).single()
        recovery = OwnerDeleteAllApplyRows.Recovery(publication.eventId, strict.state, strict.promise, strict.used, strict.lastAppliedAt)
        val reserve = checkNotNull(recovery)
        check(reserve.state == "PARTIAL" && reserve.promise == OwnerDeleteAllCapacityCharges.RECOVERY)
        val family = jdbc.query(TestOrdinaryDrainSqlV1.appliedFamily, { row, _ ->
            TestOrdinaryDrainPersistenceV1.Applied.read(row, original, hasTime = true)
        }, routes.joinToString(",", "{", "}") { it.eventId })
        check(family.size in 1..4 && family.map { it.locator }.distinct().size == family.size &&
            reserve.used[ComplaintCapacityCounter.JOURNAL_APPLIED] == family.size.toLong())
        family.forEach { applied ->
            val route = routes.single { it.eventId == applied.eventId }
            check(applied.key == route.objectKey && applied.epoch == tuple.epoch && applied.targetCount == observed.targets.size &&
                applied.kind == "OWNER_DELETE_ALL" && checkNotNull(applied.at) in verified.verifiedAt..checkNotNull(reserve.convertedAt))
            if (applied.key == observed.record.objectKey) check(applied.ciphertext == observed.record.ciphertextSha256)
        }
        check(family.single { it.key == primary.route.objectKey && it.version == verified.version }.ciphertext == HexFormat.of().formatHex(verified.hash))
        val exact = family.any { it.locator == (observed.record.objectKey to observed.record.objectVersion) }
        check(exact || family.size < 4) // The existing promise pays FOUR exact versions total, not four per key.
        stage = Stage.COUNTERS_READY
        val paid = capacity.lockForOwnerDeleteAllApply(this)
        stage = Stage.INSTALLATION
        controls.lockRun(jdbc, false)
        val installation = jdbc.query(nativeSql.installation, { row, _ -> OwnerDeleteAllApplyRows.installation(row) }, tuple.actorId).singleOrNull()
        val credential = jdbc.query(nativeSql.credential, { row, _ -> OwnerDeleteAllApplyRows.credential(row) }, tuple.actorId).singleOrNull()
        current = jdbc.query(sql.OWNER_TARGETS, { row, _ -> row.getObject(1, UUID::class.java) }, tuple.actorId)
        check(current.size <= 100 && current.distinct().size == current.size)
        val now = databaseNow()
        inventoryAt = now
        requireRetention(now)
        check(verified.retainUntil.isAfter(now) && verified.verifiedAt <= checkNotNull(receipt.completedAt) &&
            checkNotNull(receipt.completedAt) <= now && checkNotNull(reserve.convertedAt) <= now)
        completion = receipt.completedAt; expiry = receipt.expiresAt
        val identity = ScopedInstallationId(tuple.actorId, controls.scope)
        val decision = InstallationRecoveryReducer.reduce(InstallationRecoverySnapshot(identity,
            installation?.let { InstallationReservationSnapshot(identity, InstallationIdentityState.valueOf(it.state)) },
            credential?.let { InstallationCredentialSnapshot(identity, InstallationCredentialState.valueOf(it.state)) },
            hasOwnedContent = current.isNotEmpty()), InstallationRecoveryEvidence.DeleteAll(identity)) as? InstallationRecoveryDecision.Apply
            ?: error("ALL recovery disposition required")
        check(decision.identityState === InstallationIdentityState.DELETED && decision.contentEffect === RecoveryContentEffect.ERASE_ALL_OWNED_CONTENT)
        credential?.let {
            check(it.credentialVersion == if (it.state == "DELETED") nextCredentialVersion() else observed.credentialVersion)
            if (it.state == "DELETED") check(it.deletedAt == installation?.terminalAt && checkNotNull(it.deletedAt) in checkNotNull(completion)..now)
        }
        val installationCount = if (decision.reserveIdentityCapacity) 1 else 0
        val time = Timestamp.from(now)
        if (decision.reserveIdentityCapacity) {
            check(OwnerDeleteAllMaterializedCountsV1(current.size, 0, 1, if (exact) 0 else 1, 1).use.fitsWithin(reserve.remaining))
            checkWrite(); check(jdbc.update(nativeSql.reconstructInstallation, tuple.actorId, time, time) == 1)
        }
        stage = Stage.RESOURCES
        val union = (observed.targets + current).distinct().sortedBy(UUID::toString)
        check(union.size <= 200)
        val missing = ArrayList<UUID>()
        resources = union.map { id ->
            jdbc.query(sql.LOCK_RESOURCE, { row, _ -> OwnerDeleteAllApplyRows.resource(row) }, id).singleOrNull()?.also {
                check(it.deletedAt?.isAfter(now) != true)
            } ?: run {
                check(id in observed.targets && id !in current); missing.add(id)
                check(OwnerDeleteAllMaterializedCountsV1(current.size, missing.size, installationCount, if (exact) 0 else 1, 1).use.fitsWithin(reserve.remaining))
                // A missing reservation is locked by its INSERT in the same UUID-ordered pass,
                // before any content lock; never a late second lock/insertion sweep.
                checkWrite(); check(jdbc.update(sql.RECONSTRUCT_RESOURCE, id, time, time) == 1)
                OwnerDeleteAllApplyRows.Resource(id, "DELETED", now)
            }
        }
        stage = Stage.CONTENT
        val content = if (union.isEmpty()) emptyList() else jdbc.query(sql.LOCK_CONTENT, { row, _ -> OwnerDeleteAllApplyRows.content(row) }, uuidArray(union))
        check(content.map { it.id } == current)
        content.forEach { check(it.owner == tuple.actorId && resources.single { resource -> resource.id == it.id }.state in setOf("LIVE", "DELETION_PENDING")) }
        val effects = !exact || content.isNotEmpty() || missing.isNotEmpty() || installation?.state != "DELETED" || resources.any { it.state != "DELETED" }
        inventoryCounts = if (effects) OwnerDeleteAllMaterializedCountsV1(content.size, missing.size, installationCount, if (exact) 0 else 1, 1) else null
        check((inventoryCounts?.use ?: ComplaintCapacityVector.ZERO).fitsWithin(reserve.remaining))
        stage = Stage.ERASURE
        if (!decision.reserveIdentityCapacity && installation?.state != "DELETED") {
            checkWrite(); check(jdbc.update(nativeSql.deleteInstallation, time, tuple.actorId) == 1)
        }
        if (decision.credentialEffect === RecoveryCredentialEffect.COMPLETE_DELETE_ALL) {
            val selected = checkNotNull(credential)
            checkWrite(); check(jdbc.update(nativeSql.deleteCredential, time, Timestamp.from(now.plus(RETRY_RETENTION)), tuple.actorId,
                selected.credentialVersion, selected.rowVersion, selected.verifier) == 1)
        } else check(decision.credentialEffect === RecoveryCredentialEffect.PRESERVE)
        content.forEach { row ->
            checkWrite()
            val actual = jdbc.query(sql.DELETE_CONTENT, { value, _ -> value.getObject("id", UUID::class.java) to value.getLong("version") },
                row.id, tuple.actorId, row.version).single()
            check(actual == (row.id to row.version)); removed.add(row)
        }
        resources.filter { it.state != "DELETED" }.forEach { checkWrite(); check(jdbc.update(sql.TOMBSTONE_RESOURCE, time, it.id) == 1) }
        if (!exact) {
            checkWrite(); check(jdbc.update(sql.INSERT_APPLIED, observed.record.objectKey, observed.record.objectVersion, observed.record.eventId,
                observed.ciphertextHash, observed.writer, tuple.epoch, observed.targets.size, time) == 1)
        }
        stage = Stage.SETTLING
        paid.settle(this)
        stage = Stage.AUDIT
        inventoryCounts?.let { counts ->
            removed.forEach { audit.recordOwnerDeleteAll(OwnerDeleteAllAuditOutcome.Removed(it.id, it.version), paid, now) }
            audit.recordOwnerDeleteAll(OwnerDeleteAllAuditOutcome.RecoveryApplied(observed.record.eventId, counts.removed, counts.resources, counts.installations), paid, now)
        }
        check(paid.completedFor(this))
        requireFinalState()
        original.recordRecoveredVersion(this, jdbc)
        stage = Stage.COMPLETE
    }

    private fun requirePair(
        installation: OwnerDeleteAllApplyRows.Installation,
        credential: OwnerDeleteAllApplyRows.Credential,
        receipt: OwnerDeleteAllApplyRows.Receipt,
    ) {
        requireRetained()
        val expected = if (replay) "DELETED" else "DELETION_PENDING"
        check(installation.state == expected && credential.state == expected)
        check(credential.credentialVersion == if (replay) nextCredentialVersion() else observed.credentialVersion)
        check(credential.rowVersion > 0 && (!replay || credential.rowVersion > 1))
        if (queue == null) check(MessageDigest.isEqual(credential.verifier, primary().verifier))
        // The queue has the authenticated immutable ALL event and exact committed N/P/L, not a
        // current request secret. It can finish only this existing pending/deleted pair/version;
        // no missing credential, replacement verifier, primary work or new authorization is minted.
        check(installation.terminalAt == receipt.completedAt && credential.deletedAt == receipt.completedAt && credential.expiresAt == receipt.expiresAt)
    }

    private fun lockResources() {
        requireRetained()
        stage = Stage.RESOURCES
        val eventIds = observed.targets.toSet()
        val union = (eventIds + current).sortedBy(UUID::toString)
        check(eventIds.size <= 100 && union.size <= 200)
        resources = union.map { id ->
            requireRetained()
            val found = jdbc.query(sql.LOCK_RESOURCE, { row, _ -> OwnerDeleteAllApplyRows.resource(row) }, id).singleOrNull()
            if (found != null) {
                check(found.id == id && found.deletedAt?.isAfter(checkNotNull(completion)) != true)
                check(!replay || found.state == "DELETED")
                found
            } else {
                check(!replay && id in eventIds && id !in current)
                requireFutureUse(reconstructed + 1, current.size)
                checkWrite()
                val time = Timestamp.from(checkNotNull(completion))
                check(jdbc.update(sql.RECONSTRUCT_RESOURCE, id, time, time) == 1)
                reconstructed++
                OwnerDeleteAllApplyRows.Resource(id, "DELETED", checkNotNull(completion))
            }
        }
    }

    private fun lockContent(): List<OwnerDeleteAllApplyRows.Content> {
        requireRetained()
        stage = Stage.CONTENT
        val rows = if (resources.isEmpty()) {
            emptyList()
        } else {
            jdbc.query(sql.LOCK_CONTENT, { row, _ -> OwnerDeleteAllApplyRows.content(row) }, uuidArray(resources.map { it.id }))
        }
        check(rows.map { it.id } == current)
        rows.forEach { row ->
            check(row.owner == observed.event.tuple.actorId && row.kind in setOf("REPORT", "REPLY") && row.version > 0)
            check(resources.single { it.id == row.id }.state in setOf("LIVE", "DELETION_PENDING"))
        }
        if (!replay) requireFutureUse(reconstructed, rows.size)
        return rows
    }

    private fun requireReducer(installation: OwnerDeleteAllApplyRows.Installation, credential: OwnerDeleteAllApplyRows.Credential, hasContent: Boolean) {
        val identity = ScopedInstallationId(observed.event.tuple.actorId, controls.scope)
        val decision = InstallationRecoveryReducer.reduce(
            InstallationRecoverySnapshot(
                identity,
                InstallationReservationSnapshot(identity, InstallationIdentityState.valueOf(installation.state)),
                InstallationCredentialSnapshot(identity, InstallationCredentialState.valueOf(credential.state)),
                hasOwnedContent = hasContent,
                hasBlockingReceipts = !replay,
            ),
            InstallationRecoveryEvidence.DeleteAll(identity),
        )
        check(decision is InstallationRecoveryDecision.Apply && !decision.reserveIdentityCapacity)
        check(decision.identityState === InstallationIdentityState.DELETED && decision.contentEffect === RecoveryContentEffect.ERASE_ALL_OWNED_CONTENT)
        check(decision.credentialEffect === if (replay) RecoveryCredentialEffect.PRESERVE else RecoveryCredentialEffect.COMPLETE_DELETE_ALL)
    }

    private fun erase(content: List<OwnerDeleteAllApplyRows.Content>, credential: OwnerDeleteAllApplyRows.Credential) {
        requireRetained()
        stage = Stage.ERASURE
        val identity = observed.event.tuple.actorId
        val time = Timestamp.from(checkNotNull(completion))
        content.forEach { row ->
            checkWrite()
            val deleted = jdbc.query(sql.DELETE_CONTENT, { result, _ ->
                result.getObject("id", UUID::class.java) to OwnerDeleteAllApplyRows.long(result, "version")
            }, row.id, identity, row.version).single()
            check(deleted == (row.id to row.version))
            removed.add(row)
        }
        resources.filter { it.state != "DELETED" }.forEach {
            checkWrite()
            check(jdbc.update(sql.TOMBSTONE_RESOURCE, time, it.id) == 1)
        }
        checkWrite()
        check(jdbc.update(sql.DELETE_INSTALLATION, time, identity) == 1)
        checkWrite()
        val rowVersion = Math.addExact(credential.rowVersion, 1)
        val updated = jdbc.query(
            sql.DELETE_CREDENTIAL,
            { row, _ ->
                OwnerDeleteAllApplyRows.long(row, "credential_version") == nextCredentialVersion() &&
                    OwnerDeleteAllApplyRows.long(row, "version") == rowVersion &&
                    MessageDigest.isEqual(OwnerDeleteAllApplyRows.bytes(row, "secret_verifier"), credential.verifier) &&
                    OwnerDeleteAllApplyRows.instant(row, "deleted_at") == completion &&
                    OwnerDeleteAllApplyRows.instant(row, "verifier_expires_at") == expiry
            },
            time,
            Timestamp.from(checkNotNull(expiry)),
            identity,
            credential.credentialVersion,
            credential.rowVersion,
            credential.verifier,
        ).single()
        check(updated && removed.size == current.size)
    }

    private fun complete(receipt: OwnerDeleteAllApplyRows.Receipt) {
        requireRetained()
        stage = Stage.COMPLETING
        val tuple = observed.event.tuple
        val time = Timestamp.from(checkNotNull(completion))
        checkWrite()
        check(
            jdbc.update(
                sql.COMPLETE_RECEIPT, tuple.epoch, observed.record.objectVersion, observed.ciphertextHash,
                time, Timestamp.from(checkNotNull(expiry)), tuple.actorId, receipt.key, observed.record.eventId, observed.fingerprint, tuple.credentialVersion,
            ) == 1,
        )
        checkWrite()
        check(
            jdbc.update(
                sql.MARK_APPLIED,
                time,
                observed.record.eventId,
                checkNotNull(retainedVerification).bytes,
                checkNotNull(retainedVerification).verificationHash,
            ) == 1,
        )
        checkWrite()
        check(
            jdbc.update(
                sql.INSERT_APPLIED, observed.record.objectKey, observed.record.objectVersion, observed.record.eventId,
                observed.ciphertextHash, observed.writer, tuple.epoch, observed.targets.size, time,
            ) == 1,
        )
        requireApplied(checkNotNull(completion))
    }

    private fun requireApplied(at: Instant) {
        requireRetained()
        check(
            jdbc.query(sql.APPLIED, { row, _ ->
                OwnerDeleteAllApplyRows.valid(row)
                OwnerDeleteAllApplyRows.string(row, "event_id") == observed.record.eventId &&
                    OwnerDeleteAllApplyRows.string(row, "object_key") == observed.record.objectKey &&
                    OwnerDeleteAllApplyRows.string(row, "object_version") == observed.record.objectVersion &&
                    MessageDigest.isEqual(OwnerDeleteAllApplyRows.bytes(row, "ciphertext_hash"), observed.ciphertextHash) &&
                    row.getObject("writer_generation", UUID::class.java) == observed.writer &&
                    OwnerDeleteAllApplyRows.long(row, "journal_epoch") == observed.event.tuple.epoch &&
                    OwnerDeleteAllApplyRows.long(row, "target_count") == observed.targets.size.toLong() &&
                    OwnerDeleteAllApplyRows.instant(row, "applied_at") == at
            }, observed.record.objectKey, observed.record.objectVersion).single(),
        )
    }

    private fun requireFinalState() {
        requireRetained()
        check(jdbc.queryForObject(sql.NO_OWNED_CONTENT, Boolean::class.java, observed.event.tuple.actorId) == true)
        check(jdbc.queryForObject(sql.ALL_TOMBSTONED, Long::class.java, uuidArray(resources.map { it.id })) == resources.size.toLong())
        requireRetention(databaseNow())
        requireRetained()
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS_READY && allocation == null)
        stage = Stage.COUNTERS
    }

    internal fun requireCapacityPolicy(ledger: ComplaintCapacityLedger, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && policy.digestBytes().contentEquals(ledger.configuration.digestBytes()))
        check(policy.hardLimit == ledger.balance.hardLimit && policy.creationLimit == ledger.balance.creationLimit)
        check(checkNotNull(recovery).remaining.fitsWithin(ledger.balance.recoveryReserved))
        phase.ownerDeleteAllApply.checkCapacity(this, selected)
    }

    internal fun retainCapacity(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && allocation == null && value.belongsTo(this))
        allocation = value
    }

    /** Null means an exact replay, never an instruction to release a future promise. */
    internal fun materializedCounts(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate): OwnerDeleteAllMaterializedCountsV1? {
        requireCapacityWrite(value, selected)
        if (inventory != null) return inventoryCounts
        if (replay) {
            val reserve = checkNotNull(recovery)
            check(reserve.state == "PARTIAL" && reserve.convertedAt != null && !reserve.convertedAt.isBefore(checkNotNull(completion)))
            check((OwnerDeleteAllCapacityCharges.APPLIED + ComplaintCapacityCharges.AUDIT).fitsWithin(reserve.used))
            return null
        }
        check(removed.size == current.size && removed.size <= 100 && reconstructed in 0..100)
        return OwnerDeleteAllMaterializedCountsV1(removed.size, reconstructed, 0, 1, 1)
    }

    internal fun remainingReserve(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate): ComplaintCapacityVector {
        requireCapacityWrite(value, selected)
        return checkNotNull(recovery).remaining
    }

    internal fun recordProgress(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate, use: ComplaintCapacityVector) {
        requireCapacityWrite(value, selected)
        check(!replay && use == (if (inventory != null) checkNotNull(inventoryCounts).use else actualUse(reconstructed, removed.size)))
        val reserve = checkNotNull(recovery)
        val cumulative = reserve.used + use
        check(cumulative.fitsWithin(reserve.promise) && cumulative != reserve.promise && cumulative != ComplaintCapacityVector.ZERO)
        checkWrite()
        check(
            jdbc.update(
                sql.RECORD_PROGRESS,
                vectorArray(cumulative),
                Timestamp.from(checkNotNull(inventoryAt ?: completion)),
                reserve.eventId,
                vectorArray(reserve.promise),
                reserve.state,
                if (reserve.state == "PARTIAL") vectorArray(reserve.used) else null,
                reserve.convertedAt?.let(Timestamp::from),
            ) == 1,
        )
    }

    internal fun requireCapacityWrite(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.SETTLING && allocation === value)
        checkWrite()
    }

    internal fun auditConnection(
        value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply,
        selected: JdbcTemplate,
        entry: CountedOwnerDeleteAllAuditEntry,
        ordinal: Int,
    ): Connection {
        requireAuditWrite(value, selected)
        check(entry.createdAt == (inventoryAt ?: completion) && ordinal in 0..removed.size)
        when (val outcome = entry.outcome) {
            is OwnerDeleteAllAuditOutcome.Removed -> {
                check(ordinal < removed.size && outcome.resourceId == removed[ordinal].id && outcome.version == removed[ordinal].version)
            }

            is OwnerDeleteAllAuditOutcome.InstallationCompleted -> {
                check(inventory == null && ordinal == removed.size && outcome.version == nextCredentialVersion())
                check(outcome.removedCount == removed.size && outcome.reconstructedCount == reconstructed)
            }

            is OwnerDeleteAllAuditOutcome.RecoveryApplied -> {
                val counts = checkNotNull(inventoryCounts)
                check(inventory != null && ordinal == removed.size && outcome.eventId == observed.record.eventId &&
                    outcome.removedCount == counts.removed && outcome.reconstructedCount == counts.resources && outcome.installationCount == counts.installations)
            }
        }
        return phase.ownerDeleteAllApply.connection(this, selected)
    }

    internal fun requireAuditWrite(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.AUDIT && !replay && allocation === value && value.settledFor(this))
        checkWrite()
    }

    internal fun auditScope(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate): ComplaintDataScope {
        requireAuditWrite(value, selected)
        return controls.scope
    }

    internal fun auditActorKind(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate): String {
        requireAuditWrite(value, selected)
        return if (inventory == null) "INSTALLATION" else "SYSTEM"
    }

    private fun requireFutureUse(newIds: Int, contentCount: Int) = check(actualUse(newIds, contentCount).fitsWithin(checkNotNull(recovery).remaining))
    private fun nextCredentialVersion(): Long = Math.addExact(observed.credentialVersion, 1)
    private fun databaseNow(): Instant = checkNotNull(jdbc.queryForObject(sql.NOW, { row, _ -> row.getTimestamp(1).toInstant() }))
    private fun requireRetention(now: Instant) = check(!observed.verifiedAt.isAfter(now) && observed.retainedUntil.isAfter(now))
    private fun requireRetained() = phase.ownerDeleteAllApply.requireRetained(this, jdbc)
    private fun checkWrite() = phase.ownerDeleteAllApply.checkWrite(this, jdbc)

    private fun requireSelected(selected: JdbcTemplate) {
        requireRetained()
        check(selected === jdbc)
    }

    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllApplyOperation(redacted)"

    private class Released(override val completedAt: Instant, override val expiresAt: Instant) : CommittedOwnerDeleteAllApplyV1 {
        override fun toString(): String = "CommittedOwnerDeleteAllApplyV1(completed204,redacted)"
    }

    /** Retain the exact privately authenticated producers, not copied proof descriptors or the failed phase/holder. */
    private class ReconciliationPending(
        @Suppress("unused") private val work: CommittedOwnerDeleteAllWork,
        @Suppress("unused") private val proof: CommittedOwnerDeleteAllVerificationV1,
    ) : OwnerDeleteAllReconciliationPendingV1 {
        override fun toString(): String = "OwnerDeleteAllReconciliationPendingV1(redacted,no-erasure-result)"
    }

    private enum class Stage {
        RETAINED,
        CONTROL,
        RECEIPT,
        PUBLICATION,
        RESERVATION,
        COUNTERS_READY,
        COUNTERS,
        INSTALLATION,
        RESOURCES,
        CONTENT,
        ERASURE,
        SETTLING,
        COMPLETING,
        AUDIT,
        COMPLETE,
        FAILED,
    }

    companion object {
        private val RETRY_RETENTION: Duration = Duration.ofHours(192)
        private fun uuidArray(ids: List<UUID>): String = ids.joinToString(",", "{", "}")
        private fun vectorArray(vector: ComplaintCapacityVector): String = vector.toLongArray().joinToString(",", "{", "}")
        private fun actualUse(newIds: Int, contentCount: Int): ComplaintCapacityVector =
            ComplaintCapacityCharges.RESOURCE_ID.scaled(newIds.toLong()) + OwnerDeleteAllCapacityCharges.APPLIED +
                ComplaintCapacityCharges.AUDIT.scaled(contentCount.toLong() + 1)

        @Suppress("TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            capacity: JdbcComplaintCapacityStore,
            audit: AuditService,
            controls: OwnerDeleteAllControlBinding,
            policy: ComplaintCapacityPolicyV1,
            codec: OwnerDeleteAllVerificationCodecV1,
            issuer: Any,
            routing: OwnerDeleteAllJournalBindingV1,
            input: OwnerDeleteAllApplyInputV1,
        ): ComplaintOwnerDeleteAllApplyOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: ComplaintOwnerDeleteAllApplyOperation? = null
            try {
                phase.ownerDeleteAllApply.requireOperation(jdbc)
                val captured = input as? CapturedOwnerDeleteAllApply ?: error("Private committed verification capture required")
                captured.requireOwned(issuer, routing)
                controls.testGraph?.let { phase.requireTestRunOwnerDeleteAllApply(it, jdbc, input) }
                operation = ComplaintOwnerDeleteAllApplyOperation(phase, jdbc, controls, policy, codec, routing, captured)
                phase.ownerDeleteAllApply.retain(operation, jdbc)
                operation.execute(capacity, audit)
                return operation
            } catch (problem: Throwable) {
                operation?.failed(problem)
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
