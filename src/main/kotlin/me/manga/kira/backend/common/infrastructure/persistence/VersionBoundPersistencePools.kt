package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/** One root's fixed cold composition and partial-shell custody. Neither construction nor descriptors grant activation. */
internal class VersionBoundPersistencePools private constructor(
    private val root: PersistenceJdbcDriverRoot,
    endpoint: ResolvedPersistenceEndpoint,
    ordinaryCapacity: Int,
    identity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
) : AutoCloseable {
    private val custody = Any()
    private var state = State.NEW
    private var constructor: Thread? = null
    private val ordinaryBinding = binding(endpoint, PersistenceJdbcParticipantRole.ORDINARY, ordinaryCapacity, identity)
    private val deletionBinding = binding(endpoint, PersistenceJdbcParticipantRole.DELETION, ordinaryCapacity, identity)
    private val catalogBinding = binding(endpoint, PersistenceJdbcParticipantRole.CATALOG_COORDINATOR, ordinaryCapacity, identity)
    private val bindings = listOf(ordinaryBinding, deletionBinding, catalogBinding)
    private var catalog: CatalogCoordinatorPersistence? = null

    val ordinary: GuardedDataSource get() = completed { ordinaryBinding.dataSource() }
    val deletion: GuardedDataSource get() = completed { deletionBinding.dataSource() }
    val catalogCoordinator: CatalogCoordinatorPersistence get() = completed { checkNotNull(catalog) }
    val epochRotation: EpochRotationPersistence? get() = root.epochRotation

    internal fun ownsEpochRotation(resource: EpochRotationPersistence): Boolean = root.epochRotation === resource

    internal fun bind(
        owner: PersistenceJdbcLifecycleOwner,
        launchProfile: PersistencePoolLaunchProfile,
        nanoClock: PersistenceNanoClock,
    ): VersionBoundPersistencePools {
        requireConnectionFree()
        requireBinding(!owner.ownershipLockHeld() && owner.versionBoundPools === this)
        synchronized(custody) {
            requireBinding(state === State.NEW && !root.shutdown.get())
            state = State.CONSTRUCTING
            constructor = Thread.currentThread()
        }
        var returned = false
        try {
            ordinaryBinding.construct(owner, launchProfile)
            deletionBinding.construct(owner, launchProfile)
            catalog = owner.bindCatalogCoordinator(launchProfile, nanoClock)
            returned = true
            return this
        } finally {
            synchronized(custody) {
                constructor = null
                state = if (returned) State.BOUND else State.FAILED
            }
        }
    }

    internal fun requireCatalogConstruction() = synchronized(custody) {
        requireBinding(state === State.CONSTRUCTING && constructor === Thread.currentThread() && !root.shutdown.get())
    }

    internal fun prepareCatalog(owner: PersistenceJdbcLifecycleOwner, launchProfile: PersistencePoolLaunchProfile): CatalogCoordinatorPersistence {
        requireCatalogConstruction()
        return CatalogCoordinatorPersistence.versionBound(owner, catalogBinding, launchProfile)
    }

    internal fun material(role: PersistenceJdbcParticipantRole): VersionBoundPersistencePoolMaterial = when (role) {
        PersistenceJdbcParticipantRole.ORDINARY -> ordinaryBinding.material
        PersistenceJdbcParticipantRole.DELETION -> deletionBinding.material
        PersistenceJdbcParticipantRole.CATALOG_COORDINATOR -> catalogBinding.material
        PersistenceJdbcParticipantRole.EPOCH_ROTATION -> error("Epoch rotation is not a pool.")
    }

    fun descriptors(): List<VersionBoundPersistencePoolDescriptor> = completed { bindings.map { it.descriptor() } }

    internal fun ownsCatalogLifecycle(lifecycle: PoolLifecycle): Boolean = catalogBinding.ownsLifecycle(lifecycle)

    internal fun requireConstruction(owner: PersistenceJdbcLifecycleOwner, candidate: VersionBoundPersistencePoolBinding) {
        requireBinding(owner.versionBoundPools === this && bindings.any { it === candidate })
        requireCatalogConstruction() // Same actual constructor extent for every role; no independently minted role grant.
    }

    internal fun shutdownRequested(): Boolean = root.shutdown.get()

    /** Called only after the exact root's permanent seal/native conjunction. No root or managed observer recursion. */
    internal fun poolsEndedForTrust(): Boolean {
        val selected = synchronized(custody) {
            if (!root.shutdown.get() || constructor != null) return false
            state
        }
        return when (selected) {
            // The sealed root prevents the sole constructor from ever entering.
            State.NEW -> true

            State.BOUND -> bindings.all { it.localShutdownObservation() === PoolActorObservation.ENDED }

            State.CONSTRUCTING, State.FAILED -> false // Undisclosed/partially configured shells remain retained, never inferred inert.
        }
    }

    /** Retained even when bind throws. Try every exact retained lifecycle; a failed construction never becomes a release certificate. */
    override fun close() {
        requireConnectionFree()
        root.requestShutdown()
        val outcomes = bindings.asReversed().map { runCatching { it.close() } }
        outcomes.firstOrNull { it.isFailure }?.getOrThrow()
    }

    private fun <T> completed(operation: () -> T): T {
        synchronized(custody) { requireBinding(state === State.BOUND) }
        return operation()
    }

    private fun binding(
        endpoint: ResolvedPersistenceEndpoint,
        role: PersistenceJdbcParticipantRole,
        ordinaryCapacity: Int,
        identity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
    ): VersionBoundPersistencePoolBinding = VersionBoundPersistencePoolBinding.create(
        this,
        VersionBoundPersistencePoolMaterial.create(endpoint, role, ordinaryCapacity, identity),
    )

    override fun toString(): String = "VersionBoundPersistencePools(redacted)"

    private enum class State { NEW, CONSTRUCTING, BOUND, FAILED }

    companion object {
        internal fun create(
            root: PersistenceJdbcDriverRoot,
            endpoint: ResolvedPersistenceEndpoint,
            ordinaryCapacity: Int,
            identity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
        ): VersionBoundPersistencePools = VersionBoundPersistencePools(root, endpoint, ordinaryCapacity, identity)
    }
}

/** The exact raw shell and lifecycle are retained before validation; no raw getter exists. */
internal class VersionBoundPersistencePoolBinding private constructor(
    private val composition: VersionBoundPersistencePools,
    internal val material: VersionBoundPersistencePoolMaterial,
) {
    private val custody = Any()
    private var claimed = false
    private var constructor: Thread? = null
    private var completed = false
    private var pool: HikariDataSource? = null
    private var lifecycle: PoolLifecycle? = null
    private var source: GuardedDataSource? = null
    private var lower: DataSource? = null
    private var captured: VersionBoundPersistencePoolDescriptor? = null

    internal fun construct(owner: PersistenceJdbcLifecycleOwner, launchProfile: PersistencePoolLaunchProfile): GuardedDataSource {
        begin(owner)
        var returned = false
        try {
            val prepared = material.construct(owner, this, launchProfile)
            returned = true
            return prepared
        } finally {
            finish(returned)
        }
    }

    private fun begin(owner: PersistenceJdbcLifecycleOwner) {
        composition.requireConstruction(owner, this)
        synchronized(custody) {
            requireBinding(!claimed && !composition.shutdownRequested())
            claimed = true // Before even HikariDataSource's inert constructor; failed/ambiguous creation is not forgotten.
            constructor = Thread.currentThread()
        }
    }

    internal fun newPool(
        owner: PersistenceJdbcLifecycleOwner,
        endpoint: ResolvedPersistenceEndpoint,
        capacity: Int,
        role: PersistenceJdbcParticipantRole,
        ordinarySettings: HikariConfig?,
    ): HikariDataSource {
        composition.requireConstruction(owner, this)
        synchronized(custody) {
            requireConstructing()
            requireBinding(pool == null && material.matchesInput(endpoint, capacity, role) && ordinarySettings == null)
        }
        // Hikari's default constructor can read this file before any setters. No ambient configuration source is permitted.
        requireBinding(System.getProperty("hikaricp.configurationFile") == null)
        val created = HikariDataSource()
        synchronized(custody) {
            requireConstructing()
            pool = created // Before returning the shell, constructing the lifecycle/lower source, validation or callback installation.
        }
        return created
    }

    internal fun retain(source: GuardedDataSource, lifecycle: PoolLifecycle) = synchronized(custody) {
        requireConstructing()
        requireBinding(pool != null && this.lifecycle == null && this.source == null)
        this.lifecycle = lifecycle
        this.source = source
    }

    internal fun requireRetainedPool(owner: PersistenceJdbcLifecycleOwner, candidate: HikariDataSource) {
        composition.requireConstruction(owner, this)
        synchronized(custody) {
            requireConstructing()
            requireBinding(pool === candidate && lifecycle == null)
        }
    }

    internal fun configure(lower: DataSource) {
        val selected = synchronized(custody) {
            requireConstructing()
            requireBinding(this.lower == null)
            this.lower = lower
            checkNotNull(pool)
        }
        material.configure(selected)
        selected.dataSource = lower
        selected.validate() // Cold normalization on THIS retained shell, not a parallel descriptor-only HikariConfig.
        val descriptor = material.capture(selected, lower)
        synchronized(custody) { captured = descriptor }
    }

    private fun finish(returned: Boolean) = synchronized(custody) {
        requireConstructing()
        completed = returned
        constructor = null
    }

    internal fun configurationMatches(): Boolean {
        val inputs = synchronized(custody) {
            val actualPool = pool ?: return false
            val actualLower = lower ?: return false
            val descriptor = captured ?: return false
            Triple(actualPool, actualLower, descriptor)
        }
        return material.matches(inputs.first, inputs.second, inputs.third)
    }

    internal fun descriptor(): VersionBoundPersistencePoolDescriptor {
        val actual = synchronized(custody) {
            requireBinding(completed && constructor == null)
            checkNotNull(lifecycle)
        }
        requireBinding(actual.businessReady() && configurationMatches())
        return synchronized(custody) { checkNotNull(captured) }
    }

    internal fun dataSource(): GuardedDataSource = synchronized(custody) {
        requireBinding(completed && constructor == null)
        checkNotNull(source)
    }

    internal fun ownsLifecycle(candidate: PoolLifecycle): Boolean = synchronized(custody) { lifecycle === candidate }

    internal fun localShutdownObservation(): PoolActorObservation {
        val actual = synchronized(custody) {
            if (!completed || constructor != null) return PoolActorObservation.PENDING
            lifecycle ?: return PoolActorObservation.PENDING
        }
        return actual.localShutdownForTrust()
    }

    internal fun close() {
        val actual = synchronized(custody) { lifecycle }
        actual?.closePool() // Only the retained actual lifecycle; no direct raw close or invented completion observation.
    }

    private fun requireConstructing() {
        check(Thread.holdsLock(custody))
        requireBinding(claimed && constructor === Thread.currentThread())
    }

    override fun toString(): String = "VersionBoundPersistencePoolBinding(redacted)"

    companion object {
        internal fun create(composition: VersionBoundPersistencePools, material: VersionBoundPersistencePoolMaterial): VersionBoundPersistencePoolBinding =
            VersionBoundPersistencePoolBinding(composition, material)
    }
}

private fun requireBinding(condition: Boolean) {
    if (!condition) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
}
