package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

/** Observations of real prefix rows, actual HTTP calls and actual phase arguments; never values accepted as authority by production. */
internal class CatalogSignerRotationDeliveryAssertions(private val f: CatalogSignerRotationDeliveryFixture) {
    private val originalControl = f.invariantControl()
    private val originalGenesis = f.core.genesisJson()
    private val originalCounters = f.freeze.counters()
    private val originalFrozen = frozenTuple()
    private val rotation = f.freeze.row()
    private val genesis = f.freeze.d7.genesisRow()

    fun preserved() {
        assertEquals(
            originalControl,
            f.invariantControl(),
            "D, identities, writers, trust, gates and all non-head controls must remain unchanged.",
        )
        assertEquals(originalGenesis, f.core.genesisJson(), "G1, including both copy evidence records and lifecycle times, is immutable.")
        assertEquals(originalCounters, f.freeze.counters(), "Delivery cannot charge capacity a second time.")
        assertEquals(originalFrozen, frozenTuple())
        assertEquals(2L, f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java))
        assertEquals(
            0L,
            f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations WHERE successor_generation >= 3", Long::class.java),
        )
        f.assertNoFurtherSign()
    }

    fun preparedHead1() {
        f.core.assertSignedSql(f.prefix.producedSignatures)
        val head = f.head()
        assertEquals(1L, head["accepted_catalog_generation"])
        assertArrayEquals(hash(f.freeze.d7.envelope), head["accepted_catalog_hash"] as ByteArray)
        assertNull(head["pending_projection_token"])
        assertEquals(true, head["maintenance_closed"])
        assertEquals(true, head["creation_closed"])
        preserved()
    }

    fun pendingHead2(): Timestamp {
        val row = f.freeze.row()
        val head = f.head()
        assertEquals(2L, head["accepted_catalog_generation"])
        assertArrayEquals(hash(f.envelope), head["accepted_catalog_hash"] as ByteArray)
        assertEquals(f.freeze.token, head["pending_projection_token"])
        assertEquals("COMPLETED", row["state"])
        assertNull(row["projected_at"])
        assertEquals(CatalogGenesisPublishHttpFixture.OVERLAP2_VERSION, row["object_version"])
        assertEquals(Instant.ofEpochSecond(f.http.retainUntil), (row["retain_until"] as Timestamp).toInstant())
        assertCopies(row)
        preserved()
        return row["completed_at"] as Timestamp
    }

    fun projectedHead2(completedAt: Timestamp) {
        val row = f.freeze.row()
        val head = f.head()
        assertEquals(2L, head["accepted_catalog_generation"])
        assertArrayEquals(hash(f.envelope), head["accepted_catalog_hash"] as ByteArray)
        assertNull(head["pending_projection_token"])
        assertEquals("COMPLETED", row["state"])
        assertEquals(completedAt, row["completed_at"])
        assertFalse((row["projected_at"] as Timestamp).before(completedAt))
        assertCopies(row)
        preserved()
    }

    fun singlePut() {
        val request = f.http.put.requests.single()
        val credentials = CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS
        assertEquals(1, f.http.put.createdClients)
        assertEquals(1, f.http.put.closedClients)
        assertEquals(1, f.http.put.replies.single().calls)
        assertEquals(1, f.http.put.replies.single().aborts)
        assertEquals(SdkHttpMethod.PUT, request.method())
        assertEquals("https", request.protocol())
        assertEquals("s3.${S3CatalogReadbackFixture.primary.region}.amazonaws.com", request.host())
        assertEquals("/${S3CatalogReadbackFixture.primary.bucket}/${CatalogReadbackProtocol.key(2)}", request.encodedPath())
        assertEquals("*", request.header("If-None-Match"))
        assertEquals(S3CatalogReadbackFixture.primary.accountId, request.header("x-amz-expected-bucket-owner"))
        assertEquals(credentials.sessionToken(), request.header("x-amz-security-token"))
        assertTrue(request.header("Authorization").contains("Credential=${credentials.accessKeyId()}/"))
        assertEquals(f.envelope.size.toString(), request.header("Content-Length"))
        assertEquals(Sha256.hex(f.envelope), request.header("x-amz-content-sha256"))
        assertEquals(CatalogGenesisPublishHttpFixture.checksum(f.envelope), request.header("x-amz-checksum-sha256"))
        assertEquals("SHA256", request.header("x-amz-sdk-checksum-algorithm"))
        assertEquals("COMPLIANCE", request.header("x-amz-object-lock-mode"))
        assertEquals(Instant.ofEpochSecond(f.http.retainUntil).toString(), request.header("x-amz-object-lock-retain-until-date"))
        assertEquals("1", request.header("amz-sdk-request").substringAfter("attempt=").substringBefore(';'))
        assertArrayEquals(f.envelope, f.http.bodies.single())
        assertArrayEquals(f.envelope, f.http.primaryBytes)
        assertTrue(f.http.read.requests.all { it.method() == SdkHttpMethod.GET }, "No replica PUT route exists.")
    }

    fun acquiredLease(root: CatalogSignerRotationDeliveryRoot, before: Map<String, Any?>, lower: Instant, upper: Instant) {
        val actual = f.leaseRow()
        val acquiredAt = (actual["lease_expires_at"] as Timestamp).toInstant().minusSeconds(30)
        assertNotEquals(before["lease_owner"], actual["lease_owner"])
        assertEquals((before["lease_token"] as Long) + 1, actual["lease_token"])
        assertFalse(acquiredAt.isBefore(lower) || acquiredAt.isAfter(upper))
        val acquire = root.jdbc.calls.single { it.step == "lease-acquire" }
        f.core.assertArguments(
            listOf(actual["lease_owner"]) + f.historicalBindingArguments +
                listOf(before["lease_owner"], before["lease_token"], before["lease_expires_at"], before["updated_at"]),
            acquire.arguments,
        )
        if (f.head()["accepted_catalog_generation"] == 1L) {
            assertEquals(
                Duration.ofSeconds(30),
                Duration.between((actual["updated_at"] as Timestamp).toInstant(), (actual["lease_expires_at"] as Timestamp).toInstant()),
            )
        }
        bindings(root)
    }

    fun completedPhases(root: CatalogSignerRotationDeliveryRoot, original: CatalogSignerRotationDeliveryV1) {
        assertSame(root.clock, poolTestField<Any>(original.budget, "clock"))
        assertEquals(30_000_000_000L, poolTestField<Long>(original.budget, "allowanceNanos"))
        root.phases.forEach { phase ->
            val calls = root.jdbc.calls.filter { it.phase === phase }
            assertEquals("delivery-authenticate", calls.first().step)
            f.core.assertArguments(
                listOf(PgLifecycleDatabaseSettings.CANDIDATE, PgLifecycleDatabaseSettings.CANDIDATE, PgLifecycleDatabaseSettings.DATABASE),
                calls.first().arguments,
            )
            assertSame(original, ownedCutField(phase, "signerRotationDelivery"))
            assertTrue(phase.signerRotationDeliveryCleanupProven(original))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(root.jdbc.observations.getValue(phase).lease.completion.quiescent())
            val work = poolTestField<PersistenceTimeBudget>(phase, "signerRotationDeliveryWork")
            assertSame(original.budget, poolTestField<PersistenceTimeBudget>(work, "parent"))
        }
        val lease = root.jdbc.calls.filter { it.path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE }
        assertEquals(
            listOf("delivery-authenticate", "lease-lock", "delivery-gates", "lease-acquire", "lease-read", "delivery-gates"),
            lease.map { it.step },
        )
        assertTrue(
            root.jdbc.steps.none { it == "prepare" || it == "signature" || it.startsWith("charge:") || it == "lease-relinquish" },
        )
        val assembly = poolTestField<Any>(original, "assembly")
        val readbacks = poolTestField<List<*>>(assembly, "readbacks")
        assertTrue(readbacks.size in 1..3)
        val rounds = readbacks + listOfNotNull(ownedCutField(assembly, "putRound"))
        rounds.forEach { round ->
            val cap = poolTestField<PersistenceTimeBudget>(checkNotNull(round), "budget")
            assertSame(original.budget, poolTestField<PersistenceTimeBudget>(cap, "parent"))
            assertTrue(poolTestField<Long>(cap, "allowanceNanos") in 1..10_000_000_000L)
        }
        root.released()
    }

    fun rawReadback(original: CatalogSignerRotationDeliveryV1, expected: CatalogDualLocationVerifier.Overlap2Readback.State) {
        val proof = poolTestField<CatalogDualLocationVerifier.Overlap2Readback>(original, "readback")
        assertEquals(expected, proof.state)
        assertEquals(1L, proof.snapshotHead.generation)
        assertEquals(Sha256.hex(f.freeze.d7.envelope), proof.snapshotHead.envelopeSha256)
        assertEquals(f.freeze.manifest.operationToken, proof.operationToken)
        assertArrayEquals(f.envelope, proof.frozenEnvelopeBytes())
        val unpublished = expected === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_UNPUBLISHED
        val actual = if (unpublished) f.freeze.d7.envelope else f.envelope
        assertEquals(if (unpublished) 1L else 2L, proof.observedTail.generation)
        assertEquals(Sha256.hex(actual), proof.observedTail.envelopeSha256)
        assertArrayEquals(actual, proof.observedEnvelopeBytes())
        if (expected === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_DUAL_COPY) {
            assertEquals(2L, checkNotNull(proof.commonHeadEvidence()).chain.tail.generation)
            assertArrayEquals(f.freeze.read(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY), proof.primaryEvidenceBytes())
            assertArrayEquals(f.freeze.read(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY), proof.replicaEvidenceBytes())
        } else {
            assertNull(proof.primaryEvidenceBytes())
            assertNull(proof.replicaEvidenceBytes())
            if (!unpublished) assertNull(proof.commonHeadEvidence())
        }
    }

    fun pendingOwner(original: CatalogSignerRotationDeliveryV1) {
        val pending = poolTestField<Any>(original, "pending")
        assertSame(original.budget, poolTestField<PersistenceTimeBudget>(pending, "allowance"))
        assertEquals(poolTestField<Long>(original, "leaseStartedAtNanos"), poolTestField<Long>(pending, "startedAtNanos"))
        assertSame(ownedCutField(original, "completeOperation"), ownedCutField(pending, "operation"))
        assertSame(ownedCutField(original, "completedHistory"), ownedCutField(pending, "observation"))
        assertFalse(poolTestField<Boolean>(pending, "spent"))
        val campaign = poolTestField<CatalogCoordinatorLeaseCampaignV1>(original, "campaign")
        assertNull(poolTestField<AtomicReference<Any?>>(campaign, "window").get())
        assertNull(poolTestField<AtomicReference<Any?>>(campaign.custody, "active").get())
        assertSame(campaign.binding, ownedCutField(original, "binding"))
    }

    /** Independently decode write-once records against the actual DB acquisition, not owner-supplied record helpers. */
    fun acquisitionRecord(
        leaf: CatalogSignerRotationReleaseLeafV1,
        kind: String,
        head2: Boolean = false,
        times: List<Timestamp> = emptyList(),
    ) {
        assertTrue(f.freeze.complete(leaf), leaf.name)
        val allocation = f.frozenPrefix.entries.single { it.key.fileName.toString() == "allocation" }.value.second
        val values = Json.parseToJsonElement(f.freeze.read(leaf).decodeToString()).jsonArray.map { it.jsonPrimitive.content }
        val current = f.leaseRow()
        val binding = f.historicalBindingArguments.map {
            if (it is ByteArray) HexFormat.of().formatHex(it) else it.toString()
        }.toMutableList()
        if (head2) {
            binding[5] = "2"
            binding[6] = Sha256.hex(f.envelope)
        }
        val expected = listOf("catalog-signer-rotation-freeze-v1", kind, Sha256.hex(allocation), Sha256.hex(f.envelope)) +
            times.map { it.toInstant().toString() } + binding +
            listOf(
                current["lease_owner"].toString(),
                current["lease_token"].toString(),
                (current["lease_expires_at"] as Timestamp).toInstant().toString(),
            )
        assertEquals(expected, values, leaf.name)
    }

    /** Accepted B1 remains separate from a raw G2 tail; only the private pending continuation binds B2. */
    fun bindings(root: CatalogSignerRotationDeliveryRoot) {
        val lease = f.leaseRow()
        val b1 = f.historicalBindingArguments + listOf(lease["lease_owner"], lease["lease_token"], lease["lease_expires_at"])
        val b2 = b1.toMutableList().apply {
            this[5] = 2L
            this[6] = hash(f.envelope)
        }
        val row = f.freeze.row()
        val r17 = ROTATION_COLUMNS.map(rotation::get)
        val g21 = GENESIS_COLUMNS.map(genesis::get)
        val c6 = COPY_COLUMNS.map(row::get)
        root.jdbc.calls.forEach { call ->
            val expected = when (call.step) {
                "final-prepared-control", "final-prepared-control-read", "final-prepared-lease" -> b1
                "final-pending-control", "final-pending-control-read", "final-pending-lease" -> b2 + f.freeze.token
                "final-projected-control-read", "final-projected-lease" -> b2
                "final-initial-history-lock", "final-initial-history-read" -> r17
                "final-prepared-history-lock", "final-prepared-history-read" -> r17 + g21
                "final-pending-history-lock", "final-pending-history-read", "final-projected-history-read" -> r17 + g21 + c6
                "final-complete" -> r17 + c6
                "final-head" -> b1 + listOf(f.freeze.token, hash(f.envelope))
                "final-project" -> r17 + c6 + row["completed_at"]
                "final-clear-pending" -> b2 + f.freeze.token
                else -> null
            }
            if (expected != null) f.core.assertArguments(expected, call.arguments)
        }
        root.phases.filter {
            poolTestField<PersistencePhasePath>(it, "path") in setOf(
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
            )
        }.forEach { phase ->
            val steps = root.jdbc.calls.filter { it.phase === phase }.map { it.step }
            val project = poolTestField<PersistencePhasePath>(phase, "path") ===
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT
            val before = if (project) "pending" else "prepared"
            assertEquals(listOf("delivery-authenticate", "final-$before-control", "final-$before-lease", "catalog"), steps.take(4))
            assertTrue(steps[4].endsWith("history-lock"))
            assertEquals("counters", steps[5])
            for (effect in listOf("final-complete", "final-project")) {
                if (effect in steps) assertEquals("final-$before-lease", steps[steps.indexOf(effect) - 1])
            }
            assertTrue(steps.last().endsWith("-lease"))
        }
    }

    private fun assertCopies(row: Map<String, Any?>) {
        val copies = listOf(
            CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY to "primary",
            CatalogSignerRotationReleaseLeafV1.REPLICA_COPY to "replica",
        )
        for ((leaf, prefix) in copies) {
            assertTrue(f.freeze.complete(leaf))
            val bytes = f.freeze.read(leaf)
            assertArrayEquals(bytes, row["${prefix}_evidence_bytes"] as ByteArray)
            assertArrayEquals(hash(bytes), row["${prefix}_evidence_hash"] as ByteArray)
            val evidence = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val location = if (prefix == "primary") S3CatalogReadbackFixture.primary else S3CatalogReadbackFixture.replica
            assertEquals(CatalogReadbackProtocol.key(2), evidence.getValue("objectKey").jsonPrimitive.content)
            assertEquals(CatalogGenesisPublishHttpFixture.OVERLAP2_VERSION, evidence.getValue("objectVersion").jsonPrimitive.content)
            assertEquals(Sha256.hex(f.envelope), evidence.getValue("envelopeSha256").jsonPrimitive.content)
            assertEquals(f.envelope.size.toLong(), evidence.getValue("contentLength").jsonPrimitive.long)
            assertEquals(f.http.retainUntil, evidence.getValue("retainUntilEpochSecond").jsonPrimitive.long)
            assertEquals("COMPLIANCE", evidence.getValue("objectLockMode").jsonPrimitive.content)
            assertEquals(if (prefix == "primary") "COMPLETED" else "REPLICA", evidence.getValue("replicationStatus").jsonPrimitive.content)
            val where = evidence.getValue("location").jsonObject
            assertEquals(location.role, where.getValue("role").jsonPrimitive.content)
            assertEquals(location.accountId, where.getValue("accountId").jsonPrimitive.content)
            assertEquals(location.region, where.getValue("region").jsonPrimitive.content)
            assertEquals(location.bucket, where.getValue("bucket").jsonPrimitive.content)
        }
    }

    private fun frozenTuple(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(m) - ARRAY['state','completed_at','projected_at','object_version','retain_until','primary_evidence_bytes'," +
                "'primary_evidence_hash','replica_evidence_bytes','replica_evidence_hash'])::text " +
                "FROM complaint_catalog_mutations m WHERE operation_token = ?",
            String::class.java,
            f.freeze.token,
        ),
    )

    private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
    private fun SdkHttpRequest.header(name: String): String = firstMatchingHeader(name).orElseThrow()

    companion object {
        private val ROTATION_COLUMNS = listOf(
            "operation_token", "catalog_writer_generation", "approval_bytes", "approval_hash", "unsigned_bytes", "unsigned_hash",
            "signer_one_id", "signer_one_algorithm", "signer_two_id", "signer_two_algorithm", "object_key", "created_at", "predecessor_hash",
            "signer_one_signature", "signer_two_signature", "envelope_bytes", "envelope_hash",
        )
        private val GENESIS_COLUMNS = listOf(
            "operation_token", "catalog_writer_generation", "approval_bytes", "approval_hash", "unsigned_bytes", "unsigned_hash",
            "signer_one_id", "signer_one_algorithm", "signer_one_signature", "envelope_bytes", "envelope_hash", "object_key", "created_at",
            "object_version", "retain_until", "primary_evidence_bytes", "primary_evidence_hash", "replica_evidence_bytes", "replica_evidence_hash",
            "completed_at", "projected_at",
        )
        private val COPY_COLUMNS = listOf(
            "object_version", "retain_until", "primary_evidence_bytes", "primary_evidence_hash", "replica_evidence_bytes", "replica_evidence_hash",
        )
    }
}
