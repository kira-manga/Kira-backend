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
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.security.ComplaintJournalActorKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionTupleV1
import me.manga.kira.backend.security.ComplaintJournalRoutingCandidateV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteAllContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainAllPersistenceV1
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
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Actual dormant LIVE or explicitly bound TEST SQL producer, not a route, full-D/current-capability producer or publisher.
 * Every supplied binding is independent of observed rows; matching it grants only local custody.
 * No source path here acquires a secret, generates a data key, erases content or dispatches S3.
 */
internal class JdbcComplaintOwnerDeleteAllStore private constructor(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    private val audit: AuditService,
    internal val routing: OwnerDeleteAllJournalBindingV1,
    private val policy: ComplaintCapacityPolicyV1,
    internal val controls: OwnerDeleteAllControlBinding,
) {
    constructor(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService,
        desired: ComplaintInstallationDesiredSettings.Configured, routing: VersionBoundComplaintJournalRouting,
        codec: OwnerDeleteAllJournalCodecV1, policy: ComplaintCapacityPolicyV1, catalog: CatalogCommonHeadEvidence,
        process: OwnerDeleteAllProcessBinding? = null) : this(jdbc, capacity, audit, OwnerDeleteAllJournalBindingV1(routing, codec),
            policy, OwnerDeleteAllControlBinding(desired, routing, catalog, process)) {
        process?.requireDeletion(jdbc)
        process?.requirePolicy(policy)
    }
    constructor(jdbc: JdbcTemplate, capacity: JdbcComplaintCapacityStore, audit: AuditService,
        graph: TestOwnerDeleteLocalGraphV1, codec: TestOwnerDeleteJournalCodecV1?) : this(jdbc, capacity, audit,
            OwnerDeleteAllJournalBindingV1(graph.routing, codec), graph.policy, OwnerDeleteAllControlBinding(graph)) {
        graph.requireDeletion(jdbc)
        check((graph.recoveryRegistration == null) == (codec != null))
    }
    val scope get() = routing.scope
    internal val testGraph get() = checkNotNull(controls.testGraph)
    private val issuer = Any()

    internal fun requireInitialIssuer(selected: Any, selectedJdbc: JdbcTemplate) {
        check(selected === issuer && selectedJdbc === jdbc)
        testGraph.requireDeletion(selectedJdbc)
    }

    internal fun lockBoundVerification(selected: JdbcTemplate) {
        check(selected.dataSource === jdbc.dataSource)
        controls.testGraph?.requireDeletion(selected)
        if (controls.requiresBoundVerification) controls.lock(selected, authorizingPath = false)
    }

    fun authorize(candidate: InstallationDeletionCandidate, preflight: InstallationDeletionPreflightTuple): ComplaintOwnerDeleteAllOperation =
        capture(candidate, preflight, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE)

    fun reload(candidate: InstallationDeletionCandidate, preflight: InstallationDeletionPreflightTuple): ComplaintOwnerDeleteAllOperation =
        capture(candidate, preflight, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD)

    private fun capture(candidate: InstallationDeletionCandidate, preflight: InstallationDeletionPreflightTuple, path: PersistencePhasePath): ComplaintOwnerDeleteAllOperation {
        check(controls.testGraph?.recoveryRegistration == null)
        return ComplaintOwnerDeleteAllOperation.capture(jdbc, capacity, audit, controls, routing, policy, issuer, candidate, preflight, path, source = this)
    }

    internal fun reloadRegistered(original: TestRunOwnerDeleteAllContinuationV1): ComplaintOwnerDeleteAllOperation =
        ComplaintOwnerDeleteAllOperation.capture(jdbc, capacity, audit, controls, routing, policy, issuer, null, null,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD, original)

    internal fun testPreparedEvent(work: CommittedOwnerDeleteAllWork.Prepared) = routing.testEvent(preparedEvent(work))


    /** Custody only: future provider composition must additionally own actual runtime authority and its disjoint lane. */
    fun preparedEvent(work: CommittedOwnerDeleteAllWork.Prepared): OwnerDeleteAllJournalEventV1 {
        requireConnectionFree()
        controls.testGraph?.requireUnchanged()
        return ComplaintOwnerDeleteAllOperation.preparedEvent(work, issuer, routing)
    }

    /** Exact private reload custody; the fixed VERIFY consumer must still strictly validate its retained proof. */
    fun recordedEvent(work: CommittedOwnerDeleteAllWork.RecordedVerified): OwnerDeleteAllJournalEventV1 {
        requireConnectionFree()
        controls.testGraph?.requireUnchanged()
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
    private val routing: OwnerDeleteAllJournalBindingV1,
    private val policy: ComplaintCapacityPolicyV1,
    private val issuer: Any,
    private val candidate: InstallationDeletionCandidate?,
    private val preflight: InstallationDeletionPreflightTuple?,
    private val path: PersistencePhasePath,
    internal val original: TestRunOwnerDeleteAllContinuationV1?,
) {
    private val sql = if (routing.scope.testOnly) OwnerDeleteAllPersistenceSql.test(routing.scope) else OwnerDeleteAllPersistenceSql.live
    private var verifier: ByteArray? = candidate?.credential?.verifierBytes()
    private val request: InstallationDeletionCandidate get() = checkNotNull(candidate)
    private val comparison: InstallationDeletionPreflightTuple get() = checkNotNull(preflight)
    private var stage = Stage.RETAINED
    private var newAuthorization = false
    private var initialRecoveryInserted = false
    private var initialRoute: ComplaintJournalRoutingCandidateV1? = null
    private var checkpointTime: Instant? = null
    private var allocation: JdbcComplaintCapacityStore.LockedOwnerDeleteAll? = null
    private var canonical: OwnerDeleteAllJournalEventV1? = null
    private var authorizationTime: Instant? = null
    private var prepared = false
    private var recordedProof: RecordedProof? = null
    private var registeredRecovery: ComplaintCapacityVector? = null
    private var released: CommittedOwnerDeleteAllWork? = null

    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected
    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) &&
        stage === Stage.COMPLETE && canonical != null && (prepared || recordedProof != null) && allocation?.completedFor(this) == true

    val result: CommittedOwnerDeleteAllWork
        get() {
            phase.ownerDeleteAll.requireCommitted(this)
            requireConnectionFree()
            controls.testGraph?.let { it.initialDeletion?.requireGraph(it) }
            original?.requireReleasedReload()
            return released ?: (
                if (prepared) {
                    ReleasedPrepared(issuer, routing, checkNotNull(canonical), checkNotNull(verifier))
                } else {
                    ReleasedVerified(issuer, routing, checkNotNull(canonical), checkNotNull(verifier), checkNotNull(recordedProof))
                }
                ).also { released = it }
        }

    private fun execute(capacity: JdbcComplaintCapacityStore, audit: AuditService) {
        requireAt(Stage.RETAINED)
        if (original != null) { reloadRegistered(capacity); return }
        requireTuple(request, comparison)
        check(request.installation.scope == routing.scope)
        val authorizingPath = path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE
        if (authorizingPath) phase.ownerDeleteAll.claimAuthorize(this, jdbc, comparison)
        stage = Stage.CONTROL
        val control = controls.lock(jdbc, authorizingPath)
        requireRetained()
        stage = Stage.RECEIPT
        val receipts = jdbc.query(sql.LOCK_RECEIPTS, { row, _ -> receipt(row) }, request.installation.id)
        check(receipts.size <= 1) // The fixed two-row sentinel never picks an arbitrary existing key.
        if (receipts.isEmpty()) {
            check(authorizingPath)
            phase.ownerDeleteAll.checkReceiptWrite(this, jdbc)
            check(
                jdbc.update(
                    sql.INSERT_RECEIPT,
                    request.installation.id,
                    request.operationKey,
                    request.credentialVersion,
                    comparison.fingerprint.bytes(),
                ) == 1,
            )
            newAuthorization = true
            checkInitialCheckpoint() // Only this newly inserted receipt, not replay or a supplied snapshot.
            authorizeNew(capacity, audit, control)
        } else {
            requireInitialReplayObservation(receipts.single())
            reloadExisting(capacity, control, receipts.single())
        }
        requireRetained()
        check(checkNotNull(allocation).completedFor(this))
        checkInitialCheckpoint()
        stage = Stage.COMPLETE
    }

    /** No submitted secret or synthesized preflight. The registered original selects an existing
     * primary and validates its own stored verifier only after controls, receipt, publication,
     * reservation, counters and SEALED run. Nothing here can claim or write a new authorization. */
    private fun reloadRegistered(capacity: JdbcComplaintCapacityStore) {
        val registered = checkNotNull(original)
        check(candidate == null && preflight == null && path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD)
        registered.requirePersistenceForStore(checkNotNull(controls.testGraph), jdbc, this)
        stage = Stage.CONTROL
        val control = controls.lock(jdbc, false)
        val appliedSql = OwnerDeleteAllApplySql.test(routing.scope)
        stage = Stage.RECEIPT
        val receipt = jdbc.query(appliedSql.LOCK_RECEIPTS, { row, _ -> OwnerDeleteAllApplyRows.receipt(row) }, registered.actorId).single()
        check(receipt.key == registered.operationKey && receipt.state in setOf("AUTHORIZED_DELETE", "COMPLETED"))
        stage = Stage.RELOAD_PUBLICATION
        val publication = jdbc.query(sql.LOCK_REGISTERED_PUBLICATION, { row, _ ->
            publication(row, allowApplied = true) to checkNotNull(row.getTimestamp("created_at")).toInstant()
        }, receipt.reference).single()
        val row = publication.first
        val event = routing.restore(row.bytes, row.route.routingKeyId)
        check(event.belongsTo(routing) && event.tuple.actorId == registered.actorId && event.tuple.operationKey == registered.operationKey &&
            event.tuple.credentialVersion == receipt.version && event.tuple.scope == routing.scope && event.tuple.epoch == row.epoch && event.route == row.route &&
            event.route.eventId == receipt.reference && event.complaintIds().size == row.targetCount && row.writer == controls.writer &&
            MessageDigest.isEqual(HexFormat.of().parseHex(event.semanticSha256), row.semanticHash) &&
            MessageDigest.isEqual(Base64.getUrlDecoder().decode(event.tuple.encodedFingerprint()), receipt.fingerprint))
        check(publication.second == receipt.authorizedAt)
        check((row.state == "APPLIED") == (receipt.state == "COMPLETED"))
        val persistedProof = row.verification?.also { proof ->
            val parsed = OwnerDeleteAllVerificationCodecV1(routing).parse(proof.bytes, event)
            check(parsed.objectVersion == proof.objectVersion && parsed.ciphertextSha256 == proof.ciphertextSha256 &&
                Instant.parse(parsed.objectCreatedAt) == proof.objectCreatedAt && Instant.parse(parsed.retainUntil) == proof.retainUntil &&
                Instant.parse(parsed.verifiedAt) == proof.verifiedAt)
        }
        if (row.prepared) registered.requirePreparedReload()
        if (receipt.state == "COMPLETED") {
            val external = checkNotNull(receipt.external)
            val proof = checkNotNull(persistedProof)
            check(receipt.completedAt == row.appliedAt && external.eventId == event.route.eventId && external.epoch == event.tuple.epoch &&
                external.version == proof.objectVersion && HexFormat.of().formatHex(external.hash) == proof.ciphertextSha256)
        }
        control.requireContinuation(event.tuple.epoch, row.prepared)
        canonical = event
        prepared = row.prepared
        recordedProof = row.verification
        stage = Stage.RELOAD_RESERVATION
        val reserve = jdbc.query(appliedSql.LOCK_RECOVERY, { result, _ -> OwnerDeleteAllApplyRows.recovery(result) }, receipt.reference).single()
        check(reserve.promise == OwnerDeleteAllCapacityCharges.RECOVERY && reserve.used.fitsWithin(reserve.promise))
        val pendingAfterAlias = receipt.state == "AUTHORIZED_DELETE" && reserve.state == "PARTIAL"
        check(reserve.state == if (receipt.state == "COMPLETED" || pendingAfterAlias) "PARTIAL" else "RESERVED")
        if (receipt.state == "COMPLETED") check((OwnerDeleteAllCapacityCharges.APPLIED + ComplaintCapacityCharges.AUDIT).fitsWithin(reserve.used))
        registeredRecovery = reserve.remaining
        stage = Stage.COUNTERS_READY
        check(capacity.lockForOwnerDeleteAll(this).settledFor(this))
        stage = Stage.DOMAIN
        controls.lockRun(jdbc, false)
        registered.requireEarlierAuthorization(receipt.authorizedAt)
        val expectedState = if (receipt.state == "COMPLETED" || pendingAfterAlias) "DELETED" else "DELETION_PENDING"
        val installation = jdbc.query(appliedSql.LOCK_INSTALLATION, { result, _ -> OwnerDeleteAllApplyRows.installation(result) }, registered.actorId).single()
        val credential = jdbc.query(appliedSql.LOCK_CREDENTIAL, { result, _ -> OwnerDeleteAllApplyRows.credential(result) }, registered.actorId).single()
        check(installation.state == expectedState && credential.state == expectedState &&
            credential.credentialVersion == if (expectedState == "DELETED") Math.addExact(receipt.version, 1) else receipt.version)
        if (receipt.state == "COMPLETED" || pendingAfterAlias) {
            val deletedAt = checkNotNull(installation.terminalAt)
            check(credential.deletedAt == deletedAt && credential.expiresAt == deletedAt.plus(java.time.Duration.ofHours(192)))
        } else check(installation.terminalAt == null && credential.deletedAt == null && credential.expiresAt == null)
        val now = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { result, _ -> result.getTimestamp(1).toInstant() }))
        check(!receipt.authorizedAt.isAfter(now) && receipt.completedAt?.isAfter(now) != true && reserve.convertedAt?.isAfter(now) != true)
        persistedProof?.let { check(!it.verifiedAt.isAfter(now) && it.retainUntil.isAfter(now)) }
        if (receipt.state == "COMPLETED") {
            // Completed replay has no mutation or new publication branch. The same retained
            // N/P/L locks protect these bounded family/accounting rereads after domain locks.
            check(OwnerDeleteAllApplyRows.completedReplayWindowsLive(receipt.expiresAt, credential.expiresAt, now))
            val retained = TestOrdinaryDrainAllPersistenceV1.requireCompletedReplayFacts(jdbc, routing, event)
            check(retained.recovery.promise == reserve.promise && retained.recovery.used == reserve.used &&
                retained.recovery.lastAppliedAt == reserve.convertedAt)
        } else {
            persistedProof?.let { check(!receipt.authorizedAt.isAfter(it.verifiedAt)) }
            if (pendingAfterAlias) TestOrdinaryDrainAllPersistenceV1.requirePendingAliasFacts(jdbc, routing, event, receipt, reserve, now)
        }
        verifier = credential.verifier.copyOf()
        requireRetained()
        stage = Stage.COMPLETE
    }

    private fun authorizeNew(capacity: JdbcComplaintCapacityStore, audit: AuditService, control: OwnerDeleteAllControlBinding.Locked) {
        val tuple = journalTuple(control.epoch)
        val routes = routing.derive(tuple)
        val active = routing.active(tuple)
        initialRoute = active
        // No locking of an existing publication in the new-receipt path. Occupied retained candidates
        // without their exact receipt require recovery, never another active-key/epoch assignment.
        routes.forEach { route ->
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
        check(jdbc.update(sql.INSERT_RECOVERY, active.eventId, active.eventId, recoveryArray()) == 1)
        initialRecoveryInserted = true
        checkInitialCheckpoint()
        stage = Stage.DOMAIN
        lockInstallation("ACTIVE")
        // Existing state index; intentionally conservative for this dormant slice. No JSON owner scan,
        // reverse receipt/publication lock or reliance on this observation alone for serialization.
        check(jdbc.queryForObject(sql.NO_PENDING, Boolean::class.java) == true)
        val targets = lockTargets()
        val event = routing.canonicalize(tuple, targets, active.routingKeyId)
        check(event.belongsTo(routing) && event.route == active)
        canonical = event
        stage = Stage.PENDING
        checkWrite()
        check(jdbc.update(sql.PEND_ID, request.installation.id) == 1)
        checkWrite()
        check(
            jdbc.update(
                sql.PEND_CREDENTIAL,
                request.installation.id,
                request.credentialVersion,
                request.credential.verifierBytes(),
            ) == 1,
        )
        stage = Stage.PUBLICATION
        checkWrite()
        authorizationTime = checkNotNull(
            jdbc.queryForObject(
                sql.INSERT_PUBLICATION,
                { row, _ -> row.getTimestamp(1).toInstant() },
                event.route.eventId, controls.writer, tuple.epoch, targets.size, event.route.routingKeyId, event.route.objectKey,
                event.canonicalBytes(), HexFormat.of().parseHex(event.semanticSha256),
            ),
        )
        checkWrite()
        check(
            jdbc.update(
                sql.AUTHORIZE_RECEIPT,
                event.route.eventId,
                Timestamp.from(checkNotNull(authorizationTime)),
                request.installation.id,
                request.operationKey,
            ) == 1,
        )
        stage = Stage.AUDIT
        checkWrite()
        audit.recordInstallationDeleteAuthorization(request.credentialVersion, paid, checkNotNull(authorizationTime))
        check(paid.completedFor(this))
        prepared = true
    }

    private fun reloadExisting(capacity: JdbcComplaintCapacityStore, control: OwnerDeleteAllControlBinding.Locked, receipt: Receipt) {
        check(receipt.key == request.operationKey && receipt.version == request.credentialVersion)
        check(MessageDigest.isEqual(receipt.fingerprint, comparison.fingerprint.bytes()))
        check(receipt.state == "AUTHORIZED_DELETE") // A completed race is retried through the read-only preflight branch.
        val reference = checkNotNull(receipt.publication)
        stage = Stage.RELOAD_PUBLICATION
        val publication = jdbc.query(sql.LOCK_PUBLICATION, { row, _ -> publication(row) }, reference).single()
        requireRetained()
        val payload = OwnerDeleteAllJournalJsonV1(routing.limits.decoder).payload(publication.bytes)
        val targets = payload.complaintIds.map(ComplaintIdentifiers::resourceId)
        val event = routing.canonicalize(journalTuple(publication.epoch), targets, publication.route.routingKeyId)
        check(event.belongsTo(routing) && publication.writer == controls.writer && publication.route == event.route && reference == event.route.eventId)
        check(publication.targetCount == targets.size && publication.bytes.contentEquals(event.canonicalBytes()))
        check(MessageDigest.isEqual(publication.semanticHash, HexFormat.of().parseHex(event.semanticSha256)))
        prepared = publication.prepared
        recordedProof = publication.verification
        control.requireContinuation(publication.epoch, prepared)
        canonical = event
        stage = Stage.RELOAD_RESERVATION
        check(jdbc.query(sql.LOCK_RECOVERY, { row, _ -> requiredBoolean(row, "matches") }, recoveryArray(), reference).single())
        stage = Stage.COUNTERS_READY
        check(capacity.lockForOwnerDeleteAll(this).settledFor(this))
        stage = Stage.DOMAIN
        lockInstallation("DELETION_PENDING")
        // No target resnapshot, audit, counter UPDATE, publication rewrite or current-active-key selection.
    }

    private fun lockInstallation(expectedState: String) {
        requireAt(Stage.DOMAIN)
        controls.lockRun(jdbc, expectedState == "ACTIVE")
        val reservation = jdbc.query(sql.LOCK_INSTALLATION_ID, { row, _ ->
            requiredBoolean(row, "live") && row.getString("state") == expectedState
        }, request.installation.id).singleOrNull() == true
        val credential = jdbc.query(sql.LOCK_CREDENTIAL, { row, _ ->
            requiredBoolean(row, "live") && requiredBoolean(row, "nonterminal") && row.getString("state") == expectedState &&
                requiredLong(row, "credential_version") == request.credentialVersion &&
                MessageDigest.isEqual(checkNotNull(row.getBytes("secret_verifier")), checkNotNull(verifier))
        }, request.installation.id).singleOrNull() == true
        requireRetained()
        check(reservation && credential)
        checkInitialCheckpoint() // DB time after run/reservation/credential waits, never a pre-wait projection.
    }

    private fun lockTargets(): List<UUID> {
        requireAt(Stage.DOMAIN)
        val targets = jdbc.query(sql.OWNER_TARGETS, { row, _ -> row.getObject(1, UUID::class.java) }, request.installation.id)
        check(targets.size <= 100)
        stage = Stage.RESOURCES
        if (targets.isEmpty()) return targets
        val targetArray = targets.joinToString(",", "{", "}")
        val reservations = jdbc.query(sql.LOCK_RESOURCES, { row, _ ->
            check(requiredBoolean(row, "live") && row.getString("state") == "LIVE")
            row.getObject("id", UUID::class.java)
        }, targetArray)
        check(reservations == targets)
        val content = jdbc.query(sql.LOCK_CONTENT, { row, _ ->
            check(requiredBoolean(row, "live") && row.getObject("owner_id", UUID::class.java) == request.installation.id)
            check(row.getString("ownership") == "INSTALLATION" && row.getString("kind") in setOf("REPORT", "REPLY") && requiredLong(row, "version") > 0)
            row.getObject("id", UUID::class.java)
        }, targetArray)
        check(content == targets)
        requireRetained()
        checkInitialCheckpoint()
        return targets
    }

    private fun requireInitialReplayObservation(receipt: Receipt) {
        val initial = controls.testGraph?.initialDeletion ?: return
        val args = initial.observationIdentityArguments().plus(elements = arrayOf<Any?>(request.installation.id, request.installation.scope.id))
        val observed = jdbc.query(InstallationDeletionPreflightSnapshot.REGISTERED_SQL, { row, _ ->
            check(row.getBoolean("registered_current_identity") && !row.wasNull())
            InstallationDeletionPreflightSnapshot.read(row, request.installation)
        }, *args).single().compare(request)
        check(observed is InstallationDeletionPreflightSnapshot.Comparison.Authorized && observed.publicationReference == receipt.publication)
    }

    internal fun initialCheckpointArguments(original: TestOwnerDeleteProcessBindingV1): Array<Any?> {
        requireRetained()
        check(controls.testGraph?.initialDeletion === original && newAuthorization && this.original == null &&
            path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE && stage in setOf(Stage.RECEIPT, Stage.COUNTERS,
                Stage.RESERVING, Stage.DOMAIN, Stage.RESOURCES, Stage.PENDING, Stage.PUBLICATION, Stage.AUDIT))
        val current = canonical
        val route = current?.route ?: initialRoute
        return arrayOf("OWNER_DELETE_ALL", request.installation.id, request.operationKey, comparison.fingerprint.bytes(),
            request.credentialVersion, current?.complaintIds().orEmpty().joinToString(",", "{", "}"),
            route?.eventId, route?.objectKey, route?.routingKeyId, current?.canonicalBytes(),
            current?.semanticSha256?.let(HexFormat.of()::parseHex), recoveryArray(), initialRecoveryInserted,
            authorizationTime?.let(Timestamp::from), null, null, null)
    }

    private fun checkInitialCheckpoint() {
        if (!newAuthorization) return
        controls.testGraph?.initialDeletion?.let { initial ->
            val now = initial.checkCurrent(this)
            check(checkpointTime?.let { !now.isBefore(it) } != false)
            checkpointTime = now
        }
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
        registeredRecovery?.let { check(it.fitsWithin(ledger.balance.recoveryReserved)) }
        checkInitialCheckpoint() // Already locked counters; no backward control acquisition or budget restart.
        phase.ownerDeleteAll.checkCapacity(this, selected, ledger)
    }

    internal fun requiredReloadRecovery(selected: JdbcTemplate): ComplaintCapacityVector {
        requireSelected(selected)
        check(stage === Stage.COUNTERS && !newAuthorization)
        // Only the registered path can carry the validated remaining PARTIAL promise; old LIVE
        // and lower pending reloads still require the complete immutable RESERVED vector.
        return registeredRecovery ?: OwnerDeleteAllCapacityCharges.RECOVERY
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
        check(entry.submittedVersion == request.credentialVersion && entry.createdAt == authorizationTime)
        return phase.ownerDeleteAll.connection(this, selected)
    }

    internal fun requireAuditWrite(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAll, selected: JdbcTemplate) {
        requireSelected(selected)
        check(stage === Stage.AUDIT && newAuthorization && allocation === value && value.settledFor(this))
        checkWrite()
    }

    internal fun auditScope(value: JdbcComplaintCapacityStore.LockedOwnerDeleteAll, selected: JdbcTemplate): ComplaintDataScope {
        requireAuditWrite(value, selected)
        return routing.scope
    }

    private fun journalTuple(epoch: Long) = if (routing.scope.testOnly) ComplaintJournalDeletionTupleV1.testOwnerDeleteAll(
        epoch, request.installation.id, request.credentialVersion, request.operationKey, comparison.fingerprint.bytes(), routing.scope,
    ) else ComplaintJournalDeletionTupleV1(
        epoch,
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL,
        ComplaintJournalActorKindV1.INSTALLATION,
        request.installation.id,
        request.credentialVersion,
        request.operationKey,
        comparison.fingerprint.bytes(),
        routing.scope,
    )

    private fun requireRetained() {
        phase.ownerDeleteAll.requireRetained(this, jdbc)
        controls.testGraph?.requireDeletion(jdbc)
        phase.requireRegisteredInitialDeletion(controls.testGraph, jdbc)
        original?.let { phase.requireTestRunOwnerDeleteAllReload(it, checkNotNull(controls.testGraph), jdbc) }
    }
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
        val state: String,
        val appliedAt: Instant?,
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
        private val routing: OwnerDeleteAllJournalBindingV1,
        private val event: OwnerDeleteAllJournalEventV1,
        verifier: ByteArray,
    ) : CommittedOwnerDeleteAllWork {
        private val verifier = verifier.copyOf()
        override fun canonicalBytes(): ByteArray = event.canonicalBytes()
        fun verifierBytes(): ByteArray = verifier.copyOf()
        fun requireOwned(selectedIssuer: Any, selectedRouting: OwnerDeleteAllJournalBindingV1): OwnerDeleteAllJournalEventV1 {
            check(issuer === selectedIssuer && routing === selectedRouting && event.belongsTo(selectedRouting))
            return event
        }

        override fun toString(): String = "CommittedOwnerDeleteAllWork(custody-only,redacted)"
    }
    private class ReleasedPrepared(issuer: Any, routing: OwnerDeleteAllJournalBindingV1, event: OwnerDeleteAllJournalEventV1, verifier: ByteArray) :
        Released(issuer, routing, event, verifier),
        CommittedOwnerDeleteAllWork.Prepared
    private class ReleasedVerified(
        issuer: Any,
        routing: OwnerDeleteAllJournalBindingV1,
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
            routing: OwnerDeleteAllJournalBindingV1,
            policy: ComplaintCapacityPolicyV1,
            issuer: Any,
            candidate: InstallationDeletionCandidate?,
            preflight: InstallationDeletionPreflightTuple?,
            path: PersistencePhasePath,
            original: TestRunOwnerDeleteAllContinuationV1? = null,
            source: JdbcComplaintOwnerDeleteAllStore? = null,
        ): ComplaintOwnerDeleteAllOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: ComplaintOwnerDeleteAllOperation? = null
            try {
                phase.ownerDeleteAll.requireOperation(jdbc, path)
                if (controls.testGraph?.initialDeletion != null) {
                    checkNotNull(source).requireInitialIssuer(issuer, jdbc)
                    phase.requireInitialAllDeleteStore(source)
                }
                original?.let { phase.requireTestRunOwnerDeleteAllReload(it, checkNotNull(controls.testGraph), jdbc) }
                operation = ComplaintOwnerDeleteAllOperation(phase, jdbc, controls, routing, policy, issuer, candidate, preflight, path, original)
                phase.ownerDeleteAll.retain(operation, jdbc)
                operation.execute(capacity, audit)
                return operation
            } catch (problem: Throwable) {
                operation?.failed(problem)
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        fun preparedEvent(work: CommittedOwnerDeleteAllWork.Prepared, issuer: Any, routing: OwnerDeleteAllJournalBindingV1): OwnerDeleteAllJournalEventV1 {
            val retained = work as? ReleasedPrepared ?: throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
            return retained.requireOwned(issuer, routing)
        }

        fun recordedEvent(
            work: CommittedOwnerDeleteAllWork.RecordedVerified,
            issuer: Any,
            routing: OwnerDeleteAllJournalBindingV1,
        ): OwnerDeleteAllJournalEventV1 {
            val retained = work as? ReleasedVerified ?: throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
            return retained.requireOwned(issuer, routing)
        }

        fun authenticatedVerifier(
            work: CommittedOwnerDeleteAllWork,
            issuer: Any,
            routing: OwnerDeleteAllJournalBindingV1,
            expected: OwnerDeleteAllJournalEventV1,
        ): ByteArray {
            val retained = work as? Released ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            val event = retained.requireOwned(issuer, routing)
            check(expected.belongsTo(routing) && event.route == expected.route && event.semanticSha256 == expected.semanticSha256)
            check(MessageDigest.isEqual(event.canonicalBytes(), expected.canonicalBytes()))
            return retained.verifierBytes()
        }

        fun requireTuple(candidate: InstallationDeletionCandidate, tuple: InstallationDeletionPreflightTuple) {
            check(tuple.installation == candidate.installation)
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

        private fun publication(row: ResultSet, allowApplied: Boolean = false): Publication {
            check(requiredBoolean(row, "live") && requiredBoolean(row, "valid") && row.getString("event_kind") == "OWNER_DELETE_ALL")
            check(row.getString("canonicalizer") == "kcj-1")
            val state = row.getString("state")
            check(state in if (allowApplied) setOf("PREPARED", "VERIFIED", "APPLIED") else setOf("PREPARED", "VERIFIED"))
            return Publication(
                row.getObject("writer_generation", UUID::class.java),
                requiredLong(row, "journal_epoch"),
                ComplaintJournalRoutingCandidateV1(row.getString("routing_key_id"), row.getString("object_key"), row.getString("event_id")),
                row.getInt("target_count").also { check(!row.wasNull() && it in 0..100) },
                checkNotNull(row.getBytes("event_bytes")),
                checkNotNull(row.getBytes("semantic_hash")),
                state,
                row.getTimestamp("applied_at")?.toInstant(),
                state == "PREPARED",
                if (state == "VERIFIED" || state == "APPLIED") {
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
