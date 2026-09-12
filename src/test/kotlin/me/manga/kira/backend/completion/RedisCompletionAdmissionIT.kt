package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.completion.application.BoundedCompletionExecutor
import me.manga.kira.backend.completion.application.CompletionActivation
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.CompletionPermit
import me.manga.kira.backend.completion.application.CompletionPersistence
import me.manga.kira.backend.completion.application.CompletionPublication
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.completion.domain.CompletionErrorCode
import me.manga.kira.backend.completion.domain.CompletionOutcome
import me.manga.kira.backend.completion.domain.CompletionProvider
import me.manga.kira.backend.completion.domain.CompletionProviderLifetime
import me.manga.kira.backend.completion.domain.CompletionStatus
import me.manga.kira.backend.completion.domain.CompletionView
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real pending/pin protocol plus actual local synchronous workers; no remote-provider termination claim. */
@Timeout(20)
class RedisCompletionAdmissionIT {
    @BeforeEach
    @AfterEach
    fun clearRedis() {
        requireNotNull(template.connectionFactory).connection.use { it.serverCommands().flushDb() }
    }

    @Test
    fun `two instances sustain unexpired overlapping pending reservations beyond the first key deadline`() {
        val first = RedisCompletionAdmission(template, properties())
        val second = RedisCompletionAdmission(template, properties())
        val original = first.acquire(USER)
        val firstDeadline = reservations().values.single().toLong()
        assertEquals(firstDeadline, retainedUntil())
        awaitServerTime(firstDeadline - 2_000)
        val overlapping = second.acquire(USER)
        assertTrue(now() < firstDeadline - 750, "Original lease is still valid with scheduling margin")
        original.close()
        val replacement = first.acquire(USER)
        val held = reservations()
        assertCapacityDenied(second, held)

        awaitServerTime(firstDeadline + 50)
        assertCapacityDenied(first, held) // Neither held lease is expired; unrelated rate caps are disabled.
        assertTrue(retainedUntil() >= held.values.maxOf { it.toLong() })
        overlapping.close()
        val next = second.acquire(USER)
        assertCapacityDenied(first, reservations())
        replacement.close()
        next.close()
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `expired and replayed releases cannot touch successor tokens or their deadlines`() {
        val stale = RedisCompletionAdmission(template, properties(250)).acquire(USER)
        val (oldToken, oldDeadline) = reservations().entries.single().let { it.key to it.value.toLong() }
        awaitServerTime(oldDeadline)
        assertFalse(requireNotNull(template.hasKey(KEY))) // Abandonment frees capacity without a close.
        assertEquals(CompletionActivation.EXPIRED, stale.activate())

        val first = RedisCompletionAdmission(template, properties(5_000))
        val second = RedisCompletionAdmission(template, properties(5_000))
        val one = first.acquire(USER)
        val firstToken = reservations().keys.single()
        val two = second.acquire(USER)
        val successors = reservations()
        val secondToken = (successors.keys - firstToken).single()
        val expiry = retainedUntil()
        stale.close()
        stale.close()
        assertEquals(0L, activateAgain(oldToken)) // Expired activation cannot pin a successor either.
        assertEquals(0L, releaseAgain(oldToken)) // Actual Redis replay, not merely the local AtomicBoolean.
        assertEquals(successors, reservations())
        assertEquals(expiry, retainedUntil())
        assertCapacityDenied(first, successors)

        one.close()
        assertEquals(0L, releaseAgain(firstToken))
        assertEquals(successors - firstToken, reservations())
        assertEquals(expiry, retainedUntil())
        two.close()
        assertEquals(0L, releaseAgain(secondToken))
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `shorter pending reservations and turnover never shorten a live longer reservation`() {
        val long = RedisCompletionAdmission(template, properties(5_000)).acquire(USER)
        val (longToken, longDeadline) = reservations().entries.single().let { it.key to it.value.toLong() }
        val shortInstance = RedisCompletionAdmission(template, properties(250))
        val short = shortInstance.acquire(USER)
        val (shortToken, shortDeadline) = (reservations() - longToken).entries.single().let { it.key to it.value.toLong() }
        assertTrue(shortDeadline < longDeadline)
        assertEquals(longDeadline, retainedUntil())
        awaitServerTime(shortDeadline)
        val expired = snapshot()
        assertEquals(-1L, template.execute(SCRIPT, keys(), "acquire", shortToken, "0", "0", "0", "2", "4000"))
        assertEquals(expired, snapshot()) // Reusing even an expired token cannot create a new permit.

        val replacement = shortInstance.acquire(USER) // Prunes only the expired short member.
        val held = reservations()
        assertFalse(shortToken in held)
        assertEquals(longDeadline.toString(), held[longToken])
        assertEquals(longDeadline, retainedUntil())
        short.close()
        assertEquals(held, reservations())
        assertCapacityDenied(shortInstance, held)
        long.close()
        assertEquals(held - longToken, reservations())
        // Removing the longest pending owner may shorten only its harmless tail, never a remaining deadline.
        assertTrue(retainedUntil() >= (held - longToken).values.maxOf { it.toLong() })
        replacement.close()
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `acknowledged peer pins remain persistent beyond pending deadlines and only exact release removes them`() {
        val first = RedisCompletionAdmission(template, properties(250))
        val second = RedisCompletionAdmission(template, properties(250))
        val one = first.acquire(USER)
        val (firstToken, firstDeadline) = reservations().entries.single().let { it.key to it.value.toLong() }
        assertEquals(CompletionActivation.ACTIVATED, one.activate())
        assertEquals(mapOf(firstToken to PINNED), reservations())
        assertEquals(-1L, retainedUntil())
        val pinned = snapshot()
        assertEquals(CompletionActivation.UNAVAILABLE, one.activate())
        assertEquals(-1L, activateAgain(firstToken)) // Actual script duplicate, not only the local single-attempt guard.
        assertEquals(pinned, snapshot())

        val two = second.acquire(USER)
        val (secondToken, secondDeadline) = (reservations() - firstToken).entries.single().let { it.key to it.value.toLong() }
        assertEquals(-1L, retainedUntil(), "A pending peer must never lend an active pin an expiry")
        assertEquals(CompletionActivation.ACTIVATED, two.activate())
        val bothPins = mapOf(firstToken to PINNED, secondToken to PINNED)
        assertCapacityDenied(first, bothPins)
        awaitServerTime(maxOf(firstDeadline, secondDeadline) + 50)
        assertCapacityDenied(second, bothPins)
        assertEquals(-1L, retainedUntil())
        assertEquals(-1L, template.getExpire(KEY, TimeUnit.MILLISECONDS))

        one.close()
        val peer = snapshot()
        assertEquals(mapOf(secondToken to PINNED), reservations())
        assertEquals(-1L, retainedUntil())
        assertEquals(0L, releaseAgain(firstToken))
        assertEquals(peer, snapshot())
        val pending = RedisCompletionAdmission(template, properties()).acquire(USER)
        val pendingOnly = reservations() - secondToken
        assertEquals(-1L, retainedUntil())
        two.close()
        assertEquals(pendingOnly, reservations())
        assertTrue(retainedUntil() >= pendingOnly.values.single().toLong(), "Last-pin release restores the remaining pending lifetime")
        assertEquals(CompletionActivation.ACTIVATED, pending.activate())
        assertEquals(-1L, retainedUntil())
        pending.close()
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `expired pending peers cannot activate and pruning never removes a pin`() {
        val admission = RedisCompletionAdmission(template, properties(250))
        val pinned = admission.acquire(USER)
        val pinnedToken = reservations().keys.single()
        assertEquals(CompletionActivation.ACTIVATED, pinned.activate())
        val pending = admission.acquire(USER)
        val (expiredToken, deadline) = (reservations() - pinnedToken).entries.single().let { it.key to it.value.toLong() }
        awaitServerTime(deadline + 20)
        val before = snapshot()
        assertEquals(CompletionActivation.EXPIRED, pending.activate())
        assertEquals(before, snapshot(), "An expired activation neither pins nor prunes on its own")
        val replacement = admission.acquire(USER)
        val held = reservations()
        assertFalse(expiredToken in held)
        assertEquals(PINNED, held[pinnedToken])
        assertEquals(-1L, retainedUntil())
        assertCapacityDenied(admission, held)
        pending.close()
        assertEquals(0L, releaseAgain(expiredToken))
        assertEquals(held, reservations())
        replacement.close()
        assertEquals(mapOf(pinnedToken to PINNED), reservations())
        assertEquals(-1L, retainedUntil())
        pinned.close()
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `unknown activation acknowledgement grants nothing while the real pin remains until owned cleanup`() {
        val operations = mutableListOf<String>()
        val token = AtomicReference<String>()
        val transport = mock(StringRedisTemplate::class.java) { invocation ->
            if (invocation.method.name == "execute") {
                val script = invocation.getArgument<RedisScript<Long>>(0)
                val scriptKeys = invocation.getArgument<List<String>>(1)
                val args = (invocation.rawArguments[2] as Array<*>).map { it as String }.toTypedArray()
                val operation = args[0]
                operations.add(operation)
                if (operation == "acquire") token.set(args[1]) else assertEquals(token.get(), args[1])
                when (operation) {
                    "acquire", "release" -> template.execute(script, scriptKeys, *args)

                    "activate" -> {
                        assertEquals(1L, template.execute(script, scriptKeys, *args))
                        null // Real mutation happened, but the adapter receives no authoritative acknowledgement.
                    }

                    else -> error("Unexpected Redis operation")
                }
            } else {
                Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val config = properties(250).copy(globalConcurrency = 1)
        val permit = RedisCompletionAdmission(transport, config).acquire(USER)
        val formerDeadline = reservations().values.single().toLong()
        assertEquals(CompletionActivation.UNAVAILABLE, permit.activate())
        assertEquals(listOf("acquire", "activate"), operations, "Unknown activation cannot issue speculative compensation")
        assertEquals(mapOf(token.get() to PINNED), reservations())
        assertEquals(-1L, retainedUntil())
        awaitServerTime(formerDeadline + 50)
        assertCapacityDenied(RedisCompletionAdmission(template, config), reservations(), capacity = 1)
        assertEquals(-1L, retainedUntil())
        permit.close() // The owner has settled: its one owed exact-token release is now legitimate.
        permit.close()
        assertEquals(listOf("acquire", "activate", "release"), operations)
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `real service pending delay past its actual deadline cannot activate or disturb a pinned successor`() {
        val pendingEntered = CountDownLatch(1)
        val returnPending = CountDownLatch(1)
        val successorEntered = CountDownLatch(1)
        val finishSuccessor = CountDownLatch(1)
        val oldToken = AtomicReference<String>()
        val oldDeadline = AtomicLong()
        ServiceFixture(
            properties(500).copy(globalConcurrency = 1),
            onPending = {
                val old = reservations().entries.single()
                oldToken.set(old.key)
                oldDeadline.set(old.value.toLong())
                pendingEntered.countDown()
                awaitIgnoringInterrupt(returnPending)
            },
        ).use { first ->
            ServiceFixture(
                properties().copy(globalConcurrency = 1, timeout = Duration.ofSeconds(10)),
                body = {
                    successorEntered.countDown()
                    awaitIgnoringInterrupt(finishSuccessor)
                    CompletionOutcome.Success("successor result", 0)
                },
            ).use { second ->
                first.drain()
                second.drain()
                val staleCall = ServiceCall(first.service)
                var successorCall: ServiceCall? = null
                try {
                    assertTrue(pendingEntered.await(2, TimeUnit.SECONDS))
                    awaitServerTime(oldDeadline.get() + 20)
                    assertFalse(requireNotNull(template.hasKey(KEY)), "Observe actual pending expiry; never force EXPIRE or DEL")
                    successorCall = ServiceCall(second.service)
                    assertTrue(successorEntered.await(2, TimeUnit.SECONDS))
                    val successor = snapshot()
                    assertEquals(listOf(PINNED), reservations().values.toList())
                    returnPending.countDown()
                    val failure = staleCall.await().exceptionOrNull() as ServiceUnavailableException
                    assertEquals(503, failure.status.value())
                    assertEquals("COMPLETION_CONCURRENCY_LIMIT", failure.code)
                    assertEquals(1L, failure.retryAfterSeconds)
                    assertTrue(first.released.await(2, TimeUnit.SECONDS))
                    assertEquals(listOf(CompletionActivation.EXPIRED), first.activations)
                    assertEquals(0, first.calls.get(), "Expired reservation never authorizes the delayed request's provider")
                    assertEquals(1, first.closes.get())
                    val failed = first.publications.single()
                    assertEquals(CompletionStatus.FAILED, failed.status)
                    assertEquals(CompletionErrorCode.PROVIDER_UNAVAILABLE, failed.errorCode)
                    assertEquals("The completion request could not be completed.", failed.error)
                    assertNull(failed.result)
                    assertEquals(successor, snapshot())
                    assertEquals(0L, activateAgain(oldToken.get(), 1))
                    assertEquals(0L, releaseAgain(oldToken.get(), 1))
                    assertEquals(successor, snapshot())
                    assertEquals(-1L, retainedUntil())
                    assertEquals(0, second.closes.get())
                    finishSuccessor.countDown()
                    assertEquals(CompletionStatus.SUCCEEDED, successorCall.await().getOrThrow().status)
                    assertTrue(second.released.await(2, TimeUnit.SECONDS))
                    assertFalse(requireNotNull(template.hasKey(KEY)))
                } finally {
                    returnPending.countDown()
                    finishSuccessor.countDown()
                    staleCall.await()
                    successorCall?.await()
                }
            }
        }
    }

    @Test
    fun `two real services keep a timed out physical worker pinned beyond its former TTL`() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val formerDeadline = AtomicLong()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val config = properties(250).copy(globalConcurrency = 1, queueTimeout = Duration.ofMillis(500))
        val complete: (String) -> CompletionOutcome = { prompt ->
            maximumActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (prompt == "held") {
                    entered.countDown()
                    awaitIgnoringInterrupt(finish)
                }
                CompletionOutcome.Success("synthetic", 0)
            } finally {
                active.decrementAndGet()
            }
        }
        ServiceFixture(config, onPending = { formerDeadline.set(reservations().values.single().toLong()) }, body = complete).use { first ->
            ServiceFixture(config, body = complete).use { second ->
                first.drain()
                second.drain()
                assertTrue(first.worker.get() !== second.worker.get(), "Separate live service workers could otherwise overlap")
                val held = ServiceCall(first.service, "held")
                try {
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val failed = held.await().getOrThrow()
                    assertEquals(CompletionStatus.FAILED, failed.status)
                    assertEquals(CompletionErrorCode.PROVIDER_TIMEOUT, failed.errorCode)
                    assertEquals(listOf(CompletionActivation.ACTIVATED), first.activations)
                    assertEquals(1, active.get())
                    assertEquals(0, first.closes.get(), "Caller timeout is not physical worker exit")
                    val pinned = snapshot()
                    assertEquals(listOf(PINNED), reservations().values.toList())
                    awaitServerTime(formerDeadline.get() + 50)
                    assertEquals(pinned, snapshot())
                    assertEquals(-1L, retainedUntil())
                    assertEquals(-1L, template.getExpire(KEY, TimeUnit.MILLISECONDS))
                    val rejection = assertThrows<ServiceUnavailableException> { second.service.create(USER, "successor", null) }
                    assertEquals("COMPLETION_CONCURRENCY_LIMIT", rejection.code)
                    assertEquals(1L, rejection.retryAfterSeconds)
                    assertEquals(0, second.calls.get())
                    assertTrue(second.activations.isEmpty())
                    assertEquals(0, second.pending.get())
                    assertEquals(1, active.get())
                    assertEquals(pinned, snapshot())

                    finish.countDown()
                    assertTrue(first.released.await(2, TimeUnit.SECONDS), "Actual owned exit performs the deferred Redis release")
                    assertEquals(1, first.closes.get())
                    assertEquals(CompletionStatus.SUCCEEDED, second.service.create(USER, "successor", null).status)
                    assertEquals(1, first.calls.get())
                    assertEquals(1, second.calls.get())
                    assertEquals(1, second.closes.get())
                    assertEquals(0, active.get())
                    assertEquals(1, maximumActive.get())
                    assertFalse(requireNotNull(template.hasKey(KEY)))
                } finally {
                    finish.countDown()
                    held.await()
                }
            }
        }
    }

    @Test
    fun `legacy malformed oversized and expiring pin state is refused without repair on every operation`() {
        val admission = RedisCompletionAdmission(template, properties(15_000).copy(perUserPerMinute = 10, globalPerMinute = 10, perUserDailyQuota = 10))
        listOf(
            "legacy-string", "legacy-zset", "metadata", "fraction", "infinite", "negative", "zero", "leading-zero", "exponent",
            "range", "token", "capacity", "persistent-pending", "early-expiry", "expiring-pin", "mixed-expiring-pin",
        ).forEach { fault ->
            clearRedis()
            val permit = admission.acquire(USER)
            val (token, deadline) = reservations().entries.single().let { it.key to it.value.toLong() }
            when (fault) {
                "legacy-string" -> {
                    template.delete(KEY)
                    template.opsForValue().set(KEY, "1", Duration.ofMinutes(1))
                }

                "legacy-zset" -> {
                    template.delete(KEY)
                    template.opsForZSet().add(KEY, token, deadline.toDouble())
                    template.expireAt(KEY, Instant.ofEpochMilli(deadline))
                }

                "metadata" -> template.opsForHash<String, String>().put(KEY, "version", "1")

                "fraction" -> template.opsForHash<String, String>().put(KEY, token, "$deadline.5")

                "infinite" -> template.opsForHash<String, String>().put(KEY, token, "inf")

                "negative" -> template.opsForHash<String, String>().put(KEY, token, "-1")

                "zero" -> template.opsForHash<String, String>().put(KEY, token, "0")

                "leading-zero" -> template.opsForHash<String, String>().put(KEY, token, "0$deadline")

                "exponent" -> template.opsForHash<String, String>().put(KEY, token, "1e15")

                "range" -> template.opsForHash<String, String>().put(KEY, token, (MAX_INTEGER + 1).toString())

                "token" -> template.opsForHash<String, String>().put(KEY, "not-a-uuid", deadline.toString())

                "capacity" -> repeat(2) { template.opsForHash<String, String>().put(KEY, UUID.randomUUID().toString(), deadline.toString()) }

                "persistent-pending" -> template.persist(KEY)

                "early-expiry" -> template.expireAt(KEY, Instant.ofEpochMilli(deadline - 1_000))

                "expiring-pin" -> template.opsForHash<String, String>().put(KEY, token, PINNED)

                "mixed-expiring-pin" -> template.opsForHash<String, String>().put(KEY, UUID.randomUUID().toString(), PINNED)
            }
            val before = snapshot()
            assertUnavailable(assertThrows<ServiceUnavailableException> { admission.acquire(USER) })
            assertEquals(before, snapshot(), fault)
            assertEquals(CompletionActivation.UNAVAILABLE, permit.activate(), fault)
            assertEquals(before, snapshot(), fault)
            // Backend15 outcome-first containment: invalid cleanup is unconfirmed, not a replacement 429.
            permit.close()
            permit.close()
            assertEquals(before, snapshot(), fault)
        }
    }

    @Test
    fun `enabled rate state is preflighted before any earlier counter or lease mutation`() {
        val admission = RedisCompletionAdmission(template, properties(15_000).copy(perUserPerMinute = 10, globalPerMinute = 10, perUserDailyQuota = 10))
        listOf("1.5", "01", MAX_INTEGER.toString(), "wrong-type").forEach { fault ->
            clearRedis()
            val permit = admission.acquire(USER)
            val rateKey = keys()[2]
            if (fault == "wrong-type") {
                template.delete(rateKey)
                template.opsForList().rightPush(rateKey, "value")
            } else {
                template.opsForValue().set(rateKey, fault)
            }
            val before = snapshot()
            assertUnavailable(assertThrows<ServiceUnavailableException> { admission.acquire(USER) })
            assertEquals(before, snapshot(), fault)
            permit.close() // Release neither reads nor changes rate keys.
            assertEquals(before - KEY, snapshot() - KEY, fault)
        }
    }

    @Test
    fun `token collision and invalid protocol arguments refuse before writes`() {
        val admission = RedisCompletionAdmission(template, properties(5_000))
        val permit = admission.acquire(USER)
        val token = reservations().keys.single()
        val before = snapshot()
        val arguments = listOf("acquire", UUID.randomUUID().toString(), "10", "10", "10", "2", "4000")
        listOf(1 to token, 2 to "1.5", 5 to "4097", 6 to "0", 6 to MAX_INTEGER.toString()).forEach { (index, invalid) ->
            val args = arguments.toMutableList().also { it[index] = invalid }
            assertEquals(-1L, template.execute(SCRIPT, keys(), *args.toTypedArray()))
            assertEquals(before, snapshot())
        }
        val aliased = keys().toMutableList().also { it[1] = it[0] }
        assertEquals(-1L, template.execute(SCRIPT, aliased, *arguments.toTypedArray()))
        assertEquals(-1L, activateAgain("invalid-token"))
        assertEquals(-1L, releaseAgain("invalid-token"))
        assertEquals(before, snapshot())
        permit.close()
    }

    @Test
    fun `exact deadline arithmetic rejects overflow and retains the greatest canonical decimal`() {
        val peer = UUID.randomUUID().toString()
        template.opsForHash<String, String>().put(KEY, peer, MAX_INTEGER.toString())
        assertTrue(requireNotNull(template.expireAt(KEY, Instant.ofEpochMilli(MAX_INTEGER))))
        val before = snapshot()
        val overflowing = properties().copy(queueTimeout = Duration.ofMillis(1), timeout = Duration.ofMillis(MAX_INTEGER / 2 - 1))
        assertUnavailable(assertThrows<ServiceUnavailableException> { RedisCompletionAdmission(template, overflowing).acquire(USER) })
        assertEquals(before, snapshot())
        val permit = RedisCompletionAdmission(template, properties()).acquire(USER)
        assertEquals(MAX_INTEGER.toString(), reservations()[peer])
        assertEquals(MAX_INTEGER, retainedUntil()) // Decimal integer formatting, not scientific/rounded tostring.
        permit.close()
        assertEquals(before, snapshot())
    }

    private fun assertCapacityDenied(admission: RedisCompletionAdmission, expected: Map<String, String>, capacity: Int = 2) {
        assertEquals(capacity, expected.size)
        assertTrue(expected.values.all { it == PINNED || it.toLong() > now() }, "Every counted owner must still be live before rejection")
        val error = assertThrows<ServiceUnavailableException> { admission.acquire(USER) }
        assertEquals("COMPLETION_CONCURRENCY_LIMIT", error.code)
        assertEquals(503, error.status.value())
        assertEquals(1L, error.retryAfterSeconds)
        assertEquals(expected, reservations())
        assertTrue(expected.values.all { it == PINNED || it.toLong() > now() }, "A scheduling stall must not count an expired pending token as protected")
    }

    private fun assertUnavailable(error: ServiceUnavailableException) {
        assertEquals("COMPLETION_COORDINATION_UNAVAILABLE", error.code)
        assertEquals(503, error.status.value())
        assertEquals(5L, error.retryAfterSeconds)
    }

    private fun reservations(): Map<String, String> = template.opsForHash<String, String>().entries(KEY).also { state ->
        state.forEach { (token, value) ->
            assertEquals(4, UUID.fromString(token).version())
            if (value != PINNED) {
                assertTrue(value.matches(Regex("[1-9][0-9]{0,15}")))
                assertTrue(value.toLong() <= MAX_INTEGER)
            }
        }
    }

    private fun snapshot() = keys().associateWith { template.dump(it)?.toList() to requireNotNull(template.execute(EXPIRY, listOf(it))) }

    private fun retainedUntil(): Long = requireNotNull(template.execute(EXPIRY, listOf(KEY)))

    private fun now(): Long = requireNotNull(template.execute(NOW, emptyList()))

    private fun releaseAgain(token: String, capacity: Int = 2): Long =
        requireNotNull(template.execute(SCRIPT, listOf(KEY), "release", token, capacity.toString()))

    private fun activateAgain(token: String, capacity: Int = 2): Long =
        requireNotNull(template.execute(SCRIPT, listOf(KEY), "activate", token, capacity.toString()))

    private fun awaitServerTime(deadline: Long) {
        val stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (now() < deadline && System.nanoTime() < stop) Thread.sleep(10)
        assertTrue(now() >= deadline, "Redis TIME must reach the actual member deadline within the bounded wait")
    }

    private fun keys() = listOf("$PREFIX:user-minute:$USER", "$PREFIX:global-minute", "$PREFIX:user-day:$USER", KEY)

    private fun properties(timeoutMs: Long = 1_000) = KiraCompletionProperties(
        coordinationBackend = "redis",
        instanceCount = 2,
        globalConcurrency = 2,
        perUserPerMinute = 0,
        globalPerMinute = 0,
        perUserDailyQuota = 0,
        queueTimeout = Duration.ofMillis(timeoutMs),
        timeout = Duration.ofMillis(timeoutMs),
    )

    private fun awaitIgnoringInterrupt(gate: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                gate.await()
                if (interrupted) Thread.currentThread().interrupt()
                return
            } catch (_: InterruptedException) {
                interrupted = true // Remain in synchronous local work until the explicit test-owned gate opens.
            }
        }
    }

    /** Existing service/persistence seam with actual Redis; the separate PostgreSQL HTTP IT proves commits. */
    private inner class ServiceFixture(
        config: KiraCompletionProperties,
        onPending: () -> Unit = {},
        body: (String) -> CompletionOutcome = { CompletionOutcome.Success("synthetic", 0) },
    ) : AutoCloseable {
        val calls = AtomicInteger()
        val pending = AtomicInteger()
        val activations = CopyOnWriteArrayList<CompletionActivation>()
        val closes = AtomicInteger()
        val released = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val publications = CopyOnWriteArrayList<CompletionView>()
        private lateinit var executor: BoundedCompletionExecutor
        private val raw = RedisCompletionAdmission(template, config)
        private val admission = object : CompletionAdmission {
            override fun acquire(userId: UUID): CompletionPermit {
                val permit = raw.acquire(userId)
                return object : CompletionPermit {
                    override fun activate(): CompletionActivation = permit.activate().also { activations.add(it) }

                    override fun close() {
                        permit.close()
                        closes.incrementAndGet()
                        released.countDown()
                    }
                }
            }
        }
        private val persistence = mock(CompletionPersistence::class.java) { invocation ->
            when (invocation.method.name) {
                "createPending" -> {
                    pending.incrementAndGet()
                    onPending()
                    UUID.randomUUID()
                }

                "markRunning" -> {
                    worker.set(Thread.currentThread())
                    true
                }

                "storeOutcome" -> {
                    val view = CompletionView(
                        id = invocation.getArgument(0),
                        userId = USER,
                        provider = "redis-lifetime-test",
                        model = "configured-model",
                        status = invocation.getArgument(1),
                        result = invocation.getArgument(2),
                        error = invocation.getArgument(3),
                        errorCode = invocation.getArgument(4),
                        createdAt = Instant.EPOCH,
                    )
                    publications.add(view)
                    CompletionPublication(view, won = true)
                }

                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        private val provider = object : CompletionProvider {
            override val name = "redis-lifetime-test"

            // Each injected body above is synchronous local work; none starts detached tasks or a transport.
            override val lifetime = CompletionProviderLifetime.SYNCHRONOUS

            override fun complete(prompt: String, model: String): CompletionOutcome {
                calls.incrementAndGet()
                return body(prompt)
            }
        }
        val service = CompletionService(
            listOf(provider),
            config.copy(provider = provider.name, defaultModel = "configured-model", executorThreads = 1, queueCapacity = 1),
            persistence,
            admission,
            mock(KiraMetrics::class.java) { invocation ->
                if (invocation.method.name == "bindCompletionExecutor") executor = invocation.getArgument(0)
                Answers.RETURNS_DEFAULTS.answer(invocation)
            },
        )

        fun drain() {
            worker.set(executor.submit(Callable { Thread.currentThread() }).get(3, TimeUnit.SECONDS))
        }

        override fun close() {
            try {
                drain() // Same-worker sentinel observes actual body settlement, not a canceled Future.
            } finally {
                service.shutdown()
                worker.get()?.let {
                    it.join(3_000)
                    assertFalse(it.isAlive, "Owned provider worker did not stop")
                }
            }
        }
    }

    private class ServiceCall(service: CompletionService, prompt: String = "synthetic") {
        private val outcome = AtomicReference<Result<CompletionView>?>()
        private val caller = Thread { outcome.set(runCatching { service.create(USER, prompt, null) }) }.also { it.start() }

        fun await(): Result<CompletionView> {
            caller.join(3_000)
            assertFalse(caller.isAlive, "Owned request caller did not stop")
            return requireNotNull(outcome.get())
        }
    }

    companion object {
        private const val PREFIX = "kira:completion-admission"
        private const val KEY = "$PREFIX:concurrency"
        private const val PINNED = "PINNED"
        private const val MAX_INTEGER = 9_007_199_254_740_991L
        private val USER = UUID.randomUUID()

        // The same production resource, not a transcribed/simulated release protocol.
        private val SCRIPT = DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("redis/completion-admission.lua"))
            resultType = Long::class.java
        }
        private val EXPIRY = DefaultRedisScript("return redis.call('PEXPIRETIME', KEYS[1])", Long::class.java)
        private val NOW = DefaultRedisScript(
            "local t = redis.call('TIME'); return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
            Long::class.java,
        )
        private val redis = GenericContainer(DockerImageName.parse("redis:7.4.7-alpine")).withExposedPorts(6379).also { it.start() }
        private val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofSeconds(2)).build(),
        ).also {
            it.afterPropertiesSet()
            it.start()
        }
        private val template = StringRedisTemplate(factory).also { it.afterPropertiesSet() }

        @JvmStatic
        @AfterAll
        fun shutdown() {
            try {
                factory.destroy()
            } finally {
                redis.stop()
            }
        }
    }
}
