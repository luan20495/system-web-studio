package com.systemwebstudio.data

import com.systemwebstudio.data.cache.DataChangeNotifier
import com.systemwebstudio.data.cache.InMemoryCacheBackend
import com.systemwebstudio.data.cache.InMemoryDataEventBus
import com.systemwebstudio.data.cache.QueryCache
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataConnector
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.MutationExecRequest
import com.systemwebstudio.data.datasource.MutationExecutor
import com.systemwebstudio.data.datasource.MutationOutcome
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.datasource.SecretsCryptoCredentialVault
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.DiscoveryService
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.discovery.SchemaDiscovery
import com.systemwebstudio.data.gateway.DefaultDataGateway
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.InMemoryIdempotencyStore
import com.systemwebstudio.data.mapping.AppScope
import com.systemwebstudio.data.mapping.MappingCatalog
import com.systemwebstudio.data.mapping.MappingDefinition
import com.systemwebstudio.data.mapping.MappingJson
import com.systemwebstudio.data.mapping.ViewModelDefinition
import com.systemwebstudio.data.query.Column
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationCatalog
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.QueryExecutor
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.QueryResult
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.runtime.SecretsCrypto
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MutableClock(start: Instant = Instant.parse("2026-10-05T10:00:00Z")) : Clock() {
    private val ms = AtomicLong(start.toEpochMilli())
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(ms.get())
    fun advanceSeconds(s: Long) { ms.addAndGet(s * 1000) }
    override fun millis() = ms.get()
}

class InMemoryMutationCatalog : MutationCatalog {
    private val defs = CopyOnWriteArrayList<MutationDefinition>()
    fun add(d: MutationDefinition) { defs += d }
    override fun find(tenantId: UUID, dataSourceId: UUID, mutationId: String) = defs.firstOrNull { it.tenantId == tenantId && it.dataSourceId == dataSourceId && it.id == mutationId }
    override fun list(tenantId: UUID, dataSourceId: UUID): List<MutationDefinition> = defs.filter { it.tenantId == tenantId && it.dataSourceId == dataSourceId }
}

class InMemoryMappingCatalog : MappingCatalog {
    private val maps = ConcurrentHashMap<Pair<UUID, String>, MappingDefinition>()
    private val vms = ConcurrentHashMap<Pair<UUID, String>, ViewModelDefinition>()
    val scopes = CopyOnWriteArrayList<AppScope>()
    fun put(tenantId: UUID, m: MappingDefinition) { maps[tenantId to m.id] = m }
    fun put(tenantId: UUID, v: ViewModelDefinition) { vms[tenantId to v.id] = v }
    override fun findMapping(scope: AppScope, ref: String): MappingDefinition? { scopes += scope; return maps[scope.tenantId to ref] }
    override fun findViewModel(scope: AppScope, ref: String): ViewModelDefinition? { scopes += scope; return vms[scope.tenantId to ref] }
}

/** a connector whose behaviour a test scripts; rows are per data source so two tenants never share data by accident */
class FakeDataConnector : DataConnector {
    override val type = "fake"
    val rowsFor = ConcurrentHashMap<UUID, List<Map<String, Any?>>>()
    val queryCalls = AtomicInteger(); val mutationCalls = AtomicInteger()
    val credentialsSeen = CopyOnWriteArrayList<ResolvedCredential>()
    @Volatile var queryHook: (QueryRequest, DataSourceRef, ResolvedCredential) -> Unit = { _, _, _ -> }
    @Volatile var mutationHook: (MutationExecRequest, DataSourceRef, ResolvedCredential) -> MutationOutcome = { _, _, _ -> MutationOutcome(1, DataJson.toNode(mapOf("id" to "rec-1"))) }
    @Volatile var readOnly = false
    /** when set, decides the rows of a call (it sees the request, so tests can honour cursor and paging parameters) */
    @Volatile var rowsProvider: ((QueryRequest, DataSourceRef) -> List<Map<String, Any?>>)? = null
    val lastMutationParams = CopyOnWriteArrayList<Map<String, Any>>()

    override fun validateConfig(config: Map<String, String>) {}
    override fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult { credentialsSeen += cred; return ConnectionTestResult.Ok(3) }
    /** what discovery finds (empty by default); [discoveredSample] is returned, per entity, only when the caller asks for samples */
    @Volatile var discovered: DiscoveredSchema = DiscoveredSchema(emptyList())
    @Volatile var discoveredSample: List<Map<String, Any?>> = emptyList()
    val discoveries = AtomicInteger()
    override fun discovery() = object : SchemaDiscovery {
        override fun discover(ds: DataSourceRef, cred: ResolvedCredential): DiscoveredSchema { discoveries.incrementAndGet(); return discovered }
        override fun discover(ds: DataSourceRef, cred: ResolvedCredential, options: com.systemwebstudio.data.discovery.DiscoveryOptions): DiscoveredSchema {
            val schema = discover(ds, cred)
            return if (options.sampleRows == 0) schema
            else schema.copy(entities = schema.entities.map { e -> e.copy(sample = discoveredSample.take(options.sampleRows).map { row -> row.mapValues { DataJson.toNode(it.value) } }) })
        }
    }
    override fun executor() = object : QueryExecutor {
        override fun execute(req: QueryRequest, ds: DataSourceRef, cred: ResolvedCredential): QueryResult {
            queryCalls.incrementAndGet(); credentialsSeen += cred
            queryHook(req, ds, cred)
            val rows = rowsProvider?.invoke(req, ds) ?: rowsFor[ds.id] ?: emptyList()
            return QueryResult((rows.firstOrNull()?.keys ?: emptySet()).map { Column(it, NormalizedType.STRING) }, rows.map { r -> r.mapValues { DataJson.toNode(it.value) } }, false)
        }
    }
    override fun mutator(): MutationExecutor? = if (readOnly) null else object : MutationExecutor {
        override fun execute(req: MutationExecRequest, ds: DataSourceRef, cred: ResolvedCredential): MutationOutcome {
            mutationCalls.incrementAndGet(); credentialsSeen += cred; lastMutationParams += req.params
            return mutationHook(req, ds, cred)
        }
    }
}

/** everything wired with in-memory ports: the same object graph production wiring builds, minus Spring and the database */
class GatewayFixture {
    val secret = "sk-live-TOPSECRET-9f8e7d"
    val clock = MutableClock()
    val repo = InMemoryDataSourceRepository()
    val credentialStore = InMemoryCredentialStore { clock.instant() }
    val vault = SecretsCryptoCredentialVault(SecretsCrypto(Base64.getEncoder().encodeToString(ByteArray(32) { (it + 5).toByte() })), credentialStore)
    val audit = RecordingAuditSink()
    val authorizer = ScriptedAuthorizer()
    val guard = GatewayGuard(authorizer, audit)
    val connector = FakeDataConnector()
    val limiter = ClockedRateLimitGate { clock.millis() }
    val service = DataSourceService(repo, vault, DataConnectorRegistry(listOf(connector)), limiter, audit)
    val queries = InMemoryQueryCatalog()
    val mutations = InMemoryMutationCatalog()
    val mappings = InMemoryMappingCatalog()
    val snapshots = InMemorySchemaStore()
    val discovery = DiscoveryService(service, snapshots, guard, limiter, audit, clock)
    val backend = InMemoryCacheBackend(nowMs = { clock.millis() })
    val cache = QueryCache(backend, clock)
    val bus = InMemoryDataEventBus()
    val notifier = DataChangeNotifier(cache, bus, clock)
    val idempotency = InMemoryIdempotencyStore(clock)
    val gateway = DefaultDataGateway(guard, service, queries, mutations, mappings, discovery, cache, idempotency, notifier, audit, limiter, clock = clock)

    fun tenant() = TestTenant()
    fun ctx(t: TestTenant) = t.context(projectId = UUID.randomUUID(), appVersionId = "v1")

    fun register(t: TestTenant, rows: List<Map<String, Any?>> = emptyList()): DataSource {
        val id = UUID.randomUUID()
        connector.rowsFor[id] = rows
        return repo.save(DataSource(DataSourceRef(id, t.tenantId, "fake", mapOf("host" to "db.internal-looking.example.com")), "src-" + id.toString().take(4), credentialRef = vault.store(t.tenantId, mapOf("authValue" to secret))))
    }

    fun query(t: TestTenant, ds: DataSource, id: String = "customers", ttl: Int = 60, version: Long = 1, params: List<QueryParamSpec> = listOf(QueryParamSpec("status", ParamType.STRING, false))) =
        SqlQueryDefinition(id, t.tenantId, ds.id, "SELECT 1", params, 1000, ttl, version).also { queries.add(it) }

    fun mutation(t: TestTenant, ds: DataSource, id: String = "create-customer", kind: MutationKind = MutationKind.CREATE, invalidates: List<String> = listOf("customers"), entity: String? = "customer") =
        MutationDefinition(id, t.tenantId, ds.id, kind, "customers", listOf(QueryParamSpec("name", ParamType.STRING, true)), invalidates, entity).also { mutations.add(it) }

    /** a mapping id `m-<query>` exposing id + displayName (+ status) and a view model `vm-<query>` */
    fun mapping(t: TestTenant, queryId: String = "customers", version: Long = 1): Pair<MappingDefinition, ViewModelDefinition> {
        val m = MappingJson.mapping(DataJson.parse("""{"id":"m-$queryId","queryRef":"$queryId","version":$version,"fields":[
            {"from":"id","to":"id","nullable":false},{"from":"name","to":"displayName"},{"from":"status","to":"status"}]}""".toByteArray()))
        val v = MappingJson.viewModel(DataJson.parse("""{"id":"vm-$queryId","queryRef":"$queryId","mappingRef":"m-$queryId","cardinality":"LIST","fields":[
            {"name":"id","type":"NUMBER"},{"name":"displayName","type":"STRING"},{"name":"status","type":"STRING"}]}""".toByteArray()))
        mappings.put(t.tenantId, m); mappings.put(t.tenantId, v)
        return m to v
    }

    fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }
}
