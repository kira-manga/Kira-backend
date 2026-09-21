package me.manga.kira.backend.complaint.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.infrastructure.persistence.ColdFixtureFilesV1
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.encoded
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.xml
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.xmlReply
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawGetReply
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.SecretHttpReply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import java.net.URLDecoder
import java.nio.file.Path
import java.time.Instant

/** Only bytes of synthetic provider objects/input artifacts and read-only diagnostics cross JVMs. */
@Serializable
internal data class ColdSecretObjectV1(val arn: String, val version: String, val body: String, val headers: Map<String, List<String>>)
@Serializable
internal data class ColdCatalogObjectV1(val bucket: String, val key: String, val version: String, val body: String, val headers: Map<String, List<String>>)
@Serializable
internal data class ColdJournalObjectV1(val key: String, val version: String, val body: String, val modified: String,
    val retention: String, val metadata: Map<String, String>) {
    fun raw() = JournalPublisherObject(key, version, unbase64(body), Instant.parse(modified), Instant.parse(retention), metadata)
}
@Serializable
internal data class ColdKmsObjectV1(val generateRequest: String, val generateReply: String)
@Serializable
internal data class ColdRawHandoffV1(
    val format: Int, val paidCut: Boolean, val authorPid: Long, val authorStart: String,
    val documentSha256: String, val fullDSha256: String, val fence: Long,
    val approval: String, val evidence: List<String>, val secrets: List<ColdSecretObjectV1>,
    val catalog: List<ColdCatalogObjectV1>, val ordinary: List<ColdJournalObjectV1>, val kms: List<ColdKmsObjectV1>,
    val image: Map<String, List<String>>,
) {
    fun write(root: Path) = ColdFixtureFilesV1.write(root.resolve("raw-backing.json"),
        Json.encodeToString(serializer(), this).toByteArray())

    companion object {
        fun read(root: Path): ColdRawHandoffV1 = Json.decodeFromString(serializer(),
            ColdFixtureFilesV1.read(root.resolve("raw-backing.json")).toString(Charsets.UTF_8)).also {
            check(it.format == 1 && it.authorPid > 0 && it.fence >= 0)
            check(it.ordinary.size == 4 && it.ordinary.map { raw -> raw.key }.toSet().size == 4)
            check(it.kms.size == 4 && it.secrets.size in 2..64 && it.catalog.size in 2..64 && it.evidence.size == 2)
            check(it.documentSha256 == ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(root.resolve("test-deployment.json"))))
        }

        fun capture(f: TestRunOrdinaryDrainFixtureV1, paidCut: Boolean, approval: ByteArray, root: Path): ColdRawHandoffV1 {
            f.assertReleased()
            val evidence = f.history.p.f.rows.evidence
            val input = evidence.coldInputBytes()
            ColdFixtureFilesV1.write(root.resolve("test-deployment.json"), input)
            val catalog = coldCatalogObjectsV1(f.history.p.f.http.read)
            check(catalog.values.groupBy { it.bucket }.values.map { it.size }.toSet() == setOf(evidence.prefix.size + 1))
            check(catalog.values.map { it.bucket }.toSet() == OfflineTrustBundleFixture.locations.map { it.bucket }.toSet())
            val provider = f.provider
            check(provider.kms.requests.size == provider.kms.replies.size)
            val keys = provider.kms.requests.zip(provider.kms.replies).filter { it.first.target() == AwsJournalKmsFixture.GENERATE_TARGET }
                .map { (request, reply) ->
                    check(reply.status == 200 && reply.calls == 1 && reply.closes > 0)
                    ColdKmsObjectV1(request.json, reply.bytes.toString(Charsets.UTF_8))
                }
            val process = ProcessHandle.current()
            return ColdRawHandoffV1(1, paidCut, process.pid(), process.info().startInstant().orElseThrow().toString(),
                ColdFixtureFilesV1.sha256(input), ColdFixtureFilesV1.sha256(f.registration.process.canonicalBytes()),
                ColdSqlObservationV1.fence(f.observer, f.scope), base64(approval), f.rawEvidence.map(::base64), evidence.coldSecretObjects(),
                catalog.values.toList(), provider.objects.sortedBy { it.key }.map {
                    ColdJournalObjectV1(it.key, it.version, base64(it.bytes), it.lastModified.toString(), it.retainUntil.toString(), it.metadata)
                }, keys, ColdSqlObservationV1.image(f.observer))
        }
    }
}

/** Actual retained raw GET replies only; no accepted catalog/readback/proof crosses JVMs. */
internal fun coldCatalogObjectsV1(http: S3CatalogReadbackFixture): LinkedHashMap<Triple<String, String, String>, ColdCatalogObjectV1> {
    check(http.requests.size == http.replies.size)
    // Latest raw versioned GET of each actual retained copy; no checked chain/readback/proof is serialized.
    val catalog = linkedMapOf<Triple<String, String, String>, ColdCatalogObjectV1>()
    http.requests.zip(http.replies).forEach { (request, reply) ->
        val version = request.firstMatchingRawQueryParameter("versionId").orElse(null)
        if (version != null) {
            check(request.method() == SdkHttpMethod.GET && reply.status == 200 && reply.calls == 1 && reply.closes > 0)
            val location = OfflineTrustBundleFixture.locations.single { request.encodedPath().startsWith("/${it.bucket}/") }
            val key = request.encodedPath().removePrefix("/${location.bucket}/")
            catalog[Triple(location.bucket, key, version)] = ColdCatalogObjectV1(location.bucket, key, version, base64(reply.bytes), reply.headers)
        }
    }
    return catalog
}

/** Raw secret/catalog transport shared by cold TEST histories; never an identity or capability issuer. */
internal class ColdIdentityRawProvidersV1(
    secretObjects: List<ColdSecretObjectV1>, catalogObjects: List<ColdCatalogObjectV1>,
    private val requireReleased: () -> Unit,
) {
    val secrets = AwsSecretVersionFixture()
    val catalog = S3CatalogReadbackFixture()
    init {
        secrets.respond = { request ->
            val fields = request.fields()
            val raw = secretObjects.single { it.arn == fields.getValue("SecretId") && it.version == fields.getValue("VersionId") }
            SecretHttpReply(unbase64(raw.body)).apply { headers = raw.headers }
        }
        catalog.respond = { request ->
            requireReleased()
            assertEquals(SdkHttpMethod.GET, request.method())
            val location = OfflineTrustBundleFixture.locations.single { request.encodedPath().startsWith("/${it.bucket}") }
            assertEquals(location.accountId, request.firstMatchingHeader("x-amz-expected-bucket-owner").orElseThrow())
            assertEquals(S3CatalogReadbackFixture.credentials.sessionToken(), request.firstMatchingHeader("x-amz-security-token").orElseThrow())
            val values = catalogObjects.filter { it.bucket == location.bucket }.sortedBy { it.key }
            if (request.rawQueryParameters().containsKey("versions")) {
                assertEquals(CatalogReadbackProtocol.PREFIX, request.firstMatchingRawQueryParameter("prefix").orElseThrow())
                val maximum = request.firstMatchingRawQueryParameter("max-keys").orElseThrow().toInt()
                assertEquals(1, maximum)
                val cursor = request.firstMatchingRawQueryParameter("key-marker").orElse(null)?.let {
                    CatalogListCursor(it, request.firstMatchingRawQueryParameter("version-id-marker").orElseThrow())
                }
                val offset = if (cursor == null) 0 else values.indexOfFirst { it.key == cursor.keyMarker && it.version == cursor.versionIdMarker }
                    .also { check(it >= 0) } + 1
                val page = values.drop(offset).take(maximum)
                val next = if (offset + page.size < values.size) page.last().let { CatalogListCursor(it.key, it.version) } else null
                catalog.listReply(CatalogListRequest(location, CatalogReadbackProtocol.PREFIX, cursor, maximum),
                    page.map { CatalogListedVersion(it.key, it.version, unbase64(it.body).size.toLong()) }, next)
            } else {
                val raw = values.single { request.encodedPath() == "/${location.bucket}/${it.key}" &&
                    request.firstMatchingRawQueryParameter("versionId").orElseThrow() == it.version }
                S3CatalogReply(unbase64(raw.body)).apply { headers = raw.headers }
            }.apply { beforeCall = requireReleased; beforeRead = requireReleased; onAbort = requireReleased; onClose = requireReleased }
        }
    }

    fun assertClientsClosed() {
        assertEquals(secrets.createdClients, secrets.closedClients); assertEquals(catalog.createdClients, catalog.closedClients)
    }

    fun assertRepliesClosed() {
        secrets.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertTrue(it.closes > 0) }
        catalog.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertTrue(it.closes > 0) }
    }

    fun assertClosed() { assertClientsClosed(); assertRepliesClosed() }
}

/** New HTTP clients over persisted TEST mock objects. No original event/routing owner is reconstructed here. */
internal class ColdRawProvidersV1(private val saved: ColdRawHandoffV1, private val journal: TestOwnerDeleteJournalConfigurationV1) {
    private val identity = ColdIdentityRawProvidersV1(saved.secrets, saved.catalog, ::released)
    val secrets get() = identity.secrets
    val catalog get() = identity.catalog
    val kms = AwsJournalKmsFixture()
    val requests = mutableListOf<JournalPublisherHttpRequest>()
    private val objects = saved.ordinary.map(ColdJournalObjectV1::raw).sortedBy { it.key }
    private val mapper = ObjectMapper()
    private val keys = saved.kms.associate { raw ->
        val reply = mapper.readTree(raw.generateReply)
        reply["CiphertextBlob"].textValue() to (mapper.readTree(raw.generateRequest)["EncryptionContext"] to reply)
    }
    var boundary: () -> Unit = {}
    private var opened = 0
    private var closed = 0

    init {
        check(keys.size == 4)
        kms.beforePrepare = ::released
        kms.onClientClose = ::released
        kms.respond = { request ->
            released()
            assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target(), "Recovery cannot generate another ordinary candidate.")
            val fields = request.fields()
            val (context, generated) = keys.getValue(fields["CiphertextBlob"].textValue())
            assertEquals(context, fields["EncryptionContext"])
            assertEquals(journal.declaration().encryption.keyArn, fields["KeyId"].textValue())
            assertEquals(generated["KeyId"].textValue(), fields["KeyId"].textValue())
            JournalKmsHttpReply(AwsJournalKmsFixture.decryptDocument(fields["KeyId"].textValue(),
                unbase64(generated["Plaintext"].textValue())))
        }
    }

    fun ordinaryClient(): SdkHttpClient {
        released(); opened++
        return journalPublisherRawHttpClient(requests, ::released, {}, { closed++; released() }, ::ordinaryReply)
    }

    private fun ordinaryReply(request: JournalPublisherHttpRequest): S3CatalogReply {
        released()
        val location = journal.declaration().journalLocation
        journalPublisherRawAssertSigned(request, location.region, location.accountId, TestOwnerDeleteJournalPublisherFixture.CREDENTIALS)
        assertTrue(request.body.isEmpty())
        if (request.kind == "GET") {
            val key = URLDecoder.decode(request.http.encodedPath().removePrefix("/${location.bucket}/"), Charsets.UTF_8)
            val version = request.http.firstMatchingRawQueryParameter("versionId").orElseThrow()
            return journalPublisherRawGetReply(location.region, objects.single { it.key == key && it.version == version })
        }
        assertEquals("LIST", request.kind, "Only native inventory/readback is allowed on the old ordinary backing.")
        val query = request.http.rawQueryParameters()
        assertEquals(listOf(journal.ordinaryPrefix), query["prefix"])
        assertEquals(listOf("2"), query["max-keys"]); assertEquals(listOf("url"), query["encoding-type"])
        assertEquals(query.containsKey("key-marker"), query.containsKey("version-id-marker"))
        val marker = query["key-marker"]?.single(); val version = query["version-id-marker"]?.single()
        val offset = if (marker == null) 0 else objects.indexOfFirst { it.key == marker && it.version == version }.also { check(it >= 0) } + 1
        val page = objects.drop(offset).take(2); val more = offset + page.size < objects.size
        return xmlReply(buildString {
            append("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>${location.bucket}</Name><Prefix>${encoded(journal.ordinaryPrefix)}</Prefix>")
            append("<KeyMarker>${marker?.let(::encoded).orEmpty()}</KeyMarker><VersionIdMarker>${version?.let(::xml).orEmpty()}</VersionIdMarker>")
            append("<MaxKeys>2</MaxKeys><IsTruncated>$more</IsTruncated><EncodingType>url</EncodingType>")
            page.forEach { value ->
                append("<Version><Key>${encoded(value.key)}</Key><VersionId>${xml(value.version)}</VersionId><IsLatest>true</IsLatest>")
                append("<LastModified>${value.lastModified}</LastModified><Size>${value.bytes.size}</Size><StorageClass>STANDARD</StorageClass></Version>")
            }
            if (more) append("<NextKeyMarker>${encoded(page.last().key)}</NextKeyMarker><NextVersionIdMarker>${xml(page.last().version)}</NextVersionIdMarker>")
            append("</ListVersionsResult>")
        })
    }

    fun assertClosed() {
        assertEquals(opened, closed)
        identity.assertClientsClosed()
        assertEquals(kms.createdClients, kms.closedClients); assertEquals(kms.createdClients, kms.returnedClientCloses)
        requests.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(1, checkNotNull(it.reply).closes) }
        identity.assertRepliesClosed()
        kms.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertTrue(it.closes > 0) }
    }
    private fun released() { requireConnectionFree(); boundary() }
}

internal fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
internal fun unbase64(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)
