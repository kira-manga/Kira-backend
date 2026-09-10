package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.SQLException
import kotlin.concurrent.withLock

/** Existing ROTATE_BOUND_MODEL child only: actual bound cut code, MODEL provenance/fences, real unconnected Socket API. */
internal object PgConstructionEvidenceModelCases {
    fun verify(actual: PersistencePhysicalEntry) {
        val image = requireNotNull(actual.driverOpening?.image)
        val factory = pgCapturedFactory(actual)
        for (role in PersistenceTransportRole.entries) {
            prepareRefusal(image, factory, role)
            installRefusal(image, factory, role)
            returnedRefusal(image, factory, role)
            retainedReturn(image, factory, role)
        }
        for (kind in listOf("F", "G", "T")) preheldCoverage(image, factory, kind)
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            fixture.open(PersistenceTransportRole.PRIMARY)
            fixture.open(PersistenceTransportRole.AUX_CANCEL)
            check(fixture.entry.openingFacts.construction(PersistenceTransportRole.PRIMARY) == null)
            check(fixture.entry.openingFacts.construction(PersistenceTransportRole.AUX_CANCEL) == null)
            // NOT_RECORDED is not a universal success/no-failure claim, even after these normal returns.
        }
    }

    private fun prepareRefusal(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory, role: PersistenceTransportRole) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            val construction = fixture.prepare(role)
            fixture.entry.retirementRequested.set(true)
            check(construction.reserve() === PersistenceTransportRefusal.SEALED)
            val first = refusal(fixture, role, PersistenceConstructionSite.BOUND_PREPARE, PersistenceTransportRefusal.SEALED)
            check(construction.construct() is PersistenceTransportCreation.Refused)
            // MODEL later Driver wrapping must not rewrite the earlier construction record.
            fixture.entry.openingFacts.recordFailure(PersistenceOpeningFailureSite.DRIVER_CONNECT, SQLException())
            check(fixture.entry.openingFacts.construction(role) === first)
            check(fixture.entry.openingFacts.failure()?.type === PersistenceFailureType.SQL)
        }
    }

    private fun installRefusal(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory, role: PersistenceTransportRole) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            val previous = fixture.open(role)
            val entry = pgRotationEntry(fixture.entry, role)
            val call = requireNotNull(fixture.owner.tryBeginCall(entry.record, PersistenceTransportCallKind.BUSINESS))
            try {
                if (role === PersistenceTransportRole.AUX_CANCEL) {
                    previous.close()
                    check(fixture.owner.completeAuxiliaryClose(requireNotNull(entry.auxiliaryCloseReceipt))) // MODEL source body end.
                }
                val next = fixture.prepare(role)
                check(next.reserve() === PersistenceTransportRefusal.FULL) // The independent active call still prevents disposal.
                val first = refusal(fixture, role, PersistenceConstructionSite.BOUND_INSTALL, PersistenceTransportRefusal.FULL)
                check(next.construct() is PersistenceTransportCreation.Refused)
                check(fixture.entry.openingFacts.construction(role) === first)
            } finally {
                check(fixture.owner.completeCall(call))
            }
        }
    }

    private fun returnedRefusal(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory, role: PersistenceTransportRole) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            val construction = fixture.prepare(role)
            val result = construction.construct() as PersistenceTransportCreation.Refused
            check(result.reason === PersistenceTransportRefusal.INVALID_CONSTRUCTION)
            val first = refusal(fixture, role, PersistenceConstructionSite.CONSTRUCTION_SPAN, result.reason)
            check(construction.reserve() == null) // An unreserved construct call did not consume the genuine one-use grant.
            check(construction.construct() is PersistenceTransportCreation.Created)
            check(fixture.entry.openingFacts.construction(role) === first)
        }
    }

    private fun retainedReturn(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory, role: PersistenceTransportRole) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            val construction = fixture.prepare(role)
            check(construction.reserve() == null)
            check(fixture.owner.trySealForRetirement())
            check(construction.construct() is PersistenceTransportCreation.Retained)
            val evidence = requireNotNull(fixture.entry.openingFacts.construction(role))
            check(evidence.site === PersistenceConstructionSite.CONSTRUCTION_SPAN && evidence.disposition === PersistenceConstructionDisposition.RETAINED)
            check(evidence.refusal == null && evidence.type == null)
            check(pgRotationEntry(fixture.entry, role).raw.get() != null) // Retained is not a failed native constructor.
            check(construction.construct() is PersistenceTransportCreation.Refused)
            check(fixture.entry.openingFacts.construction(role) === evidence)
        }
    }

    private fun preheldCoverage(image: PersistencePgDriverImage, factory: TrackedPgSocketFactory, kind: String) {
        PgBoundRotationModelFixture(image, factory).use { fixture ->
            val construction = fixture.prepare(PersistenceTransportRole.PRIMARY)
            val lock = when (kind) {
                "F" -> fixture.physical.rendezvous.lock
                "G" -> fixture.physical.ledger.lock
                else -> fixture.transportLock()
            }
            lock.withLock { check(construction.reserve() === PersistenceTransportRefusal.INVALID_CONSTRUCTION) }
            check(fixture.entry.openingFacts.construction(PersistenceTransportRole.PRIMARY) == null)
        }
    }

    private fun refusal(
        fixture: PgBoundRotationModelFixture,
        role: PersistenceTransportRole,
        site: PersistenceConstructionSite,
        reason: PersistenceTransportRefusal,
    ): PersistenceConstructionEvidence {
        check(!fixture.transports.ownershipLockHeld())
        val evidence = requireNotNull(fixture.entry.openingFacts.construction(role))
        check(evidence.site === site && evidence.disposition === PersistenceConstructionDisposition.REFUSED && evidence.refusal === reason)
        check(evidence.type == null)
        return evidence
    }
}
