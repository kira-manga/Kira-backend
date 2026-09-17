package me.manga.kira.backend.security

import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Test

class VersionBoundComplaintConsumerBoundaryTest {
    @Test
    fun `fixed ingress refuses rotation and retirement even after retention without replacing its quota state`() {
        for (overlap in listOf(false, true)) {
            val current = admissionTestKey(1)
            val previous = if (overlap) admissionTestKey(2) else null
            val fixed = ComplaintAdmissionKeyConfiguration.fixed(current, previous, listOf(admissionTestForbidden()))
            current.destroy()
            previous?.destroy()
            val clock = MutableAdmissionTestClock()
            val guard = ComplaintIngressAdmission(
                ClientIpResolver(KiraSecurityProperties()),
                admissionTestPolicy(ingressRate = 1, ingressBuckets = if (overlap) 2 else 1),
                fixed,
                clock,
            )
            val candidate = admissionTestKey(3)
            try {
                for (now in listOf(0L, ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS + 1)) {
                    clock.value = now
                    guard.withIngress(historyTestRequest()) {}
                    admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.rotate(candidate) }
                    admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.retirePrevious() }
                    admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { guard.withIngress(historyTestRequest()) {} }
                    // Each selected generation still consumes its physical bucket: no hidden retirement.
                    admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { guard.withIngress(historyTestRequest(ip = "192.0.2.2")) {} }
                }
            } finally {
                candidate.destroy()
            }
        }
    }
}
