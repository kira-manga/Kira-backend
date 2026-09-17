package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFatalV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalReadbackV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.PRIVATE_TEXT
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.assertRedacted
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.checksum
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.errorReply
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.hash
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.OwnerDeleteAllJournalFailure
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.SdkHttpMethod
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CancellationException

/** Shared assertions only; the exact eight tests retain their existing fixtures, call order and budgets. */
internal object OwnerDeleteAllJournalPublisherAssertions {
    fun assertConflictRetries(fixture: OwnerDeleteAllJournalPublisherFixture, work: CommittedOwnerDeleteAllWork.Prepared) {
        for (first in listOf("409", "LOST", "503", "OLD_WINNER")) {
            fixture.reset()
            val older = if (first == "OLD_WINNER") fixture.objectFor(fixture.envelope(), "older-%2F+&=winner") else null
            var puts = 0
            var lists = 0
            fixture.respond = { request ->
                when {
                    request.kind == "PUT" && ++puts == 1 -> if (first == "LOST") {
                        errorReply(503).apply { beforeCall = { throw IOException(PRIVATE_TEXT) } }
                    } else {
                        errorReply(if (first == "503") 503 else 409)
                    }

                    request.kind == "LIST" && ++lists == 2 && older != null -> fixture.listReply(emptyList()).apply {
                        onClose = { fixture.stored = older } // An old dispatched conditional PUT wins AFTER the valid empty inventory.
                    }

                    else -> fixture.statefulReply(request)
                }
            }
            fixture.publisher().use { publisher ->
                assertEvidence(fixture, publisher.publish(work))
                assertEquals(listOf("LIST", "PUT", "LIST", "PUT", "LIST", "GET"), fixture.requests.map { it.kind }, first)
                assertSameCandidate(fixture)
                if (older != null) {
                    assertEquals(412, checkNotNull(fixture.requests.last { it.kind == "PUT" }.reply).status)
                    assertArrayEquals(older.bytes, checkNotNull(fixture.stored).bytes)
                }
            }
        }
    }

    fun assertUnresolvedReconciliation(fixture: OwnerDeleteAllJournalPublisherFixture, work: CommittedOwnerDeleteAllWork.Prepared) {
        for (mode in listOf("BOTH_LOST", "ACK_EMPTY", "PRECONDITION_EMPTY", "LIST_403", "GET_404")) {
            fixture.reset()
            fixture.respond = { request ->
                when {
                    mode == "LIST_403" -> errorReply(403)

                    mode == "GET_404" && request.kind == "GET" -> errorReply(404)

                    request.kind == "PUT" && mode == "BOTH_LOST" -> errorReply(503).apply {
                        beforeCall = { throw IOException(PRIVATE_TEXT) }
                    }

                    request.kind == "PUT" && mode == "PRECONDITION_EMPTY" -> errorReply(412)

                    request.kind == "PUT" && mode == "ACK_EMPTY" -> fixture.statefulReply(request).also { fixture.stored = null }

                    else -> fixture.statefulReply(request)
                }
            }
            fixture.publisher().use { publisher ->
                val failure = assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) }
                assertRedacted(failure)
                assertEquals(
                    if (mode == "BOTH_LOST") {
                        2
                    } else if (mode == "LIST_403") {
                        0
                    } else {
                        1
                    },
                    fixture.requests.count { it.kind == "PUT" },
                )
                assertTrue(fixture.requests.count { it.kind == "LIST" } <= 3)
                assertTrue(fixture.requests.count { it.kind == "GET" } <= 1)
                if (mode == "BOTH_LOST") assertSameCandidate(fixture)
                if (mode.endsWith("EMPTY") || mode == "BOTH_LOST") assertEquals(JournalPublicationFailureV1.UNRESOLVED, failure.code)
                val calls = fixture.requests.size
                assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) }
                assertEquals(calls, fixture.requests.size) // A failed local owner cannot start a fresh attempt.
            }
        }
    }

    fun assertRealCrypto(
        fixture: OwnerDeleteAllJournalPublisherFixture,
        work: CommittedOwnerDeleteAllWork.Prepared,
        original: JournalPublisherObject,
    ) {
        for (mode in listOf("OTHER_TARGETS", "WRONG_TUPLE", "TAMPERED_TAG")) {
            fixture.reset()
            val bytes = when (mode) {
                "OTHER_TARGETS" -> fixture.envelope(listOf(UUID.randomUUID()))
                "WRONG_TUPLE" -> fixture.envelope(tuple = fixture.auth.journalTuple(fixture.candidate, epoch = 12))
                else -> original.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            }
            fixture.stored = fixture.objectFor(bytes)
            val before = fixture.decrypted()
            fixture.publisher().use { publisher ->
                val failure = assertThrows<RuntimeException> { publisher.publish(work) }
                assertRedacted(failure)
                when (mode) {
                    "OTHER_TARGETS" -> assertEquals(
                        JournalPublicationFailureV1.CONFLICT,
                        assertInstanceOf(JournalPublicationExceptionV1::class.java, failure).code,
                    )

                    "TAMPERED_TAG" -> assertEquals(
                        OwnerDeleteAllJournalFailure.AUTHENTICATION_FAILED,
                        assertInstanceOf(OwnerDeleteAllJournalException::class.java, failure).code,
                    )

                    else -> assertInstanceOf(OwnerDeleteAllJournalException::class.java, failure)
                }
            }
            assertEquals(before + if (mode == "WRONG_TUPLE") 0 else 1, fixture.decrypted(), mode)
            assertEquals(listOf("LIST", "GET"), fixture.requests.map { it.kind })
        }
        fixture.reset()
        fixture.stored = original
        fixture.respond = { request ->
            fixture.statefulReply(request).also { if (request.kind == "GET") header(it, "x-amz-checksum-type", null) }
        }
        fixture.publisher().use { assertEvidence(fixture, it.publish(work)) } // Absent type still requires exact full-wire canonical SHA256.

        fixture.reset()
        fixture.wall = original.lastModified.plusSeconds(401L * 86_400).plusNanos(123_456_789)
        fixture.stored = original.copy(retainUntil = original.lastModified.plusSeconds(450L * 86_400))
        val before = fixture.generated()
        fixture.publisher().use { publisher ->
            val adopted = publisher.publish(work)
            assertEvidence(fixture, adopted)
            assertEquals(original.version, adopted.versionId)
            assertEquals(hash(original.bytes), adopted.wireSha256)
            assertEquals(original.metadata, checkNotNull(fixture.stored).metadata)
            assertTrue(adopted.retainUntil.isBefore(fixture.wall.plusSeconds(400L * 86_400))) // Retry does not restart 400 days.
        }
        assertEquals(before, fixture.generated())
        assertEquals(listOf("LIST", "GET"), fixture.requests.map { it.kind })
    }

    fun assertListReply(
        fixture: OwnerDeleteAllJournalPublisherFixture,
        work: CommittedOwnerDeleteAllWork.Prepared,
        reply: S3CatalogReply,
        accepted: Boolean,
    ) {
        fixture.reset()
        var used = false
        val generated = fixture.generated()
        fixture.respond = { request ->
            if (request.kind == "LIST" && !used) reply.also { used = true } else fixture.statefulReply(request)
        }
        fixture.publisher().use { publisher ->
            if (accepted) {
                assertEvidence(fixture, publisher.publish(work))
            } else {
                assertRedacted(assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) })
            }
        }
        assertTrue(used)
        assertEquals(generated + if (accepted) 1 else 0, fixture.generated())
        assertEquals(if (accepted) listOf("LIST", "PUT", "LIST", "GET") else listOf("LIST"), fixture.requests.map { it.kind })
        fixture.assertClosedExchanges()
    }

    fun assertPutError(
        fixture: OwnerDeleteAllJournalPublisherFixture,
        work: CommittedOwnerDeleteAllWork.Prepared,
        reply: S3CatalogReply,
        accepted: Boolean,
    ) {
        fixture.reset()
        var used = false
        fixture.respond = { request ->
            if (request.kind == "PUT" && !used) reply.also { used = true } else fixture.statefulReply(request)
        }
        fixture.publisher().use { publisher ->
            if (accepted) {
                assertEvidence(fixture, publisher.publish(work))
                assertSameCandidate(fixture)
            } else {
                assertRedacted(assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) })
                assertEquals(listOf("LIST", "PUT"), fixture.requests.map { it.kind })
            }
        }
        assertTrue(used)
        fixture.assertClosedExchanges()
    }

    fun assertEnvelopeLimit(fixture: OwnerDeleteAllJournalPublisherFixture, work: CommittedOwnerDeleteAllWork.Prepared) {
        val maximum = fixture.journal.declaration().limits.decoder.maximumEnvelopeBytes
        assertEquals(98_304, maximum)
        for (extra in listOf(0, 1)) {
            fixture.reset()
            val listed = fixture.objectFor(ByteArray(maximum) { 42 })
            fixture.stored = listed
            val fetched = fixture.objectFor(ByteArray(maximum + extra) { 42 })
            val reply = fixture.getReply(fetched)
            fixture.respond = { request -> if (request.kind == "GET") reply else fixture.statefulReply(request) }
            val unwrapped = fixture.decrypted()
            fixture.publisher().use { publisher ->
                if (extra == 0) {
                    // Raw 98,304-byte success is not a valid envelope or whole publisher success.
                    assertRedacted(assertThrows<OwnerDeleteAllJournalException> { publisher.publish(work) })
                    assertEquals(1, reply.eofProbes)
                    assertTrue(reply.reads > 1)
                } else {
                    assertRedacted(assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) })
                    assertEquals(0, reply.reads) // Declared one-over is rejected before native body reads/allocation.
                }
            }
            assertEquals(unwrapped, fixture.decrypted())
            assertEquals(listOf("LIST", "GET"), fixture.requests.map { it.kind })
        }
    }

    fun assertSignals(fixture: OwnerDeleteAllJournalPublisherFixture, work: CommittedOwnerDeleteAllWork.Prepared) {
        val signals = listOf<Pair<Class<out Throwable>, () -> Throwable>>(
            CancellationException::class.java to { CancellationException(PRIVATE_TEXT) },
            InterruptedException::class.java to { InterruptedException(PRIVATE_TEXT) },
            JournalPublicationFatalV1::class.java to { AssertionError(PRIVATE_TEXT) },
            CancellationException::class.java to { SdkClientException.create(PRIVATE_TEXT, CancellationException(PRIVATE_TEXT)) },
            InterruptedException::class.java to { SdkClientException.create(PRIVATE_TEXT, InterruptedException(PRIVATE_TEXT)) },
            JournalPublicationFatalV1::class.java to { SdkClientException.create(PRIVATE_TEXT, AssertionError(PRIVATE_TEXT)) },
        )
        for (stage in listOf("PREPARE", "CALL")) {
            for ((expected, signal) in signals) {
                fixture.reset()
                if (stage == "PREPARE") {
                    fixture.beforePrepare = { throw signal() }
                } else {
                    fixture.respond = { fixture.listReply(emptyList()).apply { beforeCall = { throw signal() } } }
                }
                fixture.publisher().use { publisher ->
                    try {
                        val failure = checkNotNull(runCatching { publisher.publish(work) }.exceptionOrNull())
                        assertInstanceOf(expected, failure)
                        assertRedacted(failure)
                        assertEquals(expected == InterruptedException::class.java, Thread.currentThread().isInterrupted)
                    } finally {
                        Thread.interrupted() // The test observes restoration above, then clears before actual PG cleanup.
                    }
                    val count = fixture.requests.size
                    assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) }
                    assertEquals(count, fixture.requests.size)
                }
            }
        }
    }

    fun assertCleanupCustody(fixture: OwnerDeleteAllJournalPublisherFixture, work: CommittedOwnerDeleteAllWork.Prepared) {
        for (mode in listOf("ABORT", "CLOSE", "FATAL_OVER_CANCELLATION", "WRAPPED_FATAL_OVER_CANCELLATION")) {
            fixture.reset()
            val reply = fixture.listReply(emptyList()).apply {
                when (mode) {
                    "ABORT" -> onAbort = { throw IOException(PRIVATE_TEXT) }

                    "CLOSE" -> onClose = { throw IOException(PRIVATE_TEXT) }

                    else -> {
                        beforeRead = { throw CancellationException(PRIVATE_TEXT) }
                        onAbort = {
                            if (mode == "FATAL_OVER_CANCELLATION") throw AssertionError(PRIVATE_TEXT)
                            throw SdkClientException.create(PRIVATE_TEXT, AssertionError(PRIVATE_TEXT))
                        }
                    }
                }
            }
            fixture.respond = { reply }
            val publisher = fixture.publisher()
            try {
                val failure = checkNotNull(runCatching { publisher.publish(work) }.exceptionOrNull())
                assertRedacted(failure)
                if (mode.contains("FATAL")) {
                    assertInstanceOf(JournalPublicationFatalV1::class.java, failure)
                } else {
                    assertEquals(JournalPublicationFailureV1.CLEANUP_FAILURE, assertInstanceOf(JournalPublicationExceptionV1::class.java, failure).code)
                }
                assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) }
                repeat(2) {
                    val closing = checkNotNull(runCatching { publisher.close() }.exceptionOrNull())
                    assertRedacted(closing)
                    assertEquals(failure.javaClass, closing.javaClass)
                }
                assertEquals(1, reply.aborts)
                assertEquals(1, reply.closes) // Even a throwing abort cannot skip the body-close attempt.
                assertEquals(listOf("LIST"), fixture.requests.map { it.kind })
            } finally {
                runCatching { publisher.close() } // Re-report only; native abort/close is never retried after its failed invocation.
            }
        }
    }

    fun assertLateNativeClose(fixture: OwnerDeleteAllJournalPublisherFixture, work: CommittedOwnerDeleteAllWork.Prepared) {
        fixture.reset()
        fixture.stored = fixture.objectFor(fixture.envelope())
        val before = fixture.decrypted()
        val publisher = fixture.publisher()
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            fixture.respond = { request -> fixture.statefulReply(request).apply { if (request.kind == "GET") beforeCall = { gate.hold() } } }
            try {
                val call = callers.launch { publisher.publish(work) }
                gate.awaitEntered()
                val reply = checkNotNull(fixture.requests.last().reply)
                assertEquals("GET", fixture.requests.last().kind)
                val closing = assertThrows<JournalPublicationExceptionV1> { publisher.close() }
                assertEquals(JournalPublicationFailureV1.CLEANUP_FAILURE, closing.code)
                assertEquals(1, reply.aborts)
                assertEquals(0, reply.closes) // Native call still owns an as-yet-unreturned body; close cannot claim quiescence.
                assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) }
                gate.release()
                val failure = call.problem()
                assertNotNull(failure)
                assertRedacted(checkNotNull(failure))
                assertEquals(1, reply.closes) // Late body closes on the original owner path, never reaches unwrap or success.
                assertEquals(before, fixture.decrypted())
                assertThrows<JournalPublicationExceptionV1> { publisher.close() }
                assertEquals(1, reply.aborts)
                assertEquals(1, reply.closes)
                assertEquals(listOf("LIST", "GET"), fixture.requests.map { it.kind })
            } finally {
                gate.release()
                runCatching { publisher.close() }
            }
        }
    }

    fun scalar(document: String, name: String, value: String): String = document.replace(Regex("<$name>[^<]*</$name>"), "<$name>$value</$name>")

    fun header(reply: S3CatalogReply, name: String, value: String?) {
        reply.headers = reply.headers.filterKeys { !it.equals(name, ignoreCase = true) } + if (value == null) emptyMap() else mapOf(name to listOf(value))
    }

    fun httpDate(value: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(value.atZone(ZoneOffset.UTC))

    fun assertNoRequests(fixture: OwnerDeleteAllJournalPublisherFixture) {
        assertTrue(fixture.requests.isEmpty())
        assertTrue(fixture.kms.requests.isEmpty())
    }

    fun assertClientsClosed(fixture: OwnerDeleteAllJournalPublisherFixture) {
        fixture.assertClosedExchanges()
        assertEquals(fixture.s3ClientsCreated, fixture.s3ClientsClosed)
        assertEquals(fixture.kms.createdClients, fixture.kms.closedClients)
    }

    fun assertEvidence(fixture: OwnerDeleteAllJournalPublisherFixture, readback: OwnerDeleteAllJournalReadbackV1) {
        val observed = checkNotNull(fixture.stored)
        assertEquals(observed.version, readback.versionId)
        assertEquals(hash(observed.bytes), readback.wireSha256)
        assertEquals(observed.lastModified, readback.lastModified)
        assertEquals(observed.retainUntil, readback.retainUntil)
        assertEquals(fixture.wall, readback.verifiedAt)
        assertEquals(fixture.event.route, readback.event.route)
        assertArrayEquals(fixture.event.canonicalBytes(), readback.event.canonicalBytes())
        assertEquals(fixture.targets, readback.event.complaintIds())
        assertTrue(readback.event.belongsTo(fixture.routing))
        fixture.assertClosedExchanges()
    }

    private fun assertSameCandidate(fixture: OwnerDeleteAllJournalPublisherFixture) {
        val puts = fixture.requests.filter { it.kind == "PUT" }
        assertEquals(2, puts.size)
        assertArrayEquals(puts[0].body, puts[1].body)
        for (name in listOf(
            "If-None-Match", "Content-Length", "Content-Type", "x-amz-checksum-sha256", "x-amz-object-lock-mode",
            "x-amz-object-lock-retain-until-date", "x-amz-meta-kira-journal-schema", "x-amz-meta-kira-journal-event-id",
            "x-amz-meta-kira-journal-ciphertext-sha256", "x-amz-meta-kira-journal-retain-until",
        )) {
            assertEquals(puts[0].header(name), puts[1].header(name), name)
        }
        assertWire(fixture)
    }

    fun assertWire(fixture: OwnerDeleteAllJournalPublisherFixture) {
        val location = fixture.journal.declaration().journalLocation
        fixture.requests.forEach { request ->
            fixture.assertSigned(request)
            val http = request.http
            if (request.kind == "LIST") {
                assertEquals(SdkHttpMethod.GET, http.method())
                assertTrue(http.encodedPath() in listOf("/${location.bucket}", "/${location.bucket}/"))
                assertEquals(fixture.event.route.objectKey, http.firstMatchingRawQueryParameter("prefix").orElseThrow())
                assertEquals("2", http.firstMatchingRawQueryParameter("max-keys").orElseThrow())
                assertEquals("url", http.firstMatchingRawQueryParameter("encoding-type").orElseThrow())
                assertTrue(http.rawQueryParameters().keys.all { it in setOf("versions", "prefix", "max-keys", "encoding-type", "x-id") })
            } else {
                assertEquals("/${location.bucket}/${fixture.event.route.objectKey}", http.encodedPath())
                if (request.kind == "GET") {
                    assertEquals(SdkHttpMethod.GET, http.method())
                    assertEquals(checkNotNull(fixture.stored).version, http.firstMatchingRawQueryParameter("versionId").orElseThrow())
                    assertEquals("ENABLED", request.header("x-amz-checksum-mode"))
                    assertTrue(http.rawQueryParameters().keys.all { it in setOf("versionId", "x-id") })
                } else {
                    assertEquals(SdkHttpMethod.PUT, http.method())
                    assertEquals("*", request.header("If-None-Match"))
                    assertEquals(checksum(request.body), request.header("x-amz-checksum-sha256"))
                    assertEquals("SHA256", request.header("x-amz-sdk-checksum-algorithm"))
                    assertEquals(request.body.size.toString(), request.header("Content-Length"))
                    assertEquals("application/octet-stream", request.header("Content-Type"))
                    assertEquals("COMPLIANCE", request.header("x-amz-object-lock-mode"))
                    val retention = Instant.parse(request.header("x-amz-object-lock-retain-until-date"))
                    assertEquals(0, retention.nano)
                    val floor = fixture.wall.plusSeconds(fixture.journal.declaration().limits.retention.ordinaryRetentionSeconds)
                    assertFalse(retention.isBefore(floor))
                    assertTrue(http.rawQueryParameters().keys.all { it == "x-id" })
                    val metadata = http.headers().filterKeys { it.startsWith("x-amz-meta-", ignoreCase = true) }
                    assertEquals(4, metadata.size)
                    assertEquals("1", request.header("x-amz-meta-kira-journal-schema"))
                    assertEquals(fixture.event.route.eventId, request.header("x-amz-meta-kira-journal-event-id"))
                    assertEquals(hash(request.body), request.header("x-amz-meta-kira-journal-ciphertext-sha256"))
                    assertEquals(retention.toString(), request.header("x-amz-meta-kira-journal-retain-until"))
                }
            }
        }
    }
}
