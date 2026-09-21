package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.JournalWriterV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalDocumentV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogRestoreInventoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationAccountingV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationHeadV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationRecordV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunActivationRunV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunNoticeSeedV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisCreationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationEnvelopeV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEncodingV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintProcessPoolFixture
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredCapacityInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredCatalogChainLimitsV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredCatalogSigningKeyInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.DesiredSecretReferenceV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestDeploymentInputFixture
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogInventoryChainVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveFirstCutSuccessorV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveFirstCutV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveInitialCheckpointV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOwnerDeleteQueueV1
import me.manga.kira.backend.security.BoundTestComplaintConsumerFixture
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.boundConsumerTestSettings
import me.manga.kira.backend.security.fullTestJournal
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.IdentityHashMap
import java.util.UUID

internal enum class ActivationEvidencePrefix { GENESIS, ROTATED, INVENTORY_ROTATED, PENDING_OVERLAP }

/** Same synthetic fixture P selected before either LIVE first-D or TEST intake; never a counter rewrite after capture. */
internal fun testActivationCapacityPolicy(original: ComplaintCapacityPolicyV1, manifestPublication: Boolean): ComplaintCapacityPolicyV1 =
    ComplaintCapacityPolicyV1.of(
        original.hardLimit.with(ComplaintCapacityCounter.STORAGE_BYTES, 2_000_000_000),
        original.creationLimit.with(ComplaintCapacityCounter.STORAGE_BYTES, 1_800_000_000),
        // The opt-in two-chunk history uses the real lower-core enrollment producer501 times.
        if (manifestPublication) 1_000 else original.dailyEnrollmentLimit,
    )

/** Existing cold pools/consumers/lanes only. No new provider, JDBC, process or concurrency harness. */
internal fun withActivationEvidence(
    prefix: ActivationEvidencePrefix = ActivationEvidencePrefix.INVENTORY_ROTATED,
    selectedSigner: String = if (prefix == ActivationEvidencePrefix.GENESIS) "catalog-old" else "catalog-new",
    testActivation: Boolean = false,
    ordinarySealHttp: TestOrdinarySealHttpFixtureV1? = null,
    ownerDeleteAll: Boolean = false,
    action: (CatalogTestRunActivationEvidenceFixture) -> Unit,
) {
    val rotations = OfflineCatalogRotationFixture.chain()
    val registry = rotations.genesis.manifest.initialWriterRegistry
    val original = fullTestJournal().declaration()
    val journal = TestOwnerDeleteJournalConfigurationV1.of(
        original.copy(
            writer = JournalWriterV1(registry.databaseIdentity, registry.restoreIdentity, registry.eventWriter.generationId),
            limits = original.limits.copy(capacity = original.limits.capacity.copy(maximumRetainedVersions = 10_000)),
        ),
        ownerDeleteAll = ownerDeleteAll,
    )
    ComplaintProcessPoolFixture(testActivation = testActivation).use { database ->
        val pools = database.bind()
        JournalPublicationLanesV1(journal).use { lanes ->
            CatalogTestRunActivationEvidenceFixture(rotations, prefix, selectedSigner, journal, pools, lanes, ordinarySealHttp = ordinarySealHttp).use(action)
        }
    }
}

/** The same signed evidence, consumers and lanes on the existing original TLS TEST-only root. */
internal fun withActivationEvidence(
    tls: VersionBoundPersistenceConnectedFixture,
    prefix: ActivationEvidencePrefix = ActivationEvidencePrefix.INVENTORY_ROTATED,
    selectedSigner: String = if (prefix == ActivationEvidencePrefix.GENESIS) "catalog-old" else "catalog-new",
    createGlobal: Int = 2,
    ordinarySealHttp: TestOrdinarySealHttpFixtureV1? = null,
    ownerDeleteAll: Boolean = false,
    ordinaryDrain: TestOrdinaryDrainFixtureInputsV1? = null,
    registeredAdminDelete: Boolean = false,
    registeredAdminBatchDelete: Boolean = false,
    activeFirstCut: Boolean = false,
    activeSealRecovery: Boolean = false,
    ordinaryRawHttp: TestActiveOrdinaryRawHttpV1? = null,
    activeFirstCutSuccessor: Boolean = false,
    globalPredecessor: TestGlobalScanPredecessorV1? = null,
    action: (CatalogTestRunActivationEvidenceFixture) -> Unit,
) {
    require(globalPredecessor == null || (prefix == ActivationEvidencePrefix.GENESIS && selectedSigner == "catalog-old"))
    val rotations = globalPredecessor?.rotations ?: OfflineCatalogRotationFixture.chain()
    val registry = rotations.genesis.manifest.initialWriterRegistry
    val original = fullTestJournal().declaration()
    val declaration = original.copy(
        writer = JournalWriterV1(registry.databaseIdentity, registry.restoreIdentity, registry.eventWriter.generationId),
        limits = (ordinaryDrain?.limits(original.limits)
            ?: original.limits.copy(capacity = original.limits.capacity.copy(maximumRetainedVersions = 10_000))).let { limits ->
            if (ordinaryRawHttp?.shortInitialCheckpointFreshness == true) {
                require(ordinaryRawHttp.initialCheckpointCreate != null)
                limits.copy(deadlines = limits.deadlines.copy(scanMillis = 30_000, scanCadenceMillis = 30_000, checkpointMaxAgeMillis = 30_000))
            } else limits
        },
    )
    val journal = if (activeFirstCut) {
        require(ordinarySealHttp?.protectedIntake == true && ordinaryDrain != null)
        TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(declaration)
    } else if (registeredAdminBatchDelete) {
        require(ordinarySealHttp != null && ordinaryDrain != null)
        TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(declaration)
    } else if (registeredAdminDelete) {
        require(ordinarySealHttp != null && ordinaryDrain != null)
        TestOwnerDeleteJournalConfigurationV1.registeredAdminErasure(declaration)
    } else TestOwnerDeleteJournalConfigurationV1.of(declaration, ownerDeleteAll = ownerDeleteAll)
    JournalPublicationLanesV1(journal).use { lanes ->
        CatalogTestRunActivationEvidenceFixture(rotations, prefix, selectedSigner, journal, tls.pools, lanes, createGlobal, ordinarySealHttp,
            if (ordinarySealHttp?.protectedIntake == true) tls else null, ordinaryDrain, activeFirstCut, activeSealRecovery, ordinaryRawHttp, activeFirstCutSuccessor,
            globalPredecessor).use(action)
    }
}

/**
 * TEST manifests and legacy prefixes are independently assembled from raw fixture inputs; the
 * opted-in G1 prefix instead retains exact actual AUTHOR/first-D/refresh bytes. Signatures reuse in-memory PSS keys and
 * the existing independent LP32 frame. Metadata remains synthetic, never an AWS/registration proof.
 */
internal class CatalogTestRunActivationEvidenceFixture(
    val rotations: RotationChainFixture,
    prefixKind: ActivationEvidencePrefix,
    val signerId: String,
    val journal: TestOwnerDeleteJournalConfigurationV1,
    val pools: VersionBoundPersistencePools,
    private val lanes: JournalPublicationLanesV1,
    createGlobal: Int = 2,
    ordinarySealHttp: TestOrdinarySealHttpFixtureV1? = null,
    intakeTls: VersionBoundPersistenceConnectedFixture? = null,
    private val ordinaryDrain: TestOrdinaryDrainFixtureInputsV1? = null,
    activeFirstCut: Boolean = false,
    activeSealRecovery: Boolean = false,
    private val ordinaryRawHttp: TestActiveOrdinaryRawHttpV1? = null,
    activeFirstCutSuccessor: Boolean = false,
    private val globalPredecessor: TestGlobalScanPredecessorV1? = null,
) : AutoCloseable {
    init {
        // The optional denial input is selected BEFORE full D and all actual protected acquisitions.
        require(ordinaryDrain == null || ordinarySealHttp != null)
        require(ordinaryRawHttp == null || activeFirstCut)
        require(!activeFirstCutSuccessor || activeFirstCut)
        require(ordinaryRawHttp?.initialCheckpoint == null || (activeFirstCut && ordinarySealHttp?.protectedIntake == true))
        require(ordinaryRawHttp?.initialCheckpointCreate == null || ordinaryRawHttp.initialCheckpoint != null)
        require(ordinaryRawHttp?.shortInitialCheckpointFreshness != true || ordinaryRawHttp.initialCheckpointCreate != null)
        require(ordinaryRawHttp?.activeOwnerDeleteQueue == null || ordinaryRawHttp?.initialCheckpoint != null)
        require(!activeSealRecovery || activeFirstCut)
        require(ordinaryRawHttp?.activeSealRecovery == null || activeSealRecovery)
        require(!activeFirstCut || (ordinarySealHttp?.protectedIntake == true && intakeTls != null &&
            ordinaryDrain != null && journal.registeredAdminBatchDelete && pools.epochRotation != null))
    }
    val activeFirstCutInput = if (activeFirstCut) TestActiveFirstCutInputFixtureV1.input() else null
    val activeFirstCutSuccessorInput = if (activeFirstCutSuccessor) TestActiveFirstCutSuccessorInputFixtureV1.input() else null
    val activeSealRecoveryInput = if (activeSealRecovery) ordinaryRawHttp?.activeSealRecovery ?: TestActiveSealRecoveryInputFixtureV1.input() else null
    val initial = globalPredecessor?.initial ?: OfflineTrustBundleFixture.bytes(rotations.initial)
    val current = globalPredecessor?.current ?: OfflineTrustBundleFixture.bytes(rotations.current)
    val policy = globalPredecessor?.policy ?: OfflineCatalogRotationFixture.policy()
    private val inventoryChain = OfflineCatalogInventoryFixture.chain(base = rotations)
    val prefix: List<ByteArray> = when (prefixKind) {
        ActivationEvidencePrefix.GENESIS -> listOf(globalPredecessor?.envelope ?: rotations.bytes().first())
        ActivationEvidencePrefix.ROTATED -> rotations.bytes()
        ActivationEvidencePrefix.PENDING_OVERLAP -> rotations.bytes().take(2)
        ActivationEvidencePrefix.INVENTORY_ROTATED -> inventoryRotationPrefix()
    }
    val inventory: CatalogRestoreInventoryV1 = if (prefixKind == ActivationEvidencePrefix.INVENTORY_ROTATED) {
        inventoryChain.generations.last().manifest.restoreInventory
    } else {
        CatalogRestoreInventoryV1(emptyList(), emptyList())
    }
    val reader = VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
        initial, current, policy, Sha256.hex(prefix.first()), S3CatalogReadbackLimits(), 600_000, pageSize = 1,
    )
    private val consumers = BoundTestComplaintConsumerFixture(journal).let { fixture ->
        val original = fixture.base.capacity
        // N=501/R=10,000 needs 839,125,696 storage units across PREPARE/projection/reserve.
        // Select this synthetic P BEFORE the consumers and full D exist, never by changing frozen limits.
        val capacity = if (pools.catalogCoordinator.catalogTestRunActivation) {
            testActivationCapacityPolicy(original, ordinarySealHttp?.manifestPublication == true)
        } else original
        fixture.configuration(settings = boundConsumerTestSettings(
            enrollmentGlobal = ordinarySealHttp?.protectedEnrollmentGlobalPerHour ?: 2, createGlobal = createGlobal), capacity = capacity)
    }
    private val activation = FullTestCatalogInputs.activation(
        pools, journal, reader, FullTestCatalogInputs.key(signerId, key(signerId).public.encoded),
        OfflineTrustBundleFixture.registryBytes(rotations.genesis.manifest.initialWriterRegistry),
        activeFirstCut = activeFirstCutInput,
    )
    internal var intakeAssembly: ComplaintTestProcessAssemblyV1? = null
        private set
    private var intakeDocument: ComplaintTestDeploymentDocumentV1? = null
    private var originalIntakeBytes: ByteArray? = null
    private var originalSecretReplies: List<ColdSecretObjectV1> = emptyList()
    private val projectedInitialCheckpoints = mutableListOf<VersionBoundTestActiveInitialCheckpointV1>()
    // process()/processOn() may revisit one cold signer graph with a different desired generation.
    // The pool pins the exact policy instance; keep its independently constructed reader with it.
    private val projectedCheckpointGraphs = IdentityHashMap<VersionBoundPersistencePools,
        Pair<VersionBoundTestActiveInitialCheckpointV1, VersionBoundTestInitialCheckpointCreateV1?>>()
    private val projectedActiveQueues = mutableListOf<VersionBoundTestActiveOwnerDeleteQueueV1>()
    /** Exact raw fixture inputs only; no acquired secret, target, registration or projection is exported. */
    internal fun coldInputBytes(): ByteArray = checkNotNull(originalIntakeBytes).copyOf()
    internal fun coldSecretObjects(): List<ColdSecretObjectV1> = originalSecretReplies.toList()
    // The new specimen starts from a protected document, not this fixture's former owner-only seam.
    private val intakeProcess = if (ordinarySealHttp?.protectedIntake == true) assembleIntake(checkNotNull(intakeTls), ordinarySealHttp, createGlobal) else null
    // Preserve the historical fixture-present and absent profiles byte-for-byte.
    private val ordinarySeal = if (intakeProcess != null) null else ordinarySealHttp?.owner(consumers.journalRouting, lanes,
        reader.chainPolicy.trustBundlePolicy.expectedEnvironment, rotations.genesis.manifest.initialWriterRegistry.catalogWriter)
    private val ordinaryDenial = ordinaryDrain?.authority(journal, reader.chainPolicy.trustBundlePolicy.expectedEnvironment)
    private val terminalDenial = ordinaryDrain?.terminalQuiescence?.authority(journal, reader.chainPolicy.trustBundlePolicy.expectedEnvironment)
    val process = process()
    val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, INSTALLATION_LIMIT)
    val generation = prefix.size + 1L
    val token = "${generation.toString(16).padStart(8, '0')}-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    val creation = OfflineCatalogGenesisCreationV1("catalog-approver-a", 1720000000 + generation * 100)
    val approvals = listOf(
        OfflineCatalogGenesisApprovalV1("catalog-approver-a", creation.createdAtEpochSecond + 10),
        OfflineCatalogGenesisApprovalV1("catalog-approver-b", creation.createdAtEpochSecond + 20),
    )
    val manifest = independentManifest()
    val envelope = signed(manifest)
    val envelopeBytes = bytes(envelope)
    val complete = prefix + envelopeBytes
    val head = CatalogLocalHead(generation, Sha256.hex(envelopeBytes))
    val readbackPolicy = reader.policyAt(Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT))
    val retainedUntil = maxOf(
        readbackPolicy.requiredRetainUntilEpochSecond,
        Instant.ofEpochSecond(creation.createdAtEpochSecond).atOffset(ZoneOffset.UTC).plusYears(10).toEpochSecond(),
    )

    // The genuine prefix reuses the exact already-read G1 retention; no synthetic retention extension.
    val prefixRetainedUntil = globalPredecessor?.retainedUntil ?: retainedUntil

    fun process(desiredGeneration: Long = 7): VersionBoundTestNamespaceProcessV1 {
        if (intakeProcess != null) return processOn(pools, desiredGeneration)
        val writer = journal.declaration().writer
        return VersionBoundTestNamespaceProcessV1.fromRetained(
            consumers, pools, 1, desiredGeneration, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity),
            lanes, reader, activation, ordinarySeal, ordinaryDenial, terminalDenial = terminalDenial,
        )
    }

    /** Reuses the original signed prefix/configuration; no randomized re-signing changes its raw identity. */
    fun processOn(pools: VersionBoundPersistencePools, desiredGeneration: Long = 7): VersionBoundTestNamespaceProcessV1 {
        val native = intakeProcess
        if (native != null) {
            if (pools === native.pools && desiredGeneration == native.desiredGeneration) return native
            val selected = FullTestCatalogInputs.activation(pools, native.consumers.journalConfiguration, native.catalogReadback,
                FullTestCatalogInputs.key(signerId, key(signerId).public.encoded),
                OfflineTrustBundleFixture.registryBytes(rotations.genesis.manifest.initialWriterRegistry), activeFirstCut = activeFirstCutInput)
            val firstCut = activeFirstCutInput?.let {
                VersionBoundTestActiveFirstCutV1.fromRetained(it, pools, native.consumers.journalConfiguration, checkNotNull(native.ordinarySeal))
            }
            val successor = activeFirstCutSuccessorInput?.let {
                VersionBoundTestActiveFirstCutSuccessorV1.fromRetained(it, pools, native.consumers.journalConfiguration,
                    checkNotNull(firstCut), checkNotNull(native.ordinarySeal))
            }
            val sealRecovery = activeSealRecoveryInput?.let {
                me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOrdinarySealRecoveryV1.fromRetained(
                    it, pools, native.consumers.journalRouting, checkNotNull(firstCut), checkNotNull(native.ordinarySeal),
                )
            }
            val checkpointGraph = native.initialCheckpoint?.let { original ->
                if (pools === native.pools) original to native.initialCheckpointCreate else {
                    projectedCheckpointGraphs.getOrPut(pools) {
                        // Different pools get their own cold recipe from original raw inputs;
                        // repeated calls reuse this exact pair, never the native runtime reader.
                        val raw = checkNotNull(ordinaryRawHttp?.initialCheckpoint)
                        val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(checkNotNull(intakeDocument))
                        check(inputs.initialCheckpoint == raw.input)
                        val checkpoint = VersionBoundTestActiveInitialCheckpointV1.fromIndependentInputs(
                            raw.input, native.consumers.journalRouting, pools, checkNotNull(native.ordinarySeal),
                            inputs.sealerMapping, raw.credentials, inputs.sealerLimits, original.clock, original.nanoTime,
                            raw.sts, raw.kms, raw.s3,
                        ).also { projectedInitialCheckpoints.add(it) }
                        val create = ordinaryRawHttp?.initialCheckpointCreate?.let {
                            VersionBoundTestInitialCheckpointCreateV1.fromIndependentInputs(
                                it, pools, native.consumers.journalRouting, checkpoint)
                        }
                        checkpoint to create
                    }
                }
            }
            val checkpoint = checkpointGraph?.first
            val initialCheckpointCreate = checkpointGraph?.second
            val queue = native.activeOwnerDeleteQueue?.let { original ->
                if (pools === native.pools) original else {
                    val raw = checkNotNull(ordinaryRawHttp?.activeOwnerDeleteQueue)
                    val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(checkNotNull(intakeDocument))
                    check(inputs.activeOwnerDeleteQueue == raw.input)
                    VersionBoundTestActiveOwnerDeleteQueueV1.fromIndependentInputs(
                        raw.input, native.consumers.journalRouting, pools, inputs.sealerMapping, raw.credentials,
                        inputs.sealerLimits, original.clock, original.nanoTime, raw.sts, raw.kms, raw.s3, raw.sqs,
                    ).also { projectedActiveQueues.add(it) }
                }
            }
            return VersionBoundTestNamespaceProcessV1.fromRetained(native.consumers, pools, 1, desiredGeneration,
                native.databaseIdentity, native.restoreIdentity, native.publicationLanes, native.catalogReadback, selected,
                native.ordinarySeal, native.ordinaryDenial, firstCut, native.activeCutoffPublication, activeFirstCutSuccessor = successor, initialCheckpoint = checkpoint, activeOrdinarySealRecovery = sealRecovery, terminalDenial = native.terminalDenial,
                initialCheckpointCreate = initialCheckpointCreate, activeOwnerDeleteQueue = queue)
        }
        val writer = journal.declaration().writer
        val activation = FullTestCatalogInputs.activation(
            pools, journal, reader, FullTestCatalogInputs.key(signerId, key(signerId).public.encoded),
            OfflineTrustBundleFixture.registryBytes(rotations.genesis.manifest.initialWriterRegistry),
        )
        return VersionBoundTestNamespaceProcessV1.fromRetained(
            consumers, pools, 1, desiredGeneration, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity),
            lanes, reader, activation, ordinarySeal, ordinaryDenial, terminalDenial = terminalDenial,
        )
    }

    /** Existing signed-prefix/P/HTTP inputs only; all policy/horizon authority remains explicitly synthetic. */
    private fun assembleIntake(
        tls: VersionBoundPersistenceConnectedFixture,
        http: TestOrdinarySealHttpFixtureV1,
        createGlobal: Int,
    ): VersionBoundTestNamespaceProcessV1 {
        val template = TestDeploymentInputFixture.document(journal)
        val trust = policy.trustBundlePolicy
        val limits = policy.limits
        val reference = tls.configuration.descriptor.authenticationPassword
        val registry = rotations.genesis.manifest.initialWriterRegistry
        val spki = key(signerId).public.encoded
        val document = template.copy(
            // Select the explicit denial recipe before parsing/acquisition/D; absence preserves the legacy bytes.
            profile = when {
                ordinaryRawHttp?.initialCheckpoint != null && activeSealRecoveryInput != null -> ComplaintTestDeploymentInputsV1.INITIAL_RECOVERY_CHECKPOINT_PROFILE
                ordinaryRawHttp?.initialCheckpoint != null -> ComplaintTestDeploymentInputsV1.INITIAL_CHECKPOINT_PROFILE
                activeFirstCutSuccessorInput != null -> ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_SUCCESSOR_PROFILE
                activeSealRecoveryInput != null -> ComplaintTestDeploymentInputsV1.ACTIVE_SEAL_RECOVERY_PROFILE
                activeFirstCutInput != null -> ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE
                ordinaryDrain == null -> template.profile
                journal.registeredAdminBatchDelete -> ComplaintTestDeploymentInputsV1.ADMIN_BATCH_ERASURE_DRAIN_PROFILE
                journal.registeredAdminDelete -> ComplaintTestDeploymentInputsV1.ADMIN_ERASURE_DRAIN_PROFILE
                journal.ownerDeleteAll -> ComplaintTestDeploymentInputsV1.OWNER_ERASURE_DRAIN_PROFILE
                else -> ComplaintTestDeploymentInputsV1.DRAIN_PROFILE
            },
            database = template.database.copy(host = tls.database.host, port = tls.endpointPort, name = PgLifecycleDatabaseSettings.DATABASE,
                runtimeUsername = PgLifecycleDatabaseSettings.CANDIDATE,
                runtimePassword = DesiredSecretReferenceV1(reference.logicalKeyId, reference.version.resourceArn, reference.version.versionId),
                publicTrustPemBase64 = TestDeploymentInputFixture.base64(tls.database.versionBoundTls().publicTrust(false)),
                protectedTrustParent = tls.database.versionBoundTls().publicTrustParent().toString()),
            capacity = DesiredCapacityInputV1(consumers.capacityPolicy.hardLimit.toLongArray().toList(),
                consumers.capacityPolicy.creationLimit.toLongArray().toList(), consumers.capacityPolicy.dailyEnrollmentLimit),
            admission = template.admission.copy(
                enrollmentGlobalPerHour = http.protectedEnrollmentGlobalPerHour ?: template.admission.enrollmentGlobalPerHour,
                ownerCreateGlobalPerHour = createGlobal),
            catalog = template.catalog.copy(readerProfile = "PROJECTED_CURRENT", initialBundleBase64 = TestDeploymentInputFixture.base64(initial),
                currentBundleBase64 = TestDeploymentInputFixture.base64(current),
                rootPublicKeySpkiBase64 = TestDeploymentInputFixture.base64(trust.rootPublicKeySpki),
                rootPublicKeySha256 = trust.rootPublicKeySha256, rootKeyId = trust.rootKeyId, rootAlgorithmId = trust.rootAlgorithmId,
                expectedEnvironment = trust.expectedEnvironment, expectedCatalogLocations = trust.expectedCatalogLocations,
                minimumBundleVersion = trust.minimumBundleVersion, currentWriterGenerationIds = policy.currentWriterGenerationIds,
                currentApproverIds = policy.currentApproverIds, expectedGenesisEnvelopeSha256 = Sha256.hex(prefix.first()), pageSize = 1,
                chainLimits = DesiredCatalogChainLimitsV1(
                    limits.maximumEnvelopeBytes, limits.maximumManifestRecords, limits.maximumGenerations, limits.maximumEncodedBytes,
                )),
            activation = template.activation.copy(signingKey = DesiredCatalogSigningKeyInputV1(signerId, FullTestCatalogInputs.KEY_ARN,
                OfflineTrustBundleFixture.ALGORITHM, TestDeploymentInputFixture.base64(spki), Sha256.hex(spki)),
                initialWriterRegistryBase64 = TestDeploymentInputFixture.base64(OfflineTrustBundleFixture.registryBytes(registry))),
            sealer = template.sealer.copy(catalogPut = template.sealer.catalogPut.copy(reference = registry.catalogWriter.putAuthority),
                catalogSign = template.sealer.catalogSign.copy(reference = registry.catalogWriter.signAuthority)),
            retention = template.retention.copy(
                environment = trust.expectedEnvironment, lastPreRunRestoreHorizon = http.horizon.toString(), horizonPolicy = http.horizonPolicy,
            ),
            ordinaryDenial = ordinaryDrain?.authorityInput(journal, trust.expectedEnvironment),
            terminalDenial = ordinaryDrain?.terminalQuiescence?.authorityInput(journal, trust.expectedEnvironment),
            activeFirstCut = activeFirstCutInput,
            activeFirstCutSuccessor = activeFirstCutSuccessorInput,
            ordinaryPublication = activeFirstCutInput?.let { TestActiveFirstCutInputFixtureV1.ordinaryInput() },
            initialCheckpoint = ordinaryRawHttp?.initialCheckpoint?.input,
            activeOrdinarySealRecovery = activeSealRecoveryInput,
            initialCheckpointCreate = ordinaryRawHttp?.initialCheckpointCreate,
            activeOwnerDeleteQueue = ordinaryRawHttp?.activeOwnerDeleteQueue?.input,
        )
        intakeDocument = document
        val inputBytes = TestDeploymentInputFixture.bytes(document)
        originalIntakeBytes = inputBytes.copyOf()
        val secrets = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(secrets, document, PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray())
        http.prepareIndependent(journal) // Raw factories and clocks fixed BEFORE the actual intake/owner construction.
        val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(secrets::httpClient, PersistenceNanoClock(http::nanos), http::now,
            PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY, http::nativeSts, http::nativeKms, http::nativeS3,
            ordinarySts = ordinaryRawHttp?.sts, ordinaryKms = ordinaryRawHttp?.kms, ordinaryS3 = ordinaryRawHttp?.s3,
            scannerSts = ordinaryRawHttp?.initialCheckpoint?.sts, scannerKms = ordinaryRawHttp?.initialCheckpoint?.kms,
            scannerS3 = ordinaryRawHttp?.initialCheckpoint?.s3,
            queueSqs = ordinaryRawHttp?.activeOwnerDeleteQueue?.sqs, queueSts = ordinaryRawHttp?.activeOwnerDeleteQueue?.sts,
            queueKms = ordinaryRawHttp?.activeOwnerDeleteQueue?.kms, queueS3 = ordinaryRawHttp?.activeOwnerDeleteQueue?.s3).also { intakeAssembly = it }
        TestDeploymentInputFixture.withManifest(inputBytes) { path ->
            assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS,
                activeFirstCutInput?.let { TestActiveFirstCutInputFixtureV1.ordinaryCredentials }, ordinaryRawHttp?.initialCheckpoint?.credentials,
                ordinaryRawHttp?.activeOwnerDeleteQueue?.credentials)
        }
        check(secrets.createdClients == secrets.closedClients && secrets.requests.size > 1)
        check(secrets.requests.size == secrets.replies.size)
        originalSecretReplies = secrets.requests.zip(secrets.replies).map { (request, reply) ->
            check(reply.status == 200 && reply.calls == 1 && reply.closes > 0 && reply.aborts == 1)
            val fields = request.fields()
            ColdSecretObjectV1(fields.getValue("SecretId"), fields.getValue("VersionId"),
                Base64.getEncoder().encodeToString(reply.bytes), reply.headers)
        }
        check(http.sts.createdClients + http.kms.createdClients + http.s3Created == 0)
        return assembly.target
    }

    /**
     * TEST-only fresh assembly from the SAME protected input values and immutable secret versions.
     * Caller first closes the actual old runtime+projector and proves native/pool/session retirement.
     * No old target, registration, projection continuation or acquired value is passed to the new graph.
     * This is a same-JVM fixture, NOT evidence of separate-process restart qualification.
     */
    internal fun reassembleRecoveryIntake(
        http: TestOrdinarySealHttpFixtureV1,
        changeDocument: (ComplaintTestDeploymentDocumentV1) -> ComplaintTestDeploymentDocumentV1 = { it },
    ): ComplaintTestProcessAssemblyV1 {
        val previous = checkNotNull(intakeProcess)
        check(http.protectedIntake && previous.pools.shutdownRequested() && previous.pools.poolsEndedForTrust())
        val document = changeDocument(checkNotNull(intakeDocument))
        val secrets = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(secrets, document, PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD.toByteArray())
        // The external raw retained object fixture was configured once, before the original intake.
        // New native clients are acquired by this new owner; no second configure/reset of its objects.
        val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(secrets::httpClient, PersistenceNanoClock(http::nanos), http::now,
            PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY, http::nativeSts, http::nativeKms, http::nativeS3,
            ordinarySts = ordinaryRawHttp?.sts, ordinaryKms = ordinaryRawHttp?.kms, ordinaryS3 = ordinaryRawHttp?.s3,
            scannerSts = ordinaryRawHttp?.initialCheckpoint?.sts, scannerKms = ordinaryRawHttp?.initialCheckpoint?.kms,
            scannerS3 = ordinaryRawHttp?.initialCheckpoint?.s3,
            queueSqs = ordinaryRawHttp?.activeOwnerDeleteQueue?.sqs, queueSts = ordinaryRawHttp?.activeOwnerDeleteQueue?.sts,
            queueKms = ordinaryRawHttp?.activeOwnerDeleteQueue?.kms, queueS3 = ordinaryRawHttp?.activeOwnerDeleteQueue?.s3)
        try {
            TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS,
                    document.activeFirstCut?.let { TestActiveFirstCutInputFixtureV1.ordinaryCredentials }, ordinaryRawHttp?.initialCheckpoint?.credentials,
                    ordinaryRawHttp?.activeOwnerDeleteQueue?.credentials)
            }
            check(secrets.createdClients == secrets.closedClients && secrets.requests.size > 1)
            return assembly
        } catch (failure: Throwable) {
            runCatching(assembly::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        // These extra recipes stay cold and fixture-owned. Each clears only its own read credential
        // reference; the actual runtime reader remains exclusively owned/closed by its assembly.
        val failures = projectedActiveQueues.asReversed().mapNotNull { runCatching(it::close).exceptionOrNull() }.toMutableList()
        projectedInitialCheckpoints.asReversed().mapNotNullTo(failures) { runCatching(it::close).exceptionOrNull() }
        runCatching { intakeAssembly?.close() }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    fun assembled(): ByteArray = expected.assemble(
        OfflineCatalogInventoryChainVerifier.verifyInventoryChain(prefix.asSequence(), initial, current, policy),
        rotations.genesis.manifest.oldestRestoreTimeEpochSecond, token, creation, approvals,
    )

    fun verify(
        generations: List<ByteArray> = complete,
        selected: CatalogTestRunActivationCanonicalV3 = expected,
        chainPolicy: OfflineCatalogChainReaderPolicy = policy,
    ) = OfflineCatalogInventoryChainVerifier.verifyTestRunActivationChain(generations.asSequence(), initial, current, chainPolicy, selected)

    fun provider(generations: List<ByteArray> = complete): SyntheticCatalogReadbackPort = SyntheticCatalogReadbackPort(generations).also {
        it.transformMetadata = { metadata -> metadata.copy(retainUntilEpochSecond = retainedUntil) }
    }

    fun readback(
        provider: SyntheticCatalogReadbackPort,
        expectedHead: CatalogLocalHead = head,
        policy: CatalogReadbackPolicy = readbackPolicy,
    ): CatalogTestRunActivationReadbackV3 =
        CatalogTestRunActivationReadbackV3.verify(provider, initial, current, policy, expectedHead, expected)

    fun withRun(run: CatalogTestRunActivationRunV1): OfflineCatalogTestRunActivationManifestV3 {
        val record = manifest.activationRecord.copy(run = run)
        return manifest.copy(activationRecord = record, history = history(record))
    }

    private fun independentManifest(): OfflineCatalogTestRunActivationManifestV3 {
        val original = rotations.genesis.manifest
        val run = CatalogTestRunActivationRunV1(
            journal.scope.id.toString(), 1, 7, Sha256.hex(process.canonicalBytes()),
            Json.decodeFromString(TestOwnerDeleteJournalDocumentV1.serializer(), journal.canonicalBytes().decodeToString()),
            1, INSTALLATION_LIMIT, TestTerminalEncodingV1("TEST_TERMINAL_V1", 1),
            accounting(INSTALLATION_LIMIT, journal.declaration().limits.capacity.maximumRetainedVersions), notices(journal.scope.id.toString()),
        )
        val record = CatalogTestRunActivationRecordV1(token, generation, Sha256.hex(prefix.last()), run)
        return OfflineCatalogTestRunActivationManifestV3(
            3, "NEW_BACKEND_TEST_RUN_ACTIVATION_V1", "kcj-1", "TEST_RUN_ACTIVATION", token, generation, record.previousEnvelopeSha256,
            original.initialTrustBundleEnvelopeSha256, original.catalogWriterGenerationId,
            CatalogSignerPolicyV1("SINGLE", "ALL_MEMBERS", listOf(OfflineCatalogRotationFixture.member(signerId))), creation, approvals,
            original.oldestRestoreTimeEpochSecond, original.initialWriterRegistry, inventory,
            CatalogInventoryDeltaV1(emptyList(), emptyList()), history(record), record,
        )
    }

    private fun inventoryRotationPrefix(): List<ByteArray> {
        val old = inventoryChain.generations.last()
        val overlap = OfflineCatalogInventoryFixture.signed(
            OfflineCatalogInventoryFixture.manifest(
                rotations.genesis, 4, OfflineCatalogInventoryFixture.bytes(old), "ROTATION_OVERLAP",
                old.manifest.restoreInventory, CatalogInventoryDeltaV1(emptyList(), emptyList()),
            ).copy(requiredSignerPolicy = CatalogSignerPolicyV1("ROTATION_OVERLAP", "ALL_MEMBERS", listOf("catalog-old", "catalog-new").map(OfflineCatalogRotationFixture::member))),
        )
        val activated = OfflineCatalogInventoryFixture.signed(
            OfflineCatalogInventoryFixture.manifest(
                rotations.genesis, 5, OfflineCatalogInventoryFixture.bytes(overlap), "ROTATION_ACTIVATE",
                old.manifest.restoreInventory, CatalogInventoryDeltaV1(emptyList(), emptyList()), "catalog-new",
            ),
        )
        return inventoryChain.bytes() + listOf(OfflineCatalogInventoryFixture.bytes(overlap), OfflineCatalogInventoryFixture.bytes(activated))
    }

    companion object {
        const val INSTALLATION_LIMIT = 501L

        fun manifestBytes(value: OfflineCatalogTestRunActivationManifestV3): ByteArray =
            CanonicalJson.canonicalize(OfflineCatalogTestRunActivationManifestV3.serializer(), value).toByteArray(Charsets.UTF_8)

        fun bytes(value: OfflineCatalogTestRunActivationEnvelopeV3): ByteArray =
            CanonicalJson.canonicalize(OfflineCatalogTestRunActivationEnvelopeV3.serializer(), value).toByteArray(Charsets.UTF_8)

        fun signed(value: OfflineCatalogTestRunActivationManifestV3): OfflineCatalogTestRunActivationEnvelopeV3 {
            val member = value.requiredSignerPolicy.members.single()
            val signer = Signature.getInstance("RSASSA-PSS").apply {
                setParameter(OfflineTrustBundleFixture.parameters)
                initSign(key(member.keyId).private)
                update(OfflineCatalogGenesisFixture.independentFrame(member.keyId, manifestBytes(value)))
            }
            val signature = OfflineCatalogGenesisSignatureV1(member.keyId, member.algorithmId, Base64.getEncoder().encodeToString(signer.sign()))
            return OfflineCatalogTestRunActivationEnvelopeV3(3, value, listOf(signature))
        }

        fun history(record: CatalogTestRunActivationRecordV1): CatalogTestRunActivationHistoryV1 {
            val empty = CatalogTestRunActivationHeadV1(0, Sha256.hexUtf8("[]"))
            val recordJson = CanonicalJson.canonicalize(CatalogTestRunActivationRecordV1.serializer(), record)
            return CatalogTestRunActivationHistoryV1(
                empty, CatalogTestRunActivationHeadV1(1, Sha256.hexUtf8("[$recordJson]")), empty, empty, empty, empty, empty,
            )
        }

        /** Independent fixed22 ordinal and literal-price oracle, not the production accounting calculator. */
        fun accounting(n: Long, r: Long): CatalogTestRunActivationAccountingV1 {
            val chunks = (n + 499) / 500
            val publications = chunks + 1
            val sidecars = chunks + 17
            fun vector(vararg values: Pair<Int, Long>): List<Long> = List(22) { index -> values.toMap()[index + 1] ?: 0L }
            val storage = n * 32_768 + publications * 278_528 + sidecars * 1_340_736 + 3_219_968 +
                (n + 3) * 65_536 + 2 * 3_776 + 2 * r * 37_696 + 1_068_608
            return CatalogTestRunActivationAccountingV1(
                "TEST_TERMINAL_ACCOUNTING_V1", 1,
                vector(3 to 1L, 21 to 3_219_968L),
                vector(2 to 4L, 4 to 2L, 11 to 1L, 18 to 2L, 21 to 1_933_120L, 22 to 1L),
                vector(1 to n, 2 to n + 3, 3 to 1L, 8 to n, 12 to publications, 17 to publications, 19 to 2 * r, 20 to 2L, 21 to storage),
            )
        }

        /** Ledger literals plus independent JSON framing/UUID formatting; no call to the production notice derivation. */
        private fun notices(runId: String): List<CatalogTestRunNoticeSeedV1> = listOf(
            Triple("complaints.notice.content-policy", "Adult content policy", "References to adult / 18+ content aren't allowed here. Please keep submissions consistent with our community guidelines."),
            Triple("complaints.notice.source-requirements", "New manga site requirements", "Any new manga site must offer at least 200 titles, have no bot verification steps, and be worth the setup effort. Adding a site takes significant time and work."),
        ).map { (noticeKey, subject, body) ->
            val frame = "[\"kira-test-notice-id-v1\",\"$runId\",\"$noticeKey\",1]"
            val id = MessageDigest.getInstance("SHA-256").digest(frame.toByteArray(Charsets.UTF_8)).copyOf(16)
            id[6] = ((id[6].toInt() and 15) or 64).toByte()
            id[8] = ((id[8].toInt() and 63) or 128).toByte()
            val hex = HexFormat.of().formatHex(id)
            val resourceId = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
            val definition = buildJsonObject {
                put("defaultBody", body)
                put("defaultSubject", subject)
                put("definitionVersion", 1)
                put("noticeKey", noticeKey)
            }
            CatalogTestRunNoticeSeedV1(noticeKey, 1, resourceId, Sha256.hexUtf8(CanonicalJson.canonicalize(definition)))
        }

        private fun key(id: String) = when (id) {
            "catalog-old" -> OfflineTrustBundleFixture.firstSigner
            "catalog-new" -> OfflineTrustBundleFixture.secondSigner
            else -> error("Unknown synthetic signing key")
        }
    }
}
