package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePoolDescriptor
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundTestActivationConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalPreparedRecoveryV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.ComplaintOwnerDeleteAllAdmissionPolicy
import me.manga.kira.backend.security.VersionBoundTestComplaintConsumerConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDenialAuthorityPolicyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalDenialAuthorityPolicyV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveInitialCheckpointV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureV1
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Complete cold PRE_CUTOVER_TEST/memory/one-declared-instance inventory and full D, separate from LIVE.
 * Exact acquired consumers, P/TEST J, completed pools, shared lanes, reader and activation policy are
 * mandatory. This is not registered/projected TEST state, a process binding or a current capability.
 * Matching control/run observations still require provenance; this owner does not promote them.
 */
internal class VersionBoundTestNamespaceProcessV1 private constructor(
    val consumers: VersionBoundTestComplaintConsumerConfigurationV1,
    val pools: VersionBoundPersistencePools,
    internal val implementationSchema: Int,
    internal val desiredGeneration: Long,
    internal val databaseIdentity: UUID,
    internal val restoreIdentity: UUID,
    val publicationLanes: JournalPublicationLanesV1,
    val catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
    val catalogActivation: VersionBoundTestActivationConfigurationV1,
    val ordinarySeal: VersionBoundTestOrdinarySealV1?,
    val ordinaryDenial: TestOrdinaryDenialAuthorityPolicyV1?,
    val activeFirstCut: VersionBoundTestActiveFirstCutV1?,
    val activeCutoffPublication: VersionBoundTestActiveCutoffPublicationV1?,
    val activeFirstCutSuccessor: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveFirstCutSuccessorV1?,
    val initialCheckpoint: VersionBoundTestActiveInitialCheckpointV1?,
    val activeRecurrent: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveRecurrentV1?,
    val activeOrdinarySealRecovery: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOrdinarySealRecoveryV1?,
    val terminalDenial: TestTerminalDenialAuthorityPolicyV1?,
    val initialCheckpointCreate: VersionBoundTestInitialCheckpointCreateV1?,
    val activeOwnerDeleteQueue: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOwnerDeleteQueueV1?,
    val initialCheckpointDeletion: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointDeletionV1?,
) {
    private val retainedPools: List<VersionBoundPersistencePoolDescriptor>
    private val canonical: ByteArray
    private val hash: ByteArray
    private val registrationClaimed = AtomicBoolean()
    private var erasureOriginal: TestRunErasureV1? = null

    init {
        requireOwners()
        // Freeze absence too: the same ordinary graph cannot acquire new CREATE semantics after D.
        pools.retainTestInitialCheckpointCreate(initialCheckpointCreate)
        pools.retainTestInitialCheckpointDeletion(initialCheckpointDeletion)
        // Same existing registry only; local N/R accounting registration is not TEST activation.
        publicationLanes.retainTestJournal(consumers.journalConfiguration)
        retainedPools = pools.descriptors()
        canonical = ComplaintEffectiveTestConfigurationV1.encode(this)
        hash = MessageDigest.getInstance("SHA-256").digest(canonical)
        requireUnchangedConfiguration()
    }

    /** Defensive historical configuration bytes/hash, not assertions about current control or TEST state. */
    fun canonicalBytes(): ByteArray = canonical.copyOf()

    fun configurationHashBytes(): ByteArray = hash.copyOf()

    /** Only the complete root derives this diagnostic full-D value. It carries no current-use authority. */
    fun desiredSettings(): ComplaintInstallationDesiredSettings.Configured {
        requireUnchangedConfiguration()
        return ComplaintInstallationDesiredSettings.Configured(
            ComplaintInstallationMode.PRE_CUTOVER_TEST,
            implementationSchema,
            desiredGeneration,
            consumers.journalConfiguration.scope,
            databaseIdentity,
            restoreIdentity,
            hash,
        )
    }

    /**
     * Local owner/descriptor checks only; safe inside an owned phase outside its lifecycle monitor.
     * No JSON, crypto, provider, checkout, callbacks or connection-free precondition here. The actual
     * pool descriptor path compares mutable Hikari/lower-source settings before returning its pins.
     */
    fun requireUnchangedConfiguration() {
        requireOwners()
        pools.requireTestInitialCheckpointCreate(initialCheckpointCreate)
        pools.requireTestInitialCheckpointDeletion(initialCheckpointDeletion)
        publicationLanes.requireTestJournal(consumers.journalConfiguration)
        val current = pools.descriptors()
        require(current.size == retainedPools.size && current.indices.all { current[it] === retainedPools[it] }) {
            INVALID_TEST_PROCESS_CONFIGURATION
        }
    }

    /** Normal retained runtime root only. Never changes a named root's permanent seals or launch policy. */
    internal fun requireRegistrationTarget() {
        requireUnchangedConfiguration()
        require(!pools.shutdownRequested()) { INVALID_TEST_PROCESS_CONFIGURATION }
        val coordinator = pools.catalogCoordinator
        require(!coordinator.catalogTestRunActivation && !coordinator.catalogSignerRotationActivation &&
            !coordinator.catalogSignerRotationRecovery && !coordinator.catalogSignerRotationDelivery &&
            !coordinator.catalogSignerRotationAuthoring && !coordinator.catalogGenesisAuthoring &&
            !coordinator.catalogGenesisFinalization && !coordinator.desiredInstallationOperator) { INVALID_TEST_PROCESS_CONFIGURATION }
        pools.ordinary.requireOrdinaryPhaseResource()
        pools.deletion.requireDeletionPhaseResource()
        val names = pools.descriptors().flatMap { it.openings() }.map { it.publicDriverProperties()["user"] }
        require(names.none { it == me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME ||
            it == me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME }) {
            INVALID_TEST_PROCESS_CONFIGURATION
        }
    }

    /** A recovery admission requires a fresh retained process target, not the already-used first-PROJECT graph. */
    internal fun claimInitialRegistration(original: ComplaintTestNamespaceRegistrationAttemptV1) {
        requireConnectionFree()
        requireRegistrationTarget()
        requireRegistration(original.process === this && registrationClaimed.compareAndSet(false, true))
    }

    internal fun claimRecoveryRegistration(original: ComplaintTestNamespaceRecoveryRegistrationAttemptV1) {
        requireConnectionFree()
        requireRegistrationTarget()
        requireRegistration(original.process === this && original.assembly.target === this && registrationClaimed.compareAndSet(false, true))
    }

    /** Fresh ACTIVE origin shares the same one-use process claim with initial/SEALED registration. */
    internal fun claimActiveRegistration(original: ComplaintTestNamespaceActiveRegistrationAttemptV1) {
        requireConnectionFree()
        requireRegistrationTarget()
        requireRegistration(original.process === this && original.assembly.target === this && registrationClaimed.compareAndSet(false, true))
    }

    /** Separately original-owned PREPARED recovery consumes the same fresh-process claim, never a registration capability. */
    internal fun claimTerminalPreparedRecovery(original: CatalogTestRunTerminalPreparedRecoveryV1) {
        requireConnectionFree()
        requireRegistrationTarget()
        requireRegistration(original.process === this && registrationClaimed.compareAndSet(false, true))
    }

    /** Same-lineage partial/PURGED replay, not PREPARED repair or a replacement registration. */
    internal fun claimTestRunErasure(original: TestRunErasureV1) {
        requireConnectionFree(); requireRegistrationTarget()
        requireRegistration(original.process === this && registrationClaimed.compareAndSet(false, true))
        erasureOriginal = original
    }

    internal fun requireTestRunErasure(original: TestRunErasureV1) {
        requireRegistrationTarget()
        requireRegistration(original.process === this && erasureOriginal === original && registrationClaimed.get())
    }

    private fun requireOwners() {
        require(implementationSchema == 1 && desiredGeneration > 0 && isV4(databaseIdentity) && isV4(restoreIdentity)) {
            INVALID_TEST_PROCESS_CONFIGURATION
        }
        require((pools.epochRotation == null) == (activeFirstCut == null) && consumers.coordinationMode == "memory" && consumers.declaredInstances == 1) {
            INVALID_TEST_PROCESS_CONFIGURATION
        }
        require(consumers.jwt.boundUserKeyProvider != null && consumers.journalRouting.journalConfiguration === consumers.journalConfiguration) {
            INVALID_TEST_PROCESS_CONFIGURATION
        }
        val journal = consumers.journalConfiguration
        val writer = journal.declaration().writer
        require(journal.scope.testOnly && isV4(journal.scope.id)) { INVALID_TEST_PROCESS_CONFIGURATION }
        require(writer.databaseIdentity == databaseIdentity.toString() && writer.restoreIdentity == restoreIdentity.toString()) {
            INVALID_TEST_PROCESS_CONFIGURATION
        }
        val deleteAll = consumers.ownerDeleteAllPolicy
        require(if (journal.ownerDeleteAll) deleteAll is ComplaintOwnerDeleteAllAdmissionPolicy.Bounded && deleteAll.scope == journal.scope &&
            deleteAll.memberLimit == consumers.ownerCreatePolicy.memberLimit && deleteAll.pruneBatch == consumers.ownerCreatePolicy.pruneBatch
            else deleteAll === ComplaintOwnerDeleteAllAdmissionPolicy.Disabled) { INVALID_TEST_PROCESS_CONFIGURATION }
        require(
            consumers.ownerCreatePolicy.memberLimit == consumers.ownerEditPolicy.memberLimit &&
                consumers.ownerCreatePolicy.memberLimit == consumers.ownerDeletePolicy.memberLimit &&
                consumers.ownerCreatePolicy.pruneBatch == consumers.ownerEditPolicy.pruneBatch &&
                consumers.ownerCreatePolicy.pruneBatch == consumers.ownerDeletePolicy.pruneBatch,
        ) { INVALID_TEST_PROCESS_CONFIGURATION }
        require((activeFirstCut == null) == (activeCutoffPublication == null)) { INVALID_TEST_PROCESS_CONFIGURATION }
        activeFirstCut?.requireRetained(pools, journal, ordinarySeal)
        activeFirstCutSuccessor?.requireRetained(pools, journal, activeFirstCut, ordinarySeal)
        activeOrdinarySealRecovery?.requireRetained(pools, consumers.journalRouting, activeFirstCut, ordinarySeal)
        activeCutoffPublication?.requireRetained(consumers.journalRouting, publicationLanes)
        require(initialCheckpoint == null || activeFirstCut != null && activeCutoffPublication != null) { INVALID_TEST_PROCESS_CONFIGURATION }
        initialCheckpoint?.requireRetained(consumers.journalRouting, pools, ordinarySeal)
        require(activeRecurrent == null || initialCheckpoint != null && activeFirstCut != null && activeCutoffPublication != null) { INVALID_TEST_PROCESS_CONFIGURATION }
        activeRecurrent?.requireRetained(consumers.journalRouting, pools, ordinarySeal)
        initialCheckpointCreate?.requireRetained(pools, consumers.journalRouting, initialCheckpoint)
        initialCheckpointDeletion?.requireRetained(pools, consumers.journalRouting, initialCheckpoint, activeCutoffPublication)
        require(activeOwnerDeleteQueue == null || initialCheckpoint != null && activeFirstCut != null) { INVALID_TEST_PROCESS_CONFIGURATION }
        activeOwnerDeleteQueue?.requireRetained(consumers.journalRouting, pools)
        catalogActivation.requireRetained(pools, catalogReadback, journal)
        ordinarySeal?.requireRetained(consumers.journalRouting, publicationLanes)
        ordinarySeal?.requireCatalogReferences(catalogActivation.putAuthority, catalogActivation.signAuthority)
        require(ordinarySeal == null || ordinarySeal.retention.environment == catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment) {
            INVALID_TEST_PROCESS_CONFIGURATION
        }
        require(ordinaryDenial == null || ordinarySeal != null) { INVALID_TEST_PROCESS_CONFIGURATION }
        ordinaryDenial?.requireEnvironment(catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment)
        ordinaryDenial?.requireJournal(journal)
        require(terminalDenial == null || ordinaryDenial != null && ordinarySeal != null) { INVALID_TEST_PROCESS_CONFIGURATION }
        terminalDenial?.requireEnvironment(catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment)
        terminalDenial?.requireJournal(journal)
    }

    override fun toString(): String = "VersionBoundTestNamespaceProcessV1(PRE_CUTOVER_TEST,memory,redacted,no-authority)"

    companion object {
        /** No supplied D/preimage, LIVE conversion or current-state input. Any optional seal owner is retained BEFORE D. */
        fun fromRetained(
            consumers: VersionBoundTestComplaintConsumerConfigurationV1,
            pools: VersionBoundPersistencePools,
            implementationSchema: Int,
            desiredGeneration: Long,
            databaseIdentity: UUID,
            restoreIdentity: UUID,
            publicationLanes: JournalPublicationLanesV1,
            catalogReadback: VersionBoundCatalogReadbackConfigurationV1,
            catalogActivation: VersionBoundTestActivationConfigurationV1,
            ordinarySeal: VersionBoundTestOrdinarySealV1? = null,
            ordinaryDenial: TestOrdinaryDenialAuthorityPolicyV1? = null,
            activeFirstCut: VersionBoundTestActiveFirstCutV1? = null,
            activeCutoffPublication: VersionBoundTestActiveCutoffPublicationV1? = null,
            activeFirstCutSuccessor: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveFirstCutSuccessorV1? = null,
            initialCheckpoint: VersionBoundTestActiveInitialCheckpointV1? = null,
            activeRecurrent: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveRecurrentV1? = null,
            activeOrdinarySealRecovery: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOrdinarySealRecoveryV1? = null,
            terminalDenial: TestTerminalDenialAuthorityPolicyV1? = null,
            initialCheckpointCreate: VersionBoundTestInitialCheckpointCreateV1? = null,
            activeOwnerDeleteQueue: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOwnerDeleteQueueV1? = null,
            initialCheckpointDeletion: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointDeletionV1? = null,
        ): VersionBoundTestNamespaceProcessV1 {
            requireConnectionFree()
            return VersionBoundTestNamespaceProcessV1(
                consumers, pools, implementationSchema, desiredGeneration, databaseIdentity, restoreIdentity,
                publicationLanes, catalogReadback, catalogActivation, ordinarySeal, ordinaryDenial, activeFirstCut, activeCutoffPublication, activeFirstCutSuccessor, initialCheckpoint, activeRecurrent, activeOrdinarySealRecovery, terminalDenial = terminalDenial,
                initialCheckpointCreate = initialCheckpointCreate,
                activeOwnerDeleteQueue = activeOwnerDeleteQueue,
                initialCheckpointDeletion = initialCheckpointDeletion,
            )
        }

        private fun isV4(value: UUID): Boolean = value.version() == 4 && value.variant() == 2
    }
}

internal const val INVALID_TEST_PROCESS_CONFIGURATION = "Invalid version-bound TEST namespace process configuration"
