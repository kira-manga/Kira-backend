package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.util.UUID

/** HTTP evidence uses actual private results; lower wire/codec/lifecycle matrices are not duplicated. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OwnerDeleteAllHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OwnerDeleteAllHttpIT::class.java).also { it.start() } }
    private val mapper = ObjectMapper()

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `actual parsed body secret deletion returns empty204 and exact original-version HTTP retry opens no provider or deletion owner`() = withFixture { f ->
        f.assertEmpty(f.exchange(), 204)
        assertEquals("COMPLETED", f.connected.receiptState())
        assertEquals("APPLIED", f.connected.publicationState())
        val state = f.auth.state()
        val proof = f.connected.proofSnapshot()
        val calls = f.connected.publisher.requests.size to f.connected.publisher.kms.requests.size
        f.connected.statements.clear()
        f.assertEmpty(f.exchange(), 204)
        assertEquals(state, f.auth.state())
        assertEquals(proof, f.connected.proofSnapshot())
        assertEquals(calls, f.connected.publisher.requests.size to f.connected.publisher.kms.requests.size)
        assertTrue(f.connected.statements.isEmpty())
        assertEquals(2, f.dispatches)
    }

    @Test
    fun `genuine APPLY completion failure returns empty202 only and separate HTTP retry returns the unchanged bound204`() = withFixture { f ->
        var registered = false
        f.connected.afterSql = { sql ->
            if (sql == OwnerDeleteAllApplySql.COMPLETE_RECEIPT && !registered) {
                registered = true
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() = error("Synthetic HTTP APPLY completion failure.")
                })
            }
        }
        try {
            f.assertEmpty(f.exchange(), 202)
        } finally {
            f.connected.afterSql = {}
        }
        assertTrue(registered)
        assertEquals("COMPLETED", f.connected.receiptState()) // Actual committed tail, not fabricated UNKNOWN evidence.
        val state = f.auth.state()
        val proof = f.connected.proofSnapshot()
        val calls = f.connected.publisher.requests.size to f.connected.publisher.kms.requests.size
        f.connected.statements.clear()
        f.assertEmpty(f.exchange(), 204)
        assertEquals(state, f.auth.state())
        assertEquals(proof, f.connected.proofSnapshot())
        assertEquals(calls, f.connected.publisher.requests.size to f.connected.publisher.kms.requests.size)
        assertTrue(f.connected.statements.isEmpty())
    }

    @Test
    fun `real wrong body credentials version and completed key refusal remain finite problems with no extra mutation`() = withFixture { f ->
        val initial = f.auth.state()
        assertProblem(f.exchange(f.request(secret = ByteArray(32) { 99 })), 403, "INSTALLATION_CREDENTIAL_REJECTED")
        assertProblem(f.exchange(f.request(version = f.connected.candidate.credentialVersion + 1)), 403, "INSTALLATION_CREDENTIAL_REJECTED")
        assertEquals(initial, f.auth.state())
        assertTrue(f.connected.publisher.requests.isEmpty())
        f.assertEmpty(f.exchange(), 204)
        val completed = f.auth.state()
        val calls = f.connected.publisher.requests.size to f.connected.publisher.kms.requests.size
        val rejected = f.exchange(f.request(key = UUID.randomUUID()))
        assertProblem(rejected, 409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(completed, f.auth.state())
        assertEquals(calls, f.connected.publisher.requests.size to f.connected.publisher.kms.requests.size)
        f.connected.assertReleased()
    }

    @Test
    fun `publication failure before proof is not accepted pending and does not run VERIFY or APPLY`() = withFixture { f ->
        f.connected.publisher.beforePrepare = { throw IOException("Synthetic private provider I/O failure.") }
        val response = f.exchange()
        assertProblem(response, 503, "SERVICE_UNAVAILABLE")
        assertEquals("AUTHORIZED_DELETE", f.connected.receiptState())
        assertFalse(f.connected.verifyWasEntered())
        assertTrue(f.connected.statements.isEmpty())
        assertFalse(response.contentAsString.contains("Synthetic"))
        f.connected.assertReleased()
    }

    @Test
    fun `unresolved original APPLY cleanup cannot write an HTTP acknowledgement or problem before the original caller settles`() = withFixture { f ->
        val key = Any()
        val sentinel = Any()
        var bound = false
        val response = DeleteAllHttpResponse()
        f.connected.afterSql = { sql ->
            if (sql == OwnerDeleteAllApplySql.COMPLETE_RECEIPT) {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        TransactionSynchronizationManager.bindResource(key, sentinel)
                        bound = true
                        error("Synthetic original cleanup not settled.")
                    }
                })
            }
        }
        try {
            assertThrows<Exception> { f.exchange(response = response) }
            assertTrue(bound)
            assertFalse(response.status in setOf(202, 204))
            assertEquals(0, response.streams)
            assertEquals(0, response.writers)
            assertTrue(response.contentAsByteArray.isEmpty())
        } finally {
            f.connected.afterSql = {}
            if (bound) assertEquals(sentinel, TransactionSynchronizationManager.unbindResource(key))
            requireConnectionFree()
        }
        f.connected.assertReleased()
    }

    @Test
    fun `lost empty acknowledgement delivery never appends a problem or repeats APPLY and exact retry remains read-only`() = withFixture { f ->
        var attempts = 0
        val response = object : DeleteAllHttpResponse() {
            override fun setStatus(status: Int) {
                attempts++
                super.setStatus(status)
                if (status == 204) throw IOException("Synthetic response delivery lost.")
            }
        }
        assertThrows<IOException> { f.exchange(response = response) }
        assertEquals(1, attempts)
        assertEquals(1, f.dispatches)
        assertEquals(0, response.streams)
        assertTrue(response.contentAsByteArray.isEmpty())
        assertEquals("COMPLETED", f.connected.receiptState())
        f.connected.assertReleased()
        val state = f.auth.state()
        val calls = f.connected.publisher.requests.size to f.connected.publisher.kms.requests.size
        f.connected.statements.clear()
        f.assertEmpty(f.exchange(), 204)
        assertEquals(state, f.auth.state())
        assertEquals(calls, f.connected.publisher.requests.size to f.connected.publisher.kms.requests.size)
        assertTrue(f.connected.statements.isEmpty())
    }

    private fun assertProblem(response: DeleteAllHttpResponse, status: Int, code: String) {
        assertEquals(status, response.status)
        assertEquals(code, mapper.readTree(response.contentAsByteArray)["errors"][0]["code"].textValue())
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(null, response.getHeader("WWW-Authenticate"))
    }

    private fun withFixture(test: (OwnerDeleteAllHttpFixture) -> Unit) = withOwnerDeleteAllAuthorization(database.value) { auth ->
        val candidate = auth.enrolled()
        val connected = OwnerDeleteAllContinuationFixture(auth, candidate, paidApplyContent(auth, candidate, 1))
        test(OwnerDeleteAllHttpFixture(connected))
    }
}
