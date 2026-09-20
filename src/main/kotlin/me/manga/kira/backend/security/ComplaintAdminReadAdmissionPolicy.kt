package me.manga.kira.backend.security

/** Approved TEST-only combined search/detail/stats ceiling. Neither a config switch nor deployment authority. */
internal sealed interface ComplaintAdminReadAdmissionPolicy {
    data object Disabled : ComplaintAdminReadAdmissionPolicy

    class Bounded(val perMinute: Int = 60) : ComplaintAdminReadAdmissionPolicy {
        init {
            require(perMinute in 1..60) { INVALID_ADMISSION_CONFIGURATION }
        }

        override fun toString(): String = "ComplaintAdminReadAdmissionPolicy.Bounded"
    }
}
