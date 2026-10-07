package com.systemwebstudio.data

import com.systemwebstudio.data.datasource.AddressPolicy
import com.systemwebstudio.data.datasource.CredentialStore
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.HostResolver
import com.systemwebstudio.data.discovery.SchemaSnapshot
import com.systemwebstudio.data.discovery.SourceSchemaStore
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.GatewayAuthorizer
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayDecision
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryDefinition
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-memory ports for tests only (the real persistence needs a migration C0 has not issued; see BOARD.md). */
class InMemoryDataSourceRepository : DataSourceRepository {
    private val rows = ConcurrentHashMap<UUID, DataSource>()
    override fun find(tenantId: UUID, id: UUID): DataSource? = rows[id]?.takeIf { it.tenantId == tenantId }
    override fun list(tenantId: UUID): List<DataSource> = rows.values.filter { it.tenantId == tenantId }
    override fun save(dataSource: DataSource): DataSource = dataSource.also { rows[it.id] = it }
    override fun delete(tenantId: UUID, id: UUID): Boolean = rows[id]?.takeIf { it.tenantId == tenantId }?.let { rows.remove(id); true } ?: false
}

class InMemoryCredentialStore(private val now: () -> java.time.Instant = { java.time.Instant.now() }) : CredentialStore {
    private val rows = ConcurrentHashMap<Pair<UUID, String>, String>()
    private val written = ConcurrentHashMap<Pair<UUID, String>, java.time.Instant>()
    override fun find(tenantId: UUID, ref: String): String? = rows[tenantId to ref]
    override fun put(tenantId: UUID, ref: String, ciphertext: String) { rows[tenantId to ref] = ciphertext; written[tenantId to ref] = now() }
    override fun remove(tenantId: UUID, ref: String) { rows.remove(tenantId to ref); written.remove(tenantId to ref) }
    override fun updatedAt(tenantId: UUID, ref: String): java.time.Instant? = written[tenantId to ref]
    val size get() = rows.size
}

class InMemoryQueryCatalog(vararg definitions: QueryDefinition) : QueryCatalog {
    private val defs = CopyOnWriteArrayList(definitions.toList())
    fun add(d: QueryDefinition) { defs += d }
    override fun find(tenantId: UUID, dataSourceId: UUID, queryId: String): QueryDefinition? =
        defs.firstOrNull { it.tenantId == tenantId && it.dataSourceId == dataSourceId && it.id == queryId }
    override fun list(tenantId: UUID, dataSourceId: UUID): List<QueryDefinition> = defs.filter { it.tenantId == tenantId && it.dataSourceId == dataSourceId }
}

class RecordingAuditSink : DataAuditSink {
    class Event(val action: String, val tenantId: UUID, val dataSourceId: UUID?, val details: Map<String, Any?>)
    val events = CopyOnWriteArrayList<Event>()
    override fun record(action: String, tenantId: UUID, dataSourceId: UUID?, details: Map<String, Any?>) { events += Event(action, tenantId, dataSourceId, details) }
    fun actions(): List<String> = events.map { it.action }
    val text: String get() = events.joinToString("\n") { "${it.action} ${it.tenantId} ${it.dataSourceId} ${it.details}" }
}

/** name → fixed addresses; counts lookups (a pinned connection must resolve exactly once) */
class FixedResolver(private val table: Map<String, List<String>>) : HostResolver {
    val lookups = AtomicInteger()
    override fun resolve(host: String): List<InetAddress> {
        lookups.incrementAndGet()
        val h = host.lowercase()
        val literal = Regex("^[0-9.]+$|^[0-9a-f:.]*:[0-9a-f:.]*$").matches(h)                    // a literal resolves to itself, like the system resolver
        return (table[h] ?: if (literal) listOf(h) else emptyList()).map { InetAddress.getByName(it) }   // IP literals only: no DNS
    }
}

/** Test-only: lets the TLS stub on 127.0.0.1 be reached. Never used for the SSRF tests, which run with the platform's real policy. */
object AllowAllAddresses : AddressPolicy {
    override fun isAllowed(address: InetAddress) = true
}

fun tenantId(): UUID = UUID.randomUUID()

/** Test authorizer: everything is allowed except what a test denies; [allowedTenants] (when set) limits who may do anything at all. */
class ScriptedAuthorizer : GatewayAuthorizer {
    val denied: MutableSet<GatewayOperation> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var allowedTenants: Set<UUID>? = null
    @Volatile var failWith: RuntimeException? = null
    val calls = CopyOnWriteArrayList<Pair<GatewayOperation, UUID?>>()
    override fun authorize(ctx: GatewayContext, operation: GatewayOperation, dataSourceId: UUID?): GatewayDecision {
        calls += operation to dataSourceId
        failWith?.let { throw it }
        if (operation in denied) return GatewayDecision.Denied("scripted")
        allowedTenants?.let { if (ctx.tenantId !in it) return GatewayDecision.Denied("scripted tenant") }
        return GatewayDecision.Allowed
    }
}

class InMemorySchemaStore : SourceSchemaStore {
    private val rows = CopyOnWriteArrayList<SchemaSnapshot>()
    override fun latest(tenantId: UUID, dataSourceId: UUID) = rows.filter { it.tenantId == tenantId && it.dataSourceId == dataSourceId }.maxByOrNull { it.version }
    @Synchronized override fun save(snapshot: SchemaSnapshot) {
        if (rows.any { it.tenantId == snapshot.tenantId && it.dataSourceId == snapshot.dataSourceId && it.version == snapshot.version }) throw ConnectorFailure(FailureCodes.CONFLICT, "version exists")
        rows += snapshot
    }
    override fun history(tenantId: UUID, dataSourceId: UUID, limit: Int) = rows.filter { it.tenantId == tenantId && it.dataSourceId == dataSourceId }.sortedByDescending { it.version }.take(limit)
    val all: List<SchemaSnapshot> get() = rows.toList()
}

/** Sliding-window limiter driven by an injectable millisecond clock (the production one is Redis-backed). */
class ClockedRateLimitGate(private val nowMs: () -> Long) : com.systemwebstudio.data.datasource.RateLimitGate {
    private val hits = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()
    @Synchronized override fun allow(key: String, limit: Long, windowSeconds: Long): Boolean {
        val now = nowMs(); val list = hits.getOrPut(key) { mutableListOf() }
        list.removeAll { it <= now - windowSeconds * 1000 }
        if (list.size >= limit) return false
        list += now; return true
    }
}

class InMemorySyncJobStore(private val clock: java.time.Clock = java.time.Clock.systemUTC()) : com.systemwebstudio.data.sync.SyncJobStore {
    private val jobs = ConcurrentHashMap<UUID, com.systemwebstudio.data.sync.SyncJob>()
    private val states = ConcurrentHashMap<UUID, com.systemwebstudio.data.sync.SyncState>()
    override fun find(tenantId: UUID, jobId: UUID) = jobs[jobId]?.takeIf { it.tenantId == tenantId }
    override fun list(tenantId: UUID, dataSourceId: UUID?) = jobs.values.filter { it.tenantId == tenantId && (dataSourceId == null || it.dataSourceId == dataSourceId) }.sortedBy { it.createdAt }
    @Synchronized override fun save(job: com.systemwebstudio.data.sync.SyncJob): com.systemwebstudio.data.sync.SyncJob {
        jobs[job.id] = job
        states.putIfAbsent(job.id, com.systemwebstudio.data.sync.SyncState(job.id, job.tenantId, nextRunAt = clock.instant()))
        return job
    }
    override fun delete(tenantId: UUID, jobId: UUID) { if (find(tenantId, jobId) != null) { jobs.remove(jobId); states.remove(jobId) } }
    override fun state(tenantId: UUID, jobId: UUID) = states[jobId]?.takeIf { it.tenantId == tenantId }
    override fun due(now: java.time.Instant, limit: Int) = jobs.values.filter { it.status == com.systemwebstudio.data.sync.SyncJobStatus.ACTIVE && (states[it.id]?.let { s -> s.nextRunAt <= now && (s.leaseUntil == null || s.leaseUntil!! <= now) } ?: false) }
        .take(limit).map { com.systemwebstudio.data.sync.DueJob(it.tenantId, it.id) }
    @Synchronized override fun tryLease(tenantId: UUID, jobId: UUID, owner: String, now: java.time.Instant, leaseSeconds: Long, force: Boolean): com.systemwebstudio.data.sync.SyncState? {
        val job = find(tenantId, jobId) ?: return null
        if (job.status != com.systemwebstudio.data.sync.SyncJobStatus.ACTIVE) return null
        val s = states[jobId] ?: return null
        if (s.leaseUntil != null && s.leaseUntil!! > now && s.leaseOwner != owner) return null
        if (!force && s.nextRunAt > now) return null
        val leased = s.copy(leaseOwner = owner, leaseUntil = now.plusSeconds(leaseSeconds))
        states[jobId] = leased
        return leased
    }
    @Synchronized override fun finish(tenantId: UUID, jobId: UUID, owner: String, update: com.systemwebstudio.data.sync.SyncStateUpdate): Boolean {
        val job = find(tenantId, jobId) ?: return false
        val s = states[jobId] ?: return false
        if (s.leaseOwner != owner || job.version != update.jobVersion) return false
        states[jobId] = s.copy(
            checkpoint = update.checkpoint, runCounter = s.runCounter + 1, leaseOwner = null, leaseUntil = null, nextRunAt = update.nextRunAt, lastRows = update.rows, lastPages = update.pages,
            lastSuccessAt = if (update.success) update.finishedAt else s.lastSuccessAt,
            lastErrorAt = if (update.success) s.lastErrorAt else update.finishedAt, lastErrorCode = if (update.success) s.lastErrorCode else update.errorCode,
            consecutiveFailures = if (update.success) 0 else s.consecutiveFailures + 1)
        if (update.pauseJob) jobs[jobId] = job.copy(status = com.systemwebstudio.data.sync.SyncJobStatus.PAUSED)
        return true
    }
    /** test hook: simulate a worker that died holding the lease */
    fun expireLease(jobId: UUID, at: java.time.Instant) { states.computeIfPresent(jobId) { _, s -> s.copy(leaseUntil = at) } }
    fun forceNextRun(jobId: UUID, at: java.time.Instant) { states.computeIfPresent(jobId) { _, s -> s.copy(nextRunAt = at) } }
}

/** upsert-by-key store honouring the SyncSink contract; can be told to fail on a given page to simulate a crash */
class InMemorySyncSink : com.systemwebstudio.data.sync.SyncSink {
    val rows = ConcurrentHashMap<Triple<UUID, UUID, String>, Map<String, tools.jackson.databind.JsonNode>>()
    private val seen = ConcurrentHashMap<String, com.systemwebstudio.data.sync.SyncApplyResult>()
    val applyCalls = AtomicInteger()
    @Volatile var failOnPage: Int? = null
    @Synchronized override fun apply(tenantId: UUID, jobId: UUID, runKey: String, page: Int, records: List<com.systemwebstudio.data.sync.SyncRecord>): com.systemwebstudio.data.sync.SyncApplyResult {
        applyCalls.incrementAndGet()
        if (failOnPage == page) throw IllegalStateException("sink crashed")
        seen["$tenantId|$jobId|$runKey|$page"]?.let { return it }
        var up = 0; var same = 0
        for (r in records) { val k = Triple(tenantId, jobId, r.key); if (rows[k] == r.row) same++ else { rows[k] = r.row; up++ } }
        return com.systemwebstudio.data.sync.SyncApplyResult(up, same).also { seen["$tenantId|$jobId|$runKey|$page"] = it }
    }
    fun keysOf(tenantId: UUID, jobId: UUID) = rows.keys.filter { it.first == tenantId && it.second == jobId }.map { it.third }.sorted()
}

class InMemoryWebhookEndpointStore : com.systemwebstudio.data.sync.webhook.WebhookEndpointStore {
    private val rows = ConcurrentHashMap<UUID, com.systemwebstudio.data.sync.webhook.WebhookEndpoint>()
    override fun find(tenantId: UUID, id: UUID) = rows[id]?.takeIf { it.tenantId == tenantId }
    override fun list(tenantId: UUID, dataSourceId: UUID?) = rows.values.filter { it.tenantId == tenantId && (dataSourceId == null || it.dataSourceId == dataSourceId) }
    override fun save(endpoint: com.systemwebstudio.data.sync.webhook.WebhookEndpoint) = endpoint.also { rows[it.id] = it }
    override fun delete(tenantId: UUID, id: UUID) { if (find(tenantId, id) != null) rows.remove(id) }
    @Volatile var failResolve = false
    override fun resolveForIngress(id: UUID): com.systemwebstudio.data.sync.webhook.WebhookEndpoint? { if (failResolve) throw IllegalStateException("db down"); return rows[id] }
}

class InMemoryReplayGuard : com.systemwebstudio.data.sync.webhook.WebhookReplayGuard {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    @Volatile var failing = false
    override fun firstSeen(endpointId: UUID, key: String, ttlSeconds: Long): Boolean { if (failing) throw IllegalStateException("redis down"); return seen.add("$endpointId|$key") }
    override fun forget(endpointId: UUID, key: String) { seen.remove("$endpointId|$key") }
}

class RecordingWorkflowPort : com.systemwebstudio.data.sync.webhook.WorkflowTriggerPort {
    val known = mutableSetOf("wf-orders")
    val triggers = CopyOnWriteArrayList<Pair<UUID, com.systemwebstudio.data.sync.webhook.WebhookTriggerContext>>()
    @Volatile var failing = false
    override fun exists(tenantId: UUID, workflowRef: String) = workflowRef in known
    override fun trigger(tenantId: UUID, workflowRef: String, context: com.systemwebstudio.data.sync.webhook.WebhookTriggerContext) {
        if (failing) throw IllegalStateException("workflow engine down"); triggers += tenantId to context
    }
}

/** Test stand-in for "who calls": canonical `tenancy.TenantContext` carries no actor, so tests keep the caller identity next to it. */
class TestTenant(
    val tenantId: UUID = UUID.randomUUID(), val actorUserId: UUID? = UUID.randomUUID(),
    val requestId: String? = "req-" + UUID.randomUUID().toString().take(8), val workspaceId: UUID? = UUID.randomUUID()
) {
    val tenant: com.systemwebstudio.tenancy.TenantContext = com.systemwebstudio.tenancy.TenantContext(tenantId, null)
    fun context(projectId: UUID? = null, appVersionId: String? = null) =
        com.systemwebstudio.data.gateway.GatewayContext(tenant, actorUserId, com.systemwebstudio.tenancy.ActorKind.USER, requestId, workspaceId, projectId, appVersionId)
}

/** Test shorthand for "look up, then fill with the ticket just obtained" — the single-threaded equivalent of what the gateway does around a connector call. */
fun com.systemwebstudio.data.cache.QueryCache.putNow(parts: com.systemwebstudio.data.cache.CacheKeyParts, payload: String, ttlSeconds: Int): Boolean =
    lookup(parts).ticket?.let { put(it, payload, ttlSeconds) } ?: false
