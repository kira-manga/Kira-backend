package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.HeldEpochSealClock
import me.manga.kira.backend.complaint.catalog.HeldEpochSealHttpFixture
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStatePolicy
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentProjectedCatalogRefreshV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalPolicyDeploymentV1
import me.manga.kira.backend.complaint.infrastructure.journal.VersionBoundLiveJournalCoverageV1
import me.manga.kira.backend.complaint.journal.liveJournalPolicy
import me.manga.kira.backend.complaint.journal.replaceLiveJournalPolicy
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.security.SecurityConfig
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import me.manga.kira.backend.security.boundConsumerTestSettings
import me.manga.kira.backend.security.historyTestRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.mock.env.MockEnvironment
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Actual cold owners only. Matching D is not live pool, current catalog, scope or activation proof. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundComplaintProcessConfigurationTest {
    @Test
    fun `independent golden bytes come from the actual acquired graph and every concrete pool without starting them`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundComplaintConsumerFixture()
            val consumers = fixture.configuration()
            val pools = database.bind()
            val configuration = processConfiguration(consumers, pools)
            val expected = checkNotNull(
                javaClass.getResourceAsStream("/fixtures/complaint-effective-configuration-v1/initial-live-memory.json"),
            ).use { it.readBytes() }
            assertSame(consumers, configuration.consumers)
            assertSame(pools, configuration.pools)
            assertArrayEquals(expected, configuration.canonicalBytes())
            assertArrayEquals(HexFormat.of().parseHex(GOLDEN_SHA256), configuration.configurationHashBytes())
            assertEquals(GOLDEN_SHA256, Sha256.hex(expected))
            assertFalse(expected.last() == '\n'.code.toByte())
            val desired = configuration.desiredSettings()
            assertEquals(ComplaintInstallationMode.LIVE, desired.mode)
            assertEquals(ComplaintDataScope.LIVE, desired.scope)
            assertEquals(7L, desired.desiredGeneration)
            assertArrayEquals(configuration.configurationHashBytes(), desired.configurationHashBytes())
            assertEquals(
                ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE,
                ComplaintInstallationCurrentStatePolicy.assess(desired, ComplaintDataScope.LIVE, null),
            )
            val document = document(configuration)
            assertEquals(
                consumers.capacityPolicy.sha256,
                document.getValue("capacityPolicy").jsonObject.getValue("sha256").jsonPrimitive.content,
            )
            assertEquals(
                consumers.journalConfiguration.sha256,
                document.getValue("journalConfiguration").jsonObject.getValue("sha256").jsonPrimitive.content,
            )
            assertEquals(
                listOf("ORDINARY", "DELETION", "CATALOG_COORDINATOR"),
                poolDocuments(document).map { it.getValue("role").jsonPrimitive.content },
            )
            listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).forEach { assertFalse(actualPool(it).isRunning) }
            configuration.requireUnchangedConfiguration()
            assertEquals(9, fixture.lookups)
        }

    @Test
    fun `input ordering returned copies private filenames and live counter consumption cannot change the retained D`() =
        ComplaintProcessPoolFixture().use { first ->
            ComplaintProcessPoolFixture().use { second ->
                val fixture = BoundComplaintConsumerFixture()
                val proxies = mutableListOf("198.51.100.0/24", "192.0.2.0/24")
                val left = processConfiguration(fixture.configuration(settings = boundConsumerTestSettings(trustedProxies = proxies)), first.bind())
                fixture.installationSecrets.reverse()
                fixture.cursorSecrets.reverse()
                val reorderedJwt = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-z", fixture.installationSecrets, fixture.user)
                val right = processConfiguration(
                    fixture.configuration(settings = boundConsumerTestSettings(trustedProxies = proxies.reversed()), jwt = reorderedJwt),
                    second.bind(),
                )
                val bytes = left.canonicalBytes()
                val hash = left.configurationHashBytes()
                assertArrayEquals(bytes, right.canonicalBytes())
                assertNotEquals(
                    first.root.endpoint.driverProperties().getProperty("sslrootcert"),
                    second.root.endpoint.driverProperties().getProperty("sslrootcert"),
                )
                proxies.clear()
                fixture.eraseOriginals()
                left.canonicalBytes().fill(0)
                left.configurationHashBytes().fill(0)
                left.desiredSettings().configurationHashBytes().fill(0)
                (left.consumers.descriptors() as MutableList<*>).clear()
                (left.consumers.trustedProxies() as MutableList<*>).clear()
                left.pools.descriptors().forEach { descriptor ->
                    descriptor.openings().forEach { (it.publicDriverProperties() as MutableMap<*, *>).clear() }
                }
                left.consumers.ingressAdmission.withIngress(historyTestRequest()) {}
                left.requireUnchangedConfiguration()
                assertArrayEquals(bytes, left.canonicalBytes())
                assertArrayEquals(hash, left.configurationHashBytes())
                val encoded = bytes.toString(Charsets.UTF_8)
                assertFalse(encoded.contains(VersionBoundPersistenceTestInputs.PASSWORD))
                assertFalse(encoded.contains(first.root.endpoint.driverProperties().getProperty("sslrootcert")))
                assertFalse(encoded.contains("kira-private-"))
                val userBytes = fixture.userSecret.useMaterial { it.copyOf() }
                assertFalse(encoded.contains(Base64.getEncoder().encodeToString(userBytes)))
                assertFalse(encoded.contains(Sha256.hex(userBytes)))
                userBytes.fill(0)
                assertEquals(9, fixture.lookups)
                assertEquals("VersionBoundComplaintProcessConfiguration(INITIAL_LIVE,memory,redacted,no-authority)", left.toString())
            }
        }

    @Test
    fun `effective ingress semantics hourly quotas shared members and trust settings each bind the actual consumer`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundComplaintConsumerFixture()
            val pools = database.bind()
            val initial = ComplaintProcessAdmissionInputs(proxies = listOf("192.0.2.0/24"))
            val original = processConfiguration(fixture.configuration(settings = initial.settings()), pools).configurationHashBytes()
            val variants = listOf(
                initial.copy(concurrent = 3), initial.copy(ingressBuckets = 65), initial.copy(ingressRate = 119),
                initial.copy(semanticBuckets = 129), initial.copy(semanticEvents = 4097), initial.copy(prune = 9),
                initial.copy(enrollmentGlobal = 3), initial.copy(createGlobal = 3), initial.copy(members = 65),
                initial.copy(memberPrune = 9), initial.copy(forwarded = true), initial.copy(proxies = listOf("198.51.100.0/24")),
            )
            variants.forEach { input ->
                val actual = fixture.configuration(settings = input.settings())
                assertSame(fixture.capacity, actual.capacityPolicy)
                assertEquals(input.enrollmentGlobal, actual.enrollmentPolicy.globalPerHour)
                assertEquals(input.createGlobal, actual.ownerCreatePolicy.globalPerHour)
                assertEquals(input.members, actual.ownerDeleteAllPolicy.memberLimit)
                assertFalse(original.contentEquals(processConfiguration(actual, pools).configurationHashBytes()))
            }
        }

    @Test
    fun `actual user scalar accessors preserve exact duration nanos and the real signer decoder refuse replacement properties`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundComplaintConsumerFixture()
            val pools = database.bind()
            val properties = KiraSecurityProperties(
                issuer = "process-issuer-π",
                audience = "process-audience",
                accessTokenTtl = Duration.ofSeconds(900, 17),
                clockSkew = Duration.ofSeconds(3, 41),
            )
            val user = JwtKeyProvider.fromAcquired(fixture.userSecret, properties)
            val jwt = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-z", fixture.installationSecrets, user)
            val configuration = processConfiguration(fixture.configuration(jwt = jwt), pools)
            val encodedUser = document(configuration).getValue("consumers").jsonObject.getValue("userJwt").jsonObject
            assertEquals(properties.issuer, user.versionBoundIssuer)
            assertEquals(properties.audience, user.versionBoundAudience)
            assertEquals(properties.accessTokenTtl, user.versionBoundAccessTokenTtl)
            assertEquals(properties.clockSkew, user.versionBoundClockSkew)
            assertEquals(
                "17",
                encodedUser.getValue("accessTokenTtl").jsonObject.getValue("nanoAdjustment").jsonPrimitive.content,
            )
            assertEquals(
                "41",
                encodedUser.getValue("clockSkew").jsonObject.getValue("nanoAdjustment").jsonPrimitive.content,
            )
            val security = SecurityConfig(MockEnvironment())
            JwtService(user, properties, Clock.systemUTC())
            security.jwtDecoder(user, properties)
            val changes = listOf(
                properties.copy(issuer = "other-issuer"),
                properties.copy(audience = "other-audience"),
                properties.copy(accessTokenTtl = properties.accessTokenTtl.plusNanos(1)),
                properties.copy(clockSkew = properties.clockSkew.plusNanos(1)),
            )
            changes.forEach { changed ->
                assertThrows<IllegalArgumentException> { JwtService(user, changed, Clock.systemUTC()) }
                assertThrows<IllegalArgumentException> { security.jwtDecoder(user, changed) }
                val actualUser = JwtKeyProvider.fromAcquired(fixture.userSecret, changed)
                val actualJwt = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-z", fixture.installationSecrets, actualUser)
                val actual = processConfiguration(fixture.configuration(jwt = actualJwt), pools)
                assertFalse(configuration.configurationHashBytes().contentEquals(actual.configurationHashBytes()))
            }
            val legacy = JwtKeyProvider(
                properties.copy(jwtSecret = fixture.userSecret.useMaterial { Base64.getEncoder().encodeToString(it) }),
            )
            assertThrows<IllegalArgumentException> { legacy.versionBoundIssuer }
            assertThrows<IllegalArgumentException> { legacy.versionBoundAudience }
            assertThrows<IllegalArgumentException> { legacy.versionBoundAccessTokenTtl }
            assertThrows<IllegalArgumentException> { legacy.versionBoundClockSkew }
            Unit
        }

    @Test
    fun `P J actual retained versions and active selections participate without substituting independent component hashes`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundComplaintConsumerFixture()
            val pools = database.bind()
            val initial = processConfiguration(fixture.configuration(), pools).configurationHashBytes()
            val policy = fixture.capacity
            val changedP = ComplaintCapacityPolicyV1.of(
                policy.hardLimit.with(ComplaintCapacityCounter.AUDIT_ROWS, policy.hardLimit[ComplaintCapacityCounter.AUDIT_ROWS] + 1),
                policy.creationLimit,
                policy.dailyEnrollmentLimit,
            )
            val journal = fixture.journal.declaration()
            val changedJ = ComplaintJournalConfigurationV1.of(journal.copy(routing = journal.routing.copy(activeKeyId = "route-a")))
            val routing = VersionBoundComplaintJournalRouting.fromAcquired(changedJ, fixture.journalSecrets)
            val selections = listOf(
                fixture.configuration(capacity = changedP),
                fixture.configuration(keys = fixture.inputs(routing = routing), journal = changedJ),
                fixture.configuration(keys = fixture.inputs(current = fixture.previous, previous = fixture.current)),
                fixture.configuration(keys = fixture.inputs(activeCursor = "cursor-a")),
                fixture.configuration(jwt = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-a", fixture.installationSecrets, fixture.user)),
            )
            val replacements = replacedProcessConsumerVersions(fixture)
            (selections + replacements).forEach { actual ->
                assertFalse(initial.contentEquals(processConfiguration(actual, pools).configurationHashBytes()))
            }
        }

    @Test
    fun `D6 full independent goldens preserve previous inventories and explicit G1 versus projected reader without observing clocks`() =
        withLiveProcessOwners { consumers, pools, wire ->
            val lanes = wire.lanes
            val sealer = wire.acquisition
            val clock = object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = this
                override fun instant(): Instant = error("Cold D6 composition must not sample UTC.")
            }
            val writer = consumers.journalConfiguration.declaration().writer
            val g1 = VersionBoundCatalogReadbackTestFixture.settings()
            for (reader in listOf(g1, projectedSettings(g1))) {
                fun previous() = VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                    consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity), reader, lanes, sealer,
                )
                val before = previous()
                val beforeBytes = before.canonicalBytes()
                val coverage = VersionBoundLiveJournalCoverageV1.withClockFixture(
                    consumers.journalRouting,
                    reader,
                    lanes,
                    liveJournalPolicy(consumers.journalRouting, reader),
                    clock,
                    { error("Cold D6 composition must not sample monotonic time.") },
                )
                val actual = liveProcessConfiguration(consumers, pools, reader, lanes, sealer, coverage)
                val expected = ComplaintLivePolicyGolden.bytes(reader.projectedCurrent)
                val hash = if (reader.projectedCurrent) ComplaintLivePolicyGolden.PROJECTED_SHA256 else ComplaintLivePolicyGolden.G1_SHA256
                assertArrayEquals(expected, actual.canonicalBytes())
                assertEquals(hash, Sha256.hex(expected))
                assertArrayEquals(HexFormat.of().parseHex(hash), actual.configurationHashBytes())
                assertSame(coverage, actual.liveCoverage)
                assertSame(reader, actual.catalogReadback)
                assertSame(sealer, actual.epochSealAcquisition)
                assertEquals(
                    document(before).filterKeys { it !in setOf("schemaVersion", "profile") },
                    document(actual).filterKeys { it !in setOf("schemaVersion", "profile", "liveCoverage") },
                )
                assertArrayEquals(beforeBytes, previous().canonicalBytes()) // D4/D5 do not inherit a policy merely because it was constructed.
                assertEquals(if (reader.projectedCurrent) "5" else "4", document(before).getValue("schemaVersion").jsonPrimitive.content)
                actual.canonicalBytes().fill(0)
                actual.configurationHashBytes().fill(0)
                actual.requireUnchangedConfiguration()
                assertArrayEquals(expected, actual.canonicalBytes())
                if (!reader.projectedCurrent) {
                    assertThrows<CatalogReadbackException> {
                        CurrentProjectedCatalogRefreshV1.ordinary(
                            actual,
                            AwsSessionCredentials.create("synthetic", "synthetic", "synthetic"),
                            AwsSessionCredentials.create("synthetic", "synthetic", "synthetic"),
                        )
                    }
                }
            }
            assertTrue(wire.requests.isEmpty())
            assertEquals(0, wire.stsFactories)
            assertEquals(0, wire.kmsFactories)
            assertEquals(0L, lanes.activeOwners().totalOwners)
            listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).forEach { assertFalse(actualPool(it).isRunning) }
        }

    @Test
    fun `D6 policy trust and age drift alter independent D but collection order clock and refreshed transport secrets cannot`() =
        withLiveProcessOwners { consumers, pools, wire ->
            val lanes = wire.lanes
            val sealer = wire.acquisition
            val reader = projectedSettings(VersionBoundCatalogReadbackTestFixture.settings())
            val policy = liveJournalPolicy(consumers.journalRouting, reader)
            fun compose(selected: LiveJournalPolicyDeploymentV1) = liveProcessConfiguration(
                consumers,
                pools,
                reader,
                lanes,
                sealer,
                VersionBoundLiveJournalCoverageV1.fromIndependentInputs(consumers.journalRouting, reader, lanes, selected),
            )
            val original = compose(policy)
            val bytes = original.canonicalBytes()
            assertEquals(ComplaintLivePolicyGolden.PROJECTED_SHA256, Sha256.hex(bytes))
            val first = policy.copyPolicies.first()
            val copies = listOf(
                first.copy(accountId = "444444444444"),
                first.copy(region = "eu-west-1"),
                first.copy(bucket = "another-backup"),
                first.copy(prefix = "another-prefix/"),
                first.copy(maximumAgeSeconds = first.maximumAgeSeconds - 1),
                first.copy(policy = first.policy.copy(policyId = "other-age-policy")),
                first.copy(policy = first.policy.copy(version = 2)),
                first.copy(policy = first.policy.copy(sha256 = "b".repeat(64))),
            ).map { replacement -> replaceLiveJournalPolicy(policy, copies = listOf(replacement) + policy.copyPolicies.drop(1)) }
            val otherPolicies = listOf(
                replaceLiveJournalPolicy(policy, lock = policy.journalLock.copy(policy = policy.journalLock.policy.copy(version = 2))),
                replaceLiveJournalPolicy(policy, hmac = policy.hmacKeys.map { it.copy(policy = it.policy.copy(version = 2)) }),
                replaceLiveJournalPolicy(policy, kms = policy.kmsKeys.map { it.copy(policy = it.policy.copy(version = 2)) }),
                replaceLiveJournalPolicy(policy, late = policy.acceptedRequestLateArrival.copy(maximumMillis = 120_001)),
                replaceLiveJournalPolicy(policy, late = policy.acceptedRequestLateArrival.copy(profileId = "other-late-profile")),
                replaceLiveJournalPolicy(
                    policy,
                    late = policy.acceptedRequestLateArrival.copy(policy = policy.acceptedRequestLateArrival.policy.copy(version = 2)),
                ),
                replaceLiveJournalPolicy(policy, utc = policy.utcUncertainty.copy(maximumMillis = 251)),
                replaceLiveJournalPolicy(policy, utc = policy.utcUncertainty.copy(profileId = "other-utc-profile")),
                replaceLiveJournalPolicy(policy, utc = policy.utcUncertainty.copy(policy = policy.utcUncertainty.policy.copy(version = 2))),
            )
            (copies + otherPolicies).forEach { changed ->
                assertFalse(original.configurationHashBytes().contentEquals(compose(changed).configurationHashBytes()))
            }
            assertArrayEquals(
                bytes,
                compose(
                    replaceLiveJournalPolicy(
                        policy,
                        copies = policy.copyPolicies.reversed(),
                        hmac = policy.hmacKeys.reversed(),
                        kms = policy.kmsKeys.reversed(),
                    ),
                ).canonicalBytes(),
            )
            val changedTrust = projectedSettings(
                VersionBoundCatalogReadbackTestFixture.settings(
                    policy = VersionBoundCatalogReadbackTestFixture.chainPolicy(
                        trust = VersionBoundCatalogReadbackTestFixture.trustPolicy(minimumVersion = 8),
                    ),
                ),
            )
            val trustOwner = VersionBoundLiveJournalCoverageV1.fromIndependentInputs(consumers.journalRouting, changedTrust, lanes, policy)
            assertFalse(
                original.configurationHashBytes().contentEquals(
                    liveProcessConfiguration(consumers, pools, changedTrust, lanes, sealer, trustOwner).configurationHashBytes(),
                ),
            )
            val credentials = AwsSessionCredentials.create("D6SYNTHETICREFRESH", "d6-refreshed-secret", "d6-refreshed-token")
            VersionBoundEpochSealAcquisitionV1.fromIndependentInputs(
                consumers.journalRouting,
                lanes,
                sealer.descriptor().deployment,
                credentials,
                "d6-refreshed-session",
            ).use { refreshed ->
                val differentClocks = VersionBoundLiveJournalCoverageV1.withClockFixture(
                    consumers.journalRouting,
                    reader,
                    lanes,
                    policy,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                    { Long.MIN_VALUE },
                )
                val rebuilt = liveProcessConfiguration(consumers, pools, reader, lanes, refreshed, differentClocks)
                assertArrayEquals(bytes, rebuilt.canonicalBytes())
                listOf(
                    credentials.accessKeyId(),
                    credentials.secretAccessKey(),
                    credentials.sessionToken(),
                    "d6-refreshed-session",
                    Instant.EPOCH.toString(),
                ).forEach {
                    assertFalse(rebuilt.canonicalBytes().decodeToString().contains(it))
                }
            }
            assertArrayEquals(bytes, original.canonicalBytes())
            assertTrue(wire.requests.isEmpty())
            assertEquals(0L, lanes.activeOwners().totalOwners)
        }

    @Test
    fun `D6 requires actual complete graph and rejects same byte reader routing lanes or closed sealer substitution`() {
        for (rotation in listOf(false, true)) {
            withLiveProcessOwners(epochRotation = rotation) { consumers, pools, wire ->
                val lanes = wire.lanes
                val sealer = wire.acquisition
                val reader = VersionBoundCatalogReadbackTestFixture.settings()
                val declaration = liveJournalPolicy(consumers.journalRouting, reader)
                val coverage = VersionBoundLiveJournalCoverageV1.fromIndependentInputs(consumers.journalRouting, reader, lanes, declaration)
                if (!rotation) {
                    assertThrows<IllegalArgumentException> { liveProcessConfiguration(consumers, pools, reader, lanes, sealer, coverage) }
                } else {
                    val process = liveProcessConfiguration(consumers, pools, reader, lanes, sealer, coverage)
                    assertThrows<IllegalArgumentException> {
                        liveProcessConfiguration(consumers, pools, VersionBoundCatalogReadbackTestFixture.settings(), lanes, sealer, coverage)
                    }
                    assertThrows<IllegalArgumentException> {
                        liveProcessConfiguration(BoundComplaintConsumerFixture().configuration(), pools, reader, lanes, sealer, coverage)
                    }
                    JournalPublicationLanesV1(consumers.journalConfiguration).use { replacement ->
                        assertThrows<IllegalArgumentException> { liveProcessConfiguration(consumers, pools, reader, replacement, sealer, coverage) }
                        val other = VersionBoundLiveJournalCoverageV1.fromIndependentInputs(consumers.journalRouting, reader, replacement, declaration)
                        assertThrows<IllegalArgumentException> { liveProcessConfiguration(consumers, pools, reader, lanes, sealer, other) }
                        assertThrows<IllegalArgumentException> { liveProcessConfiguration(consumers, pools, reader, replacement, sealer, other) }
                    }
                    sealer.close()
                    assertThrows<IllegalArgumentException> { process.requireUnchangedConfiguration() }
                    assertThrows<IllegalArgumentException> { process.desiredSettings() }
                }
                assertTrue(wire.requests.isEmpty())
                assertEquals(0L, lanes.activeOwners().totalOwners)
            }
        }
    }

    /** Same cold owners and close order as the explicit scopes; assertions retain the actual fixture instances. */
    private fun withLiveProcessOwners(
        epochRotation: Boolean = true,
        test: (VersionBoundComplaintConsumerConfiguration, VersionBoundPersistencePools, HeldEpochSealHttpFixture) -> Unit,
    ) = ComplaintProcessPoolFixture(epochRotation = epochRotation).use { database ->
        val consumers = BoundComplaintConsumerFixture().configuration()
        val pools = database.bind()
        val wire = HeldEpochSealHttpFixture(consumers, HeldEpochSealClock())
        wire.lanes.use {
            wire.acquisition.use {
                test(consumers, pools, wire)
            }
        }
    }

    private fun document(configuration: VersionBoundComplaintProcessConfiguration): JsonObject =
        Json.parseToJsonElement(configuration.canonicalBytes().toString(Charsets.UTF_8)).jsonObject

    private fun poolDocuments(document: JsonObject): List<JsonObject> =
        document.getValue("persistence").jsonObject.getValue("pools").jsonArray.map { it.jsonObject }

    private companion object {
        const val GOLDEN_SHA256 = "5e36b496219fa6a0dd5ab09cd08e826eba5958a03d0b24933500d28d2a487b69"
    }
}
