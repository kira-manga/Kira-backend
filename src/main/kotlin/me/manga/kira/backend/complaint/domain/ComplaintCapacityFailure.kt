package me.manga.kira.backend.complaint.domain

enum class ComplaintCapacityFailureCode {
    UNSUPPORTED_VERSION,
    INVALID_COUNTER_ENCODING,
    INVALID_VECTOR_WIDTH,
    NEGATIVE_AMOUNT,
    AMOUNT_OVERFLOW,
    INSUFFICIENT_UNITS,
    INVALID_CREATION_LIMIT,
    INCONSISTENT_BALANCE,
    INVALID_CONFIGURATION,
    CONFIGURATION_MISMATCH,
    CREATION_CLOSED,
    CREATION_LIMIT_REACHED,
    RESERVATION_EXCEEDED,
    INVALID_DAILY_BUCKET,
    DAILY_LIMIT_REACHED,
}

/** Fixed categories only: never put a supplied configuration, amount or identifier in diagnostics. */
class ComplaintCapacityException(val code: ComplaintCapacityFailureCode) : RuntimeException("Complaint capacity rejected: ${code.name}.")

internal fun rejectCapacity(code: ComplaintCapacityFailureCode): Nothing = throw ComplaintCapacityException(code)
