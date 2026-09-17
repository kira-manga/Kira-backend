package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.journal.liveJournalPolicy
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.SecretVersionSnapshot
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsEpochSealStsLimits
import me.manga.kira.backend.security.boundConsumerTestSettings
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class ComplaintDesiredDeploymentInputsV1Test {
    @Test
    fun `independent documents select exact D1 through D6 shapes and retain public input snapshots`() {
        for (profile in listOf("D1", "D2", "D3", "D4", "D5", "D6")) {
            val document = DesiredInstallationInputFixture.document(profile)
            val inputs = ComplaintDesiredDeploymentJsonV1.parse(bytes(document))
            assertEquals(profile, inputs.profile.name)
            assertEquals(1L, inputs.desiredGeneration)
            assertEquals(profile != "D1", inputs.catalog != null)
            assertEquals(profile == "D5", inputs.catalog?.projectedCurrent == true)
            assertEquals(profile in setOf("D3", "D4", "D6"), inputs.epochRotation)
            assertEquals(profile in setOf("D4", "D6"), inputs.sealerMapping != null)
            assertEquals(profile == "D6", inputs.livePolicy != null)
            assertNotEquals(inputs.runtimePassword.version, inputs.operatorPassword.version)
            assertEquals(inputs.allBindings().size, inputs.allBindings().map { it.version }.distinct().size)
            inputs.publicTrustPem().fill(0)
            assertArrayEquals(Base64.getDecoder().decode(document.database.publicTrustPemBase64), inputs.publicTrustPem())
            assertEquals("ComplaintDesiredDeploymentInputsV1(independent-intent,redacted,no-authority)", inputs.toString())
        }
    }

    @Test
    fun `only generation one genuine initial reader profiles permit bootstrap and D1 or projected readers cannot`() {
        for (profile in listOf("D2", "D3", "D4", "D6")) {
            val document = DesiredInstallationInputFixture.document(profile)
            ComplaintDesiredDeploymentJsonV1.parse(bytes(document)).requireBootstrapProfile()
            val later = ComplaintDesiredDeploymentJsonV1.parse(bytes(document.copy(desiredGeneration = 2)))
            assertEquals(
                ComplaintDesiredInstallationFailureV1.INPUT_REFUSED,
                assertThrows<ComplaintDesiredInstallationExceptionV1> { later.requireBootstrapProfile() }.code,
            )
        }
        for (profile in listOf("D1", "D5")) {
            val inputs = ComplaintDesiredDeploymentJsonV1.parse(bytes(DesiredInstallationInputFixture.document(profile)))
            assertEquals(
                ComplaintDesiredInstallationFailureV1.INPUT_REFUSED,
                assertThrows<ComplaintDesiredInstallationExceptionV1> { inputs.requireBootstrapProfile() }.code,
            )
        }
        for (profile in listOf("D7", "d2", "TEST")) {
            refused(bytes(DesiredInstallationInputFixture.document().copy(profile = profile)))
        }
    }

    @Test
    fun `strict parser refuses malformed duplicate unknown and supplied D fields without submitted diagnostics`() {
        val text = bytes(DesiredInstallationInputFixture.document()).decodeToString()
        val candidates = listOf(
            "[]",
            text.dropLast(1),
            "$text {}",
            text.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            text.replaceFirst("{", "{\"schemaVersion\":1,"),
            text.replaceFirst("\"host\":", "\"host\":\"duplicate-private-canary\",\"host\":"),
            text.replaceFirst("{", "{\"D\":\"submitted-private-canary\","),
            text.replaceFirst("{", "{\"desiredConfigurationHash\":\"${"a".repeat(64)}\","),
            text.replaceFirst("\"database\":{", "\"database\":{\"password\":\"submitted-private-canary\","),
        )
        candidates.forEach { refused(it.toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun `byte and structural bounds reject invalid UTF8 empty oversize and deep input before acquisition`() {
        val valid = bytes(DesiredInstallationInputFixture.document("D1"))
        val maximum = " ".repeat(ComplaintDesiredDeploymentJsonV1.MAX_BYTES - valid.size).toByteArray() + valid
        assertEquals(DesiredProcessProfileV1.D1, ComplaintDesiredDeploymentJsonV1.parse(maximum).profile)
        refused(maximum + byteArrayOf(' '.code.toByte()))
        refused(byteArrayOf())
        refused(byteArrayOf(0xC3.toByte(), 0x28))
        refused(("{\"nested\":".repeat(25) + "0" + "}".repeat(25)).toByteArray())
    }

    @Test
    fun `closed profile shapes cannot omit required readers or disguise rotation and sealer owners`() {
        val initial = DesiredInstallationInputFixture.document()
        val rotating = DesiredInstallationInputFixture.document("D3")
        val sealing = DesiredInstallationInputFixture.document("D4")
        val candidates = listOf(
            DesiredInstallationInputFixture.document("D1").copy(catalog = initial.catalog),
            initial.copy(catalog = null),
            initial.copy(epochRotation = true),
            initial.copy(sealer = sealing.sealer),
            rotating.copy(epochRotation = false),
            sealing.copy(sealer = null),
            sealing.copy(epochRotation = false),
            DesiredInstallationInputFixture.document("D5").copy(sealer = sealing.sealer),
        )
        candidates.forEach { refused(bytes(it)) }
        val d1 = bytes(DesiredInstallationInputFixture.document("D1")).decodeToString()
        refused(d1.replaceFirst("\"catalog\":null,", "").toByteArray())
        refused(d1.replaceFirst(",\"livePolicy\":null", "").toByteArray())
    }

    @Test
    fun `D6 explicitly selects G1 or projected reader and cannot replace required policy owners with claims`() {
        val original = DesiredInstallationInputFixture.document("D6")
        val reader = checkNotNull(original.catalog)
        val policy = checkNotNull(original.livePolicy)
        val projected = ComplaintDesiredDeploymentJsonV1.parse(bytes(original.copy(catalog = reader.copy(readerProfile = "PROJECTED_CURRENT"))))
        assertEquals(DesiredProcessProfileV1.D6, projected.profile)
        assertTrue(checkNotNull(projected.catalog).projectedCurrent)
        val retained = checkNotNull(projected.livePolicy)
        assertEquals(setOf("PRIMARY", "REPLICA", "OPERATOR", "OFFSITE"), retained.copyPolicies.map { it.locationClass }.toSet())
        assertEquals(policy.utcUncertainty.maximumMillis, retained.utcUncertainty.maximumMillis)
        assertEquals(
            ComplaintDesiredInstallationFailureV1.INPUT_REFUSED,
            assertThrows<ComplaintDesiredInstallationExceptionV1> { projected.requireBootstrapProfile() }.code,
        )
        val candidates = listOf(
            original.copy(livePolicy = null),
            original.copy(sealer = null),
            original.copy(epochRotation = false),
            original.copy(catalog = reader.copy(readerProfile = "projected")),
            original.copy(livePolicy = policy.copy(utcUncertainty = policy.utcUncertainty.copy(maximumMillis = -1))),
            DesiredInstallationInputFixture.document("D4").copy(livePolicy = policy),
            DesiredInstallationInputFixture.document("D2").copy(catalog = reader.copy(readerProfile = "PROJECTED_CURRENT")),
            DesiredInstallationInputFixture.document("D5").copy(catalog = reader),
        )
        candidates.forEach { refused(bytes(it)) }
        val text = bytes(original).decodeToString()
        refused(text.replaceFirst("\"readerProfile\":\"G1\",", "").toByteArray())
        refused(text.replaceFirst("\"livePolicy\":{", "\"livePolicy\":{\"installed\":true,").toByteArray())
    }

    @Test
    fun `endpoint role immutable reference and single instance policy cannot be supplied as aliases`() {
        val original = DesiredInstallationInputFixture.document()
        val db = original.database
        val candidates = listOf(
            original.copy(database = db.copy(host = "https://db.invalid")),
            original.copy(database = db.copy(runtimeUsername = VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME)),
            original.copy(database = db.copy(operatorPassword = db.runtimePassword)),
            original.copy(database = db.copy(runtimePassword = db.runtimePassword.copy(versionId = "AWSCURRENT"))),
            original.copy(database = db.copy(protectedTrustParent = "relative/trust")),
            original.copy(admission = original.admission.copy(coordinationMode = "redis")),
            original.copy(admission = original.admission.copy(declaredInstances = 2)),
            original.copy(databaseIdentity = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        )
        candidates.forEach { refused(bytes(it)) }
    }

    private fun bytes(document: ComplaintDesiredDeploymentDocumentV1): ByteArray =
        Json.encodeToString(ComplaintDesiredDeploymentDocumentV1.serializer(), document).toByteArray(Charsets.UTF_8)

    private fun refused(bytes: ByteArray) {
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { ComplaintDesiredDeploymentJsonV1.parse(bytes) }
        assertEquals(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED, failure.code)
        assertEquals("Desired configuration installation refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(failure.stackTrace.isEmpty())
    }
}

/** Independent synthetic inputs and genuine acquisition SPI owners, never a target D or provider/installation receipt. */
internal object DesiredInstallationInputFixture {
    fun document(profile: String = "D2", generation: Long = 1): ComplaintDesiredDeploymentDocumentV1 {
        val fixture = BoundComplaintConsumerFixture()
        val journal = fixture.journal.declaration()
        val jwt = KiraSecurityProperties()
        return ComplaintDesiredDeploymentDocumentV1(
            schemaVersion = 1,
            profile = profile,
            implementationSchema = 1,
            desiredGeneration = generation,
            databaseIdentity = journal.writer.databaseIdentity,
            restoreIdentity = journal.writer.restoreIdentity,
            database = DesiredDatabaseInputV1(
                "db.invalid", 5432, "fixture_db", "fixture_user", reference(VersionBoundPersistenceTestInputs.binding()),
                DesiredSecretReferenceV1(
                    "fixture-config-operator-password",
                    "arn:aws:secretsmanager:eu-west-1:123456789012:secret:fixture-config-operator-Ab12Cd",
                    "550e8400-e29b-41d4-a716-446655440001",
                ),
                2, base64(VersionBoundPersistenceTestInputs.pem()), "/deliberately-not-created/complaint-desired-install-test",
            ),
            jwt = DesiredJwtInputV1(
                reference(fixture.userSecret.descriptor),
                jwt.issuer,
                jwt.audience,
                jwt.accessTokenTtl.seconds,
                jwt.clockSkew.seconds,
                "installation-z",
                fixture.installationSecrets.map { reference(it.descriptor) },
            ),
            capacity = DesiredCapacityInputV1(
                fixture.capacity.hardLimit.toLongArray().toList(),
                fixture.capacity.creationLimit.toLongArray().toList(),
                fixture.capacity.dailyEnrollmentLimit,
            ),
            admission = admission(fixture),
            journal = DesiredJournalInputV1(
                journal.writer,
                journal.journalLocation,
                journal.authorities,
                DesiredJournalRoutingInputV1(
                    journal.routing.activeKeyId,
                    fixture.journalSecrets.map { reference(it.descriptor) },
                    journal.routing.retentionSeconds,
                    journal.routing.minimumRotationIntervalSeconds,
                ),
                journal.encryption,
                journal.recovery,
                journal.limits,
            ),
            catalog = if (profile == "D1") null else catalog(if (profile == "D5") "PROJECTED_CURRENT" else "G1"),
            epochRotation = profile in setOf("D3", "D4", "D6"),
            sealer = if (profile in setOf("D4", "D6")) sealer() else null,
            livePolicy = if (profile == "D6") livePolicy(fixture) else null,
        )
    }

    fun acquired(
        inputs: ComplaintDesiredDeploymentInputsV1,
        runtimePassword: ByteArray = VersionBoundPersistenceTestInputs.PASSWORD.toByteArray(Charsets.UTF_8),
        operatorPassword: ByteArray = "fixture-only-distinct-config-operator-password".toByteArray(Charsets.UTF_8),
    ): List<AcquiredVersionedSecret> {
        val fixture = BoundComplaintConsumerFixture()
        val hmac = listOf(fixture.userSecret, fixture.current, fixture.previous) +
            fixture.installationSecrets + fixture.cursorSecrets + fixture.journalSecrets
        return inputs.allBindings().map { binding ->
            AcquiredVersionedSecret.acquire(binding) { version ->
                when (binding) {
                    inputs.runtimePassword -> SecretVersionSnapshot(version, runtimePassword)

                    inputs.operatorPassword -> SecretVersionSnapshot(version, operatorPassword)

                    else -> hmac.single {
                        it.descriptor.family == binding.family && it.descriptor.logicalKeyId == binding.logicalKeyId
                    }.useMaterial { SecretVersionSnapshot(version, it) }
                }
            }
        }
    }

    private fun admission(fixture: BoundComplaintConsumerFixture): DesiredAdmissionInputV1 {
        val settings = boundConsumerTestSettings()
        return DesiredAdmissionInputV1(
            settings.coordinationMode, settings.declaredInstances, settings.concurrentLimit, settings.ingressBucketLimit,
            settings.ingressPerMinute, settings.semanticBucketLimit, settings.semanticEventLimit, settings.pruneBatch,
            settings.enrollmentGlobalPerHour, settings.ownerCreateGlobalPerHour, settings.ownerCreateMemberLimit, settings.ownerCreatePruneBatch,
            settings.trustForwardedHeaders, settings.trustedProxies(), reference(fixture.current.descriptor), reference(fixture.previous.descriptor),
            "cursor-z", fixture.cursorSecrets.map { reference(it.descriptor) },
        )
    }

    private fun catalog(readerProfile: String): DesiredCatalogInputV1 {
        val chain = VersionBoundCatalogReadbackTestFixture.chainPolicy()
        val trust = chain.trustBundlePolicy
        val limits = chain.limits
        val sdk = S3CatalogReadbackLimits()
        return DesiredCatalogInputV1(
            readerProfile,
            base64(VersionBoundCatalogReadbackTestFixture.initialBundleBytes()), base64(VersionBoundCatalogReadbackTestFixture.currentBundleBytes()),
            base64(trust.rootPublicKeySpki), trust.rootPublicKeySha256, trust.rootKeyId, trust.rootAlgorithmId,
            trust.expectedEnvironment, trust.expectedCatalogLocations, trust.minimumBundleVersion,
            chain.currentWriterGenerationIds, chain.currentApproverIds, VersionBoundCatalogReadbackTestFixture.EXPECTED_GENESIS_SHA256,
            DesiredCatalogChainLimitsV1(
                limits.maximumEnvelopeBytes,
                limits.maximumManifestRecords,
                limits.maximumGenerations,
                limits.maximumEncodedBytes,
            ),
            DesiredCatalogSdkLimitsV1(
                sdk.requestTimeoutMillis,
                sdk.connectTimeoutMillis,
                sdk.readTimeoutMillis,
                sdk.maximumListBytes,
                sdk.maximumErrorBytes,
                sdk.maximumObjectBytes,
            ),
            600_000, 1000, 65536,
        )
    }

    private fun sealer(): DesiredSealerInputV1 {
        val catalog = VersionBoundCatalogReadbackTestFixture.envelope().manifest.initialWriterRegistry.catalogWriter
        val sdk = AwsEpochSealStsLimits()
        return DesiredSealerInputV1(
            principal("ordinary", 'A'), principal("sealer", 'B'), principal("recovery", 'C'),
            DesiredCatalogPrincipalInputV1(catalog.putAuthority, principal("catalog-put", 'D')),
            DesiredCatalogPrincipalInputV1(catalog.signAuthority, principal("catalog-sign", 'E')),
            DesiredBootstrapOriginInputV1("bootstrap-origin", 1, "bootstrap-credential", principal("bootstrap", 'F')),
            InitialPolicyReferenceV1("installed-bundle", 1, "a".repeat(64)), "bootstrap-first",
            DesiredStsLimitsV1(
                sdk.requestTimeoutMillis,
                sdk.connectTimeoutMillis,
                sdk.readTimeoutMillis,
                sdk.maxResponseBytes,
                sdk.clockUncertaintyMillis,
            ),
        )
    }

    private fun livePolicy(fixture: BoundComplaintConsumerFixture): DesiredLivePolicyInputV1 {
        val policy = liveJournalPolicy(fixture.routing, VersionBoundCatalogReadbackTestFixture.settings())
        val lock = policy.journalLock
        return DesiredLivePolicyInputV1(
            policy.environment,
            policy.copyPolicies.map {
                DesiredLiveCopyPolicyInputV1(it.sourceKind, it.locationClass, it.accountId, it.region, it.bucket, it.prefix, it.policy, it.maximumAgeSeconds)
            },
            DesiredLiveLockPolicyInputV1(lock.location, lock.ordinaryPrefix, lock.sealTerminalPrefix, lock.authorities, lock.policy),
            policy.hmacKeys.map {
                DesiredLiveHmacRetentionInputV1(DesiredSecretReferenceV1(it.key.keyId, it.key.secret.resourceArn, it.key.secret.versionId), it.policy)
            },
            policy.kmsKeys.map { DesiredLiveKmsRetentionInputV1(it.key, it.policy) },
            policy.acceptedRequestLateArrival.let { DesiredLiveTimeBoundInputV1(it.profileId, it.policy, it.maximumMillis) },
            policy.utcUncertainty.let { DesiredLiveTimeBoundInputV1(it.profileId, it.policy, it.maximumMillis) },
        )
    }

    private fun principal(name: String, id: Char): DesiredIamPrincipalInputV1 =
        DesiredIamPrincipalInputV1("ROLE", "arn:aws:iam::123456789012:role/$name", "AROA" + id.toString().repeat(17))

    private fun reference(binding: VersionedSecretBinding): DesiredSecretReferenceV1 =
        DesiredSecretReferenceV1(binding.logicalKeyId, binding.version.resourceArn, binding.version.versionId)

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
