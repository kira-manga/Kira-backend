package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor

/** Only the original running freeze owner retains this attempt. Reconstructing these inputs cannot grant phase entry. */
internal class CatalogGenesisFreezeAttemptV1 internal constructor(
    private val operator: CatalogGenesisFreezeV1,
    private val coordinator: CatalogCoordinatorPersistence,
    internal val inputs: CatalogGenesisFreezeInputsV1,
    internal val budget: PersistenceTimeBudget,
) {
    private val caller = Thread.currentThread()
    private val ownership = coordinator.ownership
    private var path: PersistencePhasePath? = null
    private var entered = false
    private var failed = false

    internal fun prepare(): UnverifiedGenesisPreparation {
        select(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE)
        return try {
            coordinator.genesis.prepareGenesis(this)
        } finally {
            path = null
        }
    }

    internal fun snapshot(): UnverifiedGenesisPreparation {
        select(PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT)
        return try {
            coordinator.snapshot.loadGenesisPreparation(this)
        } finally {
            path = null
        }
    }

    internal fun persistSignature(local: UnverifiedGenesisPreparation, signature: ByteArray): UnverifiedGenesisPreparation {
        select(PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE)
        return try {
            coordinator.genesis.persistGenesisSignature(this, local, signature)
        } finally {
            path = null
        }
    }

    private fun select(selected: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireCatalogFreeze(path == null, CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        path = selected
        entered = false
    }

    internal fun requirePhaseEntry(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath) {
        requireRunning()
        requireCatalogFreeze(
            selected === ownership && coordinator.catalogGenesisAuthoring && path === selectedPath && !entered,
            CatalogGenesisFreezeFailureV1.PROCESS_REFUSED,
        )
        entered = true
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireCatalogFreeze(
            selected === ownership && jdbc.dataSource === coordinator.dataSource && coordinator.catalogGenesisAuthoring && path != null,
            CatalogGenesisFreezeFailureV1.PROCESS_REFUSED,
        )
        coordinator.requireResources()
    }

    /** Actual session authentication on THIS phase's original connection, not a principal nominated in an input file. */
    internal fun authenticate(selected: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(selected, jdbc)
        requireCatalogFreeze(entered && PersistencePhaseOwnership.current() != null, CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        val observed = jdbc.query(
            AUTHENTICATE,
            ResultSetExtractor { row ->
                row.next() && row.getBoolean("authenticated") && !row.wasNull() && !row.next()
            },
            inputs.request.database.database,
        )
        requireCatalogFreeze(observed == true, CatalogGenesisFreezeFailureV1.AUTHENTICATION_REFUSED)
        requirePersistence(selected, jdbc)
    }

    internal fun requireRunning() {
        requireCatalogFreeze(!failed && caller === Thread.currentThread() && operator.owns(this), CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        operator.requireRunning()
    }

    internal fun abort() {
        failed = true
    }

    override fun toString(): String = "CatalogGenesisFreezeAttemptV1(original-owner-and-inputs,redacted)"

    companion object {
        private const val AUTHENTICATE = """
            SELECT session_user = 'kira_complaint_catalog_operator'
                AND current_user = 'kira_complaint_catalog_operator'
                AND current_database() = ? AS authenticated
        """
    }
}
