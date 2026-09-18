package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenProjection
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier.Overlap2Readback
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier.Overlap2Readback.State
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/** Existing genuine raw/signature fixture; policy only, not delivery, custody or SQL authority. */
class CatalogSignerRotationDeliveryPolicyTest {
    private val fixture by lazy { CatalogReadbackFixture() }
    private val overlap get() = fixture.chain.base.rotations.first()
    private val envelope get() = OfflineCatalogRotationFixture.bytes(overlap)
    private val evaluatedAt = Instant.ofEpochSecond(CatalogReadbackFixture.EVALUATED_AT)
    private val genesisRetention get() = retention(fixture.chain.base.genesis.manifest.creation.createdAtEpochSecond)
    private val overlapRetention get() = retention(overlap.manifest.creation.createdAtEpochSecond)

    @Test
    fun `four raw delivery states use the observed generation retention without borrowing the newer deadline`() {
        assertTrue(genesisRetention < overlapRetention)
        for (state in State.entries) {
            val settings = settings()
            val readback = readback(state, settings)
            assertEquals(state, readback.state)
            assertEquals(if (state == State.PREPARED_UNPUBLISHED) genesisRetention else overlapRetention, readback.retainUntilEpochSecond)
            settings.verifySignerRotationDelivery(readback, evaluatedAt)
        }
    }

    @Test
    fun `raw common floor does not replace either observed generation ten-year creation floor`() {
        val settings = settings()
        assertTrue(settings.policyAt(evaluatedAt).requiredRetainUntilEpochSecond < genesisRetention - 1)
        for (state in State.entries) {
            val readback = readback(
                state,
                settings,
                g1Retention = if (state == State.PREPARED_UNPUBLISHED) genesisRetention - 1 else genesisRetention,
                g2Retention = overlapRetention - 1,
            )
            reject(CatalogReadbackFailure.RETENTION_MISMATCH) { settings.verifySignerRotationDelivery(readback, evaluatedAt) }
        }
    }

    @Test
    fun `delivery policy rejects projected profile different genesis pin and different sampled evaluation`() {
        val settings = settings()
        val proof = readback(State.PREPARED_DUAL_COPY, settings)
        reject(CatalogReadbackFailure.INVALID_POLICY) { settings.verifySignerRotationDelivery(proof, evaluatedAt.plusSeconds(1)) }
        val projected = VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
            fixture.initial, fixture.current, fixture.policy().chain, fixture.head(1).envelopeSha256, S3CatalogReadbackLimits(), 1000,
        )
        reject(CatalogReadbackFailure.INVALID_POLICY) { projected.verifySignerRotationDelivery(proof, evaluatedAt) }
        reject(CatalogReadbackFailure.HEAD_CONFLICT) { settings("0".repeat(64)).verifySignerRotationDelivery(proof, evaluatedAt) }
    }

    private fun settings(pin: String = fixture.head(1).envelopeSha256): VersionBoundCatalogReadbackConfigurationV1 =
        VersionBoundCatalogReadbackConfigurationV1.fromIndependentInputs(
            fixture.initial, fixture.current, fixture.policy().chain, pin, S3CatalogReadbackLimits(), 1000,
        )

    private fun readback(
        state: State,
        settings: VersionBoundCatalogReadbackConfigurationV1,
        g1Retention: Long = genesisRetention,
        g2Retention: Long = overlapRetention,
    ): Overlap2Readback {
        val unsigned = OfflineCatalogRotationFixture.manifestBytes(overlap.manifest)
        val bytes = envelope
        val mutation = CatalogFrozenMutation(
            1, overlap.manifest.operationToken, unsigned, Sha256.hex(unsigned), bytes, Sha256.hex(bytes),
            overlap.signatures.map { CatalogFrozenSignatureSlot(it.keyId, it.algorithmId, Base64.getDecoder().decode(it.signatureBase64)) },
        )
        val local = if (state == State.PROJECTION_PENDING_DUAL_COPY) {
            LocalCatalogSnapshot.ProjectionPending(
                CatalogLocalHead(2, Sha256.hex(bytes)), CatalogFrozenProjection(overlap.manifest.operationToken, bytes, Sha256.hex(bytes)),
            )
        } else {
            LocalCatalogSnapshot.Prepared(fixture.head(1), mutation)
        }
        val g1 = fixture.bytes.take(1)
        val primary = if (state == State.PREPARED_UNPUBLISHED) g1 else g1 + bytes
        val replica = if (state == State.PREPARED_UNPUBLISHED || state == State.PREPARED_AWAIT_REPLICATION) g1 else primary
        val provider = SyntheticCatalogReadbackPort(primary, replica)
        provider.transformMetadata = { metadata ->
            metadata.copy(retainUntilEpochSecond = if (metadata.requestBinding.key == CatalogReadbackProtocol.key(1)) g1Retention else g2Retention)
        }
        val result = Overlap2Readback.verify(provider, fixture.initial, fixture.current, settings.policyAt(evaluatedAt), local)
        assertEquals(0, provider.openBodies)
        assertEquals(provider.getRequests.size, provider.closedBodies)
        return result
    }

    private fun retention(createdAt: Long): Long = Instant.ofEpochSecond(createdAt).atOffset(ZoneOffset.UTC).plusYears(10).toEpochSecond()

    private fun reject(code: CatalogReadbackFailure, action: () -> Unit) {
        assertEquals(code, assertThrows(CatalogReadbackException::class.java) { action() }.code)
    }
}
