package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Single Admin P−U closure. The journal's installation owner is NOT the Admin actor. Historical
 * actor/grant observations are comparisons only: no role, expiry, ETag or grant is reauthorized. */
internal object TestOrdinaryDrainAdminPersistenceV1 {
    fun requirePrimary(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, run: TestOrdinaryDrainRowsV1.Run,
        value: TestOrdinaryDrainPersistenceV1.AdminPrimary, converted: Boolean, staged: Boolean, lockDomain: Boolean): Long {
        val p = value.publication
        val event = value.event
        original.requireInventoryEvent(event)
        requireDrain(original.routing.journalConfiguration.registeredAdminDelete && p.kind.name == "ADMIN_DELETE")
        val comparison = event.adminTuple
        val target = event.complaintIds().single()
        val tuple = ComplaintAdminDeleteTuple(comparison.actorId, comparison.scope, comparison.operationKey, target, comparison.fingerprintBytes())
        val receipt = value.receipt
        val recovery = value.recovery
        val now = TestOrdinaryDrainPersistenceV1.now(jdbc)
        requireDrain((run.progress != null || recovery.state == "PARTIAL") && p.state == "APPLIED" && p.targetCount == 1 &&
            p.scope == original.scope && p.writer.toString() == original.writer && p.createdAt <= run.sealedAt &&
            receipt.valid && receipt.matches(tuple) && receipt.state == "COMPLETED" && receipt.completed() is ComplaintAdminDeleteReceipt.Applied &&
            receipt.consumedGrantId == comparison.consumedGrantId && receipt.authorizedAt == p.createdAt && receipt.publication == p.eventId &&
            receipt.externalEvent == p.eventId && receipt.externalEpoch == p.epoch && receipt.externalVersion == p.objectVersion &&
            receipt.externalHash.contentEquals(p.ciphertextHash))
        val proof = TestOwnerDeleteVerificationCodecV1.forAdmin(original.routing).parse(checkNotNull(p.verificationBytes), event)
        ComplaintAdminDeleteVerificationOperation.requireColumns(proof, p)
        val verifiedAt = Instant.parse(proof.verifiedAt)
        requireDrain(p.createdAt <= verifiedAt && verifiedAt <= value.appliedAt && value.appliedAt <= value.receiptAt && value.receiptAt <= now &&
            verifiedAt <= recovery.lastAppliedAt && recovery.lastAppliedAt <= now && Instant.parse(proof.retainUntil).isAfter(now) &&
            (!converted || recovery.state == "CONVERTED"))
        val family = TestOrdinaryDrainPersistenceV1.requireFamily(jdbc, original, value, verifiedAt, staged)
        val suffix = if (lockDomain) " FOR UPDATE" else ""
        val ownerState = jdbc.query(TestOrdinaryDrainSqlV1.ownerIdentity + suffix, { row, _ ->
            requireScope(row, original)
            val state = checkNotNull(row.getString("state"))
            requireDrain(state in setOf("ACTIVE", "RECOVERY_RESERVED", "DELETED"))
            if (recovery.used[ComplaintCapacityCounter.INSTALLATION_IDS] != 0L) requireDrain(state in setOf("RECOVERY_RESERVED", "DELETED") &&
                row.getTimestamp("created_at").toInstant() in p.createdAt..recovery.lastAppliedAt)
            state
        }, comparison.ownerInstallationId).single()
        if (ownerState == "DELETED") {
            // A terminal bit alone is not evidence that a later ALL legitimately removed this
            // owner's credentials. Read the full genuine completed companion under the controls.
            requireDrain(TestOrdinaryDrainPersistenceV1.requireCompletedAllCompanion(jdbc, original, run, comparison.ownerInstallationId))
        } else {
            val credentials = jdbc.query(credential + suffix, { row, _ ->
                requireDrain(row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
                    row.getString("state") == "ACTIVE" && row.getLong("credential_version") > 0)
                row.getObject("id", UUID::class.java)
            }, comparison.ownerInstallationId)
            requireDrain(if (ownerState == "ACTIVE") credentials == listOf(comparison.ownerInstallationId) else credentials.isEmpty())
            if (ownerState == "RECOVERY_RESERVED") requireDrain(jdbc.queryForObject(
                "SELECT NOT EXISTS (SELECT 1 FROM complaints WHERE owner_id = ?)", Boolean::class.java, comparison.ownerInstallationId) == true)
        }
        jdbc.query(TestOrdinaryDrainSqlV1.resourceIdentity + suffix, { row, _ ->
            requireScope(row, original)
            requireDrain(row.getString("state") == "DELETED" && checkNotNull(row.getTimestamp("deleted_at")).toInstant() <= recovery.lastAppliedAt)
            if (recovery.used[ComplaintCapacityCounter.RESOURCE_IDS] != 0L) requireDrain(row.getTimestamp("created_at").toInstant() in p.createdAt..recovery.lastAppliedAt)
        }, target).single()
        requireDrain(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM complaints WHERE id = ?)", Boolean::class.java, target) == true)

        // An expired or collected grant need not be present. If retained it must agree with the
        // immutable consumed scalar; this is not a fresh/current-grant permission test or lock.
        jdbc.query(grantHistory, { row, _ -> requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid")) },
            comparison.actorId, p.createdAt.let(java.sql.Timestamp::from), comparison.consumedGrantId)
        val userExists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean::class.java, comparison.actorId) == true
        val audits = jdbc.query(targetAudits, { row, _ ->
            requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid"))
            val action = checkNotNull(row.getString("action"))
            val actor = row.getObject("actor_user_id", UUID::class.java)
            val actorKind = row.getString("complaint_actor_kind")
            val at = checkNotNull(row.getTimestamp("created_at")).toInstant()
            when (action) {
                "COMPLAINT_DELETE_AUTHORIZED" -> requireDrain(actorKind == "ADMIN" && actor == comparison.actorId && at == p.createdAt)
                "COMPLAINT_DELETED" -> requireDrain(at in verifiedAt..recovery.lastAppliedAt &&
                    (actorKind == "ADMIN" && actor == comparison.actorId || actorKind == "SYSTEM" && actor == null && !userExists))
                "COMPLAINT_RECOVERY_APPLIED" -> requireDrain(actorKind == "SYSTEM" && actor == null && at in verifiedAt..recovery.lastAppliedAt)
                else -> throw TestOrdinaryDrainExceptionV1()
            }
            Audit(action, at, row.getLong("version").let { if (row.wasNull()) null else it })
        }, original.scope, target.toString())
        val authorized = audits.filter { it.action == "COMPLAINT_DELETE_AUTHORIZED" }
        val removed = audits.filter { it.action == "COMPLAINT_DELETED" }
        val summaries = audits.filter { it.action == "COMPLAINT_RECOVERY_APPLIED" }
        requireDrain(authorized.size <= 1 && removed.size <= 1 && summaries.size <= family.size && audits.size <= 6 &&
            summaries.map { it.at }.distinct().size == summaries.size &&
            recovery.used[ComplaintCapacityCounter.AUDIT_ROWS] == removed.size.toLong() + summaries.size)
        // Normal primary spends one removal audit and no recovery summary. A genuinely retained
        // recovery primary may have no authorization audit in that snapshot, but must carry its
        // paid primary-recovery summary; absence alone never waives the audit/U equation.
        val primarySummaries = summaries.filter { it.at <= value.appliedAt }
        requireDrain(primarySummaries.size <= 1 && (primarySummaries.isNotEmpty() || authorized.size == 1 && removed.size == 1) &&
            summaries.size == family.size - 1 + primarySummaries.size)
        if (authorized.isNotEmpty() && removed.isNotEmpty()) requireDrain(authorized.single().version == removed.single().version)
        val aliases = family.filterNot { it.key == p.objectKey && it.version == p.objectVersion }.sortedBy { it.at }
        val aliasSummaries = summaries.filter { it.at > value.appliedAt }.sortedBy { it.at }
        requireDrain(aliases.size == aliasSummaries.size)
        aliases.zip(aliasSummaries).forEachIndexed { index, (applied, summary) ->
            val previous = if (index == 0) value.appliedAt else checkNotNull(aliases[index - 1].at)
            requireDrain(summary.at > previous && summary.at <= checkNotNull(applied.at))
        }
        original.throwIfSignalled(); original.budget.remainingMillis(1)
        return family.size.toLong()
    }

    private data class Audit(val action: String, val at: Instant, val version: Long?)
    private fun requireScope(row: ResultSet, original: TestRunOrdinaryDrainV1) {
        requireDrain(row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
            TestOrdinaryDrainRowsV1.boolean(row, "finite"))
    }
    private const val credential = "SELECT id, data_scope_id, test_only, state, credential_version FROM app_installations WHERE id = ?"
    private val grantHistory = """
        SELECT (user_id = ?::uuid AND scope = 'complaint-moderation-mutation' AND used_at IS NOT NULL
            AND isfinite(used_at) AND used_at <= ?::timestamptz AND used_at < expires_at) IS TRUE AS valid
        FROM admin_step_up_grants WHERE id = ?::uuid
    """.trimIndent()
    private val targetAudits = """
        SELECT action, actor_user_id, complaint_actor_kind, created_at,
            CASE WHEN action <> 'COMPLAINT_RECOVERY_APPLIED' THEN (detail->>'version')::bigint END AS version,
            (complaint_data_scope_id = ?::uuid AND entity_type = 'complaint' AND isfinite(created_at) AND
                ((action IN ('COMPLAINT_DELETE_AUTHORIZED', 'COMPLAINT_DELETED') AND jsonb_typeof(detail->'version') = 'number'
                    AND detail = jsonb_build_object('version', (detail->>'version')::bigint) AND (detail->>'version')::bigint > 0)
                OR (action = 'COMPLAINT_RECOVERY_APPLIED' AND detail = '{}'::jsonb))) IS TRUE AS valid
        FROM audit_log WHERE entity_type = 'complaint' AND entity_id = ?::text
            AND action IN ('COMPLAINT_DELETE_AUTHORIZED', 'COMPLAINT_DELETED', 'COMPLAINT_RECOVERY_APPLIED') ORDER BY id LIMIT 7
    """.trimIndent()
}
