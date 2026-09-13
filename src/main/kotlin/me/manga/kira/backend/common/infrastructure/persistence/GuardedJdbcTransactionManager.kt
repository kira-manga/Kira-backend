package me.manga.kira.backend.common.infrastructure.persistence

import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus

/** Guard before Spring suspension/propagation callbacks, even for another manager over the same DS. */
internal class GuardedJdbcTransactionManager(internal val dataSource: GuardedDataSource) : PlatformTransactionManager {
    private val dispatch = PersistenceManagerDispatch(
        this,
        JdbcTransactionManager(dataSource).apply {
            isNestedTransactionAllowed = false
            exceptionTranslator = SQLExceptionSubclassTranslator() // No metadata/borrow during failure translation.
        },
    )

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = dispatch.getTransaction(definition)
    override fun commit(status: TransactionStatus) = dispatch.complete(status, commit = true)
    override fun rollback(status: TransactionStatus) = dispatch.complete(status, commit = false)
    override fun toString(): String = "GuardedJdbcTransactionManager(redacted)"
}
