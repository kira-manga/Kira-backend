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
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val attempt = CatalogSignerRotationFreezeAttemptV1(this, process, campaign, budget)
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
        false,
        request,
        oldSigningCredentials,
        newSigningCredentials,
        primaryReadCredentials,
        replicaReadCredentials,
    )

    /**
     * No Sign is reachable on resume. Both exact durable returned signatures must exist; missing or
     * uncertain effects/files refuse. Known unattempted later Sign is an INTERNAL continuation gap
     * in this slice; lost provider response/custody needs explicit recovery resolution. Only the still-genuine original G1 campaign
     * can reach this unit: restart after PREPARED2 cannot fabricate a replacement G1 refresh result.
     */
    fun resume(
        request: CatalogSignerRotationFreezeRequestV1,
        oldSigningCredentials: AwsSessionCredentials,
        newSigningCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFreezeResultV1 = run(
        true,
        request,
        oldSigningCredentials,
        newSigningCredentials,
        primaryReadCredentials,
        replicaReadCredentials,
    )

    @Suppress("TooGenericExceptionCaught")
    private fun run(
        resuming: Boolean,
        request: CatalogSignerRotationFreezeRequestV1,
        oldSigningCredentials: AwsSessionCredentials,
        newSigningCredentials: AwsSessionCredentials,
        primaryReadCredentials: AwsSessionCredentials,
        replicaReadCredentials: AwsSessionCredentials,
    ): CatalogSignerRotationFreezeResultV1 {
        requireSignerRotation(caller === Thread.currentThread(), CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
        var product: CatalogSignerRotationFrozenProductV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireSignerRotation(!entered, CatalogSignerRotationFreezeFailureV1.PROCESS_REFUSED)
            entered = true
            attempt.reserve()
            val inputs = assembly.acquire(request)
            attempt.bind(inputs)
            val retained = CatalogSignerRotationFreezeReleaseV1(inputs, budget)
            release = retained // The original lock/files owner exists before open, including partial construction.
            if (resuming) retained.openExisting() else retained.openNew()
            product = if (resuming) {
                resumePrepared(retained, primaryReadCredentials, replicaReadCredentials)
            } else {
                freezePrepared(retained, oldSigningCredentials, newSigningCredentials, primaryReadCredentials, replicaReadCredentials)
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
        requireSignerRotationCleanup(outcomes)
        attempt.requireSqlCleanup()
        attempt.throwIfSignalled()
        requireSignerRotation(!Thread.currentThread().isInterrupted, CatalogSignerRotationFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        cleanupProven = true
        attempt.releaseAfterCleanup() // Unknown close/phase/provider outcomes retain the original shared slot.
        closeFailure = null
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

    companion object {
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
    }
}

internal enum class CatalogSignerRotationFreezeStateV1 { SIGNED_PREPARED }

internal class CatalogSignerRotationFrozenProductV1(val operationToken: String, val envelopeSha256: String)
