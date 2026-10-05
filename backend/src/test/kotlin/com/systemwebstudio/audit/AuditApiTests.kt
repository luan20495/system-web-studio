package com.systemwebstudio.audit

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuditApiTests : IntegrationTestBase() {
    @Test
    fun `audit rows cannot be updated or deleted, even by the application user`() {
        val sc = scenario()
        assertThat(sc.auditCount("CREATE_PROJECT")).isEqualTo(1L)
        assertThatThrownBy { jdbc.update("UPDATE audit_events SET action = 'X' WHERE project_id = ?", sc.projectId) }.hasMessageContaining("append-only")
        assertThatThrownBy { jdbc.update("DELETE FROM audit_events WHERE project_id = ?", sc.projectId) }.hasMessageContaining("append-only")
        assertThatThrownBy { jdbc.execute("TRUNCATE audit_events") }.hasMessageContaining("append-only")
    }

    @Test
    fun `only workspace admins read audit events, and never from another workspace`() {
        val sc = scenario()
        val admin = fx.user("admin"); fx.member(sc.ws, admin, "WORKSPACE_ADMIN")
        val events = sessionFor(admin.username).get("/api/v1/workspaces/${sc.ws}/audit-events?projectId=${sc.projectId}")
        assertThat(events.response.status).isEqualTo(200)
        val rows = sc.s.body(events)
        assertThat(rows.toList().map { it.get("action").asString() }).contains("CREATE_PROJECT")
        assertThat(rows.toString()).doesNotContain("password")

        assertThat(sc.s.get("/api/v1/workspaces/${sc.ws}/audit-events").response.status).isEqualTo(403)   // EDITOR
        val outsider = scenario()
        assertThat(outsider.s.get("/api/v1/workspaces/${sc.ws}/audit-events").response.status).isEqualTo(404)
        assertThat(session().get("/api/v1/workspaces/${sc.ws}/audit-events").response.status).isEqualTo(401)
    }

    @Test
    fun `failed logins are audited without the password and OpenAPI is served in local profile`() {
        val u = fx.user("audited")
        val s = session()
        s.login(u.username, "wrong-password-xyz")
        val n = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='LOGIN_FAILURE' AND new_value::text NOT LIKE '%wrong-password-xyz%'", Long::class.java)!!
        assertThat(n).isGreaterThanOrEqualTo(1L)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE new_value::text LIKE '%wrong-password-xyz%'", Long::class.java)).isEqualTo(0L)
        val spec = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs")).andReturn()
        assertThat(spec.response.status).isEqualTo(200)
        assertThat(spec.response.contentAsString).contains("/api/v1/workspaces/{workspaceId}/projects/{projectId}/publish").contains("sessionCookie")
    }
}
