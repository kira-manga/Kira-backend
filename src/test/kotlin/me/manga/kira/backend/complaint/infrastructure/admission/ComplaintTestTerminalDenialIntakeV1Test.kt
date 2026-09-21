package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDenialInputFixtureV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalDenialInputFixtureV1
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

/** Actual protected intake only, cold before D/activation. No registration, signer provenance or deny assertion. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class ComplaintTestTerminalDenialIntakeV1Test {
    @Test
    fun `terminal pin is optional independent input compatible with drain and ACTIVE profiles`() {
        val legacy = TestDeploymentInputFixture.document()
        val legacyRoot = Json.parseToJsonElement(TestDeploymentInputFixture.bytes(legacy).decodeToString()).jsonObject
        assertFalse("terminalDenial" in legacyRoot)
        assertNull(ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(legacy)).terminalDenial)
        assertNull(ComplaintTestDeploymentJsonV1.parse(JsonObject(legacyRoot + ("terminalDenial" to JsonNull)).toString().toByteArray()).terminalDenial)
        for (active in listOf(false, true)) {
            val document = document(active)
            val parsed = ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(document))
            assertNotNull(parsed.ordinaryDenial); assertNotNull(parsed.terminalDenial)
            assertEquals(active, parsed.activeFirstCut != null)
            val absent = document.copy(terminalDenial = null)
            assertNull(ComplaintTestDeploymentJsonV1.parse(TestDeploymentInputFixture.bytes(absent)).terminalDenial)
            assertArrayEquals(parsed.journal.canonicalBytes(), ComplaintTestDeploymentInputsV1.fromDecoded(absent).journal.canonicalBytes())
        }
    }

    @Test
    fun `actual cold assembly retains terminal authority in full D without changing the born with ACTIVE graph`() {
        for (active in listOf(false, true)) withAssembled(document(active)) { assembly ->
            val owner = assembly.target
            val bytes = owner.canonicalBytes(); val hash = owner.configurationHashBytes()
            val encoded = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(checkNotNull(owner.terminalDenial).inventory(), encoded.getValue("terminalDenial"))
            assertNotNull(owner.ordinaryDenial); assertNotNull(owner.ordinarySeal)
            assertEquals(active, owner.activeFirstCut != null); assertEquals(active, owner.activeCutoffPublication != null)
            val without = VersionBoundTestNamespaceProcessV1.fromRetained(owner.consumers, owner.pools, owner.implementationSchema,
                owner.desiredGeneration, owner.databaseIdentity, owner.restoreIdentity, owner.publicationLanes, owner.catalogReadback,
                owner.catalogActivation, owner.ordinarySeal, owner.ordinaryDenial, owner.activeFirstCut, owner.activeCutoffPublication)
            assertArrayEquals(CanonicalJson.canonicalize(JsonObject(encoded - "terminalDenial")).toByteArray(), without.canonicalBytes())
            assertFalse(hash.contentEquals(without.configurationHashBytes()))
            owner.canonicalBytes().fill(0); owner.configurationHashBytes().fill(0)
            assertArrayEquals(bytes, owner.canonicalBytes()); assertArrayEquals(hash, owner.configurationHashBytes())
            owner.requireRegistrationTarget() // A local cold-owner check, not activation.
        }
    }

    @Test
    fun `missing ordinary pin foreign terminal context purpose and incomplete terminal fields fail before secrets`() {
        val document = document(false); val input = checkNotNull(document.terminalDenial)
        val wrong = listOf(document.copy(ordinaryDenial = null), document.copy(terminalDenial = input.copy(purpose = "TEST_ORDINARY_PUT_DENIAL_AND_REQUEST_BOUND_V1")),
            document.copy(terminalDenial = input.copy(environment = "other-synthetic")),
            document.copy(terminalDenial = input.copy(dataScopeId = "99000000-0000-4000-8000-000000000001")),
            document.copy(terminalDenial = input.copy(runEnvelopeAccounting = "RUN_LIFETIME_ENVELOPE_AT_FIRST_CUT_V1")))
        val encoded = TestDeploymentInputFixture.bytes(document).decodeToString()
        val root = Json.parseToJsonElement(encoded).jsonObject
        val pin = root.getValue("terminalDenial").jsonObject
        val inputs = wrong.map(TestDeploymentInputFixture::bytes) + pin.keys.map { field ->
            JsonObject(root + ("terminalDenial" to JsonObject(pin - field))).toString().toByteArray()
        } + listOf(encoded.replaceFirst("\"terminalDenial\":{", "\"terminalDenial\":{\"purpose\":\"other\",").toByteArray())
        val http = AwsSecretVersionFixture()
        inputs.forEach { bytes -> TestDeploymentInputFixture.withManifest(bytes) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = { error("premature STS") }, kms = { error("premature KMS") }, s3 = { error("premature S3") }).use { assembly ->
                val failure = assertThrows<ComplaintTestDeploymentExceptionV1> {
                    assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                }
                assertEquals(ComplaintTestDeploymentFailureV1.INPUT_REFUSED, failure.code); assertNull(failure.cause)
            }
        } }
        assertEquals(0, http.createdClients); assertTrue(http.requests.isEmpty())
    }

    private fun document(active: Boolean): ComplaintTestDeploymentDocumentV1 {
        val journal = if (active) TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration()) else fullTestJournal()
        val base = TestDeploymentInputFixture.document(journal)
        return base.copy(profile = if (active) ComplaintTestDeploymentInputsV1.ACTIVE_FIRST_CUT_PROFILE else ComplaintTestDeploymentInputsV1.DRAIN_PROFILE,
            ordinaryDenial = TestOrdinaryDenialInputFixtureV1.input(journal).copy(environment = base.retention.environment),
            terminalDenial = TestTerminalDenialInputFixtureV1.input(journal).copy(environment = base.retention.environment),
            activeFirstCut = if (active) TestActiveFirstCutInputFixtureV1.input() else null,
            ordinaryPublication = if (active) TestActiveFirstCutInputFixtureV1.ordinaryInput() else null)
    }

    private fun withAssembled(document: ComplaintTestDeploymentDocumentV1, action: (ComplaintTestProcessAssemblyV1) -> Unit) {
        val http = AwsSecretVersionFixture(); TestDeploymentInputFixture.secrets(http, document)
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = { error("premature STS") }, kms = { error("premature KMS") }, s3 = { error("premature S3") },
                ordinarySts = { error("premature ordinary STS") }, ordinaryKms = { error("premature ordinary KMS") }, ordinaryS3 = { error("premature ordinary S3") }).use { assembly ->
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS,
                    document.activeFirstCut?.let { TestActiveFirstCutInputFixtureV1.ordinaryCredentials })
                action(assembly)
                assertTrue(PgLifecycleTestScope(assembly.lifecycleOwner).actors().none { it.hasEntered() })
                val pools = assembly.target.pools
                assertTrue(listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource).none { actualPool(it).isRunning })
                requireConnectionFree()
            }
        }
        assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(document).allBindings().size, http.requests.size)
        assertEquals(http.createdClients, http.closedClients)
    }
}
