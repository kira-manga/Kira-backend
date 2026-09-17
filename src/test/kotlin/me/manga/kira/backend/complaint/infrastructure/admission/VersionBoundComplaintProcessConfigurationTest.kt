package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStatePolicy
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.security.SecurityConfig
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import me.manga.kira.backend.security.boundConsumerTestSettings
import me.manga.kira.backend.security.historyTestRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.mock.env.MockEnvironment
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

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

    private fun document(configuration: VersionBoundComplaintProcessConfiguration): JsonObject =
        Json.parseToJsonElement(configuration.canonicalBytes().toString(Charsets.UTF_8)).jsonObject

    private fun poolDocuments(document: JsonObject): List<JsonObject> =
        document.getValue("persistence").jsonObject.getValue("pools").jsonArray.map { it.jsonObject }

    private companion object {
        const val GOLDEN_SHA256 = "5e36b496219fa6a0dd5ab09cd08e826eba5958a03d0b24933500d28d2a487b69"
    }
}
