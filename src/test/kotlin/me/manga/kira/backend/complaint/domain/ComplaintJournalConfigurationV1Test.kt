package me.manga.kira.backend.complaint.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.InitialEventRoleV1
import me.manga.kira.backend.complaint.domain.catalog.InitialJournalLocationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.SecretVersionException
import me.manga.kira.backend.security.SecretVersionFailure
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.HexFormat

class ComplaintJournalConfigurationV1Test {
    @Test
    fun `golden bytes bind the closed initial LIVE profile and explicit framing encryption and storage rules`() {
        val journal = ComplaintJournalConfigurationV1.of(InitialLiveJournalTestFixture.declaration())
        val golden = requireNotNull(javaClass.getResourceAsStream("/fixtures/complaint-journal-configuration-v1/initial-live.json"))
            .use { it.readBytes() }
        assertEquals(5046, golden.size)
        assertArrayEquals(golden, journal.canonicalBytes())
        assertEquals(GOLDEN_SHA256, journal.sha256)
        assertArrayEquals(HexFormat.of().parseHex(GOLDEN_SHA256), journal.digestBytes())
        val document = Json.parseToJsonElement(journal.canonicalBytes().toString(Charsets.UTF_8)).jsonObject
        assertEquals(
            setOf(
                "kind", "schemaVersion", "canonicalizerId", "profile", "scope", "writer", "journalLocation",
                "ordinaryPrefix", "sealTerminalPrefix", "authorities", "routing", "encryption", "recovery", "limits", "protocol",
            ),
            document.keys,
        )
        assertEquals("INITIAL_LIVE", document.getValue("profile").jsonPrimitive.content)
        val protocol = document.getValue("protocol").jsonObject
        assertEquals(setOf("payloadSchemaVersion", "envelopeSchemaVersion", "routingAlgorithm", "framing", "encryption", "storage"), protocol.keys)
        val framing = protocol.getValue("framing").jsonObject
        val domains = listOf("routingDomain", "eventIdDomain", "epochSealDomain", "aadDomain", "kmsContextDomain").map { framing.getValue(it) }
        assertEquals(5, domains.distinct().size)
        assertEquals("32", protocol.getValue("encryption").jsonObject.getValue("dataKeyBytes").jsonPrimitive.content)
        assertEquals("false", protocol.getValue("storage").jsonObject.getValue("deleteMarkersAllowed").jsonPrimitive.content)
        assertEquals("false", protocol.getValue("storage").jsonObject.getValue("nativeExpirationAllowed").jsonPrimitive.content)
        assertEquals("ComplaintJournalConfigurationV1(INITIAL_LIVE,redacted,no-authority)", journal.toString())
    }

    @Test
    fun `identity authority key version recovery and every operational bound affect the commitment`() {
        val declared = InitialLiveJournalTestFixture.declaration()
        val digest = ComplaintJournalConfigurationV1.of(declared).sha256
        (bindingMutations(declared) + limitMutations(declared)).forEach { changed ->
            assertNotEquals(digest, ComplaintJournalConfigurationV1.of(changed).sha256)
        }
    }

    @Test
    fun `key order is canonical and caller collections and returned bytes cannot mutate a configuration`() {
        val declared = InitialLiveJournalTestFixture.declaration()
        val keys = declared.routing.keys.reversed().toMutableList()
        val journal = ComplaintJournalConfigurationV1.of(declared.copy(routing = declared.routing.copy(keys = keys)))
        val bytes = journal.canonicalBytes()
        val digest = journal.digestBytes()
        keys.clear()
        journal.canonicalBytes().fill(0)
        journal.digestBytes().fill(0)
        assertNotSame(journal.declaration().routing.keys, journal.declaration().routing.keys)
        assertEquals(listOf("route-a", "route-b"), journal.declaration().routing.keys.map { it.keyId })
        assertArrayEquals(bytes, journal.canonicalBytes())
        assertArrayEquals(digest, journal.digestBytes())
        assertEquals(GOLDEN_SHA256, journal.sha256)
    }

    @Test
    fun `routing requires one active exact immutable version and at most four distinct retained keys`() {
        val declared = InitialLiveJournalTestFixture.declaration()
        val routing = declared.routing
        val four = routing.keys + listOf(InitialLiveJournalTestFixture.routingKey("c", '9'), InitialLiveJournalTestFixture.routingKey("d", '0'))
        ComplaintJournalConfigurationV1.of(declared.copy(routing = routing.copy(keys = four)))
        val invalid = listOf(
            routing.copy(keys = emptyList()),
            routing.copy(keys = four + InitialLiveJournalTestFixture.routingKey("e", '1')),
            routing.copy(keys = routing.keys + routing.keys.first()),
            routing.copy(keys = routing.keys + routing.keys.first().copy(keyId = "alias")),
            routing.copy(activeKeyId = "unknown"),
            routing.copy(keys = listOf(routing.keys.first().copy(keyId = "bad/key"))),
            routing.copy(keys = listOf(routing.keys.first().copy(keyId = declared.encryption.keyId))),
            routing.copy(retentionSeconds = declared.limits.retention.ordinaryRetentionSeconds - 1),
            routing.copy(minimumRotationIntervalSeconds = routing.retentionSeconds / 3 - 1),
        )
        invalid.forEach { rejected(declared.copy(routing = it)) }
        val foreign = ImmutableSecretVersion.awsSecretsManager(
            routing.keys.first().secret.resourceArn.replace("us-east-1", "us-west-2"),
            routing.keys.first().secret.versionId,
        )
        rejected(declared.copy(routing = routing.copy(keys = listOf(routing.keys.first().copy(secret = foreign)), activeKeyId = "route-a")))
        listOf("AWSCURRENT", "latest", "44444444-4444-4444-4444-444444444444").forEach { version ->
            val failure = assertThrows<SecretVersionException> {
                ImmutableSecretVersion.awsSecretsManager(routing.keys.first().secret.resourceArn, version)
            }
            assertEquals(SecretVersionFailure.INVALID_REFERENCE, failure.code)
        }
    }

    @Test
    fun `invalid aliases conflicting roles domains policies and cross account queues or KMS keys are refused`() {
        val d = InitialLiveJournalTestFixture.declaration()
        val a = d.authorities
        val invalidAuthorities = listOf(
            a.copy(sealTerminal = a.ordinary),
            a.copy(recovery = a.recovery.copy(credentialId = a.ordinary.roleId)),
            a.copy(recovery = a.recovery.copy(policy = a.ordinary.policy)),
            a.copy(isolation = a.isolation.copy(bucketAdministratorId = a.isolation.deploymentPrincipalId)),
            a.copy(isolation = a.isolation.copy(kmsAdministratorId = a.ordinary.roleId)),
            a.copy(isolation = a.isolation.copy(journalFailureDomainId = a.isolation.backupFailureDomainId)),
            a.copy(ordinary = a.ordinary.copy(policy = a.ordinary.policy.copy(version = 0))),
            a.copy(ordinary = a.ordinary.copy(policy = a.ordinary.policy.copy(sha256 = "A".repeat(64)))),
        )
        invalidAuthorities.forEach { rejected(d.copy(authorities = it)) }
        rejected(d.copy(writer = d.writer.copy(generationId = "not-a-canonical-generation")))
        rejected(d.copy(journalLocation = d.journalLocation.copy(bucket = "bad..bucket")))
        listOf(
            "arn:aws:kms:us-east-1:123456789012:alias/live",
            d.encryption.keyArn.replace("us-east-1", "us-west-2"),
            d.encryption.keyArn.replace("123456789012", "123456789013"),
        ).forEach { rejected(d.copy(encryption = d.encryption.copy(keyArn = it))) }
        val queue = d.recovery.queue
        listOf(queue.arn + ".fifo", queue.arn.replace("123456789012", "123456789013")).forEach {
            rejected(d.copy(recovery = d.recovery.copy(queue = queue.copy(arn = it))))
        }
        rejected(d.copy(recovery = d.recovery.copy(deadLetterQueue = queue)))
        rejected(d.copy(recovery = d.recovery.copy(queue = queue.copy(encryption = d.encryption.copy(policy = queue.encryption.policy)))))
    }

    @Test
    fun `retention maxima deadline ceilings and decoder or capacity one over bounds fail closed without clamping`() {
        val d = InitialLiveJournalTestFixture.declaration()
        val l = d.limits
        rejected(d.copy(limits = l.copy(retention = l.retention.copy(ordinaryRetentionSeconds = 400L * 86_400 - 1))))
        rejected(d.copy(limits = l.copy(retention = l.retention.copy(maximumRestoreAgeSeconds = 369L * 86_400 + 1))))
        rejected(d.copy(limits = l.copy(retention = l.retention.copy(maximumRestoreAgeSeconds = -1))))
        val time = l.deadlines
        listOf(
            time.copy(publicationAttemptMillis = 5001), time.copy(s3CallMillis = 5001), time.copy(kmsCallMillis = 0),
            time.copy(epochRotationMillis = 10_001), time.copy(epochSealMillis = 600_001), time.copy(scanMillis = 600_001),
            time.copy(scanCadenceMillis = 900_001), time.copy(queueUnhealthyMillis = 30_001),
            time.copy(queueCallMillis = 30_001), time.copy(checkpointMaxAgeMillis = 1_200_001),
        ).forEach { rejected(d.copy(limits = l.copy(deadlines = it))) }
        val decoder = l.decoder
        listOf(
            decoder.copy(maximumEnvelopeBytes = 98_305),
            decoder.copy(maximumPlaintextBytes = 65_537),
            decoder.copy(maximumJsonDepth = 33),
            decoder.copy(maximumJsonTokens = 0),
            decoder.copy(maximumObjectFields = 65),
            decoder.copy(maximumStringUtf8Bytes = 65_537),
            decoder.copy(maximumWrappedKeyBytes = 98_305),
        ).forEach { rejected(d.copy(limits = l.copy(decoder = it))) }
        val capacity = l.capacity
        listOf(
            capacity.copy(maximumPublicationLanes = 1),
            capacity.copy(routinePublicationLanes = 4),
            capacity.copy(maximumRetainedVersions = 0),
            capacity.copy(maximumScanStagingBytes = -1),
        ).forEach { rejected(d.copy(limits = l.copy(capacity = it))) }
        val maximum = d.copy(
            routing = d.routing.copy(retentionSeconds = Long.MAX_VALUE, minimumRotationIntervalSeconds = Long.MAX_VALUE),
            limits = l.copy(retention = JournalRetentionV1(Long.MAX_VALUE, Long.MAX_VALUE - 31L * 86_400)),
        )
        val document = Json.parseToJsonElement(ComplaintJournalConfigurationV1.of(maximum).canonicalBytes().toString(Charsets.UTF_8)).jsonObject
        assertEquals(
            Long.MAX_VALUE.toString(),
            document.getValue("limits").jsonObject.getValue("retention").jsonObject.getValue("ordinaryRetentionSeconds").jsonPrimitive.content,
        )
    }

    private fun bindingMutations(d: InitialLiveJournalDeclarationV1): List<InitialLiveJournalDeclarationV1> {
        val a = d.authorities
        val routing = d.routing
        val key = routing.keys.first()
        val differentVersion = ImmutableSecretVersion.awsSecretsManager(key.secret.resourceArn, InitialLiveJournalTestFixture.uuid('9'))
        return listOf(
            d.copy(writer = d.writer.copy(databaseIdentity = InitialLiveJournalTestFixture.uuid('9'))),
            d.copy(writer = d.writer.copy(restoreIdentity = InitialLiveJournalTestFixture.uuid('9'))),
            d.copy(writer = d.writer.copy(generationId = InitialLiveJournalTestFixture.uuid('9'))),
            d.copy(journalLocation = d.journalLocation.copy(bucket = "kira-journal-another")),
            d.copy(authorities = a.copy(ordinary = a.ordinary.copy(credentialId = "new-credentials"))),
            d.copy(authorities = a.copy(ordinary = a.ordinary.copy(policy = a.ordinary.policy.copy(version = 2)))),
            d.copy(authorities = a.copy(sealTerminal = a.sealTerminal.copy(policy = a.sealTerminal.policy.copy(sha256 = "f".repeat(64))))),
            d.copy(authorities = a.copy(recovery = a.recovery.copy(roleId = "new-recovery"))),
            d.copy(authorities = a.copy(isolation = a.isolation.copy(deploymentPrincipalId = "new-deploy"))),
            d.copy(authorities = a.copy(isolation = a.isolation.copy(journalFailureDomainId = "new-journal-domain"))),
            d.copy(routing = routing.copy(activeKeyId = "route-a")),
            d.copy(routing = routing.copy(keys = listOf(key.copy(secret = differentVersion), routing.keys.last()))),
            d.copy(routing = routing.copy(retentionSeconds = routing.retentionSeconds + 1)),
            d.copy(routing = routing.copy(minimumRotationIntervalSeconds = routing.minimumRotationIntervalSeconds + 1)),
            d.copy(
                encryption = d.encryption.copy(
                    keyArn = d.encryption.keyArn.replace(InitialLiveJournalTestFixture.uuid('6'), InitialLiveJournalTestFixture.uuid('9')),
                ),
            ),
            d.copy(encryption = d.encryption.copy(policy = d.encryption.policy.copy(version = 2))),
            d.copy(recovery = d.recovery.copy(queue = d.recovery.queue.copy(arn = d.recovery.queue.arn + "-other"))),
            d.copy(recovery = d.recovery.copy(queue = d.recovery.queue.copy(policy = d.recovery.queue.policy.copy(version = 2)))),
            d.copy(recovery = d.recovery.copy(deadLetterQueue = d.recovery.deadLetterQueue.copy(arn = d.recovery.deadLetterQueue.arn + "-other"))),
        )
    }

    private fun limitMutations(d: InitialLiveJournalDeclarationV1): List<InitialLiveJournalDeclarationV1> {
        val l = d.limits
        val t = l.deadlines
        val c = l.capacity
        val b = l.decoder
        return listOf(
            l.copy(retention = l.retention.copy(ordinaryRetentionSeconds = l.retention.ordinaryRetentionSeconds + 1)),
            l.copy(retention = l.retention.copy(maximumRestoreAgeSeconds = l.retention.maximumRestoreAgeSeconds + 1)),
            l.copy(deadlines = t.copy(s3CallMillis = 1501)), l.copy(deadlines = t.copy(kmsCallMillis = 1001)),
            l.copy(deadlines = t.copy(queueCallMillis = 25_001)), l.copy(deadlines = t.copy(publicationAttemptMillis = 4999)),
            l.copy(deadlines = t.copy(epochRotationMillis = 9999)), l.copy(deadlines = t.copy(epochSealMillis = 29_999)),
            l.copy(deadlines = t.copy(scanMillis = 599_999)), l.copy(deadlines = t.copy(scanCadenceMillis = 899_999)),
            l.copy(deadlines = t.copy(queueUnhealthyMillis = 29_999)), l.copy(deadlines = t.copy(checkpointMaxAgeMillis = 1_199_999)),
            l.copy(capacity = c.copy(maximumPublicationLanes = 5)), l.copy(capacity = c.copy(routinePublicationLanes = 2)),
            l.copy(capacity = c.copy(maximumRetainedVersions = 1_000_001)), l.copy(capacity = c.copy(maximumScanStagingBytes = 268_435_457)),
            l.copy(decoder = b.copy(maximumEnvelopeBytes = 98_303)), l.copy(decoder = b.copy(maximumPlaintextBytes = 65_535)),
            l.copy(decoder = b.copy(maximumJsonDepth = 17)), l.copy(decoder = b.copy(maximumJsonTokens = 4097)),
            l.copy(decoder = b.copy(maximumObjectFields = 33)), l.copy(decoder = b.copy(maximumStringUtf8Bytes = 4097)),
            l.copy(decoder = b.copy(maximumWrappedKeyBytes = 6145)),
        ).map { d.copy(limits = it) }
    }

    private fun rejected(declared: InitialLiveJournalDeclarationV1) {
        val error = assertThrows<IllegalArgumentException> { ComplaintJournalConfigurationV1.of(declared) }
        assertEquals("Invalid initial LIVE journal configuration", error.message)
    }

    companion object {
        private const val GOLDEN_SHA256 = "e1562c3f07c5983ee40f2212c601782dc3bfdc003ee1bf7f7c01b10951ad64f0"
    }
}

/** Synthetic public references only. No secret material, resolver, provider SDK or observed deployment state. */
internal object InitialLiveJournalTestFixture {
    fun declaration(): InitialLiveJournalDeclarationV1 = InitialLiveJournalDeclarationV1(
        writer = JournalWriterV1(uuid('1'), uuid('2'), uuid('3')),
        journalLocation = InitialJournalLocationV1("kira-journal-fixture", "123456789012", "us-east-1"),
        authorities = JournalAuthoritiesV1(
            role("ordinary"),
            role("seal"),
            role("recovery"),
            JournalIsolationV1(
                "deployment",
                "bucket-admin",
                "kms-admin",
                "journal-domain",
                "application-domain",
                "backup-domain",
                policy("administration"),
            ),
        ),
        routing = JournalRoutingV1("route-b", listOf(routingKey("a", '4'), routingKey("b", '5')), 450L * 86_400, 180L * 86_400),
        encryption = kms("journal", '6'),
        recovery = JournalRecoveryV1(
            JournalQueueV1("arn:aws:sqs:us-east-1:123456789012:journal-events", policy("queue"), kms("queue", '7')),
            JournalQueueV1("arn:aws:sqs:us-east-1:123456789012:journal-dlq", policy("dlq"), kms("dlq", '8')),
        ),
        limits = JournalLimitsV1(
            JournalRetentionV1(400L * 86_400, 365L * 86_400),
            JournalDeadlinesV1(1500, 1000, 25_000, 5000, 10_000, 30_000, 600_000, 900_000, 30_000, 1_200_000),
            JournalRemoteCapacityV1(4, 3, 1_000_000, 268_435_456),
            JournalDecoderLimitsV1(98_304, 65_536, 16, 4096, 32, 4096, 6144),
        ),
    )

    fun routingKey(name: String, version: Char): JournalRoutingKeyV1 = JournalRoutingKeyV1(
        "route-$name",
        ImmutableSecretVersion.awsSecretsManager("arn:aws:secretsmanager:us-east-1:123456789012:secret:journal-routing-$name-ABC123", uuid(version)),
    )

    fun uuid(digit: Char): String {
        val d = digit.toString()
        return "${d.repeat(8)}-${d.repeat(4)}-4${d.repeat(3)}-8${d.repeat(3)}-${d.repeat(12)}"
    }

    private fun policy(name: String): InitialPolicyReferenceV1 = InitialPolicyReferenceV1("$name-policy", 1L, Sha256.hexUtf8("synthetic-$name-policy"))

    private fun role(name: String): InitialEventRoleV1 = InitialEventRoleV1("$name-role", "$name-credentials", policy(name))

    private fun kms(name: String, digit: Char): JournalKmsKeyV1 =
        JournalKmsKeyV1("$name-kms", "arn:aws:kms:us-east-1:123456789012:key/${uuid(digit)}", policy("$name-kms"))
}
