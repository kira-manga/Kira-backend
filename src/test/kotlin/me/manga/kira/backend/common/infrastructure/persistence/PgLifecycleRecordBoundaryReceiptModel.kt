package me.manga.kira.backend.common.infrastructure.persistence

/** Exact F→G→T-serialized post-return MODEL journal. No native Thread observation or invented ACK/resource receipt. */
internal class PgLifecycleRecordBoundaryReceiptModel(
    private val record: PgLifecycleRecordBoundaryRecord,
    private val access: PgLifecycleRecordBoundaryAccess,
    private val hold: PgLifecycleRecordBoundaryTask,
) {
    private var original: PgLifecycleRecordBoundaryCallState? = null
    private var endedOwned = false
    private var outcomeOwned = false
    private var ownedWrites = 0

    /** Fixture retains this owner first. Original state and every nonmonotone write are serialized against the real G classifier. */
    fun suppressEndedReceipt() {
        access.requireGenuineScheduledPending() // Actual Timer-thread liveness observation only outside F/G/T.
        check(hold.isHeldRunning())
        awaitLifecycleFact {
            record.cut {
                check(original == null && !endedOwned && !outcomeOwned && ownedWrites == 0)
                requireIdentityLocked()
                check(hold.isHeldRunning() && !access.cellAcknowledged())
                val saved = access.state()
                check(saved.claimed && saved.observation.scheduling == PersistenceTimerCall(true, PersistenceTimerOutcome.RETURNED, true))
                original = saved
                check(access.extentEnded.compareAndSet(true, false))
                endedOwned = true // Only a successful CAS owns a write; a failed precondition owns nothing.
                ownedWrites++
                true
            } == true
        }
        println("PG_LIFECYCLE_RECORD_BOUNDARY_RECEIPT_JOURNAL stage=exit_suppressed owned_writes=1 proof=MODEL_G_SERIALIZED")
    }

    fun requireAckWithoutExit() {
        awaitLifecycleFact {
            record.cut {
                requireIdentityLocked()
                requireExpectedLocked()
                check(endedOwned && !outcomeOwned && access.cellAcknowledged() && !access.boundary.acknowledged())
                true
            } == true
        }
    }

    fun modelFailedEndedReceipt() {
        awaitLifecycleFact {
            record.cut {
                requireIdentityLocked()
                requireExpectedLocked()
                check(endedOwned && !outcomeOwned && access.cellAcknowledged())
                check(access.outcome.compareAndSet(PersistenceTimerOutcome.RETURNED, PersistenceTimerOutcome.THREW))
                outcomeOwned = true
                ownedWrites++
                // Outcome FIRST, and both writes under exact G: no classifier may span this transition.
                check(access.extentEnded.compareAndSet(false, requireNotNull(original).observation.scheduling.extentEnded))
                endedOwned = false // This field now equals its original value; restoration no longer owns an ended write.
                ownedWrites++
                requireExpectedLocked()
                check(!access.boundary.acknowledged())
                true
            } == true
        }
    }

    fun restore() {
        if (!endedOwned && !outcomeOwned) return
        awaitLifecycleFact {
            record.cut {
                requireIdentityLocked()
                requireExpectedLocked() // Drift/stale membership authorizes NO repair or foreign overwrite.
                val saved = requireNotNull(original).observation.scheduling
                if (outcomeOwned) {
                    check(access.outcome.compareAndSet(PersistenceTimerOutcome.THREW, saved.outcome))
                    outcomeOwned = false
                    ownedWrites++
                }
                if (endedOwned) {
                    check(access.extentEnded.compareAndSet(false, saved.extentEnded))
                    endedOwned = false
                    ownedWrites++
                }
                check(access.boundary.observation().scheduling == saved)
                true
            } == true
        }
        println("PG_LIFECYCLE_RECORD_BOUNDARY_MODEL_RESTORED original_call_only=true owned_writes=$ownedWrites proof=MODEL_G_SERIALIZED_RESTORE")
    }

    private fun requireIdentityLocked() {
        check(record.binding.rendezvous.lock.isHeldByCurrentThread && record.queryWitness.isHeldByCurrentThread)
        check(record.transportLock.isHeldByCurrentThread)
        check(record.exactCurrentLocked() && record.exactPrimaryLocked() && record.fixedStrongLocked() && record.processingEndedLocked())
        check(record.entry.retiring && record.entry.terminal === record.work.claim && record.work.producerDrainProven())
        check(record.boundaryReference.get() === access.boundary && record.workRunnerReference.get() === access.runner)
        check(record.work.disposition() === PersistenceTerminalDisposition.PENDING && !record.work.bodyExited())
    }

    private fun requireExpectedLocked() {
        val saved = requireNotNull(original)
        check(access.claimed.get() == saved.claimed && access.entered.get() == saved.observation.scheduling.entered)
        check(access.extentEnded.get() == if (endedOwned) false else saved.observation.scheduling.extentEnded)
        check(access.outcome.get() === if (outcomeOwned) PersistenceTimerOutcome.THREW else saved.observation.scheduling.outcome)
    }
}
