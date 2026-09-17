package me.manga.kira.backend.security

/** Explicit single-process configuration, not proof that an operator deployed only one replica. */
internal class ComplaintAdmissionPolicy(
    coordinationMode: String,
    declaredInstances: Int,
    val concurrentLimit: Int,
    val ingressBucketLimit: Int,
    val ingressPerMinute: Int,
    val semanticBucketLimit: Int,
    val semanticEventLimit: Int,
    val pruneBatch: Int,
    val enrollment: ComplaintEnrollmentAdmissionPolicy,
) {
    init {
        require(coordinationMode == "memory" && declaredInstances == 1) { INVALID_ADMISSION_CONFIGURATION }
        require(concurrentLimit in 1..64 && ingressBucketLimit in 1..2048 && ingressPerMinute in 1..120) { INVALID_ADMISSION_CONFIGURATION }
        require(semanticBucketLimit in 1..4096 && semanticEventLimit in 1..131072 && pruneBatch in 1..128) { INVALID_ADMISSION_CONFIGURATION }
    }

    override fun toString(): String = "ComplaintAdmissionPolicy(single-instance,redacted)"

    companion object {
        const val SECOND_NANOS = 1_000_000_000L
        const val INGRESS_WINDOW_NANOS = 60 * SECOND_NANOS
        const val INGRESS_IDLE_NANOS = 120 * SECOND_NANOS
        const val SESSION_WINDOW_NANOS = 3600 * SECOND_NANOS
        const val DELETE_ALL_WINDOW_NANOS = 24 * SESSION_WINDOW_NANOS
        const val PREVIOUS_RETENTION_NANOS = 25 * 3600 * SECOND_NANOS
        const val ADMISSION_LIFETIME_NANOS = 5 * SECOND_NANOS
        const val SESSION_ACTOR_LIMIT = 30
        const val SESSION_IP_LIMIT = 100
        const val BOOTSTRAP_IP_LIMIT = 120
        const val ENROLLMENT_IP_LIMIT = 10
    }
}

internal fun interface ComplaintAdmissionNanoClock {
    fun now(): Long
}

internal object SystemComplaintAdmissionNanoClock : ComplaintAdmissionNanoClock {
    override fun now(): Long = System.nanoTime()
}

internal enum class ComplaintAdmissionFailure { RATE_LIMITED, UNAVAILABLE, INVALID_CONTEXT, ROTATION_REFUSED }

/** Only bounded codes/times, never a cause, request, key, address or actor. */
internal class ComplaintAdmissionRejected(val code: ComplaintAdmissionFailure, val retryAfterSeconds: Long? = null) :
    RuntimeException("Complaint admission refused") {
    init {
        require((code == ComplaintAdmissionFailure.RATE_LIMITED) == (retryAfterSeconds != null))
        require(retryAfterSeconds == null || retryAfterSeconds in 1..86400)
    }

    val status: Int get() = if (code == ComplaintAdmissionFailure.RATE_LIMITED) 429 else 503
}

internal fun refuseComplaintAdmission(code: ComplaintAdmissionFailure = ComplaintAdmissionFailure.UNAVAILABLE): Nothing = throw ComplaintAdmissionRejected(code)

internal const val INVALID_ADMISSION_CONFIGURATION = "Invalid complaint admission configuration"
