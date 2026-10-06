package com.systemwebstudio.data.api

import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.discovery.AiCatalogOptions
import com.systemwebstudio.data.discovery.AiDataCatalogProvider
import com.systemwebstudio.data.gateway.DataGateway
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.gateway.GatewayProblem
import com.systemwebstudio.data.gateway.GatewayProblems
import com.systemwebstudio.data.gateway.GatewayRequests
import com.systemwebstudio.data.gateway.GatewayResponses
import com.systemwebstudio.data.sync.webhook.WebhookRoutes
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

/*
 * HTTP surface of the Data Platform, prepared without any framework (BLOCKERS B-C3-01/02, contract data-runtime §6). Everything that decides *what an
 * endpoint does* lives here and is tested here; C0's Spring controllers (package `wiring`, outside C3's ownership) shrink to: read headers/body, build the
 * `GatewayContext` from the authenticated session, call [DataApiHandler], write [ApiResult]. The proposal for that wiring is
 * `docs/parallel/agents/C3_WIRING_PROPOSAL.md`; nothing here touches `identity/SecurityConfiguration.kt` or any other C0 file.
 *
 * The runtime vocabulary is `GatewayQuery` / `GatewayMutation` (contract §2). The old `QueryRequest` is a connector-SPI type and is never part of an endpoint.
 */

/** One endpoint of the Data Platform. [permission] is what [GatewayOperation] the gateway demands (C1's `GatewayAuthorizer` maps it to a permission code); `null` only for the signed public webhook. */
data class DataRoute(val name: String, val method: String, val path: String, val permission: GatewayOperation?, val streaming: Boolean = false, val public: Boolean = false)

object DataRoutes {
    const val BASE = "/api/v1/data"

    val QUERY = DataRoute("runQuery", "POST", "$BASE/query", GatewayOperation.QUERY_EXECUTE)
    val MUTATE = DataRoute("mutate", "POST", "$BASE/mutate", GatewayOperation.MUTATION_EXECUTE)
    val TEST_CONNECTION = DataRoute("testConnection", "POST", "$BASE/sources/{dataSourceId}/test", GatewayOperation.DATASOURCE_MANAGE)
    val DISCOVER_SCHEMA = DataRoute("discoverSchema", "POST", "$BASE/sources/{dataSourceId}/schema/refresh", GatewayOperation.SCHEMA_DISCOVER)
    val REFRESH_CACHE = DataRoute("refreshCache", "POST", "$BASE/sources/{dataSourceId}/cache/refresh", GatewayOperation.CACHE_REFRESH)
    val EVENTS = DataRoute("events", "GET", "$BASE/events", GatewayOperation.EVENTS_SUBSCRIBE, streaming = true)
    val AI_CATALOG = DataRoute("aiCatalog", "GET", "$BASE/ai-catalog", GatewayOperation.DATASOURCE_READ)
    /** the one public route: authenticated by its HMAC signature, resolved to a tenant by the endpoint id only (see `WebhookIngress`) */
    val WEBHOOK = DataRoute("webhookIngest", WebhookRoutes.METHOD, WebhookRoutes.PATH, null, public = true)

    val ALL: List<DataRoute> = listOf(QUERY, MUTATE, TEST_CONNECTION, DISCOVER_SCHEMA, REFRESH_CACHE, EVENTS, AI_CATALOG, WEBHOOK)
}

/** What a controller writes back: the status, and a JSON body (null = no body). Never contains anything from a cause or a stack trace. */
class ApiResult(val status: Int, val body: JsonNode?, val retryAfterSeconds: Int? = null) {
    override fun toString() = "ApiResult($status)"
}

/**
 * The framework-free controller. Every method takes the server-built [GatewayContext] — never a tenant from the request — and the raw request material;
 * parsing is strict (unknown fields are refused), authorisation and tenant scoping happen in the gateway, and any failure becomes a [GatewayProblems] body
 * with the fixed message of the failure.
 */
class DataApiHandler(private val gateway: DataGateway, private val aiCatalog: AiDataCatalogProvider? = null) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runQuery(ctx: GatewayContext, body: ByteArray): ApiResult = respond(ctx) { ok(GatewayResponses.query(gateway.runQuery(ctx, GatewayRequests.query(body)))) }

    fun mutate(ctx: GatewayContext, body: ByteArray): ApiResult = respond(ctx) { ok(GatewayResponses.mutation(gateway.mutate(ctx, GatewayRequests.mutation(body)))) }

    fun testConnection(ctx: GatewayContext, dataSourceId: String): ApiResult =
        respond(ctx) { ok(GatewayResponses.connection(gateway.testConnection(ctx, GatewayRequests.dataSourceId(dataSourceId)))) }

    fun discoverSchema(ctx: GatewayContext, dataSourceId: String, includeSamples: Boolean): ApiResult =
        respond(ctx) { ok(GatewayResponses.schema(gateway.discoverSchema(ctx, GatewayRequests.dataSourceId(dataSourceId), includeSamples))) }

    fun refreshCache(ctx: GatewayContext, dataSourceId: String, queryId: String?): ApiResult = respond(ctx) {
        gateway.refreshCache(ctx, GatewayRequests.dataSourceId(dataSourceId), queryId?.takeIf { it.isNotBlank() })
        ApiResult(204, null)
    }

    /** the AI planner's view of the tenant's data (masked structure + approved operations the caller may use); 501 when no provider is wired */
    fun aiCatalog(ctx: GatewayContext, includeMaskedSamples: Boolean): ApiResult = respond(ctx) {
        val provider = aiCatalog ?: throw ConnectorFailure(FailureCodes.NOT_IMPLEMENTED, "not available")
        ok(provider.catalog(ctx, AiCatalogOptions(includeMaskedSamples)).toJson())
    }

    private fun ok(body: JsonNode) = ApiResult(200, body)

    private fun respond(ctx: GatewayContext, call: () -> ApiResult): ApiResult = try {
        call()
    } catch (e: ConnectorFailure) {
        problem(GatewayProblems.of(e), ctx)
    } catch (e: Exception) {
        log.error("data api call failed unexpectedly: {}", e.javaClass.name)                      // class only: messages of driver/JDK errors can carry hosts, SQL and secrets
        problem(GatewayProblems.internal(), ctx)
    }

    private fun problem(p: GatewayProblem, ctx: GatewayContext) = ApiResult(p.status, p.toJson(ctx.requestId), p.retryAfterSeconds)
}
