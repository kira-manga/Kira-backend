package me.manga.kira.backend.database

import me.manga.kira.backend.database.complaint.assertClosedComplaintSeeds
import me.manga.kira.backend.database.complaint.assertComplaintBackupStates
import me.manga.kira.backend.database.complaint.assertComplaintRestoredHelpers
import me.manga.kira.backend.database.complaint.assertPreserved
import me.manga.kira.backend.database.complaint.assertRestoreSchemaEquals
import me.manga.kira.backend.database.complaint.complaintResource
import me.manga.kira.backend.database.complaint.exec
import me.manga.kira.backend.database.complaint.newComplaintPostgres
import me.manga.kira.backend.database.complaint.restoreSchemaSnapshot
import me.manga.kira.backend.database.complaint.sequenceValues
import me.manga.kira.backend.database.complaint.strings
import me.manga.kira.backend.database.complaint.tableSnapshots
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** Local schema/data proof, not an accepted production restore-catalog or disaster-recovery drill. */
class DatabaseBackupRestoreIT {
    @ParameterizedTest(name = "dump V{0}, restore, validate and upgrade to V14")
    @ValueSource(ints = [13, 14])
    fun `populated current and historical backups preserve exact bytes relationships and sequences`(version: Int) {
        withStartedBackupContainers(newComplaintPostgres("kira_source"), newComplaintPostgres("kira_restore")) { source, target ->
            flyway(source, version).migrate()
            source.connection().use { connection ->
                connection.autoCommit = false
                try {
                    connection.exec(complaintResource("fixtures/complaint/v13-rich.sql"))
                    if (version == 14) {
                        connection.exec(complaintResource("fixtures/complaint/v14-rich.sql"))
                        connection.exec(complaintResource("fixtures/complaint/v14-backup-states.sql"))
                    }
                    connection.prepareStatement("UPDATE source_config_revisions SET notes=notes||?").use {
                        it.setString(1, TRANSPORT_SENTINEL)
                        assertEquals(1, it.executeUpdate())
                    }
                    // pg_dump uses a separate connection; uncommitted fixture state is not backup coverage.
                    connection.commit()
                } catch (failure: Throwable) {
                    runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }
            if (version == 14) source.connection().use { it.assertComplaintBackupStates() }
            val before = source.connection().use { it.tableSnapshots() }
            val structure = source.connection().use { it.restoreSchemaSnapshot() }
            val sequences = source.connection().use { it.sequenceValues() }
            restoreDatabaseDump(source, target)
            target.connection().use {
                it.assertPreserved(before)
                assertRestoreSchemaEquals(structure, it.restoreSchemaSnapshot())
                assertEquals(sequences, it.sequenceValues())
                assertEquals(listOf("synthetic exact-byte sentinel$TRANSPORT_SENTINEL"), it.strings("SELECT notes FROM source_config_revisions"))
                if (version == 14) {
                    it.assertComplaintBackupStates()
                    it.assertComplaintRestoredHelpers()
                }
            }
            assertEquals(if (version == 14) 0 else 1, flyway(target).migrate().migrationsExecuted)
            assertTrue(flyway(target).validateWithResult().validationSuccessful)
            target.connection().use {
                it.assertPreserved(before)
                assertEquals(sequences, it.sequenceValues())
                if (version == 13) it.assertClosedComplaintSeeds() else it.assertComplaintBackupStates()
                it.assertComplaintRestoredHelpers()
            }
        }
    }

    private fun flyway(postgres: PostgreSQLContainer<*>, target: Int = 14): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .target(MigrationVersion.fromVersion(target.toString()))
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .outOfOrder(false)
        .validateMigrationNaming(true)
        .load()

    companion object {
        // XML cannot represent every code point. Assertions use escaped or hash-only diagnostics.
        private const val TRANSPORT_SENTINEL = "\u0001\u0008\u000b\u001f\u007f\u009f\u001b[31mRAW\u001b[0m\t\nعربي😀?"
    }
}

internal fun <T> withStartedBackupContainers(
    source: PostgreSQLContainer<*>,
    target: PostgreSQLContainer<*>,
    block: (PostgreSQLContainer<*>, PostgreSQLContainer<*>) -> T,
): T = source.use { from ->
    target.use { to ->
        // Both cleanup owners exist before either startup. Nested use preserves primary failures and
        // still attempts source shutdown if target startup or shutdown fails.
        from.start()
        to.start()
        block(from, to)
    }
}

private fun PostgreSQLContainer<*>.connection(): Connection = DriverManager.getConnection(jdbcUrl, username, password)
