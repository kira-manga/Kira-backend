package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CheckedHistoricalGenesisEvidence
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogGenesisVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OfflineHistoricalGenesisVerifierTest {
    private val bundleFixture = OfflineTrustBundleFixture
    private val genesisFixture = OfflineCatalogGenesisFixture
    private val initial by lazy { genesisFixture.bundle() }
    private val current by lazy {
        bundleFixture.signed(initial.body.copy(version = 9, minimumCatalogHeadGeneration = 42, issuedAtEpochSecond = initial.body.issuedAtEpochSecond + 3600))
    }
    private val genesis by lazy { genesisFixture.signed(genesisFixture.manifest(initial)) }
    private val policy get() = bundleFixture.policy(minimumVersion = 9)

    @Test
    fun `old T0 under current floor produces only historical evidence while direct current APIs still reject T0`() {
        val checked = verify()
        assertEquals(initial.body, checked.initialTrustBundle.body)
        assertEquals(current.body, checked.currentTrustBundle.body)
        assertEquals(genesis.manifest, checked.genesis.manifest)
        assertArrayEquals(bundleFixture.bytes(initial), checked.initialTrustBundle.canonicalEnvelopeBytes)
        assertArrayEquals(bundleFixture.bytes(current), checked.currentTrustBundle.canonicalEnvelopeBytes)
        assertArrayEquals(genesisFixture.bytes(genesis), checked.genesis.canonicalEnvelopeBytes)
        assertEquals(Sha256.hex(bundleFixture.bytes(initial)), checked.initialTrustBundle.envelopeSha256)
        assertEquals(Sha256.hex(bundleFixture.bytes(current)), checked.currentTrustBundle.envelopeSha256)
        assertEquals(Sha256.hex(genesisFixture.bytes(genesis)), checked.genesis.envelopeSha256)
        assertEquals(42L, checked.currentTrustBundle.body.minimumCatalogHeadGeneration)
        assertEquals(1L, checked.genesis.manifest.generation)
        assertEquals("CheckedHistoricalGenesisEvidence(historical-only,no-accepted-head)", checked.toString())
        val currentFailure = assertThrows(OfflineTrustBundleException::class.java) {
            OfflineTrustBundleVerifier.verify(bundleFixture.bytes(initial), policy)
        }
        assertEquals(OfflineTrustBundleFailure.VERSION_ROLLBACK, currentFailure.code)
        val genesisFailure = assertThrows(OfflineTrustBundleException::class.java) {
            OfflineCatalogGenesisVerifier.verify(genesisFixture.bytes(genesis), bundleFixture.bytes(initial), policy)
        }
        assertEquals(OfflineTrustBundleFailure.VERSION_ROLLBACK, genesisFailure.code)
        assertEquals(9L, policy.minimumBundleVersion)
    }

    @Test
    fun `exact same T0 and current envelope remain valid at the independently permitted current version`() {
        val initialPolicy = bundleFixture.policy()
        val checked = verify(currentBundle = initial, independentPolicy = initialPolicy)
        val original = OfflineCatalogGenesisVerifier.verify(genesisFixture.bytes(genesis), bundleFixture.bytes(initial), initialPolicy)
        assertEquals(original.manifest, checked.genesis.manifest)
        assertArrayEquals(original.canonicalEnvelopeBytes, checked.genesis.canonicalEnvelopeBytes)
        assertArrayEquals(checked.initialTrustBundle.canonicalEnvelopeBytes, checked.currentTrustBundle.canonicalEnvelopeBytes)
    }

    @Test
    fun `historical evidence never bypasses current version root key identity or fingerprint pins`() {
        reject(OfflineTrustBundleFailure.VERSION_ROLLBACK, currentBundle = bundleFixture.signed(current.body.copy(version = 8)))
        reject(
            OfflineTrustBundleFailure.INVALID_SIGNATURE,
            independentPolicy = bundleFixture.policy(minimumVersion = 9, rootSpki = bundleFixture.secondSigner.public.encoded),
        )
        reject(OfflineTrustBundleFailure.POLICY_MISMATCH, independentPolicy = bundleFixture.policy(minimumVersion = 9, rootId = "other-root"))
        reject(OfflineTrustBundleFailure.INVALID_POLICY, independentPolicy = bundleFixture.policy(minimumVersion = 9, rootFingerprint = "1".repeat(64)))
    }

    @Test
    fun `both current and historical root signatures are actually checked even with a rebound catalog signature`() {
        val tamperedCurrent = current.copy(body = current.body.copy(issuedAtEpochSecond = current.body.issuedAtEpochSecond + 1))
        reject(OfflineTrustBundleFailure.INVALID_SIGNATURE, currentBundle = tamperedCurrent)
        val tamperedInitial = initial.copy(body = initial.body.copy(issuedAtEpochSecond = initial.body.issuedAtEpochSecond + 1))
        val rebound = genesisFixture.signed(genesisFixture.manifest(tamperedInitial))
        reject(OfflineTrustBundleFailure.INVALID_SIGNATURE, initialBundle = tamperedInitial, genesisEnvelope = rebound)
    }

    @Test
    fun `independent environment and both location bindings apply to current and historical bundles`() {
        val primaryChanged = bundleFixture.locations.mapIndexed { index, location ->
            if (index == 0) location.copy(bucket = "other-primary-catalog") else location
        }
        val replicaChanged = bundleFixture.locations.mapIndexed { index, location ->
            if (index == 1) location.copy(accountId = "444444444444", region = "eu-west-1") else location
        }
        val otherLocations = listOf(primaryChanged, replicaChanged)
        reject(OfflineTrustBundleFailure.POLICY_MISMATCH, independentPolicy = bundleFixture.policy(minimumVersion = 9, environment = "other-test"))
        otherLocations.forEach { locations ->
            reject(OfflineTrustBundleFailure.POLICY_MISMATCH, independentPolicy = bundleFixture.policy(minimumVersion = 9, catalogLocations = locations))
        }
        val changedBodies = listOf(initial.body.copy(environment = "other-test")) + otherLocations.map { initial.body.copy(catalogLocations = it) }
        changedBodies.forEach { body ->
            val changed = bundleFixture.signed(body)
            val rebound = genesisFixture.signed(genesisFixture.manifest(changed))
            reject(OfflineTrustBundleFailure.POLICY_MISMATCH, initialBundle = changed, genesisEnvelope = rebound)
        }
        val otherRootId = initial.copy(signature = initial.signature.copy(keyId = "other-root"))
        reject(OfflineTrustBundleFailure.POLICY_MISMATCH, initialBundle = otherRootId)
    }

    @Test
    fun `a root signed future initial version cannot be historical evidence under an earlier current version`() {
        val future = bundleFixture.signed(initial.body.copy(version = current.body.version + 1))
        val rebound = genesisFixture.signed(genesisFixture.manifest(future))
        reject(OfflineTrustBundleFailure.POLICY_MISMATCH, initialBundle = future, genesisEnvelope = rebound)
    }

    @Test
    fun `a lower version T0 issued after current Tn is rejected even when its complete genesis chronology is valid`() {
        val future = bundleFixture.signed(initial.body.copy(issuedAtEpochSecond = current.body.issuedAtEpochSecond + 1))
        val manifest = genesisFixture.manifest(future)
        val created = future.body.issuedAtEpochSecond + 60
        val rebound = genesisFixture.signed(
            manifest.copy(
                creation = manifest.creation.copy(createdAtEpochSecond = created),
                approvals = manifest.approvals.map { it.copy(approvedAtEpochSecond = created + 1) },
                oldestRestoreTimeEpochSecond = created,
            ),
        )
        OfflineCatalogGenesisVerifier.verify(genesisFixture.bytes(rebound), bundleFixture.bytes(future), bundleFixture.policy())
        reject(OfflineTrustBundleFailure.POLICY_MISMATCH, initialBundle = future, genesisEnvelope = rebound)
    }

    @Test
    fun `equal versions require identical envelopes not merely identical bodies or valid root signatures`() {
        val alternativeSignature = bundleFixture.signed(initial.body)
        assertEquals(initial.body, alternativeSignature.body)
        assertNotEquals(Sha256.hex(bundleFixture.bytes(initial)), Sha256.hex(bundleFixture.bytes(alternativeSignature)))
        val differentBody = bundleFixture.signed(current.body.copy(version = initial.body.version))
        listOf(alternativeSignature, differentBody).forEach { sameVersion ->
            OfflineTrustBundleVerifier.verify(bundleFixture.bytes(sameVersion), bundleFixture.policy())
            reject(OfflineTrustBundleFailure.POLICY_MISMATCH, currentBundle = sameVersion, independentPolicy = bundleFixture.policy())
        }
    }

    @Test
    fun `historical initial bundle must still have genesis head floor one`() {
        val noninitial = bundleFixture.signed(initial.body.copy(minimumCatalogHeadGeneration = 2))
        val rebound = genesisFixture.signed(genesisFixture.manifest(noninitial))
        reject(OfflineTrustBundleFailure.POLICY_MISMATCH, initialBundle = noninitial, genesisEnvelope = rebound)
    }

    @Test
    fun `every initial bootstrap authority component stays immutable across genuinely signed releases`() {
        val authority = current.body.bootstrapAuthority
        listOf(
            authority.copy(catalogWriterGenerationId = bundleFixture.EVENT_WRITER),
            authority.copy(requiredSigner = authority.requiredSigner.copy(keyId = "catalog-new")),
            authority.copy(initialWriterRegistrySha256 = "1".repeat(64)),
            authority.copy(catalogApproverIds = authority.catalogApproverIds.reversed()),
        ).forEach { changed ->
            val release = bundleFixture.signed(current.body.copy(bootstrapAuthority = changed))
            OfflineTrustBundleVerifier.verify(bundleFixture.bytes(release), policy)
            reject(OfflineTrustBundleFailure.POLICY_MISMATCH, currentBundle = release)
        }
    }

    @Test
    fun `current release cannot remove or retarget the genesis key even with a genuine root signature`() {
        val removed = bundleFixture.signed(current.body.copy(signers = current.body.signers.filterNot { it.keyId == "catalog-old" }))
        reject(OfflineTrustBundleFailure.INVALID_DOCUMENT, currentBundle = removed)
        val differentMaterial = bundleFixture.signer(bundleFixture.secondSigner, "catalog-old")
        val retargeted = bundleFixture.signed(current.body.copy(signers = listOf(differentMaterial)))
        val checkedCurrent = OfflineTrustBundleVerifier.verify(bundleFixture.bytes(retargeted), policy)
        assertEquals(initial.body.bootstrapAuthority, checkedCurrent.body.bootstrapAuthority)
        assertNotEquals(initial.body.signers.first().publicKeySpkiBase64, checkedCurrent.body.signers.single().publicKeySpkiBase64)
        reject(OfflineTrustBundleFailure.POLICY_MISMATCH, currentBundle = retargeted)
    }

    @Test
    fun `fixed signer algorithm validation is not skipped for either authenticated release`() {
        val changedSigner = initial.body.signers.first().copy(algorithmId = "Ed25519")
        val changedSigners = listOf(changedSigner, initial.body.signers.last())
        val changedCurrent = bundleFixture.signed(current.body.copy(signers = changedSigners))
        reject(OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM, currentBundle = changedCurrent)
        val changedInitial = bundleFixture.signed(initial.body.copy(signers = changedSigners))
        reject(OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM, initialBundle = changedInitial)
    }

    @Test
    fun `unrelated public verifier entries can change without changing immutable initial authority`() {
        listOf(current.body.signers.take(1), current.body.signers.reversed()).forEach { signers ->
            val release = bundleFixture.signed(current.body.copy(signers = signers))
            val checked = verify(currentBundle = release)
            assertEquals(genesis.manifest, checked.genesis.manifest)
            assertEquals(signers, checked.currentTrustBundle.body.signers)
        }
    }

    @Test
    fun `historical genesis still binds the complete exact T0 envelope including its particular root signature`() {
        val alternate = bundleFixture.signed(initial.body)
        assertEquals(initial.body, alternate.body)
        assertNotEquals(Sha256.hex(bundleFixture.bytes(initial)), Sha256.hex(bundleFixture.bytes(alternate)))
        reject(OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH, initialBundle = alternate)
        val bodyOnlyHash = Sha256.hex(bundleFixture.bodyBytes(initial.body))
        val bodyBound = genesisFixture.signed(genesis.manifest.copy(initialTrustBundleEnvelopeSha256 = bodyOnlyHash))
        reject(OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH, genesisEnvelope = bodyBound)
    }

    @Test
    fun `complete registry semantics are reapplied after both valid root signatures and registry hash are rebound`() {
        val registry = genesis.manifest.initialWriterRegistry
        val range = registry.eventWriter.liveRange
        val aliased = registry.copy(eventWriter = registry.eventWriter.copy(liveRange = range.copy(ordinaryPrefix = range.sealTerminalPrefix)))
        val reboundInitial = bundleFixture.signed(genesisFixture.bundleBody(aliased))
        val reboundCurrent = bundleFixture.signed(current.body.copy(bootstrapAuthority = reboundInitial.body.bootstrapAuthority))
        val reboundGenesis = genesisFixture.signed(genesisFixture.manifest(reboundInitial, aliased))
        OfflineTrustBundleVerifier.verify(bundleFixture.bytes(reboundCurrent), policy)
        reject(OfflineTrustBundleFailure.INVALID_DOCUMENT, reboundInitial, reboundCurrent, reboundGenesis)
    }

    @Test
    fun `historical path checks actual catalog signature and the closed genesis rules not just T0 linkage`() {
        val tampered = genesis.copy(manifest = genesis.manifest.copy(operationToken = bundleFixture.EVENT_WRITER))
        reject(OfflineTrustBundleFailure.INVALID_SIGNATURE, genesisEnvelope = tampered)
        val manifest = genesis.manifest
        listOf(
            manifest.copy(generation = 2),
            manifest.copy(approvals = manifest.approvals.take(1)),
            manifest.copy(restoreInventory = manifest.restoreInventory.copy(count = 1)),
        ).forEach { changed ->
            reject(OfflineTrustBundleFailure.INVALID_DOCUMENT, genesisEnvelope = genesisFixture.signed(changed))
        }
    }

    @Test
    fun `all three raw inputs remain canonical and historical T0 has no alternate schema or canonicalizer`() {
        rejectBytes(OfflineTrustBundleFailure.NON_CANONICAL, initialBytes = bundleFixture.bytes(initial) + ' '.code.toByte())
        rejectBytes(OfflineTrustBundleFailure.NON_CANONICAL, currentBytes = bundleFixture.bytes(current) + ' '.code.toByte())
        rejectBytes(OfflineTrustBundleFailure.NON_CANONICAL, genesisBytes = genesisFixture.bytes(genesis) + ' '.code.toByte())
        val wrongCanonicalizer = bundleFixture.signed(initial.body.copy(canonicalizerId = "generic-json"))
        reject(OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA, initialBundle = wrongCanonicalizer)
        val wrongSchema = bundleFixture.signed(initial.body.copy(schemaVersion = 2))
        reject(OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA, initialBundle = wrongSchema)
    }

    @Test
    fun `each raw input is separately byte bounded without widening the narrow bootstrap reader`() {
        val oversized = ByteArray(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES + 1)
        rejectBytes(OfflineTrustBundleFailure.LIMIT_EXCEEDED, initialBytes = oversized)
        rejectBytes(OfflineTrustBundleFailure.LIMIT_EXCEEDED, currentBytes = oversized)
        rejectBytes(OfflineTrustBundleFailure.LIMIT_EXCEEDED, genesisBytes = oversized)
    }

    @Test
    fun `historical evidence defends all three input byte sets and nested bundle and genesis claims`() {
        val initialBytes = bundleFixture.bytes(initial)
        val currentBytes = bundleFixture.bytes(current)
        val genesisBytes = genesisFixture.bytes(genesis)
        val checked = OfflineTrustBundleVerifier.verifyBootstrapEvidence(genesisBytes, initialBytes, currentBytes, policy)
        initialBytes.fill(0)
        currentBytes.fill(0)
        genesisBytes.fill(0)
        listOf(checked.initialTrustBundle, checked.currentTrustBundle).forEach { bundle ->
            bundle.canonicalBodyBytes.fill(0)
            bundle.canonicalEnvelopeBytes.fill(0)
            val returned = bundle.body
            (returned.signers as MutableList<OfflineCatalogSignerV1>).clear()
            (returned.bootstrapAuthority.catalogApproverIds as MutableList<String>).clear()
            assertNotSame(returned.catalogLocations, bundle.body.catalogLocations)
        }
        checked.genesis.canonicalManifestBytes.fill(0)
        checked.genesis.canonicalEnvelopeBytes.fill(0)
        val returnedGenesis = checked.genesis.manifest
        (returnedGenesis.approvals as MutableList<OfflineCatalogGenesisApprovalV1>).clear()
        (returnedGenesis.initialWriterRegistry.catalogWriter.catalogApproverIds as MutableList<String>).clear()
        assertNotSame(returnedGenesis.requiredSignerPolicy.members, checked.genesis.manifest.requiredSignerPolicy.members)
        assertEquals(initial.body, checked.initialTrustBundle.body)
        assertEquals(current.body, checked.currentTrustBundle.body)
        assertEquals(genesis.manifest, checked.genesis.manifest)
        assertArrayEquals(bundleFixture.bytes(initial), checked.initialTrustBundle.canonicalEnvelopeBytes)
        assertArrayEquals(bundleFixture.bodyBytes(initial.body), checked.initialTrustBundle.canonicalBodyBytes)
        assertArrayEquals(bundleFixture.bytes(current), checked.currentTrustBundle.canonicalEnvelopeBytes)
        assertArrayEquals(bundleFixture.bodyBytes(current.body), checked.currentTrustBundle.canonicalBodyBytes)
        assertArrayEquals(genesisFixture.bytes(genesis), checked.genesis.canonicalEnvelopeBytes)
        assertArrayEquals(genesisFixture.manifestBytes(genesis.manifest), checked.genesis.canonicalManifestBytes)
    }

    private fun verify(
        initialBundle: OfflineTrustBundleEnvelopeV1 = initial,
        currentBundle: OfflineTrustBundleEnvelopeV1 = current,
        genesisEnvelope: OfflineCatalogGenesisEnvelopeV1 = genesis,
        independentPolicy: OfflineTrustBundlePolicy = policy,
    ): CheckedHistoricalGenesisEvidence = OfflineTrustBundleVerifier.verifyBootstrapEvidence(
        genesisFixture.bytes(genesisEnvelope),
        bundleFixture.bytes(initialBundle),
        bundleFixture.bytes(currentBundle),
        independentPolicy,
    )

    private fun reject(
        code: OfflineTrustBundleFailure,
        initialBundle: OfflineTrustBundleEnvelopeV1 = initial,
        currentBundle: OfflineTrustBundleEnvelopeV1 = current,
        genesisEnvelope: OfflineCatalogGenesisEnvelopeV1 = genesis,
        independentPolicy: OfflineTrustBundlePolicy = policy,
    ) {
        val failure = assertThrows(OfflineTrustBundleException::class.java) { verify(initialBundle, currentBundle, genesisEnvelope, independentPolicy) }
        assertEquals(code, failure.code)
    }

    private fun rejectBytes(
        code: OfflineTrustBundleFailure,
        initialBytes: ByteArray = bundleFixture.bytes(initial),
        currentBytes: ByteArray = bundleFixture.bytes(current),
        genesisBytes: ByteArray = genesisFixture.bytes(genesis),
    ) {
        val failure = assertThrows(OfflineTrustBundleException::class.java) {
            OfflineTrustBundleVerifier.verifyBootstrapEvidence(genesisBytes, initialBytes, currentBytes, policy)
        }
        assertEquals(code, failure.code)
    }
}
