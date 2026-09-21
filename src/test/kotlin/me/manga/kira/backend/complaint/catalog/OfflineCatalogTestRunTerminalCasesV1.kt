package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalEnvelopeV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalManifestV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogInventoryChainVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogTestRunTerminalChainVerifier
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunTerminalParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.security.Signature
import java.util.Base64

/**
 * Parser/signature declarations derived from genuine completed D, not a made-up successful cut.
 * These independent TEST signatures/metadata are not native E publication or a producer capability.
 * No declaration test writes catalog SQL, invokes E Sign/PUT or releases complaint capabilities.
 */
internal object OfflineCatalogTestRunTerminalCasesV1 {
    fun canonicalFullHistory(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls, enrolled = true) { f ->
        val before = CatalogTestRunTerminalCasesV1.fullImage(f)
        val envelope = sign(f.manifest); val wire = bytes(envelope)
        val parsed = OfflineCatalogTestRunTerminalParser.parse(wire, 4096)
        assertArrayEquals(wire, bytes(parsed)); assertArrayEquals(f.unsigned, manifestBytes(parsed.manifest))
        val checked = verify(f, f.prefix + wire)
        assertArrayEquals(f.unsigned, checked.canonicalManifestBytes); assertArrayEquals(wire, checked.canonicalEnvelopeBytes)
        assertEquals(4, checked.manifest.schemaVersion); assertNotEquals(4L, checked.tail.generation)
        assertEquals(f.prefix.size + 1L, checked.tail.generation); assertEquals(Sha256.hex(wire), checked.tail.envelopeSha256)
        assertEquals(f.prefix.sumOf { it.size.toLong() } + wire.size, checked.encodedBytes)
        val activation = checked.activation
        assertEquals(activation.restoreInventory, checked.manifest.restoreInventory)
        assertTrue(activation.restoreInventory.sources.isNotEmpty() && activation.restoreInventory.copies.size >= 2)
        assertEquals(activation.initialWriterRegistry, checked.manifest.initialWriterRegistry)
        assertEquals(activation.oldestRestoreTimeEpochSecond, checked.manifest.oldestRestoreTimeEpochSecond)
        assertEquals(activation.history.testRunActivations.count, checked.manifest.history.testRunActivations.count)
        assertEquals(activation.history.testRunActivations.sha256, checked.manifest.history.testRunActivations.sha256)
        val json = Json.parseToJsonElement(f.unsigned.decodeToString()).jsonObject
        val record = json.getValue("terminalRecord").jsonObject
        assertFalse(record.keys.any { it in setOf("history", "envelopeSha256", "signatures") })
        val context = f.record.context()
        fun contextual(name: String, item: JsonElement) = JsonObject(mapOf(
            "dataScopeId" to JsonPrimitive(context.dataScopeId), "activationCatalogGeneration" to JsonPrimitive(context.activationCatalogGeneration),
            "activationCatalogSha256" to JsonPrimitive(context.activationCatalogSha256), name to item))
        val manifestRecords = JsonArray(listOf(contextual("manifest", record.getValue("installationManifest"))))
        val sealRecords = JsonArray((record.getValue("sealSet").jsonObject.getValue("records") as JsonArray).map { contextual("seal", it) })
        val heads = checked.manifest.history
        assertEquals(1L, heads.testRunTerminals.count)
        assertEquals(Sha256.hexUtf8(CanonicalJson.canonicalize(JsonArray(listOf(record)))), heads.testRunTerminals.sha256)
        assertEquals(1L, heads.installationManifests.count)
        assertEquals(Sha256.hexUtf8(CanonicalJson.canonicalize(manifestRecords)), heads.installationManifests.sha256)
        assertEquals(2L, heads.epochSeals.count); assertEquals(Sha256.hexUtf8(CanonicalJson.canonicalize(sealRecords)), heads.epochSeals.sha256)
        listOf(heads.expiredRestoreSources, heads.retirementAuthorizations, heads.retirementCompletions).forEach {
            assertEquals(0L, it.count); assertEquals("4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945", it.sha256)
        }
        assertEquals(1L, f.record.purge.document.preTerminalSeals.count)
        assertNotEquals(f.record.purge.document.preTerminalSeals.sha256, heads.epochSeals.sha256)
        assertNotEquals(f.d.fullSealSetSha256, heads.epochSeals.sha256, "Contextual history is not the full ordered-seal list or contextual seal-set blob.")
        assertEquals(1, f.record.installationManifest.chunks.size); assertEquals(2, f.record.progress.installationReads().size)
        assertEquals(2, f.record.progress.completedCuts().size)
        checked.canonicalManifestBytes.fill(0); checked.canonicalEnvelopeBytes.fill(0)
        assertArrayEquals(f.unsigned, checked.canonicalManifestBytes); assertArrayEquals(wire, checked.canonicalEnvelopeBytes)
        assertThrows<OfflineTrustBundleException> { OfflineCatalogTestRunActivationParser.parse(wire, 4096) }
        assertThrows<OfflineTrustBundleException> { OfflineCatalogTestRunActivationParser.parseGeneration(wire, 4096) }
        assertThrows<OfflineTrustBundleException> {
            OfflineCatalogInventoryChainVerifier.verifyTestRunActivationChain((f.prefix + wire).asSequence(), f.evidence.initial,
                f.evidence.current, f.process.catalogReadback.chainPolicy, f.expected.activation)
        }
        assertEquals(before, CatalogTestRunTerminalCasesV1.fullImage(f)); assertNoEEffects(f)
    }

    fun strictFieldsHistoryAndLinks(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        val envelope = sign(f.manifest); val wire = bytes(envelope); val text = wire.decodeToString()
        val root = Json.parseToJsonElement(text).jsonObject
        val badJson = listOf(
            JsonObject(root + ("unrecognized" to JsonPrimitive("field"))),
            change(root, listOf("manifest", "terminalRecord", "envelopeSha256"), JsonPrimitive("0".repeat(64))),
            change(root, listOf("manifest", "terminalRecord", "closure", "firstEpoch"), JsonPrimitive(1.0)),
            change(root, listOf("manifest", "terminalRecord", "closure", "state"), JsonPrimitive("PURGED")),
            change(root, listOf("manifest", "terminalRecord", "progress", "completedCuts"), JsonArray(emptyList())),
            change(root, listOf("manifest", "terminalRecord", "progress", "installationReads"), JsonArray(emptyList())),
            change(root, listOf("manifest", "terminalRecord", "progress", "configurationSha256"), JsonPrimitive("0".repeat(64))),
            change(root, listOf("manifest", "terminalRecord", "operationToken"), JsonPrimitive("ffffffff-ffff-4fff-8fff-ffffffffffff")),
            change(root, listOf("manifest", "previousEnvelopeSha256"), JsonPrimitive("0".repeat(64))),
            change(root, listOf("manifest", "schemaVersion"), JsonPrimitive(3)),
            change(root, listOf("manifest", "profile"), JsonPrimitive("NEW_BACKEND_TEST_RUN_V1")),
            change(root, listOf("manifest", "operation"), JsonPrimitive("TEST_RUN_ACTIVATION")),
        ).map(::jsonBytes)
        val malformed = badJson + listOf(byteArrayOf(0xc3.toByte(), 0x28), (text + "{}").toByteArray(),
            ("{\"schemaVersion\":4," + text.drop(1)).toByteArray(), ("[" + text + "]").toByteArray())
        malformed.forEach { value ->
            assertFalse(value.contentEquals(wire))
            assertThrows<OfflineTrustBundleException> { OfflineCatalogTestRunTerminalParser.parse(value, 4096) }
            assertThrows<OfflineTrustBundleException> { OfflineCatalogTestRunTerminalParser.parseGeneration(value, 4096) }
        }
        listOf(wire + byteArrayOf(10), (" " + text).toByteArray(),
            JsonObject(root.entries.reversed().associate { it.key to it.value }).toString().toByteArray()).forEach {
            fails(OfflineTrustBundleFailure.NON_CANONICAL) { OfflineCatalogTestRunTerminalParser.parse(it, 4096) }
        }
        val heads = root.getValue("manifest").jsonObject.getValue("history").jsonObject
        for ((family, value) in heads) {
            val current = value.jsonObject.getValue("count").toString().toLong()
            val changed = change(root, listOf("manifest", "history", family, "count"), JsonPrimitive(current + 1L))
            assertThrows<OfflineTrustBundleException> { OfflineCatalogTestRunTerminalParser.parse(jsonBytes(changed), 4096) }
        }
        // The terminal syntax cannot authenticate this historical activation hash by itself. The
        // contiguous signed predecessor fold must reject it even under a fresh genuine test signature.
        val wrongActivationHead = f.manifest.copy(history = f.manifest.history.copy(
            testRunActivations = f.manifest.history.testRunActivations.copy(sha256 = "0".repeat(64))))
        val badHead = bytes(sign(wrongActivationHead))
        assertArrayEquals(badHead, bytes(OfflineCatalogTestRunTerminalParser.parse(badHead, 4096)))
        assertThrows<OfflineTrustBundleException> { verify(f, f.prefix + badHead) }
        val removedCopy = bytes(sign(f.manifest.copy(restoreInventory = f.manifest.restoreInventory.copy(copies = f.manifest.restoreInventory.copies.dropLast(1)))))
        assertArrayEquals(removedCopy, bytes(OfflineCatalogTestRunTerminalParser.parse(removedCopy, 4096)))
        assertThrows<OfflineTrustBundleException> { verify(f, f.prefix + removedCopy) }
        val changedFloor = bytes(sign(f.manifest.copy(oldestRestoreTimeEpochSecond = f.manifest.oldestRestoreTimeEpochSecond + 1)))
        assertThrows<OfflineTrustBundleException> { verify(f, f.prefix + changedFloor) }
        listOf(f.prefix, f.prefix.dropLast(1) + wire, f.prefix + listOf(f.prefix.last(), wire),
            f.prefix + listOf(wire, wire), f.prefix + listOf(wire, f.prefix.last())).forEach {
            assertThrows<OfflineTrustBundleException> { verify(f, it) }
        }
        val signature = envelope.signatures.single()
        val corrupt = Base64.getDecoder().decode(signature.signatureBase64).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val forged = bytes(envelope.copy(signatures = listOf(signature.copy(signatureBase64 = Base64.getEncoder().encodeToString(corrupt)))))
        assertArrayEquals(forged, bytes(OfflineCatalogTestRunTerminalParser.parse(forged, 4096)))
        fails(OfflineTrustBundleFailure.INVALID_SIGNATURE) { verify(f, f.prefix + forged) }
        assertNoEEffects(f)
    }

    fun signedPaidAndReaderBounds(tls: VersionBoundPersistenceConnectedFixture) = withTerminalCatalogRun(tls) { f ->
        val envelope = sign(f.manifest); val wire = bytes(envelope)
        assertTrue(wire.size > f.unsigned.size && wire.size <= 131_072)
        assertArrayEquals(f.unsigned, manifestBytes(OfflineCatalogTestRunTerminalParser.parseManifest(f.unsigned, 4096, f.unsigned.size)))
        fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunTerminalParser.parse(wire, 4096, f.unsigned.size) }
        assertEquals(wire.size.toLong(), f.expected.envelopeSize(f.manifest), "Fixed384-byte signature overhead is paid too.")
        fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunTerminalParser.parseManifest(f.unsigned, 4096, f.unsigned.size - 1) }
        fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunTerminalParser.parse(wire, 1) }
        val all = f.prefix + wire; val limits = f.process.catalogReadback.chainPolicy.limits
        for (lower in listOf(limits.copy(maximumGenerations = f.prefix.size),
            limits.copy(maximumEncodedBytes = all.sumOf { it.size.toLong() } - 1L), limits.copy(maximumEnvelopeBytes = wire.size - 1),
            limits.copy(maximumManifestRecords = 1))) {
            assertThrows<OfflineTrustBundleException> { verify(f, all, lower) }
        }
        // Exact byte-bound syntax specimen only: adds synthetic logical-copy declarations, so the
        // unchanged-activation producer MUST NOT accept it. This is not a supported maximum-N proof.
        val sizeOnly = exactSizeManifest(f.manifest, envelope.signatures.single(), 131_072)
        val exact = bytes(sign(sizeOnly))
        assertEquals(131_072, exact.size); assertEquals(131_072L, f.expected.envelopeSize(sizeOnly))
        assertArrayEquals(exact, bytes(OfflineCatalogTestRunTerminalParser.parse(exact, 4096)))
        fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunTerminalParser.parse(exact, 4096, 131_071) }
        fails(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { OfflineCatalogTestRunTerminalParser.parse(exact + byteArrayOf(32), 4096) }
        assertThrows<OfflineTrustBundleException> { verify(f, f.prefix + exact) }
        assertEquals(f.manifest.restoreInventory, verify(f, all).manifest.restoreInventory)
        assertNoEEffects(f)
    }

    private fun verify(f: CatalogTestRunTerminalFixtureV1, chain: List<ByteArray>, limits: OfflineCatalogChainLimits = f.process.catalogReadback.chainPolicy.limits) =
        f.process.catalogReadback.chainPolicy.let { policy -> OfflineCatalogTestRunTerminalChainVerifier.verify(chain.asSequence(), f.evidence.initial,
            f.evidence.current, OfflineCatalogChainReaderPolicy(policy.trustBundlePolicy, policy.currentWriterGenerationIds, policy.currentApproverIds, limits), f.expected) }

    private fun sign(manifest: OfflineCatalogTestRunTerminalManifestV4): OfflineCatalogTestRunTerminalEnvelopeV4 {
        val member = manifest.requiredSignerPolicy.members.single()
        val key = if (member.keyId == "catalog-old") OfflineTrustBundleFixture.firstSigner else OfflineTrustBundleFixture.secondSigner
        val signature = Signature.getInstance("RSASSA-PSS").apply {
            setParameter(OfflineTrustBundleFixture.parameters); initSign(key.private)
            update(OfflineCatalogGenesisFixture.independentFrame(member.keyId, manifestBytes(manifest)))
        }.sign()
        return OfflineCatalogTestRunTerminalEnvelopeV4(4, manifest,
            listOf(OfflineCatalogGenesisSignatureV1(member.keyId, member.algorithmId, Base64.getEncoder().encodeToString(signature))))
    }
    private fun bytes(value: OfflineCatalogTestRunTerminalEnvelopeV4) = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalEnvelopeV4.serializer(), value).toByteArray(Charsets.UTF_8)
    private fun manifestBytes(value: OfflineCatalogTestRunTerminalManifestV4) = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalManifestV4.serializer(), value).toByteArray(Charsets.UTF_8)
    private fun jsonBytes(value: JsonObject) = CanonicalJson.canonicalize(value).toByteArray(Charsets.UTF_8)
    private fun change(root: JsonObject, path: List<String>, value: JsonElement): JsonObject = JsonObject(root + (path.first() to
        if (path.size == 1) value else change(root.getValue(path.first()).jsonObject, path.drop(1), value)))
    private fun fails(code: OfflineTrustBundleFailure, action: () -> Unit) = assertEquals(code, assertThrows<OfflineTrustBundleException> { action() }.code)
    private fun assertNoEEffects(f: CatalogTestRunTerminalFixtureV1) {
        assertTrue(f.probe.calls.isEmpty() && f.signing.requests.isEmpty() && f.http.bodies.isEmpty() && f.files().isEmpty())
    }

    /** Uses a fixed-width old signature for measurement only, then the caller signs the final bytes. */
    private fun exactSizeManifest(base: OfflineCatalogTestRunTerminalManifestV4, signature: OfflineCatalogGenesisSignatureV1,
        maximumBytes: Int): OfflineCatalogTestRunTerminalManifestV4 {
        fun size(value: OfflineCatalogTestRunTerminalManifestV4) = bytes(OfflineCatalogTestRunTerminalEnvelopeV4(4, value, listOf(signature))).size
        var value = base
        var index = base.restoreInventory.copies.size + 1
        while (true) {
            val copy = OfflineCatalogInventoryFixture.copy(base.restoreInventory.sources.single(), index++)
            val candidate = value.copy(restoreInventory = value.restoreInventory.copy(copies = value.restoreInventory.copies + copy))
            if (size(candidate) > maximumBytes) break
            value = candidate
        }
        var remaining = maximumBytes - size(value)
        var last = value.restoreInventory.copies.last()
        for (role in 0..2) {
            val objectVersion = when (role) { 0 -> last.manifest; 1 -> last.dump; else -> last.media }
            val count = minOf(remaining, 1024 - objectVersion.versionId.length)
            val padded = objectVersion.copy(versionId = objectVersion.versionId + "v".repeat(count))
            last = when (role) { 0 -> last.copy(manifest = padded); 1 -> last.copy(dump = padded); else -> last.copy(media = padded) }
            remaining -= count
        }
        assertEquals(0, remaining)
        return value.copy(restoreInventory = value.restoreInventory.copy(copies = value.restoreInventory.copies.dropLast(1) + last))
    }
}
