package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Real retained factory is an input to synthetic own-project scope/cell cuts, never permission to call its public bridge directly. */
internal object PgBoundRotationModelCases {
    fun verify(actual: PersistencePhysicalEntry) {
        PgConstructionEvidenceModelCases.verify(actual)
        val image = requireNotNull(actual.driverOpening?.image)
        val factory = pgCapturedFactory(actual)
        originChecks(actual)
        for (install in listOf(false, true)) {
            for (mode in listOf("RETIRE", "STOP", "SEALED", "CANCEL", "DETACHED", "UNKNOWN", "STALE", "SCOPE")) {
                admissionCut(image, factory, install, mode)
            }
            for (kind in listOf("G", "T")) contentionCut(image, factory, install, kind)
        }
        for (kind in listOf("F", "G", "T")) preheldLock(image, factory, kind)
        successfulReplacement(image, factory)
        println("PG_BOUND_ROTATION_MODEL cuts=16 contention=4 preheld=3 exact_identity=true consumed_refusals=true proof=MODEL_REAL_CUT_CODE")
    }

    private fun admissionCut(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory, install: Boolean, mode: String) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            fixture.open().close()
            val predecessor = pgRotationEntry(fixture.entry, PersistenceTransportRole.AUX_CANCEL)
            check(fixture.owner.completeAuxiliaryClose(requireNotNull(predecessor.auxiliaryCloseReceipt))) // MODEL authenticated body receipt.
            val next = fixture.prepare()
            if (install) check(fixture.cut(next, install = false) == null)
            val originalScope = fixture.entry.driverScope
            try {
                invalidate(fixture, mode)
                val expected = if (mode == "STALE" || mode == "SCOPE") PersistenceTransportRefusal.INVALID_CONSTRUCTION else PersistenceTransportRefusal.SEALED
                val refusal = if (install) fixture.cut(next, install = true) else next.reserve()
                check(refusal === expected)
                // The private cut deliberately omits the outer invocation. Enter that real one-use wrapper to consume its still-prepared phase.
                check(
                    next.reserve() === PersistenceTransportRefusal.INVALID_CONSTRUCTION || fixture.invocation(next) === PersistenceTransportInvocation.REFUSED,
                )
                check(fixture.invocation(next) === PersistenceTransportInvocation.REFUSED)
                check(next.construct() is PersistenceTransportCreation.Refused)
                check(pgRotationEntry(fixture.entry, PersistenceTransportRole.AUX_CANCEL) === predecessor)
            } finally {
                fixture.physical.ledger.entries[fixture.entry.record.slotHint] = fixture.entry
                PersistencePhysicalEntry::class.java.getDeclaredField("driverScope").also { it.isAccessible = true }.set(fixture.entry, originalScope)
            }
        }
    }

    private fun invalidate(fixture: PgBoundRotationModelFixture, mode: String) {
        when (mode) {
            "RETIRE" -> fixture.entry.retirementRequested.set(true)
            "STOP" -> fixture.physical.requestOwnedStop()
            "SEALED" -> fixture.physical.ledger.sealed = true
            "CANCEL" -> requireNotNull(fixture.entry.attempt).abandon(PersistenceFactoryFailure.TIMEOUT)
            "DETACHED" -> fixture.control.fail(PersistenceFactoryFailure.TIMEOUT)
            "UNKNOWN" -> fixture.entry.unknown = true
            "STALE" -> fixture.physical.ledger.entries[fixture.entry.record.slotHint] = null
            "SCOPE" -> PersistencePhysicalEntry::class.java.getDeclaredField("driverScope").also { it.isAccessible = true }.set(fixture.entry, null)
            else -> error("Unknown synthetic physical cut.")
        }
    }

    private fun contentionCut(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory, install: Boolean, kind: String) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            val next = fixture.prepare()
            if (install) check(fixture.cut(next, install = false) == null)
            val lock = if (kind == "G") fixture.physical.ledger.lock else fixture.transportLock()
            PgBoundModelLockHolder(lock).use { held ->
                held.start()
                val refusal = if (install) fixture.cut(next, install = true) else next.reserve()
                check(refusal === PersistenceTransportRefusal.CONTENDED)
                check(!lock.hasQueuedThread(Thread.currentThread()))
            }
            if (install) check(next.reserve() === PersistenceTransportRefusal.INVALID_CONSTRUCTION)
            check(fixture.invocation(next) === PersistenceTransportInvocation.REFUSED)
            check(next.construct() is PersistenceTransportCreation.Refused)
            check(pgRetainedSockets(fixture.entry).isEmpty())
        }
    }

    private fun preheldLock(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory, kind: String) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            val previous = fixture.open(PersistenceTransportRole.PRIMARY)
            val next = fixture.prepare(PersistenceTransportRole.PRIMARY)
            val lock = when (kind) {
                "F" -> fixture.physical.rendezvous.lock
                "G" -> fixture.physical.ledger.lock
                else -> fixture.transportLock()
            }
            lock.withLock {
                check(next.reserve() === PersistenceTransportRefusal.INVALID_CONSTRUCTION)
                check(!previous.isClosed)
            }
            check(fixture.invocation(next) === PersistenceTransportInvocation.REFUSED)
            check(next.reserve() === PersistenceTransportRefusal.INVALID_CONSTRUCTION)
            check(next.construct() is PersistenceTransportCreation.Refused)
            check(!pgRotationEntry(fixture.entry, PersistenceTransportRole.PRIMARY).extentEnded)
        }
    }

    private fun successfulReplacement(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            val primary = fixture.open(PersistenceTransportRole.PRIMARY)
            val nextPrimary = fixture.open(PersistenceTransportRole.PRIMARY)
            check(primary.isClosed && primary !== nextPrimary && !nextPrimary.isClosed)
            // Later captured-factory AUX must survive TAKEN/SETTLED/scope removal, not require opening-only admission.
            check(fixture.control.take())
            fixture.entry.opening = PersistencePhysicalOpeningPhase.SETTLED
            fixture.entry.scopeEnded = true
            fixture.scope.extentSource.primaryOpeningEnded.set(true)
            val auxiliary = fixture.open()
            auxiliary.close()
            val old = pgRotationEntry(fixture.entry, PersistenceTransportRole.AUX_CANCEL)
            check(fixture.owner.completeAuxiliaryClose(requireNotNull(old.auxiliaryCloseReceipt)))
            val next = fixture.open()
            check(next !== auxiliary && !next.isClosed)
            check(pgRetainedSockets(fixture.entry).size == 2)
            check(!fixture.owner.completeAuxiliaryClose(requireNotNull(old.auxiliaryCloseReceipt)))
        }
    }

    private fun originChecks(entry: PersistencePhysicalEntry) {
        val socket = pgRotationEntry(entry, PersistenceTransportRole.AUX_CANCEL).raw.get() as TrackedPersistenceSocket
        val origin = pgRotationOrigin(socket)
        check(origin.isCurrentAllocation()) // Bookkeeping association only: the direct current-stack predicate must still refuse here.
        check(!origin.isDirectAuxiliaryClose())
        val wrongThread = Thread.ofPlatform().unstarted { }
        val otherScope =
            PersistencePgFactoryScope(
                origin.image,
                PersistencePhysicalFactoryBinding(1, java.util.concurrent.atomic.AtomicBoolean()),
                requireNotNull(entry.transports),
            )
        val otherImage = PersistencePgDriverImage.prepare(origin.image.driver)
        val source = origin.scope.extentSource
        val variants = listOf(
            PersistencePgTransportOrigin(otherScope, origin.factory, origin.image, origin.extent, Thread.currentThread()),
            PersistencePgTransportOrigin(origin.scope, origin.factory, otherImage, origin.extent, Thread.currentThread()),
            PersistencePgTransportOrigin(
                origin.scope,
                origin.factory,
                origin.image,
                PersistenceTransportExtent(PersistenceTransportExtentSource(), origin.extent.role),
                Thread.currentThread(),
            ),
            PersistencePgTransportOrigin(origin.scope, origin.factory, origin.image, origin.extent, wrongThread),
            PersistencePgTransportOrigin(
                origin.scope,
                origin.factory,
                origin.image,
                PersistenceTransportExtent(source, PersistenceTransportRole.PRIMARY),
                Thread.currentThread(),
            ),
        )
        check(variants.none { it.isCurrentAllocation() || it.isDirectAuxiliaryClose() })
        val captured = PersistencePgFactoryScope::class.java.getDeclaredField("captured").also { it.isAccessible = true }
        val original = captured.get(origin.scope)
        try {
            captured.set(origin.scope, AtomicReference<TrackedPgSocketFactory?>())
            check(!origin.isCurrentAllocation() && !origin.isDirectAuxiliaryClose())
        } finally {
            captured.set(origin.scope, original)
        }
        val binding = TrackedPersistenceSocket::class.java.getDeclaredField("binding").also {
            it.isAccessible = true
        }.get(socket) as PersistenceTransportBinding
        check(binding.captureAuxiliaryClose(socket) == null)
        val primary = pgRotationEntry(entry, PersistenceTransportRole.PRIMARY).raw.get() as TrackedPersistenceSocket
        check(!primary.hasBinding(binding) && binding.captureAuxiliaryClose(primary) == null)
    }
}
