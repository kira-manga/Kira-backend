package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutSuccessorInputFixtureV1
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

/** Source-authored only: no JDBC/provider startup; actual immutable secret SDK fixture is retained. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestActiveFirstCutSuccessorColdInputsV1Test {
    @Test
    fun `reserved successor is a separate pre D profile sharing unchanged C native resource charge and deadlines`() {
        val document = successorDocument()
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, document)
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = { error("Premature seal STS") }, kms = { error("Premature seal KMS") }, s3 = { error("Premature seal S3") },
                ordinarySts = { error("Premature ordinary STS") }, ordinaryKms = { error("Premature ordinary KMS") },
                ordinaryS3 = { error("Premature ordinary S3") }).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                    TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
                val process = assembly.target
                val original = checkNotNull(process.activeFirstCut)
                val successor = checkNotNull(process.activeFirstCutSuccessor)
                val native = checkNotNull(process.pools.epochRotation)
                assertSame(original.resource, successor.resource)
                assertSame(native, successor.resource)
                assertEquals(original.totalAttemptMillis, successor.totalAttemptMillis)
                assertEquals(30_000L, successor.scopedLeaseMillis)
                assertEquals(1, native.descriptor().capacity)
                assertFalse(native.descriptor().pooled)
                assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, native.observePreparation())
                assertNotNull(process.activeCutoffPublication)
                assertTrue(process.consumers.journalConfiguration.registeredAdminBatchDelete)
                val d = Json.parseToJsonElement(process.canonicalBytes().decodeToString()).jsonObject
                assertEquals("PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_ACTIVE_FIRST_CUT_RESERVED_RECOVERY_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER",
                    d.getValue("profile").jsonPrimitive.content)
                val inventory = d.getValue("activeFirstCutSuccessor").jsonObject
                assertEquals("TEST_ACTIVE_FIRST_CUT_RESERVED_SUCCESSOR_V1", inventory.getValue("profile").jsonPrimitive.content)
                assertEquals("2097152", inventory.getValue("chargedStorageBytes").jsonPrimitive.content)
                assertEquals("0", inventory.getValue("counterDelta").jsonPrimitive.content)
                assertEquals("0", inventory.getValue("reserveDelta").jsonPrimitive.content)
                assertEquals("CANONICAL_AND_WIRE_REFUSED", inventory.getValue("laterStates").jsonPrimitive.content)
                assertTrue("activeFirstCut" in d && "activeCutoffPublication" in d)
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                assertTrue(listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
                assertEquals(http.createdClients, http.closedClients)
            }
        }
    }

    @Test
    fun `absent successor retains old recipes and mismatched successor combinations fail before acquisition`() {
        val old = TestDeploymentInputFixture.document()
        val successor = successorDocument()
        val original = successor.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE, activeFirstCutSuccessor = null)
        listOf(old, original).forEach { prior ->
            assertFalse("activeFirstCutSuccessor" in Json.parseToJsonElement(TestDeploymentInputFixture.bytes(prior).decodeToString()).jsonObject)
            assertNull(ComplaintTestDeploymentInputsV1.fromDecoded(prior).activeFirstCutSuccessor)
        }
        val invalid = listOf(
            successor.copy(activeFirstCutSuccessor = null), successor.copy(activeFirstCut = null),
            successor.copy(ordinaryPublication = null), successor.copy(ordinaryDenial = null),
            successor.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE),
            successor.copy(profile = ComplaintTestDeploymentInputsV1.ADMIN_ERASURE_DRAIN_PROFILE),
            successor.copy(journal = fullTestJournal(ownerDeleteAll = true).document()),
            successor.copy(activeFirstCutSuccessor = TestActiveFirstCutSuccessorInputFixtureV1.input().copy(schemaVersion = 2)),
            successor.copy(activeFirstCutSuccessor = TestActiveFirstCutSuccessorInputFixtureV1.input().copy(profile = "TEST_ACTIVE_FIRST_CUT_V1")),
            old.copy(activeFirstCutSuccessor = TestActiveFirstCutSuccessorInputFixtureV1.input()),
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
    }

    private fun successorDocument(): ComplaintTestDeploymentDocumentV1 {
        val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
        val base = TestDeploymentInputFixture.document(journal)
        return base.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_SUCCESSOR_PROFILE,
            ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(journal, base.retention.environment),
            activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), activeFirstCutSuccessor = TestActiveFirstCutSuccessorInputFixtureV1.input(),
            ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput())
    }
}
