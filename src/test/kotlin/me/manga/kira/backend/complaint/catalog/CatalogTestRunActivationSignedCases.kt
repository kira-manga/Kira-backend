package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCustodyExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCustodyFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Timestamp
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class TestActivationSignedInputCut { CURRENT_KEY, SCOPE, FULL_D, NON_CANONICAL, PREDECESSOR }
internal enum class TestActivationSignedRaceCut { SAME_ALLOCATION, GLOBAL_D_DURING_SIGN, LEASE_OWNER_DURING_SIGN }
internal enum class TestActivationSignedCorruptionCut { PARTIAL_RETURNED, ALLOCATION_BYTES, DIFFERENT_SIGNED_BYTES, ROW_SCOPE }

/** The named TEST owner performs every positive PREPARE/Sign/CAS; no diagnostic DTO or fixture SQL mints authority. */
internal object CatalogTestRunActivationSignedCases {
    fun stablePrefixAndExactReload(tls: VersionBoundPersistenceConnectedFixture, prefix: ActivationEvidencePrefix) =
        withSignedActivationRows(tls, prefix) { f ->
            val before = f.rows.counters.snapshot()
            val history = f.rows.history()
            val owner = f.begin()
            val budget = owner.budget
            f.beforeSign = {
                f.rows.assertPrepareCharge(before)
                assertNull(f.row()["signer_one_signature"])
                assertNull(f.row()["envelope_bytes"])
                assertTrue(f.probe().observations.keys.all { it.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
                assertSame(owner, SignedActivationObservation.active(tls.pools.catalogCoordinator))
                assertTrue(poolTestField<Boolean>(owner, "signArmed"))
                assertTrue(poolTestField<Boolean>(owner, "signConstructionIssued"))
                val assembly = checkNotNull(ownedCutField(owner, "assembly"))
                assertTrue(ownedCutField(assembly, "signer") != null, "Original Construction is retained before its factory/SDK opens.")
            }
            f.probe().beforeSql = { step ->
                if (step == "test-signature") {
                    for (leaf in listOf(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED, CatalogTestRunActivationReleaseLeafV1.SIGNATURE,
                        CatalogTestRunActivationReleaseLeafV1.ENVELOPE, CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED)) {
                        assertTrue(f.complete(leaf), "${leaf.name} must be complete before actual signature SQL dispatch.")
                    }
                }
            }
            f.assertSigned(f.freeze(owner))
            f.assertReleased(owner)
            assertSame(budget, owner.budget)
            assertEquals(1, f.signing.requests.size)
            assertEquals(1, f.signing.replies.single().calls)
            assertEquals(1, f.signing.replies.single().aborts)
            assertEquals(1, f.signing.replies.single().closes)
            assertEquals(history, f.rows.history().take(f.rows.evidence.prefix.size))
            f.rows.assertPrepareCharge(before)
            assertEquals(1, f.probe().steps.count { it == "test-insert-prepared" })
            assertEquals(1, f.probe().steps.count { it == "test-signature" })
            val paths = f.probe().calls.map { it.path }.distinct()
            assertTrue(paths.indexOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE) <
                paths.indexOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE))
            assertTrue(paths.indexOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE) <
                paths.indexOf(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD))
            val row = f.rows.preparedRow()
            val counters = f.rows.counters.snapshot()
            val leaves = f.leaves()
            val calls = f.probe().calls.size
            assertThrows<CatalogTestRunActivationExceptionV1> { f.freeze(owner) }
            assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(owner) }
            assertEquals(calls, f.probe().calls.size)
            assertEquals(1, f.signing.requests.size)
            assertEquals(row, f.rows.preparedRow())
            assertEquals(counters, f.rows.counters.snapshot())
            assertThrows<CatalogTestRunActivationExceptionV1> { f.rows.reload(f.rows.begin()) }
            assertEquals(row, f.rows.preparedRow(), "The old unsigned diagnostic route stays closed to signed rows.")
            assertEquals(counters, f.rows.counters.snapshot())
            assertEquals(1, f.signing.requests.size)

            // The richest Stable prefix exercises one cold restart; all three prefixes reached actual Sign/CAS above.
            if (prefix == ActivationEvidencePrefix.INVENTORY_ROTATED) {
                val lease = f.rows.lease()
                f.withFreshOwner { fresh ->
                    val recovered = f.begin(fresh)
                    f.assertSigned(f.recover(recovered))
                    f.assertReleased(recovered, fresh)
                    val current = f.rows.lease()
                    assertNotEquals(lease["lease_owner"], current["lease_owner"])
                    assertEquals((lease["lease_token"] as Long) + 1, current["lease_token"])
                    assertTrue((current["lease_expires_at"] as Timestamp).after(lease["lease_expires_at"] as Timestamp))
                    assertEquals(row, f.rows.preparedRow(), "Exact signed reload cannot even rewrite xmin.")
                    assertEquals(counters, f.rows.counters.snapshot(), "No second PREPARE charge/reserve/refund/daily mutation.")
                    assertEquals(leaves, f.leaves(), "Cold success does not rewrite historical custody or invent an old outcome.")
                    assertTrue(f.probe(fresh).steps.none { it == "test-signature" || it == "test-insert-prepared" || it.startsWith("charge:") })
                    assertEquals(1, f.signing.createdClients)
                }
            }
        }

    fun diagnosticAndColdUnsignedRefuse(tls: VersionBoundPersistenceConnectedFixture) = withSignedActivationRows(tls) { f ->
        val oldOwner = f.rows.begin()
        val diagnostic = f.rows.prepare(oldOwner)
        f.rows.assertPrepared(diagnostic)
        val row = f.rows.preparedRow()
        val counters = f.rows.counters.snapshot()
        assertFalse(Files.exists(f.allocation))
        f.withFreshOwner { fresh ->
            assertEquals(f.token, diagnostic.operationToken) // Retain the genuine old receipt; it still grants nothing.
            assertThrows<CatalogTestRunActivationExceptionV1> { f.freeze(f.begin(fresh)) }
            assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(f.begin(fresh)) }
            f.assertNoLostAssertions()
            assertEquals(row, f.rows.preparedRow())
            assertEquals(counters, f.rows.counters.snapshot())
            assertEquals(0, f.signing.createdClients)
            assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED))
            assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED), "Absence of old custody is not a retrofit opportunity.")
            f.rows.assertClosedAndHeadUnchanged()
        }
    }

    fun inputRefusal(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationSignedInputCut) =
        withSignedActivationRows(tls, selectedSigner = if (cut == TestActivationSignedInputCut.CURRENT_KEY) "catalog-old" else "catalog-new") { f ->
            val before = f.rows.counters.snapshot()
            val history = f.rows.history()
            val manifest = f.rows.evidence.manifest
            val bytes = when (cut) {
                TestActivationSignedInputCut.SCOPE -> CatalogTestRunActivationEvidenceFixture.manifestBytes(
                    f.rows.evidence.withRun(manifest.activationRecord.run.copy(testRunId = UUID.randomUUID().toString())),
                )
                TestActivationSignedInputCut.NON_CANONICAL -> f.rows.intent + byteArrayOf(' '.code.toByte())
                TestActivationSignedInputCut.PREDECESSOR -> {
                    val record = manifest.activationRecord.copy(previousEnvelopeSha256 = Sha256.hex(f.rows.evidence.prefix.first()))
                    CatalogTestRunActivationEvidenceFixture.manifestBytes(manifest.copy(
                        previousEnvelopeSha256 = record.previousEnvelopeSha256, activationRecord = record,
                        history = CatalogTestRunActivationEvidenceFixture.history(record),
                    ))
                }
                else -> f.rows.intent
            }
            val original = if (cut == TestActivationSignedInputCut.FULL_D) f.begin(process = f.rows.evidence.process(desiredGeneration = 8)) else f.begin()
            assertThrows<CatalogTestRunActivationExceptionV1> { f.freeze(original, bytes) }
            f.assertNoLostAssertions()
            assertEquals(history, f.rows.history())
            assertEquals(before, f.rows.counters.snapshot())
            assertEquals(0, f.signing.createdClients)
            assertFalse(f.exists(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED))
            assertEquals(0L, f.rows.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations WHERE operation_token = ?", Long::class.java, f.token))
            assertTrue(f.probe().steps.none { it == "test-insert-prepared" || it == "test-signature" || it.startsWith("charge:") })
        }

    fun originalAndAllocationRaces(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationSignedRaceCut) = withSignedActivationRows(tls) { f ->
        val before = f.rows.counters.snapshot()
        val original = f.begin()
        var raced = false
        f.beforeSign = {
            assertFalse(raced)
            raced = true
            when (cut) {
                TestActivationSignedRaceCut.SAME_ALLOCATION -> {
                    val sql = f.probe().calls.size
                    val reads = f.rows.http.createdClients
                    assertThrows<CatalogTestRunActivationExceptionV1> { f.freeze(f.begin()) }
                    assertSame(original, SignedActivationObservation.active(tls.pools.catalogCoordinator))
                    assertEquals(sql, f.probe().calls.size)
                    assertEquals(reads, f.rows.http.createdClients)
                    val allocation = Files.readAllBytes(f.allocation.resolve("allocation"))
                    val leaves = f.leaves()
                    val result = AtomicReference<Throwable?>()
                    val competitor = Thread {
                        try {
                            val budget = PersistenceTimeBudget.start(5_000, SystemPersistenceNanoClock)
                            CatalogTestRunActivationReleaseCustodyV1.retain(f.root, allocation, budget).use {
                                val failure = assertThrows<CatalogTestRunActivationCustodyExceptionV1> { it.openExisting() }
                                assertEquals(CatalogTestRunActivationCustodyFailureV1.LOCK_UNAVAILABLE, failure.code)
                            }
                        } catch (failure: Throwable) { result.set(failure) }
                    }.apply { isDaemon = true }
                    f.retainContender(competitor)
                    competitor.start()
                    competitor.join(3_000)
                    assertFalse(competitor.isAlive, "Bounded same-allocation contender must finish before original Sign proceeds.")
                    result.get()?.let { throw it }
                    assertEquals(leaves, f.leaves())
                }
                TestActivationSignedRaceCut.GLOBAL_D_DURING_SIGN -> assertEquals(1, f.rows.observer.update(
                    "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?",
                    ByteArray(32) { 0x6a }, ComplaintDataScope.LIVE.id,
                ))
                TestActivationSignedRaceCut.LEASE_OWNER_DURING_SIGN -> assertEquals(1, f.rows.observer.update(
                    "UPDATE complaint_journal_control SET lease_owner = ? WHERE data_scope_id = ?", UUID.randomUUID(), ComplaintDataScope.LIVE.id,
                ))
            }
        }
        if (cut == TestActivationSignedRaceCut.SAME_ALLOCATION) {
            f.assertSigned(f.freeze(original))
            f.assertReleased(original)
        } else {
            assertThrows<CatalogTestRunActivationExceptionV1> { f.freeze(original) }
            assertNull(f.row()["signer_one_signature"])
            assertNull(f.row()["envelope_bytes"])
            assertTrue(f.probe().steps.none { it == "test-signature" }, "Fresh post-lock full preimage/lease refusal precedes CAS.")
            f.rows.assertClosedAndHeadUnchanged()
        }
        assertTrue(raced)
        assertEquals(1, f.signing.requests.size)
        f.rows.assertPrepareCharge(before)
        f.assertNoLostAssertions()
    }

    fun coldCorruptionRefusesWithoutRepair(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationSignedCorruptionCut) = withSignedActivationRows(tls) { f ->
        val original = f.begin()
        f.assertSigned(f.freeze(original))
        f.assertReleased(original)
        tls.close() // Corrupt only explicitly owned test inputs after the real original holder has closed.
        when (cut) {
            TestActivationSignedCorruptionCut.PARTIAL_RETURNED -> Files.delete(f.marker(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED))
            TestActivationSignedCorruptionCut.ALLOCATION_BYTES -> {
                val path = f.allocation.resolve("allocation")
                val bytes = Files.readAllBytes(path).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                rewriteSealed(path, bytes)
                rewriteSealed(path.resolveSibling("allocation.complete"), ByteBuffer.allocate(68).putInt(bytes.size)
                    .put(Sha256.hex(bytes).toByteArray(Charsets.US_ASCII)).array())
            }
            TestActivationSignedCorruptionCut.DIFFERENT_SIGNED_BYTES -> {
                // Independently randomized valid PSS is a negative SQL replacement, not another provider operation.
                val envelope = CatalogTestRunActivationEvidenceFixture.signed(f.rows.evidence.manifest)
                val signature = Base64.getDecoder().decode(envelope.signatures.single().signatureBase64)
                assertFalse(signature.contentEquals(f.signatures.single()))
                val bytes = CatalogTestRunActivationEvidenceFixture.bytes(envelope)
                assertEquals(1, f.rows.observer.update(
                    "UPDATE complaint_catalog_mutations SET signer_one_signature = ?, envelope_bytes = ?, envelope_hash = ? WHERE operation_token = ?",
                    signature, bytes, HexFormat.of().parseHex(Sha256.hex(bytes)), f.token,
                ))
            }
            TestActivationSignedCorruptionCut.ROW_SCOPE -> assertEquals(1, f.rows.observer.update(
                "UPDATE complaint_catalog_mutations SET data_scope_id = ? WHERE operation_token = ?", UUID.randomUUID(), f.token,
            ))
        }
        val negativeRow = f.rows.preparedRow()
        val counters = f.rows.counters.snapshot()
        val negativeLeaves = f.leaves()
        val lease = f.rows.lease()
        f.withFreshOwner { fresh ->
            assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(f.begin(fresh)) }
            f.assertNoLostAssertions()
            assertEquals(1, f.signing.requests.size)
            assertEquals(negativeRow, f.rows.preparedRow())
            assertEquals(counters, f.rows.counters.snapshot())
            assertEquals(negativeLeaves, f.leaves(), "No missing marker, allocation or conflicting signed bytes may be repaired.")
            assertEquals(lease, f.rows.lease(), "Refuse the corrupt custody/row before even acquiring another lease.")
            assertTrue(f.probe(fresh).steps.none { it == "test-insert-prepared" || it == "test-signature" || it.startsWith("charge:") })
            f.rows.assertClosedAndHeadUnchanged()
        }
    }

    private fun rewriteSealed(path: Path, bytes: ByteArray) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        Files.write(path, bytes)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
    }
}
