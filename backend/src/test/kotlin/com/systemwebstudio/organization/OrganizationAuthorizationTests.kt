package com.systemwebstudio.organization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import java.util.UUID

/**
 * C1 SECURITY TESTS (H-C1-17 section 24): authentication, tenant scoping, permission semantics and privilege boundaries of the organization / employee / position APIs,
 * independent of C3's physical persistence (in-memory test double behind the seams; real PostgreSQL for accounts, tenants, permissions and audit).
 */
@Import(InMemoryOrganizationConfig::class)
class OrganizationAuthorizationTests : OrganizationTestBase() {
    private fun routes(c: Company, unit: UUID, type: UUID, user: UUID, pos: UUID) = listOf(
        "GET" to units(c), "GET" to "${units(c)}/$unit", "GET" to types(c), "GET" to "${types(c)}/$type", "GET" to employees(c), "GET" to "${employees(c)}/$user",
        "GET" to "${employees(c)}/$user/organization-memberships", "GET" to "${employees(c)}/$user/positions", "GET" to positions(c), "GET" to "${positions(c)}/$pos", "GET" to grades(c),
        "POST" to units(c), "PATCH" to "${units(c)}/$unit", "POST" to "${units(c)}/$unit/move", "POST" to "${units(c)}/$unit/archive", "POST" to "${units(c)}/$unit/restore",
        "POST" to types(c), "PATCH" to "${types(c)}/$type", "POST" to "${types(c)}/$type/disable", "POST" to "${types(c)}/$type/enable",
        "POST" to employees(c), "POST" to "${employees(c)}/$user/disable", "POST" to "${employees(c)}/$user/enable",
        "POST" to "${employees(c)}/$user/organization-memberships", "PATCH" to "${employees(c)}/$user/organization-memberships/${UUID.randomUUID()}", "DELETE" to "${employees(c)}/$user/organization-memberships/${UUID.randomUUID()}?expectedVersion=0",
        "POST" to "${employees(c)}/$user/positions", "PATCH" to "${employees(c)}/$user/positions/${UUID.randomUUID()}", "DELETE" to "${employees(c)}/$user/positions/${UUID.randomUUID()}?expectedVersion=0",
        "POST" to positions(c), "PATCH" to "${positions(c)}/$pos", "POST" to "${positions(c)}/$pos/disable", "POST" to grades(c), "POST" to "${grades(c)}/${UUID.randomUUID()}/enable")

    private fun call(s: com.systemwebstudio.support.ApiSession, method: String, path: String): Int = when (method) {
        "GET" -> s.get(path).response.status; "DELETE" -> s.delete(path).response.status; "PATCH" -> s.patch(path, "{}").response.status; else -> s.post(path, "{}").response.status }

    @Test
    fun `1 and 2 and 3 unauthenticated is 401 on every route, the Tenant Admin manages its own company, a same-company member without the capability is 403`() {
        val sys = sysAdmin(); val c = company(sys); val t = type(c, "unit"); val u = unit(c, t, "A"); val e = newEmployee(c); val pos = c.admin.body(c.admin.post(positions(c), """{"code":"P","name":"P"}"""))
        for ((m, p) in routes(c, id(u), id(t), uid(e), id(pos))) assertThat(call(session(), m, p)).describedAs("anonymous $m $p").isEqualTo(401)
        // the Tenant Admin of THIS company: every capability, every route answers something other than 401 / 403 / 404-of-the-tenant
        assertThat(c.admin.get(units(c)).response.status).isEqualTo(200); assertThat(c.admin.get(employees(c)).response.status).isEqualTo(200); assertThat(c.admin.get(positions(c)).response.status).isEqualTo(200)
        assertThat(c.admin.post(units(c), """{"typeId":"${id(t)}","code":"B","name":"b"}""").response.status).isEqualTo(201)
        // a plain member of the same company
        val plain = newEmployee(c); val ps = activateAndLogin(plain.get("employee").get("username").asString(), plain.get("activation").get("token").asString())
        for ((m, p) in routes(c, id(u), id(t), uid(e), id(pos))) assertThat(call(ps, m, p)).describedAs("plain member $m $p").isEqualTo(403)
        assertThat(flat(c).map { it.get("code").asString() }).containsExactlyInAnyOrder("A", "B")
    }

    @Test
    fun `4 and 5 and 6 and 7 a foreign company is a safe 404 on every route - guessed node, cross-tenant parent, cross-tenant employee assignment`() {
        val a = company(); val b = company(); val ta = type(a, "unit"); val tb = type(b, "unit"); val ua = unit(a, ta, "A1"); val ub = unit(b, tb, "B1")
        val eb = newEmployee(b); val ea = newEmployee(a); val pb = b.admin.body(b.admin.post(positions(b), """{"code":"P","name":"P"}"""))
        // company A's admin on company B's path: 404 for EVERY route, before any resource is looked at
        for ((m, p) in routes(b, id(ub), id(tb), uid(eb), id(pb))) assertThat(call(a.admin, m, p)).describedAs("A on B: $m $p").isEqualTo(404)
        assertThat(code(a.admin.get(units(b)), a.admin)).isEqualTo("TENANT_NOT_FOUND")
        // on its OWN path, B's ids are simply unknown
        assertThat(a.admin.get("${units(a)}/${id(ub)}").response.status).isEqualTo(404); assertThat(a.admin.get("${types(a)}/${id(tb)}").response.status).isEqualTo(404)
        assertThat(a.admin.get("${employees(a)}/${uid(eb)}").response.status).isEqualTo(404); assertThat(a.admin.get("${positions(a)}/${id(pb)}").response.status).isEqualTo(404)
        val foreignParent = a.admin.post(units(a), """{"typeId":"${id(ta)}","code":"X","name":"x","parentId":"${id(ub)}"}"""); assertThat(foreignParent.response.status).isEqualTo(404)
        assertThat(move(a, ua, ub).response.status).isEqualTo(404)
        // cross-tenant employee assignment: B's employee into A's unit, A's employee into B's unit, A's unit into B's employee
        assertThat(a.admin.post("${employees(a)}/${uid(eb)}/organization-memberships", """{"organizationUnitId":"${id(ua)}"}""").response.status).isEqualTo(404)
        val foreignUnit = a.admin.post("${employees(a)}/${uid(ea)}/organization-memberships", """{"organizationUnitId":"${id(ub)}"}"""); assertThat(foreignUnit.response.status).isEqualTo(404); assertThat(code(foreignUnit, a.admin)).isEqualTo("ORG_UNIT_NOT_FOUND")
        val ma = a.admin.body(a.admin.post("${employees(a)}/${uid(ea)}/organization-memberships", """{"organizationUnitId":"${id(ua)}"}"""))
        assertThat(a.admin.post("${employees(a)}/${uid(ea)}/positions", """{"membershipId":"${id(ma)}","positionId":"${id(pb)}"}""").response.status).describedAs("B's position").isEqualTo(404)
        assertThat(a.admin.post("${employees(a)}/${uid(eb)}/positions", """{"membershipId":"${id(ma)}","positionId":"${id(pb)}"}""").response.status).describedAs("B's employee").isEqualTo(404)
        assertThat(a.admin.post("${employees(a)}/${uid(eb)}/disable").response.status).describedAs("a person of company B cannot be disabled from A").isEqualTo(404)
        assertThat(a.admin.get("${employees(a)}?userId=${uid(eb)}").let { a.admin.body(it).get("total").asLong() }).isZero()
        // nothing changed anywhere
        assertThat(flat(a).map { it.get("code").asString() }).containsExactly("A1"); assertThat(flat(b).map { it.get("code").asString() }).containsExactly("B1")
        assertThat(employee(a, uid(ea)).get("organizationMemberships").size()).describedAs("only the legitimate membership").isEqualTo(1); assertThat(employee(a, uid(ea)).get("positions").size()).isZero(); assertThat(employee(b, uid(eb)).get("organizationMemberships").size()).isZero()
        // unknown tenant answers like a foreign one
        assertThat(a.admin.get("/api/v1/admin/tenants/${UUID.randomUUID()}/organization-units").response.status).isEqualTo(404)
    }

    @Test
    fun `8 and 9 and 10 neither a MANAGER relation, nor a position, nor the WORKSPACE_ADMIN role, nor SYSTEM_ADMIN opens the company organization`() {
        val sys = sysAdmin(); val c = company(sys); val t = type(c, "unit"); val u = unit(c, t, "A")
        val ws = UUID.fromString(sys.body(sys.post("${base(c)}/workspaces", """{"name":"WS"}""")).get("id").asString())
        // an employee who is WORKSPACE_ADMIN of a workspace of the company, MANAGER and HEAD of units, with a position and a grade
        val wa = newEmployee(c, extra = ""","workspaceId":"$ws","workspaceRole":"WORKSPACE_ADMIN""""); val waUser = uid(wa)
        val manager = c.admin.body(c.admin.post("${employees(c)}/$waUser/organization-memberships", """{"organizationUnitId":"${id(u)}","relationType":"MANAGER"}"""))
        c.admin.post("${employees(c)}/$waUser/organization-memberships", """{"organizationUnitId":"${id(unit(c, t, "B"))}","relationType":"HEAD"}""")
        val pos = c.admin.body(c.admin.post(positions(c), """{"code":"DIRECTOR","name":"Director"}""")); val gr = c.admin.body(c.admin.post(grades(c), """{"code":"G9","name":"Top","rank":99}"""))
        assertThat(c.admin.post("${employees(c)}/$waUser/positions", """{"membershipId":"${id(manager)}","positionId":"${id(pos)}","gradeId":"${id(gr)}"}""").response.status).isEqualTo(201)
        val was = activateAndLogin(wa.get("employee").get("username").asString(), wa.get("activation").get("token").asString())
        val me = was.body(was.get("/api/v1/auth/me")); assertThat(me.get("permissions").toList()).describedAs("no tenant-level permission at all").isEmpty()
        val wsRow = me.get("workspaces").toList().single { it.get("id").asString() == ws.toString() }; assertThat(wsRow.get("role").asString()).isEqualTo("WORKSPACE_ADMIN")
        assertThat(wsRow.get("permissions").toList().map { it.asString() }).doesNotContain("ORG_STRUCTURE_MANAGE", "EMPLOYEE_MANAGE", "POSITION_GRADE_MANAGE", "TENANT_MEMBERS", "TENANT_MANAGE")
        for ((m, p) in routes(c, id(u), id(t), waUser, id(pos))) assertThat(call(was, m, p)).describedAs("WORKSPACE_ADMIN + MANAGER + HEAD + position: $m $p").isEqualTo(403)
        // SYSTEM_ADMIN: platform scope only, by default no tenant business access
        for ((m, p) in routes(c, id(u), id(t), waUser, id(pos))) assertThat(call(sys, m, p)).describedAs("SYSTEM_ADMIN $m $p").isEqualTo(403)
        val sm = sys.body(sys.get("/api/v1/auth/me")); assertThat(sm.get("businessAccess").asBoolean()).isFalse(); assertThat(sm.get("permissions").toList().map { it.asString() }).containsExactlyInAnyOrder("TENANT_MANAGE", "TENANT_MEMBERS")
        // ... while it still does what a platform operator does
        assertThat(sys.get("/api/v1/admin/tenants/${c.id}/members").response.status).isEqualTo(200)
        assertThat(sys.post("${base(c)}/users", """{"username":"${uname("op")}","displayName":"Op"}""").response.status).isEqualTo(201)
        // and the structure is untouched by every refusal
        assertThat(flat(c)).hasSize(2); assertThat(unitNow(c, u).get("version").asLong()).isZero()
    }

    @Test
    fun `11 and 12 and 13 EVERY mutating route needs a MANAGE capability and every read route a VIEW one - move, archive and restore included - and no handler reads a role name`() {
        val src = listOf("src/main/kotlin/com/systemwebstudio/organization/OrganizationControllers.kt", "backend/src/main/kotlin/com/systemwebstudio/organization/OrganizationControllers.kt").map { java.io.File(it) }.first { it.exists() }.readText()
        val handlers = Regex("""@(Get|Post|Put|Patch|Delete)Mapping(?:\("([^"]*)"\))?""").findAll(src).toList()
        assertThat(handlers.size).describedAs("the controllers expose their routes").isGreaterThanOrEqualTo(38)
        val seen = mutableListOf<String>()
        handlers.forEachIndexed { i, m ->
            val body = src.substring(m.range.last, handlers.getOrNull(i + 1)?.range?.first ?: src.length)
            val perm = Regex("""Permission\.(\w+)""").find(body)?.groupValues?.get(1) ?: error("handler ${m.value} ${m.groupValues[2]} has no permission check")
            val read = m.groupValues[1] == "Get"
            if (read) assertThat(perm).describedAs("${m.value} ${m.groupValues[2]}").endsWith("_VIEW") else assertThat(perm).describedAs("${m.value} ${m.groupValues[2]}").endsWith("_MANAGE")
            seen += "${m.groupValues[1]} ${m.groupValues[2]}"
        }
        assertThat(seen).contains("Post /{unitId}/move", "Post /{unitId}/archive", "Post /{unitId}/restore", "Patch /{unitId}", "Post /{typeId}/disable", "Delete /{userId}/organization-memberships/{membershipId}", "Post /{userId}/positions")
        assertThat(src).describedAs("the decision is a permission, never a role name").doesNotContain("tenantRole ==").doesNotContain("\"TENANT_ADMIN\"").doesNotContain("workspaceRole").doesNotContain("relationType ==")
        // and at the matrix: the manage capabilities are the Tenant Admin's alone
        val admin = com.systemwebstudio.access.PermissionMatrix.tenantRoles.getValue("TENANT_ADMIN").map { it.name }
        assertThat(admin).contains("ORG_STRUCTURE_MANAGE", "EMPLOYEE_MANAGE", "POSITION_GRADE_MANAGE")
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A"); val b = unit(c, t, "B")
        assertThat(move(c, a, b).response.status).isEqualTo(200); assertThat(c.admin.post("${units(c)}/${id(a)}/archive", """{"expectedVersion":${ver(unitNow(c, a))}}""").response.status).isEqualTo(200)
        assertThat(c.admin.post("${units(c)}/${id(a)}/restore", """{"expectedVersion":${ver(unitNow(c, a))}}""").response.status).isEqualTo(200)
    }

    @Test
    fun `14 and 15 a Tenant Admin cannot grant SYSTEM_ADMIN, and the self and last-admin protections of the account system are unchanged`() {
        val sys = sysAdmin(); val c = company(sys); val e = newEmployee(c)
        assertThat(c.admin.post("/api/v1/admin/users/${uid(e)}/system-admin", """{"grant":true,"confirm":true}""").response.status).isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT system_admin FROM users WHERE id = ?", Boolean::class.java, uid(e))).isFalse()
        for (extra in listOf(""","systemAdmin":true""", ""","system_admin":true,"role":"SYSTEM_ADMIN"""")) {
            val r = c.admin.post(employees(c), """{"username":"${uname("sneak")}","displayName":"s"$extra}""")
            if (r.response.status == 201) assertThat(jdbc.queryForObject("SELECT system_admin FROM users WHERE id = ?", Boolean::class.java, UUID.fromString(c.admin.body(r).get("employee").get("userId").asString()))).isFalse()
        }
        // self role change and the last Tenant Admin (tenant API, unchanged)
        val self = c.admin.put("${base(c)}/members/${c.adminId}", """{"role":"MEMBER"}"""); assertThat(self.response.status).isEqualTo(403); assertThat(code(self, c.admin)).isEqualTo("SELF_GRANT_FORBIDDEN")
        val last = sys.put("${base(c)}/members/${c.adminId}", """{"role":"MEMBER"}"""); assertThat(last.response.status).isEqualTo(409); assertThat(code(last, sys)).isEqualTo("LAST_TENANT_ADMIN")
        assertThat(code(c.admin.post("${employees(c)}/${c.adminId}/disable"), c.admin)).describedAs("nobody disables themselves").isEqualTo("SELF_GRANT_FORBIDDEN")
    }

    @Test
    fun `16 no credential or secret reaches the audit trail of any organization, employee, position or grade action`() {
        val c = company(); val t = type(c, "unit"); val u = unit(c, t, "A"); val e = newEmployee(c, extra = ""","organizationMemberships":[{"organizationUnitId":"${id(u)}"}]""")
        val pos = c.admin.body(c.admin.post(positions(c), """{"code":"P","name":"P"}""")); c.admin.post("${employees(c)}/${uid(e)}/positions", """{"membershipId":"${id(employee(c, uid(e)).get("organizationMemberships").single())}","positionId":"${id(pos)}"}""")
        c.admin.post("${employees(c)}/${uid(e)}/disable")
        val token = e.get("activation").get("token").asString()
        val rows = jdbc.queryForList("SELECT action, coalesce(old_value::text,'') || ' ' || coalesce(new_value::text,'') AS t FROM audit_events WHERE actor_id = ?", c.adminId)
        assertThat(rows.map { it["action"] as String }).contains("ORG_UNIT_TYPE_CREATED", "ORG_UNIT_CREATED", "EMPLOYEE_CREATED", "EMPLOYEE_ORG_ASSIGNED", "POSITION_CREATED", "EMPLOYEE_POSITION_ASSIGNED", "EMPLOYEE_DISABLED")
        val dump = rows.joinToString("\n") { it["t"] as String }
        assertThat(dump).doesNotContain(token).doesNotContain(PASSWORD); assertThat(dump.lowercase()).doesNotContain("password", "argon", "hash", "\"token\"", "secret")
        // every one of those rows names its tenant and its actor
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE actor_id = ? AND action IN ('ORG_UNIT_CREATED','EMPLOYEE_CREATED') AND new_value::text LIKE ?", Long::class.java, c.adminId, "%${c.id}%")).isEqualTo(2L)
    }

    @Test
    fun `a SYSTEM_ADMIN who is also a member of the company has its member role there - but only the platform scope once the company is DELETED`() {
        val sys = sysAdmin(); val c = company(sys); val sysId = UUID.fromString(sys.body(sys.get("/api/v1/auth/me")).get("id").asString())
        jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role, active) VALUES (?, ?, 'TENANT_ADMIN', true)", c.id, sysId)       // a platform operator who also belongs to the company
        assertThat(sys.get(units(c)).response.status).describedAs("an active member keeps its Tenant Admin role").isEqualTo(200)
        jdbc.update("UPDATE tenants SET status = 'DELETED' WHERE id = ?", c.id)
        assertThat(sys.get(units(c)).response.status).describedAs("a deleted company: platform scope only, no organization data").isEqualTo(403)
        assertThat(c.admin.get(units(c)).response.status).describedAs("an ordinary user of a deleted company: 404").isEqualTo(404)
    }
}
