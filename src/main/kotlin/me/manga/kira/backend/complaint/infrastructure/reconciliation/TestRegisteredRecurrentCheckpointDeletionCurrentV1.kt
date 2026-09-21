package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentJsonV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.infrastructure.AdminDeleteRows
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyRows
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Passive deletion-only comparisons. The retained binding calls this only for its typed winning
 * new claim, under its original controls/phase. No producer, CREATE authority, provider, lock,
 * write, lease, replacement budget, eligible token or request APPLY is created here.
 */
internal class TestRegisteredRecurrentCheckpointDeletionCurrentV1(
    registration: ComplaintTestNamespaceRegistrationV1,
    private val jdbc: JdbcTemplate,
) {
    private val process = registration.process
    private val policy = checkNotNull(process.initialCheckpointDeletion)
    private val source = checkNotNull(process.activeRecurrent)
    private val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    private val routing = process.consumers.journalRouting
    private val journal = routing.journalConfiguration
    private val maximumEvents = journal.declaration().limits.capacity.maximumRetainedVersions.toLong()
    private val maximumBytes = journal.declaration().limits.capacity.maximumScanStagingBytes
    private val maximumReceipts = process.consumers.capacityPolicy.hardLimit[ComplaintCapacityCounter.NORMAL_RECEIPTS]
    private val maximumAllReceipts = process.consumers.capacityPolicy.hardLimit[ComplaintCapacityCounter.INSTALLATION_RECEIPTS]

    private fun retained() {
        requireRecurrent(policy.recurrentCurrent && process.initialCheckpointDeletion === policy && process.activeRecurrent === source)
        policy.requireRetained(process.pools, routing, process.initialCheckpoint, process.activeCutoffPublication, source)
    }

    /** Selection is never eligibility; a malformed/recurrent refusal cannot try the initial path. */
    internal fun selectInitial(): Boolean {
        retained()
        return jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.branch, { row, _ ->
            requireRecurrent(row.getBoolean("valid") && !row.wasNull())
            recurrentLong(row, "rotation_sequence").also { requireRecurrent(it in 1..14) }
        }, identity.scope).single() == 1L
    }

    internal fun readCurrent(owned: Array<Any?>): Instant {
        retained(); requireRecurrent(owned.size == 17)
        return readControl().use { current ->
            requireRecurrent(jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.owned, { row, _ ->
                row.getBoolean("valid").also { requireRecurrent(!row.wasNull()) }
            }, identity.scope, identity.writer, current.epoch, *owned).single())
            val intents = readIntents(current)
            try {
                checkNotNull(jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.history, ResultSetExtractor { rows ->
                    TestActiveRecurrentHistoryV1.read(rows, intents, current.sampledAt, source.retention)
                }, identity.scope)).use { history ->
                    requireRecurrent(history.records.size == intents.size && history.records.all { it.checkpointSha256 != null && it.checkpointedAt != null })
                    val commitment = checkNotNull(history.commitment)
                    current.requireCheckpoint(intents.last(), commitment)
                    val last = history.records.last()
                    requireRecurrent(last.checkpointSha256 == current.checkpointSha256 && last.checkpointedAt == current.checkpointedAt)
                    val bytes = current.checkpointBytes(); val archived = checkNotNull(last.checkpointBytes())
                    val document = try {
                        requireRecurrent(bytes.contentEquals(archived))
                        TestActiveRecurrentCheckpointDocumentV1.parse(bytes)
                    } finally { bytes.fill(0); archived.fill(0) }
                    current.proof(intents.last(), source.retention).use { it.requireSame(last.verification) }

                    val priorRetention = requirePriorHistory(current, owned)
                    requireCoverage(current, commitment, document)
                    // Every earlier lock wait, page and bounded parse precedes this DB sample.
                    val now = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
                    val floor = now.plusMillis(source.retention.retention.utcUncertainty.maximumMillis)
                    val completed = checkNotNull(current.checkpointedAt)
                    requireRecurrent(TestActiveRecurrentJsonV1.time(now) && now >= current.sampledAt && completed <= now &&
                        completed.plusMillis(journal.declaration().limits.deadlines.checkpointMaxAgeMillis.toLong()) >= now &&
                        priorRetention > floor && history.records.all { it.verification.retainUntil > floor })
                    retained()
                    now
                }
            } finally { intents.forEach { it.close() } }
        }
    }

    private fun readControl(): TestActiveRecurrentCurrentV1 {
        val args = identity.arguments()
        return try {
            checkNotNull(jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.current, ResultSetExtractor { rows ->
                requireRecurrent(rows.next() && rows.getBoolean("deletion_valid") && !rows.wasNull())
                val current = TestActiveRecurrentCurrentV1.copy(rows)
                try {
                    requireRecurrent(!rows.next() && current.sequence in 2..14 && current.lease == null && current.checkpointSha256 != null)
                    current
                } catch (problem: Throwable) { current.close(); throw problem }
            }, *args, identity.scope))
        } finally { args.forEach { if (it is ByteArray) it.fill(0) } }
    }

    private fun readIntents(current: TestActiveRecurrentCurrentV1): List<TestActiveRecurrentIntentV1> {
        val intents = ArrayList<TestActiveRecurrentIntentV1>()
        try {
            fun headers(sql: String, source: TestActiveCheckpointHistoryV1.Source) {
                jdbc.query(sql, ResultSetExtractor { rows ->
                    while (rows.next()) {
                        requireRecurrent(intents.size < TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
                        intents.add(TestActiveRecurrentIntentV1(rows, source, identity))
                    }
                }, identity.scope)
            }
            headers(TestRegisteredRecurrentCheckpointDeletionSqlV1.initialHeaders, TestActiveCheckpointHistoryV1.Source.V26_INITIAL)
            requireRecurrent(intents.size == 1)
            headers(TestRegisteredRecurrentCheckpointDeletionSqlV1.recurrentHeaders, TestActiveCheckpointHistoryV1.Source.V31_RECURRENT)
            intents.forEachIndexed { index, intent ->
                requireRecurrent(intent.ordinal == index + 1 && intent.state == "WIRE_FROZEN")
                jdbc.query(if (index == 0) TestRegisteredRecurrentCheckpointDeletionSqlV1.initialPayload else TestRegisteredRecurrentCheckpointDeletionSqlV1.recurrentPayload,
                    ResultSetExtractor { rows -> requireRecurrent(rows.next()); intent.bindPayload(rows); requireRecurrent(!rows.next()) }, intent.token, identity.scope)
            }
            current.requireIntents(intents)
            return intents.toList()
        } catch (problem: Throwable) { intents.forEach { it.close() }; throw problem }
    }

    /** Prior completed current-epoch work is legal, but is NOT claimed to be checkpoint-covered. */
    private fun requirePriorHistory(current: TestActiveRecurrentCurrentV1, owned: Array<Any?>): Instant {
        val counts = jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.historyCounts, { row, _ ->
            requireRecurrent(row.getBoolean("no_retirements") && !row.wasNull())
            longArrayOf(recurrentLong(row, "publications"), recurrentLong(row, "receipts"), recurrentLong(row, "all_receipts"),
                recurrentLong(row, "reservations"), recurrentLong(row, "applied"), recurrentLong(row, "rejections"))
        }, identity.scope, journal.ordinaryPrefix, sentinel(maximumEvents), sentinel(maximumReceipts), sentinel(maximumAllReceipts)).single()
        requireRecurrent(counts[0] in 0..maximumEvents && counts[1] in 0..maximumReceipts && counts[2] in 0..maximumAllReceipts &&
            counts[3] in 0..maximumEvents && counts[4] in 0..maximumEvents && counts[5] in 0..counts[1])
        var publications = 0L; var receipts = 0L; var allReceipts = 0L; var applied = 0L
        var retention = Instant.MAX
        var after: String? = null
        while (true) {
            val ids = jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.publications, { row, _ -> checkNotNull(row.getString("event_id")) },
                identity.scope, journal.ordinaryPrefix, owned[6], after, after)
            requireRecurrent(ids.size <= TestRegisteredRecurrentCheckpointDeletionSqlV1.PAGE)
            if (ids.isEmpty()) break
            ids.forEach { id ->
                requireRecurrent(publications < maximumEvents && (after == null || id > checkNotNull(after)))
                jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.publication, { row, _ -> PriorPublication(row) }, id).single().use { prior ->
                    val p = prior.row
                    requireRecurrent(p.eventId == id && p.scope == identity.scope && p.writer == identity.writer && p.epoch in 2..current.epoch &&
                        p.createdAt <= current.sampledAt && prior.appliedAt <= current.sampledAt && prior.appliedAt >= prior.proof.verifiedAt &&
                        prior.proof.createdAt <= prior.proof.verifiedAt && prior.proof.retainUntil > current.sampledAt)
                    val event = when (p.kind.name) {
                        "OWNER_DELETE", "OWNER_DELETE_ALL" -> TestOwnerDeleteJournalCodecV1.restoreCanonical(routing, p.bytes, p.routingKey)
                        "ADMIN_DELETE" -> TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(routing, p.bytes, p.routingKey)
                        "ADMIN_BATCH_DELETE" -> TestOwnerDeleteJournalCodecV1.restoreAdminBatchCanonical(routing, p.bytes, p.routingKey)
                        else -> throw TestActiveRecurrentExceptionV1()
                    }
                    when (p.kind.name) {
                        "OWNER_DELETE" -> p.requireEvent(event)
                        "OWNER_DELETE_ALL" -> p.requireEvent(OwnerDeleteAllJournalBindingV1(routing).fromTest(event))
                        else -> p.requireAdminErasureEvent(event)
                    }
                    prior.proof.requireParsed(event, routing)
                    val receiptCreatedAt = requireReceipt(prior, event, current.sampledAt)
                    applied = Math.addExact(applied, requireFamily(prior, event, current, receiptCreatedAt).toLong())
                    if (p.kind.name == "OWNER_DELETE_ALL") allReceipts++ else receipts++
                    retention = minOf(retention, prior.proof.retainUntil)
                }
                publications++; after = id
            }
        }
        val newAll = owned[0] == "OWNER_DELETE_ALL"
        val newReceipt = if (!newAll && owned[15] == null) 1L else 0L // Completed no-event rejections are counted separately.
        requireRecurrent(counts[0] == publications + (if (owned[13] == null) 0L else 1L))
        requireRecurrent(counts[1] == receipts + newReceipt + counts[5] && counts[2] == allReceipts + (if (newAll) 1L else 0L))
        requireRecurrent(counts[3] == publications + (if (owned[12] == true) 1L else 0L))
        requireRecurrent(counts[4] == applied)
        return retention
    }

    private fun requireReceipt(prior: PriorPublication, event: TestOwnerDeleteJournalEventV1, at: Instant): Instant {
        val p = prior.row; val tuple = event.comparison
        return when (p.kind.name) {
            "OWNER_DELETE_ALL" -> jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.allReceipt(journal.scope), { row, _ ->
                val n = OwnerDeleteAllApplyRows.receipt(row)
                val fingerprint = tuple.fingerprintBytes()
                try {
                    requireRecurrent(n.state == "COMPLETED" && n.key == tuple.operationKey && n.version == tuple.credentialVersion && n.fingerprint.contentEquals(fingerprint) &&
                        n.reference == p.eventId && n.authorizedAt == p.createdAt && n.completedAt == prior.appliedAt &&
                        TestActiveRecurrentJsonV1.time(n.createdAt) && n.createdAt <= at)
                    val external = checkNotNull(n.external)
                    requireRecurrent(external.eventId == p.eventId && external.epoch == p.epoch && external.version == p.objectVersion && external.hash.contentEquals(p.ciphertextHash))
                    n.createdAt // Recreated ALL N keeps the original P completion/TTL; family checks its real creation against U.
                } finally { fingerprint.fill(0); n.fingerprint.fill(0); n.external?.hash?.fill(0) }
            }, tuple.actorId).single()
            "OWNER_DELETE" -> jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.ownerReceipt, { row, _ ->
                val n = OwnerDeleteRows.Receipt(row)
                val fingerprint = tuple.fingerprintBytes()
                try {
                    val expected = ComplaintOwnerDeleteTuple(ScopedInstallationId(tuple.actorId, journal.scope), tuple.operationKey, event.complaintIds().single(), fingerprint)
                    requireRecurrent(n.valid && n.state == "COMPLETED" && n.matches(expected) && n.completed() === ComplaintOwnerDeleteReceipt.Applied &&
                        n.publication == p.eventId && n.authorizedAt == p.createdAt && n.externalEvent == p.eventId && n.externalEpoch == p.epoch &&
                        n.externalVersion == p.objectVersion && n.externalHash.contentEquals(p.ciphertextHash))
                    requireReceiptTimes(row, prior, at)
                } finally { fingerprint.fill(0); n.externalHash?.fill(0) }
            }, tuple.actorId, tuple.operationKey).single()
            else -> jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.adminReceipt, { row, _ ->
                val n = AdminDeleteRows.Receipt(row)
                try {
                    requireRecurrent(n.valid && n.state == "COMPLETED" && n.matches(AdminDeleteRows.tuple(event)) &&
                        n.publication == p.eventId && n.authorizedAt == p.createdAt && n.externalEvent == p.eventId && n.externalEpoch == p.epoch &&
                        n.externalVersion == p.objectVersion && n.externalHash.contentEquals(p.ciphertextHash))
                    AdminDeleteRows.requireApplied(n.completed(), event)
                    requireReceiptTimes(row, prior, at)
                } finally { n.externalHash?.fill(0) }
            }, tuple.actorId, tuple.operationKey).single()
        }
    }

    private fun requireReceiptTimes(row: ResultSet, prior: PriorPublication, at: Instant): Instant {
        val created = recurrentTime(row, "created_at"); val completed = recurrentTime(row, "completed_at")
        requireRecurrent(created <= completed && completed in prior.appliedAt..at)
        return created // Owner/Admin reconstructed N completes at its actual later SQL stamp, not the old P stamp.
    }

    private fun requireFamily(prior: PriorPublication, event: TestOwnerDeleteJournalEventV1, current: TestActiveRecurrentCurrentV1, receiptCreatedAt: Instant): Int {
        val p = prior.row
        val routes = routing.derive(event.comparison).candidates()
        requireRecurrent(routes.size == 4 && routes.distinct().size == 4 && event.route in routes)
        val ids = textArray(routes.map { it.eventId }); val keys = textArray(routes.map { it.objectKey })
        jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.family, { row, _ ->
            val all = p.kind.name == "OWNER_DELETE_ALL"
            requireRecurrent(recurrentLong(row, "publications") == 1L && recurrentLong(row, "reservations") == 1L &&
                recurrentLong(row, "receipts") == (if (all) 0L else 1L))
            requireRecurrent(recurrentLong(row, "all_receipts") == (if (all) 1L else 0L))
            requireRecurrent(row.getBoolean("no_retirements") && !row.wasNull())
        }, ids, keys).single()
        val family = jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.appliedFamily, { row, _ -> Applied(row, current.epoch, current.sampledAt) }, ids, keys)
        requireRecurrent(family.size in 1..4 && family.map { it.eventId }.distinct().size == family.size)
        family.forEach { a ->
            val route = routes.single { it.eventId == a.eventId }
            requireRecurrent(a.key == route.objectKey && a.epoch == p.epoch && a.kind == p.kind.name && a.targets == p.targetCount && a.at >= p.createdAt)
        }
        val primary = family.single { it.eventId == p.eventId }
        // Recovery can create P after native VERIFY; the durable equation is P.created <= E <= P.applied, E >= VERIFY.
        requireRecurrent(primary.version == prior.proof.version && primary.wire == prior.proof.ciphertext && primary.at >= prior.proof.verifiedAt && primary.at <= prior.appliedAt)
        if (p.kind.name == "OWNER_DELETE_ALL") requireRecurrent(primary.at == prior.appliedAt)
        val promise = when (p.kind.name) {
            "OWNER_DELETE_ALL" -> OwnerDeleteAllCapacityCharges.RECOVERY
            "OWNER_DELETE" -> OwnerDeleteCapacityCharges.RECOVERY
            else -> AdminDeleteRows.recovery(event)
        }
        jdbc.query(TestRegisteredRecurrentCheckpointDeletionSqlV1.recovery, { row, _ ->
            val l = OwnerDeleteRows.Recovery(row, journal.scope, p.eventId, promise)
            val converted = checkNotNull(l.convertedAt)
            requireRecurrent(TestActiveRecurrentJsonV1.time(converted) && converted <= current.sampledAt &&
                recurrentTime(row, "created_at") <= converted && family.all { it.at <= converted })
            if (p.kind.name == "OWNER_DELETE_ALL") requireRecurrent(receiptCreatedAt <= converted)
            val installations = l.used[ComplaintCapacityCounter.INSTALLATION_IDS]
            val resources = l.used[ComplaintCapacityCounter.RESOURCE_IDS]
            val audits = l.used[ComplaintCapacityCounter.AUDIT_ROWS]
            requireRecurrent(installations in 0..promise[ComplaintCapacityCounter.INSTALLATION_IDS] &&
                resources in 0..promise[ComplaintCapacityCounter.RESOURCE_IDS] && audits in family.size.toLong()..promise[ComplaintCapacityCounter.AUDIT_ROWS])
            val allowed = ComplaintCapacityCharges.INSTALLATION_ID.scaled(installations) + ComplaintCapacityCharges.RESOURCE_ID.scaled(resources) +
                ComplaintCapacityCharges.AUDIT.scaled(audits) + OwnerDeleteAllCapacityCharges.APPLIED.scaled(family.size.toLong())
            requireRecurrent(l.used == allowed)
        }, p.eventId).single()
        return family.size
    }

    /** Same passive frame grammar as the producer; no supplied expected inventory or native authority. */
    private fun requireCoverage(current: TestActiveRecurrentCurrentV1, history: TestActiveCheckpointHistoryV1, document: TestActiveRecurrentCheckpointDocumentV1) {
        val expected = history.entries.fold(0L) { n, e -> Math.addExact(n, e.eventCount) }
        requireRecurrent(expected in 0..maximumEvents && document.objectCount == expected)
        val full = MessageDigest.getInstance("SHA-256"); val coverage = MessageDigest.getInstance("SHA-256")
        val ranges = history.entries.map { MessageDigest.getInstance("SHA-256") }
        fun header(hash: MessageDigest, start: Long, end: Long, count: Long): Long = EpochSealFramesV1.update(hash,
            listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", identity.writer.toString(), journal.ordinaryPrefix, "TEST", identity.scope.toString(),
                start.toString(), end.toString(), count.toString()))
        var framed = header(full, 1, document.cutoffEpoch, expected)
        val rangeBytes = history.entries.mapIndexed { index, e -> header(ranges[index], e.epochStart, e.epochEnd, e.eventCount) }.toLongArray()
        val rangeCounts = LongArray(ranges.size)
        EpochSealFramesV1.update(coverage, listOf("kira-test-active-application-coverage-v1", "1", identity.writer.toString(), identity.scope.toString(), history.rootSha256, expected.toString()))
        var count = 0L; var after = "" to ""
        val prefix = "${journal.ordinaryPrefix}writer/${identity.writer}/epoch/"
        val lower = prefix + "1".padStart(19, '0') + "/"
        val upper = prefix + (document.cutoffEpoch + 1).toString().padStart(19, '0') + "/"
        requireRecurrent(framed in 1..maximumBytes)
        while (true) {
            val rows = jdbc.query(TestActiveRecurrentScanSqlV1.appliedPage, { row, _ -> Applied(row, document.cutoffEpoch, current.sampledAt) },
                identity.scope, document.cutoffEpoch, lower, upper, after.first, after.second)
            requireRecurrent(rows.size <= TestRegisteredRecurrentCheckpointDeletionSqlV1.PAGE)
            if (rows.isEmpty()) break
            rows.forEach { a ->
                val locator = a.key to a.version
                requireRecurrent(count < expected && TestOrdinaryDrainRowsV1.compare(after, locator) < 0 && a.at <= document.completedAt)
                val index = history.entries.indexOfFirst { a.epoch in it.epochStart..it.epochEnd }.also { requireRecurrent(it >= 0) }
                val fields = listOf(a.key, a.version, a.wire)
                framed = Math.addExact(framed, EpochSealFramesV1.update(full, fields))
                rangeBytes[index] = Math.addExact(rangeBytes[index], EpochSealFramesV1.update(ranges[index], fields))
                requireRecurrent(framed <= maximumBytes && ++rangeCounts[index] <= history.entries[index].eventCount)
                EpochSealFramesV1.update(coverage, fields + listOf(a.eventId, a.kind, identity.writer.toString(), a.epoch.toString(), a.targets.toString(), a.at.toString()))
                count++; after = locator
            }
        }
        requireRecurrent(count == expected && recurrentHex(full.digest()) == document.second.manifestSha256 &&
            recurrentHex(coverage.digest()) == document.second.applicationCoverageSha256)
        history.entries.forEachIndexed { index, entry ->
            requireRecurrent(rangeCounts[index] == entry.eventCount && rangeBytes[index] == entry.manifestFramedBytes && recurrentHex(ranges[index].digest()) == entry.manifestSha256)
        }
    }

    private inner class Applied(row: ResultSet, maximumEpoch: Long, sampledAt: Instant) {
        val key = checkNotNull(row.getString("object_key")); val version = checkNotNull(row.getString("object_version"))
        val eventId = checkNotNull(row.getString("event_id")); val wire = recurrentHash(row, "ciphertext_hash")
        val epoch = recurrentLong(row, "journal_epoch"); val kind = checkNotNull(row.getString("event_kind"))
        val targets = Math.toIntExact(recurrentLong(row, "target_count")); val at = recurrentTime(row, "applied_at")
        init {
            requireRecurrent(row.getObject("data_scope_id", UUID::class.java) == identity.scope && row.getBoolean("test_only") && !row.wasNull() &&
                row.getObject("writer_generation", UUID::class.java) == identity.writer && epoch in 2..maximumEpoch && at <= sampledAt &&
                TestActiveRecurrentJsonV1.opaque(version) && Regex("[A-Za-z0-9_-]{43}").matches(eventId) && kind in TestActiveRecurrentScanV1.FAMILIES &&
                when (kind) { "OWNER_DELETE_ALL" -> targets in 0..100; "ADMIN_BATCH_DELETE" -> targets in 1..50; else -> targets == 1 })
            val prefix = "${journal.ordinaryPrefix}writer/${identity.writer}/epoch/${epoch.toString().padStart(19, '0')}/"
            requireRecurrent(key.length in 1..1024 && key.all { it in '!'..'~' } && key.startsWith(prefix))
        }
    }

    private class PriorPublication(source: ResultSet) : AutoCloseable {
        val appliedAt = recurrentTime(source, "applied_at") // Null/unapplied history fails before copying payloads.
        val row: OwnerDeleteRows.Publication = when (source.getString("event_kind")) {
            "OWNER_DELETE" -> OwnerDeleteRows.Publication(source)
            "OWNER_DELETE_ALL" -> OwnerDeleteRows.Publication.ownerDeleteAll(source)
            "ADMIN_DELETE", "ADMIN_BATCH_DELETE" -> OwnerDeleteRows.Publication.adminErasure(source)
            else -> throw TestActiveRecurrentExceptionV1()
        }
        val proof = try {
            requireRecurrent(row.state == "APPLIED")
            TestActiveCutoffPublicationRowV1.Proof.read(source)
        } catch (problem: Throwable) { clearRow(); throw problem }
        private fun clearRow() {
            listOf(row.bytes, row.semantic, row.ciphertextHash, row.verificationBytes, row.verificationHash).forEach { it?.fill(0) }
        }
        override fun close() {
            clearRow()
            proof.close()
        }
    }
    override fun toString(): String = "TestRegisteredRecurrentCheckpointDeletionCurrentV1(fixed-current-comparisons,no-authority)"
    companion object {
        private fun sentinel(maximum: Long): Long { requireRecurrent(maximum in 0 until Long.MAX_VALUE); return maximum + 1 }
        private fun textArray(values: List<String>): String = values.joinToString(",", "{", "}") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    }
}
