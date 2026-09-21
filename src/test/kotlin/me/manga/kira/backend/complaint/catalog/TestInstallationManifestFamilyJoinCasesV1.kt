package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows

/** Focused connected source cases; execution/acceptance belongs to the controller, not this author. */
internal object TestInstallationManifestFamilyJoinCasesV1 {
    fun mixedEmittedFamiliesAndAliases(tls: VersionBoundPersistenceConnectedFixture, adminBatch: Boolean = false) = withManifestFamilyJoinRun(tls, aliases = true, adminBatch = adminBatch) { f, admin, drain ->
        drain.requireManifestPredecessor()
        val adminKind = if (adminBatch) "ADMIN_BATCH_DELETE" else "ADMIN_DELETE"
        assertThrows<TestOrdinaryDrainExceptionV1> { drain.requireInventoryKind(adminKind) }
        val observed = TestOrdinaryDrainAccountingObservationV1(f, admin.eventId)
        val ordinary = manifestOrdinaryFamilyImage(f)
        val before = observed.state()
        val primaries = setOf(f.history.eventId, admin.eventId, f.history.allEventId)
        assertEquals(6L, drain.manifestCut().denial.firstInventory.versionCount)
        assertEquals(4L, f.observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ? AND event_kind = ?",
            Long::class.java, f.scope, adminKind))
        assertEquals(if (adminBatch) 2 else 1, f.observer.queryForObject("SELECT target_count FROM complaint_journal_publications WHERE event_id = ?", Int::class.java, admin.eventId))
        if (adminBatch) {
            val literal = me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
            assertEquals(literal.installation + literal.resource.scaled(2) + literal.audit.scaled(6) + literal.appliedOnly.scaled(4), before.promise)
            assertEquals(literal.audit.scaled(5) + literal.appliedOnly.scaled(4), before.used)
            assertEquals("CONVERTED", before.recoveryState)
            assertEquals(3L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND entity_type = 'complaint_scope' AND action = 'COMPLAINT_RECOVERY_APPLIED'", Long::class.java, f.scope))
        }
        assertEquals(0, f.observer.queryForObject("SELECT target_count FROM complaint_journal_publications WHERE event_id = ?", Int::class.java, f.history.allEventId))
        assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ?", String::class.java, f.history.actor.id))
        assertEquals("DELETED", f.observer.queryForObject("SELECT state FROM app_installations WHERE id = ?", String::class.java, f.history.actor.id))
        val preparation = TestRunInstallationManifestV1.begin(drain)
        TestInstallationManifestSqlProbeV1(f).use { probe ->
            probe.original = preparation
            assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, preparation.prepare())
            probe.assertReleased()
            assertEquals(listOf(TestInstallationManifestStepV1.CAPTURE, TestInstallationManifestStepV1.COMPLETE),
                probe.calls.filter { it.sql == PRIMARY_READ }.map { it.step }.distinct())
            listOf(TestInstallationManifestStepV1.CAPTURE, TestInstallationManifestStepV1.COMPLETE).forEach { step ->
                assertEquals(primaries, probe.calls.filter { it.step === step && it.sql == PRIMARY_READ }.map { it.arguments.single() }.toSet())
            }
        }
        assertEquals(ordinary, manifestOrdinaryFamilyImage(f), "PREPARE cannot change any ordinary primary, receipt, alias, P/U, credential, domain or audit byte/xmin.")
        val paid = observed.state()
        observed.assertTransfer(before, paid, reserveSpend = TestInstallationManifestPrepareCasesV1.MANIFEST_LITERAL)
        val unchanged = manifestInvariantImage(f)
        val order = f.sealHttp.order.size
        TestInstallationManifestPublicationSqlProbeV1(f).use { probe ->
            val original = TestInstallationManifestPublicationCasesV1.publish(preparation, probe)
            probe.assertReleased()
            assertEquals(listOf(TestInstallationManifestPublicationStepV1.CAPTURE, TestInstallationManifestPublicationStepV1.COMPLETE),
                probe.calls.filter { it.sql == PRIMARY_READ }.map { it.step }.distinct())
            listOf(TestInstallationManifestPublicationStepV1.CAPTURE, TestInstallationManifestPublicationStepV1.COMPLETE).forEach { step ->
                assertEquals(primaries, probe.calls.filter { it.step === step && it.sql == PRIMARY_READ }.map { it.arguments.single() }.toSet())
            }
            assertEquals(paid, observed.state()); assertEquals(unchanged, manifestInvariantImage(f))
            assertEquals(ordinary, manifestOrdinaryFamilyImage(f))
            TestInstallationManifestPublicationCasesV1.assertNoAccountingOrDomainWrites(probe)
            assertPublishedManifests(f, original, expectedDispositions = mapOf(f.history.actor.id.toString() to TestTerminalDispositionV1.DELETED))
        }
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "GENERATE", "LIST", "PUT", "LIST", "GET", "DECRYPT"), f.sealHttp.order.drop(order))
        assertThrows<TestOrdinaryDrainExceptionV1> { drain.requireInventoryKind("OWNER_DELETE_ALL") }
    }

    /** One selected genuine history on its caller's fresh root; post-drain damage is never restored/adopted by a successor. */
    fun missingReceiptAndPaidAuditRefuse(tls: VersionBoundPersistenceConnectedFixture, publication: Boolean, adminBatch: Boolean = false) {
        withManifestFamilyJoinRun(tls, adminBatch = adminBatch) { f, admin, drain ->
            val adminKind = if (adminBatch) "ADMIN_BATCH_DELETE" else "ADMIN_DELETE"
            val preparation = if (publication) prepare(f, drain) else null
            manifestRaw(f) { connection ->
                val sql = if (publication) "DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? AND action = 'COMPLAINT_DELETED' AND complaint_actor_kind = 'ADMIN'"
                    else "DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND publication_ref = ? AND operation = '$adminKind'"
                connection.prepareStatement(sql).use { statement ->
                    statement.setObject(1, f.scope)
                    statement.setString(2, if (publication) admin.target.toString() else admin.eventId)
                    assertEquals(1, statement.executeUpdate()) // Deliberate persisted evidence loss; no counter repair or successful result injection.
                }
            }
            val observed = TestOrdinaryDrainAccountingObservationV1(f, admin.eventId)
            val damaged = observed.image()
            val paid = observed.state()
            val providers = f.sealHttp.order.toList()
            if (preparation == null) TestInstallationManifestSqlProbeV1(f).use { probe ->
                val original = TestRunInstallationManifestV1.begin(drain).also { probe.original = it }
                assertThrows<TestInstallationManifestExceptionV1> { original.prepare() }
                probe.assertReleased(requireCommitted = false)
                assertTrue(probe.calls.any { it.sql == PRIMARY_READ }, "The actual family join must expose the absent receipt, not stop at a shape-only relation.")
                assertTrue(probe.calls.all { it.step === TestInstallationManifestStepV1.CAPTURE })
                assertTrue(probe.observations.keys.all { it.databaseOutcome() === PersistenceDatabaseOutcome.ROLLED_BACK })
            } else TestInstallationManifestPublicationSqlProbeV1(f).use { probe ->
                val original = preparation.beginPublication().also { probe.original = it }
                assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
                probe.assertReleased(requireCommitted = false)
                assertTrue(probe.calls.any { it.sql.contains("FROM audit_log WHERE entity_type = 'complaint'") }, "Actual audit U must be read even though the converted vector and version cut are unchanged.")
                assertTrue(probe.calls.all { it.step === TestInstallationManifestPublicationStepV1.CAPTURE })
                assertTrue(probe.observations.keys.all { it.databaseOutcome() === PersistenceDatabaseOutcome.ROLLED_BACK })
                TestInstallationManifestPublicationCasesV1.assertNoAccountingOrDomainWrites(probe)
            }
            assertEquals(damaged, observed.image(), "Missing receipt/audit evidence is refused, never reconstructed, charged, converted again or hidden.")
            assertEquals(paid, observed.state())
            assertEquals(providers, f.sealHttp.order, "Neither a manifest PUT nor any STS/KMS/S3 work can repair post-drain evidence loss.")
            assertTrue(f.sealHttp.manifestObjects.isEmpty())
        }
    }

    private fun prepare(f: TestRunOrdinaryDrainFixtureV1, drain: TestRunOrdinaryDrainV1): TestRunInstallationManifestV1 =
        TestRunInstallationManifestV1.begin(drain).also { original ->
            TestInstallationManifestSqlProbeV1(f).use { probe ->
                probe.original = original
                assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, original.prepare())
                probe.assertReleased()
            }
        }

    private val PRIMARY_READ = TestOrdinaryDrainSqlV1.primaryReceiptLocators.removeSuffix(" FOR UPDATE")
}
