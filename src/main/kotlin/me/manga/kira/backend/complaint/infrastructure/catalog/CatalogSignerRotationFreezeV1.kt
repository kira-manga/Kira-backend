package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.nio.file.Path
import java.time.Clock

/** Exact existing canonical manifest/ID-time approval files; human authentication and durable-root custody remain external. */
internal class CatalogSignerRotationFreezeRequestV1(val approvedIntent: Path, val approvalInputs: Path, val releaseRoot: Path) {
    override fun toString(): String = "CatalogSignerRotationFreezeRequestV1(explicit-input-paths,redacted,no-authority)"
}

/**
 * Fixed first non-G1 unit only: a same-process G1 campaign and cold D7 become signed PREPARED overlap2.
 * There is no PUT, COMPLETE, PROJECT, activation, replacement process or synthetic refresh producer.
 * begin starts the one original budget before this owner opens any input, custody or provider resource.
 */
internal class CatalogSignerRotationFreezeV1 private constructor(
    process: VersionBoundComplaintProcessConfiguration,
    campaign: CatalogCoordinatorLeaseCampaignV1,
    internal val budget: PersistenceTimeBudget,
    signingHttpFactory: (() -> SdkHttpClient)?,
    readbackHttpFactory: (() -> SdkHttpClient)?,
    clock: Clock,
    private val initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val attempt = CatalogSignerRotationFreezeAttemptV1(this, process, campaign, budget, initialAuthor)
    private val assembly = CatalogSignerRotationFreezeAssemblyV1(attempt, signingHttpFactory, readbackHttpFactory, clock)
    private var release: CatalogSignerRotationFreezeReleaseV1? = null
    private var entered = false
    private var closed = false
    private var cleanupProven = false
    private var closeFailure: CatalogSignerRotationFreezeExceptionV1? = null

    fun freeze(
        request: CatalogSignerRotationFreezeRequestV1,
        oldSigningCredentials: AwsSessionCredentials,
        newSigningCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFreezeResultV1 = run(
        Mode.FREEZE,
        request,
        oldSigningCredentials,
        newSigningCredentials,
        primaryReadCredentials,
        replicaReadCredentials,
    )

    /**
     * No Sign is reachable on resume. Both exact durable returned signatures must exist; missing or
     * uncertain effects/files refuse. Known unattempted Sign2 has a separate explicit continuation;
     * lost provider response/custody needs explicit recovery resolution. Only the still-genuine original G1 campaign
     * can reach this unit: restart after PREPARED2 cannot fabricate a replacement G1 refresh result.
     */
    fun resume(
        request: CatalogSignerRotationFreezeRequestV1,
        oldSigningCredentials: AwsSessionCredentials,
        newSigningCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFreezeResultV1 = run(
        Mode.RESUME,
        request,
        oldSigningCredentials,
        newSigningCredentials,
        primaryReadCredentials,
        replicaReadCredentials,
    )

    /**
     * Only an exact signature1-persisted prefix with no Sign2/downstream arm or outcome can continue.
     * The prior invocation must have positively closed and released its actual shared slot under its
     * own allowance. It is never revived: this new owner uses only its own original bounded allowance.
     * Merely passing the old deadline after proven cleanup does not invalidate that historical proof.
     * No old-key credentials, replacement campaign, lease renewal or first-Sign replay is accepted.
     */
    fun continueSecondSign(
        request: CatalogSignerRotationFreezeRequestV1,
        previousInvocation: CatalogSignerRotationFreezeV1,
        newSigningCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFreezeResultV1 = run(
        Mode.SECOND_SIGN,
        request,
        null,
        newSigningCredentials,
        primaryReadCredentials,
        replicaReadCredentials,
        previousInvocation,
    )

    @Suppress("TooGenericExceptionCaught")
    private fun run(
        mode: Mode,
        request: CatalogSignerRotationFreezeRequestV1,
        oldSigningCredentials: AwsSessionCredentials?,
        newSigningCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
        previousInvocation: CatalogSignerRotationFreezeV1? = null,
    ): CatalogSignerRotationFreezeResultV1 {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        var product: CatalogSignerRotationFrozenProductV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireSignerRotation(!entered, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            entered = true
            if (mode === Mode.SECOND_SIGN) checkNotNull(previousInvocation).requireClosedForContinuation(attempt)
            attempt.reserve()
            val inputs = assembly.acquire(request)
            attempt.bind(inputs)
            if (mode === Mode.SECOND_SIGN) checkNotNull(previousInvocation).attempt.requireSameContinuationInputs(attempt)
            val retained = CatalogSignerRotationFreezeReleaseV1(inputs, budget)
            release = retained // The original lock/files owner exists before open, including partial construction.
            if (mode === Mode.FREEZE) retained.openNew() else retained.openExisting()
            product = when (mode) {
                Mode.FREEZE -> freezePrepared(
                    retained,
                    checkNotNull(oldSigningCredentials),
                    newSigningCredentials,
                    primaryReadCredentials,
                    replicaReadCredentials,
                )

                Mode.RESUME -> resumePrepared(retained, primaryReadCredentials, replicaReadCredentials)

                Mode.SECOND_SIGN -> continuePreparedSecondSign(retained, newSigningCredentials, primaryReadCredentials, replicaReadCredentials)
            }
            requireRunning()
        } catch (problem: Throwable) {
            failure = signerRotationSignal(problem)
            attempt.abort()
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = preferSignerRotationCleanup(failure, cleanup)
            }
        }
        failure?.let { throw boundedSignerRotationFailure(it) }
        return CatalogSignerRotationFreezeResultV1.completed(this, checkNotNull(product))
    }

    private fun freezePrepared(
        retained: CatalogSignerRotationFreezeReleaseV1,
        oldCredentials: AwsSessionCredentials,
        newCredentials: AwsSessionCredentials,
        primaryCredentials: AwsSessionCredentials,
        replicaCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFrozenProductV1 {
        val initial = attempt.readInitial()
        val predecessor = assembly.observe(initial, primaryCredentials, replicaCredentials)
        retained.armPrepare()
        var local = attempt.prepare(predecessor)
        retained.prepared(local)
        val credentials = listOf(oldCredentials, newCredentials)
        for (slot in 0..1) {
            val readback = assembly.observe(local, primaryCredentials, replicaCredentials)
            local = attempt.recheck(local, readback) // Fresh locked full B/DB lease AFTER provider close, before Sign arm.
            retained.armSign(slot, local)
            val signature = assembly.sign(slot, credentials[slot]) // Genuine SDK/PSS and actual cleanup before bytes return.
            retained.preserveSignature(slot, signature)
            val after = attempt.inputs.withSignature(local, slot, signature, readback)
            retained.armSignaturePersistence(slot, after)
            local = attempt.persistSignature(local, after)
            retained.signaturePersisted(slot, local)
        }
        return retained.signedPrepared(local)
    }

    private fun continuePreparedSecondSign(
        retained: CatalogSignerRotationFreezeReleaseV1,
        newCredentials: AwsSessionCredentials,
        primaryCredentials: AwsSessionCredentials,
        replicaCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFrozenProductV1 {
        var local = attempt.readPrepared()
        retained.requireUnattemptedSecondSign(local)
        val readback = assembly.observe(local, primaryCredentials, replicaCredentials)
        local = attempt.recheck(local, readback) // Fresh actual full B/DB-time lease after raw provider cleanup; no PREPARE or recharge.
        retained.requireUnattemptedSecondSign(local)
        retained.armSign(1, local) // Requires newly CREATED arm2, never an identical existing arm or outcome.
        val signature = assembly.signSecondOnly(newCredentials)
        retained.preserveSignature(1, signature)
        val after = attempt.inputs.withSignature(local, 1, signature, readback)
        retained.armSignaturePersistence(1, after)
        local = attempt.persistSignature(local, after)
        retained.signaturePersisted(1, local)
        return retained.signedPrepared(local)
    }

    /** Historical original cleanup only; never checks/renews the prior owner's spent running budget. */
    private fun requireClosedForContinuation(next: CatalogSignerRotationFreezeAttemptV1) {
        requireConnectionFree()
        requireSignerRotation(caller === Thread.currentThread() && entered && closed, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireSignerRotation(cleanupProven && closeFailure == null, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        attempt.requireReleasedForContinuation(next)
    }

    private fun resumePrepared(
        retained: CatalogSignerRotationFreezeReleaseV1,
        primaryCredentials: AwsSessionCredentials,
        replicaCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFrozenProductV1 {
        val signatures = retained.requireBothReturnedSignatures()
        var local = attempt.readPrepared() // Absence, changed immutable tuple or sparse conflicting SQL never permits Sign.
        val readback = assembly.observe(local, primaryCredentials, replicaCredentials)
        local = attempt.recheck(local, readback)
        val after = attempt.inputs.withBothSignatures(local, signatures, readback)
        retained.requireFrozenEnvelope(after)
        local = attempt.persistSignature(local, after) // Exact NULL/or-identical preimage; no randomized replacement.
        retained.signaturePersisted(1, local)
        return retained.signedPrepared(local)
    }

    internal fun owns(selected: CatalogSignerRotationFreezeAttemptV1): Boolean = caller === Thread.currentThread() && !closed && attempt === selected

    internal fun requireRunning() {
        initialAuthor?.requireInvocation(this)
        requireSignerRotation(caller === Thread.currentThread() && !closed, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    /** Never closes the pre-existing process/campaign pools; only this invocation's original owned resources. */
    override fun close() {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        closeFailure = CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        attempt.abort()
        val outcomes = listOf(runCatching(assembly::close), runCatching { release?.close() }, runCatching(::requireConnectionFree))
        outcomes.forEach { it.exceptionOrNull()?.let { failure -> initialAuthor?.observeFailure(failure) } }
        requireSignerRotationCleanup(outcomes)
        attempt.requireSqlCleanup()
        attempt.throwIfSignalled()
        requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        cleanupProven = true
        attempt.releaseAfterCleanup() // Unknown close/phase/provider outcomes retain the original shared slot.
        closeFailure = null
    }

    /** Same-session handoff uses actual historical cleanup, never the preceding invocation's spent deadline. */
    internal fun requireClosedForInitialAuthor(original: CatalogSignerRotationInitialAuthorV1) {
        requireConnectionFree()
        requireSignerRotation(
            caller === Thread.currentThread() && initialAuthor === original && closed && cleanupProven && closeFailure == null,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
        attempt.requireReleasedForInitialAuthor(original)
    }

    internal fun requireCleanedResult() {
        requireSignerRotation(caller === Thread.currentThread() && closed && closeFailure == null, CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN)
        requireConnectionFree()
        budget.remainingMillis(1)
    }

    internal fun requireOwnedCleanup(selected: CatalogSignerRotationFreezeAttemptV1) {
        requireSignerRotation(
            caller === Thread.currentThread() && closed && cleanupProven && attempt === selected,
            CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN,
        )
        attempt.requireSqlCleanup()
        requireConnectionFree()
    }

    override fun toString(): String = "CatalogSignerRotationFreezeV1(fixed-overlap2,redacted,no-publication-or-activation-authority)"

    private enum class Mode { FREEZE, RESUME, SECOND_SIGN }

    companion object {
        internal fun beginInitialAuthor(
            original: CatalogSignerRotationInitialAuthorV1,
            process: VersionBoundComplaintProcessConfiguration,
            campaign: CatalogCoordinatorLeaseCampaignV1,
            signingHttpFactory: (() -> SdkHttpClient)?,
            readbackHttpFactory: (() -> SdkHttpClient)?,
            clock: Clock,
        ): CatalogSignerRotationFreezeV1 {
            original.requireInvocationConstruction(process, campaign)
            return CatalogSignerRotationFreezeV1(
                process, campaign, campaign.binding.startInitialAuthorSignerRotationBudget(process, original),
                signingHttpFactory, readbackHttpFactory, clock, original,
            )
        }

        fun begin(process: VersionBoundComplaintProcessConfiguration, campaign: CatalogCoordinatorLeaseCampaignV1): CatalogSignerRotationFreezeV1 =
            CatalogSignerRotationFreezeV1(process, campaign, campaign.binding.startSignerRotationBudget(process), null, null, Clock.systemUTC())

        /** Raw HTTP/wall-time seams only; actual retained process clock, SDK, SQL, crypto and Linux custody remain in place. */
        internal fun withHttpFixtures(
            process: VersionBoundComplaintProcessConfiguration,
            campaign: CatalogCoordinatorLeaseCampaignV1,
            signingHttpFactory: () -> SdkHttpClient,
            readbackHttpFactory: () -> SdkHttpClient,
            clock: Clock = Clock.systemUTC(),
        ): CatalogSignerRotationFreezeV1 = CatalogSignerRotationFreezeV1(
            process,
            campaign,
            campaign.binding.startSignerRotationBudget(process),
            signingHttpFactory,
            readbackHttpFactory,
            clock,
        )
    }
}

/** A bounded historical completion only; future publication must acquire its own real prerequisites. */
internal class CatalogSignerRotationFreezeResultV1 private constructor(product: CatalogSignerRotationFrozenProductV1) {
    val state = CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED
    val generation = 2L
    val operationToken: String = product.operationToken
    val envelopeSha256: String = product.envelopeSha256
    override fun toString(): String = "CatalogSignerRotationFreezeResultV1(historical-signed-PREPARED2,head-unchanged,no-authority)"

    companion object {
        internal fun completed(owner: CatalogSignerRotationFreezeV1, product: CatalogSignerRotationFrozenProductV1): CatalogSignerRotationFreezeResultV1 {
            owner.requireCleanedResult()
            return CatalogSignerRotationFreezeResultV1(product)
        }

        internal fun completed(
            owner: CatalogSignerRotationPreparedRecoveryV1,
            product: CatalogSignerRotationFrozenProductV1,
        ): CatalogSignerRotationFreezeResultV1 {
            owner.requireCleanedResult(product)
            return CatalogSignerRotationFreezeResultV1(product)
        }
    }
}

internal enum class CatalogSignerRotationFreezeStateV1 { SIGNED_PREPARED }

internal class CatalogSignerRotationFrozenProductV1(val operationToken: String, val envelopeSha256: String)
