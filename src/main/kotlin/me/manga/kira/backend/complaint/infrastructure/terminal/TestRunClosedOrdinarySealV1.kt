package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree

/** Connected consumer, not a transferable closeout capability or a caller-supplied completed flag. */
internal object TestRunClosedOrdinarySealV1 {
    fun complete(drain: TestRunOrdinaryDrainV1) {
        requireConnectionFree()
        drain.requireCloseoutReady()
        val original = TestRunOrdinarySealV1.forClosedDrain(drain)
        requireDrain(original.seal() === TestRunOrdinarySealResultV1.POST_DENIAL_ORDINARY_SET_SEAL_VERIFIED)
    }
}
