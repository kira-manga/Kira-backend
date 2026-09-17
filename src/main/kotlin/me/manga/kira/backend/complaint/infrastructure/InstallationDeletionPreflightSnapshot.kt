package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightRejection
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** One bounded MVCC observation, not catalog/restore, canonical-event or provider authentication. */
internal class InstallationDeletionPreflightSnapshot private constructor(
    private val identity: Identity?,
    private val credential: Credential?,
    private val receipt: Receipt?,
    private val run: ComplaintInstallationRunObservation?,
    private val observedAt: Instant,
) {
    fun compare(candidate: InstallationDeletionCandidate): Comparison {
        requireCoherentPair()
        if (credential != null && !MessageDigest.isEqual(candidate.credential.verifierBytes(), credential.verifier)) {
            return rejected(InstallationDeletionPreflightRejection.INSTALLATION_CREDENTIAL_REJECTED)
        }
        if (identity != null && identity.scope != candidate.installation.scope) {
            return rejected(InstallationDeletionPreflightRejection.INSTALLATION_SCOPE_MISMATCH)
        }
        // An observed absent run is terminal for this requested TEST scope, never a LIVE fallback.
        if (run is ComplaintInstallationRunObservation.Absent) return rejected(InstallationDeletionPreflightRejection.INSTALLATION_SCOPE_RETIRED)
        if (identity == null) return rejected(if (terminalRun()) SCOPE_RETIRED else InstallationDeletionPreflightRejection.INSTALLATION_NOT_FOUND)
        if (credential == null) return rejected(terminalReason())
        return compareCredential(candidate, credential)
    }

    private fun compareCredential(candidate: InstallationDeletionCandidate, stored: Credential): Comparison {
        if (stored.state === InstallationCredentialState.DELETED && !observedAt.isBefore(checkNotNull(stored.expiresAt))) {
            return rejected(InstallationDeletionPreflightRejection.INSTALLATION_DELETED)
        }
        // ACTIVE/PENDING keep the submitted version. A retained DELETED credential advanced exactly once.
        val expectedVersion = receipt?.submittedVersion ?: if (stored.state === InstallationCredentialState.DELETED) {
            Math.subtractExact(stored.version, 1L)
        } else {
            stored.version
        }
        if (candidate.credentialVersion != expectedVersion) return rejected(InstallationDeletionPreflightRejection.INSTALLATION_CREDENTIAL_REJECTED)
        val fingerprint = ComplaintDeleteAllFingerprint.of(candidate)
        val retained = receipt
        if (retained != null) {
            if (retained.key != candidate.operationKey || !MessageDigest.isEqual(retained.fingerprint, fingerprint.bytes())) {
                return rejected(InstallationDeletionPreflightRejection.IDEMPOTENCY_KEY_REUSED)
            }
            // Exact committed work is examined before the new-work ACTIVE-run gate. This is only
            // a reference for the later fixed writer, which still owns the terminal TEST protocol.
            return when (retained.state) {
                ReceiptState.AUTHORIZED_DELETE -> Comparison.Authorized(fingerprint, retained.publicationReference)

                ReceiptState.COMPLETED -> if (observedAt.isBefore(checkNotNull(retained.expiresAt))) {
                    Comparison.Completed(fingerprint, retained.publicationReference, checkNotNull(retained.replay))
                } else {
                    rejected(InstallationDeletionPreflightRejection.INSTALLATION_DELETED)
                }
            }
        }
        return when (stored.state) {
            InstallationCredentialState.ACTIVE -> if (terminalRun()) rejected(SCOPE_RETIRED) else Comparison.Active(fingerprint)

            InstallationCredentialState.DELETION_PENDING -> rejected(InstallationDeletionPreflightRejection.INSTALLATION_DELETION_PENDING)

            // A still-live verifier without its exact retained receipt cannot invent a replay.
            InstallationCredentialState.DELETED -> rejected(InstallationDeletionPreflightRejection.IDEMPOTENCY_KEY_REUSED)
        }
    }

    private fun terminalRun(): Boolean = run is ComplaintInstallationRunObservation.Present && run.state !== ComplaintInstallationRunState.ACTIVE

    private fun terminalReason(): InstallationDeletionPreflightRejection = when {
        terminalRun() -> SCOPE_RETIRED
        identity?.state === InstallationIdentityState.DELETED -> InstallationDeletionPreflightRejection.INSTALLATION_DELETED
        else -> InstallationDeletionPreflightRejection.INSTALLATION_RETIRED // Includes the non-authenticated RECOVERY_RESERVED denial, V6 §7.1.
    }

    private fun requireCoherentPair() {
        if (identity == null) {
            check(credential == null && receipt == null)
        } else if (credential == null) {
            check(identity.state in setOf(InstallationIdentityState.RETIRED, InstallationIdentityState.RECOVERY_RESERVED, InstallationIdentityState.DELETED))
        } else {
            check(identity.scope == credential.scope)
            check(identity.state.name == credential.state.name)
        }
        receipt?.let { retained ->
            check(identity != null && identity.scope == retained.scope)
            when (retained.state) {
                ReceiptState.AUTHORIZED_DELETE -> {
                    check(identity.state === InstallationIdentityState.DELETION_PENDING && credential != null)
                    check(credential.version == retained.submittedVersion)
                }

                ReceiptState.COMPLETED -> {
                    check(identity.state === InstallationIdentityState.DELETED)
                    if (credential != null) check(credential.version == Math.addExact(retained.submittedVersion, 1L))
                }
            }
        }
        // PURGED must not retain a credential or receipt; a terminal label cannot hide this residue.
        if (run is ComplaintInstallationRunObservation.Present && run.state === ComplaintInstallationRunState.PURGED && identity?.scope == run.scope) {
            check(credential == null && receipt == null)
        }
    }

    internal sealed interface Comparison {
        class Rejected(val reason: InstallationDeletionPreflightRejection) : Comparison
        class Active(val fingerprint: ComplaintDeleteAllFingerprint) : Comparison
        class Authorized(val fingerprint: ComplaintDeleteAllFingerprint, val publicationReference: String) : Comparison
        class Completed(
            val fingerprint: ComplaintDeleteAllFingerprint,
            val publicationReference: String,
            val replay: InstallationDeletionCompletedReplaySnapshot,
        ) : Comparison
    }

    private class Identity(val scope: ComplaintDataScope, val state: InstallationIdentityState)

    private class Credential(
        val scope: ComplaintDataScope,
        val state: InstallationCredentialState,
        val verifier: ByteArray,
        val version: Long,
        val expiresAt: Instant?,
    )

    private class Receipt(
        val scope: ComplaintDataScope,
        val key: UUID,
        val submittedVersion: Long,
        val fingerprint: ByteArray,
        val state: ReceiptState,
        val publicationReference: String,
        val expiresAt: Instant?,
        val replay: InstallationDeletionCompletedReplaySnapshot?,
    )

    private enum class ReceiptState { AUTHORIZED_DELETE, COMPLETED }

    companion object {
        private val SCOPE_RETIRED = InstallationDeletionPreflightRejection.INSTALLATION_SCOPE_RETIRED

        fun read(row: ResultSet, requested: ScopedInstallationId): InstallationDeletionPreflightSnapshot {
            check(requiredBoolean(row, "finite_observed_at"))
            val run = ComplaintInstallationTestRunRows.read(row, requested.scope)
            if (run is ComplaintInstallationRunObservation.Present) check(requiredBoolean(row, "run_terminal_shape"))
            return InstallationDeletionPreflightSnapshot(
                readIdentity(row, requested.id),
                readCredential(row, requested.id),
                readReceipt(row, requested.id),
                run,
                checkNotNull(row.getTimestamp("observed_at")).toInstant(),
            )
        }

        private fun readIdentity(row: ResultSet, expected: UUID): Identity? {
            val id = row.getObject("identity_id", UUID::class.java) ?: return null
            check(id == expected && requiredBoolean(row, "identity_valid"))
            return Identity(scope(row, "identity_scope"), InstallationIdentityState.valueOf(checkNotNull(row.getString("identity_state"))))
        }

        private fun readCredential(row: ResultSet, expected: UUID): Credential? {
            val id = row.getObject("credential_id", UUID::class.java) ?: return null
            check(id == expected && requiredBoolean(row, "credential_valid"))
            return Credential(
                scope(row, "credential_scope"),
                InstallationCredentialState.valueOf(checkNotNull(row.getString("credential_state"))),
                checkNotNull(row.getBytes("secret_verifier")),
                requiredLong(row, "credential_version"),
                row.getTimestamp("verifier_expires_at")?.toInstant(),
            )
        }

        private fun readReceipt(row: ResultSet, expected: UUID): Receipt? {
            val id = row.getObject("receipt_installation", UUID::class.java) ?: return null
            check(id == expected && requiredBoolean(row, "receipt_valid"))
            val state = ReceiptState.valueOf(checkNotNull(row.getString("receipt_state"))) // Visible IN_PROGRESS is corruption, not permission to start again.
            val scope = scope(row, "receipt_scope")
            val publication = checkNotNull(row.getString("publication_ref"))
            requirePublication(row, state, scope, publication)
            return Receipt(
                scope,
                checkNotNull(row.getObject("deletion_key", UUID::class.java)),
                requiredLong(row, "submitted_credential_version"),
                checkNotNull(row.getBytes("fingerprint")),
                state,
                publication,
                row.getTimestamp("receipt_expires_at")?.toInstant(),
                if (state === ReceiptState.COMPLETED) InstallationDeletionCompletedReplaySnapshot.read(row) else null,
            )
        }

        private fun requirePublication(row: ResultSet, state: ReceiptState, expectedScope: ComplaintDataScope, reference: String) {
            check(row.getString("publication_id") == reference && requiredBoolean(row, "publication_valid"))
            check(scope(row, "publication_scope") == expectedScope)
            if (state === ReceiptState.AUTHORIZED_DELETE) {
                check(row.getString("publication_state") in setOf("PREPARED", "VERIFIED"))
                check(row.getString("applied_event_id") == null)
            } else {
                check(row.getString("publication_state") == "APPLIED")
                check(row.getString("external_event_id") == reference && row.getString("applied_event_id") == reference)
                check(requiredBoolean(row, "applied_valid") && scope(row, "applied_scope") == expectedScope)
                check(row.getString("applied_object_key") == row.getString("publication_object_key"))
                check(row.getObject("applied_writer", UUID::class.java) == row.getObject("publication_writer", UUID::class.java))
                check(requiredLong(row, "external_epoch") == requiredLong(row, "publication_epoch"))
                check(requiredLong(row, "applied_epoch") == requiredLong(row, "publication_epoch"))
                check(requiredLong(row, "applied_target_count") == requiredLong(row, "publication_target_count"))
                check(row.getString("external_object_version") == row.getString("publication_object_version"))
                check(row.getString("applied_object_version") == row.getString("publication_object_version"))
                check(MessageDigest.isEqual(row.getBytes("external_ciphertext_hash"), row.getBytes("publication_ciphertext_hash")))
                check(MessageDigest.isEqual(row.getBytes("applied_ciphertext_hash"), row.getBytes("publication_ciphertext_hash")))
            }
        }

        private fun rejected(reason: InstallationDeletionPreflightRejection): Comparison = Comparison.Rejected(reason)
        private fun scope(row: ResultSet, column: String): ComplaintDataScope = ComplaintDataScope.of(checkNotNull(row.getObject(column, UUID::class.java)))
        private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { check(!row.wasNull()) }
        private fun requiredLong(row: ResultSet, column: String): Long = row.getLong(column).also { check(!row.wasNull()) }

        /**
         * One statement snapshot includes both absences, DB time and all comparison links. Indexed
         * per-installation/event lookups stop at two; two results are corruption, never an arbitrary
         * winning receipt/version. No row/advisory locks, descriptor transport or content scan.
         */
        val SQL = """
            WITH deletion_time AS MATERIALIZED (SELECT clock_timestamp() AS observed_at)
            SELECT t.observed_at, isfinite(t.observed_at) AS finite_observed_at,
                i.id AS identity_id, i.data_scope_id AS identity_scope, i.state AS identity_state, i.terminal_at AS identity_terminal_at,
                (complaint_is_v4(i.id) AND complaint_scope_valid(i.data_scope_id, i.test_only) AND i.created_at IS NOT NULL
                    AND ((i.state IN ('ACTIVE','DELETION_PENDING','RECOVERY_RESERVED') AND i.terminal_at IS NULL)
                        OR (i.state IN ('RETIRED','DELETED') AND i.terminal_at IS NOT NULL))
                    AND complaint_finite_times(i.created_at, i.terminal_at)) IS TRUE AS identity_valid,
                c.id AS credential_id, c.data_scope_id AS credential_scope, c.state AS credential_state,
                CASE WHEN octet_length(c.secret_verifier) = 32 THEN c.secret_verifier END AS secret_verifier,
                c.credential_version, c.verifier_expires_at, c.deleted_at AS credential_deleted_at,
                (complaint_scope_valid(c.data_scope_id, c.test_only) AND c.credential_version > 0 AND c.version > 0
                    AND complaint_digest_valid(c.secret_verifier) AND c.created_at IS NOT NULL
                    AND ((c.state IN ('ACTIVE','DELETION_PENDING') AND c.platform IN ('ANDROID','IOS')
                            AND complaint_is_v4(c.owner_reference) AND c.owner_reference <> c.id
                            AND c.last_authenticated_at IS NOT NULL AND c.deleted_at IS NULL AND c.verifier_expires_at IS NULL)
                        OR (c.state = 'DELETED' AND c.platform IS NULL AND c.owner_reference IS NULL AND c.last_authenticated_at IS NULL
                            AND c.deleted_at IS NOT NULL AND c.verifier_expires_at = c.deleted_at + interval '192 hours'))
                    AND complaint_finite_times(c.created_at, c.last_authenticated_at, c.deleted_at, c.verifier_expires_at)) IS TRUE AS credential_valid,
                d.installation_id AS receipt_installation, d.deletion_key, d.submitted_credential_version,
                CASE WHEN octet_length(d.fingerprint) = 32 THEN d.fingerprint END AS fingerprint,
                d.data_scope_id AS receipt_scope, d.state AS receipt_state, d.publication_ref,
                d.external_event_id, d.external_epoch,
                CASE WHEN complaint_opaque_valid(d.external_object_version, 1024) THEN d.external_object_version END AS external_object_version,
                CASE WHEN octet_length(d.external_ciphertext_hash) = 32 THEN d.external_ciphertext_hash END AS external_ciphertext_hash,
                d.authorized_at AS receipt_authorized_at, d.completed_at AS receipt_completed_at, d.expires_at AS receipt_expires_at,
                (complaint_is_v4(d.installation_id) AND complaint_is_v4(d.deletion_key) AND d.submitted_credential_version > 0
                    AND complaint_digest_valid(d.fingerprint) AND complaint_scope_valid(d.data_scope_id, d.test_only)
                    AND d.created_at IS NOT NULL AND d.authorized_at IS NOT NULL AND d.publication_ref IS NOT NULL
                    AND ((d.state = 'AUTHORIZED_DELETE' AND d.outcome IS NULL AND d.response_status IS NULL
                            AND d.external_event_id IS NULL AND d.external_epoch IS NULL AND d.external_object_version IS NULL
                            AND d.external_ciphertext_hash IS NULL AND d.completed_at IS NULL AND d.expires_at IS NULL)
                        OR (d.state = 'COMPLETED' AND d.outcome = 'APPLIED' AND d.response_status = 204
                            AND d.external_event_id = d.publication_ref AND complaint_event_id_valid(d.external_event_id) AND d.external_epoch > 0
                            AND complaint_opaque_valid(d.external_object_version, 1024) AND d.external_object_version <> 'null'
                            AND complaint_digest_valid(d.external_ciphertext_hash) AND d.completed_at IS NOT NULL
                            AND d.expires_at = d.completed_at + interval '192 hours'))
                    AND complaint_finite_times(d.created_at, d.authorized_at, d.completed_at, d.expires_at)) IS TRUE AS receipt_valid,
                p.event_id AS publication_id, p.data_scope_id AS publication_scope, p.state AS publication_state,
                p.writer_generation AS publication_writer, p.journal_epoch AS publication_epoch, p.target_count AS publication_target_count,
                CASE WHEN complaint_ascii_valid(p.routing_key_id, 128) THEN p.routing_key_id END AS publication_routing_key_id,
                CASE WHEN complaint_ascii_valid(p.object_key, 1024) THEN p.object_key END AS publication_object_key,
                CASE WHEN complaint_bytes_match(p.event_bytes, p.semantic_hash, 65536) THEN p.event_bytes END AS publication_event_bytes,
                CASE WHEN octet_length(p.semantic_hash) = 32 THEN p.semantic_hash END AS publication_semantic_hash,
                CASE WHEN complaint_opaque_valid(p.object_version, 1024) THEN p.object_version END AS publication_object_version,
                CASE WHEN octet_length(p.ciphertext_hash) = 32 THEN p.ciphertext_hash END AS publication_ciphertext_hash,
                CASE WHEN complaint_bytes_match(p.verification_bytes, p.verification_hash, 65536) THEN p.verification_bytes END AS publication_verification_bytes,
                CASE WHEN octet_length(p.verification_hash) = 32 THEN p.verification_hash END AS publication_verification_hash,
                p.created_at AS publication_created_at, p.object_created_at AS publication_object_created_at,
                p.retain_until AS publication_retain_until, p.verified_at AS publication_verified_at, p.applied_at AS publication_applied_at,
                (complaint_event_id_valid(p.event_id) AND complaint_scope_valid(p.data_scope_id, p.test_only)
                    AND complaint_is_v4(p.writer_generation) AND p.journal_epoch > 0 AND p.event_kind = 'OWNER_DELETE_ALL'
                    AND p.target_count BETWEEN 0 AND 100 AND complaint_ascii_valid(p.routing_key_id, 128)
                    AND complaint_ascii_valid(p.object_key, 1024) AND p.canonicalizer = 'kcj-1'
                    AND complaint_bytes_match(p.event_bytes, p.semantic_hash, 65536) AND p.created_at IS NOT NULL
                    AND ((p.state = 'PREPARED' AND p.object_version IS NULL AND p.ciphertext_hash IS NULL
                            AND p.object_created_at IS NULL AND p.retain_until IS NULL AND p.verified_at IS NULL
                            AND p.verification_bytes IS NULL AND p.verification_hash IS NULL AND p.applied_at IS NULL)
                        OR (p.state IN ('VERIFIED','APPLIED') AND complaint_opaque_valid(p.object_version, 1024) AND p.object_version <> 'null'
                            AND complaint_digest_valid(p.ciphertext_hash) AND p.object_created_at IS NOT NULL
                            AND p.retain_until IS NOT NULL AND p.verified_at IS NOT NULL
                            AND complaint_bytes_match(p.verification_bytes, p.verification_hash, 65536)
                            AND ((p.state = 'VERIFIED' AND p.applied_at IS NULL) OR (p.state = 'APPLIED' AND p.applied_at IS NOT NULL))))
                    AND complaint_finite_times(p.created_at, p.object_created_at, p.retain_until, p.verified_at, p.applied_at)) IS TRUE AS publication_valid,
                a.event_id AS applied_event_id, a.data_scope_id AS applied_scope, a.writer_generation AS applied_writer,
                a.journal_epoch AS applied_epoch, a.target_count AS applied_target_count,
                CASE WHEN complaint_opaque_valid(a.object_key, 1024) THEN a.object_key END AS applied_object_key,
                CASE WHEN complaint_opaque_valid(a.object_version, 1024) THEN a.object_version END AS applied_object_version,
                CASE WHEN octet_length(a.ciphertext_hash) = 32 THEN a.ciphertext_hash END AS applied_ciphertext_hash,
                a.applied_at,
                (complaint_event_id_valid(a.event_id) AND complaint_scope_valid(a.data_scope_id, a.test_only)
                    AND complaint_is_v4(a.writer_generation) AND a.journal_epoch > 0 AND a.event_kind = 'OWNER_DELETE_ALL'
                    AND a.target_count BETWEEN 0 AND 100 AND complaint_opaque_valid(a.object_key, 1024)
                    AND complaint_opaque_valid(a.object_version, 1024) AND a.object_version <> 'null'
                    AND complaint_digest_valid(a.ciphertext_hash) AND a.applied_at IS NOT NULL AND isfinite(a.applied_at)) IS TRUE AS applied_valid,
                ${ComplaintInstallationTestRunRows.columns},
                (r.state = 'ACTIVE' OR (r.state = 'SEALED' AND r.sealed_at IS NOT NULL AND r.purging_at IS NULL AND r.purged_at IS NULL)
                    OR (r.state IN ('PURGING','PURGED') AND r.sealed_at IS NOT NULL AND r.purging_at IS NOT NULL
                        AND r.final_ordinary_epoch > 0 AND r.terminal_seal_epoch > r.final_ordinary_epoch
                        AND r.event_manifest_count >= 0 AND r.installation_manifest_count = r.enrolled_count
                        AND complaint_bytes_match(r.permanent_denial_bytes, r.permanent_denial_hash, 65536)
                        AND complaint_event_id_valid(r.terminal_event_id) AND complaint_opaque_valid(r.terminal_object_key, 1024)
                        AND complaint_opaque_valid(r.terminal_object_version, 1024) AND r.terminal_object_version <> 'null'
                        AND complaint_digest_valid(r.terminal_ciphertext_hash) AND complaint_digest_valid(r.terminal_catalog_hash)
                        AND r.terminal_catalog_generation BETWEEN r.activation_catalog_generation + 1 AND 65536
                        AND ((r.state = 'PURGING' AND r.purged_at IS NULL)
                            OR (r.state = 'PURGED' AND r.purged_at IS NOT NULL AND r.unused_reserve = array_fill(0::bigint, ARRAY[22])))))
                    IS TRUE AS run_terminal_shape
            FROM (VALUES (?::uuid, ?::uuid)) AS requested(id, data_scope_id)
            CROSS JOIN deletion_time t
            LEFT JOIN complaint_installation_ids i ON i.id = requested.id
            LEFT JOIN app_installations c ON c.id = requested.id
            LEFT JOIN LATERAL (SELECT d.* FROM installation_deletion_receipts d WHERE d.installation_id = requested.id LIMIT 2) d ON true
            LEFT JOIN complaint_journal_publications p ON p.event_id = d.publication_ref
            LEFT JOIN LATERAL (SELECT a.* FROM complaint_deletion_journal_applied a WHERE a.event_id = p.event_id LIMIT 2) a ON true
            LEFT JOIN complaint_test_runs r ON r.data_scope_id = requested.data_scope_id
            LIMIT 2
        """.trimIndent()
    }
}
