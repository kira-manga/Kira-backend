package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.HexFormat
import java.util.UUID

class ComplaintDeleteAllFingerprintTest {
    @Test
    fun `fixed version one frame golden binds installation scope and submitted version but never secret or separately compared key`() {
        val installation = ScopedInstallationId(UUID.fromString("10000000-0000-4000-8000-000000000001"), ComplaintDataScope.LIVE)
        val candidate = InstallationDeletionCandidate(InstallationSessionCandidate.fromVerifier(installation, ByteArray(32) { 13 }), 7, UUID.randomUUID())
        val expected = HexFormat.of().parseHex(
            "000000226b6972612d636f6d706c61696e742d726571756573742d66696e6765727072696e740000000100000004504f5354" +
                "000000202f6170692f76312f696e7374616c6c6174696f6e732f64656c6574652d616c6c000000104f574e45525f44454c4554455f414c4c" +
                "0000002430303030303030302d303030302d303030302d303030302d3030303030303030303030300000000100000024" +
                "31303030303030302d303030302d343030302d383030302d3030303030303030303030310000000137ffffffff",
        )
        assertEquals(199, expected.size)
        assertArrayEquals(expected, ComplaintDeleteAllFingerprint.frameBytes(candidate))
        val fingerprint = ComplaintDeleteAllFingerprint.of(candidate)
        assertEquals("_c4H5HZ1s5sLBDr0_rOIeq0S0embAa1qRgrsrxc-MSo", fingerprint.encoded)
        val otherKeyAndSecret = InstallationDeletionCandidate(
            InstallationSessionCandidate.fromVerifier(installation, ByteArray(32) { 29 }), 7, UUID.randomUUID(),
        )
        assertArrayEquals(expected, ComplaintDeleteAllFingerprint.frameBytes(otherKeyAndSecret))
        assertNotEquals(
            fingerprint.encoded,
            ComplaintDeleteAllFingerprint.of(InstallationDeletionCandidate(candidate.credential, 8, candidate.operationKey)).encoded,
        )
        val testScope = installation.copy(scope = ComplaintDataScope.of(UUID.fromString("20000000-0000-4000-8000-000000000002")))
        val scoped = InstallationDeletionCandidate(InstallationSessionCandidate.fromVerifier(testScope, ByteArray(32) { 13 }), 7, candidate.operationKey)
        assertFalse(expected.contentEquals(ComplaintDeleteAllFingerprint.frameBytes(scoped)))
        val changed = fingerprint.bytes()
        changed.fill(0)
        assertEquals("_c4H5HZ1s5sLBDr0_rOIeq0S0embAa1qRgrsrxc-MSo", fingerprint.encoded)
        assertEquals("ComplaintDeleteAllFingerprint(redacted)", fingerprint.toString())
    }
}
