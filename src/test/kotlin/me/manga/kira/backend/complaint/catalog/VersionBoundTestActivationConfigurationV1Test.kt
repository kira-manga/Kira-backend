package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.domain.JournalWriterV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintProcessPoolFixture
import me.manga.kira.backend.complaint.infrastructure.admission.projectedSettings
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogRotationChainVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundTestActivationConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningKeyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import me.manga.kira.backend.security.fullTestJournal
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.Base64

/** Cold original coordinator, independent raw registry/trust and public SPKI only. No Sign, PUT, SQL, session or activation. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundTestActivationConfigurationV1Test {
    @Test
    fun `both explicit reader profiles retain the actual writer key coordinator and complete fixed activation inventory`() =
        ComplaintProcessPoolFixture().use { database ->
            val pools = database.bind()
            val journal = fullTestJournal()
            val g1 = VersionBoundCatalogReadbackTestFixture.settings()
            for (reader in listOf(g1, projectedSettings(g1))) {
                val registry = FullTestCatalogInputs.registryBytes()
                val keyBytes = FullTestCatalogInputs.publicKeyBytes()
                val key = FullTestCatalogInputs.key(spki = keyBytes)
                val owner = FullTestCatalogInputs.activation(pools, journal, reader, key, registry)
                val expected = FullTestCatalogInputs.goldenDocument(reader.projectedCurrent).getValue("catalogActivation")
                assertEquals(expected, owner.inventory())
                assertSame(pools, owner.pools)
                assertSame(pools.catalogCoordinator, owner.coordinator)
                assertSame(reader, owner.reader)
                assertSame(journal, owner.journal)
                assertSame(key, owner.signingKey)
                registry.fill(0)
                keyBytes.fill(0)
                owner.initialWriterRegistryBytes().fill(0)
                (owner.initialApproverIds() as MutableList<*>).clear()
                (owner.initialWriterRegistry().catalogWriter.catalogApproverIds as MutableList<*>).clear()
                owner.requireRetained(pools, reader, journal)
                assertArrayEquals(FullTestCatalogInputs.registryBytes(), owner.initialWriterRegistryBytes())
                assertEquals(expected, owner.inventory())
            }
            assertTrue(listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
        }

    @Test
    fun `a completed genuine rotation permits the current signer although the immutable initial registry names another key`() =
        ComplaintProcessPoolFixture().use { database ->
            val pools = database.bind()
            val chain = OfflineCatalogRotationFixture.chain()
            val current = OfflineTrustBundleFixture.signed(chain.current.body.copy(minimumCatalogHeadGeneration = 3))
            val initialBytes = OfflineTrustBundleFixture.bytes(chain.initial)
            val currentBytes = OfflineTrustBundleFixture.bytes(current)
            val policy = OfflineCatalogRotationFixture.policy()
            val folded = OfflineCatalogRotationChainVerifier.verifyRotationChain(chain.bytes().asSequence(), initialBytes, currentBytes, policy)
            assertEquals(3L, folded.tail.generation)
            assertEquals(CatalogRotationState.Stable(OfflineCatalogRotationFixture.member("catalog-new")), folded.rotation)
            val reader = VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
                initialBytes, currentBytes, policy, Sha256.hex(OfflineCatalogGenesisFixture.bytes(chain.genesis)), S3CatalogReadbackLimits(), 600_000,
            )
            val registry = chain.genesis.manifest.initialWriterRegistry
            val originalJ = fullTestJournal().declaration()
            val journal = TestOwnerDeleteJournalConfigurationV1.of(
                originalJ.copy(writer = JournalWriterV1(registry.databaseIdentity, registry.restoreIdentity, registry.eventWriter.generationId)),
            )
            val actualKey = FullTestCatalogInputs.key("catalog-new", OfflineTrustBundleFixture.secondSigner.public.encoded)
            val owner = FullTestCatalogInputs.activation(pools, journal, reader, actualKey, OfflineTrustBundleFixture.registryBytes(registry))
            assertEquals("catalog-old", owner.initialWriterRegistry().catalogWriter.requiredSignerPolicy.members.single().keyId)
            assertEquals("catalog-new", owner.requiredSignerPolicy().members.single().keyId)
            assertEquals("SINGLE", owner.requiredSignerPolicy().mode)
            assertEquals("ALL_MEMBERS", owner.requiredSignerPolicy().threshold)
            assertSame(actualKey, owner.signingKey)
            assertEquals(Sha256.hex(OfflineTrustBundleFixture.secondSigner.public.encoded), owner.publicKeySha256)
            assertEquals("STABLE_SINGLE_SIGNER", owner.predecessorPolicy)
            // The independent fold above is a test witness, never passed into or issued by the cold owner.
            assertArrayEquals(OfflineTrustBundleFixture.registryBytes(registry), owner.initialWriterRegistryBytes())
        }

    @Test
    fun `canonical verified registry exact writer identities and current writer eligibility are mandatory`() =
        ComplaintProcessPoolFixture().use { database ->
            val pools = database.bind()
            val journal = fullTestJournal()
            val original = journal.declaration()
            val changes = listOf(
                original.writer.copy(databaseIdentity = "81111111-1111-4111-8111-111111111111"),
                original.writer.copy(restoreIdentity = "82222222-2222-4222-8222-222222222222"),
                original.writer.copy(generationId = "83333333-3333-4333-8333-333333333333"),
            )
            changes.forEach { writer ->
                assertThrows<IllegalArgumentException> {
                    FullTestCatalogInputs.activation(pools, TestOwnerDeleteJournalConfigurationV1.of(original.copy(writer = writer)))
                }
            }
            val excludedWriter = VersionBoundCatalogReadbackTestFixture.settings(
                policy = VersionBoundCatalogReadbackTestFixture.chainPolicy(writers = listOf(original.writer.generationId)),
            )
            assertThrows<IllegalArgumentException> { FullTestCatalogInputs.activation(pools, journal, excludedWriter) }
            val registry = FullTestCatalogInputs.registry()
            val changedRegistry = registry.copy(catalogWriter = registry.catalogWriter.copy(signAuthority = registry.catalogWriter.putAuthority))
            assertEquals(
                OfflineTrustBundleFailure.REGISTRY_HASH_MISMATCH,
                assertThrows<OfflineTrustBundleException> {
                    FullTestCatalogInputs.activation(pools, journal, registryBytes = OfflineTrustBundleFixture.registryBytes(changedRegistry))
                }.code,
            )
            assertEquals(
                OfflineTrustBundleFailure.NON_CANONICAL,
                assertThrows<OfflineTrustBundleException> {
                    FullTestCatalogInputs.activation(pools, journal, registryBytes = FullTestCatalogInputs.registryBytes() + byteArrayOf(10))
                }.code,
            )
            for (bytes in listOf(byteArrayOf(), ByteArray(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES + 1))) {
                assertThrows<IllegalArgumentException> { FullTestCatalogInputs.activation(pools, journal, registryBytes = bytes) }
            }
        }

    @Test
    fun `catalog Sign and PUT are separately checked against every TEST journal role credential policy and administrator`() =
        ComplaintProcessPoolFixture().use { database ->
            val pools = database.bind()
            val declaration = fullTestJournal().declaration()
            val catalog = FullTestCatalogInputs.registry().catalogWriter
            for (principal in listOf(catalog.signAuthority, catalog.putAuthority)) {
                for (index in 0..2) {
                    val roles = listOf(declaration.authorities.ordinary, declaration.authorities.sealTerminal, declaration.authorities.recovery)
                    val original = roles[index]
                    val mutations = listOf(
                        original.copy(roleId = principal.principalId), original.copy(credentialId = principal.principalId),
                        original.copy(policy = original.policy.copy(policyId = principal.policy.policyId)),
                    )
                    mutations.forEach { replacement ->
                        val changed = roles.mapIndexed { at, role -> if (at == index) replacement else role }
                        val authorities = declaration.authorities.copy(ordinary = changed[0], sealTerminal = changed[1], recovery = changed[2])
                        val journal = TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(authorities = authorities))
                        assertThrows<IllegalArgumentException> { FullTestCatalogInputs.activation(pools, journal) }
                    }
                }
                val isolation = declaration.authorities.isolation
                val changedIsolation = listOf(
                    isolation.copy(bucketAdministratorId = principal.principalId), isolation.copy(kmsAdministratorId = principal.principalId),
                    isolation.copy(administrationPolicy = isolation.administrationPolicy.copy(policyId = principal.policy.policyId)),
                )
                for (changed in changedIsolation) {
                    val journal = TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(authorities = declaration.authorities.copy(isolation = changed)))
                    assertThrows<IllegalArgumentException> { FullTestCatalogInputs.activation(pools, journal) }
                }
            }
            // A freshly signed registry avoids stopping at a stale hash: catalog Sign/PUT cannot alias each other either.
            val registry = OfflineTrustBundleFixture.registry()
            val alias = registry.copy(catalogWriter = registry.catalogWriter.copy(signAuthority = registry.catalogWriter.putAuthority))
            val reader = FullTestCatalogInputs.signedReader(alias)
            val journal = TestOwnerDeleteJournalConfigurationV1.of(
                declaration.copy(writer = JournalWriterV1(alias.databaseIdentity, alias.restoreIdentity, alias.eventWriter.generationId)),
            )
            assertEquals(
                OfflineTrustBundleFailure.INVALID_DOCUMENT,
                assertThrows<OfflineTrustBundleException> {
                    FullTestCatalogInputs.activation(
                        pools, journal, reader, FullTestCatalogInputs.key(spki = OfflineTrustBundleFixture.firstSigner.public.encoded),
                        OfflineTrustBundleFixture.registryBytes(alias),
                    )
                }.code,
            )
        }

    @Test
    fun `selected key requires exact current-bundle public material algorithm and fingerprint not an ID or supplied hash alone`(): Unit =
        ComplaintProcessPoolFixture().use { database ->
            val pools = database.bind()
            val journal = fullTestJournal()
            assertThrows<IllegalArgumentException> { FullTestCatalogInputs.activation(pools, journal, key = FullTestCatalogInputs.key("not-in-bundle")) }
            assertThrows<IllegalArgumentException> {
                FullTestCatalogInputs.activation(
                    pools, journal, key = FullTestCatalogInputs.key(spki = VersionBoundCatalogReadbackTestFixture.rootPublicKeySpki()),
                )
            }
            assertThrows<CatalogSigningExceptionV1> {
                FullTestCatalogInputs.activation(pools, journal, key = FullTestCatalogInputs.key(fingerprint = "0".repeat(64)))
            }
            assertThrows<OfflineTrustBundleException> {
                FullTestCatalogInputs.activation(pools, journal, key = FullTestCatalogInputs.key(spki = ByteArray(422)))
            }
            assertThrows<CatalogSigningExceptionV1> {
                FullTestCatalogInputs.activation(pools, journal, key = FullTestCatalogInputs.key(algorithm = "RSASSA_PKCS1_V1_5_SHA_256"))
            }
        }

    @Test
    fun `writer budgets are checked once and identical declarations cannot replace original pool reader or TEST J owners`() =
        ComplaintProcessPoolFixture().use { database ->
            ComplaintProcessPoolFixture().use { replacement ->
                val pools = database.bind()
                val otherPools = replacement.bind()
                val journal = fullTestJournal()
                val reader = VersionBoundCatalogReadbackTestFixture.settings()
                val owner = FullTestCatalogInputs.activation(pools, journal, reader)
                assertThrows<IllegalArgumentException> { owner.requireRetained(otherPools, reader, journal) }
                assertThrows<IllegalArgumentException> { owner.requireRetained(pools, VersionBoundCatalogReadbackTestFixture.settings(), journal) }
                assertThrows<IllegalArgumentException> { owner.requireRetained(pools, reader, TestOwnerDeleteJournalConfigurationV1.of(journal.declaration())) }
                for (total in listOf(0L, 30_001L)) {
                    assertThrows<IllegalArgumentException> { FullTestCatalogInputs.activation(pools, journal, reader, totalAttemptMillis = total) }
                }
                assertEquals(1L, FullTestCatalogInputs.activation(pools, journal, reader, totalAttemptMillis = 1).totalAttemptMillis)
                val smallEnvelopeReader = VersionBoundCatalogReadbackTestFixture.settings(
                    policy = VersionBoundCatalogReadbackTestFixture.chainPolicy(
                        limits = OfflineCatalogRotationFixture.limits().copy(maximumEnvelopeBytes = 131072),
                    ),
                )
                val small = FullTestCatalogInputs.activation(pools, journal, smallEnvelopeReader, totalAttemptMillis = 29_999)
                assertEquals(131072, small.maximumIntentBytes)
                assertEquals(29_999L, small.totalAttemptMillis)
                assertFalse(owner.inventory() == small.inventory())
                owner.requireRetained(pools, reader, journal)
            }
        }
}

/** Existing public frozen G1 inputs; expected output comes from independent raw JSON, never the production encoder. */
internal object FullTestCatalogInputs {
    const val KEY_ARN = "arn:aws:kms:us-east-1:123456789012:key/99999999-9999-4999-8999-999999999999"

    fun registry(): OfflineBootstrapRegistryV1 = VersionBoundCatalogReadbackTestFixture.envelope().manifest.initialWriterRegistry
    fun registryBytes(): ByteArray = OfflineTrustBundleFixture.registryBytes(registry())
    fun publicKeyBytes(): ByteArray = Base64.getDecoder().decode(
        OfflineTrustBundleParser.parse(VersionBoundCatalogReadbackTestFixture.currentBundleBytes()).body.signers.single().publicKeySpkiBase64,
    )

    fun key(
        id: String = "catalog-old",
        spki: ByteArray = publicKeyBytes(),
        arn: String = KEY_ARN,
        algorithm: String = OfflineTrustBundleFixture.ALGORITHM,
        fingerprint: String = Sha256.hex(spki),
    ): CatalogSigningKeyV1 = CatalogSigningKeyV1(id, arn, algorithm, spki, fingerprint)

    fun activation(
        pools: VersionBoundPersistencePools,
        journal: TestOwnerDeleteJournalConfigurationV1,
        reader: VersionBoundCatalogReadbackConfigurationV1 = VersionBoundCatalogReadbackTestFixture.settings(),
        key: CatalogSigningKeyV1 = key(),
        registryBytes: ByteArray = registryBytes(),
        totalAttemptMillis: Long = 30_000,
    ): VersionBoundTestActivationConfigurationV1 = VersionBoundTestActivationConfigurationV1.fromRetained(
        pools, reader, journal, key, registryBytes, totalAttemptMillis,
    )

    fun goldenBytes(projected: Boolean): ByteArray {
        val name = if (projected) "projected-reader" else "g1-reader"
        return checkNotNull(javaClass.getResourceAsStream("/fixtures/complaint-effective-test-configuration-v1/$name.json")).use { it.readBytes() }
    }

    fun goldenDocument(projected: Boolean): JsonObject = Json.parseToJsonElement(goldenBytes(projected).decodeToString()).jsonObject

    /** Only existing in-memory signing fixtures; used to bind changed registry inputs before semantic-negative checks. */
    fun signedReader(registry: OfflineBootstrapRegistryV1): VersionBoundCatalogReadbackConfigurationV1 {
        val body = OfflineCatalogGenesisFixture.bundleBody(registry)
        val initial = OfflineTrustBundleFixture.signed(
            body.copy(
                bootstrapAuthority = body.bootstrapAuthority.copy(
                    catalogWriterGenerationId = registry.catalogWriter.generationId,
                    requiredSigner = registry.catalogWriter.requiredSignerPolicy.members.single(),
                    catalogApproverIds = registry.catalogWriter.catalogApproverIds,
                ),
            ),
        )
        val current = OfflineTrustBundleFixture.signed(initial.body.copy(version = 9, issuedAtEpochSecond = initial.body.issuedAtEpochSecond + 3600))
        val genesis = OfflineCatalogGenesisFixture.signed(OfflineCatalogGenesisFixture.manifest(initial, registry))
        return VersionBoundCatalogReadbackConfigurationV1.fromIndependentInputs(
            OfflineTrustBundleFixture.bytes(initial), OfflineTrustBundleFixture.bytes(current),
            OfflineCatalogChainReaderPolicy(
                OfflineTrustBundleFixture.policy(minimumVersion = 9), listOf(registry.catalogWriter.generationId),
                listOf("catalog-approver-a", "catalog-approver-b"), OfflineCatalogRotationFixture.limits(),
            ),
            Sha256.hex(OfflineCatalogGenesisFixture.bytes(genesis)), S3CatalogReadbackLimits(), 600_000,
        )
    }
}
