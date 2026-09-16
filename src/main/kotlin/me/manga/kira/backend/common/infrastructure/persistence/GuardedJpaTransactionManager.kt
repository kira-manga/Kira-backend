package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers
import org.springframework.orm.jpa.EntityManagerFactoryInfo
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.orm.jpa.JpaDialect
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.vendor.HibernateJpaDialect
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.TransactionStatus
import java.util.function.Consumer

/** One private real JpaTransactionManager; public dispatch always retains the exact ordinary resource pair. */
internal class GuardedJpaTransactionManager private constructor(
    internal val entityManagerFactory: EntityManagerFactory,
    internal val dataSource: GuardedDataSource,
    customizers: TransactionManagerCustomizers?,
    private val ordinaryCompatibility: Boolean,
) : PlatformTransactionManager {
    constructor(entityManagerFactory: EntityManagerFactory, dataSource: GuardedDataSource) :
        this(entityManagerFactory, dataSource, customizers = null, ordinaryCompatibility = false)

    private val factoryInfo = entityManagerFactory as? EntityManagerFactoryInfo
        ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)

    init {
        requireFactoryResource() // Metadata only: never borrow to discover/repair an EMF's real provider.
    }

    private val supportedDialect: JpaDialect

    private val delegate = object : JpaTransactionManager() {
        private var initializer: Consumer<EntityManager>? = null

        override fun setEntityManagerInitializer(entityManagerInitializer: Consumer<EntityManager>) {
            initializer = entityManagerInitializer
        }

        @Suppress("TooGenericExceptionCaught") // Retain cleanup responsibility for every initializer failure.
        override fun createEntityManagerForTransaction(): EntityManager {
            val created = super.createEntityManagerForTransaction()
            val phase = PersistencePhaseOwnership.current()
            phase?.retainEntityManager(this@GuardedJpaTransactionManager, created)
            try {
                // Retain before the initializer. Until this method returns, Spring has no
                // holder and cannot close a failed initializer's EntityManager for us.
                initializer?.accept(created)
                return created
            } catch (failure: Throwable) {
                if (phase == null) {
                    EntityManagerFactoryUtils.closeEntityManager(created)
                } else {
                    phase.entityManagerInitializationFailed(this@GuardedJpaTransactionManager, created, failure)
                    try {
                        created.close() // One actual close attempt before handoff; never a guessed rollback/status.
                    } catch (closeFailure: Throwable) {
                        phase.recordFailure(closeFailure) // Keep exact EM/lease custody; no logging or retry.
                    }
                    // finish() alone observes real closure/quiescence and either refunds or quarantines.
                }
                throw failure
            }
        }
    }.apply {
        entityManagerFactory = this@GuardedJpaTransactionManager.entityManagerFactory
        dataSource = this@GuardedJpaTransactionManager.dataSource
        isNestedTransactionAllowed = false
        try {
            customizers?.customize(this) // Actual private manager, not the PlatformTransactionManager wrapper.
            if (ordinaryCompatibility) {
                setTransactionExecutionListeners(transactionExecutionListeners.map(::SourcePhaseExecutionListener))
            }
            if (entityManagerFactory !== this@GuardedJpaTransactionManager.entityManagerFactory ||
                dataSource !== this@GuardedJpaTransactionManager.dataSource
            ) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
            afterPropertiesSet() // Spring unconditionally replaces DS/dialect from non-null EMF metadata.
        } catch (_: Throwable) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (!ordinaryCompatibility) setJpaDialect(HibernateJpaDialect())
    }

    init {
        supportedDialect = delegate.jpaDialect
        if (supportedDialect !is HibernateJpaDialect) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        requireResourcePair()
    }
    private val dispatch = PersistenceManagerDispatch(this, delegate)
    private var phaseOwner: PersistencePhaseOwnership? = null

    internal fun bindPhaseOwner(owner: PersistencePhaseOwnership) {
        check(phaseOwner == null)
        phaseOwner = owner
    }

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        requireResourcePair() // Also guards unscoped begin; no EM may be created for a changed pair.
        if (!ordinaryCompatibility && PersistencePhaseOwnership.current() != null && hasUnsupportedPhaseCustomization()) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
        return dispatch.getTransaction(definition)
    }
    override fun commit(status: TransactionStatus) = dispatch.complete(status, commit = true)
    override fun rollback(status: TransactionStatus) = dispatch.complete(status, commit = false)
    override fun toString(): String = "GuardedJpaTransactionManager(redacted)"

    private fun hasUnsupportedPhaseCustomization(): Boolean = delegate.transactionExecutionListeners.isNotEmpty() || delegate.isNestedTransactionAllowed

    internal fun requireResourcePair() {
        requireFactoryResource()
        if (delegate.entityManagerFactory !== entityManagerFactory || delegate.dataSource !== dataSource ||
            delegate.jpaDialect !== supportedDialect
        ) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
    }

    private fun requireFactoryResource() {
        val declared = try {
            factoryInfo.dataSource
        } catch (_: Throwable) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (declared !== dataSource) throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    companion object {
        internal fun sourceOnly(
            entityManagerFactory: EntityManagerFactory,
            dataSource: GuardedDataSource,
            customizers: TransactionManagerCustomizers?,
        ): GuardedJpaTransactionManager {
            check(dataSource.sourceOnlyComposition())
            return GuardedJpaTransactionManager(entityManagerFactory, dataSource, customizers, ordinaryCompatibility = true)
        }
    }
}

/** APTM invokes afterBegin after binding resources but BEFORE returning the only rollback status. */
private class SourcePhaseExecutionListener(private val configured: TransactionExecutionListener) : TransactionExecutionListener by configured {
    @Suppress("TooGenericExceptionCaught") // Delay the failure only until the sole rollback status is retained.
    override fun afterBegin(transaction: TransactionExecution, beginFailure: Throwable?) {
        try {
            configured.afterBegin(transaction, beginFailure)
        } catch (failure: Throwable) {
            val phase = PersistencePhaseOwnership.current() ?: throw failure
            // Do not strand a successful begin without a returned status. The retained failure
            // makes begin() refuse work, then the SAME phase rolls back its now-retained status.
            phase.recordFailure(failure)
        }
    }
}
