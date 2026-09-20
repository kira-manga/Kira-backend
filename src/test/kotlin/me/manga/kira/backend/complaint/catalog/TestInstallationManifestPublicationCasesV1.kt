package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationStepV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Connected producer mechanics, not deployed IAM/retention/catalog/terminal acceptance. */
internal object TestInstallationManifestPublicationCasesV1 {
    fun publishPaidWinner(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        preparation.drain.requireManifestPredecessor()
        assertTrue(preparation.ordinaryCut.denial.firstInventory.versionCount > 0L, "Publication must recheck a nonempty cut owned by the completed drain.")
        assertThrows<TestOrdinaryDrainExceptionV1> { preparation.drain.requireInventoryKind("OWNER_DELETE") }
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val paid = observed.state()
        val unchanged = manifestInvariantImage(f)
        val history = observed.previousHistory()
        val primary = observed.primaryImage()
        val progress = observed.runBytes("permanent_denial_bytes")
        val seals = observed.runBytes("seal_set_bytes")
        val order = f.sealHttp.order.size
        val beforeCommit = linkedMapOf<PersistencePhaseContext, Map<String, List<String>>>()
        val afterCommit = linkedMapOf<PersistencePhaseContext, Map<String, List<String>>>()
        probe.before = { call ->
            if (call.sql in setOf(TestInstallationManifestPublicationSqlV1.freeze, TestInstallationManifestPublicationSqlV1.verify)) {
                beforeCommit[call.phase] = manifestRowsImage(f)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() { afterCommit[call.phase] = manifestRowsImage(f) }
                })
            }
        }
        probe.after = { call ->
            beforeCommit[call.phase]?.let { image -> assertEquals(image, manifestRowsImage(f), "A raw independent connection sees no uncommitted freeze/VERIFY.") }
        }
        f.sealHttp.beforeS3 = { request ->
            val last = probe.calls.last()
            assertEquals(TestInstallationManifestPublicationStepV1.FREEZE, last.step)
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, last.phase.databaseOutcome())
            probe.assertReleased()
            assertEquals(listOf("PREPARED" to "WIRE_FROZEN"), manifestStatePairs(f))
            val key = if (request.kind == "LIST") request.http.rawQueryParameters().getValue("prefix").single()
                else request.http.encodedPath().substringAfter("/${f.registration.process.consumers.journalConfiguration.declaration().journalLocation.bucket}/")
            assertTrue(key.contains("/installation-manifest/"))
            assertEquals(1, f.registration.process.publicationLanes.activeOwners().routineOwners)
        }
        val original = publish(preparation, probe)
        probe.before = {}; probe.after = {}; f.sealHttp.beforeS3 = {}
        probe.assertReleased()
        assertEquals(5, probe.observations.size)
        assertEquals(TestInstallationManifestPublicationStepV1.entries, probe.calls.map { it.step }.distinct())
        assertEquals(listOf(TestInstallationManifestPublicationStepV1.CAPTURE, TestInstallationManifestPublicationStepV1.COMPLETE),
            probe.calls.filter { it.sql == TestOrdinaryDrainSqlV1.appliedPage }.map { it.step }.distinct())
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "GENERATE", "LIST", "PUT", "LIST", "GET", "DECRYPT"), f.sealHttp.order.drop(order))
        assertEquals(2, beforeCommit.size); assertEquals(beforeCommit.keys, afterCommit.keys)
        beforeCommit.forEach { (phase, image) -> assertFalse(image == afterCommit.getValue(phase), "A successful phase's exact winner becomes visible after commit.") }
        assertEquals(paid, observed.state(), "All manifest liability was already paid: no double spend/refund/run progress or zero-promise conversion.")
        assertEquals(unchanged, manifestInvariantImage(f)); assertEquals(history, observed.previousHistory()); assertEquals(primary, observed.primaryImage())
        assertArrayEquals(progress, observed.runBytes("permanent_denial_bytes")); assertArrayEquals(seals, observed.runBytes("seal_set_bytes"))
        assertNoAccountingOrDomainWrites(probe)
        assertPublishedManifests(f, original)
        val count = probe.calls.size
        assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
        assertEquals(count, probe.calls.size)
    }

    fun exactVerifiedRetry(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        val first = publish(preparation, probe)
        val rows = manifestRowsImage(f)
        val paid = TestOrdinaryDrainAccountingObservationV1(f).state()
        val unchanged = manifestInvariantImage(f)
        val reference = first.authenticatedChunk(0)
        val order = f.sealHttp.order.size
        probe.reset()
        val retry = publish(preparation, probe)
        probe.assertReleased()
        assertNotSame(first, retry)
        assertEquals(first.leaseToken + 1, retry.leaseToken)
        assertEquals(reference, retry.authenticatedChunk(0))
        assertEquals(rows, manifestRowsImage(f), "Canonical/wire bytes, first verification timestamp and every winner xmin remain exact.")
        assertEquals(paid, TestOrdinaryDrainAccountingObservationV1(f).state()); assertEquals(unchanged, manifestInvariantImage(f))
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "LIST", "GET", "DECRYPT"), f.sealHttp.order.drop(order))
        assertTrue(probe.calls.any { it.step === TestInstallationManifestPublicationStepV1.FREEZE }, "Retry still rechecks current authority after STS.")
        assertTrue(probe.calls.none { it.sql in setOf(TestInstallationManifestPublicationSqlV1.freeze, TestInstallationManifestPublicationSqlV1.verify) })
        assertNoAccountingOrDomainWrites(probe)
        assertPublishedManifests(f, retry)
    }

    fun lostPutAcknowledgment(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        val requests = f.sealHttp.requests.size
        val paid = TestOrdinaryDrainAccountingObservationV1(f).state()
        f.sealHttp.lostPutAcknowledgment = true
        val original = publish(preparation, probe)
        f.sealHttp.lostPutAcknowledgment = false
        assertEquals(listOf("LIST", "PUT", "LIST", "GET"), f.sealHttp.requests.drop(requests).map { it.kind })
        assertEquals(500, f.sealHttp.requests.drop(requests).single { it.kind == "PUT" }.reply?.status)
        assertEquals(paid, TestOrdinaryDrainAccountingObservationV1(f).state())
        assertPublishedManifests(f, original)
    }

    /** One expensive501 enrollment setup only: all mixed-state and frozen retry assertions share it. */
    fun mixedTwoChunkRetry(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls, multiChunk = true) { f, preparation, probe ->
        assertEquals(2, preparation.capturedSource().count)
        assertEquals(500, preparation.capturedSource().chunk(0).count); assertEquals(1, preparation.capturedSource().chunk(1).count)
        assertEquals(listOf("PREPARED" to "CANONICAL", "PREPARED" to "CANONICAL"), manifestStatePairs(f))
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val paid = observed.state()
        val run = observed.runBytes("permanent_denial_bytes")
        val before = f.sealHttp.order.size
        var cut = false
        probe.before = { call ->
            if (call.step === TestInstallationManifestPublicationStepV1.LOAD && call.chunkIndex == 1 && call.sql == TestInstallationManifestPublicationSqlV1.publication) {
                cut = true
                error("Synthetic second-chunk LOAD refusal after genuine first-chunk VERIFY.")
            }
        }
        val first = preparation.beginPublication().also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { first.publish() }
        probe.before = {}; probe.assertReleased(requireCommitted = false)
        assertTrue(cut)
        assertEquals(listOf("VERIFIED" to "WIRE_FROZEN", "PREPARED" to "CANONICAL"), manifestStatePairs(f))
        assertEquals(1, f.sealHttp.manifestObjects.size)
        assertThrows<TestInstallationManifestExceptionV1> { first.authenticatedChunk(0) }
        val firstWinner = manifestPublicationImage(f, 0)
        assertEquals(paid, observed.state())
        observed.expireLeaseForRetry() // Explicit fixture-only expiry, not proof of natural lease passage.
        probe.reset()
        f.sealHttp.changeS3 = { request, reply ->
            if (request.kind == "GET" && checkNotNull(probe.original).chunkIndex == 1)
                reply.headers = reply.headers + ("x-amz-checksum-sha256" to listOf("A".repeat(43) + "="))
        }
        val second = preparation.beginPublication().also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { second.publish() }
        f.sealHttp.changeS3 = { _, _ -> }; probe.assertReleased(requireCommitted = false)
        assertEquals(listOf("VERIFIED" to "WIRE_FROZEN", "PREPARED" to "WIRE_FROZEN"), manifestStatePairs(f))
        assertEquals(2, f.sealHttp.manifestObjects.size)
        assertEquals(firstWinner, manifestPublicationImage(f, 0))
        assertEquals(paid, observed.state())
        val frozen = manifestRowsImage(f).getValue("sidecar")
        val beforeFinal = f.sealHttp.order.size
        observed.expireLeaseForRetry(); probe.reset()
        val completed = publish(preparation, probe)
        probe.assertReleased()
        assertEquals(frozen, manifestRowsImage(f).getValue("sidecar"))
        assertEquals(firstWinner, manifestPublicationImage(f, 0), "Reauthenticating the first VERIFIED chunk preserves its original bytes and xmin.")
        assertEquals(2, f.sealHttp.order.drop(before).count { it == "GENERATE" })
        assertEquals(2, f.sealHttp.order.drop(before).count { it == "PUT" })
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "LIST", "GET", "DECRYPT",
            "STS_SOURCE", "STS_ASSUME", "STS_TARGET", "LIST", "GET", "DECRYPT"), f.sealHttp.order.drop(beforeFinal))
        assertEquals(paid, observed.state()); assertArrayEquals(run, observed.runBytes("permanent_denial_bytes"))
        assertPublishedManifests(f, completed, entries = 501)
        assertNoAccountingOrDomainWrites(probe)
        val finalLocks = probe.calls.filter { it.step === TestInstallationManifestPublicationStepV1.COMPLETE && it.sql == TestInstallationManifestPublicationSqlV1.publication }
        val orderedIds = f.observer.query("SELECT event_id FROM complaint_journal_publications WHERE data_scope_id = ? AND event_kind = 'INSTALLATION_MANIFEST' ORDER BY object_key",
            { row, _ -> row.getString(1) }, f.scope)
        assertEquals(orderedIds, finalLocks.map { it.arguments.single() }, "COMPLETE holds exact genuinely verified publication locks in key order before counters/run.")
    }

    fun verifiedVersionMissingRefusesPut(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        publish(preparation, probe)
        val image = manifestRowsImage(f)
        val requests = f.sealHttp.requests.size
        val order = f.sealHttp.order.size
        probe.reset(); f.sealHttp.hideObject = true
        val retry = preparation.beginPublication().also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { retry.publish() }
        f.sealHttp.hideObject = false; probe.assertReleased(requireCommitted = false)
        assertEquals(listOf("LIST"), f.sealHttp.requests.drop(requests).map { it.kind })
        assertTrue(f.sealHttp.order.drop(order).none { it in setOf("GENERATE", "DECRYPT", "PUT") })
        assertEquals(image, manifestRowsImage(f))
        assertTrue(probe.calls.none { it.step === TestInstallationManifestPublicationStepV1.VERIFY })
    }

    fun malformedReadbacks(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val paid = observed.state()
        val order = f.sealHttp.order.size
        val requests = f.sealHttp.requests.size
        var mismatchedAcknowledgment = false
        f.sealHttp.changeS3 = { request, reply ->
            if (request.kind == "PUT") {
                assertEquals(200, reply.status)
                reply.headers = reply.headers + ("x-amz-version-id" to listOf("different-manifest-ack-version"))
                mismatchedAcknowledgment = true
            }
        }
        val wrongVersion = preparation.beginPublication().also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { wrongVersion.publish() }
        f.sealHttp.changeS3 = { _, _ -> }; probe.assertReleased(requireCommitted = false)
        assertTrue(mismatchedAcknowledgment)
        assertEquals(listOf("LIST", "PUT", "LIST"), f.sealHttp.requests.drop(requests).map { it.kind }, "ACK and exact LIST must identify the same version before GET/KMS.")
        assertEquals(listOf("PREPARED" to "WIRE_FROZEN"), manifestStatePairs(f))
        assertTrue(probe.calls.none { it.step === TestInstallationManifestPublicationStepV1.VERIFY })
        assertEquals(paid, observed.state())
        val corruptions = listOf<(S3CatalogReply) -> Unit>(
            { it.headers = it.headers + ("x-amz-meta-unexpected" to listOf("extra")) },
            { it.headers = it.headers + ("x-amz-object-lock-mode" to listOf("GOVERNANCE")) },
            { it.headers = it.headers + ("x-amz-object-lock-retain-until-date" to listOf("2020-01-01T00:00:00Z")) },
            { it.headers = it.headers + ("x-amz-checksum-sha256" to listOf("A".repeat(43) + "=")) },
            { it.bytes[0] = (it.bytes[0].toInt() xor 1).toByte() },
        )
        corruptions.forEach { change ->
            observed.expireLeaseForRetry(); probe.reset()
            f.sealHttp.changeS3 = { request, reply -> if (request.kind == "GET") change(reply) }
            val original = preparation.beginPublication().also { probe.original = it }
            assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
            f.sealHttp.changeS3 = { _, _ -> }; probe.assertReleased(requireCommitted = false)
            assertEquals(listOf("PREPARED" to "WIRE_FROZEN"), manifestStatePairs(f))
            assertTrue(probe.calls.none { it.step === TestInstallationManifestPublicationStepV1.VERIFY })
            assertEquals(paid, observed.state())
        }
        for (foreignKey in listOf(false, true)) {
            observed.expireLeaseForRetry(); probe.reset()
            f.sealHttp.manifestListing = { key, values ->
                val real = values.single()
                if (foreignKey) listOf(real.copy(key = "$key/unexpected")) else listOf(real, real.copy(version = "extra-version"))
            }
            val original = preparation.beginPublication().also { probe.original = it }
            val calls = f.sealHttp.requests.size
            assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
            f.sealHttp.manifestListing = { _, values -> values }; probe.assertReleased(requireCommitted = false)
            assertEquals(listOf("LIST"), f.sealHttp.requests.drop(calls).map { it.kind })
            assertTrue(probe.calls.none { it.step === TestInstallationManifestPublicationStepV1.VERIFY })
        }
        assertEquals(1, f.sealHttp.order.drop(order).count { it == "GENERATE" })
        assertEquals(1, f.sealHttp.order.drop(order).count { it == "PUT" })
        assertEquals(0, f.sealHttp.order.drop(order).count { it == "DECRYPT" }, "Malformed observations fail before expensive KMS authentication.")
        val frozen = manifestRowsImage(f).getValue("sidecar")
        observed.expireLeaseForRetry(); probe.reset()
        val successful = publish(preparation, probe)
        assertEquals(frozen, manifestRowsImage(f).getValue("sidecar"))
        assertEquals(paid, observed.state()); assertPublishedManifests(f, successful)
    }

    fun unpaidRemainderRefusesBeforeProviders(tls: VersionBoundPersistenceConnectedFixture) = withManifestPublicationRun(tls) { f, preparation, probe ->
        val observed = TestOrdinaryDrainAccountingObservationV1(f)
        val charge = TestInstallationManifestPrepareCasesV1.MANIFEST_LITERAL
        val unpaid = (observed.state().unused + charge).toLongArray().joinToString(",", "{", "}")
        manifestRaw(f) { connection ->
            connection.autoCommit = false
            try {
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                assertEquals(1, jdbc.update("UPDATE complaint_test_runs SET unused_reserve = ?::bigint[] WHERE data_scope_id = ?", unpaid, f.scope))
                for (counter in listOf(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, ComplaintCapacityCounter.RECOVERY_RESERVATIONS, ComplaintCapacityCounter.STORAGE_BYTES))
                    assertEquals(1, jdbc.update("UPDATE complaint_capacity_counters SET actual_units = actual_units - ?, test_reserved_units = test_reserved_units + ? WHERE name = ?",
                        charge[counter], charge[counter], counter.storedName))
                connection.commit()
            } catch (problem: Throwable) { connection.rollback(); throw problem }
        }
        val damaged = observed.image()
        val order = f.sealHttp.order.toList()
        val original = preparation.beginPublication().also { probe.original = it }
        assertThrows<TestInstallationManifestExceptionV1> { original.publish() }
        probe.assertReleased(requireCommitted = false)
        assertEquals(damaged, observed.image(), "Real rows without exact paid remainder are refused, not adopted, charged or backfilled.")
        assertEquals(order, f.sealHttp.order)
        assertTrue(probe.calls.all { it.step === TestInstallationManifestPublicationStepV1.CAPTURE })
        assertNoAccountingOrDomainWrites(probe)
    }

    internal fun publish(preparation: TestRunInstallationManifestV1, probe: TestInstallationManifestPublicationSqlProbeV1): TestRunInstallationManifestPublicationV1 =
        preparation.beginPublication().also { original ->
            probe.original = original
            assertEquals(TestRunInstallationManifestPublicationResultV1.ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED, original.publish())
        }

    internal fun assertNoAccountingOrDomainWrites(probe: TestInstallationManifestPublicationSqlProbeV1) {
        val allowed = setOf(TestInstallationManifestPublicationSqlV1.freeze, TestInstallationManifestPublicationSqlV1.verify,
            TestInstallationManifestPublicationSqlV1.acquire, TestInstallationManifestPublicationSqlV1.release)
        assertTrue(probe.calls.none { (it.sql.startsWith("UPDATE ") || it.sql.startsWith("INSERT ") || it.sql.startsWith("DELETE ")) && it.sql !in allowed })
    }
}
