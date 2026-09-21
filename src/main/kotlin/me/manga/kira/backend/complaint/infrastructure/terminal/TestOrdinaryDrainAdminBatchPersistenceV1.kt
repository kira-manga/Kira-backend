package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.infrastructure.AdminDeleteRows
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Phase-free, closed batch P/U comparison. Not an issuer, repair path or another deletion executor. */
internal object TestOrdinaryDrainAdminBatchPersistenceV1 {
    fun requirePrimaryFacts(jdbc: JdbcTemplate, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts, run: TestOrdinaryDrainRowsV1.Run,
        value: TestOrdinaryDrainPersistenceV1.AdminPrimary, family: List<TestOrdinaryDrainPersistenceV1.Applied>, converted: Boolean, lockDomain: Boolean) {
        val p = value.publication
        val event = value.event
        facts.requireEvent(event)
        requireDrain(facts.routing.journalConfiguration.registeredAdminBatchDelete && p.kind.name == "ADMIN_BATCH_DELETE")
        val comparison = event.adminBatchTuple
        val targets = event.complaintIds()
        val owners = comparison.ownerInstallationIds()
        val receipt = value.receipt
        val recovery = value.recovery
        val now = TestOrdinaryDrainPersistenceV1.now(jdbc)
        requireDrain((run.progress != null || recovery.state == "PARTIAL") && p.state == "APPLIED" && p.targetCount == targets.size &&
            p.scope == facts.scope && p.writer.toString() == facts.writer && p.createdAt <= run.sealedAt &&
            receipt.valid && receipt.matches(AdminDeleteRows.tuple(event)) && receipt.state == "COMPLETED" &&
            receipt.consumedGrantId == comparison.consumedGrantId && receipt.authorizedAt == p.createdAt && receipt.publication == p.eventId &&
            receipt.externalEvent == p.eventId && receipt.externalEpoch == p.epoch && receipt.externalVersion == p.objectVersion &&
            receipt.externalHash.contentEquals(p.ciphertextHash) && recovery.promise == AdminDeleteRows.recovery(event))
        AdminDeleteRows.requireApplied(receipt.completed(), event) // Exact complete sorted ID-only ACK, never single 204.
        val proof = TestOwnerDeleteVerificationCodecV1.forAdminBatch(facts.routing).parse(checkNotNull(p.verificationBytes), event)
        ComplaintAdminDeleteVerificationOperation.requireColumns(proof, p)
        val verifiedAt = Instant.parse(proof.verifiedAt)
        requireDrain(p.createdAt <= verifiedAt && verifiedAt <= value.appliedAt && value.appliedAt <= value.receiptAt && value.receiptAt <= now &&
            verifiedAt <= recovery.lastAppliedAt && recovery.lastAppliedAt <= now && Instant.parse(proof.retainUntil).isAfter(now) &&
            (!converted || recovery.state == "CONVERTED"))

        val suffix = if (lockDomain) " FOR UPDATE" else ""
        // All owner pairs precede all resources. A terminal owner must have a genuine, separately
        // checked ALL history; no terminal state or opaque manifest disposition stands in for it.
        var reconstructedOwners = 0
        owners.forEach { owner ->
            val state = jdbc.query(TestOrdinaryDrainSqlV1.ownerIdentity + suffix, { row, _ ->
                requireScope(row, facts)
                val state = checkNotNull(row.getString("state"))
                requireDrain(state in setOf("ACTIVE", "RECOVERY_RESERVED", "DELETED"))
                if (row.getTimestamp("created_at").toInstant() in p.createdAt..recovery.lastAppliedAt) {
                    requireDrain(state in setOf("RECOVERY_RESERVED", "DELETED")); reconstructedOwners++
                }
                state
            }, owner).single()
            val credentials = jdbc.query(credential + suffix, { row, _ ->
                requireDrain(row.getObject("data_scope_id", UUID::class.java) == facts.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
                    row.getString("state") == (if (state == "DELETED") "DELETED" else "ACTIVE") && row.getLong("credential_version") > 0)
                row.getObject("id", UUID::class.java)
            }, owner)
            requireDrain(when (state) { "ACTIVE" -> credentials == listOf(owner); "DELETED" -> credentials.size <= 1; else -> credentials.isEmpty() })
            if (state == "DELETED") requireDrain(TestOrdinaryDrainPersistenceV1.requireCompletedAllCompanionFacts(jdbc, facts, run, owner))
            if (state == "RECOVERY_RESERVED") requireDrain(jdbc.queryForObject(
                "SELECT NOT EXISTS (SELECT 1 FROM complaints WHERE owner_id = ?)", Boolean::class.java, owner) == true)
        }
        var reconstructedResources = 0
        targets.forEach { target ->
            jdbc.query(TestOrdinaryDrainSqlV1.resourceIdentity + suffix, { row, _ ->
                requireScope(row, facts)
                requireDrain(row.getString("state") == "DELETED" && row.getTimestamp("deleted_at").toInstant() <= recovery.lastAppliedAt)
                if (row.getTimestamp("created_at").toInstant() in p.createdAt..recovery.lastAppliedAt) reconstructedResources++
            }, target).single()
        }
        requireDrain(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM complaints WHERE id = ANY (?::uuid[]))", Boolean::class.java,
            AdminDeleteRows.array(targets)) == true)
        // Timestamps are supporting domain evidence, not attribution: another authenticated
        // event can have reconstructed a shared owner after this primary's AUTH. The event-scoped
        // summary below supplies exact paid counts; never charge every such owner to this batch.
        requireDrain(reconstructedOwners.toLong() >= recovery.used[ComplaintCapacityCounter.INSTALLATION_IDS] &&
            reconstructedResources.toLong() >= recovery.used[ComplaintCapacityCounter.RESOURCE_IDS])

        // Optional retained grant is historical association only; no renewed role, expiry or tag check.
        jdbc.query(grantHistory, { row, _ -> requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid")) },
            comparison.actorId, Timestamp.from(p.createdAt), comparison.consumedGrantId)
        val userExists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE id = ?)", Boolean::class.java, comparison.actorId) == true
        val audits = jdbc.query(targetAudits, { row, _ ->
            requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid"))
            val action = checkNotNull(row.getString("action"))
            val actor = row.getObject("actor_user_id", UUID::class.java)
            val actorKind = row.getString("complaint_actor_kind")
            val at = row.getTimestamp("created_at").toInstant()
            when (action) {
                "COMPLAINT_DELETE_AUTHORIZED" -> requireDrain(actorKind == "ADMIN" && actor == comparison.actorId && at == p.createdAt)
                "COMPLAINT_DELETED" -> requireDrain(at in verifiedAt..value.appliedAt &&
                    (actorKind == "ADMIN" && actor == comparison.actorId || actorKind == "SYSTEM" && actor == null && !userExists))
                else -> throw TestOrdinaryDrainExceptionV1() // Batch never writes a per-target recovery summary.
            }
            TargetAudit(UUID.fromString(row.getString("entity_id")), action, row.getLong("version"))
        }, facts.scope, targets.joinToString(",", "{", "}"))
        val authorized = audits.filter { it.action == "COMPLAINT_DELETE_AUTHORIZED" }
        val removed = audits.filter { it.action == "COMPLAINT_DELETED" }
        requireDrain(audits.size <= 2 * targets.size && authorized.size in setOf(0, targets.size) && removed.size <= targets.size &&
            authorized.map { it.target }.distinct().size == authorized.size && removed.map { it.target }.distinct().size == removed.size)
        authorized.forEach { auth -> removed.singleOrNull { it.target == auth.target }?.let { requireDrain(it.version == auth.version) } }

        val routes = facts.routing.derive(comparison).candidates()
        val summaries = jdbc.query(summaryAudits, { row, _ ->
            requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid"))
            val at = row.getTimestamp("created_at").toInstant()
            val result = Summary(row.getString("event_id"), at, row.getInt("removed"), row.getInt("reconstructed"), row.getInt("installation"))
            requireDrain(at in verifiedAt..recovery.lastAppliedAt && result.removed in 0..targets.size &&
                result.resources in 0..targets.size && result.owners in 0..owners.size && result.removed + result.resources <= targets.size)
            result
        }, facts.scope, facts.scope.toString(), routes.joinToString(",", "{", "}") { it.eventId })
        requireDrain(summaries.size <= 4 && summaries.map { it.at }.distinct().size == summaries.size &&
            recovery.used[ComplaintCapacityCounter.AUDIT_ROWS] == removed.size.toLong() + summaries.size)
        val primarySummary = summaries.filter { it.at <= value.appliedAt }
        requireDrain(primarySummary.size <= 1 && summaries.size == family.size - 1 + primarySummary.size)
        if (primarySummary.isEmpty()) {
            requireDrain(authorized.size == targets.size && removed.size == targets.size &&
                recovery.used[ComplaintCapacityCounter.INSTALLATION_IDS] == 0L && recovery.used[ComplaintCapacityCounter.RESOURCE_IDS] == 0L)
        } else {
            val summary = primarySummary.single()
            requireDrain(summary.eventId == p.eventId && summary.removed == removed.size && summary.resources.toLong() == recovery.used[ComplaintCapacityCounter.RESOURCE_IDS] &&
                summary.owners.toLong() == recovery.used[ComplaintCapacityCounter.INSTALLATION_IDS])
        }
        val aliases = family.filterNot { it.key == p.objectKey && it.version == p.objectVersion }.sortedBy { it.at }
        val aliasSummaries = summaries.filter { it.at > value.appliedAt }.sortedBy { it.at }
        requireDrain(aliases.size == aliasSummaries.size)
        aliases.zip(aliasSummaries).forEachIndexed { index, (applied, summary) ->
            val previous = if (index == 0) value.appliedAt else checkNotNull(aliases[index - 1].at)
            requireDrain(summary.eventId == applied.eventId && summary.at > previous && summary.at <= checkNotNull(applied.at) &&
                summary.removed == 0 && summary.resources == 0 && summary.owners == 0)
        }
    }

    private data class TargetAudit(val target: UUID, val action: String, val version: Long)
    private data class Summary(val eventId: String, val at: Instant, val removed: Int, val resources: Int, val owners: Int)
    private fun requireScope(row: ResultSet, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts) {
        requireDrain(row.getObject("data_scope_id", UUID::class.java) == facts.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
            TestOrdinaryDrainRowsV1.boolean(row, "finite"))
    }
    private const val credential = "SELECT id, data_scope_id, test_only, state, credential_version FROM app_installations WHERE id = ?"
    private val grantHistory = """
        SELECT (user_id = ?::uuid AND scope = 'complaint-moderation-mutation' AND used_at IS NOT NULL
            AND isfinite(used_at) AND used_at <= ?::timestamptz AND used_at < expires_at) IS TRUE AS valid
        FROM admin_step_up_grants WHERE id = ?::uuid
    """.trimIndent()
    private val targetAudits = """
        SELECT entity_id, action, actor_user_id, complaint_actor_kind, created_at, (detail->>'version')::bigint AS version,
            (complaint_data_scope_id = ?::uuid AND isfinite(created_at) AND
                action IN ('COMPLAINT_DELETE_AUTHORIZED', 'COMPLAINT_DELETED') AND jsonb_typeof(detail->'version') = 'number'
                AND detail = jsonb_build_object('version', (detail->>'version')::bigint) AND (detail->>'version')::bigint > 0) IS TRUE AS valid
        FROM audit_log WHERE entity_type = 'complaint' AND entity_id = ANY (?::text[])
            AND action IN ('COMPLAINT_DELETE_AUTHORIZED', 'COMPLAINT_DELETED', 'COMPLAINT_RECOVERY_APPLIED') ORDER BY entity_id, id LIMIT 101
    """.trimIndent()
    private val summaryAudits = """
        SELECT created_at, detail->>'eventId' AS event_id, (detail->>'removed')::int AS removed,
            (detail->>'reconstructed')::int AS reconstructed, (detail->>'installation')::int AS installation,
            (complaint_data_scope_id = ?::uuid AND entity_type = 'complaint_scope' AND entity_id = ?::text
                AND complaint_actor_kind = 'SYSTEM' AND actor_user_id IS NULL AND isfinite(created_at)
                AND jsonb_typeof(detail->'eventId') = 'string' AND jsonb_typeof(detail->'removed') = 'number'
                AND jsonb_typeof(detail->'reconstructed') = 'number' AND jsonb_typeof(detail->'installation') = 'number'
                AND detail = jsonb_build_object('eventId', detail->>'eventId', 'removed', (detail->>'removed')::int,
                    'reconstructed', (detail->>'reconstructed')::int, 'installation', (detail->>'installation')::int)) IS TRUE AS valid
        FROM audit_log WHERE action = 'COMPLAINT_RECOVERY_APPLIED' AND detail->>'eventId' = ANY (?::text[]) ORDER BY id LIMIT 5
    """.trimIndent()
}
