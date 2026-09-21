package me.manga.kira.backend.complaint.catalog

import software.amazon.awssdk.http.SdkHttpClient
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1

/**
 * Closed test input: three raw SDK-client factories selected before protected intake and full D.
 * No policy, row/result, lease, publication proof or owner can be supplied through this holder.
 * The separate strict ordinary-SEAL fixture remains responsible for its own STS/KMS/S3 traffic.
 */
internal class TestActiveOrdinaryRawHttpV1(
    val sts: (remainingMillis: () -> Int) -> SdkHttpClient,
    val kms: (remainingMillis: () -> Int) -> SdkHttpClient,
    val s3: (remainingMillis: () -> Int) -> SdkHttpClient,
    // Separate immutable TEST recipe selection before intake/D; absent preserves every old scenario.
    val initialCheckpoint: TestActiveInitialCheckpointHttpInputV1? = null,
)
