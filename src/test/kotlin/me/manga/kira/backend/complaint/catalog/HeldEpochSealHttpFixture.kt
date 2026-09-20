package me.manga.kira.backend.complaint.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealBootstrapOriginV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealCatalogPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealEventPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealProviderPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

/** Existing raw HTTP SPI fixture twice: genuine STS Query/signing/XML and KMS JSON/signing, never supplied SDK/session/authority. */
internal class HeldEpochSealHttpFixture(consumers: VersionBoundComplaintConsumerConfiguration, val clock: HeldEpochSealClock) {
    val routing = consumers.journalRouting
    private val journal = consumers.journalConfiguration
    val lanes = JournalPublicationLanesV1(journal)
    val sts = AwsJournalKmsFixture(journal)
    val kms = AwsJournalKmsFixture(journal)
    val requests = mutableListOf<String>()
    var stsFactories = 0
        private set
    var kmsFactories = 0
        private set
    var expirationSeconds = 900L
    var boundary: () -> Unit = {}
    var beforeConstruction: () -> Unit = {}
    var beforeStsFactory: (Int) -> Unit = {}
    var beforeKmsFactory: () -> Unit = {}
    var changeStsReply: (Int, JournalKmsHttpReply) -> Unit = { _, _ -> }
    var changeKmsReply: (JournalKmsHttpReply) -> Unit = {}
    private val assertionFailure = AtomicReference<AssertionError?>()
    private var session = ""
    val acquisition = VersionBoundEpochSealAcquisitionV1.withHttpFixture(
        routing = routing,
        publicationLanes = lanes,
        deployment = mapping(consumers),
        bootstrapCredentials = AwsJournalKmsFixture.CREDENTIALS,
        bootstrapSessionName = SOURCE_SESSION,
        stsHttpFactory = ::stsClient,
        kmsHttpFactory = ::kmsClient,
        nanoTime = clock::nanoTime,
        wallClock = { NOW.plusNanos(clock.nanoTime()) },
    )

    init {
        sts.beforePrepare = { preserveAssertions(boundary) }
        kms.beforePrepare = { preserveAssertions(boundary) }
        sts.onClientClose = { preserveAssertions(boundary) }
        kms.onClientClose = { preserveAssertions(boundary) }
        sts.respond = ::stsReply
        kms.respond = { request ->
            preserveAssertions {
                assertEquals(AwsJournalKmsFixture.GENERATE_TARGET, request.target())
                requests.add("KMS_GENERATE")
                signed(request, TARGET, "kms")
                JournalKmsHttpReply(AwsJournalKmsFixture.generateDocument(journal)).also(changeKmsReply)
            }
        }
    }

    private fun stsClient(): SdkHttpClient = preserveAssertions {
        beforeConstruction()
        beforeStsFactory(++stsFactories)
        sts.httpClient()
    }

    private fun kmsClient(): SdkHttpClient = preserveAssertions {
        beforeConstruction()
        kmsFactories++
        beforeKmsFactory()
        kms.httpClient()
    }

    private fun stsReply(request: JournalKmsHttpRequest): JournalKmsHttpReply = preserveAssertions {
        val query = query(request)
        val source = request.http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow() == AwsJournalKmsFixture.CREDENTIALS.sessionToken()
        val stage = if (query.getValue("Action") == "AssumeRole") {
            2
        } else if (source) {
            1
        } else {
            3
        }
        requests.add(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET")[stage - 1])
        signed(request, if (stage == 3) TARGET else AwsJournalKmsFixture.CREDENTIALS, "sts")
        val xml = when (stage) {
            1 -> identity("arn:aws:sts::$ACCOUNT:assumed-role/bootstrap-source/$SOURCE_SESSION", "$SOURCE_ID:$SOURCE_SESSION")

            2 -> {
                session = query.getValue("RoleSessionName")
                assumed(session)
            }

            else -> identity("arn:aws:sts::$ACCOUNT:assumed-role/epoch-sealer/$session", "$TARGET_ID:$session")
        }
        JournalKmsHttpReply(xml).apply {
            headers = mapOf("Content-Type" to listOf("text/xml; charset=utf-8"), "Content-Length" to listOf(bytes.size.toString()))
            changeStsReply(stage, this)
        }
    }

    fun assertProtocol(canonical: JsonObject) {
        val key = canonical.getValue("seal_object_key").jsonPrimitive.content
        val policy = ObjectMapper().readTree(query(sts.requests.last { query(it).getValue("Action") == "AssumeRole" }).getValue("Policy"))
        val bucket = "arn:aws:s3:::${journal.declaration().journalLocation.bucket}"
        val kmsArn = journal.declaration().encryption.keyArn
        assertEquals(
            ObjectMapper().readTree(
                """{
                  "Version":"2012-10-17","Statement":[
                    {"Effect":"Deny","NotAction":["s3:PutObject","s3:PutObjectRetention","s3:GetObjectVersion","s3:GetObjectRetention",
                      "kms:GenerateDataKey","kms:Decrypt","s3:ListBucketVersions","sts:GetCallerIdentity"],"Resource":"*"},
                    {"Effect":"Deny","Action":["s3:*","kms:*"],"NotResource":["$bucket","$bucket/$key","$kmsArn"]},
                    {"Effect":"Deny","Action":"s3:ListBucketVersions","Resource":"$bucket",
                      "Condition":{"StringNotEqualsIfExists":{"s3:prefix":"$key"}}},
                    {"Effect":"Allow","Action":["s3:PutObject","s3:PutObjectRetention","s3:GetObjectVersion","s3:GetObjectRetention",
                      "kms:GenerateDataKey","kms:Decrypt"],"Resource":["$bucket/$key","$kmsArn"]},
                    {"Effect":"Allow","Action":"s3:ListBucketVersions","Resource":"$bucket","Condition":{"StringEquals":{"s3:prefix":"$key"}}}
                  ]}
                """.trimIndent(),
            ),
            policy,
        )
        sts.requests.forEach { request ->
            val fields = query(request)
            assertEquals("2011-06-15", fields.getValue("Version"))
            if (fields.getValue("Action") == "AssumeRole") {
                assertEquals(setOf("Action", "Version", "RoleArn", "RoleSessionName", "DurationSeconds", "Policy"), fields.keys)
                assertEquals("arn:aws:iam::$ACCOUNT:role/epoch-sealer", fields.getValue("RoleArn"))
                assertEquals("900", fields.getValue("DurationSeconds"))
                assertTrue(Regex("kira-seal-[0-9a-f-]{36}").matches(fields.getValue("RoleSessionName")))
            } else {
                assertEquals(mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"), fields)
            }
        }
        assertKmsContext(canonical, kms.requests.last())
    }

    private fun assertKmsContext(canonical: JsonObject, request: JournalKmsHttpRequest) {
        val body = request.fields()
        assertEquals(setOf("KeyId", "KeySpec", "EncryptionContext"), body.fieldNames().asSequence().toSet())
        assertEquals(journal.declaration().encryption.keyArn, body["KeyId"].textValue())
        assertEquals("AES_256", body["KeySpec"].textValue())
        val context = body["EncryptionContext"]
        assertEquals(setOf(AwsJournalKmsFixture.CONTEXT_KEY), context.fieldNames().asSequence().toSet())
        val frame = ByteBuffer.wrap(Base64.getUrlDecoder().decode(context[AwsJournalKmsFixture.CONTEXT_KEY].textValue()))
        val values = buildList {
            while (frame.hasRemaining()) {
                assertTrue(frame.remaining() >= 4)
                val size = frame.int
                assertTrue(size in 0..frame.remaining())
                add(ByteArray(size).also { frame.get(it) }.toString(Charsets.UTF_8))
            }
        }
        val payload = Json.parseToJsonElement(
            HexFormat.of().parseHex(canonical.getValue("seal_bytes").jsonPrimitive.content.removePrefix("\\x")).toString(Charsets.UTF_8),
        ).jsonObject
        val d = journal.declaration()
        val writer = d.writer.generationId
        assertEquals(22, values.size)
        assertEquals(
            listOf(
                "kira-complaint-journal-kms-context-v1", "1", "1", "1", "kcj-1", "EPOCH_SEAL", "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED",
                d.encryption.keyId, d.encryption.keyArn, d.journalLocation.bucket, canonical.getValue("seal_object_key").jsonPrimitive.content,
                writer, "complaints/journal/v1/$writer/live/00000000-0000-0000-0000-000000000000/seal-terminal/", "LIVE",
                "00000000-0000-0000-0000-000000000000", "1", "1", d.routing.activeKeyId, payload.getValue("sealId").jsonPrimitive.content, "",
            ),
            values.dropLast(1),
        )
        assertEquals(12, Base64.getUrlDecoder().decode(values.last()).size)
    }

    fun assertExchangesClosed() {
        (sts.replies + kms.replies).forEach {
            assertEquals(1, it.calls)
            assertEquals(1, it.aborts)
            assertEquals(if (it.bodyPresent) 1 else 0, it.closes)
        }
        assertNoLostAssertions()
    }

    fun assertNoLostAssertions() {
        clock.assertNoLostAssertions()
        assertionFailure.get()?.let { throw it }
    }

    fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertionFailure.compareAndSet(null, failure)
        throw failure
    }

    fun assertSanitized(failure: Throwable) {
        listOf(PRIVATE_TEXT, TARGET.secretAccessKey(), TARGET.sessionToken(), AwsJournalKmsFixture.CREDENTIALS.secretAccessKey()).forEach {
            assertFalse(failure.toString().contains(it))
        }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertNoLostAssertions()
    }

    private fun signed(request: JournalKmsHttpRequest, credentials: AwsSessionCredentials, service: String) {
        assertEquals(SdkHttpMethod.POST, request.http.method())
        assertEquals("https", request.http.protocol())
        assertEquals("$service.us-east-1.amazonaws.com", request.http.host())
        assertTrue(request.http.encodedPath() in setOf("", "/"))
        assertTrue(request.http.rawQueryParameters().isEmpty())
        val authorization = request.http.firstMatchingHeader("Authorization").orElseThrow()
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/"))
        assertTrue(authorization.contains("/us-east-1/$service/aws4_request"))
        assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
    }

    private fun query(request: JournalKmsHttpRequest): Map<String, String> = request.json.split('&').map {
        val split = it.indexOf('=')
        check(split > 0)
        URLDecoder.decode(it.substring(0, split), Charsets.UTF_8) to URLDecoder.decode(it.substring(split + 1), Charsets.UTF_8)
    }.let {
        assertEquals(it.size, it.toMap().size)
        it.toMap()
    }

    private fun identity(arn: String, userId: String): String =
        """<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>""" +
            "<Arn>$arn</Arn><UserId>$userId</UserId><Account>$ACCOUNT</Account></GetCallerIdentityResult>" +
            "<ResponseMetadata><RequestId>synthetic-held-sts</RequestId></ResponseMetadata></GetCallerIdentityResponse>"

    private fun assumed(name: String): String = """<AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><AssumeRoleResult>""" +
        "<Credentials><AccessKeyId>${TARGET.accessKeyId()}</AccessKeyId><SecretAccessKey>${TARGET.secretAccessKey()}</SecretAccessKey>" +
        "<SessionToken>${TARGET.sessionToken()}</SessionToken><Expiration>${NOW.plusNanos(clock.nanoTime()).plusSeconds(expirationSeconds)}</Expiration>" +
        "</Credentials><AssumedRoleUser><AssumedRoleId>$TARGET_ID:$name</AssumedRoleId>" +
        "<Arn>arn:aws:sts::$ACCOUNT:assumed-role/epoch-sealer/$name</Arn></AssumedRoleUser><PackedPolicySize>1</PackedPolicySize>" +
        "</AssumeRoleResult><ResponseMetadata><RequestId>synthetic-held-assume</RequestId></ResponseMetadata></AssumeRoleResponse>"

    private fun mapping(consumers: VersionBoundComplaintConsumerConfiguration): EpochSealDeploymentMappingV1 {
        val authorities = consumers.journalConfiguration.declaration().authorities
        val catalog = VersionBoundCatalogReadbackTestFixture.envelope().manifest.initialWriterRegistry.catalogWriter
        fun role(name: String, id: Char): EpochSealProviderPrincipalV1 =
            EpochSealProviderPrincipalV1.role("arn:aws:iam::$ACCOUNT:role/$name", "AROA" + id.toString().repeat(17))
        return EpochSealDeploymentMappingV1(
            EpochSealEventPrincipalV1(authorities.ordinary, role("ordinary", 'A')),
            EpochSealEventPrincipalV1(authorities.sealTerminal, role("epoch-sealer", 'B')),
            EpochSealEventPrincipalV1(authorities.recovery, role("recovery", 'C')),
            EpochSealCatalogPrincipalV1(catalog.putAuthority, role("catalog-put", 'D')),
            EpochSealCatalogPrincipalV1(catalog.signAuthority, role("catalog-sign", 'E')),
            EpochSealBootstrapOriginV1("bootstrap-origin", 1, "bootstrap-credential", role("bootstrap-source", 'F')),
            InitialPolicyReferenceV1("installed-bundle", 1, "a".repeat(64)),
        )
    }

    companion object {
        const val PRIVATE_TEXT = "synthetic-private-held-sealer-fault"
        private const val ACCOUNT = "123456789012"
        private const val SOURCE_SESSION = "synthetic-source"
        private val SOURCE_ID = "AROA" + "F".repeat(17)
        private val TARGET_ID = "AROA" + "B".repeat(17)
        private val NOW = Instant.parse("2026-09-17T12:00:00Z")
        private val TARGET = AwsSessionCredentials.create("ASIAHELD000000000001", "synthetic-held-target-secret", "synthetic-held-target-session")
    }
}
