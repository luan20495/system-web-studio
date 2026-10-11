package com.systemwebstudio.tenancy

import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.springframework.test.web.servlet.MvcResult
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Helpers of the C1 security id / permission / disclosure matrix. The central one is [likeMissing]: the same request is sent with the id under test and with a
 * random UUID, and both answers must be identical (status, code, message, details; only the per-request `requestId` is removed), so a caller cannot tell a
 * record that exists elsewhere from one that does not exist at all.
 */
abstract class SecurityMatrixSupport : FinalIamTestBase() {
    /** what a caller can compare between two answers: status and the error body without its per-request requestId */
    protected data class Answer(val status: Int, val code: String?, val body: String)

    protected fun answer(r: MvcResult): Answer {
        val raw = r.response.contentAsString
        if (raw.isBlank()) return Answer(r.response.status, null, "")
        val n = json.readTree(raw)
        (n as? ObjectNode)?.remove("requestId")
        return Answer(r.response.status, n.get("code")?.asString(), n.toString())
    }

    /** [call] with [foreign] and with a fresh random UUID: exact [status] + [code], and the two answers byte-identical (no existence oracle) */
    protected fun likeMissing(what: String, status: Int, code: String, foreign: Any, call: (String) -> MvcResult) {
        val f = answer(call(foreign.toString()))
        val m = answer(call(UUID.randomUUID().toString()))
        assertThat(f.status).describedAs("$what: status (body ${f.body})").isEqualTo(status)
        assertThat(f.code).describedAs("$what: code").isEqualTo(code)
        assertThat(f).describedAs("$what: the real foreign id is answered exactly like a random one").isEqualTo(m)
    }

    protected fun expect(r: MvcResult, status: Int, code: String?, what: String) {
        val a = answer(r)
        assertThat(a.status).describedAs("$what (body ${a.body})").isEqualTo(status)
        if (code != null) assertThat(a.code).describedAs("$what: code").isEqualTo(code)
    }

    protected fun forbidden(r: MvcResult, what: String, code: String = "FORBIDDEN") = expect(r, 403, code, what)

    /** a plain account that is a member of workspace [ws] with [wsRole] (the V26 trigger makes it a tenant MEMBER), optionally with [projectRole] on [p] */
    protected fun memberOf(ws: UUID, wsRole: String, p: com.systemwebstudio.project.ProjectEntity? = null, projectRole: String? = null, prefix: String = "sm"): UserEntity {
        val u = fx.user(prefix); fx.member(ws, u, wsRole)
        if (p != null && projectRole != null) fx.projectRole(p, u, projectRole)
        return u
    }

    protected fun loginAs(u: UserEntity): ApiSession = sessionFor(u.username)
    protected fun activated(created: tools.jackson.databind.JsonNode): ApiSession =
        activateAndLogin(created.get("employee").get("username").asString(), created.get("activation").get("token").asString())
    protected fun status(c: Company) = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String::class.java, c.id)
    protected fun setStatus(sys: ApiSession, c: Company, s: String) =
        assertThat(sys.patch("${base(c)}/status", """{"status":"$s"}""").response.status).describedAs("status -> $s").isEqualTo(200)
}
