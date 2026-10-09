package com.systemwebstudio.organization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.util.UUID

/** C1 · company bootstrap: SYSTEM_ADMIN -> one request -> company + first Tenant Admin (pending) + membership + activation link, atomically (real PostgreSQL). */
@Import(InMemoryOrganizationConfig::class)
// this context carries the in-memory organization store and its own deployment listeners / recovery sweeper on the SHARED RabbitMQ and database: it is closed after the class so it never competes
// with the contexts of the publish tests that run later (the full suite failed DeploymentFailureRecoveryTests only while these contexts stayed alive)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CompanyBootstrapTests : OrganizationTestBase() {
    private fun tenantExists(slug: String) = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE slug = ?", Long::class.java, slug)!! > 0
    private fun userExists(name: String) = jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Long::class.java, name)!! > 0

    @Test
    fun `one request creates the company and its first Tenant Admin, who activates, signs in and holds the company capabilities`() {
        val sys = sysAdmin(); val slug = "boot-" + UUID.randomUUID().toString().take(8); val admin = uname("first")
        val r = sys.post("/api/v1/admin/tenants", """{"slug":"$slug","name":"Boot Co","firstAdmin":{"username":"$admin","displayName":"First Admin","email":"${admin}@example.com"}}""")
        assertThat(r.response.status).isEqualTo(201)
        val b = sys.body(r); val tenantId = UUID.fromString(b.get("id").asString())
        val link = b.get("firstAdmin")
        assertThat(link.get("purpose").asString()).isEqualTo("ACTIVATION"); assertThat(link.get("username").asString()).isEqualTo(admin)
        assertThat(link.has("password")).describedAs("no password in the API").isFalse()
        val row = jdbc.queryForMap("SELECT enabled, system_admin, activated_at FROM users WHERE username = ?", admin)
        assertThat(row["enabled"]).isEqualTo(true); assertThat(row["system_admin"]).isEqualTo(false); assertThat(row["activated_at"]).describedAs("pending").isNull()
        assertThat(jdbc.queryForObject("SELECT role FROM tenant_members WHERE tenant_id = ? AND user_id = ? AND active", String::class.java, tenantId, UUID.fromString(link.get("userId").asString()))).isEqualTo("TENANT_ADMIN")
        assertThat(session().login(admin, PASSWORD).response.status).describedAs("cannot sign in before activation").isEqualTo(401)

        val ta = activateAndLogin(admin, link.get("token").asString())
        val me = ta.body(ta.get("/api/v1/auth/me"))
        assertThat(me.get("systemAdmin").asBoolean()).isFalse(); assertThat(me.get("tenantRole").asString()).isEqualTo("TENANT_ADMIN"); assertThat(me.get("tenantId").asString()).isEqualTo(tenantId.toString())
        assertThat(me.get("permissions").toList().map { it.asString() })
            .containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS", "ORG_STRUCTURE_VIEW", "ORG_STRUCTURE_MANAGE", "EMPLOYEE_VIEW", "EMPLOYEE_MANAGE", "POSITION_GRADE_VIEW", "POSITION_GRADE_MANAGE")
        assertThat(me.get("workspaces").size()).describedAs("a Tenant Admin is no workspace member: no business permission").isZero()
        // it can use the organization API of ITS company at once
        assertThat(ta.get("/api/v1/admin/tenants/$tenantId/organization-units").response.status).isEqualTo(200)
        assertThat(ta.get("/api/v1/admin/tenants/$tenantId/employees").response.status).isEqualTo(200)
    }

    @Test
    fun `a failure after the tenant row exists rolls EVERYTHING back - no tenant without its admin, no orphan account, no audit row`() {
        val sys = sysAdmin(); val taken = uname("taken"); val first = company(sys)            // a username that already exists
        val slug = "boot-" + UUID.randomUUID().toString().take(8)
        val users = jdbc.queryForObject("SELECT count(*) FROM users", Long::class.java)!!; val tenants = jdbc.queryForObject("SELECT count(*) FROM tenants", Long::class.java)!!
        val audits = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action IN ('TENANT_CREATED','USER_CREATED','TENANT_MEMBER_SET')", Long::class.java)!!
        val dup = sys.post("/api/v1/admin/tenants", """{"slug":"$slug","name":"Doomed","firstAdmin":{"username":"${first.adminName}","displayName":"Dup"}}""")
        assertThat(dup.response.status).isEqualTo(409); assertThat(code(dup, sys)).isEqualTo("USERNAME_TAKEN")
        assertThat(tenantExists(slug)).describedAs("the tenant inserted first is gone").isFalse()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants", Long::class.java)).isEqualTo(tenants)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long::class.java)).isEqualTo(users)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action IN ('TENANT_CREATED','USER_CREATED','TENANT_MEMBER_SET')", Long::class.java)).describedAs("audit rolls back with it").isEqualTo(audits)
        // the same for an invalid e-mail and for a missing display name, after the tenant was created
        for ((label, admin) in mapOf("bad e-mail" to """{"username":"$taken","displayName":"X","email":"nope"}""", "no display name" to """{"username":"$taken"}""")) {
            val s2 = "boot-" + UUID.randomUUID().toString().take(8)
            val r = sys.post("/api/v1/admin/tenants", """{"slug":"$s2","name":"Doomed 2","firstAdmin":$admin}""")
            assertThat(r.response.status).describedAs(label).isEqualTo(400)
            assertThat(tenantExists(s2)).describedAs("$label: no tenant").isFalse(); assertThat(userExists(taken)).describedAs("$label: no account").isFalse()
        }
        // a taken slug fails before anything is created
        val slugDup = sys.post("/api/v1/admin/tenants", """{"slug":"${first.slug}","name":"Same slug","firstAdmin":{"username":"${uname("n")}","displayName":"N"}}""")
        assertThat(slugDup.response.status).isEqualTo(409); assertThat(code(slugDup, sys)).isEqualTo("TENANT_SLUG_TAKEN")
    }

    @Test
    fun `only a SYSTEM_ADMIN can bootstrap a company, SYSTEM_ADMIN gains no tenant business access, and the existing firstAdminUserId route is unchanged`() {
        val sys = sysAdmin(); val c = company(sys)
        // a Tenant Admin cannot create companies
        assertThat(c.admin.post("/api/v1/admin/tenants", """{"slug":"${uname("x")}","name":"No","firstAdmin":{"username":"${uname("y")}","displayName":"Y"}}""").response.status).isEqualTo(403)
        // the operator that created it holds no organization / employee capability in it
        for (path in listOf(units(c), types(c), employees(c))) assertThat(sys.get(path).response.status).describedAs("SYSTEM_ADMIN GET $path").isEqualTo(403)
        assertThat(sys.post(units(c), """{"typeId":"${UUID.randomUUID()}","code":"X","name":"X"}""").response.status).isEqualTo(403)
        // firstAdmin and firstAdminUserId together are refused (nothing is created)
        val slug = uname("both")
        assertThat(sys.post("/api/v1/admin/tenants", """{"slug":"$slug","name":"B","firstAdminUserId":"${c.adminId}","firstAdmin":{"username":"${uname("z")}","displayName":"Z"}}""").response.status).isEqualTo(400)
        assertThat(tenantExists(slug)).isFalse()
        // the previous way (an existing account) still works and answers the same shape without an activation link
        val legacy = sys.post("/api/v1/admin/tenants", """{"slug":"${uname("legacy")}","name":"Legacy","firstAdminUserId":"${c.adminId}"}""")
        assertThat(legacy.response.status).isEqualTo(201); val fa = sys.body(legacy).get("firstAdmin"); assertThat(fa == null || fa.isNull).isTrue()
    }
}
