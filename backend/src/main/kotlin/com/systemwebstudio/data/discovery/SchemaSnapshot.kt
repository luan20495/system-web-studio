package com.systemwebstudio.data.discovery

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * One immutable discovery result of one data source (table `source_schemas`, migration request in BOARD.md). [version] grows by one per
 * refresh; [discoveredAt] says when; [fingerprint] is a hash of the *structure* only (names, types, keys, relations) so two refreshes can be
 * compared without comparing samples; [dataSourceVersion] ties the snapshot to the revision of the data source it was read with.
 * Samples inside [schema], when [includesSamples] is true, are masked.
 */
data class SchemaSnapshot(
    val id: UUID, val tenantId: UUID, val dataSourceId: UUID, val version: Int, val discoveredAt: Instant,
    val fingerprint: String, val dataSourceVersion: Long, val requestedBy: UUID?, val includesSamples: Boolean, val schema: DiscoveredSchema
)

/** Port: persistence of snapshots. Tenant-scoped; `save` of an existing (tenant, data source, version) must fail with a CONFLICT [com.systemwebstudio.data.datasource.ConnectorFailure]. */
interface SourceSchemaStore {
    fun latest(tenantId: UUID, dataSourceId: UUID): SchemaSnapshot?
    fun save(snapshot: SchemaSnapshot)
    /** newest first */
    fun history(tenantId: UUID, dataSourceId: UUID, limit: Int): List<SchemaSnapshot>
}

object SchemaFingerprint {
    fun of(schema: DiscoveredSchema): String {
        val text = buildString {
            for (e in schema.entities.sortedWith(compareBy({ it.schema ?: "" }, { it.name }))) {
                append("E|").append(e.schema ?: "").append('|').append(e.name).append('|').append(e.kind).append('\n')
                for (f in e.fields.sortedBy { it.name }) append(" F|").append(f.name).append('|').append(f.type).append('|').append(f.nullable).append('|').append(f.primaryKey).append('\n')
                for (r in e.relations.sortedBy { it.name }) append(" R|").append(r.name).append('|').append(r.fromFields.joinToString(",")).append("->").append(r.toSchema ?: "").append('.').append(r.toEntity).append('(').append(r.toFields.joinToString(",")).append(")\n")
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class EntityDiff(val addedFields: List<String>, val removedFields: List<String>, val changedFields: List<String>)

/** What changed between two snapshots, for Studio ("the schema changed since you built this binding"). Names only; no values. */
data class SchemaDiff(val addedEntities: List<String>, val removedEntities: List<String>, val changedEntities: Map<String, EntityDiff>) {
    val isEmpty get() = addedEntities.isEmpty() && removedEntities.isEmpty() && changedEntities.isEmpty()
}

object SchemaDiffer {
    private fun key(e: DiscoveredEntity) = if (e.schema == null) e.name else "${e.schema}.${e.name}"

    fun diff(before: DiscoveredSchema, after: DiscoveredSchema): SchemaDiff {
        val b = before.entities.associateBy(::key); val a = after.entities.associateBy(::key)
        val changed = LinkedHashMap<String, EntityDiff>()
        for ((k, ae) in a) {
            val be = b[k] ?: continue
            val bf = be.fields.associateBy { it.name }; val af = ae.fields.associateBy { it.name }
            val d = EntityDiff(
                addedFields = (af.keys - bf.keys).sorted(), removedFields = (bf.keys - af.keys).sorted(),
                changedFields = af.keys.intersect(bf.keys).filter { n -> af[n]!!.type != bf[n]!!.type || af[n]!!.nullable != bf[n]!!.nullable || af[n]!!.primaryKey != bf[n]!!.primaryKey }.sorted()
            )
            if (d.addedFields.isNotEmpty() || d.removedFields.isNotEmpty() || d.changedFields.isNotEmpty()) changed[k] = d
        }
        return SchemaDiff((a.keys - b.keys).sorted(), (b.keys - a.keys).sorted(), changed)
    }
}

/** A field of the AI-facing view: [sensitive] columns carry no sample value at all. */
data class AiField(val name: String, val type: NormalizedType, val nullable: Boolean, val primaryKey: Boolean, val sensitive: Boolean)
data class AiEntity(
    val name: String, val schema: String?, val kind: EntityKind, val fields: List<AiField>, val primaryKey: List<String>,
    val relations: List<DiscoveredRelation>, val sample: List<Map<String, tools.jackson.databind.JsonNode>>
)
data class AiSchemaView(val dataSourceId: UUID, val version: Int, val discoveredAt: Instant, val entities: List<AiEntity>)

/**
 * The only form of a discovered schema that may be placed in an AI prompt: structure, plus samples that were masked again here and from
 * which every sensitive column is removed outright (a masked first letter of a name is still a hint). No metadata, no warnings, no
 * configuration, no host. AI has no data model of its own (contract app-definition-v2): this is a read-only projection of the platform's.
 */
object AiSafeSchema {
    fun of(snapshot: SchemaSnapshot): AiSchemaView = AiSchemaView(snapshot.dataSourceId, snapshot.version, snapshot.discoveredAt, snapshot.schema.entities.map { e ->
        val sensitive = e.fields.filter { SampleMasker.isSensitiveField(it.name) }.map { it.name }.toSet()
        AiEntity(e.name, e.schema, e.kind,
            e.fields.map { AiField(it.name, it.type, it.nullable, it.primaryKey, it.name in sensitive) },
            e.primaryKey, e.relations,
            SampleMasker.maskRows(e.sample).map { row -> row.filterKeys { it !in sensitive } })
    })
}
