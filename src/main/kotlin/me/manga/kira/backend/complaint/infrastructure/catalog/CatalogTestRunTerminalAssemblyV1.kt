package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogPrimaryPutAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogSigningAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutTargetV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock

/**
 * Concrete retained terminal native graph. Reuses the existing Sign, conditional PRIMARY PUT and
 * strict raw LIST/GET SDK transports; no replacement factory, replica writer, supplied proof or retry.
 */
internal class CatalogTestRunTerminalAssemblyV1(
    private val original: CatalogTestRunTerminalV1,
    private val signingFactory: (() -> SdkHttpClient)?,
    private val putFactory: (() -> SdkHttpClient)?,
    private val readbackFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    private var signer: AwsCatalogSigningAdapterV1.Construction? = null
    private var signCleanup = false
    private var signFailure: Throwable? = null
    private var putRound: PutRound? = null
    private val readbacks = ArrayList<ReadRound>(3)
    private var closed = false

    fun sign(input: CatalogTestRunTerminalFrozenV1, credentials: AwsSessionCredentials): ByteArray {
        requireConnectionFree(); original.requireSignConstruction(this, input)
        requireTestTerminalCatalog(!closed && signer == null)
        val construction = AwsCatalogSigningAdapterV1.Construction(original.budget.capped(10_000L))
        signer = construction // Before construction, including a factory that throws before returning.
        val returned = withSignerRotationCleanup({
            original.requireProviderRunning()
            val key = original.process.catalogActivation.signingKey
            val adapter = if (signingFactory == null) AwsCatalogSigningAdapterV1.openOwned(construction, key, credentials)
            else AwsCatalogSigningAdapterV1.withHttpFixture(construction, key, credentials, signingFactory)
            original.requireProviderRunning()
            adapter.sign(input.unsignedBytes())
        }, ::closeSigner)
        requireCleanup(); original.requireProviderRunning()
        return returned
    }

    fun observe(
        snapshot: CatalogTestRunTerminalSnapshotV1,
        input: CatalogTestRunTerminalFrozenV1,
        signed: CatalogTestRunTerminalSignedV1?,
        primary: AwsSessionCredentials,
        replica: AwsSessionCredentials,
    ): CatalogTestRunTerminalDeliveryReadbackV1 {
        requireConnectionFree(); original.requireObservationConstruction(this, snapshot, input, signed)
        requireTestTerminalCatalog(!closed && readbacks.size < 3)
        val reader = original.process.catalogReadback
        val policy = reader.policyAt(original.sampleWallTime())
        val round = ReadRound(original.budget.capped(minOf(reader.totalAttemptMillis, 10_000L)))
        readbacks.add(round)
        val proof = withSignerRotationCleanup({
            round.requireRunning()
            val limits = limits(round.budget)
            val adapter = S3CatalogReadbackAdapter.openOwned(round.construction, reader.currentBundleBytes(),
                reader.chainPolicy.trustBundlePolicy, primary, replica, limits,
                { round.http.open(limits, readbackFactory) }, System::nanoTime)
            round.requireRunning()
            CatalogTestRunTerminalDeliveryReadbackV1.verify(original, TimedReadback(adapter, round), policy, snapshot, input, signed)
        }, round::close)
        round.requireRunning(); round.requireCleanup(); original.sampleWallTime()
        round.proof = proof
        return proof
    }

    fun put(input: CatalogTestRunTerminalFrozenV1, signed: CatalogTestRunTerminalSignedV1,
        credentials: AwsSessionCredentials): CatalogPrimaryPutAcknowledgementV1 {
        requireConnectionFree(); original.requirePutConstruction(this, input, signed)
        requireTestTerminalCatalog(!closed && putRound == null)
        val round = PutRound(original.budget.capped(10_000L))
        putRound = round
        val target = CatalogPrimaryPutTargetV1(original.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedCatalogLocations.single { it.role == "PRIMARY" },
            input.generation, input.createdAt.epochSecond, signed.envelopeSha256)
        val acknowledgement = withSignerRotationCleanup({
            round.requireRunning()
            val adapter = round.construction.open(target, credentials, clock) { round.http.open(limits(round.budget), putFactory) }
            round.requireRunning(); adapter.put(signed.envelopeBytes())
        }, round::close)
        round.requireRunning(); round.requireCleanup(); original.sampleWallTime()
        round.acknowledgement = acknowledgement
        return acknowledgement
    }

    /** Local original identity/cleanup checks only; no network or parsing in a later SQL holder. */
    fun requireObservation(value: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireTestTerminalCatalog(!closed && readbacks.lastOrNull()?.proof === value)
        requireCleanup()
    }
    /** Same retained final observation AFTER actual graph closure; no detached/copy proof adoption. */
    fun requireClosedObservation(value: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireTestTerminalCatalog(closed && readbacks.lastOrNull()?.proof === value)
        requireCleanup()
    }
    fun requireAcknowledgement(value: CatalogPrimaryPutAcknowledgementV1) {
        requireConnectionFree(); requireTestTerminalCatalog(!closed && putRound?.acknowledgement === value)
        checkNotNull(putRound).requireCleanup()
    }
    fun requireCleanup() {
        requireTestTerminalCatalog(signer == null || signCleanup && signFailure == null)
        readbacks.forEach { it.requireCleanup() }; putRound?.requireCleanup()
    }

    private fun closeSigner() {
        signer?.let { runCatching(it::close).exceptionOrNull() }?.let {
            original.observeFailure(it); signFailure = preferSignerRotationCleanup(signFailure, it)
        }
        signFailure?.let { throw it }
        signCleanup = true
    }
    private fun limits(budget: PersistenceTimeBudget): S3CatalogReadbackLimits {
        val configured = original.process.catalogReadback.sdkLimits
        val millis = budget.remainingMillis(minOf(configured.requestTimeoutMillis, 10_000L))
        return S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(),
            minOf(configured.readTimeoutMillis.toLong(), millis).toInt(), configured.maximumListBytes,
            configured.maximumErrorBytes, configured.maximumObjectBytes)
    }
    override fun close() {
        closed = true
        val outcomes = ArrayList<Result<*>>()
        outcomes.add(runCatching(::closeSigner))
        putRound?.let { outcomes.add(runCatching(it::close)) }
        readbacks.forEach { outcomes.add(runCatching(it::close)) }
        requireSignerRotationCleanup(outcomes)
        requireCleanup()
    }

    private inner class ReadRound(val budget: PersistenceTimeBudget) : AutoCloseable {
        val construction = S3CatalogReadbackAdapter.Construction()
        val http = CatalogSignerRotationReadbackHttpPairV1(original, budget)
        var proof: CatalogTestRunTerminalDeliveryReadbackV1? = null
        private var closedCleanly = false
        private var failure: Throwable? = null
        fun requireRunning() { original.requireProviderRunning(); budget.remainingMillis(1) }
        fun requireCleanup() = requireTestTerminalCatalog(closedCleanly && failure == null)
        override fun close() {
            runCatching { withSignerRotationCleanup(construction::close, http::close) }.exceptionOrNull()?.let {
                original.observeFailure(it); failure = preferSignerRotationCleanup(failure, it)
            }
            failure?.let { throw it }; closedCleanly = true
        }
    }
    private inner class PutRound(val budget: PersistenceTimeBudget) : AutoCloseable {
        val construction = AwsCatalogPrimaryPutAdapterV1.Construction(budget)
        val http = CatalogSignerRotationReadbackHttpV1(original, budget)
        var acknowledgement: CatalogPrimaryPutAcknowledgementV1? = null
        private var closedCleanly = false
        private var failure: Throwable? = null
        fun requireRunning() { original.requireProviderRunning(); budget.remainingMillis(1) }
        fun requireCleanup() = requireTestTerminalCatalog(closedCleanly && failure == null)
        override fun close() {
            runCatching { withSignerRotationCleanup(construction::close, http::close) }.exceptionOrNull()?.let {
                original.observeFailure(it); failure = preferSignerRotationCleanup(failure, it)
            }
            failure?.let { throw it }; closedCleanly = true
        }
    }
    private inner class TimedReadback(private val actual: S3CatalogReadbackAdapter, private val round: ReadRound) : CatalogReadbackPort {
        override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }
        override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
            round.requireRunning()
            val body = actual.openVersion(request)
            val check = runCatching(round::requireRunning)
            if (check.isFailure) return withSignerRotationCleanup({ throw checkNotNull(check.exceptionOrNull()) }, body::close)
            return object : CatalogVersionBody {
                override fun metadata() = checked { body.metadata() }
                override fun read(destination: ByteArray, offset: Int, length: Int) = checked { body.read(destination, offset, length) }
                override fun close() = body.close()
            }
        }
        private fun <T> checked(action: () -> T): T { round.requireRunning(); return action().also { round.requireRunning() } }
    }
    override fun toString(): String = "CatalogTestRunTerminalAssemblyV1(original-native-graph,one-Sign,one-primary-PUT)"
}
