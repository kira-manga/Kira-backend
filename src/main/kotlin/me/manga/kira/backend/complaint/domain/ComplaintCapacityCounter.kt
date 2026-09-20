package me.manga.kira.backend.complaint.domain

/** Explicit version-1 identities. Neither the enum ordinal nor alphabetic position is a stored index. */
enum class ComplaintCapacityCounter(val storedOrdinal: Int, val storedName: String) {
    APP_INSTALLATIONS(1, "app_installations"),
    AUDIT_ROWS(2, "audit_rows"),
    CATALOG_MUTATIONS(3, "catalog_mutations"),
    COMPLAINT_ROWS(4, "complaint_rows"),
    IMPORT_ARTIFACTS(5, "import_artifacts"),
    IMPORT_RUNS(6, "import_runs"),
    IMPORT_STAGING(7, "import_staging"),
    INSTALLATION_IDS(8, "installation_ids"),
    INSTALLATION_RECEIPTS(9, "installation_receipts"),
    JOURNAL_APPLIED(10, "journal_applied"),
    JOURNAL_CONTROL(11, "journal_control"),
    JOURNAL_PUBLICATIONS(12, "journal_publications"),
    JOURNAL_RETIREMENTS(13, "journal_retirements"),
    LEGACY_RECORDS(14, "legacy_records"),
    MODERATION_GRANTS(15, "moderation_grants"),
    NORMAL_RECEIPTS(16, "normal_receipts"),
    RECOVERY_RESERVATIONS(17, "recovery_reservations"),
    RESOURCE_IDS(18, "resource_ids"),
    SCAN_ENTRIES(19, "scan_entries"),
    SCAN_RUNS(20, "scan_runs"),
    STORAGE_BYTES(21, "storage_bytes"),
    TEST_RUNS(22, "test_runs"),
}

object ComplaintCapacityEncoding {
    const val VERSION = 1
    const val WIDTH = 22

    fun requireVersion(version: Int) {
        if (version != VERSION) rejectCapacity(ComplaintCapacityFailureCode.UNSUPPORTED_VERSION)
    }

    fun counter(version: Int, storedOrdinal: Int, storedName: String): ComplaintCapacityCounter {
        requireVersion(version)
        return ComplaintCapacityCounter.entries.singleOrNull { it.storedOrdinal == storedOrdinal && it.storedName == storedName }
            ?: rejectCapacity(ComplaintCapacityFailureCode.INVALID_COUNTER_ENCODING)
    }

    fun vectorOrder(): List<ComplaintCapacityCounter> = ComplaintCapacityCounter.entries.sortedBy { it.storedOrdinal }

    /** Lock ordering is explicitly name-based, independent of vector encoding or enum declaration order. */
    fun lockOrder(): List<ComplaintCapacityCounter> = ComplaintCapacityCounter.entries.sortedBy { it.storedName }
}
