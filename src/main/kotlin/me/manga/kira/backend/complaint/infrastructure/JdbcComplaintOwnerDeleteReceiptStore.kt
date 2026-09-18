package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationFailure
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp

/** Ordinary, read-only current-row authentication and receipt from ONE MVCC statement. */
internal class JdbcComplaintOwnerDeleteReceiptStore(private val jdbc: JdbcTemplate, private val graph: TestOwnerDeleteLocalGraphV1) {
    private val issuer = Any()
    init { graph.requireOrdinary(jdbc) }
    fun authenticate(identity: ComplaintOwnerOperationIdentity): ComplaintOwnerDeleteReadOperation = capture(identity, null, PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION)
    fun preflight(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerDeleteTuple): ComplaintOwnerDeleteReadOperation = capture(identity, tuple, PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT)
    fun status(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerDeleteTuple): ComplaintOwnerDeleteReadOperation = capture(identity, tuple, PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS)
    private fun capture(identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerDeleteTuple?, path: PersistencePhasePath): ComplaintOwnerDeleteReadOperation =
        ComplaintOwnerDeleteReadOperation.capture(jdbc, graph, issuer, identity, tuple, path)

    fun requirePreflight(observation: ComplaintOwnerDeleteObservation, identity: ComplaintOwnerOperationIdentity, tuple: ComplaintOwnerDeleteTuple) {
        requireConnectionFree()
        val released = observation as? ReleasedOwnerDeleteObservation ?: error("Original released read required")
        released.requireOwned(issuer, identity, tuple)
        check(released.platform != null && released.receipt == null && released.failure == null)
    }
}

/** Facts released only by the private concrete read operation; not a JWT/current-mode capability. */
internal sealed interface ComplaintOwnerDeleteObservation {
    val platform: ComplaintPlatform?
    val receipt: ComplaintOwnerDeleteReceipt?
    val failure: ComplaintOwnerOperationFailure?
    val authorized: Boolean
}

private class ReleasedOwnerDeleteObservation(
    private val issuer: Any,
    private val identity: ComplaintOwnerOperationIdentity,
    private val tuple: ComplaintOwnerDeleteTuple?,
    private val path: PersistencePhasePath,
    override val platform: ComplaintPlatform?,
    override val receipt: ComplaintOwnerDeleteReceipt?,
    override val failure: ComplaintOwnerOperationFailure?,
    override val authorized: Boolean,
) : ComplaintOwnerDeleteObservation {
    fun requireOwned(selectedIssuer: Any, selectedIdentity: ComplaintOwnerOperationIdentity, selectedTuple: ComplaintOwnerDeleteTuple) {
        check(issuer === selectedIssuer && identity === selectedIdentity && tuple?.matches(selectedTuple) == true && path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT)
    }
    override fun toString(): String = "ComplaintOwnerDeleteObservation(released,redacted)"
}

internal class ComplaintOwnerDeleteReadOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val graph: TestOwnerDeleteLocalGraphV1,
    private val issuer: Any,
    private val identity: ComplaintOwnerOperationIdentity,
    private val tuple: ComplaintOwnerDeleteTuple?,
    private val path: PersistencePhasePath,
) {
    private var completed = false
    private var platform: ComplaintPlatform? = null
    private var receipt: ComplaintOwnerDeleteReceipt? = null
    private var failure: ComplaintOwnerOperationFailure? = null
    private var authorized = false
    private var released: ComplaintOwnerDeleteObservation? = null
    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && path === expected
    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) && completed
    val result: ComplaintOwnerDeleteObservation get() {
        phase.ownerDeleteRead.requireCommitted(this)
        requireConnectionFree()
        return released ?: ReleasedOwnerDeleteObservation(issuer, identity, tuple, path, platform, receipt, failure, authorized).also { released = it }
    }
    private fun execute() {
        phase.ownerDeleteRead.requireRetained(this, jdbc)
        graph.requireOrdinary(jdbc)
        identity.requireCurrent()
        check(identity.installation.scope == graph.routing.journalConfiguration.scope && (tuple == null || tuple.installation == identity.installation))
        val args = actorArguments(identity, graph)
        val selected = tuple
        jdbc.query(if (selected == null) OwnerDeletePersistenceSql.AUTHENTICATE else OwnerDeletePersistenceSql.OBSERVE, { row, _ ->
            platform = row.getString("platform")?.let(ComplaintPlatform::valueOf)
            if (platform != null && selected != null && row.getObject("actor_id") != null) {
                val stored = OwnerDeleteRows.Receipt(row)
                if (stored.comparable && !stored.matches(selected)) {
                    failure = ComplaintOwnerOperationFailure.KEY_REUSED
                } else if (stored.comparable) {
                    check(stored.valid)
                    when (stored.state) {
                        "COMPLETED" -> if (stored.visible) receipt = stored.completed()
                        "AUTHORIZED_DELETE" -> authorized = true
                        "IN_PROGRESS" -> if (path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS) failure = ComplaintOwnerOperationFailure.IN_PROGRESS
                        else -> error("Stored deletion state refused")
                    }
                }
            }
        }, *(if (selected == null) args else args.plus(selected.key))).single()
        phase.ownerDeleteRead.requireRetained(this, jdbc)
        completed = true
    }
    companion object {
        internal fun actorArguments(identity: ComplaintOwnerOperationIdentity, graph: TestOwnerDeleteLocalGraphV1): Array<Any?> = arrayOf(
            identity.installation.id, identity.installation.scope.id, identity.credentialVersion, Timestamp.from(identity.issuedAt),
            Timestamp.from(identity.expiresAt), graph.desiredSettings().configurationHashBytes(),
        )
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, graph: TestOwnerDeleteLocalGraphV1, issuer: Any, identity: ComplaintOwnerOperationIdentity,
            tuple: ComplaintOwnerDeleteTuple?, path: PersistencePhasePath): ComplaintOwnerDeleteReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.ownerDeleteRead.requireOperation(jdbc, path)
                check((tuple == null) == (path === PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION))
                return ComplaintOwnerDeleteReadOperation(phase, jdbc, graph, issuer, identity, tuple, path).also {
                    phase.ownerDeleteRead.retain(it, jdbc)
                    it.execute()
                }
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
