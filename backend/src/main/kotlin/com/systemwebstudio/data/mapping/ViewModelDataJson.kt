package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.query.DataJson
import tools.jackson.databind.JsonNode

/** The JSON form of [ViewModelData]: the HTTP response body of a query *and* the cached value, so a cache hit and a miss are byte-for-byte the same shape. */
object ViewModelDataJson {
    fun toNode(d: ViewModelData): JsonNode = DataJson.toNode(linkedMapOf(
        "viewModelId" to d.viewModelId,
        "cardinality" to d.cardinality.name,
        "fields" to d.fields.map { linkedMapOf("name" to it.name, "type" to it.type?.name, "nullable" to it.nullable) },
        "rows" to d.rows,
        "truncated" to d.truncated,
        "warnings" to d.warnings,
        "skippedRows" to d.skippedRows
    ))

    fun encode(d: ViewModelData): String = DataJson.mapper.writeValueAsString(toNode(d))

    /** null when the text is not a well-formed encoding (a corrupted or foreign cache entry is simply a miss) */
    fun decode(text: String): ViewModelData? = try {
        val n = DataJson.mapper.readTree(text)
        val rows = n.get("rows"); val fields = n.get("fields")
        if (rows == null || !rows.isArray || fields == null || !fields.isArray) null
        else ViewModelData(
            viewModelId = n.get("viewModelId")?.takeIf { DataJson.isText(it) }?.let { DataJson.text(it) },
            cardinality = Cardinality.valueOf(DataJson.text(n.get("cardinality"))),
            fields = DataJson.elements(fields).map { f -> ViewModelFieldInfo(DataJson.text(f.get("name")), f.get("type")?.takeIf { DataJson.isText(it) }?.let { FieldType.valueOf(DataJson.text(it)) }, f.get("nullable").asBoolean()) },
            rows = DataJson.elements(rows).map { r -> DataJson.keys(r).associateWith { k -> r.get(k) } },
            truncated = n.get("truncated").asBoolean(),
            warnings = n.get("warnings")?.let { w -> DataJson.elements(w).map { DataJson.text(it) } } ?: emptyList(),
            skippedRows = n.get("skippedRows")?.asInt() ?: 0
        )
    } catch (e: Exception) { null }
}
