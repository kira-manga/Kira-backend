package me.manga.kira.backend.complaint.domain

enum class ComplaintField {
    RESOURCE_ID,
    INSTALLATION_ID,
    DATA_SCOPE_ID,
    IDEMPOTENCY_KEY,
    FINGERPRINT,
    SUBJECT,
    BODY,
    CLOSURE_REASON,
    APP_VERSION,
    OS_VERSION,
    MANUFACTURER,
    DEVICE_MODEL,
    NOTICE_KEY,
    SEARCH,
    PAGE_LIMIT,
    BATCH_SIZE,
}

enum class ComplaintValidationReason {
    REQUIRED,
    TOO_SHORT,
    TOO_LONG,
    FORBIDDEN_CONTROL,
    MALFORMED_UNICODE,
    NON_CANONICAL_UUID,
    UUID_NOT_V4,
    INVALID_SCOPE,
    INVALID_NOTICE_KEY,
    INVALID_FINGERPRINT,
    OUT_OF_RANGE,
}

/** Only closed field/code names may cross the error boundary, never the rejected input. */
class ComplaintValidationException(val field: ComplaintField, val reason: ComplaintValidationReason) :
    RuntimeException("Complaint validation failed: ${field.name}/${reason.name}.")

enum class ComplaintRuleCode {
    IMMUTABLE_NOTICE,
    INVALID_STATUS_TARGET,
    NO_CHANGE,
    INVALID_MODERATION_STATE,
    VERSION_EXHAUSTED,
    INVALID_INSTALLATION_PAIR,
    INSTALLATION_IDENTITY_MISMATCH,
    INSTALLATION_SCOPE_MISMATCH,
    CONFLICTING_TERMINAL_EVIDENCE,
    INVALID_TEST_MANIFEST,
    INVALID_PURGED_TEST_STATE,
}

class ComplaintRuleException(val code: ComplaintRuleCode) : RuntimeException("Complaint rule rejected: ${code.name}.")
