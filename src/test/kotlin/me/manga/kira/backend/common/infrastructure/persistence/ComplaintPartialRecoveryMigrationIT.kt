package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.database.complaint.assertPartialRecoveryMigration
import me.manga.kira.backend.database.complaint.assertRotationSlotMigration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Additive schema fixtures, reusing the exact-class owned PostgreSQL binding and existing unpooled reader. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintPartialRecoveryMigrationIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintPartialRecoveryMigrationIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `populated V15 to V16 preserves data and schema except the partial recovery constraint`() {
        assertPartialRecoveryMigration(ordinaryCleanupReader(database.value))
    }

    @Test
    fun `fresh V17 retains closed seeds and enforces complete bounded rotation slot states`() {
        assertRotationSlotMigration(ordinaryCleanupReader(database.value), populated = false)
    }

    @Test
    fun `populated V16 to V17 preserves every old value and adds only an empty rotation slot`() {
        assertRotationSlotMigration(ordinaryCleanupReader(database.value), populated = true)
    }
}
