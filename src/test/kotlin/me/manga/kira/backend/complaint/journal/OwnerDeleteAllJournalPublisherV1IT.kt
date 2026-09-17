package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllStep
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllAuthorizationFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownerDeleteAllTestRouting
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.infrastructure.persistence.withOwnerDeleteAllAuthorization
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllOperation
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertCleanupCustody
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertClientsClosed
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertConflictRetries
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertEnvelopeLimit
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertEvidence
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertLateNativeClose
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertListReply
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertNoRequests
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertPutError
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertRealCrypto
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertSignals
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertUnresolvedReconciliation
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.assertWire
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.header
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.httpDate
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherAssertions.scalar
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.VERSION
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.assertRedacted
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.checksum
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.hash
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.padXml
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.xmlReply
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat

/** Genuine released SQL work and real SDK/crypto, synthetic raw providers only. No VERIFIED/APPLIED or LIVE authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllJournalPublisherV1IT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllJournalPublisherV1IT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `only genuine committed released custody reaches the fixed signed ordinary wire`() = withFixture { auth ->
        val candidate = auth.enrolled()
        val fixture = OwnerDeleteAllJournalPublisherFixture(auth, candidate, auth.content(candidate, 1))
        var operation: ComplaintOwnerDeleteAllOperation? = null
        fixture.publisher().use { publisher ->
            auth.admitted(candidate) { observed, admission ->
                val phase = auth.ownership.enterComplaintOwnerDeleteAllAuthorize(admission, observed)
                try {
                    phase.ownerDeleteAll.bindAuthorize(admission)
                    phase.begin()
                    operation = auth.store.authorize(candidate, observed)
                    val early = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
                    assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                    assertFalse(early.cleanupProven)
                    assertNoRequests(fixture)
                    phase.commit()
                    val held = assertThrows<PersistencePhaseException> { checkNotNull(operation).result }
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, held.databaseOutcome)
                    assertFalse(held.cleanupProven)
                    assertNoRequests(fixture)
                } catch (failure: Throwable) {
                    phase.recordFailure(failure)
                    throw failure
                } finally {
                    phase.finish()
                }
            }
            val work = assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, checkNotNull(operation).result)
            auth.assertReleased()
            fixture.publisher(store = auth.newStore()).use { foreign ->
                assertRedacted(assertThrows<JournalPublicationExceptionV1> { foreign.publish(work) })
            }
            fixture.publisher(selected = ownerDeleteAllTestRouting()).use { foreign ->
                assertRedacted(assertThrows<JournalPublicationExceptionV1> { foreign.publish(work) })
            }
            assertNoRequests(fixture)
            var heldRejection = false
            auth.afterStep = { step ->
                if (step == DeleteAllStep.RELOAD_PUBLICATION) {
                    assertThrows<PersistencePhaseException> { publisher.publish(work) }
                    assertNoRequests(fixture)
                    heldRejection = true
                }
            }
            assertArrayEquals(work.canonicalBytes(), auth.prepared(candidate).canonicalBytes())
            auth.afterStep = {}
            assertTrue(heldRejection)
            val durable = auth.state()
            val readback = publisher.publish(work)
            assertEvidence(fixture, readback)
            assertWire(fixture)
            val proposed = fixture.wall.plusSeconds(fixture.journal.declaration().limits.retention.ordinaryRetentionSeconds).plusMillis(5001)
            assertEquals(Instant.ofEpochSecond(proposed.epochSecond + 1), readback.retainUntil)
            assertEquals(durable, auth.state())
        }
        assertClientsClosed(fixture)
    }

    @Test
    fun `empty and hundred target events publish and authenticate without applying durable state`() {
        for (count in listOf(0, 100)) {
            withPrepared(count) { fixture, work ->
                fixture.publisher().use { publisher ->
                    val result = publisher.publish(work)
                    assertEvidence(fixture, result)
                    assertEquals(listOf("LIST", "PUT", "LIST", "GET"), fixture.requests.map { it.kind })
                    assertEquals(1, fixture.generated())
                    assertEquals(1, fixture.decrypted())
                    assertEquals(count, result.event.complaintIds().size)
                    result.event.canonicalBytes().fill(0)
                    work.canonicalBytes().fill(0)
                    assertArrayEquals(fixture.event.canonicalBytes(), result.event.canonicalBytes())
                    assertArrayEquals(fixture.event.canonicalBytes(), work.canonicalBytes())
                    assertTrue(fixture.requests.all { checkNotNull(it.reply).eofProbes == 1 })
                    assertTrue(fixture.kms.replies.all { it.eofProbes == 1 && it.aborts == 1 && it.closes == 1 })
                    assertWire(fixture)
                }
            }
        }
    }

    @Test
    fun `conditional winner loser and retained routing restart adopt the observed immutable winner`() = withPrepared { fixture, work ->
        val winner = fixture.publisher().use { it.publish(work) }
        val saved = checkNotNull(fixture.stored)
        assertEvidence(fixture, winner)
        fixture.reset()
        fixture.stored = saved
        var lists = 0
        fixture.respond = { request ->
            if (request.kind == "LIST" && ++lists == 1) fixture.listReply(emptyList()) else fixture.statefulReply(request)
        }
        val beforeGenerate = fixture.generated()
        val loser = fixture.publisher().use { it.publish(work) }
        val losingPut = fixture.requests.single { it.kind == "PUT" }
        assertEquals(412, checkNotNull(losingPut.reply).status)
        assertFalse(saved.bytes.contentEquals(losingPut.body)) // Fresh data key and nonce, identical frozen semantics.
        assertEquals(beforeGenerate + 1, fixture.generated())
        assertEquals(winner.wireSha256, loser.wireSha256)
        assertNotEquals(hash(losingPut.body), loser.wireSha256)
        assertArrayEquals(saved.bytes, checkNotNull(fixture.stored).bytes)
        assertEvidence(fixture, loser)

        fixture.reset()
        fixture.stored = saved
        val rotated = ownerDeleteAllTestRouting("route-a")
        val restartedStore = fixture.auth.newStore(rotated)
        val restarted = ComplaintOwnerDeleteAllPhaseExecutor(fixture.auth.ownership, restartedStore, fixture.auth.preflights)
        val preflight = assertInstanceOf(
            InstallationDeletionPreflightResult.Authorized::class.java,
            fixture.auth.preflights.preflight(fixture.candidate),
        )
        val reloaded = assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java, restarted.reload(fixture.candidate, preflight))
        assertEquals("route-a", rotated.journalConfiguration.declaration().routing.activeKeyId)
        assertEquals("route-b", restartedStore.preparedEvent(reloaded).route.routingKeyId)
        assertArrayEquals(work.canonicalBytes(), reloaded.canonicalBytes())
        val generated = fixture.generated()
        val adopted = fixture.publisher(restartedStore, rotated).use { it.publish(reloaded) }
        assertEquals(generated, fixture.generated())
        assertEquals(listOf("LIST", "GET"), fixture.requests.map { it.kind })
        assertTrue(adopted.event.belongsTo(rotated))
        assertEquals(winner.versionId, adopted.versionId)
        assertEquals(winner.wireSha256, adopted.wireSha256)
        assertArrayEquals(winner.event.canonicalBytes(), adopted.event.canonicalBytes())
        assertWire(fixture)
    }

    @Test
    fun `conflict and lost responses reconcile finitely with one byte identical candidate retry`() = withPrepared { fixture, work ->
        assertConflictRetries(fixture, work)
        assertUnresolvedReconciliation(fixture, work)
    }

    @Test
    fun `every exact key inventory scalar and version is evidence rather than a default or filter`() = withPrepared { fixture, work ->
        val value = fixture.objectFor(fixture.envelope())
        val empty = fixture.listDocument(emptyList())
        val single = fixture.listDocument(listOf(value))
        val documents = mutableListOf(
            "two versions" to fixture.listDocument(listOf(value, value.copy(version = "another-version"))),
            "marker only" to empty.replace("</ListVersionsResult>", "<DeleteMarker/></ListVersionsResult>"),
            "version and marker" to single.replace("</ListVersionsResult>", "<DeleteMarker/></ListVersionsResult>"),
            "foreign prefix sharing key" to fixture.listDocument(listOf(value.copy(key = value.key + "-foreign"))),
            "literal null version" to fixture.listDocument(listOf(value.copy(version = "null"))),
            "empty version" to fixture.listDocument(listOf(value.copy(version = ""))),
            "oversized version" to fixture.listDocument(listOf(value.copy(version = "v".repeat(1025)))),
            "oversized UTF8 version" to fixture.listDocument(listOf(value.copy(version = "\u20ac".repeat(342)))),
            "control in version" to scalar(single, "VersionId", "line&#10;break"),
            "truncated" to scalar(empty, "IsTruncated", "true"),
            "not latest" to scalar(single, "IsLatest", "false"),
            "foreign bucket" to scalar(empty, "Name", "other-bucket"),
            "foreign prefix" to scalar(empty, "Prefix", "other-prefix"),
            "wrong max keys" to scalar(empty, "MaxKeys", "1"),
            "wrong encoding" to scalar(empty, "EncodingType", "none"),
            "initial key marker" to scalar(empty, "KeyMarker", "cursor"),
            "initial version marker" to scalar(empty, "VersionIdMarker", "cursor"),
            "next key marker" to empty.replace("</ListVersionsResult>", "<NextKeyMarker>cursor</NextKeyMarker></ListVersionsResult>"),
            "next version marker" to empty.replace("</ListVersionsResult>", "<NextVersionIdMarker>cursor</NextVersionIdMarker></ListVersionsResult>"),
            "delimiter" to empty.replace("</ListVersionsResult>", "<Delimiter>/</Delimiter></ListVersionsResult>"),
            "common prefix" to empty.replace("</ListVersionsResult>", "<CommonPrefixes><Prefix>x</Prefix></CommonPrefixes></ListVersionsResult>"),
            "nested scalar" to scalar(empty, "IsTruncated", "<nested>false</nested>"),
            "wrong namespace" to empty.replace("http://s3.amazonaws.com/doc/2006-03-01/", "urn:untrusted"),
        )
        for (field in listOf("Name", "Prefix", "KeyMarker", "VersionIdMarker", "MaxKeys", "IsTruncated", "EncodingType")) {
            documents += "missing $field" to empty.replace(Regex("<$field>[^<]*</$field>"), "")
            documents += "duplicate $field" to empty.replace("</ListVersionsResult>", "<$field/></ListVersionsResult>")
        }
        for (field in listOf("Key", "VersionId", "IsLatest", "LastModified", "Size")) {
            documents += "missing $field" to single.replace(Regex("<$field>[^<]*</$field>"), "")
            documents += "duplicate $field" to single.replace("</Version>", "<$field/></Version>")
        }
        for (text in listOf("", "garbage", "TRUE", "False", "FALSE", " false", "false ", "\ttrue", "true\n")) {
            documents += "truncation boolean [$text]" to scalar(empty, "IsTruncated", text)
            documents += "latest boolean [$text]" to scalar(single, "IsLatest", text)
        }
        documents += "self closing truncation" to empty.replace("<IsTruncated>false</IsTruncated>", "<IsTruncated/>")
        documents += "self closing latest" to single.replace("<IsLatest>true</IsLatest>", "<IsLatest/>")
        for ((name, document) in documents) {
            fixture.reset()
            val generated = fixture.generated()
            val decrypted = fixture.decrypted()
            fixture.respond = { xmlReply(document) }
            fixture.publisher().use { publisher ->
                assertRedacted(assertThrows<JournalPublicationExceptionV1>(name) { publisher.publish(work) })
            }
            assertEquals(listOf("LIST"), fixture.requests.map { it.kind }, name)
            assertEquals(generated, fixture.generated(), name)
            assertEquals(decrypted, fixture.decrypted(), name)
            fixture.assertClosedExchanges()
        }
        for (version in listOf(VERSION, "v".repeat(1024))) {
            fixture.reset()
            fixture.stored = value.copy(version = version)
            fixture.respond = { request ->
                if (request.kind == "LIST") {
                    xmlReply(
                        fixture.listDocument().replace("false</IsTruncated>", "fa<![CDATA[lse]]></IsTruncated>")
                            .replace("true</IsLatest>", "tr<!--split--><![CDATA[ue]]></IsLatest>"),
                    )
                } else {
                    fixture.statefulReply(request)
                }
            }
            val generated = fixture.generated()
            fixture.publisher().use { assertEvidence(fixture, it.publish(work)) }
            assertEquals(generated, fixture.generated())
            assertEquals(version, fixture.requests.single { it.kind == "GET" }.http.firstMatchingRawQueryParameter("versionId").orElseThrow())
            assertWire(fixture)
        }
    }

    @Test
    fun `cheap version proof precedes unwrap and real authentication rejects conflicting content`() = withPrepared { fixture, work ->
        val original = fixture.objectFor(fixture.envelope())
        val canonical = checksum(original.bytes)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val nonCanonical = canonical.dropLast(2) + alphabet[alphabet.indexOf(canonical[42]) xor 1] + "="
        val mutations = mutableListOf<Pair<String, (S3CatalogReply) -> Unit>>(
            "missing checksum" to { header(it, "x-amz-checksum-sha256", null) },
            "different checksum" to { header(it, "x-amz-checksum-sha256", checksum(byteArrayOf(1))) },
            "semantic not wire checksum" to {
                header(it, "x-amz-checksum-sha256", Base64.getEncoder().encodeToString(HexFormat.of().parseHex(fixture.event.semanticSha256)))
            },
            "unpadded checksum" to { header(it, "x-amz-checksum-sha256", canonical.dropLast(1)) },
            "noncanonical pad bits" to { header(it, "x-amz-checksum-sha256", nonCanonical) },
            "composite checksum" to { header(it, "x-amz-checksum-type", "COMPOSITE") },
            "unknown checksum type" to { header(it, "x-amz-checksum-type", "UNKNOWN") },
            "extra metadata" to { header(it, "x-amz-meta-unrecognized", "1") },
            "wrong schema" to { header(it, "x-amz-meta-kira-journal-schema", "2") },
            "wrong event" to { header(it, "x-amz-meta-kira-journal-event-id", "wrong-event") },
            "semantic not wire metadata" to { header(it, "x-amz-meta-kira-journal-ciphertext-sha256", fixture.event.semanticSha256) },
            "retention metadata spelling" to { header(it, "x-amz-meta-kira-journal-retain-until", original.retainUntil.toString().replace("Z", ".000Z")) },
            "retention metadata later than actual" to { header(it, "x-amz-meta-kira-journal-retain-until", original.retainUntil.plusSeconds(1).toString()) },
            "missing mode" to { header(it, "x-amz-object-lock-mode", null) },
            "governance" to { header(it, "x-amz-object-lock-mode", "GOVERNANCE") },
            "missing retention" to { header(it, "x-amz-object-lock-retain-until-date", null) },
            "missing last modified" to { header(it, "Last-Modified", null) },
            "different last modified" to { header(it, "Last-Modified", httpDate(original.lastModified.minusSeconds(1))) },
            "invalid last modified" to { header(it, "Last-Modified", "not-a-date") },
            "missing version" to { header(it, "x-amz-version-id", null) },
            "null version" to { header(it, "x-amz-version-id", "null") },
            "different version" to { header(it, "x-amz-version-id", "another-version") },
            "missing metadata counter" to { header(it, "x-amz-missing-meta", "1") },
            "delete marker" to { header(it, "x-amz-delete-marker", "true") },
            "native expiration" to { header(it, "x-amz-expiration", "expiry-date=synthetic") },
            "wrong content type" to { header(it, "Content-Type", "text/plain") },
        )
        original.metadata.keys.forEach { key -> mutations += "missing $key" to { reply: S3CatalogReply -> header(reply, "x-amz-meta-$key", null) } }
        for ((name, change) in mutations) {
            fixture.reset()
            fixture.stored = original
            val before = fixture.decrypted()
            fixture.respond = { request -> fixture.statefulReply(request).also { if (request.kind == "GET") change(it) } }
            fixture.publisher().use { publisher ->
                assertRedacted(assertThrows<JournalPublicationExceptionV1>(name) { publisher.publish(work) })
            }
            assertEquals(before, fixture.decrypted(), name)
            assertEquals(listOf("LIST", "GET"), fixture.requests.map { it.kind }, name)
        }
        for (mode in listOf("SHORT", "EXPIRED", "FUTURE", "REQUEST_NOT_AFTER_CREATION")) {
            fixture.reset()
            val retain = when (mode) {
                "SHORT" -> original.lastModified.plusSeconds(fixture.journal.declaration().limits.retention.ordinaryRetentionSeconds - 1)
                "EXPIRED" -> fixture.wall.truncatedTo(ChronoUnit.SECONDS)
                else -> original.retainUntil
            }
            fixture.stored = original.copy(
                lastModified = if (mode == "FUTURE") original.lastModified.plusSeconds(86_400) else original.lastModified,
                retainUntil = retain,
                metadata = original.metadata + (
                    "kira-journal-retain-until" to if (mode == "REQUEST_NOT_AFTER_CREATION") {
                        original.lastModified.toString()
                    } else {
                        retain.toString()
                    }
                    ),
            )
            val before = fixture.decrypted()
            fixture.publisher().use { assertRedacted(assertThrows<JournalPublicationExceptionV1> { it.publish(work) }) }
            assertEquals(before, fixture.decrypted(), mode)
        }
        assertRealCrypto(fixture, work, original)
    }

    @Test
    fun `raw body header and XML limits reject one over before any unsafe decode or fallback`() = withPrepared { fixture, work ->
        val empty = fixture.listDocument(emptyList())
        for (size in listOf(16_384, 16_385)) {
            assertListReply(fixture, work, xmlReply(padXml(empty, size)), size == 16_384)
        }
        for (length in listOf(empty.toByteArray().size - 1, empty.toByteArray().size + 1)) {
            assertListReply(fixture, work, xmlReply(empty).apply { header(this, "Content-Length", length.toString()) }, false)
        }
        assertListReply(fixture, work, xmlReply(empty.dropLast(1)).apply { header(this, "Content-Length", empty.toByteArray().size.toString()) }, false)
        assertListReply(fixture, work, xmlReply(empty + "X").apply { header(this, "Content-Length", empty.toByteArray().size.toString()) }, false)
        assertListReply(fixture, work, xmlReply(empty).apply { chunkSize = 0 }, false)
        for (count in listOf(64, 65)) {
            val reply = xmlReply(empty)
            repeat(count - reply.headers.size) { header(reply, "x-filler-$it", "x") }
            assertListReply(fixture, work, reply, count == 64)
        }
        for (size in listOf(32_768, 32_769)) {
            val reply = xmlReply(empty)
            val used = reply.headers.entries.sumOf { it.key.length + it.value.single().length }
            header(reply, "x-padding", "x".repeat(size - used - "x-padding".length))
            assertListReply(fixture, work, reply, size == 32_768)
        }
        // This no-whitespace empty document has 22 StAX next() tokens including END_DOCUMENT.
        for (comments in listOf(1536 - 22, 1537 - 22)) {
            val document = empty.replace("</ListVersionsResult>", "<!--x-->".repeat(comments) + "</ListVersionsResult>")
            assertListReply(fixture, work, xmlReply(document), comments == 1536 - 22)
        }
        for (size in listOf(8192, 8193)) {
            assertPutError(fixture, work, xmlReply(padXml(ERROR_XML, size)).apply { status = 409 }, size == 8192)
        }
        for (depth in listOf(16, 17)) {
            val detail = "<Detail>".repeat(depth - 1) + "x" + "</Detail>".repeat(depth - 1)
            assertPutError(fixture, work, xmlReply(ERROR_XML.replace("</Error>", "$detail</Error>")).apply { status = 409 }, depth == 16)
        }
        for (elements in listOf(192, 193)) {
            val details = "<Detail/>".repeat(elements - 3) // Error, Code and Message are the other three elements.
            assertPutError(fixture, work, xmlReply(ERROR_XML.replace("</Error>", "$details</Error>")).apply { status = 409 }, elements == 192)
        }
        assertListReply(fixture, work, xmlReply("<!DOCTYPE ListVersionsResult [<!ENTITY hidden 'false'>]>" + scalar(empty, "IsTruncated", "&hidden;")), false)
        for (status in listOf(206, 301, 307)) assertListReply(fixture, work, xmlReply(empty).apply { this.status = status }, false)
        for ((name, value) in listOf(
            "Location" to "https://different.invalid/",
            "Content-Encoding" to "gzip",
            "Content-Range" to "bytes 0-1/2",
            "Transfer-Encoding" to "chunked",
            "x-amz-trailer" to "x-amz-checksum-sha256",
        )) {
            assertListReply(fixture, work, xmlReply(empty).apply { header(this, name, value) }, false)
        }
        assertListReply(
            fixture,
            work,
            xmlReply(empty).apply {
                header(this, "Content-Length", null)
                header(this, "Transfer-Encoding", "gzip")
            },
            false,
        )
        assertListReply(
            fixture,
            work,
            xmlReply(empty).apply {
                header(this, "Content-Length", null)
                header(this, "Transfer-Encoding", "chunked")
            },
            true,
        )
        assertEnvelopeLimit(fixture, work)
    }

    @Test
    fun `one shared shrinking attempt and retained cleanup refuse signals races and late success`() = withPrepared { fixture, work ->
        for (millis in listOf(800L, 900L)) {
            fixture.reset()
            val calls = mutableListOf<String>()
            fixture.respond = { request ->
                fixture.statefulReply(request).apply {
                    beforeCall = {
                        calls.add(request.kind)
                        fixture.nanos += millis * 1_000_000
                    }
                }
            }
            val keyReply = fixture.kms.respond
            fixture.kms.respond = { request ->
                keyReply(request).apply {
                    beforeCall = {
                        calls.add(if (request.target() == AwsJournalKmsFixture.GENERATE_TARGET) "GENERATE" else "DECRYPT")
                        fixture.nanos += millis * 1_000_000
                    }
                }
            }
            fixture.publisher().use { publisher ->
                if (millis == 800L) {
                    assertEvidence(fixture, publisher.publish(work))
                } else {
                    assertRedacted(assertThrows<OwnerDeleteAllJournalException> { publisher.publish(work) })
                    assertThrows<JournalPublicationExceptionV1> { publisher.publish(work) }
                }
            }
            assertEquals(listOf("LIST", "GENERATE", "PUT", "LIST", "GET", "DECRYPT"), calls)
            assertEquals(6 * millis * 1_000_000, fixture.nanos) // Each call < its nominal cap; total 5400ms is still refused.
        }
        fixture.reset()
        fixture.stored = fixture.objectFor(fixture.envelope())
        val before = fixture.decrypted()
        fixture.respond = { request ->
            fixture.statefulReply(request).apply { if (request.kind == "GET") onClose = { fixture.nanos = 5_000_000_000 } }
        }
        fixture.publisher().use { assertRedacted(assertThrows<OwnerDeleteAllJournalException> { it.publish(work) }) }
        assertEquals(before, fixture.decrypted()) // S3 cleanup expiry is checked before unwrap, not just before dispatch.
        assertSignals(fixture, work)
        assertCleanupCustody(fixture, work)
        assertLateNativeClose(fixture, work)
    }

    private fun withFixture(test: (OwnerDeleteAllAuthorizationFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value, test = test)

    private fun withPrepared(count: Int = 1, test: (OwnerDeleteAllJournalPublisherFixture, CommittedOwnerDeleteAllWork.Prepared) -> Unit) =
        withFixture { auth ->
            val candidate = auth.enrolled()
            val fixture = OwnerDeleteAllJournalPublisherFixture(auth, candidate, auth.content(candidate, count))
            val work = auth.prepared(candidate)
            val durable = auth.state()
            test(fixture, work)
            requireConnectionFree()
            assertEquals(durable, auth.state()) // No verification update, deletion, capacity release, repair or reauthorization.
            assertClientsClosed(fixture)
            auth.assertReleased()
        }

    private companion object {
        const val ERROR_XML = "<Error><Code>ConditionalRequestConflict</Code><Message>synthetic</Message></Error>"
    }
}
