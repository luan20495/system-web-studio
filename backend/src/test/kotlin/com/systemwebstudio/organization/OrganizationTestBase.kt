package com.systemwebstudio.organization

import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Fixtures of the organization contract tests: real PostgreSQL for everything C1 owns (accounts, tenants, workspaces, permissions, audit), real HTTP, real activation.
 * A "company" is made exactly like production: a SYSTEM_ADMIN creates it TOGETHER with its first Tenant Admin (one request), the admin opens the one-time link,
 * chooses a password and signs in. The organization store behind the seams is the in-memory test double (see InMemoryOrganization.kt) unless a test says otherwise.
 */
abstract class OrganizationTestBase : IntegrationTestBase() {
    protected val PASSWORD = "Org-Pass-2026-x"
    private var ipCounter = 0

    protected class Company(val id: UUID, val slug: String, val adminId: UUID, val adminName: String, val admin: ApiSession)

    protected fun uname(p: String) = p + "-" + UUID.randomUUID().toString().take(8)
    protected fun sysAdmin(): ApiSession = sessionFor(fx.user("org-sys", systemAdmin = true).username)
    protected fun code(r: MvcResult, s: ApiSession): String = s.body(r).get("code").asString()

    /** the activation endpoint is limited per IP (30 / 10 min) and MockMvc shares one address: every call comes from a fresh one */
    protected fun fromNewAddress(path: String, body: String): MvcResult = session().perform(
        MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body)
            .with { r -> r.remoteAddr = "10.88.${(++ipCounter / 250) % 250}.${ipCounter % 250}"; r })

    protected fun activateAndLogin(username: String, token: String): ApiSession {
        assertThat(fromNewAddress("/api/v1/auth/activation/complete", """{"token":"$token","password":"$PASSWORD"}""").response.status).describedAs("activation of $username").isEqualTo(200)
        val s = session(); assertThat(s.login(username, PASSWORD).response.status).describedAs("login of $username").isEqualTo(200)
        return s
    }

    protected fun company(sys: ApiSession = sysAdmin()): Company {
        val slug = "org-" + UUID.randomUUID().toString().take(8); val adminName = uname("ta")
        val r = sys.post("/api/v1/admin/tenants", """{"slug":"$slug","name":"Company $slug","firstAdmin":{"username":"$adminName","displayName":"Admin $adminName"}}""")
        assertThat(r.response.status).isEqualTo(201)
        val b = sys.body(r)
        val admin = activateAndLogin(adminName, b.get("firstAdmin").get("token").asString())
        return Company(UUID.fromString(b.get("id").asString()), slug, UUID.fromString(b.get("firstAdmin").get("userId").asString()), adminName, admin)
    }

    protected fun base(c: Company) = "/api/v1/admin/tenants/${c.id}"
    protected fun types(c: Company) = "${base(c)}/organization-unit-types"
    protected fun units(c: Company) = "${base(c)}/organization-units"
    protected fun employees(c: Company) = "${base(c)}/employees"
    protected fun positions(c: Company) = "${base(c)}/positions"
    protected fun grades(c: Company) = "${base(c)}/grades"

    protected fun id(n: JsonNode) = UUID.fromString(n.get("id").asString())
    protected fun ver(n: JsonNode) = n.get("version").asLong()
    protected fun ids(a: List<JsonNode>) = a.map { it.get("id").asString() }

    protected fun type(c: Company, code: String, name: String = code.uppercase(), rules: String? = null, session: ApiSession = c.admin): JsonNode {
        val r = session.post(types(c), """{"code":"$code","name":"$name"${if (rules != null) ""","rules":$rules""" else ""}}""")
        assertThat(r.response.status).describedAs("type $code").isEqualTo(201)
        return session.body(r)
    }
    protected fun unit(c: Company, type: JsonNode, code: String, parent: JsonNode? = null, name: String = code, sort: Int = 0): JsonNode {
        val r = c.admin.post(units(c), """{"typeId":"${id(type)}","code":"$code","name":"$name","sortOrder":$sort${if (parent != null) ""","parentId":"${id(parent)}"""" else ""}}""")
        assertThat(r.response.status).describedAs("unit $code").isEqualTo(201)
        return c.admin.body(r)
    }
    protected fun unitNow(c: Company, u: JsonNode): JsonNode { val r = c.admin.get("${units(c)}/${id(u)}"); assertThat(r.response.status).isEqualTo(200); return c.admin.body(r).get("unit") }
    protected fun move(c: Company, u: JsonNode, parent: JsonNode?, version: Long = ver(u)) =
        c.admin.post("${units(c)}/${id(u)}/move", """{"newParentId":${if (parent == null) "null" else "\"${id(parent)}\""},"expectedVersion":$version}""")
    protected fun flat(c: Company, q: String = "") = c.admin.body(c.admin.get("${units(c)}?format=flat$q")).toList()

    protected fun newEmployee(c: Company, username: String = uname("emp"), extra: String = "", session: ApiSession = c.admin): JsonNode {
        val r = session.post(employees(c), """{"username":"$username","displayName":"Employee $username"$extra}""")
        assertThat(r.response.status).describedAs("employee $username").isEqualTo(201)
        return session.body(r)
    }
    protected fun employee(c: Company, userId: UUID): JsonNode { val r = c.admin.get("${employees(c)}/$userId"); assertThat(r.response.status).isEqualTo(200); return c.admin.body(r) }
    protected fun uid(created: JsonNode) = UUID.fromString(created.get("employee").get("userId").asString())

    protected fun auditCount(action: String, resourceId: UUID) =
        jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND resource_id = ?", Long::class.java, action, resourceId.toString())!!
}
