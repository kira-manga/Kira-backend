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
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1
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

/** SOURCE ONLY. Cold local configuration and raw fixture secret intake are not registered/current authority. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestInitialCheckpointCreateColdInputsV1Test {
    @Test fun explicitCurrentCreateRecipeChangesOnlyItsOptionalFullDMemberAndPinsTheActualPoolBeforeOpening() {
        val scanner = scanner()
        val legacy = document(scanner)
        val oldBytes = assembled(scanner, legacy) { process ->
            assertNull(process.initialCheckpointCreate)
            process.pools.ordinary.requireTestInitialCheckpointCreate(null)
            val policy = VersionBoundTestInitialCheckpointCreateV1.fromIndependentInputs(input(), process.pools,
                process.consumers.journalRouting, checkNotNull(process.initialCheckpoint))
            assertThrows<Exception> { process.pools.retainTestInitialCheckpointCreate(policy) }
        }
        val bytes = assembled(scanner, legacy.copy(initialCheckpointCreate = input())) { process ->
            val policy = checkNotNull(process.initialCheckpointCreate)
            policy.requireRetained(process.pools, process.consumers.journalRouting, process.initialCheckpoint)
            process.pools.ordinary.requireTestInitialCheckpointCreate(policy)
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointCreate(null) }
            val lookalike = VersionBoundTestInitialCheckpointCreateV1.fromIndependentInputs(input(), process.pools,
                process.consumers.journalRouting, checkNotNull(process.initialCheckpoint))
            assertThrows<Exception> { process.pools.retainTestInitialCheckpointCreate(lookalike) }
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointCreate(lookalike) }
        }
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(VersionBoundTestInitialCheckpointCreateV1.PROFILE, json.getValue("initialCheckpointCreate").jsonObject.getValue("profile").jsonPrimitive.content)
        assertArrayEquals(oldBytes, CanonicalJson.canonicalize(JsonObject(json - "initialCheckpointCreate")).toByteArray())
        assertFalse(Json.parseToJsonElement(TestDeploymentInputFixture.bytes(legacy).decodeToString()).jsonObject.containsKey("initialCheckpointCreate"))
    }

    @Test fun missingInitialReaderAndUnknownCreatePolicyRefuseBeforeAnySecretOrResourceAcquisition() {
        val scanner = scanner()
        val current = document(scanner).copy(initialCheckpointCreate = input())
        val variants = listOf(
            current.copy(initialCheckpointCreate = input().copy(schemaVersion = 2)),
            current.copy(initialCheckpointCreate = input().copy(profile = "HEALTHY")),
            current.copy(initialCheckpointCreate = input().copy(profile = "OWNER_REPLY")),
            current.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE, initialCheckpoint = null),
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
        }
    }

    @Test fun replySelectionHasSeparateBornWithFullDProvenanceAndCannotUpgradeTheCreateOnlyPoolPin() {
        val scanner = scanner()
        val create = document(scanner).copy(initialCheckpointCreate = input())
        val oldBytes = assembled(scanner, create) { process ->
            val old = checkNotNull(process.initialCheckpointCreate)
            assertThrows<IllegalStateException> { old.requireReplies() }
            assertEquals("OWNER_CREATE_ONLY_INITIAL_EPOCH2_CAPTURED_FULL_CURRENT_CHECKPOINT", old.inventory().getValue("newWork").jsonPrimitive.content)
            assertEquals("BORN_WITH_NO_DESIRED_ONLY_OR_REPLY_BYPASS", old.inventory().getValue("poolPolicy").jsonPrimitive.content)
            val reply = VersionBoundTestInitialCheckpointCreateV1.fromIndependentInputs(input().copy(profile = VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE),
                process.pools, process.consumers.journalRouting, checkNotNull(process.initialCheckpoint))
            assertThrows<Exception> { process.pools.retainTestInitialCheckpointCreate(reply) }
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointCreate(reply) }
        }
        val bytes = assembled(scanner, create.copy(initialCheckpointCreate = input().copy(profile = VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE))) { process ->
            val reply = checkNotNull(process.initialCheckpointCreate)
            reply.requireReplies()
            process.pools.ordinary.requireTestInitialCheckpointCreate(reply)
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointCreate(null) }
        }
        val old = Json.parseToJsonElement(oldBytes.decodeToString()).jsonObject
        val current = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(VersionBoundTestInitialCheckpointCreateV1.PROFILE, old.getValue("initialCheckpointCreate").jsonObject.getValue("profile").jsonPrimitive.content)
        assertEquals(VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE, current.getValue("initialCheckpointCreate").jsonObject.getValue("profile").jsonPrimitive.content)
        assertFalse(oldBytes.contentEquals(bytes))
        assertArrayEquals(CanonicalJson.canonicalize(JsonObject(old - "initialCheckpointCreate")).toByteArray(),
            CanonicalJson.canonicalize(JsonObject(current - "initialCheckpointCreate")).toByteArray())
    }

    @Test fun editSelectionHasSeparateBornWithProvenanceAndNeitherOlderProfileCanUpgradeItsPoolPin() {
        val scanner = scanner()
        val base = document(scanner)
        val older = listOf(VersionBoundTestInitialCheckpointCreateV1.PROFILE, VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE).map { profile ->
            assembled(scanner, base.copy(initialCheckpointCreate = input().copy(profile = profile))) { process ->
                val policy = checkNotNull(process.initialCheckpointCreate)
                assertThrows<IllegalStateException> { policy.requireEdits() }
                assertEquals(profile, policy.inventory().getValue("profile").jsonPrimitive.content)
                val newer = VersionBoundTestInitialCheckpointCreateV1.fromIndependentInputs(input().copy(profile = VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE),
                    process.pools, process.consumers.journalRouting, checkNotNull(process.initialCheckpoint))
                assertThrows<Exception> { process.pools.retainTestInitialCheckpointCreate(newer) }
                assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointCreate(newer) }
            }
        }
        val bytes = assembled(scanner, base.copy(initialCheckpointCreate = input().copy(profile = VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE))) { process ->
            val policy = checkNotNull(process.initialCheckpointCreate)
            policy.requireEdits(); policy.requireReplies()
            process.pools.ordinary.requireTestInitialCheckpointCreate(policy)
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointCreate(null) }
        }
        val current = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE, current.getValue("initialCheckpointCreate").jsonObject.getValue("profile").jsonPrimitive.content)
        older.forEach { original ->
            assertFalse(original.contentEquals(bytes))
            assertArrayEquals(CanonicalJson.canonicalize(JsonObject(Json.parseToJsonElement(original.decodeToString()).jsonObject - "initialCheckpointCreate")).toByteArray(),
                CanonicalJson.canonicalize(JsonObject(current - "initialCheckpointCreate")).toByteArray())
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
                action(process); process.requireUnchangedConfiguration()
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                assertTrue(listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                assertEquals(http.createdClients, http.closedClients)
                assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
                process.canonicalBytes()
            }
        }
    }

    private fun input() = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE)
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
