package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.complaint.catalog.CatalogSignerRotationD7Inputs
import me.manga.kira.backend.complaint.catalog.FullTestCatalogInputs
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHttpFixtureV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.security.BoundTestComplaintConsumerFixture
import me.manga.kira.backend.security.fullTestJournal
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64

@EnabledOnOs(OS.LINUX, OS.MAC)
internal class ComplaintTestDeploymentInputsV1Test {
    @Test
    fun `one TEST grammar preserves exact immutable references and never accepts the LIVE grammar in either direction`() {
        val document = TestDeploymentInputFixture.document()
        val inputs = ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(document))
        assertEquals(document.dataScopeId, inputs.journal.scope.id.toString())
        assertEquals(document.retention.lastPreRunRestoreHorizon, inputs.retention.lastPreRunRestoreHorizon.toString())
        assertEquals(document.database.runtimePassword.versionId, inputs.runtimePassword.version.versionId)
        assertEquals(inputs.allBindings().size, inputs.allBindings().map { it.version }.distinct().size)
        assertTrue(inputs.allBindings().none { it.logicalKeyId.contains("operator") })
        assertArrayEquals(fullTestJournal().canonicalBytes(), inputs.journal.canonicalBytes())
        inputs.publicTrustPem().fill(0)
        assertArrayEquals(Base64.getDecoder().decode(document.database.publicTrustPemBase64), inputs.publicTrustPem())
        assertThrows<ComplaintDesiredInstallationExceptionV1> { ComplaintDesiredDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(document)) }
        for (profile in DesiredProcessProfileV1.entries) {
            val live = if (profile == DesiredProcessProfileV1.D7) {
                CatalogSignerRotationD7Inputs.document()
            } else {
                DesiredInstallationInputFixture.document(profile.name)
            }
            val liveBytes = Json.encodeToString(ComplaintDesiredDeploymentDocumentV1.serializer(), live).toByteArray()
            assertEquals(profile, ComplaintDesiredDeploymentJsonV1.parse(liveBytes).profile)
            refused(liveBytes)
            refused(TestDeploymentInputFixture.bytes(document.copy(profile = profile.name)))
        }
    }

    @Test
    fun `missing nullable fields duplicate fields malformed UTF8 floats trailing input unknown state and raw secrets are refused`() {
        val raw = TestDeploymentInputFixture.bytes(TestDeploymentInputFixture.document()).decodeToString()
        val invalid = listOf(
            raw.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            raw.replaceFirst("\"desiredGeneration\":7", "\"desiredGeneration\":7.0"),
            raw.replaceFirst("\"schemaVersion\":1,", ""),
            raw.replaceFirst("\"trustForwardedHeaders\":false", "\"trustForwardedHeaders\":null"),
            raw.replaceFirst("\"runtimeUsername\":", "\"rawSecret\":\"not-a-secret\",\"runtimeUsername\":"),
            raw.replaceFirst("\"jwt\":", "\"effectiveConfigurationHash\":\"${"a".repeat(64)}\",\"jwt\":"),
            raw.replaceFirst("\"jwt\":", "\"epochRotation\":false,\"jwt\":"),
            raw.replaceFirst("\"jwt\":", "\"livePolicy\":null,\"jwt\":"),
            raw.replaceFirst("\"jwt\":", "\"observedControl\":{},\"jwt\":"),
            raw.replaceFirst("\"runtimePassword\":{", "\"runtimePassword\":{\"family\":\"DATABASE\","),
            raw + " {}", "[]", "", raw.replaceFirst("\"catalog\":{", "\"catalog\":null,\"unusedCatalog\":{"),
        )
        invalid.forEach { refused(it.toByteArray()) }
        refused(byteArrayOf(0xc3.toByte(), 0x28))
        refused(ByteArray(ComplaintTestDeploymentJsonV1.MAX_BYTES + 1))
        val nullable = TestDeploymentInputFixture.document().copy(admission = TestDeploymentInputFixture.document().admission.copy(previousKey = null))
        val explicitNull = TestDeploymentInputFixture.bytes(nullable).decodeToString()
        assertTrue("\"previousKey\":null" in explicitNull)
        refused(explicitNull.replace("\"previousKey\":null,", "").toByteArray())
    }

    @Test
    fun `aliases reserved roots mismatched TEST identity key inventory and incomplete retention never reach acquisition`() {
        val d = TestDeploymentInputFixture.document()
        val invalid = listOf(
            d.copy(implementationSchema = 2), d.copy(desiredGeneration = 0),
            d.copy(dataScopeId = "00000000-0000-0000-0000-000000000000"),
            d.copy(dataScopeId = "99000000-0000-4000-8000-000000000001"),
            d.copy(database = d.database.copy(runtimeUsername = VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME)),
            d.copy(database = d.database.copy(runtimeUsername = VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME)),
            d.copy(database = d.database.copy(ordinaryCapacity = 1)),
            d.copy(database = d.database.copy(runtimePassword = d.database.runtimePassword.copy(versionId = "AWSCURRENT"))),
            d.copy(database = d.database.copy(runtimePassword = d.database.runtimePassword.copy(resourceArn = "alias/test-password"))),
            d.copy(jwt = d.jwt.copy(installationKeys = d.jwt.installationKeys + d.jwt.installationKeys.first())),
            d.copy(admission = d.admission.copy(declaredInstances = 2)), d.copy(admission = d.admission.copy(coordinationMode = "redis")),
            d.copy(journal = d.journal.copy(dataScopeKind = "LIVE")), d.copy(journal = d.journal.copy(ordinaryPrefix = "complaints/journal/v1/live/ordinary/")),
            d.copy(retention = d.retention.copy(hmacKeys = d.retention.hmacKeys.dropLast(1))),
            d.copy(retention = d.retention.copy(lastPreRunRestoreHorizon = "not-a-horizon")),
            d.copy(retention = d.retention.copy(acceptedRequestLateArrival = d.retention.acceptedRequestLateArrival.copy(maximumMillis = 60_001))),
            d.copy(retention = d.retention.copy(utcUncertainty = d.retention.utcUncertainty.copy(maximumMillis = -1))),
            d.copy(activation = d.activation.copy(signingKey = d.activation.signingKey.copy(publicKeySha256 = "b".repeat(64)))),
            d.copy(sealer = d.sealer.copy(catalogPut = d.sealer.catalogPut.copy(reference = d.sealer.catalogSign.reference))),
        )
        val http = AwsSecretVersionFixture()
        invalid.forEach { document -> TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient)
            val failure = assertThrows<ComplaintTestDeploymentExceptionV1> {
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
            }
            assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, failure.code)
            assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.target }
            assembly.close()
        } }
        assertEquals(0, http.createdClients)
        assertTrue(http.requests.isEmpty())
    }

    private fun refused(bytes: ByteArray) {
        val failure = assertThrows<ComplaintTestDeploymentExceptionV1> { ComplaintTestDeploymentJsonV1.parse(bytes) }
        assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, failure.code)
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("not-a-secret"))
    }
}

/** Plain synthetic input/HTTP data built from the existing fixtures, not another runtime or authority harness. */
internal object TestDeploymentInputFixture {
    fun document(journal: TestOwnerDeleteJournalConfigurationV1 = fullTestJournal()): ComplaintTestDeploymentDocumentV1 {
        val live = DesiredInstallationInputFixture.document("D6", 7)
        val d = journal.declaration()
        val database = live.database
        fun role(name: String, id: Char) = DesiredIamPrincipalInputV1("ROLE", "arn:aws:iam::123456789012:role/test-$name", "AROA" + id.toString().repeat(17))
        val sealer = checkNotNull(live.sealer).let { original -> original.copy(
            ordinaryPrincipal = role("ordinary", 'A'), sealTerminalPrincipal = role("epoch-sealer", 'B'), recoveryPrincipal = role("recovery", 'C'),
            catalogPut = original.catalogPut.copy(principal = role("catalog-put", 'D')),
            catalogSign = original.catalogSign.copy(principal = role("catalog-sign", 'E')),
            bootstrap = DesiredBootstrapOriginInputV1("test-bootstrap-origin", 1, "test-bootstrap-credential", role("bootstrap", 'F')),
            bootstrapSessionName = TestOrdinarySealHttpFixtureV1.SOURCE_SESSION,
            installedPolicyBundle = TestOrdinarySealHttpFixtureV1.policy("synthetic-test-installed-bundle"),
        ) }
        val spki = FullTestCatalogInputs.publicKeyBytes()
        val retention = TestOrdinarySealRetentionInputV1(
            checkNotNull(live.catalog).expectedEnvironment, journal.scope.id.toString(), d.writer.generationId,
            d.writer.databaseIdentity, d.writer.restoreIdentity, Instant.parse("2038-01-01T00:00:00Z").toString(),
            TestOrdinarySealHttpFixtureV1.policy("synthetic-test-restore-horizon"), TestOrdinarySealHttpFixtureV1.policy("synthetic-test-object-lock"),
            d.routing.keys.map { DesiredLiveHmacRetentionInputV1(
                DesiredSecretReferenceV1(it.keyId, it.secret.resourceArn, it.secret.versionId), TestOrdinarySealHttpFixtureV1.policy("test-hmac-${it.keyId}"),
            ) },
            listOf(d.encryption, d.recovery.queue.encryption, d.recovery.deadLetterQueue.encryption).distinct().map {
                DesiredLiveKmsRetentionInputV1(it, TestOrdinarySealHttpFixtureV1.policy("test-kms-${it.keyId}"))
            },
            DesiredLiveTimeBoundInputV1("test-late-arrival", TestOrdinarySealHttpFixtureV1.policy("test-late-arrival-policy"), 1000),
            DesiredLiveTimeBoundInputV1("test-utc-uncertainty", TestOrdinarySealHttpFixtureV1.policy("test-utc-policy"), 1000),
        )
        return ComplaintTestDeploymentDocumentV1(
            1, ComplaintTestDeploymentInputsV1.PROFILE, 1, 7, journal.scope.id.toString(), d.writer.databaseIdentity, d.writer.restoreIdentity,
            TestDatabaseInputV1(database.host, database.port, database.name, database.runtimeUsername, database.runtimePassword,
                database.ordinaryCapacity, database.publicTrustPemBase64, database.protectedTrustParent),
            live.jwt, live.capacity, live.admission, journal.document(), checkNotNull(live.catalog),
            TestActivationInputV1(DesiredCatalogSigningKeyInputV1("catalog-old", FullTestCatalogInputs.KEY_ARN,
                OfflineTrustBundleFixture.ALGORITHM, base64(spki), Sha256.hex(spki)), base64(FullTestCatalogInputs.registryBytes()), 30_000),
            sealer, retention,
        )
    }

    fun bytes(document: ComplaintTestDeploymentDocumentV1): ByteArray =
        Json.encodeToString(ComplaintTestDeploymentDocumentV1.serializer(), document).toByteArray()
    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun secrets(http: AwsSecretVersionFixture, document: ComplaintTestDeploymentDocumentV1,
        password: ByteArray = VersionBoundPersistenceTestInputs.PASSWORD.toByteArray()) {
        val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(document)
        val fixture = BoundTestComplaintConsumerFixture(inputs.journal)
        val hmac = with(fixture.base) { listOf(userSecret, current, previous) + installationSecrets + cursorSecrets } + fixture.routingSecrets
        http.respond = { request ->
            val fields = request.fields()
            val binding = inputs.allBindings().single {
                it.version.resourceArn == fields.getValue("SecretId") && it.version.versionId == fields.getValue("VersionId")
            }
            if (binding === inputs.runtimePassword) AwsSecretVersionFixture.reply(binding.version, password)
            else hmac.single { it.descriptor.family == binding.family && it.descriptor.logicalKeyId == binding.logicalKeyId }.useMaterial {
                AwsSecretVersionFixture.reply(binding.version, it)
            }
        }
    }

    fun <T> withManifest(bytes: ByteArray, action: (Path) -> T): T {
        val parent = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-test-intake-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val path = parent.resolve("test-deployment.json")
        try {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            return action(path)
        } finally {
            Files.deleteIfExists(path)
            Files.delete(parent)
        }
    }
}
