package com.systemwebstudio.data.gateway

import com.systemwebstudio.data.cache.CacheKeyParts
import com.systemwebstudio.data.cache.CacheScope
import com.systemwebstudio.data.cache.CacheTicket
import com.systemwebstudio.data.cache.ChangeCause
import com.systemwebstudio.data.cache.DataChange
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.cache.QueryCache
import com.systemwebstudio.data.cache.RecordOperation
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.MutationExecRequest
import com.systemwebstudio.data.datasource.RateLimitGate
import com.systemwebstudio.data.discovery.DiscoveryService
import com.systemwebstudio.data.mapping.AppScope
import com.systemwebstudio.data.mapping.MappingCatalog
import com.systemwebstudio.data.mapping.MappingDefinition
import com.systemwebstudio.data.mapping.MappingEngine
import com.systemwebstudio.data.mapping.MappingValidator
import com.systemwebstudio.data.mapping.ViewModelData
import com.systemwebstudio.data.mapping.ViewModelDataJson
import com.systemwebstudio.data.mapping.ViewModelDefinition
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationCatalog
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.PageSpec
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryParams
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.RecordKey
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * What a caller may ask for. There is no field for SQL, a URL, a header or a credential: [operation] is the id of an **approved query
 * definition** of the data source, [params] are its declared, typed parameters, [mappingRef] names the mapping (in the AppDefinition) that
 * shapes the result, [viewModelRef] optionally the view model it must satisfy.
 */
data class GatewayQuery(
    val dataSourceId: UUID, val operation: String, val params: Map<String, JsonNode> = emptyMap(), val page: PageSpec? = null,
    val mappingRef: String, val viewModelRef: String? = null
) {
    override fun toString() = "GatewayQuery(ds=$dataSourceId, operation=$operation)"          // parameter values may be personal data
}

enum class CacheStatus { HIT, MISS, BYPASS }

data class GatewayQueryResponse(val data: ViewModelData, val cache: CacheStatus, val dataSourceId: UUID, val operation: String)

/** A write: [operation] is an approved mutation id; [idempotencyKey] is mandatory (C4 `idempotencyKey`) so a retried action never writes twice. */
data class GatewayMutation(val dataSourceId: UUID, val operation: String, val params: Map<String, JsonNode> = emptyMap(), val idempotencyKey: String) {
    /** parameters may be personal data and the key is a credential-like token: neither is ever printed */
    override fun toString() = "GatewayMutation(ds=$dataSourceId, operation=$operation)"
}

/** [output] is the bounded result document of the source (e.g. the id of the created record) as returned to an authorised caller; it is never logged or audited. */
data class GatewayMutationResponse(val operation: String, val kind: MutationKind, val affected: Long?, val replayed: Boolean, val output: JsonNode?)

/**
 * The only door to the data layer (task T11). Every method runs the same pipeline, in this order, and stops at the first refusal:
 *
 *   request → tenant context (built by the server) → permission (C1 port, default deny) → tenant-scoped data source → approved definition
 *   lookup → mapping/view-model lookup + validation → cache / connector → mapping → response → audit
 *
 * Nothing before the permission check looks at the request's content, so an unauthorised caller learns nothing about what exists.
 */
interface DataGateway {
    fun runQuery(ctx: GatewayContext, query: GatewayQuery): GatewayQueryResponse
    fun mutate(ctx: GatewayContext, mutation: GatewayMutation): GatewayMutationResponse
    fun testConnection(ctx: GatewayContext, dataSourceId: UUID): ConnectionTestResult
    fun discoverSchema(ctx: GatewayContext, dataSourceId: UUID, includeSamples: Boolean = false): DiscoveryService.RefreshResult
    /** manual cache refresh: drops cached results of one query (or the whole data source) and tells subscribers */
    fun refreshCache(ctx: GatewayContext, dataSourceId: UUID, queryId: String? = null)
}

class DefaultDataGateway(
    private val guard: GatewayGuard,
    private val service: DataSourceService,
    private val queries: QueryCatalog,
    private val mutations: MutationCatalog,
    private val mappings: MappingCatalog,
    private val discovery: DiscoveryService,
    private val cache: QueryCache,
    private val idempotency: IdempotencyStore,
    private val listener: DataChangeListener,
    private val audit: DataAuditSink,
    private val limits: RateLimitGate,
    private val engine: MappingEngine = MappingEngine(),
    private val clock: Clock = Clock.systemUTC()
) : DataGateway {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)


    // ------------------------------------------------------------------------------------------------ query

    override fun runQuery(ctx: GatewayContext, query: GatewayQuery): GatewayQueryResponse {
        guard.require(ctx, GatewayOperation.QUERY_EXECUTE, query.dataSourceId)
        if (query.operation.isBlank() || !MAPPING_REF.matches(query.mappingRef) || (query.viewModelRef != null && !MAPPING_REF.matches(query.viewModelRef)) || query.params.size > MAX_PARAMS)
            throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "invalid request")
        val ds = service.resolve(ctx, query.dataSourceId)
        val def = queries.find(ctx.tenantId, ds.id, query.operation) ?: throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found")
        if (def.tenantId != ctx.tenantId || def.dataSourceId != ds.id) throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found")   // a catalog bug must not cross tenants

        val scope = AppScope(ctx.tenantId, ctx.projectId, ctx.appVersionId)
        val mapping = mappings.findMapping(scope, query.mappingRef) ?: throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "mapping not found")
        val viewModel = query.viewModelRef?.let { mappings.findViewModel(scope, it) ?: throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "view model not found") }
        if (mapping.queryRef != null && mapping.queryRef != def.id) throw ConnectorFailure(FailureCodes.INVALID_MAPPING, "mapping belongs to another query")
        MappingValidator.require(mapping, viewModel)                       // refuse a bad pairing before any connector call is made

        // parameters are validated against the approved definition *before* the cache is consulted: junk can neither reach a connector nor become a cache key
        val bound = QueryParams.bind(def.params, query.params)
        val canonical = QueryCache.canonicalBound(bound)
        if (canonical.length > MAX_PARAMS_BYTES) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "parameters are too large")
        val parts = CacheKeyParts(CacheScope(ctx.tenantId, ds.id, def.id), ds.version, def.version, mapping.id, mapping.version, viewModel?.id, canonical, query.page?.let { "${it.limit}/${it.offset}" })
        val ttl = def.cacheTtlSeconds

        // the key is captured here, BEFORE the data is read, and reused for the write: an invalidation in between must not be overwritten by this older answer
        var ticket: CacheTicket? = null
        if (ttl > 0) {
            val found = cache.lookup(parts)
            ticket = found.ticket
            found.payload?.let { text -> ViewModelDataJson.decode(text) }?.let { hit ->
                served(ctx, ds, def.id, mapping, hit, CacheStatus.HIT, true, null)
                return GatewayQueryResponse(hit, CacheStatus.HIT, ds.id, def.id)
            }
        }
        val data = try {
            val result = service.runQuery(ctx, ds.id, QueryRequest(def.id, query.params, query.page, ctx.tenant))
            engine.apply(result, mapping, viewModel)
        } catch (e: ConnectorFailure) {
            if (e.code == FailureCodes.INVALID_MAPPING || e.code == FailureCodes.MAPPING_FAILED) served(ctx, ds, def.id, mapping, null, CacheStatus.MISS, false, e.code)
            throw e
        }
        if (ttl > 0 && ticket != null) cache.put(ticket, ViewModelDataJson.encode(data), ttl)
        served(ctx, ds, def.id, mapping, data, if (ttl > 0) CacheStatus.MISS else CacheStatus.BYPASS, true, null)
        return GatewayQueryResponse(data, if (ttl > 0) CacheStatus.MISS else CacheStatus.BYPASS, ds.id, def.id)
    }

    private fun served(ctx: GatewayContext, ds: DataSource, queryId: String, mapping: MappingDefinition, data: ViewModelData?, cacheStatus: CacheStatus, ok: Boolean, code: String?) {
        audit.record(DataAuditActions.QUERY_SERVED, ctx.tenantId, ds.id, mapOf(
            "query" to queryId, "mapping" to mapping.id, "cache" to cacheStatus.name, "ok" to ok, "code" to code,
            "rows" to data?.rows?.size, "truncated" to data?.truncated, "skipped" to data?.skippedRows) + ctx.auditFields())
    }

    // ------------------------------------------------------------------------------------------------ mutation

    override fun mutate(ctx: GatewayContext, mutation: GatewayMutation): GatewayMutationResponse {
        guard.require(ctx, GatewayOperation.MUTATION_EXECUTE, mutation.dataSourceId)
        if (!IDEMPOTENCY_KEY.matches(mutation.idempotencyKey)) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "an idempotency key of 8 to 128 letters, digits, '-' or '_' is required")
        if (mutation.params.size > MAX_PARAMS) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "too many parameters")
        val ds = service.resolve(ctx, mutation.dataSourceId)
        val def = mutations.find(ctx.tenantId, ds.id, mutation.operation) ?: throw ConnectorFailure(FailureCodes.MUTATION_NOT_FOUND, "mutation not found")
        if (def.tenantId != ctx.tenantId || def.dataSourceId != ds.id) throw ConnectorFailure(FailureCodes.MUTATION_NOT_FOUND, "mutation not found")
        // the logical record identifier of an UPDATE / DELETE reaches the key parameter that the APPROVED definition names (never one a caller names)
        val bound = QueryParams.bind(def.params, RecordKey.resolve(def, service.recordKey(ds, def), mutation.params))
        val canonical = QueryCache.canonical(mutation.params)
        if (canonical.length > MAX_PARAMS_BYTES) throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "parameters are too large")
        val fingerprint = sha(listOf(def.id, def.version, canonical).joinToString("\u0000"))

        when (val d = idempotency.begin(ctx.tenantId, ds.id, def.id, mutation.idempotencyKey, fingerprint, IDEMPOTENCY_TTL_SECONDS)) {
            is IdempotencyDecision.Replay -> {
                audit.record(DataAuditActions.MUTATION_RUN, ctx.tenantId, ds.id, mapOf("mutation" to def.id, "replayed" to true, "ok" to true, "idem" to idemRef(mutation.idempotencyKey)) + ctx.auditFields())
                return GatewayMutationResponse(def.id, def.kind, d.stored.affected, true, d.stored.outputJson?.let { DataJson.parse(it.toByteArray()) })
            }
            IdempotencyDecision.InProgress -> throw ConnectorFailure(FailureCodes.IDEMPOTENCY_IN_PROGRESS, "a request with this idempotency key is still running")
            IdempotencyDecision.OutcomeUnknown -> {
                audit.record(DataAuditActions.MUTATION_RUN, ctx.tenantId, ds.id, mapOf("mutation" to def.id, "ok" to false, "code" to FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, "idem" to idemRef(mutation.idempotencyKey)) + ctx.auditFields())
                throw ConnectorFailure(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN, "an earlier attempt with this idempotency key ended without a known result; check the data source before trying again with a new key")
            }
            IdempotencyDecision.Conflict -> throw ConnectorFailure(FailureCodes.IDEMPOTENCY_CONFLICT, "this idempotency key was used with different parameters")
            IdempotencyDecision.Run -> Unit
        }
        val outcome = try {
            service.mutate(ctx, ds.id, MutationExecRequest(def, bound, mutation.idempotencyKey))
        } catch (e: Exception) {
            // Only a failure that CERTAINLY applied nothing frees the key. Anything else — a timeout, a lost connection, an upstream 5xx, an unclassified
            // exception — may have been applied, so the key stays reserved: a retry gets OutcomeUnknown instead of running a second write. If the store itself
            // fails here the record simply stays RESERVED, which is also safe (it turns into UNKNOWN when its lease runs out).
            val definite = e is ConnectorFailure && e.code in NOT_EXECUTED
            runCatching { if (definite) idempotency.release(ctx.tenantId, ds.id, def.id, mutation.idempotencyKey) else idempotency.markUnknown(ctx.tenantId, ds.id, def.id, mutation.idempotencyKey) }
                .onFailure { log.warn("idempotency record update failed: {}", it.javaClass.simpleName) }
            audit.record(DataAuditActions.MUTATION_RUN, ctx.tenantId, ds.id, mapOf("mutation" to def.id, "ok" to false, "code" to ((e as? ConnectorFailure)?.code ?: FailureCodes.INTERNAL),
                "outcomeKnown" to definite, "idem" to idemRef(mutation.idempotencyKey)) + ctx.auditFields())
            throw e
        }
        val output = outcome.output?.takeIf { DataJson.mapper.writeValueAsString(it).length <= MAX_OUTPUT_BYTES }
        // the write HAS happened: failing to record it must not turn a success into an error response (the record stays reserved, which is the safe state)
        runCatching { idempotency.complete(ctx.tenantId, ds.id, def.id, mutation.idempotencyKey, StoredMutation(outcome.affected, output?.let { DataJson.mapper.writeValueAsString(it) }, clock.instant())) }
            .onFailure { log.warn("idempotency completion not recorded: {}", it.javaClass.simpleName) }
        audit.record(DataAuditActions.MUTATION_RUN, ctx.tenantId, ds.id, mapOf("mutation" to def.id, "kind" to def.kind.name, "replayed" to false, "ok" to true, "affected" to outcome.affected, "idem" to idemRef(mutation.idempotencyKey)) + ctx.auditFields())
        listener.onChange(DataChange(ctx.tenantId, ds.id, ChangeCause.MUTATION, def.invalidates, def.entity, operationOf(def.kind), recordKeyOf(output)))
        return GatewayMutationResponse(def.id, def.kind, outcome.affected, false, output)
    }

    private fun operationOf(k: MutationKind): RecordOperation? = when (k) { MutationKind.CREATE -> RecordOperation.CREATED; MutationKind.UPDATE -> RecordOperation.UPDATED; MutationKind.DELETE -> RecordOperation.DELETED; MutationKind.SUBMIT -> null }

    /** only an identifier-looking scalar named id/key leaves the result document as a record key for events */
    private fun recordKeyOf(output: JsonNode?): List<String> {
        val v = listOf("id", "key").firstNotNullOfOrNull { output?.get(it)?.takeIf { n -> n.isValueNode && !n.isNull } } ?: return emptyList()
        val s = if (DataJson.isText(v)) DataJson.text(v) else v.toString()
        return if (RECORD_KEY.matches(s)) listOf(s) else emptyList()
    }

    // ------------------------------------------------------------------------------------------------ admin-ish operations

    override fun testConnection(ctx: GatewayContext, dataSourceId: UUID): ConnectionTestResult {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        return service.testConnection(ctx, dataSourceId)
    }

    override fun discoverSchema(ctx: GatewayContext, dataSourceId: UUID, includeSamples: Boolean): DiscoveryService.RefreshResult =
        discovery.refresh(ctx, dataSourceId, includeSamples)                   // checks its own permissions (SCHEMA_DISCOVER, SCHEMA_SAMPLE)

    override fun refreshCache(ctx: GatewayContext, dataSourceId: UUID, queryId: String?) {
        guard.require(ctx, GatewayOperation.CACHE_REFRESH, dataSourceId)
        val ds = service.resolve(ctx, dataSourceId)
        if (queryId != null && queries.find(ctx.tenantId, ds.id, queryId) == null) throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found")
        if (!limits.allow("cache-refresh:${ctx.tenantId}:${ds.id}", 30, 60)) throw ConnectorFailure(FailureCodes.RATE_LIMITED, "too many requests; retry later")
        listener.onChange(DataChange(ctx.tenantId, ds.id, ChangeCause.MANUAL_REFRESH, listOfNotNull(queryId)))
        audit.record(DataAuditActions.CACHE_REFRESHED, ctx.tenantId, ds.id, mapOf("query" to queryId, "scope" to (if (queryId == null) "data_source" else "query")) + ctx.auditFields())
    }

    companion object {
        const val MAX_PARAMS = 40
        const val MAX_PARAMS_BYTES = 4_096
        const val MAX_OUTPUT_BYTES = 16 * 1024
        const val IDEMPOTENCY_TTL_SECONDS = 24L * 3600
        val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_-]{8,128}$")
        val MAPPING_REF = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val RECORD_KEY = Regex("^[A-Za-z0-9_.:-]{1,100}$")
        /**
         * Failures after which the mutation is certain NOT to have been applied: refused before anything was sent (permission, validation, address policy,
         * name resolution, TLS handshake, credentials, limits, unsupported) or reported by the source as a definite rejection. Everything else is ambiguous.
         */
        val NOT_EXECUTED = setOf(
            FailureCodes.PERMISSION_DENIED, FailureCodes.INVALID_PARAMS, FailureCodes.INVALID_CONFIG, FailureCodes.INVALID_QUERY, FailureCodes.INVALID_CREDENTIAL, FailureCodes.INVALID_MAPPING,
            FailureCodes.ADDRESS_BLOCKED, FailureCodes.HOST_UNRESOLVED, FailureCodes.TLS_FAILED, FailureCodes.AUTH_REJECTED, FailureCodes.RATE_LIMITED, FailureCodes.DISABLED,
            FailureCodes.NOT_FOUND, FailureCodes.MUTATION_NOT_FOUND, FailureCodes.MUTATION_UNSUPPORTED, FailureCodes.MUTATION_REJECTED, FailureCodes.TENANT_MISMATCH,
            FailureCodes.ROLE_TOO_PRIVILEGED, FailureCodes.READ_ONLY_VIOLATION, FailureCodes.UNSUPPORTED_TYPE, FailureCodes.PAYLOAD_TOO_LARGE
        )
        /** a short, one-way reference to an idempotency key for audit and logs: the key itself (derived from the client's) is never written anywhere */
        internal fun idemRef(key: String): String = sha(key).take(12)
        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
