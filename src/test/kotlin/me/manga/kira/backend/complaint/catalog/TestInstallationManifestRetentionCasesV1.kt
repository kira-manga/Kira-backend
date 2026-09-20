package me.manga.kira.backend.complaint.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** One real-time retry inside the existing lost-FREEZE selector; all provider policy remains synthetic. */
internal object TestInstallationManifestRetentionCasesV1 {
    fun retryAfterLostFreeze(f: TestRunOrdinaryDrainFixtureV1, preparation: TestRunInstallationManifestV1,
        probe: TestInstallationManifestPublicationSqlProbeV1, failed: TestRunInstallationManifestPublicationV1): TestRunInstallationManifestPublicationV1 {
        val frozen = f.observer.query("SELECT object_key, retain_until, frozen_at, wire_bytes, metadata_bytes " +
            "FROM complaint_test_terminal_intents WHERE data_scope_id = ? AND object_kind = 'INSTALLATION_MANIFEST' AND object_ordinal = 0",
            { row, _ -> Frozen(row) }, f.scope).single()
        return frozen.use { winner ->
            val image = manifestRowsImage(f)
            val order = f.sealHttp.order.size
            val requests = f.sealHttp.requests.size
            assertTrue(f.sealHttp.manifestObjects.isEmpty(), "The committed wire winner has never reached S3.")
            awaitNaturalLeaseExpiry(f, failed, winner)
            assertEquals(image, manifestRowsImage(f))
            probe.reset()
            var lock: Instant? = null
            var shortenedReadback = false
            f.sealHttp.beforeS3 = { request ->
                if (request.kind == "PUT") {
                    assertTrue(lock == null, "Exactly one PUT can use this frozen winner.")
                    lock = Instant.parse(request.header("x-amz-object-lock-retain-until-date"))
                    assertTrue(checkNotNull(lock) > winner.minimum, "A later first PUT needs a stronger lock, not rewritten metadata.")
                    val bucket = f.registration.process.consumers.journalConfiguration.declaration().journalLocation.bucket
                    assertEquals("/$bucket/${winner.key}", request.http.encodedPath())
                    assertArrayEquals(winner.wire, request.body)
                    val metadata = request.http.headers().entries.filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
                        .associate { it.key.lowercase().removePrefix("x-amz-meta-") to it.value.single() }
                    assertEquals(winner.metadata, metadata)
                    assertEquals(winner.minimum.toString(), metadata.getValue("kira-journal-retain-until"))
                }
            }
            f.sealHttp.changeS3 = { request, reply ->
                if (request.kind == "PUT") {
                    assertEquals(200, reply.status)
                    assertEquals(f.sealHttp.manifestObjects.getValue(winner.key).version, reply.headers.getValue("x-amz-version-id").single())
                }
                if (request.kind == "GET") {
                    val provider = f.sealHttp.manifestObjects.getValue(winner.key)
                    val sent = checkNotNull(lock)
                    assertEquals(sent, provider.retainUntil)
                    val tenYearsFromCreation = provider.lastModified.atOffset(ZoneOffset.UTC).plusYears(10).toInstant()
                    assertTrue(tenYearsFromCreation > winner.minimum, "Real time, not merely SQL lease editing, makes the old lock insufficient.")
                    assertTrue(winner.minimum > provider.lastModified)
                    val shortened = sent.minusSeconds(1)
                    assertTrue(shortened >= maxOf(winner.minimum, tenYearsFromCreation, f.sealHttp.horizon.plusSeconds(31 * 86_400L)))
                    assertTrue(shortened > Instant.now().plusSeconds(1))
                    // Only the new acknowledged-L requirement fails. The immutable stored object is not changed.
                    reply.headers = reply.headers + ("x-amz-object-lock-retain-until-date" to listOf(shortened.toString()))
                    shortenedReadback = true
                }
            }
            val refused = preparation.beginPublication().also { probe.original = it }
            try { assertThrows<TestInstallationManifestExceptionV1> { refused.publish() } }
            finally { f.sealHttp.beforeS3 = {}; f.sealHttp.changeS3 = { _, _ -> } }
            probe.assertReleased(requireCommitted = false)
            assertTrue(shortenedReadback)
            assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.sealHttp.requests.drop(requests).map { it.kind })
            assertTrue(f.sealHttp.order.drop(order).none { it in setOf("GENERATE", "DECRYPT") })
            assertTrue(probe.calls.none { it.step === TestInstallationManifestPublicationStepV1.VERIFY })
            assertEquals(listOf("PREPARED" to "WIRE_FROZEN"), manifestStatePairs(f))
            assertEquals(image, manifestRowsImage(f), "Rejected ACK readback cannot change the canonical/wire winner, proof or xmin.")
            TestInstallationManifestPublicationCasesV1.assertNoAccountingOrDomainWrites(probe)

            // The first retry above used natural passage. Only this secondary fault-recovery lease
            // is explicitly expired by the fixture, avoiding another30s wait or a future DB timestamp.
            TestOrdinaryDrainAccountingObservationV1(f).expireLeaseForRetry()
            probe.reset()
            val beforeRecovery = f.sealHttp.order.size
            val recovered = TestInstallationManifestPublicationCasesV1.publish(preparation, probe)
            probe.assertReleased()
            assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "LIST", "GET", "DECRYPT"), f.sealHttp.order.drop(beforeRecovery))
            assertEquals(checkNotNull(lock), f.sealHttp.manifestObjects.getValue(winner.key).retainUntil)
            assertEquals(image.getValue("sidecar"), manifestRowsImage(f).getValue("sidecar"))
            assertPublishedManifests(f, recovered) // requestedRetainUntil=M, observed retainUntil=L>M.
            TestInstallationManifestPublicationCasesV1.assertNoAccountingOrDomainWrites(probe)

            val verified = manifestRowsImage(f)
            val reference = recovered.authenticatedChunk(0)
            val beforeReplay = f.sealHttp.order.size
            probe.reset()
            val replay = TestInstallationManifestPublicationCasesV1.publish(preparation, probe)
            probe.assertReleased()
            assertEquals(reference, replay.authenticatedChunk(0))
            assertEquals(verified, manifestRowsImage(f), "Exact VERIFIED replay keeps its first proof/time, M and all row identities even when A>M.")
            assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "LIST", "GET", "DECRYPT"), f.sealHttp.order.drop(beforeReplay))
            assertTrue(probe.calls.none { it.sql in setOf(TestInstallationManifestPublicationSqlV1.freeze, TestInstallationManifestPublicationSqlV1.verify) })
            assertPublishedManifests(f, replay)
            replay
        }
    }

    private fun awaitNaturalLeaseExpiry(f: TestRunOrdinaryDrainFixtureV1, failed: TestRunInstallationManifestPublicationV1, winner: Frozen) {
        assertEquals(0L, f.sealHttp.offsetNanos)
        val deadlines = f.registration.process.consumers.journalConfiguration.declaration().limits.deadlines
        assertEquals(30_000, deadlines.scanMillis)
        val before = leaseSample(f, failed)
        val waitMillis = maxOf(Duration.between(before.now, before.expiresAt).toMillis() + 100L, deadlines.publicationAttemptMillis + 100L)
        assertTrue(waitMillis <= deadlines.scanMillis + 100L)
        val started = System.nanoTime()
        Thread.sleep(waitMillis) // One bounded real sleep, no clock offset, lease UPDATE or polling loop.
        val after = leaseSample(f, failed)
        assertTrue(System.nanoTime() - started >= deadlines.publicationAttemptMillis * 1_000_000L)
        assertEquals(before.image, after.image, "Natural expiry must leave the lease row and xmin untouched.")
        assertTrue(after.now > before.expiresAt && after.now > winner.frozenAt.plusMillis(deadlines.publicationAttemptMillis.toLong()))
        assertTrue(winner.minimum > after.now && f.sealHttp.horizon > after.now, "M and the fixture's denial authority remain valid.")
        assertEquals(0L, f.sealHttp.offsetNanos)
    }

    private fun leaseSample(f: TestRunOrdinaryDrainFixtureV1, failed: TestRunInstallationManifestPublicationV1): LeaseSample =
        f.observer.query("SELECT lease_owner, lease_token, lease_expires_at, clock_timestamp() AS observed_at, " +
            "jsonb_build_array(to_jsonb(c), c.xmin::text)::text AS image FROM complaint_journal_control c WHERE data_scope_id = ?", { row, _ ->
            assertEquals(failed.attemptId, row.getObject("lease_owner", UUID::class.java))
            assertEquals(failed.leaseToken, row.getLong("lease_token"))
            LeaseSample(row.getTimestamp("observed_at").toInstant(), row.getTimestamp("lease_expires_at").toInstant(), row.getString("image"))
        }, f.scope).single()

    private data class LeaseSample(val now: Instant, val expiresAt: Instant, val image: String)

    private class Frozen(row: ResultSet) : AutoCloseable {
        val key: String = row.getString("object_key")
        val minimum: Instant = row.getTimestamp("retain_until").toInstant()
        val frozenAt: Instant = row.getTimestamp("frozen_at").toInstant()
        val wire: ByteArray = row.getBytes("wire_bytes")
        val metadata: Map<String, String> = row.getBytes("metadata_bytes").let { bytes ->
            try { ObjectMapper().readTree(bytes).fields().asSequence().associate { it.key to it.value.textValue() } }
            finally { bytes.fill(0) }
        }
        override fun close() { wire.fill(0) }
    }
}
