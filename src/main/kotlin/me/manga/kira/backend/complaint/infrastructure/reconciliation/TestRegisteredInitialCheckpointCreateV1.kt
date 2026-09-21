package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreationOperation
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.ComplaintAdmittedOwnerCreate
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException

/**
 * Privately assembled route, not cached eligibility. Every NEW claim performs its own locked full
 * current read on the exact original ordinary holder. AUTH/terminal PREFLIGHT/STATUS/claim-loser
 * replay require current registered desired identity + actor + exact receipt semantics, never this freshness read.
 */
internal class TestRegisteredInitialCheckpointCreateV1 private constructor(
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val assembly: ComplaintTestProcessAssemblyV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
    private val replyPolicy: VersionBoundTestInitialCheckpointCreateV1? = null,
) {
    private val process = registration.process
    internal val policy = checkNotNull(process.initialCheckpointCreate)
    private val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    private val journal = process.consumers.journalConfiguration
    private val checkpoint = checkNotNull(process.initialCheckpoint)
    private val run = TestTerminalRunContextV1(identity.scope.toString(), identity.generation, hex(identity.activationCatalogHash()),
        hex(identity.configurationHash()), identity.sealEncodingSha256)
    private val manifest: Pair<String, Long> = MessageDigest.getInstance("SHA-256").let { digest ->
        val bytes = EpochSealFramesV1.update(digest, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", identity.writer.toString(),
            journal.ordinaryPrefix, "TEST", identity.scope.toString(), "1", "1", "0"))
        check(bytes in 1..journal.declaration().limits.capacity.maximumScanStagingBytes)
        HexFormat.of().formatHex(digest.digest()) to bytes
    }

    internal fun requireEntry(selected: PersistencePhaseOwnership) {
        try {
            requireConnectionFree(); requirePhaseOwner(selected)
        } catch (problem: RuntimeException) {
            if (problem is CancellationException) throw problem
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    /** Local retained-owner checks only; safe during begin/SQL ownership, no checkout/JSON/provider. */
    internal fun requirePhaseOwner(selected: PersistencePhaseOwnership) {
        check(selected === ownership && assembly.target === process && process.initialCheckpointCreate === policy && process.initialCheckpoint === checkpoint)
        check(replyPolicy == null || replyPolicy === policy)
        replyPolicy?.requireReplies()
        policy.requireRetained(process.pools, process.consumers.journalRouting, checkpoint)
        process.pools.ordinary.requireTestInitialCheckpointCreate(policy)
        registration.requireActiveIdentityTarget(assembly)
        registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
    }

    internal fun requirePath(path: PersistencePhasePath) {
        if (path !in PATHS && !(replyPolicy != null && path in REPLY_PATHS)) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    internal fun requireNewWorkPath(path: PersistencePhasePath) {
        check(path === PersistencePhasePath.COMPLAINT_OWNER_CREATE ||
            (replyPolicy != null && path === PersistencePhasePath.COMPLAINT_OWNER_REPLY))
    }

    internal fun requireAdmission(handoff: ComplaintAdmittedOwnerCreate) =
        ComplaintIngressAdmission.requireOwnerCreateOwner(handoff, process.consumers.ingressAdmission)

    internal fun requireReadAdmission(context: ComplaintIngressContext) =
        ComplaintIngressAdmission.requireOwnerOperationReadOwner(context, process.consumers.ingressAdmission)

    /** Fixed expected identity only. The ordinary AUTH/receipt statement compares its CURRENT rows in that same snapshot. */
    internal fun observationIdentityArguments(): Array<Any?> = arrayOf(identity.scope, identity.desiredGeneration, identity.implementationSchema,
        identity.configurationHash(), identity.databaseIdentity, identity.restoreIdentity, identity.writer, identity.catalogWriter,
        identity.trustBundleHash(), identity.acceptedCatalogGeneration, identity.acceptedCatalogHash(),
        identity.globalDesiredGeneration, identity.globalConfigurationHash())

    internal fun requireOperation(operation: ComplaintOwnerCreateOperation, phase: PersistencePhaseContext,
        path: PersistencePhasePath, tuple: ComplaintOwnerOperationTuple?) {
        requirePhaseOwner(ownership); requirePath(path)
        phase.ownerOperation.requireOwner(operation, jdbc, ownership)
        check(operation.registeredWith(this) && (tuple == null || tuple.operation === ComplaintOwnerCreationOperation.OWNER_CREATE ||
            (replyPolicy != null && tuple.operation === ComplaintOwnerCreationOperation.OWNER_REPLY)))
    }

    internal fun lockAndCheck(operation: ComplaintOwnerCreateOperation, phase: PersistencePhaseContext) {
        operation.requireCurrentCheckpointRead(this, phase)
        requirePhaseOwner(ownership)
        phase.ownerOperation.requireOwner(operation, jdbc, ownership)
        // Same established control-before-counter order. These two original transaction locks are
        // retained through COMMIT/ROLLBACK; no control lock is acquired by any later recheck.
        check(jdbc.query(TestActiveInitialCheckpointSqlV1.lockGlobal, { _, _ -> true }).single())
        check(jdbc.query(TestActiveInitialCheckpointSqlV1.lockScope, { _, _ -> true }, identity.scope).single())
        checkCurrent(operation, phase)
    }

    internal fun checkCurrent(operation: ComplaintOwnerCreateOperation, phase: PersistencePhaseContext) {
        operation.requireCurrentCheckpointRead(this, phase)
        requirePhaseOwner(ownership)
        phase.ownerOperation.requireOwner(operation, jdbc, ownership)
        val connection = phase.ownerOperation.connection(operation, jdbc)
        registration.requireActiveIdentityGate(PersistenceComplaintMaintenanceGateV1.read(connection))
        jdbc.query(TestActiveInitialCheckpointSqlV1.currentForOwnerCreate, { row, _ -> TestActiveInitialCheckpointRowsV1.Current(row) },
            *identity.arguments()).single().use { current ->
            check(current.leaseOwner == null && current.leaseExpiresAt == null && current.leaseToken > current.preparingToken)
            jdbc.query(TestActiveInitialCheckpointSqlV1.slot, { row, _ ->
                TestActiveInitialCheckpointRowsV1.intentComparisons(row, identity, run, journal.sha256, current)
            }, current.operationToken, identity.scope).single().use { frozen ->
                jdbc.query(TestActiveInitialCheckpointSqlV1.sealControl, { row, _ ->
                    TestActiveInitialCheckpointRowsV1.Control.read(row, frozen, current)
                }, identity.scope).single().use { control ->
                    requireSeal(frozen, current, control)
                    val bytes = jdbc.query(CHECKPOINT_BYTES, { row, _ -> checkNotNull(row.getBytes(1)) }, identity.scope).single()
                    try {
                        val document = TestInitialCheckpointCurrentCodecV1.checkpoint(bytes)
                        requireDocument(document, frozen, current, control)
                        val args = TestActiveInitialCheckpointRowsV1.documentArguments(document, bytes, Sha256.hex(bytes))
                        try {
                            check(jdbc.query(TestActiveInitialCheckpointSqlV1.completed, { row, _ ->
                                TestActiveInitialCheckpointRowsV1.boolean(row, "valid")
                            }, *args, current.leaseToken, identity.scope).single())
                        } finally { args.forEach { if (it is ByteArray) it.fill(0) } }
                        // A fresh DB-time statement AFTER every potential earlier wait and bounded
                        // parsing step, not a timestamp evaluated before a locking SELECT waited.
                        val now = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
                        check(TestActiveInitialCheckpointDocumentV1.time(now) && !now.isBefore(current.sampledAt) &&
                            !document.completedAt.isAfter(now) && !document.completedAt.plusMillis(journal.declaration().limits.deadlines.checkpointMaxAgeMillis.toLong()).isBefore(now) &&
                            control.retainUntil.isAfter(now.plusMillis(checkpoint.retention.retention.utcUncertainty.maximumMillis)))
                        operation.requireCheckpointTime(this, phase, now)
                    } finally { bytes.fill(0) }
                }
            }
        }
        requirePhaseOwner(ownership)
        operation.requireCurrentCheckpointRead(this, phase)
    }

    private fun requireSeal(frozen: TestTerminalDurableRowV1, current: TestActiveInitialCheckpointRowsV1.Current,
        control: TestActiveInitialCheckpointRowsV1.Control) {
        val bytes = frozen.canonicalBytes()
        try {
            val seal = TestTerminalJsonV1(journal).epochSeal(bytes)
            check(seal.epochStartInclusive == 1L && seal.epochEndInclusive == 1L && seal.eventCount == 0L &&
                seal.eventManifestSha256 == manifest.first && seal.precedingSealSha256 == "" && seal.sealId == frozen.binding.objectId &&
                seal.preparingFencingToken == current.preparingToken && seal.preparingFencingToken < current.leaseToken)
        } finally { bytes.fill(0) }
        val proof = control.verificationBytes()
        try { TestInitialCheckpointCurrentCodecV1.requireSealVerification(proof, frozen, control, current.sampledAt, checkpoint.retention.retention) }
        finally { proof.fill(0) }
    }

    private fun requireDocument(value: TestActiveInitialCheckpointDocumentV1, frozen: TestTerminalDurableRowV1,
        current: TestActiveInitialCheckpointRowsV1.Current, control: TestActiveInitialCheckpointRowsV1.Control) {
        check(value.scope == identity.scope.toString() && value.desiredGeneration == identity.desiredGeneration && value.fencingToken == current.leaseToken &&
            value.configurationSha256 == run.configurationSha256 && value.journalConfigurationSha256 == journal.sha256 &&
            value.databaseIdentity == identity.databaseIdentity.toString() && value.restoreIdentity == identity.restoreIdentity.toString() &&
            value.catalogGeneration == identity.acceptedCatalogGeneration && value.catalogSha256 == hex(identity.acceptedCatalogHash()) &&
            value.trustBundleSha256 == hex(identity.trustBundleHash()) && value.catalogWriterGeneration == identity.catalogWriter.toString() &&
            value.writerGeneration == identity.writer.toString() && value.sealOperationToken == current.operationToken.toString() &&
            value.sealObjectKey == frozen.binding.objectKey && value.sealObjectVersion == control.version &&
            value.sealCanonicalSha256 == frozen.canonicalSha256 && value.sealCiphertextSha256 == frozen.wireSha256 &&
            value.manifestSha256 == manifest.first && value.manifestFramedBytes == manifest.second &&
            !value.startedAt.isBefore(control.verifiedAt) && !value.completedAt.isAfter(current.sampledAt))
    }

    override fun toString(): String = "TestRegisteredInitialCheckpointCreateV1(exact-registered-route,no-cached-eligibility)"

    companion object {
        private val PATHS = setOf(PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT, PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS,
            PersistencePhasePath.COMPLAINT_OWNER_CREATE)
        private val REPLY_PATHS = setOf(PersistencePhasePath.COMPLAINT_OWNER_REPLY_PREFLIGHT, PersistencePhasePath.COMPLAINT_OWNER_REPLY)
        private val CHECKPOINT_BYTES = "SELECT CASE WHEN complaint_bytes_match(checkpoint_bytes, checkpoint_hash, 65536) THEN checkpoint_bytes END " +
            "FROM complaint_journal_control WHERE data_scope_id = ?::uuid AND test_only"

        internal fun fromRegistered(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate): TestRegisteredInitialCheckpointCreateV1 {
            requireConnectionFree(); registration.requireUsable(); registration.requireActiveIdentityTarget(assembly)
            checkNotNull(registration.process.initialCheckpointCreate)
            registration.requireInstallationResources(ownership, jdbc)
            return TestRegisteredInitialCheckpointCreateV1(registration, assembly, ownership, jdbc).also { it.requireEntry(ownership) }
        }

        /** New explicit selection on the original reply-capable full-D/pool pin; the old factory stays CREATE-only. */
        internal fun fromRegisteredWithReplies(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate): TestRegisteredInitialCheckpointCreateV1 {
            requireConnectionFree(); registration.requireUsable(); registration.requireActiveIdentityTarget(assembly)
            val policy = checkNotNull(registration.process.initialCheckpointCreate)
            policy.requireReplies()
            registration.requireInstallationResources(ownership, jdbc)
            return TestRegisteredInitialCheckpointCreateV1(registration, assembly, ownership, jdbc, policy).also { it.requireEntry(ownership) }
        }

        private fun hex(bytes: ByteArray): String = try { HexFormat.of().formatHex(bytes) } finally { bytes.fill(0) }
    }
}
