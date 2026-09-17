package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPage
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPosition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryQuery
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryReadPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRequestContext
import me.manga.kira.backend.complaint.domain.rejectOwnerHistory
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.ComplaintOwnerCursorCodec
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.InstallationJwtRejectedException

/** No bean: a real TEST-only read path, never a live-mode/configuration/provenance producer. */
internal class ComplaintOwnerHistoryReadAdapter(
    private val testScope: ComplaintDataScope,
    private val jwt: InstallationJwtCodec,
    private val cursors: ComplaintOwnerCursorCodec,
    private val phases: ComplaintOwnerHistoryPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
) : ComplaintOwnerHistoryReadPort {
    init {
        require(testScope.testOnly) { "Dormant history requires TEST scope." }
    }

    @Suppress("SwallowedException")
    override fun authenticate(
        context: ComplaintOwnerHistoryRequestContext,
        bearer: String,
        query: ComplaintOwnerHistoryQuery,
    ): ComplaintOwnerHistoryAuthentication {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAVAILABLE)
        admission.startOwnerHistory(ingress)
        val token = try {
            jwt.verify(bearer)
        } catch (failure: InstallationJwtRejectedException) {
            rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAUTHORIZED)
        }
        if (token.installation.scope != testScope) rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAUTHORIZED)
        // Cursor crypto always precedes even the token-state SQL; signed claims alone still grant no read.
        val position = query.cursor?.let { cursors.decode(it, token.installation, query.limit) }
        val identity = ComplaintOwnerHistoryIdentity(token.installation, token.credentialVersion, token.expiresAt)
        val active = try {
            phases.authenticate(identity)
        } catch (failure: PersistencePhaseException) {
            rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAVAILABLE)
        }
        if (!active) rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAUTHORIZED)
        return Authenticated(this, Thread.currentThread(), ingress, identity, query, position)
    }

    override fun read(context: ComplaintOwnerHistoryRequestContext, authentication: ComplaintOwnerHistoryAuthentication): ComplaintOwnerHistoryPage {
        requireConnectionFree()
        val selected = authentication as? Authenticated ?: rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAUTHORIZED)
        val ingress = context as? ComplaintIngressContext ?: rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAVAILABLE)
        if (selected.owner !== this || selected.caller !== Thread.currentThread() || selected.context !== ingress || selected.consumed) {
            rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAUTHORIZED)
        }
        selected.identity.requireCurrent()
        selected.consumed = true
        // The current-row preflight has committed and actually released before either counter operation.
        admission.chargeOwnerHistory(ingress, selected.identity.installation, selected.admissionIdentity)
        admission.consumeOwnerHistory(ingress, selected.admissionIdentity)
        val rows = try {
            phases.page(selected.identity, selected.position, selected.query.limit)
        } catch (failure: PersistencePhaseException) {
            rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAVAILABLE)
        }
        if (!rows.contractValid) rejectOwnerHistory(ComplaintOwnerHistoryFailure.INTERNAL)
        if (!rows.authorized) rejectOwnerHistory(ComplaintOwnerHistoryFailure.UNAUTHORIZED)
        val items = rows.items.take(selected.query.limit)
        val next = if (rows.items.size > selected.query.limit) {
            val last = items.last()
            cursors.encode(selected.identity.installation, selected.query.limit, ComplaintOwnerHistoryPosition(last.createdAt, last.id))
        } else {
            null
        }
        return ComplaintOwnerHistoryPage(rows.notices, items, next)
    }

    override fun toString(): String = "ComplaintOwnerHistoryReadAdapter(TEST-only,no-mode-authority)"

    private class Authenticated(
        val owner: ComplaintOwnerHistoryReadAdapter,
        val caller: Thread,
        val context: ComplaintIngressContext,
        val identity: ComplaintOwnerHistoryIdentity,
        val query: ComplaintOwnerHistoryQuery,
        val position: ComplaintOwnerHistoryPosition?,
    ) : ComplaintOwnerHistoryAuthentication {
        val admissionIdentity = Any() // The local registry retains this opaque identity, never the actor/query-bearing attempt.
        var consumed = false
        override fun toString(): String = "ComplaintOwnerHistoryAuthentication(redacted)"
    }
}
