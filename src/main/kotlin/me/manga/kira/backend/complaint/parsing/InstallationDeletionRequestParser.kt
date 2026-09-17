package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.core.JsonProcessingException
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import java.nio.charset.CharacterCodingException

internal enum class InstallationDeletionRequestFailure { MALFORMED_REQUEST, PAYLOAD_TOO_LARGE }

internal class InstallationDeletionRequestRejected(val failure: InstallationDeletionRequestFailure) :
    RuntimeException("Installation deletion request refused.", null, false, false)

/**
 * Fixed delete-all syntax only. The HTTP owner must reject missing/duplicate headers and enforce the
 * streamed body/media/encoding cap inside ingress before allocation; this parser grants no authority.
 * No raw secret, request body or input-bearing exception is retained by its result.
 */
internal object InstallationDeletionRequestParser {
    const val MAX_BODY_BYTES = InstallationRequestJson.MAX_BODY_BYTES

    @Suppress("SwallowedException") // Input-bearing parser, identifier and credential diagnostics are deliberately discarded.
    fun parse(body: ByteArray, idempotencyKey: String): InstallationDeletionCandidate {
        if (body.size > MAX_BODY_BYTES) reject(InstallationDeletionRequestFailure.PAYLOAD_TOO_LARGE)
        try {
            val operationKey = ComplaintIdentifiers.idempotencyKey(idempotencyKey)
            val root = InstallationRequestJson.deletion(body)
            val installation = InstallationRequestJson.deletionInstallation(root)
            val version = InstallationRequestJson.credentialVersion(root)
            val secret = InstallationRequestJson.secret(root)
            return try {
                InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(installation, secret), version, operationKey)
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

    private fun reject(failure: InstallationDeletionRequestFailure = InstallationDeletionRequestFailure.MALFORMED_REQUEST): Nothing =
        throw InstallationDeletionRequestRejected(failure)
}
