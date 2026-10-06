package com.systemwebstudio.tenancy

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * C1 · B-C1-13 SPEC (disabled on purpose). `admin/AdminController.transfer` is NOT a C1 file, so C1 does not patch it and this test is
 * disabled: it fails against today's code (the hole is real) and must stay out of the C0 Gradle run until C0 applies the patch in
 * docs/parallel/c1/B-C1-13-admin-transfer-ownership.md. C0: apply the patch, delete the @Disabled line, run the class.
 */
class AdminTransferOwnershipSelfGrantSpec : IntegrationTestBase() {
    @Disabled("B-C1-13: needs the C0 patch in docs/parallel/c1/B-C1-13-admin-transfer-ownership.md — then remove this line")
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
