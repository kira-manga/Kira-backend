package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminDeletePhaseExecutor

/** No re-claim, semantic charge, current key selection or exception-to-202 shortcut on continuation. */
internal class ComplaintAdminDeleteContinuation(private val phases: ComplaintAdminDeletePhaseExecutor, private val publisher: TestAdminDeleteJournalPublisherFactoryV1) {
    init { phases.requirePublisher(publisher) }

    /** Registered A only. A Completed hint/recorded proof is not a fresh receipt and cannot borrow B's APPLY. */
    fun publishRegistered(outcome: TestAdminDeleteAuthorizationV1, reserved: JournalPublicationLanesV1.TestAdminDeleteReservation? = null) {
        requireConnectionFree()
        phases.requirePublisher(publisher)
        when (outcome) {
            is TestAdminDeleteAuthorizationV1.Completed -> Unit
            is TestAdminDeleteAuthorizationV1.Continue -> when (val work = outcome.work) {
                is CommittedTestAdminDeleteWork.Prepared -> {
                    if (reserved == null) publisher.reserve().use { lane -> phases.verify(work, lane.publish(work), lane) }
                    else phases.verify(work, reserved.publish(work), reserved)
                }
                is CommittedTestAdminDeleteWork.RecordedVerified -> Unit
                else -> error("Original closed work branch required")
            }
        }
    }

    /** Historical lower TEST continuation, never the registered request completion path. */
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
