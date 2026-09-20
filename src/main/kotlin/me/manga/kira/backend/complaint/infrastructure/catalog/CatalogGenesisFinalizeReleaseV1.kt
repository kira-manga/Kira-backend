package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset

/** Fixed finalization records in the ORIGINAL author allocation; historical bytes never reconstruct a verifier/attempt. */
internal class CatalogGenesisFinalizeReleaseV1(private val inputs: CatalogGenesisFreezeInputsV1, independentPin: ByteArray, budget: PersistenceTimeBudget) :
    AutoCloseable {
    private val custody = CatalogGenesisReleaseCustodyV1.retain(inputs.request.releaseRoot, inputs.allocation, budget)
    private val caller = Thread.currentThread()
    private val pin = independentPin.copyOf()
    private val releaseHash = Sha256.hex(inputs.allocation)
    private var envelope: ByteArray? = null
    private var target: VersionBoundComplaintProcessConfiguration? = null
    private var targetBinding: ByteArray? = null
    private var readback: CatalogDualLocationVerifier.GenesisReadback? = null
    private var armed: ByteArray? = null
    private var closed = false

    fun openExisting() {
        requireConnectionFree()
        requireFinalization(custody.openExisting() === CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED)
        listOf(
            CatalogGenesisReleaseLeafV1.APPROVED_INTENT to inputs.intentBytes(),
            CatalogGenesisReleaseLeafV1.TRUST_T0 to inputs.initialBytes(),
            CatalogGenesisReleaseLeafV1.TRUST_TN to inputs.currentBytes(),
            CatalogGenesisReleaseLeafV1.APPROVAL_INPUTS to inputs.approvals,
            CatalogGenesisReleaseLeafV1.PUBLIC_TARGET_BINDINGS to inputs.publicTarget,
            CatalogGenesisReleaseLeafV1.RETENTION_BINDINGS to inputs.retention,
        ).forEach { (leaf, bytes) -> requireExact(leaf, bytes) }
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_ARMED, record("prepare-armed"))
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_PREPARED_NO_SIGNATURE, record("prepared-no-signature"))
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_SIGN_ARMED, record("sign-armed"))
        val signature = requireNotNull(custody.read(CatalogGenesisReleaseLeafV1.SIGNATURE))
        requireExact(
            CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED,
            record("signature-persistence-armed", Sha256.hex(signature)),
        )
        val unsigned = UnverifiedGenesisPreparation(inputs.preparationInput().before)
        val proposal = inputs.proposal(unsigned, signature) // Genuine raw signature/trust/intent verification, not stored-hash promotion.
        val signed = requireNotNull(proposal.after.signedEnvelopeBytes)
        requireExact(CatalogGenesisReleaseLeafV1.ENVELOPE, signed)
        requireExact(
            CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTED,
            record("signature-persisted", Sha256.hex(signature), Sha256.hex(signed)),
        )
        requireFinalization(pin.contentEquals(Sha256.hex(signed).toByteArray(StandardCharsets.US_ASCII)))
        requireExact(CatalogGenesisReleaseLeafV1.PIN, pin)
        val pinText = String(pin, StandardCharsets.US_ASCII)
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_PIN_COMMITMENT_ARMED, record("pin-commitment-armed", pinText))
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_OUTCOME, record("frozen", pinText))
        val primary = custody.read(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE)
        val replica = custody.read(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE)
        val previousArm = custody.read(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED)
        val previousOutcome = custody.read(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME)
        requireFinalization((primary == null) == (replica == null)) // Never fill a partially retained evidence pair.
        requireFinalization(previousArm == null || primary != null)
        requireFinalization(previousOutcome == null || previousArm != null)
        envelope = signed
    }

    fun bindTarget(selected: VersionBoundComplaintProcessConfiguration, deployment: ComplaintDesiredDeploymentInputsV1) {
        requireConnectionFree()
        requireFinalization(target == null && !closed && caller === Thread.currentThread())
        val authorDatabase = inputs.request.database
        requireFinalization(
            authorDatabase.host == deployment.database.host && authorDatabase.port == deployment.database.port &&
                authorDatabase.database == deployment.database.name && inputs.publicTrust.contentEquals(deployment.publicTrustPem()) &&
                inputs.capacityDigest().contentEquals(selected.consumers.capacityPolicy.digestBytes()),
        )
        ComplaintSignedGenesisFirstDInputsV1.fromRaw(
            inputs.intentBytes(),
            inputs.initialBytes(),
            inputs.currentBytes(),
            checkNotNull(envelope),
            String(pin, StandardCharsets.US_ASCII),
        ).verifyFor(selected).requireTarget(selected) // Raw TARGET J/P/reader binding only, not first-D selection authority.
        targetBinding = record(
            "finalize-target",
            Sha256.hex(selected.canonicalBytes()),
            Sha256.hex(selected.consumers.journalConfiguration.canonicalBytes()),
            Sha256.hex(selected.consumers.capacityPolicy.canonicalBytes()),
            Sha256.hex(checkNotNull(envelope)),
        )
        target = selected
    }

    /** Called only after fresh real SDK verification AND actual provider cleanup, outside every SQL phase. */
    fun preserveReadback(selected: VersionBoundComplaintProcessConfiguration, fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireConnectionFree()
        requireFinalization(!closed && caller === Thread.currentThread() && target === selected && readback == null)
        selected.requireUnchangedConfiguration()
        requireFinalization(fresh.mutation().signedEnvelopeBytes.contentEquals(envelope))
        val creation = OfflineTrustBundleParser.parseGenesisManifest(inputs.intentBytes()).creation.createdAtEpochSecond
        val frozenRetention = Instant.ofEpochSecond(creation).atOffset(ZoneOffset.UTC).plusYears(10).toEpochSecond()
        requireFinalization(fresh.retainUntilEpochSecond == frozenRetention)
        val primary = fresh.primaryEvidenceBytes()
        val replica = fresh.replicaEvidenceBytes()
        val exactArm = record(
            "finalize-armed",
            Sha256.hex(checkNotNull(targetBinding)),
            fresh.envelopeSha256,
            fresh.objectVersion,
            frozenRetention.toString(),
            Sha256.hex(primary),
            Sha256.hex(replica),
        )
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE, primary)
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE, replica)
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED, exactArm)
        requireExact(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE, primary)
        requireExact(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE, replica)
        requireExact(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED, exactArm)
        custody.read(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME)?.let {
            requireFinalization(it.contentEquals(record("finalized", Sha256.hex(exactArm))))
        }
        armed = exactArm
        readback = fresh // Published only after every exact durability/reread check succeeded.
    }

    /** Retained identity checks only, safe inside the fixed phase: no filesystem, hash, parsing or provider work. */
    fun requireBarrier(selected: VersionBoundComplaintProcessConfiguration, fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireFinalization(!closed && caller === Thread.currentThread() && target === selected && readback === fresh && armed != null)
    }

    fun projected(
        selected: VersionBoundComplaintProcessConfiguration,
        fresh: CatalogDualLocationVerifier.GenesisReadback,
        projection: ProcessBoundCatalogGenesisProjection,
    ) {
        requireConnectionFree()
        requireBarrier(selected, fresh)
        projection.requireBinding(selected, fresh) // Only the actual committed AND released PROJECT operation issues this object.
        val outcome = record("finalized", Sha256.hex(checkNotNull(armed))) // Stable for PROJECTED and ALREADY_PROJECTED; not total-cleanup evidence.
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME, outcome)
        requireExact(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME, outcome)
    }

    private fun record(kind: String, vararg values: String): ByteArray = catalogFreezeRecord(kind, releaseHash, *values)
    private fun requireExact(leaf: CatalogGenesisReleaseLeafV1, bytes: ByteArray) = requireFinalization(custody.read(leaf).contentEquals(bytes))

    override fun close() {
        closed = true
        custody.close()
    }

    override fun toString(): String = "CatalogGenesisFinalizeReleaseV1(original-allocation,redacted,no-authority)"
}
