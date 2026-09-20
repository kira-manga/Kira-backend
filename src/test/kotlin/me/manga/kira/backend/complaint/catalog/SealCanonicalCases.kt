package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.PreparedEpochSealV1
import me.manga.kira.backend.security.EpochSealRangeV1
import me.manga.kira.backend.security.EpochSealRoutingTupleV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Genuine G1/capture/resolver/canonical control continuation. Canonical PREPARED is NOT wire, retention, seal verification or activation. */
internal class SealCanonicalCases(private val cases: CutoffResolverCases) {
    private val f = cases.f
    private val publications = cases.publications
    private val wire = publications.wire

    fun nonemptyIntentAndSuccessor() {
        assertEquals(2, publications.cutoffEvents.size)
        val higher = publications.events.single { it.tuple.epoch == 2L }
        val higherBefore = publications.row(higher)
        val identities = publications.events.associate { it.route.eventId to publications.eventIdentity(it) }
        val outside = outsideCanonicalAndLease()
        val leader = cases.capture()
        val captured = f.row()
        val prepared = cases.prepareCapturedLive(leader.campaign)
        val intent = assertCanonical(prepared, leader.receipt.token)
        assertTrue(publications.cutoffEvents.all { publications.state(it) == "VERIFIED" })
        assertEquals(publications.cutoffEvents.map { it.route.objectKey }.toSet(), publications.objects.keys)
        assertEquals(2, wire.generated(), "Only the two ordinary publications need data keys; canonical preparation must not acquire a seal key.")
        assertEquals(higherBefore, publications.row(higher))
        assertEquals(identities, publications.events.associate { it.route.eventId to publications.eventIdentity(it) })
        assertEquals(captured, f.row(), "Canonical preparation links the exact CAPTURED slot without advancing or clearing it.")
        assertEquals(outside, outsideCanonicalAndLease())
        cases.assertReleased()

        val proofs = publications.events.associate { it.route.eventId to publications.row(it) }
        val traffic = providerTraffic()
        val successor = f.successor(leader.campaign)
        assertNotEquals(leader.receipt.owner, successor.receipt.owner)
        assertTrue(successor.receipt.token > leader.receipt.token)
        val replay = cases.prepareCapturedLive(successor.campaign)
        assertEquals(intent, assertCanonical(replay, leader.receipt.token))
        assertEquals(prepared.operationToken, replay.operationToken)
        assertNotEquals(successor.receipt.token, replay.preparingFencingToken, "The successor cannot replace the original preparing token.")
        assertEquals(traffic, providerTraffic(), "Recomputing the complete proven manifest and canonical retry need neither KMS nor S3.")
        assertEquals(proofs, publications.events.associate { it.route.eventId to publications.row(it) })
        assertEquals(captured, f.row())
        assertEquals(outside, outsideCanonicalAndLease())

        entryRefusalsAndGenuineRecovery(successor.campaign, prepared.preparingFencingToken, intent)
        assertEquals(traffic, providerTraffic())
        assertEquals(proofs, publications.events.associate { it.route.eventId to publications.row(it) })
        assertEquals(captured, f.row())
        assertEquals(outside, outsideCanonicalAndLease())
        cases.assertReleased()
    }

    fun deferredCommitRefusalAndSuccessor() {
        assertEquals(1, publications.cutoffEvents.size)
        val outside = f.outsideRotationAndLease()
        val leader = cases.capture()
        val captured = f.row()
        val emptyIntent = sealRow()
        assertTrue(emptyIntent.values.all { it === JsonNull })
        val returned = AtomicReference<PreparedEpochSealV1?>()
        val failure = f.deferredSealCommitRefusal {
            assertThrows<PersistencePhaseException> { returned.set(cases.prepareCapturedLive(leader.campaign)) }
        }
        assertNull(returned.get(), "An unknown control COMMIT must not emit even a historical canonical PREPARED result.")
        cases.assertFailure(failure)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertEquals(emptyIntent, sealRow(), "The actual deferred FK COMMIT refusal rolls back intent; this is NOT a persisted lost-ACK case.")
        assertEquals(captured, f.row())
        assertEquals(outside, f.outsideRotationAndLease())
        assertEquals(
            "VERIFIED",
            publications.state(publications.cutoffEvents.single()),
            "Earlier publication-only proof commits survive the control failure.",
        )
        cases.assertReleased()

        val proofs = publications.events.associate { it.route.eventId to publications.row(it) }
        val traffic = providerTraffic()
        cases.assertFailure(assertThrows<PersistencePhaseException> { cases.prepareCapturedLive(leader.campaign) })
        assertEquals(emptyIntent, sealRow())
        assertEquals(traffic, providerTraffic(), "Removing the fixture constraint does not revive the failed campaign.")
        val successor = f.successor(leader.campaign)
        assertTrue(successor.receipt.token > leader.receipt.token)
        assertNotEquals(leader.receipt.owner, successor.receipt.owner)
        assertEquals(3, f.pooledPids.size) // Observe the actual original pools again after unknown COMMIT/quarantine.
        val prepared = cases.prepareCapturedLive(successor.campaign)
        val intent = assertCanonical(prepared, successor.receipt.token)
        assertNotEquals(
            leader.receipt.token,
            prepared.preparingFencingToken,
            "The stored empty row, not the failed in-memory candidate, controls recovery.",
        )
        assertEquals(traffic, providerTraffic(), "A new canonical intent over actual committed proofs creates no provider client or request.")
        assertEquals(proofs, publications.events.associate { it.route.eventId to publications.row(it) })
        val next = f.successor(successor.campaign)
        assertEquals(intent, assertCanonical(cases.prepareCapturedLive(next.campaign), successor.receipt.token))
        assertEquals(traffic, providerTraffic())
        assertEquals(captured, f.row())
        assertEquals(outside.map(::withoutSeal), outsideCanonicalAndLease())
        cases.assertReleased()
    }

    /** Reuses the actual entry control-row wait. It does not claim a race at the later COMPLAINT_SEAL_PREPARE lock. */
    private fun entryRefusalsAndGenuineRecovery(previous: CatalogCoordinatorLeaseCampaignV1, preparingToken: Long, intent: JsonObject) {
        val locks = CutoffResolverLockCases(cases)
        val bindingLeader = f.successor(previous)
        val exactBeforeFault = f.genesis.controlRow()
        try {
            locks.canonicalEntryBindingRefusal(bindingLeader.campaign)
            assertEquals(intent, sealRow())
        } finally {
            f.genesis.restoreControl(exactBeforeFault) // Restore only this exact faulted row, never manufacture accepted history.
        }
        cases.assertFailure(assertThrows<PersistencePhaseException> { cases.prepareCapturedLive(bindingLeader.campaign) })
        assertEquals(intent, sealRow())
        val leaseLeader = f.successor(bindingLeader.campaign)
        locks.canonicalEntryLeaseRefusal(leaseLeader.campaign)
        assertEquals(intent, sealRow())
        val successor = f.acquire() // Genuine DB-time acquisition of the expired row; no supplied token or lease receipt.
        assertTrue(successor.receipt.token > leaseLeader.receipt.token)
        assertNotEquals(successor.receipt.owner, leaseLeader.receipt.owner)
        assertEquals(intent, assertCanonical(cases.prepareCapturedLive(successor.campaign), preparingToken))
    }

    /** Independent twelve-field canonical byte/hash oracle; the producer never receives these fields, count, manifest or route. */
    private fun assertCanonical(result: PreparedEpochSealV1, preparingToken: Long): JsonObject {
        val rotation = f.row()
        val slot = checkNotNull(rotation.slot)
        assertEquals("CAPTURED", slot.state)
        val manifest = publications.expectedManifestSha256()
        val route = publications.routing.deriveEpochSeal(EpochSealRoutingTupleV1(EpochSealRangeV1(1, slot.epochBefore, ""), manifest)).active
        val payload = JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(1), "objectKind" to JsonPrimitive("EPOCH_SEAL"), "sealId" to JsonPrimitive(route.sealId),
                "writerGeneration" to JsonPrimitive(slot.writer.toString()), "dataScopeKind" to JsonPrimitive("LIVE"),
                "dataScopeId" to JsonPrimitive("00000000-0000-0000-0000-000000000000"), "epochStartInclusive" to JsonPrimitive(1),
                "epochEndInclusive" to JsonPrimitive(slot.epochBefore), "eventCount" to JsonPrimitive(publications.cutoffEvents.size),
                "eventManifestSha256" to JsonPrimitive(manifest), "precedingSealSha256" to JsonPrimitive(""),
                "preparingFencingToken" to JsonPrimitive(preparingToken),
            ).toSortedMap(),
        ).toString().toByteArray(Charsets.UTF_8)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
        assertEquals(slot.id, result.rotationId)
        assertEquals(rotation.sequence, result.sequence)
        assertEquals(slot.writer, result.writer)
        assertEquals(1L, result.epochStartInclusive)
        assertEquals(slot.epochBefore, result.epochEndInclusive)
        assertEquals(publications.cutoffEvents.size.toLong(), result.eventCount)
        assertEquals(manifest, result.eventManifestSha256)
        assertEquals(preparingToken, result.preparingFencingToken)
        assertEquals(route.routingKeyId, result.selectedRoutingKeyId)
        assertEquals(route.objectKey, result.objectKey)
        assertEquals(hash, result.semanticSha256)
        assertEquals(4, result.operationToken.version())
        assertEquals(2, result.operationToken.variant())
        val expected = JsonObject(
            mapOf(
                "seal_state" to JsonPrimitive("SEAL_PREPARED"), "seal_format" to JsonPrimitive(1),
                "seal_rotation_id" to JsonPrimitive(slot.id.toString()), "seal_rotation_sequence" to JsonPrimitive(rotation.sequence),
                "seal_preparing_fencing_token" to JsonPrimitive(preparingToken), "seal_routing_key_id" to JsonPrimitive(route.routingKeyId),
                "seal_epoch_start" to JsonPrimitive(1), "seal_preceding_hash" to JsonPrimitive("\\x"), "seal_epoch" to JsonPrimitive(slot.epochBefore),
                "seal_writer_generation" to JsonPrimitive(slot.writer.toString()),
                "seal_operation_token" to JsonPrimitive(result.operationToken.toString()),
                "seal_object_key" to JsonPrimitive(route.objectKey), "seal_bytes" to JsonPrimitive("\\x" + HexFormat.of().formatHex(payload)),
                "seal_hash" to JsonPrimitive("\\x$hash"), "seal_object_version" to JsonNull, "seal_ciphertext_hash" to JsonNull,
                "seal_retain_until" to JsonNull, "seal_verified_at" to JsonNull, "seal_verification_bytes" to JsonNull, "seal_verification_hash" to JsonNull,
            ),
        )
        val stored = sealRow()
        assertEquals(expected, stored, "Exact durable canonical7 plus original V14 cohort; no ciphertext, retention or VERIFIED evidence is manufactured.")
        cases.assertReleased()
        return stored
    }

    private fun sealRow(): JsonObject = JsonObject(Json.parseToJsonElement(f.genesis.controlRow()).jsonObject.filterKeys { it.startsWith("seal_") })

    private fun withoutSeal(row: String): JsonObject = JsonObject(Json.parseToJsonElement(row).jsonObject.filterKeys { !it.startsWith("seal_") })

    private fun outsideCanonicalAndLease(): List<JsonObject> = f.outsideRotationAndLease().map(::withoutSeal)

    private fun providerTraffic(): List<Int> = listOf(wire.requests.size, wire.kms.requests.size, wire.s3ClientsCreated, wire.kms.createdClients)
}

/** Shared canonical/held-sealer cut: actual deferred PG COMMIT refusal, never a supplied driver result or release flag. */
internal fun <T> EpochRotationTestFixture.deferredSealCommitRefusal(action: () -> T): T {
    val nonce = UUID.randomUUID().toString().replace("-", "")
    val table = "kira_seal_cut_$nonce"
    val constraint = "kira_seal_fk_$nonce"
    assertEquals(0L, observer.queryForObject("SELECT count(*) FROM pg_class WHERE relname = ?", Long::class.java, table))
    assertEquals(0L, observer.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = ?", Long::class.java, constraint))
    try {
        observer.execute("CREATE TABLE $table (state text PRIMARY KEY)")
        observer.execute(
            "ALTER TABLE complaint_journal_control ADD CONSTRAINT $constraint FOREIGN KEY (seal_state) " +
                "REFERENCES $table(state) DEFERRABLE INITIALLY DEFERRED",
        )
        return action()
    } finally {
        observer.execute("ALTER TABLE complaint_journal_control DROP CONSTRAINT IF EXISTS $constraint")
        observer.execute("DROP TABLE IF EXISTS $table")
    }
}
