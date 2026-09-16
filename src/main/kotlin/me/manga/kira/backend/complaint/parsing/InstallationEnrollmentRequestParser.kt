package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.core.JsonProcessingException
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import java.nio.charset.CharacterCodingException

internal enum class InstallationEnrollmentRequestFailure { MALFORMED_BODY, PAYLOAD_TOO_LARGE }

/** Bounded parsing classification only; future HTTP composition owns the 400/413 problem mapping. */
internal class InstallationEnrollmentRequestRejected(val failure: InstallationEnrollmentRequestFailure) :
    RuntimeException("Installation enrollment request refused.", null, false, false)

/**
 * Dormant four-field byte-to-comparison-candidate parser, not enrollment/admission or scope authority.
 * The request owner must enforce this streamed cap and media/encoding policy BEFORE body allocation.
 * Caller-owned body bytes and temporary JSON text are not retained; the decoded secret is erased.
 */
internal object InstallationEnrollmentRequestParser {
    const val MAX_BODY_BYTES = InstallationRequestJson.MAX_BODY_BYTES

    @Suppress("SwallowedException") // Discard input-bearing JSON/Unicode/identifier/secret diagnostics.
    fun parse(body: ByteArray): InstallationEnrollmentCandidate {
        if (body.size > MAX_BODY_BYTES) reject(InstallationEnrollmentRequestFailure.PAYLOAD_TOO_LARGE)
        try {
            val root = InstallationRequestJson.enrollment(body)
            val installation = InstallationRequestJson.installation(root)
            val platform = InstallationRequestJson.platform(root)
            val secret = InstallationRequestJson.secret(root)
            return try {
                InstallationEnrollmentCredentials.prepare(installation, platform, secret)
            } finally {
                secret.fill(0)
            }
        } catch (ex: JsonProcessingException) {
            reject()
        } catch (ex: CharacterCodingException) {
            reject()
        } catch (ex: ComplaintValidationException) {
            reject()
        } catch (ex: IllegalArgumentException) {
            reject()
        }
    }

    private fun reject(failure: InstallationEnrollmentRequestFailure = InstallationEnrollmentRequestFailure.MALFORMED_BODY): Nothing =
        throw InstallationEnrollmentRequestRejected(failure)
}
