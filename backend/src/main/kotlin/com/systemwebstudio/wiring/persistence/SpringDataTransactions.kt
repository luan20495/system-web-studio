package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.datasource.DataTransactions
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * [DataTransactions] on the platform's own transaction manager. REQUIRED propagation: a call made inside an open transaction joins it, otherwise a new one is
 * opened; an exception (any `RuntimeException` / `Error`, so a `ConnectorFailure` too) rolls the whole unit back and is rethrown unchanged. The JDBC adapters
 * and the append-only `AuditService` all use the same `JdbcTemplate`, so their statements are part of the unit.
 */
class SpringDataTransactions(manager: PlatformTransactionManager) : DataTransactions {
    private val template = TransactionTemplate(manager)

    @Suppress("UNCHECKED_CAST")
    override fun <T> run(block: () -> T): T = template.execute { block() } as T
}
