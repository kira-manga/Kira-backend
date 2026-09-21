package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointDeletionV1
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.fullTestJournal
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.jdbc.core.JdbcTemplate

/** Cold input/pool assertions only; neither local construction nor D equality is runtime admission. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestInitialCheckpointDeletionColdInputsV1Test {
    @Test fun optionalDeletionMemberPinsBothExistingPoolsAndRefusesLowerGraphsWithoutOpeningEither() {
        val scanner = scanner()
        val legacy = document(scanner)
        val absent = assembled(scanner, legacy) { process ->
            assertNull(process.initialCheckpointDeletion)
            process.pools.ordinary.requireTestInitialCheckpointDeletion(null)
            process.pools.deletion.requireTestInitialCheckpointDeletion(null)
            lower(process).requireUnchanged()
            val late = policy(process)
            assertThrows<Exception> { process.pools.retainTestInitialCheckpointDeletion(late) }
        }
        val present = assembled(scanner, legacy.copy(initialCheckpointDeletion = input())) { process ->
            val retained = checkNotNull(process.initialCheckpointDeletion)
            retained.requireRetained(process.pools, process.consumers.journalRouting, process.initialCheckpoint, process.activeCutoffPublication)
            process.pools.ordinary.requireTestInitialCheckpointDeletion(retained)
            process.pools.deletion.requireTestInitialCheckpointDeletion(retained)
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointDeletion(null) }
            assertThrows<Exception> { process.pools.deletion.requireTestInitialCheckpointDeletion(null) }
            assertThrows<Exception> { process.pools.retainTestInitialCheckpointDeletion(null) }
            val lookalike = policy(process)
            assertThrows<Exception> { process.pools.retainTestInitialCheckpointDeletion(lookalike) }
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointDeletion(lookalike) }
            assertThrows<Exception> { process.pools.deletion.requireTestInitialCheckpointDeletion(lookalike) }
            assertThrows<Exception> { lower(process) }
        }
        val json = Json.parseToJsonElement(present.decodeToString()).jsonObject
        assertEquals(VersionBoundTestInitialCheckpointDeletionV1.PROFILE,
            json.getValue("initialCheckpointDeletion").jsonObject.getValue("profile").jsonPrimitive.content)
        assertArrayEquals(absent, CanonicalJson.canonicalize(JsonObject(json - "initialCheckpointDeletion")).toByteArray())
        assertFalse(Json.parseToJsonElement(TestDeploymentInputFixture.bytes(legacy).decodeToString()).jsonObject.containsKey("initialCheckpointDeletion"))
    }

    @Test fun malformedDeletionRecipeAndMissingCheckpointOrOrdinaryRecipeRefuseBeforeSecretsOrProviders() {
        val scanner = scanner()
        val current = document(scanner).copy(initialCheckpointDeletion = input())
        val variants = listOf(
            current.copy(initialCheckpointDeletion = input().copy(schemaVersion = 2)),
            current.copy(initialCheckpointDeletion = input().copy(profile = "HEALTHY")),
            current.copy(initialCheckpointDeletion = input().copy(profile = "TEST_COLD_ACTIVE_DELETION")),
            current.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE, initialCheckpoint = null),
            current.copy(ordinaryPublication = null),
        )
        variants.forEach { document ->
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                    assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                        assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                            TestActiveFirstCutInputFixtureV1.ordinaryCredentials, if (document.initialCheckpoint == null) null else scanner.credentials)
                    }.code)
                }
            }
            assertEquals(0, http.createdClients)
            assertTrue(http.requests.isEmpty())
        }
    }

    private fun assembled(scanner: TestActiveInitialCheckpointHttpInputV1, document: ComplaintTestDeploymentDocumentV1,
        action: (VersionBoundTestNamespaceProcessV1) -> Unit): ByteArray {
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, document)
        return TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = { error("Premature sealer STS") }, kms = { error("Premature sealer KMS") }, s3 = { error("Premature sealer S3") },
                ordinarySts = { error("Premature ordinary STS") }, ordinaryKms = { error("Premature ordinary KMS") }, ordinaryS3 = { error("Premature ordinary S3") },
                scannerSts = scanner.sts, scannerKms = scanner.kms, scannerS3 = scanner.s3).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                    TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner.credentials)
                val process = assembly.target
                action(process)
                process.requireUnchangedConfiguration()
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                assertTrue(listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                assertEquals(http.createdClients, http.closedClients)
                assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
                process.canonicalBytes()
            }
        }
    }

    private fun lower(process: VersionBoundTestNamespaceProcessV1) = TestOwnerDeleteLocalGraphV1(
        JdbcTemplate(process.pools.ordinary), JdbcTemplate(process.pools.deletion), process.consumers.ingressAdmission,
        process.consumers.journalRouting, process.consumers.capacityPolicy, process.publicationLanes, process.desiredGeneration)

    private fun policy(process: VersionBoundTestNamespaceProcessV1) = VersionBoundTestInitialCheckpointDeletionV1.fromIndependentInputs(
        input(), process.pools, process.consumers.journalRouting, checkNotNull(process.initialCheckpoint), checkNotNull(process.activeCutoffPublication))

    private fun input() = TestInitialCheckpointDeletionInputV1(1, VersionBoundTestInitialCheckpointDeletionV1.PROFILE)
    private fun scanner() = TestActiveInitialCheckpointHttpInputV1(
        { error("Premature scanner STS") }, { error("Premature scanner KMS") }, { error("Premature scanner S3") })
    private fun document(scanner: TestActiveInitialCheckpointHttpInputV1): ComplaintTestDeploymentDocumentV1 {
        val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
        val base = TestDeploymentInputFixture.document(journal)
        return base.copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_CHECKPOINT_PROFILE,
            ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(journal, base.retention.environment),
            activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput(),
            initialCheckpoint = scanner.input)
    }
}
