package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestInitialAdmissionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestPublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunInstallationManifestV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOrdinaryDrainResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgePublicationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * No fabricated successful predecessor: real PROJECT/registration, empty ordinary scan/seal,
 * manifest publication and purge. The enrolled case first uses protected initial identity
 * release and the actual registered exchange. Only its later conservative gate-CLOSE is raw
 * fixture setup (not a close producer/denial claim); no open flag, identity, payment, historical
 * checkpoint or successful object is seeded. External IAM/denial/restore and setup lease
 * expiries keep the existing explicit synthetic provenance. NOT_RUN.
 */
internal fun withTerminalEpochSealRun(tls: VersionBoundPersistenceConnectedFixture, enrolled: Boolean = false,
    shortHorizon: Boolean = false, checkUnstartedPurge: Boolean = false,
    action: (TestRunPurgeFixtureV1, TestRunPurgePublicationV1, TestTerminalEpochSealSqlProbeV1) -> Unit) {
    val inputs = TestOrdinaryDrainFixtureInputsV1(scanMillis = if (shortHorizon) 30_000 else null)
    val horizon = if (shortHorizon) Instant.now().plusSeconds(86_400).truncatedTo(ChronoUnit.SECONDS) else Instant.parse("2038-01-01T00:00:00Z")
    TestOrdinarySealHttpFixtureV1(horizon = horizon, protectedIntake = enrolled, manifestPublication = true,
        purgePublication = true, terminalEpochSeal = true).use { http ->
        ComplaintTestNamespaceRegistrationCases.withRegisteredRun(tls, ordinarySealHttp = http, ordinaryDrain = inputs,
            expireClosedSetupPredecessors = true) { p, runtime, registration, _ ->
            assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
            ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
                val actor = UUID.randomUUID()
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
                            val candidate = InstallationEnrollmentCredentials.prepare(ScopedInstallationId(actor, registration.process.desiredSettings().scope),
                                ComplaintPlatform.ANDROID, ByteArray(32) { it.toByte() })
                            val result = registration.process.consumers.ingressAdmission.withIngress(MockHttpServletRequest().apply { remoteAddr = "192.0.2.43" }) {
                                adapter.enroll(it, candidate)
                            }
                            assertEquals(InstallationEnrollmentDisposition.CREATED, result.disposition)
                            assertEquals(candidate.installation, result.session.installation)
                            requireConnectionFree()
                            assertEquals(1L, f.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, f.scope))
                            assertEquals(1L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_INSTALLATION_ENROLLED'", Long::class.java, f.scope))
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
                        val manifest = preparation.beginPublication()
                        assertEquals(TestRunInstallationManifestPublicationResultV1.ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED, manifest.publish())
                        assertEquals(if (enrolled) 1 else 0, manifest.authenticatedSummary().chunkCount)
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
                            assertEquals(1L, f.observer.queryForObject(
                                "SELECT count(*) FROM complaint_installation_ids i JOIN app_installations a ON a.id = i.id AND a.data_scope_id = i.data_scope_id " +
                                    "WHERE i.data_scope_id = ? AND i.id = ? AND i.state = 'ACTIVE' AND i.terminal_at IS NULL " +
                                    "AND a.state = 'ACTIVE' AND a.secret_verifier IS NOT NULL AND a.deleted_at IS NULL",
                                Long::class.java, f.scope, actor),
                                "The manifest records a RETIRED disposition; this slice keeps the actual reservation and credential ACTIVE.")
                        }
                        f.assertFinishedPurge()
                    }
                } finally {
                    requireConnectionFree()
                    // This fixture's successful/failed outcome was checked above. Test teardown only.
                    p.f.rows.observer.update("DELETE FROM app_installations WHERE id = ? AND data_scope_id = ?", actor, p.scope)
                    p.f.rows.observer.update("DELETE FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ?", actor, p.scope)
                    p.f.rows.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_INSTALLATION_ENROLLED'", p.scope)
                }
                assertEquals(if (enrolled) 1 else 0, http.manifestObjects.size)
            }
        }
    }
}
