package me.manga.kira.backend.user

import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.application.UserAdminService
import me.manga.kira.backend.user.domain.LastAdminException
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * Test 42 (PLAN §11) — `ConcurrentLastAdminDisableIT`: with exactly two enabled admins, two truly
 * concurrent disable requests (one per admin) → exactly one succeeds and one gets 409; at least one
 * enabled ADMIN always remains. Proves the `security_state` row lock (PLAN §4.4/§5) defeats the
 * count-then-disable race a bare check would lose under READ COMMITTED.
 */
class ConcurrentLastAdminDisableIT
    @Autowired
    constructor(
        private val userAdminService: UserAdminService,
        private val users: UserRepository,
    ) : AbstractIntegrationTest() {

        private fun createAdmin(email: String) =
            users.create(email, "{bcrypt}\$2a\$10\$notarealhashjustfortheconcurrencyguard.", Role.ADMIN)

        @Test
        fun `two concurrent disables leave exactly one enabled admin`() {
            val adminA = createAdmin("admin-a@example.com")
            val adminB = createAdmin("admin-b@example.com")
            assertEquals(2L, users.countEnabledAdmins())

            val results = CopyOnWriteArrayList<Result<Unit>>()
            val barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val tasks =
                    listOf(adminA.id, adminB.id).map { id ->
                        Callable {
                            barrier.await() // release both threads at the same instant
                            results.add(runCatching { userAdminService.disable(id) })
                        }
                    }
                pool.invokeAll(tasks).forEach { it.get() }
            } finally {
                pool.shutdown()
            }

            val successes = results.count { it.isSuccess }
            val failures = results.filter { it.isFailure }
            assertEquals(1, successes, "exactly one disable must succeed")
            assertEquals(1, failures.size, "exactly one disable must fail")
            assertTrue(
                failures.single().exceptionOrNull() is LastAdminException,
                "the losing disable must be the last-admin 409",
            )
            assertEquals(1L, users.countEnabledAdmins(), "one enabled admin must remain")
        }
    }
