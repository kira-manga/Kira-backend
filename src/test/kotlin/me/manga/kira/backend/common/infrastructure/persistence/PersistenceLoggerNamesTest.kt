package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersistenceLoggerNamesTest {
    @Test
    fun `the pinned inventories remain unique and include both roots`() {
        assertEquals(30, PersistenceLoggerNames.postgres.size)
        assertEquals(11, PersistenceLoggerNames.hikari.size)
        assertEquals(42, PersistenceLoggerNames.slf4j.toSet().size)
        assertEquals(42, PersistenceLoggerNames.slf4j.size)
        assertTrue("org.postgresql" in PersistenceLoggerNames.slf4j)
        assertTrue("com.zaxxer.hikari" in PersistenceLoggerNames.slf4j)
    }

    @Test
    fun `protected namespaces are exact or dot descendants never lookalike prefixes`() {
        for (root in listOf("org.postgresql", "com.zaxxer.hikari")) {
            assertTrue(PersistenceLoggerNames.isProtected(root))
            assertTrue(PersistenceLoggerNames.isProtected("$root.child"))
            assertFalse(PersistenceLoggerNames.isProtected("${root}Extra.child"))
            assertFalse(PersistenceLoggerNames.isProtected("other.$root"))
        }
    }

    @Test
    fun `JUL ancestor inventory is finite and never mistakes the private root for a public logger`() {
        assertEquals(setOf("org", "org.postgresql", "org.postgresql.jdbc"), PersistenceLoggerNames.ancestors(listOf(BOOTSTRAP_LEAF)))
        assertFalse("" in PersistenceLoggerNames.ancestors(PersistenceLoggerNames.postgres))
    }

    @Test
    fun `transitive and aliased emitters remain explicitly represented`() {
        for (name in listOf(
            "org.postgresql.util.SharedTimer",
            "org.postgresql.util.StreamWrapper",
            "org.postgresql.core.QueryExecutorBase",
            "org.postgresql.gss.GssAction",
            "org.postgresql.jdbc.PgConnection",
        )) {
            assertTrue(name in PersistenceLoggerNames.postgres)
        }
    }
}
