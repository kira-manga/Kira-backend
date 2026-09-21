package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOrdinarySealRecoveryInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
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
)
