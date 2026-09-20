package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.util.UUID

/** Current normal-user principal and exact receipt share ONE MVCC statement before proof or quota. */
internal class JdbcComplaintAdminDeleteReceiptStore(private val jdbc: JdbcTemplate, private val graph: TestOwnerDeleteLocalGraphV1) {
    private val issuer = Any()
    private val authentication = JdbcComplaintAdminReadStore(jdbc, graph.routing.journalConfiguration.scope)
    fun authenticate(identity: ComplaintAdminReadIdentity): ComplaintAdminReadOperation = authentication.authenticateContentIdentity(identity)
    init { graph.requireOrdinary(jdbc); check(graph.recoveryRegistration == null && graph.routing.journalConfiguration.adminDelete) }
    fun preflight(identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminDeleteTuple): ComplaintAdminDeleteReadOperation =
        ComplaintAdminDeleteReadOperation.capture(jdbc, graph, issuer, identity, tuple)
    fun requirePreflight(observation: ComplaintAdminDeleteObservation, identity: ComplaintAdminReadIdentity, tuple: ComplaintAdminDeleteTuple) {
        requireConnectionFree()
        val released = observation as? ReleasedAdminDeleteObservation ?: error("Original released read required")
        released.requireOwned(issuer, identity, tuple)
        check(released.receipt == null && released.failure == null)
    }
}

internal sealed interface ComplaintAdminDeleteObservation {
    val receipt: ComplaintAdminDeleteReceipt?
    val failure: ComplaintAdminDeleteFailure?
    val authorizedGrantId: UUID?
    val authorized: Boolean get() = authorizedGrantId != null
}
private class ReleasedAdminDeleteObservation(
    private val issuer: Any, private val identity: ComplaintAdminReadIdentity, private val tuple: ComplaintAdminDeleteTuple,
    override val receipt: ComplaintAdminDeleteReceipt?, override val failure: ComplaintAdminDeleteFailure?, override val authorizedGrantId: UUID?,
) : ComplaintAdminDeleteObservation {
    fun requireOwned(selected: Any, actor: ComplaintAdminReadIdentity, candidate: ComplaintAdminDeleteTuple) {
        check(issuer === selected && identity === actor && tuple.matches(candidate))
    }
    override fun toString(): String = "ComplaintAdminDeleteObservation(released,redacted)"
}
internal class ComplaintAdminDeleteReadOperation private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, private val graph: TestOwnerDeleteLocalGraphV1,
    private val issuer: Any, private val identity: ComplaintAdminReadIdentity, private val tuple: ComplaintAdminDeleteTuple,
) {
    private var completed = false
    private var receipt: ComplaintAdminDeleteReceipt? = null
    private var failure: ComplaintAdminDeleteFailure? = null
    private var authorizedGrantId: UUID? = null
    private var released: ComplaintAdminDeleteObservation? = null
    fun belongsTo(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = phase === selected && expected === PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT
    fun completedFor(selected: PersistencePhaseContext, expected: PersistencePhasePath): Boolean = belongsTo(selected, expected) && completed
    val result: ComplaintAdminDeleteObservation get() {
        phase.adminDeleteRead.requireCommitted(this); requireConnectionFree()
        return released ?: ReleasedAdminDeleteObservation(issuer, identity, tuple, receipt, failure, authorizedGrantId).also { released = it }
    }
    private fun execute() {
        phase.adminDeleteRead.requireRetained(this, jdbc); graph.requireOrdinary(jdbc); identity.requireCurrent()
        check(identity.scope == graph.routing.journalConfiguration.scope && tuple.scope == identity.scope && tuple.actor == identity.actor)
        jdbc.query(AdminDeletePersistenceSql.OBSERVE, { row, _ ->
            failure = when (row.getString("verdict")) {
                "UNAUTHORIZED" -> ComplaintAdminDeleteFailure.UNAUTHORIZED
                "FORBIDDEN" -> ComplaintAdminDeleteFailure.FORBIDDEN
                "ALLOWED" -> null
                else -> error("Stored principal refused")
            }
            if (failure == null && row.getObject("actor_id") != null) {
                val stored = AdminDeleteRows.Receipt(row)
                if (stored.comparable && !stored.matches(tuple)) failure = ComplaintAdminDeleteFailure.KEY_REUSED
                else if (stored.comparable) {
                    check(stored.valid)
                    when (stored.state) {
                        "COMPLETED" -> if (stored.visible) receipt = stored.completed()
                        "AUTHORIZED_DELETE" -> authorizedGrantId = checkNotNull(stored.consumedGrantId)
                        "IN_PROGRESS" -> failure = ComplaintAdminDeleteFailure.IN_PROGRESS
                        else -> error("Stored deletion state refused")
                    }
                }
            }
        }, *actorArguments(identity).plus(tuple.key)).single()
        phase.adminDeleteRead.requireRetained(this, jdbc); completed = true
    }
    companion object {
        internal fun actorArguments(identity: ComplaintAdminReadIdentity): Array<Any?> = arrayOf(identity.actor, identity.credentialVersion,
            identity.validFrom?.let(Timestamp::from), Timestamp.from(identity.validUntil))
        @Suppress("TooGenericExceptionCaught")
        fun capture(jdbc: JdbcTemplate, graph: TestOwnerDeleteLocalGraphV1, issuer: Any, identity: ComplaintAdminReadIdentity,
            tuple: ComplaintAdminDeleteTuple): ComplaintAdminDeleteReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.adminDeleteRead.requireOperation(jdbc, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT)
                return ComplaintAdminDeleteReadOperation(phase, jdbc, graph, issuer, identity, tuple).also { phase.adminDeleteRead.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { phase.recordFailure(problem); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
    }
}
