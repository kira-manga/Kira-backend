package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.journal.INVALID_LIVE_JOURNAL_POLICY
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalCopyPolicyV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalHmacRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalKmsRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalLockPolicyV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalPolicyDeploymentV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalTimeBoundV1
import me.manga.kira.backend.complaint.infrastructure.journal.VersionBoundLiveJournalCoverageV1
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Cold declarations and real retained J/reader owners only. No installation or complete-source claim. */
class LiveJournalPolicyDeploymentV1Test {
    private val consumer = BoundComplaintConsumerFixture()
    private val reader = VersionBoundCatalogReadbackTestFixture.settings()
    private val original = liveJournalPolicy(consumer.routing, reader)

    @Test
    fun `snapshot sorting and retained owner identity cannot follow caller collection or same value replacements`() {
        val copies = original.copyPolicies.reversed().toMutableList()
        val hmac = original.hmacKeys.reversed().toMutableList()
        val kms = original.kmsKeys.reversed().toMutableList()
        val snapshot = replaceLiveJournalPolicy(original, copies = copies, hmac = hmac, kms = kms)
        JournalPublicationLanesV1(consumer.journal).use { lanes ->
            val owner = VersionBoundLiveJournalCoverageV1.fromIndependentInputs(consumer.routing, reader, lanes, snapshot)
            copies.clear()
            hmac.clear()
            kms.clear()
            (snapshot.copyPolicies as MutableList<*>).clear()
            (snapshot.hmacKeys as MutableList<*>).clear()
            assertEquals(original.copyPolicies, snapshot.copyPolicies)
            assertEquals(original.hmacKeys, snapshot.hmacKeys)
            assertEquals(original.kmsKeys, snapshot.kmsKeys)
            assertSame(snapshot, owner.deployment)
            owner.requireRetained(consumer.routing, reader, lanes)
            rejected { owner.requireRetained(consumer.routing, VersionBoundCatalogReadbackTestFixture.settings(), lanes) }
            val sameJRouting = VersionBoundComplaintJournalRouting.fromAcquired(consumer.journal, consumer.journalSecrets)
            rejected { owner.requireRetained(sameJRouting, reader, lanes) }
            JournalPublicationLanesV1(consumer.journal).use { other -> rejected { owner.requireRetained(consumer.routing, reader, other) } }
            assertEquals(0L, lanes.activeOwners().totalOwners)
        }
    }

    @Test
    fun `each closed logical selector rejects aliases overlap unsupported topology and malformed policy before D`() {
        val first = original.copyPolicies.first()
        val invalid = listOf(
            first.copy(sourceKind = "PHYSICAL_SNAPSHOT"),
            first.copy(locationClass = "WAL"),
            first.copy(prefix = "logical"),
            first.copy(prefix = "logical/../"),
            first.copy(prefix = "/logical/"),
            first.copy(accountId = "123"),
            first.copy(region = "unknown"),
            first.copy(bucket = "invalid..bucket"),
            first.copy(policy = first.policy.copy(version = 0)),
            first.copy(policy = first.policy.copy(sha256 = "not-a-digest")),
        )
        invalid.forEach { copy -> rejected { replaceLiveJournalPolicy(original, copies = listOf(copy)) } }
        rejected { replaceLiveJournalPolicy(original, copies = listOf(first, first)) }
        rejected { replaceLiveJournalPolicy(original, copies = listOf(first, first.copy(prefix = first.prefix + "nested/"))) }
        val sameIdDifferentContent = original.copyPolicies.last().copy(policy = first.policy.copy(sha256 = "f".repeat(64)))
        rejected { replaceLiveJournalPolicy(original, copies = listOf(first, sameIdDifferentContent)) }
        // Distinct classes and prefix siblings are unambiguous; every actual artifact is still checked at binding.
        val siblings = replaceLiveJournalPolicy(original, copies = listOf(first.copy(prefix = "logical/a/"), first.copy(prefix = "logical/b/")))
        retain(siblings)
    }

    @Test
    fun `mapping bound environment all J keys role isolation and independent backup account are not optional`() {
        rejected { retain(replaceLiveJournalPolicy(original, environment = "another-environment")) }
        val lock = original.journalLock
        listOf(
            lock.copy(location = lock.location.copy(bucket = "another-journal")),
            lock.copy(ordinaryPrefix = lock.sealTerminalPrefix),
            lock.copy(sealTerminalPrefix = lock.ordinaryPrefix),
            lock.copy(authorities = lock.authorities.copy(ordinary = lock.authorities.recovery)),
        ).forEach { changed -> rejected { retain(replaceLiveJournalPolicy(original, lock = changed)) } }
        rejected { retain(replaceLiveJournalPolicy(original, hmac = original.hmacKeys.drop(1))) }
        rejected { retain(replaceLiveJournalPolicy(original, hmac = original.hmacKeys.reversed().map { it.copy(key = it.key.copy(keyId = "different")) })) }
        rejected { retain(replaceLiveJournalPolicy(original, kms = original.kmsKeys.map { it.copy(key = it.key.copy(keyArn = it.key.keyArn + "other")) })) }
        rejected { retain(replaceLiveJournalPolicy(original, copies = original.copyPolicies.map { it.copy(accountId = lock.location.accountId) })) }
        val boundedReader = VersionBoundCatalogReadbackTestFixture.settings(
            policy = VersionBoundCatalogReadbackTestFixture.chainPolicy(limits = reader.chainPolicy.limits.copy(maximumManifestRecords = 1)),
        )
        rejected { retain(original, selectedReader = boundedReader) }
    }

    @Test
    fun `all copy ages are constrained by original J not a second supplied horizon`() {
        val maximum = consumer.journal.declaration().limits.retention.maximumRestoreAgeSeconds
        retain(replaceLiveJournalPolicy(original, copies = original.copyPolicies.map { it.copy(maximumAgeSeconds = maximum) }))
        retain(replaceLiveJournalPolicy(original, copies = original.copyPolicies.map { it.copy(maximumAgeSeconds = 0) }))
        rejected { retain(replaceLiveJournalPolicy(original, copies = original.copyPolicies.map { it.copy(maximumAgeSeconds = maximum + 1) })) }
        rejected { replaceLiveJournalPolicy(original, copies = original.copyPolicies.map { it.copy(maximumAgeSeconds = -1) }) }
        val input = consumer.journal.declaration()
        val zeroAgeJ = ComplaintJournalConfigurationV1.of(
            input.copy(limits = input.limits.copy(retention = input.limits.retention.copy(maximumRestoreAgeSeconds = 0))),
        )
        val routing = VersionBoundComplaintJournalRouting.fromAcquired(zeroAgeJ, consumer.journalSecrets)
        val policy = liveJournalPolicy(routing, reader)
        retain(policy, routing)
        rejected { retain(original, routing) }
    }

    @Test
    fun `finite qualified clock and late arrival references are explicit and never SDK timeouts`() {
        listOf(original.acceptedRequestLateArrival, original.utcUncertainty).forEach { time ->
            val bad = listOf(time.copy(maximumMillis = -1), time.copy(maximumMillis = Long.MAX_VALUE), time.copy(profileId = ""))
            bad.forEach { changed ->
                rejected { replaceLiveJournalPolicy(original, late = changed) }
                rejected { replaceLiveJournalPolicy(original, utc = changed) }
            }
        }
        val maximum = original.utcUncertainty.copy(maximumMillis = CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND * 1000)
        retain(replaceLiveJournalPolicy(original, utc = maximum)) // Calendar overflow is checked against the owner's sampled time, never clamped.
        val changed = original.acceptedRequestLateArrival.copy(maximumMillis = reader.sdkLimits.requestTimeoutMillis.toLong() + 123)
        assertEquals(changed, retain(replaceLiveJournalPolicy(original, late = changed)).deployment.acceptedRequestLateArrival)
    }

    private fun retain(
        policy: LiveJournalPolicyDeploymentV1,
        routing: VersionBoundComplaintJournalRouting = consumer.routing,
        selectedReader: VersionBoundCatalogReadbackConfigurationV1 = reader,
    ): VersionBoundLiveJournalCoverageV1 = VersionBoundLiveJournalCoverageV1.fromIndependentInputs(
        routing,
        selectedReader,
        JournalPublicationLanesV1(routing.journalConfiguration),
        policy,
    )

    private fun rejected(action: () -> Unit) {
        assertEquals(INVALID_LIVE_JOURNAL_POLICY, assertThrows<IllegalArgumentException> { action() }.message)
    }
}

/** Synthetic independent deployment inputs for the existing real consumer/catalog fixtures; not production installation evidence. */
internal fun liveJournalPolicy(
    routing: VersionBoundComplaintJournalRouting,
    reader: VersionBoundCatalogReadbackConfigurationV1,
): LiveJournalPolicyDeploymentV1 {
    val journal = routing.journalConfiguration.declaration()
    fun policy(name: String): InitialPolicyReferenceV1 = InitialPolicyReferenceV1(name, 1, "a".repeat(64))
    val copies = listOf("PRIMARY", "REPLICA", "OPERATOR", "OFFSITE").map { locationClass ->
        val replica = locationClass == "REPLICA"
        LiveJournalCopyPolicyV1(
            CatalogLogicalInventoryProtocol.SOURCE_KIND,
            locationClass,
            if (replica) "222222222222" else "111111111111",
            if (replica) "eu-west-1" else "us-east-1",
            if (replica) "backup-replica" else "backup-primary",
            "logical/",
            policy("logical-${locationClass.lowercase()}-age"),
            journal.limits.retention.maximumRestoreAgeSeconds,
        )
    }
    return LiveJournalPolicyDeploymentV1(
        reader.chainPolicy.trustBundlePolicy.expectedEnvironment,
        copies,
        LiveJournalLockPolicyV1(
            journal.journalLocation,
            OfflineBootstrapGrammar.ordinaryPrefix(journal.writer.generationId),
            OfflineBootstrapGrammar.sealTerminalPrefix(journal.writer.generationId),
            journal.authorities,
            policy("journal-compliance-retention"),
        ),
        journal.routing.keys.map { LiveJournalHmacRetentionV1(it, policy("hmac-${it.keyId}-retention")) },
        listOf(journal.encryption, journal.recovery.queue.encryption, journal.recovery.deadLetterQueue.encryption).distinct()
            .map { LiveJournalKmsRetentionV1(it, policy("kms-${it.keyId}-retention")) },
        LiveJournalTimeBoundV1("s3-accepted-request-bound-v1", policy("s3-late-arrival"), 120_000),
        LiveJournalTimeBoundV1("independent-utc-error-v1", policy("utc-uncertainty"), 250),
    )
}

internal fun replaceLiveJournalPolicy(
    original: LiveJournalPolicyDeploymentV1,
    copies: List<LiveJournalCopyPolicyV1> = original.copyPolicies,
    lock: LiveJournalLockPolicyV1 = original.journalLock,
    hmac: List<LiveJournalHmacRetentionV1> = original.hmacKeys,
    kms: List<LiveJournalKmsRetentionV1> = original.kmsKeys,
    late: LiveJournalTimeBoundV1 = original.acceptedRequestLateArrival,
    utc: LiveJournalTimeBoundV1 = original.utcUncertainty,
    environment: String = original.environment,
): LiveJournalPolicyDeploymentV1 = LiveJournalPolicyDeploymentV1(environment, copies, lock, hmac, kms, late, utc)
