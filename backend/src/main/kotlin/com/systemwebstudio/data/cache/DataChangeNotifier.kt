package com.systemwebstudio.data.cache

import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * The single listener behind every trigger (mutation, webhook, manual refresh, data-source update, sync): first the cache is invalidated,
 * *then* the events go out — a client that reacts to `QueryInvalidated` by refetching can never read the entry that was just invalidated.
 * It never throws into the caller: a failed invalidation is recorded by [QueryCache] (the scope is bypassed) and the events still go out.
 */
class DataChangeNotifier(private val cache: QueryCache, private val bus: DataEventBus, private val clock: Clock = Clock.systemUTC()) : DataChangeListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onChange(change: DataChange) {
        try {
            if (change.queryIds.isEmpty() || change.cause == ChangeCause.DATASOURCE_UPDATED) cache.invalidateDataSource(change.tenantId, change.dataSourceId)
            else change.queryIds.forEach { cache.invalidateQuery(change.tenantId, change.dataSourceId, it) }
        } catch (e: Exception) { log.warn("cache invalidation raised {}", e.javaClass.simpleName) }

        val now = clock.instant()
        fun ev(type: DataEventType, queryId: String? = null, key: String? = null) =
            NewDataEvent(type, change.tenantId, change.dataSourceId, change.cause, queryId, change.entity, change.operation, key, now)
        try {
            bus.publish(ev(DataEventType.DATA_CHANGED))
            if (change.queryIds.isEmpty() || change.cause == ChangeCause.DATASOURCE_UPDATED) bus.publish(ev(DataEventType.QUERY_INVALIDATED))
            else change.queryIds.forEach { bus.publish(ev(DataEventType.QUERY_INVALIDATED, it)) }
            if (change.entity != null && change.operation != null) change.recordKeys.forEach { bus.publish(ev(DataEventType.RECORD_CHANGED, key = it)) }
        } catch (e: Exception) { log.warn("event publish raised {}", e.javaClass.simpleName) }
    }
}
