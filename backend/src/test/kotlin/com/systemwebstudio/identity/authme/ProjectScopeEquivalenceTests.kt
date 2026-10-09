package com.systemwebstudio.identity.authme

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext

/**
 * Differential test of /auth/me projectScopes against AccessService.forProject (default policy: SYSTEM_ADMIN is platform-scope only).
 * Every scenario logs in FIRST and changes tenant / membership state afterwards (/auth/me recomputes everything from the database on every call).
 * Note: "project row without ANY workspace_members row" cannot exist (FK project_members_workspace_user_fk), so "no workspace membership" is an INACTIVE workspace_members row.
 */
@Import(SqlStatementCountingConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProjectScopeEquivalenceTests : ProjectScopeDifferentialBase() {

    @Test
    fun `project roles VIEWER EDITOR PUBLISHER OWNER, archived, inactive membership, inactive project`() {
        val owner = fx.user("am-own"); val u = fx.user("am-roles")
        val t = newTenant(); val w = newWorkspace(t)
        wsMember(w, owner, "WORKSPACE_ADMIN"); wsMember(w, u, "VIEWER")
        val pv = newProject(w, owner); val pe = newProject(w, owner); val pp = newProject(w, owner); val po = newProject(w, owner)
        val pa = newProject(w, owner, archived = true); val pi = newProject(w, owner); val pdead = newProject(w, owner, active = false)
        pm(pv, u, "VIEWER"); pm(pe, u, "EDITOR"); pm(pp, u, "PUBLISHER"); pm(po, u, "OWNER"); pm(pa, u, "OWNER"); pm(pi, u, "EDITOR", active = false); pm(pdead, u, "OWNER")
        val s = login(u)
        val sc = assertEquivalent(u, s, "project roles")
        assertThat(sc.keys).containsExactlyInAnyOrder(pv, pe, pp, po, pa)
        assertExactly(sc, pv, "VIEWER", *VIEWER)
        assertExactly(sc, pe, "EDITOR", *EDITOR)
        assertExactly(sc, pp, "PUBLISHER", *PUBLISHER)
        assertExactly(sc, po, "OWNER", *OWNER)
        assertExactly(sc, pa, "OWNER", "APP_VIEW")
        assertThat(sc.getValue(pp).permissions).contains("APP_PUBLISH").doesNotContain("APP_EDIT")
        assertThat(sc.getValue(pv).permissions).doesNotContain("APP_EDIT", "APP_PUBLISH", "APP_SHARE")
        assertThat(sc.getValue(pe).permissions).doesNotContain("APP_PUBLISH", "APP_SHARE")
    }

    @Test
    fun `workspace admin with and without project membership, plain workspace EDITOR with a project VIEWER row`() {
        val owner = fx.user("am-own"); val admin = fx.user("am-wsadmin"); val plain = fx.user("am-plain")
        val t = newTenant(); val w = newWorkspace(t)
        wsMember(w, owner, "WORKSPACE_ADMIN"); wsMember(w, admin, "WORKSPACE_ADMIN"); wsMember(w, plain, "EDITOR")
        val withRow = newProject(w, owner); val withoutRow = newProject(w, owner); val archived = newProject(w, owner, archived = true)
        pm(withRow, admin, "VIEWER"); pm(archived, admin, "EDITOR")
        pm(withRow, plain, "VIEWER")
        val sa = assertEquivalent(admin, login(admin), "workspace admin")
        assertThat(sa.keys).describedAs("projectScopes lists explicit memberships only").containsExactlyInAnyOrder(withRow, archived).doesNotContain(withoutRow)
        assertExactly(sa, withRow, "VIEWER", *WORKSPACE_ADMIN)
        assertExactly(sa, archived, "EDITOR", "APP_VIEW")
        val sp = assertEquivalent(plain, login(plain), "plain workspace EDITOR")
        assertThat(sp.keys).containsExactly(withRow)
        assertExactly(sp, withRow, "VIEWER", *VIEWER)                    // workspace EDITOR adds only PROJECT_CREATE (not canonical) - no project authority
    }

    @Test
    fun `inactive workspace membership hides the project`() {
        val owner = fx.user("am-own"); val u = fx.user("am-wsoff")
        val t = newTenant(); val w = newWorkspace(t); val w2 = newWorkspace(t)
        wsMember(w, owner, "WORKSPACE_ADMIN"); wsMember(w2, owner, "WORKSPACE_ADMIN"); wsMember(w, u, "EDITOR"); wsMember(w2, u, "EDITOR")
        val p = newProject(w, owner); val keep = newProject(w2, owner)
        pm(p, u, "OWNER"); pm(keep, u, "EDITOR")
        val s = login(u)
        assertThat(assertEquivalent(u, s, "before").keys).containsExactlyInAnyOrder(p, keep)
        setWsMemberActive(w, u, false)                                   // foreign project: project row kept, workspace membership gone
        val sc = assertEquivalent(u, s, "workspace membership inactive")
        assertThat(sc.keys).containsExactly(keep)
    }

    @Test
    fun `tenant member removed, tenant SUSPENDED, tenant DELETED hide every project of the tenant`() {
        for (change in listOf("REMOVED", "SUSPENDED", "DELETED")) {
            val owner = fx.user("am-own"); val u = fx.user("am-t-" + change.lowercase())
            val t = newTenant(); val w = newWorkspace(t)
            val other = newTenant(); val wo = newWorkspace(other)
            wsMember(w, owner, "WORKSPACE_ADMIN"); wsMember(wo, owner, "WORKSPACE_ADMIN"); wsMember(w, u, "EDITOR"); wsMember(wo, u, "VIEWER")
            val p = newProject(w, owner); val pOther = newProject(wo, owner)
            pm(p, u, "EDITOR"); pm(pOther, u, "PUBLISHER")
            val s = login(u)
            assertThat(assertEquivalent(u, s, "$change before").keys).containsExactlyInAnyOrder(p, pOther)
            when (change) { "REMOVED" -> setTenantMemberActive(t, u, false); else -> tenantStatus(t, change) }
            val sc = assertEquivalent(u, s, "tenant $change")
            assertThat(sc.keys).describedAs("tenant $change").containsExactly(pOther)
            assertExactly(sc, pOther, "PUBLISHER", *PUBLISHER)
        }
    }

    @Test
    fun `project in a workspace of another tenant than the user's tenant`() {
        val owner = fx.user("am-own"); val u = fx.user("am-xt")
        val home = newTenant(); val wHome = newWorkspace(home)
        val foreign = newTenant(); val wForeign = newWorkspace(foreign)
        wsMember(wHome, owner, "WORKSPACE_ADMIN"); wsMember(wForeign, owner, "WORKSPACE_ADMIN")
        wsMember(wHome, u, "EDITOR"); wsMember(wForeign, u, "EDITOR")              // the V26 trigger makes u a MEMBER of the foreign tenant too
        val pHome = newProject(wHome, owner); val pForeign = newProject(wForeign, owner)
        pm(pHome, u, "EDITOR"); pm(pForeign, u, "OWNER")
        val s = login(u)
        // (a) foreign tenant membership deactivated -> the foreign project is hidden
        setTenantMemberActive(foreign, u, false)
        val a = assertEquivalent(u, s, "foreign tenant membership inactive")
        assertThat(a.keys).containsExactly(pHome)
        // (b) NO tenant_members row at all for the foreign tenant (legacy data): differential only - whatever forProject decides, /auth/me must say the same
        jdbc.update("DELETE FROM tenant_members WHERE tenant_id = ? AND user_id = ?", foreign, u.id)
        val b = assertEquivalent(u, s, "no foreign tenant_members row")
        System.err.println("[ProjectScopeEquivalence] user without tenant_members row in the project's tenant: foreign project visible=${pForeign in b.keys} perms=${b[pForeign]?.permissions}")
        assertThat(b.keys).contains(pHome)
    }

    @Test
    fun `SYSTEM_ADMIN platform-only with an explicit project row and an inactive workspace membership - differential`() {
        val owner = fx.user("am-own"); val sys = fx.user("am-sys-plat", systemAdmin = true)
        val t = newTenant(); val w = newWorkspace(t)
        wsMember(w, owner, "WORKSPACE_ADMIN"); wsMember(w, sys, "VIEWER")
        val p = newProject(w, owner); val pa = newProject(w, owner, archived = true)
        pm(p, sys, "EDITOR"); pm(pa, sys, "OWNER")
        val s = login(sys)
        setWsMemberActive(w, sys, false)
        val sc = assertEquivalent(sys, s, "system admin, workspace membership inactive")
        // a stale project row of a platform operator that is NOT a workspace member grants NOTHING (platform scope only): no project scope, never TENANT_* codes inside a project
        assertThat(sc).describedAs("system admin, workspace membership inactive, tenant ACTIVE").isEmpty()
        for (status in listOf("SUSPENDED", "DELETED")) {
            tenantStatus(t, status)
            assertThat(assertEquivalent(sys, s, "system admin non-member, tenant $status")).describedAs("tenant $status").isEmpty()
        }
    }

    @Test
    fun `SYSTEM_ADMIN with explicit workspace membership - active tenant, tenant membership removed, SUSPENDED, DELETED`() {
        val owner = fx.user("am-own"); val sys = fx.user("am-sys-mem", systemAdmin = true)
        val t = newTenant(); val w = newWorkspace(t)
        wsMember(w, owner, "WORKSPACE_ADMIN"); wsMember(w, sys, "VIEWER")
        val p = newProject(w, owner); val pa = newProject(w, owner, archived = true)
        pm(p, sys, "EDITOR"); pm(pa, sys, "OWNER")
        val s = login(sys)
        val active = assertEquivalent(sys, s, "system admin member, tenant ACTIVE")
        assertExactly(active, p, "EDITOR", *EDITOR)                     // membership authority only: no platform code, no bypass
        assertExactly(active, pa, "OWNER", "APP_VIEW")
        setTenantMemberActive(t, sys, false)
        assertThat(assertEquivalent(sys, s, "system admin member, tenant membership removed")).isEmpty()
        setTenantMemberActive(t, sys, true)
        assertThat(assertEquivalent(sys, s, "system admin member, tenant membership restored").keys).containsExactlyInAnyOrder(p, pa)
        tenantStatus(t, "SUSPENDED")
        assertThat(assertEquivalent(sys, s, "system admin member, tenant SUSPENDED")).isEmpty()
        tenantStatus(t, "DELETED")
        assertThat(assertEquivalent(sys, s, "system admin member, tenant DELETED")).isEmpty()
    }
}
