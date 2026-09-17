package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffPersistenceOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochSealCustodyV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.security.EpochSealAttemptV1
import me.manga.kira.backend.security.EpochSealRangeV1
import me.manga.kira.backend.security.EpochSealRoutingTupleV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Existing TLS/root/G1/history/capture setup. D4's actual cold owner exists before D and genuine G1 acceptance. */
internal fun withHeldEpochSeal(
    tls: VersionBoundPersistenceConnectedFixture,
    clock: HeldEpochSealClock,
    d4: Boolean = true,
    cutoffCount: Int = 1,
    test: (HeldEpochSealTestFixture) -> Unit,
) {
    var http: HeldEpochSealHttpFixture? = null
    var joined: HeldEpochSealTestFixture? = null
    try {
        withProcessBoundCatalogGenesis(
            tls,
            bindProcess = { consumers, pools ->
                val wire = HeldEpochSealHttpFixture(consumers, clock).also { http = it }
                val writer = consumers.journalConfiguration.declaration().writer
                if (d4) {
                    VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                        consumers, pools, 1, 7, UUID.fromString(writer.databaseIdentity), UUID.fromString(writer.restoreIdentity),
                        VersionBoundCatalogReadbackTestFixture.settings(), wire.lanes, wire.acquisition,
                    )
                } else {
                    VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochRotation(
                        consumers,
                        pools,
                        1,
                        7,
                        UUID.fromString(writer.databaseIdentity),
                        UUID.fromString(writer.restoreIdentity),
                        VersionBoundCatalogReadbackTestFixture.settings(),
                    )
                }
            },
        ) { genesis ->
            CutoffResolverPublicationFixture(genesis).use { history ->
                repeat(cutoffCount) { history.seed(1) }
                history.seed(2)
                genesis.stageSigned()
                val readback = CurrentAcceptedCatalogRefreshHttpFixture(genesis)
                val refresh = readback.owner().use { it.refresh() }
                readback.assertFullReadback()
                EpochRotationTestFixture(tls, genesis, refresh).use { rotation ->
                    rotation.prepare()
                    HeldEpochSealTestFixture(rotation, history, checkNotNull(http)).use {
                        joined = it
                        test(it)
                    }
                }
            }
        }
    } finally {
        // Before the joined fixture exists, this scope still owns the cold recipe/lanes from bind/refresh construction.
        if (joined == null) {
            http?.let { wire ->
                try {
                    wire.acquisition.close()
                } finally {
                    wire.lanes.close()
                }
            }
        }
    }
}

/** Observation/negative cuts only; no Prepared DTO, candidate, key, session, returned native owner or success flag is supplied. */
internal class HeldEpochSealTestFixture(val f: EpochRotationTestFixture, val history: CutoffResolverPublicationFixture, val wire: HeldEpochSealHttpFixture) :
    AutoCloseable {
    val clock = wire.clock
    val lanes = wire.lanes
    val issuer = f.coordinator.cutoffPublications
    private val held = mutableListOf<CatalogEpochSealCustodyV1>()
    private var retainedCleanup = false

    init {
        history.wire.beforePrepare = ::providerBoundary
        history.wire.kms.beforePrepare = ::providerBoundary
        history.wire.onClientClose = ::providerBoundary
        history.wire.kms.onClientClose = ::providerBoundary
        wire.boundary = ::providerBoundary
        wire.beforeConstruction = ::assertAcquisitionEntry
    }

    fun capture(): CatalogCoordinatorLeaseAcquisitionV1 {
        val leader = f.acquire()
        val request = f.protocol.requestScan(leader.campaign)
        f.released()
        val cutoff = f.protocol.captureEpoch(request)
        assertEquals(1L, cutoff.epoch)
        assertEquals(2L, cutoff.epochAfter)
        f.released()
        clock.arm()
        return leader
    }

    fun factory(selectedLanes: JournalPublicationLanesV1 = lanes): OwnerDeleteAllJournalPublisherFactoryV1 {
        history.sampleWall()
        return OwnerDeleteAllJournalPublisherFactoryV1.cutoffWithHttpFixture(
            selectedLanes,
            history.routing,
            OwnerDeleteAllJournalPublisherFixture.CREDENTIALS,
            history.wire::httpClient,
            history.wire.kms::httpClient,
            history.wire.clock,
            clock::nanoTime,
        )
    }

    fun open(campaign: CatalogCoordinatorLeaseCampaignV1): CatalogEpochSealCustodyV1 {
        val result = runCatching {
            factory().use { ordinary -> issuer.openPreparedSeal(campaign, ordinary).also { held.add(it) } }
        }
        wire.assertNoLostAssertions()
        return result.getOrThrow()
    }

    fun activeAttempt(): CatalogCutoffAttemptV1? = poolTestField<AtomicReference<CatalogCutoffAttemptV1?>>(issuer, "active").get()

    fun codecAttempt(): EpochSealAttemptV1 = poolTestField(checkNotNull(activeAttempt()), "codecAttempt")

    fun sealRow(): JsonObject = JsonObject(Json.parseToJsonElement(f.genesis.controlRow()).jsonObject.filterKeys { it.startsWith("seal_") })

    fun assertCanonical(preparingToken: Long): JsonObject {
        val manifest = history.expectedManifestSha256()
        val route = history.routing.deriveEpochSeal(EpochSealRoutingTupleV1(EpochSealRangeV1(1, 1, ""), manifest)).active
        val payload = JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(1), "objectKind" to JsonPrimitive("EPOCH_SEAL"), "sealId" to JsonPrimitive(route.sealId),
                "writerGeneration" to JsonPrimitive(f.row().slot!!.writer.toString()), "dataScopeKind" to JsonPrimitive("LIVE"),
                "dataScopeId" to JsonPrimitive("00000000-0000-0000-0000-000000000000"), "epochStartInclusive" to JsonPrimitive(1),
                "epochEndInclusive" to JsonPrimitive(1), "eventCount" to JsonPrimitive(history.cutoffEvents.size),
                "eventManifestSha256" to JsonPrimitive(manifest), "precedingSealSha256" to JsonPrimitive(""),
                "preparingFencingToken" to JsonPrimitive(preparingToken),
            ).toSortedMap(),
        ).toString().toByteArray(Charsets.UTF_8)
        val stored = sealRow()
        assertEquals(JsonPrimitive("\\x" + HexFormat.of().formatHex(payload)), stored.getValue("seal_bytes"))
        assertEquals(
            JsonPrimitive("\\x" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))),
            stored.getValue("seal_hash"),
        )
        assertEquals(JsonPrimitive(route.objectKey), stored.getValue("seal_object_key"))
        assertEquals(JsonPrimitive(preparingToken), stored.getValue("seal_preparing_fencing_token"))
        assertEquals(JsonPrimitive("SEAL_PREPARED"), stored.getValue("seal_state"))
        assertEquals(JsonPrimitive(1), stored.getValue("seal_format"))
        listOf("object_version", "ciphertext_hash", "retain_until", "verified_at", "verification_bytes", "verification_hash").forEach {
            assertEquals(JsonNull, stored.getValue("seal_$it"), "Held encryption must not manufacture wire freeze, retention or VERIFIED state.")
        }
        val token = UUID.fromString(stored.getValue("seal_operation_token").jsonPrimitive.content)
        assertEquals(4, token.version())
        assertEquals(2, token.variant())
        return stored
    }

    fun assertHeld(owner: CatalogEpochSealCustodyV1, attempt: CatalogCutoffAttemptV1) {
        assertTrue(held.any { it === owner })
        assertSame(attempt, activeAttempt(), "The original issuer must retain the same unfinished run, not clear active in its return finally.")
        owner.requireHeld()
        attempt.requireRunning()
        assertEquals(1, lanes.activeOwners().routineOwners)
        assertEquals(0, lanes.activeOwners().privacyOwners)
        assertEquals(2, wire.sts.createdClients - wire.sts.closedClients)
        assertEquals(1, wire.kms.createdClients - wire.kms.closedClients)
        wire.assertExchangesClosed()
        history.assertReleased()
        f.released()
    }

    fun assertReleased() {
        assertNull(activeAttempt())
        assertEquals(0L, lanes.activeOwners().totalOwners)
        assertEquals(wire.sts.createdClients, wire.sts.returnedClientCloses)
        assertEquals(wire.kms.createdClients, wire.kms.returnedClientCloses)
        wire.assertExchangesClosed()
        history.assertReleased()
        f.released()
    }

    fun expectRetainedCleanup() {
        retainedCleanup = true
    }

    private fun assertAcquisitionEntry() = wire.preserveAssertions {
        providerBoundary()
        assertNotNull(activeAttempt())
        assertTrue(lanes.activeOwners().routineOwners >= 1, "The actual seal custody must be charged before native construction.")
        val preparation = clock.preparations.last()
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, preparation.databaseOutcome())
        assertTrue(preparation.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        val operation = ownedCutField(preparation.cutoffPublications, "retained") as CatalogCutoffPersistenceOperationV1
        assertTrue(operation.released())
        operation.requireReleased() // Same original caller: actual commit AND release, not a replacement DTO or observed flag.
        assertSame(activeAttempt(), operation.attempt)
        assertEquals(JsonPrimitive("SEAL_PREPARED"), sealRow().getValue("seal_state"))
    }

    private fun providerBoundary() = wire.preserveAssertions {
        requireConnectionFree()
        f.released()
        assertEquals(
            0L,
            f.observer.queryForObject(
                "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid " +
                    "WHERE a.datname = current_database() AND a.usename = ? AND " +
                    "(l.locktype = 'advisory' OR l.relation IN ('complaint_journal_control'::regclass, " +
                    "'complaint_journal_publications'::regclass, 'installation_deletion_receipts'::regclass, 'complaint_capacity_counters'::regclass))",
                Long::class.java,
                PgLifecycleDatabaseSettings.CANDIDATE,
            ),
            "The original candidate's control/publication/receipt/counter holder and epoch fence must end before SDK request or native close.",
        )
    }

    override fun close() {
        val failures = held.mapNotNull { runCatching(it::close).exceptionOrNull() }.toMutableList()
        runCatching(wire.acquisition::close).exceptionOrNull()?.let(failures::add)
        runCatching(lanes::close).exceptionOrNull()?.let(failures::add)
        if (retainedCleanup) {
            assertTrue(failures.isNotEmpty())
            failures.forEach(wire::assertSanitized)
            assertNotNull(activeAttempt())
            assertEquals(1L, lanes.activeOwners().totalOwners, "A thrown native close is not completion or a refunded owner.")
            wire.assertExchangesClosed()
            history.assertReleased()
            f.released()
        } else {
            failures.firstOrNull()?.let { throw it }
            assertReleased()
        }
        history.wire.beforePrepare = {}
        history.wire.kms.beforePrepare = {}
        history.wire.onClientClose = {}
        history.wire.kms.onClientClose = {}
    }
}
