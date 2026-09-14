package me.manga.kira.backend.security

import java.time.Instant

/**
 * One source-only cleanup batch inside the selected ordinary persistence phase.
 * The named executor supplies its once-sampled trusted Clock cutoff; this is not a caller-facing
 * expiry override. Return only the affected count in 0..50, never grant IDs or proof material.
 */
internal interface SourceGrantCleanup {
    fun deleteEligibleSourceGrants(cutoff: Instant): Int
}
