package me.manga.kira.backend.complaint.domain

import java.util.UUID

/** Normalized reply body and diagnostics only. The locked parent, never this request, supplies type/subject/key. */
internal class ComplaintReplyRequest private constructor(
    val identity: ComplaintReportIdentity,
    val parentId: UUID,
    val body: String,
    val metadata: ComplaintReportMetadata,
) {
    val operation: ComplaintOwnerCreationOperation get() = ComplaintOwnerCreationOperation.OWNER_REPLY

    override fun toString(): String = "ComplaintReplyRequest(redacted)"

    companion object {
        fun normalize(
            identity: ComplaintReportIdentity,
            parentId: UUID,
            body: String,
            metadata: ComplaintReportMetadataInput,
        ): ComplaintReplyRequest {
            ComplaintIdentifiers.resourceId(parentId.toString())
            require(parentId != identity.clientId.value)
            return ComplaintReplyRequest(identity, parentId, ComplaintReportTextRules.replyBody(body), ComplaintReportMetadata.normalize(metadata))
        }
    }
}
