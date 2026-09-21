package me.manga.kira.backend.complaint.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogWriterV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealBootstrapOriginV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealCatalogPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealEventPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealProviderPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalHmacRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalKmsRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalTimeBoundV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealRetentionDeclarationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawGetReply
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.complaint.journal.journalPublisherRawListDocument
import me.manga.kira.backend.complaint.journal.journalPublisherRawPutReply
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.net.URLDecoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Existing public raw HTTP SPI fixtures only. TEST authority/retention declarations are explicitly
 * synthetic and cold, BEFORE full-D/PROJECT. No registration, SQL result, SDK result or release is supplied.
 * Actual STS identities, exact-key session policy, SigV4, XML/JSON, KMS and AES-GCM run in the product path.
 */
internal class TestOrdinarySealHttpFixtureV1(
    val horizon: Instant = Instant.parse("2038-01-01T00:00:00Z"),
    val horizonPolicy: InitialPolicyReferenceV1 = policy("synthetic-test-restore-horizon"),
    val protectedIntake: Boolean = false,
    val manifestPublication: Boolean = false,
    val purgePublication: Boolean = false,
) : AutoCloseable {
    val sts = AwsJournalKmsFixture()
    val kms = AwsJournalKmsFixture()
    val requests = mutableListOf<JournalPublisherHttpRequest>()
    val order = mutableListOf<String>()
    var offsetNanos = 0L
    var boundary: () -> Unit = {}
    var nativeBoundary: () -> Unit = {}
    var changeSts: (Int, JournalKmsHttpReply) -> Unit = { _, _ -> }
    var beforeS3: (JournalPublisherHttpRequest) -> Unit = {}
    var changeS3: (JournalPublisherHttpRequest, S3CatalogReply) -> Unit = { _, _ -> }
    var onNativeClose: () -> Unit = {}
    var beforeNativeFactory: (String) -> Unit = {}
    var beforeNativeRequest: (String, () -> Int) -> Unit = { _, _ -> }
    val nativeFactories = mutableListOf<String>()
    val requestBudgets = mutableListOf<Pair<String, Int>>()
    var hideObject = false
    var lostPutAcknowledgment = false
    var stored: JournalPublisherObject? = null
    // Opt-in sibling family only. Keep the original strict seal object/version assertions intact.
    val manifestObjects = linkedMapOf<String, JournalPublisherObject>()
    val purgeObjects = linkedMapOf<String, JournalPublisherObject>()
    var manifestListing: (String, List<JournalPublisherObject>) -> List<JournalPublisherObject> = { _, values -> values }
    var s3Created = 0
        private set
    var s3Closed = 0
        private set
    var s3CloseReturned = 0
        private set
    private var session = ""
    private val assertion = AtomicReference<AssertionError?>()
    private var journal: TestOwnerDeleteJournalConfigurationV1? = null
    private var acquisition: VersionBoundTestOrdinarySealV1? = null
    private var expectedKey: String? = null

    fun now(): Instant = Instant.now().plusNanos(offsetNanos)
    fun nanos(): Long = System.nanoTime() + offsetNanos

    fun owner(routing: TestOwnerDeleteJournalRoutingV1, lanes: JournalPublicationLanesV1, environment: String,
        catalog: InitialCatalogWriterV1): VersionBoundTestOrdinarySealV1 {
        check(!protectedIntake && acquisition == null)
        configure(routing.journalConfiguration)
        val d = routing.journalConfiguration.declaration()
        val retained = TestOrdinarySealRetentionDeclarationV1(environment, routing.journalConfiguration.scope.id.toString(),
            d.writer.generationId, d.writer.databaseIdentity, d.writer.restoreIdentity, horizon, horizonPolicy,
            policy("synthetic-test-object-lock"), d.routing.keys.map { LiveJournalHmacRetentionV1(it, policy("test-hmac-${it.keyId}")) },
            listOf(d.encryption, d.recovery.queue.encryption, d.recovery.deadLetterQueue.encryption).distinct().map {
                LiveJournalKmsRetentionV1(it, policy("test-kms-${it.keyId}"))
            }, LiveJournalTimeBoundV1("test-late-arrival", policy("test-late-arrival-policy"), 1_000),
            LiveJournalTimeBoundV1("test-utc-uncertainty", policy("test-utc-policy"), 1_000))
        val authorities = d.authorities
        fun role(name: String, id: Char) = EpochSealProviderPrincipalV1.role("arn:aws:iam::$ACCOUNT:role/$name", "AROA" + id.toString().repeat(17))
        val deployment = EpochSealDeploymentMappingV1(
            EpochSealEventPrincipalV1(authorities.ordinary, role("test-ordinary", 'A')),
            EpochSealEventPrincipalV1(authorities.sealTerminal, role("test-epoch-sealer", 'B')),
            EpochSealEventPrincipalV1(authorities.recovery, role("test-recovery", 'C')),
            EpochSealCatalogPrincipalV1(catalog.putAuthority, role("test-catalog-put", 'D')),
            EpochSealCatalogPrincipalV1(catalog.signAuthority, role("test-catalog-sign", 'E')),
            EpochSealBootstrapOriginV1("test-bootstrap-origin", 1, "test-bootstrap-credential", role("test-bootstrap", 'F')),
            policy("synthetic-test-installed-bundle"))
        return VersionBoundTestOrdinarySealV1.withHttpFixture(routing, lanes, deployment, retained,
            AwsJournalKmsFixture.CREDENTIALS, SOURCE_SESSION, sts::httpClient, kms::httpClient, ::s3Client,
            nanoTime = ::nanos, wallClock = ::now).also { acquisition = it }
    }

    /** Raw HTTP expectations are fixed before intake; the fixture does not construct/replace the native owner. */
    fun prepareIndependent(journal: TestOwnerDeleteJournalConfigurationV1) {
        check(protectedIntake && acquisition == null)
        configure(journal)
    }

    private fun configure(journal: TestOwnerDeleteJournalConfigurationV1) {
        check(this.journal == null)
        this.journal = journal
        val d = journal.declaration()
        sts.respond = ::stsReply
        sts.beforePrepare = { checked { released() } }
        kms.beforePrepare = { checked { released() } }
        sts.onClientClose = { checked { requireConnectionFree(); nativeBoundary(); onNativeClose() } }
        kms.onClientClose = { checked { requireConnectionFree(); nativeBoundary(); onNativeClose() } }
        kms.respond = { request -> checked {
            signed(request, TARGET, "kms")
            assertEquals(d.encryption.keyArn, request.fields()["KeyId"].textValue())
            val generated = request.target() == AwsJournalKmsFixture.GENERATE_TARGET
            assertTrue(generated || request.target() == AwsJournalKmsFixture.DECRYPT_TARGET)
            order.add(if (generated) "GENERATE" else "DECRYPT")
            JournalKmsHttpReply(if (generated) AwsJournalKmsFixture.generateDocument(d.encryption.keyArn, AwsJournalKmsFixture.keyBytes(), AwsJournalKmsFixture.wrappedBytes())
                else AwsJournalKmsFixture.decryptDocument(d.encryption.keyArn, AwsJournalKmsFixture.keyBytes()))
        } }
    }

    fun nativeSts(remaining: () -> Int): SdkHttpClient = nativeClient("STS", remaining, sts::httpClient)
    fun nativeKms(remaining: () -> Int): SdkHttpClient = nativeClient("KMS", remaining, kms::httpClient)
    fun nativeS3(remaining: () -> Int): SdkHttpClient = nativeClient("S3", remaining, ::s3Client)

    private fun nativeClient(kind: String, remaining: () -> Int, factory: () -> SdkHttpClient): SdkHttpClient {
        check(protectedIntake)
        nativeFactories.add(kind)
        beforeNativeFactory(kind)
        val raw = factory()
        // Like the URL helper, evaluate remaining only at request I/O, after the owning transport exists.
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                val budget = remaining()
                assertTrue(budget > 0)
                requestBudgets.add(kind to budget)
                beforeNativeRequest(kind, remaining)
                raw.prepareRequest(request)
            }
            override fun close() = raw.close()
            override fun clientName(): String = "SyntheticNativeTestSeal$kind"
        }
    }

    private fun s3Client(): SdkHttpClient {
        checked { released() }
        s3Created++
        return journalPublisherRawHttpClient(requests, { checked { released() } }, {}, {
            s3Closed++
            checked { requireConnectionFree(); nativeBoundary(); onNativeClose() }
            s3CloseReturned++
        }, ::s3Reply)
    }

    private fun s3Reply(request: JournalPublisherHttpRequest): S3CatalogReply = checked {
        released()
        beforeS3(request)
        order.add(request.kind)
        val j = checkNotNull(journal)
        val location = j.declaration().journalLocation
        val key = checkNotNull(expectedKey)
        val manifest = "/installation-manifest/" in key
        val purge = "/test-run-purge/" in key
        val existing = if (manifest) manifestObjects[key] else if (purge) purgeObjects[key] else stored
        journalPublisherRawAssertSigned(request, location.region, location.accountId, TARGET)
        assertTrue(key.startsWith(j.sealTerminalPrefix) && ("/epoch-seal/" in key || manifestPublication && manifest || purgePublication && purge))
        assertFalse(request.http.encodedPath().contains("/live/"))
        val reply = when (request.kind) {
            "LIST" -> {
                assertEquals(key, request.http.rawQueryParameters().getValue("prefix").single())
                assertEquals("2", request.http.rawQueryParameters().getValue("max-keys").single())
                val visible = if (hideObject) emptyList() else listOfNotNull(existing)
                OwnerDeleteAllJournalPublisherFixture.xmlReply(journalPublisherRawListDocument(location.bucket, key,
                    if (manifest) manifestListing(key, visible) else visible))
            }
            "GET" -> {
                assertEquals("/${location.bucket}/$key", request.http.encodedPath())
                assertEquals(checkNotNull(existing).version, request.http.rawQueryParameters().getValue("versionId").single())
                journalPublisherRawGetReply(location.region, if (manifest || purge) existing.copy(bytes = existing.bytes.copyOf()) else existing)
            }
            else -> {
                assertEquals("PUT", request.kind)
                assertEquals("/${location.bucket}/$key", request.http.encodedPath())
                assertEquals("*", request.header("If-None-Match"))
                assertEquals("COMPLIANCE", request.header("x-amz-object-lock-mode"))
                if (existing != null) OwnerDeleteAllJournalPublisherFixture.errorReply(412) else {
                    val version = if (manifest) "test-manifest-version-${manifestObjects.size + 1}" else if (purge) "test-purge-version-${purgeObjects.size + 1}" else VERSION
                    val value = JournalPublisherObject(key, version, request.body.copyOf(), now().truncatedTo(ChronoUnit.SECONDS),
                        Instant.parse(request.header("x-amz-object-lock-retain-until-date")),
                        request.http.headers().entries.filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
                            .associate { it.key.lowercase().removePrefix("x-amz-meta-") to it.value.single() })
                    if (manifest) manifestObjects[key] = value else if (purge) purgeObjects[key] = value else stored = value
                    if (lostPutAcknowledgment) OwnerDeleteAllJournalPublisherFixture.errorReply(500) else journalPublisherRawPutReply(value)
                }
            }
        }
        reply.also { changeS3(request, it) }
    }

    private fun stsReply(request: JournalKmsHttpRequest): JournalKmsHttpReply = checked {
        released()
        val fields = query(request)
        val source = request.http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow() == AwsJournalKmsFixture.CREDENTIALS.sessionToken()
        val stage = if (fields.getValue("Action") == "AssumeRole") 2 else if (source) 1 else 3
        order.add(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET")[stage - 1])
        signed(request, if (stage == 3) TARGET else AwsJournalKmsFixture.CREDENTIALS, "sts")
        assertEquals("2011-06-15", fields.getValue("Version"))
        val xml = if (stage == 2) {
            assertEquals(setOf("Action", "Version", "RoleArn", "RoleSessionName", "DurationSeconds", "Policy"), fields.keys)
            assertEquals("arn:aws:iam::$ACCOUNT:role/test-epoch-sealer", fields.getValue("RoleArn"))
            assertEquals("900", fields.getValue("DurationSeconds"))
            session = fields.getValue("RoleSessionName")
            val manifest = session.startsWith("kira-manifest-")
            val purge = session.startsWith("kira-purge-")
            val sessions = listOfNotNull("seal", "manifest".takeIf { manifestPublication }, "purge".takeIf { purgePublication }).joinToString("|")
            assertTrue(Regex("kira-($sessions)-[0-9a-f-]{36}").matches(session))
            val policy = ObjectMapper().readTree(fields.getValue("Policy"))
            expectedKey = policy["Statement"][4]["Condition"]["StringEquals"]["s3:prefix"].textValue()
            assertEquals(manifest, "/installation-manifest/" in checkNotNull(expectedKey))
            assertEquals(purge, "/test-run-purge/" in checkNotNull(expectedKey))
            val location = checkNotNull(journal).declaration().journalLocation
            val resources = policy["Statement"][3]["Resource"].map { it.textValue() }
            assertEquals(listOf("arn:aws:s3:::${location.bucket}/$expectedKey", checkNotNull(journal).declaration().encryption.keyArn), resources)
            """<AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><AssumeRoleResult>""" +
                "<Credentials><AccessKeyId>${TARGET.accessKeyId()}</AccessKeyId><SecretAccessKey>${TARGET.secretAccessKey()}</SecretAccessKey>" +
                "<SessionToken>${TARGET.sessionToken()}</SessionToken><Expiration>${now().plusSeconds(900)}</Expiration></Credentials>" +
                "<AssumedRoleUser><AssumedRoleId>$TARGET_ID:$session</AssumedRoleId><Arn>arn:aws:sts::$ACCOUNT:assumed-role/test-epoch-sealer/$session</Arn>" +
                "</AssumedRoleUser><PackedPolicySize>1</PackedPolicySize></AssumeRoleResult><ResponseMetadata><RequestId>test-seal-assume</RequestId></ResponseMetadata></AssumeRoleResponse>"
        } else {
            assertEquals(setOf("Action", "Version"), fields.keys)
            val name = if (stage == 1) "test-bootstrap/$SOURCE_SESSION" else "test-epoch-sealer/$session"
            val user = if (stage == 1) "$SOURCE_ID:$SOURCE_SESSION" else "$TARGET_ID:$session"
            """<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>""" +
                "<Arn>arn:aws:sts::$ACCOUNT:assumed-role/$name</Arn><UserId>$user</UserId><Account>$ACCOUNT</Account></GetCallerIdentityResult>" +
                "<ResponseMetadata><RequestId>test-seal-identity</RequestId></ResponseMetadata></GetCallerIdentityResponse>"
        }
        JournalKmsHttpReply(xml).apply {
            headers = mapOf("Content-Type" to listOf("text/xml; charset=utf-8"), "Content-Length" to listOf(bytes.size.toString()))
            changeSts(stage, this)
        }
    }

    private fun signed(request: JournalKmsHttpRequest, credentials: AwsSessionCredentials, service: String) {
        assertEquals("https", request.http.protocol())
        assertEquals("$service.${checkNotNull(journal).declaration().journalLocation.region}.amazonaws.com", request.http.host())
        assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val authorization = request.http.firstMatchingHeader("Authorization").orElseThrow()
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/") && "/$service/aws4_request" in authorization)
    }

    private fun released() { requireConnectionFree(); boundary() }
    private fun <T> checked(work: () -> T): T = try { work() } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
    fun assertDisposed(requireReturnedClose: Boolean = true) {
        assertEquals(s3Created, s3Closed)
        if (requireReturnedClose) assertEquals(s3Created, s3CloseReturned)
        listOf(sts, kms).forEach { f ->
            assertEquals(f.createdClients, f.closedClients)
            if (requireReturnedClose) assertEquals(f.createdClients, f.returnedClientCloses)
            f.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(if (it.bodyPresent) 1 else 0, it.closes) }
        }
        requests.forEach { request ->
            assertEquals(1, request.calls); assertEquals(1, request.aborts)
            request.reply?.let { assertEquals(if (request.responseReturned && it.bodyPresent) 1 else 0, it.closes) }
        }
        assertNoLostAssertions()
    }
    override fun close() { acquisition?.close(); assertNoLostAssertions() }

    companion object {
        const val ACCOUNT = "123456789012"
        const val SOURCE_SESSION = "synthetic-test-source"
        const val VERSION = "test-seal-version-1"
        private val SOURCE_ID = "AROA" + "F".repeat(17)
        private val TARGET_ID = "AROA" + "B".repeat(17)
        val TARGET: AwsSessionCredentials = AwsSessionCredentials.create("ASIATESTSEAL00000001", "synthetic-test-seal-secret", "synthetic-test-seal-session")
        fun policy(id: String): InitialPolicyReferenceV1 = InitialPolicyReferenceV1(id, 1, "a".repeat(64))
        fun query(request: JournalKmsHttpRequest): Map<String, String> = request.json.split('&').associate { field ->
            val split = field.indexOf('=')
            URLDecoder.decode(field.substring(0, split), Charsets.UTF_8) to URLDecoder.decode(field.substring(split + 1), Charsets.UTF_8)
        }
    }
}
