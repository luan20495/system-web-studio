package com.systemwebstudio.data.datasource

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.auditFields
import com.systemwebstudio.data.discovery.DiscoveryOptions
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.QueryResult
import org.slf4j.LoggerFactory
import java.util.UUID

/** Port: persistence of data sources. Needs a migration C0 has not issued yet (BOARD.md, *Migration requests*); T8 ships the port only. */
interface DataSourceRepository {
    /** Tenant-scoped on purpose: there is no lookup by id alone, so another tenant's data source is simply not found. */
    fun find(tenantId: UUID, id: UUID): DataSource?
    fun list(tenantId: UUID): List<DataSource>
    fun save(dataSource: DataSource): DataSource

    /**
     * Ownership-checked lookup (B-C0-W-05): the data source only when it belongs to [tenantId] AND [workspaceId]. A tenant-level source (no workspace)
     * never matches, and another workspace's source is indistinguishable from a missing one. Persistent adapters should override this with a query that
     * filters in SQL; the default derives it from [find] so in-memory test doubles keep working.
     */
    fun findInWorkspace(tenantId: UUID, workspaceId: UUID, id: UUID): DataSource? = find(tenantId, id)?.takeIf { it.workspaceId == workspaceId }
}

/**
 * Which data sources of the tenant a call may reach at run time. [TENANT] is the historical C3 behaviour (tenant boundary only) and stays the default so
 * C3's own tests are unchanged; [WORKSPACE] is what C0's wiring uses (tenant + workspace of the caller's context, default deny: a context without a
 * workspace, or a source without one, reaches nothing).
 */
enum class DataSourceScope { TENANT, WORKSPACE }

/** Port: abuse budget per (tenant, data source, operation). */
fun interface RateLimitGate {
    fun allow(key: String, limit: Long, windowSeconds: Long): Boolean
}

/** Adapter on the platform's existing Redis [RateLimiter] (read-only use). A limiter outage denies: the budget exists to protect third-party systems. */
class RedisRateLimitGate(private val limiter: RateLimiter) : RateLimitGate {
    override fun allow(key: String, limit: Long, windowSeconds: Long): Boolean =
        try { limiter.hit(key, limit, windowSeconds).allowed } catch (e: Exception) { false }
}

/**
 * Port: audit trail of data-platform operations. [details] must contain fixed, non-sensitive fields only — ids, codes, counts, never
 * parameter values, rows, payloads, signatures or credentials. [dataSourceId] is null for tenant-level events. The acting user, when there
 * is one, travels as `details["actor"]` (async work — sync runs, webhooks — has no request to read it from).
 */
fun interface DataAuditSink {
    fun record(action: String, tenantId: UUID, dataSourceId: UUID?, details: Map<String, Any?>)
}

/** Adapter on the existing append-only `AuditService.record` (the audit module is read-only for C3: only this call is used). */
class AuditServiceSink(private val audit: AuditService) : DataAuditSink {
    override fun record(action: String, tenantId: UUID, dataSourceId: UUID?, details: Map<String, Any?>) {
        val actor = (details["actor"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val workspace = (details["workspace"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        audit.record(action, "DATA_SOURCE", dataSourceId, workspaceId = workspace, actorId = actor ?: AuditService.currentActorId(),
            newValue = details + ("tenantId" to tenantId.toString()))
    }
}

object DataAuditActions {
    const val TESTED = "DATASOURCE_TESTED"
    const val DISCOVERED = "DATASOURCE_DISCOVERED"
    const val QUERIED = "DATASOURCE_QUERIED"
    const val MUTATED = "DATASOURCE_MUTATED"
    const val CREATED = "DATASOURCE_CREATED"
    const val UPDATED = "DATASOURCE_UPDATED"
    const val CREDENTIAL_ROTATED = "DATASOURCE_CREDENTIAL_ROTATED"
    const val STATUS_CHANGED = "DATASOURCE_STATUS_CHANGED"
    const val SCHEMA_REFRESHED = "DATASOURCE_SCHEMA_REFRESHED"
    const val QUERY_SERVED = "DATA_QUERY_SERVED"
    const val MUTATION_RUN = "DATA_MUTATION_RUN"
    const val CACHE_REFRESHED = "DATA_CACHE_REFRESHED"
    const val DENIED = "DATA_ACCESS_DENIED"
    const val SYNC_JOB_CHANGED = "DATA_SYNC_JOB_CHANGED"
    const val SYNC_RUN = "DATA_SYNC_RUN"
    const val EVENTS_SUBSCRIBED = "DATA_EVENTS_SUBSCRIBED"
    const val WEBHOOK_ENDPOINT_CHANGED = "DATA_WEBHOOK_ENDPOINT_CHANGED"
    const val WEBHOOK_ACCEPTED = "DATA_WEBHOOK_ACCEPTED"
    const val WEBHOOK_REJECTED = "DATA_WEBHOOK_REJECTED"
    const val AI_CATALOG_BUILT = "DATA_AI_CATALOG_BUILT"
}

/**
 * The single orchestration point in front of the connectors: tenant-scoped lookup → status check → rate limit → decrypt credential →
 * connector → audit. It is what the future Data Gateway (and admin test/discover endpoints) call; **it performs no permission check**
 * (that is C1's `AccessContext`, applied by the caller before this point — BLOCKERS B-C3-01), and no HTTP endpoint exposes it: the Data Gateway (`data/gateway`) is the authorised front door.
 *
 * Whatever a connector does, the caller only ever sees a result or a [ConnectorFailure] with fixed text. Credentials exist inside this
 * method's stack frame only; audit payloads and logs carry ids, codes and counts, never configuration values or secrets.
 */
class DataSourceService(
    private val repository: DataSourceRepository,
    private val vault: CredentialVault,
    private val registry: DataConnectorRegistry,
    private val limits: RateLimitGate,
    private val audit: DataAuditSink,
    private val scope: DataSourceScope = DataSourceScope.TENANT
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun testConnection(ctx: GatewayContext, dataSourceId: UUID): ConnectionTestResult {
        val ds = load(ctx, dataSourceId)
        throttle("test", ds, 20)
        val result = try {
            registry.require(ds.ref.type).test(ds.ref, vault.open(ds))
        } catch (e: ConnectorFailure) {
            ConnectionTestResult.Failed(e.code, e.safeMessage)
        } catch (e: Exception) {
            internal(e); ConnectionTestResult.Failed(FailureCodes.INTERNAL, "unexpected error")
        }
        audit.record(DataAuditActions.TESTED, ds.tenantId, ds.id, mapOf("type" to ds.ref.type, "ok" to (result is ConnectionTestResult.Ok),
            "code" to (result as? ConnectionTestResult.Failed)?.code) + ctx.auditFields())
        return result
    }

    fun discoverSchema(ctx: GatewayContext, dataSourceId: UUID, options: DiscoveryOptions = DiscoveryOptions()): DiscoveredSchema {
        val ds = load(ctx, dataSourceId)
        throttle("discover", ds, 10)
        return guarded(DataAuditActions.DISCOVERED, ds, ctx, mapOf("samples" to options.sampleRows)) {
            registry.require(ds.ref.type).discovery().discover(ds.ref, vault.open(ds), options)
        }
    }

    /** Tenant-scoped, status-checked lookup for the Gateway (it needs the revision for cache keys). Authorisation is the caller's job. */
    fun resolve(ctx: GatewayContext, dataSourceId: UUID): DataSource = load(ctx, dataSourceId)

    /**
     * Write path, for the Data Gateway only and **after** it authorised the caller, found the approved mutation and applied idempotency.
     * Read-only connectors answer [FailureCodes.MUTATION_UNSUPPORTED].
     */
    fun mutate(ctx: GatewayContext, dataSourceId: UUID, req: MutationExecRequest): MutationOutcome {
        if (req.definition.tenantId != ctx.tenantId || req.definition.dataSourceId != dataSourceId) throw ConnectorFailure(FailureCodes.TENANT_MISMATCH, "data source does not belong to the tenant")
        val ds = load(ctx, dataSourceId)
        throttle("mutate", ds, 120)
        return guarded(DataAuditActions.MUTATED, ds, ctx, mapOf("mutation" to req.definition.id)) {
            val mutator = registry.require(ds.ref.type).mutator() ?: throw ConnectorFailure(FailureCodes.MUTATION_UNSUPPORTED, "this data source is read-only")
            mutator.execute(req, ds.ref, vault.open(ds))
        }
    }

    /** For the Data Gateway only, **after** it has authorised the caller and resolved the approved query. */
    fun runQuery(ctx: GatewayContext, dataSourceId: UUID, req: QueryRequest): QueryResult {
        if (req.tenant.tenantId != ctx.tenantId) throw ConnectorFailure(FailureCodes.TENANT_MISMATCH, "data source does not belong to the tenant")
        val ds = load(ctx, dataSourceId)
        throttle("query", ds, 600)
        return guarded(DataAuditActions.QUERIED, ds, ctx, mapOf("query" to req.queryId)) { registry.require(ds.ref.type).executor().execute(req, ds.ref, vault.open(ds)) }
    }

    /** Same answer for "missing", "another tenant's" and (with [DataSourceScope.WORKSPACE]) "another workspace's": nothing leaks, and the status is only looked at after ownership. */
    private fun load(ctx: GatewayContext, id: UUID): DataSource {
        val notFound = { ConnectorFailure(FailureCodes.NOT_FOUND, "data source not found") }
        val ds = when (scope) {
            DataSourceScope.TENANT -> repository.find(ctx.tenantId, id)
            DataSourceScope.WORKSPACE -> repository.findInWorkspace(ctx.tenantId, ctx.workspaceId ?: throw notFound(), id)
        } ?: throw notFound()
        if (ds.tenantId != ctx.tenantId) throw notFound()      // a repository bug must not cross tenants
        if (scope == DataSourceScope.WORKSPACE && ds.workspaceId != ctx.workspaceId) throw notFound()      // ... nor workspaces
        if (ds.status != DataSourceStatus.ACTIVE) throw ConnectorFailure(FailureCodes.DISABLED, "data source is disabled")
        return ds
    }

    private fun throttle(operation: String, ds: DataSource, perMinute: Long) {
        if (!limits.allow("data-source:$operation:${ds.tenantId}:${ds.id}", perMinute, 60)) throw ConnectorFailure(FailureCodes.RATE_LIMITED, "too many requests; retry later")
    }

    private fun <T> guarded(action: String, ds: DataSource, ctx: GatewayContext, extra: Map<String, Any?> = emptyMap(), call: () -> T): T {
        val base = mapOf("type" to ds.ref.type) + extra + ctx.auditFields()
        try {
            val result = call()
            audit.record(action, ds.tenantId, ds.id, base + ("ok" to true))
            return result
        } catch (e: ConnectorFailure) {
            audit.record(action, ds.tenantId, ds.id, base + mapOf("ok" to false, "code" to e.code))
            throw e
        } catch (e: Exception) {
            internal(e)
            audit.record(action, ds.tenantId, ds.id, base + mapOf("ok" to false, "code" to FailureCodes.INTERNAL))
            throw ConnectorFailure(FailureCodes.INTERNAL, "unexpected error")
        }
    }

    /** only the exception class is logged: messages and stack traces of driver/JDK errors can contain hosts, SQL and secrets */
    private fun internal(e: Exception) { log.error("data source operation failed unexpectedly: {}", e.javaClass.name) }
}
