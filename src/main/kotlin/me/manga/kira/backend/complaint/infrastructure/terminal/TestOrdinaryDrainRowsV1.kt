package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Bounded row comparisons only; none of these DTOs issues inventory, recovery or settlement authority. */
internal object TestOrdinaryDrainRowsV1 {
    class Control(row: ResultSet, val initialHistory: TestOrdinaryDrainActiveHistoryV1? = null) {
        val epoch = row.getLong("publication_epoch")
        val sequence = row.getLong("rotation_sequence")
        val terminalRotationSequence = (initialHistory?.count?.toLong() ?: 0L) + 1L
        val needsCapture = sequence == terminalRotationSequence - 1L
        val ordinaryStart = initialHistory?.reference?.epochEndInclusive?.let { Math.addExact(it, 1L) } ?: 1L
        val ordinaryHistoryCharge = initialHistory?.ordinaryPaid ?: ComplaintCapacityVector.ZERO
        val cutoff = if (needsCapture) epoch else row.getLong("rotation_epoch_before")
        val captureId: UUID? = row.getObject("rotation_id", UUID::class.java)
        val captureFence = row.getLong("rotation_capture_token")
        val capturedAt: Instant? = row.getTimestamp("rotation_captured_at")?.toInstant()
        val previousSealEpoch = row.getLong("seal_epoch")
        val historyHash = hash(row, "history_hash")
        init {
            requireDrain(boolean(row, "valid") && sequence in (terminalRotationSequence - 1)..terminalRotationSequence && epoch > 0 && cutoff > 0)
            if (!needsCapture) requireDrain(epoch == Math.addExact(cutoff, 1L) && captureId != null && captureFence > 0 && capturedAt != null)
            initialHistory?.requireControlFingerprint(historyHash)
            if (initialHistory != null) requireDrain(previousSealEpoch == initialHistory.reference.epochEndInclusive && cutoff == ordinaryStart &&
                (needsCapture && epoch == ordinaryStart && captureId == initialHistory.operationToken ||
                    !needsCapture && epoch == Math.addExact(ordinaryStart, 1L) && captureId != initialHistory.operationToken))
        }
        fun requireSame(other: Control) {
            requireDrain(epoch == other.epoch && sequence == other.sequence && cutoff == other.cutoff && captureId == other.captureId &&
                captureFence == other.captureFence && capturedAt == other.capturedAt && previousSealEpoch == other.previousSealEpoch && historyHash == other.historyHash)
            requireSameHistory(other)
        }
        fun requireSameHistory(other: Control) {
            requireDrain((initialHistory == null) == (other.initialHistory == null))
            initialHistory?.requireSame(other.initialHistory)
        }
        fun ordinarySeals(final: TestTerminalSealRefV1): List<TestTerminalSealRefV1> = (initialHistory?.records?.map { it.reference } ?: emptyList()) + final
    }

    class Run(row: ResultSet, original: TestRunOrdinaryDrainV1) {
        val sealedAt: Instant = checkNotNull(row.getTimestamp("sealed_at")).toInstant()
        val installationLimit = row.getLong("installation_limit")
        val enrolledCount = row.getLong("enrolled_count")
        val reserve = vector(row, "original_reserve")
        val unused = vector(row, "unused_reserve")
        val progressBytes: ByteArray? = row.getBytes("progress_bytes")
        val progressHash: ByteArray? = row.getBytes("progress_hash")
        val progress: TestTerminalProgressV1? = progressBytes?.let { TestTerminalJsonV1(original.routing.journalConfiguration).progress(it) }
        val sealSetBytes: ByteArray? = row.getBytes("seal_set_bytes")
        val sealSetHash: ByteArray? = row.getBytes("seal_set_hash")
        val ordinaryEpoch: Long? = nullableLong(row, "final_ordinary_epoch")
        val reservedTerminalEpoch: Long? = nullableLong(row, "terminal_seal_epoch")
        val sealCount: Long? = nullableLong(row, "generation_seal_count")
        val sealRoot: ByteArray? = row.getBytes("generation_seal_root")
        val plan = TestTerminalAccountingPlanV1(installationLimit, original.maximumVersions)
        init {
            requireDrain(boolean(row, "valid") && reserve == plan.originalUnusedReserve)
            requireDrain(progressBytes == null && progressHash == null || progressBytes != null && progressHash != null &&
                Sha256.hex(progressBytes) == HexFormat.of().formatHex(progressHash) && progress?.context() == original.runContext)
            requireDrain((sealSetBytes == null && sealSetHash == null && ordinaryEpoch == null && reservedTerminalEpoch == null && sealCount == null && sealRoot == null) ||
                (sealSetBytes != null && sealSetHash != null && ordinaryEpoch != null && reservedTerminalEpoch == Math.addExact(ordinaryEpoch, 1L) &&
                    sealCount != null && sealRoot != null && progress != null && Sha256.hex(sealSetBytes) == HexFormat.of().formatHex(sealSetHash)))
        }
        /** Complete disjoint remainder, not an aggregate "fits" check or retrospective payment inference. */
        fun requirePaidRemainder(scanCharge: ComplaintCapacityVector, sidecars: Long) {
            requireDrain(sidecars in 0..1)
            val spent = TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(enrolledCount) + TestTerminalCapacityChargesV1.AUDIT + scanCharge +
                TestTerminalCapacityChargesV1.SIDECAR.scaled(sidecars) +
                (if (progress == null) ComplaintCapacityVector.ZERO else TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA)
            requireDrain(spent.fitsWithin(reserve) && unused == reserve - spent)
        }
    }

    class Scan(row: ResultSet, original: TestRunOrdinaryDrainV1) {
        val id = checkNotNull(row.getObject("scan_id", UUID::class.java))
        val pass = row.getInt("pass")
        val fence = row.getLong("fencing_token")
        val state: String = checkNotNull(row.getString("state"))
        val count = row.getLong("entry_count")
        val framedBytes = row.getLong("entry_bytes")
        val startedAt = checkNotNull(row.getTimestamp("started_at")).toInstant()
        val finishedAt = row.getTimestamp("finished_at")?.toInstant()
        val root: String? = row.getBytes("manifest_hash")?.let { HexFormat.of().formatHex(it) }
        init {
            val declaration = original.routing.journalConfiguration.declaration()
            requireDrain(id.version() == 4 && id.variant() == 2 && pass in 1..2 && fence in 1..original.leaseToken &&
                row.getObject("data_scope_id", UUID::class.java) == original.scope && boolean(row, "test_only") &&
                row.getObject("restore_identity", UUID::class.java).toString() == declaration.writer.restoreIdentity &&
                row.getLong("desired_generation") == original.registration.process.desiredGeneration &&
                row.getObject("writer_generation", UUID::class.java).toString() == original.writer && row.getLong("cutoff_epoch") == original.cutoff &&
                row.getLong("maximum_entries") == original.maximumVersions && row.getLong("maximum_bytes") == original.maximumFramedBytes &&
                count in 0..original.maximumVersions && framedBytes in 0..original.maximumFramedBytes &&
                startedAt.epochSecond >= 0 && (finishedAt == null || !finishedAt.isBefore(startedAt)))
            requireDrain((state == "SCANNING" && root == null && finishedAt == null) ||
                (state == "COMPLETE" && root?.matches(HASH) == true && finishedAt != null) ||
                (state == "ABANDONED" && root == null && finishedAt != null))
        }
    }

    data class Entry(
        val key: String, val version: String, val ciphertext: String, val semantic: String, val eventId: String,
        val epoch: Long, val ciphertextBytes: Long, val replay: String, val kind: String,
    ) {
        val locator: Pair<String, String> get() = key to version
        fun fields(): List<String> = listOf(key, version, ciphertext)
        fun framedBytes(): Long = EpochSealFramesV1.frame(fields()).let { try { it.size.toLong() } finally { it.fill(0) } }
        fun requireSame(other: Entry) = requireDrain(copy(replay = "PENDING") == other.copy(replay = "PENDING"))
        companion object {
            fun observed(original: TestRunOrdinaryDrainV1, read: TestOrdinaryInventoryReadbackV1): Entry {
                original.requireInventoryEvent(read.event)
                return Entry(read.event.route.objectKey, requireJournalVersion(read.versionId), read.wireSha256, read.event.semanticSha256,
                    read.event.route.eventId, read.event.comparison.epoch, read.ciphertextByteCount, "PENDING", read.event.comparison.eventKind.name).also { it.requireBound(original) }
            }
            fun read(row: ResultSet, original: TestRunOrdinaryDrainV1): Entry {
                requireDrain(row.getObject("data_scope_id", UUID::class.java) == original.scope && boolean(row, "test_only") &&
                    row.getObject("writer_generation", UUID::class.java).toString() == original.writer)
                return Entry(checkNotNull(row.getString("object_key")), requireJournalVersion(row.getString("object_version")),
                    hash(row, "ciphertext_hash"), hash(row, "semantic_hash"), checkNotNull(row.getString("event_id")),
                    row.getLong("journal_epoch"), row.getLong("entry_bytes"), checkNotNull(row.getString("replay_state")),
                    checkNotNull(row.getString("event_kind"))).also { it.requireBound(original) }
            }
        }
        private fun requireBound(original: TestRunOrdinaryDrainV1) {
            val journal = original.routing.journalConfiguration
            original.requireInventoryKind(kind)
            requireDrain(epoch in 1..original.cutoff && ciphertextBytes in 1..journal.declaration().limits.decoder.maximumEnvelopeBytes.toLong() &&
                ciphertext.matches(HASH) && semantic.matches(HASH) && replay in setOf("PENDING", "APPLIED"))
            EpochSealFramesV1.opaque(eventId)
            requireDrain(journal.declaration().routing.keys.any { retained ->
                val prefix = "${journal.ordinaryPrefix}writer/${original.writer}/epoch/${epoch.toString().padStart(19, '0')}/${retained.keyId}/"
                key.startsWith(prefix) && key.removePrefix(prefix).matches(Regex("[A-Za-z0-9_-]{43}"))
            })
            // Object-key and event-id HMACs use distinct domains. Their association is authenticated
            // by the native codec, never by equating the two opaque digests in a SQL DTO.
            EpochSealFramesV1.opaque(key.substringAfterLast('/'))
        }
    }

    /** Strict terminal reader deliberately separate from the unchanged RESERVED/PARTIAL lower reader. */
    class Recovery(row: ResultSet, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts, eventId: String, kind: String,
        event: me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1? = null) {
        constructor(row: ResultSet, original: TestRunOrdinaryDrainV1, eventId: String, kind: String) :
            this(row, TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, original.cutoff), eventId, kind) {
            original.requireInventoryKind(kind)
        }
        val state = checkNotNull(row.getString("state"))
        val promise = vector(row, "reserved_amounts")
        val used = vector(row, "converted_amounts")
        val lastAppliedAt = checkNotNull(row.getTimestamp("converted_at")).toInstant()
        val remaining get() = promise - used
        init {
            facts.requireKind(kind)
            val all = kind == "OWNER_DELETE_ALL"
            val batch = kind == "ADMIN_BATCH_DELETE"
            val batchEvent = if (batch) checkNotNull(event).also {
                facts.requireEvent(it); requireDrain(it.route.eventId == eventId && it.comparison.eventKind.name == kind)
            } else null
            val ownerCount = batchEvent?.adminBatchTuple?.ownerInstallationIds()?.size ?: 1
            val targetCount = batchEvent?.complaintIds()?.size ?: if (all) 100 else 1
            val expected = if (batchEvent != null) me.manga.kira.backend.complaint.infrastructure.AdminDeleteRows.recovery(batchEvent)
                else if (all) OwnerDeleteAllCapacityCharges.RECOVERY else OwnerDeleteCapacityCharges.RECOVERY
            requireDrain(row.getString("event_id") == eventId && row.getString("publication_ref") == eventId &&
                row.getObject("data_scope_id", UUID::class.java) == facts.scope && boolean(row, "test_only") && boolean(row, "finite") &&
                row.getInt("accounting_version") == 1 && state in setOf("PARTIAL", "CONVERTED") &&
                promise == expected &&
                used.fitsWithin(promise) && !used.isZero())
            val installs = used[ComplaintCapacityCounter.INSTALLATION_IDS]
            val resources = used[ComplaintCapacityCounter.RESOURCE_IDS]
            val audits = used[ComplaintCapacityCounter.AUDIT_ROWS]
            val applied = used[ComplaintCapacityCounter.JOURNAL_APPLIED]
            requireDrain(installs in 0..ownerCount.toLong() && resources in 0..targetCount.toLong() && audits in 0..(if (all) 113L else targetCount + 4L) && applied in 1..4 &&
                used == ComplaintCapacityCharges.INSTALLATION_ID.scaled(installs) + ComplaintCapacityCharges.RESOURCE_ID.scaled(resources) +
                    ComplaintCapacityCharges.AUDIT.scaled(audits) +
                    (if (all) OwnerDeleteAllCapacityCharges.APPLIED else OwnerDeleteCapacityCharges.APPLIED).scaled(applied))
        }
    }

    class Fold(private val original: TestRunOrdinaryDrainV1, expected: Long) {
        private val hash = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        private var ciphertextBytes = 0L
        private var previous: Pair<String, String>? = null
        private val expectedCount = expected
        private var framedBytes = EpochSealFramesV1.update(hash, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
            original.routing.journalConfiguration.ordinaryPrefix, "TEST", original.scope.toString(), "1", original.cutoff.toString(), expected.toString()))
        private val prefixBytes = framedBytes
        init { requireDrain(expected in 0..original.maximumVersions && framedBytes <= original.maximumFramedBytes) }
        fun entry(entry: Entry) {
            requireDrain(count < expectedCount && previous?.let { compare(it, entry.locator) < 0 } != false)
            framedBytes = Math.addExact(framedBytes, EpochSealFramesV1.update(hash, entry.fields()))
            ciphertextBytes = Math.addExact(ciphertextBytes, entry.ciphertextBytes)
            requireDrain(framedBytes <= original.maximumFramedBytes && ciphertextBytes <= original.maximumCiphertextBytes)
            count++; previous = entry.locator
        }
        fun finish(start: Instant, end: Instant): Summary {
            requireDrain(count == expectedCount && !end.isBefore(start))
            return Summary(TestTerminalInventoryWitnessV1(start.epochSecond, end.epochSecond, count, ciphertextBytes,
                HexFormat.of().formatHex(hash.digest())), framedBytes, framedBytes - prefixBytes)
        }
    }
    data class Summary(val witness: TestTerminalInventoryWitnessV1, val framedBytes: Long, val entryFramedBytes: Long)

    fun compare(a: Pair<String, String>, b: Pair<String, String>): Int = a.first.compareTo(b.first).takeIf { it != 0 } ?: compareVersion(a.second, b.second)
    private fun compareVersion(a: String, b: String): Int {
        // Version IDs remain opaque on the wire. Only SQL's deterministic COLLATE "C" fold
        // sorts them; PostgreSQL UTF-8 byte order is not Java's UTF-16 surrogate order.
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(left.size, right.size)) {
            val order = (left[i].toInt() and 255).compareTo(right[i].toInt() and 255)
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }
    fun boolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { requireDrain(!row.wasNull()) }
    fun hash(row: ResultSet, column: String): String = checkNotNull(row.getBytes(column)).let {
        requireDrain(it.size == 32); HexFormat.of().formatHex(it)
    }
    fun vector(row: ResultSet, column: String): ComplaintCapacityVector {
        val sql = checkNotNull(row.getArray(column))
        return try {
            requireDrain(sql.baseType == Types.BIGINT)
            val values = sql.array as? Array<*> ?: throw TestOrdinaryDrainExceptionV1()
            requireDrain(values.size == ComplaintCapacityEncoding.WIDTH)
            ComplaintCapacityVector.of(LongArray(values.size) { checkNotNull(values[it] as? Long) })
        } finally { sql.free() }
    }
    private fun nullableLong(row: ResultSet, column: String): Long? = row.getLong(column).let { if (row.wasNull()) null else it }
    private val HASH = Regex("[0-9a-f]{64}")
}
