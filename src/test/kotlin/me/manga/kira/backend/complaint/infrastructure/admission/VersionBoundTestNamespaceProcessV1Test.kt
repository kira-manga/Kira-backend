package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.FullTestCatalogInputs
import me.manga.kira.backend.complaint.catalog.OfflineCatalogRotationFixture
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationControlObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStatePolicy
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundTestActivationConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.BoundTestComplaintConsumerFixture
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.SecretVersionSnapshot
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import me.manga.kira.backend.security.VersionBoundTestComplaintConsumerConfigurationV1
import me.manga.kira.backend.security.VersionedSecretBinding
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
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Full cold D, not lower TEST comparison bytes, a relabelled LIVE D, an installed namespace or a grant. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundTestNamespaceProcessV1Test {
    @Test
    fun `complete TEST D matches both independent raw golden byte profiles using actual acquired owners and unopened pools`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundTestComplaintConsumerFixture()
            val consumers = fixture.configuration()
            val pools = database.bind()
            val lookups = fixture.base.lookups
            JournalPublicationLanesV1(fixture.journal).use { lanes ->
                val g1 = VersionBoundCatalogReadbackTestFixture.settings()
                for (reader in listOf(g1, projectedSettings(g1))) {
                    val activation = FullTestCatalogInputs.activation(pools, fixture.journal, reader)
                    val root = process(consumers, pools, lanes, reader, activation)
                    val expected = FullTestCatalogInputs.goldenBytes(reader.projectedCurrent)
                    val digest = if (reader.projectedCurrent) PROJECTED_SHA256 else G1_SHA256
                    assertEquals(digest, Sha256.hex(expected))
                    assertArrayEquals(expected, root.canonicalBytes())
                    assertArrayEquals(HexFormat.of().parseHex(digest), root.configurationHashBytes())
                    assertEquals(32, root.configurationHashBytes().size)
                    assertFalse(expected.last() == '\n'.code.toByte())
                    assertFalse(expected.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
                    assertSame(consumers, root.consumers)
                    assertSame(pools, root.pools)
                    assertSame(lanes, root.publicationLanes)
                    assertSame(reader, root.catalogReadback)
                    assertSame(activation, root.catalogActivation)
                    val desired = root.desiredSettings()
                    assertEquals(ComplaintInstallationMode.PRE_CUTOVER_TEST, desired.mode)
                    assertEquals(fixture.journal.scope, desired.scope)
                    assertEquals(7L, desired.desiredGeneration) // A first fresh TEST run does not require desired generation 1.
                    assertArrayEquals(root.configurationHashBytes(), desired.configurationHashBytes())
                    val encoded = document(root)
                    assertEquals(
                        setOf(
                            "kind", "schemaVersion", "canonicalizerId", "profile", "identity", "capacityPolicy", "journalConfiguration",
                            "consumers", "persistence", "publicationLanes", "catalogReadback", "catalogActivation",
                        ),
                        encoded.keys,
                    )
                    val flatJ = Json.parseToJsonElement(fixture.journal.canonicalBytes().decodeToString()).jsonObject
                    assertFalse("scope" in flatJ)
                    assertEquals("TEST", flatJ.getValue("dataScopeKind").jsonPrimitive.content)
                    assertEquals(TEST_J_SHA256, Sha256.hex(fixture.journal.canonicalBytes()))
                    assertEquals(TEST_J_SHA256, encoded.getValue("journalConfiguration").jsonObject.getValue("sha256").jsonPrimitive.content)
                    val poolDocuments = encoded.getValue("persistence").jsonObject.getValue("pools").jsonArray
                    assertEquals(
                        listOf("ORDINARY", "DELETION", "CATALOG_COORDINATOR"),
                        poolDocuments.map { it.jsonObject.getValue("role").jsonPrimitive.content },
                    )
                    val password = pools.descriptors().first().authenticationPassword
                    pools.descriptors().forEach { assertSame(password, it.authenticationPassword) }
                    root.requireUnchangedConfiguration()
                }
                assertEquals(0L, lanes.activeOwners().totalOwners)
            }
            assertEquals(lookups, fixture.base.lookups)
            assertCold(pools)
        }

    @Test
    fun `matching full TEST D control and ACTIVE run remain diagnostic provenance-required and neither observation becomes desired input`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundTestComplaintConsumerFixture()
            val pools = database.bind()
            JournalPublicationLanesV1(fixture.journal).use { lanes ->
                val root = process(fixture.configuration(), pools, lanes)
                val desired = root.desiredSettings()
                val hash = desired.configurationHashBytes()
                val active = ComplaintInstallationRunObservation.Present(desired.scope, true, ComplaintInstallationRunState.ACTIVE, hash)
                val control = control(desired, hash)
                hash.fill(0)
                assertEquals(
                    ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED,
                    ComplaintInstallationCurrentStatePolicy.assess(desired, desired.scope, control, active),
                )
                assertEquals(
                    ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE,
                    ComplaintInstallationCurrentStatePolicy.assess(desired, desired.scope, control),
                )
                val different = root.configurationHashBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
                assertEquals(
                    ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH,
                    ComplaintInstallationCurrentStatePolicy.assess(desired, desired.scope, control(desired, different), active),
                )
                assertEquals(
                    ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH,
                    ComplaintInstallationCurrentStatePolicy.assess(
                        desired, desired.scope, control,
                        ComplaintInstallationRunObservation.Present(desired.scope, true, ComplaintInstallationRunState.ACTIVE, different),
                    ),
                )
                assertEquals(
                    ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED,
                    ComplaintInstallationCurrentStatePolicy.assess(
                        desired, desired.scope, control,
                        ComplaintInstallationRunObservation.Present(desired.scope, true, ComplaintInstallationRunState.SEALED, root.configurationHashBytes()),
                    ),
                )
                assertEquals(G1_SHA256, Sha256.hex(root.canonicalBytes()))
                assertArrayEquals(desired.configurationHashBytes(), root.desiredSettings().configurationHashBytes())
            }
        }

    @Test
    fun `private paths collection order defensive copies quota consumption and shared LIVE lane counts cannot rewrite cold TEST D`() =
        ComplaintProcessPoolFixture().use { first ->
            ComplaintProcessPoolFixture().use { second ->
                val fixture = BoundTestComplaintConsumerFixture()
                val leftPools = first.bind()
                val rightPools = second.bind()
                val proxies = mutableListOf("198.51.100.0/24", "192.0.2.0/24", "192.0.2.0/24")
                val settings = boundConsumerTestSettings(trustedProxies = proxies)
                val leftConsumers = fixture.configuration(settings = settings)
                fixture.base.cursorSecrets.reverse()
                fixture.base.installationSecrets.reverse()
                val jwt = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-z", fixture.base.installationSecrets, fixture.base.user)
                val routing = TestOwnerDeleteJournalRoutingV1.fromAcquired(fixture.journal, fixture.routingSecrets.reversed())
                val rightConsumers = fixture.configuration(
                    keys = fixture.inputs(routing = routing), jwt = jwt,
                    settings = boundConsumerTestSettings(trustedProxies = proxies.reversed()),
                )
                // The same registry also retains LIVE; this is shared N/R accounting, never LIVE authority for TEST.
                JournalPublicationLanesV1(fixture.base.journal).use { lanes ->
                    val left = process(leftConsumers, leftPools, lanes)
                    val right = process(rightConsumers, rightPools, lanes)
                    assertSame(lanes, left.publicationLanes)
                    lanes.requireJournal(fixture.base.journal)
                    lanes.requireTestJournal(fixture.journal)
                    val bytes = left.canonicalBytes()
                    val hash = left.configurationHashBytes()
                    assertArrayEquals(bytes, right.canonicalBytes())
                    val paths = listOf(first.root, second.root).map { it.endpoint.driverProperties().getProperty("sslrootcert") }
                    assertNotEquals(paths[0], paths[1])
                    val encoded = bytes.decodeToString()
                    paths.forEach { assertFalse(encoded.contains(it)) }
                    assertFalse(encoded.contains(VersionBoundPersistenceTestInputs.PASSWORD))
                    assertFalse(encoded.contains(Sha256.hex(VersionBoundPersistenceTestInputs.PASSWORD.toByteArray(Charsets.UTF_8))))
                    val secrets = listOf(fixture.base.userSecret, fixture.base.current, fixture.base.previous) +
                        fixture.base.installationSecrets + fixture.base.cursorSecrets + fixture.routingSecrets
                    secrets.forEach { secret ->
                        secret.useMaterial { material ->
                            assertFalse(encoded.contains(Base64.getEncoder().encodeToString(material)))
                            assertFalse(encoded.contains(Sha256.hex(material)))
                        }
                    }
                    val trustedIp = document(left).getValue("consumers").jsonObject.getValue("admission").jsonObject.getValue("trustedIp").jsonObject
                    assertEquals(3, trustedIp.getValue("trustedProxies").jsonArray.size)
                    proxies.clear()
                    fixture.base.eraseOriginals()
                    left.canonicalBytes().fill(0)
                    left.configurationHashBytes().fill(0)
                    left.desiredSettings().configurationHashBytes().fill(0)
                    left.catalogActivation.initialWriterRegistryBytes().fill(0)
                    left.catalogReadback.initialBundleBytes().fill(0)
                    left.catalogReadback.currentBundleBytes().fill(0)
                    (left.consumers.descriptors() as MutableList<*>).clear()
                    (left.consumers.trustedProxies() as MutableList<*>).clear()
                    leftPools.descriptors().forEach { descriptor ->
                        descriptor.openings().forEach { (it.publicDriverProperties() as MutableMap<*, *>).clear() }
                    }
                    left.consumers.ingressAdmission.withIngress(historyTestRequest()) {}
                    checkNotNull(lanes.tryRoutinePublication()).use {
                        assertEquals(1L, lanes.activeOwners().totalOwners)
                        left.requireUnchangedConfiguration()
                        assertArrayEquals(bytes, left.canonicalBytes())
                        assertArrayEquals(hash, left.configurationHashBytes())
                    }
                    assertEquals(0L, lanes.activeOwners().totalOwners)
                    assertCold(leftPools)
                    assertCold(rightPools)
                }
            }
        }

    @Test
    fun `exact TEST J reader original coordinator and lane binding cannot be replaced by equal-looking owners`(): Unit =
        ComplaintProcessPoolFixture().use { database ->
            ComplaintProcessPoolFixture().use { replacement ->
                val fixture = BoundTestComplaintConsumerFixture()
                val consumers = fixture.configuration()
                val pools = database.bind()
                val otherPools = replacement.bind()
                val reader = VersionBoundCatalogReadbackTestFixture.settings()
                val activation = FullTestCatalogInputs.activation(pools, fixture.journal, reader)
                JournalPublicationLanesV1(fixture.journal).use { lanes ->
                    val original = process(consumers, pools, lanes, reader, activation)
                    assertThrows<IllegalArgumentException> { process(consumers, otherPools, lanes, reader, activation) }
                    assertThrows<IllegalArgumentException> { process(consumers, pools, lanes, VersionBoundCatalogReadbackTestFixture.settings(), activation) }
                    val otherJ = TestOwnerDeleteJournalConfigurationV1.of(fixture.journal.declaration())
                    val otherConsumers = BoundTestComplaintConsumerFixture(otherJ).configuration()
                    assertThrows<IllegalArgumentException> { process(otherConsumers, pools, lanes, reader, activation) }
                    JournalPublicationLanesV1(otherJ).use { wrongLanes ->
                        assertThrows<JournalPublicationExceptionV1> { process(consumers, pools, wrongLanes, reader, activation) }
                    }
                    val otherActivation = FullTestCatalogInputs.activation(otherPools, fixture.journal, reader)
                    assertThrows<IllegalArgumentException> { process(consumers, pools, lanes, reader, otherActivation) }
                    for (schema in listOf(0, 2)) {
                        assertThrows<IllegalArgumentException> { process(consumers, pools, lanes, reader, activation, schema = schema) }
                    }
                    for (generation in listOf(0L, -1L)) {
                        assertThrows<IllegalArgumentException> { process(consumers, pools, lanes, reader, activation, generation = generation) }
                    }
                    for (id in listOf(UUID(0, 0), UUID.fromString("81111111-1111-4111-8111-111111111111"))) {
                        assertThrows<IllegalArgumentException> { process(consumers, pools, lanes, reader, activation, database = id) }
                        assertThrows<IllegalArgumentException> { process(consumers, pools, lanes, reader, activation, restore = id) }
                    }
                    assertDifferent(original, process(consumers, pools, lanes, reader, activation, generation = 8), "desired generation")
                    assertArrayEquals(original.canonicalBytes(), process(consumers, pools, lanes, reader, activation).canonicalBytes())
                }
                val limits = fixture.journal.declaration().limits
                val differentJ = TestOwnerDeleteJournalConfigurationV1.of(
                    fixture.journal.declaration().copy(limits = limits.copy(capacity = limits.capacity.copy(maximumPublicationLanes = 5))),
                )
                JournalPublicationLanesV1(fixture.base.journal).use { liveLanes ->
                    assertThrows<JournalPublicationExceptionV1> {
                        process(BoundTestComplaintConsumerFixture(differentJ).configuration(), pools, liveLanes)
                    }
                }
            }
        }

    @Test
    fun `incomplete pools optional rotation insufficient ordinary capacity and database-consumer version reuse cannot produce full TEST D`() {
        val fixture = BoundTestComplaintConsumerFixture()
        ComplaintProcessPoolFixture().use { database ->
            assertThrows<PersistenceBoundaryException> {
                FullTestCatalogInputs.activation(checkNotNull(database.owner.versionBoundPools), fixture.journal)
            }
            database.bind()
        }
        ComplaintProcessPoolFixture(epochRotation = true).use { database ->
            val pools = database.bind()
            assertThrows<IllegalArgumentException> { FullTestCatalogInputs.activation(pools, fixture.journal) }
        }
        ComplaintProcessPoolFixture(capacity = 1).use { database ->
            val pools = database.bind()
            JournalPublicationLanesV1(fixture.journal).use { lanes ->
                assertThrows<IllegalArgumentException> { process(fixture.configuration(), pools, lanes) }
            }
        }
        val versions = listOf(fixture.base.userSecret, fixture.base.previous, fixture.base.cursorSecrets.last(), fixture.routingSecrets.last())
        versions.forEach { secret ->
            ComplaintProcessPoolFixture(password = password(secret.descriptor.version)).use { database ->
                val pools = database.bind()
                JournalPublicationLanesV1(fixture.journal).use { lanes ->
                    assertThrows<IllegalArgumentException> { process(fixture.configuration(), pools, lanes) }
                }
            }
        }
    }

    @Test
    fun `real Hikari drift in each role or lower datasource substitution invalidates current use but not historical cached bytes`() {
        for (role in 0..3) {
            ComplaintProcessPoolFixture(retained = true).use { database ->
                val fixture = BoundTestComplaintConsumerFixture()
                val pools = database.bind()
                JournalPublicationLanesV1(fixture.journal).use { lanes ->
                    val root = process(fixture.configuration(), pools, lanes)
                    val bytes = root.canonicalBytes()
                    val hash = root.configurationHashBytes()
                    val sources = listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource)
                    if (role < 3) {
                        actualPool(sources[role]).maximumPoolSize += 1
                    } else {
                        actualPool(pools.ordinary).dataSource = pools.deletion
                    }
                    assertThrows<PersistenceBoundaryException> { root.requireUnchangedConfiguration() }
                    assertThrows<PersistenceBoundaryException> { root.desiredSettings() }
                    assertArrayEquals(bytes, root.canonicalBytes())
                    assertArrayEquals(hash, root.configurationHashBytes())
                    assertCold(pools)
                }
            }
        }
    }

    @Test
    fun `actual admission settings P JWT precision active selections and every acquired secret family change D`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundTestComplaintConsumerFixture()
            val pools = database.bind()
            JournalPublicationLanesV1(fixture.journal).use { lanes ->
                val original = process(fixture.configuration(), pools, lanes)
                val settings = ComplaintProcessAdmissionInputs()
                val variants = listOf(
                    settings.copy(concurrent = 3), settings.copy(ingressBuckets = 65), settings.copy(ingressRate = 119),
                    settings.copy(semanticBuckets = 129), settings.copy(semanticEvents = 4097), settings.copy(prune = 9),
                    settings.copy(enrollmentGlobal = 3), settings.copy(createGlobal = 3), settings.copy(members = 65), settings.copy(memberPrune = 9),
                    settings.copy(forwarded = true), settings.copy(proxies = listOf("192.0.2.0/24")),
                )
                variants.forEachIndexed { index, changed ->
                    assertDifferent(original, process(fixture.configuration(settings = changed.settings()), pools, lanes), "admission $index")
                }
                val p = fixture.base.capacity
                val policies = listOf(
                    ComplaintCapacityPolicyV1.of(
                        p.hardLimit.with(ComplaintCapacityCounter.AUDIT_ROWS, p.hardLimit[ComplaintCapacityCounter.AUDIT_ROWS] + 1),
                        p.creationLimit, p.dailyEnrollmentLimit,
                    ),
                    ComplaintCapacityPolicyV1.of(
                        p.hardLimit, p.creationLimit.with(ComplaintCapacityCounter.AUDIT_ROWS, p.creationLimit[ComplaintCapacityCounter.AUDIT_ROWS] - 1),
                        p.dailyEnrollmentLimit,
                    ),
                    ComplaintCapacityPolicyV1.of(p.hardLimit, p.creationLimit, p.dailyEnrollmentLimit - 1),
                )
                policies.forEach { changed -> assertDifferent(original, process(fixture.configuration(capacity = changed), pools, lanes), "P") }
                val selections = listOf(
                    fixture.configuration(keys = fixture.inputs(current = fixture.base.previous, previous = fixture.base.current)),
                    fixture.configuration(keys = fixture.inputs(previous = null)),
                    fixture.configuration(keys = fixture.inputs(activeCursor = "cursor-a")),
                    fixture.configuration(
                        jwt = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-a", fixture.base.installationSecrets, fixture.base.user),
                    ),
                )
                selections.forEach { changed -> assertDifferent(original, process(changed, pools, lanes), "active or retained selection") }
                val properties = KiraSecurityProperties()
                val userSettings = listOf(
                    properties.copy(issuer = "test-full-issuer-π"), properties.copy(audience = "test-full-audience"),
                    properties.copy(accessTokenTtl = Duration.ofSeconds(3600, 17)), properties.copy(clockSkew = Duration.ofSeconds(60, 41)),
                )
                userSettings.forEach { changed ->
                    val user = JwtKeyProvider.fromAcquired(fixture.base.userSecret, changed)
                    val jwt = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-z", fixture.base.installationSecrets, user)
                    assertDifferent(original, process(fixture.configuration(jwt = jwt), pools, lanes), "actual JWT settings")
                }
                replacementVersions(fixture).forEach { changed ->
                    JournalPublicationLanesV1(changed.journalConfiguration).use { changedLanes ->
                        assertDifferent(original, process(changed, pools, changedLanes), "actual immutable secret reference")
                    }
                }
            }
        }

    @Test
    fun `flat TEST J commitment covers namespace roles routing KMS queues retention deadlines decoder and N R budgets`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundTestComplaintConsumerFixture()
            val pools = database.bind()
            JournalPublicationLanesV1(fixture.journal).use { lanes ->
                val original = process(fixture.configuration(), pools, lanes)
                val j = fixture.journal.declaration()
                val routing = j.routing.keys.first()
                val changedVersion = ImmutableSecretVersion.awsSecretsManager(routing.secret.resourceArn, "e1111111-1111-4111-8111-111111111111")
                val variants = listOf(
                    j.copy(scope = ComplaintDataScope.of(UUID.fromString("b4444444-4444-4444-8444-444444444444"))),
                    j.copy(journalLocation = j.journalLocation.copy(bucket = "another-test-journal")),
                    j.copy(authorities = j.authorities.copy(recovery = j.authorities.recovery.copy(policy = j.authorities.recovery.policy.copy(version = 2)))),
                    j.copy(authorities = j.authorities.copy(isolation = j.authorities.isolation.copy(backupFailureDomainId = "other-backup-domain"))),
                    j.copy(routing = j.routing.copy(activeKeyId = j.routing.keys.first { it.keyId != j.routing.activeKeyId }.keyId)),
                    j.copy(routing = j.routing.copy(keys = j.routing.keys.map { if (it === routing) it.copy(secret = changedVersion) else it })),
                    j.copy(routing = j.routing.copy(retentionSeconds = j.routing.retentionSeconds + 1)),
                    j.copy(encryption = j.encryption.copy(policy = j.encryption.policy.copy(version = 2))),
                    j.copy(recovery = j.recovery.copy(queue = j.recovery.queue.copy(policy = j.recovery.queue.policy.copy(version = 2)))),
                    j.copy(
                        limits = j.limits.copy(
                            retention = j.limits.retention.copy(maximumRestoreAgeSeconds = j.limits.retention.maximumRestoreAgeSeconds - 1),
                        ),
                    ),
                    j.copy(limits = j.limits.copy(deadlines = j.limits.deadlines.copy(s3CallMillis = 1499))),
                    j.copy(limits = j.limits.copy(decoder = j.limits.decoder.copy(maximumJsonDepth = 17))),
                    j.copy(limits = j.limits.copy(capacity = j.limits.capacity.copy(maximumRetainedVersions = 1_000_001))),
                    j.copy(limits = j.limits.copy(capacity = j.limits.capacity.copy(maximumPublicationLanes = 5))),
                    j.copy(limits = j.limits.copy(capacity = j.limits.capacity.copy(routinePublicationLanes = 2))),
                )
                variants.forEachIndexed { index, declaration ->
                    val changed = BoundTestComplaintConsumerFixture(TestOwnerDeleteJournalConfigurationV1.of(declaration)).configuration()
                    JournalPublicationLanesV1(changed.journalConfiguration).use { changedLanes ->
                        val root = process(changed, pools, changedLanes)
                        assertDifferent(original, root, "TEST J category $index")
                        assertEquals(
                            changed.journalConfiguration.sha256,
                            document(root).getValue("journalConfiguration").jsonObject.getValue("sha256").jsonPrimitive.content,
                        )
                    }
                }
            }
        }

    @Test
    fun `actual database endpoint ordinary capacity public trust and acquired password reference participate in full TEST D`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundTestComplaintConsumerFixture()
            val consumers = fixture.configuration()
            val pools = database.bind()
            val version = ImmutableSecretVersion.awsSecretsManager(
                VersionBoundPersistenceTestInputs.binding().version.resourceArn, "e2222222-2222-4222-8222-222222222222",
            )
            JournalPublicationLanesV1(fixture.journal).use { lanes ->
                val original = process(consumers, pools, lanes)
                val variants = listOf(
                    { ComplaintProcessPoolFixture(host = "other.invalid") }, { ComplaintProcessPoolFixture(port = 5433) },
                    { ComplaintProcessPoolFixture(database = "other_db") }, { ComplaintProcessPoolFixture(username = "other_user") },
                    { ComplaintProcessPoolFixture(capacity = 3) }, { ComplaintProcessPoolFixture(capacity = 6) },
                    { ComplaintProcessPoolFixture(password = password(version)) },
                    { ComplaintProcessPoolFixture(trust = VersionBoundPersistenceTestInputs.pem() + byteArrayOf(10)) },
                )
                variants.forEachIndexed { index, create ->
                    create().use { replacement ->
                        val nextPools = replacement.bind()
                        val root = process(consumers, nextPools, lanes)
                        assertDifferent(original, root, "persistence $index")
                        val limit = document(root).getValue("persistence").jsonObject.getValue("admission").jsonObject.getValue("ordinaryOwnerLimit")
                        assertEquals(minOf(4, nextPools.descriptors().first().hikari.sizing.maximumPoolSize - 1).toString(), limit.jsonPrimitive.content)
                        assertCold(nextPools)
                    }
                }
            }
        }

    @Test
    fun `independent reader trust eligibility chain SDK pagination and attempt limits plus writer policy cannot disappear from D`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundTestComplaintConsumerFixture()
            val consumers = fixture.configuration()
            val pools = database.bind()
            JournalPublicationLanesV1(fixture.journal).use { lanes ->
                val reader = VersionBoundCatalogReadbackTestFixture.settings()
                val original = process(consumers, pools, lanes, reader)
                val chain = OfflineCatalogRotationFixture.limits()
                val readers = listOf(
                    projectedSettings(reader),
                    VersionBoundCatalogReadbackTestFixture.settings(pageSize = 7),
                    VersionBoundCatalogReadbackTestFixture.settings(maximumPagesPerLocation = 32),
                    VersionBoundCatalogReadbackTestFixture.settings(totalAttemptMillis = 599_999),
                    VersionBoundCatalogReadbackTestFixture.settings(sdkLimits = S3CatalogReadbackLimits(requestTimeoutMillis = 9999)),
                    VersionBoundCatalogReadbackTestFixture.settings(pin = "a".repeat(64)),
                    VersionBoundCatalogReadbackTestFixture.settings(
                        policy = VersionBoundCatalogReadbackTestFixture.chainPolicy(
                            trust = VersionBoundCatalogReadbackTestFixture.trustPolicy(minimumVersion = 8),
                        ),
                    ),
                    VersionBoundCatalogReadbackTestFixture.settings(
                        policy = VersionBoundCatalogReadbackTestFixture.chainPolicy(approvers = listOf("catalog-approver-a", "current-only-approver")),
                    ),
                    VersionBoundCatalogReadbackTestFixture.settings(
                        policy = VersionBoundCatalogReadbackTestFixture.chainPolicy(
                            writers = listOf(VersionBoundCatalogReadbackTestFixture.CATALOG_WRITER, "77777777-7777-4777-8777-777777777777"),
                        ),
                    ),
                ) + listOf(
                    chain.copy(maximumEnvelopeBytes = 131072), chain.copy(maximumManifestRecords = 4095),
                    chain.copy(maximumGenerations = 65535), chain.copy(maximumEncodedBytes = chain.maximumEncodedBytes - 1),
                ).map { VersionBoundCatalogReadbackTestFixture.settings(policy = VersionBoundCatalogReadbackTestFixture.chainPolicy(limits = it)) }
                readers.forEachIndexed { index, changed -> assertDifferent(original, process(consumers, pools, lanes, changed), "catalog reader $index") }
                val changedBudget = FullTestCatalogInputs.activation(pools, fixture.journal, reader, totalAttemptMillis = 29_999)
                val changedKeyArn = FullTestCatalogInputs.activation(
                    pools, fixture.journal, reader,
                    key = FullTestCatalogInputs.key(arn = "arn:aws:kms:us-east-1:123456789012:key/99999999-9999-4999-8999-999999999998"),
                )
                for (activation in listOf(changedBudget, changedKeyArn)) {
                    val changed = process(consumers, pools, lanes, reader, activation)
                    assertDifferent(original, changed, "actual activation writer")
                    assertEquals(document(original).filterKeys { it != "catalogActivation" }, document(changed).filterKeys { it != "catalogActivation" })
                }
                val eligibility = document(process(consumers, pools, lanes, readers[7]))
                assertNotEquals(
                    eligibility.getValue("catalogActivation").jsonObject.getValue("initialApproverIds"),
                    eligibility.getValue("catalogReadback").jsonObject.getValue("currentApproverIds"),
                )
            }
        }

    @Test
    fun `historical registry Sign PUT policy changes are committed from genuinely rebound raw trust inputs`() =
        ComplaintProcessPoolFixture().use { database ->
            val fixture = BoundTestComplaintConsumerFixture()
            val pools = database.bind()
            val registry = FullTestCatalogInputs.registry()
            val writer = registry.catalogWriter
            val variants = listOf(
                registry.copy(catalogWriter = writer.copy(signAuthority = writer.signAuthority.copy(principalId = "other-catalog-sign"))),
                registry.copy(catalogWriter = writer.copy(putAuthority = writer.putAuthority.copy(principalId = "other-catalog-put"))),
                registry.copy(catalogWriter = writer.copy(signAuthority = writer.signAuthority.copy(policy = writer.signAuthority.policy.copy(version = 2)))),
                registry.copy(
                    catalogWriter = writer.copy(putAuthority = writer.putAuthority.copy(policy = writer.putAuthority.policy.copy(sha256 = "a".repeat(64)))),
                ),
            )
            JournalPublicationLanesV1(fixture.journal).use { lanes ->
                val consumers = fixture.configuration()
                val original = process(consumers, pools, lanes)
                for (changedRegistry in variants) {
                    val reader = FullTestCatalogInputs.signedReader(changedRegistry)
                    val activation = FullTestCatalogInputs.activation(
                        pools, fixture.journal, reader,
                        key = FullTestCatalogInputs.key(spki = OfflineTrustBundleFixture.firstSigner.public.encoded),
                        registryBytes = OfflineTrustBundleFixture.registryBytes(changedRegistry),
                    )
                    val changed = process(consumers, pools, lanes, reader, activation)
                    assertDifferent(original, changed, "historical raw registry writer policy")
                    assertEquals(changedRegistry.catalogWriter.signAuthority, activation.signAuthority)
                    assertEquals(changedRegistry.catalogWriter.putAuthority, activation.putAuthority)
                    assertEquals(Sha256.hex(OfflineTrustBundleFixture.registryBytes(changedRegistry)), activation.initialWriterRegistrySha256)
                    val rawWriter = Json.parseToJsonElement(OfflineTrustBundleFixture.registryBytes(changedRegistry).decodeToString())
                        .jsonObject.getValue("catalogWriter").jsonObject
                    val encodedWriter = document(changed).getValue("catalogActivation").jsonObject
                    assertEquals(rawWriter.getValue("signAuthority"), encodedWriter.getValue("signAuthority"))
                    assertEquals(rawWriter.getValue("putAuthority"), encodedWriter.getValue("putAuthority"))
                }
            }
        }

    private fun process(
        consumers: VersionBoundTestComplaintConsumerConfigurationV1,
        pools: VersionBoundPersistencePools,
        lanes: JournalPublicationLanesV1,
        reader: VersionBoundCatalogReadbackConfigurationV1 = VersionBoundCatalogReadbackTestFixture.settings(),
        activation: VersionBoundTestActivationConfigurationV1 = FullTestCatalogInputs.activation(pools, consumers.journalConfiguration, reader),
        generation: Long = 7,
        schema: Int = 1,
        database: UUID = UUID.fromString(consumers.journalConfiguration.declaration().writer.databaseIdentity),
        restore: UUID = UUID.fromString(consumers.journalConfiguration.declaration().writer.restoreIdentity),
    ): VersionBoundTestNamespaceProcessV1 = VersionBoundTestNamespaceProcessV1.fromRetained(
        consumers, pools, schema, generation, database, restore, lanes, reader, activation,
    )

    private fun document(root: VersionBoundTestNamespaceProcessV1): JsonObject = Json.parseToJsonElement(root.canonicalBytes().decodeToString()).jsonObject

    private fun assertDifferent(original: VersionBoundTestNamespaceProcessV1, changed: VersionBoundTestNamespaceProcessV1, category: String) {
        assertFalse(original.configurationHashBytes().contentEquals(changed.configurationHashBytes()), category)
    }

    private fun assertCold(pools: VersionBoundPersistencePools) {
        listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).forEach { assertFalse(actualPool(it).isRunning) }
    }

    private fun control(desired: ComplaintInstallationDesiredSettings.Configured, hash: ByteArray): ComplaintInstallationControlObservation =
        ComplaintInstallationControlObservation(
            desired.scope, true, 1, desired.desiredGeneration, hash, desired.databaseIdentity,
            desired.restoreIdentity, maintenanceClosed = true, creationClosed = true, scanRequested = true, journalDegraded = null,
        )

    private fun password(version: ImmutableSecretVersion): AcquiredVersionedSecret = AcquiredVersionedSecret.acquire(
        VersionedSecretBinding.of(SecretMaterialFamily.DATABASE, SecretMaterialPurpose.AUTHENTICATION_PASSWORD, "fixture-db-password", version),
    ) { SecretVersionSnapshot(it, VersionBoundPersistenceTestInputs.PASSWORD.toByteArray(Charsets.UTF_8)) }

    private fun replacementVersions(fixture: BoundTestComplaintConsumerFixture): List<VersionBoundTestComplaintConsumerConfigurationV1> = listOf(
        fixture.base.userSecret, fixture.base.installationSecrets.last(), fixture.base.previous,
        fixture.base.cursorSecrets.last(), fixture.routingSecrets.first(),
    ).mapIndexed { index, original ->
        val replacement = fixture.base.acquired(original.descriptor.family, original.descriptor.logicalKeyId, 221 + index)
        when (original.descriptor.family) {
            SecretMaterialFamily.USER_ADMIN_JWT -> fixture.configuration(
                jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(
                    "installation-z", fixture.base.installationSecrets, JwtKeyProvider.fromAcquired(replacement, KiraSecurityProperties()),
                ),
            )

            SecretMaterialFamily.INSTALLATION_JWT -> fixture.configuration(
                jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(
                    "installation-z", fixture.base.installationSecrets.map { if (it === original) replacement else it }, fixture.base.user,
                ),
            )

            SecretMaterialFamily.COMPLAINT_ADMISSION -> fixture.configuration(keys = fixture.inputs(previous = replacement))
            SecretMaterialFamily.COMPLAINT_CURSOR -> fixture.configuration(
                keys = fixture.inputs(cursors = fixture.base.cursorSecrets.map { if (it === original) replacement else it }),
            )

            SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING -> {
                val j = fixture.journal.declaration()
                val keys = j.routing.keys.map {
                    if (it.keyId == original.descriptor.logicalKeyId) it.copy(secret = replacement.descriptor.version) else it
                }
                val changedJ = TestOwnerDeleteJournalConfigurationV1.of(j.copy(routing = j.routing.copy(keys = keys)))
                val routing = TestOwnerDeleteJournalRoutingV1.fromAcquired(changedJ, fixture.routingSecrets.map { if (it === original) replacement else it })
                fixture.configuration(journal = changedJ, keys = fixture.inputs(routing = routing))
            }

            else -> error("Unexpected acquired fixture family")
        }
    }

    private companion object {
        const val G1_SHA256 = "b480a6a5e9b4f7190fd269730773946a33fe689ce2234f07c86a6fe1498cf8a5"
        const val PROJECTED_SHA256 = "4bf466a442438a6ef8f492a935de6a55a5df61130da39d94bd139d7a86f7455d"
        const val TEST_J_SHA256 = "100c6989673ddf460ecad84724f7f7910d93998a2b40d6bf07cc392de95c830b"
    }
}
