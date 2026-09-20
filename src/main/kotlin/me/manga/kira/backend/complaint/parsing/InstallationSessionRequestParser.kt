package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.core.JsonProcessingException
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import java.nio.charset.CharacterCodingException

internal enum class InstallationSessionRequestFailure { MALFORMED_BODY, PAYLOAD_TOO_LARGE }

/** Bounded parsing classification only; future HTTP composition owns the 400/413 problem mapping. */
internal class InstallationSessionRequestRejected(val failure: InstallationSessionRequestFailure) :
    RuntimeException("Installation session request refused.", null, false, false)

/**
 * Dormant byte-to-comparison-candidate parser, not authentication or current-mode authority.
 * The request owner must enforce the same streamed cap and media/encoding policy BEFORE allocating
 * this body, within ingress. Caller-owned body bytes and Jackson's temporary text are not retained.
 */
internal object InstallationSessionRequestParser {
    const val MAX_BODY_BYTES = InstallationRequestJson.MAX_BODY_BYTES

    @Suppress("SwallowedException") // Discard input-bearing JSON/Unicode/identifier/secret diagnostics.
    fun parse(body: ByteArray): InstallationSessionCandidate {
        if (body.size > MAX_BODY_BYTES) reject(InstallationSessionRequestFailure.PAYLOAD_TOO_LARGE)
        try {
            val root = InstallationRequestJson.session(body)
            val installation = InstallationRequestJson.installation(root)
            val secret = InstallationRequestJson.secret(root)
            return try {
                InstallationEnrollmentCredentials.prepareSession(installation, secret)
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

    private fun reject(failure: InstallationSessionRequestFailure = InstallationSessionRequestFailure.MALFORMED_BODY): Nothing =
        throw InstallationSessionRequestRejected(failure)
}
