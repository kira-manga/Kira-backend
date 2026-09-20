package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import me.manga.kira.backend.database.CatalogGenesisExitV1
import me.manga.kira.backend.database.ComplaintCatalogGenesisPublishWorkerMain
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpRequest

/** One real worker bridge over existing publisher fixtures, not another effect/recovery harness or owning-child qualification. */
internal class CatalogGenesisPublishCliCases(private val f: CatalogGenesisPublishFixture) {
    fun publisherCliUsesOriginalFrozenRequestAndReadOnlyRecovery() {
        val before = f.state()
        val args = f.selected.cliArguments().apply { this[0] = "publish" } // Existing original AUTHOR document; no TARGET substitution.
        val publisher = f.invocation() // Original budget begins after fixture-only input preparation, before the actual worker reads it.
        var currentBeforePut = false
        f.http.beforePut = {
            assertCurrentOriginal(publisher)
            assertColdTarget(publisher)
            assertTrue(f.complete(CatalogGenesisReleaseLeafV1.PUBLISH_ARMED))
            assertFalse(f.exists(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME))
            currentBeforePut = true
        }
        assertEquals(CatalogGenesisExitV1.AWAIT_REPLICATION, execute(publisher, args))
        assertTrue(currentBeforePut)
        publisher.assertReleased()
        assertTrue(f.complete(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME))
        assertFalse(f.exists(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE))
        assertFalse(f.exists(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE))
        val arm = f.read(CatalogGenesisReleaseLeafV1.PUBLISH_ARMED)
        val outcome = f.read(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME)
        assertEquals(1, f.http.put.requests.size)
        assertEquals(1, f.http.put.replies.single().calls)
        assertArrayEquals(f.selected.envelope, f.http.bodies.single())

        f.http.completeReplication()
        val recovery = f.invocation()
        var currentBeforeRecoveryRead = false
        f.http.beforeRead = {
            assertCurrentOriginal(recovery)
            assertColdTarget(recovery)
            currentBeforeRecoveryRead = true
        }
        assertEquals(CatalogGenesisExitV1.DUAL_COPY_OBSERVED, execute(recovery, args.copyOf().apply { this[0] = "recover" }))
        assertTrue(currentBeforeRecoveryRead)
        recovery.assertReleased()
        assertArrayEquals(arm, f.read(CatalogGenesisReleaseLeafV1.PUBLISH_ARMED))
        assertArrayEquals(outcome, f.read(CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME))
        val fresh: CatalogDualLocationVerifier.GenesisReadback = poolTestField(recovery.operator, "completeReadback")
        assertTrue(f.complete(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE))
        assertTrue(f.complete(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE))
        assertArrayEquals(fresh.primaryEvidenceBytes(), f.read(CatalogGenesisReleaseLeafV1.PRIMARY_READBACK_EVIDENCE))
        assertArrayEquals(fresh.replicaEvidenceBytes(), f.read(CatalogGenesisReleaseLeafV1.SECONDARY_READBACK_EVIDENCE))
        assertEquals(1, f.http.put.createdClients)
        assertEquals(1, f.http.put.requests.size)
        assertEquals(1, f.freeze.invocations.sumOf { it.signing.requests.size })
        assertEquals(before, f.state(), "The executable publisher cannot select D, COMPLETE, PROJECT or change counters.")
        f.assertUnchangedFreeze()
    }

    private fun execute(invocation: CatalogGenesisPublishInvocation, args: Array<String>): CatalogGenesisExitV1 {
        val recover = args[0] == "recover"
        val sessions = linkedMapOf(
            "SECRETS" to AwsSecretVersionFixture.CREDENTIALS,
            "PRIMARY_READ" to S3CatalogReadbackFixture.credentials,
            "REPLICA_READ" to S3CatalogReadbackFixture.credentials,
        )
        if (f.inputs.sealerMapping != null) sessions["SEALER"] = AwsSecretVersionFixture.CREDENTIALS
        if (!recover) sessions["PRIMARY_PUT"] = CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS
        val environment = sessions.flatMap { (family, session) ->
            listOf(
                "KIRA_CATALOG_PUBLISH_${family}_ACCESS_KEY_ID" to session.accessKeyId(),
                "KIRA_CATALOG_PUBLISH_${family}_SECRET_ACCESS_KEY" to session.secretAccessKey(),
                "KIRA_CATALOG_PUBLISH_${family}_SESSION_TOKEN" to session.sessionToken(),
            )
        }.toMap()
        val budget = invocation.operator.budget
        val reads = f.http.read.requests.size
        val puts = f.http.put.requests.size
        val putClients = f.http.put.createdClients
        val result = ComplaintCatalogGenesisPublishWorkerMain.execute(args, invocation.operator, environment)
        assertSame(budget, invocation.operator.budget)
        assertSame(budget, invocation.attempt.budget)
        assertSame(invocation.operator, poolTestField<CatalogGenesisPublishV1>(invocation.attempt, "operator"))
        val expectedInventory = f.inputs.allBindings().map { it.version.resourceArn to it.version.versionId }
        val actualInventory = invocation.secrets.requests.map { it.fields().let { fields -> fields["SecretId"] to fields["VersionId"] } }
        assertEquals(expectedInventory.size, actualInventory.size)
        assertEquals(expectedInventory.toSet(), actualInventory.toSet()) // Includes real config-operator input, not AUTHOR credentials.
        invocation.secrets.requests.forEach { assertSession(it.http, sessions.getValue("SECRETS")) }
        f.http.read.requests.drop(reads).forEach { assertSession(it, S3CatalogReadbackFixture.credentials) }
        if (recover) {
            assertTrue(environment.keys.none { it.startsWith("KIRA_CATALOG_PUBLISH_PRIMARY_PUT_") })
            assertEquals(putClients, f.http.put.createdClients)
            assertEquals(puts, f.http.put.requests.size)
        } else {
            f.http.put.requests.drop(puts).forEach { assertSession(it, sessions.getValue("PRIMARY_PUT")) }
        }
        return result
    }

    private fun assertCurrentOriginal(invocation: CatalogGenesisPublishInvocation) {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        val phase = invocation.phases.single()
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        assertEquals(1, invocation.observations.size)
        assertSame(invocation.attempt, poolTestField<CatalogGenesisPublishAttemptV1>(phase, "catalogPublisherAttempt"))
        assertSame(invocation.operator.budget, invocation.attempt.budget)
        assertSame(invocation.target, invocation.attempt.process)
        val reader: PersistenceTimeBudget = poolTestField(invocation.operator, "readerBudget")
        assertSame(invocation.operator.budget, poolTestField<PersistenceTimeBudget>(reader, "parent"))
        assertEquals(0, checkNotNull(invocation.coordinator).activeSnapshotOwners())
    }

    private fun assertColdTarget(invocation: CatalogGenesisPublishInvocation) {
        val target = checkNotNull(invocation.target)
        val scope = invocation.scopes.getValue("targetOwner")
        assertArrayEquals(f.selected.selectedCanonical, target.canonicalBytes())
        assertArrayEquals(f.selected.selectedHash, target.configurationHashBytes())
        assertTrue(scope.actors().none { it.hasEntered() })
        assertTrue(scope.entries().isEmpty() && scope.entries(deletion = true).isEmpty() && scope.catalogEntries().isEmpty())
        assertFalse(actualPool(target.pools.ordinary).isRunning)
        assertFalse(actualPool(target.pools.deletion).isRunning)
        assertFalse(actualPool(target.pools.catalogCoordinator.dataSource).isRunning)
        assertFalse(scope.owner.snapshot().ordinaryReady || scope.owner.snapshot().deletionReady)
    }

    private fun assertSession(request: SdkHttpRequest, session: AwsSessionCredentials) {
        assertTrue(request.firstMatchingHeader("Authorization").orElseThrow().startsWith("AWS4-HMAC-SHA256 Credential=${session.accessKeyId()}/"))
        assertEquals(session.sessionToken(), request.firstMatchingHeader("X-Amz-Security-Token").orElseThrow())
    }
}
