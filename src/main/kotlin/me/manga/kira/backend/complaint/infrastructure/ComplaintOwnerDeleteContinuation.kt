package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeletePhaseExecutor

/** No re-claim, semantic charge, current key selection or exception-to-202 shortcut on continuation. */
internal class ComplaintOwnerDeleteContinuation(private val phases: ComplaintOwnerDeletePhaseExecutor, private val publisher: TestOwnerDeleteJournalPublisherFactoryV1) {
    init { phases.requirePublisher(publisher) }
    fun complete(outcome: TestOwnerDeleteAuthorizationV1, reserved: JournalPublicationLanesV1.TestOwnerDeleteReservation? = null): ComplaintOwnerDeleteReceipt {
        requireConnectionFree()
        phases.requirePublisher(publisher)
        return when (outcome) {
            is TestOwnerDeleteAuthorizationV1.Completed -> outcome.receipt
            is TestOwnerDeleteAuthorizationV1.Continue -> {
                val work = outcome.work
                val proof = when (work) {
                    is CommittedTestOwnerDeleteWork.Prepared -> {
                        val readback = if (reserved == null) publisher.reserve().use { it.publish(work) } else reserved.publish(work)
                        phases.verify(readback)
                    }
                    is CommittedTestOwnerDeleteWork.RecordedVerified -> phases.resume(work)
                    else -> error("Original closed work branch required")
                }
                phases.apply(work, proof)
            }
        }
    }
}
