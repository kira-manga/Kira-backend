package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminDetailQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadResult
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminReadPhaseExecutor
import me.manga.kira.backend.security.ComplaintAdminCursorCodec
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Duration

/** Real normal-user crypto/current-ADMIN reads. No Spring registration or TEST projection authority. */
internal class ComplaintAdminReadAdapter(
    private val testScope: ComplaintDataScope,
    @Qualifier("jwtDecoder") userDecoder: JwtDecoder,
    private val cursors: ComplaintAdminCursorCodec,
    private val phases: ComplaintAdminReadPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
    userClockSkew: Duration,
) : ComplaintAdminReadPort {
    private val identities = ComplaintAdminJwtIdentityDecoder(testScope, userDecoder, userClockSkew)

    init {
        require(testScope.testOnly && !userClockSkew.isNegative && userClockSkew <= Duration.ofSeconds(60)) { "Invalid TEST Admin read composition." }
    }

    @Suppress("SwallowedException")
    override fun authenticate(context: ComplaintAdminReadRequestContext, bearer: String, query: ComplaintAdminReadQuery): ComplaintAdminReadAuthentication {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        when (query) {
            is ComplaintAdminSearchQuery -> admission.startAdminSearch(ingress)
            is ComplaintAdminDetailQuery -> admission.startAdminDetail(ingress)
        }
        val identity = identities.decode(bearer)
        // This proves only a signed token identity/filter binding, never the current DB ADMIN role.
        val position = (query as? ComplaintAdminSearchQuery)?.let { search -> search.cursor?.let { cursors.decode(it, identity.actor, search) } }
        val current = try {
            phases.authenticate(identity)
        } catch (failure: PersistencePhaseException) {
            rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        }
        requireAllowed(current)
        if (query.scope != testScope) rejectAdminRead(ComplaintAdminReadFailure.NOT_FOUND)
        identity.requireCurrent()
        return Authenticated(this, Thread.currentThread(), ingress, identity, query, position)
    }

    @Suppress("SwallowedException")
    override fun read(context: ComplaintAdminReadRequestContext, authentication: ComplaintAdminReadAuthentication): ComplaintAdminReadResult {
        requireConnectionFree()
        val selected = authentication as? Authenticated ?: rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
        val ingress = context as? ComplaintIngressContext ?: rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        if (selected.owner !== this || selected.caller !== Thread.currentThread() || selected.context !== ingress || selected.consumed) {
            rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
        }
        selected.identity.requireCurrent()
        selected.consumed = true
        // Both operations are connection-free and follow actual original preflight release.
        admission.chargeAdminRead(ingress, selected.identity.actor, testScope, selected.admissionIdentity)
        admission.consumeAdminRead(ingress, selected.admissionIdentity)
        val rows = try {
            when (val query = selected.query) {
                is ComplaintAdminSearchQuery -> phases.search(selected.identity, query, selected.position)
                is ComplaintAdminDetailQuery -> phases.detail(selected.identity, query.id)
            }
        } catch (failure: PersistencePhaseException) {
            rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        }
        requireAllowed(rows)
        return when (val query = selected.query) {
            is ComplaintAdminDetailQuery -> ComplaintAdminReadResult.Detail(rows.items.singleOrNull() ?: rejectAdminRead(ComplaintAdminReadFailure.NOT_FOUND))
            is ComplaintAdminSearchQuery -> {
                val items = rows.items.take(query.limit)
                val next = if (rows.items.size > query.limit) {
                    val last = items.last()
                    cursors.encode(selected.identity.actor, query, ComplaintAdminReadPosition(last.updatedAt, last.id))
                } else {
                    null
                }
                ComplaintAdminReadResult.Page(items, next)
            }
        }
    }

    private fun requireAllowed(rows: ComplaintAdminReadRows) {
        if (!rows.contractValid) rejectAdminRead(ComplaintAdminReadFailure.INTERNAL)
        rows.verdict.failure?.let(::rejectAdminRead)
    }

    override fun toString(): String = "ComplaintAdminReadAdapter(TEST-only,no-mode-authority)"

    private class Authenticated(
        val owner: ComplaintAdminReadAdapter,
        val caller: Thread,
        val context: ComplaintIngressContext,
        val identity: ComplaintAdminReadIdentity,
        val query: ComplaintAdminReadQuery,
        val position: ComplaintAdminReadPosition?,
    ) : ComplaintAdminReadAuthentication {
        val admissionIdentity = Any()
        var consumed = false
        override fun toString(): String = "ComplaintAdminReadAuthentication(redacted)"
    }
}
