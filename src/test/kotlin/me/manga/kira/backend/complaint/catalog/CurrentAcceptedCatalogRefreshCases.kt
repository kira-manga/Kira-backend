package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.time.Duration
import java.util.UUID

/** Existing SAME_THREAD TLS suite delegates here; no new PostgreSQL registration or separately owned pool. */
internal fun withCurrentAcceptedCatalogRefresh(tls: VersionBoundPersistenceConnectedFixture, test: (CurrentAcceptedCatalogRefreshCases) -> Unit) =
    withProcessBoundCatalogGenesis(
        tls,
        bindProcess = { consumers, pools ->
            val writer = consumers.journalConfiguration.declaration().writer
            VersionBoundComplaintProcessConfiguration.fromRetained(
                consumers,
                pools,
                1,
                7,
                UUID.fromString(writer.databaseIdentity),
                UUID.fromString(writer.restoreIdentity),
                VersionBoundCatalogReadbackTestFixture.settings(),
            )
        },
    ) { test(CurrentAcceptedCatalogRefreshCases(it)) }

/** Genuine signed public G1 -> real SDK HTTP SPI -> original owned PG, not supplied catalog or checkpoint success. */
internal class CurrentAcceptedCatalogRefreshCases(private val f: ProcessBoundCatalogGenesisFixture) {
    fun preparedRefreshCompletesProjectsAndRetries() {
        f.stageSigned()
        assertPrepared()
        val counters = counterRows()
        val wire = CurrentAcceptedCatalogRefreshHttpFixture(f)
        wire.owner().use { owner ->
            assertHead(owner.refresh().catalogFor(f.process))
            wire.assertFullReadback()
            assertProjected()
            assertEquals(counters, counterRows(), "Completion/projection must consume only the original prepaid G1 charge.")
            val projected = f.state()
            wire.clock.advance(Duration.ofMinutes(1))
            assertHead(owner.refresh().catalogFor(f.process))
            wire.assertFullReadback(attempts = 2)
            assertEquals(projected, f.state(), "Exact projected retry must preserve every row, timestamp, copy tuple and counter.")
        }
        f.released()
    }

    fun wrongBindingAndStaleHistoryAreRejected() {
        f.stageSigned()
        val desired = f.process.configurationHashBytes()
        val stale = desired.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        f.setDesired(stale)
        val prepared = f.state()
        val wire = CurrentAcceptedCatalogRefreshHttpFixture(f)
        wire.owner().use { owner ->
            rejectedPersistence { owner.refresh() }
            wire.assertFullReadback()
            assertEquals(prepared, f.state(), "A stale explicit D must not complete or project genuine PREPARED G1.")
            assertPrepared()
            f.setDesired(null)
            rejectedPersistence { owner.refresh() }
            wire.assertFullReadback(attempts = 2)
            val pending = assertInstanceOf(
                LocalCatalogSnapshot.ProjectionPending::class.java, f.coordinator.snapshot.load(f.initial, f.current, f.policy),
            )
            assertEquals(f.token.toString(), pending.projection.operationToken)
            assertArrayEquals(f.genesisBytes, pending.projection.signedEnvelopeBytes)
            val completed = mutation()
            assertEquals("COMPLETED", completed["state"])
            assertNotNull(completed["completed_at"])
            assertNull(completed["projected_at"])
            val pendingControl = f.observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
            assertNull(pendingControl["desired_configuration_hash"], "Completion must not synthesize D or grant a bound projection.")
            assertEquals(f.token, pendingControl["pending_projection_token"])
            f.setDesired(desired)
            val result = owner.refresh()
            wire.assertFullReadback(attempts = 3)
            assertHead(result.catalogFor(f.process))
            assertProjected()
            assertEquals(completed["completed_at"], mutation()["completed_at"], "Pending resume must project, never repeat completion.")
            val exact = recompose(checkNotNull(f.process.catalogReadback))
            assertArrayEquals(desired, exact.configurationHashBytes())
            val changed = recompose(VersionBoundCatalogReadbackTestFixture.settings(pageSize = 2))
            assertFalse(desired.contentEquals(changed.configurationHashBytes()))
            val projected = f.state()
            val requests = wire.http.requests.size
            for (other in listOf(exact, changed, recompose(null))) {
                assertSame(f.process.consumers, other.consumers)
                assertSame(f.process.pools, other.pools)
                rejected(CatalogReadbackFailure.INVALID_POLICY) { result.catalogFor(other) }
            }
            assertEquals(requests, wire.http.requests.size)
            assertEquals(projected, f.state(), "A historical result is not convertible to another process or reader configuration.")
            f.setDesired(stale)
            val conflict = f.state()
            rejectedPersistence { owner.refresh() }
            wire.assertFullReadback(attempts = 4)
            assertEquals(conflict, f.state(), "Projected retry must recheck current D rather than trust the previous result.")
            f.setDesired(desired)
            assertHead(owner.refresh().catalogFor(f.process))
            wire.assertFullReadback(attempts = 5)
            assertEquals(projected, f.state())
        }
    }

    fun expiredRefreshClosesBeforePersistence() {
        f.stageSigned()
        val prepared = f.state()
        val budget = Math.multiplyExact(checkNotNull(f.process.catalogReadback).totalAttemptMillis, 1_000_000L)
        val opening = CurrentAcceptedCatalogRefreshHttpFixture(f)
        opening.afterHttpConstruction = { opening.now = budget }
        opening.owner().use { owner -> rejected(CatalogReadbackFailure.LIMIT_EXCEEDED) { owner.refresh() } }
        opening.assertCleanupCalls()
        assertEquals(1, opening.http.createdClients, "A late first HTTP return must stop before the replica constructor.")
        assertTrue(opening.http.requests.isEmpty())
        assertEquals(prepared, f.state())

        val closing = CurrentAcceptedCatalogRefreshHttpFixture(f)
        closing.afterHttpClose = { ordinal -> if (ordinal == 2) closing.now = budget }
        closing.owner().use { owner ->
            rejected(CatalogReadbackFailure.LIMIT_EXCEEDED) { owner.refresh() }
            closing.assertFullReadback()
            assertEquals(prepared, f.state(), "Even fully consumed G1 cannot cross to completion after the attempt expires during cleanup.")
            assertPrepared()
            closing.afterHttpClose = {}
            assertHead(owner.refresh().catalogFor(f.process))
            closing.assertFullReadback(attempts = 2)
            assertProjected() // Expiry with actually successful cleanup does not permanently poison the original coordinator.
        }
    }

    fun failedProviderCleanupPoisonsOriginalCoordinator() {
        f.stageSigned()
        val prepared = f.state()
        val wire = CurrentAcceptedCatalogRefreshHttpFixture(f)
        val replacementWire = CurrentAcceptedCatalogRefreshHttpFixture(f)
        val samePoolsNewProcess = recompose(checkNotNull(f.process.catalogReadback))
        assertArrayEquals(f.process.configurationHashBytes(), samePoolsNewProcess.configurationHashBytes())
        var refusedDuringConstruction = false
        replacementWire.owner(samePoolsNewProcess).use { replacement ->
            wire.afterHttpConstruction = { ordinal ->
                if (ordinal == 1) {
                    rejected(CatalogReadbackFailure.LIMIT_EXCEEDED) { replacement.refresh() }
                    refusedDuringConstruction = true
                }
            }
            wire.afterHttpClose = { ordinal -> if (ordinal == 1) throw IOException("Synthetic raw-client close acknowledgement failure.") }
            wire.owner().use { owner -> rejected(CatalogReadbackFailure.CLOSE_FAILURE) { owner.refresh() } }
            wire.assertFullReadback()
            assertTrue(refusedDuringConstruction, "Custody must precede the very first HTTP constructor.")
            assertEquals(prepared, f.state(), "A throwing client cleanup must never complete/project G1 or return a result.")
            rejected(CatalogReadbackFailure.LIMIT_EXCEEDED) { replacement.refresh() }
            replacementWire.assertCleanupCalls()
            assertEquals(0, replacementWire.http.createdClients)
            assertTrue(replacementWire.http.requests.isEmpty())
            assertEquals(prepared, f.state())
        }
        // The original refresh is closed above, but that does not replace its unknown native-cleanup receipt.
        wire.assertCleanupCalls()
        f.released()
    }

    private fun assertHead(evidence: CatalogCommonHeadEvidence) {
        assertEquals(1L, evidence.chain.tail.generation)
        assertEquals(VersionBoundCatalogReadbackTestFixture.EXPECTED_GENESIS_SHA256, evidence.chain.tail.envelopeSha256)
        assertEquals(VersionBoundCatalogReadbackTestFixture.retainUntil.epochSecond, evidence.retainUntilEpochSecond)
        assertEquals(S3CatalogReadbackFixture.VERSION, evidence.objectVersion)
        assertEquals(f.genesisBytes.size.toLong(), evidence.primaryEncodedBytes)
        assertEquals(evidence.primaryEncodedBytes, evidence.replicaEncodedBytes)
        assertTrue(evidence.chain.inventory.sources.isEmpty() && evidence.chain.inventory.copies.isEmpty())
    }

    private fun assertPrepared() {
        val local = assertInstanceOf(LocalCatalogSnapshot.PreparedGenesis::class.java, f.coordinator.snapshot.load(f.initial, f.current, f.policy))
        assertArrayEquals(f.genesisBytes, local.mutation.signedEnvelopeBytes)
        val row = mutation()
        assertEquals("PREPARED", row["state"])
        assertNull(row["completed_at"])
        assertNull(row["projected_at"])
        f.released()
    }

    private fun assertProjected() {
        val local = assertInstanceOf(LocalCatalogSnapshot.Accepted::class.java, f.coordinator.snapshot.load(f.initial, f.current, f.policy))
        assertEquals(1L, local.head.generation)
        assertEquals(VersionBoundCatalogReadbackTestFixture.EXPECTED_GENESIS_SHA256, local.head.envelopeSha256)
        val row = mutation()
        assertEquals("COMPLETED", row["state"])
        assertNotNull(row["completed_at"])
        assertNotNull(row["projected_at"])
        assertArrayEquals(f.genesisBytes, row["envelope_bytes"] as ByteArray)
        assertNotNull(row["primary_evidence_bytes"])
        assertNotNull(row["replica_evidence_bytes"])
        val control = f.observer.queryForMap("SELECT * FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id)
        assertArrayEquals(f.process.configurationHashBytes(), control["desired_configuration_hash"] as ByteArray)
        assertEquals(1L, control["publication_epoch"])
        assertEquals(true, control["maintenance_closed"])
        assertEquals(true, control["creation_closed"])
        assertNull(control["pending_projection_token"])
        assertNull(control["checkpoint_bytes"])
        assertNull(control["seal_state"])
        f.released()
    }

    private fun recompose(settings: VersionBoundCatalogReadbackConfigurationV1?): VersionBoundComplaintProcessConfiguration {
        val desired = f.process.desiredSettings()
        return VersionBoundComplaintProcessConfiguration.fromRetained(
            f.process.consumers,
            f.process.pools,
            desired.implementationSchema,
            desired.desiredGeneration,
            desired.databaseIdentity,
            desired.restoreIdentity,
            settings,
        )
    }

    private fun mutation(): Map<String, Any?> = f.observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", f.token)

    private fun counterRows(): List<String> =
        f.observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)

    private fun rejectedPersistence(action: () -> Any?) {
        val failure = assertThrows(PersistencePhaseException::class.java) { action() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        f.released()
    }

    private fun rejected(code: CatalogReadbackFailure, action: () -> Any?) {
        val failure = assertThrows(CatalogReadbackException::class.java) { action() }
        assertEquals(code, failure.code)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        f.released()
    }
}
