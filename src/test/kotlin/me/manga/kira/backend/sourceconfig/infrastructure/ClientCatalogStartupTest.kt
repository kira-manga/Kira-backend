package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.config.KiraClientCatalogProperties
import me.manga.kira.backend.sourceconfig.application.ClientCatalogFixture
import me.manga.kira.backend.sourceconfig.application.ClientCatalogQueryService
import me.manga.kira.backend.sourceconfig.application.StoredCatalogPairVerifier
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalogRepository
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.aop.support.AopUtils
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Real binding, startup caller and Spring transaction proxy. Mock ports do not establish PostgreSQL MVCC behavior. */
class ClientCatalogStartupTest {
    private val fixture = ClientCatalogFixture()
    private val floor = mock(RevisionFloorStartupValidator::class.java)
    private val state = mock(PublicationStateStartupValidator::class.java)

    @Test
    fun `bound configuration and startup and every client query enter actual read-only repeatable-read proxy`() {
        observeTransactions()
        context(properties()).run { context ->
            assertNull(context.startupFailure)
            val properties = context.getBean(KiraClientCatalogProperties::class.java)
            assertEquals(fixture.config(), properties)
            val query = context.getBean(ClientCatalogQueryService::class.java)
            assertTrue(AopUtils.isAopProxy(query))
            // ContextRunner does not invoke ApplicationRunner automatically; use the real boot caller.
            context.getBean(StartupConsistencyRunner::class.java).run(null)
            inOrder(floor, state, fixture.documents).apply {
                verify(floor).validate()
                verify(state).validate()
                verify(fixture.documents).latestPointer()
            }
            assertSame(fixture.baseline.document, query.documentForClient(null))
            assertSame(fixture.pinned.manifest, query.manifestForClient(listOf("9.0.1")))
            assertSame(fixture.current.document, query.documentMetaForClient(listOf("9.0.2")))
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            fixture.documentRows[100] = fixture.baseline.document.copy(documentJson = "synthetic corruption")
            assertThrows(ServiceUnavailableException::class.java) { query.documentForClient(null) }
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        }
    }

    @Test
    fun `disabled startup preserves existing validators and their failures without loading client pairs`() {
        context(emptyMap()).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.getBean(KiraClientCatalogProperties::class.java).enabled)
            val startup = context.getBean(StartupConsistencyRunner::class.java)
            // Existing signer lifecycle initialization is outside the client startup/read interaction assertion.
            clearInvocations(fixture.documents, fixture.catalogs, fixture.signer)
            startup.run(null)
            inOrder(floor, state).apply {
                verify(floor).validate()
                verify(state).validate()
            }
            verifyNoInteractions(fixture.documents, fixture.catalogs, fixture.signer)
            clearInvocations(floor, state)
            val refusal = IllegalStateException("synthetic existing floor refusal")
            doThrow(refusal).`when`(floor).validate()
            assertSame(refusal, assertThrows(IllegalStateException::class.java) { startup.run(null) })
            verifyNoInteractions(state, fixture.documents, fixture.catalogs, fixture.signer)
        }
    }

    @Test
    fun `invalid configuration refuses eager refresh and missing configured pair refuses actual startup caller`() {
        val missingBaseline = properties().filterKeys { !it.startsWith("kira.client-catalog.baseline.") }
        context(missingBaseline, lazy = true).run { context ->
            val failure = context.startupFailure
            assertNotNull(failure)
            val root = generateSequence(checkNotNull(failure)) { it.cause }.last()
            assertEquals("client catalog delivery configuration is invalid; see docs/SOURCE_CONFIG_LIFECYCLE.md.", root.message)
        }
        context(properties() + ("kira.client-catalog.unknown-selection" to true)).run { context ->
            assertNotNull(context.startupFailure, "unknown selection properties must not silently disable protection")
        }
        fixture.manifestRows.remove(101)
        context(properties()).run { context ->
            assertNull(context.startupFailure)
            val error = assertThrows(ServiceUnavailableException::class.java) {
                context.getBean(StartupConsistencyRunner::class.java).run(null)
            }
            assertEquals("CLIENT_CATALOG_UNAVAILABLE", error.code)
            assertNull(error.cause)
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        }
    }

    private fun observeTransactions() {
        doAnswer {
            assertTransaction()
            fixture.pointer
        }.`when`(fixture.documents).latestPointer()
        doAnswer {
            assertTransaction()
            fixture.documentRows[it.getArgument<Long>(0)]
        }.`when`(fixture.documents).findByRevision(anyLong())
        doAnswer {
            assertTransaction()
            fixture.manifestRows[it.getArgument<Long>(0)]
        }.`when`(fixture.catalogs).findByRevision(anyLong())
    }

    private fun assertTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
    }

    private fun context(properties: Map<String, Any>, lazy: Boolean = false): ApplicationContextRunner = ApplicationContextRunner()
        .withInitializer { context ->
            context.environment.propertySources.apply {
                remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                addFirst(MapPropertySource("synthetic-client-catalog", properties))
            }
            if (lazy) context.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor())
        }
        .withUserConfiguration(ClientConfiguration::class.java)
        .withBean("documents", PublishedDocumentRepository::class.java, { fixture.documents })
        .withBean("catalogs", PublishedSourceCatalogRepository::class.java, { fixture.catalogs })
        .withBean("signer", DocumentSigner::class.java, { fixture.signer })
        .withBean("floor", RevisionFloorStartupValidator::class.java, { floor })
        .withBean("state", PublicationStateStartupValidator::class.java, { state })
        .withBean("transactionManager", PlatformTransactionManager::class.java, { FixtureTransactions() })

    private fun properties(): Map<String, Any> = mapOf(
        "kira.client-catalog.enabled" to true,
        "kira.client-catalog.baseline.revision" to 100,
        "kira.client-catalog.baseline.document-checksum" to fixture.baseline.document.checksum,
        "kira.client-catalog.baseline.manifest-checksum" to fixture.baseline.manifest.checksum,
        "kira.client-catalog.current-versions[0]" to "9.0.2",
        "kira.client-catalog.pinned-versions[0].app-version" to "9.0.1",
        "kira.client-catalog.pinned-versions[0].snapshot.revision" to 101,
        "kira.client-catalog.pinned-versions[0].snapshot.document-checksum" to fixture.pinned.document.checksum,
        "kira.client-catalog.pinned-versions[0].snapshot.manifest-checksum" to fixture.pinned.manifest.checksum,
    )

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KiraClientCatalogProperties::class)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(ClientCatalogQueryService::class, StoredCatalogPairVerifier::class, StartupConsistencyRunner::class)
    private class ClientConfiguration

    /** Exercises Spring transaction interception/synchronization only, not a replacement production manager. */
    private class FixtureTransactions : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}
