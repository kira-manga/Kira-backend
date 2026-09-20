package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisSignatureProposal
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintSignedGenesisFirstDInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutTargetV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.nio.charset.StandardCharsets
import java.util.HexFormat

/** Fixed create-once delivery history under the ORIGINAL AUTHOR allocation; never deserializes publication permission. */
@Suppress("TooManyFunctions") // Separate typed arm/acknowledgement/diagnostic/final-copy boundaries, not a caller-selected stage API.
internal class CatalogGenesisPublishReleaseV1(
    private val owner: CatalogGenesisPublishV1,
    private val inputs: CatalogGenesisFreezeInputsV1,
    independentPin: ByteArray,
    budget: PersistenceTimeBudget,
) : AutoCloseable {
    private val custody = CatalogGenesisReleaseCustodyV1.retain(inputs.request.releaseRoot, inputs.allocation, budget)
    private val pin = independentPin.copyOf()
    private val releaseHash = Sha256.hex(inputs.allocation)
    private val manifest = OfflineTrustBundleParser.parseGenesisManifest(inputs.intentBytes())
    private var proposal: CatalogGenesisSignatureProposal? = null
    private var target: VersionBoundComplaintProcessConfiguration? = null
    private var attempt: CatalogGenesisPublishAttemptV1? = null
    private var publication: CatalogPrimaryPutTargetV1? = null
    private var exactArm: ByteArray? = null
    private var retainedArm: ByteArray? = null
    private var retainedOutcome: ByteArray? = null
    private var retainedVersion: String? = null
    private var copiesPresent = false
    private var newArm = false
    private var putClaimed = false
    private var closed = false

    fun openExisting() {
        requireOwner()
        requirePublication(custody.openExisting() === CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED)
        listOf(
            CatalogGenesisReleaseLeafV1.APPROVED_INTENT to inputs.intentBytes(),
            CatalogGenesisReleaseLeafV1.TRUST_T0 to inputs.initialBytes(),
            CatalogGenesisReleaseLeafV1.TRUST_TN to inputs.currentBytes(),
            CatalogGenesisReleaseLeafV1.APPROVAL_INPUTS to inputs.approvals,
            CatalogGenesisReleaseLeafV1.PUBLIC_TARGET_BINDINGS to inputs.publicTarget, // AUTHOR reference/version, never overwritten with TARGET D.
            CatalogGenesisReleaseLeafV1.RETENTION_BINDINGS to inputs.retention,
        ).forEach { (leaf, bytes) -> requireExact(leaf, bytes) }
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_ARMED, record("prepare-armed"))
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_PREPARED_NO_SIGNATURE, record("prepared-no-signature"))
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_SIGN_ARMED, record("sign-armed"))
        val signature = requireNotNull(custody.read(CatalogGenesisReleaseLeafV1.SIGNATURE))
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED, record("signature-persistence-armed", Sha256.hex(signature)))
        val verified = inputs.proposal(UnverifiedGenesisPreparation(inputs.preparationInput().before), signature)
        val envelope = checkNotNull(verified.after.signedEnvelopeBytes)
        requireExact(CatalogGenesisReleaseLeafV1.ENVELOPE, envelope)
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTED, record("signature-persisted", Sha256.hex(signature), Sha256.hex(envelope)))
        requirePublication(pin.contentEquals(Sha256.hex(envelope).toByteArray(StandardCharsets.US_ASCII)))
        requireExact(CatalogGenesisReleaseLeafV1.PIN, pin)
        val pinText = String(pin, StandardCharsets.US_ASCII)
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_PIN_COMMITMENT_ARMED, record("pin-commitment-armed", pinText))
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_OUTCOME, record("frozen", pinText))
        retainedArm = custody.read(CatalogGenesisReleaseLeafV1.PUBLISH_ARMED)
        retainedOutcome = custody.read(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME)
        val primary = custody.read(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE)
        val replica = custody.read(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE)
        requirePublication((primary == null) == (replica == null)) // Never complete a partially retained immutable pair on recovery.
        requirePublication(retainedOutcome == null || retainedArm != null)
        requirePublication(primary == null || (retainedArm != null && retainedOutcome != null))
        requirePublication(custody.read(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED) == null)
        requirePublication(custody.read(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME) == null) // The separate finalizer owns that recovery lane.
        copiesPresent = primary != null
        proposal = verified
    }

    fun bindTarget(
        selected: VersionBoundComplaintProcessConfiguration,
        deployment: ComplaintDesiredDeploymentInputsV1,
    ): ComplaintSignedGenesisFirstDInputsV1.Verified {
        requireOwner()
        requirePublication(target == null && proposal != null)
        val author = inputs.request.database
        requirePublication(
            author.host == deployment.database.host && author.port == deployment.database.port && author.database == deployment.database.name &&
                inputs.publicTrust.contentEquals(deployment.publicTrustPem()) &&
                inputs.capacityDigest().contentEquals(selected.consumers.capacityPolicy.digestBytes()),
        )
        val envelope = checkNotNull(checkNotNull(proposal).after.signedEnvelopeBytes)
        val comparison = ComplaintSignedGenesisFirstDInputsV1.fromRaw(
            inputs.intentBytes(),
            inputs.initialBytes(),
            inputs.currentBytes(),
            envelope,
            String(pin, StandardCharsets.US_ASCII),
        ).verifyFor(selected) // Exact independently pinned release + actual acquired TARGET J/P/reader, not a SQL selection result.
        val locations = OfflineTrustBundleVerifier.verify(inputs.currentBytes(), inputs.chain.trustBundlePolicy).body.catalogLocations
        val destination = CatalogPrimaryPutTargetV1(locations[0], 1, manifest.creation.createdAtEpochSecond, Sha256.hex(envelope))
        val database = deployment.database
        val password = deployment.runtimePassword
        val binding = record(
            "publish-target",
            HexFormat.of().formatHex(selected.configurationHashBytes()), Sha256.hex(selected.canonicalBytes()),
            database.host, database.port.toString(), database.name, database.runtimeUsername, Sha256.hex(deployment.publicTrustPem()),
            password.logicalKeyId, password.version.resourceArn, password.version.versionId,
            Sha256.hex(selected.consumers.journalConfiguration.canonicalBytes()), Sha256.hex(selected.consumers.capacityPolicy.canonicalBytes()),
        )
        val arm = record(
            "publish-armed", manifest.operationToken, envelope.size.toString(), destination.envelopeSha256, String(pin, StandardCharsets.US_ASCII),
            locations[0].role, locations[0].accountId, locations[0].region, locations[0].bucket,
            locations[1].role, locations[1].accountId, locations[1].region, locations[1].bucket,
            destination.key, destination.createdAt.epochSecond.toString(), destination.retainUntil.epochSecond.toString(),
            "COMPLIANCE", "SHA256", destination.checksum, Sha256.hex(inputs.retention), Sha256.hex(binding),
        )
        retainedArm?.let { requirePublication(it.contentEquals(arm)) }
        publication = destination
        exactArm = arm
        target = selected
        retainedOutcome?.let(::readOutcome) // Bounded canonical shape and exact target binding, but never an acknowledgement capability.
        return comparison
    }

    fun attach(selected: CatalogGenesisPublishAttemptV1) {
        requireOwner()
        requirePublication(attempt == null && selected.process === target)
        selected.requireRelease(this)
        attempt = selected
    }

    fun isArmed(): Boolean {
        requireSelected()
        return retainedArm != null
    }

    fun prepared(policy: CatalogReadbackPolicy): LocalCatalogSnapshot.PreparedGenesis {
        requireSelected()
        val signed = checkNotNull(proposal)
        // Only after actual current ALL-history equality and known commit/release may this exact disk snapshot feed raw readback.
        return CatalogGenesisPreparationVerifier.verifyPinnedReadback(
            signed,
            UnverifiedGenesisPreparation(signed.after),
            inputs.initialBytes(),
            inputs.currentBytes(),
            policy,
        )
    }

    fun primaryTarget(): CatalogPrimaryPutTargetV1 {
        requireSelected()
        return checkNotNull(publication)
    }

    fun envelopeBytes(): ByteArray {
        requireSelected()
        return checkNotNull(checkNotNull(proposal).after.signedEnvelopeBytes).copyOf()
    }

    fun arm() {
        requireSelected()
        owner.requireNamespaceClosed(this)
        requirePublication(retainedArm == null && retainedOutcome == null && !copiesPresent && !newArm && !putClaimed)
        val arm = checkNotNull(exactArm)
        requirePublication(custody.putIfAbsent(CatalogGenesisReleaseLeafV1.PUBLISH_ARMED, arm) === CatalogGenesisCustodyObservationV1.CREATED)
        requireExact(CatalogGenesisReleaseLeafV1.PUBLISH_ARMED, arm)
        retainedArm = arm
        newArm = true // Only this invocation's successful create + force + exact reread. Identical old bytes NEVER set this bit.
    }

    fun claimPut() {
        requireSelected()
        owner.requireNamespaceClosed(this)
        requirePublication(newArm && retainedArm != null && retainedOutcome == null && !putClaimed)
        putClaimed = true // Spent even if the HTTP/SDK constructor never returns, before the actual lower is opened.
    }

    fun acknowledged(observed: CatalogPrimaryPutAcknowledgementV1) {
        requireSelected()
        owner.requireAcknowledgement(this, observed)
        requirePublication(newArm && putClaimed && retainedOutcome == null && observed.envelopeSha256 == checkNotNull(publication).envelopeSha256)
        preserveOutcome("publish-acknowledged", observed.versionId)
    }

    fun awaitReplication(observed: CatalogReadbackResult.GenesisAwaitReplication) {
        requireSelected()
        owner.requireDiagnostic(this, observed)
        val destination = checkNotNull(publication)
        requirePublication(!copiesPresent && retainedArm != null && observed.operationToken == manifest.operationToken)
        requirePublication(observed.candidate.generation == 1L && observed.candidate.envelopeSha256 == destination.envelopeSha256)
        requirePublication(observed.retainUntilEpochSecond == destination.retainUntil.epochSecond)
        preserveObservedVersion(observed.objectVersion) // No PENDING/COMPLETED status enters this stable outcome or the final-copy slots.
    }

    fun completedCopies(fresh: CatalogDualLocationVerifier.GenesisReadback) {
        requireSelected()
        owner.requireCompleteReadback(this, fresh)
        val destination = checkNotNull(publication)
        requirePublication(retainedArm != null && fresh.resume === GenesisResume.PREPARED)
        requirePublication(fresh.mutation().signedEnvelopeBytes.contentEquals(checkNotNull(proposal).after.signedEnvelopeBytes))
        requirePublication(fresh.envelopeSha256 == destination.envelopeSha256 && fresh.retainUntilEpochSecond == destination.retainUntil.epochSecond)
        preserveObservedVersion(fresh.objectVersion)
        val primary = fresh.primaryEvidenceBytes()
        val replica = fresh.replicaEvidenceBytes()
        requireSelected()
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE, primary)
        requireSelected() // A late first force/close cannot start its sibling under an expired stricter reader cap.
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE, replica)
        requireExact(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE, primary)
        requireExact(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE, replica)
        copiesPresent = true
    }

    private fun preserveObservedVersion(version: String) {
        if (retainedOutcome == null) {
            preserveOutcome("publish-readback-recovered", version) // A lost SDK acknowledgement is never invented on recovery.
        } else {
            requirePublication(retainedVersion == version)
            requireExact(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME, checkNotNull(retainedOutcome))
        }
    }

    private fun preserveOutcome(kind: String, version: String) {
        requireSelected()
        requirePublication(retainedArm != null && retainedOutcome == null && CatalogReadbackProtocol.validVersion(version))
        val bytes = outcomeRecord(kind, version)
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME, bytes)
        requireExact(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME, bytes)
        retainedOutcome = bytes
        retainedVersion = version
    }

    private fun readOutcome(bytes: ByteArray) {
        val values = Json.decodeFromString(ListSerializer(String.serializer()), String(bytes, StandardCharsets.UTF_8))
        requirePublication(values.size == 7 && values[1] in setOf("publish-acknowledged", "publish-readback-recovered"))
        requirePublication(CatalogReadbackProtocol.validVersion(values[4]) && bytes.contentEquals(outcomeRecord(values[1], values[4])))
        retainedVersion = values[4]
    }

    private fun outcomeRecord(kind: String, version: String): ByteArray = record(
        kind,
        Sha256.hex(checkNotNull(exactArm)),
        version,
        checkNotNull(publication).envelopeSha256,
        checkNotNull(publication).checksum,
    )

    private fun requireOwner() {
        requireConnectionFree()
        requirePublication(!closed && owner.ownsRelease(this), CatalogGenesisPublishFailureV1.PROCESS_REFUSED)
        owner.requireRunning()
    }

    private fun requireSelected() {
        requireOwner()
        val selected = checkNotNull(attempt)
        requirePublication(selected.process === target)
        selected.requireRelease(this)
        selected.requireSelected()
    }

    private fun record(kind: String, vararg values: String): ByteArray = catalogFreezeRecord(kind, releaseHash, *values)
    private fun requireExact(leaf: CatalogGenesisReleaseLeafV1, bytes: ByteArray) {
        requireOwner() // The same reader cap also gates rereads after a late durable publication write.
        val observed = custody.read(leaf)
        requireOwner()
        requirePublication(observed.contentEquals(bytes))
    }

    override fun close() {
        closed = true
        custody.close()
    }

    override fun toString(): String = "CatalogGenesisPublishReleaseV1(original-allocation,stable-history,no-replay-authority)"
}
