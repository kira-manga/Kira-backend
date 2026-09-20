package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference

internal class PersistenceTimerReferenceCallsTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(value = PgTimerProbeCase::class, mode = EnumSource.Mode.MATCH_ALL, names = ["MODEL_.*"])
    fun `MODEL_PROVIDER one shot failures ownership reentrancy and capture extent cuts remain conservative`(mode: PgTimerProbeCase) {
        PgTimerProbeProcess(Files.createDirectory(root.resolve(mode.name)), mode).use { child ->
            child.start()
            child.awaitVerified()
        }
    }

    @Test
    fun `capture task has only a private detached identity cell and no outer owner field`() {
        val type = Class.forName("${PersistencePgTimerAccess::class.java.name}\$CaptureTask", false, javaClass.classLoader)
        assertEquals(TimerTask::class.java, type.superclass)
        val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(1, fields.size)
        assertEquals(AtomicReference::class.java, fields.single().type)
        assertTrue(Modifier.isPrivate(fields.single().modifiers))
        assertFalse(fields.any { it.name.startsWith("this$") })
        assertEquals(listOf(AtomicReference::class.java), type.declaredConstructors.single().parameterTypes.toList())
    }

    @Test
    fun `consumer operations expose neither native handles nor callbacks tasks drain tokens or writable cells`() {
        val methods = PersistenceTimerReferenceCalls::class.java.declaredMethods
        assertEquals(setOf("acquireReference", "captureThread", "releaseOwnedReference", "observe", "newBoundary"), methods.map { it.name }.toSet())
        val boundary = methods.single { it.name == "newBoundary" }
        assertEquals(listOf(PersistenceRetainedPlatformThread::class.java), boundary.parameterTypes.toList())
        assertEquals(PersistenceTimerBoundary::class.java, boundary.returnType)
        val original = methods.filterNot { it === boundary }
        assertTrue(original.all { it.parameterCount == 0 })
        assertTrue(original.all { it.returnType in setOf(PersistenceTimerAction::class.java, PersistenceTimerObservation::class.java) })
        val snapshotTypes = listOf(PersistenceTimerObservation::class.java, PersistenceTimerCall::class.java, PersistenceTimerCapture::class.java)
        assertTrue(snapshotTypes.flatMap { it.declaredFields.toList() }.filterNot { Modifier.isStatic(it.modifiers) }.all { Modifier.isFinal(it.modifiers) })
    }

    @Test
    fun `record boundary has private construction and its task retains no root record raw resource or callback`() {
        val boundary = Class.forName("${PersistencePgTimerAccess::class.java.name}\$Boundary", false, javaClass.classLoader)
        assertTrue(Modifier.isPrivate(boundary.modifiers))
        val task = Class.forName("${PersistencePgTimerAccess::class.java.name}\$BoundaryTask", false, javaClass.classLoader)
        assertEquals(TimerTask::class.java, task.superclass)
        val fields = task.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(1, fields.size)
        val cell = fields.single().type
        assertTrue(Modifier.isPrivate(cell.modifiers) && Modifier.isPrivate(fields.single().modifiers))
        assertEquals(setOf(Thread::class.java, java.util.concurrent.atomic.AtomicBoolean::class.java), cell.declaredFields.map { it.type }.toSet())
        val api = PersistenceTimerBoundary::class.java.declaredMethods
        assertEquals(setOf("schedule", "acknowledged", "observation"), api.map { it.name }.toSet())
        assertTrue(api.all { it.parameterCount == 0 })
    }
}
