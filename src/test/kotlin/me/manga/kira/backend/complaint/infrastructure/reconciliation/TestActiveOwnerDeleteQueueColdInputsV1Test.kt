package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestDeploymentInputFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.fullTestJournal
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
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials

/** Source-authored protected cold composition: no actors, SQL, scheduler or native queue work. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestActiveOwnerDeleteQueueColdInputsV1Test {
    private val scanner = TestActiveInitialCheckpointHttpInputV1(
        { error("Premature scanner STS") }, { error("Premature scanner KMS") }, { error("Premature scanner S3") })
    private val queue = TestActiveOwnerDeleteQueueHttpInputV1(
        { error("Premature queue STS") }, { error("Premature queue KMS") }, { error("Premature queue S3") }, { error("Premature queue SQS") })

    @Test fun optionalBornWithQueueChangesOnlyItsFullDFieldAndRemainsEntirelyCold() {
        val baseline = document()
        val old = assembled(baseline)
        val selected = baseline.copy(activeOwnerDeleteQueue = queue.input)
        val bytes = assembled(selected)
        assertFalse(old.contentEquals(bytes))
        val d = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertArrayEquals(old, CanonicalJson.canonicalize(JsonObject(d - "activeOwnerDeleteQueue")).toByteArray(),
            "Absent queue preserves old canonical bytes and every old counter, price, reserve and owner recipe.")
        val inventory = d.getValue("activeOwnerDeleteQueue").jsonObject
        assertEquals("TEST_ACTIVE_OWNER_DELETE_QUEUE_V1", inventory.getValue("profile").jsonPrimitive.content)
        assertEquals("8192", inventory.getValue("observationStorageBytes").jsonPrimitive.content)
        assertEquals("1", inventory.getValue("maximumObservationRowsPerScope").jsonPrimitive.content)
        assertEquals("1", inventory.getValue("longPollSeconds").jsonPrimitive.content)
        assertEquals("1", inventory.getValue("maximumMessagesPerQueue").jsonPrimitive.content)
        assertEquals("EXACT_REGISTERED_ORDINARY_DELETIONS_V1", inventory.getValue("family").jsonPrimitive.content)
        val families = inventory.getValue("supportedFamilies").jsonObject
        assertEquals(setOf("OWNER_DELETE", "OWNER_DELETE_ALL", "ADMIN_DELETE", "ADMIN_BATCH_DELETE"), families.keys)
        listOf("OWNER_DELETE", "ADMIN_DELETE", "ADMIN_BATCH_DELETE").forEach {
            assertEquals("NATIVE_RECOVERY_FOUR_RETAINED_KEYS_ONE_VERSION_PER_KEY", families.getValue(it).jsonPrimitive.content)
        }
        assertEquals("EXACT_RETAINED_VERIFIED_PRIMARY_OR_UNEXPIRED_APPLIED_REPLAY", families.getValue("OWNER_DELETE_ALL").jsonPrimitive.content)
        assertEquals("MISSING_NPL_PREPARED_WITHOUT_VERIFY_MISSING_PAIR_ALIASES_POST_REPLAY_DOMAIN_REPAIR",
            inventory.getValue("allRecoveryUnfinished").jsonPrimitive.content)
        assertEquals("NO_CHECKPOINT_NO_CAPABILITY_NO_HEALTHY_NO_AUTOSTART", inventory.getValue("authority").jsonPrimitive.content)
        assertFalse(bytes.decodeToString().contains(queue.credentials.accessKeyId()))
        assertFalse(bytes.decodeToString().contains(TestActiveOwnerDeleteQueueHttpInputV1.SESSION))
    }

    @Test fun missingDependenciesMalformedRecipesAndAliasedPublicationCredentialsRefuseBeforeSecrets() {
        val selected = document().copy(activeOwnerDeleteQueue = queue.input)
        listOf(selected.copy(initialCheckpoint = null), selected.copy(activeFirstCut = null),
            selected.copy(activeOwnerDeleteQueue = queue.input.copy(schemaVersion = 2)),
            selected.copy(activeOwnerDeleteQueue = queue.input.copy(profile = "GENERAL_QUEUE_HEALTH")),
            selected.copy(activeOwnerDeleteQueue = queue.input.copy(totalAttemptMillis = 1499)),
            selected.copy(activeOwnerDeleteQueue = queue.input.copy(totalAttemptMillis = 10001)),
            selected.copy(activeOwnerDeleteQueue = queue.input.copy(recoverySessionName = null)),
            selected.copy(activeOwnerDeleteQueue = queue.input.copy(recoverySessionName = " wrong ")))
            .forEach { refusedBeforeSecrets(it, queue.credentials) }
        refusedBeforeSecrets(selected, null)
        refusedBeforeSecrets(selected, AwsSecretVersionFixture.CREDENTIALS)
        refusedBeforeSecrets(selected, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
        refusedBeforeSecrets(document(), queue.credentials)
    }

    private fun assembled(document: ComplaintTestDeploymentDocumentV1): ByteArray {
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, document)
        var bytes: ByteArray? = null
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = { error("Premature seal STS") }, kms = { error("Premature seal KMS") }, s3 = { error("Premature seal S3") },
                ordinarySts = { error("Premature publication STS") }, ordinaryKms = { error("Premature publication KMS") }, ordinaryS3 = { error("Premature publication S3") },
                scannerSts = scanner.sts, scannerKms = scanner.kms, scannerS3 = scanner.s3,
                queueSqs = queue.sqs, queueSts = queue.sts, queueKms = queue.kms, queueS3 = queue.s3).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                    TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner.credentials,
                    if (document.activeOwnerDeleteQueue == null) null else queue.credentials)
                val process = assembly.target
                bytes = process.canonicalBytes()
                assertNotNull(process.initialCheckpoint); assertNotNull(process.activeFirstCut); assertNotNull(process.activeCutoffPublication)
                if (document.activeOwnerDeleteQueue == null) {
                    assertNull(process.activeOwnerDeleteQueue)
                    assertFalse("activeOwnerDeleteQueue" in Json.parseToJsonElement(process.canonicalBytes().decodeToString()).jsonObject)
                } else {
                    val recipe = checkNotNull(process.activeOwnerDeleteQueue)
                    assertSame(recipe, ownedCutField(assembly, "activeQueue"))
                    assertSame(queue.sts, ownedCutField(recipe, "sts")); assertSame(queue.kms, ownedCutField(recipe, "kms"))
                    assertSame(queue.s3, ownedCutField(recipe, "s3")); assertSame(queue.sqs, ownedCutField(recipe, "sqs"))
                    assertSame(scanner.sts, ownedCutField(checkNotNull(process.initialCheckpoint), "sts"))
                    recipe.requireRetained(process.consumers.journalRouting, process.pools)
                    assertNull((ownedCutField(recipe, "active") as java.util.concurrent.atomic.AtomicReference<*>).get())
                }
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, checkNotNull(process.pools.epochRotation).observePreparation())
                assertTrue(listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                assertEquals(http.createdClients, http.closedClients)
                assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
            }
        }
        return checkNotNull(bytes)
    }

    private fun refusedBeforeSecrets(document: ComplaintTestDeploymentDocumentV1, credentials: AwsSessionCredentials?) {
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                    assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                        TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner.credentials, credentials)
                }.code)
            }
        }
        assertEquals(0, http.createdClients)
    }
    private fun document(): ComplaintTestDeploymentDocumentV1 {
        val j = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
        val base = TestDeploymentInputFixture.document(j)
        return base.copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_CHECKPOINT_PROFILE,
            ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(j, base.retention.environment),
            activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput(), initialCheckpoint = scanner.input)
    }
}
