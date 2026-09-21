package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOrdinarySealRecoveryInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOrdinarySealRecoveryV1

/** Literal test selector supplied before protected parsing/D, not a restored owner or phase result. */
internal object TestActiveSealRecoveryInputFixtureV1 {
    fun input() = TestActiveOrdinarySealRecoveryInputV1(1, VersionBoundTestActiveOrdinarySealRecoveryV1.PROFILE)
    fun currentPutFloorInput() = TestActiveOrdinarySealRecoveryInputV1(1, VersionBoundTestActiveOrdinarySealRecoveryV1.CURRENT_PUT_FLOOR_PROFILE)
}
