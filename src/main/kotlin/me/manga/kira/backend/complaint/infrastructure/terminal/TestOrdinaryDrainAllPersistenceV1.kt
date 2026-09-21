package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyRows
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllInventorySqlV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** ALL-specific residual proof. Never coerces installation-deletion evidence into a report receipt. */
internal object TestOrdinaryDrainAllPersistenceV1 {
    fun requirePrimaryFacts(jdbc: JdbcTemplate, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts, run: TestOrdinaryDrainRowsV1.Run,
        value: TestOrdinaryDrainPersistenceV1.AllPrimary, family: List<TestOrdinaryDrainPersistenceV1.Applied>, converted: Boolean, lockDomain: Boolean) {
        requireDrain(value.publication.createdAt <= run.sealedAt && (run.progress != null || value.recovery.state == "PARTIAL") &&
            (!converted || value.recovery.state == "CONVERTED"))
        requireCompletedFacts(jdbc, facts, value, family, lockDomain)
    }

    /** The registered caller already owns N/P/L before counters/domain. These fixed, non-locking
     * rereads compare the retained family; they issue no work, verifier, native or drain authority. */
    fun requireCompletedReplayFacts(jdbc: JdbcTemplate, binding: OwnerDeleteAllJournalBindingV1,
        event: OwnerDeleteAllJournalEventV1): TestOrdinaryDrainPersistenceV1.AllPrimary {
        requireDrain(event.belongsTo(binding))
        val facts = TestOrdinaryDrainPersistenceV1.FamilyFacts(checkNotNull(binding.test), event.tuple.epoch)
        val value = TestOrdinaryDrainPersistenceV1.closedPrimary(jdbc, facts, event.route.eventId) as? TestOrdinaryDrainPersistenceV1.AllPrimary
            ?: throw TestOrdinaryDrainExceptionV1()
        requireDrain(value.recovery.state == "PARTIAL" && value.event.canonicalBytes().contentEquals(event.canonicalBytes()))
        val family = TestOrdinaryDrainPersistenceV1.readFamilyFacts(jdbc, facts, value, checkNotNull(value.publication.verifiedAt))
        requireCompletedFacts(jdbc, facts, value, family, lockDomain = false)
        return value
    }

    private fun requireCompletedFacts(jdbc: JdbcTemplate, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts,
        value: TestOrdinaryDrainPersistenceV1.AllPrimary, family: List<TestOrdinaryDrainPersistenceV1.Applied>, lockDomain: Boolean) {
        val p = value.publication
        val event = value.event
        facts.requireEvent(event)
        requireDrain(p.kind.name == "OWNER_DELETE_ALL" && p.state == "APPLIED" && p.scope == facts.scope &&
            p.writer.toString() == facts.writer)
        val r = value.receipt
        val tuple = event.tuple
        requireDrain(value.installationId == tuple.actorId && r.key == tuple.operationKey && r.version == tuple.credentialVersion &&
            r.fingerprint.contentEquals(tuple.fingerprintBytes()) && r.reference == p.eventId && r.state == "COMPLETED" &&
            r.authorizedAt == p.createdAt && r.completedAt == value.appliedAt && r.expiresAt == value.appliedAt.plus(Duration.ofHours(192)))
        val external = checkNotNull(r.external)
        requireDrain(external.eventId == p.eventId && external.epoch == p.epoch && external.version == p.objectVersion && external.hash.contentEquals(p.ciphertextHash))
        val binding = OwnerDeleteAllJournalBindingV1(facts.routing)
        val proof = OwnerDeleteAllVerificationCodecV1(binding).parse(checkNotNull(p.verificationBytes), binding.fromTest(event))
        requireDrain(proof.objectVersion == p.objectVersion && proof.ciphertextSha256 == HexFormat.of().formatHex(p.ciphertextHash) &&
            Instant.parse(proof.objectCreatedAt) == p.objectCreatedAt && Instant.parse(proof.retainUntil) == p.retainUntil && Instant.parse(proof.verifiedAt) == p.verifiedAt)
        val primaryApplied = family.single { it.key == p.objectKey && it.version == p.objectVersion }
        requireDrain(primaryApplied.eventId == p.eventId && primaryApplied.ciphertext == proof.ciphertextSha256 && primaryApplied.at == value.appliedAt)
        val now = TestOrdinaryDrainPersistenceV1.now(jdbc)
        val verifiedAt = Instant.parse(proof.verifiedAt)
        val recovery = value.recovery
        // A is the actual stored/reconstructed comparison time, not an invented earlier AUTH.
        // Native V, primary completion T and last counted application R keep their own clocks.
        requireDrain(p.createdAt <= value.appliedAt && verifiedAt <= value.appliedAt && value.appliedAt <= recovery.lastAppliedAt &&
            recovery.lastAppliedAt <= now && r.createdAt <= recovery.lastAppliedAt && Instant.parse(proof.retainUntil).isAfter(now))
        val suffix = if (lockDomain) " FOR UPDATE" else ""
        val identity = jdbc.query(TestOrdinaryDrainSqlV1.ownerIdentity + suffix, { row, _ ->
            requireScope(row, facts)
            requireDrain(row.getString("state") == "DELETED")
            row.getTimestamp("created_at").toInstant() to checkNotNull(row.getTimestamp("terminal_at")).toInstant()
        }, tuple.actorId).single()
        requireDrain(identity.second in p.createdAt..recovery.lastAppliedAt)
        if (recovery.used[ComplaintCapacityCounter.INSTALLATION_IDS] != 0L) requireDrain(identity.first in p.createdAt..recovery.lastAppliedAt)
        val credentials = jdbc.query(OwnerDeleteAllInventorySqlV1(facts.routing.journalConfiguration.scope).credential.removeSuffix(" FOR UPDATE") + suffix,
            { row, _ -> OwnerDeleteAllApplyRows.credential(row) }, tuple.actorId)
        requireDrain(credentials.size <= 1)
        credentials.forEach { credential ->
            requireDrain(credential.state == "DELETED" && credential.credentialVersion == Math.addExact(tuple.credentialVersion, 1L) &&
                credential.deletedAt == identity.second && credential.expiresAt == identity.second.plus(Duration.ofHours(192)))
        }
        var reconstructed = 0L
        val resourceDeletions = ArrayList<Instant>()
        event.complaintIds().sortedBy(UUID::toString).forEach { target ->
            jdbc.query(TestOrdinaryDrainSqlV1.resourceIdentity + suffix, { row, _ ->
                requireScope(row, facts)
                val deletedAt = checkNotNull(row.getTimestamp("deleted_at")).toInstant()
                requireDrain(row.getString("state") == "DELETED" && deletedAt <= recovery.lastAppliedAt)
                resourceDeletions.add(deletedAt)
                if (row.getTimestamp("created_at").toInstant() >= p.createdAt) reconstructed++
            }, target).single()
        }
        requireDrain(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM complaints WHERE owner_id = ?)", Boolean::class.java, tuple.actorId) == true)
        val installationSummaries = jdbc.query(primaryAudit, { row, _ -> Summary.read(row, recovery.lastAppliedAt, value.appliedAt, installation = true) },
            Math.addExact(tuple.credentialVersion, 1L), facts.scope, facts.scope.toString(), Timestamp.from(value.appliedAt))
        val routes = facts.routing.derive(tuple).candidates()
        val recoverySummaries = jdbc.query(recoveryAudits, { row, _ -> Summary.read(row, recovery.lastAppliedAt, p.createdAt, installation = false) },
            facts.scope, facts.scope.toString(), routes.joinToString(",", "{", "}") { it.eventId })
        // Four E versions do not mean four lifetime summaries. Every retained summary still
        // spends the unchanged original 113-audit promise, including bounded bookkeeping repair.
        requireDrain(recoverySummaries.size <= 113 && recoverySummaries.size.toLong() <= recovery.used[ComplaintCapacityCounter.AUDIT_ROWS] &&
            recoverySummaries.map { it.at }.distinct().size == recoverySummaries.size)
        val systemPrimaries = recoverySummaries.filter { it.at == value.appliedAt }
        // The native proof and exact N/P/E/L comparisons precede this accounting check. The
        // producer's audit corroborates effects: one INSTALLATION or SYSTEM shape, never both.
        requireDrain(installationSummaries.size + systemPrimaries.size == 1)
        val primarySummary = (installationSummaries + systemPrimaries).single()
        val primaryActor = if (installationSummaries.isNotEmpty()) "INSTALLATION" else "SYSTEM"
        requireDrain(primaryActor == "INSTALLATION" || primarySummary.eventId == p.eventId)
        requireRemovalAudits(jdbc, facts, primarySummary, primaryActor)
        val summaries = recoverySummaries.filterNot { it === primarySummary }
        val allSummaries = listOf(primarySummary) + summaries
        requireDrain(allSummaries.maxOf { it.at } == recovery.lastAppliedAt && allSummaries.any { it.at == identity.second })
        val receiptRepaired = r.createdAt > value.appliedAt && summaries.any { it.eventId == p.eventId && it.at >= r.createdAt }
        summaries.forEach { summary ->
            val introduced = family.filter { it.eventId == summary.eventId && it.at == summary.at }
            requireDrain(introduced.size <= 1 && family.any { it.eventId == summary.eventId && checkNotNull(it.at) <= summary.at })
            requireRemovalAudits(jdbc, facts, summary, "SYSTEM")
            // Earlier aliases describe their own E. Later zero-effect primary summaries can
            // corroborate repeated missing-N repair only with an actually recreated retained N.
            // Their audit charges never supply missing family evidence or authenticate a caller.
            requireDrain(introduced.isNotEmpty() ||
                summary.removed + summary.resources + summary.installations > 0 ||
                identity.second == summary.at || summary.at in resourceDeletions ||
                receiptRepaired && summary.eventId == p.eventId && summary.at > value.appliedAt)
        }
        family.filterNot { it.key == p.objectKey && it.version == p.objectVersion }.forEach { applied ->
            requireDrain(summaries.count { it.eventId == applied.eventId && it.at == applied.at } == 1)
        }
        requireDrain(recovery.used[ComplaintCapacityCounter.RESOURCE_IDS] == reconstructed &&
            reconstructed == primarySummary.resources.toLong() + summaries.sumOf { it.resources.toLong() } &&
            recovery.used[ComplaintCapacityCounter.INSTALLATION_IDS] == primarySummary.installations.toLong() + summaries.sumOf { it.installations.toLong() } &&
            recovery.used[ComplaintCapacityCounter.AUDIT_ROWS] == primarySummary.removed + 1L + summaries.sumOf { it.removed + 1L })
        // Unlike LIVE, TEST ordinary objects are ineligible for production retirement (frozen V6
        // §7.2). requireSupported rejects EVERY retirement row, and this reader rejects any spent
        // retirement counter. Only after the paid denial/all-version cut, terminal identity and
        // zero current content can unused reconstruction/version/audit/retirement slices close.
        // The run/manifest/purge reserves are separate and remain wholly outside this P−U proof.
    }

    private fun requireRemovalAudits(jdbc: JdbcTemplate, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts, summary: Summary, actor: String) {
        // Never reuse an ambiguous scope/time group as two different primaries' actual U.
        val groups = jdbc.queryForObject("""
            SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND entity_type = 'complaint_scope'
                AND action IN ('COMPLAINT_INSTALLATION_DELETED', 'COMPLAINT_RECOVERY_APPLIED') AND created_at = ?
        """.trimIndent(), Long::class.java, facts.scope, Timestamp.from(summary.at))
        requireDrain(groups == 1L)
        val removed = jdbc.query(removalAudits, { row, _ ->
            requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid"))
            checkNotNull(row.getString("entity_id"))
        }, facts.scope, actor, Timestamp.from(summary.at))
        requireDrain(removed.size == summary.removed && removed.distinct().size == removed.size)
        removed.forEach { id ->
            jdbc.query(TestOrdinaryDrainSqlV1.resourceIdentity, { row, _ ->
                requireScope(row, facts)
                requireDrain(row.getString("state") == "DELETED" && row.getTimestamp("deleted_at").toInstant() >= summary.at)
            }, UUID.fromString(id)).single()
        }
    }
    private class Summary(val eventId: String?, val at: Instant, val removed: Int, val resources: Int, val installations: Int) {
        companion object {
            fun read(row: ResultSet, last: Instant, first: Instant, installation: Boolean): Summary {
                requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid"))
                val at = checkNotNull(row.getTimestamp("created_at")).toInstant()
                val removed = row.getInt("removed")
                val resources = row.getInt("reconstructed")
                val installations = if (installation) 0 else row.getInt("installation")
                requireDrain(at in first..last && removed in 0..100 && resources in 0..100 && installations in 0..1)
                return Summary(if (installation) null else checkNotNull(row.getString("event_id")), at, removed, resources, installations)
            }
        }
    }
    private fun requireScope(row: ResultSet, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts) {
        requireDrain(row.getObject("data_scope_id", UUID::class.java) == facts.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
            TestOrdinaryDrainRowsV1.boolean(row, "finite"))
    }
    private val primaryAudit = """
        SELECT created_at, (detail->>'removed')::int AS removed, (detail->>'reconstructed')::int AS reconstructed,
            (actor_user_id IS NULL AND complaint_actor_kind = 'INSTALLATION' AND isfinite(created_at) AND
                detail = jsonb_build_object('version', ?::bigint, 'removed', (detail->>'removed')::int, 'reconstructed', (detail->>'reconstructed')::int)) IS TRUE AS valid
        FROM audit_log WHERE complaint_data_scope_id = ?::uuid AND entity_type = 'complaint_scope' AND entity_id = ?
            AND action = 'COMPLAINT_INSTALLATION_DELETED' AND created_at = ?::timestamptz ORDER BY id LIMIT 2
    """.trimIndent()
    private val recoveryAudits = """
        SELECT created_at, detail->>'eventId' AS event_id, (detail->>'removed')::int AS removed,
            (detail->>'reconstructed')::int AS reconstructed, (detail->>'installation')::int AS installation,
            (actor_user_id IS NULL AND complaint_actor_kind = 'SYSTEM' AND isfinite(created_at) AND detail =
                jsonb_build_object('eventId', detail->>'eventId', 'removed', (detail->>'removed')::int,
                    'reconstructed', (detail->>'reconstructed')::int, 'installation', (detail->>'installation')::int)) IS TRUE AS valid
        FROM audit_log WHERE complaint_data_scope_id = ?::uuid AND entity_type = 'complaint_scope' AND entity_id = ?
            AND action = 'COMPLAINT_RECOVERY_APPLIED' AND detail->>'eventId' = ANY (?::text[]) ORDER BY id LIMIT 114
    """.trimIndent()
    private val removalAudits = """
        SELECT entity_id, (complaint_data_scope_id = ?::uuid AND actor_user_id IS NULL AND complaint_actor_kind = ?
            AND detail = jsonb_build_object('version', (detail->>'version')::bigint) AND (detail->>'version')::bigint > 0
            AND isfinite(created_at)) IS TRUE AS valid
        FROM audit_log WHERE action = 'COMPLAINT_DELETED' AND entity_type = 'complaint' AND created_at = ?::timestamptz ORDER BY id LIMIT 101
    """.trimIndent()
}
