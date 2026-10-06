package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.discovery.SchemaDiscovery
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.QueryDefinition
import com.systemwebstudio.data.query.QueryExecutor

/** Result of a connection test: typed, fixed-text, no host/IP/connection string/credential. */
sealed interface ConnectionTestResult {
    /** [warnings] are fixed-text advisories (for example "role has write privileges"); they do not fail the test. */
    data class Ok(val latencyMillis: Long, val warnings: List<String> = emptyList()) : ConnectionTestResult
    data class Failed(val code: String, val message: String) : ConnectionTestResult
}

/**
 * One kind of data source (contract `data-connector.md`). Connectors are called **only** by the Data Gateway / [DataSourceService]
 * after authorisation; they never open a connection on their own and never see a client-supplied SQL or URL.
 *
 * `validateConfig` is an addition to the contract's shape (proposed in DECISIONS D-C3-01): the same non-secret-config validation must run
 * when a data source is saved and again before each use, and only the connector knows its own keys. [descriptor] and [mutator] are the SPI
 * additions (see ConnectorSpi.kt); both have defaults, so a connector that only reads and has nothing special to declare is unchanged.
 */
interface DataConnector {
    val type: String
    val descriptor: ConnectorDescriptor get() = ConnectorDescriptor(type, type, ConnectorStatus.AVAILABLE, setOf(ConnectorCapability.DISCOVERY, ConnectorCapability.QUERY))
    /** null = this connector is read-only */
    fun mutator(): MutationExecutor? = null
    /** @throws ConnectorFailure(INVALID_CONFIG) with a fixed message naming the field, never its value */
    fun validateConfig(config: Map<String, String>)

    /**
     * Management API: checked when an approved query is created or edited, so a definition this connector could never run is refused up front (the same checks run
     * again before every execution). [config] is the data source's non-secret configuration. Default: accept (the definition types already validated themselves).
     * @throws ConnectorFailure(INVALID_QUERY) with a fixed message, never the offending text
     */
    fun validateQueryDefinition(def: QueryDefinition, config: Map<String, String>) {}

    /**
     * Management API: checked when an approved mutation is created or edited. Default: a read-only connector ([mutator] is null) refuses with
     * `READ_ONLY_VIOLATION`; a connector that writes also checks the shape of [MutationDefinition.target] and whether this data source may write at all.
     * @throws ConnectorFailure(READ_ONLY_VIOLATION | INVALID_CONFIG | INVALID_QUERY) with a fixed message
     */
    fun validateMutationDefinition(def: MutationDefinition, config: Map<String, String>) {
        if (mutator() == null) throw ConnectorFailure(FailureCodes.READ_ONLY_VIOLATION, "this data source is read-only")
    }

    fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult
    fun discovery(): SchemaDiscovery
    fun executor(): QueryExecutor
}

class DataConnectorRegistry(connectors: Collection<DataConnector>) {
    private val byType = connectors.associateBy { it.type }.also { require(it.size == connectors.size) { "duplicate connector type" } }
    /** a planned connector is listed in the catalogue but is never returned for use */
    fun find(type: String): DataConnector? = byType[type]?.takeIf { it.descriptor.status == ConnectorStatus.AVAILABLE }
    fun require(type: String): DataConnector {
        val c = byType[type] ?: throw ConnectorFailure(FailureCodes.UNSUPPORTED_TYPE, "data source type is not supported")
        if (c.descriptor.status != ConnectorStatus.AVAILABLE) throw ConnectorFailure(FailureCodes.NOT_IMPLEMENTED, "this connector is not available yet")
        return c
    }
    val types: Set<String> get() = byType.filterValues { it.descriptor.status == ConnectorStatus.AVAILABLE }.keys
    /** the catalogue for Studio / the admin API: available and planned connector types */
    fun descriptors(): List<ConnectorDescriptor> = byType.values.map { it.descriptor }.sortedBy { it.type }
}
