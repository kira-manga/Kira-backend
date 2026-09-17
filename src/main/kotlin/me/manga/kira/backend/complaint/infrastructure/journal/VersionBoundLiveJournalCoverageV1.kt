package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentProjectedCatalogRefreshV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import java.time.Clock
import java.time.Instant

/**
 * Cold owner retained before D6/G1. It verifies independent declarations, not their installation or
 * all real-world sources. Only a genuine same-process projected refresh can bind logical coverage.
 * G1 absence, V2 ACCEPTED claims and policy hashes are never promoted to backup/restore acceptance.
 */
internal class VersionBoundLiveJournalCoverageV1 private constructor(
    private val routing: VersionBoundComplaintJournalRouting,
    private val reader: VersionBoundCatalogReadbackConfigurationV1,
    private val publicationLanes: JournalPublicationLanesV1,
    val deployment: LiveJournalPolicyDeploymentV1,
    private val clock: Clock,
    private val nanoTime: () -> Long,
) {
    private val journal = routing.journalConfiguration
    private val declaration = journal.declaration()
    private var previousUtc: Instant? = null
    private var previousNanos: Long? = null
    private var clockRefused = false

    init {
        requireConnectionFree()
        deployment.requireJournal(journal, reader)
        requireRetained(routing, reader, publicationLanes)
    }

    /** Fixed identity checks only; safe inside existing locked full-B checks. No clock, copies or provider work. */
    fun requireRetained(
        selectedRouting: VersionBoundComplaintJournalRouting,
        selectedReader: VersionBoundCatalogReadbackConfigurationV1,
        selectedLanes: JournalPublicationLanesV1,
    ) {
        require(routing === selectedRouting && reader === selectedReader && publicationLanes === selectedLanes && routing.journalConfiguration === journal) {
            INVALID_LIVE_JOURNAL_POLICY
        }
        publicationLanes.requireJournal(journal)
    }

    fun bind(process: VersionBoundComplaintProcessConfiguration, catalog: CurrentProjectedCatalogRefreshV1.Result): Binding =
        Binding.fromProjected(this, process, catalog)

    @Synchronized
    private fun sampleUtc(): Instant {
        requireJournalPublication(!clockRefused, JournalPublicationFailureV1.RETENTION_MISMATCH)
        clockRefused = true // A thrown/invalid clock or regression permanently refuses this cold owner's time path.
        val sampled = clock.instant()
        val nanos = nanoTime()
        OrdinaryJournalRetentionV1.requireInstant(sampled, wholeSecond = false)
        requireJournalPublication(
            previousUtc?.let { !sampled.isBefore(it) } != false && previousNanos?.let { nanos - it >= 0 } != false,
            JournalPublicationFailureV1.RETENTION_MISMATCH,
        )
        previousUtc = sampled
        previousNanos = nanos
        clockRefused = false
        return sampled
    }

    override fun toString(): String = "VersionBoundLiveJournalCoverageV1(cold-logical-policy,redacted,no-installation-or-current-authority)"

    /**
     * Retains the actual historical Result, never a supplied inventory/head DTO or mutable global
     * cache. Existing ordinary AUTH/APPLY full-B locking remains mandatory; this performs no lease,
     * desired-state install, current-head advancement, external backup acceptance or seal freeze.
     */
    class Binding private constructor(
        private val owner: VersionBoundLiveJournalCoverageV1,
        private val process: VersionBoundComplaintProcessConfiguration,
        private val result: CurrentProjectedCatalogRefreshV1.Result,
    ) {
        private val catalog = result.catalogFor(process)
        private val inventory = catalog.chain.inventory
        private val retention = owner.declaration.limits.retention
        private val sourceMaximumAges: Map<String, Long>

        init {
            requireBinding(owner.routing, owner.publicationLanes)
            sourceMaximumAges = requireInventory()
            journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) { requiredRestoreFloor(owner.sampleUtc()) }
        }

        internal fun catalogFor(selected: VersionBoundComplaintProcessConfiguration): CatalogCommonHeadEvidence {
            requireConnectionFree()
            requireJournalPublication(selected === process)
            requireProcess()
            return catalog
        }

        internal fun requireBinding(selectedRouting: VersionBoundComplaintJournalRouting, selectedLanes: JournalPublicationLanesV1) {
            requireConnectionFree()
            owner.requireRetained(selectedRouting, checkNotNull(process.catalogReadback), selectedLanes)
            requireProcess()
        }

        internal fun forNewObject(attempt: JournalCodecAttemptV1): Instant = journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
            requireProcess()
            attempt.requireOwner(owner.routing)
            // Sample original J remaining BEFORE UTC. The discarded fractional millisecond is not lost,
            // nor can a later subcall/renewal cap or a freshly started attempt replace this total budget.
            val remainingUpperMillis = attempt.remainingMillis(owner.declaration.limits.deadlines.publicationAttemptMillis).toLong() + 1
            val now = owner.sampleUtc()
            val publicationFloor = now.plusMillis(owner.deployment.utcUncertainty.maximumMillis)
                .plusMillis(remainingUpperMillis)
                .plusMillis(owner.deployment.acceptedRequestLateArrival.maximumMillis)
                .plusSeconds(retention.ordinaryRetentionSeconds)
            OrdinaryJournalRetentionV1.ceilingSecond(maxOf(publicationFloor, requiredRestoreFloor(now)))
        }

        internal fun verify(lastModified: Instant, retainUntil: Instant, requestedRetention: String): Instant =
            journalPublicationCall(JournalPublicationFailureV1.RETENTION_MISMATCH) {
                requireProcess()
                val now = owner.sampleUtc()
                OrdinaryJournalRetentionV1.requireInstant(lastModified, wholeSecond = true)
                OrdinaryJournalRetentionV1.requireInstant(retainUntil, wholeSecond = true)
                val requested = OrdinaryJournalRetentionV1.canonicalInstant(requestedRetention)
                val latestNow = now.plusMillis(owner.deployment.utcUncertainty.maximumMillis)
                OrdinaryJournalRetentionV1.requireInstant(latestNow, wholeSecond = false)
                val minimum = maxOf(lastModified.plusSeconds(retention.ordinaryRetentionSeconds), requested, requiredRestoreFloor(now))
                requireJournalPublication(
                    !lastModified.isAfter(now) && retainUntil.isAfter(latestNow) && !retainUntil.isBefore(minimum) && requested.isAfter(lastModified),
                    JournalPublicationFailureV1.RETENTION_MISMATCH,
                )
                // Original Last-Modified and requested metadata stay immutable even after an actual lock extension.
                now
            }

        private fun requireProcess() {
            requireConnectionFree()
            requireJournalPublication(process.liveCoverage === owner && process.consumers.journalRouting === owner.routing)
            requireJournalPublication(process.catalogReadback === owner.reader && result.catalogFor(process) === catalog)
            // Result/catalog equality is historical provenance, not continuous current full-B authority.
        }

        private fun requireInventory(): Map<String, Long> {
            requireJournalPublication(catalog.chain.tail.generation > 1 && inventory.sources.isNotEmpty() && inventory.copies.isNotEmpty())
            val records = 1L + 5L * inventory.sources.size + 4L * inventory.copies.size
            requireJournalPublication(records <= owner.reader.chainPolicy.limits.maximumManifestRecords, JournalPublicationFailureV1.LIMIT_EXCEEDED)
            val sources = inventory.sources.associateBy { it.sourceId }
            val ages = sources.mapValues { retention.maximumRestoreAgeSeconds }.toMutableMap()
            requireJournalPublication(sources.size == inventory.sources.size && inventory.copies.map { it.sourceId }.toSet() == sources.keys)
            requireJournalPublication(inventory.copies.map { it.copyId }.distinct().size == inventory.copies.size)
            inventory.sources.forEach {
                requireJournalPublication(
                    it.kind == CatalogLogicalInventoryProtocol.SOURCE_KIND && it.state == CatalogLogicalInventoryProtocol.CLAIMED_STATE &&
                        it.databaseIdentity == owner.declaration.writer.databaseIdentity && it.restoreIdentity == owner.declaration.writer.restoreIdentity,
                )
            }
            inventory.copies.forEach { copy ->
                val source = checkNotNull(sources[copy.sourceId])
                requireJournalPublication(copy.state == CatalogLogicalInventoryProtocol.CLAIMED_STATE && copy.bundleSha256 == source.bundleSha256)
                listOf(copy.manifest, copy.dump, copy.media).forEach {
                    val policy = owner.deployment.copyPolicyFor(source.kind, copy.locationClass, it)
                        ?: throw JournalPublicationExceptionV1(JournalPublicationFailureV1.RETENTION_MISMATCH)
                    ages[source.sourceId] = minOf(ages.getValue(source.sourceId), policy.maximumAgeSeconds)
                }
            }
            return ages.toMap()
        }

        private fun requiredRestoreFloor(now: Instant): Instant {
            val uncertainty = owner.deployment.utcUncertainty.maximumMillis
            val earliestNow = now.minusMillis(uncertainty)
            val latestNow = now.plusMillis(uncertainty)
            OrdinaryJournalRetentionV1.requireInstant(earliestNow, wholeSecond = false)
            OrdinaryJournalRetentionV1.requireInstant(latestNow, wholeSecond = false)
            val horizon = inventory.sources.maxOf { source ->
                val restorePoint = Instant.ofEpochSecond(source.restorePointEpochSecond)
                OrdinaryJournalRetentionV1.requireInstant(restorePoint, wholeSecond = true)
                val end = restorePoint.plusSeconds(retention.maximumRestoreAgeSeconds)
                OrdinaryJournalRetentionV1.requireInstant(end, wholeSecond = true)
                val copyEnd = restorePoint.plusSeconds(sourceMaximumAges.getValue(source.sourceId))
                requireJournalPublication(
                    !restorePoint.isAfter(earliestNow) && !copyEnd.isBefore(latestNow),
                    JournalPublicationFailureV1.RETENTION_MISMATCH,
                )
                end // Every extant source, not an invented event-to-backup selection or ADD_COPY creation time.
            }
            return horizon.plusSeconds(SAFETY_MARGIN_SECONDS).also { OrdinaryJournalRetentionV1.requireInstant(it, wholeSecond = true) }
        }

        override fun toString(): String = "LiveJournalCoverageBindingV1(historical-logical-only,redacted,no-backup-acceptance-or-current-authority)"

        companion object {
            internal fun fromProjected(
                owner: VersionBoundLiveJournalCoverageV1,
                process: VersionBoundComplaintProcessConfiguration,
                catalog: CurrentProjectedCatalogRefreshV1.Result,
            ): Binding = journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                requireConnectionFree()
                requireJournalPublication(process.liveCoverage === owner)
                Binding(owner, process, catalog)
            }
        }
    }

    companion object {
        private const val SAFETY_MARGIN_SECONDS = 31 * 86_400L

        fun fromIndependentInputs(
            routing: VersionBoundComplaintJournalRouting,
            reader: VersionBoundCatalogReadbackConfigurationV1,
            publicationLanes: JournalPublicationLanesV1,
            deployment: LiveJournalPolicyDeploymentV1,
        ): VersionBoundLiveJournalCoverageV1 = VersionBoundLiveJournalCoverageV1(
            routing,
            reader,
            publicationLanes,
            deployment,
            Clock.systemUTC(),
            System::nanoTime,
        )

        /** Clock sources only; no dates, claimed observations, checked inventory or authority callbacks. */
        fun withClockFixture(
            routing: VersionBoundComplaintJournalRouting,
            reader: VersionBoundCatalogReadbackConfigurationV1,
            publicationLanes: JournalPublicationLanesV1,
            deployment: LiveJournalPolicyDeploymentV1,
            clock: Clock,
            nanoTime: () -> Long,
        ): VersionBoundLiveJournalCoverageV1 = VersionBoundLiveJournalCoverageV1(routing, reader, publicationLanes, deployment, clock, nanoTime)
    }
}
