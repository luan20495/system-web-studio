package com.systemwebstudio.tenancy

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * C1 · B-C1-13 — ENABLED by C0. `admin/AdminController.transfer` refuses to make the caller itself the owner (403 SELF_GRANT_FORBIDDEN): the C0 patch
 * from docs/parallel/c1/B-C1-13-admin-transfer-ownership.md was applied in 8b944cc. The test was written by C1 as a spec while the hole was open;
 * its assertions are unchanged.
 */
class AdminTransferOwnershipSelfGrantSpec : IntegrationTestBase() {
    @Test
    fun `a system admin who is only a workspace member cannot make itself the owner of an application`() {
        val sc = scenario(); val sys = fx.user("xfer-sys", systemAdmin = true); fx.member(sc.ws, sys, "VIEWER"); val s = sessionFor(sys.username)
        val before = jdbc.queryForObject("SELECT owner_user_id FROM projects WHERE id = ?", java.util.UUID::class.java, sc.projectId)
        val r = s.post("/api/v1/admin/applications/${sc.projectId}/transfer-ownership", """{"userId":"${sys.id}"}""")
        assertThat(r.response.status).isEqualTo(403)
        assertThat(s.body(r).get("code").asString()).isEqualTo("SELF_GRANT_FORBIDDEN")
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM projects WHERE id = ?", java.util.UUID::class.java, sc.projectId)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id = ? AND user_id = ?", Long::class.java, sc.projectId, sys.id)).isZero()
    }
}
