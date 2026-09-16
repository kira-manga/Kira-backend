package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineBootstrapRegistry
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveRangeV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineBootstrapRegistryVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class OfflineBootstrapRegistryVerifierTest {
    private val fixture = OfflineTrustBundleFixture
    private val registry = fixture.registry()
    private val range get() = registry.eventWriter.liveRange
    private val registryBytes get() = fixture.registryBytes(registry)
    private val bundleBytes by lazy { fixture.bytes(fixture.signedForRegistry(registry)) }
    private val roleIds = listOf(
        registry.catalogWriter.putAuthority.principalId,
        registry.catalogWriter.signAuthority.principalId,
        range.ordinaryAuthority.roleId,
        range.sealTerminalAuthority.roleId,
    )
    private val policies = listOf(
        registry.catalogWriter.putAuthority.policy,
        registry.catalogWriter.signAuthority.policy,
        range.ordinaryAuthority.policy,
        range.sealTerminalAuthority.policy,
    )

    @Test
    fun `real rooted bundle binds the exact canonical typed registry and returns claims only`() {
        val checked = OfflineBootstrapRegistryVerifier.verify(registryBytes, bundleBytes, fixture.policy())
        assertEquals(registry, checked.registry)
        assertArrayEquals(registryBytes, checked.canonicalRegistryBytes)
        assertEquals(Sha256.hex(registryBytes), checked.registrySha256)
        assertEquals(Sha256.hex(bundleBytes), checked.trustBundleEnvelopeSha256)
        assertEquals(7L, checked.trustBundleVersion)
        assertEquals("CheckedOfflineBootstrapRegistry(no-live-authority)", checked.toString())
    }

    @Test
    fun `registry API checks the independent root policy and current bundle floor itself`() {
        rejectBytes(registryBytes, OfflineTrustBundleFailure.INVALID_SIGNATURE, fixture.policy(rootSpki = fixture.secondSigner.public.encoded))
        rejectBytes(registryBytes, OfflineTrustBundleFailure.INVALID_POLICY, fixture.policy(rootFingerprint = "0".repeat(64)))
        rejectBytes(registryBytes, OfflineTrustBundleFailure.VERSION_ROLLBACK, fixture.policy(minimumVersion = 8))
        rejectBytes(registryBytes, OfflineTrustBundleFailure.POLICY_MISMATCH, fixture.policy(environment = "another-environment"))
    }

    @Test
    fun `neither a valid old key-only bundle nor unsigned bootstrap changes grant registry authority`() {
        val oldRoot = Base64.getDecoder().decode(fixture.golden("root-spki.base64").decodeToString().trim())
        rejectBytes(
            registryBytes,
            OfflineTrustBundleFailure.MALFORMED_INPUT,
            fixture.policy(rootSpki = oldRoot),
            fixture.golden("envelope.json"),
        )
        val signed = fixture.signedForRegistry(registry)
        val authority = signed.body.bootstrapAuthority.copy(catalogApproverIds = signed.body.bootstrapAuthority.catalogApproverIds.reversed())
        val tampered = signed.copy(body = signed.body.copy(bootstrapAuthority = authority))
        rejectBytes(registryBytes, OfflineTrustBundleFailure.INVALID_SIGNATURE, trustBundleBytes = fixture.bytes(tampered))
    }

    @Test
    fun `canonical registry changes without root rebinding fail the authenticated hash`() {
        val changed = withRange(range.copy(configurationSha256 = Sha256.hexUtf8("another-synthetic-config")))
        rejectBytes(fixture.registryBytes(changed), OfflineTrustBundleFailure.REGISTRY_HASH_MISMATCH)
    }

    @Test
    fun `root-bound registry still rejects unsupported schema and canonicalizer`() {
        rejectRegistry(registry.copy(schemaVersion = 2), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectRegistry(registry.copy(canonicalizerId = "generic-json"), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
    }

    @Test
    fun `database and restore identities require canonical variant UUIDv4 even when both claims match`() {
        val invalid = listOf(
            "",
            "00000000-0000-0000-0000-000000000000",
            fixture.DATABASE_ID.replace("-4333-", "-3333-"),
            fixture.DATABASE_ID.replace("-8333-", "-7333-"),
            "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
            fixture.DATABASE_ID.replace("-", ""),
            " " + fixture.DATABASE_ID,
        )
        invalid.forEach { value ->
            rejectRegistry(registry.copy(databaseIdentity = value, eventWriter = registry.eventWriter.copy(databaseIdentity = value)))
            rejectRegistry(registry.copy(restoreIdentity = value, eventWriter = registry.eventWriter.copy(restoreIdentity = value)))
        }
    }

    @Test
    fun `writer identities match rooted authority and each other without inferring freshness`() {
        val catalog = registry.catalogWriter
        val event = registry.eventWriter
        rejectRegistry(registry.copy(catalogWriter = catalog.copy(generationId = fixture.EVENT_WRITER)))
        rejectRegistry(registry.copy(eventWriter = event.copy(databaseIdentity = fixture.CATALOG_WRITER)))
        rejectRegistry(registry.copy(eventWriter = event.copy(restoreIdentity = fixture.CATALOG_WRITER)))
        listOf(
            fixture.CATALOG_WRITER,
            "00000000-0000-0000-0000-000000000000",
            fixture.EVENT_WRITER.replace("-4222-", "-3222-"),
            fixture.EVENT_WRITER.replace("-8222-", "-7222-"),
            "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
        ).forEach { generation ->
            val matchingPrefixes = range.copy(
                ordinaryPrefix = range.ordinaryPrefix.replace(fixture.EVENT_WRITER, generation),
                sealTerminalPrefix = range.sealTerminalPrefix.replace(fixture.EVENT_WRITER, generation),
            )
            rejectRegistry(registry.copy(eventWriter = event.copy(generationId = generation, liveRange = matchingPrefixes)))
        }
        listOf("", "active", "CANDIDATE", "RETIRED").forEach { state ->
            rejectRegistry(registry.copy(catalogWriter = catalog.copy(registration = state)))
            rejectRegistry(registry.copy(eventWriter = event.copy(registration = state)))
        }
    }

    @Test
    fun `initial signer policy is exactly one rooted tuple not any available bundle key`() {
        val signerPolicy = registry.catalogWriter.requiredSignerPolicy
        val member = signerPolicy.members.single()
        val other = member.copy(keyId = "catalog-new")
        listOf(
            signerPolicy.copy(mode = "ROTATING"),
            signerPolicy.copy(threshold = "ANY_MEMBER"),
            signerPolicy.copy(members = emptyList()),
            signerPolicy.copy(members = listOf(member, other)),
            signerPolicy.copy(members = listOf(other, member)),
            signerPolicy.copy(members = listOf(member, member)),
            signerPolicy.copy(members = listOf(other)),
            signerPolicy.copy(members = listOf(member.copy(keyId = "unknown-key"))),
            signerPolicy.copy(members = listOf(member.copy(algorithmId = "Ed25519"))),
        ).forEach { rejectRegistry(registry.copy(catalogWriter = registry.catalogWriter.copy(requiredSignerPolicy = it))) }
    }

    @Test
    fun `catalog approvers must match the rooted ordered set and are not inferred from release approvals`() {
        val approvers = registry.catalogWriter.catalogApproverIds
        listOf(
            emptyList(),
            approvers.take(1),
            listOf(approvers[0], approvers[0]),
            approvers.reversed(),
            approvers + "another-approver",
            fixture.body().approvals.map { it.approverId },
        ).forEach { rejectRegistry(registry.copy(catalogWriter = registry.catalogWriter.copy(catalogApproverIds = it))) }
    }

    @Test
    fun `another authenticated required signer and sixteen approvers work only when explicitly rooted`() {
        val approvers = (1..16).map { "catalog-approver-$it" }
        val signerPolicy = registry.catalogWriter.requiredSignerPolicy
        val required = signerPolicy.members.single().copy(keyId = "catalog-new")
        val changed = registry.copy(
            catalogWriter = registry.catalogWriter.copy(
                requiredSignerPolicy = signerPolicy.copy(members = listOf(required)),
                catalogApproverIds = approvers,
            ),
        )
        val originalBody = fixture.body()
        val authority = originalBody.bootstrapAuthority.copy(
            requiredSigner = required,
            catalogApproverIds = approvers,
            initialWriterRegistrySha256 = Sha256.hex(fixture.registryBytes(changed)),
        )
        val signed = fixture.bytes(fixture.signed(originalBody.copy(bootstrapAuthority = authority)))
        assertEquals(changed, OfflineBootstrapRegistryVerifier.verify(fixture.registryBytes(changed), signed, fixture.policy()).registry)
    }

    @Test
    fun `all six catalog put sign event ordinary and terminal role alias pairs are rejected`() {
        roleIds.indices.forEach { first ->
            (first + 1 until roleIds.size).forEach { second ->
                rejectRegistry(withRoles(roleIds.toMutableList().apply { this[second] = this[first] }))
            }
        }
    }

    @Test
    fun `credential references cannot alias each other or any catalog or event role`() {
        rejectRegistry(withRange(range.copy(ordinaryAuthority = range.ordinaryAuthority.copy(credentialId = range.sealTerminalAuthority.credentialId))))
        roleIds.forEach { role ->
            rejectRegistry(withRange(range.copy(ordinaryAuthority = range.ordinaryAuthority.copy(credentialId = role))))
            rejectRegistry(withRange(range.copy(sealTerminalAuthority = range.sealTerminalAuthority.copy(credentialId = role))))
        }
    }

    @Test
    fun `all six policy identity alias pairs are rejected even with valid immutable reference fields`() {
        policies.indices.forEach { first ->
            (first + 1 until policies.size).forEach { second ->
                rejectRegistry(withPolicies(policies.toMutableList().apply { this[second] = this[second].copy(policyId = this[first].policyId) }))
            }
        }
    }

    @Test
    fun `every inventory reference is bounded ASCII rather than inline credentials URLs or paths`() {
        referenceMutations("x".repeat(65)).forEach { rejectRegistry(it) }
        listOf("", "with space", "_leading", "../path", "https://example.invalid/key", "é", "x\n").forEach { value ->
            rejectRegistry(withRange(range.copy(routingKeyId = value)))
        }
        listOf("a", "A" + "._-0".repeat(15) + "xyz").forEach { value ->
            val changed = withRange(range.copy(routingKeyId = value))
            assertEquals(changed, checkRegistry(changed).registry)
        }
    }

    @Test
    fun `policy references require positive long versions and exact lowercase digests`() {
        policies.forEachIndexed { index, policy ->
            val invalid = listOf(policy.copy(version = 0), policy.copy(version = -1)) +
                listOf("", "0".repeat(63), "0".repeat(65), "A".repeat(64), "g".repeat(64)).map { policy.copy(sha256 = it) }
            invalid.forEach { replacement -> rejectRegistry(withPolicies(policies.toMutableList().apply { this[index] = replacement })) }
        }
        // A digest is only a reference: equal digests do not prove deployed permissions or collapse distinct policy identities.
        val sameDigest = withPolicies(policies.map { it.copy(version = Long.MAX_VALUE, sha256 = policies[0].sha256) })
        assertEquals(sameDigest, checkRegistry(sameDigest).registry)
    }

    @Test
    fun `prefixes reject swaps overlap traversal encoding wrong generation and missing suffixes`() {
        rejectRegistry(withRange(range.copy(ordinaryPrefix = range.sealTerminalPrefix, sealTerminalPrefix = range.ordinaryPrefix)))
        rejectRegistry(withRange(range.copy(ordinaryPrefix = range.sealTerminalPrefix)))
        rejectRegistry(withRange(range.copy(sealTerminalPrefix = range.ordinaryPrefix)))
        listOf(range.ordinaryPrefix, range.sealTerminalPrefix).forEachIndexed { index, prefix ->
            listOf(
                prefix.dropLast(1),
                prefix + "extra/",
                prefix.substringBeforeLast('/', "").substringBeforeLast('/') + "/",
                prefix.replace(fixture.EVENT_WRITER, fixture.CATALOG_WRITER),
                prefix.replace(fixture.EVENT_WRITER + "/", ""),
                prefix.replace("/live/", "/test/"),
                prefix.replace("/live/", "/../live/"),
                prefix.replace("/live/", "/%2e%2e/live/"),
                prefix.replace("/live/", "//live/"),
                prefix.replace("complaints/", "complaints%2f"),
                "s3://kira-synthetic-journal/$prefix",
            ).forEach { value ->
                val changed = if (index == 0) range.copy(ordinaryPrefix = value) else range.copy(sealTerminalPrefix = value)
                rejectRegistry(withRange(changed))
            }
        }
    }

    @Test
    fun `initial range is only zero LIVE scope OPEN epoch one and the exact empty seal history`() {
        listOf(
            range.copy(scope = range.scope.copy(kind = "TEST")),
            range.copy(scope = range.scope.copy(kind = "live")),
            range.copy(scope = range.scope.copy(id = fixture.EVENT_WRITER)),
            range.copy(state = "SEALED"),
            range.copy(state = "TERMINAL"),
            range.copy(state = "ACTIVE"),
            range.copy(firstEpoch = 0),
            range.copy(firstEpoch = 2),
            range.copy(firstEpoch = -1),
            range.copy(sealHistory = range.sealHistory.copy(count = 1)),
            range.copy(sealHistory = range.sealHistory.copy(count = -1)),
            range.copy(sealHistory = range.sealHistory.copy(sha256 = Sha256.hexUtf8("[{}]"))),
            range.copy(sealHistory = range.sealHistory.copy(sha256 = range.sealHistory.sha256.uppercase())),
        ).forEach { rejectRegistry(withRange(it)) }
    }

    @Test
    fun `journal location and configuration digest claims cannot use malformed identifiers`() {
        val location = range.journalLocation
        listOf("", "x", "x".repeat(64), "bad..bucket", "UPPER", "https://bucket").forEach { value ->
            rejectRegistry(withRange(range.copy(journalLocation = location.copy(bucket = value))))
        }
        listOf("", "1", "1".repeat(13), "aaaaaaaaaaaa").forEach { value ->
            rejectRegistry(withRange(range.copy(journalLocation = location.copy(accountId = value))))
        }
        listOf("", "us east 1", "us-east", "US-east-1", "x".repeat(65)).forEach { value ->
            rejectRegistry(withRange(range.copy(journalLocation = location.copy(region = value))))
        }
        listOf("", "0".repeat(63), "0".repeat(65), "A".repeat(64), "g".repeat(64)).forEach { value ->
            rejectRegistry(withRange(range.copy(configurationSha256 = value)))
        }
    }

    @Test
    fun `every registry object is closed including forbidden terminal legacy test and credential fields`() {
        val text = registryBytes.decodeToString()
        listOf(
            "{",
            "\"catalogWriter\":{",
            "\"eventWriter\":{",
            "\"requiredSignerPolicy\":{",
            "\"members\":[{",
            "\"putAuthority\":{",
            "\"signAuthority\":{",
            "\"policy\":{",
            "\"liveRange\":{",
            "\"scope\":{",
            "\"journalLocation\":{",
            "\"ordinaryAuthority\":{",
            "\"sealTerminalAuthority\":{",
            "\"sealHistory\":{",
        ).forEach { marker ->
            rejectBytes(text.replaceFirst(marker, "$marker\"unknown\":0,").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
        listOf(
            "{" to "trustBundleEnvelopeSha256",
            "{" to "genesisSha256",
            "{" to "predecessor",
            "{" to "legacy",
            "{" to "importMetadata",
            "\"eventWriter\":{" to "testRun",
            "\"liveRange\":{" to "lastEpoch",
            "\"liveRange\":{" to "terminal",
            "\"liveRange\":{" to "denialProof",
            "\"liveRange\":{" to "sealedState",
            "\"ordinaryAuthority\":{" to "credentialValue",
            "\"sealTerminalAuthority\":{" to "secretKey",
        ).forEach { (marker, field) ->
            rejectBytes(text.replaceFirst(marker, "$marker\"$field\":0,").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
    }

    @Test
    fun `registry requires fields and rejects null floats booleans and coercible wrong types`() {
        val text = registryBytes.decodeToString()
        listOf("null", "1.0", "1e0", "true", "{}").forEach { value ->
            rejectBytes(text.replace("\"firstEpoch\":1", "\"firstEpoch\":$value").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
        // A numeric string reaches typed decoding but is still refused by the exact canonical-byte guard.
        val quotedEpoch = text.replace("\"firstEpoch\":1", "\"firstEpoch\":\"1\"").toByteArray()
        rejectBytes(quotedEpoch, OfflineTrustBundleFailure.NON_CANONICAL)
        listOf(
            text.replace(",\"firstEpoch\":1", ""),
            text.replace(",\"schemaVersion\":1", ""),
            text.replaceFirst("\"databaseIdentity\":\"${fixture.DATABASE_ID}\",", ""),
            text.replace("\"catalogApproverIds\":[\"catalog-approver-a\",\"catalog-approver-b\"],", ""),
        ).forEach { rejectBytes(it.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT) }
    }

    @Test
    fun `registry rejects duplicates escaped field aliases malformed UTF8 and unpaired surrogates`() {
        val text = registryBytes.decodeToString()
        listOf(
            text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"\\u0073chemaVersion\":1"),
            text.replace("\"firstEpoch\":1", "\"firstEpoch\":1,\"firstEpoch\":1"),
            text.replaceFirst("\"policyId\":", "\"policyId\":\"duplicate\",\"policyId\":"),
            text.replace("catalog-put-role", "\\ud800"),
        ).forEach { rejectBytes(it.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT) }
        rejectBytes(registryBytes.also { it[10] = 0xff.toByte() }, OfflineTrustBundleFailure.MALFORMED_INPUT)
        rejectBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + registryBytes, OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    @Test
    fun `registry rejects trailing values comments and noncanonical whitespace escaping and integer spelling`() {
        val text = registryBytes.decodeToString()
        listOf("$text{}", "$text true", "/* comment */$text").forEach { rejectBytes(it.toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT) }
        listOf(" $text", "$text\n", text.replace("catalog-put-role", "\\u0063atalog-put-role"), text.replace("\"count\":0", "\"count\":-0")).forEach {
            rejectBytes(it.toByteArray(), OfflineTrustBundleFailure.NON_CANONICAL)
        }
    }

    @Test
    fun `registry byte cap precedes bundle verification and streaming budgets precede closed decoding`() {
        rejectBytes(ByteArray(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES + 1), OfflineTrustBundleFailure.LIMIT_EXCEEDED, trustBundleBytes = byteArrayOf())
        val atCap = registryBytes + ByteArray(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES - registryBytes.size) { ' '.code.toByte() }
        rejectBytes(atCap, OfflineTrustBundleFailure.NON_CANONICAL)
        rejectBytes(("{\"x\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        rejectBytes(("{\"" + "x".repeat(65) + "\":0}").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val fields = (0..32).joinToString(prefix = "{", postfix = "}") { "\"field$it\":0" }
        rejectBytes(fields.toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val sixteenNumbers = List(16) { "0" }.joinToString(prefix = "[", postfix = "]")
        val next = List(16) { sixteenNumbers }.joinToString(prefix = "[", postfix = "]")
        val tokens = List(16) { next }.joinToString(prefix = "{\"x\":[", postfix = "]}")
        rejectBytes(tokens.toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `registry streaming array UTF8 string and integer limits are enforced before semantics`() {
        val signerPolicy = registry.catalogWriter.requiredSignerPolicy
        val sixteenPolicy = signerPolicy.copy(members = List(16) { signerPolicy.members[0] })
        val sixteen = registry.copy(catalogWriter = registry.catalogWriter.copy(requiredSignerPolicy = sixteenPolicy))
        assertEquals(16, OfflineTrustBundleParser.parseRegistry(fixture.registryBytes(sixteen)).catalogWriter.requiredSignerPolicy.members.size)
        val seventeenPolicy = signerPolicy.copy(members = List(17) { signerPolicy.members[0] })
        val seventeen = registry.copy(catalogWriter = registry.catalogWriter.copy(requiredSignerPolicy = seventeenPolicy))
        rejectBytes(fixture.registryBytes(seventeen), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        listOf("a".repeat(4096), "é".repeat(2048)).forEach { value ->
            val parsed = OfflineTrustBundleParser.parseRegistry(fixture.registryBytes(withRange(range.copy(routingKeyId = value))))
            assertEquals(value, parsed.eventWriter.liveRange.routingKeyId)
        }
        listOf("a".repeat(4097), "é".repeat(2049)).forEach { value ->
            rejectBytes(fixture.registryBytes(withRange(range.copy(routingKeyId = value))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        }
        val text = registryBytes.decodeToString()
        rejectBytes(text.replace("\"firstEpoch\":1", "\"firstEpoch\":10000000000000000000").toByteArray(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        rejectBytes(text.replace("\"firstEpoch\":1", "\"firstEpoch\":9223372036854775808").toByteArray(), OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    @Test
    fun `checked registry bytes and nested lists do not leak mutable internal state`() {
        val input = registryBytes
        val inputBundle = bundleBytes.copyOf()
        val bundleHash = Sha256.hex(inputBundle)
        val checked = OfflineBootstrapRegistryVerifier.verify(input, inputBundle, fixture.policy())
        input.fill(0)
        inputBundle.fill(0)
        checked.canonicalRegistryBytes.fill(0)
        val returned = checked.registry
        (returned.catalogWriter.catalogApproverIds as MutableList<String>).clear()
        assertNotSame(returned.catalogWriter.requiredSignerPolicy.members, checked.registry.catalogWriter.requiredSignerPolicy.members)
        assertEquals(registry, checked.registry)
        assertArrayEquals(registryBytes, checked.canonicalRegistryBytes)
        assertEquals(Sha256.hex(registryBytes), checked.registrySha256)
        assertEquals(bundleHash, checked.trustBundleEnvelopeSha256)
    }

    @Test
    fun `claims holder snapshots caller supplied nested signer and approver lists`() {
        val signers = registry.catalogWriter.requiredSignerPolicy.members.toMutableList()
        val approvers = registry.catalogWriter.catalogApproverIds.toMutableList()
        val mutable = registry.copy(
            catalogWriter = registry.catalogWriter.copy(
                requiredSignerPolicy = registry.catalogWriter.requiredSignerPolicy.copy(members = signers),
                catalogApproverIds = approvers,
            ),
        )
        val input = registryBytes
        val checked = CheckedOfflineBootstrapRegistry(mutable, input, Sha256.hex(input), Sha256.hex(bundleBytes), 7)
        signers[0] = signers[0].copy(keyId = "mutated")
        signers.clear()
        approvers.clear()
        input.fill(0)
        assertEquals(registry, checked.registry)
        assertArrayEquals(registryBytes, checked.canonicalRegistryBytes)
    }

    @Test
    fun `registry diagnostics contain no submitted values or parser causes`() {
        val marker = "synthetic-private-marker"
        val invalid = registryBytes.decodeToString().replaceFirst("{", "{\"$marker\":0,").toByteArray()
        val failure = assertThrows(OfflineTrustBundleException::class.java) { OfflineBootstrapRegistryVerifier.verify(invalid, bundleBytes, fixture.policy()) }
        assertFalse(failure.toString().contains(marker))
        assertNull(failure.cause)
    }

    private fun withRange(value: InitialLiveRangeV1): OfflineBootstrapRegistryV1 = registry.copy(eventWriter = registry.eventWriter.copy(liveRange = value))

    private fun withRoles(values: List<String>): OfflineBootstrapRegistryV1 = registry.copy(
        catalogWriter = registry.catalogWriter.copy(
            putAuthority = registry.catalogWriter.putAuthority.copy(principalId = values[0]),
            signAuthority = registry.catalogWriter.signAuthority.copy(principalId = values[1]),
        ),
        eventWriter = registry.eventWriter.copy(
            liveRange = range.copy(
                ordinaryAuthority = range.ordinaryAuthority.copy(roleId = values[2]),
                sealTerminalAuthority = range.sealTerminalAuthority.copy(roleId = values[3]),
            ),
        ),
    )

    private fun withPolicies(values: List<InitialPolicyReferenceV1>): OfflineBootstrapRegistryV1 = registry.copy(
        catalogWriter = registry.catalogWriter.copy(
            putAuthority = registry.catalogWriter.putAuthority.copy(policy = values[0]),
            signAuthority = registry.catalogWriter.signAuthority.copy(policy = values[1]),
        ),
        eventWriter = registry.eventWriter.copy(
            liveRange = range.copy(
                ordinaryAuthority = range.ordinaryAuthority.copy(policy = values[2]),
                sealTerminalAuthority = range.sealTerminalAuthority.copy(policy = values[3]),
            ),
        ),
    )

    private fun referenceMutations(value: String): List<OfflineBootstrapRegistryV1> =
        roleIds.indices.map { index -> withRoles(roleIds.toMutableList().apply { this[index] = value }) } +
            policies.indices.map { index -> withPolicies(policies.toMutableList().apply { this[index] = this[index].copy(policyId = value) }) } +
            listOf(
                withRange(range.copy(ordinaryAuthority = range.ordinaryAuthority.copy(credentialId = value))),
                withRange(range.copy(sealTerminalAuthority = range.sealTerminalAuthority.copy(credentialId = value))),
                withRange(range.copy(routingKeyId = value)),
                withRange(range.copy(encryptionKeyId = value)),
            )

    private fun checkRegistry(value: OfflineBootstrapRegistryV1): CheckedOfflineBootstrapRegistry =
        OfflineBootstrapRegistryVerifier.verify(fixture.registryBytes(value), fixture.bytes(fixture.signedForRegistry(value)), fixture.policy())

    private fun rejectRegistry(value: OfflineBootstrapRegistryV1, code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT) {
        val bytes = fixture.registryBytes(value)
        val signed = fixture.bytes(fixture.signedForRegistry(value))
        // Every semantic-negative case first proves a valid real root signature and matching hash, so neither can mask validation.
        val rooted = OfflineTrustBundleVerifier.verify(signed, fixture.policy())
        assertEquals(Sha256.hex(bytes), rooted.body.bootstrapAuthority.initialWriterRegistrySha256)
        rejectBytes(bytes, code, trustBundleBytes = signed)
    }

    private fun rejectBytes(
        bytes: ByteArray,
        code: OfflineTrustBundleFailure,
        policy: OfflineTrustBundlePolicy = fixture.policy(),
        trustBundleBytes: ByteArray = bundleBytes,
    ) {
        val failure = assertThrows(OfflineTrustBundleException::class.java) { OfflineBootstrapRegistryVerifier.verify(bytes, trustBundleBytes, policy) }
        assertEquals(code, failure.code)
    }
}
