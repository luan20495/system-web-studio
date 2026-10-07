package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.discovery.DiscoveredEntity
import com.systemwebstudio.data.discovery.DiscoveredField
import com.systemwebstudio.data.discovery.DiscoveredRelation
import com.systemwebstudio.data.discovery.DiscoveredSchema
import com.systemwebstudio.data.discovery.EntityKind
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.query.DataJson
import tools.jackson.databind.JsonNode

/**
 * JSON form of a [DiscoveredSchema] for `source_schemas.snapshot` (V28). The schema only ever holds structure and already-masked samples
 * (see `SampleMasker`); nothing here adds or removes data. Decoding is strict: anything unexpected throws, and the store reports the snapshot as absent.
 */
internal object SchemaCodec {
    fun encode(schema: DiscoveredSchema): String {
        val doc = LinkedHashMap<String, Any?>()
        doc["truncated"] = schema.truncated
        doc["warnings"] = schema.warnings
        doc["entities"] = schema.entities.map { e ->
            mapOf(
                "name" to e.name, "schema" to e.schema, "kind" to e.kind.name,
                "fields" to e.fields.map { f -> mapOf("name" to f.name, "type" to f.type.name, "nullable" to f.nullable, "sourceType" to f.sourceType, "primaryKey" to f.primaryKey) },
                "primaryKey" to e.primaryKey,
                "relations" to e.relations.map { r -> mapOf("name" to r.name, "fromFields" to r.fromFields, "toEntity" to r.toEntity, "toSchema" to r.toSchema, "toFields" to r.toFields) },
                "metadata" to e.metadata,
                "sample" to e.sample.map { row -> row.mapValues { (_, v) -> DataJson.toJava(v) } }
            )
        }
        return DataJson.mapper.writeValueAsString(doc)
    }

    @Suppress("UNCHECKED_CAST")
    fun decode(json: String): DiscoveredSchema {
        val d = DataJson.mapper.readValue(json, Map::class.java) as Map<String, Any?>
        val entities = (d["entities"] as List<*>).map { raw ->
            val e = raw as Map<String, Any?>
            DiscoveredEntity(
                name = e["name"] as String, schema = e["schema"] as String?, kind = EntityKind.valueOf(e["kind"] as String),
                fields = (e["fields"] as List<*>).map { fr ->
                    val f = fr as Map<String, Any?>
                    DiscoveredField(f["name"] as String, NormalizedType.valueOf(f["type"] as String), f["nullable"] as Boolean, f["sourceType"] as String?, f["primaryKey"] as? Boolean ?: false)
                },
                primaryKey = strings(e["primaryKey"]),
                relations = (e["relations"] as? List<*> ?: emptyList<Any?>()).map { rr ->
                    val r = rr as Map<String, Any?>
                    DiscoveredRelation(r["name"] as String, strings(r["fromFields"]), r["toEntity"] as String, r["toSchema"] as String?, strings(r["toFields"]))
                },
                metadata = (e["metadata"] as? Map<String, Any?>)?.mapValues { it.value as String } ?: emptyMap(),
                sample = (e["sample"] as? List<*> ?: emptyList<Any?>()).map { row -> (row as Map<String, Any?>).mapValues<String, Any?, JsonNode> { (_, v) -> DataJson.toNode(v) } }
            )
        }
        return DiscoveredSchema(entities, d["truncated"] as? Boolean ?: false, strings(d["warnings"]))
    }

    private fun strings(raw: Any?): List<String> = (raw as? List<*> ?: emptyList<Any?>()).map { it as String }
}
