package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenProjection
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintProcessPoolFixture
import me.manga.kira.backend.complaint.infrastructure.admission.processConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizationObservation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.GenesisResume
import me.manga.kira.backend.complaint.infrastructure.catalog.ProcessBoundCatalogGenesisProjection
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCatalogGenesisPersistencePhaseExecutor
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.jdbc.core.JdbcTemplate
import java.lang.reflect.Modifier
import java.util.UUID

/** Genuine local signatures + explicitly synthetic provider observations. Not AWS/IAM/ceremony or release qualification. */
class CatalogGenesisFinalizationVerifierTest {
    private val declarations by lazy { CatalogGenesisInitialLiveTestFixture() }
    private val fixture get() = declarations.catalog
    private val binding by lazy { declarations.binding() }

    @Test
    fun `opaque handoff retains both exact copy tuples and defensive bytes after every body closes`() {
        val provider = provider()
        val initial = fixture.initial.copyOf()
        val current = fixture.current.copyOf()
        provider.onList = {
            initial.fill(0)
            current.fill(0)
        }
        val verified = CatalogDualLocationVerifier.GenesisReadback.verify(provider, initial, current, fixture.policy(), fixture.preparedGenesis())
        assertEquals(GenesisResume.PREPARED, verified.resume)
        assertEquals(fixture.head(1).envelopeSha256, verified.envelopeSha256)
        assertEquals(Sha256.hex(fixture.initial), verified.initialTrustBundleSha256)
        assertEquals(Sha256.hex(fixture.current), verified.currentTrustBundleSha256)
        assertEquals(2, provider.closedBodies)
        assertEquals(0, provider.openBodies)
        val primary = verified.primaryEvidenceBytes()
        val replica = verified.replicaEvidenceBytes()
        assertFalse(primary.contentEquals(replica))
        for ((bytes, role) in listOf(primary to "PRIMARY", replica to "REPLICA")) {
            assertTrue(bytes.size in 1..65536)
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            assertEquals(role, json.getValue("location").jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals("catalog-version-1", json.getValue("objectVersion").jsonPrimitive.content)
            assertEquals(verified.envelopeSha256, json.getValue("envelopeSha256").jsonPrimitive.content)
            assertEquals("COMPLIANCE", json.getValue("objectLockMode").jsonPrimitive.content)
            assertEquals(if (role == "PRIMARY") "COMPLETED" else "REPLICA", json.getValue("replicationStatus").jsonPrimitive.content)
        }
        primary.fill(0)
        replica.fill(0)
        verified.mutation().signedEnvelopeBytes!!.fill(0)
        assertArrayEquals(fixture.bytes.first(), verified.mutation().signedEnvelopeBytes)
        val input = CatalogGenesisMutationInput.complete(verified, binding)
        input.frozenArguments().filterIsInstance<ByteArray>().forEach { it.fill(0) }
        input.beforeSignatureArguments().filterIsInstance<ByteArray>().forEach { it.fill(0) }
        assertArrayEquals(fixture.bytes.first(), input.beforeSignatureArguments()[1] as ByteArray)
        val finalization = checkNotNull(input.finalization)
        val control = finalization.controlArguments()
        assertEquals(1, control[7])
        assertEquals(1L, control[8])
        assertArrayEquals(declarations.syntheticDesiredHash(), control[9] as ByteArray)
        (control[9] as ByteArray).fill(0)
        assertArrayEquals(declarations.syntheticDesiredHash(), finalization.controlArguments()[9] as ByteArray)
        val later = verify(provider(), policy = policy(evaluatedAt = CatalogReadbackFixture.EVALUATED_AT + 10))
        assertArrayEquals(verified.primaryEvidenceBytes(), later.primaryEvidenceBytes())
        assertArrayEquals(verified.replicaEvidenceBytes(), later.replicaEvidenceBytes())
    }

    @Test
    fun `diagnostic result wrappers and public checked claims have no constructor or promotion path into writes`() {
        val type = CatalogDualLocationVerifier.GenesisReadback::class.java
        assertTrue(type.declaredConstructors.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
        assertFalse(type.declaredMethods.any { it.name == "copy" })
        val diagnostic = fixture.verify(provider(), fixture.preparedGenesis()) as CatalogReadbackResult.PreparedCompletionEvidence
        val forged = diagnostic.copy(operationToken = "66666666-6666-4666-8666-666666666666")
        assertFalse(type.isInstance(forged))
        val factories = CatalogGenesisMutationInput.Companion::class.java.declaredMethods.filter { it.name in setOf("complete", "project") }
        assertEquals(2, factories.size)
        assertTrue(factories.all { it.parameterTypes.contentEquals(arrayOf(type, CatalogGenesisInitialLiveBinding::class.java)) })
        assertFalse(
            CatalogDualLocationVerifier.GenesisReadback.Companion::class.java.declaredMethods.any { method ->
                method.parameterTypes.any { CatalogReadbackResult::class.java.isAssignableFrom(it) }
            },
        )
        val projection = ProcessBoundCatalogGenesisProjection::class.java
        val constructors = projection.declaredConstructors.filterNot { it.isSynthetic }
        assertEquals(1, constructors.size)
        assertTrue(Modifier.isPrivate(constructors.single().modifiers))
        assertTrue(constructors.single().parameterTypes.contentEquals(arrayOf(CatalogGenesisMutationOperation::class.java)))
        assertFalse(projection.declaredMethods.any { it.name == "copy" || it.name.startsWith("set") })
        assertTrue(projection.declaredFields.filterNot { it.isSynthetic }.all { Modifier.isFinal(it.modifiers) })
        assertFalse(
            ProcessBoundCatalogGenesisProjection.Companion::class.java.declaredMethods.any { method ->
                method.parameterTypes.any { it === CatalogGenesisFinalizationObservation::class.java || CatalogReadbackResult::class.java.isAssignableFrom(it) }
            },
        )
    }

    @Test
    fun `never accepted is not adoptable and accepted replay cannot masquerade as pending projection recovery`() {
        val absent = provider()
        assertThrows(CatalogReadbackException::class.java) { verify(absent, LocalCatalogSnapshot.NeverAccepted) }
        assertTrue(absent.listRequests.isEmpty())
        val pending = verify(provider(), fixture.projection(1))
        val replay = verify(provider(), LocalCatalogSnapshot.Accepted(fixture.head(1)))
        assertEquals(GenesisResume.PROJECTION_PENDING, pending.resume)
        assertEquals(GenesisResume.PROJECTED_REPLAY, replay.resume)
        assertFalse(CatalogGenesisMutationInput.project(pending, binding).finalization!!.replayOnly)
        assertTrue(CatalogGenesisMutationInput.project(replay, binding).finalization!!.replayOnly)
        listOf(pending, replay).forEach { readback ->
            assertThrows(CatalogReadbackException::class.java) { CatalogGenesisMutationInput.complete(readback, binding) }
        }
        val wrongToken = LocalCatalogSnapshot.ProjectionPending(
            fixture.head(1),
            CatalogFrozenProjection("66666666-6666-4666-8666-666666666666", fixture.bytes.first(), fixture.head(1).envelopeSha256),
        )
        assertThrows(CatalogReadbackException::class.java) { verify(provider(), wrongToken) }
    }

    @Test
    fun `missing replica extra versions later tails and another genuine PSS can never mint finalization input`() {
        val alternate = OfflineCatalogGenesisFixture.bytes(OfflineCatalogGenesisFixture.signed(fixture.chain.base.genesis.manifest))
        assertNotEquals(fixture.head(1).envelopeSha256, Sha256.hex(alternate))
        val extra = provider().also { port ->
            port.replicaVersions.add(port.replicaVersions.single().copy(versionId = "another-version"))
        }
        val wrongRetention = provider().also { port ->
            port.transformMetadata =
                { if (it.requestBinding.location.role == "REPLICA") it.copy(retainUntilEpochSecond = it.retainUntilEpochSecond!! + 1) else it }
        }
        listOf(
            SyntheticCatalogReadbackPort(emptyList()),
            SyntheticCatalogReadbackPort(fixture.bytes.take(1), emptyList()),
            SyntheticCatalogReadbackPort(fixture.bytes.take(2)),
            SyntheticCatalogReadbackPort(listOf(alternate)),
            extra,
            wrongRetention,
        ).forEach { port ->
            val failure = assertThrows(CatalogReadbackException::class.java) { verify(port) }
            assertEquals(0, port.openBodies)
            assertEquals(null, failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }
        assertThrows(CatalogReadbackException::class.java) { verify(provider(), fixture.preparedGenesis(signed = false)) }
        assertThrows(CatalogReadbackException::class.java) { verify(provider(), policy = policy(pin = "0".repeat(64))) }
        val forgedCurrent = OfflineTrustBundleFixture.bytes(
            fixture.chain.base.current.copy(body = fixture.chain.base.current.body.copy(version = fixture.chain.base.current.body.version + 1)),
        )
        val cold = provider()
        assertThrows(OfflineTrustBundleException::class.java) {
            CatalogDualLocationVerifier.GenesisReadback.verify(cold, fixture.initial, forgedCurrent, fixture.policy(), fixture.preparedGenesis())
        }
        assertTrue(cold.listRequests.isEmpty())
    }

    @Test
    fun `initial LIVE binding validates declaration tuple and copies opaque D while deriving P`() {
        val supplied = declarations.syntheticDesiredHash()
        val desired = declarations.desired(hash = supplied)
        val expected = declarations.binding(desired)
        supplied.fill(0)
        desired.configurationHashBytes().fill(1)
        expected.desiredConfigurationHashBytes().fill(2)
        expected.capacityPolicyDigestBytes().fill(3)
        assertArrayEquals(declarations.syntheticDesiredHash(), expected.desiredConfigurationHashBytes())
        assertArrayEquals(declarations.capacity.digestBytes(), expected.capacityPolicyDigestBytes())
        assertFalse(expected.desiredConfigurationHashBytes().contentEquals(declarations.journal.digestBytes()))
        assertFalse(expected.desiredConfigurationHashBytes().contentEquals(expected.capacityPolicyDigestBytes()))
        assertFalse(declarations.journal.digestBytes().contentEquals(expected.capacityPolicyDigestBytes()))
        assertEquals(1, expected.implementationSchema)
        assertEquals(1L, expected.desiredGeneration)
        assertEquals(Long.MAX_VALUE, declarations.binding(declarations.desired(generation = Long.MAX_VALUE)).desiredGeneration)
        expected.requireMatchingRegistry(declarations.registry)
        val type = CatalogGenesisInitialLiveBinding::class.java
        assertTrue(type.declaredConstructors.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
        assertFalse(type.declaredMethods.any { it.name == "copy" })
        assertEquals("CatalogGenesisInitialLiveBinding(opaque-D,declared-J-P,no-authority)", expected.toString())

        val other = UUID.fromString("99999999-9999-4999-8999-999999999999")
        val test = ComplaintInstallationDesiredSettings.Configured(
            ComplaintInstallationMode.PRE_CUTOVER_TEST,
            1,
            1L,
            ComplaintDataScope.of(other),
            desired.databaseIdentity,
            desired.restoreIdentity,
            declarations.syntheticDesiredHash(),
        )
        listOf(test, declarations.desired(database = other), declarations.desired(restore = other)).forEach { invalid ->
            assertThrows(CatalogReadbackException::class.java) { declarations.binding(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { declarations.desired(generation = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            ComplaintInstallationDesiredSettings.Configured(
                ComplaintInstallationMode.LIVE,
                2,
                1L,
                ComplaintDataScope.LIVE,
                desired.databaseIdentity,
                desired.restoreIdentity,
                declarations.syntheticDesiredHash(),
            )
        }
    }

    @Test
    fun `initial LIVE binding compares every writer and range field before genuine readback finalization`() {
        val registry = declarations.registry
        val writer = registry.eventWriter
        val range = writer.liveRange
        val other = "99999999-9999-4999-8999-999999999999"
        val mismatchedWriters = listOf(
            writer.copy(generationId = other),
            writer.copy(databaseIdentity = other),
            writer.copy(restoreIdentity = other),
            writer.copy(registration = "SEALED"),
        )
        val mismatchedRanges = listOf(
            range.copy(scope = range.scope.copy(kind = "TEST")),
            range.copy(scope = range.scope.copy(id = other)),
            range.copy(state = "SEALED"),
            range.copy(journalLocation = range.journalLocation.copy(bucket = "other-journal")),
            range.copy(journalLocation = range.journalLocation.copy(accountId = "999999999999")),
            range.copy(journalLocation = range.journalLocation.copy(region = "us-west-2")),
            range.copy(ordinaryPrefix = range.ordinaryPrefix + "other/"),
            range.copy(sealTerminalPrefix = range.sealTerminalPrefix + "other/"),
            range.copy(ordinaryAuthority = range.ordinaryAuthority.copy(roleId = "other-role")),
            range.copy(ordinaryAuthority = range.ordinaryAuthority.copy(policy = range.ordinaryAuthority.policy.copy(version = 2))),
            range.copy(sealTerminalAuthority = range.sealTerminalAuthority.copy(credentialId = "other-credential")),
            range.copy(routingKeyId = "other-routing-key"),
            range.copy(encryptionKeyId = "other-encryption-key"),
            range.copy(configurationSha256 = "0".repeat(64)),
            range.copy(firstEpoch = 2),
            range.copy(sealHistory = range.sealHistory.copy(count = 1)),
            range.copy(sealHistory = range.sealHistory.copy(sha256 = "0".repeat(64))),
        )
        val mismatches = listOf(
            registry.copy(schemaVersion = 2),
            registry.copy(canonicalizerId = "other"),
            registry.copy(databaseIdentity = other),
            registry.copy(restoreIdentity = other),
        ) + (mismatchedWriters + mismatchedRanges.map { writer.copy(liveRange = it) }).map { registry.copy(eventWriter = it) }
        mismatches.forEach { rawClaims ->
            // Even a successful necessary comparison is not a raw-verifier handoff or catalog authority.
            assertThrows(CatalogReadbackException::class.java) { binding.requireMatchingRegistry(rawClaims) }
        }
        val declared = declarations.journal.declaration()
        val changed = ComplaintJournalConfigurationV1.of(
            declared.copy(limits = declared.limits.copy(deadlines = declared.limits.deadlines.copy(s3CallMillis = 1499))),
        )
        val anotherJournal = declarations.binding(journal = changed)
        val verified = verify(provider())
        assertThrows(CatalogReadbackException::class.java) { CatalogGenesisMutationInput.complete(verified, anotherJournal) }
        assertThrows(CatalogReadbackException::class.java) { CatalogGenesisMutationInput.project(verified, anotherJournal) }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `retained initial LIVE binding derives defensive D J P from the actual graph and rejects equal descriptor replacement resources`() =
        ComplaintProcessPoolFixture().use { first ->
            ComplaintProcessPoolFixture().use { second ->
                val acquired = BoundComplaintConsumerFixture()
                val consumers = acquired.configuration()
                val pools = first.bind()
                val otherPools = second.bind()
                val process = processConfiguration(consumers, pools)
                val other = processConfiguration(consumers, otherPools)
                val expected = CatalogGenesisInitialLiveBinding.fromRetained(process)
                assertSame(process, expected.process)
                assertArrayEquals(process.configurationHashBytes(), other.configurationHashBytes())
                val desired = process.configurationHashBytes()
                expected.desiredConfigurationHashBytes().fill(0)
                expected.capacityPolicyDigestBytes().fill(0)
                process.desiredSettings().configurationHashBytes().fill(0)
                assertArrayEquals(desired, expected.desiredConfigurationHashBytes())
                assertArrayEquals(consumers.capacityPolicy.digestBytes(), expected.capacityPolicyDigestBytes())
                assertEquals(1, expected.implementationSchema)
                assertEquals(7L, expected.desiredGeneration)
                expected.requireMatchingRegistry(VersionBoundCatalogReadbackTestFixture.envelope().manifest.initialWriterRegistry)
                val coordinator = pools.catalogCoordinator
                val jdbc = JdbcTemplate(coordinator.dataSource)
                expected.requirePersistence(coordinator.ownership, jdbc)
                for ((owner, source) in listOf(
                    otherPools.catalogCoordinator.ownership to coordinator.dataSource,
                    coordinator.ownership to otherPools.catalogCoordinator.dataSource,
                    coordinator.ownership to pools.ordinary,
                    coordinator.ownership to pools.deletion,
                )) {
                    val failure = assertThrows(PersistencePhaseException::class.java) { expected.requirePersistence(owner, JdbcTemplate(source)) }
                    assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, failure.code)
                }
                val port = SyntheticCatalogReadbackPort(emptyList())
                val wrongExecutor = ComplaintCatalogGenesisPersistencePhaseExecutor(
                    otherPools.catalogCoordinator.ownership, JdbcTemplate(otherPools.catalogCoordinator.dataSource),
                )
                val refused = assertThrows(PersistencePhaseException::class.java) {
                    wrongExecutor.resumeGenesis(
                        port,
                        VersionBoundCatalogReadbackTestFixture.initialBundleBytes(),
                        VersionBoundCatalogReadbackTestFixture.currentBundleBytes(),
                        VersionBoundCatalogReadbackTestFixture.settings().policyAt(VersionBoundCatalogReadbackTestFixture.evaluatedAt),
                        expected,
                    )
                }
                assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, refused.code)
                assertTrue(port.listRequests.isEmpty() && port.getRequests.isEmpty())
                listOf(pools, otherPools).flatMap { listOf(it.ordinary, it.deletion, it.catalogCoordinator.dataSource) }
                    .forEach { assertFalse(actualPool(it).isRunning) }
                assertEquals(9, acquired.lookups)
                assertEquals("CatalogGenesisInitialLiveBinding(retained-process,G1-only,no-authority)", expected.toString())
            }
        }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `retained genesis binding rechecks actual noncatalog pool drift instead of trusting cached D`() =
        ComplaintProcessPoolFixture(retained = true).use { database ->
            val pools = database.bind()
            val process = processConfiguration(BoundComplaintConsumerFixture().configuration(), pools)
            val expected = CatalogGenesisInitialLiveBinding.fromRetained(process)
            val hash = expected.desiredConfigurationHashBytes()
            val coordinator = pools.catalogCoordinator
            actualPool(pools.deletion).maximumPoolSize += 1
            assertThrows(PersistenceBoundaryException::class.java) { expected.requireUnchangedConfiguration() }
            assertThrows(PersistenceBoundaryException::class.java) { expected.requirePersistence(coordinator.ownership, JdbcTemplate(coordinator.dataSource)) }
            assertThrows(PersistenceBoundaryException::class.java) { CatalogGenesisInitialLiveBinding.fromRetained(process) }
            assertArrayEquals(hash, expected.desiredConfigurationHashBytes(), "Historical bytes are not current use permission.")
            listOf(pools.ordinary, pools.deletion, coordinator.dataSource).forEach { assertFalse(actualPool(it).isRunning) }
        }

    private fun provider(): SyntheticCatalogReadbackPort = SyntheticCatalogReadbackPort(fixture.bytes.take(1))

    private fun verify(
        provider: SyntheticCatalogReadbackPort,
        local: LocalCatalogSnapshot = fixture.preparedGenesis(),
        policy: CatalogReadbackPolicy = fixture.policy(),
    ): CatalogDualLocationVerifier.GenesisReadback =
        CatalogDualLocationVerifier.GenesisReadback.verify(provider, fixture.initial, fixture.current, policy, local)

    private fun policy(evaluatedAt: Long = CatalogReadbackFixture.EVALUATED_AT, pin: String = fixture.head(1).envelopeSha256): CatalogReadbackPolicy =
        CatalogReadbackPolicy(fixture.policy().chain, pin, evaluatedAt, CatalogReadbackFixture.RETAIN_UNTIL, 1, 8)
}
