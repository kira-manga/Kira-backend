package me.manga.kira.backend.security

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.opaque
import me.manga.kira.backend.security.TestTerminalTestFixture.Companion.uuid
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Version-bound local HMAC candidates, not persisted intents, authenticated headers, KMS requests or publications. */
class TestTerminalRoutingV1Test {
    private val f = TestTerminalTestFixture()

    @Test
    fun retainedRoutingKeysMatchIndependentTestFamilyHmacAndObjectKeys() {
        val keyLiterals = f.vectors.getValue("keys").jsonArray.map { it.jsonObject }
        val buffers = keyLiterals.map { HexFormat.of().parseHex(it.terminalText("synthetic_key_hex")) }
        val acquired = f.acquired(material = { buffers[it] })
        val routing = TestTerminalRoutingV1.fromAcquired(f.journal, acquired.reversed())
        val cases = listOf(
            Triple("INSTALLATION_MANIFEST", "installationManifest", routing.deriveInstallationManifest(f.manifest)),
            Triple("TEST_RUN_PURGE", "purge", routing.derivePurge(f.purge)),
            Triple("EPOCH_SEAL", "epochSeal", routing.deriveEpochSeal(f.ordinarySeal)),
        )
        val ids = HashSet<String>()
        val objectKeys = HashSet<String>()
        cases.forEach { (kind, document, routes) ->
            val descriptor = f.vectors.getValue("descriptors").jsonObject.getValue(kind).jsonObject
            val omitted = if (kind == "EPOCH_SEAL") "sealId" else "eventId"
            val independent = terminalCanonical(JsonObject(f.value(document) - omitted))
            assertArrayEquals(descriptor.terminalText("canonical_utf8").toByteArray(), independent)
            assertEquals(descriptor.terminalText("sha256"), terminalHash(independent))
            val actualDescriptor = when (kind) {
                "INSTALLATION_MANIFEST" -> f.json.installationDescriptorSha256(f.manifest)
                "TEST_RUN_PURGE" -> f.json.purgeDescriptorSha256(f.purge)
                else -> f.json.sealDescriptorSha256(f.ordinarySeal)
            }
            assertEquals(descriptor.terminalText("sha256"), actualDescriptor)
            assertEquals(4, routes.candidates().size)
            assertEquals("test-route-01", routes.active.routingKeyId)
            assertEquals(routes.active, routes.candidates().first())
            assertNotSame(routes.candidates(), routes.candidates())
            routes.candidates().forEachIndexed { index, actual ->
                val expected = f.route(kind, index)
                assertEquals(keyLiterals[index].terminalText("routing_key_id"), actual.routingKeyId)
                assertEquals(expected.terminalText("journal_id"), actual.journalId)
                assertEquals(expected.terminalText("object_key"), actual.objectKey)
                val idFields = expected.getValue("id_fields").jsonArray.map { it.jsonPrimitive.content }
                val keyFields = expected.getValue("key_fields").jsonArray.map { it.jsonPrimitive.content }
                assertArrayEquals(HexFormat.of().parseHex(expected.terminalText("id_frame_hex")), terminalFrame(idFields))
                assertArrayEquals(HexFormat.of().parseHex(expected.terminalText("key_frame_hex")), terminalFrame(keyFields))
                assertEquals(expected.terminalText("journal_id"), hmac(buffers[index], terminalFrame(idFields)))
                assertEquals(expected.terminalText("object_id"), hmac(buffers[index], terminalFrame(keyFields)))
                assertNotEquals(actual.journalId, expected.terminalText("object_id"))
                assertTrue(actual.objectKey.startsWith(f.journal.sealTerminalPrefix + if (kind == "EPOCH_SEAL") "2/" else "3/"))
                assertTrue(ids.add(actual.journalId) && objectKeys.add(actual.objectKey))
                for (wrongDomain in listOf("kira-complaint-journal-event-id-v1", "kira-complaint-journal-epoch-seal-v1", keyFields.first())) {
                    assertNotEquals(actual.journalId, hmac(buffers[index], terminalFrame(listOf(wrongDomain) + idFields.drop(1))))
                }
            }
        }
        assertEquals(12, ids.size)
        assertEquals(12, objectKeys.size)
        assertEquals(f.journal.declaration().routing.keys.map { it.keyId to it.secret }, routing.descriptors().map { it.logicalKeyId to it.version })
        assertTrue(routing.descriptors().all { it.family == SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING && it.purpose == SecretMaterialPurpose.HMAC_SHA256 })
        assertNotSame(routing.descriptors(), routing.descriptors())
        buffers.forEach { it.fill(0) }
        acquired.forEach { it.useMaterial { copy -> copy.fill(0) } }
        assertEquals(f.route("INSTALLATION_MANIFEST").terminalText("journal_id"), routing.deriveInstallationManifest(f.manifest).active.journalId)
        for (width in listOf(32, 128)) {
            val material = List(4) { index -> ByteArray(width) { n -> (1 + index * 37 + n).toByte() } }
            val candidates = TestTerminalRoutingV1.fromAcquired(f.journal, f.acquired(material = { material[it] })).deriveInstallationManifest(f.manifest).candidates()
            candidates.forEachIndexed { index, route ->
                val fields = f.route("INSTALLATION_MANIFEST", index).getValue("id_fields").jsonArray.map { it.jsonPrimitive.content }
                assertEquals(hmac(material[index], terminalFrame(fields)), route.journalId)
            }
        }
    }

    @Test
    fun retainedOrdinaryConsumerMatchesExistingSealCandidatesButCannotDeriveTerminalEvents() {
        val acquired = f.acquired()
        val original = TestOwnerDeleteJournalRoutingV1.fromAcquired(f.journal, acquired)
        val retained = TestTerminalRoutingV1.fromRetained(original)
        val expected = TestTerminalRoutingV1.fromAcquired(f.journal, acquired).deriveEpochSeal(f.ordinarySeal)
        assertEquals(original.descriptors(), retained.descriptors())
        assertEquals(expected.active, retained.deriveEpochSeal(f.ordinarySeal).active)
        assertEquals(expected.candidates(), retained.deriveEpochSeal(f.ordinarySeal).candidates())
        assertEquals(TestTerminalFailureV1.KEY_FAILURE,
            terminalRejected { retained.deriveInstallationManifest(f.manifest) }.code)
        assertEquals(TestTerminalFailureV1.KEY_FAILURE,
            terminalRejected { retained.derivePurge(f.purge) }.code)
        assertEquals(expected.candidates(), retained.deriveEpochSeal(f.ordinarySeal).candidates(),
            "Refusing terminal-event use neither replaces nor destroys the original retained seal keys.")
    }

    @Test
    fun descriptorAndHeaderContextMutationsCannotBorrowAnotherFamilyIdentity() {
        val acquired = f.acquired()
        val routing = TestTerminalRoutingV1.fromAcquired(f.journal, acquired)
        val manifest = routing.deriveInstallationManifest(f.manifest).active
        val purge = routing.derivePurge(f.purge).active
        val seal = routing.deriveEpochSeal(f.ordinarySeal).active
        assertEquals(manifest, routing.deriveInstallationManifest(f.manifest(context = f.manifest.context().copy(eventId = opaque(99)))).active)
        assertEquals(purge, routing.derivePurge(f.purge(context = f.purge.context().copy(eventId = opaque(99)))).active)
        assertEquals(seal, routing.deriveEpochSeal(f.ordinarySeal.copy(sealId = opaque(99))).active)
        val changedContexts = listOf(
            f.manifest.context().copy(run = f.run.copy(activationCatalogGeneration = 5)),
            f.manifest.context().copy(run = f.run.copy(activationCatalogSha256 = "c".repeat(64))),
            f.manifest.context().copy(run = f.run.copy(configurationSha256 = "c".repeat(64))),
            f.manifest.context().copy(publicationEpoch = 4),
        )
        changedContexts.forEach { assertNotEquals(manifest, routing.deriveInstallationManifest(f.manifest(context = it)).active) }
        assertNotEquals(manifest, routing.deriveInstallationManifest(f.manifest(context = f.manifest.context(), root = "c".repeat(64))).active)
        assertNotEquals(manifest, routing.deriveInstallationManifest(f.manifest(context = f.manifest.context(), index = 1, count = 2)).active)
        assertNotEquals(manifest, routing.deriveInstallationManifest(f.manifest(context = f.manifest.context(), entries = f.entries.map { it.copy(disposition = TestTerminalDispositionV1.DELETED) })).active)
        for (changed in listOf(
            f.purge(summary = f.summary.copy(chunksSha256 = "c".repeat(64))),
            f.purge(inventory = f.countHash("preTerminalInventory").copy(sha256 = "c".repeat(64))),
            f.purge(seals = f.countHash("preTerminalSeals").copy(sha256 = "c".repeat(64))),
            f.purge(seal = f.sealRef.copy(objectRef = f.sealRef.objectRef.copy(objectVersion = "version-2"))),
        )) assertNotEquals(purge, routing.derivePurge(changed).active)
        for (changed in listOf(f.ordinarySeal.copy(eventCount = 3), f.ordinarySeal.copy(preparingFencingToken = 18),
            f.ordinarySeal.copy(eventManifestSha256 = "c".repeat(64)), f.ordinarySeal.copy(epochEndInclusive = 3))) {
            assertNotEquals(seal, routing.deriveEpochSeal(changed).active)
        }
        val declaration = f.journal.declaration()
        val otherScope = TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(scope = ComplaintDataScope.of(UUID.fromString(uuid(9)))))
        val otherWriter = TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(writer = declaration.writer.copy(generationId = uuid(9))))
        for ((journal, context) in listOf(otherScope to f.manifest.context().copy(run = f.run.copy(dataScopeId = uuid(9))),
            otherWriter to f.manifest.context().copy(writerGeneration = uuid(9)))) {
            val changed = f.manifest(context = context)
            terminalRejected { routing.deriveInstallationManifest(changed) }
            assertNotEquals(manifest, TestTerminalRoutingV1.fromAcquired(journal, acquired).deriveInstallationManifest(changed).active)
        }
        val otherBucket = TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(journalLocation = declaration.journalLocation.copy(bucket = "other-test-bucket")))
        val otherKms = TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(encryption = declaration.encryption.copy(keyId = "other-kms")))
        for (journal in listOf(otherScope, otherWriter, otherBucket, otherKms)) {
            val json = TestTerminalJsonV1(journal)
            terminalRejected { json.eventHeader(f.bytes("eventHeader")) }
            terminalRejected { json.sealHeader(f.bytes("sealHeader")) }
            terminalRejected { json.encodeEventHeader(f.eventHeader()) }
            terminalRejected { json.encodeSealHeader(f.sealHeader()) }
        }
        assertThrows<IllegalArgumentException> { TestOwnerDeleteJournalConfigurationV1.of(declaration.copy(scope = ComplaintDataScope.LIVE)) }
        val badBindings: List<(VersionedSecretBinding) -> VersionedSecretBinding> = listOf(
            { VersionedSecretBinding.of(SecretMaterialFamily.COMPLAINT_CURSOR, it.purpose, it.logicalKeyId, it.version) },
            { VersionedSecretBinding.of(SecretMaterialFamily.DATABASE, SecretMaterialPurpose.AUTHENTICATION_PASSWORD, it.logicalKeyId, it.version) },
            { VersionedSecretBinding.of(it.family, it.purpose, "other-${it.logicalKeyId}", it.version) },
            { VersionedSecretBinding.of(it.family, it.purpose, it.logicalKeyId, ImmutableSecretVersion.awsSecretsManager(it.version.resourceArn, uuid(99))) },
        )
        badBindings.forEach { binding -> assertEquals(TestTerminalFailureV1.KEY_FAILURE,
            terminalRejected { TestTerminalRoutingV1.fromAcquired(f.journal, f.acquired(binding = binding)) }.code) }
        for (inputs in listOf(emptyList(), acquired.dropLast(1), acquired.dropLast(1) + acquired.first())) {
            assertEquals(TestTerminalFailureV1.KEY_FAILURE, terminalRejected { TestTerminalRoutingV1.fromAcquired(f.journal, inputs) }.code)
        }
        terminalRejected { TestTerminalRoutingV1.fromAcquired(f.journal, acquired + acquired.first()) }
        for (width in listOf(31, 129)) assertEquals(TestTerminalFailureV1.KEY_FAILURE,
            terminalRejected { TestTerminalRoutingV1.fromAcquired(f.journal, f.acquired(material = { ByteArray(width) { 7 } })) }.code)
        val short = ByteArray(32) { (it + 1).toByte() }
        val long = ByteArray(65) { (it + 1).toByte() }
        for (pair in listOf(short to short, short to short.copyOf(64), long to MessageDigest.getInstance("SHA-256").digest(long))) {
            val keys = f.acquired(material = { index -> when (index) { 0 -> pair.first; 1 -> pair.second; else -> ByteArray(32) { n -> (1 + index * 37 + n).toByte() } } })
            assertEquals(TestTerminalFailureV1.KEY_FAILURE, terminalRejected { TestTerminalRoutingV1.fromAcquired(f.journal, keys) }.code)
        }
        assertTrue(routing.toString().contains("no-intent-or-provider-authority"))
        // Headers bind their declared J context only: no AEAD, payload-ID authentication or accepted D/activation is claimed.
    }

    private fun hmac(key: ByteArray, frame: ByteArray): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(frame))
    }
}
