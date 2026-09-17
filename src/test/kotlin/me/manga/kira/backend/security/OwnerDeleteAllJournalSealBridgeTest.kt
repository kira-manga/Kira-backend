package me.manga.kira.backend.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.HexFormat

class OwnerDeleteAllJournalSealBridgeTest {
    @Test
    fun `portless restoration retains existing independent ordinary golden bytes and rejects relabelled family or key`() {
        val fixture = EpochSealTestFixtureV1()
        val input = requireNotNull(javaClass.getResourceAsStream("/fixtures/owner-delete-all-journal-v1/golden.json")).use { it.readBytes() }
        val vectors = Json.parseToJsonElement(input.toString(Charsets.UTF_8)).jsonObject.getValue("vectors").jsonArray
        vectors.forEach { element ->
            val vector = element.jsonObject
            val bytes = HexFormat.of().parseHex(vector.getValue("plaintextHex").jsonPrimitive.content)
            val header = vector.getValue("header").jsonObject
            val restored = OwnerDeleteAllJournalCodecV1.restoreCanonical(fixture.owner, bytes, header.getValue("routingKeyId").jsonPrimitive.content)
            assertArrayEquals(bytes, restored.canonicalBytes())
            assertEquals(vector.getValue("semanticSha256").jsonPrimitive.content, restored.semanticSha256)
            assertEquals(header.getValue("objectKey").jsonPrimitive.content, restored.route.objectKey)
            assertThrows(OwnerDeleteAllJournalException::class.java) { OwnerDeleteAllJournalCodecV1.restoreCanonical(fixture.owner, bytes, "route-a") }
            val relabelled = bytes.toString(Charsets.UTF_8).replace("OWNER_DELETE_ALL", "OWNER_DELETE").toByteArray()
            assertThrows(OwnerDeleteAllJournalException::class.java) { OwnerDeleteAllJournalCodecV1.restoreCanonical(fixture.owner, relabelled, "route-b") }
        }
        assertEquals(0, fixture.keys.generations)
    }

    @Test
    fun `ordinary publication retains five second ceiling and every subcall observes original enclosing seal remainder`() {
        val fixture = EpochSealTestFixtureV1()
        val codec = OwnerDeleteAllJournalCodecV1(fixture.owner, fixture.keys, fixture.nonces) { fixture.clock.nanos }
        val total = PersistenceTimeBudget.start(30_000, PersistenceNanoClock { fixture.clock.nanos })
        fixture.clock.nanos = 28_500_000_000L
        val attempt = codec.startAttempt(total)
        val event = codec.canonicalize(EpochSealTestFixtureV1.tuple(), emptyList())
        fixture.clock.nanos = 29_750_000_000L
        codec.seal(event, attempt)
        assertEquals(250, fixture.keys.requests.single().timeoutMillis)
        fixture.clock.nanos = 30_000_000_000L
        val expired = assertThrows(OwnerDeleteAllJournalException::class.java) { codec.seal(event, attempt) }
        assertEquals(OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED, expired.code)
        assertEquals(1, fixture.keys.generations)
        assertThrows(OwnerDeleteAllJournalException::class.java) { codec.startAttempt(total) }

        val fresh = EpochSealTestFixtureV1()
        val parent = PersistenceTimeBudget.start(30_000, PersistenceNanoClock { fresh.clock.nanos })
        val ordinary = JournalCodecAttemptV1(fresh.owner, { fresh.clock.nanos }, parent)
        fresh.clock.nanos = 5_000_000_000L
        assertEquals(25_000L, parent.remainingMillis(30_000))
        assertEquals(
            OwnerDeleteAllJournalFailure.DEADLINE_EXHAUSTED,
            assertThrows(OwnerDeleteAllJournalException::class.java) { ordinary.remainingMillis(1) }.code,
        )
    }
}
