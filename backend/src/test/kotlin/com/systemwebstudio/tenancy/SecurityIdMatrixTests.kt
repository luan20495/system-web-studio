package com.systemwebstudio.tenancy

import com.systemwebstudio.organization.InMemoryOrganizationConfig
import com.systemwebstudio.project.ProjectEntity
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MvcResult
import java.util.UUID

/**
 * C1 security matrix (HTTP layer, exact status + code): wrong ids answered like missing ids, roles without the permission, permissions without a role shortcut,
 * same-session revocation, SUSPENDED / DELETED companies and foreign-id disclosure. Everything lives in companies made through the real API (purged afterwards).
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityIdMatrixTests : SecurityMatrixSupport() {
    // child rows (versions / deployments / assets) inserted by a test: removed before the base purge deletes their projects (a subclass @AfterEach runs first)
    private val inserted = mutableListOf<Pair<String, UUID>>()
    @AfterEach fun purgeInsertedChildren() {
        for (table in listOf("deployments", "assets", "project_versions")) inserted.filter { it.first == table }.forEach { jdbc.update("DELETE FROM $table WHERE id = ?", it.second) }
        inserted.clear()
    }

    private fun version(p: ProjectEntity, by: UUID): UUID = UUID.randomUUID().also {
        jdbc.update("INSERT INTO project_versions (id, workspace_id, project_id, version_number, schema_snapshot, kind, summary, created_by) VALUES (?,?,?,1,'{}'::jsonb,'INITIAL','v1',?)", it, p.workspaceId, p.id, by)
        inserted += "project_versions" to it
    }
    private fun deployment(p: ProjectEntity, v: UUID, by: UUID): UUID = UUID.randomUUID().also {
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?,?,?,?,?,'PRIVATE','RUNNING','mock')", it, p.workspaceId, p.id, v, by)
        inserted.add(0, "deployments" to it)
    }
    private fun asset(p: ProjectEntity, by: UUID): UUID = UUID.randomUUID().also {
        jdbc.update("INSERT INTO assets (id, workspace_id, project_id, name, content_type, size_bytes, storage_key, status, created_by) VALUES (?,?,?,'a.png','image/png',1,?,'READY',?)", it, p.workspaceId, p.id, "sm/$it", by)
        inserted += "assets" to it
    }

    private fun position(c: Company, code: String) = c.admin.body(c.admin.post(positions(c), """{"code":"$code","name":"$code"}""")).also { assertThat(it.get("id")).isNotNull() }
    private fun grade(c: Company, code: String) = c.admin.body(c.admin.post(grades(c), """{"code":"$code","name":"$code"}""")).also { assertThat(it.get("id")).isNotNull() }
    private fun membership(c: Company, user: UUID, unit: tools.jackson.databind.JsonNode): tools.jackson.databind.JsonNode {
        val r = c.admin.post("${employees(c)}/$user/organization-memberships", """{"organizationUnitId":"${id(unit)}"}"""); assertThat(r.response.status).isEqualTo(201); return c.admin.body(r)
    }

    // =============================================================================================================== 1. WRONG IDS = MISSING IDS
    @Test
    fun `1ab a Tenant Admin of A using B's tenant id on tenant and organization routes - 404 TENANT_NOT_FOUND identical to a random id, whatever B's status`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys)
        val s = a.admin
        fun all(label: String) {
            likeMissing("$label GET tenant", 404, "TENANT_NOT_FOUND", b.id) { s.get("/api/v1/admin/tenants/$it") }
            likeMissing("$label PATCH tenant", 404, "TENANT_NOT_FOUND", b.id) { s.patch("/api/v1/admin/tenants/$it", """{"name":"Hijacked"}""") }
            likeMissing("$label GET members", 404, "TENANT_NOT_FOUND", b.id) { s.get("/api/v1/admin/tenants/$it/members") }
            likeMissing("$label GET member-candidates", 404, "TENANT_NOT_FOUND", b.id) { s.get("/api/v1/admin/tenants/$it/member-candidates") }
            likeMissing("$label PUT member", 404, "TENANT_NOT_FOUND", b.id) { s.put("/api/v1/admin/tenants/$it/members/${b.adminId}", """{"role":"MEMBER"}""") }
            likeMissing("$label DELETE member", 404, "TENANT_NOT_FOUND", b.id) { s.delete("/api/v1/admin/tenants/$it/members/${b.adminId}") }
            likeMissing("$label POST users", 404, "TENANT_NOT_FOUND", b.id) { s.post("/api/v1/admin/tenants/$it/users", """{"username":"sm-never-made","displayName":"x"}""") }
            likeMissing("$label POST workspaces", 404, "TENANT_NOT_FOUND", b.id) { s.post("/api/v1/admin/tenants/$it/workspaces", """{"name":"Intruder"}""") }
            for (r in listOf("organization-units", "organization-unit-types", "employees", "positions", "grades"))
                likeMissing("$label GET $r", 404, "TENANT_NOT_FOUND", b.id) { s.get("/api/v1/admin/tenants/$it/$r") }
            likeMissing("$label POST type", 404, "TENANT_NOT_FOUND", b.id) { s.post("/api/v1/admin/tenants/$it/organization-unit-types", """{"code":"x","name":"X"}""") }
            likeMissing("$label POST employee", 404, "TENANT_NOT_FOUND", b.id) { s.post("/api/v1/admin/tenants/$it/employees", """{"username":"sm-never-made","displayName":"x"}""") }
            likeMissing("$label POST position", 404, "TENANT_NOT_FOUND", b.id) { s.post("/api/v1/admin/tenants/$it/positions", """{"code":"P","name":"P"}""") }
        }
        all("ACTIVE B")
        setStatus(sys, b, "SUSPENDED"); all("SUSPENDED B")                 // a stranger never learns the status (forTenant 404s before requireTenantWritable, AccessService.kt:131)
        setStatus(sys, b, "DELETED"); all("DELETED B")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE tenant_id = ?", Long::class.java, b.id)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'sm-never-made'", Long::class.java)).isZero()
    }

    @Test
    fun `1a2 on its OWN tenant routes, B's users and B's workspace are unknown - PUT and DELETE member, provisioning into B's workspace, employee lookup`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys); val wsB = wsIn(b)
        val eb = uid(newEmployee(b)); val s = a.admin
        fun all(label: String) {
            likeMissing("$label PUT member (B's employee)", 404, "USER_NOT_FOUND", eb) { s.put("${base(a)}/members/$it", """{"role":"MEMBER"}""") }
            likeMissing("$label PUT member (B's Tenant Admin)", 404, "USER_NOT_FOUND", b.adminId) { s.put("${base(a)}/members/$it", """{"role":"TENANT_ADMIN"}""") }
            likeMissing("$label DELETE member", 404, "TENANT_MEMBER_NOT_FOUND", eb) { s.delete("${base(a)}/members/$it") }
            likeMissing("$label GET employee", 404, "EMPLOYEE_NOT_FOUND", eb) { s.get("${employees(a)}/$it") }
            likeMissing("$label POST users into B's workspace", 404, "WORKSPACE_NOT_FOUND", wsB) {
                s.post("${base(a)}/users", """{"username":"sm-never-made","displayName":"x","workspaceId":"$it","workspaceRole":"VIEWER"}""")
            }
        }
        all("enabled")
        // a DISABLED foreign account is still just unknown (TenantService.eligibility: DISABLED only for an account related to this tenant)
        assertThat(sys.patch("/api/v1/admin/users/$eb/status", """{"enabled":false}""").response.status).isEqualTo(200)
        all("disabled")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?", Long::class.java, a.id, eb)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ? AND active", Long::class.java, b.id, b.adminId)).isEqualTo(1L)
    }

    @Test
    fun `1c wrong workspace id - another tenant's workspace (active or suspended) and an unjoined workspace of the same tenant answer exactly like a random id`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys)
        val w1 = wsIn(a, "W1"); val w3 = wsIn(a, "W3 not joined"); val wB = wsIn(b, "WB")
        val ownerB = memberOf(wB, "EDITOR"); val pB = fx.project(wB, ownerB)
        val u = memberOf(w1, "WORKSPACE_ADMIN"); val s = loginAs(u)
        val bUser = memberOf(wB, "VIEWER", prefix = "sm-bu")
        val policyB = jdbc.queryForObject("SELECT merge_policy FROM workspaces WHERE id = ?", String::class.java, wB)
        fun all(label: String, foreign: UUID) {
            likeMissing("$label project list", 404, "WORKSPACE_NOT_FOUND", foreign) { s.get(api(UUID.fromString(it))) }
            likeMissing("$label project create", 404, "WORKSPACE_NOT_FOUND", foreign) { s.post(api(UUID.fromString(it)), """{"name":"Intruder"}""") }
            likeMissing("$label project of that workspace", 404, "WORKSPACE_NOT_FOUND", foreign) { s.get(api(UUID.fromString(it), pB.id)) }
            likeMissing("$label members", 404, "WORKSPACE_NOT_FOUND", foreign) { s.get("/api/v1/workspaces/$it/members") }
            likeMissing("$label add member", 404, "WORKSPACE_NOT_FOUND", foreign) { s.post("/api/v1/workspaces/$it/members", """{"username":"${ownerB.username}","role":"VIEWER"}""") }
            likeMissing("$label change member", 404, "WORKSPACE_NOT_FOUND", foreign) { s.patch("/api/v1/workspaces/$it/members/${ownerB.id}", """{"role":"VIEWER"}""") }
            likeMissing("$label remove member", 404, "WORKSPACE_NOT_FOUND", foreign) { s.delete("/api/v1/workspaces/$it/members/${ownerB.id}") }
            likeMissing("$label merge-policy GET", 404, "WORKSPACE_NOT_FOUND", foreign) { s.get("/api/v1/workspaces/$it/merge-policy") }
            likeMissing("$label merge-policy PUT", 404, "WORKSPACE_NOT_FOUND", foreign) { s.put("/api/v1/workspaces/$it/merge-policy", """{"policy":"${if (policyB == "AUTO_MERGE_ALLOWED") "REVIEW_REQUIRED" else "AUTO_MERGE_ALLOWED"}"}""") }
            likeMissing("$label audit", 404, "WORKSPACE_NOT_FOUND", foreign) { s.get("/api/v1/workspaces/$it/audit-events") }
        }
        assertThat(s.get("/api/v1/workspaces/$w1/members").response.status).describedAs("positive control: its own workspace").isEqualTo(200)
        all("foreign tenant", wB); all("same tenant, not joined", w3)
        // a foreign username is indistinguishable from a username nobody has (MemberController.resolveUser: tenant filter before the enabled check)
        likeMissing("add a foreign user by name", 404, "USER_NOT_FOUND", bUser.username) { s.post("/api/v1/workspaces/$w1/members", """{"username":"$it","role":"VIEWER"}""") }
        setStatus(sys, b, "SUSPENDED")
        all("SUSPENDED foreign tenant", wB)                                     // 404 before the tenant gate (AccessEvaluator.kt:48): the status is not disclosed
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE workspace_id = ?", Long::class.java, wB)).isEqualTo(2L)
        assertThat(jdbc.queryForObject("SELECT merge_policy FROM workspaces WHERE id = ?", String::class.java, wB)).describedAs("merge policy untouched").isEqualTo(policyB)
    }

    @Test
    fun `1d wrong project id - a project of W2 (caller is its OWNER) under W1, and a project of another tenant, answer 404 PROJECT_NOT_FOUND exactly like a random id on every project route`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys)
        val w1 = wsIn(a, "W1"); val w2 = wsIn(a, "W2"); val wB = wsIn(b)
        val u = memberOf(w1, "WORKSPACE_ADMIN"); fx.member(w2, u, "EDITOR"); val p2 = fx.project(w2, u, "P2 of W2")
        val ownerB = memberOf(wB, "EDITOR"); val pB = fx.project(wB, ownerB)
        val s = loginAs(u)
        assertThat(s.get(api(w2, p2.id)).response.status).describedAs("positive control: P2 under its own workspace").isEqualTo(200)
        fun routes(label: String, foreign: UUID) {
            fun p(it: String) = api(w1, UUID.fromString(it))
            likeMissing("$label GET", 404, "PROJECT_NOT_FOUND", foreign) { s.get(p(it)) }
            likeMissing("$label PATCH", 404, "PROJECT_NOT_FOUND", foreign) { s.patch(p(it), """{"expectedRevision":0,"name":"Hijacked"}""") }
            likeMissing("$label DELETE", 404, "PROJECT_NOT_FOUND", foreign) { s.delete("${p(it)}?expectedRevision=0") }
            likeMissing("$label archive", 404, "PROJECT_NOT_FOUND", foreign) { s.post("${p(it)}/archive") }
            likeMissing("$label PATCH schema", 404, "PROJECT_NOT_FOUND", foreign) { s.patch("${p(it)}/schema", """{"expectedRevision":0,"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"x"}]}""") }
            for (child in listOf("versions", "deployments", "assets", "members", "site", "runtime", "form-submissions", "domains"))
                likeMissing("$label GET $child", 404, "PROJECT_NOT_FOUND", foreign) { s.get("${p(it)}/$child") }
            likeMissing("$label publish", 404, "PROJECT_NOT_FOUND", foreign) { s.post("${p(it)}/publish", """{"visibility":"PRIVATE","expectedRevision":0}""", "Idempotency-Key" to "sm-wrong-project-key") }
            likeMissing("$label add member", 404, "PROJECT_NOT_FOUND", foreign) { s.post("${p(it)}/members", """{"username":"${ownerB.username}","role":"VIEWER"}""") }
        }
        routes("P2 under W1", p2.id); routes("B's project under W1", pB.id)
        assertThat(jdbc.queryForObject("SELECT name FROM projects WHERE id = ?", String::class.java, p2.id)).isEqualTo("P2 of W2")
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM projects WHERE id = ?", String::class.java, pB.id)).isEqualTo("ACTIVE")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id IN (?, ?)", Long::class.java, p2.id, pB.id)).isZero()
    }

    @Test
    fun `1e2 a child id (version, deployment, asset) of another project of the same workspace, or of another tenant, is unknown under the caller's own project`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys)
        val w = wsIn(a); val wB = wsIn(b)
        val u = memberOf(w, "EDITOR"); val p1 = fx.project(w, u, "P1"); val p2 = fx.project(w, u, "P2")      // the caller OWNS both projects
        val ownerB = memberOf(wB, "EDITOR"); val pB = fx.project(wB, ownerB)
        val v2 = version(p2, u.id); val d2 = deployment(p2, v2, u.id); val a2 = asset(p2, u.id)
        val vB = version(pB, ownerB.id); val dB = deployment(pB, vB, ownerB.id); val aB = asset(pB, ownerB.id)
        val s = loginAs(u)
        // positive controls: the ids are real under their own project
        assertThat(s.get("${api(w, p2.id)}/versions/$v2").response.status).isEqualTo(200)
        assertThat(s.get("${api(w, p2.id)}/deployments/$d2").response.status).isEqualTo(200)
        assertThat(s.body(s.get("${api(w, p2.id)}/assets")).toList().map { it.get("id").asString() }).contains(a2.toString())
        val p1base = api(w, p1.id)
        for ((label, v, d, aId) in listOf(listOf("same workspace", v2, d2, a2), listOf("other tenant", vB, dB, aB))) {
            likeMissing("$label version", 404, "VERSION_NOT_FOUND", v) { s.get("$p1base/versions/$it") }
            likeMissing("$label version restore", 404, "VERSION_NOT_FOUND", v) { s.post("$p1base/versions/$it/restore", """{"expectedRevision":0}""") }
            likeMissing("$label deployment", 404, "DEPLOYMENT_NOT_FOUND", d) { s.get("$p1base/deployments/$it") }
            likeMissing("$label asset delete", 404, "ASSET_NOT_FOUND", aId) { s.delete("$p1base/assets/$it") }
        }
        assertThat(jdbc.queryForList("SELECT status FROM assets WHERE id IN (?, ?)", String::class.java, a2, aB)).containsOnly("READY")
        assertThat(jdbc.queryForObject("SELECT revision FROM projects WHERE id = ?", Long::class.java, p1.id)).isZero()
    }

    @Test
    fun `1f organization ids of company B on company A's routes, another employee's membership (404) and an ended membership (409)`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys); val s = a.admin
        val ta = type(a, "dept"); val ua = unit(a, ta, "A1"); val ua2 = unit(a, ta, "A2")
        val ea = uid(newEmployee(a)); val ea2 = uid(newEmployee(a))
        val pa = position(a, "PA"); val ga = grade(a, "GA")
        val ma = membership(a, ea, ua); val ma2 = membership(a, ea2, ua)
        val tb = type(b, "dept"); val ub = unit(b, tb, "B1"); val eb = uid(newEmployee(b)); val pb = position(b, "PB"); val gb = grade(b, "GB")
        val mb = membership(b, eb, ub)
        val epb = b.admin.body(b.admin.post("${employees(b)}/$eb/positions", """{"membershipId":"${id(mb)}","positionId":"${id(pb)}"}""")).let { id(it) }

        likeMissing("GET unit", 404, "ORG_UNIT_NOT_FOUND", id(ub)) { s.get("${units(a)}/$it") }
        likeMissing("PATCH unit", 404, "ORG_UNIT_NOT_FOUND", id(ub)) { s.patch("${units(a)}/$it", """{"name":"x","expectedVersion":0}""") }
        likeMissing("archive unit", 404, "ORG_UNIT_NOT_FOUND", id(ub)) { s.post("${units(a)}/$it/archive", """{"expectedVersion":0}""") }
        likeMissing("move under B's unit", 404, "ORG_UNIT_NOT_FOUND", id(ub)) { s.post("${units(a)}/${id(ua)}/move", """{"newParentId":"$it","expectedVersion":${ver(unitNow(a, ua))}}""") }
        likeMissing("create under B's unit", 404, "ORG_UNIT_NOT_FOUND", id(ub)) { s.post(units(a), """{"typeId":"${id(ta)}","code":"X","name":"x","parentId":"$it"}""") }
        likeMissing("create with B's type", 404, "ORG_UNIT_TYPE_NOT_FOUND", id(tb)) { s.post(units(a), """{"typeId":"$it","code":"X","name":"x"}""") }
        likeMissing("GET type", 404, "ORG_UNIT_TYPE_NOT_FOUND", id(tb)) { s.get("${types(a)}/$it") }
        likeMissing("PATCH type", 404, "ORG_UNIT_TYPE_NOT_FOUND", id(tb)) { s.patch("${types(a)}/$it", """{"name":"x","expectedVersion":0}""") }
        likeMissing("GET position", 404, "POSITION_NOT_FOUND", id(pb)) { s.get("${positions(a)}/$it") }
        likeMissing("PATCH position", 404, "POSITION_NOT_FOUND", id(pb)) { s.patch("${positions(a)}/$it", """{"name":"x","expectedVersion":0}""") }
        likeMissing("GET grade", 404, "GRADE_NOT_FOUND", id(gb)) { s.get("${grades(a)}/$it") }
        likeMissing("PATCH grade", 404, "GRADE_NOT_FOUND", id(gb)) { s.patch("${grades(a)}/$it", """{"name":"x","expectedVersion":0}""") }
        likeMissing("GET employee", 404, "EMPLOYEE_NOT_FOUND", eb) { s.get("${employees(a)}/$it") }
        likeMissing("disable employee", 404, "EMPLOYEE_NOT_FOUND", eb) { s.post("${employees(a)}/$it/disable") }
        likeMissing("employee memberships", 404, "EMPLOYEE_NOT_FOUND", eb) { s.get("${employees(a)}/$it/organization-memberships") }
        likeMissing("employee positions", 404, "EMPLOYEE_NOT_FOUND", eb) { s.get("${employees(a)}/$it/positions") }
        likeMissing("employee filter by unit", 404, "ORG_UNIT_NOT_FOUND", id(ub)) { s.get("${employees(a)}?organizationUnitId=$it") }
        likeMissing("employee filter by position", 404, "POSITION_NOT_FOUND", id(pb)) { s.get("${employees(a)}?positionId=$it") }
        likeMissing("employee filter by grade", 404, "GRADE_NOT_FOUND", id(gb)) { s.get("${employees(a)}?gradeId=$it") }
        likeMissing("assign A's employee to B's unit", 404, "ORG_UNIT_NOT_FOUND", id(ub)) { s.post("${employees(a)}/$ea/organization-memberships", """{"organizationUnitId":"$it"}""") }
        likeMissing("PATCH B's membership", 404, "ORG_MEMBERSHIP_NOT_FOUND", id(mb)) { s.patch("${employees(a)}/$ea/organization-memberships/$it", """{"relationType":"HEAD","expectedVersion":0}""") }
        likeMissing("DELETE B's membership", 404, "ORG_MEMBERSHIP_NOT_FOUND", id(mb)) { s.delete("${employees(a)}/$ea/organization-memberships/$it?expectedVersion=0") }
        likeMissing("position within B's membership", 404, "ORG_MEMBERSHIP_NOT_FOUND", id(mb)) { s.post("${employees(a)}/$ea/positions", """{"membershipId":"$it","positionId":"${id(pa)}"}""") }
        likeMissing("B's position", 404, "POSITION_NOT_FOUND", id(pb)) { s.post("${employees(a)}/$ea/positions", """{"membershipId":"${id(ma)}","positionId":"$it"}""") }
        likeMissing("B's grade", 404, "GRADE_NOT_FOUND", id(gb)) { s.post("${employees(a)}/$ea/positions", """{"membershipId":"${id(ma)}","positionId":"${id(pa)}","gradeId":"$it"}""") }
        likeMissing("PATCH B's position assignment", 404, "POSITION_ASSIGNMENT_NOT_FOUND", epb) { s.patch("${employees(a)}/$ea/positions/$it", """{"gradeId":"${id(ga)}","expectedVersion":0}""") }
        likeMissing("DELETE B's position assignment", 404, "POSITION_ASSIGNMENT_NOT_FOUND", epb) { s.delete("${employees(a)}/$ea/positions/$it?expectedVersion=0") }
        // SAME company: the membership of ANOTHER employee is not this employee's (EmployeeDirectoryService.activeMembership: find(tenant, user, membership))
        likeMissing("another employee's membership", 404, "ORG_MEMBERSHIP_NOT_FOUND", id(ma2)) { s.post("${employees(a)}/$ea/positions", """{"membershipId":"$it","positionId":"${id(pa)}"}""") }
        likeMissing("PATCH another employee's membership", 404, "ORG_MEMBERSHIP_NOT_FOUND", id(ma2)) { s.patch("${employees(a)}/$ea/organization-memberships/$it", """{"relationType":"HEAD","expectedVersion":0}""") }
        likeMissing("DELETE another employee's membership", 404, "ORG_MEMBERSHIP_NOT_FOUND", id(ma2)) { s.delete("${employees(a)}/$ea/organization-memberships/$it?expectedVersion=0") }
        // an ENDED membership of this employee: 409, not 404 (it is this employee's own record)
        val ended = membership(a, ea, ua2)
        assertThat(s.delete("${employees(a)}/$ea/organization-memberships/${id(ended)}?expectedVersion=${ver(ended)}").response.status).isEqualTo(200)
        expect(s.post("${employees(a)}/$ea/positions", """{"membershipId":"${id(ended)}","positionId":"${id(pa)}"}"""), 409, "ORG_MEMBERSHIP_INACTIVE", "position within an ended membership")
        // nothing moved
        assertThat(employee(a, ea).get("positions").size()).isZero()
        assertThat(employee(a, ea2).get("organizationMemberships").size()).isEqualTo(1)
        assertThat(employee(b, eb).get("positions").size()).isEqualTo(1); assertThat(employee(b, eb).get("organizationMemberships").size()).isEqualTo(1)
        assertThat(flat(a).map { it.get("code").asString() }).containsExactlyInAnyOrder("A1", "A2"); assertThat(flat(b).map { it.get("code").asString() }).containsExactly("B1")
    }

    // =============================================================================================================== 2. ROLE != PERMISSION
    @Test
    fun `2a a WORKSPACE_ADMIN holds no tenant authority - every tenant admin route of its own company is 403 (it is a tenant MEMBER), platform routes 403 ADMIN_REQUIRED`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val wa = memberOf(ws, "WORKSPACE_ADMIN"); val other = memberOf(ws, "VIEWER"); val s = loginAs(wa)
        assertThat(s.get(base(c)).response.status).describedAs("its own company is visible").isEqualTo(200)
        forbidden(s.get("${base(c)}/members"), "members")
        forbidden(s.get("${base(c)}/member-candidates"), "member-candidates")
        forbidden(s.put("${base(c)}/members/${other.id}", """{"role":"TENANT_ADMIN"}"""), "PUT member")
        forbidden(s.delete("${base(c)}/members/${other.id}"), "DELETE member")
        forbidden(s.post("${base(c)}/users", """{"username":"${uname("sm")}","displayName":"x"}"""), "POST users")
        forbidden(s.post("${base(c)}/workspaces", """{"name":"Mine"}"""), "POST workspaces")
        forbidden(s.patch(base(c), """{"name":"Taken"}"""), "rename")
        forbidden(s.patch("${base(c)}/status", """{"status":"SUSPENDED"}"""), "status", "ADMIN_REQUIRED")
        forbidden(s.patch("/api/v1/admin/users/${other.id}/status", """{"enabled":false}"""), "global user status", "ADMIN_REQUIRED")
        assertThat(jdbc.queryForObject("SELECT role FROM tenant_members WHERE tenant_id = ? AND user_id = ?", String::class.java, c.id, other.id)).isEqualTo("MEMBER")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE tenant_id = ?", Long::class.java, c.id)).isEqualTo(1L)
    }

    @Test
    fun `2b a Tenant Admin is not a workspace member - 404 until added, then exactly the granted workspace and project roles`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = memberOf(ws, "EDITOR"); val p = fx.project(ws, owner)
        val wa = memberOf(ws, "WORKSPACE_ADMIN"); val was = loginAs(wa); val os = loginAs(owner)
        val ta = c.admin
        likeMissing("Tenant Admin, own company's workspace", 404, "WORKSPACE_NOT_FOUND", ws) { ta.get(api(UUID.fromString(it))) }
        likeMissing("Tenant Admin, members", 404, "WORKSPACE_NOT_FOUND", ws) { ta.get("/api/v1/workspaces/$it/members") }
        expect(ta.get(api(ws, p.id)), 404, "WORKSPACE_NOT_FOUND", "project")
        assertThat(workspaceIds(me(ta))).doesNotContain(ws.toString())

        assertThat(was.post("/api/v1/workspaces/$ws/members", """{"username":"${c.adminName}","role":"VIEWER"}""").response.status).isEqualTo(201)
        assertThat(ta.body(ta.get(api(ws))).size()).describedAs("workspace VIEWER: no project listed").isZero()
        expect(ta.get(api(ws, p.id)), 404, "PROJECT_NOT_FOUND", "project without a project role")
        forbidden(ta.get("/api/v1/workspaces/$ws/members"), "workspace members (no MEMBER_MANAGE)")
        forbidden(ta.post(api(ws), """{"name":"By TA"}"""), "create project (VIEWER has no PROJECT_CREATE)")

        assertThat(os.post("${api(ws, p.id)}/members", """{"username":"${c.adminName}","role":"VIEWER"}""").response.status).isEqualTo(201)
        assertThat(ta.get(api(ws, p.id)).response.status).isEqualTo(200)
        forbidden(rename(ta, ws, p.id, revision(ta, ws, p.id), "by TA"), "rename as project VIEWER")
        assertThat(scopePerms(me(ta), p.id)).containsExactlyInAnyOrder("APP_VIEW", "APP_USE")
    }

    @Test
    fun `2c TENANT_ADMIN of another company is a plain MEMBER here - 403 on every tenant and organization route, nothing in auth me for this company`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys); val wsA = wsIn(a)
        val x = memberOf(wsA, "VIEWER"); val xs = loginAs(x)
        fx.member(wsIn(b), x, "VIEWER")
        assertThat(b.admin.put("${base(b)}/members/${x.id}", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
        val m = me(xs)
        val entryA = m.get("tenants").toList().single { it.get("id").asString() == a.id.toString() }
        val entryB = m.get("tenants").toList().single { it.get("id").asString() == b.id.toString() }
        assertThat(entryB.get("role").asString()).isEqualTo("TENANT_ADMIN"); assertThat(strings(entryB.get("permissions"))).contains("TENANT_MEMBERS", "EMPLOYEE_MANAGE")
        assertThat(entryA.get("role").asString()).isEqualTo("MEMBER"); assertThat(strings(entryA.get("permissions"))).isEmpty()
        assertThat(xs.get("${base(b)}/members").response.status).describedAs("positive control: B").isEqualTo(200)
        forbidden(xs.get("${base(a)}/members"), "A members")
        forbidden(xs.post("${base(a)}/users", """{"username":"${uname("sm")}","displayName":"x"}"""), "A users")
        forbidden(xs.post("${base(a)}/workspaces", """{"name":"x"}"""), "A workspaces")
        forbidden(xs.put("${base(a)}/members/${a.adminId}", """{"role":"MEMBER"}"""), "A demote its admin")
        forbidden(xs.patch(base(a), """{"name":"x"}"""), "A rename")
        for (path in listOf(units(a), types(a), employees(a), positions(a), grades(a))) forbidden(xs.get(path), "A GET $path")
        forbidden(xs.post(types(a), """{"code":"x","name":"X"}"""), "A POST type")
        assertThat(jdbc.queryForObject("SELECT role FROM tenant_members WHERE tenant_id = ? AND user_id = ?", String::class.java, a.id, a.adminId)).isEqualTo("TENANT_ADMIN")
    }

    @Test
    fun `2d project roles on the remaining write routes - VIEWER reads only, EDITOR cannot publish, manage members or delete, PUBLISHER cannot edit the page`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = memberOf(ws, "EDITOR"); val p = fx.project(ws, owner)
        fun as_(role: String): ApiSession = loginAs(memberOf(ws, "VIEWER", p, role, "sm-${role.lowercase()}"))
        val viewer = as_("VIEWER"); val editor = as_("EDITOR"); val publisher = as_("PUBLISHER")
        val base = api(ws, p.id); val r = UUID.randomUUID()
        val schemaPatch = """{"expectedRevision":0,"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"x"}]}"""
        for ((who, s) in listOf("VIEWER" to viewer, "EDITOR" to editor, "PUBLISHER" to publisher)) {
            for (path in listOf(base, "$base/versions", "$base/deployments", "$base/assets", "$base/site")) assertThat(s.get(path).response.status).describedAs("$who GET $path").isEqualTo(200)
            forbidden(s.get("$base/members"), "$who list project members (PROJECT_MEMBERS)")
            forbidden(s.post("$base/members", """{"username":"${owner.username}","role":"VIEWER"}"""), "$who add project member")
            forbidden(s.delete("$base?expectedRevision=0"), "$who delete project")
            forbidden(s.post("$base/archive"), "$who archive")
        }
        for ((who, s) in listOf("VIEWER" to viewer, "PUBLISHER" to publisher)) {
            forbidden(s.patch("$base/schema", schemaPatch), "$who PATCH schema")
            forbidden(s.post("$base/versions/$r/restore", """{"expectedRevision":0}"""), "$who restore (permission before the id lookup)")
            forbidden(s.delete("$base/assets/$r"), "$who delete asset")
            forbidden(s.post("$base/assets/upload-url", """{"fileName":"a.png","contentType":"image/png","size":10}"""), "$who upload asset")
        }
        for ((who, s) in listOf("VIEWER" to viewer, "EDITOR" to editor)) {
            forbidden(s.post("$base/site/rollback", """{"deploymentId":"$r"}"""), "$who rollback")
            forbidden(s.delete("$base/site"), "$who unpublish")
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id = ? AND active", Long::class.java, p.id)).isEqualTo(4L)
        assertThat(jdbc.queryForObject("SELECT lifecycle FROM projects WHERE id = ?", String::class.java, p.id)).isEqualTo("ACTIVE")
    }

    // =============================================================================================================== 3. SAME-SESSION CHANGES
    @Test
    fun `3a workspace member removed and workspace role lowered under a live session - the next request and auth me agree at once`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = memberOf(ws, "EDITOR"); val p = fx.project(ws, owner)
        val wa = memberOf(ws, "WORKSPACE_ADMIN"); val was = loginAs(wa)
        val u = memberOf(ws, "EDITOR", p, "EDITOR"); val us = loginAs(u)
        val w2 = memberOf(ws, "WORKSPACE_ADMIN"); val w2s = loginAs(w2)
        assertThat(us.get(api(ws, p.id)).response.status).isEqualTo(200); assertThat(scopeIds(me(us))).contains(p.id.toString())
        assertThat(w2s.get("/api/v1/workspaces/$ws/members").response.status).isEqualTo(200); assertThat(w2s.get(api(ws, p.id)).response.status).describedAs("WORKSPACE_ADMIN sees every project").isEqualTo(200)

        assertThat(was.delete("/api/v1/workspaces/$ws/members/${u.id}").response.status).isEqualTo(204)
        expect(us.get(api(ws, p.id)), 404, "WORKSPACE_NOT_FOUND", "removed member: project")
        expect(us.get(api(ws)), 404, "WORKSPACE_NOT_FOUND", "removed member: list")
        expect(rename(us, ws, p.id, 0, "stale"), 404, "WORKSPACE_NOT_FOUND", "removed member: write")
        val m = me(us); assertThat(workspaceIds(m)).doesNotContain(ws.toString()); assertThat(scopeIds(m)).doesNotContain(p.id.toString())

        assertThat(was.patch("/api/v1/workspaces/$ws/members/${w2.id}", """{"role":"VIEWER"}""").response.status).isEqualTo(200)
        forbidden(w2s.get("/api/v1/workspaces/$ws/members"), "lowered admin: members")
        expect(w2s.get(api(ws, p.id)), 404, "PROJECT_NOT_FOUND", "lowered admin: a project it has no role on")
        assertThat(w2s.body(w2s.get(api(ws))).size()).isZero()
        assertThat(strings(workspaceRow(me(w2s), ws).get("permissions"))).doesNotContain("MEMBER_MANAGE", "APP_VIEW", "PROJECT_CREATE")
    }

    @Test
    fun `3b tenant role downgraded TENANT_ADMIN to MEMBER under a live session - organization and tenant routes 403 on the next request, auth me carries no tenant code`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val x = memberOf(ws, "VIEWER")
        assertThat(c.admin.put("${base(c)}/members/${x.id}", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
        val xs = loginAs(x)
        assertThat(xs.get(units(c)).response.status).isEqualTo(200); assertThat(xs.get("${base(c)}/members").response.status).isEqualTo(200)
        assertThat(strings(me(xs).get("permissions"))).contains("TENANT_MEMBERS", "ORG_STRUCTURE_MANAGE")

        assertThat(c.admin.put("${base(c)}/members/${x.id}", """{"role":"MEMBER"}""").response.status).isEqualTo(200)
        forbidden(xs.get(units(c)), "units"); forbidden(xs.get(employees(c)), "employees"); forbidden(xs.get("${base(c)}/members"), "members")
        forbidden(xs.post("${base(c)}/users", """{"username":"${uname("sm")}","displayName":"x"}"""), "users")
        forbidden(xs.put("${base(c)}/members/${c.adminId}", """{"role":"MEMBER"}"""), "demote the remaining admin")
        assertThat(xs.get(base(c)).response.status).describedAs("still a member: the company itself is visible").isEqualTo(200)
        val m = me(xs)
        assertThat(strings(m.get("permissions"))).isEmpty()
        assertThat(strings(m.get("tenants").toList().single { it.get("id").asString() == c.id.toString() }.get("permissions"))).isEmpty()
        assertThat(jdbc.queryForObject("SELECT role FROM tenant_members WHERE tenant_id = ? AND user_id = ?", String::class.java, c.id, c.adminId)).isEqualTo("TENANT_ADMIN")
    }

    @Test
    fun `3c a Tenant Admin globally disabled - next request 401 on the organization route, re-enable never revives the old cookie, a fresh login does`() {
        val sys = sysAdmin(); val c = company(sys)
        assertThat(c.admin.get(units(c)).response.status).isEqualTo(200)
        val cookie = c.admin.cookie(SESSION_COOKIE)!!
        assertThat(sys.patch("/api/v1/admin/users/${c.adminId}/status", """{"enabled":false}""").response.status).isEqualTo(200)
        assertThat(c.admin.get(units(c)).response.status).isEqualTo(401)
        assertThat(c.admin.get("/api/v1/auth/me").response.status).isEqualTo(401)
        assertThat(sys.patch("/api/v1/admin/users/${c.adminId}/status", """{"enabled":true}""").response.status).isEqualTo(200)
        assertThat(replay(cookie, units(c))).describedAs("old cookie after re-enable").isEqualTo(401)
        assertThat(c.admin.get(units(c)).response.status).isEqualTo(401)
        val fresh = session(); assertThat(fresh.login(c.adminName, PASSWORD).response.status).isEqualTo(200)
        assertThat(fresh.get(units(c)).response.status).isEqualTo(200)
    }

    // =============================================================================================================== 4. SUSPENDED / DELETED company
    @Test
    fun `4a SUSPENDED - the Tenant Admin is refused workspaces, accounts and member writes (403 TENANT_SUSPENDED), reads stay, the platform operator still succeeds, reactivation restores`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = memberOf(ws, "EDITOR"); val p = fx.project(ws, owner)
        val m1 = memberOf(ws, "VIEWER"); val m2 = memberOf(ws, "VIEWER"); val ms = loginAs(m1)
        fx.member(ws, entity(c.adminId), "EDITOR"); fx.projectRole(p, entity(c.adminId), "EDITOR")         // the Tenant Admin is also a workspace / project member
        val ta = c.admin
        assertThat(ta.get(api(ws, p.id)).response.status).isEqualTo(200)
        setStatus(sys, c, "SUSPENDED")

        expect(ta.post("${base(c)}/workspaces", """{"name":"Frozen"}"""), 403, "TENANT_SUSPENDED", "TA POST workspaces")
        expect(ta.post("${base(c)}/users", """{"username":"${uname("sm")}","displayName":"x"}"""), 403, "TENANT_SUSPENDED", "TA POST users")
        expect(ta.put("${base(c)}/members/${m1.id}", """{"role":"TENANT_ADMIN"}"""), 403, "TENANT_SUSPENDED", "TA PUT member")
        expect(ta.delete("${base(c)}/members/${m1.id}"), 403, "TENANT_SUSPENDED", "TA DELETE member")
        expect(ta.post(types(c), """{"code":"x","name":"X"}"""), 403, "TENANT_SUSPENDED", "TA organization write")
        for (path in listOf(base(c), "${base(c)}/members", "${base(c)}/member-candidates", units(c), employees(c))) assertThat(ta.get(path).response.status).describedAs("TA read $path").isEqualTo(200)
        // the Tenant Admin and a plain member as WORKSPACE members: business routes closed with the same code
        for ((who, s) in listOf("TA" to ta, "member" to ms)) {
            expect(s.get(api(ws)), 403, "TENANT_SUSPENDED", "$who project list")
            expect(s.get(api(ws, p.id)), 403, "TENANT_SUSPENDED", "$who project")
        }
        expect(rename(ta, ws, p.id, 0, "x"), 403, "TENANT_SUSPENDED", "TA project write")
        // a plain member asking for a tenant write learns the permission answer, not the status (permission is checked first, TenantController.kt:113 / :143)
        forbidden(ms.put("${base(c)}/members/${m2.id}", """{"role":"MEMBER"}"""), "member PUT member")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspaces WHERE tenant_id = ?", Long::class.java, c.id)).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT role FROM tenant_members WHERE tenant_id = ? AND user_id = ?", String::class.java, c.id, m1.id)).isEqualTo("MEMBER")

        // the platform operator repairs a suspended company
        assertThat(sys.post("${base(c)}/workspaces", """{"name":"By operator"}""").response.status).isEqualTo(201)
        assertThat(sys.post("${base(c)}/users", """{"username":"${uname("sm-op")}","displayName":"x"}""").response.status).isEqualTo(201)
        assertThat(sys.put("${base(c)}/members/${m1.id}", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
        assertThat(sys.delete("${base(c)}/members/${m1.id}").response.status).isEqualTo(204)

        setStatus(sys, c, "ACTIVE")
        assertThat(ta.post("${base(c)}/workspaces", """{"name":"Back"}""").response.status).isEqualTo(201)
        assertThat(ta.post("${base(c)}/users", """{"username":"${uname("sm")}","displayName":"x"}""").response.status).isEqualTo(201)
        assertThat(ta.put("${base(c)}/members/${m2.id}", """{"role":"TENANT_ADMIN"}""").response.status).isEqualTo(200)
        assertThat(ta.delete("${base(c)}/members/${m2.id}").response.status).isEqualTo(204)
        assertThat(ta.get(api(ws, p.id)).response.status).isEqualTo(200)
    }

    @Test
    fun `4b DELETED - for the Tenant Admin every tenant and organization route is 404 TENANT_NOT_FOUND exactly like a random tenant, for members every workspace route 404`() {
        val sys = sysAdmin(); val c = company(sys); val ws = wsIn(c)
        val owner = memberOf(ws, "EDITOR"); val p = fx.project(ws, owner); val m1 = memberOf(ws, "VIEWER"); val os = loginAs(owner)
        setStatus(sys, c, "DELETED")
        val ta = c.admin
        likeMissing("GET tenant", 404, "TENANT_NOT_FOUND", c.id) { ta.get("/api/v1/admin/tenants/$it") }
        likeMissing("PATCH tenant", 404, "TENANT_NOT_FOUND", c.id) { ta.patch("/api/v1/admin/tenants/$it", """{"name":"x"}""") }
        likeMissing("members", 404, "TENANT_NOT_FOUND", c.id) { ta.get("/api/v1/admin/tenants/$it/members") }
        likeMissing("POST workspaces", 404, "TENANT_NOT_FOUND", c.id) { ta.post("/api/v1/admin/tenants/$it/workspaces", """{"name":"x"}""") }
        likeMissing("POST users", 404, "TENANT_NOT_FOUND", c.id) { ta.post("/api/v1/admin/tenants/$it/users", """{"username":"sm-never-made","displayName":"x"}""") }
        likeMissing("PUT member", 404, "TENANT_NOT_FOUND", c.id) { ta.put("/api/v1/admin/tenants/$it/members/${m1.id}", """{"role":"MEMBER"}""") }
        likeMissing("DELETE member", 404, "TENANT_NOT_FOUND", c.id) { ta.delete("/api/v1/admin/tenants/$it/members/${m1.id}") }
        likeMissing("units", 404, "TENANT_NOT_FOUND", c.id) { ta.get("/api/v1/admin/tenants/$it/organization-units") }
        likeMissing("POST type", 404, "TENANT_NOT_FOUND", c.id) { ta.post("/api/v1/admin/tenants/$it/organization-unit-types", """{"code":"x","name":"X"}""") }
        likeMissing("member: project list", 404, "WORKSPACE_NOT_FOUND", ws) { os.get(api(UUID.fromString(it))) }
        likeMissing("member: project", 404, "WORKSPACE_NOT_FOUND", ws) { os.get(api(UUID.fromString(it), p.id)) }
        assertThat(workspaceIds(me(os))).doesNotContain(ws.toString()); assertThat(tenantIds(me(ta))).doesNotContain(c.id.toString())
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'sm-never-made'", Long::class.java)).isZero()
    }

    // =============================================================================================================== 5. DISCLOSURE
    @Test
    fun `5a auth me never lists a tenant, workspace or project without an ACTIVE relation - stale project row behind a removed workspace membership, disabled employee, unrelated company`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys); val c = company(sys)
        val wA = wsIn(a); val wA2 = wsIn(a, "A2"); val wB = wsIn(b); val wC = wsIn(c)
        val ownerA = memberOf(wA, "EDITOR"); val pA = fx.project(wA, ownerA); val pA2 = fx.project(wA, ownerA, "not mine")
        val ownerB = memberOf(wB, "EDITOR"); val pB = fx.project(wB, ownerB); val ownerC = memberOf(wC, "EDITOR"); val pC = fx.project(wC, ownerC)
        val x = memberOf(wA, "VIEWER", pA, "EDITOR"); fx.member(wA2, x, "VIEWER"); fx.member(wB, x, "VIEWER"); fx.projectRole(pB, x, "EDITOR")
        val xs = loginAs(x)
        val before = me(xs)
        assertThat(tenantIds(before)).contains(a.id.toString(), b.id.toString()); assertThat(scopeIds(before)).containsExactlyInAnyOrder(pA.id.toString(), pB.id.toString())

        // workspace A2 membership removed through the API
        val waA2 = memberOf(wA2, "WORKSPACE_ADMIN"); assertThat(loginAs(waA2).delete("/api/v1/workspaces/$wA2/members/${x.id}").response.status).isEqualTo(204)
        // company B: workspace membership inactive, the project row forced back to active (a stale row behind it must not resurrect anything)
        jdbc.update("UPDATE workspace_members SET active = FALSE WHERE workspace_id = ? AND user_id = ?", wB, x.id)
        jdbc.update("UPDATE project_members SET active = TRUE WHERE project_id = ? AND user_id = ?", pB.id, x.id)
        val m = me(xs)
        assertThat(workspaceIds(m)).containsExactly(wA.toString())
        assertThat(scopeIds(m)).describedAs("stale project row behind an inactive workspace membership").containsExactly(pA.id.toString())
        assertThat(tenantIds(m)).doesNotContain(c.id.toString())
        expect(xs.get(api(wB, pB.id)), 404, "WORKSPACE_NOT_FOUND", "stale project row grants nothing")
        expect(xs.get(api(wA, pA2.id)), 404, "PROJECT_NOT_FOUND", "a project without a role")
        likeMissing("unrelated company's project, through its workspace", 404, "WORKSPACE_NOT_FOUND", wC) { xs.get(api(UUID.fromString(it), pC.id)) }

        // company B disables the employee: B disappears from tenants[]
        assertThat(b.admin.post("${employees(b)}/${x.id}/disable").response.status).isEqualTo(200)
        assertThat(tenantIds(me(xs))).doesNotContain(b.id.toString(), c.id.toString()).contains(a.id.toString())
    }

    @Test
    fun `5b the member-candidates directory returns only accounts related to the tenant, and a foreign admin cannot read it`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys)
        val tag = "smdir" + UUID.randomUUID().toString().take(6)
        val former = newEmployee(a, username = "$tag-former"); val formerId = uid(former); activated(former)                // activated, then disabled by A: a former member (related)
        assertThat(a.admin.post("${employees(a)}/$formerId/disable").response.status).isEqualTo(200)
        val foreign = newEmployee(b, username = "$tag-foreign"); activated(foreign)                                            // B only
        val current = newEmployee(a, username = "$tag-current"); activated(current)                                           // already an active member: not a candidate
        val stranger = fx.user(tag + "-stranger"); jdbc.update("UPDATE users SET activated_at = now() WHERE id = ?", stranger.id)    // no relation at all
        val op = fx.user(tag + "-op", systemAdmin = true); jdbc.update("UPDATE users SET activated_at = now() WHERE id = ?", op.id)
        jdbc.update("INSERT INTO tenant_members (tenant_id, user_id, role, active) VALUES (?, ?, 'MEMBER', false)", a.id, op.id)   // even a related platform operator is never listed
        val list = a.admin.body(a.admin.get("${base(a)}/member-candidates?q=$tag")).toList().map { it.get("userId").asString() }
        assertThat(list).containsExactly(formerId.toString())
        assertThat(a.admin.body(a.admin.get("${base(a)}/member-candidates")).toList().map { it.get("userId").asString() })
            .describedAs("no filter: still only related accounts").doesNotContain(uid(foreign).toString(), stranger.id.toString(), op.id.toString(), uid(current).toString())
        likeMissing("B's admin reads A's directory", 404, "TENANT_NOT_FOUND", a.id) { b.admin.get("/api/v1/admin/tenants/$it/member-candidates?q=$tag") }
        likeMissing("add a stranger by id", 404, "USER_NOT_FOUND", stranger.id) { a.admin.put("${base(a)}/members/$it", """{"role":"MEMBER"}""") }
        likeMissing("add a platform operator by id", 404, "USER_NOT_FOUND", op.id) { a.admin.put("${base(a)}/members/$it", """{"role":"MEMBER"}""") }
    }

    @Test
    fun `5c a foreign user id on the global and member routes of a Tenant Admin answers exactly like a random id`() {
        val sys = sysAdmin(); val a = company(sys); val b = company(sys); val wA = wsIn(a)
        val wa = memberOf(wA, "WORKSPACE_ADMIN"); val was = loginAs(wa)
        val eb = uid(newEmployee(b))
        likeMissing("TA global user status", 403, "ADMIN_REQUIRED", eb) { a.admin.patch("/api/v1/admin/users/$it/status", """{"enabled":false}""") }
        likeMissing("TA global user detail", 403, "ADMIN_REQUIRED", eb) { a.admin.get("/api/v1/admin/users/$it") }
        likeMissing("workspace admin PATCH a foreign user", 404, "MEMBER_NOT_FOUND", eb) { was.patch("/api/v1/workspaces/$wA/members/$it", """{"role":"VIEWER"}""") }
        likeMissing("workspace admin DELETE a foreign user", 404, "MEMBER_NOT_FOUND", eb) { was.delete("/api/v1/workspaces/$wA/members/$it") }
        for (id in listOf(eb, UUID.randomUUID())) {
            val r = a.admin.get("${employees(a)}?userId=$id")
            assertThat(r.response.status).isEqualTo(200); assertThat(a.admin.body(r).get("total").asLong()).describedAs("employee filter by a foreign / random userId").isZero()
        }
    }
}
