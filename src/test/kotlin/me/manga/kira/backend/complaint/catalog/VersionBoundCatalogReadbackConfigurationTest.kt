package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** Pure independent settings/calendar checks and genuine raw signatures over supplied observations, not SDK/PG authority. */
class VersionBoundCatalogReadbackConfigurationTest {
    private val fixture = VersionBoundCatalogReadbackTestFixture

    @Test
    fun `raw artifacts public root and independent selection inputs remain defensive`() {
        val initial = fixture.initialBundleBytes()
        val current = fixture.currentBundleBytes()
        val root = fixture.rootPublicKeySpki()
        val locations = OfflineTrustBundleFixture.locations.toMutableList()
        val writers = mutableListOf(fixture.CATALOG_WRITER)
        val approvers = mutableListOf("catalog-approver-a", "catalog-approver-b")
        val policy = fixture.chainPolicy(fixture.trustPolicy(root = root, locations = locations), writers, approvers)
        val settings = fixture.settings(initial = initial, current = current, policy = policy)
        initial.fill(0)
        current.fill(0)
        root.fill(0)
        locations.clear()
        writers.clear()
        approvers.clear()
        settings.initialBundleBytes().fill(0)
        settings.currentBundleBytes().fill(0)
        settings.chainPolicy.trustBundlePolicy.rootPublicKeySpki.fill(0)
        assertArrayEquals(fixture.initialBundleBytes(), settings.initialBundleBytes())
        assertArrayEquals(fixture.currentBundleBytes(), settings.currentBundleBytes())
        assertArrayEquals(fixture.rootPublicKeySpki(), settings.chainPolicy.trustBundlePolicy.rootPublicKeySpki)
        assertEquals(OfflineTrustBundleFixture.locations, settings.chainPolicy.trustBundlePolicy.expectedCatalogLocations)
        assertEquals(listOf(fixture.CATALOG_WRITER), settings.chainPolicy.currentWriterGenerationIds)
        assertEquals(listOf("catalog-approver-a", "catalog-approver-b"), settings.chainPolicy.currentApproverIds)
    }

    @Test
    fun `current root location version and G1 profile failures cannot become retained settings`() {
        val badTrust = listOf(
            fixture.trustPolicy(fingerprint = "0".repeat(64)),
            fixture.trustPolicy(rootId = "wrong-independent-root"),
            fixture.trustPolicy(environment = "wrong-environment"),
            fixture.trustPolicy(locations = OfflineTrustBundleFixture.locations.reversed()),
            fixture.trustPolicy(minimumVersion = 10),
        )
        badTrust.forEach { trust -> rejected(CatalogReadbackFailure.INVALID_POLICY) { fixture.settings(policy = fixture.chainPolicy(trust)) } }
        rejected(CatalogReadbackFailure.INVALID_POLICY) { fixture.settings(current = fixture.initialBundleBytes()) }
        rejected(CatalogReadbackFailure.INVALID_POLICY) { fixture.settings(pin = "not-a-pin") }
        rejected(CatalogReadbackFailure.INVALID_POLICY) { fixture.settings(totalAttemptMillis = 600_001) }
        rejected(CatalogReadbackFailure.INVALID_POLICY) { fixture.settings(totalAttemptMillis = 0) }
        val laterHead = OfflineTrustBundleFixture.bytes(OfflineTrustBundleFixture.signed())
        val policy = OfflineCatalogChainReaderPolicy(
            OfflineTrustBundleFixture.policy(),
            listOf(OfflineTrustBundleFixture.CATALOG_WRITER),
            listOf("catalog-approver-a", "catalog-approver-b"),
            OfflineCatalogRotationFixture.limits(),
        )
        rejected(CatalogReadbackFailure.INVALID_POLICY) { fixture.settings(initial = laterHead, current = laterHead, policy = policy) }
    }

    @Test
    fun `whole attempt precedes UTC calendar years and whole-second ceiling with checked upper range`() {
        val settings = fixture.settings(totalAttemptMillis = 1)
        val boundaries = listOf(
            "2024-02-29T12:34:56Z" to "2026-02-28T12:34:57Z",
            "2024-02-29T23:59:59.999999999Z" to "2026-03-01T00:00:01Z",
            "9997-12-31T23:59:58.999Z" to "9999-12-31T23:59:59Z",
        )
        boundaries.forEach { (evaluation, expected) ->
            assertEquals(Instant.parse(expected).epochSecond, settings.policyAt(Instant.parse(evaluation)).requiredRetainUntilEpochSecond)
        }
        assertEquals(
            Instant.parse("2028-09-17T00:10:00Z").epochSecond,
            fixture.settings().policyAt(fixture.evaluatedAt).requiredRetainUntilEpochSecond,
        )
        listOf(Instant.MIN, Instant.MAX, Instant.ofEpochSecond(-1), Instant.parse("9997-12-31T23:59:58.999000001Z")).forEach { instant ->
            rejected(CatalogReadbackFailure.RETENTION_MISMATCH) { settings.policyAt(instant) }
        }
    }

    @Test
    fun `same exact G1 remains resumable without moving ten-year floor and both retention boundaries refuse short copies`() {
        val settings = fixture.settings()
        val first = readback(settings)
        settings.verifyGenesis(first, fixture.evaluatedAt)
        val later = Instant.parse("2030-09-17T00:00:00Z")
        val replay = readback(settings, later)
        settings.verifyGenesis(replay, later)
        assertArrayEquals(fixture.genesisBytes(), checkNotNull(replay.mutation().signedEnvelopeBytes))
        assertArrayEquals(first.primaryEvidenceBytes(), replay.primaryEvidenceBytes())
        val shortCreation = readback(settings, retention = fixture.retainUntil.minusSeconds(1))
        rejected(CatalogReadbackFailure.RETENTION_MISMATCH) { settings.verifyGenesis(shortCreation, fixture.evaluatedAt) }
        val renewalBoundary = Instant.parse("2032-02-28T12:24:56Z")
        settings.verifyGenesis(readback(settings, renewalBoundary), renewalBoundary)
        rejected(CatalogReadbackFailure.RETENTION_MISMATCH) { readback(settings, renewalBoundary.plusNanos(1)) }
    }

    @Test
    fun `genuine raw handoff cannot bypass selected pin writer approvers time or future signed creation`() {
        val settings = fixture.settings()
        val observed = readback(settings)
        rejected(CatalogReadbackFailure.INVALID_POLICY) { fixture.settings(pin = "ab".repeat(32)).verifyGenesis(observed, fixture.evaluatedAt) }
        rejected(CatalogReadbackFailure.INVALID_POLICY) { settings.verifyGenesis(observed, fixture.evaluatedAt.plusSeconds(1)) }
        val anotherWriter = fixture.chainPolicy(writers = listOf("77777777-7777-4777-8777-777777777777"))
        rejected(CatalogReadbackFailure.HEAD_CONFLICT) { fixture.settings(policy = anotherWriter).verifyGenesis(observed, fixture.evaluatedAt) }
        val anotherApprover = fixture.chainPolicy(approvers = listOf("catalog-approver-a", "catalog-approver-c"))
        rejected(CatalogReadbackFailure.HEAD_CONFLICT) { fixture.settings(policy = anotherApprover).verifyGenesis(observed, fixture.evaluatedAt) }
        val beforeCreation = Instant.ofEpochSecond(fixture.envelope().manifest.creation.createdAtEpochSecond - 1)
        val future = readback(settings, beforeCreation)
        rejected(CatalogReadbackFailure.RETENTION_MISMATCH) { settings.verifyGenesis(future, beforeCreation) }
    }

    private fun readback(
        settings: VersionBoundCatalogReadbackConfigurationV1,
        evaluation: Instant = fixture.evaluatedAt,
        retention: Instant = fixture.retainUntil,
    ): CatalogDualLocationVerifier.GenesisReadback {
        val port = SyntheticCatalogReadbackPort(listOf(fixture.genesisBytes())).apply {
            transformMetadata = { it.copy(retainUntilEpochSecond = retention.epochSecond) }
        }
        return CatalogDualLocationVerifier.GenesisReadback.verify(
            port,
            settings.initialBundleBytes(),
            settings.currentBundleBytes(),
            settings.policyAt(evaluation),
            LocalCatalogSnapshot.Accepted(CatalogLocalHead(1, fixture.EXPECTED_GENESIS_SHA256)),
        )
    }

    private fun rejected(code: CatalogReadbackFailure, action: () -> Unit) {
        val failure = assertThrows<CatalogReadbackException> { action() }
        assertEquals(code, failure.code)
        assertEquals("Catalog readback rejected: ${code.name}.", failure.message)
        assertNull(failure.cause)
    }
}
