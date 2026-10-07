package com.systemwebstudio.wiring

import com.systemwebstudio.access.adapters.PublicQueryAllowList
import com.systemwebstudio.access.adapters.PublicSiteAuthorizer
import com.systemwebstudio.access.adapters.PublicSiteDecision
import com.systemwebstudio.access.adapters.PublicSiteGatewayContext
import com.systemwebstudio.access.adapters.PublicSiteRequest
import com.systemwebstudio.access.adapters.PublicSiteResponses
import com.systemwebstudio.app.definition.AppDefinitionFormatException
import com.systemwebstudio.app.definition.AppDefinitionCodec
import com.systemwebstudio.app.definition.AppResolutionException
import com.systemwebstudio.app.definition.PublicQueries
import com.systemwebstudio.app.definition.QueryMode
import com.systemwebstudio.app.definition.ResolutionCodes
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.common.RequestIdFilter
import com.systemwebstudio.common.requestIdOf
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditSink
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayQuery
import com.systemwebstudio.data.mapping.ViewModelDataJson
import com.systemwebstudio.data.query.PageSpec
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.publish.SiteService
import com.systemwebstudio.tenancy.ActorKind
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.tenancy.TenantStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/*
 * C0 · the Public Runtime route, V1 (docs/contracts/v2/published-runtime.md section 4, D-C0-35 / D-C0-36):
 *
 *   POST /sites/{slug}/_data/queries/{queryId}/run          anonymous, same origin through the sites gateway, no cookie, no session, no CSRF (nothing to ride)
 *
 * The only thing the browser names is the query (path) and its parameters (body `{"params":{...}}`, nothing else is accepted). Everything else is derived on the
 * server: slug -> site -> the ACTIVE release (`sites.current_deployment_id`, never "the latest RUNNING deployment") -> project -> workspace -> tenant -> principal
 * PUBLIC_SITE (C1 `PublicSiteAuthorizer`, which runs BEFORE the data gateway) -> the release's immutable public-query allow-list -> the LIVE binding -> C3
 * `DataGateway.runQuery`. Nothing else of the gateway is reachable from here: no mutation, no discovery, no cache refresh, no events, no sync, no webhooks.
 *
 * Every refusal of the policy is the same `404 QUERY_NOT_FOUND`; the reason goes to the log at DEBUG, never to the visitor. Mounted only with
 * `app.data-platform.enabled=true` AND `app.sites.public-data.enabled=true` (both default false).
 */

private const val CONDITION = "\${app.data-platform.enabled:false} and \${app.sites.public-data.enabled:false}"
private val PUBLIC_DATA_PATH = Regex("^/sites/[^/]+/_data/queries/[^/]+/run$")

/**
 * The public queries of a RELEASE, read from the immutable snapshot of the version the release pins (`project_versions.schema_snapshot` is never updated; a
 * deployment's `version_id` never changes). Draft edits therefore cannot change what an active release exposes, a rollback changes the answer with the pointer,
 * and a release answers only for itself. Eligible = a query the author declared `public: true`, mode READ, in that snapshot; and only while the project's
 * publish policy carries the public-data approval (`publish_configs.public_data_approved`, the author's explicit acknowledgement; revoking it closes the route at
 * once). No row, no approval, no snapshot, any error: the empty set (C1 then refuses every query).
 */
class ReleaseSnapshotPublicQueryAllowList(private val jdbc: JdbcTemplate, private val json: JsonMapper, private val codec: AppDefinitionCodec) : PublicQueryAllowList {
    override fun publicQueryIds(tenantId: UUID, projectId: UUID, releaseId: UUID): Set<String> = try {
        val rows = jdbc.queryForList(
            """SELECT v.schema_snapshot::text AS snapshot, pc.public_data_approved AS approved
               FROM deployments d
               JOIN project_versions v ON v.id = d.version_id AND v.project_id = d.project_id
               JOIN projects p ON p.id = d.project_id JOIN workspaces w ON w.id = p.workspace_id AND w.tenant_id = ?
               JOIN publish_configs pc ON pc.project_id = d.project_id AND pc.tenant_id = w.tenant_id
               WHERE d.id = ? AND d.project_id = ?""", tenantId, releaseId, projectId)
        val row = rows.firstOrNull()
        if (row == null || row["approved"] != true) emptySet() else PublicQueries.of(codec.fromJson(json.readTree(row["snapshot"] as String))).toSet()
    } catch (e: Exception) {
        emptySet()                                                           // an unreadable snapshot is not public: fail closed
    }
}

/** body limit of the public route (contract: nginx 16 KiB + this filter, so the API is safe even when reached without the gateway) */
@Component
@ConditionalOnExpression(CONDITION)
class PublicDataBodyLimitFilter(
    @Value("\${app.sites.public-data.max-body-bytes:16384}") private val limit: Int,
    private val json: JsonMapper
) : OncePerRequestFilter() {
    init { require(limit in 256..1_048_576) { "app.sites.public-data.max-body-bytes must be between 256 and 1048576" } }

    override fun shouldNotFilter(request: HttpServletRequest) = request.method != "POST" || !PUBLIC_DATA_PATH.matches(request.requestURI)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > limit) return tooLarge(request, response)
        val bytes = request.inputStream.readNBytes(limit + 1)
        if (bytes.size > limit) return tooLarge(request, response)
        chain.doFilter(ReplayedRequest(request, bytes), response)
    }

    private fun tooLarge(request: HttpServletRequest, response: HttpServletResponse) {
        val r = ManagementErrors.reply(413, FailureCodes.PAYLOAD_TOO_LARGE, "request body is too large", requestIdOf(request))
        response.status = 413
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader(HttpHeaders.CONNECTION, "close")
        response.writer.write(json.writeValueAsString(r.body))
    }
}

@Configuration
@ConditionalOnExpression(CONDITION)
class PublicDataConfiguration {
    /** the release allow-list provider C1's policy consults; without this bean the policy keeps its default, DENY ALL */
    @Bean
    fun publicQueryAllowList(jdbc: JdbcTemplate, json: JsonMapper, codec: AppDefinitionCodec): PublicQueryAllowList = ReleaseSnapshotPublicQueryAllowList(jdbc, json, codec)
}

@RestController
@RequestMapping("/sites/{slug}/_data")
@ConditionalOnExpression(CONDITION)
class PublicDataController(
    private val sites: SiteService,
    private val jdbc: JdbcTemplate,
    private val authorizer: PublicSiteAuthorizer,
    private val definitions: RuntimeAppDefinitions,
    private val resolvers: RuntimeResolvers,
    private val gateways: ObjectProvider<DataGateway>,
    private val limiter: RateLimiter,
    private val audit: ObjectProvider<DataAuditSink>,
    private val json: JsonMapper,
    @Value("\${app.sites.public-data.max-rows:100}") private val maxRows: Int,
    @Value("\${app.sites.public-data.max-response-bytes:262144}") private val maxResponseBytes: Int,
    @Value("\${app.sites.public-data.rate.ip-burst:30}") private val ipBurst: Long,
    @Value("\${app.sites.public-data.rate.ip-burst-window-seconds:10}") private val ipBurstWindow: Long,
    @Value("\${app.sites.public-data.rate.site-ip-per-minute:120}") private val siteIpPerMinute: Long,
    @Value("\${app.sites.public-data.rate.site-per-minute:1200}") private val sitePerMinute: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init { require(maxRows in 1..PageSpec.MAX_PAGE_LIMIT && maxResponseBytes in 1_024..4_194_304) { "app.sites.public-data.max-rows / max-response-bytes are out of range" } }

    private companion object {
        val SLUG = Regex("^[a-z0-9][a-z0-9-]{1,79}$")
        val BODY_KEYS = setOf("params")
    }

    private fun notFound(requestId: String?) = reply(404, PublicSiteResponses.NOT_FOUND_CODE, PublicSiteResponses.NOT_FOUND_MESSAGE, requestId)

    private fun reply(status: Int, code: String, message: String, requestId: String?, retryable: Boolean = false, retryAfter: Long? = null): ResponseEntity<JsonNode> =
        ManagementErrors.reply(status, code, message, requestId, retryable, retryAfter).let { r ->
            ResponseEntity.status(r.statusCode).headers(r.headers).header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(r.body)
        }

    /** a fixed-window budget; a Redis outage refuses (the budget protects the data sources) */
    private fun allow(key: String, limit: Long, window: Long): Pair<Boolean, Long> = try {
        limiter.hit(key, limit, window).let { it.allowed to it.retryAfterSeconds }
    } catch (e: Exception) { false to 5L }

    @PostMapping("/queries/{queryId}/run")
    fun run(@PathVariable slug: String, @PathVariable queryId: String, @RequestBody(required = false) body: JsonNode?, request: HttpServletRequest): ResponseEntity<JsonNode> {
        val requestId = RequestIdFilter.current()
        val ip = request.remoteAddr ?: "unknown"

        // 1. abuse control before anything is looked up: per client address, independent of the slug the client names
        allow("pubdata:ip:$ip", ipBurst, ipBurstWindow).let { (ok, retry) -> if (!ok) return reply(429, "RATE_LIMITED", "Too many requests; retry later.", requestId, true, retry) }

        // 2. strict body: only `params`
        val params = try {
            if (body != null && !body.isNull) {
                if (!body.isObject) return reply(400, "INVALID_REQUEST", "The body must be an object.", requestId)
                if (body.propertyNames().any { it !in BODY_KEYS }) return reply(400, "INVALID_REQUEST", "Only 'params' is accepted.", requestId)
            }
            RuntimeRequests.queryRun(body).params
        } catch (e: BadRuntimeRequest) { return reply(400, "INVALID_REQUEST", "The request is not valid.", requestId) }

        // 3. the site and its ACTIVE release, server side; the tenant from the workspace
        if (!SLUG.matches(slug)) return notFound(requestId)
        val site = sites.live(slug) ?: return notFound(requestId)
        val tenantId = jdbc.queryForList("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, site.workspaceId).firstOrNull() ?: return notFound(requestId)
        allow("pubdata:site:$slug:$ip", siteIpPerMinute, 60).let { (ok, retry) -> if (!ok) return reply(429, "RATE_LIMITED", "Too many requests; retry later.", requestId, true, retry) }
        allow("pubdata:site:$slug", sitePerMinute, 60).let { (ok, retry) -> if (!ok) return reply(429, "RATE_LIMITED", "Too many requests; retry later.", requestId, true, retry) }

        // 4. C1 decides BEFORE the data gateway: operation, LIVE, active public release, release allow-list
        val decision = authorizer.authorize(PublicSiteRequest(PublicSiteGatewayContext(tenantId, site.workspaceId, site.projectId, slug, site.deploymentId), "QUERY_EXECUTE", "LIVE", queryId))
        val principal = when (decision) {
            is PublicSiteDecision.NotFound -> { log.debug("public query refused slug={} reason={}", slug, decision.auditReason); return notFound(requestId) }
            is PublicSiteDecision.Allowed -> decision.principal
        }

        // 5. the release's own definition (its pinned version) and the LIVE binding; nothing the browser sent decides any of this
        val loaded = definitions.loadVersion(principal.tenantId, principal.projectId, principal.appVersionId.toString()) ?: return notFound(requestId)
        val query = try {
            resolvers.forDocument(principal.tenantId, principal.projectId, ExecutionMode.LIVE, loaded.document).query(queryId)
        } catch (e: AppResolutionException) {
            return if (e.code == ResolutionCodes.DATA_SOURCE_UNBOUND) reply(422, ResolutionCodes.DATA_SOURCE_UNBOUND, "The data of this query is not available.", requestId) else notFound(requestId)
        } catch (e: AppDefinitionFormatException) { return notFound(requestId) }
        if (query.mode != QueryMode.READ) return notFound(requestId)
        val mappingRef = MappingPicker.pick(loaded.document, queryId) ?: return notFound(requestId)
        val gateway = gateways.getIfAvailable() ?: return reply(503, "DATA_RUNTIME_UNAVAILABLE", "The data runtime is not available.", requestId, true, 5)

        // 6. C3: runQuery only, as PUBLIC_SITE, with the public caps
        return try {
            val ctx = GatewayContext(TenantContext(principal.tenantId, null, TenantStatus.ACTIVE, false), null, ActorKind.PUBLIC_SITE, requestId, principal.workspaceId, principal.projectId, principal.appVersionId.toString())
            val limit = (query.maxRows ?: maxRows).coerceIn(1, maxRows)
            val r = gateway.runQuery(ctx, GatewayQuery(query.dataSource.sourceId, query.operationKey, params, PageSpec(limit), mappingRef))
            val out = json.createObjectNode()
            out.put("queryId", queryId)
            out.put("mode", "LIVE")                                  // the shape of runtime-api.md R1, which the page runtime reads
            out.put("cache", r.cache.name)
            out.set("result", ViewModelDataJson.toNode(r.data))
            val text = json.writeValueAsString(out)
            if (text.toByteArray().size > maxResponseBytes) return reply(502, FailureCodes.RESPONSE_TOO_LARGE, "The result is too large.", requestId)
            runCatching { audit.getIfAvailable()?.record("DATA_PUBLIC_QUERY_SERVED", principal.tenantId, query.dataSource.sourceId, principal.auditAttributes(queryId) + mapOf("request" to requestId)) }
            ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").contentType(MediaType.APPLICATION_JSON).body(out as JsonNode)
        } catch (e: ConnectorFailure) {
            when (e.code) {
                FailureCodes.RATE_LIMITED, FailureCodes.REFRESH_TOO_SOON -> reply(429, "RATE_LIMITED", "Too many requests; retry later.", requestId, true, 30)
                FailureCodes.PERMISSION_DENIED, FailureCodes.NOT_FOUND, FailureCodes.QUERY_NOT_FOUND, FailureCodes.TENANT_MISMATCH -> notFound(requestId)
                FailureCodes.INVALID_PARAMS, FailureCodes.INVALID_QUERY -> reply(400, "INVALID_REQUEST", "The parameters are not valid.", requestId)
                FailureCodes.TIMEOUT -> reply(504, "DATA_TIMEOUT", "The data source did not answer in time.", requestId, true)
                else -> reply(502, "DATA_UNAVAILABLE", "The data is not available.", requestId, true)     // never the connector's text, host, schema, SQL or credential metadata
            }
        } catch (e: Exception) {
            log.error("public query failed unexpectedly: {}", e.javaClass.name)                     // class only: driver / JDK messages can carry hosts, SQL and secrets
            reply(500, FailureCodes.INTERNAL, "Unexpected error", requestId)
        }
    }
}

/** the one envelope for this controller (malformed JSON, wrong media type, anything unexpected); the shape is `management-api.md` section 4 */
@RestControllerAdvice(assignableTypes = [PublicDataController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnExpression(CONDITION)
class PublicDataExceptionAdvice {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(r: HttpServletRequest) = ManagementErrors.reply(400, "INVALID_REQUEST", "The body is not valid JSON.", requestIdOf(r))

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException, r: HttpServletRequest) = ManagementErrors.reply(e.status.value(), e.code, e.message, requestIdOf(r), retryAfterSeconds = e.headers["Retry-After"]?.toLongOrNull())

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, r: HttpServletRequest): ResponseEntity<JsonNode> {
        log.error("public data request failed unexpectedly: {}", e.javaClass.name)
        return ManagementErrors.reply(500, FailureCodes.INTERNAL, "Unexpected error", requestIdOf(r))
    }
}
