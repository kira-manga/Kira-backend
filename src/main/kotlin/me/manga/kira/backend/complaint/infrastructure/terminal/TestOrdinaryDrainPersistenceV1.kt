package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyRows
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Fixed comparisons under the registered original's controls. Row values are never native event
 * issuers. Recovery enters only through the deletion holder and its current private GET readback;
 * conversion enters only through the coordinator's receipt/publication/recovery-before-counters path.
 */
internal object TestOrdinaryDrainPersistenceV1 {
    fun requireControls(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1) {
        lockControlIdentities(jdbc, original)
        original.requireCapturedControl(readControl(jdbc, original))
        requireLease(jdbc, original)
    }

    fun lockControlIdentities(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1) {
        tick(original)
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) {
            requireDrain(jdbc.query(sql, { row, _ -> TestOrdinaryDrainRowsV1.boolean(row, "valid") },
                *original.registration.sealingControlArguments()).single())
            tick(original)
        }
    }

    fun readControl(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1): TestOrdinaryDrainRowsV1.Control =
        jdbc.query(TestOrdinaryDrainSqlV1.control, { row, _ -> TestOrdinaryDrainRowsV1.Control(row) }, original.scope).single()

    fun requireLease(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1) {
        tick(original)
        requireDrain(jdbc.query(TestOrdinarySealSqlV1.lease, { row, _ -> TestOrdinaryDrainRowsV1.boolean(row, "valid") },
            original.attemptId, original.leaseToken, original.scope).single())
    }

    fun readRun(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1): TestOrdinaryDrainRowsV1.Run {
        tick(original)
        val run = jdbc.query(TestOrdinaryDrainSqlV1.run, { row, _ -> TestOrdinaryDrainRowsV1.Run(row, original) },
            *original.registration.sealingRunArguments()).single()
        // Like the registered primary APPLY, verify the immutable audit without taking an audit
        // lock before installation/resource/content. Recovery and conversion keep that lock order.
        requireDrain(jdbc.query(TestRunSealingSqlV1.readAudit, { row, _ -> TestOrdinaryDrainRowsV1.boolean(row, "valid") },
            *original.registration.sealingAuditArguments(run.sealedAt)).single())
        return run
    }

    fun scanCharge(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, run: TestOrdinaryDrainRowsV1.Run): ComplaintCapacityVector =
        jdbc.query(TestOrdinaryDrainSqlV1.pool, { row, _ -> run.plan.scanPool.chargeFor(row.getLong("runs"), row.getLong("entries")) },
            original.scope, original.scope).single()

    fun scans(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1): List<TestOrdinaryDrainRowsV1.Scan> =
        jdbc.query(TestOrdinaryDrainSqlV1.runs, { row, _ -> TestOrdinaryDrainRowsV1.Scan(row, original) }, original.scope).also {
            requireDrain(it.size <= 2)
        }

    /** No other terminal intent may hide behind a state/kind filter or the paid sidecar count. */
    fun sidecars(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, run: TestOrdinaryDrainRowsV1.Run): Long {
        val rows = jdbc.query(TestOrdinarySealSqlV1.sidecar, { row, _ ->
            requireDrain(run.progress != null && TestOrdinaryDrainRowsV1.boolean(row, "valid") &&
                row.getObject("data_scope_id", UUID::class.java) == original.scope &&
                row.getString("object_kind") == "EPOCH_SEAL" && row.getInt("object_ordinal") == 0 &&
                row.getObject("writer_generation", UUID::class.java).toString() == original.writer &&
                row.getObject("operation_token", UUID::class.java) == original.capturedControl().captureId &&
                row.getLong("epoch_start") == 1L && row.getLong("epoch_end") == original.cutoff &&
                row.getLong("preparing_fencing_token") in 1..original.leaseToken &&
                row.getLong("activation_catalog_generation") == original.runContext.activationCatalogGeneration &&
                TestOrdinaryDrainRowsV1.hash(row, "activation_catalog_hash") == original.runContext.activationCatalogSha256 &&
                TestOrdinaryDrainRowsV1.hash(row, "configuration_hash") == original.runContext.configurationSha256 &&
                TestOrdinaryDrainRowsV1.hash(row, "terminal_encoding_hash") == original.runContext.terminalEncodingSha256 &&
                TestOrdinaryDrainRowsV1.hash(row, "journal_configuration_hash") == original.routing.journalConfiguration.sha256)
            row.getObject("operation_token", UUID::class.java)
        }, original.scope, original.routing.journalConfiguration.sealTerminalPrefix + "%")
        requireDrain(rows.size <= 1)
        return rows.size.toLong()
    }

    fun requireSupported(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1) {
        tick(original)
        requireDrain(jdbc.query(TestOrdinaryDrainSqlV1.supported, { row, _ -> TestOrdinaryDrainRowsV1.boolean(row, "valid") },
            original.scope, original.routing.journalConfiguration.ordinaryPrefix + "%",
            original.routing.journalConfiguration.sealTerminalPrefix + "%", original.writer, original.routing.journalConfiguration.ownerDeleteAll).single())
    }

    fun requireNoPending(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1) {
        requireDrain(jdbc.query(TestOrdinaryDrainSqlV1.noPending, { row, _ -> TestOrdinaryDrainRowsV1.boolean(row, "valid") },
            original.scope, original.scope, original.scope, original.scope, original.scope).single())
    }

    fun requirePaidScanHeaders(original: TestRunOrdinaryDrainV1, rows: List<TestOrdinaryDrainRowsV1.Scan>, completePair: Boolean) {
        val cut = original.paidCut()
        if (completePair) requireDrain(rows.size == 2 && rows.map { it.pass } == listOf(1, 2))
        rows.forEach { row ->
            val witness = if (row.pass == 1) cut.denial.firstInventory else cut.denial.secondInventory
            requireDrain(row.id.toString() == cut.scanId && row.state == "COMPLETE" && row.fence == cut.fencingToken &&
                row.count == witness.versionCount && row.framedBytes == cut.framedByteCount && row.root == witness.sha256 &&
                row.startedAt.epochSecond == witness.startedAtEpochSecond && row.finishedAt?.epochSecond == witness.completedAtEpochSecond)
        }
    }

    fun entryPage(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, id: UUID, pass: Int, after: Pair<String, String>?): List<TestOrdinaryDrainRowsV1.Entry> =
        jdbc.query(TestOrdinaryDrainSqlV1.entryPage, { row, _ -> TestOrdinaryDrainRowsV1.Entry.read(row, original) },
            id, pass, original.scope, after?.first, after?.first, after?.second)

    fun exactEntries(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, entry: TestOrdinaryDrainRowsV1.Entry): List<TestOrdinaryDrainRowsV1.Entry> {
        val rows = jdbc.query(TestOrdinaryDrainSqlV1.exactEntries, { row, _ ->
            requireDrain(row.getObject("scan_id", UUID::class.java) == original.scanId)
            row.getInt("pass") to TestOrdinaryDrainRowsV1.Entry.read(row, original)
        }, original.scanId, original.scope, entry.key, entry.version)
        requireDrain(rows.map { it.first } == listOf(1, 2))
        rows.forEach { entry.requireSame(it.second) }
        requireDrain(rows[0].second.replay == rows[1].second.replay)
        return rows.map { it.second }
    }

    fun requireRecoveryRun(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, entry: TestOrdinaryDrainRowsV1.Entry) {
        val run = readRun(jdbc, original)
        original.requirePaidProgress(run.progress)
        val sidecars = sidecars(jdbc, original, run)
        requireDrain(sidecars == 0L && run.sealSetBytes == null)
        run.requirePaidRemainder(scanCharge(jdbc, original, run), sidecars)
        requireSupported(jdbc, original)
        requirePaidScanHeaders(original, scans(jdbc, original), completePair = true)
        exactEntries(jdbc, original, entry)
    }

    fun markRecovered(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, entry: TestOrdinaryDrainRowsV1.Entry) {
        // Called only by the exact retained reducer after its real domain/audit/accounting effects.
        requireDrain(PersistencePhaseOwnership.current() != null)
        exactEntries(jdbc, original, entry)
        val applied = jdbc.query(OwnerDeletePersistenceSql.LOCK_APPLIED, { row, _ -> Applied.read(row, original) }, entry.key, entry.version).single()
        applied.requireEntry(entry)
        requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.markApplied, original.scanId, original.scope, entry.key, entry.version,
            hex(entry.ciphertext), hex(entry.semantic), entry.eventId, entry.kind, original.writer, entry.epoch, entry.ciphertextBytes) == 2)
        requireLease(jdbc, original)
    }

    sealed class Primary(val publication: OwnerDeleteRows.Publication, val appliedAt: Instant,
        val event: TestOwnerDeleteJournalEventV1, val recovery: TestOrdinaryDrainRowsV1.Recovery)
    class OwnerPrimary(val receipt: OwnerDeleteRows.Receipt, val receiptAt: Instant, publication: OwnerDeleteRows.Publication,
        appliedAt: Instant, event: TestOwnerDeleteJournalEventV1, recovery: TestOrdinaryDrainRowsV1.Recovery) : Primary(publication, appliedAt, event, recovery)
    class AllPrimary(val installationId: UUID, val receipt: OwnerDeleteAllApplyRows.Receipt, publication: OwnerDeleteRows.Publication,
        appliedAt: Instant, event: TestOwnerDeleteJournalEventV1, recovery: TestOrdinaryDrainRowsV1.Recovery) : Primary(publication, appliedAt, event, recovery)

    /** Conversion calls locked=true BEFORE counter acquisition. Other whole-relation passes are read-only under controls/run. */
    fun primary(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, id: String, locked: Boolean): Primary {
        tick(original)
        val receiptSql = if (locked) TestOrdinarySealSqlV1.receipt else TestOrdinarySealSqlV1.receipt.removeSuffix(" FOR UPDATE")
        val publicationSql = if (locked) TestOrdinarySealSqlV1.publication else TestOrdinarySealSqlV1.publication.removeSuffix(" FOR UPDATE")
        val recoverySql = if (locked) OwnerDeletePersistenceSql.LOCK_RECOVERY else OwnerDeletePersistenceSql.LOCK_RECOVERY.removeSuffix(" FOR UPDATE")
        val receipts = jdbc.query(receiptSql, { row, _ ->
            requireDrain(row.getString("actor_kind") == "INSTALLATION")
            OwnerDeleteRows.Receipt(row) to checkNotNull(row.getTimestamp("completed_at")).toInstant()
        }, id)
        val allReceiptSql = TestOrdinarySealSqlV1.ownerDeleteAllReceipt(original.routing.journalConfiguration.scope).let {
            if (locked) it else it.removeSuffix(" FOR UPDATE")
        }
        val allReceipts = jdbc.query(allReceiptSql, { row, _ ->
            checkNotNull(row.getObject("installation_id", UUID::class.java)) to OwnerDeleteAllApplyRows.receipt(row)
        }, id)
        val publication = jdbc.query(publicationSql, { row, _ ->
            val kind = checkNotNull(row.getString("event_kind"))
            original.requireInventoryKind(kind)
            (if (kind == "OWNER_DELETE_ALL") OwnerDeleteRows.Publication.ownerDeleteAll(row) else OwnerDeleteRows.Publication(row)) to
                checkNotNull(row.getTimestamp("applied_at")).toInstant()
        }, id).single()
        val p = publication.first
        val event = TestOwnerDeleteJournalCodecV1.restoreCanonical(original.routing, p.bytes, p.routingKey)
        p.requireEvent(event)
        val recovery = jdbc.query(recoverySql, { row, _ -> TestOrdinaryDrainRowsV1.Recovery(row, original, id, p.kind.name) }, id).single()
        return when (p.kind) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> {
                requireDrain(allReceipts.isEmpty())
                val receipt = receipts.single()
                OwnerPrimary(receipt.first, receipt.second, p, publication.second, event, recovery)
            }
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> {
                requireDrain(receipts.isEmpty())
                val receipt = allReceipts.single()
                AllPrimary(receipt.first, receipt.second, p, publication.second, event, recovery)
            }
            else -> throw TestOrdinaryDrainExceptionV1()
        }
    }

    fun requirePrimary(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, run: TestOrdinaryDrainRowsV1.Run, value: Primary,
        converted: Boolean, staged: Boolean, lockDomain: Boolean = false): Long {
        if (value is AllPrimary) return TestOrdinaryDrainAllPersistenceV1.requirePrimary(jdbc, original, run, value, converted, staged, lockDomain)
        requireDrain(value is OwnerPrimary)
        val owner = value as OwnerPrimary
        tick(original)
        val p = value.publication
        val event = value.event
        original.requireInventoryEvent(event)
        val tuple = ComplaintOwnerDeleteTuple(ScopedInstallationId(event.tuple.actorId, event.tuple.scope), event.tuple.operationKey,
            event.complaintIds().single(), event.tuple.fingerprintBytes())
        val r = owner.receipt
        val recovery = value.recovery
        val at = now(jdbc)
        requireDrain(run.progress != null || recovery.state == "PARTIAL")
        requireDrain(p.state == "APPLIED" && p.scope == original.scope && p.writer.toString() == original.writer && !p.createdAt.isAfter(run.sealedAt) &&
            r.valid && r.matches(tuple) && r.state == "COMPLETED" && r.completed() === ComplaintOwnerDeleteReceipt.Applied &&
            r.authorizedAt == p.createdAt && r.publication == p.eventId && r.externalEvent == p.eventId && r.externalEpoch == p.epoch &&
            r.externalVersion == p.objectVersion && r.externalHash.contentEquals(p.ciphertextHash))
        val proof = TestOwnerDeleteVerificationCodecV1(original.routing).parse(checkNotNull(p.verificationBytes), event)
        ComplaintOwnerDeleteVerificationOperation.requireColumns(proof, p)
        val verifiedAt = Instant.parse(proof.verifiedAt)
        requireDrain(!verifiedAt.isAfter(value.appliedAt) && !value.appliedAt.isAfter(owner.receiptAt) && !owner.receiptAt.isAfter(at) &&
            Instant.parse(proof.retainUntil).isAfter(at) && !recovery.lastAppliedAt.isBefore(verifiedAt) && !recovery.lastAppliedAt.isAfter(at) &&
            (!converted || recovery.state == "CONVERTED"))
        val family = requireFamily(jdbc, original, value, verifiedAt, staged)
        val ownerSql = TestOrdinaryDrainSqlV1.ownerIdentity + if (lockDomain) " FOR UPDATE" else ""
        val resourceSql = TestOrdinaryDrainSqlV1.resourceIdentity + if (lockDomain) " FOR UPDATE" else ""
        jdbc.query(ownerSql, { row, _ ->
            requireIdentity(row, original)
            val state = row.getString("state")
            requireDrain(state in setOf("ACTIVE", "RECOVERY_RESERVED") || state == "DELETED" &&
                requireCompletedAllCompanion(jdbc, original, run, event.tuple.actorId))
            if (recovery.used[ComplaintCapacityCounter.INSTALLATION_IDS] != 0L) requireDrain(state in setOf("RECOVERY_RESERVED", "DELETED") &&
                row.getTimestamp("created_at").toInstant() in p.createdAt..recovery.lastAppliedAt)
        }, event.tuple.actorId).single()
        jdbc.query(resourceSql, { row, _ ->
            requireIdentity(row, original)
            requireDrain(row.getString("state") == "DELETED" && row.getTimestamp("deleted_at").toInstant() <= recovery.lastAppliedAt)
            if (recovery.used[ComplaintCapacityCounter.RESOURCE_IDS] != 0L) requireDrain(row.getTimestamp("created_at").toInstant() in p.createdAt..recovery.lastAppliedAt)
        }, tuple.targetId).single()
        requireDrain(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM complaints WHERE id = ?)", Boolean::class.java, tuple.targetId) == true)
        val audits = jdbc.query(TestOrdinaryDrainSqlV1.targetAudits, { row, _ ->
            requireDrain(TestOrdinaryDrainRowsV1.boolean(row, "valid") && row.getTimestamp("created_at").toInstant() in verifiedAt..recovery.lastAppliedAt)
            row.getString("action")
        }, original.scope, tuple.targetId.toString())
        requireDrain(audits.size <= 5 && audits.count { it == "COMPLAINT_DELETED" } <= 1 &&
            audits.count { it == "COMPLAINT_RECOVERY_APPLIED" } <= family.size && recovery.used[ComplaintCapacityCounter.AUDIT_ROWS] == audits.size.toLong())
        tick(original)
        return family.size.toLong()
    }

    /** Shared exact-version comparison for ONLY the two explicitly supported owner families. */
    fun requireFamily(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, value: Primary, verifiedAt: Instant, staged: Boolean): List<Applied> {
        val p = value.publication
        val event = value.event
        val routes = original.routing.derive(event.tuple).candidates()
        requireDrain(routes.size == 4)
        val family = jdbc.query(TestOrdinaryDrainSqlV1.appliedFamily, { row, _ -> Applied.read(row, original, hasTime = true) },
            routes.joinToString(",", "{", "}") { it.eventId })
        requireDrain(family.size in 1..4 && family.map { it.locator }.distinct().size == family.size &&
            value.recovery.used[ComplaintCapacityCounter.JOURNAL_APPLIED] == family.size.toLong())
        requireDrain(family.groupBy { it.key }.values.all { sameKey -> sameKey.map { it.ciphertext }.distinct().size == 1 })
        var primarySeen = false
        family.forEach { applied ->
            val route = routes.single { it.eventId == applied.eventId }
            requireDrain(applied.key == route.objectKey && applied.epoch == p.epoch && applied.kind == p.kind.name && applied.targetCount == p.targetCount &&
                checkNotNull(applied.at) in verifiedAt..value.recovery.lastAppliedAt)
            if (route == event.route) {
                requireDrain(applied.ciphertext == HexFormat.of().formatHex(p.ciphertextHash))
                if (applied.version == p.objectVersion) primarySeen = true
            }
            if (staged) {
                val copies = jdbc.query(TestOrdinaryDrainSqlV1.exactEntries, { row, _ -> row.getInt("pass") to TestOrdinaryDrainRowsV1.Entry.read(row, original) },
                    original.scanId, original.scope, applied.key, applied.version)
                requireDrain(copies.map { it.first } == listOf(1, 2))
                copies.forEach { applied.requireEntry(it.second); requireDrain(it.second.replay == "APPLIED") }
                copies[0].second.requireSame(copies[1].second)
            }
        }
        requireDrain(primarySeen)
        return family
    }

    private fun requireCompletedAllCompanion(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, run: TestOrdinaryDrainRowsV1.Run, actor: UUID): Boolean {
        requireDrain(original.routing.journalConfiguration.ownerDeleteAll)
        // Read-only under the original controls: do not acquire another receipt/publication lock
        // after this conversion has locked counters/domain. No terminal state is accepted alone.
        val receipt = jdbc.query(OwnerDeleteAllApplySql.test(original.routing.journalConfiguration.scope).LOCK_RECEIPTS.removeSuffix(" FOR UPDATE"),
            { row, _ -> OwnerDeleteAllApplyRows.receipt(row) }, actor).single()
        val companion = primary(jdbc, original, receipt.reference, locked = false) as? AllPrimary ?: throw TestOrdinaryDrainExceptionV1()
        requireDrain(companion.installationId == actor)
        requirePrimary(jdbc, original, run, companion, converted = false, staged = false)
        return true
    }

    fun primaryPage(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, after: String?): List<String> =
        jdbc.query(TestOrdinaryDrainSqlV1.primaryPage, { row, _ -> checkNotNull(row.getString("event_id")) },
            original.scope, original.routing.journalConfiguration.ordinaryPrefix + "%", after, after)

    fun requireAllPrimaries(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, run: TestOrdinaryDrainRowsV1.Run,
        converted: Boolean, staged: Boolean): Long {
        requireSupported(jdbc, original)
        requireNoPending(jdbc, original)
        var after: String? = null
        var primaries = 0L
        var applied = 0L
        while (true) {
            tick(original)
            val page = primaryPage(jdbc, original, after)
            if (page.isEmpty()) break
            page.forEach { id ->
                requireDrain(after?.let { it < id } != false && primaries < original.maximumVersions)
                applied = Math.addExact(applied, requirePrimary(jdbc, original, run, primary(jdbc, original, id, locked = false), converted, staged))
                requireDrain(applied <= original.maximumVersions)
                primaries++; after = id
            }
        }
        val actual = jdbc.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, original.scope)
        requireDrain(actual == applied) // Includes every applied row, including aliases, never only primary publications.
        return applied
    }

    class Applied(val eventId: String, val key: String, val version: String, val ciphertext: String, val epoch: Long, val kind: String, val targetCount: Int,
        val at: Instant?, val stamp: String?) {
        val locator get() = key to version
        fun requireEntry(entry: TestOrdinaryDrainRowsV1.Entry) {
            requireDrain(eventId == entry.eventId && key == entry.key && version == entry.version && ciphertext == entry.ciphertext && epoch == entry.epoch && kind == entry.kind)
        }
        companion object {
            fun read(row: ResultSet, original: TestRunOrdinaryDrainV1, hasTime: Boolean = false): Applied {
                val kind = checkNotNull(row.getString("event_kind"))
                original.requireInventoryKind(kind)
                val targets = row.getInt("target_count").also { requireDrain(!row.wasNull()) }
                requireDrain(row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
                    row.getObject("writer_generation", UUID::class.java).toString() == original.writer &&
                    (if (kind == "OWNER_DELETE_ALL") targets in 0..100 else targets == 1) &&
                    TestOrdinaryDrainRowsV1.boolean(row, "finite") && row.getLong("journal_epoch") in 1..original.cutoff)
                return Applied(checkNotNull(row.getString("event_id")), checkNotNull(row.getString("object_key")), requireJournalVersion(row.getString("object_version")),
                    TestOrdinaryDrainRowsV1.hash(row, "ciphertext_hash"), row.getLong("journal_epoch"), kind, targets,
                    if (hasTime) checkNotNull(row.getTimestamp("applied_at")).toInstant() else null, if (hasTime) row.getString("stamp") else null)
            }
        }
    }

    fun appliedPage(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1, after: Pair<String, String>?): List<Applied> =
        jdbc.query(TestOrdinaryDrainSqlV1.appliedPage, { row, _ -> Applied.read(row, original, hasTime = true) }, original.scope,
            original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%",
            after?.first, after?.first, after?.second)

    /** Survives partial staging recycle: the entire actually applied key/version set must still be the paid cut. */
    fun requireAppliedCut(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1) {
        val cut = original.paidCut()
        val hash = MessageDigest.getInstance("SHA-256")
        val expected = cut.denial.firstInventory
        var framed = EpochSealFramesV1.update(hash, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
            original.routing.journalConfiguration.ordinaryPrefix, "TEST", original.scope.toString(), "1", original.cutoff.toString(), expected.versionCount.toString()))
        var count = 0L
        var after: Pair<String, String>? = null
        while (true) {
            tick(original)
            val page = appliedPage(jdbc, original, after)
            if (page.isEmpty()) break
            page.forEach { row ->
                requireDrain(count < expected.versionCount && after?.let { TestOrdinaryDrainRowsV1.compare(it, row.locator) < 0 } != false)
                framed = Math.addExact(framed, EpochSealFramesV1.update(hash, listOf(row.key, row.version, row.ciphertext)))
                requireDrain(framed <= original.maximumFramedBytes)
                count++; after = row.locator
            }
        }
        requireDrain(count == expected.versionCount && framed == cut.framedByteCount && HexFormat.of().formatHex(hash.digest()) == expected.sha256)
    }

    private fun requireIdentity(row: ResultSet, original: TestRunOrdinaryDrainV1) {
        requireDrain(row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
            TestOrdinaryDrainRowsV1.boolean(row, "finite"))
    }
    fun now(jdbc: JdbcTemplate): Instant = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
    private fun tick(original: TestRunOrdinaryDrainV1) { original.throwIfSignalled(); original.budget.remainingMillis(1) }
    private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
}
