package me.manga.kira.backend.complaint.infrastructure.reconciliation

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.AUTHORIZATION
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.PROMISE
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.READ_CREATE
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.assertCharge
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.checked
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.header
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.problem
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Timestamp
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.LockSupport

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Real registered TEST HTTP A and separately owned B;
 * no JWT substitution, seeded completion, status route, synchronous APPLY or history reset.
 * G1: Delegated model safety refusal; no implementation retry or workaround.
 */
internal object TestRegisteredOwnerDeleteAllHttpCasesV1 {
    fun authenticatedReplayAfterSeparateB(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredOwnerDeleteAllHttp(tls) { f ->
        val actor = f.first.initial.candidate().installation
        val token = f.enroll(actor)
        val target = UUID.randomUUID(); val input = RegisteredOwnerDeleteAllHttpRequestV1(actor, listOf(target))
        val beforeCheckpoint = f.image(); val beforeCounters = f.counters()
        problem(f.delete(input), 503, "SERVICE_UNAVAILABLE")
        f.assertReleased()
        assertEquals(beforeCheckpoint, f.image()); assertEquals(beforeCounters, f.counters())
        assertNull(f.raw.publisher, "Enrollment/registration cannot replace the current checkpoint.")

        f.withCheckpoint {
            f.create(target, token)
            val owner = poolTestField<PersistencePhaseOwnership>(f.web.startup, "ownership")
            val jdbc = poolTestField<JdbcTemplate>(f.web.startup, "jdbc")
            f.first.registration.requireIdentityAdmissionPhaseResources(owner, jdbc)
            val older = ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(f.first.registration, f.first.assembly, owner, jdbc, f.audit)
            assertEquals(READ_CREATE, older.mappedPaths)
            val excluded = object : MockHttpServletRequest("POST", ComplaintInstallationRoutes.DELETE_ALL) {
                override fun getInputStream(): ServletInputStream = error("Older selector read ALL input.")
            }
            val response = MockHttpServletResponse()
            older.ingressFilter.doFilter(excluded, response, FilterChain { _, _ -> error("Older selector widened to ALL.") })
            assertEquals(404, response.status)
            val selected = f.web.context.getBean(ComplaintTestBootstrapHttpCompositionV1::class.java)
            assertTrue(selected.mapsRequest(MockHttpServletRequest("DELETE", "${ComplaintInstallationRoutes.HISTORY}/$target")), "Existing owner DELETE remains selected.")
            checked(f.web.get("${ComplaintInstallationRoutes.HISTORY}/$target", token), 200)

            val rows = f.image(); val counters = f.counters()
            val wrongSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x5a })
            problem(f.delete(input, secret = wrongSecret, bearer = token), 403, "INSTALLATION_CREDENTIAL_REJECTED")
            problem(f.delete(input, version = 2), 403, "INSTALLATION_CREDENTIAL_REJECTED")
            problem(f.delete(input, key = null), 400, "VALIDATION_FAILED")
            problem(f.delete(input, path = "${ComplaintInstallationRoutes.DELETE_ALL}?unexpected=1"), 400, "VALIDATION_FAILED")
            for ((method, path) in listOf("GET" to ComplaintInstallationRoutes.DELETE_ALL, "POST" to "${ComplaintInstallationRoutes.DELETE_ALL}/",
                "POST" to "${ComplaintInstallationRoutes.DELETE_ALL}/status", "GET" to ComplaintInstallationRoutes.ME,
                "POST" to "${ComplaintInstallationRoutes.HISTORY}/$target/replies", "PATCH" to "${ComplaintInstallationRoutes.HISTORY}/$target/content",
                "DELETE" to "/api/v1/admin/complaints/$target")) f.web.refusedBeforeBody(method, path, token, 404)
            f.assertReleased()
            assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertNull(f.raw.publisher)

            f.raw.expected = input
            problem(f.delete(input), 503, "SERVICE_UNAVAILABLE") // No bearer is needed; A/VERIFY is not 202 or 204.
            f.assertReleased(); f.assertPending(input, verified = true)
            assertCharge(counters, f.counters(), AUTHORIZATION, PROMISE)
            val record = f.raw.record()
            val pending = f.image(); val paid = f.counters(); val native = f.raw.counts()
            problem(f.delete(input, key = UUID.randomUUID()), 409, "IDEMPOTENCY_KEY_REUSED")
            problem(f.delete(input, secret = wrongSecret, bearer = token), 403, "INSTALLATION_CREDENTIAL_REJECTED")
            problem(f.delete(input, version = 2), 403, "INSTALLATION_CREDENTIAL_REJECTED")
            problem(f.delete(input, bearer = "not-a-valid-jwt"), 503, "SERVICE_UNAVAILABLE")
            f.assertReleased()
            assertEquals(pending, f.image()); assertEquals(paid, f.counters()); assertEquals(native, f.raw.counts())
            assertTrue(f.raw.queue.order.isEmpty()); assertTrue(f.raw.queue.ackRequests.isEmpty(), "HTTP cannot start B.")

            f.completeThroughQueue(input, record)
            f.assertApplied(input, record) // Includes actual B's exact original 192-hour credential + receipt retention.
            val completed = f.image(); val completedCounters = f.counters(); val completedNative = f.raw.counts()
            val replay = checked(f.delete(input), 204)
            assertArrayEquals(ByteArray(0), replay.body())
            for (name in listOf("Content-Type", "Content-Length", "ETag", "Location", "Retry-After")) assertNull(header(replay, name))
            // A cached successful replay cannot bypass another request's original body authentication.
            problem(f.delete(input, secret = wrongSecret, bearer = token), 403, "INSTALLATION_CREDENTIAL_REJECTED")
            problem(f.delete(input, version = 2), 403, "INSTALLATION_CREDENTIAL_REJECTED")
            problem(f.delete(input, key = UUID.randomUUID()), 409, "IDEMPOTENCY_KEY_REUSED")
            checked(f.delete(input, bearer = token), 204) // This JWT's credential is now DELETED/version2: never bearer authority.
            checked(f.delete(input, bearer = "not-a-valid-jwt"), 204)
            f.assertReleased()
            assertEquals(completed, f.image(), "Fresh authenticated replay never extends or rewrites the retained TTL/proof/history.")
            assertEquals(completedCounters, f.counters()); assertEquals(completedNative, f.raw.counts())
            f.first.registration.close()
            checked(f.delete(input), 503); f.assertReleased()
            assertEquals(completed, f.image()); assertEquals(completedCounters, f.counters()); assertEquals(completedNative, f.raw.counts())
        }
    }

    fun expiredCurrentCheckpoint(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredOwnerDeleteAllHttp(tls, shortFreshness = true) { f ->
        val actor = f.first.initial.candidate().installation; val token = f.enroll(actor)
        f.withCheckpoint {
            val target = UUID.randomUUID(); f.create(target, token)
            val input = RegisteredOwnerDeleteAllHttpRequestV1(actor, listOf(target)); f.raw.expected = input
            assertEquals(30_000, f.first.process.consumers.journalConfiguration.declaration().limits.deadlines.checkpointMaxAgeMillis)
            val expires = (f.first.control().getValue("checkpoint_completed_at") as Timestamp).toInstant().plusMillis(30_000)
            val rows = f.image(); val counters = f.counters(); val native = f.raw.counts()
            awaitLifecycleFact(31_000) { f.jdbc.queryForObject("SELECT clock_timestamp() > ?::timestamptz", Boolean::class.java, Timestamp.from(expires)) == true }
            problem(f.delete(input), 503, "SERVICE_UNAVAILABLE")
            f.assertReleased()
            assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertEquals(native, f.raw.counts())
            assertNull(f.raw.publisher); assertTrue(f.raw.queue.order.isEmpty())
            // This actual checkpoint expiry is not an eight-day completed-verifier expiry experiment.
        }
    }

    fun nativeReadbackFailure(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredOwnerDeleteAllHttp(tls) { f ->
        val actor = f.first.initial.candidate().installation; val token = f.enroll(actor)
        f.withCheckpoint {
            val target = UUID.randomUUID(); f.create(target, token)
            val input = RegisteredOwnerDeleteAllHttpRequestV1(actor, listOf(target))
            f.raw.expected = input; f.raw.failReadback = true
            val counters = f.counters()
            problem(f.delete(input), 503, "SERVICE_UNAVAILABLE")
            f.assertReleased(); f.assertPending(input, verified = false)
            assertCharge(counters, f.counters(), AUTHORIZATION, PROMISE)
            val publisher = checkNotNull(f.raw.publisher)
            assertEquals(1, publisher.requests.count { it.kind == "PUT" }); assertEquals(1, publisher.objects.size)
            assertTrue(publisher.requests.any { it.kind == "GET" }); assertEquals(1, publisher.generated()); assertEquals(0, publisher.decrypted())
            assertTrue(f.raw.queue.order.isEmpty()); assertTrue(f.raw.queue.ackRequests.isEmpty())
            // No implementation retry, forged readback or test-written completion. Actual SDK response,
            // client, lane and original SQL ownership cleanup is asserted before disposable teardown.
        }
    }

    fun originalAllLaneShutdown(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredOwnerDeleteAllHttp(tls) { f ->
        val all = poolTestField<TestOwnerDeleteAllJournalPublisherFactoryV1>(f.web.startup, "ownerDeleteAllPublisher")
        val owner = poolTestField<TestOwnerDeleteJournalPublisherFactoryV1>(f.web.startup, "ownerDeletePublisher")
        assertFalse(all.isClosed()); assertFalse(owner.isClosed())
        val counters = f.counters(); val native = f.raw.counts()
        val lane = all.reserve()
        try {
            OwnedCallerTestScope().use { callers ->
                callers.beforeClose(lane::close)
                val releasing = callers.launch {
                    awaitLifecycleFact(5_000) { f.web.ingressSnapshot().stopped &&
                        runCatching { f.first.registration.requireUsable() }.exceptionOrNull() is ComplaintTestNamespaceRegistrationExceptionV1 }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.first.registration.requireUsable() }
                    assertEquals(0, f.web.ingressSnapshot().reservations); assertEquals(0, f.web.admission.activeOwners())
                    assertEquals(0, f.deletionAdmission.activeOwners().totalOwners)
                    assertEquals(1L, f.first.process.publicationLanes.activeOwners().totalOwners)
                    // ALL alone holds the original startup; no ordinary/deletion owner can mask a missing predicate.
                    val until = System.nanoTime() + 100_000_000
                    while (System.nanoTime() < until) {
                        assertTrue(f.web.emf.isOpen); assertTrue(f.web.context.isActive)
                        assertFalse(all.isClosed()); assertFalse(owner.isClosed())
                        assertFalse(f.first.process.pools.shutdownRequested())
                        assertFalse(poolTestField<Boolean>(f.web.startup, "cleanupProven"))
                        LockSupport.parkNanos(1_000_000)
                    }
                    lane.close()
                }
                f.web.startup.close(); releasing.value()
            }
            f.web.assertDisposed(nativeStillActive = true)
            assertTrue(all.isClosed()); assertTrue(owner.isClosed())
            assertTrue(poolTestField<Boolean>(f.web.startup, "ownerDeleteAllPublisherCloseReturned"))
            assertTrue(poolTestField<Boolean>(f.web.startup, "ownerDeletePublisherCloseReturned"))
            assertThrows<ComplaintTestDeploymentExceptionV1> { f.web.startup.start() }
            assertEquals(counters, f.counters()); assertEquals(native, f.raw.counts())
        } finally { lane.close() }
    }
}
