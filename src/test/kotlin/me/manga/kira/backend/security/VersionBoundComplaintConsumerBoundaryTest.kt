package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest

class VersionBoundComplaintConsumerBoundaryTest {
    @Test
    fun `fixed ingress refuses rotation and retirement even after retention without replacing its quota state`() {
        for (overlap in listOf(false, true)) {
            val current = admissionTestKey(1)
            val previous = if (overlap) admissionTestKey(2) else null
            val fixed = ComplaintAdmissionKeyConfiguration.fixed(current, previous, listOf(admissionTestForbidden()))
            current.destroy()
            previous?.destroy()
            val clock = MutableAdmissionTestClock()
            val guard = ComplaintIngressAdmission(
                ClientIpResolver(KiraSecurityProperties()),
                admissionTestPolicy(ingressRate = 1, ingressBuckets = if (overlap) 2 else 1),
                fixed,
                clock,
            )
            val candidate = admissionTestKey(3)
            try {
                for (now in listOf(0L, ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS + 1)) {
                    clock.value = now
                    guard.withIngress(historyTestRequest()) {}
                    admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.rotate(candidate) }
                    admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.retirePrevious() }
                    admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { guard.withIngress(historyTestRequest()) {} }
                    // Each selected generation still consumes its physical bucket: no hidden retirement.
                    admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { guard.withIngress(historyTestRequest(ip = "192.0.2.2")) {} }
                }
            } finally {
                candidate.destroy()
            }
        }
    }

    @Test
    fun `actual acquired owner refuses both key mutations and preserves descriptors and already charged ingress`() {
        val fixture = BoundComplaintConsumerFixture()
        for (previous in listOf(null, fixture.previous)) {
            val owner = fixture.configuration(fixture.inputs(previous = previous), boundConsumerTestSettings(ingressRate = 1))
            val descriptors = owner.descriptors()
            owner.ingressAdmission.withIngress(historyTestRequest()) { context ->
                val identity = Any()
                owner.ingressAdmission.startSession(context)
                owner.ingressAdmission.chargeSession(context, admissionTestActor(1), identity)
                owner.ingressAdmission.consumeSession(context, identity)
            }
            val candidate = admissionTestKey(80)
            try {
                admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { owner.ingressAdmission.rotate(candidate) }
                admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { owner.ingressAdmission.retirePrevious() }
            } finally {
                candidate.destroy()
            }
            assertEquals(descriptors, owner.descriptors())
            assertEquals(fixture.current.descriptor.logicalKeyId, owner.admissionCurrentKeyId)
            assertEquals(previous?.descriptor?.logicalKeyId, owner.admissionPreviousKeyId)
            admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { owner.ingressAdmission.withIngress(historyTestRequest()) {} }
        }
    }

    @Test
    fun `missing foreign duplicate selections repeated full version references and declaration only JWT owners fail closed`() {
        val fixture = BoundComplaintConsumerFixture()
        invalid { fixture.inputs(cursors = emptyList()) }
        invalid { fixture.inputs(cursors = List(9) { fixture.cursorSecrets.first() }) }
        invalid { fixture.inputs(current = fixture.userSecret) }
        invalid { fixture.inputs(cursors = listOf(fixture.current)) }
        invalid { fixture.inputs(activeCursor = "missing") }
        invalid { fixture.inputs(previous = fixture.current) }
        invalid { fixture.inputs(cursors = listOf(fixture.cursorSecrets.first(), fixture.cursorSecrets.first())) }
        val database = fixture.acquired(
            SecretMaterialFamily.DATABASE,
            "database",
            201,
            purpose = SecretMaterialPurpose.AUTHENTICATION_PASSWORD,
        )
        invalid { fixture.inputs(current = database) }

        // Different material cannot turn one immutable provider reference into multiple keys or families.
        val reusedUserVersion = fixture.acquired(
            SecretMaterialFamily.COMPLAINT_ADMISSION,
            "admission-z",
            202,
            version = fixture.userSecret.descriptor.version,
        )
        invalid { fixture.configuration(fixture.inputs(current = reusedUserVersion)) }
        val reusedJournalVersion = fixture.acquired(
            SecretMaterialFamily.COMPLAINT_CURSOR,
            "cursor-z",
            203,
            version = fixture.journalSecrets.first().descriptor.version,
        )
        invalid { fixture.configuration(fixture.inputs(cursors = listOf(reusedJournalVersion))) }
        val declarationOnlyJwt = VersionBoundInstallationJwtConfiguration.fromAcquired(
            "installation-z",
            fixture.installationSecrets,
            "user-issuer",
            "user-audience",
            listOf(fixture.userSecret),
        )
        invalid { fixture.configuration(jwt = declarationOnlyJwt) }
        for (size in listOf(31, 129)) {
            invalid {
                val admission = fixture.acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-z", 204, ByteArray(size))
                fixture.configuration(fixture.inputs(current = admission))
            }
            invalid {
                val cursor = fixture.acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-z", 205, ByteArray(size))
                fixture.configuration(fixture.inputs(cursors = listOf(cursor)))
            }
        }
    }

    @Test
    fun `all actual retained HMAC families reject effective key reuse across distinct immutable versions`() {
        val fixture = BoundComplaintConsumerFixture()
        val admissionForbidden = listOf(fixture.userSecret, fixture.installationSecrets.last(), fixture.cursorSecrets.last(), fixture.journalSecrets.first())
        for (source in admissionForbidden) {
            val reused = fixture.acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-z", 201, alias(source))
            invalid { fixture.configuration(fixture.inputs(current = reused)) }
        }
        val cursorForbidden = listOf(fixture.userSecret, fixture.installationSecrets.last(), fixture.previous, fixture.journalSecrets.first())
        for (source in cursorForbidden) {
            val reused = fixture.acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-z", 202, alias(source))
            invalid { fixture.configuration(fixture.inputs(cursors = listOf(reused, fixture.cursorSecrets.last()))) }
        }
        // The mandatory journal owner must also be separated from the actual user and every retained JWT key.
        val retainedJournal = fixture.journalSecrets.first()
        for (source in listOf(fixture.userSecret, fixture.installationSecrets.last())) {
            val reused = fixture.acquired(
                SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING,
                retainedJournal.descriptor.logicalKeyId,
                203,
                alias(source),
                retainedJournal.descriptor.version,
            )
            val routing = VersionBoundComplaintJournalRouting.fromAcquired(fixture.journal, listOf(reused, fixture.journalSecrets.last()))
            invalid { fixture.configuration(fixture.inputs(routing = routing)) }
        }
        val repeatedAdmission = fixture.current.useMaterial {
            fixture.acquired(SecretMaterialFamily.COMPLAINT_ADMISSION, "admission-a", 204, it.copyOf())
        }
        invalid { fixture.configuration(fixture.inputs(previous = repeatedAdmission)) }
        val repeatedCursor = fixture.acquired(SecretMaterialFamily.COMPLAINT_CURSOR, "cursor-a", 205, alias(fixture.cursorSecrets.first()))
        invalid { fixture.configuration(fixture.inputs(cursors = listOf(fixture.cursorSecrets.first(), repeatedCursor))) }
        fixture.configuration() // Refusal and temporary cleanup never consume or mutate the original acquired graph.
    }

    @Test
    fun `journal active and retained declarations cannot substitute the actual J owner and quotas cannot bypass P or memory bounds`() {
        val fixture = BoundComplaintConsumerFixture()
        val declaration = fixture.journal.declaration()
        val activeChanged = ComplaintJournalConfigurationV1.of(declaration.copy(routing = declaration.routing.copy(activeKeyId = "route-a")))
        val retainedRemoved = ComplaintJournalConfigurationV1.of(
            declaration.copy(routing = declaration.routing.copy(keys = declaration.routing.keys.filter { it.keyId == "route-b" })),
        )
        val retained = declaration.routing.keys.first()
        val otherVersion = ImmutableSecretVersion.awsSecretsManager(retained.secret.resourceArn, "69000000-0000-4000-8000-000000000001")
        val changedKeys = declaration.routing.keys.map { key -> if (key.keyId == retained.keyId) key.copy(secret = otherVersion) else key }
        val versionChanged = ComplaintJournalConfigurationV1.of(
            declaration.copy(routing = declaration.routing.copy(keys = changedKeys)),
        )
        for (differentJ in listOf(activeChanged, retainedRemoved, versionChanged, ComplaintJournalConfigurationV1.of(declaration))) {
            invalid { fixture.configuration(journal = differentJ) }
        }
        val invalidSettings = listOf(
            boundConsumerTestSettings(enrollmentGlobal = 0),
            boundConsumerTestSettings(enrollmentGlobal = 100),
            boundConsumerTestSettings(createGlobal = 0),
            boundConsumerTestSettings(createGlobal = 18),
            boundConsumerTestSettings(coordinationMode = "redis"),
            boundConsumerTestSettings(declaredInstances = 2),
            boundConsumerTestSettings(ingressRate = 121),
        )
        invalidSettings.forEach { settings -> invalid { fixture.configuration(settings = settings) } }
    }

    private fun alias(acquired: AcquiredVersionedSecret): ByteArray = acquired.useMaterial {
        if (it.size > 64) MessageDigest.getInstance("SHA-256").digest(it) else it.copyOf(64)
    }

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows<IllegalArgumentException> { operation() }
        assertTrue(
            failure.message in setOf(INVALID_BOUND_COMPLAINT_CONSUMERS, INVALID_ADMISSION_CONFIGURATION, "Invalid owner cursor configuration."),
        )
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
