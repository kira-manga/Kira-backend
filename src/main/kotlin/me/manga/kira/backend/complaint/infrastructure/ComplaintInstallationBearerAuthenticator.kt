package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRejected
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.ComplaintSecurityFailure
import me.manga.kira.backend.security.ComplaintSecurityRejected
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.InstallationJwtRejectedException
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken

/**
 * Dormant TEST early rejection only. Reuses the fixed current-row operation after real bounded JWT
 * verification; does NOT invoke a semantic adapter, mint its admission, or replace producer rechecks.
 * ACTIVE test fixtures still provide no catalog/projection, current-mode or restore authority.
 */
internal class ComplaintInstallationBearerAuthenticator(
    private val scope: ComplaintDataScope,
    private val jwt: InstallationJwtCodec,
    private val phases: ComplaintOwnerHistoryPhaseExecutor,
    private val ingress: ComplaintIngressAdmission,
) {
    init {
        require(scope.testOnly) { "Dormant installation authentication requires TEST scope." }
    }

    @Suppress("SwallowedException")
    fun authenticate(context: ComplaintIngressContext, candidate: Authentication): Authentication {
        requireConnectionFree()
        ingress.requireLiveContext(context)
        val bearer = candidate as? BearerTokenAuthenticationToken ?: unauthorized()
        try {
            val token = jwt.verify(bearer.token)
            if (token.installation.scope != scope) unauthorized()
            val identity = ComplaintOwnerHistoryIdentity(token.installation, token.credentialVersion, token.expiresAt)
            if (!phases.authenticate(identity)) unauthorized()
            // Result access itself requires committed physical release. Nothing is published while a lease is owned.
            requireConnectionFree()
            ingress.requireLiveContext(context)
            identity.requireCurrent()
            return Authenticated(this, context, Thread.currentThread(), InstallationHttpPrincipal(token.installation, token.credentialVersion))
        } catch (failure: InstallationJwtRejectedException) {
            unauthorized()
        } catch (failure: PersistencePhaseException) {
            throw ComplaintSecurityRejected(ComplaintSecurityFailure.UNAVAILABLE)
        } catch (failure: ComplaintOwnerHistoryRejected) {
            throw ComplaintSecurityRejected(ComplaintSecurityFailure.UNAVAILABLE)
        }
    }

    /** A Spring Authentication assembled elsewhere, even with the same authority or UUID, is not this early check. */
    fun belongsTo(context: ComplaintIngressContext, authentication: Authentication?): Boolean {
        requireConnectionFree()
        ingress.requireLiveContext(context)
        val selected = authentication as? Authenticated ?: return false
        return selected.owner === this && selected.context === context && selected.caller === Thread.currentThread() && selected.isAuthenticated
    }

    override fun toString(): String = "ComplaintInstallationBearerAuthenticator(TEST-only,no-mode-authority)"

    private class Authenticated(
        val owner: ComplaintInstallationBearerAuthenticator,
        val context: ComplaintIngressContext,
        val caller: Thread,
        private val installation: InstallationHttpPrincipal,
    ) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority(InstallationJwtCodec.ROLE))) {
        init {
            super.setAuthenticated(true)
        }

        override fun getPrincipal(): InstallationHttpPrincipal = installation
        override fun getCredentials(): Any? = null
        override fun getName(): String = "installation"
        override fun setAuthenticated(authenticated: Boolean) {
            require(!authenticated) { "Installation authentication cannot be reassigned." }
            super.setAuthenticated(false)
        }

        override fun toString(): String = "InstallationHttpAuthentication(redacted)"
    }

    private companion object {
        fun unauthorized(): Nothing = throw InvalidBearerTokenException("Installation credential refused.")
    }
}

/** Distinct from AuthenticatedUser. It is neither a domain handoff nor a reusable routing/admission capability. */
internal class InstallationHttpPrincipal(val installation: ScopedInstallationId, val credentialVersion: Long) {
    override fun toString(): String = "InstallationHttpPrincipal(redacted)"
}
