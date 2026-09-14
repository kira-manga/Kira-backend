package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.sql.Savepoint
import java.sql.Statement

/** Lower-state rules only. These do not substitute for the connected real-JPA/PG outcome tests. */
class PersistenceJdbcTransactionTest {
    @Test
    fun `acknowledged commit is not erased by rollback or later reset failure`() {
        val transaction = active()
        transaction.connectionReturned(COMMIT, emptyArray(), null)
        transaction.connectionReturned(ROLLBACK, emptyArray(), null)
        transaction.connectionFailed(AUTO_COMMIT)

        assertEquals(PersistenceDatabaseOutcome.COMMITTED, transaction.databaseOutcome())
        assertTrue(transaction.uncertain(), "Cleanup/reuse uncertainty remains distinct from the known commit.")
        assertFalse(transaction.clean())
    }

    @Test
    fun `unknown commit stays unknown after a returned rollback and refuses auto commit reset`() {
        val transaction = active()
        transaction.connectionFailed(COMMIT)
        transaction.connectionReturned(ROLLBACK, emptyArray(), null)

        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, transaction.databaseOutcome())
        assertTrue(transaction.uncertain())
        assertFalse(transaction.clean())
        assertThrows<SQLException> { transaction.beforeConnection(AUTO_COMMIT, arrayOf(true), returning = true) }
    }

    @Test
    fun `acknowledged rollback and clean local state are not the same fact`() {
        val transaction = active()
        assertEquals(PersistenceDatabaseOutcome.NONE, transaction.databaseOutcome())
        assertFalse(transaction.clean())

        transaction.connectionReturned(ROLLBACK, emptyArray(), null)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, transaction.databaseOutcome())
        assertTrue(transaction.clean())
        transaction.connectionFailed(AUTO_COMMIT)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, transaction.databaseOutcome())
        assertFalse(transaction.clean())
    }

    @Test
    fun `failed rollback has no fabricated acknowledged outcome`() {
        val transaction = active()
        transaction.connectionFailed(ROLLBACK)
        transaction.connectionReturned(ROLLBACK, emptyArray(), null)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, transaction.databaseOutcome())
        assertFalse(transaction.clean())
    }

    @Test
    fun `savepoint rollback is not a completed physical transaction`() {
        val transaction = active()
        val savepoint = object : Savepoint {
            override fun getSavepointId(): Int = 1
            override fun getSavepointName(): String = "synthetic"
        }
        transaction.connectionReturned(Connection::class.java.getMethod("rollback", Savepoint::class.java), arrayOf(savepoint), null)
        assertEquals(PersistenceDatabaseOutcome.NONE, transaction.databaseOutcome())
        assertFalse(transaction.clean())
    }

    @Test
    fun `successor inherits clean settings but never the old transaction result`() {
        val transaction = active()
        transaction.connectionReturned(COMMIT, emptyArray(), null)
        transaction.connectionReturned(AUTO_COMMIT, arrayOf(true), null)
        val successor = PersistenceJdbcTransaction()
        successor.inherit(transaction)

        assertTrue(successor.clean())
        assertEquals(PersistenceDatabaseOutcome.NONE, successor.databaseOutcome())
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, transaction.databaseOutcome())
    }

    @Test
    fun `post outcome native descendant work again prevents clean return`() {
        val transaction = active()
        transaction.connectionReturned(COMMIT, emptyArray(), null)
        assertTrue(transaction.clean())
        transaction.beforeChild(Statement::class.java.getMethod("close"), PhysicalJdbcKind.STATEMENT)
        assertFalse(transaction.clean(), "Driver cleanup can issue SQL; a prior commit is not later cleanup consent.")
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, transaction.databaseOutcome())
    }

    private fun active(): PersistenceJdbcTransaction = PersistenceJdbcTransaction().apply {
        connectionReturned(AUTO_COMMIT, arrayOf(false), null)
        beforeChild(Statement::class.java.getMethod("execute", String::class.java), PhysicalJdbcKind.STATEMENT)
    }

    private companion object {
        val AUTO_COMMIT = Connection::class.java.getMethod("setAutoCommit", Boolean::class.javaPrimitiveType)
        val COMMIT = Connection::class.java.getMethod("commit")
        val ROLLBACK = Connection::class.java.getMethod("rollback")
    }
}
