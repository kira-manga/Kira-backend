package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Bounded credential preparation only, not HTTP authentication or current-mode/admission authority. */
internal object InstallationEnrollmentCredentials {
    fun prepare(installation: ScopedInstallationId, platform: ComplaintPlatform, secret: ByteArray): InstallationEnrollmentCandidate =
        InstallationEnrollmentCandidate.fromVerifier(installation, platform, verifier(installation, secret))

    fun prepareSession(installation: ScopedInstallationId, secret: ByteArray): InstallationSessionCandidate =
        InstallationSessionCandidate.fromVerifier(installation, verifier(installation, secret))

    private fun verifier(installation: ScopedInstallationId, secret: ByteArray): ByteArray {
        require(secret.size == 32)
        val id = installation.id
        val identifier = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("kira-installation-secret-v1\u0000".toByteArray(Charsets.UTF_8))
        digest.update(identifier)
        val copied = secret.copyOf()
        return try {
            digest.digest(copied)
        } finally {
            copied.fill(0)
        }
    }
}
