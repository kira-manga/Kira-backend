package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintGrantCleanupPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.OrdinaryPersistencePhaseExecutor
import me.manga.kira.backend.user.domain.Role
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.concurrent.atomic.AtomicReference

/** One verification followed by one real completed cleanup and one issuance claim; no caller Boolean authorizes an insert. */
internal class VerifiedScopedAdminStepUp private constructor(private val snapshot: StepUpUserSnapshot) {
    val scope: ScopedAdminStepUpScope get() = snapshot.scope
    private val stage = AtomicReference(Stage.VERIFIED)

    internal fun completeCleanup(source: OrdinaryPersistencePhaseExecutor, complaint: ComplaintGrantCleanupPhaseExecutor) {
        verificationBoundary()
        if (!stage.compareAndSet(Stage.VERIFIED, Stage.CLEANING)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        when (scope) {
            ScopedAdminStepUpScope.SOURCE -> source.cleanupSourceGrants()
            ScopedAdminStepUpScope.COMPLAINT -> complaint.cleanupComplaintGrants()
        }
        // Cleanup's existing result already requires committed, actually released completion, not a caller assertion.
        verificationBoundary()
        stage.set(Stage.CLEANED)
    }

    internal fun claimForIssuance(): StepUpUserSnapshot {
        if (!stage.compareAndSet(Stage.CLEANED, Stage.CLAIMED)) refuse(PersistencePhaseFailureCode.WORK_FAILED)
        return snapshot
    }

    override fun toString(): String = "VerifiedScopedAdminStepUp(redacted)"

    private enum class Stage { VERIFIED, CLEANING, CLEANED, CLAIMED }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun verify(
            snapshot: StepUpUserSnapshot,
            rawPassword: String,
            clientIp: String,
            passwords: PasswordEncoder,
            throttle: AuthThrottle,
        ): VerifiedScopedAdminStepUp {
            snapshot.requireReleased()
            val identity = snapshot.email ?: snapshot.userId.toString()
            var retained: AuthLoginAttempt? = null
            var primary: Throwable? = null
            try {
                val attempt = connectionFreeCall {
                    // Retain before the post-call boundary can fail; never lose an acknowledged reservation there.
                    throttle.beginLoginAttempt(identity, clientIp).also { retained = it }
                }
                val accepted = snapshot.passwordHash != null && snapshot.enabled && snapshot.role === Role.ADMIN &&
                    connectionFreeCall { passwords.matches(rawPassword, snapshot.passwordHash) }
                connectionFreeCall { attempt.complete(accepted) } // Explicit failure ACK before 401; success ACK before any grant phase.
                if (!accepted) throw UnauthorizedException("Password verification failed.", code = "INVALID_STEP_UP_CREDENTIALS")
            } catch (problem: Throwable) {
                primary = problem
                throw problem
            } finally {
                retained?.let { closeAttempt(it, primary) }
            }
            return VerifiedScopedAdminStepUp(snapshot)
        }

        @Suppress("TooGenericExceptionCaught")
        private fun closeAttempt(attempt: AuthLoginAttempt, primary: Throwable?) {
            try {
                // Completed/ambiguous attempts cannot retry storage. An illegal boundary never calls the provider:
                // its existing lease recovers abandonment; neither local exit nor expiry is a successful ACK.
                connectionFreeCall { attempt.close() }
            } catch (problem: Throwable) {
                if (primary == null) throw problem
                if (primary !== problem) primary.addSuppressed(problem) // Only an already-sanitized closing failure.
            }
        }

        // Private fixed-call plumbing, not an application transaction callback or an alternate phase owner.
        @Suppress("TooGenericExceptionCaught")
        private fun <T> connectionFreeCall(call: () -> T): T {
            verificationBoundary()
            try {
                return call()
            } catch (problem: Throwable) {
                throw safeFailure(problem)
            } finally {
                verificationBoundary() // Also executes when a dependency throws or retains a resource before throwing.
            }
        }

        private fun verificationBoundary() {
            try {
                requireConnectionFree()
            } catch (failure: PersistencePhaseException) {
                PersistencePhaseOwnership.current()?.recordFailure(failure)
                throw failure
            }
            if (Thread.currentThread().isInterrupted) refuse(PersistencePhaseFailureCode.INTERRUPTED)
        }

        private fun refuse(code: PersistencePhaseFailureCode): Nothing {
            val failure = PersistencePhaseException(code)
            PersistencePhaseOwnership.current()?.recordFailure(failure)
            throw failure
        }

        private fun safeFailure(problem: Throwable): RuntimeException = when (problem) {
            is PersistencePhaseException -> problem

            is TooManyRequestsException -> if (problem.code == "AUTH_THROTTLE_UNAVAILABLE") {
                TooManyRequestsException(
                    "Authentication is temporarily unavailable. Try again later.",
                    code = "AUTH_THROTTLE_UNAVAILABLE",
                    retryAfterSeconds = problem.retryAfterSeconds,
                )
            } else {
                TooManyRequestsException("Too many attempts. Try again later.", retryAfterSeconds = problem.retryAfterSeconds)
            }

            is ServiceUnavailableException -> ServiceUnavailableException(
                "Authentication is temporarily unavailable. Try again later.",
                retryAfterSeconds = problem.retryAfterSeconds,
            )

            is InterruptedException -> {
                Thread.currentThread().interrupt()
                PersistencePhaseException(PersistencePhaseFailureCode.INTERRUPTED)
            }

            else -> PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
        }
    }
}
