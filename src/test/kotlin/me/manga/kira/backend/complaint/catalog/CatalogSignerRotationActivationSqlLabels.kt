package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationLeaseSqlV1.ACQUIRE_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationLeaseSqlV1.LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationLeaseSqlV1.READ_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.INSERT_SIGNER_ROTATION_ACTIVATION_PREPARED
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_HEAD_LEASE
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_LEASE
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_SIGNER_ROTATION_ACTIVATION_CLEAR_PENDING
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_SIGNER_ROTATION_ACTIVATION_COMPLETION
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_SIGNER_ROTATION_ACTIVATION_HEAD
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_SIGNER_ROTATION_ACTIVATION_PROJECTION
import me.manga.kira.backend.complaint.infrastructure.catalog.WRITE_SIGNER_ROTATION_ACTIVATION_SIGNATURE

/** Labels only for the existing real TLS/JDBC observer; never SQL dispatch or accepted history/capability fabrication. */
@Suppress("CyclomaticComplexMethod")
internal fun signerRotationActivationSqlStep(sql: String): String? = when (sql) {
    LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE_CONTROL -> "activation-pending-lease-lock"
    READ_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE_CONTROL -> "activation-pending-lease-read"
    ACQUIRE_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE -> "activation-pending-lease-acquire"
    LOCK_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL -> "activation-head-control"
    READ_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL -> "activation-head-control-read"
    READ_SIGNER_ROTATION_ACTIVATION_HEAD_LEASE -> "activation-head-lease"
    LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL -> "activation-pending-control"
    READ_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL -> "activation-pending-control-read"
    READ_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE -> "activation-pending-lease"
    LOCK_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL -> "activation-projected-control"
    READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL -> "activation-projected-control-read"
    READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_LEASE -> "activation-projected-lease"
    LOCK_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY -> "activation-head-history-lock"
    READ_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY -> "activation-head-history-read"
    LOCK_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY -> "activation-prepared-history-lock"
    READ_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY -> "activation-prepared-history-read"
    LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY -> "activation-pending-history-lock"
    READ_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY -> "activation-pending-history-read"
    LOCK_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY -> "activation-projected-history-lock"
    READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY -> "activation-projected-history-read"
    INSERT_SIGNER_ROTATION_ACTIVATION_PREPARED -> "activation-prepare"
    WRITE_SIGNER_ROTATION_ACTIVATION_SIGNATURE -> "activation-signature"
    WRITE_SIGNER_ROTATION_ACTIVATION_COMPLETION -> "activation-complete"
    WRITE_SIGNER_ROTATION_ACTIVATION_HEAD -> "activation-head"
    WRITE_SIGNER_ROTATION_ACTIVATION_PROJECTION -> "activation-project"
    WRITE_SIGNER_ROTATION_ACTIVATION_CLEAR_PENDING -> "activation-clear-pending"
    else -> null
}
