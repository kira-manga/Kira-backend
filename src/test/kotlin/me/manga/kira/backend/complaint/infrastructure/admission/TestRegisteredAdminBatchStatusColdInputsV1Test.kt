package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1
import me.manga.kira.backend.security.ComplaintAdminBatchStatusAdmissionPolicy
import me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy
import me.manga.kira.backend.security.ComplaintAdminStatusAdmissionPolicy
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.fullTestJournal
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Existing cold intake/secret/pool recipes, never registered or current authority. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestRegisteredAdminBatchStatusColdInputsV1Test {
    @Test fun absentAdminBatchStatusStaysDisabledAndExplicitDeclarationOnlyAddsItsFullDMemberWithoutChangingStatusOrIssuer() {
        val scanner = scanner(); val statusOnly = document(scanner)
        assertFalse(Json.parseToJsonElement(TestDeploymentInputFixture.bytes(statusOnly).decodeToString()).jsonObject.containsKey("adminBatchStatus"))
        assertNull(ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(statusOnly)).adminBatchStatus)
        val old = assembled(scanner, statusOnly) { process ->
            assertSame(ComplaintAdminBatchStatusAdmissionPolicy.Disabled, process.consumers.adminBatchStatusPolicy)
            assertSame(ComplaintAdminBatchStatusAdmissionPolicy.Disabled,
                poolTestField<ComplaintAdminBatchStatusAdmissionPolicy>(process.consumers.ingressAdmission, "adminBatchStatusPolicy"))
            assertOriginalPolicies(process)
        }
        val oldConsumers = Json.parseToJsonElement(old.decodeToString()).jsonObject.getValue("consumers").jsonObject
        assertFalse(oldConsumers.containsKey("adminBatchStatus"))
        for (perHour in listOf(1, 60)) {
            val selected = statusOnly.copy(adminBatchStatus = input(perHour))
            assertEquals(input(perHour), ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(selected)).adminBatchStatus)
            var policy: ComplaintAdminBatchStatusAdmissionPolicy.Bounded? = null
            val bytes = assembled(scanner, selected) { process ->
                val ingress = process.consumers.ingressAdmission
                policy = poolTestField<ComplaintAdminBatchStatusAdmissionPolicy>(ingress, "adminBatchStatusPolicy") as ComplaintAdminBatchStatusAdmissionPolicy.Bounded
                assertSame(process.consumers.adminBatchStatusPolicy, policy); assertEquals(perHour, checkNotNull(policy).perHour)
                assertOriginalPolicies(process)
                val status = process.consumers.adminStatusPolicy as ComplaintAdminStatusAdmissionPolicy.Bounded
                assertEquals(status.memberLimit, checkNotNull(policy).memberLimit); assertEquals(status.pruneBatch, checkNotNull(policy).pruneBatch)
                val members = poolTestField<Any>(ingress, "mutationMembers")
                for (store in listOf("creates", "adminContents", "adminStatuses", "adminBatchStatuses")) {
                    assertSame(members, poolTestField<Any>(poolTestField<Any>(ingress, store), "members"))
                }
            }
            val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val consumers = json.getValue("consumers").jsonObject
            val batch = consumers.getValue("adminBatchStatus").jsonObject
            assertEquals(setOf("schemaVersion", "profile", "perHour", "memberLimit", "pruneBatch", "windowNanos", "routes", "action",
                "authentication", "current", "responseOwner", "stepUpOwner", "bodyMaximumBytes", "targetMinimum", "targetMaximum"), batch.keys)
            assertEquals("1", batch.value("schemaVersion")); assertEquals(TestRegisteredAdminBatchStatusInputV1.PROFILE, batch.value("profile"))
            assertEquals(perHour.toString(), batch.value("perHour"))
            assertEquals(checkNotNull(policy).memberLimit.toString(), batch.value("memberLimit"))
            assertEquals(checkNotNull(policy).pruneBatch.toString(), batch.value("pruneBatch")); assertEquals("3600000000000", batch.value("windowNanos"))
            assertEquals(listOf("POST:/api/v1/admin/complaints/batch"), batch.getValue("routes").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("STATUS", batch.value("action"))
            assertEquals("QUALIFIED_USER_JWT_CURRENT_DB_ADMIN_AND_ORIGINAL_COMPLAINT_SCOPED_PROOF", batch.value("authentication"))
            assertEquals("ORIGINAL_TYPED_INITIAL_OR_EXPLICIT_RECURRENT_CHECKPOINT_NEW_CLAIM_ONLY", batch.value("current"))
            assertEquals("ONE_SHARED_OWNER_HISTORY_DETAIL_ADMIN_EIGHT_UNTIL_DELIVERY", batch.value("responseOwner"))
            assertEquals("EXISTING_REGISTERED_ADMIN_CONTENT_COMPLAINT_ISSUER", batch.value("stepUpOwner"))
            assertEquals("32768", batch.value("bodyMaximumBytes")); assertEquals("1", batch.value("targetMinimum")); assertEquals("50", batch.value("targetMaximum"))
            assertEquals(oldConsumers.getValue("adminStatus"), consumers.getValue("adminStatus"))
            assertEquals(oldConsumers.getValue("adminContent"), consumers.getValue("adminContent"))
            assertArrayEquals(old, CanonicalJson.canonicalize(JsonObject(json + ("consumers" to JsonObject(consumers - "adminBatchStatus")))).toByteArray())
            assertFalse(old.contentEquals(bytes))
        }
    }

    @Test fun malformedOrUnpairedAdminBatchStatusDeclarationRefusesBeforeAnySecretNativePoolOrLifecycleWork() {
        val scanner = scanner(); val current = document(scanner).copy(adminBatchStatus = input(60))
        val bytes = TestDeploymentInputFixture.bytes(current).decodeToString()
        val member = "\"adminBatchStatus\":{\"schemaVersion\":1,\"profile\":\"${TestRegisteredAdminBatchStatusInputV1.PROFILE}\",\"perHour\":60}"
        assertTrue(bytes.contains(member))
        val invalid = listOf(
            member.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            member.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            member.replace("\"schemaVersion\":1,", ""),
            member.replace(TestRegisteredAdminBatchStatusInputV1.PROFILE, "ADMIN"),
            member.replace("\"profile\":\"${TestRegisteredAdminBatchStatusInputV1.PROFILE}\",", ""),
            member.replace("\"${TestRegisteredAdminBatchStatusInputV1.PROFILE}\"", "1"),
            member.replace("\"perHour\":60", "\"perHour\":0"),
            member.replace("\"perHour\":60", "\"perHour\":61"),
            member.replace("\"perHour\":60", "\"perHour\":\"60\""),
            member.replace("\"perHour\":60", "\"perHour\":1.5"),
            member.replace("\"perHour\":60", "\"perHour\":6e1"),
            member.replace("\"perHour\":60", "\"perHour\":null"),
            member.replace("\"perHour\":60", "\"perHour\":true"),
            member.replace(",\"perHour\":60", ""),
            member.dropLast(1) + ",\"perHour\":1}",
            member.dropLast(1) + ",\"targetMaximum\":51}",
            "\"adminBatchStatus\":null",
            "\"adminBatchStatus\":[]",
            "\"adminBatchStatus\":true",
        ).map { bytes.replace(member, it).toByteArray() } + listOf(
            (bytes.dropLast(1) + ",$member}").toByteArray(),
            "$bytes{}".toByteArray(),
            TestDeploymentInputFixture.bytes(current.copy(adminStatus = null)),
            TestDeploymentInputFixture.bytes(current.copy(adminContent = null)),
            TestDeploymentInputFixture.bytes(current.copy(adminRead = null)),
            TestDeploymentInputFixture.bytes(current.copy(initialCheckpointCreate = null)),
        )
        invalid.forEach { document ->
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.withManifest(document) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                    sts = { error("Premature sealer STS") }, kms = { error("Premature sealer KMS") }, s3 = { error("Premature sealer S3") },
                    ordinarySts = { error("Premature ordinary STS") }, ordinaryKms = { error("Premature ordinary KMS") }, ordinaryS3 = { error("Premature ordinary S3") },
                    scannerSts = scanner.sts, scannerKms = scanner.kms, scannerS3 = scanner.s3).use { assembly ->
                    assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                        assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                            TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner.credentials)
                    }.code)
                    // A refused assembly has no ready-only lifecycleOwner. These slots retain any acquired owner even after close.
                    assertNull(poolTestField<Any?>(assembly, "persistence")); assertNull(poolTestField<Any?>(assembly, "owner"))
                }
            }
            assertEquals(0, http.createdClients); assertTrue(http.requests.isEmpty())
        }
    }

    private fun assertOriginalPolicies(process: VersionBoundTestNamespaceProcessV1) {
        val content = process.consumers.adminContentPolicy as ComplaintAdminContentAdmissionPolicy.Bounded
        val status = process.consumers.adminStatusPolicy as ComplaintAdminStatusAdmissionPolicy.Bounded
        assertSame(content, poolTestField<ComplaintAdminContentAdmissionPolicy>(process.consumers.ingressAdmission, "adminContentPolicy"))
        assertSame(status, poolTestField<ComplaintAdminStatusAdmissionPolicy>(process.consumers.ingressAdmission, "adminStatusPolicy"))
        assertEquals(60, content.perHour); assertEquals(60, status.perHour)
        assertEquals(300L, checkNotNull(process.consumers.adminStepUp).properties.stepUpTtl.seconds)
    }
    private fun assembled(scanner: TestActiveInitialCheckpointHttpInputV1, document: ComplaintTestDeploymentDocumentV1,
        action: (VersionBoundTestNamespaceProcessV1) -> Unit): ByteArray {
        val http = AwsSecretVersionFixture(); TestDeploymentInputFixture.secrets(http, document)
        return TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = { error("Premature sealer STS") }, kms = { error("Premature sealer KMS") }, s3 = { error("Premature sealer S3") },
                ordinarySts = { error("Premature ordinary STS") }, ordinaryKms = { error("Premature ordinary KMS") }, ordinaryS3 = { error("Premature ordinary S3") },
                scannerSts = scanner.sts, scannerKms = scanner.kms, scannerS3 = scanner.s3).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                    TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner.credentials)
                val process = assembly.target; action(process); process.requireUnchangedConfiguration()
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                assertTrue(listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                assertEquals(http.createdClients, http.closedClients)
                assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
                process.canonicalBytes()
            }
        }
    }
    private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content
    private fun input(perHour: Int) = TestRegisteredAdminBatchStatusInputV1(1, TestRegisteredAdminBatchStatusInputV1.PROFILE, perHour)
    private fun scanner() = TestActiveInitialCheckpointHttpInputV1(
        { error("Premature scanner STS") }, { error("Premature scanner KMS") }, { error("Premature scanner S3") })
    private fun document(scanner: TestActiveInitialCheckpointHttpInputV1): ComplaintTestDeploymentDocumentV1 {
        val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
        val base = TestDeploymentInputFixture.document(journal)
        return base.copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_CHECKPOINT_PROFILE,
            ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(journal, base.retention.environment),
            activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput(),
            initialCheckpoint = scanner.input, initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
            adminRead = TestRegisteredAdminReadInputV1(1, TestRegisteredAdminReadInputV1.PROFILE, 60),
            adminContent = TestRegisteredAdminContentInputV1(1, TestRegisteredAdminContentInputV1.PROFILE, 60),
            adminStatus = TestRegisteredAdminStatusInputV1(1, TestRegisteredAdminStatusInputV1.PROFILE, 60))
    }
}
