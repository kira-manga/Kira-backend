package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.security.EpochSealTestFixtureV1.Companion.material
import me.manga.kira.backend.security.EpochSealTestFixtureV1.Companion.sha
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class EpochSealManifestV1Test {
    @Test
    fun `independent LP32 frame pins derived counts manifest roots all retained keys and separate key id purposes`() {
        val fixture = EpochSealTestFixtureV1()
        val writer = fixture.journal.declaration().writer.generationId
        val ordinary = "complaints/journal/v1/$writer/live/$SCOPE/ordinary/"
        val seal = "complaints/journal/v1/$writer/live/$SCOPE/seal-terminal/"
        listOf(emptyList(), listOf(fixture.entry(1), fixture.entry(2))).forEach { entries ->
            val manifest = fixture.manifest(fixture.codec.startAttempt(), entries)
            val prefix = listOf(DOMAIN, "1", "manifest", writer, ordinary, "LIVE", SCOPE, "1", "42", entries.size.toString())
            val expected = sha(frame(prefix + entries.flatMap { listOf(it.key, it.version, it.checksum) }))
            assertEquals(entries.size.toLong(), manifest.eventCount)
            assertEquals(expected, manifest.eventManifestSha256)
            val routes = fixture.owner.deriveEpochSeal(EpochSealRoutingTupleV1(manifest.range, manifest.eventManifestSha256))
            assertEquals(listOf("route-a", "route-b"), routes.candidates().map { it.routingKeyId })
            assertEquals("route-b", routes.active.routingKeyId)
            routes.candidates().forEach { route ->
                val fields = listOf(writer, seal, "LIVE", SCOPE, "1", "42", "0", expected, "", route.routingKeyId)
                val keyMac = mac(route.routingKeyId, listOf(DOMAIN, "1", "key") + fields)
                val sealId = mac(route.routingKeyId, listOf(DOMAIN, "1", "id") + fields)
                assertEquals("${seal}writer/$writer/epoch/0000000000000000042/${route.routingKeyId}/$keyMac", route.objectKey)
                assertEquals(sealId, route.sealId)
                assertNotEquals(keyMac, sealId)
                assertFalse(route.objectKey.startsWith(ordinary))
            }
        }
        val tuple = EpochSealRoutingTupleV1(EpochSealRangeV1(2, 42, "a".repeat(64)), "b".repeat(64))
        val baseline = fixture.owner.deriveEpochSeal(tuple).active
        listOf(
            EpochSealRoutingTupleV1(EpochSealRangeV1(3, 42, "a".repeat(64)), "b".repeat(64)),
            EpochSealRoutingTupleV1(EpochSealRangeV1(2, 43, "a".repeat(64)), "b".repeat(64)),
            EpochSealRoutingTupleV1(EpochSealRangeV1(2, 42, "c".repeat(64)), "b".repeat(64)),
            EpochSealRoutingTupleV1(tuple.range, "c".repeat(64)),
        ).forEach { assertNotEquals(baseline, fixture.owner.deriveEpochSeal(it).active) }
    }

    @Test
    fun `two passes cannot skip duplicate changed missing extra or unsorted entries and failure never resumes`() {
        val fixture = EpochSealTestFixtureV1()
        val one = fixture.entry(1)
        val two = fixture.entry(2)
        val original = listOf(one, two)
        listOf(
            emptyList(),
            listOf(one),
            listOf(one, two, fixture.entry(3)),
            listOf(two, one),
            listOf(one, one),
            listOf(one, two.copy(version = "changed-version")),
            listOf(one, two.copy(checksum = "f".repeat(64))),
        ).forEach { repeated ->
            val builder = builder(fixture)
            original.forEach { builder.firstPass(it.key, it.version, it.checksum) }
            builder.beginSecondPass()
            assertThrows(EpochSealExceptionV1::class.java) {
                repeated.forEach { builder.secondPass(it.key, it.version, it.checksum) }
                builder.finish()
            }
            assertThrows(EpochSealExceptionV1::class.java) { builder.finish() }
            assertThrows(EpochSealExceptionV1::class.java) { builder.beginSecondPass() }
        }
        listOf(listOf(one, one), listOf(two, one)).forEach { repeated ->
            val builder = builder(fixture)
            assertThrows(EpochSealExceptionV1::class.java) { repeated.forEach { builder.firstPass(it.key, it.version, it.checksum) } }
            assertThrows(EpochSealExceptionV1::class.java) { builder.beginSecondPass() }
        }
    }

    @Test
    fun `same J namespace range retained key version checksum and capacity bound every entry`() {
        val fixture = EpochSealTestFixtureV1()
        val one = fixture.entry(1)
        val invalid = listOf(
            one.copy(key = one.key.replace("ordinary/", "seal-terminal/")), one.copy(key = one.key.replace("/route-b/", "/missing/")),
            one.copy(key = one.key + "="), fixture.entry(43), one.copy(version = "null"), one.copy(version = ""),
            one.copy(version = "\ud800"), one.copy(version = "π".repeat(513)), one.copy(checksum = "A".repeat(64)),
        )
        invalid.forEach { entry ->
            assertThrows(EpochSealExceptionV1::class.java) { builder(fixture).firstPass(entry.key, entry.version, entry.checksum) }
        }
        val d = fixture.journal.declaration()
        listOf(
            d.limits.capacity.copy(maximumRetainedVersions = 1),
            d.limits.capacity.copy(maximumScanStagingBytes = 1),
        ).forEach { capacity ->
            val bounded = EpochSealTestFixtureV1(ComplaintJournalConfigurationV1.of(d.copy(limits = d.limits.copy(capacity = capacity))))
            assertThrows(EpochSealExceptionV1::class.java) { bounded.manifest(bounded.codec.startAttempt()) }
        }
        listOf(Triple(0L, 42L, ""), Triple(2L, 1L, "a".repeat(64)), Triple(1L, 42L, "a".repeat(64)), Triple(2L, 42L, "")).forEach {
            assertThrows(EpochSealExceptionV1::class.java) { EpochSealRangeV1(it.first, it.second, it.third) }
        }
        val validUtf8 = one.copy(version = "π".repeat(512))
        assertEquals(1L, fixture.manifest(fixture.codec.startAttempt(), listOf(validUtf8)).eventCount)
        val foreign = EpochSealTestFixtureV1(fixture.journal)
        assertThrows(EpochSealExceptionV1::class.java) { EpochSealManifestV1.start(fixture.owner, 1, 42, "", foreign.codec.startAttempt()) }
    }

    private fun builder(fixture: EpochSealTestFixtureV1): EpochSealManifestV1.Builder =
        EpochSealManifestV1.start(fixture.owner, 1, 42, "", fixture.codec.startAttempt())

    private fun mac(keyId: String, fields: List<String>): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(material(keyId), "HmacSHA256"))
        Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(frame(fields)))
    }

    private fun frame(fields: List<String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            fields.forEach { field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size)
                out.write(encoded)
            }
        }
        bytes.toByteArray()
    }

    private companion object {
        const val SCOPE = "00000000-0000-0000-0000-000000000000"
        const val DOMAIN = "kira-complaint-journal-epoch-seal-v1"
    }
}
