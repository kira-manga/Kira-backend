package me.manga.kira.backend.database

import org.flywaydb.core.Flyway

/** One-shot migration entry point for the release image; it never starts HTTP, JPA, or schedulers. */
object DatabaseMigrationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val url = requiredEnvironment("KIRA_MIGRATION_DB_URL")
        val username = requiredEnvironment("KIRA_MIGRATION_DB_USERNAME")
        val password = requiredEnvironment("KIRA_MIGRATION_DB_PASSWORD")
        val requireTls = System.getenv("KIRA_MIGRATION_REQUIRE_TLS")?.toBooleanStrictOrNull() ?: true
        require(url.startsWith("jdbc:postgresql://")) { "Migration database must be PostgreSQL" }
        if (requireTls) {
            require(url.substringAfter('?', "").split('&').any { it.equals("sslmode=verify-full", ignoreCase = true) }) {
                "Migration PostgreSQL must use sslmode=verify-full"
            }
        }
        val result =
            Flyway
                .configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .outOfOrder(false)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .load()
                .migrate()
        println("Database migration complete; applied=${result.migrationsExecuted} target=${result.targetSchemaVersion}")
    }

    private fun requiredEnvironment(name: String): String = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
        "$name is required"
    }
}
