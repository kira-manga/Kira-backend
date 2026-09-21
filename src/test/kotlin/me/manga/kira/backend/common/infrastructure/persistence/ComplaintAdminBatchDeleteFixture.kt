package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintAdminBatchHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminBatchStatusResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchDeleteService
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchStatusService
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteTarget
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequest
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.TestAdminBatchDeleteJournalTupleV1
import me.manga.kira.backend.security.adminBatchStatusTestRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Timestamp
import java.util.UUID

/** Reuse the existing Admin/owner SQL, scoped issuer and raw provider fixture; no new database owner. */
internal fun withAdminBatchDelete(database: PgLifecycleDatabaseFixture, test: (ComplaintAdminBatchDeleteFixture) -> Unit) =
    withOwnerDeleteAllAuthorization(database) { existing ->
        OrdinaryComplaintTestInstallationFixture(existing.base).use { run ->
            ComplaintAdminDeleteFixture(existing, run, batchEnabled = true).use { core -> test(ComplaintAdminBatchDeleteFixture(core)) }
        }
    }

internal class ComplaintAdminBatchDeleteFixture(val core: ComplaintAdminDeleteFixture) {
    private val mapper = ObjectMapper()
    fun attempt(targets: List<Pair<UUID, Long>>, key: UUID = UUID.randomUUID()): AdminBatchDeleteFixtureAttempt {
        val raw = ComplaintAdminBatchDeleteInput(core.scope, key, targets.map { (id, version) ->
            ComplaintAdminBatchDeleteTarget(id, ComplaintAdminDeletePrecondition.parse(id, "\"complaint-$id-v$version\""))
        })
        return AdminBatchDeleteFixtureAttempt(raw, ComplaintAdminDeleteCandidate.prepare(core.base.ordinary.userId, ComplaintAdminDeleteRequest.normalize(raw)))
    }
    fun input(attempt: AdminBatchDeleteFixtureAttempt, proof: String? = null, bearer: String? = core.token): MockHttpServletRequest =
        adminBatchStatusTestRequest(core.scope, attempt.key, bearer, proof, mapper.writeValueAsString(linkedMapOf(
            "action" to "DELETE", "targets" to attempt.raw.targets.reversed().map { linkedMapOf("id" to it.id.toString(), "actionTag" to it.precondition.canonical) },
        )))
    fun http(publishers: TestAdminDeleteJournalPublisherFactoryV1) = ComplaintAdminBatchHttpHandler(
        ComplaintAdminBatchStatusService(object : ComplaintAdminBatchStatusPort {
            override fun change(context: ComplaintAdminBatchStatusRequestContext, bearer: String, proof: String?, input: ComplaintAdminBatchStatusInput): ComplaintAdminBatchStatusReceipt =
                error("Destructive request must never call STATUS, nor scalar DELETE N times.")
        }), core.ingress, ComplaintAdminBatchStatusResponses(core.responseOwner),
        ComplaintAdminBatchDeleteService(ComplaintAdminDeleteAdapter(core.graph, core.decoder, core.reads, core.phases, publishers)),
    )
    fun send(request: MockHttpServletRequest, handler: ComplaintAdminBatchHttpHandler) =
        MockHttpServletResponse().also { handler.handleRequest(request, it); core.assertReleased() }
    fun wire(attempt: AdminBatchDeleteFixtureAttempt, grant: UUID, owners: List<UUID> = listOf(core.creator.actor.id)): TestOwnerDeleteJournalPublisherFixture {
        val tuple = TestAdminBatchDeleteJournalTupleV1(11, core.base.ordinary.userId, attempt.key, attempt.candidate.tuple.fingerprintBytes(), core.scope,
            grant, owners.distinct().sortedBy(UUID::toString))
        return TestOwnerDeleteJournalPublisherFixture(core.routing, core.codec.canonicalizeAdminBatch(tuple, attempt.ids)).also { wire ->
            wire.wall = checkNotNull(core.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
            wire.beforeOpen = {
                requireConnectionFree(); wire.wall = checkNotNull(core.observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
            }
            wire.beforePrepare = core::assertReleased
        }
    }
    fun prepared(attempt: AdminBatchDeleteFixtureAttempt, proof: String, publishers: TestAdminDeleteJournalPublisherFactoryV1): CommittedTestAdminDeleteWork.Prepared =
        publishers.reserve().use {
            core.ingress.withIngress(input(attempt, proof)) { context ->
                core.ingress.startAdminBatchDelete(context)
                val identity = core.decoder.decode(core.token)
                assertNull(core.reads.authenticate(identity).verdict.failure)
                val preflight = core.reads.preflight(identity, attempt.candidate.tuple)
                assertNull(preflight.failure); assertNull(preflight.receipt); assertFalse(preflight.authorized)
                core.assertReleased()
                val admitted = core.ingress.admitAdminBatchDelete(context, attempt.candidate.tuple)
                val outcome = core.phases.authorize(identity, attempt.candidate, preflight, proof, admitted)
                core.assertReleased()
                assertInstanceOf(CommittedTestAdminDeleteWork.Prepared::class.java, assertInstanceOf(TestAdminDeleteAuthorizationV1.Continue::class.java, outcome).work)
            }
        }
    fun reload(attempt: AdminBatchDeleteFixtureAttempt): CommittedTestAdminDeleteWork {
        val identity = core.decoder.decode(core.token)
        val preflight = core.reads.preflight(identity, attempt.candidate.tuple)
        assertTrue(preflight.authorized)
        return assertInstanceOf(TestAdminDeleteAuthorizationV1.Continue::class.java, core.phases.reload(identity, attempt.candidate, preflight)).work.also { core.assertReleased() }
    }
}

internal class AdminBatchDeleteFixtureAttempt(val raw: ComplaintAdminBatchDeleteInput, val candidate: ComplaintAdminDeleteCandidate) {
    val ids get() = raw.targets.map { it.id }
    val key get() = raw.key
}
