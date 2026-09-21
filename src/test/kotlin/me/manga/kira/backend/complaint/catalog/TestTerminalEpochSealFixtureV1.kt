package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintInstallationEnrollmentStore
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationEnrollmentPhaseExecutor
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * No fabricated successful predecessor: real PROJECT/registration, empty ordinary scan/seal,
 * manifest publication and purge. The enrolled case first uses protected initial identity
 * release and the actual registered exchange. The opt-in additional500 use the existing
 * registered lower-core enrollment producer, not501 HTTP admissions or a quota exemption.
 * Only the later conservative gate-CLOSE is raw fixture setup (not a close producer/denial
 * claim); no open flag, identity, payment, historical
 * checkpoint or successful object is seeded. External IAM/denial/restore and setup lease
 * expiries keep the existing explicit synthetic provenance. NOT_RUN.
 */
internal fun withTerminalEpochSealRun(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean = false,
    shortHorizon: Boolean = false, checkUnstartedPurge: Boolean = false,
    terminalQuiescence: TestTerminalQuiescenceFixtureInputsV1? = null,
    additionalRawEnrolled: Int = 0,
    action: (TestRunPurgeFixtureV1, TestRunPurgePublicationV1, TestTerminalEpochSealSqlProbeV1) -> Unit) {
    require(additionalRawEnrolled == 0 || enrolled && additionalRawEnrolled == 500)
    val enrollmentCount = if (enrolled) 1 + additionalRawEnrolled else 0
    val chunkSizes = if (additionalRawEnrolled == 500) listOf(500, 1) else if (enrolled) listOf(1) else emptyList()
    val inputs = TestOrdinaryDrainFixtureInputsV1(scanMillis = if (shortHorizon) 30_000 else null, terminalQuiescence = terminalQuiescence)
    val horizon = if (shortHorizon) Instant.now().plusSeconds(86_400).truncatedTo(ChronoUnit.SECONDS) else Instant.parse("2038-01-01T00:00:00Z")
    TestOrdinarySealHttpFixtureV1(horizon = horizon, protectedIntake = enrolled || terminalQuiescence != null, manifestPublication = true,
        purgePublication = true, terminalEpochSeal = true, terminalInventory = terminalQuiescence != null).use { http ->
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ordinarySealHttp = http, ordinaryDrain = inputs,
            expireClosedSetupPredecessors = true) { p, runtime, registration, _ ->
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
            ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                val actors = arrayListOf(UUID.randomUUID())
                try {
                    TestRunPurgeFixtureV1(p, runtime, registration, audit, http, inputs).use { f ->
                        f.assertUnused()
                        if (enrolled) {
                            val assembly = checkNotNull(p.f.rows.evidence.intakeAssembly)
                            val initial = ComplaintTestInitialAdmissionV1.withHttpFixture(registration, assembly, p.f.http::readClient,
                                SignedActivationObservation.WALL_CLOCK)
                            initial.release(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
                            initial.requireActualCleanup(); registration.requireReleasedIdentityAdmission(); requireConnectionFree()
                            val adapter = ComplaintInstallationExchangeAdapter(registration, ordinary.ownership, ordinary.jdbc,
                                ComplaintInstallationEnrollmentAudit { scope, paid, at -> audit.recordInstallationEnrollment(scope, paid, at) })
                            val candidate = InstallationEnrollmentCredentials.prepare(ScopedInstallationId(actors.first(), registration.process.desiredSettings().scope),
                                ComplaintPlatform.ANDROID, ByteArray(32) { it.toByte() })
                            val result = registration.process.consumers.ingressAdmission.withIngress(MockHttpServletRequest().apply { remoteAddr = "192.0.2.43" }) {
                                adapter.enroll(it, candidate)
                            }
                            assertEquals(InstallationEnrollmentDisposition.CREATED, result.disposition)
                            assertEquals(candidate.installation, result.session.installation)
                            requireConnectionFree()
                            if (additionalRawEnrolled != 0) {
                                // Same real lower-core pattern as the existing two-chunk manifest fixture,
                                // now bound to the genuinely released registration; no ingress/HTTP claim.
                                val policy = registration.process.consumers.capacityPolicy
                                assertTrue(policy.dailyEnrollmentLimit >= enrollmentCount.toLong())
                                val capacity = JdbcComplaintCapacityStore(ordinary.jdbc, policy.digestBytes())
                                val phases = ComplaintInstallationEnrollmentPhaseExecutor(ordinary.ownership,
                                    JdbcComplaintInstallationEnrollmentStore(ordinary.jdbc, capacity,
                                        ComplaintInstallationEnrollmentAudit { scope, paid, at -> audit.recordInstallationEnrollment(scope, paid, at) }, registration))
                                repeat(additionalRawEnrolled) {
                                    val selected = ScopedInstallationId(UUID.randomUUID().also(actors::add), registration.process.desiredSettings().scope)
                                    val additional = InstallationEnrollmentCredentials.prepare(selected, ComplaintPlatform.ANDROID, ByteArray(32) { it.toByte() })
                                    val completed = assertInstanceOf(InstallationEnrollmentResult.Enrolled::class.java, phases.enroll(additional))
                                    assertEquals(selected, completed.installation); assertEquals(InstallationEnrollmentDisposition.CREATED, completed.disposition)
                                    f.assertDatabaseReleased()
                                }
                            }
                            assertEquals(enrollmentCount.toLong(), f.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, f.scope))
                            assertEquals(enrollmentCount.toLong(), f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_INSTALLATION_ENROLLED'", Long::class.java, f.scope))
                            // Conservative fixture-only closure, after actual exchange release. No identity,
                            // epoch/history, D, source count, reserve or proof is inserted/rewritten here.
                            assertEquals(2, f.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true " +
                                "WHERE data_scope_id IN (?, ?) AND NOT maintenance_closed AND NOT creation_closed", ComplaintDataScope.LIVE.id, f.scope))
                            f.assertNoPreviousHistory()
                        }
                        assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
                        val drain = f.beginDrain()
                        assertEquals(TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED,
                            drain.drain(f.approval(drain), f.rawEvidence, AwsJournalKmsFixture.CREDENTIALS, AwsJournalKmsFixture.CREDENTIALS))
                        f.assertReleased()
                        assertEquals(0L, drain.manifestCut().denial.firstInventory.versionCount)
                        assertEquals(listOf("LIST", "LIST"), f.inventoryRequests.map { it.kind })
                        val preparation = TestRunInstallationManifestV1.begin(drain)
                        assertEquals(TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK, preparation.prepare())
                        assertEquals(chunkSizes, (0 until preparation.capturedSource().count).map { preparation.capturedSource().chunk(it).count })
                        val manifest = preparation.beginPublication()
                        assertEquals(TestRunInstallationManifestPublicationResultV1.ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED, manifest.publish())
                        val summary = manifest.authenticatedSummary()
                        assertEquals(chunkSizes.size, summary.chunkCount); assertEquals(enrollmentCount.toLong(), summary.installationCount)
                        assertEquals(enrollmentCount.toLong(), summary.retiredCount); assertEquals(0L, summary.deletedCount)
                        val json = TestTerminalJsonV1(registration.process.consumers.journalConfiguration)
                        val actualIds = chunkSizes.indices.flatMap { index ->
                            val ref = manifest.authenticatedChunk(index)
                            val canonical = checkNotNull(f.observer.queryForObject("SELECT event_bytes FROM complaint_journal_publications " +
                                "WHERE data_scope_id = ? AND object_key = ? AND object_version = ?", ByteArray::class.java, f.scope, ref.objectKey, ref.objectVersion))
                            try {
                                assertEquals(ref.canonicalSha256, Sha256.hex(canonical))
                                assertEquals(ref.ciphertextSha256, Sha256.hex(http.manifestObjects.getValue(ref.objectKey).bytes))
                                val body = json.installationManifest(canonical)
                                assertEquals(index, body.chunkIndex); assertEquals(chunkSizes.size, body.chunkCount)
                                assertEquals(chunkSizes[index], body.entries().size)
                                body.entries().map { it.installationId }
                            } finally { canonical.fill(0) }
                        }
                        assertEquals(if (enrolled) actors.map(UUID::toString).sorted() else emptyList<String>(), actualIds)
                        val purge = manifest.beginPurgePublication()
                        if (checkUnstartedPurge) {
                            val image = p.image(); val providers = http.order.toList()
                            assertThrows<RuntimeException> { purge.beginTerminalEpochSeal() }
                            assertEquals(image, p.image()); assertEquals(providers, http.order)
                        }
                        assertEquals(TestRunPurgePublicationResultV1.PURGE_AUTHENTICATED_AND_VERIFIED, purge.publish())
                        purge.authenticatedPurge(); f.assertReleased()
                        val inventories = f.inventoryRequests.size
                        val boundary = http.boundary; val native = http.nativeBoundary
                        TestTerminalEpochSealSqlProbeV1(f).use { probe ->
                            http.boundary = { boundary(); probe.assertReleased(requireCommitted = false) }
                            http.nativeBoundary = { native(); probe.assertReleased(requireCommitted = false) }
                            try { action(f, purge, probe) }
                            finally { http.boundary = boundary; http.nativeBoundary = native }
                        }
                        assertEquals(inventories, f.inventoryRequests.size, "The terminal epoch seal does not run either final native inventory.")
                        if (enrolled) {
                            assertEquals(actors.size.toLong(), f.observer.queryForObject(
                                "SELECT count(*) FROM complaint_installation_ids i JOIN app_installations a ON a.id = i.id AND a.data_scope_id = i.data_scope_id " +
                                    "WHERE i.data_scope_id = ? AND i.id = ANY (CAST(? AS uuid[])) AND i.state = 'ACTIVE' AND i.terminal_at IS NULL " +
                                    "AND a.state = 'ACTIVE' AND a.secret_verifier IS NOT NULL AND a.deleted_at IS NULL",
                                Long::class.java, f.scope, actors.joinToString(prefix = "{", postfix = "}")),
                                "The manifest records a RETIRED disposition; this slice keeps the actual reservation and credential ACTIVE.")
                        }
                        f.assertFinishedPurge()
                    }
                } finally {
                    requireConnectionFree()
                    // This fixture's successful/failed outcome was checked above. Test teardown only.
                    actors.forEach { actor ->
                        p.f.rows.observer.update("DELETE FROM app_installations WHERE id = ? AND data_scope_id = ?", actor, p.scope)
                        p.f.rows.observer.update("DELETE FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ?", actor, p.scope)
                    }
                    p.f.rows.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_INSTALLATION_ENROLLED'", p.scope)
                }
                assertEquals(chunkSizes.size, http.manifestObjects.size)
            }
        }
    }
}
