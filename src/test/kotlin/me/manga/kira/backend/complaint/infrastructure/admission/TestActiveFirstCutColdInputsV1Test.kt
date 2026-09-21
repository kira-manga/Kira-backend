package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.fullTestJournal
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
import software.amazon.awssdk.http.SdkHttpClient

/** Authored tests use existing raw SDK fixtures; actual STS/KMS/S3 and every runtime participant must stay cold. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestActiveFirstCutColdInputsV1Test {
    @Test
    fun `sole active profile binds batch J real same root nonpooled descriptor and independent ordinary owner before D`() {
        val document = activeDocument()
        val http = AwsSecretVersionFixture()
        val sealSts: (() -> Int) -> SdkHttpClient = { error("Premature seal STS") }
        val sealKms: (() -> Int) -> SdkHttpClient = { error("Premature seal KMS") }
        val sealS3: (() -> Int) -> SdkHttpClient = { error("Premature seal S3") }
        val ordinary = TestActiveOrdinaryRawHttpV1(
            sts = { error("Premature ordinary STS") }, kms = { error("Premature ordinary KMS") }, s3 = { error("Premature ordinary S3") },
        )
        TestDeploymentInputFixture.secrets(http, document)
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = sealSts, kms = sealKms, s3 = sealS3,
                ordinarySts = ordinary.sts, ordinaryKms = ordinary.kms, ordinaryS3 = ordinary.s3).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                    TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
                val process = assembly.target
                val resource = checkNotNull(process.pools.epochRotation)
                assertSame(resource, assembly.lifecycleOwner.epochRotation)
                assertTrue(resource.belongsTo(process.pools))
                assertEquals(1, resource.descriptor().capacity)
                assertFalse(resource.descriptor().pooled)
                assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, resource.observePreparation())
                assertNotNull(process.activeFirstCut)
                assertSame(process.activeCutoffPublication, ownedCutField(assembly, "activePublication"))
                val ordinaryOwner = checkNotNull(process.activeCutoffPublication)
                val sealOwner = checkNotNull(process.ordinarySeal)
                assertSame(ordinary.sts, ownedCutField(ordinaryOwner, "sts"))
                assertSame(ordinary.kms, ownedCutField(ordinaryOwner, "kms"))
                assertSame(ordinary.s3, ownedCutField(ordinaryOwner, "s3"))
                assertSame(sealSts, ownedCutField(sealOwner, "sts"))
                assertSame(sealKms, ownedCutField(sealOwner, "kms"))
                assertSame(sealS3, ownedCutField(sealOwner, "s3"))
                assertTrue(process.consumers.journalConfiguration.registeredAdminBatchDelete)
                assertTrue(process.consumers.journalConfiguration.registeredAdminDelete)
                assertTrue(process.consumers.journalConfiguration.ownerDeleteAll)
                val d = Json.parseToJsonElement(process.canonicalBytes().decodeToString()).jsonObject
                assertEquals("PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_ACTIVE_FIRST_CUT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER",
                    d.getValue("profile").jsonPrimitive.content)
                val inventory = d.getValue("activeFirstCut").jsonObject
                assertEquals("2097152", inventory.getValue("storage").jsonObject.getValue("chargedBytes").jsonPrimitive.content)
                assertEquals("0", inventory.getValue("storage").jsonObject.getValue("reserveDelta").jsonPrimitive.content)
                assertEquals("false", inventory.getValue("nonpooledCapture").jsonObject.getValue("pooled").jsonPrimitive.content)
                assertTrue("activeCutoffPublication" in d)
                assertFalse(process.canonicalBytes().decodeToString().contains(TestActiveFirstCutInputFixtureV1.ORDINARY_SESSION))
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                assertTrue(listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
                assertEquals(http.createdClients, http.closedClients)
            }
        }
    }

    @Test
    fun `old absent input omits every new field and active combinations fail before any immutable acquisition`() {
        val old = TestDeploymentInputFixture.document()
        val oldJson = Json.parseToJsonElement(TestDeploymentInputFixture.bytes(old).decodeToString()).jsonObject
        assertFalse("activeFirstCut" in oldJson || "ordinaryPublication" in oldJson)
        assertNull(ComplaintTestDeploymentInputsV1.fromDecoded(old).activeFirstCut)
        val active = activeDocument()
        val invalid = listOf(
            active.copy(activeFirstCut = null), active.copy(ordinaryPublication = null), active.copy(ordinaryDenial = null),
            active.copy(profile = ComplaintTestDeploymentInputsV1.ADMIN_ERASURE_DRAIN_PROFILE),
            active.copy(journal = fullTestJournal(ownerDeleteAll = true).document()),
            active.copy(activeFirstCut = TestActiveFirstCutInputFixtureV1.input().copy(schemaVersion = 2)),
            active.copy(ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput().copy(sessionName = null)),
            active.copy(ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput().copy(sessionName = " invalid ")),
            old.copy(activeFirstCut = TestActiveFirstCutInputFixtureV1.input()),
        )
        invalid.forEach { document ->
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                    assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                        assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                            TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
                    }.code)
                }
            }
            assertEquals(0, http.createdClients)
        }
        for (credentials in listOf(null, AwsSecretVersionFixture.CREDENTIALS)) {
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(active)) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                    assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                        assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS, credentials)
                    }.code)
                }
            }
            assertEquals(0, http.createdClients, "Missing/aliased ordinary credential source refuses before secrets.")
        }
    }

    private fun activeDocument(): ComplaintTestDeploymentDocumentV1 {
        val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
        val base = TestDeploymentInputFixture.document(journal)
        return base.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE,
            ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(journal, base.retention.environment),
            activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput())
    }
}
