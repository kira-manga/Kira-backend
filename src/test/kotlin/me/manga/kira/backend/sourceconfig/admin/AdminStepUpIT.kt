package me.manga.kira.backend.sourceconfig.admin

import ch.qos.logback.classic.LoggerContext
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.UnauthorizedException
import me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLease
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTerminalCall
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PoolActorCustody
import me.manga.kira.backend.common.infrastructure.persistence.PoolLifecycle
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.AdminStepUpService
import me.manga.kira.backend.security.AuthLoginAttempt
import me.manga.kira.backend.security.AuthThrottleService
import me.manga.kira.backend.support.PgLifecycleLocalRunCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.orm.jpa.EntityManagerFactoryInfo
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListeners
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.support.AbstractTestExecutionListener
import org.springframework.test.context.support.DirtiesContextTestExecutionListener
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@Import(AdminStepUpIT.SourceTransactionCustomization::class, AdminStepUpIT.ControllerOwnedPostgres::class, AdminStepUpIT.SourceSchedulerConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
    listeners = [AdminStepUpIT.VerifyExternalSchedulerShutdown::class],
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS,
)
class AdminStepUpIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var stepUp: AdminStepUpService

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var admission: OrdinaryPersistenceAdmission

    @Autowired
    @Qualifier("ordinaryDataSource")
    private lateinit var ordinary: GuardedDataSource

    @Autowired
    @Qualifier("complaintDeletionDataSource")
    private lateinit var deletion: GuardedDataSource

    @Autowired
    private lateinit var probe: SourceTransactionProbe

    @MockitoSpyBean(name = "passwordEncoder")
    private lateinit var observedPasswords: PasswordEncoder

    @MockitoSpyBean(name = "authThrottleService")
    private lateinit var observedThrottle: AuthThrottleService

    private val dependencyCalls = mutableListOf<String>()
    private var afterPassword: () -> Unit = {}

    @BeforeEach
    fun observeReleasedVerification() {
        externalSchedulerOwner.pool = ordinaryLifecycle()
        dependencyCalls.clear()
        afterPassword = {}
        probe.leases.clear()
        probe.failAfterBegin = false
        probe.failInitializer = false
        probe.failedInitializerEntityManager = null
        doAnswer { invocation ->
            observeDependency("password")
            try {
                val matched = invocation.callRealMethod() as Boolean
                if (matched) afterPassword()
                matched
            } finally {
                assertReleased()
            }
        }.`when`(observedPasswords).matches(anyString(), anyString())
        doAnswer { invocation ->
            observeDependency("check")
            try {
                observeAttempt(invocation.callRealMethod() as AuthLoginAttempt)
            } finally {
                assertReleased()
            }
        }.`when`(observedThrottle).beginLoginAttempt(anyString(), anyString())
    }

    /** Observe the actual returned reservation, not a replacement provider or a fabricated successful receipt. */
    private fun observeAttempt(real: AuthLoginAttempt): AuthLoginAttempt = spy(real).also { attempt ->
        doAnswer { invocation ->
            observeDependency(if (invocation.getArgument<Boolean>(0)) "success" else "failure")
            try {
                invocation.callRealMethod()
            } finally {
                assertReleased()
            }
        }.`when`(attempt).complete(anyBoolean())
        doAnswer { invocation ->
            assertReleased() // Includes no-op close after a claimed completion and unexpected-exit close.
            try {
                invocation.callRealMethod()
            } finally {
                assertReleased()
            }
        }.`when`(attempt).close()
    }

    @Test
    fun `password step-up stores only a hash and proof is one-time`() {
        assertTrue(context.getBeansOfType(LogbackMetrics::class.java).isEmpty())
        assertTrue((LoggerFactory.getILoggerFactory() as LoggerContext).turboFilterList.isEmpty())
        assertFalse(context.getBeansOfType(MeterRegistry::class.java).isEmpty())

        val response =
            mockMvc.post("/api/v1/admin/step-up") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
            }.andExpect {
                status { isOk() }
                jsonPath("$.scope") { value("source-admin-mutation") }
                jsonPath("$.token") { isNotEmpty() }
                jsonPath("$.expiresAt") { exists() }
            }.andReturn().response
        val token = objectMapper.readTree(response.contentAsString).get("token").asText()
        val storedHash =
            jdbcTemplate.queryForObject(
                "SELECT token_hash FROM admin_step_up_grants WHERE user_id = ?",
                String::class.java,
                admin.id,
            )
        assertEquals(64, storedHash?.length)
        assertNotEquals(token, storedHash)
        assertEquals(Sha256.hexUtf8(token), storedHash)
        assertTrue(response.getHeader("Cache-Control")?.contains("no-store") == true)
        assertEquals(listOf("check", "password", "success"), dependencyCalls)
        assertEquals(3, probe.leases.size, "Snapshot, cleanup and final issuance use three real released phase leases.")
        assertReleased()

        stepUp.requireSourceMutation(admin.id, token)
        val second =
            assertThrows(UnauthorizedException::class.java) {
                stepUp.requireSourceMutation(admin.id, token)
            }
        assertEquals("ADMIN_STEP_UP_REQUIRED", second.code)
    }

    @Test
    fun `wrong password and expired proof fail closed`() {
        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to "wrong-password"))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.errors[0].code") { value("INVALID_STEP_UP_CREDENTIALS") }
        }
        assertEquals(listOf("check", "password", "failure"), dependencyCalls)
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))

        val issued = stepUp.issue(admin.id, ADMIN_PASSWORD, "127.0.0.1")
        jdbcTemplate.update(
            "UPDATE admin_step_up_grants " +
                "SET created_at = now() - interval '2 seconds', expires_at = now() - interval '1 second'",
        )
        val expired =
            assertThrows(UnauthorizedException::class.java) {
                stepUp.requireSourceMutation(admin.id, issued.token)
            }
        assertEquals("ADMIN_STEP_UP_REQUIRED", expired.code)
    }

    @Test
    fun `lost real throttle acknowledgement refuses HTTP proof before cleanup or issuance`() {
        afterPassword = { observedThrottle.clearAll() }
        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
        }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.errors[0].code") { value("TOO_MANY_REQUESTS") }
            jsonPath("$.token") { doesNotExist() }
        }
        assertEquals(listOf("check", "password", "success"), dependencyCalls)
        assertEquals(1, probe.leases.size, "Only the released snapshot precedes the refused completion acknowledgement.")
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))
        assertReleased()
    }

    @Test
    fun `ordinary Boot identities and readiness work while all complaint and deletion admission stays closed`() {
        assertSame(ordinary, context.getBean("dataSource"))
        assertSame(context.getBean("transactionManager"), context.getBean("ordinaryJpaTransactionManager"))
        assertSame(jdbcTemplate, context.getBean("ordinaryJdbcTemplate"))
        assertSame(ordinary, jdbcTemplate.dataSource)
        val factories = context.getBeansOfType(EntityManagerFactory::class.java)
        assertEquals(1, factories.size)
        assertSame(ordinary, (factories.values.single() as EntityManagerFactoryInfo).dataSource)
        assertEquals(2, context.getBeansOfType(DataSource::class.java).size)
        assertEquals(1, context.getBeansOfType(JdbcConnectionDetails::class.java).size)
        assertTrue(ordinary.sourceOnlyComposition())
        assertFalse(ordinary.isWrapperFor(HikariDataSource::class.java))
        assertThrows(SQLException::class.java) { ordinary.unwrap(HikariDataSource::class.java) }
        assertTrue(ordinary.ordinaryPoolSize() > 1, "A closed complaint gate must not depend on the P=1 special case.")
        assertNull(admission.tryComplaintBoundary())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, deletion.prepareDeletion())
        assertThrows(SQLException::class.java) { deletion.connection }

        mockMvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
        mockMvc.get("/actuator/health/liveness").andExpect { status { isOk() } }
        assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, deletion.observePreparation())
        assertTrue(closedCounters().all { it })
        assertReleased()
    }

    @Test
    fun `real external scheduler maintenance leaves HTTP source issuance ready and all complaint deletion gates closed`() {
        val scheduler = externalSchedulerOwner.executor
        assertTrue(
            scheduler.firstHousekeeper.await(5, TimeUnit.SECONDS),
            "Observe the actual configured 100ms HouseKeeper, not a synthetic timer or elapsed-time receipt.",
        )
        val lifecycle = ordinaryLifecycle()
        val tasks = checkNotNull(lifecycle.actorSnapshot().scheduledTasks)
        assertFalse(tasks.sealed)
        assertNull(lifecycle.actorSnapshot().firstFailure)
        assertTrue(ordinary.businessReady())
        assertFalse(scheduler.authorityEscaped.get())
        assertFalse(scheduler.isShutdown)
        assertFalse(scheduler.removeOnCancelPolicy)
        assertTrue(scheduler.executeExistingDelayedTasksAfterShutdownPolicy)
        assertEquals(1, scheduler.corePoolSize)
        assertSame(
            scheduler.housekeeperThread.get(),
            scheduler.submit<Thread> {
                assertFalse(PoolActorCustody.currentThreadOwnsActorFrame())
                assertFalse(lifecycle.isAuthenticPoolCaller())
                Thread.currentThread()
            }.get(5, TimeUnit.SECONDS),
        )
        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { isNotEmpty() }
        }
        assertEquals(listOf("check", "password", "success"), dependencyCalls)
        assertEquals(3, probe.leases.size)
        assertReleased() // Healthy recurring maintenance is not part of this exact lease/phase proof.
        assertNull(admission.tryComplaintBoundary())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, deletion.prepareDeletion())
        assertThrows(SQLException::class.java) { deletion.connection }
        assertTrue(closedCounters().all { it })
        mockMvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
        assertNull(lifecycle.actorSnapshot().firstFailure)
    }

    @Test
    fun `live HTTP issuance rechecks changed hash enabled and role after released password verification`() {
        val originalHash = jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String::class.java, admin.id)!!
        for (change in listOf("hash", "enabled", "role")) {
            jdbcTemplate.update("UPDATE users SET password_hash = ?, enabled = true, role = 'ADMIN' WHERE id = ?", originalHash, admin.id)
            var changed = false
            afterPassword = {
                assertReleased()
                when (change) {
                    "hash" -> jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ?", originalHash + "changed", admin.id)
                    "enabled" -> jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", admin.id)
                    "role" -> jdbcTemplate.update("UPDATE users SET role = 'USER' WHERE id = ?", admin.id)
                }
                changed = true
            }
            mockMvc.post("/api/v1/admin/step-up") {
                header("Authorization", "Bearer $adminToken")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
            }.andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.errors[0].code") { value("ADMIN_STEP_UP_UNAVAILABLE") }
                jsonPath("$.token") { doesNotExist() }
            }
            assertTrue(changed)
            assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))
            assertTrue(closedCounters().all { it })
            assertReleased()
        }
    }

    @Test
    fun `throwing configured afterBegin listener leaves no grant or holder and a subsequent live request succeeds`() {
        probe.failAfterBegin = true
        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.errors[0].code") { value("ADMIN_STEP_UP_UNAVAILABLE") }
        }
        assertFalse(probe.failAfterBegin, "The failure hook must run after the actual Spring begin and holder binding.")
        assertEquals(1, probe.leases.size)
        assertTrue(dependencyCalls.isEmpty(), "A failed snapshot must not reach throttle or password work.")
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))
        assertReleased()

        val response = mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { isNotEmpty() }
        }.andReturn().response
        val token = objectMapper.readTree(response.contentAsString).get("token").asText()
        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_step_up_grants WHERE token_hash = ?",
                Long::class.java,
                Sha256.hexUtf8(token),
            ),
        )
        assertReleased()
    }

    @Test
    fun `throwing configured EntityManager initializer closes its unhanded EM and a subsequent live request succeeds`() {
        probe.failInitializer = true
        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.errors[0].code") { value("ADMIN_STEP_UP_UNAVAILABLE") }
            jsonPath("$.token") { doesNotExist() }
        }
        assertFalse(probe.failInitializer, "The configured initializer must fail before Spring receives the real EM.")
        val failedEntityManager = checkNotNull(probe.failedInitializerEntityManager)
        assertFalse(failedEntityManager.isOpen, "Retention alone is not cleanup: the actual unhanded EM must close.")
        assertTrue(probe.leases.isEmpty(), "This initializer fails before transaction begin and any phase SQL.")
        assertTrue(dependencyCalls.isEmpty(), "A failed snapshot must not reach throttle or password work.")
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))
        assertTrue(closedCounters().all { it })
        assertReleased()

        mockMvc.post("/api/v1/admin/step-up") {
            header("Authorization", "Bearer $adminToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("password" to ADMIN_PASSWORD))
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { isNotEmpty() }
        }
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM admin_step_up_grants", Long::class.java))
        assertFalse(failedEntityManager.isOpen)
        assertReleased()
    }

    private fun observeDependency(name: String) {
        assertFalse(probe.leases.isEmpty(), "Verification must follow an actual guarded snapshot, not a mock persistence result.")
        assertReleased()
        dependencyCalls.add(name)
    }

    private fun assertReleased() {
        assertNull(PersistencePhaseOwnership.current())
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, admission.activeOwners())
        assertTrue(probe.leases.all { it.completion.quiescent() })
        requireConnectionFree()
    }

    private fun closedCounters(): List<Boolean> = jdbcTemplate.query(
        "SELECT configuration_closed AND configuration_hash IS NULL AND hard_limit = 0 AND actual_units = 0 FROM complaint_capacity_counters",
        { result, _ -> result.getBoolean(1) },
    ).also { assertEquals(22, it.size) }

    private fun ordinaryLifecycle(): PoolLifecycle = GuardedDataSource::class.java.getDeclaredField("lifecycle").let { field ->
        check(field.trySetAccessible()) // Observe our own retained owner only, never private Hikari/JDK/native fields.
        field.get(ordinary) as PoolLifecycle
    }

    @TestConfiguration(proxyBeanMethods = false)
    internal class SourceSchedulerConfiguration {
        @Bean(name = ["sourceExternalSchedulerOwner"], destroyMethod = "close")
        fun sourceExternalSchedulerOwner(): SourceSchedulerOwner = externalSchedulerOwner

        companion object {
            @Bean
            @JvmStatic
            fun schedulerOwnerBeforeOrdinaryDataSource(): BeanFactoryPostProcessor = BeanFactoryPostProcessor { factory ->
                val definition = factory.getBeanDefinition("dataSource")
                val dependencies = definition.dependsOn?.toList().orEmpty() + "sourceExternalSchedulerOwner"
                definition.setDependsOn(*dependencies.distinct().toTypedArray())
            }
        }
    }

    /** Test-only owner; the scheduler itself is not a Spring bean or an alternative datasource. */
    internal class SourceSchedulerOwner : AutoCloseable {
        val executor = SourceScheduledExecutor()

        @Volatile
        var pool: PoolLifecycle? = null
        val ended = AtomicBoolean()
        val failure = AtomicReference<Throwable>()

        override fun close() {
            try {
                val retained = pool
                if (retained != null) {
                    assertEquals(
                        PersistenceTerminalCall.RETURNED,
                        retained.firstCloseOutcome(),
                        "Spring must destroy the guarded datasource BEFORE its test scheduler owner.",
                    )
                    assertFalse(executor.isShutdown, "Hikari/guarded teardown must not stop the shared executor.")
                    executor.submit {
                        assertFalse(retained.isAuthenticPoolCaller())
                        assertFalse(PoolActorCustody.currentThreadOwnsActorFrame())
                    }.get(5, TimeUnit.SECONDS)
                }
                assertFalse(executor.authorityEscaped.get())
            } catch (problem: Throwable) {
                failure.compareAndSet(null, problem)
            } finally {
                try {
                    executor.shutdownNow() // Exclusively the external TEST owner's action.
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                    ended.set(true)
                } catch (problem: Throwable) {
                    failure.compareAndSet(null, problem)
                }
            }
        }
    }

    internal class SourceScheduledExecutor :
        ScheduledThreadPoolExecutor(
            1,
            java.util.concurrent.ThreadFactory { command ->
                Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false).name("source-endpoint-external-scheduler").unstarted(command)
            },
        ) {
        val firstHousekeeper = CountDownLatch(1)
        val housekeeperThread = AtomicReference<Thread>()
        val authorityEscaped = AtomicBoolean()

        init {
            removeOnCancelPolicy = false
        }

        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            val housekeeper = initialDelay == 100L && unit === TimeUnit.MILLISECONDS
            return super.scheduleWithFixedDelay(
                {
                    command.run()
                    if (PoolActorCustody.currentThreadOwnsActorFrame()) authorityEscaped.set(true)
                    if (housekeeper) {
                        housekeeperThread.set(Thread.currentThread())
                        firstHousekeeper.countDown()
                    }
                },
                initialDelay,
                delay,
                unit,
            )
        }

        override fun afterExecute(runnable: Runnable, failure: Throwable?) {
            if (PoolActorCustody.currentThreadOwnsActorFrame()) authorityEscaped.set(true)
            super.afterExecute(runnable, failure)
        }
    }

    /** afterTestClass callbacks run in reverse order: verify only AFTER the default dirty-context close. */
    internal class VerifyExternalSchedulerShutdown : AbstractTestExecutionListener() {
        override fun getOrder(): Int = DirtiesContextTestExecutionListener.ORDER - 1

        override fun afterTestClass(ctx: TestContext) {
            // Never obtain/reload a closed application context. Spring performs the normal cache
            // eviction/destruction; propagate the test owner's recorded assertions afterwards.
            externalSchedulerOwner.failure.get()?.let { throw it }
            assertTrue(externalSchedulerOwner.ended.get())
        }
    }

    companion object {
        private val externalSchedulerOwner = SourceSchedulerOwner() // Inert until the real Hikari submits work.

        @JvmStatic
        @DynamicPropertySource
        fun externalSchedulerProperty(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.hikari.scheduled-executor") { externalSchedulerOwner.executor }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    internal class SourceTransactionCustomization {
        @Bean
        fun sourceTransactionProbe(): SourceTransactionProbe = SourceTransactionProbe()

        @Bean
        fun sourceTransactionCustomizer(probe: SourceTransactionProbe): TransactionManagerCustomizer<JpaTransactionManager> =
            TransactionManagerCustomizer { manager ->
                manager.setEntityManagerInitializer { entityManager ->
                    if (PersistencePhaseOwnership.current() != null && probe.failInitializer) {
                        probe.failInitializer = false
                        probe.failedInitializerEntityManager = entityManager
                        assertTrue(entityManager.isOpen)
                        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
                        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                        error("Synthetic live source EntityManager initializer failure.")
                    }
                }
                manager.setTransactionExecutionListeners(
                    listOf(
                        object : TransactionExecutionListener {
                            override fun afterBegin(transaction: TransactionExecution, beginFailure: Throwable?) {
                                if (PersistencePhaseOwnership.current() == null || beginFailure != null) return
                                val holder = TransactionSynchronizationManager.getResource(checkNotNull(manager.dataSource)) as ConnectionHolder
                                probe.leases.add(ownedPoolLease(holder.connection))
                                if (probe.failAfterBegin) {
                                    probe.failAfterBegin = false
                                    error("Synthetic live source afterBegin failure.")
                                }
                            }
                        },
                    ),
                )
            }
    }

    internal class SourceTransactionProbe {
        val leases = mutableListOf<PersistenceJdbcLease>()
        var failAfterBegin = false
        var failInitializer = false
        var failedInitializerEntityManager: EntityManager? = null
    }

    /** Existing run/class/nonce/generation-verified local fixture; never an arbitrary datasource URL override. */
    @TestConfiguration(proxyBeanMethods = false)
    @Conditional(PgLifecycleLocalRunCondition::class)
    internal class ControllerOwnedPostgres {
        @Bean(destroyMethod = "close")
        fun sourceEndpointDatabase(): PgLifecycleDatabaseFixture = PgLifecycleDatabaseFixture(AdminStepUpIT::class.java).apply { start() }

        @Bean
        fun sourceEndpointConnectionDetails(database: PgLifecycleDatabaseFixture): JdbcConnectionDetails = object : JdbcConnectionDetails {
            override fun getJdbcUrl(): String = "jdbc:postgresql://${database.host}:${database.port}/${PgLifecycleDatabaseSettings.DATABASE}" +
                "?sslmode=disable&gssEncMode=disable&requireAuth=scram-sha-256&channelBinding=disable"

            override fun getUsername(): String = PgLifecycleDatabaseSettings.OBSERVER
            override fun getPassword(): String = PgLifecycleDatabaseSettings.OBSERVER_PASSWORD
            override fun getDriverClassName(): String = "org.postgresql.Driver"
            override fun toString(): String = "ControllerOwnedSourceEndpointConnectionDetails(redacted)"
        }
    }
}
