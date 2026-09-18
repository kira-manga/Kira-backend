package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintDeleteAllFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreationOperation
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerHistoryPosition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Real acquired cold TEST consumers. An admitted comparison tuple is not authentication, SQL custody or TEST activation. */
class VersionBoundTestComplaintConsumerConfigurationV1Test {
    @Test
    fun `actual acquired TEST owners retain keys and trusted IP selection after input and returned copy mutation`() {
        val fixture = BoundTestComplaintConsumerFixture()
        val proxies = mutableListOf("192.0.2.0/24", "198.51.100.0/24", "192.0.2.0/24")
        val owner = fixture.configuration(settings = boundConsumerTestSettings(ingressRate = 1, trustForwardedHeaders = true, trustedProxies = proxies))
        val descriptors = owner.descriptors()
        val lookups = fixture.base.lookups
        assertSame(fixture.base.jwt, owner.jwt)
        assertSame(fixture.base.user, owner.jwt.boundUserKeyProvider)
        assertSame(fixture.base.capacity, owner.capacityPolicy)
        assertSame(fixture.journal, owner.journalConfiguration)
        assertSame(fixture.routing, owner.journalRouting)
        assertEquals(11, descriptors.size)
        assertEquals(fixture.routing.descriptors(), descriptors.filter { it.family == SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING })
        assertEquals("admission-z", owner.admissionCurrentKeyId)
        assertEquals("admission-a", owner.admissionPreviousKeyId)
        assertEquals("cursor-z", owner.ownerCursorCodec.activeKeyId)
        assertEquals(setOf("cursor-a", "cursor-z"), owner.ownerCursorCodec.verificationKeyIds())
        val actor = fixture.actor(1)
        val position = ComplaintOwnerHistoryPosition(Instant.parse("2026-09-17T01:00:00.123456789Z"), UUID.randomUUID())
        val cursor = owner.ownerCursorCodec.encode(actor, 50, position)
        val legacy = fixture.base.legacyCursor("cursor-a", Clock.systemUTC())
        val retainedCursor = legacy.encode(actor, 50, position)
        owner.ingressAdmission.withIngress(historyTestRequest(ip = "192.0.2.1").apply { addHeader("X-Forwarded-For", "203.0.113.9") }) {}

        fixture.base.eraseOriginals()
        fixture.base.cursorSecrets.clear()
        fixture.base.installationSecrets.clear()
        fixture.routingSecrets.clear()
        proxies.clear()
        (owner.descriptors() as MutableList<*>).clear()
        (owner.trustedProxies() as MutableList<*>).clear()
        owner.journalConfiguration.canonicalBytes().fill(0)
        (owner.ownerCursorCodec.verificationKeyIds() as MutableSet<*>).clear()
        val decodedPositions = listOf(
            owner.ownerCursorCodec.decode(cursor, actor, 50), owner.ownerCursorCodec.decode(retainedCursor, actor, 50), legacy.decode(cursor, actor, 50),
        )
        for (decoded in decodedPositions) {
            assertEquals(position.id, decoded.id)
            assertEquals(position.createdAt, decoded.createdAt)
        }
        assertEquals(descriptors, owner.descriptors())
        descriptors.zip(owner.descriptors()).forEach { (acquired, retained) -> assertSame(acquired, retained) }
        assertEquals(listOf("192.0.2.0/24", "198.51.100.0/24", "192.0.2.0/24"), owner.trustedProxies())
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            owner.ingressAdmission.withIngress(historyTestRequest(ip = "192.0.2.2").apply { addHeader("X-Forwarded-For", "203.0.113.9") }) {}
        }
        assertEquals(lookups, fixture.base.lookups)
    }

    @Test
    fun `create reply edit and single delete share one retained-key member budget and delete-all remains disabled`() {
        val fixture = BoundTestComplaintConsumerFixture()
        val owner = fixture.configuration(settings = boundConsumerTestSettings(createGlobal = 3, ownerCreateMemberLimit = 8, ownerCreatePruneBatch = 2))
        val actor = fixture.actor(1)
        val key = UUID.randomUUID()
        val target = UUID.randomUUID()
        val parent = UUID.randomUUID()
        // Equal actor/key/fingerprint must still have four distinct fixed operation members, two HMAC generations each.
        val fingerprint = ByteArray(32) { 43 }
        val create = ComplaintOwnerOperationTuple(actor, key, target, fingerprint)
        val reply = ComplaintOwnerOperationTuple(actor, key, ComplaintOwnerCreationOperation.OWNER_REPLY, listOf(parent, target), fingerprint)
        val edit = ComplaintOwnerEditTuple(actor, key, target, fingerprint)
        val delete = ComplaintOwnerDeleteTuple(actor, key, target, fingerprint)
        repeat(2) {
            creation(owner, create)
            creation(owner, reply)
            editing(owner, edit)
            deletion(owner, delete)
        }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { creation(owner, creationTuple(actor)) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { creation(owner, creationTuple(actor, reply = true)) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { editing(owner, ComplaintOwnerEditTuple(actor, UUID.randomUUID(), target, fingerprint)) }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) {
            deletion(owner, ComplaintOwnerDeleteTuple(actor, UUID.randomUUID(), target, fingerprint))
        }
        assertSame(ComplaintOwnerDeleteAllAdmissionPolicy.Disabled, owner.ownerDeleteAllPolicy)
        owner.ingressAdmission.withIngress(historyTestRequest()) { context ->
            owner.ingressAdmission.startOwnerDeleteAll(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { owner.ingressAdmission.admitOwnerDeleteAll(context, deleteAllTuple(actor)) }
        }
        // A LIVE-shaped comparison tuple reaches the disabled policy, rather than passing on TEST's earlier scope refusal alone.
        owner.ingressAdmission.withIngress(historyTestRequest()) { context ->
            owner.ingressAdmission.startOwnerDeleteAll(context)
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) {
                owner.ingressAdmission.admitOwnerDeleteAll(context, deleteAllTuple(admissionTestActor(1)))
            }
        }
        val policy = owner.capacityPolicy
        val changed = ComplaintCapacityPolicyV1.of(
            policy.hardLimit.with(ComplaintCapacityCounter.AUDIT_ROWS, policy.hardLimit[ComplaintCapacityCounter.AUDIT_ROWS] + 1),
            policy.creationLimit,
            policy.dailyEnrollmentLimit,
        )
        assertTrue(owner.ownerCreatePolicy.matchesLocked(ledger(policy)))
        assertTrue(owner.ownerEditPolicy.matchesLocked(ledger(policy)))
        assertTrue(owner.ownerDeletePolicy.matchesLocked(ledger(policy)))
        assertFalse(owner.ownerCreatePolicy.matchesLocked(ledger(changed)))
        assertFalse(owner.ownerEditPolicy.matchesLocked(ledger(changed)))
        assertFalse(owner.ownerDeletePolicy.matchesLocked(ledger(changed)))
        assertEquals(8, owner.ownerEditPolicy.memberLimit)
        assertEquals(8, owner.ownerDeletePolicy.memberLimit)
        assertEquals(2, owner.ownerEditPolicy.pruneBatch)
        assertEquals(2, owner.ownerDeletePolicy.pruneBatch)
        // Refused fresh work never evicts the already charged tuples.
        creation(owner, create)
        creation(owner, reply)
        editing(owner, edit)
        deletion(owner, delete)
    }

    @Test
    fun `real TEST ingress enforces one mixed sixty per hour edit-delete allowance and fixed key owners cannot rotate`() {
        val fixture = BoundTestComplaintConsumerFixture()
        val owner = fixture.configuration(settings = boundConsumerTestSettings(ownerCreateMemberLimit = 128))
        val actor = fixture.actor(1)
        val first = ComplaintOwnerDeleteTuple(actor, UUID.randomUUID(), UUID.randomUUID(), ByteArray(32) { 43 })
        deletion(owner, first)
        repeat(59) { index ->
            val key = UUID.randomUUID()
            if (index % 2 == 0) editing(owner, ComplaintOwnerEditTuple(actor, key, first.targetId, first.fingerprintBytes()))
            else deletion(owner, ComplaintOwnerDeleteTuple(actor, key, first.targetId, first.fingerprintBytes()))
        }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            deletion(owner, ComplaintOwnerDeleteTuple(actor, UUID.randomUUID(), first.targetId, first.fingerprintBytes()))
        }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            editing(owner, ComplaintOwnerEditTuple(actor, UUID.randomUUID(), first.targetId, first.fingerprintBytes()))
        }
        deletion(owner, first)
        deletion(owner, ComplaintOwnerDeleteTuple(fixture.actor(2), UUID.randomUUID(), first.targetId, first.fingerprintBytes()))
        val key = admissionTestKey(210)
        try {
            admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { owner.ingressAdmission.rotate(key) }
            admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { owner.ingressAdmission.retirePrevious() }
        } finally {
            key.destroy()
        }
        deletion(owner, first)
    }

    @Test
    fun `explicit enrollment and shared create-reply global quotas drive the actual guard without changing P`() {
        val fixture = BoundTestComplaintConsumerFixture()
        val originalP = fixture.base.capacity.canonicalBytes()
        for (quota in 1..2) {
            val owner = fixture.configuration(settings = boundConsumerTestSettings(enrollmentGlobal = quota, createGlobal = quota))
            assertSame(fixture.base.capacity, owner.capacityPolicy)
            assertArrayEquals(originalP, owner.capacityPolicy.canonicalBytes())
            repeat(quota) { number ->
                owner.ingressAdmission.withIngress(historyTestRequest(ip = "203.0.113.${number + 1}")) { context ->
                    owner.ingressAdmission.chargeEnrollment(context, Any())
                }
                creation(owner, creationTuple(fixture.actor(number + 1), reply = number % 2 == 1))
            }
            admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
                owner.ingressAdmission.withIngress(historyTestRequest(ip = "203.0.113.10")) { owner.ingressAdmission.chargeEnrollment(it, Any()) }
            }
            admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { creation(owner, creationTuple(fixture.actor(10), reply = true)) }
        }
    }

    @Test
    fun `actual TEST routing forbids cross-family effective HMAC reuse including retained keys and normalized long keys`() {
        val fixture = BoundTestComplaintConsumerFixture()
        for (route in fixture.routingSecrets) {
            val admission = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-z", 201, effectiveAlias(route))
            assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(current = admission)) }
            val cursor = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-z", 202, effectiveAlias(route))
            assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(cursors = listOf(cursor))) }
        }
        val retained = fixture.routingSecrets.last()
        for (source in listOf(fixture.base.userSecret, fixture.base.installationSecrets.last(), fixture.base.previous, fixture.base.cursorSecrets.last())) {
            val replacement = fixture.base.acquired(
                SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING, retained.descriptor.logicalKeyId, 203, effectiveAlias(source), retained.descriptor.version,
            )
            val routing = TestOwnerDeleteJournalRoutingV1.fromAcquired(fixture.journal, fixture.routingSecrets.map { if (it === retained) replacement else it })
            assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(routing = routing)) }
        }
        val sources = listOf(fixture.base.userSecret, fixture.base.installationSecrets.last(), fixture.base.cursorSecrets.last())
        for (source in sources) {
            val admission = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-z", 204, effectiveAlias(source))
            assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(current = admission)) }
        }
        for (source in listOf(fixture.base.userSecret, fixture.base.installationSecrets.last(), fixture.base.previous)) {
            val cursor = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-z", 205, effectiveAlias(source))
            assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(cursors = listOf(cursor))) }
        }
        val repeatedPrevious = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-a", 205, effectiveAlias(fixture.base.current))
        assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(previous = repeatedPrevious)) }
        val repeatedCursor = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-a", 206, effectiveAlias(fixture.base.cursorSecrets.first()))
        assertThrows<IllegalArgumentException> {
            fixture.configuration(keys = fixture.inputs(cursors = listOf(fixture.base.cursorSecrets.first(), repeatedCursor)))
        }
        fixture.configuration() // A refusal must not consume or damage the acquired inputs.
    }

    @Test
    fun `equal declarations cannot replace TEST J and secret versions settings or non-acquired JWT owners fail closed`() {
        val fixture = BoundTestComplaintConsumerFixture()
        assertThrows<IllegalArgumentException> { fixture.configuration(journal = TestOwnerDeleteJournalConfigurationV1.of(fixture.journal.declaration())) }
        val aliases = listOf(fixture.base.userSecret, fixture.base.installationSecrets.last(), fixture.base.cursorSecrets.last(), fixture.routingSecrets.last())
        for (source in aliases) {
            val admission = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-z", 201, version = source.descriptor.version)
            assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(current = admission)) }
        }
        val cursor = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-z", 202, version = fixture.base.userSecret.descriptor.version)
        assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(cursors = listOf(cursor))) }
        assertThrows<IllegalArgumentException> { fixture.inputs(current = fixture.base.userSecret) }
        assertThrows<IllegalArgumentException> { fixture.inputs(cursors = emptyList()) }
        assertThrows<IllegalArgumentException> { fixture.inputs(cursors = List(9) { fixture.base.cursorSecrets.first() }) }
        assertThrows<IllegalArgumentException> { fixture.inputs(previous = fixture.base.current) }
        assertThrows<IllegalArgumentException> { fixture.inputs(activeCursor = "missing") }
        val declaredJwt = VersionBoundInstallationJwtConfiguration.fromAcquired(
            "installation-z", fixture.base.installationSecrets, "issuer", "audience", listOf(fixture.base.userSecret),
        )
        assertThrows<IllegalArgumentException> { fixture.configuration(jwt = declaredJwt) }
        val invalidSettings = listOf(
            boundConsumerTestSettings(coordinationMode = "redis"), boundConsumerTestSettings(declaredInstances = 2),
            boundConsumerTestSettings(enrollmentGlobal = 0), boundConsumerTestSettings(createGlobal = 0),
            boundConsumerTestSettings(ingressRate = 121), boundConsumerTestSettings(ownerCreateMemberLimit = 1),
            boundConsumerTestSettings(ownerCreatePruneBatch = 129),
        )
        invalidSettings.forEach { assertThrows<IllegalArgumentException> { fixture.configuration(settings = it) } }
        for (size in listOf(31, 129)) {
            val tooShortOrLong = fixture.base.acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-z", 203, ByteArray(size))
            assertThrows<IllegalArgumentException> { fixture.configuration(keys = fixture.inputs(current = tooShortOrLong)) }
        }
    }

    private fun creation(owner: VersionBoundTestComplaintConsumerConfigurationV1, tuple: ComplaintOwnerOperationTuple) =
        owner.ingressAdmission.withIngress(historyTestRequest()) { context ->
            if (tuple.operation == ComplaintOwnerCreationOperation.OWNER_REPLY) {
                owner.ingressAdmission.startOwnerReply(context)
                owner.ingressAdmission.admitOwnerReply(context, tuple)
            } else {
                owner.ingressAdmission.startOwnerCreate(context)
                owner.ingressAdmission.admitOwnerCreate(context, tuple)
            }
        }

    private fun editing(owner: VersionBoundTestComplaintConsumerConfigurationV1, tuple: ComplaintOwnerEditTuple) =
        owner.ingressAdmission.withIngress(historyTestRequest()) { context ->
            owner.ingressAdmission.startOwnerEdit(context)
            owner.ingressAdmission.admitOwnerEdit(context, tuple)
        }

    private fun deletion(owner: VersionBoundTestComplaintConsumerConfigurationV1, tuple: ComplaintOwnerDeleteTuple) =
        owner.ingressAdmission.withIngress(historyTestRequest()) { context ->
            owner.ingressAdmission.startOwnerDelete(context)
            owner.ingressAdmission.admitOwnerDelete(context, tuple)
        }

    private fun creationTuple(actor: ScopedInstallationId, reply: Boolean = false): ComplaintOwnerOperationTuple = ComplaintOwnerOperationTuple(
        actor, UUID.randomUUID(), if (reply) ComplaintOwnerCreationOperation.OWNER_REPLY else ComplaintOwnerCreationOperation.OWNER_CREATE,
        List(if (reply) 2 else 1) { UUID.randomUUID() }, ByteArray(32) { 43 },
    )

    private fun ledger(policy: ComplaintCapacityPolicyV1) = ComplaintCapacityLedger(
        ComplaintCapacityConfiguration.of(policy.digestBytes(), false), ComplaintCapacityBalance(policy.hardLimit, policy.creationLimit, policy.hardLimit),
    )

    private fun effectiveAlias(secret: AcquiredVersionedSecret): ByteArray = secret.useMaterial {
        if (it.size > 64) MessageDigest.getInstance("SHA-256").digest(it) else it.copyOf(64)
    }

    /** Comparison input only: no SQL preflight, registered namespace or reusable entry capability. */
    private fun deleteAllTuple(actor: ScopedInstallationId): InstallationDeletionPreflightTuple {
        val candidate = InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(actor, ByteArray(32) { 7 }), 1, UUID.randomUUID())
        return object : InstallationDeletionPreflightTuple {
            override val installation = actor
            override val submittedCredentialVersion = 1L
            override val operationKey = candidate.operationKey
            override val fingerprint = ComplaintDeleteAllFingerprint.of(candidate)
        }
    }
}

/** Small composition of existing synthetic acquired/P/TEST-J fixtures; never a provider or persistence harness. */
internal class BoundTestComplaintConsumerFixture(val journal: TestOwnerDeleteJournalConfigurationV1 = fullTestJournal()) {
    val base = BoundComplaintConsumerFixture()
    val routingSecrets = journal.declaration().routing.keys.mapIndexed { index, key ->
        base.acquired(
            SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING, key.keyId, 121 + index, admissionTestBytes(121 + index, if (index == 0) 128 else 32), key.secret,
        )
    }.toMutableList()
    val routing = TestOwnerDeleteJournalRoutingV1.fromAcquired(journal, routingSecrets)

    fun inputs(
        current: AcquiredVersionedSecret = base.current,
        previous: AcquiredVersionedSecret? = base.previous,
        activeCursor: String = "cursor-z",
        cursors: List<AcquiredVersionedSecret> = base.cursorSecrets,
        routing: TestOwnerDeleteJournalRoutingV1 = this.routing,
    ): VersionBoundTestComplaintConsumerInputsV1 = VersionBoundTestComplaintConsumerInputsV1(current, previous, activeCursor, cursors, routing)

    fun configuration(
        keys: VersionBoundTestComplaintConsumerInputsV1 = inputs(),
        settings: VersionBoundComplaintConsumerSettings = boundConsumerTestSettings(),
        jwt: VersionBoundInstallationJwtConfiguration = base.jwt,
        capacity: ComplaintCapacityPolicyV1 = base.capacity,
        journal: TestOwnerDeleteJournalConfigurationV1 = this.journal,
    ): VersionBoundTestComplaintConsumerConfigurationV1 = VersionBoundTestComplaintConsumerConfigurationV1.fromAcquired(jwt, capacity, journal, keys, settings)

    fun actor(number: Int): ScopedInstallationId = ScopedInstallationId(admissionTestActor(number).id, journal.scope)
}

/** TEST keeps the independent historical registry's writer lineage, not its LIVE scope or role authority. */
internal fun fullTestJournal(): TestOwnerDeleteJournalConfigurationV1 = TestOwnerDeleteJournalConfigurationV1.of(
    ownerDeleteTestJournal().declaration().copy(writer = InitialLiveJournalTestFixture.declaration().writer),
)
