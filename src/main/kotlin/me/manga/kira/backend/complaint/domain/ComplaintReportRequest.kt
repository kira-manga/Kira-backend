package me.manga.kira.backend.complaint.domain

/** Only this operation has an approved normalized request/frame in this producer. */
internal enum class ComplaintReportOperation { OWNER_CREATE, }

/** All four members are required. Null appVersion is not an empty string or a missing-key default. */
internal class ComplaintReportMetadataInput(val appVersion: String?, val osVersion: String, val manufacturer: String, val deviceModel: String) {
    override fun toString(): String = "ComplaintReportMetadataInput(redacted)"
}

internal class ComplaintReportMetadata private constructor(val appVersion: String?, val osVersion: String, val manufacturer: String, val deviceModel: String) {
    override fun toString(): String = "ComplaintReportMetadata(redacted)"

    companion object {
        internal fun normalize(input: ComplaintReportMetadataInput): ComplaintReportMetadata = ComplaintReportMetadata(
            input.appVersion?.let { ComplaintReportTextRules.normalize(it, ComplaintReportField.APP_VERSION) },
            ComplaintReportTextRules.normalize(input.osVersion, ComplaintReportField.OS_VERSION),
            ComplaintReportTextRules.normalize(input.manufacturer, ComplaintReportField.MANUFACTURER),
            ComplaintReportTextRules.normalize(input.deviceModel, ComplaintReportField.DEVICE_MODEL),
        )
    }
}

internal sealed interface ComplaintReportRequestResult {
    class Accepted(val request: ComplaintReportRequest) : ComplaintReportRequestResult {
        override fun toString(): String = "ComplaintReportRequest.Accepted(redacted)"
    }

    class Rejected(val field: ComplaintReportField, val reason: ComplaintReportRejection) : ComplaintReportRequestResult {
        override fun toString(): String = "ComplaintReportRequest.Rejected($field,$reason)"
    }
}

/** Live-only normalized values, not raw JSON, authenticated scope, admission or a durable pending record. */
internal class ComplaintReportRequest private constructor(
    val identity: ComplaintReportIdentity,
    val type: ComplaintType,
    val subject: String,
    val body: String,
    val metadata: ComplaintReportMetadata,
) {
    val operation: ComplaintReportOperation get() = ComplaintReportOperation.OWNER_CREATE

    override fun toString(): String = "ComplaintReportRequest(redacted)"

    companion object {
        fun normalize(
            identity: ComplaintReportIdentity,
            type: ComplaintType,
            subject: String,
            body: String,
            metadata: ComplaintReportMetadataInput,
        ): ComplaintReportRequestResult = try {
            ComplaintReportRequestResult.Accepted(
                ComplaintReportRequest(
                    identity,
                    type,
                    ComplaintReportTextRules.normalize(subject, ComplaintReportField.SUBJECT),
                    ComplaintReportTextRules.normalize(body, ComplaintReportField.BODY),
                    ComplaintReportMetadata.normalize(metadata),
                ),
            )
        } catch (failure: ComplaintReportTextRejected) {
            ComplaintReportRequestResult.Rejected(failure.field, failure.reason)
        }
    }
}
