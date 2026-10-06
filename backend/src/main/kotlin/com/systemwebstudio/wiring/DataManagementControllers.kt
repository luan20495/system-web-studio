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
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.cache.DataChangeListener
import com.systemwebstudio.data.datasource.RateLimitGate
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
import com.systemwebstudio.wiring.persistence.DataSourceBindingWriter
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

internal fun problem(e: ConnectorFailure): ResponseEntity<JsonNode> {
    val p = GatewayProblems.of(e)
    val b = ResponseEntity.status(p.status)
    if (p.retryAfterSeconds != null) b.header(HttpHeaders.RETRY_AFTER, p.retryAfterSeconds.toString())
    return b.body(p.toJson(RequestIdFilter.current()))
}

/** Application bindings: slot -> registered data source, per project and mode (V28 `data_source_bindings`). */
class DataBindingService(
    private val repository: DataSourceRepository,
    private val writer: DataSourceBindingWriter,
    private val guard: GatewayGuard,
    private val audit: DataAuditSink
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
        val ds = repository.findInWorkspace(ctx.tenantId, workspace, dataSourceId) ?: throw notFound()
        if (!writer.bind(ctx.tenantId, workspace, project, mode, slotId, ds.id, ctx.actorUserId)) throw notFound()
        audit.record(DataAuditActions.BINDING_CHANGED, ctx.tenantId, ds.id, mapOf("mode" to mode.name, "slot" to slotId, "change" to "bind", "project" to project.toString()) + fields(ctx))
        return Binding(mode, slotId, ds.id, Instant.now())
    }

    fun unbind(ctx: GatewayContext, mode: ExecutionMode, slotId: String) {
        guard.require(ctx, GatewayOperation.DATASOURCE_MANAGE, null)
        val project = ctx.projectId ?: throw notFound()
        val current = writer.find(ctx.tenantId, project, mode, slotId) ?: throw notFound()
        if (!writer.unbind(ctx.tenantId, project, mode, slotId)) throw notFound()
        audit.record(DataAuditActions.BINDING_CHANGED, ctx.tenantId, current, mapOf("mode" to mode.name, "slot" to slotId, "change" to "unbind", "project" to project.toString()) + fields(ctx))
    }

    private fun fields(ctx: GatewayContext) = mapOf("actor" to ctx.actorUserId?.toString(), "workspace" to ctx.workspaceId?.toString(), "request" to ctx.requestId)
    private fun notFound() = ConnectorFailure(FailureCodes.NOT_FOUND, "not found")
}

@Configuration
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class DataManagementConfiguration {
    /** workspace-scoped (B-C0-W-03 / D-C0-22): tenant AND workspace, default deny */
    @Bean
    fun c3DataSourceAdminService(
        repository: DataSourceRepository, vault: CredentialVault, registry: DataConnectorRegistry, guard: GatewayGuard, limits: RateLimitGate,
        audit: DataAuditSink, listener: DataChangeListener, jdbc: JdbcTemplate
    ) = DataSourceAdminService(repository, vault, registry, guard, limits, audit, listener, scope = DataSourceScope.WORKSPACE, credentialActors = JdbcCredentialActorLookup(jdbc))

    @Bean
    fun c3DataBindingService(repository: DataSourceRepository, writer: DataSourceBindingWriter, guard: GatewayGuard, audit: DataAuditSink) =
        DataBindingService(repository, writer, guard, audit)
}

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/data-sources")
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class DataSourceManagementController(
    private val access: AccessService,
    private val admin: DataSourceAdminService,
    private val gateway: DataGateway
) {
    private fun ctx(me: StudioUserDetails, workspaceId: UUID): GatewayContext =
        ManagementContexts.workspace(access.forWorkspace(me.userId, workspaceId), RequestIdFilter.current())      // 404 for a non-member / another tenant

    private fun reply(status: Int = 200, block: () -> JsonNode?): ResponseEntity<JsonNode> = try {
        val body = block()
        if (body == null) ResponseEntity.status(status).build<JsonNode>() else ResponseEntity.status(status).body(body)
    } catch (e: ConnectorFailure) { problem(e) }

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

    /** name, config and status; each given field is applied, the answer is the data source as it is afterwards */
    @PatchMapping("/{id}")
    fun update(@PathVariable workspaceId: UUID, @PathVariable id: String, @RequestBody(required = false) body: JsonNode?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<JsonNode> {
        val c = ctx(me, workspaceId)
        return reply {
            val change = ManagementRequests.update(body)
            val dsId = ManagementRequests.id(id)
            var view = admin.get(c, dsId)
            if (change.name != null || change.config != null) view = admin.update(c, dsId, change.name, change.config)
            if (change.status != null) view = admin.setStatus(c, dsId, change.status)
            ManagementResponses.dataSource(view)
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

    private fun mode(raw: String): ExecutionMode = when (raw.uppercase()) {
        "LIVE" -> ExecutionMode.LIVE
        "TEST" -> ExecutionMode.TEST
        else -> throw ConnectorFailure(FailureCodes.INVALID_PARAMS, "mode must be LIVE or TEST")
    }

    private fun reply(status: Int = 200, block: () -> JsonNode?): ResponseEntity<JsonNode> = try {
        val body = block()
        if (body == null) ResponseEntity.status(status).build<JsonNode>() else ResponseEntity.status(status).body(body)
    } catch (e: ConnectorFailure) { problem(e) }

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
