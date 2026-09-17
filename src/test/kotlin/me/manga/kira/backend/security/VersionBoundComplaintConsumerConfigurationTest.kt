package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryFailure
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPosition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryRejected
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class VersionBoundComplaintConsumerConfigurationTest {
    @Test
    fun `actual acquired graph and trusted IP selection remain fixed after input and returned copy mutation without relookup`() {
        val fixture = BoundComplaintConsumerFixture()
        val proxies = mutableListOf("192.0.2.0/24", "198.51.100.0/24")
        val settings = boundConsumerTestSettings(ingressRate = 1, trustForwardedHeaders = true, trustedProxies = proxies)
        val inputs = fixture.inputs()
        val owner = fixture.configuration(inputs, settings)
        val expected = fixture.jwt.descriptors() + listOf(fixture.previous.descriptor, fixture.current.descriptor) +
            fixture.cursorSecrets.map { it.descriptor }.sortedBy { it.logicalKeyId } + fixture.routing.descriptors()
        assertEquals(9, fixture.lookups)
        assertSame(fixture.jwt, owner.jwt)
        assertSame(fixture.user, owner.jwt.boundUserKeyProvider)
        assertSame(fixture.capacity, owner.capacityPolicy)
        assertSame(fixture.journal, owner.journalConfiguration)
        assertSame(fixture.routing, owner.journalRouting)
        assertEquals("admission-z", owner.admissionCurrentKeyId)
        assertEquals("admission-a", owner.admissionPreviousKeyId)
        assertEquals("cursor-z", owner.ownerCursorCodec.activeKeyId)
        assertEquals(setOf("cursor-a", "cursor-z"), owner.ownerCursorCodec.verificationKeyIds())
        owner.ingressAdmission.withIngress(historyTestRequest(ip = "192.0.2.1").apply { addHeader("X-Forwarded-For", "203.0.113.9") }) {}

        fixture.eraseOriginals()
        fixture.installationSecrets.clear()
        fixture.cursorSecrets.clear()
        fixture.journalSecrets.clear()
        proxies.clear()
        (owner.descriptors() as MutableList<VersionedSecretBinding>).clear()
        (owner.trustedProxies() as MutableList<String>).clear()
        (owner.ownerCursorCodec.verificationKeyIds() as MutableSet<String>).clear()
        fixture.current.useMaterial { it.fill(0) }

        assertEquals(expected, owner.descriptors())
        expected.zip(owner.descriptors()).forEach { (acquired, bound) -> assertSame(acquired, bound) }
        assertEquals(listOf("192.0.2.0/24", "198.51.100.0/24"), owner.trustedProxies())
        assertTrue(owner.trustForwardedHeaders)
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            owner.ingressAdmission.withIngress(historyTestRequest(ip = "192.0.2.2").apply { addHeader("X-Forwarded-For", "203.0.113.9") }) {}
        }
        assertEquals(9, fixture.lookups)
        val rendered = listOf(owner, inputs, settings, owner.descriptors(), owner.ownerCursorCodec).joinToString()
        listOf("admission-z", "cursor-z", fixture.current.descriptor.version.resourceArn, fixture.current.descriptor.version.versionId).forEach {
            assertFalse(rendered.contains(it))
        }
    }

    @Test
    fun `actual selected cursor interoperates with the legacy constructor and accepts retained keys without changing protocol or expiry`() {
        val fixture = BoundComplaintConsumerFixture()
        val codec = fixture.configuration().ownerCursorCodec
        val actor = admissionTestActor(1)
        val position = ComplaintOwnerHistoryPosition(Instant.parse("2026-09-17T01:00:00.123456789Z"), UUID.randomUUID())
        val legacy = fixture.legacyCursor("cursor-a", Clock.systemUTC())
        val old = legacy.encode(actor, 50, position)
        val fresh = codec.encode(actor, 50, position)
        fixture.eraseOriginals()
        for (decoded in listOf(codec.decode(old, actor, 50), legacy.decode(fresh, actor, 50), codec.decode(fresh, actor, 50))) {
            assertEquals(position.id, decoded.id)
            assertEquals(position.createdAt, decoded.createdAt)
        }
        assertTrue(fresh.startsWith("v1."))
        assertSame(ComplaintOwnerCursorProtocol, codec.protocol)
        assertEquals(900L, codec.protocol.TTL_SECONDS)
        assertEquals(60L, codec.protocol.FUTURE_SKEW_SECONDS)
        assertEquals(50, codec.protocol.MAX_PAGE_LIMIT)
        val expired = fixture.legacyCursor("cursor-a", Clock.fixed(Instant.now().minusSeconds(901), ZoneOffset.UTC)).encode(actor, 50, position)
        val failure = assertThrows<ComplaintOwnerHistoryRejected> { codec.decode(expired, actor, 50) }
        assertEquals(ComplaintOwnerHistoryFailure.INVALID_CURSOR, failure.failure)
        assertThrows<IllegalArgumentException> { codec.encode(actor, 51, position) }
        assertEquals(9, fixture.lookups)
    }

    @Test
    fun `same retained P derives all actual policies while explicit global hourly quotas remain outside P and drive the actual guard`() {
        val fixture = BoundComplaintConsumerFixture()
        val originalP = fixture.capacity.canonicalBytes()
        val daily = ComplaintDailyAdmission(null, 0, fixture.capacity.dailyEnrollmentLimit)
        val differentP = ComplaintCapacityPolicyV1.of(
            fixture.capacity.hardLimit,
            fixture.capacity.creationLimit.with(ComplaintCapacityCounter.AUDIT_ROWS, 8_000_000),
            fixture.capacity.dailyEnrollmentLimit,
        )
        for (quota in 1..2) {
            // Independent cold owners for this test, not replacement of live quota state or a rotation procedure.
            val owner = fixture.configuration(
                settings = boundConsumerTestSettings(
                    enrollmentGlobal = quota,
                    createGlobal = quota,
                    ownerCreateMemberLimit = 16,
                    ownerCreatePruneBatch = 3,
                ),
            )
            assertSame(fixture.capacity, owner.capacityPolicy)
            assertArrayEquals(originalP, owner.capacityPolicy.canonicalBytes())
            assertSame(owner.enrollmentPolicy, owner.admissionPolicy.enrollment)
            assertEquals(quota, owner.enrollmentPolicy.globalPerHour)
            assertEquals(quota, owner.ownerCreatePolicy.globalPerHour)
            assertEquals(16, owner.ownerDeleteAllPolicy.memberLimit)
            assertEquals(3, owner.ownerDeleteAllPolicy.pruneBatch)
            assertEquals(owner.ownerCreatePolicy.memberLimit, owner.ownerDeleteAllPolicy.memberLimit)
            assertEquals(owner.ownerCreatePolicy.pruneBatch, owner.ownerDeleteAllPolicy.pruneBatch)
            assertEquals("memory", owner.coordinationMode)
            assertEquals(1, owner.declaredInstances)
            assertTrue(owner.enrollmentPolicy.matchesLocked(ledger(fixture.capacity), daily))
            assertTrue(owner.ownerCreatePolicy.matchesLocked(ledger(fixture.capacity)))
            assertTrue(owner.ownerDeleteAllPolicy.matchesLocked(ledger(fixture.capacity)))
            assertFalse(owner.enrollmentPolicy.matchesLocked(ledger(differentP), daily))
            assertFalse(owner.ownerCreatePolicy.matchesLocked(ledger(differentP)))
            assertFalse(owner.ownerDeleteAllPolicy.matchesLocked(ledger(differentP)))
            repeat(quota) { enrollment(owner, it + 1) }
            admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { enrollment(owner, quota + 1) }
            repeat(quota) { create(owner, it + 1) }
            admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { create(owner, quota + 1) }
        }
    }

    @Test
    fun `actual acquired owner enforces five fresh delete-all tuples per installation without recharging exact retries`() {
        val fixture = BoundComplaintConsumerFixture()
        val owner = fixture.configuration()
        val actor = admissionTestActor(1)
        val first = deleteAllTuple(actor)
        deleteAll(owner, first)
        // A new comparison view of the same tuple must deduplicate, not just the original object.
        repeat(3) { deleteAll(owner, deleteAllTuple(actor, first.operationKey)) }
        repeat(4) { deleteAll(owner, deleteAllTuple(actor)) }
        val failure = admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { deleteAll(owner, deleteAllTuple(actor)) }
        assertTrue(requireNotNull(failure.retryAfterSeconds) in 1L..86400L)
        deleteAll(owner, deleteAllTuple(actor, first.operationKey))
        deleteAll(owner, deleteAllTuple(admissionTestActor(2)))
        assertEquals(9, fixture.lookups)
    }

    @Test
    fun `actual acquired create and delete use distinct tuples in one shared retained-key member budget`() {
        val fixture = BoundComplaintConsumerFixture()
        val owner = fixture.configuration(settings = boundConsumerTestSettings(ownerCreateMemberLimit = 4, ownerCreatePruneBatch = 1))
        val deleted = deleteAllTuple(admissionTestActor(1))
        val created = ComplaintOwnerOperationTuple(deleted.installation, deleted.operationKey, UUID.randomUUID(), deleted.fingerprint.bytes())
        assertEquals(fixture.current.descriptor.logicalKeyId, owner.admissionCurrentKeyId)
        assertEquals(fixture.previous.descriptor.logicalKeyId, owner.admissionPreviousKeyId)

        // Same actor/key/digest, different operations: each registers both retained HMAC generations.
        create(owner, created)
        deleteAll(owner, deleted)
        create(owner, created)
        deleteAll(owner, deleted)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { deleteAll(owner, deleteAllTuple(deleted.installation)) }
        val nextCreate = ComplaintOwnerOperationTuple(created.installation, UUID.randomUUID(), UUID.randomUUID(), created.fingerprintBytes())
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { create(owner, nextCreate) }
        // Refused fresh work neither evicts either operation's original member nor spends a retry.
        create(owner, created)
        deleteAll(owner, deleted)
        assertEquals(9, fixture.lookups)
    }

    private fun enrollment(owner: VersionBoundComplaintConsumerConfiguration, number: Int) {
        owner.ingressAdmission.withIngress(historyTestRequest(ip = "203.0.113.$number")) { context ->
            owner.ingressAdmission.chargeEnrollment(context, Any())
        }
    }

    private fun create(owner: VersionBoundComplaintConsumerConfiguration, number: Int) {
        val tuple = ComplaintOwnerOperationTuple(admissionTestActor(number), UUID.randomUUID(), UUID.randomUUID(), ByteArray(32) { 43 })
        create(owner, tuple, "198.51.100.$number")
    }

    private fun create(owner: VersionBoundComplaintConsumerConfiguration, tuple: ComplaintOwnerOperationTuple, ip: String = "198.51.100.1") {
        owner.ingressAdmission.withIngress(historyTestRequest(ip = ip)) { context ->
            owner.ingressAdmission.startOwnerCreate(context)
            // The actual handoff path rejects any clock other than SystemComplaintAdmissionNanoClock.
            owner.ingressAdmission.admitOwnerCreate(context, tuple)
        }
    }

    private fun deleteAll(owner: VersionBoundComplaintConsumerConfiguration, tuple: InstallationDeletionPreflightTuple) {
        owner.ingressAdmission.withIngress(historyTestRequest()) { context ->
            owner.ingressAdmission.startOwnerDeleteAll(context)
            val admitted = owner.ingressAdmission.admitOwnerDeleteAll(context, tuple)
            ComplaintIngressAdmission.requireOwnerDeleteAllEntry(admitted, tuple)
        }
    }

    /** Synthetic comparison data for local composition only; cannot pass real SQL preflight custody. */
    private fun deleteAllTuple(actor: ScopedInstallationId, key: UUID = UUID.randomUUID()): InstallationDeletionPreflightTuple {
        val candidate = InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(actor, ByteArray(32) { 7 }), 1, key)
        return object : InstallationDeletionPreflightTuple {
            override val installation = actor
            override val submittedCredentialVersion = 1L
            override val operationKey = key
            override val fingerprint = ComplaintDeleteAllFingerprint.of(candidate)
        }
    }

    private fun ledger(policy: ComplaintCapacityPolicyV1): ComplaintCapacityLedger = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(policy.digestBytes(), false),
        ComplaintCapacityBalance(policy.hardLimit, policy.creationLimit, policy.hardLimit),
    )
}

/** Reuses existing P/J fixtures. Only synthetic acquisition bytes, no provider or persistence harness. */
internal class BoundComplaintConsumerFixture {
    private val originals = ArrayList<ByteArray>()
    var lookups = 0
        private set

    val userSecret = acquired(SecretMaterialFamily.USER_ADMIN_JWT, JwtService.KEY_ID, 91)
    val user = JwtKeyProvider.fromAcquired(userSecret, KiraSecurityProperties())
    val installationSecrets = mutableListOf(
        acquired(SecretMaterialFamily.INSTALLATION_JWT, "installation-z", 81),
        acquired(SecretMaterialFamily.INSTALLATION_JWT, "installation-a", 82, admissionTestBytes(82, 128)),
    )
    val jwt = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-z", installationSecrets, user)
    val capacity = ownerCreateTestCapacityPolicy()
    val journal = ComplaintJournalConfigurationV1.of(InitialLiveJournalTestFixture.declaration())
    val journalSecrets = journal.declaration().routing.keys.mapIndexed { index, key ->
        acquired(
            SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING,
            key.keyId,
            index + 21,
            admissionTestBytes(index + 21, if (index == 0) 128 else 32),
            key.secret,
        )
    }.toMutableList()
    val routing = VersionBoundComplaintJournalRouting.fromAcquired(journal, journalSecrets)
    val current = acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-z", 61)
    val previous = acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-a", 62)
    val cursorSecrets = mutableListOf(
        acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-z", 41),
        acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-a", 42),
    )

    fun inputs(
        current: AcquiredVersionedSecret = this.current,
        previous: AcquiredVersionedSecret? = this.previous,
        activeCursor: String = "cursor-z",
        cursors: List<AcquiredVersionedSecret> = cursorSecrets,
        routing: VersionBoundComplaintJournalRouting = this.routing,
    ): VersionBoundComplaintConsumerInputs = VersionBoundComplaintConsumerInputs(current, previous, activeCursor, cursors, routing)

    fun configuration(
        keys: VersionBoundComplaintConsumerInputs = inputs(),
        settings: VersionBoundComplaintConsumerSettings = boundConsumerTestSettings(),
        jwt: VersionBoundInstallationJwtConfiguration = this.jwt,
        capacity: ComplaintCapacityPolicyV1 = this.capacity,
        journal: ComplaintJournalConfigurationV1 = this.journal,
    ): VersionBoundComplaintConsumerConfiguration = VersionBoundComplaintConsumerConfiguration.fromAcquired(jwt, capacity, journal, keys, settings)

    fun acquired(
        family: SecretMaterialFamily,
        id: String,
        seed: Int,
        material: ByteArray = admissionTestBytes(seed),
        version: ImmutableSecretVersion = boundConsumerTestVersion(seed),
        purpose: SecretMaterialPurpose = SecretMaterialPurpose.HMAC_SHA256,
    ): AcquiredVersionedSecret {
        originals.add(material)
        val binding = VersionedSecretBinding.of(family, purpose, id, version)
        return AcquiredVersionedSecret.acquire(binding) {
            lookups += 1
            SecretVersionSnapshot(it, material)
        }
    }

    fun eraseOriginals() = originals.forEach { it.fill(0) }

    fun legacyCursor(active: String, clock: Clock): ComplaintOwnerCursorCodec {
        val copies = ArrayList<ByteArray>()
        fun copy(acquired: AcquiredVersionedSecret): ByteArray = acquired.useMaterial { it.copyOf().also { temporary -> copies.add(temporary) } }
        return try {
            val keys = cursorSecrets.associate { it.descriptor.logicalKeyId to copy(it) }
            val forbidden = (listOf(userSecret, current, previous) + installationSecrets + journalSecrets).map(::copy)
            ComplaintOwnerCursorCodec(active, keys, forbidden, clock)
        } finally {
            copies.forEach { it.fill(0) }
        }
    }
}

internal fun boundConsumerTestSettings(
    enrollmentGlobal: Int = 2,
    createGlobal: Int = 2,
    ingressRate: Int = 120,
    trustForwardedHeaders: Boolean = false,
    trustedProxies: List<String> = emptyList(),
    coordinationMode: String = "memory",
    declaredInstances: Int = 1,
    ownerCreateMemberLimit: Int = 64,
    ownerCreatePruneBatch: Int = 8,
): VersionBoundComplaintConsumerSettings = VersionBoundComplaintConsumerSettings(
    coordinationMode,
    declaredInstances,
    2,
    64,
    ingressRate,
    128,
    4096,
    8,
    enrollmentGlobal,
    createGlobal,
    ownerCreateMemberLimit,
    ownerCreatePruneBatch,
    trustForwardedHeaders,
    trustedProxies,
)

private fun boundConsumerTestVersion(number: Int): ImmutableSecretVersion = ImmutableSecretVersion.awsSecretsManager(
    "arn:aws:secretsmanager:us-east-1:123456789012:secret:kira/consumer-fixture-AbC123",
    "68000000-0000-4000-8000-${number.toString(16).padStart(12, '0')}",
)
