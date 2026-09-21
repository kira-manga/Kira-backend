package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1

/**
 * Split2 input seam, NOT a queue delivery, APPLY result, portable proof or authority constructor.
 * Only the original paid scan's exact native GET/KMS/AEAD read, after concrete exchange/key/buffer
 * and client cleanup, can issue it. The native reader retains its private observation identity.
 *
 * A joined reducer must use the named original graph/lease and budget and real existing APPLY
 * owners. Returning this object or a caller Boolean never completes recovery: the producer rereads
 * exact permanent application coverage under its current fenced transaction before proceeding.
 * No generic SQL callback or synthetic delivery conversion is supplied by split1.
 */
internal class TestActiveRecurrentScanRecoveryInputV1 private constructor(
    private val scan: TestActiveRecurrentScanV1,
    private val native: TestOrdinaryInventoryReadbackV1,
) {
    internal val event: TestOwnerDeleteJournalEventV1 get() { requireCurrent(); return native.event }
    internal val versionId: String get() { requireCurrent(); return native.versionId }
    internal val wireSha256: String get() { requireCurrent(); return native.wireSha256 }
    internal val resources: VersionBoundPersistencePools get() { requireCurrent(); return scan.original.process.pools }
    internal val registration: ComplaintTestNamespaceRegistrationV1 get() { requireCurrent(); return scan.original.registration }
    internal val assembly: ComplaintTestProcessAssemblyV1 get() { requireCurrent(); return scan.original.assembly }
    /** Same attempt and real last-renewal window, never a new per-event deadline. */
    internal fun phaseBudget(): PersistenceTimeBudget { requireCurrent(); return scan.original.phaseBudget() }
    internal fun requireCurrentUse(registration: ComplaintTestNamespaceRegistrationV1,
        assembly: ComplaintTestProcessAssemblyV1, routing: TestOwnerDeleteJournalRoutingV1) {
        requireCurrent()
        requireRecurrent(registration === scan.original.registration && assembly === scan.original.assembly && routing === scan.original.routing)
    }
    internal fun requireOriginal(original: TestActiveRecurrentV1) {
        requireCurrent(); requireRecurrent(original === scan.original)
    }
    internal fun readback(): TestOrdinaryInventoryReadbackV1 { requireCurrent(); return native }
    private fun requireCurrent() {
        requireConnectionFree()
        scan.requireRecoveryInput(this, native)
    }
    override fun toString(): String = "TestActiveRecurrentScanRecoveryInputV1(private-native-origin,no-queue-or-apply-authority,redacted)"

    companion object {
        internal fun fromReleasedNative(scan: TestActiveRecurrentScanV1,
            native: TestOrdinaryInventoryReadbackV1): TestActiveRecurrentScanRecoveryInputV1 {
            requireConnectionFree(); scan.requireRecoveryReadback(native)
            return TestActiveRecurrentScanRecoveryInputV1(scan, native)
        }
    }
}
