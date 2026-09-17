package me.manga.kira.backend.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.JournalRoutingKeyV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class VersionBoundComplaintJournalRoutingTest {
    @Test
    fun `independent Python golden frames bind actual routing and event ID bytes for owner and admin`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/complaint-journal-routing-v1/golden.json")).use { it.readBytes() }
        assertEquals("99bb2ed8fea3513edfdaef042286b458f9767ae8873f0212d207df4bab8fd1a5", hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        val vectors = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("vectors").jsonArray
        val owner = owner(journal())
        assertEquals(2, vectors.size)
        vectors.forEach { entry ->
            val vector = entry.jsonObject
            val fields = vector.getValue("fieldsAfterDomain").jsonArray.map { it.jsonPrimitive.content }
            listOf("routing" to "routing", "eventId" to "event-id").forEach { (name, purpose) ->
                val frame = unhex(vector.text(name + "FrameHex"))
                assertArrayEquals(independentFrame(listOf("kira-complaint-journal-$purpose-v1") + fields), frame)
                assertEquals(vector.getValue(name + "FrameBytes").jsonPrimitive.int, frame.size)
                val mac = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(unhex(vector.text("keyHex")), "HmacSHA256"))
                    doFinal(frame)
                }
                assertEquals(vector.text(name + "MacHex"), hex(mac))
            }
            val tuple = tuple(
                epoch = vector.getValue("epoch").jsonPrimitive.long,
                eventKind = ComplaintJournalDeletionKindV1.valueOf(vector.text("eventKind")),
                actorKind = ComplaintJournalActorKindV1.valueOf(vector.text("actorKind")),
                actorId = UUID.fromString(vector.text("actorId")),
                credentialVersion = vector.getValue("credentialVersion").jsonPrimitive.longOrNull,
                operationKey = UUID.fromString(vector.text("operationKey")),
                fingerprint = unhex(vector.text("fingerprintHex")),
            )
            val actual = owner.derive(tuple).active
            assertEquals(vector.text("routingKeyId"), actual.routingKeyId)
            assertEquals(vector.text("objectKey"), actual.objectKey)
            assertEquals(vector.text("routing"), actual.objectKey.substringAfterLast('/'))
            assertEquals(vector.text("eventId"), actual.eventId)
            assertTrue(Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]").matches(actual.eventId))
            assertTrue(actual.objectKey.length <= 1024 && actual.objectKey.all { it in ' '..'~' })
        }
    }

    @Test
    fun `the same J owner derives all four retained candidates deterministically and never looks up again`() {
        val declared = InitialLiveJournalTestFixture.declaration()
        val allKeys = declared.routing.keys + listOf(key("c", '6'), key("d", '7'))
        val journal = ComplaintJournalConfigurationV1.of(declared.copy(routing = declared.routing.copy(keys = allKeys.reversed())))
        var lookups = 0
        val acquired = inputs(journal) { lookups++ }
        val owner = VersionBoundComplaintJournalRouting.fromAcquired(journal, acquired.reversed())
        val result = owner.derive(tuple())
        assertSame(journal, owner.journalConfiguration)
        assertEquals(listOf("route-a", "route-b", "route-c", "route-d"), result.candidates().map { it.routingKeyId })
        assertEquals("route-b", result.active.routingKeyId)
        assertSame(result.candidates()[1], result.active)
        assertEquals(result.candidates(), owner.derive(tuple()).candidates())
        assertEquals(result.candidates(), VersionBoundComplaintJournalRouting.fromAcquired(journal, acquired).derive(tuple()).candidates())
        acquired.zip(owner.descriptors()).forEach { (input, descriptor) -> assertSame(input.descriptor, descriptor) }
        val forbidden = owner.admissionForbiddenFamily()
        assertEquals("journal-routing", forbidden.name)
        allKeys.forEach { assertTrue(forbids(forbidden, material(it.keyId))) }
        assertFalse(forbids(forbidden, ByteArray(32) { 127 }))
        assertEquals(4, lookups)
    }

    @Test
    fun `tuple writer namespace key ID material and purpose changes separate the opaque outputs`() {
        val journal = journal()
        val routing = owner(journal)
        val baseline = routing.derive(tuple()).active
        val changes = listOf(
            tuple(epoch = 43),
            tuple(eventKind = ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL),
            tuple(eventKind = ComplaintJournalDeletionKindV1.ADMIN_DELETE),
            tuple(actorId = UUID.fromString("71000000-0000-4000-8000-000000000002")),
            tuple(credentialVersion = 8),
            tuple(operationKey = UUID.fromString("72000000-0000-4000-8000-000000000002")),
            tuple(fingerprint = ByteArray(32) { (129 + it).toByte() }),
        ).map { routing.derive(it).active }
        val declared = journal.declaration()
        val otherWriter = ComplaintJournalConfigurationV1.of(
            declared.copy(writer = declared.writer.copy(generationId = InitialLiveJournalTestFixture.uuid('9'))),
        )
        val renamed = declared.routing.keys.map { if (it.keyId == "route-b") it.copy(keyId = "route-z") else it }
        val otherId = ComplaintJournalConfigurationV1.of(declared.copy(routing = declared.routing.copy(activeKeyId = "route-z", keys = renamed)))
        val otherIdOwner = owner(otherId) { id -> material(if (id == "route-z") "route-b" else id) }
        val otherMaterial = owner(journal) { id -> if (id == "route-b") ByteArray(32) { (it + 64).toByte() } else material(id) }
        (changes + owner(otherWriter).derive(tuple()).active + otherIdOwner.derive(tuple()).active + otherMaterial.derive(tuple()).active).forEach {
            assertNotEquals(baseline.objectKey.substringAfterLast('/'), it.objectKey.substringAfterLast('/'))
            assertNotEquals(baseline.eventId, it.eventId)
        }
        routing.derive(tuple()).candidates().forEach { assertNotEquals(it.eventId, it.objectKey.substringAfterLast('/')) }
    }

    @Test
    fun `the factory refuses missing extra repeated foreign or relabelled exact J acquisitions`() {
        val journal = journal()
        val expected = journal.declaration().routing.keys
        val valid = inputs(journal)
        listOf(emptyList(), valid.take(1), valid + acquired(key("c", '6')), listOf(valid.first(), valid.first())).forEach {
            invalid { VersionBoundComplaintJournalRouting.fromAcquired(journal, it) }
        }
        val first = expected.first()
        val otherVersion = ImmutableSecretVersion.awsSecretsManager(first.secret.resourceArn, InitialLiveJournalTestFixture.uuid('9'))
        val otherArn = ImmutableSecretVersion.awsSecretsManager(
            first.secret.resourceArn.replace("journal-routing-a", "journal-routing-other"), first.secret.versionId,
        )
        listOf(first.copy(keyId = "other-label"), first.copy(secret = otherVersion), first.copy(secret = otherArn)).forEach {
            invalid { VersionBoundComplaintJournalRouting.fromAcquired(journal, listOf(acquired(it), valid.last())) }
        }
        val foreign = acquired(first, family = SecretMaterialFamily.INSTALLATION_JWT)
        invalid { VersionBoundComplaintJournalRouting.fromAcquired(journal, listOf(foreign, valid.last())) }
        assertEquals(expected.map { it.secret }, valid.map { it.descriptor.version })
    }

    @Test
    fun `retained routing keys reject weak oversized effective aliases and a fifth input without dropping acquisitions`() {
        val journal = journal()
        val declared = journal.declaration().routing.keys
        val short = material("route-a")
        val long = ByteArray(128) { (it * 13 + 7).toByte() }
        val aliases = listOf(short to short, short to short.copyOf(64), long to MessageDigest.getInstance("SHA-256").digest(long))
        aliases.forEach { (a, b) ->
            val aliasInputs = listOf(acquired(declared.first(), a), acquired(declared.last(), b))
            invalid { VersionBoundComplaintJournalRouting.fromAcquired(journal, aliasInputs) }
            assertArrayEquals(a, aliasInputs.first().useMaterial { it.copyOf() })
            assertArrayEquals(b, aliasInputs.last().useMaterial { it.copyOf() })
        }
        listOf(1, 31, 129, 65_536).forEach { size ->
            val material = ByteArray(size) { 11 }
            val input = acquired(declared.last(), material)
            invalid { VersionBoundComplaintJournalRouting.fromAcquired(journal, listOf(acquired(declared.first()), input)) }
            assertArrayEquals(material, input.useMaterial { it.copyOf() })
        }
        val five = inputs(journal) + listOf(acquired(key("c", '6')), acquired(key("d", '7')), acquired(key("e", '8')))
        invalid { VersionBoundComplaintJournalRouting.fromAcquired(journal, five) }
    }

    @Test
    fun `copies diagnostics and typed LIVE event actor credential and operation boundaries remain closed`() {
        val journal = journal()
        val raw = mapOf("route-a" to material("route-a"), "route-b" to material("route-b"))
        val acquired = inputs(journal, materialFor = raw::getValue).toMutableList()
        val owner = VersionBoundComplaintJournalRouting.fromAcquired(journal, acquired)
        val fingerprint = ByteArray(32) { (128 + it).toByte() }
        val eventTuple = tuple(fingerprint = fingerprint)
        val result = owner.derive(eventTuple)
        val expected = result.candidates()
        raw.values.forEach { it.fill(0) }
        fingerprint.fill(0)
        acquired.clear()
        (owner.descriptors() as MutableList<VersionedSecretBinding>).clear()
        (result.candidates() as MutableList<ComplaintJournalRoutingCandidateV1>).clear()
        assertEquals(expected, owner.derive(eventTuple).candidates())
        assertEquals(expected, result.candidates())
        val rendered = listOf(owner, eventTuple, result, result.active, owner.descriptors(), owner.admissionForbiddenFamily()).joinToString()
        listOf(eventTuple.actorId.toString(), eventTuple.operationKey.toString(), journal.declaration().writer.generationId, "route-b").forEach {
            assertFalse(rendered.contains(it))
        }
        listOf(0L, -1L).forEach { invalid { tuple(epoch = it) } }
        listOf(null, 0L, -1L).forEach { invalid { tuple(credentialVersion = it) } }
        ComplaintJournalDeletionKindV1.entries.forEach { kind ->
            owner.derive(tuple(eventKind = kind))
            val wrongActor =
                if (kind.actorKind == ComplaintJournalActorKindV1.ADMIN) {
                    ComplaintJournalActorKindV1.INSTALLATION
                } else {
                    ComplaintJournalActorKindV1.ADMIN
                }
            invalid { tuple(eventKind = kind, actorKind = wrongActor) }
        }
        invalid { tuple(eventKind = ComplaintJournalDeletionKindV1.ADMIN_DELETE, credentialVersion = 0) }
        invalid { tuple(actorId = UUID.fromString("71000000-0000-1000-8000-000000000001")) }
        invalid { tuple(operationKey = UUID(0, 0)) }
        listOf(31, 33).forEach { invalid { tuple(fingerprint = ByteArray(it)) } }
        invalid { tuple(scope = ComplaintDataScope.of(UUID.fromString("74000000-0000-4000-8000-000000000001"))) }
    }

    private fun journal(): ComplaintJournalConfigurationV1 = ComplaintJournalConfigurationV1.of(InitialLiveJournalTestFixture.declaration())

    private fun owner(
        journal: ComplaintJournalConfigurationV1,
        materialFor: (String) -> ByteArray = ::material,
    ): VersionBoundComplaintJournalRouting = VersionBoundComplaintJournalRouting.fromAcquired(journal, inputs(journal, materialFor))

    private fun inputs(
        journal: ComplaintJournalConfigurationV1,
        materialFor: (String) -> ByteArray = ::material,
        onLookup: () -> Unit = {},
    ): List<AcquiredVersionedSecret> = journal.declaration().routing.keys.map { acquired(it, materialFor(it.keyId), onLookup = onLookup) }

    private fun acquired(
        key: JournalRoutingKeyV1,
        material: ByteArray = material(key.keyId),
        family: SecretMaterialFamily = SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING,
        onLookup: () -> Unit = {},
    ): AcquiredVersionedSecret {
        val binding = VersionedSecretBinding.of(family, SecretMaterialPurpose.HMAC_SHA256, key.keyId, key.secret)
        return AcquiredVersionedSecret.acquire(binding) { requested ->
            onLookup()
            assertEquals(key.secret, requested)
            SecretVersionSnapshot(key.secret, material)
        }
    }

    private fun key(name: String, version: Char): JournalRoutingKeyV1 = InitialLiveJournalTestFixture.routingKey(name, version)

    private fun material(keyId: String): ByteArray = ByteArray(32) { (it + (keyId.last() - 'a') * 32).toByte() }

    private fun tuple(
        epoch: Long = 42,
        eventKind: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE,
        actorKind: ComplaintJournalActorKindV1 = eventKind.actorKind,
        actorId: UUID = UUID.fromString("71000000-0000-4000-8000-000000000001"),
        credentialVersion: Long? = if (actorKind == ComplaintJournalActorKindV1.INSTALLATION) 7 else null,
        operationKey: UUID = UUID.fromString("72000000-0000-4000-8000-000000000001"),
        fingerprint: ByteArray = ByteArray(32) { (128 + it).toByte() },
        scope: ComplaintDataScope = ComplaintDataScope.LIVE,
    ): ComplaintJournalDeletionTupleV1 = ComplaintJournalDeletionTupleV1(
        epoch, eventKind, actorKind, actorId, credentialVersion, operationKey, fingerprint, scope,
    )

    private fun independentFrame(fields: List<String>): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            fields.forEach {
                val bytes = it.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
        }
        buffer.toByteArray()
    }

    private fun forbids(family: ComplaintAdmissionForbiddenFamily, material: ByteArray): Boolean {
        val probe = ComplaintAdmissionKey("probe", material)
        return try {
            family.forbids(probe)
        } finally {
            probe.destroy()
        }
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

    private fun unhex(value: String): ByteArray = HexFormat.of().parseHex(value)

    private fun invalid(action: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { action() }
        assertTrue(failure.message in setOf("Invalid complaint journal routing configuration", "Invalid complaint journal deletion tuple"))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
