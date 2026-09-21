package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
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

    companion object {
        /** Separate recurrent dispatch; it cannot claim the initial original's page/provenance. */
        internal fun resolve(original: TestActiveRecurrentV1): TestActiveOrdinarySealManifestV1 {
            requireConnectionFree(); original.requireCutoffRunning()
            val factory = original.cutoffRecipe.publisher(original)
            try {
                var after = (original.epochStart - 1) to ""
                var count = 0L
                while (true) {
                    original.renew()
                    val page = original.epochPage(after)
                    try {
                        if (page.rows.isEmpty()) break
                        page.rows.forEach { row ->
                            requireActiveSeal(count < original.maximumEntries &&
                                (row.epoch > after.first || row.epoch == after.first && row.eventId > after.second))
                            val event = row.event(original)
                            if (row.state == "PREPARED") {
                                original.renew()
                                val work = ReleasedTestActiveCutoffPublicationV1.fromPage(page, row, event)
                                try {
                                    factory.reserveCutoff().use { it.publishCutoff(work) }
                                    original.persistObservation(work)
                                } finally { work.close() }
                            } else row.requireProof(event, original.routing)
                            after = row.epoch to row.eventId; count++
                            original.requireCutoffRunning()
                        }
                    } finally { page.closeRows() }
                }
            } finally { factory.close() }
            val builder = TestActiveOrdinarySealManifestV1.Builder(original.routing, original.cutoff, original.epochStart)
            repeat(2) { pass ->
                var after: Pair<String, String>? = null
                while (true) {
                    original.renew()
                    val page = original.keyPage(after)
                    try {
                        if (page.manifestRows.isEmpty()) break
                        page.manifestRows.forEach { row ->
                            requireActiveSeal(after?.let { TestOrdinaryDrainRowsV1.compare(it, row.locator) < 0 } != false)
                            row.requireOriginal(original)
                            builder.entry(row); after = row.locator
                        }
                    } finally { page.closeRows() }
                }
                if (pass == 0) builder.beginSecond()
            }
            original.requireCutoffRunning()
            return builder.finish()
        }
    }
}

/** Only the original fixed known-committed/released page issues this work. No request receipt or APPLY conversion. */
internal class ReleasedTestActiveCutoffPublicationV1 private constructor(
    private val first: TestActiveOrdinarySealOperationV1?,
    private val recurrent: TestActiveRecurrentOperationV1?,
    internal val row: TestActiveCutoffPublicationRowV1,
    internal val event: TestOwnerDeleteJournalEventV1,
) : AutoCloseable {
    private val routing = first?.original?.routing ?: checkNotNull(recurrent).original.routing
    init { requireActiveSeal((first == null) != (recurrent == null)) }
    private var lane: JournalPublicationLanesV1.TestOwnerDeleteReservation? = null
    private var attempt: TestOwnerDeleteCodecAttemptV1? = null
    private var observed: TestOwnerDeleteJournalReadbackV1? = null
    private var bytes: ByteArray? = null
    private var closed = false
    internal fun requireOriginal(selected: TestActiveOrdinarySealV1) {
        requireActiveSeal(selected === first?.original && recurrent == null && !closed)
        checkNotNull(first).requirePreparedRow(row)
    }
    internal fun requireOriginal(selected: TestActiveRecurrentV1) {
        requireActiveSeal(selected === recurrent?.original && first == null && !closed)
        checkNotNull(recurrent).requirePreparedRow(row)
    }
    private fun requirePage() {
        if (first != null) requireOriginal(first.original) else requireOriginal(checkNotNull(recurrent).original)
    }
    private fun requireCutoff() {
        if (first != null) first.original.requireCutoffRunning() else checkNotNull(recurrent).original.requireCutoffRunning()
    }
    internal fun retainPublication(selected: JournalPublicationLanesV1.TestOwnerDeleteReservation, time: TestOwnerDeleteCodecAttemptV1) {
        requireConnectionFree(); requirePage(); requireCutoff()
        requireActiveSeal(lane == null && attempt == null && observed == null)
        lane = selected; attempt = time
    }
    internal fun requirePublication(routing: TestOwnerDeleteJournalRoutingV1, actual: TestOwnerDeleteJournalEventV1, time: TestOwnerDeleteCodecAttemptV1) {
        requireConnectionFree(); requirePage(); requireCutoff()
        requireActiveSeal(routing === this.routing && actual === event && time === attempt && lane != null && observed == null)
        time.remainingMillis(1)
    }
    internal fun retainReleasedObservation(selected: JournalPublicationLanesV1.TestOwnerDeleteReservation, time: TestOwnerDeleteCodecAttemptV1,
        readback: TestOwnerDeleteJournalReadbackV1) {
        requireConnectionFree(); requirePage()
        requireActiveSeal(lane === selected && attempt === time && observed == null && readback.event === event)
        selected.requireReleasedCutoff(this, time)
        time.remainingMillis(1)
        val canonical = when (event.comparison.eventKind) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> OwnerDeleteAllVerificationCodecV1(OwnerDeleteAllJournalBindingV1(routing)).let { it.canonicalBytes(it.observed(readback)) }
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> TestOwnerDeleteVerificationCodecV1(routing).let { it.canonicalBytes(it.observed(readback)) }
            ComplaintJournalDeletionKindV1.ADMIN_DELETE -> TestOwnerDeleteVerificationCodecV1.forAdmin(routing).let { it.canonicalBytes(it.observed(readback)) }
            ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> TestOwnerDeleteVerificationCodecV1.forAdminBatch(routing).let { it.canonicalBytes(it.observed(readback)) }
            else -> throw TestActiveOrdinarySealExceptionV1()
        }
        observed = readback; bytes = canonical
    }
    internal fun evidence(): TestOwnerDeleteJournalReadbackV1 {
        requireActiveSeal(!closed)
        if (first != null) first.original.requireEvidenceRunning() else checkNotNull(recurrent).original.requireEvidenceRunning()
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
            return ReleasedTestActiveCutoffPublicationV1(page, null, row, event)
        }
        internal fun fromPage(page: TestActiveRecurrentOperationV1, row: TestActiveCutoffPublicationRowV1,
            event: TestOwnerDeleteJournalEventV1): ReleasedTestActiveCutoffPublicationV1 {
            requireConnectionFree(); page.requirePreparedRow(row)
            val expected = row.event(page.original)
            val actualBytes = event.canonicalBytes(); val expectedBytes = expected.canonicalBytes()
            try { requireActiveSeal(event.belongsTo(page.original.routing) && event.route == expected.route && actualBytes.contentEquals(expectedBytes)) }
            finally { actualBytes.fill(0); expectedBytes.fill(0) }
            return ReleasedTestActiveCutoffPublicationV1(null, page, row, event)
        }
    }
}
