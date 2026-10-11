package com.systemwebstudio.tenancy

import com.systemwebstudio.organization.InMemoryOrganizationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * C1 hardening - DETERMINISTIC proof that the last-administrator rules take the row locks BEFORE they count. HardeningRaceTests races two requests (probabilistic); here an
 * EXTERNAL transaction plays "the other request, inside its critical section": it holds the FOR UPDATE locks on the administrator rows. The request under test must then BLOCK
 * (if the service counted without locking it would answer at once), and when the external transaction commits a concurrent removal of the caller's own admin row, the blocked
 * request re-evaluates under READ COMMITTED, finds ONE administrator left and answers 409. Remove the FOR UPDATE from TenantService.assertNotLastAdmin /
 * AccountService.setSystemAdmin and these tests fail.
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HardeningLockTests : FinalIamTestBase() {
    private fun <T> blockedUntil(release: () -> Unit, call: Callable<T>): T {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val f = pool.submit(call)
            try { f.get(2, TimeUnit.SECONDS); throw AssertionError("the request completed while the administrator rows were locked: the count is NOT taken under a lock") } catch (_: TimeoutException) { /* blocked, as required */ }
            release()
            return f.get(60, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `last TENANT_ADMIN - a request that finds the admin rows locked waits, then re-counts and answers 409 LAST_TENANT_ADMIN`() {
        val sys = sysAdmin(); val c = company(sys); val a2 = fx.user("lock-a2"); tenantService.setMember(c.id, a2.id, TenantRole.TENANT_ADMIN)
        val ds = jdbc.dataSource!!
        ds.connection.use { ext ->
            ext.autoCommit = false
            ext.prepareStatement("SELECT user_id FROM tenant_members WHERE tenant_id = ? AND role = 'TENANT_ADMIN' AND active ORDER BY user_id FOR UPDATE").use { st -> st.setObject(1, c.id); st.executeQuery().use { while (it.next()) { /* locked */ } } }
            val r = blockedUntil({
                // the "other transaction" removes the caller's own admin row and commits
                ext.prepareStatement("UPDATE tenant_members SET active = FALSE WHERE tenant_id = ? AND user_id = ?").use { st -> st.setObject(1, c.id); st.setObject(2, c.adminId); st.executeUpdate() }
                ext.commit()
            }, Callable { c.admin.put("${base(c)}/members/${a2.id}", """{"role":"MEMBER"}""") })
            assertThat(r.response.status).describedAs("demotion of the last remaining admin").isEqualTo(409)
            assertThat(c.admin.body(r).get("code").asString()).isEqualTo("LAST_TENANT_ADMIN")
        }
        assertThat(jdbc.queryForList("SELECT user_id FROM tenant_members WHERE tenant_id = ? AND role = 'TENANT_ADMIN' AND active", UUID::class.java, c.id)).containsExactly(a2.id)
    }

    @Test
    fun `last SYSTEM_ADMIN - a request that finds the system-admin rows locked waits, then re-counts and answers 409 LAST_SYSTEM_ADMIN`() {
        val others = jdbc.queryForList("SELECT id FROM users WHERE system_admin AND enabled", UUID::class.java)
        try {
            if (others.isNotEmpty()) jdbc.update("UPDATE users SET system_admin = FALSE WHERE id IN (${others.joinToString(",") { "'$it'" }})")
            val s1 = fx.user("lock-s1", systemAdmin = true); val s2 = fx.user("lock-s2", systemAdmin = true); val s1s = sessionFor(s1.username)
            assertThat(jdbc.queryForList("SELECT id FROM users WHERE system_admin AND enabled", UUID::class.java)).containsExactlyInAnyOrder(s1.id, s2.id)
            jdbc.dataSource!!.connection.use { ext ->
                ext.autoCommit = false
                ext.prepareStatement("SELECT id FROM users WHERE system_admin AND enabled ORDER BY id FOR UPDATE").use { st -> st.executeQuery().use { while (it.next()) { /* locked */ } } }
                val r = blockedUntil({
                    ext.prepareStatement("UPDATE users SET system_admin = FALSE WHERE id = ?").use { st -> st.setObject(1, s1.id); st.executeUpdate() }     // the other operator revokes the caller meanwhile
                    ext.commit()
                }, Callable { s1s.post("/api/v1/admin/users/${s2.id}/system-admin", """{"grant":false,"confirm":true}""") })
                assertThat(r.response.status).describedAs("revoking the last remaining system administrator").isEqualTo(409)
                assertThat(s1s.body(r).get("code").asString()).isEqualTo("LAST_SYSTEM_ADMIN")
            }
            assertThat(jdbc.queryForList("SELECT id FROM users WHERE system_admin AND enabled", UUID::class.java)).containsExactly(s2.id)
        } finally {
            if (others.isNotEmpty()) jdbc.update("UPDATE users SET system_admin = TRUE WHERE id IN (${others.joinToString(",") { "'$it'" }})")
        }
    }
}
