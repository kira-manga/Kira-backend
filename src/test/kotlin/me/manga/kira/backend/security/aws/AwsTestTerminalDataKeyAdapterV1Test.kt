package me.manga.kira.backend.security.aws

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.security.JournalDataKeyRequestV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.OwnerDeleteAllJournalFailure
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecTestFixtureV1
import me.manga.kira.backend.security.TestTerminalCryptoReferenceV1
import me.manga.kira.backend.security.TestTerminalCryptoReferenceV1.CONTEXT_KEY
import me.manga.kira.backend.security.TestTerminalCryptoReferenceV1.PRIVATE_TEXT
import me.manga.kira.backend.security.TestTerminalTestFixture
import me.manga.kira.backend.security.documentName
import me.manga.kira.backend.security.terminalCanonical
import me.manga.kira.backend.security.terminalCodecZero
import me.manga.kira.backend.security.terminalFrame
import me.manga.kira.backend.security.terminalText
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.CREDENTIALS
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.DECRYPT_TARGET
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.GENERATE_TARGET
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.base64
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.decryptDocument
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.generateDocument
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.wrappedBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.SdkHttpMethod
import java.io.IOException
import java.util.concurrent.CancellationException

/** Actual SDK with only the existing HTTP SPI substituted; no AWS, S3, activation or producer proof. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AwsTestTerminalDataKeyAdapterV1Test {
    @Test
    fun threeTerminalFamiliesUseExactSignedContextsAndIndependentCryptoWithRetainedRoutes() {
        for (kind in TestTerminalCodecKindV1.entries) {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val http = httpFixture(f)
            val content = f.content(kind, attempt)
            adapter(f, attempt, http).use { owner ->
                assertSame(f.journal, owner.journal)
                assertSame(attempt, owner.attempt)
                assertTrue(http.requests.isEmpty())
                val envelope = f.codec.seal(content, attempt, owner)
                assertArrayEquals(TestTerminalCryptoReferenceV1.wire(kind), envelope.wireBytes(), "raw 32-byte key is not decoded a second time")
                assertSame(content, f.codec.open(f.bucket, content.route.objectKey, content, envelope.wireBytes(), attempt, owner).content)
                assertRequest(http.requests[0], f, GENERATE_TARGET, TestTerminalCryptoReferenceV1.context(kind))
                assertRequest(http.requests[1], f, DECRYPT_TARGET, TestTerminalCryptoReferenceV1.context(kind))

                val retainedRoute = f.source.route(kind.name, 1)
                val retained = f.content(kind, attempt, retainedRoute.terminalText("routing_key_id"))
                val id = if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) "sealId" else "eventId"
                val canonical = terminalCanonical(JsonObject(f.source.value(kind.documentName()) + (id to JsonPrimitive(retainedRoute.terminalText("journal_id")))))
                val header = TestTerminalCryptoReferenceV1.header(kind, retainedRoute, serial = 17)
                val retainedEnvelope = f.codec.seal(retained, attempt, owner)
                assertArrayEquals(canonical, retained.canonicalBytes())
                assertArrayEquals(TestTerminalCryptoReferenceV1.encrypt(kind, header, canonical), retainedEnvelope.wireBytes())
                assertSame(retained, f.codec.open(f.bucket, retained.route.objectKey, retained, retainedEnvelope.wireBytes(), attempt, owner).content)
                assertRequest(http.requests[2], f, GENERATE_TARGET, TestTerminalCryptoReferenceV1.context(kind, header))
                assertRequest(http.requests[3], f, DECRYPT_TARGET, TestTerminalCryptoReferenceV1.context(kind, header))
                assertEquals("test-route-02", retained.route.routingKeyId, "a retained selection is never relabelled active")
                assertEquals(4, http.requests.size)
                http.replies.forEach(::assertReleased)
                assertEquals(0, http.closedClients, "the codec borrows, never closes, the adapter")
                for (privateValue in listOf(f.bucket, f.journal.declaration().encryption.keyArn, TestTerminalTestFixture.SCOPE)) {
                    assertFalse(owner.toString().contains(privateValue))
                }
                retainedEnvelope.close()
                retained.close()
                envelope.close()
                content.close()
            }
            assertEquals(1, http.createdClients)
            assertEquals(1, http.closedClients)
            assertEquals(1, http.returnedClientCloses)
            f.nonces.destinations.forEach(::terminalCodecZero)
        }
    }

    @Test
    fun closedTerminalProfilesRejectCrossFamilyIdentityAndEnforceTheActual6144ByteProviderCap() {
        for (kind in TestTerminalCodecKindV1.entries) {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val http = httpFixture(f)
            val fields = listOf("kira-complaint-journal-kms-context-v1", "1") +
                TestTerminalCryptoReferenceV1.values(kind, TestTerminalCryptoReferenceV1.header(kind))
            val changes = listOf(
                "writerGeneration" to TestTerminalTestFixture.uuid(99), "dataScopeKind" to "LIVE",
                "dataScopeId" to TestTerminalTestFixture.uuid(99), "sealTerminalPrefix" to TestTerminalTestFixture.ordinaryPrefix(),
                "objectKey" to "different/object.kjev", "routingKeyId" to "test-route-02", "kmsKeyArn" to "alias/wrong-key",
                "nonce" to TestTerminalCryptoReferenceV1.url(ByteArray(11)),
            ) + if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) {
                listOf("epochStartInclusive" to "3", "epochEndInclusive" to "0", "sealId" to TestTerminalCryptoReferenceV1.url(ByteArray(31)))
            } else {
                listOf("publicationEpoch" to "03", "eventId" to TestTerminalCryptoReferenceV1.url(ByteArray(31)))
            }
            val invalidContexts = changes.map { (name, value) ->
                framed(fields.toMutableList().also { it[TestTerminalCryptoReferenceV1.order(kind).indexOf(name) + 2] = value })
            } + listOf(
                framed(fields.dropLast(1)), framed(fields + "extra"),
                mapOf(CONTEXT_KEY to TestTerminalCryptoReferenceV1.url(terminalFrame(fields).also { repeat(4) { n -> it[n] = 0xff.toByte() } })),
                mapOf(CONTEXT_KEY to TestTerminalCryptoReferenceV1.context(kind).getValue(CONTEXT_KEY) + "="),
                TestTerminalCryptoReferenceV1.context(kind) + ("caller-context" to "not-permitted"),
            ) + TestTerminalCodecKindV1.entries.filter { it != kind }.map { TestTerminalCryptoReferenceV1.context(it) }
            adapter(f, attempt, http).use { owner ->
                invalidContexts.forEach { rejected { owner.generate(request(f, kind, context = it)) } }
                rejected { owner.generate(request(f, kind, context = ordinaryContext(f))) }
                assertTrue(http.requests.isEmpty(), "$kind has one closed family before HTTP acquisition")
                owner.generate(request(f, kind)).close()
                assertEquals(1, http.requests.size, "local refusals do not strand the undispatched slot")
            }
            val equalJournal = TestOwnerDeleteJournalConfigurationV1.of(f.journal.declaration())
            assertNotSame(f.journal, equalJournal)
            val notOpened = httpFixture(f)
            rejected { AwsTestTerminalDataKeyAdapterV1.withHttpFixture(equalJournal, CREDENTIALS, attempt, notOpened::httpClient) }
            assertEquals(0, notOpened.createdClients, "an equal declaration is not the original acquired journal owner")
        }

        // Minimal affected-family isolation, not a replay of the ordinary TEST or LIVE adapter suite.
        val isolated = TestTerminalCodecTestFixtureV1()
        val ordinaryHttp = httpFixture(isolated)
        AwsTestOwnerDeleteDataKeyAdapterV1.withHttpFixture(isolated.journal, CREDENTIALS, ordinaryHttp::httpClient) { isolated.nanos }.use { owner ->
            rejected { owner.generate(request(isolated, TestTerminalCodecKindV1.INSTALLATION_MANIFEST)) }
            assertTrue(ordinaryHttp.requests.isEmpty())
        }
        val liveHttp = AwsJournalKmsFixture()
        AwsJournalDataKeyAdapter.withEpochSealHttpFixture(liveHttp.journal, CREDENTIALS, liveHttp::httpClient) { liveHttp.now }.use { owner ->
            val requested = JournalDataKeyRequestV1(liveHttp.journal.declaration().encryption.keyArn,
                TestTerminalCryptoReferenceV1.context(TestTerminalCodecKindV1.EPOCH_SEAL), 6144, 1000)
            rejected { owner.generate(requested) }
            assertTrue(liveHttp.requests.isEmpty())
        }

        val wide = isolated.limited(isolated.journal.declaration().limits.decoder.copy(maximumWrappedKeyBytes = 12_288))
        val kind = TestTerminalCodecKindV1.EPOCH_SEAL
        val wideAttempt = wide.attempt(kind)
        val maximum = wrappedBytes(6144)
        val wideHttp = httpFixture(wide, maximum)
        adapter(wide, wideAttempt, wideHttp).use { owner ->
            val requested = request(wide, kind)
            val generated = owner.generate(requested)
            val key = generated.plaintextKey
            val wrapped = generated.wrappedKey
            assertArrayEquals(TestTerminalCryptoReferenceV1.key(), key)
            assertArrayEquals(maximum, wrapped)
            generated.close()
            terminalCodecZero(key)
            terminalCodecZero(wrapped)
            val plaintext = owner.unwrap(requested, maximum)
            val unwrapped = plaintext.plaintextKey
            assertArrayEquals(TestTerminalCryptoReferenceV1.key(), unwrapped)
            plaintext.close()
            terminalCodecZero(unwrapped)
            assertArrayEquals(wrappedBytes(6144), maximum, "adapter never consumes the caller's unwrap input")
            listOf(6145, 12_288).forEach { bound -> rejected { owner.generate(request(wide, kind, maximum = bound)) } }
            val excess = wrappedBytes(6145)
            rejected { owner.unwrap(requested, excess) }
            assertArrayEquals(wrappedBytes(6145), excess)
            assertEquals(2, wideHttp.requests.size)
            wideHttp.replies.forEach(::assertReleased)
        }

        val smaller = isolated.limited(isolated.journal.declaration().limits.decoder.copy(maximumWrappedKeyBytes = 32))
        val smallHttp = httpFixture(smaller)
        adapter(smaller, smaller.attempt(kind), smallHttp).use { owner ->
            rejected { owner.generate(request(smaller, kind, maximum = 33)) }
            assertTrue(smallHttp.requests.isEmpty())
            rejected { owner.generate(request(smaller, kind)) } // The normal 64-byte response cannot widen actual J=32.
            assertEquals(1, smallHttp.requests.size)
            assertReleased(smallHttp.replies.single())
        }

        val arn = wide.journal.declaration().encryption.keyArn
        val valid = generateDocument(arn, TestTerminalCryptoReferenceV1.key(), TestTerminalCryptoReferenceV1.wrapped())
        val invalidResponses = listOf(
            valid.replace(arn, "alias/not-the-pinned-key"),
            generateDocument(arn, ByteArray(31), TestTerminalCryptoReferenceV1.wrapped()),
            generateDocument(arn, ByteArray(33), TestTerminalCryptoReferenceV1.wrapped()),
            generateDocument(arn, TestTerminalCryptoReferenceV1.key(), wrappedBytes(6145)),
            valid.dropLast(1) + ",\"CiphertextForRecipient\":null}",
            valid.dropLast(1) + ",\"KeyMaterialId\":\"${"A".repeat(64)}\"}",
        )
        for (json in invalidResponses) {
            val f = TestTerminalCodecTestFixtureV1(TestTerminalTestFixture(wide.journal))
            rejectReply(f, JournalKmsHttpReply(json))
        }
        rejectReply(isolated, JournalKmsHttpReply(decryptDocument(arn, TestTerminalCryptoReferenceV1.key()).replace("SYMMETRIC_DEFAULT", "AES-256-GCM")), decrypt = true)
        val tooLarge = JournalKmsHttpReply(ByteArray(JournalKmsJsonPreflight.MAX_RESPONSE_BYTES + 1) { ' '.code.toByte() })
        rejectReply(isolated, tooLarge)
        assertEquals(0, tooLarge.reads, "declared over-bound body refuses before any body read")
    }

    @Test
    fun originalBudgetAndConcreteLeaseCleanupSurviveExpiryCancellationAndLateCloseWithoutRetry() {
        val kind = TestTerminalCodecKindV1.EPOCH_SEAL
        run {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind, PersistenceTimeBudget.start(100, PersistenceNanoClock { f.nanos }))
            val http = httpFixture(f)
            f.nanos = 100_000_000
            rejected { adapter(f, attempt, http) }
            assertEquals(0, http.createdClients)
            assertTrue(http.requests.isEmpty())
        }
        run {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind, PersistenceTimeBudget.start(100, PersistenceNanoClock { f.nanos }))
            val http = httpFixture(f)
            val construction = AwsTestTerminalDataKeyAdapterV1.Construction()
            rejected {
                construction.open(f.journal, CREDENTIALS, attempt) { _ ->
                    // The transport's call-scoped timeout callback is not a construction-time clock.
                    http.httpClient().also { f.nanos = 100_000_000 }
                }
            }
            construction.close()
            construction.close()
            assertEquals(1, http.createdClients)
            assertEquals(1, http.closedClients)
            assertEquals(1, http.returnedClientCloses)
            assertTrue(http.requests.isEmpty(), "late returned raw owner is closed without starting a request")
        }
        run {
            val f = TestTerminalCodecTestFixtureV1()
            var enclosingNanos = 0L
            var prepareHooks = 0
            val attempt = f.attempt(kind, PersistenceTimeBudget.start(5000, PersistenceNanoClock { enclosingNanos }))
            val http = httpFixture(f)
            val slow = JournalKmsHttpReply(generateDocument(f.journal.declaration().encryption.keyArn,
                TestTerminalCryptoReferenceV1.key(), TestTerminalCryptoReferenceV1.wrapped())).apply {
                chunkSize = 3
                beforeRead = { enclosingNanos += 7_000_000 }
            }
            http.respond = { slow }
            http.afterPrepare = {
                prepareHooks++
                enclosingNanos = 4_980_000_000
            }
            adapter(f, attempt, http).use { owner ->
                // SDK setup retains actual J's 1000ms; only the original enclosing clock advances at the HTTP boundary.
                enclosingNanos = 4_000_000_000
                rejected { owner.generate(request(f, kind)) }
                assertEquals(1, prepareHooks)
                assertEquals(1, http.requests.size)
                assertEquals(3, slow.reads, "quick reads cannot restart the remaining original 20ms")
                assertEquals(5_001_000_000L, enclosingNanos)
                assertEquals(0L, f.nanos, "local call clock is fixed; each sample must recheck the original enclosing budget")
                assertReleased(slow)
                rejected { owner.generate(request(f, kind)) }
                assertEquals(1, prepareHooks)
                assertEquals(1, http.requests.size)
                assertEquals(3, slow.reads)
            }
            assertEquals(1, http.closedClients)
        }
        run {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val http = httpFixture(f)
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                assertThrows<PersistencePhaseException> { adapter(f, attempt, http) }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            assertEquals(0, http.createdClients)
            adapter(f, attempt, http).use { owner ->
                TransactionSynchronizationManager.setActualTransactionActive(true)
                try {
                    assertThrows<PersistencePhaseException> { owner.generate(request(f, kind)) }
                    assertThrows<PersistencePhaseException> { owner.unwrap(request(f, kind), TestTerminalCryptoReferenceV1.wrapped()) }
                } finally {
                    TransactionSynchronizationManager.setActualTransactionActive(false)
                }
            }
            assertTrue(http.requests.isEmpty())
        }
        run {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val http = httpFixture(f)
            val owner = adapter(f, attempt, http)
            val requested = request(f, kind)
            val first = owner.generate(requested)
            val firstKey = first.plaintextKey
            val firstWrapped = first.wrappedKey
            assertEquals(0, http.replies.single().closes, "returned lease still owns the native slot")
            rejected { owner.generate(requested) }
            rejected { owner.unwrap(requested, TestTerminalCryptoReferenceV1.wrapped()) }
            assertEquals(1, http.requests.size)
            first.close()
            first.close()
            terminalCodecZero(firstKey)
            terminalCodecZero(firstWrapped)
            val next = owner.generate(requested)
            val nextKey = next.plaintextKey
            val nextWrapped = next.wrappedKey
            owner.close()
            owner.close()
            terminalCodecZero(nextKey)
            terminalCodecZero(nextWrapped)
            rejected { owner.generate(requested) }
            assertEquals(2, http.requests.size)
            http.replies.forEach(::assertReleased)
            assertEquals(1, http.closedClients)
        }
        run {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val http = httpFixture(f)
            http.respond = { JournalKmsHttpReply(generateDocument(f.journal.declaration().encryption.keyArn,
                TestTerminalCryptoReferenceV1.key(), TestTerminalCryptoReferenceV1.wrapped())).apply {
                beforeRead = { throw CancellationException(PRIVATE_TEXT) }
            } }
            adapter(f, attempt, http).use { owner ->
                sanitized(assertThrows<CancellationException> { owner.generate(request(f, kind)) })
            }
            assertEquals(1, http.requests.size)
            assertReleased(http.replies.single())
            assertEquals(1, http.closedClients)
        }
        run {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val http = httpFixture(f)
            val owner = adapter(f, attempt, http)
            val lease = owner.generate(request(f, kind))
            val key = lease.plaintextKey
            val wrapped = lease.wrappedKey
            val reply = http.replies.single()
            reply.onClose = {
                terminalCodecZero(key)
                terminalCodecZero(wrapped)
                throw IOException(PRIVATE_TEXT)
            }
            repeat(2) { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { lease.close() } }
            terminalCodecZero(key)
            terminalCodecZero(wrapped)
            rejected { owner.generate(request(f, kind)) }
            repeat(2) { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { owner.close() } }
            assertReleased(reply)
            assertEquals(1, http.requests.size)
            assertEquals(1, http.closedClients)
        }
        run {
            val f = TestTerminalCodecTestFixtureV1()
            val attempt = f.attempt(kind)
            val http = httpFixture(f)
            val owner = adapter(f, attempt, http)
            http.afterPrepare = { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { owner.close() } }
            rejected { owner.generate(request(f, kind)) }
            repeat(2) { rejected(OwnerDeleteAllJournalFailure.KEY_CLEANUP_FAILURE) { owner.close() } }
            rejected { owner.generate(request(f, kind)) }
            val reply = http.replies.single()
            assertEquals(0, reply.calls)
            assertEquals(0, reply.reads)
            assertEquals(0, reply.closes)
            assertEquals(1, reply.aborts)
            assertEquals(1, http.requests.size)
            assertEquals(1, http.closedClients)
        }
    }

    private fun httpFixture(f: TestTerminalCodecTestFixtureV1, wrapped: ByteArray = TestTerminalCryptoReferenceV1.wrapped()): AwsJournalKmsFixture =
        AwsJournalKmsFixture().apply {
            respond = { captured ->
                val arn = f.journal.declaration().encryption.keyArn
                JournalKmsHttpReply(if (captured.target() == DECRYPT_TARGET) decryptDocument(arn, TestTerminalCryptoReferenceV1.key())
                    else generateDocument(arn, TestTerminalCryptoReferenceV1.key(), wrapped))
            }
        }

    private fun adapter(f: TestTerminalCodecTestFixtureV1, attempt: TestTerminalAttemptV1, http: AwsJournalKmsFixture): AwsTestTerminalDataKeyAdapterV1 =
        AwsTestTerminalDataKeyAdapterV1.withHttpFixture(f.journal, CREDENTIALS, attempt, http::httpClient)

    private fun request(f: TestTerminalCodecTestFixtureV1, kind: TestTerminalCodecKindV1,
        context: Map<String, String> = TestTerminalCryptoReferenceV1.context(kind),
        maximum: Int = minOf(6144, f.journal.declaration().limits.decoder.maximumWrappedKeyBytes),
    ): JournalDataKeyRequestV1 = JournalDataKeyRequestV1(f.journal.declaration().encryption.keyArn, context, maximum,
        f.journal.declaration().limits.deadlines.kmsCallMillis)

    private fun framed(fields: List<String>): Map<String, String> = mapOf(CONTEXT_KEY to TestTerminalCryptoReferenceV1.url(terminalFrame(fields)))

    private fun ordinaryContext(f: TestTerminalCodecTestFixtureV1): Map<String, String> {
        val d = f.journal.declaration()
        val id = TestTerminalTestFixture.opaque(77)
        val key = "${TestTerminalTestFixture.ordinaryPrefix()}writer/${d.writer.generationId}/epoch/0000000000000000003/${d.routing.activeKeyId}/$id"
        return framed(AwsJournalKmsFixture.testOwnerDeleteFields(f.journal, key, id, 3, d.routing.activeKeyId,
            TestTerminalCryptoReferenceV1.url(TestTerminalCryptoReferenceV1.nonce())))
    }

    private fun assertRequest(request: JournalKmsHttpRequest, f: TestTerminalCodecTestFixtureV1, target: String, context: Map<String, String>) {
        val http = request.http
        val fields = request.fields()
        val generate = target == GENERATE_TARGET
        assertEquals(if (generate) setOf("KeyId", "KeySpec", "EncryptionContext") else
            setOf("KeyId", "CiphertextBlob", "EncryptionAlgorithm", "EncryptionContext"), fields.fieldNames().asSequence().toSet())
        assertEquals(f.journal.declaration().encryption.keyArn, fields["KeyId"].textValue())
        assertEquals(context.keys, fields["EncryptionContext"].fieldNames().asSequence().toSet())
        assertEquals(context.getValue(CONTEXT_KEY), fields["EncryptionContext"][CONTEXT_KEY].textValue())
        if (generate) assertEquals("AES_256", fields["KeySpec"].textValue()) else {
            assertEquals("SYMMETRIC_DEFAULT", fields["EncryptionAlgorithm"].textValue())
            assertEquals(base64(TestTerminalCryptoReferenceV1.wrapped()), fields["CiphertextBlob"].textValue())
        }
        assertEquals(SdkHttpMethod.POST, http.method())
        assertEquals("https", http.protocol())
        assertEquals("kms.${f.journal.declaration().journalLocation.region}.amazonaws.com", http.host())
        assertEquals(443, http.port())
        assertEquals("/", http.encodedPath())
        assertTrue(http.rawQueryParameters().isEmpty())
        assertEquals(target, request.target())
        assertEquals(CREDENTIALS.sessionToken(), http.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
        val authorization = http.firstMatchingHeader("Authorization").orElseThrow()
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=${CREDENTIALS.accessKeyId()}/"))
        assertTrue(authorization.contains("/${f.journal.declaration().journalLocation.region}/kms/aws4_request"))
        assertTrue(authorization.contains("x-amz-target") && authorization.contains("x-amz-security-token"))
        assertTrue(Regex(".*Signature=[0-9a-f]{64}").matches(authorization))
    }

    private fun rejectReply(f: TestTerminalCodecTestFixtureV1, reply: JournalKmsHttpReply, decrypt: Boolean = false) {
        val kind = TestTerminalCodecKindV1.EPOCH_SEAL
        val attempt = f.attempt(kind)
        val http = httpFixture(f).apply { respond = { reply } }
        adapter(f, attempt, http).use { owner ->
            rejected { if (decrypt) owner.unwrap(request(f, kind), TestTerminalCryptoReferenceV1.wrapped()) else owner.generate(request(f, kind)) }
        }
        assertReleased(reply)
        assertEquals(1, http.requests.size)
        assertEquals(1, http.closedClients)
    }

    private fun assertReleased(reply: JournalKmsHttpReply) {
        assertEquals(1, reply.calls)
        assertEquals(1, reply.aborts)
        assertEquals(1, reply.closes)
    }

    private fun rejected(code: OwnerDeleteAllJournalFailure = OwnerDeleteAllJournalFailure.KEY_FAILURE, action: () -> Unit) {
        val failure = assertThrows<OwnerDeleteAllJournalException> { action() }
        assertEquals(code, failure.code)
        sanitized(failure)
    }

    private fun sanitized(failure: Throwable) {
        assertFalse(failure.toString().contains(PRIVATE_TEXT))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
