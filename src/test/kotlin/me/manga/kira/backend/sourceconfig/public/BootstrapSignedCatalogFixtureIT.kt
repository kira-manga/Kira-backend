package me.manga.kira.backend.sourceconfig.public

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.config.KiraSigningProperties
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.sourceconfig.InitialSourceCatalogFixtures
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import me.manga.kira.backend.sourceconfig.admin.bootstrapMutationRows
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogReceipt
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogEntry
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogManifest
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import me.manga.kira.backend.sourceconfig.signing.SourceCatalogSignatureCodec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat

/**
 * Reproducible public TEST-ONLY vector from actual PENDING bootstrap, PostgreSQL and public reads.
 * Neither payload is reserialized: only the outer test envelope is formatted. The separate App
 * build consumes an identical committed copy; no sibling build or production trust pin is involved.
 * This class has its own Spring configuration, but still shares the inherited PostgreSQL container.
 */
@Import(BootstrapSignedCatalogFixtureIT.FixtureConfiguration::class)
@TestPropertySource(properties = ["kira.config.bundled-revision-floor=6", "kira.config.minimum-server-revision=100"])
class BootstrapSignedCatalogFixtureIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var signer: DocumentSigner

    @Autowired
    private lateinit var documents: PublishedDocumentRepository

    @OptIn(ExperimentalSerializationApi::class)
    private val fixtureJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    @DisabledIfEnvironmentVariable(named = GENERATE_ENV, matches = "true")
    fun bootstrapMatchesCommittedFixture() {
        val actual = captureFixture()
        val expected = requireNotNull(javaClass.getResourceAsStream("/fixtures/$FIXTURE_NAME")) {
            "Missing committed signed fixture; use the explicit candidate export and independent review, never an automatic golden rewrite"
        }.use { it.readBytes() }

        assertArrayEquals(expected, actual, "real bootstrap output must reproduce every committed payload byte and metadata field")
    }

    /** Explicit candidate export, NOT a passing golden comparison. It cannot write test resources. */
    @Test
    @EnabledIfEnvironmentVariable(named = GENERATE_ENV, matches = "true")
    fun exportCandidateOnly() {
        val candidate = captureFixture()
        val output = Path.of("build", "fixtures", FIXTURE_NAME)
        Files.createDirectories(output.parent)
        Files.write(output, candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        println("TEST-ONLY candidate, not golden-comparison evidence: $output SHA-256=${Sha256.hex(candidate)}")
    }

    private fun captureFixture(): ByteArray {
        val raw = requireNotNull(javaClass.getResourceAsStream("/fixtures/bundled-full.json")).use { it.readBytes() }
        val receipt = bootstrapRawFixture(raw)
        val expectedSources = InitialSourceCatalogFixtures.approvedDocument().sources.filter { it.engine == "generic" }
        val documentResponse = getPublicDocument().andExpect { status { isOk() } }.andReturn().response
        val documentPayload = documentResponse.exactUtf8()
        val document = SourceConfigParser.parseStrictDocument(documentPayload)
        assertEquals(receipt.documentChecksum, Sha256.hexUtf8(documentPayload))
        assertEquals(CATALOG_REVISION, document.revision)
        assertEquals(PUBLICATION_TIME, document.generatedAt)
        assertEquals(expectedSources, document.sources)

        val response = mockMvc.get("/api/v2/source-config/manifest").andExpect { status { isOk() } }.andReturn().response
        val payload = response.exactUtf8()
        val manifest = servedJson.decodeFromString(SourceCatalogManifest.serializer(), payload)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(1, manifest.sourceSchemaVersion)
        assertEquals(CATALOG_REVISION, manifest.catalogRevision)
        assertEquals(PUBLICATION_TIME, manifest.generatedAt)
        assertEquals(expectedSources.map { it.api }, manifest.sources.map { it.api })
        assertEquals((0 until 12).toList(), manifest.sources.map { it.order })
        assertTrue(manifest.sources.all { it.sourceRevision == 1 && it.lifecycle == "active" && it.engine == "generic" })
        assertTrue(manifest.removedSources.isEmpty())
        assertEquals(12, manifest.sources.map { Triple(it.api, it.sourceRevision, it.checksum) }.distinct().size)
        assertEquals(receipt.catalogChecksum, response.singleHeader("X-Config-Checksum"))
        assertEquals(mapOf(TEST_KEY_ID to TEST_PUBLIC_KEY_BASE64), signer.publicKeys())

        val metadata = captureManifestMetadata(response, payload)
        val artifacts = manifest.sources.mapIndexed { index, entry -> captureSource(entry, expectedSources[index]) }
        val fixture = buildJsonObject {
            put("fixtureVersion", 1)
            put("verificationKeys", buildJsonObject { signer.publicKeys().forEach { (id, key) -> put(id, key) } })
            put(
                "manifest",
                buildJsonObject {
                    put("payload", payload)
                    put("metadata", metadata)
                },
            )
            put("sources", JsonArray(artifacts))
        }
        // Deterministic outer formatting only; signed strings retain their exact original UTF-8 bytes.
        return (fixtureJson.encodeToString(JsonObject.serializer(), fixture) + "\n").toByteArray(Charsets.UTF_8)
    }

    private fun bootstrapRawFixture(raw: ByteArray): InitialSourceCatalogReceipt {
        val pending = documents.initialSourceCatalogState()
        assertEquals(InitialSourceCatalogPhase.PENDING, pending.phase)
        assertNull(pending.receipt)
        assertNull(pending.latestDocumentRevision)
        val before = publicState()
        assertEquals(0L, before.snapshots)
        assertEquals(0L, before.catalogs)
        assertEquals(0L, before.entries)
        assertEquals(0L, before.removed)
        assertTrue(jdbcTemplate.bootstrapMutationRows().values.all { it.isEmpty() }, "start with genuinely empty authoring/publication history")

        val response = bootstrapRequest(raw).andExpect { status { isOk() } }.andReturn().response
        val state = documents.initialSourceCatalogState()
        assertEquals(InitialSourceCatalogPhase.COMPLETE, state.phase)
        val receipt = requireNotNull(state.receipt)
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsBytes(receipt)), objectMapper.readTree(response.contentAsByteArray))
        assertEquals(InitialSourceCatalogPolicy.POLICY_ID, receipt.policyId)
        assertEquals(InitialSourceCatalogPolicy.REFERENCE_SHA256, receipt.referenceSha256)
        assertEquals(Sha256.hex(raw), receipt.payloadSha256)
        assertEquals(CATALOG_REVISION, receipt.documentRevision)
        assertEquals(CATALOG_REVISION, receipt.catalogRevision)
        assertEquals(CATALOG_REVISION, latestPointer())
        assertEquals(Instant.parse(PUBLICATION_TIME), receipt.completedAt)
        assertEquals(admin.id, receipt.actorId)
        assertEquals(45L, sourceRowCount())
        val after = publicState()
        assertEquals(1L, after.snapshots)
        assertEquals(1L, after.catalogs)
        assertEquals(12L, after.entries)
        assertEquals(0L, after.removed)
        assertEquals(
            12L,
            jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs WHERE status = 'active' AND engine = 'generic'", Long::class.java),
        )
        assertEquals(
            33L,
            jdbcTemplate.queryForObject("SELECT count(*) FROM source_configs WHERE status = 'withheld' AND engine = 'legacy'", Long::class.java),
        )
        assertEquals(
            SourceConfigParser.parseStrictDocument(raw.toString(Charsets.UTF_8)).sources.map { it.api },
            jdbcTemplate.query("SELECT api FROM source_configs ORDER BY position ASC, api ASC", { rs, _ -> rs.getString("api") }),
        )
        assertPublicArtifactAbsent("Lavatoons", 1)
        return receipt
    }

    private fun captureManifestMetadata(response: MockHttpServletResponse, payload: String): JsonObject {
        val format = response.singleHeader("X-Config-Signature-Format")
        val algorithm = response.singleHeader("X-Config-Signature-Algorithm")
        val keyId = response.singleHeader("X-Config-Signing-Key-Id")
        val signature = response.singleHeader("X-Config-Signature")
        val revision = response.singleHeader("X-Config-Revision").toLong()
        val checksum = response.singleHeader("X-Config-Checksum")
        val createdAt = response.singleHeader("X-Config-Created-At")
        val previousRevision = response.getHeader("X-Config-Previous-Revision")?.toLong()
        val previousChecksum = response.getHeader("X-Config-Previous-Checksum")
        assertEquals(SourceCatalogSignatureCodec.MANIFEST_FORMAT, format)
        assertEquals("Ed25519", algorithm)
        assertEquals(TEST_KEY_ID, keyId)
        assertEquals(CATALOG_REVISION, revision)
        assertEquals(PUBLICATION_TIME, createdAt)
        assertNull(previousRevision)
        assertNull(previousChecksum)
        assertEquals(Sha256.hexUtf8(payload), checksum)
        assertEquals("\"$checksum\"", response.singleHeader(HttpHeaders.ETAG))
        verifySignature(
            SourceCatalogSignatureCodec.manifestPayload(revision, previousRevision, previousChecksum, checksum, Instant.parse(createdAt), payload),
            signature,
        )
        return buildJsonObject {
            put("format", format)
            put("algorithm", algorithm)
            put("keyId", keyId)
            put("signatureBase64", signature)
            put("revision", revision)
            put("checksum", checksum)
            put("createdAt", createdAt)
            put("previousRevision", previousRevision)
            put("previousChecksum", previousChecksum)
        }
    }

    private fun captureSource(entry: SourceCatalogEntry, expected: SourceConfig): JsonObject {
        val response =
            mockMvc.get("/api/v2/source-config/sources/{api}/revisions/{revision}", entry.api, entry.sourceRevision)
                .andExpect { status { isOk() } }.andReturn().response
        val payload = response.exactUtf8()
        val api = response.singleHeader("X-Source-Api")
        val revision = response.singleHeader("X-Source-Revision").toInt()
        val checksum = response.singleHeader("X-Source-Checksum")
        val canonVersion = response.singleHeader("X-Source-Canon-Version")
        assertEquals(entry.api, api)
        assertEquals(entry.sourceRevision, revision)
        assertEquals(entry.checksum, checksum)
        assertEquals("kcj-1", canonVersion)
        assertEquals(TEST_KEY_ID, entry.sourceSigningKeyId)
        assertEquals(Sha256.hexUtf8(payload), checksum)
        assertEquals("\"$checksum\"", response.singleHeader(HttpHeaders.ETAG))
        assertEquals(expected, SourceConfigParser.parseStrictSource(payload), "full lifecycle-neutral source equality for $api")
        verifySignature(SourceCatalogSignatureCodec.sourcePayload(api, revision, checksum, payload), entry.sourceSignature)
        return buildJsonObject {
            put("api", api)
            put("sourceRevision", revision)
            put("checksum", checksum)
            put("canonVersion", canonVersion)
            put("payload", payload)
        }
    }

    private fun verifySignature(payload: ByteArray, signatureBase64: String) {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(TEST_PUBLIC_KEY_BASE64)))
        val verified = Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(payload)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
        assertTrue(verified, "verify the actual detached signature using the independently pinned TEST-ONLY public key")
    }

    private fun MockHttpServletResponse.singleHeader(name: String): String = getHeaders(name).single()

    private fun MockHttpServletResponse.exactUtf8(): String {
        val bytes = contentAsByteArray
        val payload = bytes.toString(Charsets.UTF_8)
        assertArrayEquals(bytes, payload.toByteArray(Charsets.UTF_8), "captured UTF-8 strings must round-trip every signed response byte")
        return payload
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixtureConfiguration {
        @Bean
        @Primary
        fun backend22FixtureSigner(): DocumentSigner = DocumentSigner(
            KiraSigningProperties(
                enabled = true,
                activeKeyId = TEST_KEY_ID,
                privateKey = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(TEST_ONLY_PKCS8_HEX)),
                verificationKeys = listOf(KiraSigningProperties.VerificationKey(TEST_KEY_ID, TEST_PUBLIC_KEY_BASE64)),
            ),
        )

        @Bean
        @Primary
        fun backend22PublicationClock(): Clock = Clock.fixed(Instant.parse(PUBLICATION_TIME), ZoneOffset.UTC)

        // The security decoder uses wall time. Retain real JWT issuance and authorization, not an expired fixed-time token or a bypass.
        @Bean
        @Primary
        fun backend22WallClockJwtService(keys: JwtKeyProvider, properties: KiraSecurityProperties): JwtService =
            JwtService(keys, properties, Clock.systemUTC())
    }

    private companion object {
        const val GENERATE_ENV = "KIRA_BACKEND22_GENERATE_FIXTURE"
        const val FIXTURE_NAME = "bootstrap-v2-v6-signed.json"
        const val PUBLICATION_TIME = "2026-09-13T00:00:00Z"
        const val CATALOG_REVISION = 100L
        const val TEST_KEY_ID = "backend22-bootstrap-test-only-v1"

        // RFC 8032 section 7.1 TEST 1 public vector, wrapped as RFC 8410 X.509/PKCS#8.
        // Publicly known TEST-ONLY material, never a production/release key. Private bytes stay in Backend test code only.
        const val TEST_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
        const val TEST_ONLY_PKCS8_HEX = "302e020100300506032b657004220420" +
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    }
}
