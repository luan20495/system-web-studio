package com.systemwebstudio.data.query

import java.time.Instant
import java.util.UUID

enum class DefinitionStatus { ACTIVE, DISABLED }

class StoredQueryDefinition(val definition: QueryDefinition, val status: DefinitionStatus, val createdAt: Instant, val updatedAt: Instant)
class StoredMutationDefinition(val definition: MutationDefinition, val status: DefinitionStatus, val createdAt: Instant, val updatedAt: Instant)

/**
 * Port: the management view of `data_queries` (V28). Unlike [QueryCatalog] (what the runtime may run: ACTIVE only), this sees every status, because an
 * operator manages disabled definitions too. Tenant- and data-source-scoped on every call; a stored document that no longer decodes is simply not listed.
 * Writes are only ever called inside a [com.systemwebstudio.data.datasource.DataTransactions] unit together with their audit row.
 */
interface QueryDefinitionStore {
    fun find(tenantId: UUID, dataSourceId: UUID, queryId: String): StoredQueryDefinition?
    fun list(tenantId: UUID, dataSourceId: UUID): List<StoredQueryDefinition>
    /** @throws com.systemwebstudio.data.datasource.ConnectorFailure(CONFLICT) when a definition with this id already exists for the data source */
    fun insert(def: QueryDefinition, status: DefinitionStatus)
    /** replaces the stored definition only while its stored version is [expectedVersion]; writes [def]'s version. @return false when it is gone or changed meanwhile */
    fun replace(def: QueryDefinition, status: DefinitionStatus, expectedVersion: Long): Boolean
    fun delete(tenantId: UUID, dataSourceId: UUID, queryId: String): Boolean
}

/** Port: the management view of `data_mutations` (V28), the write-side twin of [QueryDefinitionStore]. */
interface MutationDefinitionStore {
    fun find(tenantId: UUID, dataSourceId: UUID, mutationId: String): StoredMutationDefinition?
    fun list(tenantId: UUID, dataSourceId: UUID): List<StoredMutationDefinition>
    fun insert(def: MutationDefinition, status: DefinitionStatus)
    fun replace(def: MutationDefinition, status: DefinitionStatus, expectedVersion: Long): Boolean
    fun delete(tenantId: UUID, dataSourceId: UUID, mutationId: String): Boolean
    /** true while an idempotency record of this mutation is RESERVED or UNKNOWN: a write may be in flight or may have been applied, so the definition stays */
    fun hasUnfinishedWrites(tenantId: UUID, dataSourceId: UUID, mutationId: String): Boolean
}
