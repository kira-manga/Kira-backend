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
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointHttpInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1
import me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.fullTestJournal
import me.manga.kira.backend.security.historyTestRequest
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
import java.time.Instant
import java.util.UUID

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Cold input/retained consumers are not registration or launch authority. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class TestRegisteredAdminReadColdInputsV1Test {
    @Test fun optionalAdminReadBindsOnlyItsDeclaredPolicyCursorAndFullDMemberWhileAbsenceStaysDisabled() {
        val scanner = scanner()
        val legacy = document(scanner)
        assertFalse(Json.parseToJsonElement(TestDeploymentInputFixture.bytes(legacy).decodeToString()).jsonObject.containsKey("adminRead"))
        val oldBytes = assembled(scanner, legacy) { process ->
            assertSame(ComplaintAdminReadAdmissionPolicy.Disabled, process.consumers.adminReadPolicy)
            assertNull(process.consumers.adminCursorCodec)
            process.consumers.ingressAdmission.withIngress(historyTestRequest()) { context ->
                assertThrows<ComplaintAdmissionRejected> { process.consumers.ingressAdmission.startAdminStats(context) }
            }
        }
        for (perMinute in listOf(1, 60)) {
            val selected = legacy.copy(adminRead = input(perMinute))
            assertEquals(input(perMinute), ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(selected)).adminRead)
            val bytes = assembled(scanner, selected) { process ->
                val consumers = process.consumers
                assertEquals(perMinute, (consumers.adminReadPolicy as ComplaintAdminReadAdmissionPolicy.Bounded).perMinute)
                assertSame(consumers.adminReadPolicy, poolTestField<ComplaintAdminReadAdmissionPolicy>(consumers.ingressAdmission, "adminReadPolicy"))
                val codec = checkNotNull(consumers.adminCursorCodec)
                assertEquals(consumers.ownerCursorCodec.activeKeyId, codec.activeKeyId)
                val actor = UUID.randomUUID()
                val query = ComplaintAdminSearchQuery(process.desiredSettings().scope)
                val position = ComplaintAdminReadPosition(Instant.parse("2026-09-22T01:00:00.123456Z"), UUID.randomUUID())
                val cursor = codec.encode(actor, query, position)
                val decoded = codec.decode(cursor, actor, query)
                assertEquals(position.id, decoded.id); assertEquals(position.updatedAt, decoded.updatedAt)
            }
            val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val consumers = json.getValue("consumers").jsonObject
            val read = consumers.getValue("adminRead").jsonObject
            assertEquals("1", read.getValue("schemaVersion").jsonPrimitive.content)
            assertEquals(TestRegisteredAdminReadInputV1.PROFILE, read.getValue("profile").jsonPrimitive.content)
            assertEquals(perMinute.toString(), read.getValue("perMinute").jsonPrimitive.content)
            assertEquals(setOf("POST:/api/v1/admin/complaints/search", "GET:/api/v1/admin/complaints/{id}", "GET:/api/v1/admin/complaints/stats"),
                read.getValue("routes").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals("ONE_SHARED_OWNER_HISTORY_DETAIL_ADMIN_EIGHT_UNTIL_DELIVERY", read.getValue("responseOwner").jsonPrimitive.content)
            assertEquals("900", read.getValue("cursor").jsonObject.getValue("ttlSeconds").jsonPrimitive.content)
            assertEquals("2048", read.getValue("cursor").jsonObject.getValue("maximumCharacters").jsonPrimitive.content)
            assertArrayEquals(oldBytes, CanonicalJson.canonicalize(JsonObject(json + ("consumers" to JsonObject(consumers - "adminRead")))).toByteArray())
            assertFalse(oldBytes.contentEquals(bytes))
        }
    }

    @Test fun invalidOrIncompleteAdminReadDeclarationRefusesBeforeAnySecretOrNativeResourceAcquisition() {
        val scanner = scanner()
        val current = document(scanner).copy(adminRead = input(60))
        val bytes = TestDeploymentInputFixture.bytes(current).decodeToString()
        val validMember = "\"adminRead\":{\"schemaVersion\":1,\"profile\":\"${TestRegisteredAdminReadInputV1.PROFILE}\",\"perMinute\":60}"
        assertTrue(bytes.contains(validMember))
        val invalidMembers = listOf(
            validMember.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            validMember.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            validMember.replace(TestRegisteredAdminReadInputV1.PROFILE, "ADMIN"),
            validMember.replace("\"perMinute\":60", "\"perMinute\":0"),
            validMember.replace("\"perMinute\":60", "\"perMinute\":61"),
            validMember.replace(",\"perMinute\":60", ""),
            validMember.replace("\"perMinute\":60", "\"perMinute\":\"60\""),
            validMember.replace("\"perMinute\":60", "\"perMinute\":1.5"),
            validMember.dropLast(1) + ",\"enabled\":true}",
            validMember.dropLast(1) + ",\"perMinute\":1}",
        ).map { bytes.replace(validMember, it).toByteArray() } +
            listOf(TestDeploymentInputFixture.bytes(current.copy(initialCheckpointCreate = null)))
        invalidMembers.forEach { invalid ->
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.withManifest(invalid) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                    assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                        assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                            TestActiveFirstCutInputFixtureV1.ordinaryCredentials, scanner.credentials)
                    }.code)
                }
            }
            assertEquals(0, http.createdClients); assertTrue(http.requests.isEmpty())
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
    private fun input(perMinute: Int) = TestRegisteredAdminReadInputV1(1, TestRegisteredAdminReadInputV1.PROFILE, perMinute)
    private fun scanner() = TestActiveInitialCheckpointHttpInputV1(
        { error("Premature scanner STS") }, { error("Premature scanner KMS") }, { error("Premature scanner S3") })
    private fun document(scanner: TestActiveInitialCheckpointHttpInputV1): ComplaintTestDeploymentDocumentV1 {
        val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
        val base = TestDeploymentInputFixture.document(journal)
        return base.copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_CHECKPOINT_PROFILE,
            ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(journal, base.retention.environment),
            activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput(),
            initialCheckpoint = scanner.input, initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE))
    }
}
