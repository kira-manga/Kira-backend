package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import org.junit.jupiter.api.Assertions.assertEquals
import java.sql.Timestamp
import java.util.UUID

/**
 * Row-shape fixtures only: no complaint enrollment, request admission, receipt charge, run authority
 * or W04 terminal-rejection behavior is implemented or certified by these statements.
 */
internal fun seedComplaintAuditRows(f: OrdinaryComplaintAuditFixture) {
    val at = Timestamp.from(f.at)
    f.observer.update(
        "INSERT INTO complaint_installation_ids (id, data_scope_id, test_only, state, created_at) VALUES (?, ?, true, 'ACTIVE', ?)",
        f.installationId,
        f.scope.id,
        at,
    )
    f.observer.update(
        "INSERT INTO app_installations (id, data_scope_id, test_only, secret_verifier, platform, state, credential_version, " +
            "owner_reference, created_at, last_authenticated_at, version) VALUES (?, ?, true, ?, 'ANDROID', 'ACTIVE', 1, ?, ?, ?, 1)",
        f.installationId,
        f.scope.id,
        ByteArray(32) { 29 },
        UUID.randomUUID(),
        at,
        at,
    )
    f.observer.update(
        "INSERT INTO complaint_resource_ids (id, data_scope_id, test_only, state, created_at) VALUES (?, ?, true, 'LIVE', ?)",
        f.resourceId,
        f.scope.id,
        at,
    )
    f.observer.update(
        "INSERT INTO complaints (id, data_scope_id, test_only, owner_id, ownership, kind, type, status, subject, body, " +
            "platform, os_version, manufacturer, device_model, created_at, updated_at, version) " +
            "VALUES (?, ?, true, ?, 'INSTALLATION', 'REPORT', 'TECHNICAL', 'OPEN', ?, ?, 'ANDROID', '', '', '', ?, ?, 1)",
        f.resourceId,
        f.scope.id,
        f.installationId,
        "synthetic-private-subject",
        "synthetic-private-body",
        at,
        at,
    )
    f.observer.update(
        "INSERT INTO admin_step_up_grants (id, user_id, token_hash, scope, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
        f.grantId,
        f.ordinary.userId,
        Sha256.hexUtf8(f.token),
        ScopedAdminStepUpScope.COMPLAINT.storedName,
        Timestamp.from(f.at.minusSeconds(60)),
        Timestamp.from(f.at.plusSeconds(600)),
    )
}

internal fun claimComplaintAuditReceipt(f: OrdinaryComplaintAuditFixture) {
    assertEquals(
        1,
        f.jdbc.update(
            "INSERT INTO complaint_idempotency_receipts (actor_kind, actor_id, idempotency_key, operation, fingerprint, target_ids, " +
                "data_scope_id, test_only, state, created_at) VALUES ('ADMIN', ?, ?, 'ADMIN_STATUS', ?, ARRAY[?]::uuid[], ?, true, 'IN_PROGRESS', ?)",
            f.ordinary.userId,
            f.receiptId,
            ByteArray(32) { 42 },
            f.resourceId,
            f.scope.id,
            Timestamp.from(f.at),
        ),
    )
}

internal fun changeComplaintAuditStatus(f: OrdinaryComplaintAuditFixture) {
    val current = f.jdbc.query(
        "SELECT status, version FROM complaints WHERE id = ? AND data_scope_id = ? FOR UPDATE",
        { result, _ -> result.getString(1) to result.getLong(2) },
        f.resourceId,
        f.scope.id,
    )
    assertEquals(listOf("OPEN" to 1L), current)
    assertEquals(
        1,
        f.jdbc.update(
            "UPDATE complaints SET status = 'IN_PROGRESS', version = 2, updated_at = ? " +
                "WHERE id = ? AND data_scope_id = ? AND status = 'OPEN' AND version = 1",
            Timestamp.from(f.at),
            f.resourceId,
            f.scope.id,
        ),
    )
}

internal fun completeComplaintAuditReceipt(f: OrdinaryComplaintAuditFixture) {
    assertEquals(
        1,
        f.jdbc.update(
            "UPDATE complaint_idempotency_receipts SET state = 'COMPLETED', outcome = 'APPLIED', response_status = 200, " +
                "ack_ids = ARRAY[?]::uuid[], ack_versions = ARRAY[2]::bigint[], response_etag = ?, completed_at = ?, expires_at = ? " +
                "WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ? AND state = 'IN_PROGRESS'",
            f.resourceId,
            "\"complaint-${f.resourceId}-v2\"",
            Timestamp.from(f.at),
            Timestamp.from(f.at.plusSeconds(192 * 3_600L)),
            f.ordinary.userId,
            f.receiptId,
        ),
    )
}

internal fun deleteComplaintAuditRows(f: OrdinaryComplaintAuditFixture) {
    f.observer.update("DELETE FROM audit_log WHERE actor_user_id = ?", f.ordinary.userId)
    f.observer.update("DELETE FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ?", f.ordinary.userId)
    f.observer.update("DELETE FROM complaints WHERE id = ? AND data_scope_id = ?", f.resourceId, f.scope.id)
    f.observer.update("DELETE FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ?", f.resourceId, f.scope.id)
    f.observer.update("DELETE FROM app_installations WHERE id = ? AND data_scope_id = ?", f.installationId, f.scope.id)
    f.observer.update("DELETE FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ?", f.installationId, f.scope.id)
    // The existing ordinary owner removes this fixture's grants and user after every dependent row is gone.
}
