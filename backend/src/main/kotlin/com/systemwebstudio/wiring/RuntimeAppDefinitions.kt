package com.systemwebstudio.wiring

import com.systemwebstudio.app.definition.AppDataBindingResolver
import com.systemwebstudio.app.definition.AppDefinitionCodec
import com.systemwebstudio.data.mapping.AppScope
import com.systemwebstudio.data.mapping.MappingCatalog
import com.systemwebstudio.data.mapping.MappingDefinition
import com.systemwebstudio.data.mapping.MappingJson
import com.systemwebstudio.data.mapping.ViewModelDefinition
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.canonical.AppDefinitionSource
import com.systemwebstudio.version.SchemaRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/** One AppDefinition document and where it came from: [versionId] is the published `project_versions.id` for LIVE and null for the working draft. */
class LoadedDefinition(val document: JsonNode, val versionId: String?)

/** What the runtime needs from the AppDefinition store (an interface so adapters can be tested without a database). Both calls are tenant-scoped; `null` = not found. */
interface DefinitionLoader {
    fun load(tenantId: UUID, projectId: UUID, mode: ExecutionMode): LoadedDefinition?
    fun loadVersion(tenantId: UUID, projectId: UUID, versionId: String): LoadedDefinition?
}

/**
 * C0 · reads the AppDefinition of an application for the runtime. The AppDefinition V2 keys live in the Page Schema of the project:
 * TEST = the working draft (`page_schemas`), LIVE = the schema snapshot of the version of the ACTIVE release (`sites.current_deployment_id`, D-C0-33).
 * Every method first proves that the project belongs to the tenant (tenant of its workspace) and answers `null` otherwise — another tenant's
 * application is indistinguishable from a missing one. Stateless; no cache (a published version is immutable, the draft must be fresh).
 */
@Component
class RuntimeAppDefinitions(private val jdbc: JdbcTemplate, private val schemas: SchemaRepository) : DefinitionLoader {

    override fun load(tenantId: UUID, projectId: UUID, mode: ExecutionMode): LoadedDefinition? {
        if (!inTenant(tenantId, projectId)) return null
        return when (mode) {
            ExecutionMode.TEST -> schemas.currentSchema(projectId)?.let { LoadedDefinition(it, null) }
            ExecutionMode.LIVE -> {
                val versionId = publishedVersionId(projectId) ?: return null
                schemas.version(projectId, versionId)?.schemaSnapshot?.let { LoadedDefinition(it, versionId.toString()) }
            }
        }
    }

    /** A specific version of the project (the `appVersionId` of a C3 [AppScope]); `null` when it does not exist, is not this project's, or the tenant does not match. */
    override fun loadVersion(tenantId: UUID, projectId: UUID, versionId: String): LoadedDefinition? {
        val id = runCatching { UUID.fromString(versionId) }.getOrNull() ?: return null
        if (!inTenant(tenantId, projectId)) return null
        return schemas.version(projectId, id)?.schemaSnapshot?.let { LoadedDefinition(it, id.toString()) }
    }

    private fun inTenant(tenantId: UUID, projectId: UUID): Boolean = try {
        jdbc.queryForList(
            "SELECT 1 FROM projects p JOIN workspaces w ON w.id = p.workspace_id WHERE p.id = ? AND w.tenant_id = ? AND p.active",
            projectId, tenantId
        ).isNotEmpty()
    } catch (e: Exception) {
        false   // fail closed
    }

    /**
     * D-C0-33 · the published version is the one of the ACTIVE release: `sites.current_deployment_id` (the pointer that only the fenced release scope moves), with
     * the same status filter the sites gateway serves with (`DEPLOYING` / `RUNNING`, SiteService.live). Never "the latest RUNNING deployment": that answered after an
     * unpublish and could name another release than the one visitors were getting. No pointer (never published, unpublished, archived) = nothing is published.
     */
    private fun publishedVersionId(projectId: UUID): UUID? = jdbc.queryForList(
        """SELECT d.version_id FROM sites s JOIN deployments d ON d.id = s.current_deployment_id AND d.project_id = s.project_id
           WHERE s.project_id = ? AND d.status IN ('DEPLOYING', 'RUNNING')""",
        UUID::class.java, projectId
    ).firstOrNull()
}

/** C4's view of the AppDefinition (the JSON document only). */
class RuntimeAppDefinitionSource(private val definitions: DefinitionLoader) : AppDefinitionSource {
    override fun load(tenantId: UUID, appId: UUID, mode: ExecutionMode): JsonNode? = definitions.load(tenantId, appId, mode)?.document
}

/**
 * C3's view: `mappings` / `viewModels` of the AppDefinition version named by the scope (no version = the working draft).
 *
 * The document stores the LOCAL query id in `queryRef` (`orders-list`), while C3's gateway compares `mapping.queryRef` with the id of the approved query in its
 * catalog, which is the query's `operationKey` (`orders.list`) - `docs/contracts/v2/data-runtime.md` ("the resolver must translate"). It is translated here, on a COPY
 * of the node (the stored document is never touched), so the gateway's "this mapping belongs to this query" check keeps its meaning: a mapping of another local query
 * still names another operation and is refused. A `queryRef` that names no local query is left as it is and therefore matches nothing.
 */
class RuntimeMappingCatalog(private val definitions: DefinitionLoader) : MappingCatalog {
    override fun findMapping(scope: AppScope, ref: String): MappingDefinition? = find(scope, "mappings", ref)?.let { MappingJson.mapping(it) }

    override fun findViewModel(scope: AppScope, ref: String): ViewModelDefinition? = find(scope, "viewModels", ref)?.let { MappingJson.viewModel(it) }

    private fun find(scope: AppScope, key: String, ref: String): JsonNode? {
        val projectId = scope.projectId ?: return null
        val versionId = scope.appVersionId
        val loaded = if (versionId == null) definitions.load(scope.tenantId, projectId, ExecutionMode.TEST) else definitions.loadVersion(scope.tenantId, projectId, versionId)
        val document = loaded?.document ?: return null
        val array = document.get(key) ?: return null
        if (!array.isArray) return null
        for (i in 0 until array.size()) {
            val item = array.get(i) ?: continue
            val id = item.get("id")
            if (id != null && id.isString && id.asString() == ref) return withOperationKey(document, item)
        }
        return null
    }

    private fun withOperationKey(document: JsonNode, item: JsonNode): JsonNode {
        val queryRef = item.get("queryRef")?.takeIf { it.isString }?.asString() ?: return item
        val queries = document.get("queries")?.takeIf { it.isArray } ?: return item
        for (i in 0 until queries.size()) {
            val q = queries.get(i) ?: continue
            if (q.get("id")?.takeIf { it.isString }?.asString() != queryRef) continue
            val operationKey = q.get("operationKey")?.takeIf { it.isString }?.asString() ?: return item
            val copy: JsonNode = item.deepCopy()
            (copy as? ObjectNode)?.put("queryRef", operationKey)
            return copy
        }
        return item
    }
}

/**
 * Local data source id -> registered source id for an application whose AppDefinition names no `sourceRef`. There is no persistent store for these
 * bindings yet (D-C0-20): the default answers "nothing bound", which the resolver reports as `DATA_SOURCE_UNBOUND`. Never persisted by the runtime.
 */
fun interface DataSourceSlotBindings {
    fun bindings(tenantId: UUID, projectId: UUID, mode: ExecutionMode): Map<String, UUID>
}

object NoSlotBindings : DataSourceSlotBindings {
    override fun bindings(tenantId: UUID, projectId: UUID, mode: ExecutionMode): Map<String, UUID> = emptyMap()
}

/**
 * Builds the C2 resolver for one loaded document. Throws C2's `AppDefinitionFormatException` for a document that is not a valid AppDefinition.
 * [slots] answers "nothing bound" unless a [DataSourceSlotBindings] bean exists (D-C0-20).
 */
class RuntimeResolvers(private val codec: AppDefinitionCodec, private val slots: DataSourceSlotBindings = NoSlotBindings) {
    fun forDocument(tenantId: UUID, projectId: UUID, mode: ExecutionMode, document: JsonNode): AppDataBindingResolver =
        AppDataBindingResolver(codec.fromJson(document), slots.bindings(tenantId, projectId, mode))
}
