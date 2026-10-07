package com.systemwebstudio.data.api

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.discovery.DefaultAiDataCatalogProvider
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.data.sync.webhook.WebhookRoutes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The route table and the framework-free handler C0's controllers will call: canonical GatewayQuery/GatewayMutation only, server-built context, fixed-text problems. */
class DataApiTests {
    private val f = GatewayFixture()
    private val rows = listOf(mapOf("id" to 1, "name" to "Ada", "status" to "A", "ssn" to "123-45-6789"))
    private val handler = DataApiHandler(f.gateway, DefaultAiDataCatalogProvider(f.repo, f.queries, f.mutations, f.snapshots, f.guard, f.audit, f.clock))

    private fun json(s: String) = s.toByteArray()

    // ------------------------------------------------------------------------------------------------ route table

    @Test fun `routes are unique and live under the canonical prefixes`() {
        val keys = DataRoutes.ALL.map { it.method + " " + it.path }
        assertThat(keys.toSet().size).isEqualTo(keys.size)
        assertThat(DataRoutes.ALL.map { it.name }.toSet().size).isEqualTo(DataRoutes.ALL.size)
        assertThat(DataRoutes.ALL.all { it.path.startsWith("/api/v1/") }).isTrue()
        assertThat(DataRoutes.ALL.filter { it != DataRoutes.WEBHOOK }.all { it.path.startsWith(DataRoutes.BASE + "/") }).isTrue()
    }

    @Test fun `the webhook route is the canonical one and the only public one`() {
        assertThat(DataRoutes.WEBHOOK.path).isEqualTo("/api/v1/webhooks/data/{endpointId}")
        assertThat(DataRoutes.WEBHOOK.method).isEqualTo("POST")
        assertThat(DataRoutes.WEBHOOK.path).isEqualTo(WebhookRoutes.PATH)
        assertThat(DataRoutes.ALL.filter { it.public }).containsExactly(DataRoutes.WEBHOOK)
        // every other route demands a gateway operation, the public one demands none (it is authenticated by its signature)
        assertThat(DataRoutes.ALL.filter { it.permission == null }).containsExactly(DataRoutes.WEBHOOK)
    }

    @Test fun `no route takes a tenant from the path and none uses the old query contract`() {
        DataRoutes.ALL.forEach {
            assertThat(it.path.contains("tenant", ignoreCase = true)).isFalse()
            assertThat(it.path.contains("{") && !it.path.contains("{dataSourceId}") && !it.path.contains("{endpointId}")).isFalse()
        }
        assertThat(DataRoutes.ALL.map { it.path }.any { it.contains("queryRequest", ignoreCase = true) }).isFalse()
    }

    @Test fun `each route asks for the gateway operation its handler enforces`() {
        assertThat(DataRoutes.QUERY.permission).isEqualTo(GatewayOperation.QUERY_EXECUTE)
        assertThat(DataRoutes.MUTATE.permission).isEqualTo(GatewayOperation.MUTATION_EXECUTE)
        assertThat(DataRoutes.TEST_CONNECTION.permission).isEqualTo(GatewayOperation.DATASOURCE_MANAGE)
        assertThat(DataRoutes.DISCOVER_SCHEMA.permission).isEqualTo(GatewayOperation.SCHEMA_DISCOVER)
        assertThat(DataRoutes.REFRESH_CACHE.permission).isEqualTo(GatewayOperation.CACHE_REFRESH)
        assertThat(DataRoutes.EVENTS.permission).isEqualTo(GatewayOperation.EVENTS_SUBSCRIBE)
        assertThat(DataRoutes.EVENTS.streaming).isTrue()
    }

    // ------------------------------------------------------------------------------------------------ handler

    @Test fun `a query is parsed strictly run by the gateway and rendered as the view model`() {
        val t = f.tenant(); val ds = f.register(t, rows); f.query(t, ds); f.mapping(t)
        val ok = handler.runQuery(f.ctx(t), json("""{"dataSourceId":"${ds.id}","operation":"customers","mappingRef":"m-customers","viewModelRef":"vm-customers"}"""))
        assertThat(ok.status).isEqualTo(200)
        assertThat(ok.body.toString().contains("Ada")).isTrue()
        assertThat(ok.body.toString().contains("123-45-6789")).isFalse()                    // the raw column never leaves
        assertThat(ok.body.toString().contains("ssn")).isFalse()
    }

    @Test fun `a tenant id in the body is rejected and nothing runs`() {
        val t = f.tenant(); val ds = f.register(t, rows); f.query(t, ds); f.mapping(t)
        val r = handler.runQuery(f.ctx(t), json("""{"dataSourceId":"${ds.id}","operation":"customers","mappingRef":"m-customers","tenantId":"${UUID.randomUUID()}"}"""))
        assertThat(r.status).isEqualTo(400)
        assertThat(f.connector.queryCalls.get()).isEqualTo(0)
        val m = handler.mutate(f.ctx(t), json("""{"dataSourceId":"${ds.id}","operation":"x","idempotencyKey":"idem-key-0001","tenantId":"${UUID.randomUUID()}"}"""))
        assertThat(m.status).isEqualTo(400)
    }

    @Test fun `problems carry the fixed message the status and the request id and nothing else`() {
        val t = f.tenant(); val ds = f.register(t, rows); f.query(t, ds); f.mapping(t)
        f.authorizer.denied += GatewayOperation.QUERY_EXECUTE
        val ctx = f.ctx(t)
        val r = handler.runQuery(ctx, json("""{"dataSourceId":"${ds.id}","operation":"customers","mappingRef":"m-customers"}"""))
        assertThat(r.status).isEqualTo(403)
        assertThat(r.body!!.get("code").asString()).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(r.body!!.get("requestId").asString()).isEqualTo(ctx.requestId)
        assertThat(r.body!!.size()).isEqualTo(3)
    }

    @Test fun `an unexpected exception becomes a 500 without detail`() {
        val t = f.tenant(); val ds = f.register(t, rows); f.query(t, ds); f.mapping(t)
        val boom = DataApiHandler(object : com.systemwebstudio.data.gateway.DataGateway {
            override fun runQuery(ctx: com.systemwebstudio.data.gateway.GatewayContext, query: com.systemwebstudio.data.gateway.GatewayQuery): com.systemwebstudio.data.gateway.GatewayQueryResponse = throw IllegalStateException("jdbc:postgresql://secret-host/db password=hunter2")
            override fun mutate(ctx: com.systemwebstudio.data.gateway.GatewayContext, mutation: com.systemwebstudio.data.gateway.GatewayMutation): com.systemwebstudio.data.gateway.GatewayMutationResponse = throw UnsupportedOperationException()
            override fun testConnection(ctx: com.systemwebstudio.data.gateway.GatewayContext, dataSourceId: UUID): com.systemwebstudio.data.datasource.ConnectionTestResult = throw UnsupportedOperationException()
            override fun discoverSchema(ctx: com.systemwebstudio.data.gateway.GatewayContext, dataSourceId: UUID, includeSamples: Boolean): com.systemwebstudio.data.discovery.DiscoveryService.RefreshResult = throw UnsupportedOperationException()
            override fun refreshCache(ctx: com.systemwebstudio.data.gateway.GatewayContext, dataSourceId: UUID, queryId: String?) = throw UnsupportedOperationException()
        })
        val r = boom.runQuery(f.ctx(t), json("""{"dataSourceId":"${ds.id}","operation":"customers","mappingRef":"m-customers"}"""))
        assertThat(r.status).isEqualTo(500)
        assertThat(r.body.toString().contains("secret-host")).isFalse()
        assertThat(r.body.toString().contains("hunter2")).isFalse()
    }

    @Test fun `a malformed path id is not found rather than an error with detail`() {
        val t = f.tenant()
        assertThat(handler.testConnection(f.ctx(t), "not-a-uuid").status).isEqualTo(404)
        assertThat(handler.discoverSchema(f.ctx(t), "../../etc/passwd", false).status).isEqualTo(404)
        assertThat(handler.refreshCache(f.ctx(t), "x", null).status).isEqualTo(404)
    }

    @Test fun `cache refresh answers 204 and is permission checked`() {
        val t = f.tenant(); val ds = f.register(t, rows); f.query(t, ds)
        assertThat(handler.refreshCache(f.ctx(t), ds.id.toString(), "customers").status).isEqualTo(204)
        f.authorizer.denied += GatewayOperation.CACHE_REFRESH
        assertThat(handler.refreshCache(f.ctx(t), ds.id.toString(), null).status).isEqualTo(403)
    }

    @Test fun `ai catalog is 501 when no provider is wired and filtered per caller when it is`() {
        val t = f.tenant(); f.register(t, rows)
        assertThat(DataApiHandler(f.gateway, null).aiCatalog(f.ctx(t), false).status).isEqualTo(501)
        val ok = handler.aiCatalog(f.ctx(t), false)
        assertThat(ok.status).isEqualTo(200)
        assertThat(ok.body.toString().contains("TOPSECRET")).isFalse()
        f.authorizer.denied += GatewayOperation.DATASOURCE_READ
        val none = handler.aiCatalog(f.ctx(t), false)
        assertThat(none.status).isEqualTo(200)
        assertThat(none.body!!.get("dataSources").size()).isEqualTo(0)
    }

    @Test fun `an api result never prints its body`() {
        val t = f.tenant()
        val r = handler.testConnection(f.ctx(t), "x")
        assertThat(r.toString()).isEqualTo("ApiResult(404)")
    }
}
