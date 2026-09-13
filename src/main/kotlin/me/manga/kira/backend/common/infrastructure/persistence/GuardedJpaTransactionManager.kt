package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.EntityManagerFactoryInfo
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.vendor.HibernateJpaDialect
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus

/** No bean or injectable delegate. The dormant slice fixes the real ordinary JPA/EMF/resource pair. */
internal class GuardedJpaTransactionManager(internal val entityManagerFactory: EntityManagerFactory, internal val dataSource: GuardedDataSource) :
    PlatformTransactionManager {
    private val factoryInfo = entityManagerFactory as? EntityManagerFactoryInfo
        ?: throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)

    init {
        requireFactoryResource() // Metadata only: never borrow to discover/repair an EMF's real provider.
    }

    private val supportedDialect = HibernateJpaDialect()

    // Deliberately no customizers, execution listeners or EntityManager initializer. This dormant
    // controlled composition is not future Boot bean/customizer compatibility certification.
    private val delegate = object : JpaTransactionManager() {
        override fun createEntityManagerForTransaction(): EntityManager = super.createEntityManagerForTransaction().also { created ->
            PersistencePhaseOwnership.current()?.retainEntityManager(this@GuardedJpaTransactionManager, created)
        }
    }.apply {
        entityManagerFactory = this@GuardedJpaTransactionManager.entityManagerFactory
        dataSource = this@GuardedJpaTransactionManager.dataSource
        isNestedTransactionAllowed = false
        try {
            afterPropertiesSet() // Spring unconditionally replaces DS/dialect from non-null EMF metadata.
        } catch (_: Throwable) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        // Factory-supplied dialect customization is not part of this fixed composition. Pin AFTER
        // autodetection; do not reset the DS to conceal an invalid factory/provider association.
        setJpaDialect(supportedDialect)
    }

    init {
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
        if (PersistencePhaseOwnership.current() != null &&
            (delegate.transactionExecutionListeners.isNotEmpty() || delegate.isNestedTransactionAllowed)
        ) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
        return dispatch.getTransaction(definition)
    }
    override fun commit(status: TransactionStatus) = dispatch.complete(status, commit = true)
    override fun rollback(status: TransactionStatus) = dispatch.complete(status, commit = false)
    override fun toString(): String = "GuardedJpaTransactionManager(redacted)"

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
}
