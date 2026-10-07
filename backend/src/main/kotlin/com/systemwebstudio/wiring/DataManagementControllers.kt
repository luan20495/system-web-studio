package com.systemwebstudio.wiring

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.common.RequestIdFilter
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.CredentialVault
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.DataSourceAdminService
import com.systemwebstudio.data.datasource.DataSourceRepository
import com.systemwebstudio.data.datasource.DataSourceScope
import com.systemwebstudio.data.datasource.DataTransactions
import com.systemwebstudio.data.datasource.NoTransactions
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.datasource.RateLimitGate
import com.systemwebstudio.data.discovery.DiscoveryService
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayGuard
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.gateway.GatewayProblems
import com.systemwebstudio.data.gateway.GatewayResponses
import com.systemwebstudio.data.gateway.ManagementRequests
import com.systemwebstudio.data.gateway.ManagementResponses
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.tenancy.TenantStatus
import com.systemwebstudio.data.query.DefinitionAdminService
import com.systemwebstudio.data.query.MutationDefinitionStore
import com.systemwebstudio.data.query.QueryDefinitionStore
import com.systemwebstudio.wiring.persistence.DataSourceBindingWriter
import com.systemwebstudio.wiring.persistence.JdbcMutationDefinitionStore
import com.systemwebstudio.wiring.persistence.JdbcQueryDefinitionStore
import com.systemwebstudio.wiring.persistence.JdbcCredentialActorLookup
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/*
 * B-C0-W-03 · Data Source Management API. Mounted only with `app.data-platform.enabled=true`, like every other data route.
 *
 * Contract (docs/parallel/c3/MANAGEMENT_API.md): workspace-scoped routes under `/api/v1/workspaces/{workspaceId}`.
 *  - The tenant is resolved from the workspace by `AccessService` (404 for a non-member, a missing workspace or another tenant's workspace — existence is
 *    never disclosed); the workspace comes from the path AFTER that proof; nothing is taken from the body.
 *  - Authorisation is C1's, unchanged: every call goes through `GatewayGuard` -> C1 `GatewayAuthorizer` (DATASOURCE_READ -> DATA_SOURCE_VIEW,
 *    DATASOURCE_MANAGE -> DATA_SOURCE_MANAGE), default deny. 403 `PERMISSION_DENIED` for a member without the permission.
 *  - A data source (or project) of another workspace/tenant is exactly as "not found" as one that does not exist (404 `NOT_FOUND`).
 *  - No response, error body, log line or audit payload contains a credential or the reference to one.
 */

/** the C3 context of a workspace-level call: tenant resolved by C1, actor = the authenticated user, no project */
internal object ManagementContexts {
    fun workspace(access: AccessContext, requestId: String?): GatewayContext =
        GatewayContext(tenant = TenantContext(access.tenantId, null, TenantStatus.ACTIVE, false), actorUserId = access.userId, requestId = requestId, workspaceId = access.workspaceId)
}

internal fun problem(e: ConnectorFailure): ResponseEntity<JsonNode> = ManagementErrors.of(e)      // the frozen envelope, see ManagementTransport.kt

/** runs a route body; a [ConnectorFailure] becomes its fixed-text problem answer, null = an empty body with [status] */
internal fun managementReply(status: Int = 200, block: () -> JsonNode?): ResponseEntity<JsonNode> = try {
    val body = block()
    if (body == null) ResponseEntity.status(status).build<JsonNode>() else ResponseEntity.status(status).body(body)
} catch (e: ConnectorFailure) { problem(e) }

/**
 * Application bindings: slot -> registered data source, per project and mode (V28 `data_source_bindings`).
 * TEST and LIVE are separate rows and never stand in for each other. Every change is one [DataTransactions] unit with its audit row, and the source row is
 * locked while the binding is written, so a concurrent delete of the source either sees the binding or waits for it.
 */
class DataBindingService(
    private val repository: DataSourceRepository,
    private val writer: DataSourceBindingWriter,
    private val guard: GatewayGuard,
    private val audit: DataAuditSink,
    private val tx: DataTransactions = NoTransactions
) {
    class Binding(val mode: ExecutionMode, val slotId: String, val dataSourceId: UUID, val updatedAt: Instant)

    fun list(ctx: GatewayContext): List<Binding> {
        guard.require(ctx, GatewayOperation.DATASOURCE_READ, null)
        val project = ctx.projectId ?: throw notFound()
        return writer.list(ctx.tenantId, project).filter { guard.allowed(ctx, GatewayOperation.DATASOURCE_READ, it.dataSourceId) }
            .map { Binding(ExecutionMode.valueOf(it.mode), it.slotId, it.dataSourceId, it.updatedAt) }
    }

    /** Binds (or re-points) one slot. The data source must belong to the caller's workspace AND tenant, the project to the same workspace; otherwise 404. */
    fun bind(ctx: GatewayContext, mode: ExecutionMode, slotId: String, dataSourceId: UUID): Binding {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, dataSourceId)
        val workspace = ctx.workspaceId ?: throw notFound()
        val project = ctx.projectId ?: throw notFound()
        return tx.run {
            val ds = repository.findInWorkspaceForUpdate(ctx.tenantId, workspace, dataSourceId) ?: throw notFound()
            if (!writer.bind(ctx.tenantId, workspace, project, mode, slotId, ds.id, ctx.actorUserId)) throw notFound()
            audit.record(DataAuditActions.BINDING_CHANGED, ctx.tenantId, ds.id, mapOf("mode" to mode.name, "slot" to slotId, "change" to "bind", "project" to project.toString()) + fields(ctx))
            Binding(mode, slotId, ds.id, Instant.now())
        }
    }

    fun unbind(ctx: GatewayContext, mode: ExecutionMode, slotId: String) {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, null)
        val project = ctx.projectId ?: throw notFound()
        tx.run {
            val current = writer.find(ctx.tenantId, project, mode, slotId) ?: throw notFound()
            if (!writer.unbind(ctx.tenantId, project, mode, slotId)) throw notFound()
            audit.record(DataAuditActions.BINDING_CHANGED, ctx.tenantId, current, mapOf("mode" to mode.name, "slot" to slotId, "change" to "unbind", "project" to project.toString()) + fields(ctx))
        }
    }

    private fun fields(ctx: GatewayContext) = mapOf("actor" to ctx.actorUserId?.toString(), "workspace" to ctx.workspaceId?.toString(), "request" to ctx.requestId)
    private fun notFound() = ConnectorFailure(FailureCodes.NOT_FOUND, "not found")
}

@Configuration
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class DataManagementConfiguration {
    /** workspace-scoped (B-C0-W-03 / D-C0-22): tenant AND workspace, default deny; every change is one unit of work with its audit row */
    @Bean
    fun c3DataSourceAdminService(
        repository: DataSourceRepository, vault: CredentialVault, registry: DataConnectorRegistry, guard: GatewayGuard, limits: RateLimitGate,
        audit: DataAuditSink, listener: DataChangeListener, jdbc: JdbcTemplate, tx: DataTransactions
    ) = DataSourceAdminService(repository, vault, registry, guard, limits, audit, listener, scope = DataSourceScope.WORKSPACE, credentialActors = JdbcCredentialActorLookup(jdbc), tx = tx)

    @Bean
    fun c3DataBindingService(repository: DataSourceRepository, writer: DataSourceBindingWriter, guard: GatewayGuard, audit: DataAuditSink, tx: DataTransactions) =
        DataBindingService(repository, writer, guard, audit, tx)

    @Bean fun c3QueryDefinitionStore(jdbc: JdbcTemplate): QueryDefinitionStore = JdbcQueryDefinitionStore(jdbc)
    @Bean fun c3MutationDefinitionStore(jdbc: JdbcTemplate): MutationDefinitionStore = JdbcMutationDefinitionStore(jdbc)

    @Bean
    fun c3DefinitionAdminService(
        repository: DataSourceRepository, registry: DataConnectorRegistry, guard: GatewayGuard, limits: RateLimitGate, audit: DataAuditSink,
        queries: QueryDefinitionStore, mutations: MutationDefinitionStore, listener: DataChangeListener, tx: DataTransactions
    ) = DefinitionAdminService(repository, registry, guard, limits, audit, queries, mutations, listener, DataSourceScope.WORKSPACE, tx)
}

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/data-sources")
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class DataSourceManagementController(
    private val access: AccessService,
    private val admin: DataSourceAdminService,
    private val gateway: DataGateway,
    private val discovery: DiscoveryService
) {
    private fun ctx(me: StudioUserDetails, workspaceId: UUID): GatewayContext =
        ManagementContexts.workspace(access.forWorkspace(me.userId, workspaceId), RequestIdFilter.current())      // 404 for a non-member / another tenant

    private fun reply(status: Int = 200, block: () -> JsonNode?): ResponseEntity<JsonNode> = managementReply(status, block)

    /** the connector types a data source can have: available and planned, fixed text */
    @GetMapping("/connectors")
    fun connectors(@PathVariable workspaceId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply { ManagementResponses.connectors(admin.connectorCatalog(c)) }
    }

    @GetMapping
    fun list(@PathVariable workspaceId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply { ManagementResponses.dataSources(admin.list(c)) }
    }

    @PostMapping
    fun create(@PathVariable workspaceId: UUID, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply(201) { ManagementResponses.dataSource(admin.create(c, ManagementRequests.create(body))) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable workspaceId: UUID, @PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply { ManagementResponses.dataSource(admin.get(c, ManagementRequests.id(id))) }
    }

    /** name, config and status in ONE transaction and ONE new version; `expectedVersion` makes it an optimistic update (409 on a mismatch); the answer is the data source as it is afterwards */
    @PatchMapping("/{id}")
    fun update(@PathVariable workspaceId: UUID, @PathVariable id: String, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply {
            val change = ManagementRequests.update(body)
            ManagementResponses.dataSource(admin.patch(c, ManagementRequests.id(id), change))
        }
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable workspaceId: UUID, @PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply(204) { admin.delete(c, ManagementRequests.id(id)); null }
    }

    /** metadata only: configured / type / keys (names) / updatedAt / updatedBy */
    @GetMapping("/{id}/credential")
    fun credential(@PathVariable workspaceId: UUID, @PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply { ManagementResponses.credential(admin.credentialInfo(c, ManagementRequests.id(id))) }
    }

    /** set or replace the credential; the body is write-only and the answer is the metadata, never the secret */
    @PutMapping("/{id}/credential")
    fun setCredential(@PathVariable workspaceId: UUID, @PathVariable id: String, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply {
            val dsId = ManagementRequests.id(id)
            admin.rotateCredential(c, dsId, ManagementRequests.credential(body))
            ManagementResponses.credential(admin.credentialInfo(c, dsId))
        }
    }

    @DeleteMapping("/{id}/credential")
    fun removeCredential(@PathVariable workspaceId: UUID, @PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply(204) { admin.removeCredential(c, ManagementRequests.id(id)); null }
    }

    /** runs the connector's own connection test with the stored credential; a failed test is a 200 with `ok:false` and a stable code */
    @PostMapping("/{id}/test")
    fun test(@PathVariable workspaceId: UUID, @PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply { GatewayResponses.connection(gateway.testConnection(c, ManagementRequests.id(id))) }
    }

    /** discovers the structure of the data source and stores it as a new snapshot version; `{"includeSamples":true}` additionally needs QUERY_EXECUTE (C1) and is masked */
    @PostMapping("/{id}/schema/discover")
    fun discover(@PathVariable workspaceId: UUID, @PathVariable id: String, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply { ManagementResponses.schemaSummary(discovery.refresh(c, ManagementRequests.id(id), ManagementRequests.discover(body))) }
    }

    /** the latest stored snapshot; 404 while none was discovered */
    @GetMapping("/{id}/schema")
    fun schema(@PathVariable workspaceId: UUID, @PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply { ManagementResponses.schemaSnapshot(discovery.stored(c, ManagementRequests.id(id)) ?: throw ConnectorFailure(FailureCodes.NOT_FOUND, "no schema has been discovered yet")) }
    }
}

/** Approved query and mutation definitions, scoped by data source (contract §3.5); the project-scoped form is not approved and does not exist. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/data-sources/{id}")
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class DataDefinitionController(
    private val access: AccessService,
    private val definitions: DefinitionAdminService
) {
    private fun ctx(me: StudioUserDetails, workspaceId: UUID): GatewayContext =
        ManagementContexts.workspace(access.forWorkspace(me.userId, workspaceId), RequestIdFilter.current())

    @GetMapping("/queries")
    fun listQueries(@PathVariable workspaceId: UUID, @PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply { ManagementResponses.querySummaries(definitions.listQueries(c, ManagementRequests.id(id))) }
    }

    @PostMapping("/queries")
    fun createQuery(@PathVariable workspaceId: UUID, @PathVariable id: String, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply(201) { ManagementResponses.queryDefinition(definitions.createQuery(c, ManagementRequests.id(id), ManagementRequests.queryCreate(body))) }
    }

    @GetMapping("/queries/{queryId}")
    fun getQuery(@PathVariable workspaceId: UUID, @PathVariable id: String, @PathVariable queryId: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply { ManagementResponses.queryDefinition(definitions.getQuery(c, ManagementRequests.id(id), ManagementRequests.definitionId(queryId))) }
    }

    @PatchMapping("/queries/{queryId}")
    fun updateQuery(@PathVariable workspaceId: UUID, @PathVariable id: String, @PathVariable queryId: String, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply { ManagementResponses.queryDefinition(definitions.updateQuery(c, ManagementRequests.id(id), ManagementRequests.definitionId(queryId), ManagementRequests.definitionPatch(body))) }
    }

    @DeleteMapping("/queries/{queryId}")
    fun deleteQuery(@PathVariable workspaceId: UUID, @PathVariable id: String, @PathVariable queryId: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply(204) { definitions.deleteQuery(c, ManagementRequests.id(id), ManagementRequests.definitionId(queryId)); null }
    }

    @GetMapping("/mutations")
    fun listMutations(@PathVariable workspaceId: UUID, @PathVariable id: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply { ManagementResponses.mutationSummaries(definitions.listMutations(c, ManagementRequests.id(id))) }
    }

    @PostMapping("/mutations")
    fun createMutation(@PathVariable workspaceId: UUID, @PathVariable id: String, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply(201) { ManagementResponses.mutationDefinition(definitions.createMutation(c, ManagementRequests.id(id), ManagementRequests.mutationCreate(body))) }
    }

    @GetMapping("/mutations/{mutationId}")
    fun getMutation(@PathVariable workspaceId: UUID, @PathVariable id: String, @PathVariable mutationId: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply { ManagementResponses.mutationDefinition(definitions.getMutation(c, ManagementRequests.id(id), ManagementRequests.definitionId(mutationId))) }
    }

    @PatchMapping("/mutations/{mutationId}")
    fun updateMutation(@PathVariable workspaceId: UUID, @PathVariable id: String, @PathVariable mutationId: String, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply { ManagementResponses.mutationDefinition(definitions.updateMutation(c, ManagementRequests.id(id), ManagementRequests.definitionId(mutationId), ManagementRequests.definitionPatch(body))) }
    }

    @DeleteMapping("/mutations/{mutationId}")
    fun deleteMutation(@PathVariable workspaceId: UUID, @PathVariable id: String, @PathVariable mutationId: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return managementReply(204) { definitions.deleteMutation(c, ManagementRequests.id(id), ManagementRequests.definitionId(mutationId)); null }
    }
}

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/data-bindings")
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class DataBindingController(
    private val access: AccessService,
    private val bindings: DataBindingService
) {
    private fun ctx(me: StudioUserDetails, workspaceId: UUID, projectId: UUID): GatewayContext {
        val a = access.forProject(me.userId, workspaceId, projectId)               // 404: non-member, unknown project, another tenant/workspace
        a.require(Permission.PROJECT_EDIT)                                         // bindings are application configuration
        return RuntimeContexts.gateway(a, projectId, RequestIdFilter.current(), null)
    }

    /** `TEST` or `LIVE`, upper case, nothing else: no normalisation and no fallback from one to the other */
    private fun mode(raw: String): ExecutionMode = ExecutionMode.valueOf(ManagementRequests.bindingMode(raw))

    private fun reply(status: Int = 200, block: () -> JsonNode?): ResponseEntity<JsonNode> = managementReply(status, block)

    @GetMapping
    fun list(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId, projectId)
        return reply { com.systemwebstudio.data.query.DataJson.toNode(linkedMapOf("items" to bindings.list(c).map {
            linkedMapOf("mode" to it.mode.name, "slotId" to it.slotId, "dataSourceId" to it.dataSourceId.toString(), "updatedAt" to it.updatedAt.toString()) })) }
    }

    @PutMapping("/{mode}/{slotId}")
    fun bind(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable mode: String, @PathVariable slotId: String,
        @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails
    ): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId, projectId)
        return reply {
            val m = mode(mode)
            val b = bindings.bind(c, m, ManagementRequests.slot(slotId), ManagementRequests.bindingTarget(body))
            ManagementResponses.binding(b.mode.name, b.slotId, b.dataSourceId, b.updatedAt)
        }
    }

    @DeleteMapping("/{mode}/{slotId}")
    fun unbind(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable mode: String, @PathVariable slotId: String, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId, projectId)
        return reply(204) { bindings.unbind(c, mode(mode), ManagementRequests.slot(slotId)); null }
    }
}
