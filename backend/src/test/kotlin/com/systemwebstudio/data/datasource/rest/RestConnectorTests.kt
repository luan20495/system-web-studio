package com.systemwebstudio.data.datasource.rest

import com.systemwebstudio.data.AllowAllAddresses
import com.systemwebstudio.data.FixedResolver
import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.tenancy.TenantContext
import com.systemwebstudio.data.discovery.NormalizedType
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.PageSpec
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.RestQueryDefinition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class RestConnectorTests {
    private val tenant = UUID.randomUUID()
    private val dsId = UUID.randomUUID()
    private val secret = "Bearer sk-live-TOPSECRET-9f8e7d"
    private val cred = ResolvedCredential.of(mapOf("authValue" to secret))
    private val config = mapOf("baseUrl" to "https://api.example.test/v1", "authHeader" to "Authorization")
    private fun ref(cfg: Map<String, String> = config, t: UUID = tenant) = DataSourceRef(dsId, t, DataSourceTypes.REST, cfg)
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }
    private fun json(s: String): JsonNode = DataJson.parse(s.toByteArray())
    private fun req(id: String, params: Map<String, String> = emptyMap(), page: PageSpec? = null, t: UUID = tenant) =
        QueryRequest(id, params.mapValues { DataJson.toNode(it.value) }, page, TenantContext(t, null))

    /** records the request, answers with a canned response */
    private class FakeTransport(var response: () -> HttpGetResponse = { HttpGetResponse(200, "application/json", "[]".toByteArray()) }) : RestTransport {
        val requests = mutableListOf<HttpGetRequest>()
        override fun get(request: HttpGetRequest): HttpGetResponse { requests += request; return response() }
    }
    private fun ok(body: String, type: String = "application/json") = HttpGetResponse(200, type, body.toByteArray())

    private val orders = RestQueryDefinition("orders", tenant, dsId, "/orders/{id}", listOf(QueryParamSpec("id", ParamType.STRING)))
    private val list = RestQueryDefinition("list", tenant, dsId, "/items", listOf(QueryParamSpec("q", ParamType.STRING, required = false), QueryParamSpec("n", ParamType.INTEGER, required = false)),
        queryParams = mapOf("q" to "search", "n" to "page[size]"), rowsPointer = "/data", maxRows = 3, discoverable = true)

    // ---------------------------------------------------------------- configuration

    @Test fun `configuration accepts a plain https base URL and nothing else`() {
        val c = RestConnector(InMemoryQueryCatalog(), FakeTransport())
        c.validateConfig(config)
        c.validateConfig(mapOf("baseUrl" to "https://api.example.test:8443"))
        val bad = listOf(
            mapOf("baseUrl" to "http://api.example.test"),
            mapOf("baseUrl" to "https://user:$secret@api.example.test"),
            mapOf("baseUrl" to "https://api.example.test/v1?key=$secret"),
            mapOf("baseUrl" to "https://api.example.test/#x"),
            mapOf("baseUrl" to "https://localhost"), mapOf("baseUrl" to "https://127.0.0.1"), mapOf("baseUrl" to "https://[::1]/"), mapOf("baseUrl" to "https://10.1.2.3"),
            mapOf("baseUrl" to "https://metadata.internal"), mapOf("baseUrl" to "https://intranet"), mapOf("baseUrl" to "https://svc.local"),
            mapOf("baseUrl" to "https://api.example.test/../etc"), mapOf("baseUrl" to "https://api.example.test:99999"), mapOf("baseUrl" to ""), emptyMap(),
            config + ("authHeader" to "Host"), config + ("authHeader" to "X Bad"), config + ("timeoutMs" to "9999999"), config + ("maxRows" to "0"), config + ("apiKey" to secret)
        )
        for (cfg in bad) {
            val f = failure { c.validateConfig(cfg) }
            assertThat(f.code).isEqualTo(FailureCodes.INVALID_CONFIG)
            assertThat(f.message).doesNotContain("TOPSECRET")          // a rejected value (a pasted credential) is never echoed
        }
    }

    // ---------------------------------------------------------------- connection test

    @Test fun `connection test maps statuses to typed results and sends the credential only as the configured header`() {
        val t = FakeTransport(); val c = RestConnector(InMemoryQueryCatalog(), t)
        assertThat(c.test(ref(), cred)).isInstanceOf(ConnectionTestResult.Ok::class.java)
        val sent = t.requests.single()
        assertThat(sent.url.toString()).isEqualTo("https://api.example.test/v1/")
        assertThat(sent.headers).isEqualTo(mapOf("Authorization" to secret))
        for ((status, code) in listOf(401 to FailureCodes.AUTH_REJECTED, 403 to FailureCodes.AUTH_REJECTED, 302 to FailureCodes.REDIRECT_BLOCKED, 301 to FailureCodes.REDIRECT_BLOCKED,
            500 to FailureCodes.UPSTREAM_STATUS, 404 to FailureCodes.UPSTREAM_STATUS)) {
            t.response = { HttpGetResponse(status, null, ByteArray(0), "https://169.254.169.254/") }
            val r = c.test(ref(), cred) as ConnectionTestResult.Failed
            assertThat(r.code).isEqualTo(code)
            assertThat(r.message).doesNotContain("169.254.169.254")
            assertThat(r.toString()).doesNotContain("TOPSECRET")
        }
    }

    @Test fun `connection test turns every transport failure into a failed result, never an exception`() {
        for (code in listOf(FailureCodes.ADDRESS_BLOCKED, FailureCodes.TIMEOUT, FailureCodes.TLS_FAILED, FailureCodes.CONNECT_FAILED, FailureCodes.HOST_UNRESOLVED)) {
            val c = RestConnector(InMemoryQueryCatalog(), FakeTransport { throw ConnectorFailure(code, "fixed") })
            assertThat((c.test(ref(), cred) as ConnectionTestResult.Failed).code).isEqualTo(code)
        }
        assertThat((RestConnector(InMemoryQueryCatalog(), FakeTransport()).test(ref(mapOf("baseUrl" to "http://x.example.test")), cred) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.INVALID_CONFIG)
    }

    @Test fun `a configured auth header without a stored credential fails instead of calling unauthenticated`() {
        val t = FakeTransport()
        val r = RestConnector(InMemoryQueryCatalog(), t).test(ref(), ResolvedCredential.NONE) as ConnectionTestResult.Failed
        assertThat(r.code).isEqualTo(FailureCodes.INVALID_CREDENTIAL)
        assertThat(t.requests.size).isEqualTo(0)
    }

    // ---------------------------------------------------------------- query execution

    @Test fun `parameters are bound into a declared template and the host can never change`() {
        val t = FakeTransport { ok("""{"id":"x"}""") }
        val e = RestConnector(InMemoryQueryCatalog(orders), t).executor()
        for (value in listOf("42", "a b", "é", "@evil.example", "evil.example", "..%2F..", "a?b=c", "a#b", "%2e%2e", "x:y", "\"; DROP", "a@b:c")) {
            e.execute(req("orders", mapOf("id" to value)), ref(), cred)
            val url = t.requests.last().url
            assertThat(url.host).isEqualTo("api.example.test")
            assertThat(url.scheme).isEqualTo("https")
            assertThat(url.rawPath).startsWith("/v1/orders/")
            assertThat(url.rawPath.removePrefix("/v1/orders/").contains('/')).isFalse()      // exactly one segment
            assertThat(url.rawQuery).isNull(); assertThat(url.rawFragment).isNull(); assertThat(url.userInfo).isNull()
        }
        assertThat(t.requests.first().url.rawPath).isEqualTo("/v1/orders/42")
        assertThat(t.requests[1].url.rawPath).isEqualTo("/v1/orders/a%20b")
        assertThat(t.requests[2].url.rawPath).isEqualTo("/v1/orders/%C3%A9")
    }

    @Test fun `values that could escape the path are refused outright`() {
        val e = RestConnector(InMemoryQueryCatalog(orders), FakeTransport { ok("{}") }).executor()
        for (value in listOf("../admin", "a/b", "/x", "..", ".", "", "a\\b", "a\nb", "a\u0000b"))
            assertThat(failure { e.execute(req("orders", mapOf("id" to value)), ref(), cred) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `unknown, missing and mistyped parameters are rejected`() {
        val e = RestConnector(InMemoryQueryCatalog(orders, list), FakeTransport { ok("{}") }).executor()
        assertThat(failure { e.execute(req("orders", mapOf("id" to "1", "extra" to "x")), ref(), cred) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(failure { e.execute(req("orders"), ref(), cred) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(failure { e.execute(QueryRequest("list", mapOf("n" to DataJson.toNode("abc")), null, TenantContext(tenant, null)), ref(), cred) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `query parameters are encoded and sorted by name`() {
        val t = FakeTransport { ok("""{"data":[]}""") }
        RestConnector(InMemoryQueryCatalog(list), t).executor().execute(
            QueryRequest("list", mapOf("q" to DataJson.toNode("a&b=c d"), "n" to DataJson.toNode(5)), null, TenantContext(tenant, null)), ref(), cred)
        assertThat(t.requests.single().url.rawQuery).isEqualTo("page%5Bsize%5D=5&search=a%26b%3Dc%20d")
    }

    @Test fun `rows come from the pointer, are capped, and truncation is reported`() {
        val body = """{"data":[{"id":1,"name":"a","price":1.5,"ok":true,"meta":{"k":1}},{"id":2,"name":null},{"id":3},{"id":4}],"other":1}"""
        val r = RestConnector(InMemoryQueryCatalog(list), FakeTransport { ok(body) }).executor().execute(req("list"), ref(), cred)
        assertThat(r.rows.size).isEqualTo(3)                              // definition maxRows = 3
        assertThat(r.truncated).isTrue()
        val types = r.columns.associate { it.name to it.type }
        assertThat(types["id"]).isEqualTo(NormalizedType.INTEGER)
        assertThat(types["name"]).isEqualTo(NormalizedType.STRING)
        assertThat(types["price"]).isEqualTo(NormalizedType.NUMBER)
        assertThat(types["ok"]).isEqualTo(NormalizedType.BOOLEAN)
        assertThat(types["meta"]).isEqualTo(NormalizedType.JSON)
        val page = RestConnector(InMemoryQueryCatalog(list), FakeTransport { ok(body) }).executor().execute(req("list", page = PageSpec(2, 1)), ref(), cred)
        assertThat(page.rows.size).isEqualTo(2); assertThat(page.rows[0]["id"]!!.asString()).isEqualTo("2")
    }

    @Test fun `the data source's own row cap lowers the definition's`() {
        val rows = (1..50).joinToString(",") { """{"id":$it}""" }
        val r = RestConnector(InMemoryQueryCatalog(list.copy(maxRows = 40, rowsPointer = "")), FakeTransport { ok("[$rows]") }).executor().execute(req("list"), ref(config + ("maxRows" to "7")), cred)
        assertThat(r.rows.size).isEqualTo(7); assertThat(r.truncated).isTrue()
    }

    @Test fun `redirects, error statuses and non-JSON answers fail with fixed codes`() {
        val t = FakeTransport(); val e = RestConnector(InMemoryQueryCatalog(list), t).executor()
        fun code(resp: HttpGetResponse): String { t.response = { resp }; return failure { e.execute(req("list"), ref(), cred) }.code }
        assertThat(code(HttpGetResponse(302, null, ByteArray(0), "https://10.0.0.1/"))).isEqualTo(FailureCodes.REDIRECT_BLOCKED)
        assertThat(code(HttpGetResponse(401, "application/json", "{}".toByteArray()))).isEqualTo(FailureCodes.AUTH_REJECTED)
        assertThat(code(HttpGetResponse(500, "application/json", "{}".toByteArray()))).isEqualTo(FailureCodes.UPSTREAM_STATUS)
        assertThat(code(ok("<html>", "text/html"))).isEqualTo(FailureCodes.RESPONSE_NOT_JSON)
        assertThat(code(ok("not json at all"))).isEqualTo(FailureCodes.RESPONSE_NOT_JSON)
        assertThat(code(ok("""{"nodata":[]}"""))).isEqualTo(FailureCodes.RESPONSE_INVALID)          // pointer /data missing
        assertThat(code(ok("""{"data":"scalar"}"""))).isEqualTo(FailureCodes.RESPONSE_INVALID)
    }

    @Test fun `tenant and catalog scoping`() {
        val other = UUID.randomUUID()
        val e = RestConnector(InMemoryQueryCatalog(orders), FakeTransport { ok("{}") }).executor()
        assertThat(failure { e.execute(req("orders", mapOf("id" to "1"), t = other), ref(), cred) }.code).isEqualTo(FailureCodes.TENANT_MISMATCH)
        assertThat(failure { e.execute(req("orders", mapOf("id" to "1"), t = other), ref(t = other), cred) }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)   // other tenant's data source: no such definition
        assertThat(failure { e.execute(req("missing"), ref(), cred) }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)
    }

    @Test fun `results and failures never carry the credential`() {
        val t = FakeTransport { ok("""[{"id":1}]""") }
        val r = RestConnector(InMemoryQueryCatalog(list.copy(rowsPointer = "")), t).executor().execute(req("list"), ref(), cred)
        assertThat(r.toString()).doesNotContain("TOPSECRET")
        t.response = { throw ConnectorFailure(FailureCodes.TIMEOUT, "the data source did not answer in time") }
        val f = failure { RestConnector(InMemoryQueryCatalog(list), t).executor().execute(req("list"), ref(), cred) }
        assertThat(f.toString()).doesNotContain("TOPSECRET"); assertThat(f.message).doesNotContain("api.example.test")
        assertThat(t.requests.first().toString()).doesNotContain("TOPSECRET")        // the request object itself does not print its headers
    }

    // ---------------------------------------------------------------- definitions

    @Test fun `a REST query definition cannot hold an absolute URL, a traversal or an undeclared parameter`() {
        for (path in listOf("http://evil.example/x", "//evil.example/x", "/a/../b", "/a/./b", "orders", "/a b", "/a?x=1", "/a#f", "/{undeclared}", "/a/{id", "/a/%2e%2e/b"))
            assertThatThrownBy { RestQueryDefinition("q", tenant, dsId, path) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RestQueryDefinition("q", tenant, dsId, "/a", queryParams = mapOf("nope" to "k")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RestQueryDefinition("bad id", tenant, dsId, "/a") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RestQueryDefinition("q", tenant, dsId, "/a", rowsPointer = "data") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---------------------------------------------------------------- discovery

    @Test fun `discovery reports the shape of declared endpoints only, with fixed-text warnings`() {
        val hidden = RestQueryDefinition("hidden", tenant, dsId, "/secret", discoverable = false)
        val needsId = orders.copy(discoverable = true)
        val failing = RestQueryDefinition("failing", tenant, dsId, "/boom", discoverable = true)
        val t = FakeTransport { ok("""{"data":[{"id":1,"name":"a"},{"id":2,"name":null}]}""") }
        val transport = object : RestTransport {
            override fun get(request: HttpGetRequest): HttpGetResponse = if (request.url.rawPath.endsWith("/boom")) HttpGetResponse(500, null, ByteArray(0)) else t.get(request)
        }
        val schema = RestConnector(InMemoryQueryCatalog(list, hidden, needsId, failing), transport).discovery().discover(ref(), cred)
        assertThat(schema.entities.map { it.name }).containsExactly("list")
        val fields = schema.entities.single().fields.associateBy { it.name }
        assertThat(fields["id"]!!.type).isEqualTo(NormalizedType.INTEGER); assertThat(fields["id"]!!.nullable).isFalse()
        assertThat(fields["name"]!!.nullable).isTrue()
        assertThat(schema.warnings.sorted()).containsExactly("failing: ${FailureCodes.UPSTREAM_STATUS}", "orders: needs parameters, skipped")
        assertThat(schema.toString()).doesNotContain("TOPSECRET")
        assertThat(t.requests.none { it.url.rawPath.endsWith("/secret") }).isTrue()
    }

    // ---------------------------------------------------------------- end to end, real TLS and the real transport

    @Test fun `end to end over TLS with the pinned transport`() {
        TlsStub(TlsStub.json("""{"data":[{"id":1,"name":"widget"}]}""")).use { stub ->
            val resolver = FixedResolver(mapOf(TlsStub.HOST to listOf("127.0.0.1")))
            val cfg = mapOf("baseUrl" to "https://${TlsStub.HOST}:${stub.port}/v1", "authHeader" to "X-Api-Key")
            val c = RestConnector(InMemoryQueryCatalog(list), PinnedHttpsTransport(resolver, AllowAllAddresses, stub.clientContext))
            val r = c.executor().execute(req("list"), ref(cfg), ResolvedCredential.of(mapOf("authValue" to "k-123456")))
            assertThat(r.rows.size).isEqualTo(1)
            assertThat(stub.heads.single()).startsWith("GET /v1/items HTTP/1.1")
            assertThat(stub.heads.single()).contains("X-Api-Key: k-123456")
            assertThat(c.test(ref(cfg), ResolvedCredential.of(mapOf("authValue" to "k-123456")))).isInstanceOf(ConnectionTestResult.Ok::class.java)
        }
    }

    @Test fun `with the default policy a data source whose name resolves into the private network is refused end to end`() {
        TlsStub(TlsStub.json("{}")).use { stub ->
            for (ip in listOf("127.0.0.1", "10.0.0.5", "169.254.169.254")) {
                val c = RestConnector(InMemoryQueryCatalog(list), PinnedHttpsTransport(FixedResolver(mapOf(TlsStub.HOST to listOf(ip))), sslContext = stub.clientContext))
                val cfg = mapOf("baseUrl" to "https://${TlsStub.HOST}:${stub.port}")
                assertThat((c.test(ref(cfg), ResolvedCredential.NONE) as ConnectionTestResult.Failed).code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
                assertThat(failure { c.executor().execute(req("list"), ref(cfg), ResolvedCredential.NONE) }.code).isEqualTo(FailureCodes.ADDRESS_BLOCKED)
            }
            assertThat(stub.connections.get()).isEqualTo(0)
        }
    }
}
