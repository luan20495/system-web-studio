package com.systemwebstudio.wiring

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.app.definition.AppDefinitionFormatException
import com.systemwebstudio.app.definition.AppResolutionException
import com.systemwebstudio.app.definition.QueryMode
import com.systemwebstudio.app.definition.ResolutionCodes
import com.systemwebstudio.common.RequestIdFilter
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.gateway.GatewayProblems
import com.systemwebstudio.data.gateway.GatewayQuery
import com.systemwebstudio.data.mapping.ViewModelDataJson
import com.systemwebstudio.data.query.PageSpec
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.logic.action.ExecutionMode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Turns a [RuntimeReply] into the HTTP response (status, JSON body, optional `Retry-After`). */
fun RuntimeReply.toResponse(): ResponseEntity<JsonNode> {
    val builder = ResponseEntity.status(status)
    if (retryAfterSeconds != null) builder.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
    return builder.body(body)
}

/**
 * C0 · R1 of `docs/contracts/v2/runtime-api.md`: run a READ query of the application's AppDefinition. Mounted only with `app.data-platform.enabled=true`.
 * tenantId comes from `AccessService.forProject`; the client names the query by its LOCAL id and cannot name a data source, SQL, a URL or a tenant.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/app-runtime")
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class AppRuntimeDataController(
    private val access: AccessService,
    private val definitions: RuntimeAppDefinitions,
    private val resolvers: RuntimeResolvers,
    private val gateways: ObjectProvider<DataGateway>,
    json: JsonMapper
) {
    private val responses = RuntimeResponses(json)
    private val mapper = json

    @PostMapping("/queries/{queryId}/run")
    fun runQuery(
        @PathVariable workspaceId: UUID,
        @PathVariable projectId: UUID,
        @PathVariable queryId: String,
        @RequestBody(required = false) body: JsonNode?,
        @AuthenticationPrincipal me: StudioUserDetails
    ): ResponseEntity<JsonNode> {
        val ctx = access.forProject(me.userId, workspaceId, projectId)      // 404 for a non-member / another tenant; sets the tenant
        val req = try { RuntimeRequests.queryRun(body) } catch (e: BadRuntimeRequest) { return responses.error(400, e.code, e.message ?: "invalid request", requestId = RequestIdFilter.current()).toResponse() }
        ctx.require(if (req.mode == ExecutionMode.TEST) Permission.PROJECT_EDIT else Permission.APP_USE)
        ctx.require(Permission.QUERY_EXECUTE)
        val requestId = RequestIdFilter.current()
        fun fail(status: Int, code: String, message: String) = responses.error(status, code, message, requestId = requestId).toResponse()

        val gateway = gateways.getIfAvailable() ?: return fail(503, ActionDataPortAdapter.DATA_RUNTIME_UNAVAILABLE, "The data runtime is not available.")
        val loaded = definitions.load(ctx.tenantId, projectId, req.mode) ?: return fail(404, "QUERY_NOT_FOUND", "Query not found")
        val query = try {
            resolvers.forDocument(ctx.tenantId, projectId, req.mode, loaded.document).query(queryId)
        } catch (e: AppResolutionException) {
            return if (e.code == ResolutionCodes.UNKNOWN_REFERENCE) fail(404, "QUERY_NOT_FOUND", "Query not found") else fail(422, e.code, "The data reference of this query cannot be resolved.")
        } catch (e: AppDefinitionFormatException) {
            return fail(422, "INVALID_DEFINITION", "The application definition is not valid.")
        }
        if (query.mode != QueryMode.READ) return fail(422, ResolutionCodes.WRONG_MODE, "Only a READ query can be run here.")
        val mappingRef = req.mappingRef ?: MappingPicker.pick(loaded.document, queryId) ?: return fail(422, "MAPPING_REF_REQUIRED", "Say which mapping shapes this query.")
        val page = req.page ?: query.maxRows?.let { PageSpec(it.coerceIn(1, PageSpec.MAX_PAGE_LIMIT)) }

        return try {
            val gatewayContext = RuntimeContexts.gateway(ctx, projectId, requestId, loaded.versionId)
            val r = gateway.runQuery(gatewayContext, GatewayQuery(query.dataSource.sourceId, query.operationKey, req.params, page, mappingRef))
            val out = mapper.createObjectNode()
            out.put("queryId", queryId)
            out.put("mode", req.mode.name)
            out.put("cache", r.cache.name)
            out.set<JsonNode>("result", ViewModelDataJson.toNode(r.data))
            ResponseEntity.ok(out as JsonNode)
        } catch (e: ConnectorFailure) {
            val p = GatewayProblems.of(e)
            RuntimeReply(p.status, responses.error(p.status, p.code, p.message, requestId = requestId).body, p.retryAfterSeconds?.toLong()).toResponse()
        } catch (e: Exception) {
            fail(500, "INTERNAL", "Unexpected error")
        }
    }
}
