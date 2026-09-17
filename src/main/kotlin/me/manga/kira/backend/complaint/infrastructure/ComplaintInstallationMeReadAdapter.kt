package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMe
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeFailure
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMeReadPort
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRejected
import me.manga.kira.backend.complaint.domain.rejectInstallationMe
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.InstallationJwtRejectedException

/** TEST-only current-row reader. Reuses actual authentication SQL, never history content or mode authority. */
internal class ComplaintInstallationMeReadAdapter(
    private val testScope: ComplaintDataScope,
    private val jwt: InstallationJwtCodec,
    private val phases: ComplaintOwnerHistoryPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
) : ComplaintInstallationMeReadPort {
    init {
        require(testScope.testOnly) { "Dormant installation read requires TEST scope." }
    }

    @Suppress("SwallowedException") // Neither JWT-library nor persistence diagnostics cross the boundary.
    override fun authenticate(
        context: ComplaintInstallationRequestContext,
        bearer: String,
    ): ComplaintInstallationMeAuthentication = try {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectInstallationMe(ComplaintInstallationMeFailure.UNAVAILABLE)
        admission.startOwnerHistory(ingress)
        val token = jwt.verify(bearer)
        if (token.installation.scope != testScope) rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
        val identity = ComplaintOwnerHistoryIdentity(token.installation, token.credentialVersion, token.expiresAt)
        if (!phases.authenticate(identity)) rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
        Authenticated(this, Thread.currentThread(), ingress, identity)
    } catch (failure: InstallationJwtRejectedException) {
        rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
    } catch (failure: PersistencePhaseException) {
        rejectInstallationMe(ComplaintInstallationMeFailure.UNAVAILABLE)
    }

    @Suppress("SwallowedException") // Preserve bounded failure meaning, not inherited exception content.
    override fun read(
        context: ComplaintInstallationRequestContext,
        authentication: ComplaintInstallationMeAuthentication,
    ): ComplaintInstallationMe = try {
        requireConnectionFree()
        val selected = selected(context, authentication)
        selected.identity.requireCurrent()
        selected.consumed = true
        // The first current-row operation has committed and actually released before either counter call.
        admission.chargeOwnerHistory(selected.context, selected.identity.installation, selected.admissionIdentity)
        admission.consumeOwnerHistory(selected.context, selected.admissionIdentity)
        if (!phases.authenticate(selected.identity)) rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
        selected.identity.requireCurrent()
        // This second, committed/released query equality-checked all three projected scalars against current rows.
        ComplaintInstallationMe(selected.identity.installation, selected.identity.version)
    } catch (failure: PersistencePhaseException) {
        rejectInstallationMe(ComplaintInstallationMeFailure.UNAVAILABLE)
    } catch (failure: ComplaintOwnerHistoryRejected) {
        rejectInstallationMe(ComplaintInstallationMeFailure.UNAVAILABLE)
    }

    private fun selected(context: ComplaintInstallationRequestContext, authentication: ComplaintInstallationMeAuthentication): Authenticated {
        val selected = authentication as? Authenticated ?: rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
        if (selected.owner !== this || selected.caller !== Thread.currentThread() || selected.context !== context) {
            rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
        }
        if (selected.consumed) rejectInstallationMe(ComplaintInstallationMeFailure.UNAUTHORIZED)
        return selected
    }

    override fun toString(): String = "ComplaintInstallationMeReadAdapter(TEST-only,no-mode-authority)"

    private class Authenticated(
        val owner: ComplaintInstallationMeReadAdapter,
        val caller: Thread,
        val context: ComplaintIngressContext,
        val identity: ComplaintOwnerHistoryIdentity,
    ) : ComplaintInstallationMeAuthentication {
        val admissionIdentity = Any() // The ingress registry retains only this opaque identity, not the claims.
        var consumed = false
        override fun toString(): String = "ComplaintInstallationMeAuthentication(redacted)"
    }
}
