package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PersistencePgAuxiliaryCloseFramesTest {
    private val image = pgTestDriverImage()

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [0, 1, 2, 3, 4, 5])
    fun `MODEL direct auxiliary close requires each contiguous exact class method and descriptor`(index: Int) {
        val pattern = image.patterns.auxiliaryClose
        assertEquals(6, pattern.size)
        assertTrue(PersistencePgDriverFrames.matches(pattern, pattern))
        val frame = pattern[index]
        val wrong = listOf(
            PersistencePgFrame(String::class.java, frame.method, frame.descriptor),
            PersistencePgFrame(frame.type, frame.method + "Unknown", frame.descriptor),
            PersistencePgFrame(frame.type, frame.method, frame.descriptor + "I"),
        )
        for (replacement in wrong) {
            val actual = pattern.toMutableList().also { it[index] = replacement }
            assertFalse(PersistencePgDriverFrames.matches(actual, pattern))
        }
        assertFalse(PersistencePgDriverFrames.matches(pattern.filterIndexed { slot, _ -> slot != index }, pattern))
    }

    @Test
    fun `MODEL raw aliases constructor cleanup prefix filtering and over-bound stacks are not direct-close witnesses`() {
        val pattern = image.patterns.auxiliaryClose
        val unrelated = PersistencePgFrame(TrackedPersistenceInputStream::class.java, "close", "()V")
        assertFalse(PersistencePgDriverFrames.matches(listOf(unrelated) + pattern, pattern))
        assertFalse(PersistencePgDriverFrames.matches(pattern.take(4) + unrelated + pattern.drop(4), pattern))
        assertFalse(PersistencePgDriverFrames.matches(pattern.dropLast(1), pattern))
        val constructor = PersistencePgFrame(image.type("org.postgresql.core.PGStream"), "createSocket", "(I)Ljava/net/Socket;")
        assertFalse(PersistencePgDriverFrames.matches(pattern.take(4) + constructor + pattern.drop(5), pattern))
        val bounded = pattern + List(PersistencePgDriverFrames.MAX_FRAMES - pattern.size) { unrelated }
        assertTrue(PersistencePgDriverFrames.matches(bounded, pattern))
        assertFalse(PersistencePgDriverFrames.matches(bounded + unrelated, pattern))
        assertFalse(PersistencePgDriverFrames.auxiliaryCloseAllowed(image))
    }
}
