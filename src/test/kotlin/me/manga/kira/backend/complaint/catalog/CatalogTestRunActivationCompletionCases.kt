package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.sql.Timestamp
import java.util.UUID

internal enum class TestActivationCompletionCopyCut {
    DUPLICATE_VERSION, DELETE_MARKER, VERSION_MISMATCH, RETENTION_MISMATCH, REPLICA_BYTES, BOTH_REPLACED, PRIMARY_NOT_COMPLETED,
}

/** Actual signed TEST prefix -> conditional primary SDK PUT -> authenticated dual copies -> real COMPLETE/pending. Never PROJECT. */
internal object CatalogTestRunActivationCompletionCases {
    fun stablePrefixAndColdPending(tls: VersionBoundPersistenceConnectedFixture, prefix: ActivationEvidencePrefix) =
        withCompletionActivationRows(tls, prefix) { f ->
            f.http.replicateOnPut = true
            f.signed.withFreshOwner { delivery ->
                val original = f.begin(delivery)
                val budget = original.budget
                val oldLease = f.rows.lease()
                val lower = checkNotNull(f.rows.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
                var constructionObserved = false
                var atomicCutObserved = false
                f.http.afterPutClientCreated = {
                    f.signed.releasedSql()
                    constructionObserved = true
                    assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
                    assertTrue(poolTestField<Boolean>(original, "publicationArmIssued"))
                    assertTrue(poolTestField<Boolean>(original, "publicationArmed"))
                    assertTrue(poolTestField<Boolean>(original, "putConstructionIssued"))
                    val assembly = checkNotNull(ownedCutField(original, "deliveryAssembly"))
                    val round = checkNotNull(ownedCutField(assembly, "putRound"))
                    assertTrue(ownedCutField(round, "construction") != null, "Original SDK Construction is retained before raw construction.")
                    val reload = f.signed.probe(delivery).calls.last { it.path == PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD }.phase
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, reload.databaseOutcome())
                    assertEquals(f.initialPrepared, f.rows.preparedRow())
                }
                f.signed.probe(delivery).beforeSql = { step -> if (step == "test-complete") {
                    assertSame(original, SignedActivationObservation.active(delivery.pools.catalogCoordinator))
                    assertEquals(1, f.http.put.closedClients, "Actual original PUT native cleanup precedes COMPLETE.")
                    assertEquals(f.http.read.createdClients, f.http.read.closedClients)
                    for (leaf in listOf(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY, CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY,
                        CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY, CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED)) {
                        assertTrue(f.signed.complete(leaf), "${leaf.name} precedes real COMPLETE dispatch.")
                    }
                } }
                f.signed.probe(delivery).afterSql = { step -> if (step == "test-mark-pending") {
                    assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE,
                        poolTestField<PersistencePhasePath>(checkNotNull(PersistencePhaseOwnership.current()), "path"))
                    assertUncommittedPairInvisible(f)
                    atomicCutObserved = true
                } }
                val receipt = try { f.deliver(original) } finally {
                    f.http.afterPutClientCreated = {}
                    f.signed.probe(delivery).beforeSql = {}
                    f.signed.probe(delivery).afterSql = {}
                }
                val upper = checkNotNull(f.rows.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
                assertTrue(constructionObserved && atomicCutObserved)
                assertTrue(!receipt.completedAt.isBefore(lower) && !receipt.completedAt.isAfter(upper))
                f.assertPending(receipt)
                f.assertReleased(original, delivery)
                f.singlePrimaryPut()
                assertSame(budget, original.budget)
                val lease = f.rows.lease()
                assertNotEquals(oldLease["lease_owner"], lease["lease_owner"])
                assertEquals((oldLease["lease_token"] as Long) + 1L, lease["lease_token"])
                assertTrue((lease["lease_expires_at"] as Timestamp).after(oldLease["lease_expires_at"] as Timestamp))
                val calls = f.signed.probe(delivery).calls
                val complete = calls.single { it.step == "test-complete" }
                assertSame(complete.phase, calls.single { it.step == "test-mark-pending" }.phase, "Row/head/pending use one actual TLS transaction.")
                assertTrue(calls.any { it.path == PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD })
                assertTrue(calls.none { it.step == "test-signature" || it.step == "test-insert-prepared" || it.step.startsWith("charge:") })
                for (leaf in listOf(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED, CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME,
                    CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME)) assertTrue(f.signed.complete(leaf), leaf.name)
                val row = f.rows.preparedRow()
                val leaves = f.signed.leaves()
                val count = calls.size
                assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) }
                assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(original) }
                assertThrows<CatalogTestRunActivationExceptionV1> { f.reloadPending(original) }
                assertEquals(count, calls.size)
                assertEquals(row, f.rows.preparedRow())
                assertEquals(leaves, f.signed.leaves())

                if (prefix == ActivationEvidencePrefix.INVENTORY_ROTATED) f.signed.withFreshOwner(previous = delivery) { cold ->
                    val recovered = f.begin(cold)
                    val result = f.reloadPending(recovered)
                    f.assertPending(result)
                    f.assertReleased(recovered, cold)
                    assertEquals(receipt.completedAt, result.completedAt)
                    assertEquals(row, f.rows.preparedRow(), "Cold exact pending replay must not rewrite xmin or timestamps.")
                    assertEquals(leaves, f.signed.leaves(), "A new SQL owner cannot rewrite historical arms/outcomes with its new lease.")
                    assertEquals(1, f.http.put.createdClients)
                    assertEquals(1, f.signed.signing.createdClients)
                    assertTrue(f.signed.probe(cold).calls.any { it.path == PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE })
                    assertTrue(f.signed.probe(cold).steps.none { it == "test-complete" || it == "test-mark-pending" || it.startsWith("charge:") })
                    assertEquals((lease["lease_token"] as Long) + 1L, f.rows.lease()["lease_token"])
                    assertNotEquals(lease["lease_owner"], f.rows.lease()["lease_owner"])
                }
            }
        }

    fun lostAcknowledgementLagAndReadOnlyRecovery(tls: VersionBoundPersistenceConnectedFixture) = withCompletionActivationRows(tls) { f ->
        f.signed.withFreshOwner { publishing ->
            val original = f.begin(publishing)
            var accepted = false
            f.http.afterPutAccepted = {
                f.signed.releasedSql()
                accepted = true
                throw IOException("Synthetic TEST primary accepted actual bytes before its acknowledgement was lost.")
            }
            try { assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) } } finally { f.http.afterPutAccepted = {} }
            assertTrue(accepted)
            assertEquals(f.http.publishedVersion, f.http.primaryVersion)
            assertNull(f.http.replicaVersion)
            assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
            f.assertPrepared()
            f.assertCleanFailure(original, publishing)
            f.singlePrimaryPut()
        }
        val armed = f.signed.leaves()
        f.signed.withFreshOwner { lagging ->
            val original = f.begin(lagging)
            assertEquals(CatalogTestRunActivationFailureV1.DELIVERY_PENDING,
                assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(original) }.code)
            f.assertPrepared()
            f.assertCleanFailure(original, lagging)
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
            assertEquals(1, f.http.put.createdClients)
            armed.forEach { (path, value) -> assertEquals(value, f.signed.leaves()[path]) }
            val again = f.begin(lagging)
            assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(again) }
            f.assertCleanFailure(again, lagging)
            assertEquals(1, f.http.put.createdClients, "Write credentials plus absent ACK/replica never mint a second PUT arm.")
        }
        f.http.completeReplication() // Only copies bytes accepted by the one real SDK PUT.
        val waiting = f.signed.leaves()
        f.signed.withFreshOwner { recovering ->
            val original = f.begin(recovering)
            f.assertPending(f.recover(original))
            f.assertReleased(original, recovering)
            f.singlePrimaryPut()
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED), "Later dual proof cannot fabricate an old PUT acknowledgement.")
            waiting.forEach { (path, value) -> assertEquals(value, f.signed.leaves()[path]) }
            assertEquals(1, f.signed.probe(recovering).steps.count { it == "test-complete" })
        }
    }

    fun acknowledgedReplicaLagKeepsItsArmAndPendingReloadCannotAdoptPrepared(tls: VersionBoundPersistenceConnectedFixture) =
        withCompletionActivationRows(tls) { f ->
            f.signed.withFreshOwner { publishing ->
                val original = f.begin(publishing)
                assertEquals(CatalogTestRunActivationFailureV1.DELIVERY_PENDING,
                    assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) }.code)
                f.assertPrepared()
                f.assertCleanFailure(original, publishing)
                f.singlePrimaryPut()
                assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
                assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION))
                assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
            }
            val waiting = f.signed.leaves()
            f.signed.withFreshOwner { fresh ->
                f.http.completeReplication() // Copies only the body sent by the original SDK PUT, not a seeded TEST object.
                val lease = f.rows.lease()
                val reads = f.http.read.createdClients
                val pendingOnly = f.begin(fresh)
                assertEquals(CatalogTestRunActivationFailureV1.STATE_REFUSED,
                    assertThrows<CatalogTestRunActivationExceptionV1> { f.reloadPending(pendingOnly) }.code)
                f.assertCleanFailure(pendingOnly, fresh)
                f.assertPrepared()
                assertEquals(lease, f.rows.lease(), "Strict pending reload cannot acquire a lease for a merely signed PREPARED row.")
                assertEquals(reads, f.http.read.createdClients)
                assertEquals(waiting, f.signed.leaves())
                assertTrue(f.signed.probe(fresh).steps.none { it == "test-complete" || it == "test-mark-pending" })

                val recovering = f.begin(fresh)
                f.assertPending(f.recover(recovering))
                f.assertReleased(recovering, fresh)
                f.singlePrimaryPut()
                waiting.forEach { (path, value) -> assertEquals(value, f.signed.leaves()[path]) }
                assertEquals(1, f.signed.probe(fresh).steps.count { it == "test-complete" })
                assertEquals(1, f.signed.probe(fresh).steps.count { it == "test-mark-pending" })
            }
        }

    fun rawCopyConflictsNeverCompleteOrReput(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationCompletionCopyCut) =
        withCompletionActivationRows(tls) { f ->
            f.http.replicateOnPut = true
            f.http.afterPutAccepted = {
                when (cut) {
                    TestActivationCompletionCopyCut.DUPLICATE_VERSION -> f.http.duplicatePrimary = true
                    TestActivationCompletionCopyCut.DELETE_MARKER -> f.http.deleteMarkerRole = "REPLICA"
                    TestActivationCompletionCopyCut.VERSION_MISMATCH -> f.http.replicaVersion = "different-test-copy-version"
                    TestActivationCompletionCopyCut.RETENTION_MISMATCH -> f.http.replicaRetention++
                    TestActivationCompletionCopyCut.REPLICA_BYTES -> f.http.replicaBytes = f.http.replicaBytes.copyOf().also {
                        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                    }
                    TestActivationCompletionCopyCut.BOTH_REPLACED -> {
                        // Independently valid randomized PSS is only a negative server response, never another provider Sign.
                        val replacement = CatalogTestRunActivationEvidenceFixture.bytes(CatalogTestRunActivationEvidenceFixture.signed(f.rows.evidence.manifest))
                        assertFalse(replacement.contentEquals(f.envelope))
                        f.http.primaryBytes = replacement.copyOf()
                        f.http.replicaBytes = replacement.copyOf()
                    }
                    TestActivationCompletionCopyCut.PRIMARY_NOT_COMPLETED -> f.http.primaryReplication = "PENDING"
                }
            }
            f.signed.withFreshOwner { publishing ->
                val original = f.begin(publishing)
                try { assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) } } finally { f.http.afterPutAccepted = {} }
                f.assertPrepared()
                f.assertCleanFailure(original, publishing)
                assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
                assertTrue(f.signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
                assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED))
                assertTrue(f.signed.probe(publishing).steps.none { it == "test-complete" || it == "test-mark-pending" })
                assertArrayEquals(f.envelope, f.http.bodies.single(), "Even rejected readback cannot change the actually sent signed body.")
            }
            val leaves = f.signed.leaves()
            f.signed.withFreshOwner { recovering ->
                val original = f.begin(recovering)
                assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(original) }
                f.assertPrepared()
                f.assertCleanFailure(original, recovering)
                assertEquals(leaves, f.signed.leaves(), "Conflicting raw copies are refused, not repaired or recertified.")
                assertEquals(1, f.http.put.requests.size)
                assertEquals(1, f.http.put.createdClients)
                assertTrue(f.signed.probe(recovering).steps.none { it == "test-complete" || it == "test-mark-pending" })
            }
        }

    /** Direct existing observer connection avoids adding a second Spring transaction participant at this intentional cut. */
    private fun assertUncommittedPairInvisible(f: CompletionActivationObservation) {
        checkNotNull(f.rows.observer.dataSource).connection.use { connection ->
            connection.prepareStatement(
                "SELECT m.state, m.completed_at, c.accepted_catalog_generation, c.pending_projection_token " +
                    "FROM complaint_catalog_mutations m CROSS JOIN complaint_journal_control c WHERE m.operation_token = ? AND c.data_scope_id = ?",
            ).use { statement ->
                statement.setObject(1, f.signed.token)
                statement.setObject(2, ComplaintDataScope.LIVE.id)
                statement.executeQuery().use { row ->
                    assertTrue(row.next())
                    assertEquals("PREPARED", row.getString(1))
                    assertNull(row.getTimestamp(2))
                    assertEquals(f.rows.evidence.generation - 1L, row.getLong(3))
                    assertNull(row.getObject(4, UUID::class.java))
                    assertFalse(row.next())
                }
            }
        }
    }
}
