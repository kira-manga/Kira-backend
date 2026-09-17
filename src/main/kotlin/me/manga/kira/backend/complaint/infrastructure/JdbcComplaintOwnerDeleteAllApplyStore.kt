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
internal class JdbcComplaintOwnerDeleteAllApplyStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    desired: ComplaintInstallationDesiredSettings.Configured,
    private val routing: VersionBoundComplaintJournalRouting,
    private val policy: ComplaintCapacityPolicyV1,
    catalog: CatalogCommonHeadEvidence,
    private val verification: JdbcComplaintOwnerDeleteAllVerificationStore,
) {
    private val issuer = Any()
    private val controls = OwnerDeleteAllControlBinding(desired, routing, catalog)
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
            CapturedOwnerDeleteAllApply(issuer, routing, event, record, bytes, hash, verifier, work, proof)
        } finally {
            verifier.fill(0)
        }
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

private class CapturedOwnerDeleteAllApply(
    private val issuer: Any,
    private val routing: VersionBoundComplaintJournalRouting,
    val event: OwnerDeleteAllJournalEventV1,
    val record: OwnerDeleteAllVerificationRecordV1,
    bytes: ByteArray,
    hash: ByteArray,
    verifier: ByteArray,
    val work: CommittedOwnerDeleteAllWork,
    val proof: CommittedOwnerDeleteAllVerificationV1,
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
    val verifier = verifier.copyOf().also { check(it.size == 32) }

    fun requireOwned(selectedIssuer: Any, selectedRouting: VersionBoundComplaintJournalRouting) {
        check(issuer === selectedIssuer && routing === selectedRouting && event.belongsTo(selectedRouting))
    }

    override fun toString(): String = "OwnerDeleteAllApplyInputV1(private-committed-proof,redacted)"
}

/** Fixed lock/mutation sequence; there is no caller-provided callback, vector, row list or alternate holder. */
internal class ComplaintOwnerDeleteAllApplyOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val controls: OwnerDeleteAllControlBinding,
    private val policy: ComplaintCapacityPolicyV1,
    private val codec: OwnerDeleteAllVerificationCodecV1,
    private val observed: CapturedOwnerDeleteAllApply,
) {
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

    fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE &&
        completion != null && expiry != null && allocation?.completedFor(this) == true

    val result: CommittedOwnerDeleteAllApplyV1
        get() {
            phase.ownerDeleteAllApply.requireCommitted(this)
            requireConnectionFree()
            return released ?: Released(checkNotNull(completion), checkNotNull(expiry)).also { released = it }
        }

    val continuationResult: OwnerDeleteAllApplyOutcomeV1
        get() {
            if (!phase.ownerDeleteAllApply.reconciliationPending(this)) return result
            requireConnectionFree()
            return pending ?: ReconciliationPending(observed.work, observed.proof).also { pending = it }
        }

    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        requireRetained()
        check(stage === Stage.RETAINED)
        stage = Stage.CONTROL
        controls.lock(jdbc, authorizingPath = false).requireContinuation(observed.event.tuple.epoch, prepared = false)
        val receipt = lockProof()
        stage = Stage.RESERVATION
        recovery = jdbc.query(OwnerDeleteAllApplySql.LOCK_RECOVERY, { row, _ -> OwnerDeleteAllApplyRows.recovery(row) }, receipt.reference).single()
        check(checkNotNull(recovery).eventId == observed.record.eventId && checkNotNull(recovery).promise == OwnerDeleteAllCapacityCharges.RECOVERY)
        stage = Stage.COUNTERS_READY
        val paid = capacity.lockForOwnerDeleteAllApply(this)
        stage = Stage.INSTALLATION
        val identity = observed.event.tuple.actorId
        val installation = jdbc.query(OwnerDeleteAllApplySql.LOCK_INSTALLATION, { row, _ -> OwnerDeleteAllApplyRows.installation(row) }, identity).single()
        val credential = jdbc.query(OwnerDeleteAllApplySql.LOCK_CREDENTIAL, { row, _ -> OwnerDeleteAllApplyRows.credential(row) }, identity).single()
        requirePair(installation, credential, receipt)
        current = jdbc.query(OwnerDeleteAllApplySql.OWNER_TARGETS, { row, _ -> row.getObject(1, UUID::class.java) }, identity)
        check(current.size <= 100 && current.distinct().size == current.size && (!replay || current.isEmpty()))
        val now = databaseNow()
        requireRetention(now)
        check(checkNotNull(recovery).convertedAt?.isAfter(now) != true)
        completion = if (replay) checkNotNull(receipt.completedAt) else now
        expiry = if (replay) checkNotNull(receipt.expiresAt) else now.plus(RETRY_RETENTION)
        check(!checkNotNull(completion).isAfter(now) && checkNotNull(expiry).isAfter(now))
        check(!checkNotNull(completion).isBefore(observed.verifiedAt))
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
        stage = Stage.COMPLETE
    }

    private fun lockProof(): OwnerDeleteAllApplyRows.Receipt {
        requireRetained()
        stage = Stage.RECEIPT
        val tuple = observed.event.tuple
        val receipt = jdbc.query(OwnerDeleteAllApplySql.LOCK_RECEIPTS, { row, _ -> OwnerDeleteAllApplyRows.receipt(row) }, tuple.actorId).single()
        check(receipt.key == tuple.operationKey && receipt.version == tuple.credentialVersion && receipt.reference == observed.record.eventId)
        check(MessageDigest.isEqual(receipt.fingerprint, observed.fingerprint))
        stage = Stage.PUBLICATION
        val publication = jdbc.query(
            OwnerDeleteAllApplySql.LOCK_PUBLICATION,
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
        check(parsed == observed.record && proof.bytes.contentEquals(observed.verificationBytes))
        check(MessageDigest.isEqual(proof.verificationHash, observed.verificationHash) && MessageDigest.isEqual(proof.hash, observed.ciphertextHash))
        check(proof.version == parsed.objectVersion && proof.createdAt == Instant.parse(parsed.objectCreatedAt))
        check(proof.retainUntil == observed.retainedUntil && proof.verifiedAt == observed.verifiedAt)
        val now = databaseNow()
        requireRetention(now)
        check(!publication.createdAt.isAfter(now) && !publication.createdAt.isAfter(observed.verifiedAt))
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
        check(MessageDigest.isEqual(credential.verifier, observed.verifier))
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
            val found = jdbc.query(OwnerDeleteAllApplySql.LOCK_RESOURCE, { row, _ -> OwnerDeleteAllApplyRows.resource(row) }, id).singleOrNull()
            if (found != null) {
                check(found.id == id && found.deletedAt?.isAfter(checkNotNull(completion)) != true)
                check(!replay || found.state == "DELETED")
                found
            } else {
                check(!replay && id in eventIds && id !in current)
                requireFutureUse(reconstructed + 1, current.size)
                checkWrite()
                val time = Timestamp.from(checkNotNull(completion))
                check(jdbc.update(OwnerDeleteAllApplySql.RECONSTRUCT_RESOURCE, id, time, time) == 1)
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
            jdbc.query(OwnerDeleteAllApplySql.LOCK_CONTENT, { row, _ -> OwnerDeleteAllApplyRows.content(row) }, uuidArray(resources.map { it.id }))
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
        val identity = ScopedInstallationId(observed.event.tuple.actorId, ComplaintDataScope.LIVE)
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
            val deleted = jdbc.query(OwnerDeleteAllApplySql.DELETE_CONTENT, { result, _ ->
                result.getObject("id", UUID::class.java) to OwnerDeleteAllApplyRows.long(result, "version")
            }, row.id, identity, row.version).single()
            check(deleted == (row.id to row.version))
            removed.add(row)
        }
        resources.filter { it.state != "DELETED" }.forEach {
            checkWrite()
            check(jdbc.update(OwnerDeleteAllApplySql.TOMBSTONE_RESOURCE, time, it.id) == 1)
        }
        checkWrite()
        check(jdbc.update(OwnerDeleteAllApplySql.DELETE_INSTALLATION, time, identity) == 1)
        checkWrite()
        val rowVersion = Math.addExact(credential.rowVersion, 1)
        val updated = jdbc.query(
            OwnerDeleteAllApplySql.DELETE_CREDENTIAL,
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
                OwnerDeleteAllApplySql.COMPLETE_RECEIPT, tuple.epoch, observed.record.objectVersion, observed.ciphertextHash,
                time, Timestamp.from(checkNotNull(expiry)), tuple.actorId, receipt.key, observed.record.eventId, observed.fingerprint, tuple.credentialVersion,
            ) == 1,
        )
        checkWrite()
        check(
            jdbc.update(
                OwnerDeleteAllApplySql.MARK_APPLIED,
                time,
                observed.record.eventId,
                observed.verificationBytes,
                observed.verificationHash,
            ) == 1,
        )
        checkWrite()
        check(
            jdbc.update(
                OwnerDeleteAllApplySql.INSERT_APPLIED, observed.record.objectKey, observed.record.objectVersion, observed.record.eventId,
                observed.ciphertextHash, observed.writer, tuple.epoch, observed.targets.size, time,
            ) == 1,
        )
        requireApplied(checkNotNull(completion))
    }

    private fun requireApplied(at: Instant) {
        requireRetained()
        check(
            jdbc.query(OwnerDeleteAllApplySql.APPLIED, { row, _ ->
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
        check(jdbc.queryForObject(OwnerDeleteAllApplySql.NO_OWNED_CONTENT, Boolean::class.java, observed.event.tuple.actorId) == true)
        check(jdbc.queryForObject(OwnerDeleteAllApplySql.ALL_TOMBSTONED, Long::class.java, uuidArray(resources.map { it.id })) == resources.size.toLong())
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
    internal fun materializedCounts(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate): Pair<Int, Int>? {
        requireCapacityWrite(value, selected)
        if (replay) {
            val reserve = checkNotNull(recovery)
            check(reserve.state == "PARTIAL" && reserve.convertedAt != null && !reserve.convertedAt.isBefore(checkNotNull(completion)))
            check((OwnerDeleteAllCapacityCharges.APPLIED + ComplaintCapacityCharges.AUDIT).fitsWithin(reserve.used))
            return null
        }
        check(removed.size == current.size && removed.size <= 100 && reconstructed in 0..100)
        return removed.size to reconstructed
    }

    internal fun remainingReserve(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate): ComplaintCapacityVector {
        requireCapacityWrite(value, selected)
        return checkNotNull(recovery).remaining
    }

    internal fun recordProgress(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate, use: ComplaintCapacityVector) {
        requireCapacityWrite(value, selected)
        check(!replay && use == actualUse(reconstructed, removed.size))
        val reserve = checkNotNull(recovery)
        val cumulative = reserve.used + use
        check(cumulative.fitsWithin(reserve.promise) && cumulative != reserve.promise && cumulative != ComplaintCapacityVector.ZERO)
        checkWrite()
        check(
            jdbc.update(
                OwnerDeleteAllApplySql.RECORD_PROGRESS,
                vectorArray(cumulative),
                Timestamp.from(checkNotNull(completion)),
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
        check(entry.createdAt == completion && ordinal in 0..removed.size)
        when (val outcome = entry.outcome) {
            is OwnerDeleteAllAuditOutcome.Removed -> {
                check(ordinal < removed.size && outcome.resourceId == removed[ordinal].id && outcome.version == removed[ordinal].version)
            }

            is OwnerDeleteAllAuditOutcome.InstallationCompleted -> {
                check(ordinal == removed.size && outcome.version == nextCredentialVersion())
                check(outcome.removedCount == removed.size && outcome.reconstructedCount == reconstructed)
            }
        }
        return phase.ownerDeleteAllApply.connection(this, selected)
    }

    internal fun requireAuditWrite(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAllApply, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.AUDIT && !replay && allocation === value && value.settledFor(this))
        checkWrite()
    }

    private fun requireFutureUse(newIds: Int, contentCount: Int) = check(actualUse(newIds, contentCount).fitsWithin(checkNotNull(recovery).remaining))
    private fun nextCredentialVersion(): Long = Math.addExact(observed.credentialVersion, 1)
    private fun databaseNow(): Instant = checkNotNull(jdbc.queryForObject(OwnerDeleteAllApplySql.NOW, { row, _ -> row.getTimestamp(1).toInstant() }))
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
            routing: VersionBoundComplaintJournalRouting,
            input: OwnerDeleteAllApplyInputV1,
        ): ComplaintOwnerDeleteAllApplyOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: ComplaintOwnerDeleteAllApplyOperation? = null
            try {
                phase.ownerDeleteAllApply.requireOperation(jdbc)
                val captured = input as? CapturedOwnerDeleteAllApply ?: error("Private committed verification capture required")
                captured.requireOwned(issuer, routing)
                operation = ComplaintOwnerDeleteAllApplyOperation(phase, jdbc, controls, policy, codec, captured)
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
