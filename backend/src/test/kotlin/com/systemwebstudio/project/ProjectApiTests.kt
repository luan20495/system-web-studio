package com.systemwebstudio.project

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ProjectApiTests : IntegrationTestBase() {
    @Test
    fun `create trims name sets owner and writes audit`() {
        val u = fx.user("ed"); val ws = fx.workspace(); fx.member(ws, u, "EDITOR")
        val s = sessionFor(u.username)
        val r = s.post(api(ws), """{"name":"  My Site  "}""")
        assertThat(r.response.status).isEqualTo(201)
        val id = s.body(r).get("id").asString()
        assertThat(s.body(r).get("name").asString()).isEqualTo("My Site")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='CREATE_PROJECT' AND resource_id=?", Long::class.java, id)).isEqualTo(1L)
        assertThat(s.body(s.get(api(ws))).size()).isEqualTo(1)
    }

    @Test
    fun `validation errors are structured with field details`() {
        val u = fx.user(); val ws = fx.workspace(); fx.member(ws, u, "EDITOR"); val s = sessionFor(u.username)
        val r = s.post(api(ws), """{"name":""}""")
        assertThat(r.response.status).isEqualTo(400)
        val body = s.body(r)
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED")
        assertThat(body.get("details").get("fields").has("name")).isTrue()
        assertThat(body.get("requestId").asString()).isEqualTo(r.response.getHeader("X-Request-Id"))
        val bad = s.post(api(ws), "{not json")
        assertThat(bad.response.status).isEqualTo(400)
        assertThat(s.body(bad).get("code").asString()).isEqualTo("MALFORMED_REQUEST")
        assertThat(s.body(bad).toString()).doesNotContain("Exception").doesNotContain("at com.")
    }

    @Test
    fun `404 405 and unknown route use the error contract`() {
        val u = fx.user(); val ws = fx.workspace(); fx.member(ws, u, "EDITOR"); val s = sessionFor(u.username)
        val missing = s.get(api(ws, java.util.UUID.randomUUID()))
        assertThat(missing.response.status).isEqualTo(404)
        assertThat(s.body(missing).get("code").asString()).isEqualTo("PROJECT_NOT_FOUND")
        assertThat(s.get("/api/v1/nope").response.status).isEqualTo(404)
        val put = s.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(api(ws)))
        assertThat(put.response.status).isEqualTo(405)
        assertThat(s.body(put).get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED")
    }

    @Test
    fun `RBAC matrix for project operations`() {
        val owner = fx.user("owner"); val ws = fx.workspace(); fx.member(ws, owner, "EDITOR")
        val p = fx.project(ws, owner)
        val viewer = fx.user("viewer"); fx.member(ws, viewer, "VIEWER"); fx.projectRole(p, viewer, "VIEWER")
        val editor = fx.user("editor"); fx.member(ws, editor, "VIEWER"); fx.projectRole(p, editor, "EDITOR")
        val publisher = fx.user("pub"); fx.member(ws, publisher, "VIEWER"); fx.projectRole(p, publisher, "PUBLISHER")
        val wsAdmin = fx.user("wsadmin"); fx.member(ws, wsAdmin, "WORKSPACE_ADMIN")

        val v = sessionFor(viewer.username)
        assertThat(v.get(api(ws, p.id)).response.status).isEqualTo(200)
        assertThat(v.patch(api(ws, p.id), """{"expectedRevision":0,"name":"x"}""").response.status).isEqualTo(403)
        assertThat(v.delete(api(ws, p.id) + "?expectedRevision=0").response.status).isEqualTo(403)
        assertThat(v.post(api(ws), """{"name":"nope"}""").response.status).isEqualTo(403)

        val e = sessionFor(editor.username)
        assertThat(e.patch(api(ws, p.id), """{"expectedRevision":0,"name":"by editor"}""").response.status).isEqualTo(200)
        assertThat(e.delete(api(ws, p.id) + "?expectedRevision=1").response.status).isEqualTo(403)

        val pb = sessionFor(publisher.username)
        assertThat(pb.get(api(ws, p.id)).response.status).isEqualTo(200)
        assertThat(pb.patch(api(ws, p.id), """{"expectedRevision":1,"name":"by pub"}""").response.status).isEqualTo(403)

        val a = sessionFor(wsAdmin.username)                       // no project membership, still manages the workspace
        assertThat(a.body(a.get(api(ws))).size()).isEqualTo(1)
        assertThat(a.patch(api(ws, p.id), """{"expectedRevision":1,"description":"d"}""").response.status).isEqualTo(200)
        assertThat(sessionFor(owner.username).delete(api(ws, p.id) + "?expectedRevision=2").response.status).isEqualTo(204)
    }

    @Test
    fun `foreign workspace and foreign project are not disclosed`() {
        val a = fx.user("a"); val wsA = fx.workspace(); fx.member(wsA, a, "EDITOR"); val pa = fx.project(wsA, a)
        val b = fx.user("b"); val wsB = fx.workspace(); fx.member(wsB, b, "EDITOR"); val pb = fx.project(wsB, b)
        val sb = sessionFor(b.username)
        assertThat(sb.get(api(wsA)).response.status).isEqualTo(404)                    // not a member of that workspace
        assertThat(sb.get(api(wsA, pa.id)).response.status).isEqualTo(404)
        assertThat(sb.get(api(wsB, pa.id)).response.status).isEqualTo(404)             // right workspace, someone else's project id
        assertThat(sb.patch(api(wsB, pa.id), """{"expectedRevision":0,"name":"hijack"}""").response.status).isEqualTo(404)
        // same workspace but no project membership
        val c = fx.user("c"); fx.member(wsB, c, "EDITOR")
        assertThat(sessionFor(c.username).get(api(wsB, pb.id)).response.status).isEqualTo(404)
        assertThat(sessionFor(c.username).body(sessionFor(c.username).get(api(wsB))).size()).isEqualTo(0)
    }

    @Test
    fun `competing writes with the same expected revision let exactly one win`() {
        val u = fx.user(); val ws = fx.workspace(); fx.member(ws, u, "EDITOR"); val p = fx.project(ws, u)
        val s = sessionFor(u.username)
        repeat(3) { round ->
            val rev = s.body(s.get(api(ws, p.id))).get("revision").asLong()
            val pool = Executors.newFixedThreadPool(2); val gate = CountDownLatch(1)
            val calls = (1..2).map { n -> Callable { gate.await(); s.patch(api(ws, p.id), """{"expectedRevision":$rev,"name":"writer $round-$n"}""").response.status } }
            val futures = calls.map { pool.submit(it) }
            gate.countDown()
            val statuses = futures.map { it.get() }.sorted()
            pool.shutdown()
            assertThat(statuses).isEqualTo(listOf(200, 409))
        }
    }

    @Test
    fun `stale revision returns 409 with current revision`() {
        val u = fx.user(); val ws = fx.workspace(); fx.member(ws, u, "EDITOR"); val p = fx.project(ws, u); val s = sessionFor(u.username)
        assertThat(s.patch(api(ws, p.id), """{"expectedRevision":0,"name":"one"}""").response.status).isEqualTo(200)
        val stale = s.patch(api(ws, p.id), """{"expectedRevision":0,"name":"two"}""")
        assertThat(stale.response.status).isEqualTo(409)
        assertThat(s.body(stale).get("code").asString()).isEqualTo("REVISION_CONFLICT")
        assertThat(s.body(stale).get("details").get("currentRevision").asLong()).isEqualTo(1L)
    }

    @Test
    fun `settings persist and are audited`() {
        val u = fx.user(); val ws = fx.workspace(); fx.member(ws, u, "EDITOR"); val p = fx.project(ws, u); val s = sessionFor(u.username)
        val r = s.patch(api(ws, p.id), """{"expectedRevision":0,"name":"Renamed","siteVisibility":"PUBLIC","domain":"demo.example.com","deploymentMode":"SELF_HOSTED","deploymentTarget":"vps-1"}""")
        assertThat(r.response.status).isEqualTo(200)
        val reread = s.body(s.get(api(ws, p.id)))
        assertThat(reread.get("siteVisibility").asString()).isEqualTo("PUBLIC")
        assertThat(reread.get("domain").asString()).isEqualTo("demo.example.com")
        assertThat(reread.get("revision").asLong()).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='UPDATE_PROJECT' AND project_id=?", Long::class.java, p.id)).isEqualTo(1L)
        assertThat(s.patch(api(ws, p.id), """{"expectedRevision":1,"domain":"not a host!"}""").response.status).isEqualTo(400)
    }

    @Test
    fun `audit rows cannot be updated or deleted`() {
        val u = fx.user(); sessionFor(u.username)
        val id = jdbc.queryForObject("SELECT id FROM audit_events WHERE resource_id=? LIMIT 1", java.util.UUID::class.java, u.id.toString())
        org.junit.jupiter.api.assertThrows<DataAccessException> { jdbc.update("UPDATE audit_events SET action='X' WHERE id=?", id) }
        org.junit.jupiter.api.assertThrows<DataAccessException> { jdbc.update("DELETE FROM audit_events WHERE id=?", id) }
        org.junit.jupiter.api.assertThrows<DataAccessException> { jdbc.execute("TRUNCATE audit_events") }
    }
}
