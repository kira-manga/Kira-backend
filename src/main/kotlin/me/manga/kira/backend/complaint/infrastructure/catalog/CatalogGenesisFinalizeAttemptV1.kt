package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor

/** The one real TARGET invocation selects each fixed phase; equal-D processes and reconstructed evidence cannot enter. */
internal class CatalogGenesisFinalizeAttemptV1 internal constructor(
    private val operator: CatalogGenesisFinalizeV1,
    internal val process: VersionBoundComplaintProcessConfiguration,
    private val release: CatalogGenesisFinalizeReleaseV1,
    internal val budget: PersistenceTimeBudget,
) {
    private val caller = Thread.currentThread()
    private val coordinator = process.pools.catalogCoordinator
    private val ownership = coordinator.ownership
    private val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }
        .openings().map { it.publicDriverProperties() }
    private val username = checkNotNull(openings.map { it["user"] }.distinct().single()).also {
        requireFinalization(
            it != VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME &&
                it != VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME,
        )
    }
    private val database = checkNotNull(openings.map { it["PGDBNAME"] }.distinct().single())
    internal val expected = CatalogGenesisInitialLiveBinding.fromRetained(process)
    private var refresh: CatalogReadbackRefreshCustodyV1.Attempt? = null
    private var readback: CatalogDualLocationVerifier.GenesisReadback? = null
    private var path: PersistencePhasePath? = null
    private var entered = false
    private var failed = false
    private var completed = false
    private var projected = false
    internal val phaseBudget: PersistenceTimeBudget get() = checkNotNull(refresh?.finalizationBudget)

    internal fun attach(producer: CurrentAcceptedCatalogRefreshV1, selected: CatalogReadbackRefreshCustodyV1.Attempt) {
        requireConnectionFree()
        requireRunning()
        requireFinalization(refresh == null && operator.ownsRefresh(producer))
        selected.requireFinalizer(this)
        refresh = selected
    }

    internal fun snapshot(initial: ByteArray, current: ByteArray, policy: CatalogReadbackPolicy): LocalCatalogSnapshot {
        select(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)
        return try {
            coordinator.snapshot.loadFinalizing(initial, current, policy, this)
        } finally {
            path = null
        }
    }

    internal fun preserveReadback(producer: CurrentAcceptedCatalogRefreshV1, fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireConnectionFree()
        requireRunning()
        requireFinalization(operator.ownsRefresh(producer) && readback == null && path == null)
        checkNotNull(refresh).requireProviderClosed()
        release.preserveReadback(process, fresh)
        requireRunning()
        readback = fresh
    }

    internal fun complete(fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireBarrier(fresh, expected)
        requireFinalization(!completed && !projected)
        select(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE)
        try {
            coordinator.genesis.completeFinalizing(fresh, expected, this)
            completed = true
        } finally {
            path = null
        }
    }

    internal fun project(fresh: CatalogDualLocationVerifier.GenesisReadback): ProcessBoundCatalogGenesisProjection {
        requireBarrier(fresh, expected)
        requireFinalization(!projected && (fresh.resume != GenesisResume.PREPARED || completed))
        select(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT)
        return try {
            coordinator.genesis.projectFinalizing(fresh, expected, this).also { projected = true }
        } finally {
            path = null
        }
    }

    internal fun preserveProjection(fresh: CatalogDualLocationVerifier.GenesisReadback, projection: ProcessBoundCatalogGenesisProjection) {
        requireConnectionFree()
        requireBarrier(fresh, expected)
        requireFinalization(projected && path == null)
        release.projected(process, fresh, projection)
        requireRunning()
    }

    /** Includes the original SDK/slot and open-custody identity, not a supplied success Boolean. No I/O under a phase. */
    internal fun requireBarrier(fresh: CatalogDualLocationVerifier.GenesisReadback, binding: CatalogGenesisInitialLiveBinding) {
        requireRunning()
        requireFinalization(readback === fresh && expected === binding)
        checkNotNull(refresh).requireProviderClosed()
        release.requireBarrier(process, fresh)
    }

    private fun select(selected: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        checkNotNull(refresh).requireRunning()
        requireFinalization(path == null)
        path = selected
        entered = false
    }

    internal fun requirePhaseEntry(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath) {
        requireRunning()
        checkNotNull(refresh).requireRunning()
        requireFinalization(selected === ownership && coordinator.catalogGenesisFinalization && path === selectedPath && !entered)
        if (selectedPath != PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT) requireBarrier(checkNotNull(readback), expected)
        entered = true
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        checkNotNull(refresh).requireRunning()
        requireFinalization(
            selected === ownership && jdbc.dataSource === coordinator.dataSource && coordinator.catalogGenesisFinalization && path != null,
        )
        expected.requirePersistence(selected, jdbc)
    }

    internal fun authenticate(selected: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(selected, jdbc)
        requireFinalization(entered && PersistencePhaseOwnership.current() != null)
        val authenticated = jdbc.query(
            "SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() },
            username,
            username,
            database,
        )
        requireFinalization(authenticated == true, CatalogGenesisFinalizeFailureV1.AUTHENTICATION_REFUSED)
        requirePersistence(selected, jdbc)
    }

    internal fun requireRunning() {
        requireFinalization(!failed && caller === Thread.currentThread() && operator.owns(this), CatalogGenesisFinalizeFailureV1.PROCESS_REFUSED)
        operator.requireRunning()
        refresh?.finalizationBudget?.remainingMillis(1) // Durable handoffs/outcome and slot finish cannot outlive the unchanged reader cap either.
        process.requireUnchangedConfiguration()
    }

    internal fun abort() {
        failed = true
    }

    override fun toString(): String = "CatalogGenesisFinalizeAttemptV1(original-target,redacted,no-portable-authority)"
}
