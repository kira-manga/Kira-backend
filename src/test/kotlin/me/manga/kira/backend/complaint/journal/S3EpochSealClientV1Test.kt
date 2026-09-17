package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFatalV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.EpochSealS3BindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalPutObservationV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3SdkOwnerV1
import me.manga.kira.backend.complaint.journal.EpochSealS3FixtureV1.Companion.VERSION
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.header
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.httpDate
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.scalar
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.CREDENTIALS
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.PRIVATE_TEXT
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.assertRedacted
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.errorReply
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.xmlReply
import me.manga.kira.backend.security.EpochSealContentV1
import me.manga.kira.backend.security.EpochSealEnvelopeV1
import me.manga.kira.backend.security.EpochSealExceptionV1
import me.manga.kira.backend.security.EpochSealFailureV1
import me.manga.kira.backend.security.EpochSealTestFixtureV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.core.exception.SdkClientException
import java.io.IOException
import java.util.concurrent.CancellationException

/** Real S3/KMS SDKs over existing HTTP fixtures; local encoded candidates are never PREPARED, wire-ready or current-owner authority. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class S3EpochSealClientV1Test {
    @Test
    fun `signed fixed seal PUT LIST and versioned GET authenticate exact key metadata and twenty two field KMS context`() {
        EpochSealS3FixtureV1().use { f ->
            f.client().use { client ->
                val acknowledged = assertInstanceOf(JournalPutObservationV1.Acknowledged::class.java, client.putIfAbsent())
                assertEquals(VERSION, acknowledged.versionId)
                assertEquals(f.envelope.wireSha256, acknowledged.wireSha256)
                f.assertObserved(f.readback(client).verify(acknowledged))
                assertEquals(listOf("PUT", "LIST", "GET"), f.http.requests.map { it.kind })
                f.assertWire()
                f.assertSealKmsContext()
                assertEquals(1, f.http.s3ClientsCreated)
                assertEquals(0, f.http.s3ClientsClosed) // Native exchanges, not the reusable S3 client, close before KMS.
            }
        }
    }

    @Test
    fun `foreign content routing attempt canonical route retention and JDBC holders cannot open a seal HTTP owner`() {
        EpochSealS3FixtureV1().use { f ->
            val foreign = EpochSealTestFixtureV1()
            publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) {
                EpochSealS3BindingV1.encoded(foreign.owner, f.content, f.envelope, f.requested, foreign.codec.startAttempt())
            }
            sealFailure(EpochSealFailureV1.INVALID_INPUT) {
                EpochSealS3BindingV1.encoded(f.local.owner, f.content, f.envelope, f.requested, foreign.codec.startAttempt())
            }
            val restored = f.codec.restoreCanonical(
                f.content.canonicalBytes(),
                f.content.route.routingKeyId,
                f.content.route.objectKey,
                f.content.semanticSha256,
                f.attempt,
            )
            publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) {
                EpochSealS3BindingV1.encoded(f.local.owner, restored, f.envelope, f.requested, f.attempt)
            }
            val forged = EpochSealContentV1(
                f.local.owner,
                f.content.payload,
                f.content.route.copy(objectKey = f.http.event.route.objectKey),
                f.content.canonicalBytes(),
            )
            sealFailure(EpochSealFailureV1.INVALID_INPUT) {
                EpochSealS3BindingV1.encoded(f.local.owner, forged, EpochSealEnvelopeV1(forged, f.envelope.wireBytes()), f.requested, f.attempt)
            }
            publicationFailure(JournalPublicationFailureV1.RETENTION_MISMATCH) {
                EpochSealS3BindingV1.encoded(f.local.owner, f.content, f.envelope, f.requested.plusNanos(1), f.attempt)
            }
            val resource = Any()
            TransactionSynchronizationManager.bindResource(resource, Any())
            try {
                assertThrows<PersistencePhaseException> { f.client() }
            } finally {
                TransactionSynchronizationManager.unbindResource(resource)
            }
            f.http.nanos = f.http.journal.declaration().limits.deadlines.epochSealMillis * 1_000_000L
            sealFailure(EpochSealFailureV1.DEADLINE_EXHAUSTED) { f.client() }
            assertEquals(0, f.http.s3ClientsCreated)
            assertTrue(f.http.requests.isEmpty())
            assertEquals(0, f.http.decrypted())
        }
    }

    @Test
    fun `ordinary SDK custody cannot execute seal operations and seal custody cannot switch even to an equivalent binding`() {
        EpochSealS3FixtureV1().use { f ->
            JournalS3SdkOwnerV1.Construction().use { construction ->
                val ordinary = construction.openOrdinary(f.local.owner, CREDENTIALS, { f.http.httpClient() }, { f.http.nanos }, null)
                publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) { ordinary.listEpochSeal(f.binding) }
                publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) { ordinary.putEpochSeal(f.binding) }
                publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) { ordinary.getEpochSeal(f.binding, VERSION) }
            }
            EpochSealS3BindingV1.encoded(f.local.owner, f.content, f.envelope, f.requested, f.attempt).use { equivalent ->
                JournalS3SdkOwnerV1.Construction().use { construction ->
                    val seal = construction.openEpochSealFixture(f.binding, CREDENTIALS, f.http::httpClient) { f.http.nanos }
                    publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) { seal.listEpochSeal(equivalent) }
                    publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) { seal.putEpochSeal(equivalent) }
                    publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) { seal.getEpochSeal(equivalent, VERSION) }
                }
            }
            assertEquals(2, f.http.s3ClientsCreated)
            assertTrue(f.http.requests.isEmpty())
            assertEquals(0, f.http.decrypted())
        }
    }

    @Test
    fun `candidate copies remain byte and metadata identical across explicit calls and closing a client never renews or discards the binding`() {
        EpochSealS3FixtureV1().use { f ->
            f.envelope.wireBytes().fill(0)
            f.content.canonicalBytes().fill(0)
            f.binding.candidate.bytes().fill(0)
            assertArrayEquals(f.envelope.wireBytes(), f.binding.candidate.bytes())
            assertEquals(f.metadata(), f.binding.candidate.metadata())
            f.http.respond = { errorReply(409) }
            repeat(2) {
                f.client().use { client -> assertSame(JournalPutObservationV1.Conflict, client.putIfAbsent()) }
                f.http.wall = f.http.wall.plusSeconds(3600)
            }
            assertEquals(listOf("PUT", "PUT"), f.http.requests.map { it.kind })
            assertArrayEquals(f.http.requests[0].body, f.http.requests[1].body)
            f.assertWire() // Includes the original, not the advanced wall clock's retention date.
            assertEquals(1, f.http.generated())
            f.binding.close()
            publicationFailure(JournalPublicationFailureV1.INVALID_PUT) { f.binding.candidate.bytes() }
            publicationFailure(JournalPublicationFailureV1.INVALID_BINDING) { f.client() }
            assertEquals(2, f.http.s3ClientsCreated)
        }
    }

    @Test
    fun `conditional conflict precondition failure server error and committed lost reply remain single PUT observations without implicit retry or adoption`() {
        val outcomes = listOf(
            409 to JournalPutObservationV1.Conflict,
            412 to JournalPutObservationV1.PreconditionFailed,
            503 to JournalPutObservationV1.Uncertain,
            null to JournalPutObservationV1.Uncertain,
        )
        outcomes.forEach { (status, expected) ->
            EpochSealS3FixtureV1().use { f ->
                f.http.respond = { request ->
                    f.http.stored = f.objectFor(request.body) // A competing or lost-reply immutable object is only fixture state.
                    if (status == null) throw IOException(PRIVATE_TEXT)
                    errorReply(status)
                }
                f.client().use { client -> assertSame(expected, client.putIfAbsent()) }
                assertEquals(listOf("PUT"), f.http.requests.map { it.kind })
                assertEquals(0, f.http.decrypted())
                f.assertWire()
            }
        }
    }

    @Test
    fun `no acknowledgment permits exact existing wire with extended compliance but rejects authentic same plaintext encrypted differently before KMS`() {
        EpochSealS3FixtureV1().use { f ->
            f.http.stored = f.objectFor().copy(retainUntil = f.requested.plusSeconds(86_400))
            f.http.respond = { request -> f.reply(request).apply { if (request.kind == "GET") header(this, "x-amz-checksum-type", null) } }
            f.client().use { client -> f.assertObserved(f.readback(client).verify()) }
            assertEquals(listOf("LIST", "GET"), f.http.requests.map { it.kind })
            assertEquals(1, f.http.generated())
            assertEquals(1, f.http.decrypted())
            f.assertWire()
        }
        EpochSealS3FixtureV1().use { f ->
            val different = f.codec.seal(f.content, f.attempt)
            assertNotEquals(f.envelope.wireSha256, different.wireSha256)
            val opened = f.codec.open(f.local.bucket, f.content.route.objectKey, f.content, different.wireBytes(), f.attempt)
            assertArrayEquals(f.content.canonicalBytes(), opened.content.canonicalBytes()) // Genuinely authentic, not corrupt test bytes.
            f.http.stored = f.objectFor(different.wireBytes())
            val before = f.http.decrypted()
            f.client().use { client -> publicationFailure(JournalPublicationFailureV1.CONFLICT) { f.readback(client).verify() } }
            assertEquals(before, f.http.decrypted())
            assertEquals(listOf("LIST", "GET"), f.http.requests.map { it.kind })
            assertEquals(2, f.http.generated())
        }
    }

    @Test
    fun `acknowledgment version and wire hash are cross checked rather than replacing fresh exact LIST and GET evidence`() {
        listOf(true, false).forEach { wrongVersion ->
            EpochSealS3FixtureV1().use { f ->
                f.http.stored = f.objectFor()
                val acknowledgment = JournalPutObservationV1.Acknowledged(
                    if (wrongVersion) "other-version" else VERSION,
                    if (wrongVersion) f.envelope.wireSha256 else "f".repeat(64),
                )
                f.client().use { client -> publicationFailure(JournalPublicationFailureV1.CONFLICT) { f.readback(client).verify(acknowledgment) } }
                assertEquals(if (wrongVersion) listOf("LIST") else listOf("LIST", "GET"), f.http.requests.map { it.kind })
                assertEquals(0, f.http.decrypted())
            }
        }
    }

    @Test
    fun `only complete empty LIST is absence and ordinary sibling extra versions markers truncation or duplicate fields cannot reach GET`() {
        EpochSealS3FixtureV1().use { f ->
            f.client().use { client ->
                assertNull(client.listExact())
                publicationFailure(JournalPublicationFailureV1.UNRESOLVED) { f.readback(client).verify() }
            }
            assertEquals(listOf("LIST", "LIST"), f.http.requests.map { it.kind })
            assertEquals(0, f.http.decrypted())
        }
        listOf("ordinary", "sibling", "two", "marker", "truncated", "next", "not-latest", "duplicate").forEach { mode ->
            EpochSealS3FixtureV1().use { f ->
                val value = f.objectFor()
                val good = f.listDocument(listOf(value))
                val document = when (mode) {
                    "ordinary" -> f.listDocument(listOf(value.copy(key = f.http.event.route.objectKey)))
                    "sibling" -> f.listDocument(listOf(value.copy(key = value.key + "-sibling")))
                    "two" -> f.listDocument(listOf(value, value.copy(version = "second-version")))
                    "marker" -> good.replace("</ListVersionsResult>", "<DeleteMarker></DeleteMarker></ListVersionsResult>")
                    "truncated" -> scalar(good, "IsTruncated", "true")
                    "next" -> good.replace("</ListVersionsResult>", "<NextKeyMarker>other</NextKeyMarker></ListVersionsResult>")
                    "not-latest" -> scalar(good, "IsLatest", "false")
                    else -> good.replace("</Version>", "<VersionId>duplicate-version</VersionId></Version>")
                }
                f.http.respond = { xmlReply(document) }
                f.client().use { client -> publicationFailure(JournalPublicationFailureV1.INVALID_LISTING) { f.readback(client).verify() } }
                assertEquals(listOf("LIST"), f.http.requests.map { it.kind })
                assertEquals(0, f.http.decrypted())
            }
        }
    }

    @Test
    fun `exact metadata version content type checksum and necessary retention checks all precede seal KMS unwrap`() {
        val modes = listOf("schema", "id", "metadata-hash", "metadata-date", "extra", "version", "type", "checksum", "mode", "short", "modified")
        modes.forEach { mode ->
            EpochSealS3FixtureV1().use { f ->
                f.http.stored = f.objectFor()
                val changed = when (mode) {
                    "schema" -> "x-amz-meta-kira-journal-schema" to "2"
                    "id" -> "x-amz-meta-kira-journal-event-id" to f.http.event.route.eventId
                    "metadata-hash" -> "x-amz-meta-kira-journal-ciphertext-sha256" to "f".repeat(64)
                    "metadata-date" -> "x-amz-meta-kira-journal-retain-until" to f.requested.minusSeconds(1).toString()
                    "extra" -> "x-amz-meta-caller-added" to "not-accepted"
                    "version" -> "x-amz-version-id" to "not-the-listed-version"
                    "type" -> "Content-Type" to "text/plain"
                    "checksum" -> "x-amz-checksum-sha256" to null
                    "mode" -> "x-amz-object-lock-mode" to "GOVERNANCE"
                    "short" -> "x-amz-object-lock-retain-until-date" to f.requested.minusSeconds(1).toString()
                    else -> "Last-Modified" to httpDate(f.created.minusSeconds(1))
                }
                f.http.respond = { request -> f.reply(request).apply { if (request.kind == "GET") header(this, changed.first, changed.second) } }
                val expected = when (mode) {
                    "checksum" -> JournalPublicationFailureV1.CHECKSUM_MISMATCH
                    "mode", "short", "modified" -> JournalPublicationFailureV1.RETENTION_MISMATCH
                    else -> JournalPublicationFailureV1.INVALID_READBACK
                }
                f.client().use { client -> publicationFailure(expected) { f.readback(client).verify() } }
                assertEquals(listOf("LIST", "GET"), f.http.requests.map { it.kind })
                assertEquals(0, f.http.decrypted())
            }
        }
        EpochSealS3FixtureV1(retentionSeconds = 1).use { f ->
            f.http.stored = f.objectFor() // Exact candidate metadata and requested date do not establish the necessary J-relative floor.
            f.client().use { client -> publicationFailure(JournalPublicationFailureV1.RETENTION_MISMATCH) { f.readback(client).verify() } }
            assertEquals(0, f.http.decrypted())
        }
    }

    @Test
    fun `detached GET bytes clear on close while failed native abort or body close prevents unwrap and remains sticky without duplicate cleanup`() {
        EpochSealS3FixtureV1().use { f ->
            f.http.stored = f.objectFor()
            f.client().use { client ->
                val fetched = client.getVersion(VERSION)
                val detached = fetched.bytes
                assertArrayEquals(f.envelope.wireBytes(), detached)
                f.http.assertClosedExchanges()
                fetched.close()
                assertTrue(detached.all { it == 0.toByte() })
                assertArrayEquals(f.envelope.wireBytes(), checkNotNull(f.http.stored).bytes)
            }
        }
        listOf(true, false).forEach { abortFails ->
            EpochSealS3FixtureV1().use { f ->
                f.http.stored = f.objectFor()
                val failed = f.http.getReply()
                if (abortFails) failed.onAbort = { throw IOException(PRIVATE_TEXT) } else failed.onClose = { throw IOException(PRIVATE_TEXT) }
                f.http.respond = { request -> if (request.kind == "GET") failed else f.reply(request) }
                val client = f.client()
                try {
                    publicationFailure(JournalPublicationFailureV1.CLEANUP_FAILURE) { f.readback(client).verify() }
                    assertEquals(0, f.http.decrypted())
                    assertThrows<JournalPublicationExceptionV1> { client.listExact() }
                    repeat(2) { publicationFailure(JournalPublicationFailureV1.CLEANUP_FAILURE) { client.close() } }
                    assertEquals(listOf("LIST", "GET"), f.http.requests.map { it.kind })
                    val reply = checkNotNull(f.http.requests.last().reply)
                    assertEquals(1, reply.aborts)
                    assertEquals(1, reply.closes)
                    assertEquals(1, f.http.s3ClientsClosed)
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    @Test
    fun `SDK wrapped seal failures cancellation interruption and fatal signals preserve control flow and close every arrived S3 exchange`() {
        val signals = listOf(
            EpochSealExceptionV1(EpochSealFailureV1.DEADLINE_EXHAUSTED),
            CancellationException(PRIVATE_TEXT),
            InterruptedException(PRIVATE_TEXT),
            LinkageError(PRIVATE_TEXT),
        )
        signals.forEach { signal ->
            EpochSealS3FixtureV1().use { f ->
                f.http.stored = f.objectFor()
                val failed = f.http.getReply()
                failed.beforeRead = { throw SdkClientException.builder().message(PRIVATE_TEXT).cause(signal).build() }
                f.http.respond = { request -> if (request.kind == "GET") failed else f.reply(request) }
                val client = f.client()
                try {
                    val failure = checkNotNull(runCatching { f.readback(client).verify() }.exceptionOrNull())
                    when (signal) {
                        is EpochSealExceptionV1 -> assertSame(signal, assertInstanceOf(EpochSealExceptionV1::class.java, failure))

                        is CancellationException -> assertInstanceOf(CancellationException::class.java, failure)

                        is InterruptedException -> {
                            assertInstanceOf(InterruptedException::class.java, failure)
                            assertTrue(Thread.currentThread().isInterrupted)
                        }

                        else -> assertInstanceOf(JournalPublicationFatalV1::class.java, failure)
                    }
                    assertRedacted(failure)
                    assertEquals(0, f.http.decrypted())
                    assertEquals(listOf("LIST", "GET"), f.http.requests.map { it.kind })
                    f.http.assertClosedExchanges()
                } finally {
                    Thread.interrupted()
                    client.close()
                }
            }
        }
    }

    @Test
    fun `LIST and GET consume the original seal or enclosing total and expired observations cannot recover after rollback or a fresh subcall`() {
        listOf<Long?>(null, 900).forEach { outerMillis ->
            EpochSealS3FixtureV1(outerMillis).use { f ->
                f.http.stored = f.objectFor()
                val total = (outerMillis ?: f.http.journal.declaration().limits.deadlines.epochSealMillis.toLong()) * 1_000_000L
                f.http.nanos = total - 900_000_000L
                f.http.respond = { request ->
                    val reply = f.reply(request)
                    f.http.nanos = if (request.kind == "LIST") total - 500_000_000L else total
                    reply
                }
                f.client().use { client ->
                    sealFailure(EpochSealFailureV1.DEADLINE_EXHAUSTED) { f.readback(client).verify() }
                    assertEquals(listOf("LIST", "GET"), f.http.requests.map { it.kind })
                    assertEquals(0, f.http.decrypted())
                    f.http.nanos = 0
                    sealFailure(EpochSealFailureV1.DEADLINE_EXHAUSTED) { client.putIfAbsent() }
                    assertEquals(2, f.http.requests.size)
                }
            }
        }
        EpochSealS3FixtureV1().use { f ->
            val reply = xmlReply(f.listDocument())
            reply.beforeRead = { f.http.nanos = f.http.journal.declaration().limits.deadlines.s3CallMillis * 1_000_000L }
            f.http.respond = { reply }
            f.client().use { client -> publicationFailure(JournalPublicationFailureV1.DEADLINE_EXHAUSTED) { client.listExact() } }
            assertEquals(listOf("LIST"), f.http.requests.map { it.kind })
            assertFalse(f.http.nanos >= f.http.journal.declaration().limits.deadlines.epochSealMillis * 1_000_000L)
        }
    }

    private fun publicationFailure(code: JournalPublicationFailureV1, action: () -> Unit) {
        val failure = assertThrows<JournalPublicationExceptionV1> { action() }
        assertEquals(code, failure.code)
        assertRedacted(failure)
    }

    private fun sealFailure(code: EpochSealFailureV1, action: () -> Unit) {
        val failure = assertThrows<EpochSealExceptionV1> { action() }
        assertEquals(code, failure.code)
        assertRedacted(failure)
    }
}
