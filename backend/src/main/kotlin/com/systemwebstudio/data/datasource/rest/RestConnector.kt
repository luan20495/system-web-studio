package com.systemwebstudio.data.datasource.rest

import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataConnector
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.discovery.DiscoveredEntity
import com.systemwebstudio.data.discovery.DiscoveredField
import com.systemwebstudio.data.discovery.DiscoveryOptions
import com.systemwebstudio.data.discovery.SampleMasker
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.discovery.SchemaDiscovery
import com.systemwebstudio.data.query.Column
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.QueryCatalog
import com.systemwebstudio.data.query.QueryExecutor
import com.systemwebstudio.data.query.QueryParams
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.QueryResult
import com.systemwebstudio.data.query.RestQueryDefinition
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeType
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * REST data source: HTTPS GET of **declared** path templates on a registered base URL, through [PinnedHttpsTransport] (SSRF guard reused
 * from `PublicAddress`, pinned resolution, no redirects, timeouts and size caps). The credential is added as one header at the last moment
 * and exists nowhere else; failures are [ConnectorFailure]s with fixed text.
 *
 * Only `GET` is supported in this foundation: queries are reads, and a write-capable REST verb belongs to the Action runtime (C4).
 */
class RestConnector(
    catalog: QueryCatalog,
    private val transport: RestTransport = PinnedHttpsTransport()
) : DataConnector {
    override val type = DataSourceTypes.REST
    private val exec = RestQueryExecutor(catalog, transport)
    private val discovery = RestSchemaDiscovery(catalog, exec)

    override fun validateConfig(config: Map<String, String>) { RestConnectorConfig.parse(config) }
    override fun validateQueryDefinition(def: com.systemwebstudio.data.query.QueryDefinition, config: Map<String, String>) {
        if (def !is RestQueryDefinition) throw ConnectorFailure(FailureCodes.INVALID_QUERY, "a REST data source takes REST queries")
    }
    override fun discovery(): SchemaDiscovery = discovery
    override fun executor(): QueryExecutor = exec

    override fun test(ds: DataSourceRef, cred: ResolvedCredential): ConnectionTestResult {
        return try {
            val cfg = RestConnectorConfig.parse(ds.configNonSecret)
            val started = System.nanoTime()
            val response = transport.get(RestRequests.build(cfg, cfg.testPath, "", cred))
            val millis = (System.nanoTime() - started) / 1_000_000
            when (response.status) {
                in 200..299 -> ConnectionTestResult.Ok(millis)
                in 300..399 -> ConnectionTestResult.Failed(FailureCodes.REDIRECT_BLOCKED, "the endpoint redirects; configure the final address (redirects are never followed)")
                401, 403 -> ConnectionTestResult.Failed(FailureCodes.AUTH_REJECTED, "the data source rejected the credential")
                else -> ConnectionTestResult.Failed(FailureCodes.UPSTREAM_STATUS, "the data source answered with an error status")
            }
        } catch (e: ConnectorFailure) {
            ConnectionTestResult.Failed(e.code, e.safeMessage)
        }
    }
}

/** Builds the transport request. The only code that turns a credential into a header. */
internal object RestRequests {
    fun build(cfg: RestConnectorConfig, path: String, rawQuery: String, cred: ResolvedCredential): HttpGetRequest {
        val headers = LinkedHashMap<String, String>()
        cfg.authHeader?.let { name -> headers[name] = cred.get(AUTH_VALUE) ?: throw ConnectorFailure(FailureCodes.INVALID_CREDENTIAL, "credential is incomplete") }
        val url = URI(cfg.origin + cfg.basePath + path + (if (rawQuery.isEmpty()) "" else "?$rawQuery"))
        return HttpGetRequest(url, headers, connectTimeoutMillis = minOf(cfg.timeoutMillis, 3_000), totalTimeoutMillis = cfg.timeoutMillis.toLong(), maxResponseBytes = cfg.maxResponseBytes)
    }
    const val AUTH_VALUE = "authValue"
}

class RestQueryExecutor(private val catalog: QueryCatalog, private val transport: RestTransport) : QueryExecutor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(req: QueryRequest, ds: DataSourceRef, cred: ResolvedCredential): QueryResult {
        if (req.tenant.tenantId != ds.tenantId) throw ConnectorFailure(FailureCodes.TENANT_MISMATCH, "data source does not belong to the tenant")
        val def = catalog.find(ds.tenantId, ds.id, req.queryId) as? RestQueryDefinition ?: throw ConnectorFailure(FailureCodes.QUERY_NOT_FOUND, "query not found")
        val cfg = RestConnectorConfig.parse(ds.configNonSecret)
        val bound = QueryParams.bind(def.params, req.params)
        val limit = minOf(def.maxRows, cfg.maxRows, req.page?.limit ?: Int.MAX_VALUE)

        val path = fillPath(def, bound)
        val query = ArrayList<Pair<String, String>>()
        for ((param, key) in def.queryParams.toSortedMap()) bound[param]?.let { query += key to stringValue(it) }
        req.page?.let { p ->
            def.limitParam?.let { query += it to (limit + 1).toString() }
            def.offsetParam?.let { query += it to p.offset.toString() }
        }
        val rawQuery = query.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        val response = transport.get(RestRequests.build(cfg, path, rawQuery, cred))
        when (response.status) {
            in 200..299 -> Unit
            in 300..399 -> throw ConnectorFailure(FailureCodes.REDIRECT_BLOCKED, "the data source redirected the request; redirects are not followed")
            401, 403 -> throw ConnectorFailure(FailureCodes.AUTH_REJECTED, "the data source rejected the credential")
            else -> throw ConnectorFailure(FailureCodes.UPSTREAM_STATUS, "the data source answered with an error status")
        }
        val ct = response.contentType?.lowercase()
        if (ct == null || !(ct.startsWith("application/json") || ct.contains("+json"))) throw ConnectorFailure(FailureCodes.RESPONSE_NOT_JSON, "the data source did not answer with JSON")
        val root = try { DataJson.parse(response.body) } catch (e: Exception) {
            log.debug("unparseable json: {}", e.javaClass.simpleName)
            throw ConnectorFailure(FailureCodes.RESPONSE_NOT_JSON, "the data source answered with invalid JSON")
        }
        val node = if (def.rowsPointer.isEmpty()) root else root.at(def.rowsPointer)
        if (node.isMissingNode) throw ConnectorFailure(FailureCodes.RESPONSE_INVALID, "the response does not have the expected shape")
        var rows: List<JsonNode> = when {
            node.isArray -> DataJson.elements(node)
            node.isObject -> listOf(node)
            else -> throw ConnectorFailure(FailureCodes.RESPONSE_INVALID, "the response does not have the expected shape")
        }
        if (req.page != null && def.offsetParam == null && req.page.offset > 0) rows = rows.drop(req.page.offset)
        val truncated = rows.size > limit
        val cut = rows.take(limit).map { toRow(it) }
        return QueryResult(inferColumns(cut), cut, truncated)
    }

    private fun toRow(node: JsonNode): Map<String, JsonNode> =
        if (node.isObject) LinkedHashMap<String, JsonNode>().also { m -> DataJson.keys(node).forEach { k -> m[k] = node.get(k) } } else mapOf("value" to node)

    private fun fillPath(def: RestQueryDefinition, bound: Map<String, Any>): String =
        RestQueryDefinition.PLACEHOLDER.replace(def.pathTemplate) { m ->
            val v = bound[m.groupValues[1]] ?: throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "missing parameter ${m.groupValues[1]}")
            pathSegment(stringValue(v))
        }

    companion object {
        private val UNRESERVED = Regex("[A-Za-z0-9._~-]")

        internal fun stringValue(v: Any): String = when (v) { is java.time.OffsetDateTime -> v.toString(); is java.math.BigDecimal -> v.toPlainString(); else -> v.toString() }

        /** one path segment: `/`, `\`, control characters and dot-segments are refused outright, everything outside the unreserved set is percent-encoded */
        internal fun pathSegment(value: String): String {
            if (value.isEmpty() || value == "." || value == ".." || value.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f })
                throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "invalid path parameter")
            return encode(value)
        }

        internal fun encode(value: String): String {
            val sb = StringBuilder()
            for (b in value.toByteArray(StandardCharsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (UNRESERVED.matches(c.toString())) sb.append(c) else sb.append('%').append("%02X".format(b.toInt() and 0xff))
            }
            return sb.toString()
        }

        internal fun inferColumns(rows: List<Map<String, JsonNode>>): List<Column> {
            val order = LinkedHashMap<String, NormalizedType?>()
            for (row in rows.take(100)) for ((k, v) in row) {
                val t = typeOf(v)
                val seen = order[k]
                order[k] = when { !order.containsKey(k) -> t; t == null -> seen; seen == null -> t; seen == t -> t
                    (seen == NormalizedType.INTEGER && t == NormalizedType.NUMBER) || (seen == NormalizedType.NUMBER && t == NormalizedType.INTEGER) -> NormalizedType.NUMBER
                    else -> NormalizedType.OTHER }
            }
            return order.map { (k, t) -> Column(k, t ?: NormalizedType.OTHER) }
        }

        internal fun typeOf(v: JsonNode): NormalizedType? = when (DataJson.type(v)) {
            JsonNodeType.STRING -> NormalizedType.STRING
            JsonNodeType.NUMBER -> if (v.isIntegralNumber) NormalizedType.INTEGER else NormalizedType.NUMBER
            JsonNodeType.BOOLEAN -> NormalizedType.BOOLEAN
            JsonNodeType.ARRAY, JsonNodeType.OBJECT -> NormalizedType.JSON
            JsonNodeType.BINARY -> NormalizedType.BINARY
            else -> null
        }
    }
}

/**
 * Discovery for REST sources: the shape of the responses of the **declared, discoverable** endpoints (no crawling, no guessing URLs).
 * Each probe goes through the same [RestQueryExecutor] — same SSRF guard, limits and error mapping — with the parameter defaults only.
 */
class RestSchemaDiscovery(private val catalog: QueryCatalog, private val executor: RestQueryExecutor) : SchemaDiscovery {
    override fun discover(ds: DataSourceRef, cred: ResolvedCredential): DiscoveredSchema = discover(ds, cred, DiscoveryOptions())

    override fun discover(ds: DataSourceRef, cred: ResolvedCredential, options: DiscoveryOptions): DiscoveredSchema {
        val all = catalog.list(ds.tenantId, ds.id).filterIsInstance<RestQueryDefinition>().filter { it.discoverable }
        val defs = all.take(MAX_ENDPOINTS)
        val entities = ArrayList<DiscoveredEntity>(); val warnings = ArrayList<String>()
        for (def in defs) {
            if (def.params.any { it.required && it.default == null }) { warnings += "${def.id}: needs parameters, skipped"; continue }
            try {
                val sample = executor.execute(QueryRequest(def.id, emptyMap(), null, com.systemwebstudio.tenancy.TenantContext(ds.tenantId, null)), ds, cred)
                val nullable = sample.columns.associate { c -> c.name to (sample.rows.isEmpty() || sample.rows.any { r -> r[c.name]?.let { DataJson.type(it) == JsonNodeType.NULL } != false }) }
                entities += DiscoveredEntity(def.id, null, EntityKind.ENDPOINT, sample.columns.map { DiscoveredField(it.name, it.type, nullable[it.name] ?: true) },
                    metadata = mapOf("source" to "rest", "path" to def.pathTemplate),
                    sample = if (options.sampleRows > 0) SampleMasker.maskRows(sample.rows, options.sampleRows) else emptyList())      // masked before it leaves this function
            } catch (e: ConnectorFailure) {
                warnings += "${def.id}: ${e.code}"
            }
        }
        return DiscoveredSchema(entities, truncated = all.size > MAX_ENDPOINTS, warnings = warnings)
    }

    private companion object { const val MAX_ENDPOINTS = 25 }
}
