package com.systemwebstudio.data.query

import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.data.discovery.NormalizedType
import tools.jackson.databind.JsonNode
import java.util.UUID

data class PageSpec(val limit: Int, val offset: Int = 0) {
    init { require(limit in 1..MAX_PAGE_LIMIT && offset in 0..MAX_PAGE_OFFSET) { "invalid page" } }
    companion object { const val MAX_PAGE_LIMIT = 10_000; const val MAX_PAGE_OFFSET = 1_000_000 }
}

/**
 * Contract `data-connector.md`: **no SQL and no URL from the client** — only the id of an approved, already defined query plus typed
 * parameters. What the id means (SQL template, REST path template) is decided by the [QueryCatalog], never by the caller.
 */
data class QueryRequest(val queryId: String, val params: Map<String, JsonNode>, val page: PageSpec?, val tenant: TenantContext)

data class Column(val name: String, val type: NormalizedType)

/** [truncated] = the row, byte or page cap cut the result; the rows returned are a prefix of the full result. */
data class QueryResult(val columns: List<Column>, val rows: List<Map<String, JsonNode>>, val truncated: Boolean)

interface QueryExecutor {
    fun execute(req: QueryRequest, ds: DataSourceRef, cred: ResolvedCredential): QueryResult
}

/** DATE = calendar date (ISO-8601 `yyyy-MM-dd`); the names STRING/NUMBER/BOOLEAN/DATE also exist in C2's `ParamType` so app definitions map onto them directly */
enum class ParamType { STRING, INTEGER, NUMBER, BOOLEAN, TIMESTAMP, DATE }

data class QueryParamSpec(val name: String, val type: ParamType, val required: Boolean = true, val default: JsonNode? = null) {
    init { require(NAME.matches(name)) { "invalid parameter name" } }
    companion object { val NAME = Regex("^[a-z][A-Za-z0-9_]{0,39}$") }
}

/** Hard ceilings no definition or datasource configuration can raise. */
object QueryLimits {
    const val MAX_ROWS = 10_000
    const val DEFAULT_ROWS = 1_000
    const val MAX_RESPONSE_BYTES = 5_000_000          // same ceiling as the existing connector proxy (MAX_RESPONSE)
    const val DEFAULT_RESPONSE_BYTES = 2_000_000
    const val MIN_TIMEOUT_MS = 500
    const val MAX_TIMEOUT_MS = 30_000
    const val DEFAULT_TIMEOUT_MS = 10_000
    const val MAX_CACHE_TTL_SECONDS = 86_400
}

/**
 * An approved query. Definitions are server-side data (created by an authorised user through a later API), never client input.
 * Sealed: a connector executes only the kind it understands.
 */
sealed interface QueryDefinition {
    val id: String
    val tenantId: UUID
    val dataSourceId: UUID
    val params: List<QueryParamSpec>
    val maxRows: Int
    /** 0 = never cached; otherwise how long the *mapped* result of this query may be served from the cache (ceiling [QueryLimits.MAX_CACHE_TTL_SECONDS]) */
    val cacheTtlSeconds: Int
    /** bumped on every edit of the definition; part of the cache key, so an edited query never serves results of the old text */
    val version: Long
}

/**
 * Read-only SQL with named parameters (`:name`). The text is checked by `SqlGuard` when it is registered and again before each run;
 * values are only ever bound as prepared-statement parameters, never concatenated.
 */
data class SqlQueryDefinition(
    override val id: String, override val tenantId: UUID, override val dataSourceId: UUID, val sql: String,
    override val params: List<QueryParamSpec> = emptyList(), override val maxRows: Int = QueryLimits.DEFAULT_ROWS,
    override val cacheTtlSeconds: Int = 0, override val version: Long = 1
) : QueryDefinition {
    init {
        require(ID.matches(id)) { "invalid query id"; }; require(maxRows in 1..QueryLimits.MAX_ROWS) { "invalid maxRows" }
        require(cacheTtlSeconds in 0..QueryLimits.MAX_CACHE_TTL_SECONDS) { "invalid cache ttl" }
    }
    companion object { val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$") }
}

/**
 * A GET of a declared path template. `{name}` segments take a declared parameter; [queryParams] maps parameter name → query-string key.
 * [rowsPointer] is a JSON Pointer to the array (or object) holding the rows (`""` = the document root).
 */
data class RestQueryDefinition(
    override val id: String, override val tenantId: UUID, override val dataSourceId: UUID, val pathTemplate: String,
    override val params: List<QueryParamSpec> = emptyList(), val queryParams: Map<String, String> = emptyMap(),
    val rowsPointer: String = "", override val maxRows: Int = QueryLimits.DEFAULT_ROWS,
    val limitParam: String? = null, val offsetParam: String? = null, val discoverable: Boolean = false,
    override val cacheTtlSeconds: Int = 0, override val version: Long = 1
) : QueryDefinition {
    init {
        require(SqlQueryDefinition.ID.matches(id)) { "invalid query id" }
        require(cacheTtlSeconds in 0..QueryLimits.MAX_CACHE_TTL_SECONDS) { "invalid cache ttl" }
        require(maxRows in 1..QueryLimits.MAX_ROWS) { "invalid maxRows" }
        require(pathTemplate.startsWith("/") && PATH.matches(pathTemplate) && pathTemplate.split('/').none { it == ".." || it == "." }) { "invalid path template" }
        val declared = params.map { it.name }.toSet()
        require(declared.size == params.size) { "duplicate parameter" }
        PLACEHOLDER.findAll(pathTemplate).forEach { require(it.groupValues[1] in declared) { "path parameter is not declared" } }
        queryParams.forEach { (p, key) -> require(p in declared && KEY.matches(key)) { "invalid query parameter mapping" } }
        require(rowsPointer.isEmpty() || POINTER.matches(rowsPointer)) { "invalid rows pointer" }
        listOfNotNull(limitParam, offsetParam).forEach { require(KEY.matches(it)) { "invalid paging parameter" } }
    }
    companion object {
        private const val SEG = "(?:[A-Za-z0-9._~-]+|\\{[a-z][A-Za-z0-9_]{0,39}\\})"
        private val PATH = Regex("^/$|^(?:/$SEG)+/?$")                 // no empty segments: "//host/x" is never a path
        val PLACEHOLDER = Regex("\\{([a-z][A-Za-z0-9_]{0,39})\\}")
        private val KEY = Regex("^[A-Za-z][A-Za-z0-9_.\\[\\]-]{0,63}$")
        private val POINTER = Regex("^(/[A-Za-z0-9_.-]{1,64}){1,8}$")
    }
}

/**
 * Port: where approved definitions live. Persistence needs a migration that C0 has not issued yet (BOARD.md, *Migration requests*), so
 * T8 ships the port only; tests use an in-memory implementation.
 */
interface QueryCatalog {
    /** Tenant- and datasource-scoped: a definition of another tenant or data source is simply not found. */
    fun find(tenantId: UUID, dataSourceId: UUID, queryId: String): QueryDefinition?
    fun list(tenantId: UUID, dataSourceId: UUID): List<QueryDefinition>
}

/** Parameter binding shared by every executor: typed, declared names only, defaults applied, nothing echoed back. */
internal object QueryParams {
    private const val MAX_STRING = 1_000

    /** Returns parameter name → validated Java value (String, Long, java.math.BigDecimal, Boolean, java.time.OffsetDateTime, java.time.LocalDate). */
    fun bind(specs: List<QueryParamSpec>, given: Map<String, JsonNode>): Map<String, Any> {
        val byName = specs.associateBy { it.name }
        if (given.keys.any { it !in byName }) throw bad("unknown parameter")
        val out = LinkedHashMap<String, Any>()
        for (spec in specs) {
            val node = given[spec.name] ?: spec.default
            val raw = node?.let { DataJson.toJava(it) }
            if (raw == null) { if (spec.required) throw bad("missing parameter ${spec.name}"); continue }
            out[spec.name] = coerce(spec, raw)
        }
        return out
    }

    private fun coerce(spec: QueryParamSpec, raw: Any): Any = when (spec.type) {
        ParamType.STRING -> (raw as? String)?.takeIf { it.length <= MAX_STRING && it.none { c -> c == '\u0000' } } ?: throw bad("invalid parameter ${spec.name}")
        ParamType.INTEGER -> when (raw) {
            is Int -> raw.toLong(); is Long -> raw
            is java.math.BigInteger -> runCatching { raw.longValueExact() }.getOrNull() ?: throw bad("invalid parameter ${spec.name}")
            else -> throw bad("invalid parameter ${spec.name}")
        }
        ParamType.NUMBER -> when (raw) {
            is Int -> java.math.BigDecimal(raw); is Long -> java.math.BigDecimal(raw); is java.math.BigInteger -> java.math.BigDecimal(raw)
            is Double -> raw.takeIf { it.isFinite() }?.let { java.math.BigDecimal.valueOf(it) } ?: throw bad("invalid parameter ${spec.name}")
            else -> throw bad("invalid parameter ${spec.name}")
        }
        ParamType.BOOLEAN -> raw as? Boolean ?: throw bad("invalid parameter ${spec.name}")
        ParamType.TIMESTAMP -> (raw as? String)?.let { runCatching { java.time.OffsetDateTime.parse(it) }.getOrNull() } ?: throw bad("invalid parameter ${spec.name}")
        ParamType.DATE -> (raw as? String)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: throw bad("invalid parameter ${spec.name}")
    }

    private fun bad(msg: String) = ConnectorFailure(FailureCodes.INVALID_PARAMS, msg)
}
