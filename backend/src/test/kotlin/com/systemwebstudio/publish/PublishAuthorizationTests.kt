package com.systemwebstudio.publish

import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

/**
 * Who may publish, roll back and unpublish is decided by the SERVER, from the current database state, for every request: `AccessService.forProject(...)`
 * then `AccessContext.require(Permission.PROJECT_PUBLISH)` (the storage constant of the canonical code APP_PUBLISH). Nothing here goes through the UI: every
 * call is a direct API call (MockMvc, real session cookie, real CSRF token), so no frontend gating is part of the proof.
 *
 * Roles used (PermissionMatrix, not changed here): project VIEWER = APP_VIEW only, EDITOR = APP_EDIT (no publish), PUBLISHER = APP_PUBLISH. The workspace role of
 * every actor is VIEWER, which holds nothing, so the project role is the whole grant.
 *
 * A refused request must leave nothing behind, and each test compares a footprint before and after: deployments and their statuses, events, artifacts,
 * idempotency keys, the audit entries of the three operations, the active pointer and its version.
 */
class PublishAuthorizationTests : ScopeIntegrationTestBase() {
    @Autowired lateinit var sites: SiteService
    @Autowired lateinit var store: ArtifactStore

    // ------------------------------------------------------------------ helpers

    private class Snap(val deployments: List<String>, val events: Long, val artifacts: Long, val keys: Long, val audits: Long, val pointer: String)

    private fun snap(projectId: UUID) = Snap(
        jdbc.queryForList("SELECT id::text || ':' || status FROM deployments WHERE project_id = ? ORDER BY id", String::class.java, projectId),
        jdbc.queryForObject("SELECT count(*) FROM deployment_events e JOIN deployments d ON d.id = e.deployment_id WHERE d.project_id = ?", Long::class.java, projectId)!!,
        jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ?", Long::class.java, projectId)!!,
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE scope_key LIKE ?", Long::class.java, "%:$projectId:%")!!,
        jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE project_id = ? AND action IN ('PUBLISH', 'SITE_ROLLBACK', 'SITE_UNPUBLISHED')", Long::class.java, projectId)!!,
        jdbc.queryForList("SELECT coalesce(current_deployment_id::text, '-') || '/' || pointer_version FROM sites WHERE project_id = ?", String::class.java, projectId).firstOrNull() ?: "no-site-row")

    /** a refused request changes none of the records above (a job could only ever be enqueued for a deployment row, so a late one is given time to show) */
    private fun assertUnchanged(projectId: UUID, before: Snap, what: String, keysToo: Boolean = true) {
        Thread.sleep(500)
        val after = snap(projectId)
        assertThat(after.deployments).describedAs("$what: deployments and their statuses").isEqualTo(before.deployments)
        assertThat(after.events).describedAs("$what: deployment events").isEqualTo(before.events)
        assertThat(after.artifacts).describedAs("$what: artifacts").isEqualTo(before.artifacts)
        if (keysToo) assertThat(after.keys).describedAs("$what: idempotency keys").isEqualTo(before.keys)
        assertThat(after.audits).describedAs("$what: publish / rollback / unpublish audit entries").isEqualTo(before.audits)
        assertThat(after.pointer).describedAs("$what: active pointer and its version").isEqualTo(before.pointer)
    }

    private class Rel(val id: UUID)

    /** a real, verifiable release of [projectId]: a RUNNING deployment with an artifact whose file is in the store */
    private fun release(sc: Scenario, projectId: UUID = sc.projectId): Rel {
        sites.ensureSlug(projectId, "Auth")
        val bytes = ("<h1>auth</h1>" + UUID.randomUUID()).toByteArray()
        val files = listOf(ManifestFile("index.html", bytes.size, StaticSiteBuilder.sha256(bytes), "text/html; charset=utf-8"))
        val sha = StaticSiteBuilder.sha256(json.writeValueAsString(files).toByteArray()); val prefix = "$projectId/$sha"
        val art = UUID.randomUUID(); val dep = UUID.randomUUID()
        store.putOnce("$prefix/index.html", bytes, "text/html")
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,?,1,?,CAST(? AS jsonb))", art, projectId, sha, prefix, bytes.size, json.writeValueAsString(files))
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, projectId)
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id) VALUES (?,?,?,?,?,'PUBLIC','RUNNING','static',?)", dep, sc.ws, projectId, version, sc.user.id, art)
        return Rel(dep)
    }

    private fun activate(projectId: UUID, r: Rel) {
        jdbc.update("""UPDATE sites SET current_deployment_id = ?, pointer_version = pointer_version + 1, active_seq = (SELECT activation_seq FROM deployments WHERE id = ?), active_operation_id = ?
            WHERE project_id = ?""", r.id, r.id, r.id, projectId)
    }

    private fun pointer(projectId: UUID) = jdbc.queryForList("SELECT current_deployment_id FROM sites WHERE project_id = ?", UUID::class.java, projectId).firstOrNull()

    /** a second application in the same workspace as [sc], created through the API by the owner */
    private fun secondApp(sc: Scenario): Scenario {
        val id = UUID.fromString(sc.s.body(sc.s.post(api(sc.ws), """{"name":"Second"}""")).get("id").asString())
        return Scenario(sc.user, sc.ws, id, sc.s, api(sc.ws, id))
    }

    private class Actor(val userId: UUID, val username: String, val s: ApiSession)

    /** a signed-in user whose only grant on the application is [projectRole] (workspace role VIEWER holds no permission) */
    private fun actor(sc: Scenario, projectRole: String): Actor {
        val u = fx.user(projectRole.lowercase()); fx.member(sc.ws, u, "VIEWER"); fx.projectRole(fx.projects.findById(sc.projectId).get(), u, projectRole)
        return Actor(u.id, u.username, sessionFor(u.username))
    }

    private fun publishBody(sc: Scenario) = """{"visibility":"PUBLIC","expectedRevision":${sc.revision()}}"""
    private fun publish(base: String, s: ApiSession, body: String, key: String = "auth-" + UUID.randomUUID()) = s.post("$base/publish", body, "Idempotency-Key" to key)
    private fun rollback(base: String, s: ApiSession, to: UUID, key: String = "auth-" + UUID.randomUUID()) = s.post("$base/site/rollback", """{"deploymentId":"$to"}""", "Idempotency-Key" to key)
    private fun unpublish(base: String, s: ApiSession) = s.delete("$base/site")

    private fun code(r: org.springframework.test.web.servlet.MvcResult, s: ApiSession) = runCatching { s.body(r).get("code").asString() }.getOrNull()

    /** an application with an older and a newer release, the newer one active */
    private fun published(): Triple<Scenario, Rel, Rel> {
        val sc = scenario(); val older = release(sc); val newer = release(sc); activate(sc.projectId, newer); return Triple(sc, older, newer)
    }

    // ------------------------------------------------------------------ A. publish

    @Test
    fun `publish - APP_VIEW only is 403 and creates nothing`() {
        val sc = scenario(); val before = snap(sc.projectId); val v = actor(sc, "VIEWER")
        val r = publish(sc.base, v.s, publishBody(sc))
        assertThat(r.response.status).isEqualTo(403)
        assertThat(v.s.body(r).get("message").asString()).contains("PROJECT_PUBLISH")
        assertUnchanged(sc.projectId, before, "APP_VIEW publish")
    }

    @Test
    fun `publish - APP_EDIT only is 403 and creates nothing`() {
        val sc = scenario(); val before = snap(sc.projectId); val e = actor(sc, "EDITOR")
        assertThat(publish(sc.base, e.s, publishBody(sc)).response.status).isEqualTo(403)
        assertUnchanged(sc.projectId, before, "APP_EDIT publish")
    }

    @Test
    fun `publish - APP_PUBLISH is accepted by the normal contract, and the publisher cannot edit`() {
        val sc = scenario(); val before = snap(sc.projectId); val p = actor(sc, "PUBLISHER")
        val r = publish(sc.base, p.s, publishBody(sc))
        assertThat(r.response.status).isEqualTo(202)
        val id = p.s.body(r).get("id").asString()
        assertThat(snap(sc.projectId).deployments.size).isEqualTo(before.deployments.size + 1)
        await().atMost(Duration.ofSeconds(30)).until { p.s.body(p.s.get("${sc.base}/deployments/$id")).get("status").asString() in setOf("RUNNING", "FAILED") }
        // the grant is publish only: no edit
        assertThat(p.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"x"}]}""").response.status).isEqualTo(403)
    }

    @Test
    fun `direct API calls without a session are 401 for all three operations and change nothing`() {
        val (sc, older, _) = published(); val before = snap(sc.projectId)
        assertThat(publish(sc.base, session(), publishBody(sc)).response.status).isEqualTo(401)
        assertThat(rollback(sc.base, session(), older.id).response.status).isEqualTo(401)
        assertThat(unpublish(sc.base, session()).response.status).isEqualTo(401)
        assertUnchanged(sc.projectId, before, "anonymous")
    }

    // ------------------------------------------------------------------ B. rollback

    @Test
    fun `rollback - APP_VIEW and APP_EDIT are 403 and the pointer, its version and every status are unchanged`() {
        val (sc, older, _) = published(); val before = snap(sc.projectId)
        for (role in listOf("VIEWER", "EDITOR")) {
            val a = actor(sc, role)
            assertThat(rollback(sc.base, a.s, older.id).response.status).describedAs("$role rollback").isEqualTo(403)
        }
        assertUnchanged(sc.projectId, before, "rollback")
    }

    @Test
    fun `rollback - APP_PUBLISH is allowed`() {
        val (sc, older, _) = published(); val p = actor(sc, "PUBLISHER")
        assertThat(rollback(sc.base, p.s, older.id).response.status).isEqualTo(200)
        assertThat(pointer(sc.projectId)).isEqualTo(older.id)
    }

    // ------------------------------------------------------------------ C. unpublish

    @Test
    fun `unpublish - APP_VIEW and APP_EDIT are 403 and the site stays online`() {
        val (sc, _, newer) = published(); val before = snap(sc.projectId)
        for (role in listOf("VIEWER", "EDITOR")) {
            val a = actor(sc, role)
            assertThat(unpublish(sc.base, a.s).response.status).describedAs("$role unpublish").isEqualTo(403)
        }
        assertUnchanged(sc.projectId, before, "unpublish")
        assertThat(pointer(sc.projectId)).isEqualTo(newer.id)
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("online").asBoolean()).isTrue()
    }

    @Test
    fun `unpublish - APP_PUBLISH is allowed`() {
        val (sc, _, _) = published(); val p = actor(sc, "PUBLISHER")
        assertThat(unpublish(sc.base, p.s).response.status).isEqualTo(200)
        assertThat(pointer(sc.projectId)).isNull()
    }

    // ------------------------------------------------------------------ cross tenant / workspace / project

    @Test
    fun `a publisher of ANOTHER workspace gets 404 on all three operations, with the right ids and with a forged workspace and project pair`() {
        val (sc, older, _) = published(); val before = snap(sc.projectId)
        val other = scenario()                                               // its owner holds APP_PUBLISH, in a different workspace and tenant
        val forged = "/api/v1/workspaces/${other.ws}/projects/${sc.projectId}"      // a workspace the caller belongs to, a project of another workspace
        for (base in listOf(sc.base, forged)) {
            val r = publish(base, other.s, publishBody(sc)); assertThat(r.response.status).describedAs("publish $base").isEqualTo(404)
            assertThat(rollback(base, other.s, older.id).response.status).describedAs("rollback $base").isEqualTo(404)
            assertThat(unpublish(base, other.s).response.status).describedAs("unpublish $base").isEqualTo(404)
        }
        assertUnchanged(sc.projectId, before, "cross tenant")
        assertThat(snap(other.projectId).deployments).isEmpty()
    }

    @Test
    fun `the publisher of application A has no authority over application B of the same workspace`() {
        val a = scenario(); val b = secondApp(a); val relB = release(a, b.projectId); activate(b.projectId, relB)
        val publisherOfA = actor(a, "PUBLISHER"); val beforeB = snap(b.projectId)
        assertThat(publish(b.base, publisherOfA.s, publishBody(b)).response.status).isEqualTo(404)       // no grant on B: B is not even visible
        assertThat(rollback(b.base, publisherOfA.s, relB.id).response.status).isEqualTo(404)
        assertThat(unpublish(b.base, publisherOfA.s).response.status).isEqualTo(404)
        assertUnchanged(b.projectId, beforeB, "cross project")
        assertThat(pointer(b.projectId)).isEqualTo(relB.id)
    }

    @Test
    fun `a forged deploymentId of another application cannot cross the project boundary in a rollback`() {
        val a = scenario(); val b = secondApp(a)
        val relA = release(a); activate(a.projectId, relA); val relB = release(a, b.projectId); activate(b.projectId, relB)
        val beforeA = snap(a.projectId); val beforeB = snap(b.projectId)
        // the caller really is entitled to roll A back (owner), but the deployment named belongs to B (and an unknown id belongs to nobody)
        for (forged in listOf(relB.id, UUID.randomUUID())) {
            val r = rollback(a.base, a.s, forged)
            assertThat(r.response.status).describedAs("forged $forged").isEqualTo(400)
            assertThat(a.s.body(r).get("code").asString()).isEqualTo("DEPLOYMENT_NOT_RESTORABLE")
        }
        // the caller IS authorized for A, so its Idempotency-Key is judged (and kept) before the deployment is looked at - by design (SiteControllers.rollback); a
        // key row binds that key to that one request and moves nothing. Everything that matters to the release is compared.
        assertUnchanged(a.projectId, beforeA, "forged deploymentId, application A", keysToo = false)
        assertUnchanged(b.projectId, beforeB, "forged deploymentId, application B")
    }

    // ------------------------------------------------------------------ the grant is read from the database on every request

    @Test
    fun `a permission revoked while the SAME session is alive is refused on the next publish, rollback and unpublish`() {
        val (sc, older, _) = published(); val p = actor(sc, "PUBLISHER")
        // 1. the session works: a rollback to the release that is already active is accepted and changes nothing
        val active = pointer(sc.projectId)!!
        assertThat(rollback(sc.base, p.s, active).response.status).describedAs("granted before the revocation").isEqualTo(200)
        // 2. the grant is downgraded in the database; the session is NOT closed and nothing is logged in again
        jdbc.update("UPDATE project_members SET role = 'VIEWER' WHERE project_id = ? AND user_id = ?", sc.projectId, p.userId)
        val before = snap(sc.projectId)
        assertThat(publish(sc.base, p.s, publishBody(sc)).response.status).describedAs("publish after revocation").isEqualTo(403)
        assertThat(rollback(sc.base, p.s, older.id).response.status).describedAs("rollback after revocation").isEqualTo(403)
        assertThat(unpublish(sc.base, p.s).response.status).describedAs("unpublish after revocation").isEqualTo(403)
        assertUnchanged(sc.projectId, before, "revoked (downgraded)")
        // 3. the membership is removed altogether: the application is no longer even visible
        jdbc.update("UPDATE project_members SET active = false WHERE project_id = ? AND user_id = ?", sc.projectId, p.userId)
        assertThat(publish(sc.base, p.s, publishBody(sc)).response.status).isEqualTo(404)
        assertThat(rollback(sc.base, p.s, older.id).response.status).isEqualTo(404)
        assertThat(unpublish(sc.base, p.s).response.status).isEqualTo(404)
        assertUnchanged(sc.projectId, before, "revoked (removed)")
        // 4. and it comes back the moment the database says so, on the same session
        jdbc.update("UPDATE project_members SET active = true, role = 'PUBLISHER' WHERE project_id = ? AND user_id = ?", sc.projectId, p.userId)
        assertThat(rollback(sc.base, p.s, older.id).response.status).describedAs("granted again").isEqualTo(200)
    }

    @Test
    fun `a user disabled while the SAME session is alive is 401 ACCOUNT_DISABLED on publish, rollback and unpublish`() {
        val (sc, older, _) = published()
        for (operation in listOf("publish", "rollback", "unpublish")) {
            val p = actor(sc, "PUBLISHER")
            assertThat(rollback(sc.base, p.s, pointer(sc.projectId)!!).response.status).describedAs("$operation: granted before").isEqualTo(200)
            fx.disable(p.userId)                                                         // the session cookie is kept
            val before = snap(sc.projectId)
            val r = when (operation) { "publish" -> publish(sc.base, p.s, publishBody(sc)); "rollback" -> rollback(sc.base, p.s, older.id); else -> unpublish(sc.base, p.s) }
            assertThat(r.response.status).describedAs("$operation by a disabled user").isEqualTo(401)
            assertThat(code(r, p.s)).isEqualTo("ACCOUNT_DISABLED")
            assertUnchanged(sc.projectId, before, "disabled user, $operation")
        }
    }

    // ------------------------------------------------------------------ other mutation routes that reach a deployment or a release

    @Test
    fun `the server-runtime rollback and stop and the domain routes need APP_PUBLISH as well`() {
        val sc = scenario(); val before = snap(sc.projectId)
        for (role in listOf("VIEWER", "EDITOR")) {
            val a = actor(sc, role)
            assertThat(a.s.post("${sc.base}/runtime/rollback", """{"deploymentId":"${UUID.randomUUID()}"}""").response.status).describedAs("$role runtime rollback").isEqualTo(403)
            assertThat(a.s.post("${sc.base}/runtime/stop").response.status).describedAs("$role runtime stop").isEqualTo(403)
            assertThat(a.s.post("${sc.base}/domains", """{"hostname":"demo.example.com"}""").response.status).describedAs("$role add domain").isEqualTo(403)
            assertThat(a.s.post("${sc.base}/domains/${UUID.randomUUID()}/verify").response.status).describedAs("$role verify domain").isEqualTo(403)
            assertThat(a.s.delete("${sc.base}/domains/${UUID.randomUUID()}").response.status).describedAs("$role remove domain").isEqualTo(403)
        }
        // the publisher passes the permission gate and is stopped by the application kind (a website has no server part), not by authorization
        val p = actor(sc, "PUBLISHER")
        val r = p.s.post("${sc.base}/runtime/stop")
        assertThat(r.response.status).isEqualTo(409); assertThat(p.s.body(r).get("code").asString()).isEqualTo("NOT_A_SERVER_APP")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM site_domains WHERE project_id = ?", Long::class.java, sc.projectId)).isZero()
        assertUnchanged(sc.projectId, before, "runtime / domains")
    }
}
