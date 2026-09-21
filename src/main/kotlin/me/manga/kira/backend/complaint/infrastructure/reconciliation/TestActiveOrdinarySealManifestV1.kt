package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import java.security.MessageDigest
import java.util.HexFormat

/** Local expected-set closure only. Immutable content/proof, not four imaginary terminal receipt xmins. */
internal class TestActiveOrdinarySealManifestV1 private constructor(val count: Long, val sha256: String, val framedBytes: Long, val fingerprint: String) {
    internal class Builder(private val routing: TestOwnerDeleteJournalRoutingV1, private val cutoff: Long, private val start: Long = 1) {
        private val limit = routing.journalConfiguration.declaration().limits.capacity
        private val first = MessageDigest.getInstance("SHA-256")
        private val second = MessageDigest.getInstance("SHA-256")
        private val manifest = MessageDigest.getInstance("SHA-256")
        private var previous: String? = null
        private var recurrentPrevious: Pair<String, String>? = null
        private var count = 0L
        private var repeated = 0L
        private var bytes = 0L
        private var repeatedBytes = 0L
        private var total = 0L
        private var fingerprint: String? = null
        private var pass = 0
        private var poisoned = false

        init { requireActiveSeal(start in 1..cutoff) }

        fun entry(row: TestActiveCutoffPublicationRowV1) = guarded {
            requireActiveSeal(pass in 0..1 && row.epoch in start..cutoff && previous?.let { it < row.objectKey } != false)
            val proof = checkNotNull(row.proof)
            val fields = listOf(row.objectKey, proof.version, proof.ciphertext)
            if (pass == 0) {
                requireActiveSeal(count < limit.maximumRetainedVersions)
                val frame = EpochSealFramesV1.frame(fields)
                try { bytes = add(bytes, frame.size.toLong()) } finally { frame.fill(0) }
                EpochSealFramesV1.update(first, fields + row.fingerprint()); count++
            } else {
                requireActiveSeal(repeated < count)
                repeatedBytes = add(repeatedBytes, EpochSealFramesV1.update(manifest, fields))
                EpochSealFramesV1.update(second, fields + row.fingerprint()); repeated++
            }
            previous = row.objectKey
        }
        /** Exact recurrent P/E projection, never a synthetic publication or VERIFY record. */
        fun entry(row: TestActiveRecurrentOperationV1.ManifestEntry) = guarded {
            requireActiveSeal(pass in 0..1 && row.epoch in start..cutoff &&
                recurrentPrevious?.let { TestOrdinaryDrainRowsV1.compare(it, row.locator) < 0 } != false)
            val fields = listOf(row.locator.first, row.locator.second, row.wire)
            val physical = row.fingerprint()
            if (pass == 0) {
                requireActiveSeal(count < limit.maximumRetainedVersions)
                val frame = EpochSealFramesV1.frame(fields)
                try { bytes = add(bytes, frame.size.toLong()) } finally { frame.fill(0) }
                EpochSealFramesV1.update(first, fields + physical); count++
            } else {
                requireActiveSeal(repeated < count)
                repeatedBytes = add(repeatedBytes, EpochSealFramesV1.update(manifest, fields))
                EpochSealFramesV1.update(second, fields + physical); repeated++
            }
            recurrentPrevious = row.locator
        }
        fun beginSecond() = guarded {
            requireActiveSeal(pass == 0)
            fingerprint = HexFormat.of().formatHex(first.digest())
            val journal = routing.journalConfiguration
            total = add(bytes, EpochSealFramesV1.update(manifest, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", journal.declaration().writer.generationId,
                journal.ordinaryPrefix, "TEST", journal.scope.id.toString(), start.toString(), cutoff.toString(), count.toString())))
            pass = 1; previous = null; recurrentPrevious = null
        }
        fun finish(): TestActiveOrdinarySealManifestV1 = guarded {
            requireActiveSeal(pass == 1 && count == repeated && bytes == repeatedBytes && fingerprint == HexFormat.of().formatHex(second.digest()))
            pass = 2
            TestActiveOrdinarySealManifestV1(count, HexFormat.of().formatHex(manifest.digest()), total, checkNotNull(fingerprint))
        }
        private fun add(prior: Long, next: Long): Long {
            requireActiveSeal(next >= 0 && prior >= 0 && next <= limit.maximumScanStagingBytes - prior)
            return prior + next
        }
        private fun <T> guarded(body: () -> T): T {
            requireActiveSeal(!poisoned); poisoned = true
            val value = body(); poisoned = false; return value
        }
    }
}
