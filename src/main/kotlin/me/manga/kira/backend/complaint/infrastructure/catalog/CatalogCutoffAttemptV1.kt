package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceOwnedFactoryCaller
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.security.EpochSealAttemptV1
import me.manga.kira.backend.security.EpochSealManifestV1
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Actual original-coordinator attempt, one caller and one original same-J seal budget through every subcall. */
internal class CatalogCutoffAttemptV1 internal constructor(
    private val issuer: CatalogCutoffPublicationsV1,
    internal val campaign: CatalogCoordinatorLeaseCampaignV1,
    internal val jdbc: JdbcTemplate,
    internal val budget: PersistenceTimeBudget,
) {
    private val caller = PersistenceOwnedFactoryCaller.capture()
    private val binding = campaign.binding
    private val ownership = binding.coordinator.ownership
    private val arguments = binding.arguments()
    internal val routing = binding.cutoffRouting()
    internal val writer: UUID = UUID.fromString(routing.journalConfiguration.declaration().writer.generationId)
    internal val owner = campaign.owner
    internal val token = campaign.token
    private val maximumRows = routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions
    private val codecAttempt = EpochSealAttemptV1(routing, ownership.nanoClock::nanoTime, budget)
    private var failed = false
    private var renewalCadenceStarted = false
    private var stage = Stage.DISCOVER
    private var retained: CatalogCutoffPersistenceOperationV1? = null
    private var slot: CatalogEpochRotationSlotV1? = null
    private var page: CatalogCutoffPersistenceOperationV1? = null
    private var pageIndex = 0
    private var afterEpoch = 0L
    private var afterId = ""
    private var afterKey = ""
    private var resolvedCount = 0L
    private var passCount = 0L
    private var exhausted = false
    private var builder: EpochSealManifestV1.Builder? = null
    private var manifest: EpochSealManifestV1? = null

    internal fun requireEvidenceRunning() {
        if (!caller.isCurrent() || failed || !issuer.owns(this)) refuse()
        if (caller.sampleActualFlag() != null) throw PersistencePhaseException(PersistencePhaseFailureCode.INTERRUPTED)
        budget.remainingMillis(10_000)
        binding.requirePersistence(ownership, jdbc)
        check(binding.cutoffRouting() === routing)
        budget.remainingMillis(10_000)
    }

    internal fun requireRunning() {
        requireEvidenceRunning()
        if (renewalCadenceStarted) campaign.requireSealContinuity(budget) else campaign.requireRotationContinuity(budget)
        requireEvidenceRunning()
    }

    internal fun requireRenewalCadence() {
        renewalCadenceStarted = true // Checks the campaign's ACTUAL renewed Window; this does not assign a clock/window.
        requireRunning()
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, selectedJdbc: JdbcTemplate, path: PersistencePhasePath) {
        if (path === PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY) requireEvidenceRunning() else requireRunning()
        if (selected !== ownership || selectedJdbc !== jdbc) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun matchesBinding(row: CatalogEpochRotationBindingRowV1): Boolean = row.matchesLeaseArguments(arguments)

    internal fun beginOperation(path: PersistencePhasePath) {
        requirePersistence(ownership, jdbc, path)
        check(retained == null || checkNotNull(retained).released())
        retained = null
    }

    internal fun retain(operation: CatalogCutoffPersistenceOperationV1) {
        requirePersistence(ownership, jdbc, operation.path)
        check(retained == null && operation.attempt === this)
        retained = operation
    }

    internal fun requireOperation(operation: CatalogCutoffPersistenceOperationV1) {
        requirePersistence(ownership, jdbc, operation.path)
        check(retained === operation)
    }

    internal fun checkControl(row: CatalogEpochRotationRowV1) {
        row.requireCurrent(this)
        val observed = checkNotNull(row.slot)
        check(row.historyEmpty && observed.state === CatalogEpochRotationStateV1.CAPTURED && observed.sequence == 1L)
        check(observed.epochBefore in 1 until Long.MAX_VALUE && observed.epochAfter == row.publicationEpoch)
        slot?.let { check(it.sameState(observed)) }
        check(stage === Stage.DISCOVER || stage === Stage.FINAL)
    }

    internal fun acceptControl(operation: CatalogCutoffPersistenceOperationV1) {
        operation.requireReleased()
        requireOperation(operation)
        val row = operation.controlRow()
        checkControl(row)
        if (stage === Stage.DISCOVER) {
            slot = checkNotNull(row.slot)
            stage = Stage.RESOLVE
        } else {
            stage = Stage.COMPLETE
        }
    }

    internal fun cutoffEpoch(): Long = checkNotNull(slot).epochBefore

    internal fun pageSql(): String = when (stage) {
        Stage.RESOLVE -> CatalogCutoffPublicationSqlV1.EPOCH_PAGE
        Stage.COUNT, Stage.DIGEST -> CatalogCutoffPublicationSqlV1.SORTED_PAGE
        else -> error("Invalid cutoff page stage.")
    }

    internal fun pageArguments(): Array<Any?> {
        requireRunning()
        check(!exhausted && page == null)
        return if (stage === Stage.RESOLVE) {
            arrayOf(writer, cutoffEpoch(), afterEpoch, afterId)
        } else {
            check(stage === Stage.COUNT || stage === Stage.DIGEST)
            val prefix = "${OfflineBootstrapGrammar.ordinaryPrefix(writer.toString())}writer/$writer/epoch/"
            val lower = prefix + "1".padStart(19, '0') + "/"
            val upper = prefix + Math.addExact(cutoffEpoch(), 1L).toString().padStart(19, '0') + "/"
            arrayOf(writer, lower, upper, afterKey)
        }
    }

    internal fun acceptPage(operation: CatalogCutoffPersistenceOperationV1): List<CatalogCutoffPublicationRowV1> {
        operation.requireReleased()
        requireOperation(operation)
        check(page == null && !exhausted)
        val rows = operation.rows()
        check(rows.size <= CatalogCutoffPublicationSqlV1.PAGE_SIZE)
        page = operation
        pageIndex = 0
        return rows
    }

    internal fun recordResolved(row: CatalogCutoffPublicationRowV1) {
        requireRunning()
        check(stage === Stage.RESOLVE)
        requireNext(row)
        check(row.epoch > afterEpoch || (row.epoch == afterEpoch && row.eventId > afterId))
        check(resolvedCount < maximumRows)
        resolvedCount++
        afterEpoch = row.epoch
        afterId = row.eventId
        pageIndex++
    }

    internal fun recordManifest(row: CatalogCutoffPublicationRowV1) {
        requireRunning()
        check(stage === Stage.COUNT || stage === Stage.DIGEST)
        requireNext(row)
        check(row.objectKey > afterKey && passCount < resolvedCount)
        val event = row.event(this)
        val proof = row.firstProof(event, routing) // Includes APPLIED and preserves exact first evidence; PREPARED refuses.
        val selected = checkNotNull(builder)
        if (stage === Stage.COUNT) {
            selected.firstPass(row.objectKey, proof.objectVersion, proof.ciphertextSha256)
        } else {
            selected.secondPass(row.objectKey, proof.objectVersion, proof.ciphertextSha256)
        }
        passCount++
        pageIndex++
        afterKey = row.objectKey
        requireRunning()
    }

    private fun requireNext(row: CatalogCutoffPublicationRowV1) {
        val selected = checkNotNull(page)
        selected.requireReleased()
        check(selected.rows().getOrNull(pageIndex) === row)
    }

    internal fun endPage(): Boolean {
        requireRunning()
        val rows = checkNotNull(page).rows()
        check(pageIndex == rows.size)
        exhausted = rows.size < CatalogCutoffPublicationSqlV1.PAGE_SIZE
        page = null
        return exhausted
    }

    internal fun beginManifest() {
        requireRunning()
        check(stage === Stage.RESOLVE && exhausted && page == null)
        builder = EpochSealManifestV1.start(routing, 1L, cutoffEpoch(), "", codecAttempt)
        stage = Stage.COUNT
        exhausted = false
    }

    internal fun beginSecondPass() {
        requireRunning()
        check(stage === Stage.COUNT && exhausted && page == null && passCount == resolvedCount)
        checkNotNull(builder).beginSecondPass()
        passCount = 0
        afterKey = ""
        exhausted = false
        stage = Stage.DIGEST
    }

    internal fun finishManifest() {
        requireRunning()
        check(stage === Stage.DIGEST && exhausted && page == null && passCount == resolvedCount)
        manifest = checkNotNull(builder).finish().also { check(it.eventCount == resolvedCount) }
        stage = Stage.FINAL
    }

    internal fun result(operation: CatalogCutoffPersistenceOperationV1): Pair<CatalogEpochRotationSlotV1, EpochSealManifestV1> {
        operation.requireReleased()
        requireOperation(operation)
        requireRunning()
        check(stage === Stage.COMPLETE)
        val selected = checkNotNull(manifest)
        selected.requireOwner(routing, codecAttempt)
        return checkNotNull(slot) to selected
    }

    internal fun abort() {
        failed = true
        campaign.close()
    }

    internal fun finish() {
        try {
            if (failed || stage !== Stage.COMPLETE) abort() else requireRunning()
        } finally {
            caller.restoreAfterFailure()
        }
    }

    override fun toString(): String = "CatalogCutoffAttemptV1(original-J,retained-current-campaign,no-seal-authority)"

    private enum class Stage { DISCOVER, RESOLVE, COUNT, DIGEST, FINAL, COMPLETE }
    private fun refuse(): Nothing = throw PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED)
}
