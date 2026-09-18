package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import java.io.InterruptedIOException
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Only its exact one-shot owner may retain/select this attempt; no supplied tuple, Result or callback can grant entry. */
internal class CatalogSignerRotationFreezeAttemptV1 internal constructor(
    private val owner: CatalogSignerRotationFreezeV1,
    internal val process: VersionBoundComplaintProcessConfiguration,
    internal val campaign: CatalogCoordinatorLeaseCampaignV1,
    internal val budget: PersistenceTimeBudget,
) {
    private val caller = Thread.currentThread()
    private val coordinator = process.pools.catalogCoordinator
    private val ownership = coordinator.ownership
    private val jdbc = campaign.jdbc
    private val binding = campaign.binding.arguments()
    private val capacity = process.consumers.capacityPolicy.digestBytes()
    internal val predecessorHash = campaign.binding.signerRotationPredecessorHash(process)
    private var selectedInputs: CatalogSignerRotationInputsV1? = null
    internal val inputs: CatalogSignerRotationInputsV1 get() = checkNotNull(selectedInputs)
    private var selected: CatalogSignerRotationSqlInputV1? = null
    private var latest: CatalogSignerRotationObservationV1? = null
    private var entered = false
    private var reserved = false
    private var released = false
    private var failed = false
    private var originalPhase: PersistencePhaseContext? = null
    private var phaseRetainedForEntry = false
    private var sqlCleanupUnproven = false
    private val originalSignal = AtomicReference<Throwable?>()

    init {
        requireConnectionFree()
        campaign.binding.requireSignerRotationProcess(process)
        campaign.binding.requirePersistence(ownership, jdbc)
        campaign.requireRotationContinuity(budget)
    }

    internal fun reserve() {
        requireConnectionFree()
        requireRunning()
        requireSignerRotation(!reserved && !released, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        coordinator.catalogRefreshCustody.reserveSignerRotation(this)
        reserved = true
    }

    internal fun bind(value: CatalogSignerRotationInputsV1) {
        requireRunning()
        requireSignerRotation(reserved && selectedInputs == null)
        selectedInputs = value
    }

    internal fun readInitial(): CatalogSignerRotationObservationV1 = execute(CatalogSignerRotationSqlInputV1.initial(this))
    internal fun readPrepared(): CatalogSignerRotationObservationV1 = execute(CatalogSignerRotationSqlInputV1.prepared(this))

    internal fun prepare(readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback): CatalogSignerRotationObservationV1 {
        val before = checkNotNull(latest)
        requireSignerRotation(before.mutation == null)
        requireReadback(before, readback)
        return execute(CatalogSignerRotationSqlInputV1.prepare(this, before))
    }

    internal fun recheck(
        before: CatalogSignerRotationObservationV1,
        readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback,
    ): CatalogSignerRotationObservationV1 {
        requireLatest(before)
        requireReadback(before, readback)
        return execute(CatalogSignerRotationSqlInputV1.recheck(this, before))
    }

    internal fun persistSignature(before: CatalogSignerRotationObservationV1, after: CatalogFrozenMutation): CatalogSignerRotationObservationV1 {
        requireLatest(before)
        inputs.requireMutation(after)
        return execute(CatalogSignerRotationSqlInputV1.signature(this, before, after))
    }

    private fun requireReadback(before: CatalogSignerRotationObservationV1, readback: CatalogDualLocationVerifier.SignerRotationAuthorReadback) {
        requireConnectionFree()
        requireRunning()
        readback.requireSnapshot(before.localSnapshot)
        inputs.requirePredecessor(readback)
        requireSignerRotation(readback.genesisBytes().contentEquals(before.genesis.signedEnvelopeBytes))
    }

    private fun requireLatest(before: CatalogSignerRotationObservationV1) {
        requireRunning()
        requireSignerRotation(before === latest, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute(input: CatalogSignerRotationSqlInputV1): CatalogSignerRotationObservationV1 {
        requireConnectionFree()
        requireRunning()
        requireSignerRotation(reserved && selected == null && input.attempt === this, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        selected = input
        entered = false
        phaseRetainedForEntry = false
        try {
            val observation = coordinator.signerRotation.execute(input)
            requireRunning()
            inputs.requireObservation(observation)
            latest = observation
            return observation
        } catch (problem: Throwable) {
            observeFailure(problem)
            // A supplied positive exception flag is never cleanup authority. An unretained uncertain entry can only veto release.
            if (!phaseRetainedForEntry && problem is PersistencePhaseException && !problem.cleanupProven) sqlCleanupUnproven = true
            abort()
            throwIfSignalled()
            throw problem
        } finally {
            selected = null
        }
    }

    internal fun requirePhaseEntry(candidate: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireRunning()
        requireSignerRotation(candidate === ownership && selected?.path === path && !entered, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        entered = true
    }

    /** Called by the real ownership before publication or permit effects, not after enter returns to its executor. */
    internal fun retainPhase(phase: PersistencePhaseContext) {
        requireSignerRotation(
            caller === Thread.currentThread() && entered && selected != null && originalPhase == null && !phaseRetainedForEntry && !sqlCleanupUnproven,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
        originalPhase = phase
        phaseRetainedForEntry = true
    }

    /** No cleanup inference from exception flags, later ThreadLocal removal, another phase or another caller. */
    @Suppress("TooGenericExceptionCaught")
    internal fun observePhaseCleanup(phase: PersistencePhaseContext) {
        if (caller !== Thread.currentThread() || originalPhase !== phase) {
            sqlCleanupUnproven = true
            return
        }
        try {
            if (!phase.catalogSignerRotation.cleanupProven(this)) sqlCleanupUnproven = true
            if (!sqlCleanupUnproven) originalPhase = null // Only this exact original context positively settled.
        } catch (problem: Throwable) {
            sqlCleanupUnproven = true
            observeFailure(problem) // Finally bookkeeping must not replace an original fatal/cancellation/interruption.
        }
    }

    internal fun requireSqlCleanup() {
        requireSignerRotation(
            caller === Thread.currentThread() && !sqlCleanupUnproven && originalPhase == null,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
    }

    /** Retain only signals, never arbitrary SQL/provider diagnostics; lower cleanup may otherwise report only a bounded phase code. */
    internal fun observeFailure(problem: Throwable) {
        val signal = when {
            problem is Error -> problem

            problem is CancellationException -> CancellationException("Catalog signer rotation freeze cancelled.")

            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ->
                InterruptedException("Catalog signer rotation freeze interrupted.")

            else -> return
        }
        while (true) {
            val previous = originalSignal.get()
            if (previous is Error || (previous is CancellationException && signal !is Error) ||
                (previous is InterruptedException && signal is InterruptedException)
            ) {
                return
            }
            if (originalSignal.compareAndSet(previous, signal)) return
        }
    }

    internal fun throwIfSignalled() {
        originalSignal.get()?.let { throw signerRotationSignal(it) }
    }

    internal fun requirePersistence(candidate: PersistencePhaseOwnership, candidateJdbc: JdbcTemplate, input: CatalogSignerRotationSqlInputV1) {
        requireRunning()
        requireSignerRotation(
            candidate === ownership && candidateJdbc === jdbc && selected === input && reserved,
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        campaign.binding.requirePersistence(candidate, candidateJdbc)
        coordinator.catalogRefreshCustody.requireSignerRotation(this)
    }

    internal fun requireRunning() {
        throwIfSignalled()
        requireSignerRotation(
            caller === Thread.currentThread() && !failed && !released && owner.owns(this),
            CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED,
        )
        requireSignerRotation(!sqlCleanupUnproven, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        owner.requireRunning()
        campaign.binding.requireSignerRotationProcess(process)
        campaign.requireRotationContinuity(budget)
        if (reserved) coordinator.catalogRefreshCustody.requireSignerRotation(this)
    }

    /** Detached outside phase entry; never regenerate/hash full B under the control lock. */
    internal fun bindingArguments(): Array<Any?> {
        requireConnectionFree()
        requireRunning()
        return arrayOf(*binding.map { if (it is ByteArray) it.copyOf() else it }.toTypedArray(), campaign.owner, campaign.token)
    }

    internal fun bindingRecordValues(): Array<String> {
        requireConnectionFree()
        return (
            binding.map { if (it is ByteArray) HexFormat.of().formatHex(it) else it.toString() } +
                listOf(HexFormat.of().formatHex(capacity), campaign.owner.toString(), campaign.token.toString())
            ).toTypedArray()
    }

    internal fun capacityDigest(): ByteArray = capacity.copyOf()

    internal fun requireCustody(candidate: CatalogReadbackRefreshCustodyV1) {
        requireSignerRotation(coordinator.catalogRefreshCustody === candidate && caller === Thread.currentThread())
    }

    internal fun abort() {
        failed = true // Never revives; known full-signature recovery needs a fresh explicit owner, not this failed invocation.
    }

    internal fun releaseAfterCleanup() {
        owner.requireOwnedCleanup(this)
        if (!reserved) return
        coordinator.catalogRefreshCustody.releaseSignerRotationAfterCleanup(this)
        released = true
    }

    internal fun requireActualCleanup() = owner.requireOwnedCleanup(this)

    override fun toString(): String = "CatalogSignerRotationFreezeAttemptV1(exact-cold-process-and-campaign,redacted)"
}
