package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.catalog.TestActiveSealRecoveryInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutSuccessorInputFixtureV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1
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

/** Source-authored cold graph tests. SDK clients, SQL participants and runtime actors must remain unopened. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestActiveInitialCheckpointColdInputsV1Test {
    @Test fun independentScannerRecipeAndReadPrincipalAreRetainedBeforeDWithoutOpeningAnyNativeOwner() {
        val scanner = coldScanner()
        assembled(scanner, document(scanner), "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_CHECKPOINT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER")
    }

    private fun assembled(scanner: TestActiveInitialCheckpointHttpInputV1, document: ComplaintTestDeploymentDocumentV1,
        expectedProfile: String): ByteArray {
        var canonical: ByteArray? = null
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, document)
        val sealSts: (() -> Int) -> SdkHttpClient = { error("Premature sealer STS") }
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient, sts = sealSts,
                kms = { error("Premature sealer KMS") }, s3 = { error("Premature sealer S3") },
                ordinarySts = { error("Premature ordinary STS") }, ordinaryKms = { error("Premature ordinary KMS") }, ordinaryS3 = { error("Premature ordinary S3") },
                scannerSts = scanner.sts, scannerKms = scanner.kms, scannerS3 = scanner.s3).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                    TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner.credentials)
                val process = assembly.target
                val owner = checkNotNull(process.initialCheckpoint)
                assertSame(owner, ownedCutField(assembly, "initialCheckpoint"))
                assertSame(scanner.sts, ownedCutField(owner, "sts")); assertSame(scanner.kms, ownedCutField(owner, "kms")); assertSame(scanner.s3, ownedCutField(owner, "s3"))
                assertSame(process.ordinarySeal, ownedCutField(owner, "retention"))
                assertSame(sealSts, ownedCutField(checkNotNull(process.ordinarySeal), "sts"))
                assertNotNull(process.activeFirstCut); assertNotNull(process.activeCutoffPublication)
                val d = Json.parseToJsonElement(process.canonicalBytes().decodeToString()).jsonObject
                canonical = process.canonicalBytes()
                assertEquals(expectedProfile, d.getValue("profile").jsonPrimitive.content)
                if (document.activeOrdinarySealRecovery == null) assertNull(process.activeOrdinarySealRecovery)
                else checkNotNull(process.activeOrdinarySealRecovery).requireRetained(process.pools, process.consumers.journalRouting,
                    process.activeFirstCut, process.ordinarySeal)
                if (document.activeFirstCutSuccessor == null) assertNull(process.activeFirstCutSuccessor)
                else checkNotNull(process.activeFirstCutSuccessor).requireRetained(process.pools, process.consumers.journalConfiguration,
                    process.activeFirstCut, process.ordinarySeal)
                val inventory = d.getValue("initialCheckpoint").jsonObject
                assertEquals("TEST_INITIAL_EMPTY_EPOCH1", inventory.getValue("profile").jsonPrimitive.content)
                assertEquals("4416", inventory.getValue("scanRowStorageBytes").jsonPrimitive.content)
                assertEquals("2", inventory.getValue("maximumScanRows").jsonPrimitive.content)
                assertEquals("0", inventory.getValue("maximumScanEntries").jsonPrimitive.content)
                assertTrue("activeFirstCut" in d && "activeCutoffPublication" in d && "ordinarySeal" in d)
                assertFalse(process.canonicalBytes().decodeToString().contains(TestActiveInitialCheckpointHttpInputV1.SESSION))
                assertFalse(process.canonicalBytes().decodeToString().contains(scanner.credentials.accessKeyId()))
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, checkNotNull(process.pools.epochRotation).observePreparation())
                assertTrue(listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                assertEquals(http.createdClients, http.closedClients)
                assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
            }
        }
        return checkNotNull(canonical)
    }

    @Test fun combinedRecoveryAndCheckpointRetainIndependentOwnersBeforeDAndCommitOptionalSuccessor() {
        val scanner = coldScanner()
        val old = document(scanner)
        val oldBytes = assembled(scanner, old, "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_CHECKPOINT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER")
        val combined = old.copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_RECOVERY_CHECKPOINT_PROFILE,
            activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.input())
        val expected = "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_RECOVERY_CHECKPOINT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER"
        val bytes = assembled(scanner, combined, expected)
        val withSuccessor = assembled(scanner, combined.copy(activeFirstCutSuccessor = TestActiveFirstCutSuccessorInputFixtureV1.input()), expected)
        val parsed = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val parsedSuccessor = Json.parseToJsonElement(withSuccessor.decodeToString()).jsonObject
        assertTrue("initialCheckpoint" in parsed && "activeOrdinarySealRecovery" in parsed)
        assertFalse("activeFirstCutSuccessor" in parsed)
        assertTrue("activeFirstCutSuccessor" in parsedSuccessor)
        assertFalse(bytes.contentEquals(withSuccessor), "Optional authority must change full D, not be retrofitted.")
        assertArrayEquals(bytes, CanonicalJson.canonicalize(JsonObject(parsedSuccessor - "activeFirstCutSuccessor")).toByteArray())
        val oldProfile = Json.parseToJsonElement(oldBytes.decodeToString()).jsonObject.getValue("profile")
        assertArrayEquals(oldBytes, CanonicalJson.canonicalize(JsonObject((parsed - "activeOrdinarySealRecovery") + ("profile" to oldProfile))).toByteArray(),
            "All old checkpoint resources, prices, capacities and omitted-option bytes remain unchanged.")
    }

    @Test fun combinedPartialRecipesAndSilentMixingIntoOldProfilesRefuseBeforeSecrets() {
        val scanner = coldScanner()
        val combined = document(scanner).copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_RECOVERY_CHECKPOINT_PROFILE,
            activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.input())
        listOf(combined.copy(initialCheckpoint = null), combined.copy(activeOrdinarySealRecovery = null),
            combined.copy(activeFirstCut = null), combined.copy(ordinaryPublication = null), combined.copy(ordinaryDenial = null),
            combined.copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_CHECKPOINT_PROFILE),
            combined.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_SEAL_RECOVERY_PROFILE),
            combined.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_SUCCESSOR_PROFILE,
                activeFirstCutSuccessor = TestActiveFirstCutSuccessorInputFixtureV1.input()),
            document(scanner).copy(activeFirstCutSuccessor = TestActiveFirstCutSuccessorInputFixtureV1.input())
        ).forEach { refusedBeforeSecrets(it, scanner.credentials) }
        refusedBeforeSecrets(combined, null)
        refusedBeforeSecrets(combined, AwsSecretVersionFixture.CREDENTIALS)
    }

    @Test fun absentOldProfileOmitsScannerAndMissingMismatchedOrAliasedInputsRefuseBeforeSecrets() {
        val scanner = coldScanner()
        val current = document(scanner)
        val old = current.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE, initialCheckpoint = null)
        assertNull(ComplaintTestDeploymentInputsV1.fromDecoded(old).initialCheckpoint)
        assertFalse("initialCheckpoint" in Json.parseToJsonElement(TestDeploymentInputFixture.bytes(old).decodeToString()).jsonObject)
        val invalid = listOf(current.copy(initialCheckpoint = null), current.copy(activeFirstCut = null),
            current.copy(ordinaryPublication = null), current.copy(ordinaryDenial = null),
            current.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE),
            current.copy(initialCheckpoint = scanner.input.copy(schemaVersion = 2)),
            current.copy(initialCheckpoint = scanner.input.copy(profile = "TEST_GENERAL_REPLAY")),
            current.copy(initialCheckpoint = scanner.input.copy(recoverySessionName = null)),
            current.copy(initialCheckpoint = scanner.input.copy(recoverySessionName = " wrong ")))
        invalid.forEach { input -> refusedBeforeSecrets(input, scanner.credentials) }
        refusedBeforeSecrets(current, null)
        refusedBeforeSecrets(current, AwsSecretVersionFixture.CREDENTIALS)
        refusedBeforeSecrets(current, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
        refusedBeforeSecrets(old, scanner.credentials)
    }

    private fun refusedBeforeSecrets(document: ComplaintTestDeploymentDocumentV1, scanner: software.amazon.awssdk.auth.credentials.AwsSessionCredentials?) {
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                    assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                        TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner)
                }.code)
            }
        }
        assertEquals(0, http.createdClients)
    }
    private fun coldScanner() = TestActiveInitialCheckpointHttpInputV1(
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
