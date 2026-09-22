package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOrdinarySealRecoveryInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveOwnerDeleteQueueHttpInputV1
import software.amazon.awssdk.http.SdkHttpClient

/**
 * Closed test input: raw SDK-client factories and immutable recipes selected before protected intake/full D.
 * No runtime policy, row/result, lease, publication proof or owner can be supplied through this holder.
 * The separate strict ordinary-SEAL fixture remains responsible for its own STS/KMS/S3 traffic.
 */
internal class TestActiveOrdinaryRawHttpV1(
    val sts: (remainingMillis: () -> Int) -> SdkHttpClient,
    val kms: (remainingMillis: () -> Int) -> SdkHttpClient,
    val s3: (remainingMillis: () -> Int) -> SdkHttpClient,
    // Separate immutable TEST recipe selection before intake/D; absent preserves every old scenario.
    val initialCheckpoint: TestActiveInitialCheckpointHttpInputV1? = null,
    val activeSealRecovery: TestActiveOrdinarySealRecoveryInputV1? = null,
    val initialCheckpointCreate: TestInitialCheckpointCreateInputV1? = null,
    // Explicit TEST-only birth configuration. Tightens J before parsing/D; never changes a live deadline.
    val shortInitialCheckpointFreshness: Boolean = false,
    val activeOwnerDeleteQueue: TestActiveOwnerDeleteQueueHttpInputV1? = null,
    val initialCheckpointDeletion: TestInitialCheckpointDeletionInputV1? = null,
    val activeRecurrent: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentInputV1? = null,
    // Cold TEST construction input; only claimant races opt in to a second P-1 ordinary owner.
    val ordinaryPoolSize: Int = 2,
    // Optional born-with Admin read declaration; absence preserves every existing fixture's full D.
    val adminRead: me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminReadInputV1? = null,
    // Optional single-content/password cohort; never supplies a grant, authenticated identity or current checkpoint.
    val adminContent: me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminContentInputV1? = null,
    // Optional status/closure declaration; selection and every grant/current-state check remain product work.
    val adminStatus: me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminStatusInputV1? = null,
) {
    init { require(ordinaryPoolSize == 2 || ordinaryPoolSize == 3 && initialCheckpointCreate != null) }
}
