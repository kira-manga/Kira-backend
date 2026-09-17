package me.manga.kira.backend.security.aws

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.security.EpochSealTestFixtureV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * Real StsClient Query/signing/XML over the existing synchronous HTTP fixture, never AWS.
 * Locally derived fixture keys are NOT committed intents; these tests establish no G1/D4,
 * sealer authority, installed/effective policy, retention, native timeout or session revocation.
 */
class AwsEpochSealStsAdapterTest {
    @Test
    fun `real regional SDK signs source identity assume role and target identity with returned credentials`() {
        val seal = EpochSealTestFixtureV1()
        val http = AwsJournalKmsFixture(seal.journal).apply { stsReplies() }
        val attempt = seal.codec.startAttempt()
        val key = seal.content(attempt).route.objectKey
        val adapter = http.stsAdapter(seal)
        adapter.use {
            adapter.acquire(key, attempt).use { session ->
                session.checkUsable()
                assertEquals(EXPIRATION, session.expiration)
                assertEquals(ACCOUNT, session.accountId)
                assertEquals(TARGET_ROLE_ID, session.roleId)
                assertTrue(Regex("kira-seal-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(session.sessionName))
                assertEquals(targetArn(session.sessionName), session.arn)
                assertEquals(2, http.createdClients)
                assertEquals(0, http.closedClients)
                assertEquals(listOf("GetCallerIdentity", "AssumeRole", "GetCallerIdentity"), http.requests.map { it.query().getValue("Action") })
                val source = http.requests.first()
                val assume = http.requests[1]
                val target = http.requests.last()
                assertEquals(mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"), source.query())
                assertEquals(source.query(), target.query())
                assertEquals(setOf("Action", "Version", "RoleArn", "RoleSessionName", "DurationSeconds", "Policy"), assume.query().keys)
                assertEquals("2011-06-15", assume.query().getValue("Version"))
                assertEquals(TARGET_ROLE_ARN, assume.query().getValue("RoleArn"))
                assertEquals(session.sessionName, assume.query().getValue("RoleSessionName"))
                assertEquals("900", assume.query().getValue("DurationSeconds"))
                http.requests.forEachIndexed { index, request ->
                    assertEquals(SdkHttpMethod.POST, request.http.method())
                    assertEquals("https", request.http.protocol())
                    assertEquals("sts.us-east-1.amazonaws.com", request.http.host())
                    assertEquals(443, request.http.port())
                    assertEquals("/", request.http.encodedPath())
                    assertTrue(request.http.rawQueryParameters().isEmpty())
                    assertFalse(request.http.firstMatchingHeader("X-Amz-Target").isPresent)
                    val credentials = if (index == 2) TARGET_CREDENTIALS else AwsJournalKmsFixture.CREDENTIALS
                    val authorization = request.http.firstMatchingHeader("Authorization").orElseThrow()
                    assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/"))
                    assertTrue(authorization.contains("/us-east-1/sts/aws4_request"))
                    assertTrue(authorization.contains("x-amz-security-token"))
                    assertTrue(Regex(".*Signature=[0-9a-f]{64}").matches(authorization))
                    assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
                }
                val rendered = listOf(adapter, session, binding(), AwsEpochSealStsLimits()).joinToString()
                listOf(key, SOURCE_ARN, TARGET_ROLE_ARN, TARGET_ROLE_ID, TARGET_CREDENTIALS.sessionToken()).forEach {
                    assertFalse(rendered.contains(it))
                }
                reject { adapter.acquire(key, attempt) }
                assertEquals(3, http.requests.size)
            }
            assertEquals(2, http.closedClients)
            reject { adapter.acquire(key, attempt) }
        }
        assertReleased(http, 3, 2)
    }

    @Test
    fun `source account ARN and stable principal mismatch cannot reach AssumeRole`() {
        listOf<(String) -> String>(
            { it.replace("<Account>$ACCOUNT</Account>", "<Account>123456789013</Account>") },
            { it.replace(SOURCE_ARN, SOURCE_ARN.replace("bootstrap-source", "another-source")) },
            { it.replace(SOURCE_ARN, SOURCE_ARN.replace(ACCOUNT, "123456789013")) },
            { it.replace(SOURCE_USER_ID, SOURCE_USER_ID.replace("BBBB", "CCCC")) },
            { it.replace(SOURCE_USER_ID, SOURCE_USER_ID.substringBefore(':') + ":another-session") },
            { it.replace(SOURCE_ARN, "arn:aws:iam::$ACCOUNT:root") },
        ).forEach { change ->
            rejectResponse(1) { xml -> xmlReply(change(xml)) }
        }
    }

    @Test
    fun `AssumeRole same named recreated role foreign account and mismatched session cannot reach target identity`() {
        listOf<(String) -> String>(
            { it.replace("arn:aws:sts::$ACCOUNT:", "arn:aws:sts::123456789013:") },
            { it.replace("assumed-role/epoch-sealer/", "assumed-role/another-role/") },
            { it.replace(TARGET_ROLE_ID, "AROA" + "R".repeat(17)) },
            { it.replace("<AssumedRoleId>$TARGET_ROLE_ID:", "<AssumedRoleId>$TARGET_ROLE_ID:other-") },
            { it.replace("assumed-role/epoch-sealer/kira-seal-", "assumed-role/epoch-sealer/other-seal-") },
            { it.replace("<Arn>arn:aws:sts::", "<Arn>arn:aws-cn:sts::") },
            { it.replace(Regex("<Arn>[^<]+</Arn>"), "<Arn>$TARGET_ROLE_ARN</Arn>") },
        ).forEach { change ->
            rejectResponse(2) { xml -> xmlReply(change(xml)) }
        }
    }

    @Test
    fun `returned credentials must independently report exact target account ARN and stable role session`() {
        listOf<(String) -> String>(
            { it.replace("<Account>$ACCOUNT</Account>", "<Account>123456789013</Account>") },
            { it.replace("assumed-role/epoch-sealer/", "assumed-role/another-role/") },
            { it.replace(TARGET_ROLE_ID, "AROA" + "R".repeat(17)) },
            { it.replace("<UserId>$TARGET_ROLE_ID:", "<UserId>$TARGET_ROLE_ID:other-") },
            { it.replace("assumed-role/epoch-sealer/kira-seal-", "assumed-role/epoch-sealer/other-seal-") },
            { identity() },
        ).forEach { change ->
            rejectResponse(3) { xml -> xmlReply(change(xml)) }
        }
    }

    @Test
    fun `the entire generated policy confines exact object list prefix actions and KMS key without invented context fields`() {
        val seal = EpochSealTestFixtureV1()
        val http = AwsJournalKmsFixture(seal.journal).apply { stsReplies() }
        val attempt = seal.codec.startAttempt()
        val key = seal.content(attempt).route.objectKey
        http.stsAdapter(seal).use { adapter -> adapter.acquire(key, attempt).close() }
        val policy = http.requests[1].query().getValue("Policy")
        assertTrue(policy.length <= 2048)
        val bucket = "arn:aws:s3:::${seal.bucket}"
        val kms = seal.journal.declaration().encryption.keyArn
        // Independent full document, not the production policy encoder or a subset of its statements.
        val expected = """
            {
              "Version":"2012-10-17",
              "Statement":[
                {"Effect":"Deny","NotAction":[
                  "s3:PutObject","s3:PutObjectRetention","s3:GetObjectVersion","s3:GetObjectRetention",
                  "kms:GenerateDataKey","kms:Decrypt","s3:ListBucketVersions","sts:GetCallerIdentity"
                ],"Resource":"*"},
                {"Effect":"Deny","Action":["s3:*","kms:*"],"NotResource":["$bucket","$bucket/$key","$kms"]},
                {"Effect":"Deny","Action":"s3:ListBucketVersions","Resource":"$bucket",
                  "Condition":{"StringNotEqualsIfExists":{"s3:prefix":"$key"}}},
                {"Effect":"Allow","Action":[
                  "s3:PutObject","s3:PutObjectRetention","s3:GetObjectVersion","s3:GetObjectRetention","kms:GenerateDataKey","kms:Decrypt"
                ],"Resource":["$bucket/$key","$kms"]},
                {"Effect":"Allow","Action":"s3:ListBucketVersions","Resource":"$bucket",
                  "Condition":{"StringEquals":{"s3:prefix":"$key"}}}
              ]
            }
        """.trimIndent()
        assertEquals(ObjectMapper().readTree(expected), ObjectMapper().readTree(policy))
        assertReleased(http, 3, 2)
    }

    @Test
    fun `valid same J long routing id exceeding inline policy ceiling refuses cold rather than removing confinement`() {
        val initial = EpochSealTestFixtureV1.journal().declaration()
        val longId = "r".repeat(64)
        val journal = ComplaintJournalConfigurationV1.of(
            initial.copy(
                routing = initial.routing.copy(
                    activeKeyId = longId,
                    keys = initial.routing.keys.map { if (it.keyId == initial.routing.activeKeyId) it.copy(keyId = longId) else it },
                ),
            ),
        )
        val seal = EpochSealTestFixtureV1(journal)
        val http = AwsJournalKmsFixture(journal)
        val attempt = seal.codec.startAttempt()
        val key = seal.content(attempt).route.objectKey
        assertTrue(key.contains("/$longId/"))
        http.stsAdapter(seal).use { adapter ->
            assertEquals(EpochSealStsFailure.INVALID_INPUT, reject { adapter.acquire(key, attempt) }.code)
        }
        assertReleased(http, 0, 0)
    }

    @Test
    fun `Query preflight cannot change exact policy role duration session or add tags managed policies and duplicate names`() {
        val seal = EpochSealTestFixtureV1()
        val http = AwsJournalKmsFixture(seal.journal).apply { stsReplies() }
        val attempt = seal.codec.startAttempt()
        val key = seal.content(attempt).route.objectKey
        http.stsAdapter(seal).use { adapter -> adapter.acquire(key, attempt).close() }
        val fields = http.requests[1].query()
        val call = EpochSealStsCall(
            "AssumeRole",
            fields - setOf("Action", "Version"),
            EpochSealStsAcquisition(attempt) { http.now },
            2500,
        ) { http.now }
        fun encoded(values: Map<String, String>): ByteArray = values.entries.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }.toByteArray(Charsets.US_ASCII)
        EpochSealStsProtocol.request(http.requests[1].json.toByteArray(), call) {}
        listOf(
            fields + ("Policy" to fields.getValue("Policy").replace(key, "$key*")),
            fields + ("Policy" to fields.getValue("Policy").replace("\"s3:prefix\":\"$key\"", "\"s3:prefix\":\"\"")),
            fields + ("RoleArn" to TARGET_ROLE_ARN.replace("epoch-sealer", "ordinary-writer")),
            fields + ("RoleSessionName" to "caller-selected-session"),
            fields + ("DurationSeconds" to "3600"),
            fields + ("Action" to "AssumeRoleWithWebIdentity"),
            fields + ("Tags.member.1.Key" to "unrequested"),
            fields - "Policy" + ("PolicyArns.member.1.arn" to "arn:aws:iam::aws:policy/AdministratorAccess"),
        ).forEach { changed -> reject { EpochSealStsProtocol.request(encoded(changed), call) {} } }
        val duplicateEncodedName = String(encoded(fields - "DurationSeconds")) +
            "&%50olicy=" + URLEncoder.encode(fields.getValue("Policy"), Charsets.UTF_8)
        reject { EpochSealStsProtocol.request(duplicateEncodedName.toByteArray(), call) {} }
        reject { EpochSealStsProtocol.request(ByteArray(EpochSealStsProtocol.MAX_REQUEST_BYTES + 1) { 'A'.code.toByte() }, call) {} }
        assertReleased(http, 3, 2)
    }

    @Test
    fun `closed XML refuses DTD entity expansion duplicate critical fields nesting namespace and trailing documents`() {
        val valid = identity()
        listOf(
            "", "<GetCallerIdentityResponse/>", valid + valid, valid + PRIVATE_TEXT,
            valid.replace("https://sts.amazonaws.com/doc/2011-06-15/", "https://synthetic-unapproved.invalid/"),
            valid.replace("<Account>$ACCOUNT</Account>", "<Account>$ACCOUNT</Account><Account>$ACCOUNT</Account>"),
            valid.replace("<Arn>$SOURCE_ARN</Arn>", "<Arn>$SOURCE_ARN</Arn><Arn>$SOURCE_ARN</Arn>"),
            valid.replace("<Arn>$SOURCE_ARN</Arn>", "<Arn><Value>$SOURCE_ARN</Value></Arn>"),
            valid.replace("</GetCallerIdentityResult>", "<Unknown>$PRIVATE_TEXT</Unknown></GetCallerIdentityResult>"),
            "<!DOCTYPE GetCallerIdentityResponse>" + valid,
            "<!DOCTYPE GetCallerIdentityResponse [<!ENTITY identity '$SOURCE_ARN'>]>" + valid.replace(SOURCE_ARN, "&identity;"),
            "<!DOCTYPE GetCallerIdentityResponse SYSTEM 'file:///synthetic-sts-must-not-read'>" + valid,
            valid.replace("<Arn>$SOURCE_ARN</Arn>", "<Arn>" + "<Nested>".repeat(100) + SOURCE_ARN + "</Nested>".repeat(100) + "</Arn>"),
        ).forEach { xml -> rejectResponse(1) { xmlReply(xml) } }
        val prefix = valid.substringBefore(SOURCE_ARN).toByteArray()
        val suffix = (SOURCE_ARN.drop(1) + valid.substringAfter(SOURCE_ARN)).toByteArray()
        // Overlong 'a' would yield the correct ARN if UTF-8 were decoded permissively.
        listOf(
            byteArrayOf(0xc1.toByte(), 0xa1.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xe2.toByte(), 0x82.toByte()),
        ).forEach { malformed -> rejectResponse(1) { xmlReply(prefix + malformed + suffix) } }
    }

    @Test
    fun `pinned SDK optional token sizing and deprecated packed size may be absent without becoming policy evidence`() {
        val sizing = "<SessionTokenUtilization>50</SessionTokenUtilization>" +
            "<SessionTokenSize>${TARGET_CREDENTIALS.sessionToken().length}</SessionTokenSize>"
        listOf("none", "new-only", "legacy-and-new").forEach { fields ->
            val seal = EpochSealTestFixtureV1()
            val http = AwsJournalKmsFixture(seal.journal).apply {
                stsReplies { stage, xml ->
                    if (stage != 2) {
                        xmlReply(xml)
                    } else {
                        val withoutLegacy = if (fields == "legacy-and-new") xml else xml.replace("<PackedPolicySize>1</PackedPolicySize>", "")
                        xmlReply(if (fields == "none") withoutLegacy else withoutLegacy.replace("</AssumeRoleResult>", "$sizing</AssumeRoleResult>"))
                    }
                }
            }
            val attempt = seal.codec.startAttempt()
            http.stsAdapter(seal).use { adapter -> adapter.acquire(seal.content(attempt).route.objectKey, attempt).close() }
            assertReleased(http, 3, 2)
        }
    }

    @Test
    fun `credential XML omissions duplicates unrequested fields packed policy overflow and malformed expiry refuse target construction`() {
        listOf<(String) -> String>(
            { it.replace(Regex("<AccessKeyId>[^<]*</AccessKeyId>"), "") },
            { it.replace(TARGET_CREDENTIALS.accessKeyId(), "A") },
            { it.replace(TARGET_CREDENTIALS.accessKeyId(), AwsJournalKmsFixture.CREDENTIALS.accessKeyId()) },
            { it.replace(Regex("<SecretAccessKey>[^<]*</SecretAccessKey>"), "<SecretAccessKey></SecretAccessKey>") },
            { it.replace(Regex("<SessionToken>[^<]*</SessionToken>"), "<SessionToken>bad\n$PRIVATE_TEXT</SessionToken>") },
            { it.replace("</Credentials>", "<AccessKeyId>${TARGET_CREDENTIALS.accessKeyId()}</AccessKeyId></Credentials>") },
            { it.replace("</AssumeRoleResult>", "<SourceIdentity>unrequested</SourceIdentity></AssumeRoleResult>") },
            { it.replace("<PackedPolicySize>1</PackedPolicySize>", "<PackedPolicySize>101</PackedPolicySize>") },
            { it.replace("<PackedPolicySize>1</PackedPolicySize>", "<PackedPolicySize>-1</PackedPolicySize>") },
            { it.replace("<PackedPolicySize>1</PackedPolicySize>", "<PackedPolicySize>1.0</PackedPolicySize>") },
            { it.replace("</AssumeRoleResult>", "<SessionTokenUtilization>101</SessionTokenUtilization></AssumeRoleResult>") },
            { it.replace("</AssumeRoleResult>", "<SessionTokenSize>${TARGET_CREDENTIALS.sessionToken().length + 1}</SessionTokenSize></AssumeRoleResult>") },
            { it.replace("</AssumeRoleResult>", "<SessionTokenSize>16385</SessionTokenSize></AssumeRoleResult>") },
            { it.replace("</AssumeRoleResult>", "<SessionTokenUtilization>50</SessionTokenUtilization>".repeat(2) + "</AssumeRoleResult>") },
            { it.replace("<Expiration>$EXPIRATION</Expiration>", "") },
            { it.replace("<Expiration>$EXPIRATION</Expiration>", "<Expiration>not-an-instant</Expiration>") },
        ).forEach { change -> rejectResponse(2) { xml -> xmlReply(change(xml)) } }
        listOf(NOW.minusSeconds(1), NOW, NOW.plusSeconds(1), NOW.plusSeconds(30), NOW.plusSeconds(902)).forEach { expiration ->
            rejectResponse(2) { xml -> xmlReply(xml.replace(EXPIRATION.toString(), expiration.toString())) }
        }
    }

    @Test
    fun `bounded HTTP refuses redirects errors ambiguous lengths encoding no progress and oversized bodies with one attempt`() {
        listOf("redirect", "error", "length", "duplicate", "encoding", "type", "missing", "zero", "short", "read").forEach { fault ->
            val reply = rejectResponse(2) { xml ->
                xmlReply(xml).apply {
                    when (fault) {
                        "redirect" -> {
                            status = 307
                            headers = headers + ("Location" to listOf("https://synthetic-unapproved.invalid/"))
                        }
                        "error" -> status = 503
                        "length" -> headers = headers + ("Content-Length" to listOf(Long.MAX_VALUE.toString()))
                        "duplicate" -> headers = headers + ("Content-Length" to listOf(bytes.size.toString(), bytes.size.toString()))
                        "encoding" -> headers = headers + ("Content-Encoding" to listOf("gzip"))
                        "type" -> headers = headers + ("Content-Type" to listOf("application/json"))
                        "missing" -> bodyPresent = false
                        "zero" -> chunkSize = 0
                        "short" -> headers = headers + ("Content-Length" to listOf((bytes.size + 1).toString()))
                        else -> beforeRead = { throw IOException(PRIVATE_TEXT) }
                    }
                }
            }
            if (fault in setOf("redirect", "error", "length", "duplicate", "encoding", "type", "missing")) assertEquals(0, reply.reads)
        }
        val maximum = AwsEpochSealStsLimits().maxResponseBytes
        val oversized = rejectResponse(2) {
            xmlReply(ByteArray(maximum + 1000) { ' '.code.toByte() }).apply { headers = headers - "Content-Length" }
        }
        assertEquals(maximum + 1, oversized.bytesRead)
    }

    @Test
    fun `foreign keys routing owners expired attempts and JDBC holders refuse before any HTTP construction`() {
        val seal = EpochSealTestFixtureV1()
        val http = AwsJournalKmsFixture(seal.journal)
        val attempt = seal.codec.startAttempt()
        val key = seal.content(attempt).route.objectKey
        listOf(
            key.replace("/seal-terminal/", "/ordinary/"), key.replace("/live/", "/test/"), key + "*",
            key.replace("0000000000000000042", "0000000000000000000"), key.replace("/route-b/", "/foreign-routing/"), key.dropLast(1) + "B",
        ).forEach { bad -> http.stsAdapter(seal).use { adapter -> reject { adapter.acquire(bad, attempt) } } }
        http.stsAdapter(seal).use { adapter -> reject { adapter.acquire(key, EpochSealTestFixtureV1().codec.startAttempt()) } }
        val adapter = http.stsAdapter(seal)
        TransactionSynchronizationManager.initSynchronization()
        try {
            assertThrows(PersistencePhaseException::class.java) { adapter.acquire(key, attempt) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            adapter.close()
        }
        seal.clock.nanos = 30_000_000_000L
        http.stsAdapter(seal).use { expired ->
            assertEquals(EpochSealStsFailure.DEADLINE_EXHAUSTED, reject { expired.acquire(key, attempt) }.code)
        }
        assertReleased(http, 0, 0)
    }

    @Test
    fun `three individually bounded calls share the original total budget and observed sessions cannot recover a clock rollback`() {
        val seal = EpochSealTestFixtureV1()
        val http = AwsJournalKmsFixture(seal.journal).apply {
            stsReplies { _, xml -> xmlReply(xml).apply { beforeCall = { now += 2_000_000_000L } } }
        }
        val attempt = seal.codec.startAttempt(PersistenceTimeBudget.start(5000, PersistenceNanoClock { http.now }))
        val key = seal.content(attempt).route.objectKey
        http.stsAdapter(seal, wallClock = { NOW.plusNanos(http.now) }).use { adapter ->
            assertEquals(EpochSealStsFailure.DEADLINE_EXHAUSTED, reject { adapter.acquire(key, attempt) }.code)
            reject { adapter.acquire(key, attempt) }
        }
        assertEquals(0, http.replies.last().reads)
        assertReleased(http, 3, 2)
        val other = EpochSealTestFixtureV1()
        val rollback = AwsJournalKmsFixture(other.journal).apply { stsReplies() }
        val retained = other.codec.startAttempt()
        rollback.stsAdapter(other).use { adapter ->
            adapter.acquire(other.content(retained).route.objectKey, retained).use { session ->
                rollback.now = -1
                assertEquals(EpochSealStsFailure.SESSION_EXPIRED, reject(session::checkUsable).code)
                rollback.now = 0
                assertEquals(EpochSealStsFailure.SESSION_EXPIRED, reject(session::checkUsable).code)
            }
        }
        assertReleased(rollback, 3, 2)
    }

    @Test
    fun `target read cancellation wrapped interruption and fatal signals preserve control flow and close every arrived resource`() {
        val interrupted = SdkClientException.builder().message(PRIVATE_TEXT).cause(InterruptedException(PRIVATE_TEXT)).build()
        listOf(CancellationException(PRIVATE_TEXT), interrupted, AssertionError("synthetic-fatal-sts")).forEach { signal ->
            val seal = EpochSealTestFixtureV1()
            val http = AwsJournalKmsFixture(seal.journal).apply {
                stsReplies { stage, xml -> xmlReply(xml).apply { if (stage == 3) beforeRead = { throw signal } } }
            }
            val attempt = seal.codec.startAttempt()
            val adapter = http.stsAdapter(seal)
            val operation = { adapter.acquire(seal.content(attempt).route.objectKey, attempt) }
            try {
                when (signal) {
                    is AssertionError -> assertSame(signal, assertThrows(AssertionError::class.java) { operation() })
                    is CancellationException -> sanitized(assertThrows(CancellationException::class.java) { operation() })
                    else -> {
                        sanitized(assertThrows(InterruptedException::class.java) { operation() })
                        assertTrue(Thread.currentThread().isInterrupted)
                    }
                }
            } finally {
                Thread.interrupted()
                adapter.close()
                Thread.interrupted()
            }
            assertReleased(http, 3, 2)
        }
    }

    @Test
    fun `failed target factory abort body close or client close retains failure without second acquisition or duplicate cleanup`() {
        listOf("factory", "abort", "body", "client").forEach { fault ->
            val seal = EpochSealTestFixtureV1()
            val http = AwsJournalKmsFixture(seal.journal).apply {
                stsReplies { stage, xml ->
                    xmlReply(xml).apply {
                        if (stage == 3 && fault == "abort") onAbort = { throw IOException(PRIVATE_TEXT) }
                        if (stage == 3 && fault == "body") onClose = { throw IOException(PRIVATE_TEXT) }
                    }
                }
                if (fault == "client") onClientClose = { throw IOException(PRIVATE_TEXT) }
            }
            var factories = 0
            val adapter = http.stsAdapter(seal, httpFactory = {
                if (++factories == 2 && fault == "factory") throw IOException(PRIVATE_TEXT)
                http.httpClient()
            })
            val attempt = seal.codec.startAttempt()
            val key = seal.content(attempt).route.objectKey
            if (fault == "client") {
                val session = adapter.acquire(key, attempt)
                reject(session::close)
                reject(session::checkUsable)
            } else {
                reject { adapter.acquire(key, attempt) }
            }
            repeat(2) { reject(adapter::close) }
            reject { adapter.acquire(key, attempt) }
            assertEquals(2, factories)
            assertReleased(http, if (fault == "factory") 2 else 3, if (fault == "factory") 1 else 2)
        }
    }

    private fun AwsJournalKmsFixture.stsAdapter(
        seal: EpochSealTestFixtureV1,
        binding: AwsEpochSealStsBinding = binding(),
        limits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(),
        credentials: AwsSessionCredentials = AwsJournalKmsFixture.CREDENTIALS,
        httpFactory: () -> SdkHttpClient = ::httpClient,
        wallClock: () -> Instant = { NOW },
    ): AwsEpochSealStsAdapter = AwsEpochSealStsAdapter.withHttpFixture(seal.owner, credentials, binding, limits, httpFactory, { now }, wallClock)

    /** Configure only the existing raw HTTP fixture; do not replace SDK clients or identity responses with mocks. */
    private fun AwsJournalKmsFixture.stsReplies(change: (Int, String) -> JournalKmsHttpReply = { _, xml -> xmlReply(xml) }) {
        var session = ""
        respond = { request ->
            val number = requests.size
            val xml = when (number) {
                1 -> identity()
                2 -> {
                    session = request.query().getValue("RoleSessionName")
                    assumed(session)
                }
                3 -> identity(arn = targetArn(session), userId = "$TARGET_ROLE_ID:$session")
                else -> error("Unexpected extra synthetic STS request")
            }
            change(number, xml)
        }
    }

    private fun rejectResponse(stage: Int, change: (String) -> JournalKmsHttpReply): JournalKmsHttpReply {
        val seal = EpochSealTestFixtureV1()
        val http = AwsJournalKmsFixture(seal.journal).apply {
            stsReplies { number, xml -> if (number == stage) change(xml) else xmlReply(xml) }
        }
        val attempt = seal.codec.startAttempt()
        http.stsAdapter(seal).use { adapter -> reject { adapter.acquire(seal.content(attempt).route.objectKey, attempt) } }
        assertReleased(http, stage, if (stage == 3) 2 else 1)
        return http.replies.last()
    }

    private fun assertReleased(http: AwsJournalKmsFixture, requests: Int, clients: Int) {
        assertEquals(requests, http.requests.size)
        assertEquals(clients, http.createdClients)
        assertEquals(clients, http.closedClients)
        http.replies.forEach { reply ->
            assertEquals(1, reply.calls)
            assertEquals(1, reply.aborts)
            assertEquals(if (reply.bodyPresent) 1 else 0, reply.closes)
        }
    }

    private fun reject(action: () -> Unit): EpochSealStsException = assertThrows(EpochSealStsException::class.java) { action() }.also(::sanitized)

    private fun sanitized(failure: Throwable) {
        listOf(PRIVATE_TEXT, SOURCE_ARN, TARGET_ROLE_ARN, TARGET_CREDENTIALS.secretAccessKey(), TARGET_CREDENTIALS.sessionToken()).forEach {
            assertFalse(failure.toString().contains(it))
        }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun JournalKmsHttpRequest.query(): Map<String, String> {
        val pairs = json.split('&').map { field ->
            val split = field.indexOf('=')
            check(split > 0)
            URLDecoder.decode(field.substring(0, split), Charsets.UTF_8) to URLDecoder.decode(field.substring(split + 1), Charsets.UTF_8)
        }
        assertEquals(pairs.size, pairs.toMap().size)
        return pairs.toMap()
    }

    private fun binding(): AwsEpochSealStsBinding = AwsEpochSealStsBinding(ACCOUNT, SOURCE_ARN, SOURCE_USER_ID, TARGET_ROLE_ARN, TARGET_ROLE_ID)

    private fun xmlReply(xml: String): JournalKmsHttpReply = xmlReply(xml.toByteArray(Charsets.UTF_8))

    private fun xmlReply(bytes: ByteArray): JournalKmsHttpReply = JournalKmsHttpReply(bytes).apply {
        headers = mapOf("Content-Type" to listOf("text/xml; charset=utf-8"), "Content-Length" to listOf(bytes.size.toString()))
    }

    private fun identity(account: String = ACCOUNT, arn: String = SOURCE_ARN, userId: String = SOURCE_USER_ID): String =
        """<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>""" +
            "<Arn>$arn</Arn><UserId>$userId</UserId><Account>$account</Account></GetCallerIdentityResult>" +
            "<ResponseMetadata><RequestId>synthetic-request-1</RequestId></ResponseMetadata></GetCallerIdentityResponse>"

    private fun assumed(session: String): String =
        """<AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><AssumeRoleResult>""" +
            "<Credentials><AccessKeyId>${TARGET_CREDENTIALS.accessKeyId()}</AccessKeyId>" +
            "<SecretAccessKey>${TARGET_CREDENTIALS.secretAccessKey()}</SecretAccessKey>" +
            "<SessionToken>${TARGET_CREDENTIALS.sessionToken()}</SessionToken><Expiration>$EXPIRATION</Expiration></Credentials>" +
            "<AssumedRoleUser><AssumedRoleId>$TARGET_ROLE_ID:$session</AssumedRoleId><Arn>${targetArn(session)}</Arn></AssumedRoleUser>" +
            "<PackedPolicySize>1</PackedPolicySize></AssumeRoleResult>" +
            "<ResponseMetadata><RequestId>synthetic-request-2</RequestId></ResponseMetadata></AssumeRoleResponse>"

    private fun targetArn(session: String): String = "arn:aws:sts::$ACCOUNT:assumed-role/epoch-sealer/$session"

    private companion object {
        const val ACCOUNT = "123456789012"
        const val SOURCE_ARN = "arn:aws:sts::123456789012:assumed-role/bootstrap-source/synthetic-source"
        const val TARGET_ROLE_ARN = "arn:aws:iam::123456789012:role/epoch-sealer"
        const val PRIVATE_TEXT = "synthetic-private-sts-provider-text"
        val SOURCE_USER_ID = "AROA" + "B".repeat(17) + ":synthetic-source"
        val TARGET_ROLE_ID = "AROA" + "S".repeat(17)
        val NOW: Instant = Instant.parse("2026-09-17T12:00:00Z")
        val EXPIRATION: Instant = NOW.plusSeconds(900)
        val TARGET_CREDENTIALS: AwsSessionCredentials = AwsSessionCredentials.create(
            "ASIATARGET00000000001",
            "synthetic-sts-target-secret-not-a-real-credential",
            "synthetic-sts-target-session",
        )
    }
}
