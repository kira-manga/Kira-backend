package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.security.ComplaintJournalActorKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.ComplaintJournalDeletionTupleV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** Same owned TLS/PG -> SDK G1 -> real rotation. Historical events are data, never authorization or opaque work. */
internal fun withCutoffResolverHistory(
    tls: VersionBoundPersistenceConnectedFixture,
    cutoffCount: Int = 1,
    higherEpoch: Boolean = false,
    test: (EpochRotationTestFixture, CutoffResolverPublicationFixture) -> Unit,
) = withProcessBoundCatalogGenesis(
    tls,
    bindProcess = { consumers, pools ->
        val writer = consumers.journalConfiguration.declaration().writer
        VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochRotation(
            consumers,
            pools,
            1,
            7,
            UUID.fromString(writer.databaseIdentity),
            UUID.fromString(writer.restoreIdentity),
            VersionBoundCatalogReadbackTestFixture.settings(),
        )
    },
) { genesis ->
    CutoffResolverPublicationFixture(genesis).use { publications ->
        repeat(cutoffCount) { publications.seed(1) }
        if (higherEpoch) publications.seed(2)
        // No synthetic seal/checkpoint or rewritten G1 control is installed to create these historical rows.
        genesis.stageSigned()
        val wire = CurrentAcceptedCatalogRefreshHttpFixture(genesis)
        val refresh = wire.owner().use { it.refresh() }
        wire.assertFullReadback()
        EpochRotationTestFixture(tls, genesis, refresh).use { rotation ->
            rotation.prepare()
            test(rotation, publications)
        }
    }
}

/**
 * Fixture SQL inserts canonical PREPARED history only, BEFORE genuine G1/campaign/capture. It does not
 * qualify new API authorization, erasure, recovery-capacity accounting or LIVE provider/retention policy.
 * The actual resolver must issue its own committed/released row, perform SDK readback and persist proof.
 */
internal class CutoffResolverPublicationFixture(private val genesis: ProcessBoundCatalogGenesisFixture) : AutoCloseable {
    val routing = genesis.process.consumers.journalRouting
    private val observer = genesis.observer
    private val dataKeys = NeverOwnerDeleteAllDataKeys()
    private val codec = OwnerDeleteAllJournalCodecV1(routing, dataKeys)
    private val seeded = mutableListOf<OwnerDeleteAllJournalEventV1>()
    val events: List<OwnerDeleteAllJournalEventV1> get() = seeded.toList()
    val cutoffEvents: List<OwnerDeleteAllJournalEventV1> get() = seeded.filter { it.tuple.epoch == 1L }
    val objects = linkedMapOf<String, JournalPublisherObject>()
    lateinit var wire: OwnerDeleteAllJournalPublisherFixture
        private set

    fun seed(epoch: Long): OwnerDeleteAllJournalEventV1 {
        requireConnectionFree()
        assertEquals(
            true,
            observer.queryForObject(
                "SELECT accepted_catalog_generation IS NULL AND rotation_sequence = 0 FROM complaint_journal_control WHERE data_scope_id = ?",
                Boolean::class.java,
                ComplaintDataScope.LIVE.id,
            ),
            "Historical data must precede genuine G1/capture; fixture rows never reopen a closed range.",
        )
        val tuple = ComplaintJournalDeletionTupleV1(
            epoch,
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL,
            ComplaintJournalActorKindV1.INSTALLATION,
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            ByteArray(32) { (seeded.size + it).toByte() },
            ComplaintDataScope.LIVE,
        )
        val event = codec.canonicalize(tuple, listOf(UUID.randomUUID()))
        assertEquals(0, dataKeys.calls.get())
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaint_journal_publications (event_id, data_scope_id, test_only, writer_generation, journal_epoch, " +
                    "event_kind, target_count, routing_key_id, object_key, canonicalizer, event_bytes, semantic_hash, state, created_at) " +
                    "VALUES (?, ?, false, ?, ?, 'OWNER_DELETE_ALL', ?, ?, ?, 'kcj-1', ?, ?, 'PREPARED', clock_timestamp())",
                event.route.eventId, ComplaintDataScope.LIVE.id, UUID.fromString(routing.journalConfiguration.declaration().writer.generationId),
                epoch, event.complaintIds().size, event.route.routingKeyId, event.route.objectKey, event.canonicalBytes(),
                HexFormat.of().parseHex(event.semanticSha256),
            ),
        )
        seeded.add(event)
        if (!::wire.isInitialized) {
            wire = OwnerDeleteAllJournalPublisherFixture.historical(routing, event)
            wire.respond = ::reply
        }
        return event
    }

    fun sampleWall() {
        wire.wall = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
    }

    /** The existing raw HTTP fixture still drives genuine SDKs, signing, bounded bodies, crypto and cleanup. */
    fun reply(request: JournalPublisherHttpRequest): S3CatalogReply {
        val key = key(request)
        val current = objects[key]
        return when (request.kind) {
            "LIST" -> wire.listReply(listOfNotNull(current), key)

            "GET" -> wire.getReply(checkNotNull(current))

            else -> {
                if (current != null) {
                    OwnerDeleteAllJournalPublisherFixture.errorReply(412)
                } else {
                    val value = JournalPublisherObject(
                        key,
                        OwnerDeleteAllJournalPublisherFixture.VERSION,
                        request.body.copyOf(),
                        wire.wall.truncatedTo(ChronoUnit.SECONDS),
                        Instant.parse(request.header("x-amz-object-lock-retain-until-date")),
                        request.http.headers().entries.filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
                            .associate { it.key.lowercase().removePrefix("x-amz-meta-") to it.value.single() },
                    )
                    objects[key] = value
                    wire.putReply(value)
                }
            }
        }
    }

    fun key(request: JournalPublisherHttpRequest): String {
        val key = if (request.kind == "LIST") {
            request.http.firstMatchingRawQueryParameter("prefix").orElseThrow()
        } else {
            request.http.encodedPath().removePrefix("/${routing.journalConfiguration.declaration().journalLocation.bucket}/")
        }
        assertTrue(seeded.any { it.route.objectKey == key }, "Only this fixture's committed keys may be dispatched.")
        return key
    }

    fun row(event: OwnerDeleteAllJournalEventV1): String = checkNotNull(
        observer.queryForObject("SELECT to_jsonb(p)::text FROM complaint_journal_publications p WHERE event_id = ?", String::class.java, event.route.eventId),
    )

    fun state(event: OwnerDeleteAllJournalEventV1): String = checkNotNull(
        observer.queryForObject("SELECT state FROM complaint_journal_publications WHERE event_id = ?", String::class.java, event.route.eventId),
    )

    fun immutableRow(event: OwnerDeleteAllJournalEventV1): String = checkNotNull(
        observer.queryForObject(
            "SELECT (to_jsonb(p) - ARRAY['state','applied_at'])::text FROM complaint_journal_publications p WHERE event_id = ?",
            String::class.java,
            event.route.eventId,
        ),
    )

    fun eventIdentity(event: OwnerDeleteAllJournalEventV1): String = checkNotNull(
        observer.queryForObject(
            "SELECT (to_jsonb(p) - ARRAY['state','object_version','ciphertext_hash','object_created_at','retain_until'," +
                "'verified_at','verification_bytes','verification_hash','applied_at'])::text FROM complaint_journal_publications p WHERE event_id = ?",
            String::class.java,
            event.route.eventId,
        ),
    )

    /** Historical APPLIED state only AFTER real resolver verification. No erasure/apply/capacity authority is claimed. */
    fun advanceAppliedStateForTest(event: OwnerDeleteAllJournalEventV1) {
        val before = immutableRow(event)
        assertEquals(
            1,
            observer.update(
                "UPDATE complaint_journal_publications SET state = 'APPLIED', applied_at = clock_timestamp() " +
                    "WHERE event_id = ? AND state = 'VERIFIED' AND verification_bytes IS NOT NULL AND verification_hash IS NOT NULL",
                event.route.eventId,
            ),
        )
        assertEquals(before, immutableRow(event))
    }

    /** Deliberate unsupported row family; no unsupported codec or positive recovery proof is synthesized. */
    fun unsupportedFamilyForTest(event: OwnerDeleteAllJournalEventV1) {
        assertEquals(
            1,
            observer.update(
                "UPDATE complaint_journal_publications SET event_kind = 'RETENTION' WHERE event_id = ? AND state = 'PREPARED'",
                event.route.eventId,
            ),
        )
    }

    /** Independent bounded fixture oracle. The producer never receives this count, list, ordering or digest. */
    fun expectedManifestSha256(): String {
        val journal = routing.journalConfiguration.declaration()
        val prefix = "complaints/journal/v1/${journal.writer.generationId}/live/${ComplaintDataScope.LIVE.id}/ordinary/"
        val selected = cutoffEvents.map { event ->
            val observed = objects.getValue(event.route.objectKey)
            val hash = OwnerDeleteAllJournalPublisherFixture.hash(observed.bytes)
            assertEquals(
                true,
                observer.queryForObject(
                    "SELECT state IN ('VERIFIED','APPLIED') AND object_version = ? AND ciphertext_hash = ? " +
                        "AND verification_bytes IS NOT NULL AND verification_hash IS NOT NULL FROM complaint_journal_publications WHERE event_id = ?",
                    Boolean::class.java,
                    observed.version,
                    HexFormat.of().parseHex(hash),
                    event.route.eventId,
                ),
            )
            listOf(event.route.objectKey, observed.version, hash)
        }.sortedWith(compareBy<List<String>> { it[0] }.thenBy { it[1] }.thenBy { it[2] })
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        listOf(
            "kira-complaint-journal-epoch-seal-v1", "1", "manifest", journal.writer.generationId, prefix,
            "LIVE", ComplaintDataScope.LIVE.id.toString(), "1", "1", selected.size.toString(),
        ).forEach(::field)
        selected.forEach { triple -> triple.forEach(::field) }
        return HexFormat.of().formatHex(digest.digest())
    }

    fun assertReleased() {
        assertEquals(0, dataKeys.calls.get())
        if (::wire.isInitialized) {
            wire.assertClosedExchanges()
            assertEquals(wire.s3ClientsCreated, wire.s3ClientsClosed)
            assertEquals(wire.kms.createdClients, wire.kms.closedClients)
        }
        genesis.released()
    }

    override fun close() {
        assertReleased()
        seeded.forEach { event ->
            assertEquals(1, observer.update("DELETE FROM complaint_journal_publications WHERE event_id = ?", event.route.eventId))
        }
    }
}
