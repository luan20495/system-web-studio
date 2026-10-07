package com.systemwebstudio.data.cache

import java.util.UUID

enum class ChangeCause { MUTATION, WEBHOOK, MANUAL_REFRESH, DATASOURCE_UPDATED, SYNC }

enum class RecordOperation { CREATED, UPDATED, DELETED }

/**
 * "Something about this data source's data may have changed." One value type for every trigger the spec lists (mutation, webhook, manual
 * refresh, data-source update, sync): the listener turns it into cache invalidation and realtime events, so no trigger can forget one of them.
 * [queryIds] empty = every query of the data source. It never carries row data: [recordKeys] are identifiers only (at most 50).
 */
data class DataChange(
    val tenantId: UUID, val dataSourceId: UUID, val cause: ChangeCause,
    val queryIds: List<String> = emptyList(), val entity: String? = null, val operation: RecordOperation? = null, val recordKeys: List<String> = emptyList()
) {
    init { require(queryIds.size <= 50 && recordKeys.size <= 50) { "too many ids" } }
}

fun interface DataChangeListener {
    fun onChange(change: DataChange)
}

/** for wiring where nothing listens (tests, tools) */
object NoopDataChangeListener : DataChangeListener {
    override fun onChange(change: DataChange) = Unit
}
