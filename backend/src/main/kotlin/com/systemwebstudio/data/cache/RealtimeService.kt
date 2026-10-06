package com.systemwebstudio.data.cache

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.auditFields
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import java.util.UUID

/**
 * Authorised entry to the event stream (the controller that exposes `GET …/data/events` as `text/event-stream` is C0's wiring, see the
 * blueprint in the design doc). Subscribing needs [GatewayOperation.EVENTS_SUBSCRIBE] — for the tenant, or per data source when a filter is
 * given — and every data source in the filter must belong to the caller's tenant (a foreign id is "not found", exactly like elsewhere).
 * The returned handle re-checks the permission on demand ([stillAllowed], called on each heartbeat) so a revoked permission ends the stream.
 */
class RealtimeService(private val bus: DataEventBus, private val guard: GatewayGuard, private val repository: DataSourceRepository, private val audit: DataAuditSink) {

    class Handle internal constructor(private val sub: DataEventSubscription, private val allowed: () -> Boolean) : AutoCloseable {
        fun next(timeoutMillis: Long): DataEvent? = sub.next(timeoutMillis)
        fun stillAllowed(): Boolean = allowed()
        override fun close() = sub.close()
    }

    fun subscribe(ctx: GatewayContext, dataSourceIds: Set<UUID>? = null, lastEventId: Long? = null): Handle {
        if (dataSourceIds != null && dataSourceIds.size > MAX_FILTER) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "too many data sources in the filter")
        val targets: List<UUID?> = dataSourceIds?.toList() ?: listOf(null)
        targets.forEach { guard.require(ctx, GatewayOperation.EVENTS_SUBSCRIBE, it) }
        dataSourceIds?.forEach { id -> repository.find(ctx.tenantId, id) ?: throw ConnectorFailure(FailureCodes.NOT_FOUND, "data source not found") }
        val sub = bus.subscribe(ctx.tenantId, dataSourceIds, lastEventId)
        audit.record(DataAuditActions.EVENTS_SUBSCRIBED, ctx.tenantId, dataSourceIds?.singleOrNull(), mapOf("filtered" to (dataSourceIds != null)) + ctx.auditFields())
        return Handle(sub) { targets.all { guard.allowed(ctx, GatewayOperation.EVENTS_SUBSCRIBE, it) } }
    }

    companion object { const val MAX_FILTER = 50 }
}
