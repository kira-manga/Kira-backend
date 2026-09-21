package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveRecurrentV1
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
        val recurrent = current.copy(initialCheckpointDeletion = recurrentDeletionInput(), activeRecurrent = recurrentInput())
        val variants = listOf(
            current.copy(initialCheckpointDeletion = input().copy(schemaVersion = 2)),
            current.copy(initialCheckpointDeletion = input().copy(profile = "HEALTHY")),
            current.copy(initialCheckpointDeletion = input().copy(profile = "TEST_COLD_ACTIVE_DELETION")),
            current.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE, initialCheckpoint = null),
            current.copy(ordinaryPublication = null),
            recurrent.copy(activeRecurrent = null),
            recurrent.copy(activeRecurrent = recurrentInput().copy(schemaVersion = 2)),
            recurrent.copy(activeRecurrent = recurrentInput().copy(profile = "HEALTHY")),
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

    @Test fun theOldInitialDeletionProfileKeepsLiteralInventoryAndPoolPinsWithAnOptionalRecurrentRecipe() {
        val scanner = scanner()
        val base = document(scanner).copy(initialCheckpointDeletion = input())
        val old = assembled(scanner, base) { process -> assertInventory(process, initialInventory(process)) }
        val withRecurrent = assembled(scanner, base.copy(activeRecurrent = recurrentInput())) { process ->
            val retained = checkNotNull(process.initialCheckpointDeletion)
            retained.requireRetained(process.pools, process.consumers.journalRouting, process.initialCheckpoint, process.activeCutoffPublication,
                process.activeRecurrent)
            assertInventory(process, initialInventory(process))
            val lookalike = policy(process)
            assertEquals(retained.inventory(), lookalike.inventory())
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointDeletion(lookalike) }
            assertThrows<Exception> { process.pools.deletion.requireTestInitialCheckpointDeletion(lookalike) }
        }
        val oldJson = Json.parseToJsonElement(old.decodeToString()).jsonObject
        val extra = Json.parseToJsonElement(withRecurrent.decodeToString()).jsonObject
        assertEquals("PRE_CUTOVER_TEST_ACTIVE_RECURRENT_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER", extra.getValue("profile").jsonPrimitive.content)
        assertArrayEquals(old, CanonicalJson.canonicalize(JsonObject(
            (extra - "activeRecurrent") + ("profile" to oldJson.getValue("profile")))).toByteArray())
    }

    @Test fun recurrentDeletionSelectionPinsTheExactRecurrentReaderAndBothPoolsBeforeAnyResourceOpens() {
        val scanner = scanner()
        val base = document(scanner).copy(activeRecurrent = recurrentInput())
        val old = assembled(scanner, base.copy(initialCheckpointDeletion = input())) {}
        val bytes = assembled(scanner, base.copy(initialCheckpointDeletion = recurrentDeletionInput())) { process ->
            val checkpoint = checkNotNull(process.initialCheckpoint)
            val ordinary = checkNotNull(process.activeCutoffPublication)
            val recurrent = checkNotNull(process.activeRecurrent)
            val retained = checkNotNull(process.initialCheckpointDeletion)
            retained.requireRetained(process.pools, process.consumers.journalRouting, checkpoint, ordinary, recurrent)
            process.pools.ordinary.requireTestInitialCheckpointDeletion(retained)
            process.pools.deletion.requireTestInitialCheckpointDeletion(retained)
            assertThrows<Exception> { retained.requireRetained(process.pools, process.consumers.journalRouting, checkpoint, ordinary) }
            assertThrows<Exception> { VersionBoundTestInitialCheckpointDeletionV1.fromIndependentInputs(recurrentDeletionInput(), process.pools,
                process.consumers.journalRouting, checkpoint, ordinary) }
            assertThrows<Exception> { lower(process) }
            assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointDeletion(null) }
            assertThrows<Exception> { process.pools.deletion.requireTestInitialCheckpointDeletion(null) }
            val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(base)
            VersionBoundTestActiveRecurrentV1.fromIndependentInputs(recurrentInput(), process.consumers.journalRouting, process.pools,
                checkNotNull(process.ordinarySeal), inputs.sealerMapping, scanner.credentials, inputs.sealerLimits,
                recurrent.clock, recurrent.nanoTime, scanner.sts, scanner.kms, scanner.s3).use { lookalike ->
                assertEquals(recurrent.inventory(), lookalike.inventory())
                assertThrows<Exception> { retained.requireRetained(process.pools, process.consumers.journalRouting, checkpoint, ordinary, lookalike) }
                val replacement = VersionBoundTestInitialCheckpointDeletionV1.fromIndependentInputs(recurrentDeletionInput(), process.pools,
                    process.consumers.journalRouting, checkpoint, ordinary, lookalike)
                assertThrows<Exception> { process.pools.retainTestInitialCheckpointDeletion(replacement) }
                assertThrows<Exception> { process.pools.ordinary.requireTestInitialCheckpointDeletion(replacement) }
                assertThrows<Exception> { process.pools.deletion.requireTestInitialCheckpointDeletion(replacement) }
            }
            assertInventory(process, JsonObject(initialInventory(process) + mapOf(
                "profile" to JsonPrimitive("TEST_REGISTERED_CURRENT_RECURRENT_CHECKPOINT_DELETION_V1"),
                "newWork" to JsonPrimitive("CURRENT_EPOCH_OWNER_DELETE_OWNER_DELETE_ALL_ADMIN_DELETE_OR_ADMIN_BATCH_DELETE"),
                "checkpoint" to JsonPrimitive("CURRENT_FULL_RECURRENT_CHECKPOINT_AND_COMPLETE_PRIOR_P_N_L_APPLY_NO_REFUSAL_FALLBACK"))))
        }
        assertFalse(old.contentEquals(bytes))
        fun withoutDeletion(value: ByteArray) = CanonicalJson.canonicalize(JsonObject(
            Json.parseToJsonElement(value.decodeToString()).jsonObject - "initialCheckpointDeletion")).toByteArray()
        assertArrayEquals(withoutDeletion(old), withoutDeletion(bytes), "Only this consumer recipe changes; its readers and full-D authority stay exact.")
    }

    private fun assertInventory(process: VersionBoundTestNamespaceProcessV1, expected: JsonObject) = assertArrayEquals(
        CanonicalJson.canonicalize(expected).toByteArray(), CanonicalJson.canonicalize(checkNotNull(process.initialCheckpointDeletion).inventory()).toByteArray())

    /** Literal pre-extension inventory, including absence of any additional key. */
    private fun initialInventory(process: VersionBoundTestNamespaceProcessV1) = buildJsonObject {
        put("profile", "TEST_REGISTERED_CURRENT_INITIAL_CHECKPOINT_FIRST_DELETION_V1"); put("schemaVersion", 1)
        put("journalConfigurationSha256", process.consumers.journalConfiguration.sha256)
        put("checkpointMaximumAgeMillis", process.consumers.journalConfiguration.declaration().limits.deadlines.checkpointMaxAgeMillis)
        put("origin", "EXACT_RELEASED_INITIAL_REGISTRATION_ASSEMBLY_OWNERS_TEMPLATES_INGRESS_AND_P")
        put("newWork", "ONE_FIRST_EPOCH2_OWNER_DELETE_OWNER_DELETE_ALL_ADMIN_DELETE_OR_ADMIN_BATCH_DELETE")
        put("poolPolicy", "BORN_WITH_PRESENCE_AND_ABSENCE_NO_LOWER_OR_RECOVERY_AUTH")
        put("locking", "ORIGINAL_M_SHARED_E_GLOBAL_SCOPE_RECEIPT_GRANT_COUNTER_RUN_ACTOR_TARGET")
        put("checkpoint", "CURRENT_FULL_INITIAL_CHECKPOINT_GLOBAL_AND_SCOPE_SCAN_GATES_NO_SYNTHETIC_PROOF")
        put("replay", "CURRENT_REGISTERED_IDENTITY_AND_ACTOR_BEFORE_EXACT_RECEIPT_NO_FRESHNESS_OR_NEW_CHARGE")
        put("publication", "EXACT_PRE_D_ORDINARY_RECIPE_RESERVED_SHARED_NATIVE_LANE_AND_REAL_STS")
        put("verification", "EXACT_RELEASED_PREPARED_WORK_AND_NATIVE_READBACK_NO_DIRECT_REQUEST_APPLY")
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
    private fun recurrentDeletionInput() = TestInitialCheckpointDeletionInputV1(1, VersionBoundTestInitialCheckpointDeletionV1.RECURRENT_PROFILE)
    private fun recurrentInput() = TestActiveRecurrentInputV1(1, TestActiveRecurrentStorageV1.PROFILE, TestActiveInitialCheckpointHttpInputV1.SESSION)
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
