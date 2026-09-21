package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejected
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightRejection
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.ownerDeleteTestIngress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID

/** Negative drift never creates authority. All successful origins/grants/primaries come from the genuine helper. */
internal object TestRegisteredInitialCheckpointDeletionFailureCasesV1 {
    fun exactResourcesAndHandoff(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val rows = f.image(); val counters = f.counters(); val calls = f.deletion.calls.size
        val g = f.graph
        assertThrows<Exception> { TestOwnerDeleteLocalGraphV1(f.exchange.jdbc, f.deletion, f.ingress, g.routing, g.policy, g.lanes) }
        assertThrows<Exception> { TestOwnerDeleteLocalGraphV1(f.exchange.jdbc, f.deletion, f.ingress, g.routing, g.policy, g.lanes,
            f.process.desiredGeneration, initialDeletion = f.binding) }
        assertThrows<Exception> { TestOwnerDeleteProcessBindingV1.fromRegistered(f.registration, f.assembly,
            f.exchange.ordinary.ownership, JdbcTemplate(f.runtime.pools.ordinary), f.deletionOwner, f.deletion) }
        assertThrows<Exception> { TestOwnerDeleteProcessBindingV1.fromRegistered(f.registration, f.assembly,
            f.exchange.ordinary.ownership, f.exchange.jdbc, f.deletionOwner, JdbcTemplate(f.runtime.pools.deletion)) }
        // Distinct VALID managers, not rebinding a manager already owned by the fixture.
        val otherOrdinary = PersistencePhaseOwnership(f.exchange.ordinary.admission,
            GuardedJpaTransactionManager(f.exchange.ordinary.entityManagerFactory, f.exchange.ordinary.pool))
        val otherDeletion = PersistencePhaseOwnership.deletion(f.admission, GuardedJdbcTransactionManager(f.runtime.pools.deletion))
        assertThrows<Exception> { TestOwnerDeleteProcessBindingV1.fromRegistered(f.registration, f.assembly,
            otherOrdinary, f.exchange.jdbc, f.deletionOwner, f.deletion) }
        assertThrows<Exception> { TestOwnerDeleteProcessBindingV1.fromRegistered(f.registration, f.assembly,
            f.exchange.ordinary.ownership, f.exchange.jdbc, otherDeletion, f.deletion) }
        // Marking a lower graph as recovery cannot promote even this genuine registration to new AUTH.
        val recovery = TestOwnerDeleteLocalGraphV1(f.exchange.jdbc, f.deletion, f.ingress, g.routing, g.policy, g.lanes,
            f.process.desiredGeneration, recoveryRegistration = f.registration)
        val recoveryStore = JdbcComplaintOwnerDeleteStore(f.deletion, f.capacity, f.audit, recovery, f.codec)
        assertEquals(calls, f.deletion.calls.size)

        val identity = f.creators.first().identity()
        val preflight = f.ingress.withIngress(f.request()) { context ->
            f.ingress.startOwnerDelete(context); f.ownerReads.preflight(identity, f.ownerCandidate.tuple)
        }
        assertNull(preflight.failure)
        assertThrows<Exception> { recoveryStore.authorize(identity, f.ownerCandidate, checkNotNull(preflight.platform)) }
        val foreign = ownerDeleteTestIngress(g.policy)
        foreign.withIngress(f.request()) { context ->
            foreign.startOwnerDelete(context)
            val handoff = foreign.admitOwnerDelete(context, f.ownerCandidate.tuple)
            f.ownerPublisher.reserve().use { lane ->
                assertThrows<Exception> { f.ownerPhases.authorize(identity, f.ownerCandidate, preflight, handoff, lane) }
            }
        }
        assertThrows<Exception> { f.ownerReads.preflight(identity, f.ownerCandidate.tuple) }
        f.ingress.withIngress(f.request()) { context ->
            f.ingress.startOwnerDelete(context)
            val current = f.ownerReads.preflight(identity, f.ownerCandidate.tuple)
            val handoff = f.ingress.admitOwnerDelete(context, f.ownerCandidate.tuple)
            assertThrows<Exception> { f.ownerPhases.authorize(identity, f.ownerCandidate, current, handoff) }
            f.ownerPublisher.reserve().use { lane ->
                OwnedCallerTestScope().use { callers ->
                    val wrongThread = callers.launch { f.ownerPhases.authorize(identity, f.ownerCandidate, current, handoff, lane) }
                    assertInstanceOf(Exception::class.java, wrongThread.problem())
                }
            }
        }
        assertEquals(calls, f.deletion.calls.size, "Resource/ingress/thread/lane substitution is refused before deletion SQL.")
        assertEquals(rows, f.image()); assertEquals(counters, f.counters()); f.assertReleased()

        f.ingress.withIngress(f.request()) { context ->
            f.ingress.startOwnerDelete(context)
            val currentIdentity = f.creators.first().identity()
            val current = f.ownerReads.preflight(currentIdentity, f.ownerCandidate.tuple)
            val handoff = f.ingress.admitOwnerDelete(context, f.ownerCandidate.tuple)
            f.ownerPublisher.reserve().use { lane ->
                assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET scan_requested = true WHERE data_scope_id = ?", f.scope))
                try {
                    phaseRefused { f.ownerPhases.authorize(currentIdentity, f.ownerCandidate, current, handoff, lane) }
                    val refusedCalls = f.deletion.calls.size
                    assertThrows<Exception> { f.ownerPhases.authorize(currentIdentity, f.ownerCandidate, current, handoff, lane) }
                    assertEquals(refusedCalls, f.deletion.calls.size, "A spent real handoff cannot begin another original.")
                } finally { f.foreignUpdate("UPDATE complaint_journal_control SET scan_requested = false WHERE data_scope_id = ?", f.scope) }
            }
        }
        assertEquals(rows, f.image()); assertEquals(counters, f.counters()); f.assertReleased()

        // Retire factories BEFORE closing their authority; cleanup is not a covert recovery path.
        f.ownerPublisher.close(); f.registration.close()
        assertThrows<Exception> { TestOwnerDeleteProcessBindingV1.fromRegistered(f.registration, f.assembly,
            f.exchange.ordinary.ownership, f.exchange.jdbc, f.deletionOwner, f.deletion) }
        assertThrows<Exception> { ownerAttempt(f) }
        assertEquals(rows, f.image()); assertEquals(counters, f.counters())
    }

    fun privacyGatesAndCurrentIdentity(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        for (scope in listOf(UUID(0, 0), f.scope)) {
            val original = f.observer.queryForMap("SELECT creation_closed, maintenance_closed, scan_requested, desired_configuration_hash, desired_generation " +
                "FROM complaint_journal_control WHERE data_scope_id = ?", scope)
            try {
                assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = true, maintenance_closed = true WHERE data_scope_id = ?", scope))
                unchangedRefusal(f)
            } finally {
                f.foreignUpdate("UPDATE complaint_journal_control SET creation_closed = ?, maintenance_closed = ? WHERE data_scope_id = ?",
                    original["creation_closed"], original["maintenance_closed"], scope)
            }
            for ((column, bad) in listOf("scan_requested" to true, "desired_configuration_hash" to ByteArray(32) { 0x68 },
                "desired_generation" to (original.getValue("desired_generation") as Number).toLong() + 1)) {
                try {
                    assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", bad, scope))
                    unchangedRefusal(f)
                } finally { f.foreignUpdate("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], scope) }
            }
        }
        // No fabricated pending token: that requires a real catalog PREPARED successor not supplied here.
        TestRegisteredInitialCheckpointDeletionCasesV1.genuine(f, creationClosed = true)
    }

    fun checkpointColumnsAndNativeSeal(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val original = f.checkpoint.control()
        val hash = ByteArray(32) { 0x79 }
        val changes = linkedMapOf<String, Any>(
            "checkpoint_generation" to (original.getValue("checkpoint_generation") as Number).toLong() + 1,
            "checkpoint_fencing_token" to (original.getValue("checkpoint_fencing_token") as Number).toLong() + 1,
            "checkpoint_catalog_generation" to (original.getValue("checkpoint_catalog_generation") as Number).toLong() + 1,
            "checkpoint_catalog_hash" to hash, "checkpoint_writer_generation" to UUID.randomUUID(), "checkpoint_cutoff_epoch" to 2L,
            "checkpoint_configuration_hash" to hash, "checkpoint_database_identity" to UUID.randomUUID(), "checkpoint_restore_identity" to UUID.randomUUID(),
            "checkpoint_started_at" to Timestamp.from((original.getValue("checkpoint_started_at") as Timestamp).toInstant().minusNanos(1000)),
            "checkpoint_completed_at" to Timestamp.from((original.getValue("checkpoint_completed_at") as Timestamp).toInstant().plusNanos(1000)),
            "checkpoint_object_count" to 1L, "checkpoint_byte_count" to 1L,
        )
        for ((column, bad) in changes) {
            try {
                assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", bad, f.scope))
                unchangedRefusal(f)
            } finally { f.foreignUpdate("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", original[column], f.scope) }
        }
        // Four narrower column invariants belong to the real schema; never turn off constraints.
        for ((column, bad) in listOf("checkpoint_schema" to 2, "checkpoint_result" to "FAILED",
            "checkpoint_bytes" to "{}".toByteArray(), "checkpoint_hash" to hash)) {
            val before = f.first.controlImage()
            assertThrows<SQLException> { f.foreignUpdate("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", bad, f.scope) }
            assertEquals(before, f.first.controlImage())
        }
        val checkpoint = original.getValue("checkpoint_bytes") as ByteArray
        val fields = Json.parseToJsonElement(checkpoint.decodeToString()).jsonObject
        for ((name, bad) in listOf("journalConfigurationSha256" to JsonPrimitive("8".repeat(64)), "profile" to JsonPrimitive("HEALTHY"))) {
            val changed = CanonicalJson.canonicalize(JsonObject(fields + (name to bad))).toByteArray()
            try {
                assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET checkpoint_bytes = ?, checkpoint_hash = ? WHERE data_scope_id = ?",
                    changed, HexFormat.of().parseHex(Sha256.hex(changed)), f.scope))
                unchangedRefusal(f)
            } finally { f.foreignUpdate("UPDATE complaint_journal_control SET checkpoint_bytes = ?, checkpoint_hash = ? WHERE data_scope_id = ?",
                checkpoint, original["checkpoint_hash"], f.scope) }
        }
        val proof = original.getValue("seal_verification_bytes") as ByteArray
        val fieldsOfProof = Json.parseToJsonElement(proof.decodeToString()).jsonObject
        val changedProof = CanonicalJson.canonicalize(JsonObject(fieldsOfProof + ("objectVersion" to JsonPrimitive("unrelated-version")))).toByteArray()
        try {
            assertEquals(1, f.foreignUpdate("UPDATE complaint_journal_control SET seal_verification_bytes = ?, seal_verification_hash = ? WHERE data_scope_id = ?",
                changedProof, HexFormat.of().parseHex(Sha256.hex(changedProof)), f.scope))
            unchangedRefusal(f)
        } finally { f.foreignUpdate("UPDATE complaint_journal_control SET seal_verification_bytes = ?, seal_verification_hash = ? WHERE data_scope_id = ?",
            proof, original["seal_verification_hash"], f.scope) }
        f.authorize(); TestRegisteredInitialCheckpointDeletionCasesV1.assertPrimary(f, "PREPARED")
    }

    fun laneExhaustionAndUnsupportedSecondPrimary(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val rows = f.image(); val before = f.counters(); val calls = f.deletion.calls.size
        val lanes = mutableListOf<JournalPublicationLanesV1.TestOwnerDeleteReservation>()
        try {
            repeat(f.process.consumers.journalConfiguration.declaration().limits.capacity.maximumPublicationLanes) { lanes.add(f.ownerPublisher.reserve()) }
            assertNull(f.ownerPublisher.tryReserve())
            assertThrows<JournalPublicationExceptionV1> { f.authorize() }
            assertEquals(calls, f.deletion.calls.size); assertEquals(rows, f.image()); assertEquals(before, f.counters())
        } finally { lanes.asReversed().forEach { it.close() } }
        f.authorize(); f.assertCharge(before)
        val primary = f.image(); val counters = f.counters(); val audits = f.audits()
        phaseRefused { ownerAttempt(f, f.ownerCandidate(operationKey = UUID.randomUUID())) }
        f.ingress.withIngress(f.request()) { context ->
            f.ingress.startOwnerDeleteAll(context)
            val preflight = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, f.allPreflights.preflight(f.allCandidate))
            val handoff = f.ingress.admitOwnerDeleteAll(context, preflight)
            f.allPublisher.reserve().use { lane -> phaseRefused { f.allPhases.authorize(f.allCandidate, preflight, handoff, lane) } }
        }
        assertEquals(primary, f.image()); assertEquals(counters, f.counters()); assertEquals(audits, f.audits())
        f.assertReload(verified = false); assertEquals(listOf(0, 0, 0, 0), f.native.counts())
    }

    fun wrongOwnerSecret(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val rows = f.image(); val counters = f.counters()
        val bad = InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(f.actor, ByteArray(32) { 0x7f }),
            f.creators.first().identity().credentialVersion, f.key)
        f.ingress.withIngress(f.request()) { context ->
            f.ingress.startOwnerDeleteAll(context)
            val rejected = assertInstanceOf(InstallationDeletionPreflightResult.Rejected::class.java, f.allPreflights.preflight(bad))
            assertEquals(InstallationDeletionPreflightRejection.INSTALLATION_CREDENTIAL_REJECTED, rejected.reason)
        }
        assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertTrue(f.deletion.calls.isEmpty())
        assertEquals(listOf(0, 0, 0, 0), f.native.counts()); f.assertReleased()
    }

    fun genuineGrantAndCurrentAdmin(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val proof = checkNotNull(f.proof)
        // A real SOURCE proof is not a complaint grant. Do not insert an alleged grant directly.
        val source = f.stepUp.issue(ScopedAdminStepUpScope.SOURCE)
        val rows = f.image(); val counters = f.counters(); val audits = f.audits()
        for (token in listOf<String?>(null, source.token)) {
            assertEquals(ComplaintAdminDeleteFailure.STEP_UP_REQUIRED, assertThrows<ComplaintAdminDeleteRejected> { adminAttempt(f, token) }.failure)
        }
        val stored = f.observer.queryForMap("SELECT created_at, expires_at, used_at FROM admin_step_up_grants WHERE id = ?", proof.grantId)
        // Negative revocation/expiry of the actually issued proof, never a successful consumption fixture.
        for ((column, bad) in listOf("expires_at" to Timestamp.from((stored.getValue("created_at") as Timestamp).toInstant().plusNanos(1000)),
            "used_at" to Timestamp.from((stored.getValue("created_at") as Timestamp).toInstant()))) {
            try {
                assertEquals(1, f.foreignUpdate("UPDATE admin_step_up_grants SET $column = ? WHERE id = ?", bad, proof.grantId))
                assertEquals(ComplaintAdminDeleteFailure.STEP_UP_REQUIRED, assertThrows<ComplaintAdminDeleteRejected> { adminAttempt(f, proof.token) }.failure)
            } finally { f.foreignUpdate("UPDATE admin_step_up_grants SET $column = ? WHERE id = ?", stored[column], proof.grantId) }
        }
        val user = f.adminUser()
        for ((column, bad, expected) in listOf(Triple("role", "USER", ComplaintAdminDeleteFailure.FORBIDDEN),
            Triple("credential_version", user.credentialVersion + 1, ComplaintAdminDeleteFailure.UNAUTHORIZED))) {
            try {
                f.ingress.withIngress(f.request()) { context ->
                    f.ingress.startAdminDelete(context)
                    val identity = f.adminDecoder.decode(f.adminToken)
                    val preflight = f.adminReads.preflight(identity, f.adminCandidate.tuple)
                    assertNull(preflight.failure); assertFalse(preflight.authorized)
                    assertEquals(1, f.foreignUpdate("UPDATE users SET $column = ? WHERE id = ?", bad, user.id))
                    val handoff = f.ingress.admitAdminDelete(context, f.adminCandidate.tuple)
                    f.adminPublisher.reserve().use { lane ->
                        assertEquals(expected, assertThrows<ComplaintAdminDeleteRejected> {
                            f.adminPhases.authorize(identity, f.adminCandidate, preflight, proof.token, handoff, lane)
                        }.failure)
                    }
                }
            } finally { f.foreignUpdate("UPDATE users SET $column = ? WHERE id = ?", if (column == "role") user.role.name else user.credentialVersion, user.id) }
        }
        assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertEquals(audits, f.audits())
        assertEquals(false, f.observer.queryForObject("SELECT used_at IS NOT NULL FROM admin_step_up_grants WHERE id = ?", Boolean::class.java, proof.grantId))
        f.assertReleased()
        TestRegisteredInitialCheckpointDeletionCasesV1.genuine(f)
    }

    internal fun ownerAttempt(f: TestRegisteredInitialCheckpointDeletionFixtureV1,
        candidate: ComplaintOwnerDeleteCandidate = f.ownerCandidate): TestOwnerDeleteAuthorizationV1 = f.ingress.withIngress(f.request()) { context ->
        f.ingress.startOwnerDelete(context)
        val identity = f.creators.first().identity()
        val preflight = f.ownerReads.preflight(identity, candidate.tuple)
        assertNull(preflight.failure); assertNull(preflight.receipt); assertFalse(preflight.authorized)
        val handoff = f.ingress.admitOwnerDelete(context, candidate.tuple)
        f.ownerPublisher.reserve().use { lane -> f.ownerPhases.authorize(identity, candidate, preflight, handoff, lane) }
    }

    internal fun adminAttempt(f: TestRegisteredInitialCheckpointDeletionFixtureV1, proof: String?): TestAdminDeleteAuthorizationV1 =
        f.ingress.withIngress(f.request()) { context ->
            f.ingress.startAdminDelete(context)
            val identity = f.adminDecoder.decode(f.adminToken)
            val preflight = f.adminReads.preflight(identity, f.adminCandidate.tuple)
            assertNull(preflight.failure); assertNull(preflight.receipt); assertFalse(preflight.authorized)
            val handoff = f.ingress.admitAdminDelete(context, f.adminCandidate.tuple)
            f.adminPublisher.reserve().use { lane -> f.adminPhases.authorize(identity, f.adminCandidate, preflight, proof, handoff, lane) }
        }

    internal fun phaseRefused(action: () -> Any?): PersistencePhaseException = assertThrows<PersistencePhaseException> { action() }.also {
        assertTrue(it.cleanupProven); assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, it.databaseOutcome)
    }

    private fun unchangedRefusal(f: TestRegisteredInitialCheckpointDeletionFixtureV1) {
        val rows = f.image(); val counters = f.counters(); val audits = f.audits(); val providers = f.native.counts()
        phaseRefused { ownerAttempt(f) }
        assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertEquals(audits, f.audits()); assertEquals(providers, f.native.counts())
        f.assertReleased()
    }
}
