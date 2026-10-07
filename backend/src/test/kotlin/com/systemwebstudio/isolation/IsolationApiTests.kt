package com.systemwebstudio.isolation

import com.systemwebstudio.support.ApiSession
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MvcResult
import java.util.UUID

/**
 * C1 · T1 — isolation / IDOR / BOLA matrix.
 *
 * One table of every project-scoped route (including routes that are only meaningful for code or server apps: the access check runs first, so they
 * must already answer 404 for an outsider) is replayed with every wrong combination of workspace id, project id and identity. The expectation is
 * always the same: the caller learns nothing (404 with the access-layer code), cannot act (403) or is not signed in (401).
 * See docs/parallel/audit/T1-isolation-audit.md for the inventory this table was derived from.
 */
class IsolationApiTests : IntegrationTestBase() {
    private class Ep(val method: String, val suffix: String, val body: String? = null, val headers: List<Pair<String, String>> = emptyList()) {
        override fun toString() = "$method $suffix"
        val isCode get() = suffix.startsWith("/code")
    }

    private val fake: UUID = UUID.randomUUID()
    private val ws = "/api/v1/workspaces"

    /** valid-shaped bodies on purpose: bean validation runs before the controller, so a malformed body would hide a missing access check behind a 400 */
    private val endpoints: List<Ep> = listOf(
        Ep("GET", ""), Ep("PATCH", "", """{"expectedRevision":0,"name":"hijack"}"""), Ep("POST", "/archive"), Ep("POST", "/restore"),
        Ep("GET", "/schema"),
        Ep("PATCH", "/schema", """{"expectedRevision":0,"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"x"}]}"""),
        Ep("GET", "/versions"), Ep("GET", "/versions/$fake"), Ep("POST", "/versions/$fake/restore", """{"expectedRevision":0}"""),
        Ep("GET", "/prompts"), Ep("POST", "/prompts", """{"prompt":"hello","expectedRevision":0}"""),
        Ep("POST", "/publish", """{"visibility":"PUBLIC","expectedRevision":0}""", listOf("Idempotency-Key" to "isolation-key-0001")),
        Ep("GET", "/deployments"), Ep("GET", "/deployments/$fake"),
        Ep("GET", "/site"), Ep("POST", "/site/rollback", """{"deploymentId":"$fake"}"""), Ep("DELETE", "/site"),
        Ep("GET", "/domains"), Ep("POST", "/domains", """{"hostname":"www.isolation-probe.example"}"""),
        Ep("POST", "/domains/$fake/verify"), Ep("POST", "/domains/$fake/check-tls"), Ep("DELETE", "/domains/$fake"),
        Ep("GET", "/form-submissions"), Ep("GET", "/form-submissions/export"), Ep("DELETE", "/form-submissions/$fake"),
        Ep("GET", "/assets"), Ep("POST", "/assets/upload-url", """{"fileName":"a.png","contentType":"image/png","size":1024}"""),
        Ep("POST", "/assets/complete", """{"assetId":"$fake"}"""), Ep("DELETE", "/assets/$fake"),
        Ep("GET", "/members"), Ep("POST", "/members", """{"username":"nobody","role":"VIEWER"}"""),
        Ep("PATCH", "/members/$fake", """{"role":"VIEWER"}"""), Ep("DELETE", "/members/$fake"),
        Ep("POST", "/templates", """{"name":"probe"}"""), Ep("POST", "/component-packages", """{"sectionId":"hero-1","name":"probe"}"""),
        Ep("GET", "/runtime"), Ep("POST", "/runtime/rollback", """{"deploymentId":"$fake"}"""), Ep("POST", "/runtime/stop"),
        Ep("PUT", "/runtime/secrets", """{"name":"API_KEY","value":"v"}"""), Ep("DELETE", "/runtime/secrets/API_KEY"),
        Ep("GET", "/code/tree"), Ep("GET", "/code/file?path=README.md"), Ep("GET", "/code/commits"), Ep("GET", "/code/changes"),
        Ep("GET", "/code/changes/$fake"), Ep("GET", "/code/changes/$fake/diff"), Ep("POST", "/code/changes", """{"summary":"probe","files":[]}"""),
        Ep("POST", "/code/changes/$fake/approve"), Ep("POST", "/code/changes/$fake/discard"), Ep("POST", "/code/changes/$fake/merge"),
        Ep("PUT", "/code/merge-policy", """{"policy":"AUTO_MERGE_ALLOWED"}"""), Ep("POST", "/code/clone-access"),
        Ep("GET", "/code/dependencies"), Ep("POST", "/code/dependencies", """{"name":"left-pad"}"""),
        Ep("GET", "/code/design"), Ep("POST", "/code/design/edit", """{"path":"src/App.tsx","nodeId":"n1"}"""),
        Ep("GET", "/code/ai"), Ep("POST", "/code/ai", """{"prompt":"probe"}""")
    )

    private fun call(s: ApiSession, base: String, ep: Ep): MvcResult {
        val path = base + ep.suffix
        return when (ep.method) {
            "GET" -> s.get(path)
            "POST" -> s.post(path, ep.body ?: "{}", *ep.headers.toTypedArray())
            "PUT" -> s.put(path, ep.body ?: "{}")
            "PATCH" -> s.patch(path, ep.body ?: "{}")
            "DELETE" -> s.delete(path)
            else -> error("unsupported ${ep.method}")
        }
    }

    private fun code(s: ApiSession, r: MvcResult): String? = runCatching { s.body(r).get("code")?.asString() }.getOrNull()

    /** every endpoint must answer with `status` (and, when given, one of `codes`); all misses are reported together */
    private fun expectAll(who: String, s: ApiSession, base: String, eps: List<Ep>, status: Int, codes: Set<String>? = null) {
        val misses = eps.mapNotNull { ep ->
            val r = call(s, base, ep)
            val c = code(s, r)
            if (r.response.status == status && (codes == null || c in codes)) null else "$who: $ep -> ${r.response.status}/$c (wanted $status${codes?.let { " $it" } ?: ""})"
        }
        assertThat(misses).describedAs("endpoints that answered differently from the isolation contract").isEmpty()
    }

    private val hidden = setOf("WORKSPACE_NOT_FOUND", "PROJECT_NOT_FOUND")

    private fun count(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    @Test
    fun `an outsider learns nothing and changes nothing through any combination of foreign workspace and project ids`() {
        val a = scenario(); val b = scenario()
        val before = count("SELECT count(*) FROM audit_events WHERE project_id = ?", a.projectId)
        expectAll("foreign workspace + foreign project", b.s, a.base, endpoints, 404, hidden)
        expectAll("own workspace + foreign project id", b.s, api(b.ws, a.projectId), endpoints, 404, hidden)
        expectAll("foreign workspace + own project id", b.s, api(a.ws, b.projectId), endpoints, 404, hidden)
        // the victim's project is untouched: still active, same name, no new member/domain/asset/deployment/audit rows
        assertThat(count("SELECT count(*) FROM projects WHERE id = ? AND active AND name = 'Scenario'", a.projectId)).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM project_members WHERE project_id = ?", a.projectId)).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM site_domains WHERE project_id = ?", a.projectId)).isEqualTo(0L)
        assertThat(count("SELECT count(*) FROM assets WHERE project_id = ?", a.projectId)).isEqualTo(0L)
        assertThat(count("SELECT count(*) FROM deployments WHERE project_id = ?", a.projectId)).isEqualTo(0L)
        assertThat(count("SELECT count(*) FROM audit_events WHERE project_id = ?", a.projectId)).isEqualTo(before)
        assertThat(count("SELECT count(*) FROM projects WHERE workspace_id = ?", a.ws)).isEqualTo(1L)
    }

    @Test
    fun `a workspace member without a role on the project cannot see it or act on it`() {
        val a = scenario()
        val mate = fx.user("mate"); fx.member(a.ws, mate, "EDITOR")
        expectAll("workspace EDITOR without project role", sessionFor(mate.username), a.base, endpoints, 404, hidden)
        val viewerMate = fx.user("vmate"); fx.member(a.ws, viewerMate, "VIEWER")
        expectAll("workspace VIEWER without project role", sessionFor(viewerMate.username), a.base, endpoints, 404, hidden)
    }

    @Test
    fun `without a session every project route is 401`() {
        val a = scenario()
        expectAll("anonymous", session(), a.base, endpoints, 401)
        assertThat(session().get("$ws/${a.ws}/projects").response.status).isEqualTo(401)
        assertThat(session().get("$ws/${a.ws}/audit-events").response.status).isEqualTo(401)
        assertThat(session().get("$ws/${a.ws}/merge-policy").response.status).isEqualTo(401)
    }

    @Test
    fun `workspace-level routes of a foreign workspace are hidden and create nothing`() {
        val a = scenario(); val b = scenario()
        val projectsBefore = count("SELECT count(*) FROM projects WHERE workspace_id = ?", a.ws)
        val membersBefore = count("SELECT count(*) FROM workspace_members WHERE workspace_id = ?", a.ws)
        val outsider = b.s
        fun status(r: MvcResult) = r.response.status to code(outsider, r)
        val expected = 404 to "WORKSPACE_NOT_FOUND"
        assertThat(status(outsider.get("$ws/${a.ws}/projects"))).isEqualTo(expected)
        assertThat(status(outsider.post("$ws/${a.ws}/projects", """{"name":"planted"}"""))).isEqualTo(expected)
        assertThat(status(outsider.get("$ws/${a.ws}/members"))).isEqualTo(expected)
        assertThat(status(outsider.post("$ws/${a.ws}/members", """{"username":"${b.user.username}","role":"WORKSPACE_ADMIN"}"""))).isEqualTo(expected)
        assertThat(status(outsider.patch("$ws/${a.ws}/members/${a.user.id}", """{"role":"VIEWER"}"""))).isEqualTo(expected)
        assertThat(status(outsider.delete("$ws/${a.ws}/members/${a.user.id}"))).isEqualTo(expected)
        assertThat(status(outsider.get("$ws/${a.ws}/audit-events?projectId=${a.projectId}"))).isEqualTo(expected)
        assertThat(status(outsider.get("$ws/${a.ws}/merge-policy"))).isEqualTo(expected)
        assertThat(status(outsider.put("$ws/${a.ws}/merge-policy", """{"policy":"AUTO_MERGE_ALLOWED"}"""))).isEqualTo(expected)
        assertThat(count("SELECT count(*) FROM projects WHERE workspace_id = ?", a.ws)).isEqualTo(projectsBefore)
        assertThat(count("SELECT count(*) FROM workspace_members WHERE workspace_id = ?", a.ws)).isEqualTo(membersBefore)
        // the outsider's own project list never contains the victim's project
        assertThat(outsider.body(outsider.get("$ws/${b.ws}/projects")).toList().map { it.get("id").asString() }).doesNotContain(a.projectId.toString())
    }

    @Test
    fun `audit events are scoped to the caller's workspace even when a foreign project id is passed as a filter`() {
        val a = scenario()
        val adminA = fx.user("auditA"); fx.member(a.ws, adminA, "WORKSPACE_ADMIN")
        a.s.patch(a.base, """{"expectedRevision":${a.revision()},"name":"Renamed"}""")                    // make sure A has events
        assertThat(sessionFor(adminA.username).get("$ws/${a.ws}/audit-events?projectId=${a.projectId}").response.status).isEqualTo(200)

        val evil = fx.user("auditB"); val wsB = fx.workspace(); fx.member(wsB, evil, "WORKSPACE_ADMIN")
        val s = sessionFor(evil.username)
        val filtered = s.get("$ws/$wsB/audit-events?projectId=${a.projectId}")
        assertThat(filtered.response.status).isEqualTo(200)
        assertThat(s.body(filtered).size()).isEqualTo(0)
        val all = s.body(s.get("$ws/$wsB/audit-events?limit=200")).toList()
        assertThat(all.map { it.get("projectId")?.asString() }).doesNotContain(a.projectId.toString())
        assertThat(s.get("$ws/${a.ws}/audit-events").response.status).isEqualTo(404)
    }

    @Test
    fun `ids of another project's versions, assets, domains and deployments are not usable through my own project`() {
        val a = scenario(); val b = scenario()
        val versionA = a.s.body(a.s.get("${a.base}/versions")).get(0).get("id").asString()
        val assetA = a.s.body(a.s.post("${a.base}/assets/upload-url", """{"fileName":"a.png","contentType":"image/png","size":1024}""")).get("assetId").asString()
        val domainA = a.s.body(a.s.post("${a.base}/domains", """{"hostname":"www.iso-${UUID.randomUUID().toString().take(8)}.example"}""")).get("id").asString()
        val deploymentA = a.s.body(a.s.post("${a.base}/publish", """{"visibility":"PUBLIC","expectedRevision":${a.revision()}}""", "Idempotency-Key" to "iso-${UUID.randomUUID()}")).get("id").asString()

        val versionsB = b.versionCount(); val revB = b.revision()
        assertThat(b.s.get("${b.base}/versions/$versionA").response.status).isEqualTo(404)
        assertThat(b.s.post("${b.base}/versions/$versionA/restore", """{"expectedRevision":$revB}""").response.status).isEqualTo(404)
        assertThat(b.s.delete("${b.base}/assets/$assetA").response.status).isEqualTo(404)
        assertThat(b.s.post("${b.base}/assets/complete", """{"assetId":"$assetA"}""").response.status).isEqualTo(404)
        assertThat(b.s.post("${b.base}/domains/$domainA/verify").response.status).isEqualTo(404)
        assertThat(b.s.post("${b.base}/domains/$domainA/check-tls").response.status).isEqualTo(404)
        assertThat(b.s.delete("${b.base}/domains/$domainA").response.status).isEqualTo(404)
        assertThat(b.s.get("${b.base}/deployments/$deploymentA").response.status).isEqualTo(404)
        // not restorable is reported as 400 (nothing to point at) or 404: either way it must not be served by B
        assertThat(b.s.post("${b.base}/site/rollback", """{"deploymentId":"$deploymentA"}""").response.status).isIn(400, 404)

        assertThat(b.versionCount()).isEqualTo(versionsB); assertThat(b.revision()).isEqualTo(revB)
        assertThat(count("SELECT count(*) FROM assets WHERE id = ? AND status = 'PENDING'", UUID.fromString(assetA))).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM site_domains WHERE id = ? AND project_id = ? AND status = 'PENDING'", UUID.fromString(domainA), a.projectId)).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM sites WHERE project_id = ?", b.projectId)).isEqualTo(0L)
        assertThat(count("SELECT count(*) FROM deployments WHERE project_id = ?", b.projectId)).isEqualTo(0L)
        assertThat(b.s.body(b.s.get("${b.base}/assets")).size()).isEqualTo(0)
        assertThat(b.s.body(b.s.get("${b.base}/domains")).size()).isEqualTo(0)
        assertThat(b.s.body(b.s.get("${b.base}/deployments")).size()).isEqualTo(0)
        // and the owner still reaches all of them
        assertThat(a.s.get("${a.base}/versions/$versionA").response.status).isEqualTo(200)
        assertThat(a.s.get("${a.base}/deployments/$deploymentA").response.status).isEqualTo(200)
    }

    private val writesWithoutCode get() = endpoints.filter { it.method != "GET" && !it.isCode }

    @Test
    fun `a project viewer reads the basics, cannot read form data and cannot write anything`() {
        val sc = scenario(); val project = fx.projects.findById(sc.projectId).get()
        val viewer = fx.user("viewer"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(project, viewer, "VIEWER")
        val v = sessionFor(viewer.username)
        for (path in listOf("", "/schema", "/versions", "/deployments", "/assets", "/prompts"))
            assertThat(v.get(sc.base + path).response.status).describedAs("viewer GET $path").isEqualTo(200)
        // submissions contain visitors' personal data: PROJECT_EDIT, not PROJECT_READ
        assertThat(v.get("${sc.base}/form-submissions").response.status).isEqualTo(403)
        assertThat(v.get("${sc.base}/form-submissions/export").response.status).isEqualTo(403)
        assertThat(v.get("${sc.base}/members").response.status).isEqualTo(403)
        expectAll("viewer write", v, sc.base, writesWithoutCode, 403)
        assertThat(count("SELECT count(*) FROM projects WHERE id = ? AND name = 'Scenario' AND active", sc.projectId)).isEqualTo(1L)
        assertThat(count("SELECT count(*) FROM project_members WHERE project_id = ?", sc.projectId)).isEqualTo(2L)
    }

    @Test
    fun `an archived project is read-only for its owner too`() {
        val sc = scenario()
        assertThat(sc.s.post("${sc.base}/archive").response.status).isEqualTo(200)
        for (path in listOf("", "/schema", "/versions", "/deployments", "/assets"))
            assertThat(sc.s.get(sc.base + path).response.status).describedAs("archived GET $path").isEqualTo(200)
        expectAll("archived owner write", sc.s, sc.base, writesWithoutCode.filter { it.suffix != "/archive" && it.suffix != "/restore" }, 403)
        assertThat(sc.s.post("${sc.base}/archive").response.status).isEqualTo(409)
        assertThat(sc.s.post("${sc.base}/restore").response.status).isEqualTo(200)                       // the owner can bring it back; nothing else changed
        assertThat(sc.s.patch(sc.base, """{"expectedRevision":${sc.revision()},"name":"Back"}""").response.status).isEqualTo(200)
    }

    @Test
    fun `a disabled account loses every route on its next request, reads and writes alike`() {
        val sc = scenario(); val project = fx.projects.findById(sc.projectId).get()
        // one account per probe: the first refused request ends a session, so each probe needs its own signed-in session
        val probes = listOf(Ep("GET", ""), Ep("GET", "/schema"), Ep("GET", "/members"), Ep("PATCH", "", """{"expectedRevision":0,"name":"late"}"""), Ep("POST", "/archive"))
        val users = probes.map { fx.user("gone").also { u -> fx.member(sc.ws, u, "EDITOR"); fx.projectRole(project, u, "OWNER") } }
        val sessions = users.map { sessionFor(it.username) }
        sessions.forEach { assertThat(it.get(sc.base).response.status).isEqualTo(200) }
        users.forEach { fx.disable(it.id) }
        val misses = probes.indices.mapNotNull { i ->
            val r = call(sessions[i], sc.base, probes[i]); val c = code(sessions[i], r)
            if (r.response.status == 401 && c == "ACCOUNT_DISABLED") null else "${probes[i]} -> ${r.response.status}/$c"
        }
        assertThat(misses).isEmpty()
        assertThat(count("SELECT count(*) FROM projects WHERE id = ? AND name = 'Scenario' AND active AND lifecycle = 'ACTIVE'", sc.projectId)).isEqualTo(1L)
        assertThat(session().login(users[0].username).response.status).isIn(401, 403)                    // and the account cannot sign in again
    }

    @Test
    fun `a role in one workspace grants nothing in another`() {
        val a = scenario(); val project = fx.projects.findById(a.projectId).get()
        val dual = fx.user("dual"); fx.member(a.ws, dual, "VIEWER"); fx.projectRole(project, dual, "VIEWER")
        val wsB = fx.workspace(); fx.member(wsB, dual, "WORKSPACE_ADMIN")
        val s = sessionFor(dual.username)
        assertThat(s.get("$ws/$wsB/members").response.status).isEqualTo(200)                              // admin where they are admin
        assertThat(s.get("$ws/${a.ws}/members").response.status).isEqualTo(403)                           // plain viewer where they are not
        assertThat(s.get("$ws/${a.ws}/audit-events").response.status).isEqualTo(403)
        assertThat(s.post("${a.base}/members", """{"username":"${fx.user("friend").username}","role":"VIEWER"}""").response.status).isEqualTo(403)
        assertThat(s.patch(a.base, """{"expectedRevision":${a.revision()},"name":"mine now"}""").response.status).isEqualTo(403)
        assertThat(s.get("$ws/${a.ws}/projects/${a.projectId}").response.status).isEqualTo(200)
        // the project id of A under B's workspace id (where they are admin) is still not found
        assertThat(s.get(api(wsB, a.projectId)).response.status).isEqualTo(404)
        assertThat(s.patch(api(wsB, a.projectId), """{"expectedRevision":0,"name":"hijack"}""").response.status).isEqualTo(404)
    }
}
