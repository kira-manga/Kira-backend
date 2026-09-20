package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminDeletePhaseExecutor

/** No re-claim, semantic charge, current key selection or exception-to-202 shortcut on continuation. */
internal class ComplaintAdminDeleteContinuation(private val phases: ComplaintAdminDeletePhaseExecutor, private val publisher: TestAdminDeleteJournalPublisherFactoryV1) {
    init { phases.requirePublisher(publisher) }
    fun complete(outcome: TestAdminDeleteAuthorizationV1, reserved: JournalPublicationLanesV1.TestAdminDeleteReservation? = null): ComplaintAdminDeleteReceipt {
        requireConnectionFree()
        phases.requirePublisher(publisher)
        return when (outcome) {
            is TestAdminDeleteAuthorizationV1.Completed -> outcome.receipt
            is TestAdminDeleteAuthorizationV1.Continue -> {
                val work = outcome.work
                val proof = when (work) {
                    is CommittedTestAdminDeleteWork.Prepared -> {
                        val readback = if (reserved == null) publisher.reserve().use { it.publish(work) } else reserved.publish(work)
                        phases.verify(readback)
                    }
                    is CommittedTestAdminDeleteWork.RecordedVerified -> phases.resume(work)
                    else -> error("Original closed work branch required")
                }
                phases.apply(work, proof)
            }
        }
    }
}
