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
import me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy
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

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Cold recipes and full D are not registration or current-checkpoint authority. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestRegisteredAdminContentColdInputsV1Test {
    @Test fun absentAdminContentStaysDisabledAndExplicitDeclarationBindsItsPolicyIssuerAndSharedOwnerInFullD() {
        val scanner = scanner(); val legacy = document(scanner)
        assertFalse(Json.parseToJsonElement(TestDeploymentInputFixture.bytes(legacy).decodeToString()).jsonObject.containsKey("adminContent"))
        val old = assembled(scanner, legacy) { process ->
            assertSame(ComplaintAdminContentAdmissionPolicy.Disabled,
                poolTestField<ComplaintAdminContentAdmissionPolicy>(process.consumers.ingressAdmission, "adminContentPolicy"))
            assertSame(ComplaintAdminContentAdmissionPolicy.Disabled, process.consumers.adminContentPolicy)
            assertNull(process.consumers.adminStepUp)
        }
        for (perHour in listOf(1, 60)) {
            val selected = legacy.copy(adminContent = input(perHour))
            assertEquals(input(perHour), ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(selected)).adminContent)
            var policy: ComplaintAdminContentAdmissionPolicy.Bounded? = null
            val bytes = assembled(scanner, selected) { process ->
                policy = poolTestField<ComplaintAdminContentAdmissionPolicy>(process.consumers.ingressAdmission, "adminContentPolicy") as ComplaintAdminContentAdmissionPolicy.Bounded
                assertSame(process.consumers.adminContentPolicy, policy)
                assertEquals(perHour, checkNotNull(policy).perHour)
                assertEquals(300L, checkNotNull(process.consumers.adminStepUp).properties.stepUpTtl.seconds)
            }
            val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val consumers = json.getValue("consumers").jsonObject
            val content = consumers.getValue("adminContent").jsonObject
            assertEquals(setOf("schemaVersion", "profile", "perHour", "memberLimit", "pruneBatch", "windowNanos", "routes",
                "authentication", "current", "responseOwner", "stepUp"), content.keys)
            assertEquals("1", content.value("schemaVersion")); assertEquals(TestRegisteredAdminContentInputV1.PROFILE, content.value("profile"))
            assertEquals(perHour.toString(), content.value("perHour"))
            assertEquals(checkNotNull(policy).memberLimit.toString(), content.value("memberLimit"))
            assertEquals(checkNotNull(policy).pruneBatch.toString(), content.value("pruneBatch"))
            assertEquals("3600000000000", content.value("windowNanos"))
            assertEquals(setOf("POST:/api/v1/admin/step-up", "PATCH:/api/v1/admin/complaints/{id}/content"),
                content.getValue("routes").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals("QUALIFIED_USER_JWT_CURRENT_DB_ADMIN_AND_ORIGINAL_CONFIGURED_PASSWORD_ENCODER", content.value("authentication"))
            assertEquals("ORIGINAL_TYPED_INITIAL_OR_EXPLICIT_RECURRENT_CHECKPOINT_NEW_CLAIM_ONLY", content.value("current"))
            assertEquals("ONE_SHARED_OWNER_HISTORY_DETAIL_ADMIN_EIGHT_UNTIL_DELIVERY", content.value("responseOwner"))
            val stepUp = content.getValue("stepUp").jsonObject
            assertEquals(setOf("scope", "ttlSeconds", "bodyMaximumBytes", "passwordMaximumCharacters", "responseMaximumBytes", "throttle"), stepUp.keys)
            assertEquals("complaint-moderation-mutation", stepUp.value("scope")); assertEquals("300", stepUp.value("ttlSeconds"))
            assertEquals("4096", stepUp.value("bodyMaximumBytes")); assertEquals("256", stepUp.value("passwordMaximumCharacters"))
            assertEquals("1024", stepUp.value("responseMaximumBytes"))
            assertEquals(mapOf("backend" to "memory", "instanceCount" to "1", "maxEntries" to "1024", "loginFailureThreshold" to "5",
                "loginIpFailureThreshold" to "25", "loginAttemptTtlMillis" to "30000", "loginInitialBlockMillis" to "60000",
                "loginMaxBlockMillis" to "900000", "loginFailureWindowMillis" to "900000", "registrationMaxPerWindow" to "20",
                "registrationWindowMillis" to "3600000"), stepUp.getValue("throttle").jsonObject.mapValues { it.value.jsonPrimitive.content })
            assertArrayEquals(old, CanonicalJson.canonicalize(JsonObject(json + ("consumers" to JsonObject(consumers - "adminContent")))).toByteArray())
            assertFalse(old.contentEquals(bytes))
        }
    }

    @Test fun malformedOrUnpairedAdminContentDeclarationRefusesBeforeAnySecretNativePoolOrLifecycleWork() {
        val scanner = scanner(); val current = document(scanner).copy(adminContent = input(60))
        val bytes = TestDeploymentInputFixture.bytes(current).decodeToString()
        val member = "\"adminContent\":{\"schemaVersion\":1,\"profile\":\"${TestRegisteredAdminContentInputV1.PROFILE}\",\"perHour\":60}"
        assertTrue(bytes.contains(member))
        val invalid = listOf(
            member.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            member.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            member.replace(TestRegisteredAdminContentInputV1.PROFILE, "ADMIN"),
            member.replace("\"perHour\":60", "\"perHour\":0"),
            member.replace("\"perHour\":60", "\"perHour\":61"),
            member.replace("\"perHour\":60", "\"perHour\":\"60\""),
            member.replace("\"perHour\":60", "\"perHour\":1.5"),
            member.replace(",\"perHour\":60", ""),
            member.dropLast(1) + ",\"perHour\":1}",
            member.dropLast(1) + ",\"ttlSeconds\":1}",
        ).map { bytes.replace(member, it).toByteArray() } + listOf(
            TestDeploymentInputFixture.bytes(current.copy(adminRead = null)),
            TestDeploymentInputFixture.bytes(current.copy(initialCheckpointCreate = null)),
        )
        invalid.forEach { document ->
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.withManifest(document) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                    assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                        assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                            TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner.credentials)
                    }.code)
                    // Refusal has no ready target; these retained slots are never cleared by cleanup.
                    assertNull(poolTestField<Any?>(assembly, "persistence"))
                    assertNull(poolTestField<Any?>(assembly, "owner"))
                }
            }
            assertEquals(0, http.createdClients); assertTrue(http.requests.isEmpty())
        }
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
    private fun input(perHour: Int) = TestRegisteredAdminContentInputV1(1, TestRegisteredAdminContentInputV1.PROFILE, perHour)
    private fun scanner() = TestActiveInitialCheckpointHttpInputV1(
        { error("Premature scanner STS") }, { error("Premature scanner KMS") }, { error("Premature scanner S3") })
    private fun document(scanner: TestActiveInitialCheckpointHttpInputV1): ComplaintTestDeploymentDocumentV1 {
        val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
        val base = TestDeploymentInputFixture.document(journal)
        return base.copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_CHECKPOINT_PROFILE,
            ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(journal, base.retention.environment),
            activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput(),
            initialCheckpoint = scanner.input, initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
            adminRead = TestRegisteredAdminReadInputV1(1, TestRegisteredAdminReadInputV1.PROFILE, 60))
    }
}
