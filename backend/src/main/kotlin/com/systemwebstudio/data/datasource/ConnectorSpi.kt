package com.systemwebstudio.data.datasource

import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.DiscoveryOptions
import com.systemwebstudio.data.discovery.SchemaDiscovery
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.QueryExecutor
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.QueryResult
import tools.jackson.databind.JsonNode

/*
 * Connector SPI. A new kind of source (MySQL, GraphQL, CSV, Google Sheets, Odoo, Salesforce, ...) is one class implementing [DataConnector]
 * plus a [ConnectorDescriptor]; nothing else in the platform changes. What every connector must honour is written down once, as the
 * connector contract test kit (`ConnectorSpiTests` for the slots and the registry, plus each driver's own security tests), and every driver in this package must satisfy it:
 *
 *   1. all outbound network access goes through `PinnedResolution` + `PublicAddressPolicy` (no second SSRF mechanism, no redirects);
 *   2. the credential is used inside the call and appears nowhere else (messages, logs, audit, results);
 *   3. only [ConnectorFailure] with fixed text escapes (no cause, no stack, no host/IP/SQL/URL);
 *   4. results are bounded in time, rows and bytes; the connector enforces the data source's limits even if the caller did not;
 *   5. a data source of another tenant is never reachable: `ds.tenantId` is compared with the request's tenant;
 *   6. reads cannot write: a connector that cannot guarantee it does not offer [ConnectorCapability.QUERY].
 */

enum class ConnectorCapability { DISCOVERY, QUERY, MUTATION, SYNC }

/** AVAILABLE = implemented and tested; PLANNED = the slot and its contract exist, there is no driver yet (a live driver needs real credentials to verify). */
enum class ConnectorStatus { AVAILABLE, PLANNED }

data class ConfigKeySpec(val name: String, val required: Boolean, val description: String)

/** What Studio and the admin API may show about a connector type: no secrets, no code, fixed text. */
data class ConnectorDescriptor(
    val type: String,
    val displayName: String,
    val status: ConnectorStatus,
    val capabilities: Set<ConnectorCapability>,
    val configKeys: List<ConfigKeySpec> = emptyList(),
    /** names of the credential entries the connector reads (never values) */
    val credentialKeys: List<String> = emptyList(),
    val notes: String? = null
)

/** A write expressed as a reference to a **declared, approved mutation** — never SQL, a table name or a URL (C4 `MutationRequest` twin). */
class MutationExecRequest(val definition: MutationDefinition, val params: Map<String, Any>, val idempotencyKey: String?) {
    override fun toString() = "MutationExecRequest(${definition.id})"       // parameter values may be personal data
}

/** [affected] = rows/records touched when the source reports it; [output] = a small result document (bounded by the connector). */
class MutationOutcome(val affected: Long?, val output: JsonNode?) {
    override fun toString() = "MutationOutcome(affected=$affected)"
}

/**
 * Write side of a connector (BLOCKERS B-C3-03 / C4 `ActionDataPort.mutate`). Optional: a connector that returns null from
 * [DataConnector.mutator] is read-only, which is what the REST and PostgreSQL drivers of this phase are by design.
 */
interface MutationExecutor {
    fun execute(req: MutationExecRequest, ds: DataSourceRef, cred: ResolvedCredential): MutationOutcome
}

/** Registered so Studio can list the connector catalogue and the SPI is exercised; every operation answers [FailureCodes.NOT_IMPLEMENTED]. */
class PlannedConnector(override val descriptor: ConnectorDescriptor) : DataConnector {
    override val type = descriptor.type
    private fun nope(): Nothing = throw ConnectorFailure(FailureCodes.NOT_IMPLEMENTED, "this connector is not available yet")
    override fun validateConfig(config: Map<String, String>) = nope()
    override fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult = ConnectionTestResult.Failed(FailureCodes.NOT_IMPLEMENTED, "this connector is not available yet")
    override fun discovery(): SchemaDiscovery = object : SchemaDiscovery {
        override fun discover(ds: DataSourceRef, cred: ResolvedCredential): DiscoveredSchema = nope()
        override fun discover(ds: DataSourceRef, cred: ResolvedCredential, options: DiscoveryOptions): DiscoveredSchema = nope()
    }
    override fun executor(): QueryExecutor = object : QueryExecutor {
        override fun execute(req: QueryRequest, ds: DataSourceRef, cred: ResolvedCredential): QueryResult = nope()
    }
}

/** The connector slots the platform reserves. Each states the capability set and configuration it will have so Studio and C5 can design against it. */
object PlannedConnectors {
    private fun planned(type: String, name: String, caps: Set<ConnectorCapability>, keys: List<ConfigKeySpec>, cred: List<String>, notes: String) =
        PlannedConnector(ConnectorDescriptor(type, name, ConnectorStatus.PLANNED, caps, keys, cred, notes))
    private val rw = setOf(ConnectorCapability.DISCOVERY, ConnectorCapability.QUERY, ConnectorCapability.SYNC)

    fun all(): List<DataConnector> = listOf(
        planned("mysql", "MySQL (read-only)", rw,
            listOf(ConfigKeySpec("host", true, "public DNS name"), ConfigKeySpec("port", false, "default 3306"), ConfigKeySpec("database", true, "database name"), ConfigKeySpec("sslmode", false, "TLS is mandatory")),
            listOf("username", "password"), "Same rules as PostgreSQL: TLS only, read-only session, SqlGuard-style statement check, pinned public addresses."),
        planned("csv", "CSV file", setOf(ConnectorCapability.DISCOVERY, ConnectorCapability.QUERY),
            listOf(ConfigKeySpec("url", true, "https URL of the file"), ConfigKeySpec("delimiter", false, "default ,"), ConfigKeySpec("hasHeader", false, "default true")),
            listOf("authValue"), "Fetched through the pinned HTTPS transport with the REST byte/row/time caps; the header row gives the fields, every column is text until a mapping converts it."),
        planned("graphql", "GraphQL", rw,
            listOf(ConfigKeySpec("endpoint", true, "https URL"), ConfigKeySpec("authHeader", false, "header name carrying the credential")),
            listOf("authValue"), "Queries are approved documents with typed variables; introspection is the discovery. Needs POST support in the pinned transport."),
        planned("google_sheets", "Google Sheets", rw,
            listOf(ConfigKeySpec("spreadsheetId", true, "id of the sheet"), ConfigKeySpec("range", false, "A1 range")),
            listOf("serviceAccountKey"), "OAuth/service account; read-only scope. Needs live Google credentials to verify."),
        planned("odoo", "Odoo", rw + ConnectorCapability.MUTATION,
            listOf(ConfigKeySpec("baseUrl", true, "https URL"), ConfigKeySpec("database", true, "Odoo database")),
            listOf("username", "apiKey"), "JSON-RPC over the pinned transport; models become entities. Needs a live instance to verify."),
        planned("salesforce", "Salesforce", rw + ConnectorCapability.MUTATION,
            listOf(ConfigKeySpec("instanceUrl", true, "https URL")),
            listOf("clientId", "clientSecret"), "OAuth client-credentials; SOQL as approved, parameterised queries. Needs a live org to verify.")
    )
}
