package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant

internal fun withCatalogSignerRotationDelivery(tls: VersionBoundPersistenceConnectedFixture, test: (CatalogSignerRotationDeliveryFixture) -> Unit) =
    CatalogSignerRotationDeliveryFixture(tls).use { fixture ->
        fixture.prepare()
        test(fixture)
    }

/** Same production initial-author prefix and TLS/PG carrier. No seeded signed2 row, fabricated lease or pre-published cloud object. */
internal class CatalogSignerRotationDeliveryFixture(tls: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    private val roots = mutableListOf<CatalogSignerRotationDeliveryRoot>()
    val initial = CatalogSignerRotationInitialAuthorFixture(tls)
    val freeze get() = initial.freeze
    val observer get() = freeze.observer
    val core = CatalogSignerRotationFreezeCases(freeze)
    lateinit var prefix: CatalogSignerRotationFreezeInvocation
        private set
    lateinit var http: CatalogGenesisPublishHttpFixture
        private set
    lateinit var envelope: ByteArray
        private set
    lateinit var selectedCanonical: ByteArray
        private set
    lateinit var historicalBindingArguments: List<Any?>
        private set
    lateinit var historicalLease: Map<String, Any?>
        private set
    lateinit var frozenPrefix: Map<Path, Pair<Map<String, Any>, ByteArray>>
        private set

    fun retainRoot(root: CatalogSignerRotationDeliveryRoot) {
        roots.add(root) // Retained before preparation can start any original actors, including failed preparation.
    }

    fun prepare() {
        initial.assemble()
        initial.bootstrap()
        val before = freeze.d7.desired.control()
        val genesis = core.genesisJson()
        val capacity = core.counterBalances()
        prefix = initial.invocation()
        val result = prefix.execute()
        assertEquals(CatalogSignerRotationFreezeStateV1.SIGNED_PREPARED, result.state)
        assertEquals(2L, result.generation)
        assertEquals(freeze.manifest.operationToken, result.operationToken)
        prefix.assertReleased()
        assertEquals(2, prefix.signing.requests.size)
        envelope = core.assertSignedSql(prefix.producedSignatures)
        assertEquals(Sha256.hex(envelope), result.envelopeSha256)
        core.assertCharge(capacity)
        core.assertHeadUnchanged(before, genesis)
        selectedCanonical = initial.process.canonicalBytes()
        // Full B is detached before retiring its real declaration/process owner; retirement is never a fresh authority source.
        historicalBindingArguments = initial.retainedCampaign().binding.arguments().toList()
        historicalLease = leaseRow()
        initial.assertClosed()
        frozenPrefix = freeze.snapshotLeaves()
        val leaves = CatalogSignerRotationReleaseLeafV1.entries
        val freezeEnd = CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME.ordinal + 1
        leaves.take(freezeEnd).forEach { assertTrue(freeze.complete(it), it.name) }
        leaves.drop(freezeEnd).forEach { assertFalse(freeze.exists(it), it.name) }
        freeze.d7.retireRuntime()
        prefix.fixtureCleanup()
        assertEquals(historicalLease, leaseRow(), "Local session/root retirement cannot expire, relinquish or replace the DB lease.")
        http = CatalogGenesisPublishHttpFixture(
            envelope,
            freeze.manifest.creation.createdAtEpochSecond,
            predecessorBytes = freeze.d7.envelope,
            predecessorRetainUntil = freeze.d7.retainUntil.epochSecond,
        )
        assertNull(http.primaryVersion)
        assertNull(http.replicaVersion)
        assertTrue(http.primaryBytes.isEmpty() && http.replicaBytes.isEmpty())
        assertNoFurtherSign()
    }

    /** Wait for the unchanged server expiry before constructing a fresh one-shot operation allowance. */
    fun awaitActualLeaseExpiry() {
        requireConnectionFree()
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        val before = leaseRow()
        val expires = (before["lease_expires_at"] as Timestamp).toInstant()
        val wait = PersistenceTimeBudget.start(35_000)
        while (databaseNow().isBefore(expires)) Thread.sleep(wait.remainingMillis(100))
        assertEquals(before, leaseRow())
        assertEquals(0L, freeze.clock.extraNanos)
    }

    fun leaseRow(): Map<String, Any?> = observer.queryForMap(
        "SELECT lease_owner, lease_token, lease_expires_at, updated_at FROM complaint_journal_control WHERE data_scope_id = ?",
        ComplaintDataScope.LIVE.id,
    )

    fun head(): Map<String, Any?> = observer.queryForMap(
        "SELECT accepted_catalog_generation, accepted_catalog_hash, pending_projection_token, maintenance_closed, creation_closed " +
            "FROM complaint_journal_control WHERE data_scope_id = ?",
        ComplaintDataScope.LIVE.id,
    )

    fun invariantControl(): String = checkNotNull(
        observer.queryForObject(
            "SELECT (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'," +
                "'accepted_catalog_generation','accepted_catalog_hash','pending_projection_token'])::text " +
                "FROM complaint_journal_control c WHERE data_scope_id = ?",
            String::class.java,
            ComplaintDataScope.LIVE.id,
        ),
    )

    fun databaseNow(): Instant = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()

    fun assertNoFurtherSign() {
        assertEquals(2, freeze.invocations.sumOf { it.signing.requests.size })
        assertArrayEquals(envelope, freeze.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE))
        freeze.assertLeavesUnchanged(frozenPrefix, allowAdditionalLeaves = true)
        freeze.d7.assertFrozenUnchanged()
    }

    override fun close() {
        val stopped = runCatching(freeze.d7::retireRuntime)
        val retired = freeze.invocations.map { runCatching(it::fixtureCleanup) }
        val ready = runCatching {
            stopped.getOrThrow()
            requireConnectionFree()
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
            assertTrue(freeze.invocations.all { it.cleanupVerified })
            assertTrue(roots.all { it.cleanupVerified }, "Every original delivery root must physically retire before fixture row cleanup.")
        }
        val detached = runCatching {
            ready.getOrThrow()
            if (::prefix.isInitialized) {
                // Post-assertion fixture isolation only: detach this fixture's FK before the existing teardown deletes operation2.
                // Never PROJECT, alter lease/head/times, clear a failed owner slot, or turn UNKNOWN into a cleanup/success receipt.
                observer.update(
                    "UPDATE complaint_journal_control SET pending_projection_token = NULL WHERE data_scope_id = ? AND pending_projection_token = ?",
                    ComplaintDataScope.LIVE.id,
                    freeze.token,
                )
            }
        }
        val closed = runCatching {
            ready.getOrThrow()
            detached.getOrThrow()
            initial.close()
        }
        rethrowSignerRotationFixtureFailures(listOf(stopped) + retired + listOf(ready, detached, closed))
    }
}
