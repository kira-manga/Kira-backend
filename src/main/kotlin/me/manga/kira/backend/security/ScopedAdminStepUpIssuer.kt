package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.infrastructure.transaction.ScopedAdminStepUpPhaseExecutor
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

/** Split issuer behind the existing scoped endpoint; complaint admission remains separately closed. */
internal class ScopedAdminStepUpIssuer(
    private val phases: ScopedAdminStepUpPhaseExecutor,
    private val passwords: PasswordEncoder,
    private val throttle: AuthThrottle,
) {
    fun issueSource(userId: UUID, rawPassword: String, clientIp: String): IssuedScopedAdminStepUp {
        val snapshot = phases.readSourceSnapshot(userId)
        val verified = VerifiedScopedAdminStepUp.verify(snapshot, rawPassword, clientIp, passwords, throttle)
        return phases.issueSource(verified)
    }

    fun issueComplaint(userId: UUID, rawPassword: String, clientIp: String): IssuedScopedAdminStepUp {
        val snapshot = phases.readComplaintSnapshot(userId)
        val verified = VerifiedScopedAdminStepUp.verify(snapshot, rawPassword, clientIp, passwords, throttle)
        return phases.issueComplaint(verified)
    }

    override fun toString(): String = "ScopedAdminStepUpIssuer(redacted)"
}
