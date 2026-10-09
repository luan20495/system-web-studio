package com.systemwebstudio.identity.authme

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource

/** Same differential property with the legacy SYSTEM_ADMIN business-data bypass switched ON (own context). */
@Import(SqlStatementCountingConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = ["app.tenancy.system-admin-business-access=true"])
class ProjectScopeLegacyFlagEquivalenceTests : ProjectScopeDifferentialBase() {

    @Test
    fun `legacy bypass - SYSTEM_ADMIN with and without workspace membership, ordinary user unchanged`() {
        val owner = fx.user("am-own"); val sysMember = fx.user("am-lsys-mem", systemAdmin = true); val sysPlatform = fx.user("am-lsys-plat", systemAdmin = true)
        val plain = fx.user("am-lplain")
        val t = newTenant(); val w = newWorkspace(t)
        wsMember(w, owner, "WORKSPACE_ADMIN"); wsMember(w, sysMember, "VIEWER"); wsMember(w, sysPlatform, "VIEWER"); wsMember(w, plain, "VIEWER")
        val p = newProject(w, owner); val pa = newProject(w, owner, archived = true)
        for (u in listOf(sysMember, sysPlatform)) { pm(p, u, "VIEWER"); pm(pa, u, "EDITOR") }
        pm(p, plain, "VIEWER")
        val sm = login(sysMember); val sp = login(sysPlatform); val spl = login(plain)
        setWsMemberActive(w, sysPlatform, false)

        val member = assertEquivalent(sysMember, sm, "legacy: system admin member")
        assertThat(member.getValue(p).permissions).describedAs("legacy bypass: every canonical project code").contains("APP_EDIT", "APP_PUBLISH", "APP_SHARE", "DATA_MUTATE", "WORKFLOW_MANAGE")
        assertExactly(member, pa, "EDITOR", "APP_VIEW")
        assertEquivalent(sysPlatform, sp, "legacy: system admin, workspace membership inactive")
        assertExactly(assertEquivalent(plain, spl, "legacy: ordinary user"), p, "VIEWER", *VIEWER)

        for (status in listOf("SUSPENDED", "DELETED")) {
            tenantStatus(t, status)
            assertEquivalent(sysMember, sm, "legacy: system admin member, tenant $status")
            assertEquivalent(sysPlatform, sp, "legacy: system admin non-member, tenant $status")
            assertThat(assertEquivalent(plain, spl, "legacy: ordinary user, tenant $status")).describedAs("the flag never widens an ordinary user").isEmpty()
        }
    }
}
