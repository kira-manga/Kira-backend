package me.manga.kira.backend.complaint.domain

internal enum class ComplaintReportField { SUBJECT, BODY, APP_VERSION, OS_VERSION, MANUFACTURER, DEVICE_MODEL }

internal enum class ComplaintReportRejection {
    REQUIRED,
    TOO_SHORT,
    TOO_LONG,
    FORBIDDEN_CONTROL,
    MALFORMED_UNICODE,
    NORMALIZATION_MISMATCH,
}

internal class ComplaintReportTextRejected(val field: ComplaintReportField, val reason: ComplaintReportRejection) :
    RuntimeException("Complaint report text rejected.", null, false, false)

/** Preserve existing domain validation, but refuse any divergence from the approved fixed trim table. */
internal object ComplaintReportTextRules {
    // Bounds direct-call scratch work. The HTTP owner must still enforce its aggregate raw 16KiB body cap first.
    private const val MAX_INPUT_CODE_UNITS = 16_384

    fun normalize(value: String, field: ComplaintReportField): String {
        if (value.length > MAX_INPUT_CODE_UNITS) reject(field, ComplaintReportRejection.TOO_LONG)
        val normalized = try {
            existingRule(value, field)
        } catch (failure: ComplaintValidationException) {
            reject(field, rejection(failure.reason))
        }
        // Existing rules reject controls/unpaired surrogates BEFORE trim; only then compare the fixed-table result.
        val fixed = value.replace("\r\n", "\n").trim(::isReportWhitespace)
        if (normalized != fixed) reject(field, ComplaintReportRejection.NORMALIZATION_MISMATCH)
        return normalized
    }

    private fun existingRule(value: String, field: ComplaintReportField): String = when (field) {
        ComplaintReportField.SUBJECT -> ComplaintTextRules.subject(value)

        ComplaintReportField.BODY -> ComplaintTextRules.reportBody(value)

        ComplaintReportField.APP_VERSION -> ComplaintTextRules.appVersion(value)
            ?: reject(field, ComplaintReportRejection.NORMALIZATION_MISMATCH)

        ComplaintReportField.OS_VERSION -> ComplaintTextRules.osVersion(value)

        ComplaintReportField.MANUFACTURER -> ComplaintTextRules.manufacturer(value)

        ComplaintReportField.DEVICE_MODEL -> ComplaintTextRules.deviceModel(value)
    }

    private fun rejection(reason: ComplaintValidationReason): ComplaintReportRejection = when (reason) {
        ComplaintValidationReason.REQUIRED -> ComplaintReportRejection.REQUIRED
        ComplaintValidationReason.TOO_SHORT -> ComplaintReportRejection.TOO_SHORT
        ComplaintValidationReason.TOO_LONG -> ComplaintReportRejection.TOO_LONG
        ComplaintValidationReason.FORBIDDEN_CONTROL -> ComplaintReportRejection.FORBIDDEN_CONTROL
        ComplaintValidationReason.MALFORMED_UNICODE -> ComplaintReportRejection.MALFORMED_UNICODE
        else -> ComplaintReportRejection.NORMALIZATION_MISMATCH
    }

    private fun reject(field: ComplaintReportField, reason: ComplaintReportRejection): Nothing = throw ComplaintReportTextRejected(field, reason)
}

private fun isReportWhitespace(value: Char): Boolean = when (value) {
    '\t', '\n', ' ', '\u00a0', '\u1680', in '\u2000'..'\u200a',
    '\u2028', '\u2029', '\u202f', '\u205f', '\u3000',
    -> true

    else -> false
}
