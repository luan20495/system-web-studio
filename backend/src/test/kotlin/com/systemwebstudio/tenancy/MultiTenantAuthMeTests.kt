package com.systemwebstudio.tenancy

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.PermissionCodes
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.organization.InMemoryOrganizationConfig
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MvcResult
import tools.jackson.databind.JsonNode
import java.io.File
import java.util.UUID

/**
 * M-052 / AD01 - `/auth/me` `tenants[].permissions` for a user who belongs to SEVERAL companies with different roles.
 * The per-entry `permissions` is the authorization signal of THAT tenant only (TENANT_ADMIN: the eight tenant + organization codes, MEMBER: none), recomputed on every request;
 * the root `permissions[]` stay platform + PRIMARY tenant (DEFAULT if a member, else the oldest membership) and never receive a secondary tenant's codes; `role` is informational.
 * Every assertion on `/auth/me` is paired with the direct API truth of the same session (the server, not the DTO, decides).
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultiTenantAuthMeTests : FinalIamTestBase() {
    @Autowired lateinit var access: AccessService

    private val adminCodes = listOf(
        "EMPLOYEE_MANAGE", "EMPLOYEE_VIEW", "ORG_STRUCTURE_MANAGE", "ORG_STRUCTURE_VIEW", "POSITION_GRADE_MANAGE", "POSITION_GRADE_VIEW", "TENANT_MANAGE", "TENANT_MEMBERS"
    )

    // DEFAULT-tenant workspaces made by fx.workspace() are not in a test company: removed here (a subclass @AfterEach runs before the base purge)
    private val defaultWorkspaces = mutableListOf<UUID>()
    @AfterEach fun purgeDefaultWorkspaces() {
        if (defaultWorkspaces.isEmpty()) return
        val ids = defaultWorkspaces.joinToString(",") { "'$it'" }
        jdbc.execute("DELETE FROM workspace_members WHERE workspace_id IN ($ids)")
        jdbc.execute("DELETE FROM workspaces WHERE id IN ($ids)")
        defaultWorkspaces.clear()
    }

    // ---------------------------------------------------------------------------------------------------- fixtures (real services / real API)
    /** a plain account that is a MEMBER of the DEFAULT tenant (through a DEFAULT workspace membership + the V26 trigger), i.e. its PRIMARY tenant is DEFAULT with role MEMBER */
    private fun defaultMember(prefix: String): UserEntity {
        val u = fx.user(prefix); val ws = fx.workspace().also { defaultWorkspaces += it }; fx.member(ws, u, "VIEWER")
        return u
    }
    /** relates [u] to company [c] the production way: a workspace membership there (trigger -> tenant MEMBER) */
    private fun relate(c: Company, u: UserEntity) { fx.member(wsIn(c), u, "VIEWER") }
    /** the company's own Tenant Admin promotes / demotes [u] through PUT /admin/tenants/{id}/members/{userId} */
    private fun setRole(by: ApiSession, c: Company, u: UserEntity, role: String) {
        val r = by.put("${base(c)}/members/${u.id}", """{"role":"$role"}""")
        assertThat(r.response.status).describedAs("PUT members ${u.username} -> $role").isEqualTo(200)
        assertThat(by.body(r).get("role").asString()).isEqualTo(role)
    }

    private fun entry(m: JsonNode, c: Company): JsonNode? = m.get("tenants").toList().firstOrNull { it.get("id").asString() == c.id.toString() }
    private fun entryPerms(m: JsonNode, c: Company): List<String> = strings(entry(m, c)!!.get("permissions"))
    private fun rootPerms(m: JsonNode) = strings(m.get("permissions"))
    private fun status(r: MvcResult) = r.response.status

    private fun assertPrimaryIsDefaultMember(m: JsonNode, what: String) {
        assertThat(m.get("tenantId").asString()).describedAs("$what: primary tenant").isEqualTo(TenantIds.DEFAULT.toString())
        assertThat(m.get("tenantRole").asString()).describedAs("$what: primary role").isEqualTo("MEMBER")
        assertThat(rootPerms(m)).describedAs("$what: root permissions = what a primary MEMBER gets (none), never a secondary tenant's codes").isEmpty()
        assertThat(m.get("tenants").toList().first().get("id").asString()).describedAs("$what: the primary is listed first").isEqualTo(TenantIds.DEFAULT.toString())
        assertThat(strings(m.get("tenants").toList().first().get("permissions"))).describedAs("$what: DEFAULT entry (MEMBER)").isEmpty()
    }

    // ---------------------------------------------------------------------------------------------------- A + B + C + H (API side)
    @Test
    fun `A B C - admin of A shows the eight codes on A only, MEMBER of B shows none and is refused B, an unrelated company is absent, root permissions stay the primary's`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys); val c = company(sys)
        val u = defaultMember("mt-u")
        relate(a, u); setRole(a.admin, a, u, "TENANT_ADMIN")
        relate(b, u)                                                     // MEMBER of B
        val s = sessionFor(u.username)
        val m = me(s)

        // A: exactly the eight codes, sorted as the API returns them; role is informational
        assertThat(entryPerms(m, a)).describedAs("tenants[A].permissions").isEqualTo(adminCodes)
        assertThat(entry(m, a)!!.get("role").asString()).isEqualTo("TENANT_ADMIN")
        assertThat(entry(m, a)!!.get("status").asString()).isEqualTo("ACTIVE")
        assertThat(entry(m, a)!!.get("slug").asString()).isEqualTo(a.slug)
        // root: platform + PRIMARY (DEFAULT, MEMBER) only - none of A's codes leak there
        assertPrimaryIsDefaultMember(m, "A")
        assertThat(rootPerms(m)).doesNotContainAnyElementsOf(adminCodes)
        assertThat(m.get("platformScope").asBoolean()).isFalse()
        // B: listed, MEMBER, no permission
        assertThat(entry(m, b)!!.get("role").asString()).isEqualTo("MEMBER")
        assertThat(entryPerms(m, b)).describedAs("tenants[B].permissions").isEmpty()
        // C (no relation), and the companies' own admins (another user's companies): absent, by id
        assertThat(tenantIds(m)).describedAs("only the user's own active memberships").containsExactlyInAnyOrder(TenantIds.DEFAULT.toString(), a.id.toString(), b.id.toString())
        assertThat(tenantIds(m)).doesNotContain(c.id.toString())
        for (t in m.get("tenants")) assertThat(strings(t.get("permissions"))).isSubsetOf(PermissionCodes.CANONICAL)

        // the direct API truth: A's admin + organization routes 200, the same routes on B 403 (member, lacks the permission), on C 404 (no disclosure)
        for (path in listOf(base(a), "${base(a)}/members", units(a), types(a), employees(a), positions(a), grades(a)))
            assertThat(status(s.get(path))).describedAs("A: $path").isEqualTo(200)
        assertThat(status(s.post(types(a), """{"code":"dept","name":"Dept"}"""))).describedAs("A: organization write").isEqualTo(201)
        assertThat(status(s.get(base(b)))).describedAs("B: GET tenant (a member may see its company)").isEqualTo(200)
        for (path in listOf("${base(b)}/members", "${base(b)}/member-candidates", units(b), types(b), employees(b), positions(b), grades(b)))
            assertThat(status(s.get(path))).describedAs("B: $path").isEqualTo(403)
        assertThat(status(s.post(types(b), """{"code":"dept","name":"Dept"}"""))).describedAs("B: organization write").isEqualTo(403)
        assertThat(status(s.patch(base(b), """{"name":"Hijack"}"""))).describedAs("B: rename").isEqualTo(403)
        assertThat(status(s.post("${base(b)}/users", """{"username":"${uname("mt-x")}","displayName":"X","tenantRole":"MEMBER"}"""))).describedAs("B: provisioning").isEqualTo(403)
        assertThat(status(s.put("${base(b)}/members/${b.adminId}", """{"role":"MEMBER"}"""))).describedAs("B: demote B's admin").isEqualTo(403)
        for (path in listOf(base(c), "${base(c)}/members", units(c), employees(c)))
            assertThat(status(s.get(path))).describedAs("C: $path").isEqualTo(404)
        assertThat(jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String::class.java, b.id)).isEqualTo("Company ${b.slug}")
        assertThat(tenantService.roleOf(b.id, b.adminId)).isEqualTo(TenantRole.TENANT_ADMIN)
    }

    // ---------------------------------------------------------------------------------------------------- D revocation
    @Test
    fun `D membership removed by the company's other admin - the same session no longer lists A and A's admin API answers 404`() {
        val sys = sysAdmin(); val a = company(sys)
        val u = defaultMember("mt-d"); relate(a, u); setRole(a.admin, a, u, "TENANT_ADMIN")
        val s = sessionFor(u.username)
        assertThat(entryPerms(me(s), a)).isEqualTo(adminCodes)
        assertThat(status(s.get("${base(a)}/members"))).isEqualTo(200)

        assertThat(status(a.admin.delete("${base(a)}/members/${u.id}"))).isEqualTo(204)

        val after = me(s)
        assertThat(entry(after, a)).describedAs("A is no longer listed").isNull()
        assertThat(tenantIds(after)).containsExactly(TenantIds.DEFAULT.toString())
        assertPrimaryIsDefaultMember(after, "D")
        for (path in listOf(base(a), "${base(a)}/members", units(a), employees(a)))
            assertThat(status(s.get(path))).describedAs("revoked: $path").isEqualTo(404)
        assertThat(status(s.post(types(a), """{"code":"dept","name":"Dept"}"""))).isEqualTo(404)
        assertThat(status(s.patch(base(a), """{"name":"Late"}"""))).isEqualTo(404)
    }

    // ---------------------------------------------------------------------------------------------------- E downgrade
    @Test
    fun `E role downgraded TENANT_ADMIN to MEMBER by the platform operator - same session sees MEMBER with no permission and the admin API and org writes are 403`() {
        val sys = sysAdmin(); val a = company(sys)
        val u = defaultMember("mt-e"); relate(a, u); setRole(a.admin, a, u, "TENANT_ADMIN")
        val s = sessionFor(u.username)
        assertThat(entryPerms(me(s), a)).isEqualTo(adminCodes)
        assertThat(status(s.post(types(a), """{"code":"dept","name":"Dept"}"""))).isEqualTo(201)

        setRole(sys, a, u, "MEMBER")

        val after = me(s)
        assertThat(entry(after, a)!!.get("role").asString()).isEqualTo("MEMBER")
        assertThat(entryPerms(after, a)).describedAs("no stale admin codes").isEmpty()
        assertPrimaryIsDefaultMember(after, "E")
        for (path in listOf("${base(a)}/members", units(a), types(a), employees(a), positions(a), grades(a)))
            assertThat(status(s.get(path))).describedAs("downgraded: $path").isEqualTo(403)
        assertThat(status(s.post(types(a), """{"code":"team","name":"Team"}"""))).isEqualTo(403)
        assertThat(status(s.patch(base(a), """{"name":"Late"}"""))).isEqualTo(403)
        assertThat(status(s.post("${base(a)}/users", """{"username":"${uname("mt-x")}","displayName":"X","tenantRole":"MEMBER"}"""))).isEqualTo(403)
    }

    // ---------------------------------------------------------------------------------------------------- F suspended
    @Test
    fun `F SUSPENDED company - still listed with status SUSPENDED and the eight capabilities, while the server refuses organization writes 403 TENANT_SUSPENDED and keeps reads and members open`() {
        val sys = sysAdmin(); val a = company(sys)
        val u = defaultMember("mt-f"); relate(a, u); setRole(a.admin, a, u, "TENANT_ADMIN")
        val s = sessionFor(u.username)
        val t = type(a, "dept"); unit(a, t, "D1")

        assertThat(status(sys.patch("${base(a)}/status", """{"status":"SUSPENDED"}"""))).isEqualTo(200)

        val m = me(s)
        val e = entry(m, a)!!
        assertThat(e.get("status").asString()).isEqualTo("SUSPENDED")
        assertThat(e.get("role").asString()).isEqualTo("TENANT_ADMIN")
        assertThat(strings(e.get("permissions"))).describedAs("capabilities of the role, independent of the status").isEqualTo(adminCodes)
        assertPrimaryIsDefaultMember(m, "F")

        for ((what, r) in listOf(
            "POST type" to s.post(types(a), """{"code":"team","name":"Team"}"""),
            "POST unit" to s.post(units(a), """{"typeId":"${id(t)}","code":"D2","name":"d2"}"""),
            "POST employee" to s.post(employees(a), """{"username":"${uname("emp")}","displayName":"x"}"""),
            "POST position" to s.post(positions(a), """{"code":"P1","name":"P"}"""),
            "PATCH rename" to s.patch(base(a), """{"name":"Nope"}""")
        )) { assertThat(status(r)).describedAs(what).isEqualTo(403); assertThat(errorCode(s, r)).describedAs(what).isEqualTo("TENANT_SUSPENDED") }
        for (path in listOf(units(a), types(a), employees(a), positions(a), grades(a), base(a), "${base(a)}/members"))
            assertThat(status(s.get(path))).describedAs("suspended read $path").isEqualTo(200)
        assertThat(flat(a).map { it.get("code").asString() }).containsExactly("D1")
        assertThat(status(s.post("${base(a)}/users", """{"username":"${uname("susp")}","displayName":"Suspended user"}"""))).describedAs("tenant-admin routes stay open: POST /users").isEqualTo(201)
    }

    // ---------------------------------------------------------------------------------------------------- G deleted
    @Test
    fun `G DELETED company - absent from tenants and every API of it 404 for the former admin`() {
        val sys = sysAdmin(); val a = company(sys)
        val u = defaultMember("mt-g"); relate(a, u); setRole(a.admin, a, u, "TENANT_ADMIN")
        val s = sessionFor(u.username)
        assertThat(entryPerms(me(s), a)).isEqualTo(adminCodes)

        assertThat(status(sys.patch("${base(a)}/status", """{"status":"DELETED"}"""))).isEqualTo(200)

        val m = me(s)
        assertThat(tenantIds(m)).describedAs("no disclosure of the deleted company").doesNotContain(a.id.toString()).containsExactly(TenantIds.DEFAULT.toString())
        assertThat(m.toString()).doesNotContain(a.id.toString()).doesNotContain(a.slug)
        assertPrimaryIsDefaultMember(m, "G")
        for (path in listOf(base(a), "${base(a)}/members", units(a), types(a), employees(a), positions(a), grades(a)))
            assertThat(status(s.get(path))).describedAs("deleted: $path").isEqualTo(404)
        assertThat(status(s.post(types(a), """{"code":"dept","name":"Dept"}"""))).isEqualTo(404)
        assertThat(status(s.patch(base(a), """{"name":"Zombie"}"""))).isEqualTo(404)
    }

    // ---------------------------------------------------------------------------------------------------- H role is not authority
    @Test
    fun `H the server decides from the membership, never from the role field - AccessService forTenant grants exactly the eight codes in A and nothing elsewhere`() {
        val sys = sysAdmin(); val a = company(sys); val c = company(sys)
        val u = defaultMember("mt-h"); relate(a, u); setRole(a.admin, a, u, "TENANT_ADMIN")
        val member = defaultMember("mt-hm"); relate(a, member)
        val ms = sessionFor(member.username)
        val mm = me(ms)
        assertThat(entry(mm, a)!!.get("role").asString()).isEqualTo("MEMBER"); assertThat(entryPerms(mm, a)).isEmpty()
        for (path in listOf("${base(a)}/members", units(a), employees(a))) assertThat(status(ms.get(path))).describedAs("MEMBER: $path").isEqualTo(403)
        assertThat(status(ms.put("${base(a)}/members/${member.id}", """{"role":"TENANT_ADMIN"}"""))).describedAs("a MEMBER cannot self-promote").isEqualTo(403)
        assertThat(tenantService.roleOf(a.id, member.id)).isEqualTo(TenantRole.MEMBER)

        // the AccessService view is the one /auth/me mirrors: per tenant, from the active membership
        val inA = access.forTenant(u.id, a.id)
        assertThat(PermissionCodes.canonicalCodesOf(inA.permissions)).isEqualTo(adminCodes)
        assertThat(inA.platformScope).isFalse()
        assertThat(PermissionCodes.canonicalCodesOf(access.forTenant(u.id, TenantIds.DEFAULT).permissions)).describedAs("DEFAULT (MEMBER)").isEmpty()
        assertThat(PermissionCodes.canonicalCodesOf(access.forTenant(member.id, a.id).permissions)).isEmpty()
        assertThatThrownBy { access.forTenant(u.id, c.id) }.isInstanceOfSatisfying(ApiException::class.java) { e -> assertThat(e.status.value()).isEqualTo(404) }
        assertThatThrownBy { access.forTenant(u.id, UUID.randomUUID()) }.isInstanceOfSatisfying(ApiException::class.java) { e -> assertThat(e.status.value()).isEqualTo(404) }
        assertThat(entryPerms(me(sessionFor(u.username)), a)).isEqualTo(PermissionCodes.canonicalCodesOf(inA.permissions))
    }

    @Test
    fun `H source guard - the tenant and organization controllers never compare a role name, decisions go through Permission`() {
        val root = listOf("src/main/kotlin/com/systemwebstudio", "backend/src/main/kotlin/com/systemwebstudio").map { File(it) }.first { it.isDirectory }
        for (rel in listOf("organization/OrganizationControllers.kt", "tenancy/TenantController.kt")) {
            val f = File(root, rel); assertThat(f).describedAs(rel).isFile()
            // code only: drop block and line comments (the KDoc legitimately names the roles)
            val code = f.readText().replace(Regex("/\\*[\\s\\S]*?\\*/"), "").lines().joinToString("\n") { it.replace(Regex("//.*$"), "") }
            assertThat(code).describedAs("$rel: no TenantRole.TENANT_ADMIN reference").doesNotContain("TenantRole.TENANT_ADMIN").doesNotContain("TenantRole.MEMBER")
            assertThat(code).describedAs("$rel: no \"TENANT_ADMIN\" literal").doesNotContain("\"TENANT_ADMIN\"")
            assertThat(Regex("""(tenantRole|\.role)\s*[!=]=""").containsMatchIn(code)).describedAs("$rel: no role equality check").isFalse()
            assertThat(code).describedAs("$rel: authorizes with Permission.*").contains("Permission.")
        }
    }

    // ---------------------------------------------------------------------------------------------------- AD01 real flow
    @Test
    fun `AD01 primary DEFAULT MEMBER and TENANT_ADMIN of a secondary company - the client picks the entry by id, reads permissions, and the direct admin + organization API succeeds`() {
        val sys = sysAdmin(); val a = company(sys)
        val u = defaultMember("mt-ad01"); relate(a, u); setRole(a.admin, a, u, "TENANT_ADMIN")
        val s = sessionFor(u.username)
        val m = me(s)
        assertPrimaryIsDefaultMember(m, "AD01")
        // what a portal does: pick tenants[] BY ID, then gate on that entry's permissions (not on role, not on the root array)
        val picked = m.get("tenants").toList().single { it.get("id").asString() == a.id.toString() }
        val perms = strings(picked.get("permissions"))
        assertThat(perms).isEqualTo(adminCodes)
        assertThat(perms).contains("TENANT_MEMBERS", "TENANT_MANAGE", "ORG_STRUCTURE_VIEW")

        assertThat(status(s.get(base(a)))).isEqualTo(200)
        val members = s.get("${base(a)}/members"); assertThat(status(members)).isEqualTo(200)
        assertThat(s.body(members).toList().map { it.get("userId").asString() }).contains(u.id.toString(), a.adminId.toString())
        val created = s.post("${base(a)}/users", """{"username":"${uname("mt-new")}","displayName":"New Hire","tenantRole":"MEMBER"}""")
        assertThat(status(created)).describedAs("POST users").isEqualTo(201)
        val renamed = s.patch(base(a), """{"name":"Renamed by secondary admin"}""")
        assertThat(status(renamed)).describedAs("PATCH rename").isEqualTo(200)
        assertThat(s.body(renamed).get("name").asString()).isEqualTo("Renamed by secondary admin")
        for (path in listOf(units(a), "${units(a)}?format=flat", types(a), employees(a), positions(a), grades(a)))
            assertThat(status(s.get(path))).describedAs("organization read $path").isEqualTo(200)
        assertThat(status(s.post(types(a), """{"code":"dept","name":"Dept"}"""))).describedAs("organization write").isEqualTo(201)
        // still nothing on the primary scope
        assertThat(rootPerms(me(s))).isEmpty()
        assertThat(status(s.get("${base(TenantIds.DEFAULT)}/members"))).describedAs("DEFAULT (MEMBER) members").isEqualTo(403)
    }

    private fun base(id: UUID) = "/api/v1/admin/tenants/$id"
}
