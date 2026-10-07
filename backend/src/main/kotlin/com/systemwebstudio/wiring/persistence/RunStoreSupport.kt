package com.systemwebstudio.wiring.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Shared by the V29 run stores. A transaction here is a plain JDBC transaction on the SAME DataSource as the [JdbcTemplate] (it joins one that is already
 * active on the thread), so the compare-and-set of a workflow run and the upsert of its steps commit or roll back together.
 */
internal object RunStoreSupport {
    fun transactions(jdbc: JdbcTemplate): TransactionOperations =
        TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource) { "the JdbcTemplate has no DataSource" }))

    /** An identifier of this process for `action_runs.worker_id` (diagnostics only: who started a RUNNING row). Never used for any decision. */
    fun workerId(): String = "node-" + UUID.randomUUID().toString().take(8)

    /** the "no application" key scope of `action_runs_key_unique` (COALESCE target) */
    val NIL_UUID: UUID = UUID(0L, 0L)

    fun cap(text: String?, max: Int): String? = text?.take(max)
}
