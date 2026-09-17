package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.CountedInstallationDeleteAuthorizationAuditEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.ComplaintJournalActorKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionTupleV1
import me.manga.kira.backend.security.ComplaintJournalRoutingCandidateV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalJsonV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Actual dormant LIVE SQL producer, not a route, full-D/current-capability producer or publisher.
 * Every supplied binding is independent of observed rows; matching it grants only local custody.
 * No source path here acquires a secret, generates a data key, erases content or dispatches S3.
 */
internal class JdbcComplaintOwnerDeleteAllStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    desired: ComplaintInstallationDesiredSettings.Configured,
    private val routing: VersionBoundComplaintJournalRouting,
    private val codec: OwnerDeleteAllJournalCodecV1,
    private val policy: ComplaintCapacityPolicyV1,
    catalog: CatalogCommonHeadEvidence,
    private val process: OwnerDeleteAllProcessBinding? = null,
) {
    init {
        process?.requireDeletion(jdbc)
        process?.requirePolicy(policy)
    }

    private val issuer = Any()
    private val controls = OwnerDeleteAllControlBinding(desired, routing, catalog, process)

    /** Bound VERIFY rechecks current control before locking its receipt; legacy diagnostic paths stay unchanged. */
    internal fun lockBoundVerification(selected: JdbcTemplate) {
        if (process != null) {
            process.requireDeletion(selected)
            check(selected.dataSource === jdbc.dataSource)
            controls.lock(selected, authorizingPath = false)
        }
    }

    fun authorize(candidate: InstallationDeletionCandidate, preflight: InstallationDeletionPreflightTuple): ComplaintOwnerDeleteAllOperation =
        capture(candidate, preflight, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE)

    fun reload(candidate: InstallationDeletionCandidate, preflight: InstallationDeletionPreflightTuple): ComplaintOwnerDeleteAllOperation =
        capture(candidate, preflight, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD)

    private fun capture(candidate: InstallationDeletionCandidate, preflight: InstallationDeletionPreflightTuple, path: PersistencePhasePath) =
        ComplaintOwnerDeleteAllOperation.capture(jdbc, capacity, audit, controls, routing, codec, policy, issuer, candidate, preflight, path)

    /** Custody only: future provider composition must additionally own actual runtime authority and its disjoint lane. */
    fun preparedEvent(work: CommittedOwnerDeleteAllWork.Prepared): OwnerDeleteAllJournalEventV1 {
        requireConnectionFree()
        return ComplaintOwnerDeleteAllOperation.preparedEvent(work, issuer, routing)
    }

    /** Exact private reload custody; the fixed VERIFY consumer must still strictly validate its retained proof. */
    fun recordedEvent(work: CommittedOwnerDeleteAllWork.RecordedVerified): OwnerDeleteAllJournalEventV1 {
        requireConnectionFree()
        return ComplaintOwnerDeleteAllOperation.recordedEvent(work, issuer, routing)
    }

    /** Private committed authentication custody, never an external event field or a verifier inferred from the APPLY row. */
    fun authenticatedVerifier(work: CommittedOwnerDeleteAllWork, event: OwnerDeleteAllJournalEventV1): ByteArray {
        requireConnectionFree()
        return ComplaintOwnerDeleteAllOperation.authenticatedVerifier(work, issuer, routing, event)
    }

    override fun toString(): String = "JdbcComplaintOwnerDeleteAllStore(dormant,no-runtime-authority)"
}

/** Only the SQL operation's private post-release instances exist. Neither branch is S3/version evidence. */
internal sealed interface CommittedOwnerDeleteAllWork {
    fun canonicalBytes(): ByteArray

    sealed interface Prepared : CommittedOwnerDeleteAllWork

    /** Known committed local VERIFIED reload, not a new provider readback or refreshed retention. */
    sealed interface RecordedVerified : CommittedOwnerDeleteAllWork {
        val objectVersion: String
        val ciphertextSha256: String
        val objectCreatedAt: Instant
        val retainUntil: Instant
        val verifiedAt: Instant
        fun verificationBytes(): ByteArray
        fun verificationHash(): ByteArray
    }
}

/** One exact retained JDBC holder, fixed lock order and result issuer; no caller-driven mutation cursor. */
internal class ComplaintOwnerDeleteAllOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val controls: OwnerDeleteAllControlBinding,
    private val routing: VersionBoundComplaintJournalRouting,
    private val codec: OwnerDeleteAllJournalCodecV1,
    private val policy: ComplaintCapacityPolicyV1,
    private val issuer: Any,
    private val candidate: InstallationDeletionCandidate,
    private val preflight: InstallationDeletionPreflightTuple,
    private val path: PersistencePhasePath,
) {
    private var stage = Stage.RETAINED
    private var newAuthorization = false
    private var allocation: JdbcComplaintCapacityStore.LockedOwnerDeleteAll? = null
    private var canonical: OwnerDeleteAllJournalEventV1? = null
    private var authorizationTime: Instant? = null
    private var prepared = false
    private var recordedProof: RecordedProof? = null
    private var released: CommittedOwnerDeleteAllWork? = null

    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected
    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) &&
        stage === Stage.COMPLETE && canonical != null && (prepared || recordedProof != null) && allocation?.completedFor(this) == true

    val result: CommittedOwnerDeleteAllWork
        get() {
            phase.ownerDeleteAll.requireCommitted(this)
            requireConnectionFree()
            return released ?: (
                if (prepared) {
                    ReleasedPrepared(issuer, routing, checkNotNull(canonical), candidate.credential.verifierBytes())
                } else {
                    ReleasedVerified(issuer, routing, checkNotNull(canonical), candidate.credential.verifierBytes(), checkNotNull(recordedProof))
                }
                ).also { released = it }
        }

    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        requireAt(Stage.RETAINED)
        requireTuple(candidate, preflight)
        val authorizingPath = path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE
        if (authorizingPath) phase.ownerDeleteAll.claimAuthorize(this, jdbc, preflight)
        stage = Stage.CONTROL
        val control = controls.lock(jdbc, authorizingPath)
        requireRetained()
        stage = Stage.RECEIPT
        val receipts = jdbc.query(OwnerDeleteAllPersistenceSql.LOCK_RECEIPTS, { row, _ -> receipt(row) }, candidate.installation.id)
        check(receipts.size <= 1) // The fixed two-row sentinel never picks an arbitrary existing key.
        if (receipts.isEmpty()) {
            check(authorizingPath)
            phase.ownerDeleteAll.checkReceiptWrite(this, jdbc)
            check(
                jdbc.update(
                    OwnerDeleteAllPersistenceSql.INSERT_RECEIPT,
                    candidate.installation.id,
                    candidate.operationKey,
                    candidate.credentialVersion,
                    preflight.fingerprint.bytes(),
                ) == 1,
            )
            newAuthorization = true
            authorizeNew(capacity, audit, control)
        } else {
            reloadExisting(capacity, control, receipts.single())
        }
        requireRetained()
        check(checkNotNull(allocation).completedFor(this))
        stage = Stage.COMPLETE
    }

    private fun authorizeNew(capacity: JdbcComplaintCapacityStore, audit: AuditService, control: OwnerDeleteAllControlBinding.Locked) {
        val tuple = journalTuple(control.epoch)
        val routes = routing.derive(tuple)
        // No locking of an existing publication in the new-receipt path. Occupied retained candidates
        // without their exact receipt require recovery, never another active-key/epoch assignment.
        routes.candidates().forEach { route ->
            check(
                jdbc.queryForObject(
                    "SELECT NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE event_id = ? OR object_key = ?)",
                    Boolean::class.java,
                    route.eventId,
                    route.objectKey,
                ) == true,
            )
        }
        stage = Stage.COUNTERS_READY
        val paid = capacity.lockForOwnerDeleteAll(this)
        check(paid.settledFor(this))
        stage = Stage.RESERVING
        checkWrite()
        check(jdbc.update(OwnerDeleteAllPersistenceSql.INSERT_RECOVERY, routes.active.eventId, routes.active.eventId, recoveryArray()) == 1)
        stage = Stage.DOMAIN
        lockInstallation("ACTIVE")
        // Existing state index; intentionally conservative for this dormant slice. No JSON owner scan,
        // reverse receipt/publication lock or reliance on this observation alone for serialization.
        check(jdbc.queryForObject(OwnerDeleteAllPersistenceSql.NO_PENDING, Boolean::class.java) == true)
        val targets = lockTargets()
        val event = codec.canonicalize(tuple, targets, routes.active.routingKeyId)
        check(event.belongsTo(routing) && event.route == routes.active)
        canonical = event
        stage = Stage.PENDING
        checkWrite()
        check(jdbc.update(OwnerDeleteAllPersistenceSql.PEND_ID, candidate.installation.id) == 1)
        checkWrite()
        check(
            jdbc.update(
                OwnerDeleteAllPersistenceSql.PEND_CREDENTIAL,
                candidate.installation.id,
                candidate.credentialVersion,
                candidate.credential.verifierBytes(),
            ) == 1,
        )
        stage = Stage.PUBLICATION
        checkWrite()
        authorizationTime = checkNotNull(
            jdbc.queryForObject(
                OwnerDeleteAllPersistenceSql.INSERT_PUBLICATION,
                { row, _ -> row.getTimestamp(1).toInstant() },
                event.route.eventId, controls.writer, tuple.epoch, targets.size, event.route.routingKeyId, event.route.objectKey,
                event.canonicalBytes(), HexFormat.of().parseHex(event.semanticSha256),
            ),
        )
        checkWrite()
        check(
            jdbc.update(
                OwnerDeleteAllPersistenceSql.AUTHORIZE_RECEIPT,
                event.route.eventId,
                Timestamp.from(checkNotNull(authorizationTime)),
                candidate.installation.id,
                candidate.operationKey,
            ) == 1,
        )
        stage = Stage.AUDIT
        checkWrite()
        audit.recordInstallationDeleteAuthorization(candidate.credentialVersion, paid, checkNotNull(authorizationTime))
        check(paid.completedFor(this))
        prepared = true
    }

    private fun reloadExisting(capacity: JdbcComplaintCapacityStore, control: OwnerDeleteAllControlBinding.Locked, receipt: Receipt) {
        check(receipt.key == candidate.operationKey && receipt.version == candidate.credentialVersion)
        check(MessageDigest.isEqual(receipt.fingerprint, preflight.fingerprint.bytes()))
        check(receipt.state == "AUTHORIZED_DELETE") // A completed race is retried through the read-only preflight branch.
        val reference = checkNotNull(receipt.publication)
        stage = Stage.RELOAD_PUBLICATION
        val publication = jdbc.query(OwnerDeleteAllPersistenceSql.LOCK_PUBLICATION, { row, _ -> publication(row) }, reference).single()
        requireRetained()
        val payload = OwnerDeleteAllJournalJsonV1(routing.journalConfiguration.declaration().limits.decoder).payload(publication.bytes)
        val targets = payload.complaintIds.map(ComplaintIdentifiers::resourceId)
        val event = codec.canonicalize(journalTuple(publication.epoch), targets, publication.route.routingKeyId)
        check(event.belongsTo(routing) && publication.writer == controls.writer && publication.route == event.route && reference == event.route.eventId)
        check(publication.targetCount == targets.size && publication.bytes.contentEquals(event.canonicalBytes()))
        check(MessageDigest.isEqual(publication.semanticHash, HexFormat.of().parseHex(event.semanticSha256)))
        prepared = publication.prepared
        recordedProof = publication.verification
        control.requireContinuation(publication.epoch, prepared)
        canonical = event
        stage = Stage.RELOAD_RESERVATION
        check(jdbc.query(OwnerDeleteAllPersistenceSql.LOCK_RECOVERY, { row, _ -> requiredBoolean(row, "matches") }, recoveryArray(), reference).single())
        stage = Stage.COUNTERS_READY
        check(capacity.lockForOwnerDeleteAll(this).settledFor(this))
        stage = Stage.DOMAIN
        lockInstallation("DELETION_PENDING")
        // No target resnapshot, audit, counter UPDATE, publication rewrite or current-active-key selection.
    }

    private fun lockInstallation(expectedState: String) {
        requireAt(Stage.DOMAIN)
        val reservation = jdbc.query(OwnerDeleteAllPersistenceSql.LOCK_INSTALLATION_ID, { row, _ ->
            requiredBoolean(row, "live") && row.getString("state") == expectedState
        }, candidate.installation.id).singleOrNull() == true
        val credential = jdbc.query(OwnerDeleteAllPersistenceSql.LOCK_CREDENTIAL, { row, _ ->
            requiredBoolean(row, "live") && requiredBoolean(row, "nonterminal") && row.getString("state") == expectedState &&
                requiredLong(row, "credential_version") == candidate.credentialVersion &&
                MessageDigest.isEqual(checkNotNull(row.getBytes("secret_verifier")), candidate.credential.verifierBytes())
        }, candidate.installation.id).singleOrNull() == true
        requireRetained()
        check(reservation && credential)
    }

    private fun lockTargets(): List<UUID> {
        requireAt(Stage.DOMAIN)
        val targets = jdbc.query(OwnerDeleteAllPersistenceSql.OWNER_TARGETS, { row, _ -> row.getObject(1, UUID::class.java) }, candidate.installation.id)
        check(targets.size <= 100)
        stage = Stage.RESOURCES
        if (targets.isEmpty()) return targets
        val targetArray = targets.joinToString(",", "{", "}")
        val reservations = jdbc.query(OwnerDeleteAllPersistenceSql.LOCK_RESOURCES, { row, _ ->
            check(requiredBoolean(row, "live") && row.getString("state") == "LIVE")
            row.getObject("id", UUID::class.java)
        }, targetArray)
        check(reservations == targets)
        val content = jdbc.query(OwnerDeleteAllPersistenceSql.LOCK_CONTENT, { row, _ ->
            check(requiredBoolean(row, "live") && row.getObject("owner_id", UUID::class.java) == candidate.installation.id)
            check(row.getString("ownership") == "INSTALLATION" && row.getString("kind") in setOf("REPORT", "REPLY") && requiredLong(row, "version") > 0)
            row.getObject("id", UUID::class.java)
        }, targetArray)
        check(content == targets)
        requireRetained()
        return targets
    }

    internal fun beginCounterLock(selected: JdbcTemplate): Boolean {
        requireSelected(selected)
        check(stage === Stage.COUNTERS_READY && allocation == null)
        stage = Stage.COUNTERS
        return newAuthorization
    }

    internal fun requireCapacityPolicy(ledger: ComplaintCapacityLedger, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && policy.digestBytes().contentEquals(ledger.configuration.digestBytes()))
        check(policy.hardLimit == ledger.balance.hardLimit && policy.creationLimit == ledger.balance.creationLimit)
        phase.ownerDeleteAll.checkCapacity(this, selected, ledger)
    }

    internal fun retainCapacity(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAll, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && allocation == null && value.belongsTo(this))
        allocation = value
    }

    internal fun requireCapacityWrite(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAll, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && newAuthorization && allocation === value)
        checkWrite()
    }

    internal fun auditConnection(
        value: JdbcComplaintCapacityStore.LockedOwnerDeleteAll,
        selected: JdbcTemplate,
        entry: CountedInstallationDeleteAuthorizationAuditEntry,
    ): Connection {
        requireAuditWrite(value, selected)
        check(entry.submittedVersion == candidate.credentialVersion && entry.createdAt == authorizationTime)
        return phase.ownerDeleteAll.connection(this, selected)
    }

    internal fun requireAuditWrite(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAll, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.AUDIT && newAuthorization && allocation === value && value.settledFor(this))
        checkWrite()
    }

    private fun journalTuple(epoch: Long) = ComplaintJournalDeletionTupleV1(
        epoch,
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL,
        ComplaintJournalActorKindV1.INSTALLATION,
        candidate.installation.id,
        candidate.credentialVersion,
        candidate.operationKey,
        preflight.fingerprint.bytes(),
        ComplaintDataScope.LIVE,
    )

    private fun requireRetained() = phase.ownerDeleteAll.requireRetained(this, jdbc)
    private fun requireAt(expected: Stage) {
        requireRetained()
        check(stage === expected)
    }

    private fun requireSelected(selected: JdbcTemplate) {
        requireRetained()
        check(selected === jdbc)
    }
    private fun checkWrite() = phase.ownerDeleteAll.checkWrite(this, jdbc)

    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    override fun toString(): String = "ComplaintOwnerDeleteAllOperation(redacted)"

    private enum class Stage {
        RETAINED,
        CONTROL,
        RECEIPT,
        COUNTERS_READY,
        COUNTERS,
        RESERVING,
        DOMAIN,
        RESOURCES,
        PENDING,
        PUBLICATION,
        AUDIT,
        RELOAD_PUBLICATION,
        RELOAD_RESERVATION,
        COMPLETE,
        FAILED,
    }

    private class Receipt(val key: UUID, val version: Long, val fingerprint: ByteArray, val state: String, val publication: String?)
    private class Publication(
        val writer: UUID,
        val epoch: Long,
        val route: ComplaintJournalRoutingCandidateV1,
        val targetCount: Int,
        val bytes: ByteArray,
        val semanticHash: ByteArray,
        val prepared: Boolean,
        val verification: RecordedProof?,
    )

    private class RecordedProof(
        val objectVersion: String,
        val ciphertextSha256: String,
        val objectCreatedAt: Instant,
        val retainUntil: Instant,
        val verifiedAt: Instant,
        val bytes: ByteArray,
        val hash: ByteArray,
    )

    private abstract class Released(
        private val issuer: Any,
        private val routing: VersionBoundComplaintJournalRouting,
        private val event: OwnerDeleteAllJournalEventV1,
        verifier: ByteArray,
    ) : CommittedOwnerDeleteAllWork {
        private val verifier = verifier.copyOf()
        override fun canonicalBytes(): ByteArray = event.canonicalBytes()
        fun verifierBytes(): ByteArray = verifier.copyOf()
        fun requireOwned(selectedIssuer: Any, selectedRouting: VersionBoundComplaintJournalRouting): OwnerDeleteAllJournalEventV1 {
            check(issuer === selectedIssuer && routing === selectedRouting && event.belongsTo(selectedRouting))
            return event
        }

        override fun toString(): String = "CommittedOwnerDeleteAllWork(custody-only,redacted)"
    }
    private class ReleasedPrepared(issuer: Any, routing: VersionBoundComplaintJournalRouting, event: OwnerDeleteAllJournalEventV1, verifier: ByteArray) :
        Released(issuer, routing, event, verifier),
        CommittedOwnerDeleteAllWork.Prepared
    private class ReleasedVerified(
        issuer: Any,
        routing: VersionBoundComplaintJournalRouting,
        event: OwnerDeleteAllJournalEventV1,
        verifier: ByteArray,
        proof: RecordedProof,
    ) : Released(issuer, routing, event, verifier),
        CommittedOwnerDeleteAllWork.RecordedVerified {
        override val objectVersion = proof.objectVersion
        override val ciphertextSha256 = proof.ciphertextSha256
        override val objectCreatedAt = proof.objectCreatedAt
        override val retainUntil = proof.retainUntil
        override val verifiedAt = proof.verifiedAt
        private val bytes = proof.bytes.copyOf()
        private val hash = proof.hash.copyOf()
        override fun verificationBytes(): ByteArray = bytes.copyOf()
        override fun verificationHash(): ByteArray = hash.copyOf()
    }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        fun capture(
            jdbc: JdbcTemplate,
            capacity: JdbcComplaintCapacityStore,
            audit: AuditService,
            controls: OwnerDeleteAllControlBinding,
            routing: VersionBoundComplaintJournalRouting,
            codec: OwnerDeleteAllJournalCodecV1,
            policy: ComplaintCapacityPolicyV1,
            issuer: Any,
            candidate: InstallationDeletionCandidate,
            preflight: InstallationDeletionPreflightTuple,
            path: PersistencePhasePath,
        ): ComplaintOwnerDeleteAllOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: ComplaintOwnerDeleteAllOperation? = null
            try {
                phase.ownerDeleteAll.requireOperation(jdbc, path)
                operation = ComplaintOwnerDeleteAllOperation(phase, jdbc, controls, routing, codec, policy, issuer, candidate, preflight, path)
                phase.ownerDeleteAll.retain(operation, jdbc)
                operation.execute(capacity, audit)
                return operation
            } catch (problem: Throwable) {
                operation?.failed(problem)
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        fun preparedEvent(work: CommittedOwnerDeleteAllWork.Prepared, issuer: Any, routing: VersionBoundComplaintJournalRouting): OwnerDeleteAllJournalEventV1 {
            val retained = work as? ReleasedPrepared ?: throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
            return retained.requireOwned(issuer, routing)
        }

        fun recordedEvent(
            work: CommittedOwnerDeleteAllWork.RecordedVerified,
            issuer: Any,
            routing: VersionBoundComplaintJournalRouting,
        ): OwnerDeleteAllJournalEventV1 {
            val retained = work as? ReleasedVerified ?: throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
            return retained.requireOwned(issuer, routing)
        }

        fun authenticatedVerifier(
            work: CommittedOwnerDeleteAllWork,
            issuer: Any,
            routing: VersionBoundComplaintJournalRouting,
            expected: OwnerDeleteAllJournalEventV1,
        ): ByteArray {
            val retained = work as? Released ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            val event = retained.requireOwned(issuer, routing)
            check(expected.belongsTo(routing) && event.route == expected.route && event.semanticSha256 == expected.semanticSha256)
            check(MessageDigest.isEqual(event.canonicalBytes(), expected.canonicalBytes()))
            return retained.verifierBytes()
        }

        fun requireTuple(candidate: InstallationDeletionCandidate, tuple: InstallationDeletionPreflightTuple) {
            check(candidate.installation.scope == ComplaintDataScope.LIVE && tuple.installation == candidate.installation)
            check(tuple.submittedCredentialVersion == candidate.credentialVersion && tuple.operationKey == candidate.operationKey)
            check(MessageDigest.isEqual(tuple.fingerprint.bytes(), ComplaintDeleteAllFingerprint.of(candidate).bytes()))
        }

        private fun receipt(row: ResultSet): Receipt {
            check(requiredBoolean(row, "live") && requiredBoolean(row, "valid"))
            return Receipt(
                row.getObject("deletion_key", UUID::class.java),
                requiredLong(row, "submitted_credential_version"),
                checkNotNull(row.getBytes("fingerprint")),
                checkNotNull(row.getString("state")),
                row.getString("publication_ref"),
            )
        }

        private fun publication(row: ResultSet): Publication {
            check(requiredBoolean(row, "live") && requiredBoolean(row, "valid") && row.getString("event_kind") == "OWNER_DELETE_ALL")
            check(row.getString("canonicalizer") == "kcj-1")
            val state = row.getString("state")
            check(state in setOf("PREPARED", "VERIFIED"))
            return Publication(
                row.getObject("writer_generation", UUID::class.java),
                requiredLong(row, "journal_epoch"),
                ComplaintJournalRoutingCandidateV1(row.getString("routing_key_id"), row.getString("object_key"), row.getString("event_id")),
                row.getInt("target_count").also { check(!row.wasNull() && it in 0..100) },
                checkNotNull(row.getBytes("event_bytes")),
                checkNotNull(row.getBytes("semantic_hash")),
                state == "PREPARED",
                if (state == "VERIFIED") {
                    RecordedProof(
                        checkNotNull(row.getString("object_version")),
                        HexFormat.of().formatHex(checkNotNull(row.getBytes("ciphertext_hash"))),
                        checkNotNull(row.getTimestamp("object_created_at")).toInstant(),
                        checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                        checkNotNull(row.getTimestamp("verified_at")).toInstant(),
                        checkNotNull(row.getBytes("verification_bytes")),
                        checkNotNull(row.getBytes("verification_hash")),
                    )
                } else {
                    null
                },
            )
        }

        private fun requiredBoolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { check(!row.wasNull()) }
        private fun requiredLong(row: ResultSet, name: String): Long = row.getLong(name).also { check(!row.wasNull()) }
        private fun recoveryArray(): String = OwnerDeleteAllCapacityCharges.RECOVERY.toLongArray().joinToString(",", "{", "}")
    }
}
