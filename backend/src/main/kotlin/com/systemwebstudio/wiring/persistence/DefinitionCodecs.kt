package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryDefinition
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.RestQueryDefinition
import com.systemwebstudio.data.query.SqlQueryDefinition
import java.util.UUID

/**
 * Hand-written JSON codecs of the approved query / mutation definitions stored in `data_queries.definition` / `data_mutations.definition`
 * (V28). Identity (tenant, data source, id) and `version` are columns, not part of the document, so a document can never claim another
 * tenant. Decoding is strict and fail-closed: an unknown kind, a missing field or a definition that its own type refuses (the `init` checks of
 * [SqlQueryDefinition], [RestQueryDefinition], [MutationDefinition]) decodes to `null`, which the catalogs report as "not found".
 */
internal object DefinitionCodecs {
    const val KIND_SQL = "SQL"
    const val KIND_REST = "REST"

    fun kindOf(def: QueryDefinition): String = when (def) { is SqlQueryDefinition -> KIND_SQL; is RestQueryDefinition -> KIND_REST }

    fun encodeQuery(def: QueryDefinition): String {
        val doc = LinkedHashMap<String, Any?>()
        doc["params"] = encodeParams(def.params)
        doc["maxRows"] = def.maxRows
        doc["cacheTtlSeconds"] = def.cacheTtlSeconds
        when (def) {
            is SqlQueryDefinition -> doc["sql"] = def.sql
            is RestQueryDefinition -> {
                doc["pathTemplate"] = def.pathTemplate
                doc["queryParams"] = def.queryParams
                doc["rowsPointer"] = def.rowsPointer
                doc["limitParam"] = def.limitParam
                doc["offsetParam"] = def.offsetParam
                doc["discoverable"] = def.discoverable
            }
        }
        return DataJson.mapper.writeValueAsString(doc)
    }

    /** @return null for an unknown kind or any document that does not decode and validate */
    @Suppress("UNCHECKED_CAST")
    fun decodeQuery(kind: String, id: String, tenantId: UUID, dataSourceId: UUID, version: Long, json: String): QueryDefinition? = try {
        val d = DataJson.mapper.readValue(json, Map::class.java) as Map<String, Any?>
        val params = decodeParams(d["params"])
        val maxRows = (d["maxRows"] as Number).toInt()
        val ttl = (d["cacheTtlSeconds"] as? Number)?.toInt() ?: 0
        when (kind) {
            KIND_SQL -> SqlQueryDefinition(id, tenantId, dataSourceId, d["sql"] as String, params, maxRows, ttl, version)
            KIND_REST -> RestQueryDefinition(
                id, tenantId, dataSourceId, d["pathTemplate"] as String, params,
                (d["queryParams"] as? Map<String, Any?>)?.mapValues { it.value as String } ?: emptyMap(),
                d["rowsPointer"] as? String ?: "", maxRows, d["limitParam"] as String?, d["offsetParam"] as String?,
                d["discoverable"] as? Boolean ?: false, ttl, version
            )
            else -> null
        }
    } catch (e: RuntimeException) { null }

    fun encodeMutation(def: MutationDefinition): String {
        val doc = LinkedHashMap<String, Any?>()
        doc["target"] = def.target
        doc["params"] = encodeParams(def.params)
        doc["invalidates"] = def.invalidates
        doc["entity"] = def.entity
        return DataJson.mapper.writeValueAsString(doc)
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeMutation(kind: String, id: String, tenantId: UUID, dataSourceId: UUID, version: Long, json: String): MutationDefinition? = try {
        val d = DataJson.mapper.readValue(json, Map::class.java) as Map<String, Any?>
        MutationDefinition(
            id, tenantId, dataSourceId, MutationKind.valueOf(kind), d["target"] as String, decodeParams(d["params"]),
            (d["invalidates"] as? List<*>)?.map { it as String } ?: emptyList(), d["entity"] as String?, version
        )
    } catch (e: RuntimeException) { null }

    private fun encodeParams(params: List<QueryParamSpec>): List<Map<String, Any?>> = params.map { p ->
        val m = LinkedHashMap<String, Any?>()
        m["name"] = p.name; m["type"] = p.type.name; m["required"] = p.required
        p.default?.let { m["default"] = DataJson.toJava(it) }
        m
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeParams(raw: Any?): List<QueryParamSpec> = (raw as? List<*> ?: emptyList<Any?>()).map { p ->
        val m = p as Map<String, Any?>
        QueryParamSpec(m["name"] as String, ParamType.valueOf(m["type"] as String), m["required"] as? Boolean ?: true, m["default"]?.let { DataJson.toNode(it) })
    }
}
