package com.systemwebstudio.app.definition

import java.util.UUID

/*
 * AppDataBindingResolver (docs/contracts/v2/data-runtime.md §3): the ONE place that turns the LOCAL ids of an AppDefinition into the runtime
 * identifiers the data platform understands. Both the UI query path (data bindings, view models) and C4's ActionDataPort adapter use it, so a
 * query is resolved the same way whoever asks.
 *
 *   binding / action / view model ──► queryRef (local id) ──► QueryDef ──► dataSourceRef (local id) ──► DataSourceDef.sourceRef (UUID of the
 *   registered data source) + QueryDef.operationKey (id of the approved query / mutation in the data platform catalog)
 *
 * The document only ever stores LOCAL ids (plus `sourceRef`, the slot's registration). The runtime id of a query, mapping, view model or
 * action definition is never written into the document; it is derived here, per call. Pure: no I/O, no Spring, no import of data-platform
 * classes (this package imports C3 SHAPES only through the contract; the adapter in `wiring.*` converts the result into `GatewayQuery` /
 * `GatewayMutation`, adds the tenant, the user and the idempotency key).
 */

/** Why a local reference could not be resolved. [code] is stable for adapters and tests; [message] names ids only, never data. */
class AppResolutionException(val code: String, message: String) : RuntimeException(message)

object ResolutionCodes {
    const val UNKNOWN_REFERENCE = "UNKNOWN_REFERENCE"
    /** the data source slot has no registered source (a template slot that nobody bound yet) */
    const val DATA_SOURCE_UNBOUND = "DATA_SOURCE_UNBOUND"
    const val NO_OPERATION = "NO_OPERATION"
    const val WRONG_MODE = "WRONG_MODE"
    const val NOT_A_DATA_ACTION = "NOT_A_DATA_ACTION"
    const val INVALID_SOURCE_ID = "INVALID_SOURCE_ID"
    const val NO_QUERY = "NO_QUERY"
}

/** A data source slot of the document with its registered source: [sourceId] is the id in the data platform (C3 `DataSource.id`). */
data class ResolvedDataSource(val localId: String, val type: String, val sourceId: UUID)

/** A declared query ready for the gateway: the source, the approved operation, the declared parameters and the row cap. */
data class ResolvedQuery(
    val queryId: String, val mode: QueryMode, val dataSource: ResolvedDataSource, val operationKey: String,
    val params: List<ParamDef>, val maxRows: Int?
)

/**
 * A mapping together with the id the DATA PLATFORM knows its query by. [definition] still carries the local `queryRef`; C3 compares a mapping's
 * query with its catalog id, so the adapter must give C3 [queryOperationKey] instead (data-runtime.md §3, "mismatches to fix").
 */
data class ResolvedMapping(val mappingId: String, val queryOperationKey: String, val definition: MappingDef)

/** A data binding resolved to its read: the query, the mapping that shapes the rows (from the binding or its view model) and the view model. */
data class ResolvedBinding(
    val bindingId: String, val sectionId: String, val prop: String, val query: ResolvedQuery,
    val mapping: ResolvedMapping?, val viewModel: ViewModelDef?
)

/** What a data-touching action needs from the platform: a query (REFRESH_QUERY reads, record actions write) or a connector operation (CALL_API). */
data class ResolvedActionData(
    val actionId: String, val type: ActionType, val query: ResolvedQuery?, val operation: ResolvedOperation?, val viewModel: ViewModelDef?
)

/** CALL_API: an approved operation of a registered data source, never a URL. */
data class ResolvedOperation(val dataSource: ResolvedDataSource, val operationKey: String)

/**
 * @param slotBindings registrations supplied by the runtime for slots whose `sourceRef` is null in the document (a project created from a
 * template binds its slots outside the document, so the shared template stays free of tenant data). A `sourceRef` in the document wins.
 */
class AppDataBindingResolver(private val def: AppDefinitionV2, private val slotBindings: Map<String, UUID> = emptyMap()) {
    private val dataSources = def.dataSources.associateBy { it.id }
    private val queries = def.queries.associateBy { it.id }
    private val mappings = def.mappings.associateBy { it.id }
    private val viewModels = def.viewModels.associateBy { it.id }
    private val actions = def.actions.associateBy { it.id }
    private val bindings = def.dataBindings.associateBy { it.id }

    fun dataSource(localId: String): ResolvedDataSource {
        val ds = dataSources[localId] ?: throw unknown("data source", localId)
        val raw = ds.sourceRef
        val id = when {
            raw != null -> runCatching { UUID.fromString(raw) }.getOrNull()
                ?: throw AppResolutionException(ResolutionCodes.INVALID_SOURCE_ID, "data source '$localId' has an invalid sourceRef")
            else -> slotBindings[localId]
                ?: throw AppResolutionException(ResolutionCodes.DATA_SOURCE_UNBOUND, "data source '$localId' is not bound to a registered source")
        }
        return ResolvedDataSource(ds.id, ds.type, id)
    }

    fun query(queryRef: String): ResolvedQuery {
        val q = queries[queryRef] ?: throw unknown("query", queryRef)
        val key = q.operationKey ?: throw AppResolutionException(ResolutionCodes.NO_OPERATION, "query '$queryRef' names no approved operation")
        return ResolvedQuery(q.id, q.mode, dataSource(q.dataSourceRef), key, q.params, q.maxRows)
    }

    /** the mapping with its query translated to the data platform's id */
    fun mapping(mappingRef: String): ResolvedMapping {
        val m = mappings[mappingRef] ?: throw unknown("mapping", mappingRef)
        val q = queries[m.queryRef] ?: throw unknown("query", m.queryRef)
        val key = q.operationKey ?: throw AppResolutionException(ResolutionCodes.NO_OPERATION, "query '${q.id}' names no approved operation")
        return ResolvedMapping(m.id, key, m)
    }

    /** a data binding reads through a READ query, directly or through its view model; the mapping comes from the binding or from the view model */
    fun binding(bindingId: String): ResolvedBinding {
        val b = bindings[bindingId] ?: throw unknown("data binding", bindingId)
        val vm = b.viewModelRef?.let { viewModels[it] ?: throw unknown("view model", it) }
        val queryRef = b.queryRef ?: vm?.queryRef ?: throw AppResolutionException(ResolutionCodes.NO_QUERY, "data binding '$bindingId' reaches no query")
        val query = query(queryRef)
        if (query.mode != QueryMode.READ) throw AppResolutionException(ResolutionCodes.WRONG_MODE, "data binding '$bindingId' needs a READ query; '$queryRef' is a WRITE query")
        val mappingRef = b.mappingRef ?: vm?.mappingRef
        return ResolvedBinding(b.id, b.sectionId, b.prop, query, mappingRef?.let { mapping(it) }, vm)
    }

    /** every binding of a section, in document order (what a renderer needs to fill one section) */
    fun bindingsOf(sectionId: String): List<ResolvedBinding> = def.dataBindings.filter { it.sectionId == sectionId }.map { binding(it.id) }

    /** the data an action needs: REFRESH_QUERY → a READ query, record actions → a WRITE query, CALL_API → an operation; other actions touch no data */
    fun action(actionId: String): ResolvedActionData {
        val a = actions[actionId] ?: throw unknown("action", actionId)
        val vm = a.viewModelRef?.let { viewModels[it] ?: throw unknown("view model", it) }
        return when (a.type) {
            ActionType.REFRESH_QUERY -> {
                val q = query(a.queryRef ?: throw AppResolutionException(ResolutionCodes.NO_QUERY, "action '$actionId' names no query"))
                if (q.mode != QueryMode.READ) throw AppResolutionException(ResolutionCodes.WRONG_MODE, "REFRESH_QUERY needs a READ query; '${q.queryId}' is a WRITE query")
                ResolvedActionData(a.id, a.type, q, null, vm)
            }
            ActionType.SUBMIT_FORM, ActionType.CREATE_RECORD, ActionType.UPDATE_RECORD, ActionType.DELETE_RECORD -> {
                val q = query(a.queryRef ?: throw AppResolutionException(ResolutionCodes.NO_QUERY, "action '$actionId' names no query"))
                if (q.mode != QueryMode.WRITE) throw AppResolutionException(ResolutionCodes.WRONG_MODE, "${a.type} needs a WRITE query; '${q.queryId}' is a READ query")
                ResolvedActionData(a.id, a.type, q, null, vm)
            }
            ActionType.CALL_API -> {
                val ds = dataSource(a.dataSourceRef ?: throw unknown("data source", "(none)"))
                val key = a.operationKey ?: throw AppResolutionException(ResolutionCodes.NO_OPERATION, "action '$actionId' names no approved operation")
                ResolvedActionData(a.id, a.type, null, ResolvedOperation(ds, key), vm)
            }
            ActionType.NAVIGATE, ActionType.NOTIFY, ActionType.START_WORKFLOW ->
                throw AppResolutionException(ResolutionCodes.NOT_A_DATA_ACTION, "${a.type} action '$actionId' does not touch data")
        }
    }

    private fun unknown(what: String, id: String) = AppResolutionException(ResolutionCodes.UNKNOWN_REFERENCE, "unknown $what '$id'")
}
