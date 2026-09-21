package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1

/** Actual nonempty PREPARED resolution, followed by two complete local expected-set passes. */
internal class TestActiveCutoffPublicationsV1(private val original: TestActiveOrdinarySealV1) {
    fun resolve(): TestActiveOrdinarySealManifestV1 {
        requireConnectionFree(); original.requireCutoffRunning()
        val factory = original.cutoffRecipe.publisher(original)
        try {
            var after = 0L to ""
            var count = 0L
            while (true) {
                original.renew()
                val page = original.epochPage(after)
                try {
                    if (page.rows.isEmpty()) break
                    page.rows.forEach { row ->
                        requireActiveSeal(count < original.routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions)
                        requireActiveSeal(row.epoch > after.first || row.epoch == after.first && row.eventId > after.second)
                        val event = row.event(original) // Strict canonical+route restoration before any provider dispatch.
                        if (row.state == "PREPARED") {
                            original.renew()
                            val work = ReleasedTestActiveCutoffPublicationV1.fromPage(page, row, event)
                            try {
                                factory.reserveCutoff().use { it.publishCutoff(work) }
                                // Historical evidence-only CAS retains M/resource/session guards, not leadership/APPLY.
                                original.persistObservation(work)
                            } finally { work.close() }
                        } else row.requireProof(event, original.routing)
                        after = row.epoch to row.eventId; count++
                        original.requireCutoffRunning()
                    }
                } finally { page.closeRows() }
            }
        } finally { factory.close() }
        val builder = TestActiveOrdinarySealManifestV1.Builder(original.routing, original.cutoff)
        repeat(2) { pass ->
            var after = ""
            while (true) {
                original.renew()
                val page = original.keyPage(after)
                try {
                    if (page.rows.isEmpty()) break
                    page.rows.forEach { row ->
                        requireActiveSeal(row.objectKey > after && row.objectKey >= original.lowerCutoffKey && row.objectKey < original.upperCutoffKey)
                        val event = row.event(original)
                        row.requireProof(event, original.routing)
                        builder.entry(row)
                        after = row.objectKey
                    }
                } finally { page.closeRows() }
            }
            if (pass == 0) builder.beginSecond()
        }
        original.requireCutoffRunning()
        return builder.finish()
    }
}

/** Only the original fixed known-committed/released page issues this work. No request receipt or APPLY conversion. */
internal class ReleasedTestActiveCutoffPublicationV1 private constructor(
    private val page: TestActiveOrdinarySealOperationV1,
    internal val row: TestActiveCutoffPublicationRowV1,
    internal val event: TestOwnerDeleteJournalEventV1,
) : AutoCloseable {
    private val original = page.original
    private var lane: JournalPublicationLanesV1.TestOwnerDeleteReservation? = null
    private var attempt: TestOwnerDeleteCodecAttemptV1? = null
    private var observed: TestOwnerDeleteJournalReadbackV1? = null
    private var bytes: ByteArray? = null
    private var closed = false
    internal fun requireOriginal(selected: TestActiveOrdinarySealV1) {
        requireActiveSeal(selected === original && !closed)
        page.requirePreparedRow(row)
    }
    internal fun retainPublication(selected: JournalPublicationLanesV1.TestOwnerDeleteReservation, time: TestOwnerDeleteCodecAttemptV1) {
        requireConnectionFree(); requireOriginal(original); original.requireCutoffRunning()
        requireActiveSeal(lane == null && attempt == null && observed == null)
        lane = selected; attempt = time
    }
    internal fun requirePublication(routing: TestOwnerDeleteJournalRoutingV1, actual: TestOwnerDeleteJournalEventV1, time: TestOwnerDeleteCodecAttemptV1) {
        requireConnectionFree(); requireOriginal(original); original.requireCutoffRunning()
        requireActiveSeal(routing === original.routing && actual === event && time === attempt && lane != null && observed == null)
        time.remainingMillis(1)
    }
    internal fun retainReleasedObservation(selected: JournalPublicationLanesV1.TestOwnerDeleteReservation, time: TestOwnerDeleteCodecAttemptV1,
        readback: TestOwnerDeleteJournalReadbackV1) {
        requireConnectionFree(); requireOriginal(original)
        requireActiveSeal(lane === selected && attempt === time && observed == null && readback.event === event)
        selected.requireReleasedCutoff(this, time)
        time.remainingMillis(1)
        val canonical = when (event.comparison.eventKind) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> OwnerDeleteAllVerificationCodecV1(OwnerDeleteAllJournalBindingV1(original.routing)).let { it.canonicalBytes(it.observed(readback)) }
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> TestOwnerDeleteVerificationCodecV1(original.routing).let { it.canonicalBytes(it.observed(readback)) }
            ComplaintJournalDeletionKindV1.ADMIN_DELETE -> TestOwnerDeleteVerificationCodecV1.forAdmin(original.routing).let { it.canonicalBytes(it.observed(readback)) }
            ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> TestOwnerDeleteVerificationCodecV1.forAdminBatch(original.routing).let { it.canonicalBytes(it.observed(readback)) }
            else -> throw TestActiveOrdinarySealExceptionV1()
        }
        observed = readback; bytes = canonical
    }
    internal fun evidence(): TestOwnerDeleteJournalReadbackV1 {
        requireActiveSeal(!closed)
        original.requireEvidenceRunning()
        checkNotNull(lane).requireReleasedCutoff(this, checkNotNull(attempt))
        return checkNotNull(observed)
    }
    internal fun verificationBytes(): ByteArray { evidence(); return checkNotNull(bytes).copyOf() }
    internal fun verificationHash(): ByteArray { evidence(); return TestActiveOrdinarySealRowsV1.hex(Sha256.hex(checkNotNull(bytes))) }
    override fun close() { closed = true; bytes?.fill(0) }
    override fun toString(): String = "ReleasedTestActiveCutoffPublicationV1(original-page,receiptless,redacted)"
    companion object {
        internal fun fromPage(page: TestActiveOrdinarySealOperationV1, row: TestActiveCutoffPublicationRowV1,
            event: TestOwnerDeleteJournalEventV1): ReleasedTestActiveCutoffPublicationV1 {
            requireConnectionFree(); page.requirePreparedRow(row)
            val expected = row.event(page.original)
            val actualBytes = event.canonicalBytes(); val expectedBytes = expected.canonicalBytes()
            try { requireActiveSeal(event.belongsTo(page.original.routing) && event.route == expected.route && actualBytes.contentEquals(expectedBytes)) }
            finally { actualBytes.fill(0); expectedBytes.fill(0) }
            return ReleasedTestActiveCutoffPublicationV1(page, row, event)
        }
    }
}
