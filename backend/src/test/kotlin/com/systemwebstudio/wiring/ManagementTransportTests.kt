package com.systemwebstudio.wiring

import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MvcResult
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.json.JsonMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper

/**
 * C0 transport of the Management API (docs/contracts/v2/management-api.md section 1, 4, D-C0-28): ONE error envelope for every non-2xx answer, the body-size
 * limit (413), and the route table that is really mounted by Spring (exactly the approved routes; no raw mutation route, no project-scoped definition route).
 */
@ExtendWith(OutputCaptureExtension::class)
@Import(ManagementTestBeans::class)
class ManagementTransportTests : ManagementApiTestBase() {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") lateinit var mappings: RequestMappingHandlerMapping
    @Autowired lateinit var mapper: JsonMapper

    private val keys = setOf("code", "message", "requestId", "retryable", "details")

    /** `{code,message,requestId,retryable,details}` and nothing else; the request id is the one of the response header */
    private fun assertEnvelope(s: ApiSession, r: MvcResult, status: Int, code: String? = null) {
        val body = s.body(r)
        assertThat(r.response.status).describedAs(r.response.contentAsString).isEqualTo(status)
        assertThat(body.propertyNames().asSequence().toSet()).describedAs(r.response.contentAsString).isEqualTo(keys)
        assertThat(body.get("message").asString()).isNotBlank()
        assertThat(body.get("retryable").isBoolean).isTrue()
        assertThat(body.get("details").isObject).isTrue()
        assertThat(body.get("requestId").asString()).isNotBlank().isEqualTo(r.response.getHeader("X-Request-Id"))
        if (code != null) assertThat(body.get("code").asString()).isEqualTo(code)
    }

    // ------------------------------------------------------------------------------------------------ the envelope

    @Test
    fun `access, validation and framework errors all use the frozen envelope`() {
        val a = admin(); val other = admin()
        // C1 access proof: a workspace of another tenant is the same 404 as an unknown one
        assertEnvelope(a.s, a.s.get("/api/v1/workspaces/${other.ws}/data-sources"), 404)
        // a member without the permission: 403
        val (_, editor) = member(a.ws, "EDITOR")
        assertEnvelope(editor, editor.post(a.base, """{"name":"${uniq("x")}","type":"fake","config":{}}"""), 403)
        // C3 fixed-text failures
        // a data source id that cannot be a UUID is the same 404 as an unknown one (C3, D-C0-30); a bad workspace / project path value is a 400
        assertEnvelope(a.s, a.s.get("${a.base}/not-a-uuid"), 404, "NOT_FOUND")
        assertEnvelope(a.s, a.s.post(a.base, """{"name":"${uniq("x")}","type":"fake","config":{},"tenantId":"${a.tenant}"}"""), 400, "INVALID_PARAMS")
        assertEnvelope(a.s, a.s.post(a.base, """{"name":"${uniq("x")}","type":"no-such-type","config":{}}"""), 422, "UNSUPPORTED_TYPE")
        assertEnvelope(a.s, a.s.get("${a.base}/${java.util.UUID.randomUUID()}"), 404, "NOT_FOUND")
        // framework: malformed JSON, a path id that is not a UUID, wrong method
        assertEnvelope(a.s, a.s.post(a.base, "{not json"), 400, "INVALID_PARAMS")
        assertEnvelope(a.s, a.s.get("/api/v1/workspaces/not-a-uuid/data-sources"), 400, "INVALID_PARAMS")
        assertEnvelope(a.s, a.s.put(a.base, "{}"), 405, "METHOD_NOT_ALLOWED")
        // a conflict
        val name = uniq("dup"); a.create(name = name)
        assertEnvelope(a.s, a.create(name = name), 409, "CONFLICT")
    }

    @Test
    fun `retryable is true only for a throttled or timed out call`() {
        val a = admin()
        val ids = (1..70).map { a.create(name = uniq("t")) }      // 60 changing calls per tenant per minute
        val limited = ids.firstOrNull { it.response.status == 429 } ?: a.create(name = uniq("t"))
        assertEnvelope(a.s, limited, 429, "RATE_LIMITED")
        assertThat(a.s.body(limited).get("retryable").asBoolean()).isTrue()
        assertThat(limited.response.getHeader("Retry-After")).isNotBlank()
        assertThat(a.s.body(a.s.get("${a.base}/${java.util.UUID.randomUUID()}")).get("retryable").asBoolean()).isFalse()
    }

    // ------------------------------------------------------------------------------------------------ the body limit

    @Test
    fun `a valid body is accepted and an oversized one is a 413 with the envelope, no data change and nothing in the log`(out: CapturedOutput) {
        val a = admin()
        val ok = a.create(credential = mapOf("username" to "u", "password" to secret))
        assertThat(ok.response.status).describedAs(ok.response.contentAsString).isEqualTo(201)

        val id = a.s.body(ok).get("id").asString()
        val rows = count("SELECT count(*) FROM data_sources")
        val pad = "p".repeat(70_000)
        val big = a.s.put("${a.base}/$id/credential", """{"credential":{"username":"u","password":"$secret"},"pad":"$pad"}""")
        assertEnvelope(a.s, big, 413, "PAYLOAD_TOO_LARGE")
        assertThat(big.response.contentAsString).doesNotContain(secret).doesNotContain(pad.take(50))
        val bigCreate = a.s.post(a.base, """{"name":"${uniq("big")}","type":"fake","config":{"pad":"$pad"},"credential":{"password":"$secret"}}""")
        assertEnvelope(a.s, bigCreate, 413, "PAYLOAD_TOO_LARGE")
        assertThat(count("SELECT count(*) FROM data_sources")).describedAs("an oversized create changed nothing").isEqualTo(rows)
        assertThat(count("SELECT count(*) FROM audit_events WHERE new_value::text LIKE ?", "%$pad%")).isZero()
        assertThat(out.all).describedAs("no body, secret or padding in the log").doesNotContain(secret).doesNotContain(pad.take(50))
    }

    @Test
    fun `the limit is enforced on the bytes actually sent, not on the declared length`() {
        val filter = ManagementBodyLimitFilter(1024, mapper)
        fun run(size: Int, declared: Long?): Pair<MockHttpServletResponse, Boolean> {
            val raw = MockHttpServletRequest("PUT", "/api/v1/workspaces/${java.util.UUID.randomUUID()}/data-sources/${java.util.UUID.randomUUID()}/credential")
            raw.requestURI = raw.requestURI; raw.setContent(ByteArray(size) { 'a'.code.toByte() })
            val req: HttpServletRequest = if (declared == null) raw else object : HttpServletRequestWrapper(raw) { override fun getContentLengthLong() = declared }
            val res = MockHttpServletResponse(); val chain = MockFilterChain()
            filter.doFilter(req, res, chain)
            return res to (chain.request != null)
        }
        assertThat(run(1024, null).second).describedAs("exactly the limit passes").isTrue()
        assertThat(run(1025, null).let { it.first.status to it.second }).describedAs("one byte over").isEqualTo(413 to false)
        assertThat(run(1025, -1L).let { it.first.status to it.second }).describedAs("length not declared (chunked)").isEqualTo(413 to false)
        assertThat(run(5000, 10L).let { it.first.status to it.second }).describedAs("a lie about the length").isEqualTo(413 to false)
        assertThat(run(1_000_000, 1_000_000L).first.status).isEqualTo(413)
        val tooLarge = mapper.readTree(run(2000, null).first.contentAsString)
        assertThat(tooLarge.propertyNames().asSequence().toSet()).isEqualTo(keys)
        assertThat(tooLarge.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE")
        // a GET and a route outside the Management API are never touched
        val other = MockHttpServletRequest("POST", "/api/v1/auth/login"); other.setContent(ByteArray(5000))
        val chain = MockFilterChain(); filter.doFilter(other, MockHttpServletResponse(), chain)
        assertThat(chain.request).isNotNull()
    }

    // ------------------------------------------------------------------------------------------------ the mounted route table

    private val approved = setOf(
        "GET /api/v1/workspaces/{workspaceId}/data-sources/connectors", "GET /api/v1/workspaces/{workspaceId}/data-sources", "POST /api/v1/workspaces/{workspaceId}/data-sources",
        "GET /api/v1/workspaces/{workspaceId}/data-sources/{id}", "PATCH /api/v1/workspaces/{workspaceId}/data-sources/{id}", "DELETE /api/v1/workspaces/{workspaceId}/data-sources/{id}",
        "GET /api/v1/workspaces/{workspaceId}/data-sources/{id}/credential", "PUT /api/v1/workspaces/{workspaceId}/data-sources/{id}/credential",
        "DELETE /api/v1/workspaces/{workspaceId}/data-sources/{id}/credential", "POST /api/v1/workspaces/{workspaceId}/data-sources/{id}/test",
        "POST /api/v1/workspaces/{workspaceId}/data-sources/{id}/schema/discover", "GET /api/v1/workspaces/{workspaceId}/data-sources/{id}/schema",
        "GET /api/v1/workspaces/{workspaceId}/data-sources/{id}/queries", "POST /api/v1/workspaces/{workspaceId}/data-sources/{id}/queries",
        "GET /api/v1/workspaces/{workspaceId}/data-sources/{id}/queries/{queryId}", "PATCH /api/v1/workspaces/{workspaceId}/data-sources/{id}/queries/{queryId}",
        "DELETE /api/v1/workspaces/{workspaceId}/data-sources/{id}/queries/{queryId}",
        "GET /api/v1/workspaces/{workspaceId}/data-sources/{id}/mutations", "POST /api/v1/workspaces/{workspaceId}/data-sources/{id}/mutations",
        "GET /api/v1/workspaces/{workspaceId}/data-sources/{id}/mutations/{mutationId}", "PATCH /api/v1/workspaces/{workspaceId}/data-sources/{id}/mutations/{mutationId}",
        "DELETE /api/v1/workspaces/{workspaceId}/data-sources/{id}/mutations/{mutationId}",
        "GET /api/v1/workspaces/{workspaceId}/projects/{projectId}/data-bindings", "PUT /api/v1/workspaces/{workspaceId}/projects/{projectId}/data-bindings/{mode}/{slotId}",
        "DELETE /api/v1/workspaces/{workspaceId}/projects/{projectId}/data-bindings/{mode}/{slotId}"
    )

    private fun mounted(): Set<String> = mappings.handlerMethods.keys.flatMap { info ->
        val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
        val methods = info.methodsCondition.methods.map { it.name }
        patterns.flatMap { p -> methods.map { "$it $p" } }
    }.toSet()

    @Test
    fun `Spring mounts exactly the approved Management routes and nothing else`() {
        val all = mounted()
        val management = all.filter { Regex("^[A-Z]+ /api/v1/workspaces/\\{workspaceId\\}/(data-sources|projects/\\{projectId\\}/data-(bindings|queries|mutations))").containsMatchIn(it) }.toSet()
        assertThat(management).describedAs("the mounted Management routes").isEqualTo(approved)
        // contract section 3.5: no project-scoped definition route; section 1.10: no raw mutation route
        assertThat(all.filter { "data-queries" in it || "data-mutations" in it }).isEmpty()
        assertThat(all.filter { it.contains("/api/v1/data/") || it.endsWith("/api/v1/data") || "/mutate" in it }).describedAs("no raw data route").isEmpty()
    }

    @Test
    fun `the raw mutation route does not exist - 404 for a signed in administrator, not 403 or 405`() {
        val a = admin()
        for (path in listOf("/api/v1/data/mutate", "/api/v1/data/query", "/api/v1/workspaces/${a.ws}/data/mutate", "/api/v1/workspaces/${a.ws}/projects/${java.util.UUID.randomUUID()}/data-queries")) {
            val r = a.s.post(path, """{"dataSourceId":"${java.util.UUID.randomUUID()}","operation":"x","params":{}}""")
            assertThat(r.response.status).describedAs("POST $path").isEqualTo(404)
        }
        assertThat(a.s.get("/api/v1/data/mutate").response.status).isEqualTo(404)
    }

    @Test
    fun `every approved route reaches a controller - a signed in administrator never gets a framework 404 or 405 on them`() {
        val a = admin(); val id = a.created(); val project = scenario()
        val ws = a.ws; val base = a.base
        val calls = listOf(
            a.s.get("$base/connectors"), a.s.get(base), a.s.get("$base/$id"), a.s.get("$base/$id/credential"), a.s.get("$base/$id/schema"),
            a.s.get("$base/$id/queries"), a.s.get("$base/$id/queries/q1"), a.s.get("$base/$id/mutations"), a.s.get("$base/$id/mutations/m1"),
            a.s.post("$base/$id/test", "{}"), a.s.post("$base/$id/queries", "{}"), a.s.post("$base/$id/mutations", "{}"),
            a.s.patch("$base/$id/queries/q1", "{}"), a.s.patch("$base/$id/mutations/m1", "{}"), a.s.delete("$base/$id/queries/q1"), a.s.delete("$base/$id/mutations/m1"),
            a.s.get("/api/v1/workspaces/$ws/projects/${project.projectId}/data-bindings")
        )
        for (r in calls) assertThat(r.response.status).describedAs("${r.request.method} ${r.request.requestURI}").isNotIn(405, 415, 500)
        // the answers that exist for a missing definition are the contract's, with the envelope
        assertEnvelope(a.s, a.s.get("$base/$id/queries/q1"), 404, "NOT_FOUND")
    }
}
