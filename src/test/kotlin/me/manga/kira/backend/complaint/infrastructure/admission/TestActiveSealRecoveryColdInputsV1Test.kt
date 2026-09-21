package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveSealRecoveryInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOrdinarySealRecoveryV1
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.fullTestJournal
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** Cold-only source tests; absent-option bytes remain unchanged. Parent owns separately pinned C37/A-checkpoint combination joins. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestActiveSealRecoveryColdInputsV1Test {
    @Test fun `independent recovery recipe is retained before full D without new native resource price or startup`() {
        val old = activeDocument()
        val recovery = old.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_SEAL_RECOVERY_PROFILE,
            activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.input())
        val originalBytes = assembled(old)
        val selectedBytes = assembled(recovery)
        val selected = Json.parseToJsonElement(selectedBytes.decodeToString()).jsonObject
        assertEquals("PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_SEAL_RECOVERY_MEMORY_SINGLE_INSTANCE_SINGLE_CATALOG_SIGNER",
            selected.getValue("profile").jsonPrimitive.content)
        val inventory = selected.getValue("activeOrdinarySealRecovery").jsonObject
        assertEquals(VersionBoundTestActiveOrdinarySealRecoveryV1.PROFILE, inventory.getValue("profile").jsonPrimitive.content)
        assertEquals("0", inventory.getValue("newCharge").jsonPrimitive.content)
        assertEquals("2000", inventory.getValue("phaseMillis").jsonPrimitive.content)
        assertEquals("30000", inventory.getValue("leaseMillis").jsonPrimitive.content)
        assertEquals("10000", inventory.getValue("renewalWindowMillis").jsonPrimitive.content)
        assertEquals("IMMUTABLE_FROZEN_REQUEST_MISSING_OBJECT_MAY_REFUSE_NO_REPAIR", inventory.getValue("retention").jsonPrimitive.content)
        val oldProfile = Json.parseToJsonElement(originalBytes.decodeToString()).jsonObject.getValue("profile")
        val withoutOnlyNewRecipe = JsonObject((selected - "activeOrdinarySealRecovery") + ("profile" to oldProfile))
        assertArrayEquals(originalBytes, CanonicalJson.canonicalize(withoutOnlyNewRecipe).toByteArray(),
            "No silent change in full-D pools/provider inventories, quotas, timeout/capacity/price or old absent bytes.")
    }

    @Test fun `current PUT floor is an explicit subrecipe and changes only its policy in full D`() {
        val old = activeDocument().copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_SEAL_RECOVERY_PROFILE,
            activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.input())
        val current = old.copy(activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.currentPutFloorInput())
        val oldBytes = assembled(old)
        val currentBytes = assembled(current)
        assertFalse(oldBytes.contentEquals(currentBytes), "The new retention policy must be born into a different full D.")
        val oldD = Json.parseToJsonElement(oldBytes.decodeToString()).jsonObject
        val currentD = Json.parseToJsonElement(currentBytes.decodeToString()).jsonObject
        assertEquals(oldD.getValue("profile"), currentD.getValue("profile"), "No new top-level profile or default.")
        val oldPolicy = oldD.getValue("activeOrdinarySealRecovery").jsonObject
        val currentPolicy = currentD.getValue("activeOrdinarySealRecovery").jsonObject
        assertEquals(VersionBoundTestActiveOrdinarySealRecoveryV1.CURRENT_PUT_FLOOR_PROFILE, currentPolicy.getValue("profile").jsonPrimitive.content)
        assertEquals("IMMUTABLE_FROZEN_M_ONE_PUT_L_MAX_M_CURRENT_FLOOR_ACK_A_GE_L_ADOPTION_ACTUAL_FLOORS", currentPolicy.getValue("retention").jsonPrimitive.content)
        val withoutOnlyNewPolicy = JsonObject(currentD + ("activeOrdinarySealRecovery" to JsonObject(currentPolicy +
            mapOf("profile" to oldPolicy.getValue("profile"), "retention" to oldPolicy.getValue("retention")))))
        assertArrayEquals(oldBytes, CanonicalJson.canonicalize(withoutOnlyNewPolicy).toByteArray(),
            "The old recipe, resource/price, provider, timing and capacity inventories remain byte-identical.")
    }

    @Test fun `old defaults omit recovery and malformed profile partial recipe unknown field or schema refuses before secrets`() {
        val old = activeDocument()
        val bytes = TestDeploymentInputFixture.bytes(old)
        assertArrayEquals(bytes, TestDeploymentInputFixture.bytes(old.copy(activeOrdinarySealRecovery = null)))
        assertFalse("activeOrdinarySealRecovery" in Json.parseToJsonElement(bytes.decodeToString()).jsonObject)
        assertNull(ComplaintTestDeploymentInputsV1.fromDecoded(old).activeOrdinarySealRecovery)
        val good = old.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_SEAL_RECOVERY_PROFILE,
            activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.input())
        listOf(
            good.copy(activeOrdinarySealRecovery = null),
            good.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE),
            good.copy(activeFirstCut = null), good.copy(ordinaryPublication = null), good.copy(ordinaryDenial = null),
            good.copy(activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.input().copy(schemaVersion = 2)),
            good.copy(activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.input().copy(profile = "TEST_ACTIVE_PREVERIFY_ANY_STATE")),
            TestDeploymentInputFixture.document().copy(activeOrdinarySealRecovery = TestActiveSealRecoveryInputFixtureV1.input()),
        ).forEach { refused(TestDeploymentInputFixture.bytes(it)) }
        val json = Json.parseToJsonElement(TestDeploymentInputFixture.bytes(good).decodeToString()).jsonObject
        val extended = JsonObject(json + ("activeOrdinarySealRecovery" to JsonObject(json.getValue("activeOrdinarySealRecovery").jsonObject +
            ("extendFrozenRetention" to JsonPrimitive(true)))))
        refused(extended.toString().toByteArray())
    }

    private fun assembled(document: ComplaintTestDeploymentDocumentV1): ByteArray {
        val raw = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(raw, document)
        var bytes: ByteArray? = null
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(raw::httpClient,
                sts = { error("Cold input cannot open native seal STS") }, kms = { error("Cold input cannot open native seal KMS") }, s3 = { error("Cold input cannot open native seal S3") },
                ordinarySts = { error("Cold input cannot open ordinary STS") }, ordinaryKms = { error("Cold input cannot open ordinary KMS") }, ordinaryS3 = { error("Cold input cannot open ordinary S3") }).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
                val target = assembly.target
                assertNotNull(target.activeFirstCut)
                if (document.activeOrdinarySealRecovery == null) assertNull(target.activeOrdinarySealRecovery)
                else checkNotNull(target.activeOrdinarySealRecovery).requireRetained(target.pools, target.consumers.journalRouting, target.activeFirstCut, target.ordinarySeal)
                assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, checkNotNull(target.pools.epochRotation).observePreparation())
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                assertTrue(listOf(target.pools.ordinary, target.pools.deletion, target.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                bytes = target.canonicalBytes()
            }
        }
        assertEquals(raw.createdClients, raw.closedClients)
        return checkNotNull(bytes)
    }

    private fun refused(bytes: ByteArray) {
        val raw = AwsSecretVersionFixture()
        TestDeploymentInputFixture.withManifest(bytes) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(raw::httpClient).use { assembly ->
                assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                    assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
                }.code)
            }
        }
        assertEquals(0, raw.createdClients)
    }

    private fun activeDocument(): ComplaintTestDeploymentDocumentV1 {
        val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
        val base = TestDeploymentInputFixture.document(journal)
        return base.copy(profile = ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE,
            ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(journal, base.retention.environment),
            activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput())
    }
}
