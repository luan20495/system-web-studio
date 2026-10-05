package com.systemwebstudio.publish

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** Publishing runs through the real RabbitMQ container and worker; only the cloud provider is the mock one. */
class PublishApiTests : IntegrationTestBase() {
    private fun publish(sc: Scenario, key: String = "key-" + UUID.randomUUID(), visibility: String = "PUBLIC", rev: Long = sc.revision()) =
        sc.s.post("${sc.base}/publish", """{"visibility":"$visibility","expectedRevision":$rev}""", "Idempotency-Key" to key)

    private fun awaitStatus(sc: Scenario, id: String, vararg wanted: String) = await().atMost(Duration.ofSeconds(30)).until {
        sc.s.body(sc.s.get("${sc.base}/deployments/$id")).get("status").asString() in wanted
    }

    @Test
    fun `publish runs the state machine to RUNNING with ordered events, url and audit`() {
        val sc = scenario()
        val r = publish(sc)
        assertThat(r.response.status).isEqualTo(202)
        val id = sc.s.body(r).get("id").asString()
        awaitStatus(sc, id, "RUNNING", "FAILED")
        val d = sc.s.body(sc.s.get("${sc.base}/deployments/$id"))
        assertThat(d.get("status").asString()).isEqualTo("RUNNING")
        assertThat(d.get("mock").asBoolean()).isTrue()                      // never presented as a real deploy
        assertThat(d.get("url").asString()).isNotBlank()
        assertThat(d.get("events").toList().map { it.get("status").asString() })
            .containsExactly("QUEUED", "POLICY_CHECK", "SECURITY_CHECK", "BUILDING", "DEPLOYING", "RUNNING")
        assertThat(sc.auditCount("PUBLISH")).isEqualTo(1L)
        assertThat(sc.auditCount("DEPLOY_STATUS_CHANGE")).isEqualTo(5L)
        assertThat(sc.s.body(sc.s.get(sc.base)).get("siteVisibility").asString()).isEqualTo("PUBLIC")
    }

    @Test
    fun `the same idempotency key never creates a second deployment, a different payload is rejected`() {
        val sc = scenario()
        val first = publish(sc, "idem-key-0001")
        val again = publish(sc, "idem-key-0001")
        assertThat(again.response.status).isEqualTo(202)
        assertThat(again.response.getHeader("Idempotent-Replay")).isEqualTo("true")
        assertThat(sc.s.body(again).get("id")).isEqualTo(sc.s.body(first).get("id"))
        assertThat(publish(sc, "idem-key-0001", visibility = "PRIVATE").response.status).isEqualTo(409)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id = ?", Long::class.java, sc.projectId)).isEqualTo(1L)
        assertThat(sc.s.post("${sc.base}/publish", """{"visibility":"PUBLIC","expectedRevision":0}""").response.status).isEqualTo(400)  // header missing
        assertThat(publish(sc, "short").response.status).isEqualTo(400)
    }

    @Test
    fun `concurrent duplicate requests with one key create exactly one deployment`() {
        val sc = scenario()
        val rev = sc.revision()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val results = (1..6).map { pool.submit<Int> { publish(sc, "race-key-0001", rev = rev).response.status } }.map { it.get() }
        pool.shutdown()
        assertThat(results).allMatch { it == 202 || it == 409 }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id = ?", Long::class.java, sc.projectId)).isEqualTo(1L)
    }

    @Test
    fun `stale revision is 409, editor and viewer cannot publish, other projects are 404`() {
        val sc = scenario()
        assertThat(publish(sc, rev = sc.revision() - 1).response.status).isEqualTo(409)
        val project = fx.projects.findById(sc.projectId).get()
        val viewer = fx.user("viewer"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(project, viewer, "VIEWER")
        val editor = fx.user("editor"); fx.member(sc.ws, editor, "VIEWER"); fx.projectRole(project, editor, "EDITOR")
        val publisher = fx.user("publisher"); fx.member(sc.ws, publisher, "VIEWER"); fx.projectRole(project, publisher, "PUBLISHER")
        val body = """{"visibility":"PUBLIC","expectedRevision":${sc.revision()}}"""
        assertThat(sessionFor(viewer.username).post("${sc.base}/publish", body, "Idempotency-Key" to "viewer-key-01").response.status).isEqualTo(403)
        assertThat(sessionFor(editor.username).post("${sc.base}/publish", body, "Idempotency-Key" to "editor-key-01").response.status).isEqualTo(403)
        assertThat(sessionFor(publisher.username).post("${sc.base}/publish", body, "Idempotency-Key" to "publisher-key1").response.status).isEqualTo(202)
        val other = scenario()
        val id = jdbc.queryForObject("SELECT id::text FROM deployments WHERE project_id = ?", String::class.java, sc.projectId)
        assertThat(other.s.get("${other.base}/deployments/$id").response.status).isEqualTo(404)
    }

    @Test
    fun `a failing provider ends in FAILED with an error and no url, and a page with script content is blocked`() {
        val sc = scenario()
        sc.s.patch(sc.base, """{"expectedRevision":${sc.revision()},"deploymentTarget":"fail"}""")
        val id = sc.s.body(publish(sc)).get("id").asString()
        awaitStatus(sc, id, "FAILED", "RUNNING")
        val d = sc.s.body(sc.s.get("${sc.base}/deployments/$id"))
        assertThat(d.get("status").asString()).isEqualTo("FAILED")
        assertThat(d.get("error").asString()).isNotBlank()
        assertThat(d.get("url").isNull).isTrue()
        assertThat(sc.s.body(sc.s.get(sc.base)).get("siteVisibility").asString()).isNotEqualTo("PUBLIC")

        val bad = scenario()
        val hero = bad.section("Hero")!!.get("id").asString()
        bad.s.patch("${bad.base}/schema", """{"expectedRevision":${bad.revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"$hero","path":"title","value":"<script>alert(1)</script>"}]}""")
        val badId = bad.s.body(publish(bad)).get("id").asString()
        awaitStatus(bad, badId, "FAILED", "RUNNING")
        val bd = bad.s.body(bad.s.get("${bad.base}/deployments/$badId"))
        assertThat(bd.get("status").asString()).isEqualTo("FAILED")
        assertThat(bd.get("error").asString()).contains("Security")
    }

    @Test
    fun `transitions are compare-and-set and cannot skip or go backwards`() {
        assertThat(DeploymentStatus.allowed("QUEUED", "BUILDING")).isFalse()
        assertThat(DeploymentStatus.allowed("BUILDING", "POLICY_CHECK")).isFalse()
        assertThat(DeploymentStatus.allowed("RUNNING", "FAILED")).isFalse()
        assertThat(DeploymentStatus.allowed("BUILDING", "FAILED")).isTrue()
        val sc = scenario()
        val id = UUID.fromString(sc.s.body(publish(sc)).get("id").asString())
        awaitStatus(sc, id.toString(), "RUNNING", "FAILED")
        val repo = org.springframework.test.util.ReflectionTestUtils.getField(this, "jdbc")!!   // use a repository over the same DataSource
        val deployments = DeploymentRepository(repo as org.springframework.jdbc.core.JdbcTemplate)
        assertThat(deployments.transition(id, "QUEUED", "POLICY_CHECK", null)).isFalse()       // already past QUEUED: the second worker loses
    }
}
