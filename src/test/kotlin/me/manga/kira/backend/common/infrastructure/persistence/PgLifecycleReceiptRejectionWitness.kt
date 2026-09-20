package me.manga.kira.backend.common.infrastructure.persistence

/** Pure Linux-LF decoded-output witness; the parent supplies its original nonce and actual completed-child facts. */
internal object PgLifecycleReceiptRejectionWitness {
    const val FINAL_RECEIPT_REASON = "Synthetic lifecycle child final receipt rejected."

    fun matches(mode: PgLifecycleCase, nonce: String, output: String?, exit: Int, rejection: IllegalStateException): Boolean {
        if (output == null || exit != 0) return false
        if (rejection.message != FINAL_RECEIPT_REASON || rejection.cause != null || rejection.suppressed.isNotEmpty()) return false
        val expected = when (mode) {
            PgLifecycleCase.MISSING_RECEIPT -> ""

            PgLifecycleCase.WRONG_RECEIPT -> "PG_LIFECYCLE_VERIFIED mode=${mode.name} nonce=wrong\n"

            PgLifecycleCase.DUPLICATE_RECEIPT -> {
                val line = "PG_LIFECYCLE_VERIFIED mode=${mode.name} nonce=$nonce\n"
                line + line
            }

            else -> return false
        }
        return output == expected
    }
}
