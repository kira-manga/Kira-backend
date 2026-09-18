package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol

/** Separate closed operation custody, never G1's permanent allocation/pin inventory or an approval wire protocol. */
internal enum class CatalogSignerRotationReleaseLeafV1(internal val fileName: String, internal val maximumBytes: Int) {
    APPROVED_INTENT("approved-intent", CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES),
    TRUST_T0("trust-t0", OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
    TRUST_TN("trust-tn", OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
    APPROVAL_INPUTS("approval-inputs", CatalogSignerRotationCapacityV1.MAX_APPROVAL_BYTES),
    BINDING("binding", CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES),
    PREPARE_ARMED("prepare-armed", 1024),
    PREPARED("prepared", 1024),
    SIGN_ONE_ARMED("sign-one-armed", 1024),
    SIGN_TWO_ARMED("sign-two-armed", 1024),
    SIGN_ONE_RETURNED("sign-one-returned", 1024),
    SIGN_TWO_RETURNED("sign-two-returned", 1024),
    SIGNATURE_ONE("signature-one", OfflineTrustBundleProtocol.SIGNATURE_BYTES),
    SIGNATURE_TWO("signature-two", OfflineTrustBundleProtocol.SIGNATURE_BYTES),
    SIGN_ONE_SQL_ARMED("sign-one-sql-armed", 1024),
    SIGN_TWO_SQL_ARMED("sign-two-sql-armed", 1024),
    SIGN_ONE_SQL_PERSISTED("sign-one-sql-persisted", 1024),
    SIGN_TWO_SQL_PERSISTED("sign-two-sql-persisted", 1024),
    ENVELOPE("envelope", CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES),
    FREEZE_OUTCOME("freeze-outcome", 1024),
}

internal enum class CatalogSignerRotationCustodyObservationV1 { CREATED, IDENTICAL_OBSERVED }

internal enum class CatalogSignerRotationCustodyFailureV1 {
    INVALID_INPUT, INVALID_STATE, WRONG_CALLER, INTERRUPTED, TIME_BUDGET, UNSUPPORTED_FILESYSTEM,
    UNPROTECTED_PATH, LOCK_UNAVAILABLE, INVENTORY_REFUSED, INCOMPLETE, DIFFERENT_BYTES, IO_UNCERTAIN, CLEANUP_UNCERTAIN,
}

internal class CatalogSignerRotationCustodyExceptionV1(val code: CatalogSignerRotationCustodyFailureV1) :
    RuntimeException("Catalog signer rotation custody refused: ${code.name}.")

internal fun requireSignerRotationCustody(condition: Boolean, code: CatalogSignerRotationCustodyFailureV1) {
    if (!condition) throw CatalogSignerRotationCustodyExceptionV1(code)
}
