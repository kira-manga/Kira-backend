package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.FullTestCatalogInputs
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDenialAuthorityPolicyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDenialInputFixtureV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** Real protected-file/immutable-secret/cold-process intake, never registration or denial admission. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class ComplaintTestOrdinaryDenialIntakeV1Test {
    @Test
    fun `optional absence and explicit null remain legacy only while drain requires a complete policy`() {
        val legacy = TestDeploymentInputFixture.document()
        val legacyBytes = TestDeploymentInputFixture.bytes(legacy)
        val root = Json.parseToJsonElement(legacyBytes.decodeToString()).jsonObject
        val legacyInputs = ComplaintTestDeploymentJsonV1.parse(legacyBytes)
        assertFalse("ordinaryDenial" in root)
        assertNull(legacyInputs.ordinaryDenial)
        assertNull(ComplaintTestDeploymentJsonV1.parse(wire(JsonObject(root + ("ordinaryDenial" to JsonNull)))).ordinaryDenial)
        val drain = drainDocument()
        val inputs = ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(drain))
        assertNotNull(inputs.ordinaryDenial)
        assertArrayEquals(legacyInputs.journal.canonicalBytes(), inputs.journal.canonicalBytes())
        fun references(value: ComplaintTestDeploymentInputsV1): List<List<String>> = value.allBindings().map {
            listOf(it.family.name, it.purpose.name, it.logicalKeyId, it.version.resourceArn, it.version.versionId)
        }
        assertEquals(references(legacyInputs), references(inputs))
        val drainRoot = Json.parseToJsonElement(TestDeploymentInputFixture.bytes(drain).decodeToString()).jsonObject
        refused(wire(JsonObject(drainRoot - "ordinaryDenial")))
        refused(wire(JsonObject(drainRoot + ("ordinaryDenial" to JsonNull))))
        assertThrows<ComplaintDesiredInstallationExceptionV1> { ComplaintDesiredDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(drain)) }
    }

    @Test
    fun `wrong profile purpose or journal context is rejected before any immutable secret acquisition`() {
        val document = drainDocument()
        val input = checkNotNull(document.ordinaryDenial)
        val anotherId = "99000000-0000-4000-8000-000000000001"
        val wrongPins = listOf(
            input.copy(environment = "other-synthetic-test"), input.copy(dataScopeId = anotherId),
            input.copy(writerGeneration = anotherId), input.copy(databaseIdentity = anotherId), input.copy(restoreIdentity = anotherId),
            input.copy(bucket = "other-synthetic-journal"), input.copy(accountId = "987654321012"), input.copy(region = "us-west-2"),
            input.copy(purpose = "CATALOG_SIGNING"), input.copy(runEnvelopeAccounting = "PAY_AT_PURGED"),
            input.copy(publicKeySha256 = "0".repeat(64)), input.copy(minimumApprovalVersion = 0),
            input.copy(authorityGrant = input.authorityGrant.copy(version = 0)),
        )
        val invalid = wrongPins.map { document.copy(ordinaryDenial = it) } + listOf(
            document.copy(profile = ComplaintTestDeploymentInputsV1.PROFILE), document.copy(ordinaryDenial = null),
            document.copy(profile = "D6"), document.copy(profile = "D7"), document.copy(profile = "LIVE"),
            document.copy(profile = "PRE_CUTOVER_TEST_ORDINARY_DRAIN_V2"),
            // The composed ALL recipe cannot silently widen the current DELETE-only drain.
            document.copy(profile = ComplaintTestDeploymentInputsV1.OWNER_ERASURE_PROFILE),
            document.copy(journal = document.journal.copy(profile = "REGISTERED_TEST_OWNER_ERASURE")),
            document.copy(profile = ComplaintTestDeploymentInputsV1.OWNER_ERASURE_PROFILE,
                journal = document.journal.copy(profile = "REGISTERED_TEST_OWNER_ERASURE")),
        )
        val http = AwsSecretVersionFixture()
        invalid.forEach { changed ->
            TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(changed)) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                    sts = { error("premature STS") }, kms = { error("premature KMS") }, s3 = { error("premature S3") }).use { assembly ->
                    val failure = assertThrows<ComplaintTestDeploymentExceptionV1> {
                        assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                    }
                    assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, failure.code)
                    assertNull(failure.cause)
                    assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.target }
                }
            }
        }
        assertEquals(0, http.createdClients)
        assertEquals(0, http.closedClients)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `ordinary denial input remains closed required and duplicate rejecting including nested independent grants`() {
        val document = drainDocument()
        val text = TestDeploymentInputFixture.bytes(document).decodeToString()
        val root = Json.parseToJsonElement(text).jsonObject
        val input = root.getValue("ordinaryDenial").jsonObject
        input.keys.forEach { field -> refused(wire(JsonObject(root + ("ordinaryDenial" to JsonObject(input - field))))) }
        for (field in listOf("authorityGrant", "implementationAcceptance", "evidenceRetentionPolicy")) {
            val reference = input.getValue(field).jsonObject
            reference.keys.forEach { key ->
                val changed = JsonObject(input + (field to JsonObject(reference - key)))
                refused(wire(JsonObject(root + ("ordinaryDenial" to changed))))
            }
        }
        val unknown = JsonObject(input + ("admittedEvidence" to JsonPrimitive("not-an-authority")))
        refused(wire(JsonObject(root + ("ordinaryDenial" to unknown))))
        refused(text.replaceFirst("\"ordinaryDenial\":{", "\"ordinaryDenial\":{\"purpose\":\"other\",").toByteArray())
        refused(text.replaceFirst("\"minimumApprovalVersion\":1", "\"minimumApprovalVersion\":1,\"\\u006dinimumApprovalVersion\":1").toByteArray())
        refused(text.replaceFirst("\"authorityGrant\":{", "\"authorityGrant\":{\"observedPolicy\":\"not-a-proof\",").toByteArray())
    }

    @Test
    fun `real assembly retains the independent purpose policy in full D before any runtime owner starts`() {
        val document = drainDocument()
        withAssembled(document) { assembly ->
            val target = assembly.target
            val inputs = ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(document))
            val policy = checkNotNull(target.ordinaryDenial)
            assertEquals(checkNotNull(inputs.ordinaryDenial).inventory(), policy.inventory())
            assertEquals(policy.inventory(), encoded(target).getValue("ordinaryDenial"))
            assertNotNull(target.ordinarySeal)
            assertSame(target.pools, assembly.lifecycleOwner.versionBoundPools)
            assertArrayEquals(inputs.journal.canonicalBytes(), target.consumers.journalConfiguration.canonicalBytes())
            target.requireRegistrationTarget() // Local owner check only, not activation/registration.
            val bytes = target.canonicalBytes()
            val hash = target.configurationHashBytes()
            target.canonicalBytes().fill(0)
            target.configurationHashBytes().fill(0)
            assertArrayEquals(bytes, target.canonicalBytes())
            assertArrayEquals(hash, target.configurationHashBytes())
        }
    }

    @Test
    fun `absent denial owner preserves both historical golden bytes and the existing retained seal preimage`() {
        withAssembled(TestDeploymentInputFixture.document()) { assembly ->
            val legacy = assembly.target
            assertNull(legacy.ordinaryDenial)
            assertFalse("ordinaryDenial" in encoded(legacy))
            val noSeal = retained(legacy, ordinarySeal = null, ordinaryDenial = null)
            assertArrayEquals(FullTestCatalogInputs.goldenBytes(false), noSeal.canonicalBytes())
            val expectedLegacy = JsonObject(FullTestCatalogInputs.goldenDocument(false) + ("ordinarySeal" to checkNotNull(legacy.ordinarySeal).inventory()))
            assertArrayEquals(CanonicalJson.canonicalize(expectedLegacy).toByteArray(Charsets.UTF_8), legacy.canonicalBytes())
            val explicitNull = retained(legacy, ordinaryDenial = null)
            assertArrayEquals(legacy.canonicalBytes(), explicitNull.canonicalBytes())
            assertArrayEquals(legacy.configurationHashBytes(), explicitNull.configurationHashBytes())
        }
    }

    @Test
    fun `changed independent pins change D on the same cold owners and through a fresh actual assembly`() {
        val document = drainDocument()
        val input = checkNotNull(document.ordinaryDenial)
        val changedGrant = input.copy(authorityGrant = input.authorityGrant.copy(version = 2))
        val originalHash = withAssembled(document) { assembly ->
            val original = assembly.target
            val bytes = original.canonicalBytes()
            val hash = original.configurationHashBytes()
            val replacement = TestOrdinaryDenialInputFixtureV1.input(spki = OfflineTrustBundleFixture.secondSigner.public.encoded)
            val changes = listOf(changedGrant, replacement, input.copy(keyId = "synthetic-ordinary-denial-2"), input.copy(minimumApprovalVersion = 2),
                input.copy(implementationAcceptance = input.implementationAcceptance.copy(version = 2)),
                input.copy(evidenceRetentionPolicy = input.evidenceRetentionPolicy.copy(version = 2)))
            changes.forEach { changed ->
                val parsed = ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(document.copy(ordinaryDenial = changed)))
                val policy = checkNotNull(parsed.ordinaryDenial)
                val next = retained(original, ordinaryDenial = policy)
                assertSame(policy, next.ordinaryDenial)
                assertSame(original.pools, next.pools)
                assertSame(original.consumers, next.consumers)
                assertSame(original.ordinarySeal, next.ordinarySeal)
                assertFalse(hash.contentEquals(next.configurationHashBytes()))
                assertEquals(encoded(original).filterKeys { it != "ordinaryDenial" }, encoded(next).filterKeys { it != "ordinaryDenial" })
                assertEquals(policy.inventory(), encoded(next).getValue("ordinaryDenial"))
            }
            assertArrayEquals(bytes, original.canonicalBytes())
            assertArrayEquals(hash, original.configurationHashBytes())
            hash
        }
        val changedHash = withAssembled(document.copy(ordinaryDenial = changedGrant)) { it.target.configurationHashBytes() }
        assertFalse(originalHash.contentEquals(changedHash))
    }

    @Test
    fun `retained process independently refuses a denial pin without a seal or with a foreign context`() {
        withAssembled(drainDocument()) { assembly ->
            val target = assembly.target
            val original = target.canonicalBytes()
            assertThrows<IllegalArgumentException> { retained(target, ordinarySeal = null) }
            val input = TestOrdinaryDenialInputFixtureV1.input()
            val anotherId = "99000000-0000-4000-8000-000000000001"
            listOf(input.copy(environment = "other-synthetic-test"), input.copy(dataScopeId = anotherId),
                input.copy(writerGeneration = anotherId), input.copy(databaseIdentity = anotherId), input.copy(restoreIdentity = anotherId),
                input.copy(bucket = "other-synthetic-journal"), input.copy(accountId = "987654321012"), input.copy(region = "us-west-2")).forEach { changed ->
                val policy = TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(changed)
                assertThrows<IllegalArgumentException> { retained(target, ordinaryDenial = policy) }
            }
            target.requireUnchangedConfiguration()
            assertArrayEquals(original, target.canonicalBytes())
        }
    }

    private fun drainDocument(): ComplaintTestDeploymentDocumentV1 {
        val document = TestDeploymentInputFixture.document()
        val journal = TestOwnerDeleteJournalConfigurationV1.fromDocument(document.journal)
        return document.copy(profile = ComplaintTestDeploymentInputsV1.DRAIN_PROFILE,
            ordinaryDenial = TestOrdinaryDenialInputFixtureV1.input(journal).copy(environment = document.retention.environment))
    }

    private fun retained(
        original: VersionBoundTestNamespaceProcessV1,
        ordinarySeal: VersionBoundTestOrdinarySealV1? = original.ordinarySeal,
        ordinaryDenial: TestOrdinaryDenialAuthorityPolicyV1? = original.ordinaryDenial,
    ): VersionBoundTestNamespaceProcessV1 = VersionBoundTestNamespaceProcessV1.fromRetained(
        original.consumers, original.pools, original.implementationSchema, original.desiredGeneration,
        original.databaseIdentity, original.restoreIdentity, original.publicationLanes, original.catalogReadback, original.catalogActivation,
        ordinarySeal, ordinaryDenial,
    )

    private fun <T> withAssembled(document: ComplaintTestDeploymentDocumentV1, action: (ComplaintTestProcessAssemblyV1) -> T): T {
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, document)
        return TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            val result = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = { error("premature STS") }, kms = { error("premature KMS") }, s3 = { error("premature S3") }).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                val target = assembly.target
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                val value = action(assembly)
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                listOf(target.pools.ordinary, target.pools.deletion, target.pools.catalogCoordinator.dataSource).forEach {
                    assertFalse(actualPool(it).isRunning)
                }
                requireConnectionFree()
                value
            }
            assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
            assertEquals(http.createdClients, http.closedClients)
            assertTrue(http.requests.all { it.fields().keys == setOf("SecretId", "VersionId") })
            result
        }
    }

    private fun encoded(target: VersionBoundTestNamespaceProcessV1): JsonObject = Json.parseToJsonElement(target.canonicalBytes().decodeToString()).jsonObject
    private fun wire(value: JsonObject): ByteArray = value.toString().toByteArray(Charsets.UTF_8)
    private fun refused(bytes: ByteArray) {
        val failure = assertThrows<ComplaintTestDeploymentExceptionV1> { ComplaintTestDeploymentJsonV1.parse(bytes) }
        assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, failure.code)
        assertEquals("TEST deployment intake refused.", failure.message)
        assertNull(failure.cause)
    }
}
