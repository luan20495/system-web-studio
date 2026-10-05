package com.systemwebstudio.version

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VersionApiTests : IntegrationTestBase() {
    private fun versions(sc: Scenario) = sc.s.body(sc.s.get("${sc.base}/versions"))

    @Test
    fun `restore creates a NEW version, leaves history untouched and persists`() {
        val sc = scenario()
        val v1Id = versions(sc).get(0).get("id").asString()
        val v1Before = sc.s.body(sc.s.get("${sc.base}/versions/$v1Id")).get("schema")
        sc.prompt("xóa đánh giá")
        sc.prompt("thêm sản phẩm")
        assertThat(versions(sc).size()).isEqualTo(3)
        assertThat(versions(sc).get(0).get("current").asBoolean()).isTrue()          // newest first
        assertThat(versions(sc).get(2).get("restorable").asBoolean()).isTrue()

        val r = sc.s.post("${sc.base}/versions/$v1Id/restore", """{"expectedRevision":${sc.revision()}}""")
        assertThat(r.response.status).isEqualTo(200)
        val body = sc.s.body(r)
        assertThat(body.get("version").get("kind").asString()).isEqualTo("RESTORE")
        assertThat(body.get("version").get("versionNumber").asInt()).isEqualTo(4)
        assertThat(body.get("schema")).isEqualTo(v1Before)

        assertThat(versions(sc).size()).isEqualTo(4)                                  // nothing deleted
        assertThat(sc.s.body(sc.s.get("${sc.base}/versions/$v1Id")).get("schema")).isEqualTo(v1Before)   // old snapshot immutable
        assertThat(sc.section("Testimonials")).isNotNull()                            // "page reload" reads restored state from PostgreSQL
        assertThat(sc.auditCount("RESTORE_VERSION")).isEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT restored_from_version_id::text FROM project_versions WHERE project_id=? AND version_number=4", String::class.java, sc.projectId)).isEqualTo(v1Id)
    }

    @Test
    fun `viewer cannot restore, editor can, stale revision is 409, foreign version is 404`() {
        val sc = scenario()
        val v1Id = versions(sc).get(0).get("id").asString()
        sc.prompt("xóa đánh giá")
        val project = fx.projects.findById(sc.projectId).get()
        val viewer = fx.user("viewer"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(project, viewer, "VIEWER")
        val editor = fx.user("editor"); fx.member(sc.ws, editor, "VIEWER"); fx.projectRole(project, editor, "EDITOR")
        val rev = sc.revision()
        assertThat(sessionFor(viewer.username).post("${sc.base}/versions/$v1Id/restore", """{"expectedRevision":$rev}""").response.status).isEqualTo(403)
        assertThat(sc.s.post("${sc.base}/versions/$v1Id/restore", """{"expectedRevision":${rev - 1}}""").response.status).isEqualTo(409)
        assertThat(sessionFor(editor.username).post("${sc.base}/versions/$v1Id/restore", """{"expectedRevision":$rev}""").response.status).isEqualTo(200)

        val other = scenario()
        assertThat(other.s.post("${other.base}/versions/$v1Id/restore", """{"expectedRevision":0}""").response.status).isEqualTo(404)
        assertThat(other.s.get("${other.base}/versions/$v1Id").response.status).isEqualTo(404)
    }

    @Test
    fun `version numbers are gapless and component usage is recorded`() {
        val sc = scenario()
        repeat(3) { sc.prompt(if (it % 2 == 0) "bỏ đánh giá" else "hiện đánh giá") }
        assertThat(versions(sc).toList().map { it.get("versionNumber").asInt() }).containsExactly(4, 3, 2, 1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM component_usage WHERE project_id=? AND component_id='Hero'", Long::class.java, sc.projectId)).isEqualTo(4L)
    }
}
