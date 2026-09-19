package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol

/** One closed TEST freeze/delivery inventory. No rotation, PROJECT, issuer or later-run leaves. */
internal enum class CatalogTestRunActivationReleaseLeafV1(internal val fileName: String, internal val maximumBytes: Int) {
    APPROVED_INTENT("approved-intent", OfflineCatalogTestRunActivationProtocol.MAX_DOCUMENT_BYTES),
    FULL_CONFIGURATION("full-configuration", 524288),
    TRUST_T0("trust-t0", OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
    TRUST_TN("trust-tn", OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
    INITIAL_REGISTRY("initial-registry", OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES),
    APPROVAL_INPUTS("approval-inputs", 4096),
    GLOBAL_PREIMAGE("global-preimage", 524288),
    BINDING("binding", 4096),
    PREPARE_ARMED("prepare-armed", 1024),
    PREPARED("prepared", 1024),
    SIGN_ARMED("sign-armed", 1024),
    SIGNATURE("signature", OfflineTrustBundleProtocol.SIGNATURE_BYTES),
    ENVELOPE("envelope", OfflineCatalogTestRunActivationProtocol.MAX_DOCUMENT_BYTES),
    SIGN_RETURNED("sign-returned", 1024),
    SIGNATURE_SQL_ARMED("signature-sql-armed", 1024),
    SIGNATURE_SQL_PERSISTED("signature-sql-persisted", 1024),
    FREEZE_OUTCOME("freeze-outcome", 1024),
    PUBLICATION_ARMED("publication-armed", 1024),
    PUBLICATION_ACKNOWLEDGED("publication-acknowledged", 4096),
    PUBLICATION_AWAIT_REPLICATION("publication-await-replication", 4096),
    PRIMARY_COPY("primary-copy", 65536),
    REPLICA_COPY("replica-copy", 65536),
    PUBLICATION_DUAL_COPY("publication-dual-copy", 4096),
    COMPLETE_ARMED("complete-armed", 1024),
    COMPLETE_OUTCOME("complete-outcome", 1024),
    PENDING_RELOAD_OUTCOME("pending-reload-outcome", 1024),
}

internal enum class CatalogTestRunActivationCustodyObservationV1 { CREATED, IDENTICAL_OBSERVED }

internal enum class CatalogTestRunActivationCustodyFailureV1 {
    INVALID_INPUT, INVALID_STATE, WRONG_CALLER, INTERRUPTED, TIME_BUDGET, UNSUPPORTED_FILESYSTEM,
    UNPROTECTED_PATH, LOCK_UNAVAILABLE, INVENTORY_REFUSED, INCOMPLETE, DIFFERENT_BYTES, IO_UNCERTAIN, CLEANUP_UNCERTAIN,
}

internal class CatalogTestRunActivationCustodyExceptionV1(val code: CatalogTestRunActivationCustodyFailureV1) :
    RuntimeException("Catalog TEST activation custody refused: ${code.name}.")

internal fun requireTestActivationCustody(condition: Boolean, code: CatalogTestRunActivationCustodyFailureV1) {
    if (!condition) throw CatalogTestRunActivationCustodyExceptionV1(code)
}
