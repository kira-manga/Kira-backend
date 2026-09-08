package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.net.Socket
import java.sql.Driver
import javax.net.SocketFactory

/** MODEL frame vectors and real public metadata, never a grant to the production current-stack path. */
internal class PersistencePgDriverFramesTest {
    private val image = pgTestDriverImage()

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11])
    fun `MODEL every enumerated primary chain needs every exact type method and descriptor`(index: Int) {
        val pattern = image.patterns.primary[index]
        assertSame(PersistenceTransportRole.PRIMARY, PersistencePgDriverFrames.classify(pattern, image.patterns))
        assertEveryFrameRequired(pattern)
    }

    @Test
    fun `MODEL auxiliary chain is cancel-specific and independent of opening suffix`() {
        val pattern = image.patterns.auxiliary
        assertSame(PersistenceTransportRole.AUX_CANCEL, PersistencePgDriverFrames.classify(pattern, image.patterns))
        assertEveryFrameRequired(pattern)
        assertEquals("sendQueryCancel", pattern.last().method)
        assertTrue(pattern.none { it.type === PersistencePgDriverOpening::class.java })
    }

    @Test
    fun `MODEL constructor chain includes exact reflective route and actual opening tail`() {
        val pattern = image.patterns.constructor
        assertTrue(PersistencePgDriverFrames.matches(pattern, pattern))
        assertEveryFrameRequired(pattern)
        assertEquals("<init>", pattern[2].method)
        assertEquals("instantiate", pattern[3].method)
        assertSame(PersistencePgDriverOpening::class.java, pattern.last().type)
        assertNull(PersistencePgDriverFrames.classify(pattern, image.patterns))
    }

    @Test
    fun `MODEL no prefix skipping search suffix truncation or over-cap vectors are accepted`() {
        val pattern = image.patterns.primary.first()
        val foreign = PersistencePgFrame(String::class.java, "unknown", "()V")
        assertFalse(PersistencePgDriverFrames.matches(listOf(foreign) + pattern, pattern))
        assertFalse(PersistencePgDriverFrames.matches(pattern.drop(1), pattern))
        assertFalse(PersistencePgDriverFrames.matches(pattern.dropLast(1), pattern))
        assertFalse(PersistencePgDriverFrames.matches(pattern, emptyList()))
        val exactlyBounded = pattern + List(PersistencePgDriverFrames.MAX_FRAMES - pattern.size) { foreign }
        assertTrue(PersistencePgDriverFrames.matches(exactlyBounded, pattern))
        assertFalse(PersistencePgDriverFrames.matches(exactlyBounded + foreign, pattern))
        assertFalse(PersistencePgDriverFrames.matches(exactlyBounded + foreign, exactlyBounded + foreign))
    }

    @Test
    fun `MODEL same binary class name from another loader is not the same authority`() {
        val original = PersistencePgFrame::class.java
        val resource = "/${original.name.replace('.', '/')}.class"
        val bytes = requireNotNull(original.getResourceAsStream(resource)).use { it.readNBytes(65_537) }
        assertTrue(bytes.size in 1..65_536)
        val duplicate = PgFrameDuplicateLoader().define(bytes)
        assertEquals(original.name, duplicate.name)
        assertNotSame(original, duplicate)
        val expected = PersistencePgFrame(original, "matches", "()Z")
        assertFalse(expected.matches(PersistencePgFrame(duplicate, "matches", "()Z")))
    }

    @Test
    fun `REAL_METADATA bridge is final public no-arg-only with no covariant socket bridge`() {
        val bridge = TrackedPgSocketFactory::class.java
        assertSame(SocketFactory::class.java, bridge.superclass)
        assertTrue(Modifier.isPublic(bridge.modifiers) && Modifier.isFinal(bridge.modifiers))
        assertEquals(1, bridge.constructors.size)
        assertEquals(0, bridge.constructors.single().parameterCount)
        val methods = bridge.declaredMethods.filter { it.name == "createSocket" }
        assertEquals(5, methods.size)
        methods.forEach {
            assertSame(Socket::class.java, it.returnType)
            assertFalse(it.isBridge || it.isSynthetic)
        }
        assertSame(bridge, Class.forName(bridge.name, false, image.type("org.postgresql.util.ObjectFactory").classLoader))
    }

    @Test
    fun `REAL_STACK direct metadata walkers refuse unrelated unit-test callers`() {
        assertFalse(PersistencePgDriverFrames.constructorAllowed(image))
        assertNull(PersistencePgDriverFrames.classifyAllocation(image))
    }

    private fun assertEveryFrameRequired(pattern: List<PersistencePgFrame>) {
        pattern.forEachIndexed { index, frame ->
            val wrong = listOf(
                PersistencePgFrame(String::class.java, frame.method, frame.descriptor),
                PersistencePgFrame(frame.type, frame.method + "Unknown", frame.descriptor),
                PersistencePgFrame(frame.type, frame.method, frame.descriptor + "I"),
            )
            for (replacement in wrong) {
                val actual = pattern.toMutableList().also { it[index] = replacement }
                assertFalse(PersistencePgDriverFrames.matches(actual, pattern), "Exact frame $index was not required.")
                assertNull(PersistencePgDriverFrames.classify(actual, image.patterns))
            }
        }
    }
}

internal fun pgTestDriverImage(): PersistencePgDriverImage = PersistencePgDriverImage.prepare(
    Class.forName("org.postgresql.Driver", false, PersistenceDriverBootstrap::class.java.classLoader).asSubclass(Driver::class.java),
)

private class PgFrameDuplicateLoader : ClassLoader(PersistencePgFrame::class.java.classLoader) {
    fun define(bytes: ByteArray): Class<*> = defineClass(PersistencePgFrame::class.java.name, bytes, 0, bytes.size)
}
