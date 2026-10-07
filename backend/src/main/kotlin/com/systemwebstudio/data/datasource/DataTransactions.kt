package com.systemwebstudio.data.datasource

/**
 * Port: one unit of work. Everything a management call writes — the domain row(s), the credential row, the audit row — runs inside [run], so a change that
 * cannot be audited, or whose later step fails, is not applied (Management API contract §5: "a write and its audit row are one unit").
 *
 * The persistent adapter (`SpringDataTransactions`) opens a real database transaction, or joins the one that is already open (nested calls are one unit,
 * the outermost [run] decides). Any exception thrown by [block] rolls the whole unit back and is rethrown unchanged. Side effects that cannot be rolled back
 * (cache invalidation, events) belong AFTER [run] returns.
 */
interface DataTransactions {
    fun <T> run(block: () -> T): T
}

/** No transaction: for the in-memory test doubles, where there is nothing to roll back. Production wiring never uses it. */
object NoTransactions : DataTransactions {
    override fun <T> run(block: () -> T): T = block()
}
