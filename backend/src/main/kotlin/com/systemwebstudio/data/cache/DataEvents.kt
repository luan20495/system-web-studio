package com.systemwebstudio.data.cache

import com.systemwebstudio.data.query.DataJson
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Realtime contract of the Data Platform (task J): three event types plus one control event, delivered per tenant over SSE.
 * Events say *that* something changed and *what to refetch* — never row data — so a missed event costs a refetch, never a wrong value, and
 * the HTTP read path (cache, permission, mapping) stays the only way data reaches a browser ("consistency before realtime").
 *
 * - [DATA_CHANGED]      something in data source X changed (cause: mutation | webhook | manual_refresh | datasource_updated | sync)
 * - [QUERY_INVALIDATED] cached/rendered results of query Q (or of every query of X when [DataEvent.queryId] is null) are stale: refetch
 * - [RECORD_CHANGED]    one record of an entity was created/updated/deleted; carries the identifier only
 * - [STREAM_RESET]      the subscriber missed events (slow, reconnected too late, node restarted): refetch everything it displays
 */
enum class DataEventType(val wire: String) {
    DATA_CHANGED("DataChanged"), QUERY_INVALIDATED("QueryInvalidated"), RECORD_CHANGED("RecordChanged"), STREAM_RESET("StreamReset")
}

/** [seq] is per tenant and strictly increasing (the SSE `id:`); the tenant is not in the payload — a stream only ever carries its own tenant's events. */
data class DataEvent(
    val seq: Long, val type: DataEventType, val tenantId: UUID, val dataSourceId: UUID?, val cause: ChangeCause?, val queryId: String? = null,
    val entity: String? = null, val operation: RecordOperation? = null, val recordKey: String? = null, val at: Instant
)

/** What a producer hands the bus; the bus assigns [DataEvent.seq]. */
data class NewDataEvent(
    val type: DataEventType, val tenantId: UUID, val dataSourceId: UUID?, val cause: ChangeCause?, val queryId: String? = null,
    val entity: String? = null, val operation: RecordOperation? = null, val recordKey: String? = null, val at: Instant
)

/** A consumer's view of one tenant's stream. Not thread-safe: one reader per subscription. */
interface DataEventSubscription : AutoCloseable {
    /** the next event for this subscriber or null after [timeoutMillis] (the caller sends a heartbeat then); may return a [DataEventType.STREAM_RESET] */
    fun next(timeoutMillis: Long): DataEvent?
}

/**
 * Port. The in-memory implementation is per node; a multi-node deployment needs an adapter over Redis pub/sub or streams
 * (BLOCKERS B-C3-09) — until then an event reaches only subscribers connected to the node that produced it, which is why events are
 * hints and cache invalidation (shared Redis) is the part that must be right.
 */
interface DataEventBus {
    fun publish(event: NewDataEvent)
    /** [afterSeq]: resume point from `Last-Event-ID`; null = only events from now on. [dataSourceIds]: null = all of the tenant's. */
    fun subscribe(tenantId: UUID, dataSourceIds: Set<UUID>? = null, afterSeq: Long? = null): DataEventSubscription
}

/** Per-tenant ring buffer (default 500 events) with a monotonic sequence; a subscriber that falls behind the ring gets one [DataEventType.STREAM_RESET]. */
class InMemoryDataEventBus(private val ringSize: Int = 500, private val maxSubscribersPerTenant: Int = 100) : DataEventBus {
    private class Ring(val size: Int) {
        val lock = ReentrantLock(); val changed = lock.newCondition()
        var seq = 0L; val buf = ArrayDeque<DataEvent>(); var subscribers = 0
    }
    private val rings = java.util.concurrent.ConcurrentHashMap<UUID, Ring>()
    private fun ring(t: UUID) = rings.computeIfAbsent(t) { Ring(ringSize) }

    override fun publish(event: NewDataEvent) {
        val r = ring(event.tenantId)
        r.lock.withLock {
            r.seq++
            r.buf.addLast(DataEvent(r.seq, event.type, event.tenantId, event.dataSourceId, event.cause, event.queryId, event.entity, event.operation, event.recordKey, event.at))
            while (r.buf.size > r.size) r.buf.removeFirst()
            r.changed.signalAll()
        }
    }

    override fun subscribe(tenantId: UUID, dataSourceIds: Set<UUID>?, afterSeq: Long?): DataEventSubscription {
        val r = ring(tenantId)
        r.lock.withLock {
            if (r.subscribers >= maxSubscribersPerTenant) throw com.systemwebstudio.data.datasource.ConnectorFailure(com.systemwebstudio.data.datasource.FailureCodes.RATE_LIMITED, "too many open event streams")
            r.subscribers++
            return Sub(r, tenantId, dataSourceIds, afterSeq ?: r.seq)
        }
    }

    private inner class Sub(val r: Ring, val tenantId: UUID, val filter: Set<UUID>?, var cursor: Long) : DataEventSubscription {
        private var closed = false
        override fun next(timeoutMillis: Long): DataEvent? {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            r.lock.withLock {
                while (true) {
                    if (closed) return null
                    val oldest = r.buf.firstOrNull()?.seq ?: (r.seq + 1)
                    if (cursor < oldest - 1) {                                        // events between the cursor and the ring start are gone
                        cursor = oldest - 1
                        return DataEvent(cursor, DataEventType.STREAM_RESET, tenantId, null, null, at = Instant.now())
                    }
                    val e = r.buf.firstOrNull { it.seq > cursor && (filter == null || it.dataSourceId == null || it.dataSourceId in filter) }
                    if (e != null) { cursor = e.seq; return e }
                    cursor = maxOf(cursor, r.seq)                                      // everything buffered was filtered out
                    val left = deadline - System.nanoTime()
                    if (left <= 0) return null
                    r.changed.awaitNanos(left)
                }
            }
        }
        override fun close() { r.lock.withLock { if (!closed) { closed = true; r.subscribers--; r.changed.signalAll() } } }
    }
}

/** Server-Sent-Events framing. Payloads are single-line JSON built from typed fields (no free text), so no field can inject a frame boundary. */
object SseFormat {
    fun frame(e: DataEvent): String {
        val fields = LinkedHashMap<String, Any?>()
        fields["type"] = e.type.wire
        e.dataSourceId?.let { fields["dataSourceId"] = it.toString() }
        e.cause?.let { fields["cause"] = it.name.lowercase() }
        e.queryId?.let { fields["queryId"] = it }
        e.entity?.let { fields["entity"] = it }
        e.operation?.let { fields["operation"] = it.name.lowercase() }
        e.recordKey?.let { fields["recordKey"] = it }
        fields["at"] = e.at.toString()
        val json = DataJson.mapper.writeValueAsString(fields).replace("\n", "").replace("\r", "")
        return "id: ${e.seq}\nevent: ${e.type.wire}\ndata: $json\n\n"
    }
    const val HEARTBEAT = ": keep-alive\n\n"
    const val RETRY_HINT = "retry: 3000\n\n"
}
