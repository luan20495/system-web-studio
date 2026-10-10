package com.systemwebstudio.tenancy

import com.systemwebstudio.organization.InMemoryOrganizationConfig
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MvcResult
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * C1 hardening (6623b92) - B: the last-TENANT_ADMIN and last-SYSTEM_ADMIN rules hold under concurrency. Two administrators act on EACH OTHER at the same instant
 * (two real threads, real HTTP through MockMvc, one CountDownLatch releases both): the locked count lets exactly one pass.
 *
 * The loser is normally 409 LAST_TENANT_ADMIN / LAST_SYSTEM_ADMIN (both requests passed authorization, the second one waited on the row locks). If one request happens to
 * finish completely before the other even starts its authorization check, the loser has already lost its own role and gets 403 instead: also correct ("never both"),
 * so 403 is accepted, and the number of real 409 contentions is printed for the record.
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HardeningRaceTests : FinalIamTestBase() {
    private val ROUNDS = 5

    private class Outcome(val status: Int, val code: String?)

    /** runs both calls on two threads released together by one latch */
    private fun race(vararg calls: Pair<ApiSession, (ApiSession) -> MvcResult>): List<Outcome> {
        val pool = Executors.newFixedThreadPool(calls.size); val ready = CountDownLatch(calls.size); val go = CountDownLatch(1)
        try {
            val futures = calls.map { (s, call) -> pool.submit(Callable { ready.countDown(); go.await(); val r = call(s); Outcome(r.response.status, runCatching { s.body(r).get("code")?.asString() }.getOrNull()) }) }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue()
            go.countDown()
            return futures.map { it.get(120, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    private fun activeTenantAdmins(c: Company): List<UUID> =
        jdbc.queryForList("SELECT user_id FROM tenant_members WHERE tenant_id = ? AND role = 'TENANT_ADMIN' AND active", UUID::class.java, c.id)

    /** a company with exactly two active TENANT_ADMINs: its first admin (A1, via the real bootstrap) and A2 */
    private fun twoAdmins(sys: ApiSession): Triple<Company, UUID, ApiSession> {
        val c = company(sys); val a2 = fx.user("race-a2"); tenantService.setMember(c.id, a2.id, TenantRole.TENANT_ADMIN)
        val a2s = sessionFor(a2.username)
        assertThat(activeTenantAdmins(c)).containsExactlyInAnyOrder(c.adminId, a2.id)
        return Triple(c, a2.id, a2s)
    }

    private fun assertOneWinner(outcomes: List<Outcome>, conflictCode: String, what: String): Boolean {
        val winners = outcomes.count { it.status in 200..299 }
        assertThat(winners).describedAs("$what: exactly one request wins (statuses ${outcomes.map { "${it.status}/${it.code}" }})").isEqualTo(1)
        val loser = outcomes.single { it.status !in 200..299 }
        // 409 = both requests passed authorization and the second waited on the locks; 403 = the loser was DEMOTED first (still a member, no permission); 404 = the loser was REMOVED first
        // (no longer a member of the company, so the company is invisible to it). All three mean "never both".
        assertThat(loser.status).describedAs("$what: loser").isIn(409, 403, 404)
        if (loser.status == 409) assertThat(loser.code).describedAs(what).isEqualTo(conflictCode)
        return loser.status == 409
    }

    @Test
    fun `B1 two TENANT_ADMINs demote each other at the same time - exactly one succeeds, one TENANT_ADMIN remains (5 fresh companies)`() {
        val sys = sysAdmin(); var contended = 0
        repeat(ROUNDS) { round ->
            val (c, a2, a2s) = twoAdmins(sys)
            val outcomes = race(
                c.admin to { s -> s.put("${base(c)}/members/$a2", """{"role":"MEMBER"}""") },
                a2s to { s -> s.put("${base(c)}/members/${c.adminId}", """{"role":"MEMBER"}""") })
            if (assertOneWinner(outcomes, "LAST_TENANT_ADMIN", "demote round $round")) contended++
            assertThat(activeTenantAdmins(c)).describedAs("round $round: exactly one active TENANT_ADMIN").hasSize(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND active", Long::class.java, c.id)).describedAs("both stay members").isEqualTo(2L)
        }
        println("HARDENING-RACE demote: $ROUNDS rounds, $contended ended with 409 LAST_TENANT_ADMIN (the rest serialized to 403)")
    }

    @Test
    fun `B2 two TENANT_ADMINs remove each other at the same time - exactly one succeeds, one TENANT_ADMIN remains (5 fresh companies)`() {
        val sys = sysAdmin(); var contended = 0
        repeat(ROUNDS) { round ->
            val (c, a2, a2s) = twoAdmins(sys)
            val outcomes = race(
                c.admin to { s -> s.delete("${base(c)}/members/$a2") },
                a2s to { s -> s.delete("${base(c)}/members/${c.adminId}") })
            if (assertOneWinner(outcomes, "LAST_TENANT_ADMIN", "remove round $round")) contended++
            assertThat(activeTenantAdmins(c)).describedAs("round $round: exactly one active TENANT_ADMIN").hasSize(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND active", Long::class.java, c.id)).describedAs("the loser stays").isEqualTo(1L)
        }
        println("HARDENING-RACE remove: $ROUNDS rounds, $contended ended with 409 LAST_TENANT_ADMIN (the rest serialized to 403)")
    }

    @Test
    fun `B3 three TENANT_ADMINs each demote another one at the same time - at least one TENANT_ADMIN always remains`() {
        val sys = sysAdmin()
        repeat(ROUNDS) { round ->
            val (c, a2, a2s) = twoAdmins(sys)
            val a3 = fx.user("race-a3"); tenantService.setMember(c.id, a3.id, TenantRole.TENANT_ADMIN); val a3s = sessionFor(a3.username)
            val outcomes = race(
                c.admin to { s -> s.put("${base(c)}/members/$a2", """{"role":"MEMBER"}""") },
                a2s to { s -> s.put("${base(c)}/members/${a3.id}", """{"role":"MEMBER"}""") },
                a3s to { s -> s.put("${base(c)}/members/${c.adminId}", """{"role":"MEMBER"}""") })
            val ok = outcomes.count { it.status == 200 }
            assertThat(ok).describedAs("round $round ${outcomes.map { "${it.status}/${it.code}" }}").isBetween(1, 2)
            assertThat(outcomes.filter { it.status != 200 }.map { it.status }).allMatch { it == 409 || it == 403 }
            assertThat(activeTenantAdmins(c)).describedAs("round $round").hasSize(3 - ok).isNotEmpty()
        }
    }

    // ------------------------------------------------------------------------------------------------ SYSTEM_ADMIN
    /**
     * The LAST_SYSTEM_ADMIN condition is only reachable when S1 and S2 are the ONLY enabled system admins. The test database is shared by the classes of this JVM (they
     * run one after the other, never in parallel), and earlier classes leave platform operators behind: their flag is switched off for the duration of the test and
     * restored in a finally block (only rows that were enabled system admins at the start are touched).
     */
    @Test
    fun `B4 the only two SYSTEM_ADMINs revoke each other at the same time through the real route - never both, one platform operator remains (5 rounds)`() {
        val others = jdbc.queryForList("SELECT id FROM users WHERE system_admin AND enabled", UUID::class.java)
        println("HARDENING-RACE system admins enabled before the test: ${others.size}")
        var contended = 0
        try {
            if (others.isNotEmpty()) jdbc.update("UPDATE users SET system_admin = FALSE WHERE id IN (${others.joinToString(",") { "'$it'" }})")
            repeat(ROUNDS) { round ->
                val s1 = fx.user("race-s1", systemAdmin = true); val s2 = fx.user("race-s2", systemAdmin = true)
                val s1s = sessionFor(s1.username); val s2s = sessionFor(s2.username)
                assertThat(jdbc.queryForList("SELECT id FROM users WHERE system_admin AND enabled", UUID::class.java)).describedAs("round $round: only S1 and S2").containsExactlyInAnyOrder(s1.id, s2.id)
                val outcomes = race(
                    s1s to { s -> s.post("/api/v1/admin/users/${s2.id}/system-admin", """{"grant":false,"confirm":true}""") },
                    s2s to { s -> s.post("/api/v1/admin/users/${s1.id}/system-admin", """{"grant":false,"confirm":true}""") })
                if (assertOneWinner(outcomes, "LAST_SYSTEM_ADMIN", "system admin round $round")) contended++
                val left = jdbc.queryForList("SELECT id FROM users WHERE system_admin AND enabled", UUID::class.java)
                assertThat(left).describedAs("round $round: exactly one enabled SYSTEM_ADMIN remains").hasSize(1)
                jdbc.update("UPDATE users SET system_admin = FALSE WHERE id = ?", left.single())      // the next round starts again with exactly two
            }
        } finally {
            if (others.isNotEmpty()) jdbc.update("UPDATE users SET system_admin = TRUE WHERE id IN (${others.joinToString(",") { "'$it'" }})")
        }
        assertThat(jdbc.queryForList("SELECT id FROM users WHERE system_admin AND enabled", UUID::class.java)).containsExactlyInAnyOrderElementsOf(others)
        println("HARDENING-RACE system-admin revoke: $ROUNDS rounds, $contended ended with 409 LAST_SYSTEM_ADMIN (the rest serialized to 403)")
    }
}
