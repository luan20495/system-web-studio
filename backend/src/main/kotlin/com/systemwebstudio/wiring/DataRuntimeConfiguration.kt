package com.systemwebstudio.wiring

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.cache.DataChangeNotifier
import com.systemwebstudio.data.cache.DataEventBus
import com.systemwebstudio.data.cache.InMemoryDataEventBus
import com.systemwebstudio.data.cache.QueryCache
import com.systemwebstudio.data.cache.RedisCacheBackend
import com.systemwebstudio.data.datasource.AuditServiceSink
import com.systemwebstudio.data.datasource.CredentialStore
import com.systemwebstudio.data.datasource.CredentialVault
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataConnector
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.PlannedConnectors
import com.systemwebstudio.data.datasource.RateLimitGate
import com.systemwebstudio.data.datasource.RedisRateLimitGate
import com.systemwebstudio.data.datasource.SecretsCryptoCredentialVault
import com.systemwebstudio.data.datasource.postgres.PostgresConnector
import com.systemwebstudio.data.datasource.postgres.PostgresTargetPolicy
import com.systemwebstudio.data.datasource.rest.RestConnector
import com.systemwebstudio.data.discovery.DiscoveryService
import com.systemwebstudio.data.discovery.SourceSchemaStore
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.gateway.DefaultDataGateway
import com.systemwebstudio.data.gateway.GatewayAuthorizer
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.IdempotencyStore
import com.systemwebstudio.data.mapping.MappingCatalog
import com.systemwebstudio.data.query.MutationCatalog
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.runtime.SecretsCrypto
import com.systemwebstudio.wiring.persistence.DataSourceBindingWriter
import com.systemwebstudio.wiring.persistence.IdempotencyPurgeRunner
import com.systemwebstudio.wiring.persistence.JdbcCredentialStore
import com.systemwebstudio.wiring.persistence.JdbcDataSourceRepository
import com.systemwebstudio.wiring.persistence.JdbcDataSourceSlotBindings
import com.systemwebstudio.wiring.persistence.JdbcIdempotencyStore
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import com.systemwebstudio.wiring.persistence.JdbcQueryCatalog
import com.systemwebstudio.wiring.persistence.JdbcSourceSchemaStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import com.systemwebstudio.access.adapters.GatewayAuthorizer as C1GatewayAuthorizer

/**
 * C0 · the Data Platform runtime: C1/C2 adapters plus the durable C3 persistence of V28 and the `DataGateway` bean (D-C0-21).
 * Present only with `app.data-platform.enabled=true` (default false), so with the flag off nothing here exists and the data routes keep answering 503.
 *
 * What is durable (V28, plain JDBC): data sources, credentials (ciphertext only), schema snapshots, approved queries / mutations, idempotency
 * records and the slot bindings. What stays in memory by design: the query cache lives in Redis, the realtime event bus is per instance.
 * Production connectors (postgres, rest) are read-only; a writable connector is a separate decision (BLOCKERS B-C0-W-04). The registry also takes
 * every other `DataConnector` bean, which is how tests plug in a writable test connector.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class DataRuntimeConfiguration {
    @Bean
    fun c3GatewayAuthorizer(c1: C1GatewayAuthorizer): GatewayAuthorizer = C3GatewayAuthorizerAdapter(c1)

    @Bean
    fun runtimeMappingCatalog(definitions: RuntimeAppDefinitions): MappingCatalog = RuntimeMappingCatalog(definitions)

    // ---- persistence (V28)

    @Bean fun c3DataSourceRepository(jdbc: JdbcTemplate): DataSourceRepository = JdbcDataSourceRepository(jdbc)
    @Bean fun c3CredentialStore(jdbc: JdbcTemplate): CredentialStore = JdbcCredentialStore(jdbc)
    @Bean fun c3QueryCatalog(jdbc: JdbcTemplate): QueryCatalog = JdbcQueryCatalog(jdbc)
    @Bean fun c3MutationCatalog(jdbc: JdbcTemplate): MutationCatalog = JdbcMutationCatalog(jdbc)
    @Bean fun c3SourceSchemaStore(jdbc: JdbcTemplate): SourceSchemaStore = JdbcSourceSchemaStore(jdbc)
    @Bean fun c3SlotBindings(jdbc: JdbcTemplate): DataSourceSlotBindings = JdbcDataSourceSlotBindings(jdbc)
    @Bean fun c3BindingWriter(jdbc: JdbcTemplate): DataSourceBindingWriter = DataSourceBindingWriter(jdbc)

    /** D4: retention is configurable and never below 7 days (the store refuses a smaller value, so a bad setting stops the application at startup) */
    @Bean
    fun c3IdempotencyStore(
        jdbc: JdbcTemplate,
        @Value("\${app.data-platform.idempotency-retention:P30D}") retention: String
    ): JdbcIdempotencyStore = JdbcIdempotencyStore(jdbc, minRetention = Duration.parse(retention))

    @Bean
    fun c3IdempotencyPurgeRunner(store: JdbcIdempotencyStore): IdempotencyPurgeRunner = IdempotencyPurgeRunner(store)

    // ---- gateway

    @Bean fun c3CredentialVault(crypto: SecretsCrypto, store: CredentialStore): CredentialVault = SecretsCryptoCredentialVault(crypto, store)
    @Bean fun c3RateLimitGate(limiter: RateLimiter): RateLimitGate = RedisRateLimitGate(limiter)
    @Bean fun c3AuditSink(audit: AuditService): DataAuditSink = AuditServiceSink(audit)

    @Bean
    fun c3ConnectorRegistry(
        queries: QueryCatalog,
        @Value("\${spring.datasource.url:}") platformUrl: String,
        @Value("\${app.runtime.appdb-url:}") appDbUrl: String,
        extra: ObjectProvider<DataConnector>
    ): DataConnectorRegistry {
        // the platform's own databases are never a valid target of a data source (fail closed: without a readable platform URL the application does not start)
        val policy = PostgresTargetPolicy.denyingPlatformDatabases(*listOfNotNull(platformUrl.takeIf { it.isNotBlank() }, appDbUrl.takeIf { it.isNotBlank() }).toTypedArray())
        val connectors = ArrayList<DataConnector>()
        connectors.add(PostgresConnector(queries, policy))
        connectors.add(RestConnector(queries))
        connectors.addAll(PlannedConnectors.all())
        connectors.addAll(extra.orderedStream().toList())
        return DataConnectorRegistry(connectors)
    }

    @Bean
    fun c3DataSourceService(repository: DataSourceRepository, vault: CredentialVault, registry: DataConnectorRegistry, limits: RateLimitGate, audit: DataAuditSink) =
        DataSourceService(repository, vault, registry, limits, audit)

    @Bean fun c3QueryCache(redis: StringRedisTemplate): QueryCache = QueryCache(RedisCacheBackend(redis))
    @Bean fun c3EventBus(): DataEventBus = InMemoryDataEventBus()
    @Bean fun c3ChangeListener(cache: QueryCache, bus: DataEventBus): DataChangeListener = DataChangeNotifier(cache, bus)
    @Bean fun c3GatewayGuard(authorizer: GatewayAuthorizer, audit: DataAuditSink) = GatewayGuard(authorizer, audit)

    @Bean
    fun c3DiscoveryService(service: DataSourceService, store: SourceSchemaStore, guard: GatewayGuard, limits: RateLimitGate, audit: DataAuditSink) =
        DiscoveryService(service, store, guard, limits, audit)

    /** The one `DataGateway`: R1 (`/api/runtime/**` query route) and the Action data port ask for it through `ObjectProvider<DataGateway>`. */
    @Bean
    fun c3DataGateway(
        guard: GatewayGuard, service: DataSourceService, queries: QueryCatalog, mutations: MutationCatalog, mappings: MappingCatalog,
        discovery: DiscoveryService, cache: QueryCache, idempotency: IdempotencyStore, listener: DataChangeListener, audit: DataAuditSink, limits: RateLimitGate
    ): DataGateway = DefaultDataGateway(guard, service, queries, mutations, mappings, discovery, cache, idempotency, listener, audit, limits)
}
