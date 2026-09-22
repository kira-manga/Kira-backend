package me.manga.kira.backend.complaint.domain

import kotlinx.serialization.Serializable
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import java.util.HexFormat

/** Immutable capacity-policy preimage P only: neither full desired configuration D nor activation authority. */
internal class ComplaintCapacityPolicyV1 private constructor(
    val hardLimit: ComplaintCapacityVector,
    val creationLimit: ComplaintCapacityVector,
    val dailyEnrollmentLimit: Long,
) {
    private val canonical = CanonicalJson.canonicalize(
        CapacityPolicyDocument.serializer(),
        CapacityPolicyDocument(
            kind = KIND,
            schemaVersion = SCHEMA_VERSION,
            canonicalizerId = CANONICALIZER_ID,
            accountingVersion = ACCOUNTING_VERSION,
            counters = ComplaintCapacityEncoding.vectorOrder().map { counter ->
                CapacityPolicyCounterDocument(counter.storedName, counter.storedOrdinal, hardLimit[counter], creationLimit[counter])
            },
            dailyEnrollmentLimit = dailyEnrollmentLimit,
        ),
    ).toByteArray(Charsets.UTF_8)

    val sha256: String = Sha256.hex(canonical)

    fun canonicalBytes(): ByteArray = canonical.copyOf()

    fun digestBytes(): ByteArray = HexFormat.of().parseHex(sha256)

    /** Admission rule only; historical v1 policies and their canonical bytes remain readable. */
    fun requireCleanStart() {
        val retired = listOf(
            ComplaintCapacityCounter.IMPORT_ARTIFACTS,
            ComplaintCapacityCounter.IMPORT_RUNS,
            ComplaintCapacityCounter.IMPORT_STAGING,
            ComplaintCapacityCounter.LEGACY_RECORDS,
        )
        if (retired.any { hardLimit[it] != 0L || creationLimit[it] != 0L }) {
            rejectCapacity(ComplaintCapacityFailureCode.INVALID_CONFIGURATION)
        }
    }

    override fun toString(): String = "ComplaintCapacityPolicyV1(redacted)"

    companion object {
        private const val KIND = "kira-complaint-capacity-policy"
        private const val SCHEMA_VERSION = 1
        private const val CANONICALIZER_ID = "kcj-1"
        private const val ACCOUNTING_VERSION = 1

        /** No supplied digest, alternate schema, observations or implicit/clamped limits. Vectors are already immutable. */
        fun of(hardLimit: ComplaintCapacityVector, creationLimit: ComplaintCapacityVector, dailyEnrollmentLimit: Long): ComplaintCapacityPolicyV1 {
            ComplaintCapacityEncoding.requireVersion(ACCOUNTING_VERSION)
            if (CanonicalJson.CANON_VERSION != CANONICALIZER_ID || dailyEnrollmentLimit < 0L) {
                rejectCapacity(ComplaintCapacityFailureCode.INVALID_CONFIGURATION)
            }
            if (!creationLimit.fitsWithin(hardLimit)) rejectCapacity(ComplaintCapacityFailureCode.INVALID_CREATION_LIMIT)
            return ComplaintCapacityPolicyV1(hardLimit, creationLimit, dailyEnrollmentLimit)
        }
    }
}

// No defaults: every domain/version field and every zero limit must be present under kcj-1.
@Serializable
private class CapacityPolicyDocument(
    val kind: String,
    val schemaVersion: Int,
    val canonicalizerId: String,
    val accountingVersion: Int,
    val counters: List<CapacityPolicyCounterDocument>,
    val dailyEnrollmentLimit: Long,
)

@Serializable
private class CapacityPolicyCounterDocument(val name: String, val ordinal: Int, val hardLimit: Long, val creationLimit: Long)
