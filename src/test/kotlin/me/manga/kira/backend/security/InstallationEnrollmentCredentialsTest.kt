package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.HexFormat
import java.util.UUID

class InstallationEnrollmentCredentialsTest {
    private val id = ScopedInstallationId(UUID.fromString("10000000-0000-4000-8000-000000000001"), ComplaintDataScope.LIVE)

    @Test
    fun `verifier binds exact purpose NUL UUID network bytes and 256 bit secret`() {
        val candidate = InstallationEnrollmentCredentials.prepare(id, ComplaintPlatform.ANDROID, ByteArray(32) { it.toByte() })
        assertEquals("515d5b404fcf3ec1d0e2a8a22b9335dbb7e10d7285b2d14f8695accc96eea473", HexFormat.of().formatHex(candidate.verifierBytes()))
        val changedId = ScopedInstallationId(UUID.fromString("10000000-0000-4000-8000-000000000002"), ComplaintDataScope.LIVE)
        val otherId = InstallationEnrollmentCredentials.prepare(changedId, ComplaintPlatform.ANDROID, ByteArray(32) { it.toByte() })
        val otherSecret = InstallationEnrollmentCredentials.prepare(id, ComplaintPlatform.ANDROID, ByteArray(32))
        assertFalse(candidate.verifierBytes().contentEquals(otherId.verifierBytes()))
        assertFalse(candidate.verifierBytes().contentEquals(otherSecret.verifierBytes()))
    }

    @Test
    fun `candidate copies comparison bytes and never retains or prints the submitted secret`() {
        val secret = ByteArray(32) { it.toByte() }
        val candidate = InstallationEnrollmentCredentials.prepare(id, ComplaintPlatform.IOS, secret)
        val expected = candidate.verifierBytes()
        secret.fill(0)
        candidate.verifierBytes().fill(0)
        assertArrayEquals(expected, candidate.verifierBytes())
        val copied = InstallationEnrollmentCandidate.fromVerifier(id, ComplaintPlatform.IOS, expected)
        expected.fill(0)
        assertArrayEquals(candidate.verifierBytes(), copied.verifierBytes())
        assertEquals("InstallationEnrollmentCandidate(redacted)", candidate.toString())
    }

    @Test
    fun `only exact decoded secret and verifier lengths are accepted`() {
        for (size in listOf(0, 1, 31, 33, 4096)) {
            assertThrows<IllegalArgumentException> { InstallationEnrollmentCredentials.prepare(id, ComplaintPlatform.IOS, ByteArray(size)) }
            assertThrows<IllegalArgumentException> { InstallationEnrollmentCandidate.fromVerifier(id, ComplaintPlatform.IOS, ByteArray(size)) }
        }
    }

    @Test
    fun `session preparation shares the exact UUID secret verifier without a platform or retained secret`() {
        val secret = ByteArray(32) { it.toByte() }
        val session = InstallationEnrollmentCredentials.prepareSession(id, secret)
        val enrolled = InstallationEnrollmentCredentials.prepare(id, ComplaintPlatform.ANDROID, secret)
        assertEquals("515d5b404fcf3ec1d0e2a8a22b9335dbb7e10d7285b2d14f8695accc96eea473", HexFormat.of().formatHex(session.verifierBytes()))
        assertArrayEquals(enrolled.verifierBytes(), session.verifierBytes())
        val copy = session.verifierBytes()
        val candidate = InstallationSessionCandidate.fromVerifier(id, copy)
        copy.fill(0)
        secret.fill(0)
        session.verifierBytes().fill(0)
        assertArrayEquals(enrolled.verifierBytes(), session.verifierBytes())
        assertArrayEquals(enrolled.verifierBytes(), candidate.verifierBytes())
        assertEquals("InstallationSessionCandidate(redacted)", session.toString())
        val other = ScopedInstallationId(UUID.fromString("10000000-0000-4000-8000-000000000002"), ComplaintDataScope.LIVE)
        val otherCandidate = InstallationEnrollmentCredentials.prepareSession(other, ByteArray(32) { it.toByte() })
        assertFalse(session.verifierBytes().contentEquals(otherCandidate.verifierBytes()))
        for (size in listOf(0, 1, 31, 33, 4096)) {
            assertThrows<IllegalArgumentException> { InstallationEnrollmentCredentials.prepareSession(id, ByteArray(size)) }
            assertThrows<IllegalArgumentException> { InstallationSessionCandidate.fromVerifier(id, ByteArray(size)) }
        }
    }
}
