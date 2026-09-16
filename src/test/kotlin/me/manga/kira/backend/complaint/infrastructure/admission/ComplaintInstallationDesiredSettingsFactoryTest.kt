package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ComplaintInstallationDesiredSettingsFactoryTest {
    @Test
    fun `disabled is default retains no inactive binding and modes have no aliases`() {
        assertSame(ComplaintInstallationDesiredSettings.Disabled, ComplaintInstallationDesiredSettingsFactory.fromDeployment())
        assertSame(
            ComplaintInstallationDesiredSettings.Disabled,
            ComplaintInstallationDesiredSettingsFactory.fromDeployment("disabled", 0, -1, ByteArray(1), "invalid", null, "invalid"),
        )
        assertSame(
            ComplaintInstallationDesiredSettings.Disabled,
            ComplaintInstallationDesiredSettingsFactory.fromDeployment(null, 1, 1, bytes(), DATABASE, RESTORE, RUN),
        )
        assertEquals(ComplaintInstallationMode.DISABLED, ComplaintInstallationDesiredSettings.Disabled.mode)
        listOf("", " ", "DISABLED", "LIVE", "test", "pre-cutover-test", " live", "live ", "live\n").forEach { mode ->
            invalid { configured(mode = mode) }
        }
    }

    @Test
    fun `exact modes bind live zero or immutable TEST scope and positive Long generation without invented cap`() {
        listOf(1L, 65_536L, 65_537L, Long.MAX_VALUE).forEach { generation ->
            val live = configured(generation = generation)
            assertEquals(ComplaintInstallationMode.LIVE, live.mode)
            assertEquals(ComplaintDataScope.LIVE, live.scope)
            assertEquals(1, live.implementationSchema)
            assertEquals(generation, live.desiredGeneration)
            assertEquals(UUID.fromString(DATABASE), live.databaseIdentity)
            assertEquals(UUID.fromString(RESTORE), live.restoreIdentity)
            assertArrayEquals(bytes(), live.configurationHashBytes())
            val test = configured("pre_cutover_test", generation = generation, testRunId = RUN)
            assertEquals(ComplaintInstallationMode.PRE_CUTOVER_TEST, test.mode)
            assertEquals(UUID.fromString(RUN), test.scope.id)
            assertTrue(test.scope.testOnly)
        }
        // V14 requires valid v4 identities, not a made-up inequality or digest entropy requirement.
        val equalIdentities = configured(restore = DATABASE, hash = ByteArray(32))
        assertEquals(equalIdentities.databaseIdentity, equalIdentities.restoreIdentity)
        assertArrayEquals(ByteArray(32), equalIdentities.configurationHashBytes())
    }

    @Test
    fun `enabled settings require every binding with exact schema generation and digest width`() {
        listOf<Int?>(null, -1, 0, 2, Int.MAX_VALUE).forEach { schema -> invalid { configured(schema = schema) } }
        listOf<Long?>(null, Long.MIN_VALUE, -1, 0).forEach { generation -> invalid { configured(generation = generation) } }
        invalid { configured(hash = null) }
        listOf(0, 1, 31, 33, 64).forEach { size -> invalid { configured(hash = ByteArray(size)) } }
        invalid { configured(database = null) }
        invalid { configured(restore = null) }
        invalid { configured("pre_cutover_test") }
        listOf("", RUN, ComplaintDataScope.LIVE.id.toString()).forEach { run -> invalid { configured(testRunId = run) } }
    }

    @Test
    fun `database restore and TEST identities use existing exact canonical nonzero v4 grammar`() {
        val invalidIdentities = listOf(
            "",
            ComplaintDataScope.LIVE.id.toString(),
            DATABASE.uppercase(),
            "1-1-4000-8000-1",
            "aaaaaaaa-bbbb-1ccc-8ddd-eeeeeeeeeeee",
            "aaaaaaaa-bbbb-4ccc-7ddd-eeeeeeeeeeee",
            " $DATABASE",
            "$DATABASE\n",
            "x".repeat(1024),
        )
        invalidIdentities.forEach { value ->
            invalid { configured(database = value) }
            invalid { configured(restore = value) }
            invalid { configured("pre_cutover_test", testRunId = value) }
        }
    }

    @Test
    fun `configured digest copies inputs and outputs compares all bytes and diagnostics stay fixed`() {
        val input = bytes()
        val expected = input.copyOf()
        val desired = configured(hash = input)
        input.fill(77)
        desired.configurationHashBytes().fill(88)
        assertArrayEquals(expected, desired.configurationHashBytes())
        assertTrue(desired.matchesConfigurationHash(expected))
        assertFalse(desired.matchesConfigurationHash(null))
        assertFalse(desired.matchesConfigurationHash(expected.copyOf(31)))
        repeat(32) { index ->
            val changed = expected.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFalse(desired.matchesConfigurationHash(changed))
        }
        assertEquals("ComplaintInstallationDesiredSettings.Configured(redacted)", desired.toString())
        assertEquals("ComplaintInstallationDesiredSettings.Disabled", ComplaintInstallationDesiredSettings.Disabled.toString())
    }

    private fun configured(
        mode: String = "live",
        schema: Int? = 1,
        generation: Long? = 1,
        hash: ByteArray? = bytes(),
        database: String? = DATABASE,
        restore: String? = RESTORE,
        testRunId: String? = null,
    ): ComplaintInstallationDesiredSettings.Configured = ComplaintInstallationDesiredSettingsFactory.fromDeployment(
        mode,
        schema,
        generation,
        hash,
        database,
        restore,
        testRunId,
    ) as ComplaintInstallationDesiredSettings.Configured

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { operation() }
        assertEquals("Invalid installation desired settings", failure.message)
        assertNull(failure.cause)
    }

    private fun bytes(): ByteArray = ByteArray(32) { (it + 1).toByte() }

    private companion object {
        const val DATABASE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        const val RESTORE = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"
        const val RUN = "cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa"
    }
}
