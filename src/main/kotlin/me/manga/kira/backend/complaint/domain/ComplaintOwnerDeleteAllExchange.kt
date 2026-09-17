package me.manga.kira.backend.complaint.domain

/** The concrete adapter authenticates the original ingress and runs one fixed continuation. */
internal interface ComplaintOwnerDeleteAllExchange {
    fun deleteAll(context: ComplaintInstallationRequestContext, candidate: InstallationDeletionCandidate): ComplaintOwnerDeleteAllResponse
}

/** Output discrimination only, never request, proof, erasure or current-mode authority. */
internal sealed interface ComplaintOwnerDeleteAllResponse {
    interface Completed : ComplaintOwnerDeleteAllResponse
    interface Pending : ComplaintOwnerDeleteAllResponse
}
