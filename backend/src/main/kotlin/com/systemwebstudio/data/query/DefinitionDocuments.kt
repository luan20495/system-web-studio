package com.systemwebstudio.data.query

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.util.UUID

/**
 * Management API contract §3.5: the JSON form of an approved query / mutation definition as a client writes it and reads it back — and the only way a
 * client-supplied document becomes a [QueryDefinition] / [MutationDefinition]. Parsing is strict (unknown key, wrong type, over-size = `INVALID_QUERY`, never an
 * echo of the offending value), and the result is always built through the Kotlin definition types, whose `init` checks are the same rules the gateway applies on
 * every load: a document the gateway would refuse can never be stored.
 *
 * The identity of a definition (tenant, data source, id, version) is never part of the document: the caller supplies tenant and data source from the server-side
 * context, the id from the route, the version from the store.
 */
object DefinitionDocuments {
    const val KIND_SQL = "SQL"
    const val KIND_REST = "REST"
    private const val MAX_PARAMS = 40
    private const val MAX_TEXT = 20_000

    private val SQL_KEYS = setOf("sql", "params", "maxRows", "cacheTtlSeconds")
    private val REST_KEYS = setOf("pathTemplate", "params", "queryParams", "rowsPointer", "maxRows", "limitParam", "offsetParam", "discoverable", "cacheTtlSeconds")
    private val MUTATION_KEYS = setOf("target", "params", "invalidates", "entity")
    private val PARAM_KEYS = setOf("name", "type", "required", "default")

    fun kindOf(def: QueryDefinition): String = when (def) { is SqlQueryDefinition -> KIND_SQL; is RestQueryDefinition -> KIND_REST }

    // ------------------------------------------------------------------------------------------------ parse

    fun parseQuery(kind: String, id: String, tenantId: UUID, dataSourceId: UUID, version: Long, doc: JsonNode): QueryDefinition = guarded {
        when (kind) {
            KIND_SQL -> {
                strict(doc, SQL_KEYS)
                SqlQueryDefinition(id, tenantId, dataSourceId, text(doc, "sql", MAX_TEXT), params(doc), int(doc, "maxRows") ?: QueryLimits.DEFAULT_ROWS, int(doc, "cacheTtlSeconds") ?: 0, version)
            }
            KIND_REST -> {
                strict(doc, REST_KEYS)
                RestQueryDefinition(
                    id, tenantId, dataSourceId, text(doc, "pathTemplate", 500), params(doc), stringMap(doc, "queryParams"), optText(doc, "rowsPointer", 200) ?: "",
                    int(doc, "maxRows") ?: QueryLimits.DEFAULT_ROWS, optText(doc, "limitParam", 64), optText(doc, "offsetParam", 64), bool(doc, "discoverable") ?: false,
                    int(doc, "cacheTtlSeconds") ?: 0, version
                )
            }
            else -> throw bad("unknown query kind")
        }
    }

    fun parseMutation(kind: MutationKind, id: String, tenantId: UUID, dataSourceId: UUID, version: Long, doc: JsonNode): MutationDefinition = guarded {
        strict(doc, MUTATION_KEYS)
        val invalidates = doc.get("invalidates")?.takeUnless { it.isNull }?.let { n ->
            if (!n.isArray) throw bad("invalidates must be a list")
            DataJson.elements(n).map { e -> if (!DataJson.isText(e)) throw bad("invalidates must be a list of ids"); DataJson.text(e) }
        } ?: emptyList()
        MutationDefinition(id, tenantId, dataSourceId, kind, text(doc, "target", 200), params(doc), invalidates, optText(doc, "entity", 100), version)
    }

    // ------------------------------------------------------------------------------------------------ project

    /** the full, client-facing document of a query definition (stored forms only: no tenant, no data source, no credential, no host) */
    fun queryDocument(def: QueryDefinition): Map<String, Any?> {
        val d = LinkedHashMap<String, Any?>()
        when (def) {
            is SqlQueryDefinition -> d["sql"] = def.sql
            is RestQueryDefinition -> {
                d["pathTemplate"] = def.pathTemplate; d["queryParams"] = def.queryParams; d["rowsPointer"] = def.rowsPointer
                d["limitParam"] = def.limitParam; d["offsetParam"] = def.offsetParam; d["discoverable"] = def.discoverable
            }
        }
        d["params"] = paramDocuments(def.params); d["maxRows"] = def.maxRows; d["cacheTtlSeconds"] = def.cacheTtlSeconds
        return d
    }

    fun mutationDocument(def: MutationDefinition): Map<String, Any?> =
        linkedMapOf("target" to def.target, "params" to paramDocuments(def.params), "invalidates" to def.invalidates, "entity" to def.entity)

    private fun paramDocuments(params: List<QueryParamSpec>): List<Map<String, Any?>> = params.map { p ->
        val m = LinkedHashMap<String, Any?>()
        m["name"] = p.name; m["type"] = p.type.name; m["required"] = p.required
        p.default?.let { m["default"] = DataJson.toJava(it) }
        m
    }

    /** what an audit row records instead of the definition itself: a hash of its canonical document (same document = same hash) */
    fun fingerprint(def: QueryDefinition): String = hash(kindOf(def), queryDocument(def))
    fun fingerprint(def: MutationDefinition): String = hash(def.kind.name, mutationDocument(def))

    private fun hash(kind: String, doc: Map<String, Any?>): String =
        MessageDigest.getInstance("SHA-256").digest((kind + "|" + DataJson.mapper.writeValueAsString(doc)).toByteArray()).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------------------------------------ helpers

    /** the definition types refuse an invalid value with `require`; that is the same refusal the gateway applies on load, reported here as INVALID_QUERY without the value */
    private fun <T> guarded(block: () -> T): T = try { block() } catch (e: IllegalArgumentException) { throw bad("the definition is not valid") }

    private fun strict(doc: JsonNode, allowed: Set<String>) {
        if (!doc.isObject) throw bad("definition must be an object")
        if (DataJson.keys(doc).any { it !in allowed }) throw bad("definition has a field that is not accepted")
    }

    private fun params(doc: JsonNode): List<QueryParamSpec> {
        val n = doc.get("params")?.takeUnless { it.isNull } ?: return emptyList()
        if (!n.isArray || n.size() > MAX_PARAMS) throw bad("params must be a short list")
        return DataJson.elements(n).map { p ->
            strict(p, PARAM_KEYS)
            val type = try { ParamType.valueOf(text(p, "type", 20)) } catch (e: IllegalArgumentException) { throw bad("unknown parameter type") }
            val default = p.get("default")?.takeUnless { it.isNull }?.also { if (DataJson.isContainer(it)) throw bad("a default must be a plain value") }
            QueryParamSpec(text(p, "name", 40), type, bool(p, "required") ?: true, default)
        }
    }

    private fun stringMap(doc: JsonNode, key: String): Map<String, String> {
        val n = doc.get(key)?.takeUnless { it.isNull } ?: return emptyMap()
        if (!n.isObject || n.size() > MAX_PARAMS) throw bad("$key must be a short object")
        return DataJson.keys(n).associateWith { k -> n.get(k).let { v -> if (!DataJson.isText(v)) throw bad("$key values must be text"); DataJson.text(v).also { t -> if (t.length > 100) throw bad("$key is too long") } } }
    }

    private fun text(n: JsonNode, key: String, max: Int): String {
        val v = n.get(key) ?: throw bad("$key is required")
        if (!DataJson.isText(v)) throw bad("$key must be text")
        return DataJson.text(v).also { if (it.length > max) throw bad("$key is too long") }
    }
    private fun optText(n: JsonNode, key: String, max: Int): String? = n.get(key)?.takeUnless { it.isNull }?.let { text(n, key, max) }
    private fun int(n: JsonNode, key: String): Int? = n.get(key)?.takeUnless { it.isNull }?.let { if (!it.isIntegralNumber || !it.canConvertToInt()) throw bad("$key must be a whole number"); it.asInt() }
    private fun bool(n: JsonNode, key: String): Boolean? = n.get(key)?.takeUnless { it.isNull }?.let { if (!it.isBoolean) throw bad("$key must be true or false"); it.asBoolean() }
    private fun bad(msg: String) = ConnectorFailure(FailureCodes.INVALID_QUERY, msg)
}
