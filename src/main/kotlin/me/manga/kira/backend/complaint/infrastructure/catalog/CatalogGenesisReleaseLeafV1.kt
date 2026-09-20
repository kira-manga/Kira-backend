package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol

/**
 * Fixed raw-byte slots, not an approval format or a stage/replay state machine. The future entry must
 * validate their existing wire formats and bindings; custody only checks byte bounds and completeness.
 */
internal enum class CatalogGenesisReleaseLeafV1(internal val fileName: String, internal val maximumBytes: Int) {
    APPROVED_INTENT("approved-intent", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    TRUST_T0("trust-t0", OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
    TRUST_TN("trust-tn", OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
    APPROVAL_INPUTS("approval-inputs", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    SIGNATURE("signature", OfflineTrustBundleProtocol.SIGNATURE_BYTES),
    ENVELOPE("envelope", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    PIN("pin", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    PUBLIC_TARGET_BINDINGS("public-target-bindings", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    RETENTION_BINDINGS("retention-bindings", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    PRIMARY_READBACK_EVIDENCE("primary-readback-evidence", 65_536),
    SECONDARY_READBACK_EVIDENCE("secondary-readback-evidence", 65_536),
    FREEZE_ARMED("freeze-armed", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FREEZE_OUTCOME("freeze-outcome", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FREEZE_PREPARED_NO_SIGNATURE("freeze-prepared-no-signature", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FREEZE_SIGN_ARMED("freeze-sign-armed", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FREEZE_SIGNATURE_PERSISTENCE_ARMED("freeze-signature-persistence-armed", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FREEZE_SIGNATURE_PERSISTED("freeze-signature-persisted", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FREEZE_PIN_COMMITMENT_ARMED("freeze-pin-commitment-armed", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FIRST_D_ARMED("first-d-armed", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FIRST_D_OUTCOME("first-d-outcome", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    PUBLISH_ARMED("publish-armed", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    PUBLISH_OUTCOME("publish-outcome", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FINALIZE_ARMED("finalize-armed", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
    FINALIZE_OUTCOME("finalize-outcome", CatalogGenesisCapacity.MAX_DOCUMENT_BYTES),
}

/** Neither result grants effect/replay eligibility or proves total stage cleanup. */
internal enum class CatalogGenesisCustodyObservationV1 { CREATED, IDENTICAL_OBSERVED }

internal enum class CatalogGenesisCustodyFailureV1 {
    INVALID_INPUT,
    INVALID_STATE,
    WRONG_CALLER,
    INTERRUPTED,
    TIME_BUDGET,
    UNSUPPORTED_FILESYSTEM,
    UNPROTECTED_PATH,
    LOCK_UNAVAILABLE,
    INVENTORY_REFUSED,
    INCOMPLETE,
    DIFFERENT_BYTES,
    IO_UNCERTAIN,
    CLEANUP_UNCERTAIN,
}

/** No path, supplied bytes, native exception, cause or exception transcript crosses this boundary. */
internal class CatalogGenesisCustodyExceptionV1(val code: CatalogGenesisCustodyFailureV1) :
    RuntimeException("Catalog genesis release custody refused: ${code.name}.")

internal fun requireGenesisCustody(condition: Boolean, code: CatalogGenesisCustodyFailureV1) {
    if (!condition) throw CatalogGenesisCustodyExceptionV1(code)
}
