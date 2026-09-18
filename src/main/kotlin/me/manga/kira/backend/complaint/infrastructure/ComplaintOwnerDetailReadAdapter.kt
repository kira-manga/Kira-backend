package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetail
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailReadPort
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDetailRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRejected
import me.manga.kira.backend.complaint.domain.rejectOwnerDetail
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDetailPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.InstallationJwtRejectedException
import java.util.UUID

/** No bean: actual TEST-scoped preflight and exact-ID read, never live-mode or restore authority. */
internal class ComplaintOwnerDetailReadAdapter(
    private val testScope: ComplaintDataScope,
    private val jwt: InstallationJwtCodec,
    private val authenticationPhases: ComplaintOwnerHistoryPhaseExecutor,
    private val phases: ComplaintOwnerDetailPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
) : ComplaintOwnerDetailReadPort {
    init {
        require(testScope.testOnly) { "Dormant detail requires TEST scope." }
    }

    @Suppress("SwallowedException") // Only bounded failure meaning crosses the JWT/persistence boundary.
    override fun authenticate(context: ComplaintOwnerDetailRequestContext, bearer: String, id: UUID): ComplaintOwnerDetailAuthentication = try {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAVAILABLE)
        admission.startOwnerHistory(ingress)
        val token = jwt.verify(bearer)
        if (token.installation.scope != testScope) rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
        val identity = ComplaintOwnerHistoryIdentity(token.installation, token.credentialVersion, token.expiresAt)
        if (!authenticationPhases.authenticate(identity)) rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
        Authenticated(this, Thread.currentThread(), ingress, identity, id)
    } catch (failure: InstallationJwtRejectedException) {
        rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
    } catch (failure: PersistencePhaseException) {
        rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAVAILABLE)
    }

    @Suppress("SwallowedException")
    override fun read(context: ComplaintOwnerDetailRequestContext, authentication: ComplaintOwnerDetailAuthentication): ComplaintOwnerDetail = try {
        requireConnectionFree()
        val selected = selected(context, authentication)
        selected.identity.requireCurrent()
        selected.consumed = true
        // The original current-row preflight has committed and physically released before spending this shared read allowance.
        admission.chargeOwnerHistory(selected.context, selected.identity.installation, selected.admissionIdentity)
        admission.consumeOwnerHistory(selected.context, selected.admissionIdentity)
        val row = phases.read(selected.identity, selected.id)
        selected.identity.requireCurrent()
        if (!row.contractValid) rejectOwnerDetail(ComplaintOwnerDetailFailure.INTERNAL)
        if (!row.authorized) rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
        row.detail ?: rejectOwnerDetail(ComplaintOwnerDetailFailure.NOT_FOUND)
    } catch (failure: PersistencePhaseException) {
        rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAVAILABLE)
    } catch (failure: ComplaintOwnerHistoryRejected) {
        rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAVAILABLE)
    }

    private fun selected(context: ComplaintOwnerDetailRequestContext, authentication: ComplaintOwnerDetailAuthentication): Authenticated {
        val selected = authentication as? Authenticated ?: rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
        if (selected.owner !== this || selected.caller !== Thread.currentThread() || selected.context !== context) {
            rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
        }
        if (selected.consumed) rejectOwnerDetail(ComplaintOwnerDetailFailure.UNAUTHORIZED)
        return selected
    }

    override fun toString(): String = "ComplaintOwnerDetailReadAdapter(TEST-only,no-mode-authority)"

    private class Authenticated(
        val owner: ComplaintOwnerDetailReadAdapter,
        val caller: Thread,
        val context: ComplaintIngressContext,
        val identity: ComplaintOwnerHistoryIdentity,
        val id: UUID,
    ) : ComplaintOwnerDetailAuthentication {
        val admissionIdentity = Any() // The local registry retains this opaque identity, never the actor/target-bearing handoff.
        var consumed = false
        override fun toString(): String = "ComplaintOwnerDetailAuthentication(redacted)"
    }
}
