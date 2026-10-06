package com.systemwebstudio.data.query

import java.util.UUID

/** Same four kinds C4's `MutationKind` uses (DECISIONS D-009 of C4), so the Action adapter maps them one to one. */
enum class MutationKind { CREATE, UPDATE, DELETE, SUBMIT }

/**
 * An approved write — the write-side twin of a query definition (BLOCKERS B-C3-03, C4 B-006). It names *which declared operation of the
 * data source* runs ([target], interpreted only by the connector: a path template, a statement id, a model/method), the typed parameters it
 * accepts, and which cached queries a success makes stale. It never carries SQL, a URL or a credential, and a caller never supplies one.
 *
 * [entity] is only used to label `RecordChanged` events. [invalidates] empty = the whole data source's cache is dropped on success.
 */
data class MutationDefinition(
    val id: String, val tenantId: UUID, val dataSourceId: UUID, val kind: MutationKind, val target: String,
    val params: List<QueryParamSpec> = emptyList(), val invalidates: List<String> = emptyList(),
    val entity: String? = null, val version: Long = 1
) {
    init {
        require(SqlQueryDefinition.ID.matches(id)) { "invalid mutation id" }
        require(target.isNotBlank() && target.length <= 200) { "invalid mutation target" }
        require(params.map { it.name }.toSet().size == params.size && params.size <= 40) { "invalid parameters" }
        require(invalidates.size <= 50 && invalidates.all { SqlQueryDefinition.ID.matches(it) }) { "invalid invalidation list" }
        require(entity == null || ENTITY.matches(entity)) { "invalid entity" }
    }
    private companion object { val ENTITY = Regex("^[A-Za-z0-9_.-]{1,100}$") }
}

/** Port: approved mutations. Tenant- and datasource-scoped like [QueryCatalog]. Needs a migration (BOARD.md). */
interface MutationCatalog {
    fun find(tenantId: UUID, dataSourceId: UUID, mutationId: String): MutationDefinition?
    /** the approved mutations of one data source (tenant-scoped like [find]); used to build the AI-facing catalog */
    fun list(tenantId: UUID, dataSourceId: UUID): List<MutationDefinition>
}
