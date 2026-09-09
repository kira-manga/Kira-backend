package me.manga.kira.backend.common.infrastructure.persistence

internal enum class PgLifecycleRecordBoundaryMode(val deletion: Boolean, val requests: Int) {
    RECORD_BOUNDARY_REUSE_ORDINARY(false, 2),
    RECORD_BOUNDARY_REUSE_DELETION(true, 2),
    MODEL_RECORD_BOUNDARY_RECEIPTS_ORDINARY(false, 1),
    MODEL_RECORD_BOUNDARY_RECEIPTS_DELETION(true, 1),
    MODEL_RECORD_BOUNDARY_ADAPTER_AUTHORIZATION(false, 0),
    ;

    companion object {
        fun from(mode: PgLifecycleCase): PgLifecycleRecordBoundaryMode? = entries.singleOrNull { it.name == mode.name }
    }
}
