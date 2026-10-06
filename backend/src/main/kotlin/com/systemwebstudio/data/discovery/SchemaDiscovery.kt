package com.systemwebstudio.data.discovery

import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.ResolvedCredential
import tools.jackson.databind.JsonNode

/** Source-independent field types; every connector maps its own types onto these. */
enum class NormalizedType { STRING, INTEGER, NUMBER, BOOLEAN, TIMESTAMP, DATE, TIME, BINARY, JSON, OTHER }

enum class EntityKind { TABLE, VIEW, ENDPOINT, FILE }

data class DiscoveredField(
    val name: String, val type: NormalizedType, val nullable: Boolean, val sourceType: String? = null,
    val primaryKey: Boolean = false
)

/** A foreign key (SQL) or declared reference: [fromFields] of this entity point at [toFields] of [toEntity]. */
data class DiscoveredRelation(val name: String, val fromFields: List<String>, val toEntity: String, val toSchema: String?, val toFields: List<String>)

/**
 * A table/view (SQL), a declared endpoint's response shape (REST) or a file (CSV).
 *
 * [sample] holds **masked** rows only (see [SampleMasker]): connectors mask at the point they read, so raw values never exist in a
 * discovery result, and the discovery service masks once more before anything is stored or shown to the AI. [metadata] is fixed,
 * non-sensitive text (kind of source, path template of a declared endpoint, ...).
 */
data class DiscoveredEntity(
    val name: String, val schema: String?, val kind: EntityKind, val fields: List<DiscoveredField>,
    val primaryKey: List<String> = emptyList(),
    val relations: List<DiscoveredRelation> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val sample: List<Map<String, JsonNode>> = emptyList()
)

/**
 * Normalised discovery result. It carries structure (and masked samples when asked for): no credential, no connection string, no host.
 * [warnings] are fixed-text notes (for example "table list truncated"); [truncated] says a size cap cut the listing.
 */
data class DiscoveredSchema(val entities: List<DiscoveredEntity>, val truncated: Boolean = false, val warnings: List<String> = emptyList())

/** [sampleRows] = how many rows per entity to read for the masked sample; 0 (the default) reads no data at all. */
data class DiscoveryOptions(val sampleRows: Int = 0) {
    init { require(sampleRows in 0..MAX_SAMPLE_ROWS) { "invalid sample size" } }
    companion object { const val MAX_SAMPLE_ROWS = 5; const val MAX_SAMPLED_ENTITIES = 50 }
}

/** Contract `data-connector.md`. Implementations must be read-only, bounded (time/size) and must never return secrets or unmasked samples. */
interface SchemaDiscovery {
    fun discover(ds: DataSourceRef, cred: ResolvedCredential): DiscoveredSchema
    fun discover(ds: DataSourceRef, cred: ResolvedCredential, options: DiscoveryOptions): DiscoveredSchema = discover(ds, cred)
}
