package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual invocation exits, not success receipts; retained before factory dispatch. */
internal class PersistenceOpeningFacts {
    val driverEntered = AtomicBoolean()
    val driverEnded = AtomicBoolean()
    val factoryEntered = AtomicBoolean()
    val factoryEnded = AtomicBoolean()
    val awaitingResourcePhase = AtomicBoolean()
    val fatal = AtomicBoolean()
    val outcome = AtomicReference<PersistencePhysicalOpening?>()
    val scopeCallEnded = AtomicBoolean()
    private val firstFailure = AtomicReference<PersistenceOpeningFailure?>()
    private val primaryConstruction = AtomicReference<PersistenceConstructionEvidence?>()
    private val auxiliaryConstruction = AtomicReference<PersistenceConstructionEvidence?>()

    fun failure(): PersistenceOpeningFailure? = firstFailure.get()

    fun construction(role: PersistenceTransportRole): PersistenceConstructionEvidence? = constructionCell(role).get()

    /** Called outside F/G/T. Direct Driver capture precedes the outer fallback and scope-leave spans. */
    fun recordFailure(site: PersistenceOpeningFailureSite, failure: Throwable): Boolean =
        firstFailure.compareAndSet(null, PersistenceOpeningEvidence.opening(site, failure))

    fun recordConstructionRefusal(role: PersistenceTransportRole, site: PersistenceConstructionSite, refusal: PersistenceTransportRefusal): Boolean =
        constructionCell(role).compareAndSet(null, PersistenceOpeningEvidence.refused(site, refusal))

    fun recordConstructionThrow(role: PersistenceTransportRole, failure: Throwable): Boolean =
        constructionCell(role).compareAndSet(null, PersistenceOpeningEvidence.thrown(failure))

    fun recordConstructionRetained(role: PersistenceTransportRole): Boolean = constructionCell(role).compareAndSet(null, PersistenceOpeningEvidence.retained)

    private fun constructionCell(role: PersistenceTransportRole): AtomicReference<PersistenceConstructionEvidence?> =
        if (role === PersistenceTransportRole.PRIMARY) primaryConstruction else auxiliaryConstruction

    override fun toString(): String = "PersistenceOpeningFacts(redacted)"
}
