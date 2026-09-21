package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstCutInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOrdinaryPublicationInputV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials

/** Cold synthetic inputs only. No scope, row, signed approval, lease or successful capture is supplied. */
internal object TestActiveFirstCutInputFixtureV1 {
    const val ORDINARY_SESSION = "synthetic-active-ordinary"
    val ordinaryCredentials: AwsSessionCredentials = AwsSessionCredentials.create(
        "SYNTHETICORDINARYKEY",
        "synthetic-ordinary-secret-not-a-real-credential",
        "synthetic-ordinary-session-token",
    )
    fun input() = TestActiveFirstCutInputV1(1, TestActiveFirstSealStorageV1.PROFILE)
    fun ordinaryInput() = TestActiveOrdinaryPublicationInputV1(ORDINARY_SESSION)
}
