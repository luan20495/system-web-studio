package com.systemwebstudio.identity.authme

import com.systemwebstudio.identity.UserEntity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID

/**
 * M-052 - GET /api/v1/auth/me computes `tenants[].permissions` in memory: the SQL statement count of the request must not grow with the number of ACTIVE tenant memberships.
 * Exact property asserted: statements(T = 100) <= statements(T = 1) + 2 (an N+1 - one AccessService.forTenant / one query per tenant - fails it), plus a positive control.
 * One user grows from 1 to 10 to 100 memberships (bulk SQL, mixed TENANT_ADMIN / MEMBER); [AuthMeFixtureBase.purge] removes the tenants, memberships and the user afterwards.
 */
@Import(SqlStatementCountingConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TenantCardinalityQueryTests : AuthMeFixtureBase() {
    private val adminCodes = listOf(
        "EMPLOYEE_MANAGE", "EMPLOYEE_VIEW", "ORG_STRUCTURE_MANAGE", "ORG_STRUCTURE_VIEW", "POSITION_GRADE_MANAGE", "POSITION_GRADE_VIEW", "TENANT_MANAGE", "TENANT_MEMBERS"
    )

    /** adds tenants [from, to) with an ACTIVE membership of [user]: every third one TENANT_ADMIN, the rest MEMBER (2 statements, whatever the count) */
    private fun addMemberships(user: UserEntity, run: String, from: Int, to: Int) {
        jdbc.update("""INSERT INTO tenants (id, slug, name, status)
                       SELECT gen_random_uuid(), 'tc-' || ? || '-' || g, 'TC ' || g, 'ACTIVE' FROM generate_series(?, ? - 1) AS g""", run, from, to)
        jdbc.update("""INSERT INTO tenant_members (tenant_id, user_id, role, active)
                       SELECT t.id, ?, CASE WHEN split_part(t.slug, '-', 3)::int % 3 = 0 THEN 'TENANT_ADMIN' ELSE 'MEMBER' END, TRUE
                       FROM tenants t WHERE t.slug LIKE 'tc-' || ? || '-%' AND split_part(t.slug, '-', 3)::int >= ? AND split_part(t.slug, '-', 3)::int < ?""",
            user.id, run, from, to)
    }

    private fun expectedRoles(user: UserEntity): Map<String, String> = jdbc.query(
        """SELECT t.id, tm.role FROM tenant_members tm JOIN tenants t ON t.id = tm.tenant_id WHERE tm.user_id = ? AND tm.active AND t.status <> 'DELETED'""",
        { rs, _ -> rs.getObject(1, UUID::class.java).toString() to rs.getString(2) }, user.id).toMap()

    @Test
    fun `auth me statement count does not grow with the number of tenant memberships and every tenant entry carries the right permissions`() {
        val user = fx.user("tc-card")
        val run = UUID.randomUUID().toString().replace("-", "").take(10)
        val s = login(user)
        val statements = linkedMapOf<Int, Long>()
        val soft = SoftAssertions()
        var have = 0
        for (t in listOf(1, 10, 100)) {
            addMemberships(user, run, have, t); have = t
            val expected = expectedRoles(user)
            assertThat(expected).describedAs("fixture: $t active memberships").hasSize(t)
            repeat(2) { me(s) }                                    // warm-up (caches, lazy beans, JIT), discarded
            val m = measureMe(s)
            statements[t] = m.statements
            System.err.println("[TenantCardinality] T=$t statements(request thread)=${m.statements} statements(all threads)=${m.statementsAllThreads} ms=${"%.1f".format(m.millis)} bytes=${m.bytes}")

            val entries = m.body.get("tenants").toList()
            soft.assertThat(entries).describedAs("T=$t: one entry per active membership").hasSize(t)
            soft.assertThat(entries.map { it.get("id").asString() }.toSet()).describedAs("T=$t: tenant ids").isEqualTo(expected.keys)
            for (e in entries) {
                val id = e.get("id").asString(); val role = expected[id] ?: continue
                val perms = e.get("permissions").toList().map { it.asString() }
                soft.assertThat(e.get("role").asString()).describedAs("role of $id").isEqualTo(role)
                if (role == "TENANT_ADMIN") soft.assertThat(perms).describedAs("admin entry $id").isEqualTo(adminCodes)
                else soft.assertThat(perms).describedAs("member entry $id").isEmpty()
            }
            // root permissions: the primary (oldest membership, here the first listed) only, never the union of the admin entries
            val primary = entries.first()
            soft.assertThat(m.body.get("tenantId").asString()).describedAs("T=$t: primary").isEqualTo(primary.get("id").asString())
            soft.assertThat(m.body.get("permissions").toList().map { it.asString() }).describedAs("T=$t: root permissions = the primary's role set")
                .isEqualTo(primary.get("permissions").toList().map { it.asString() })
        }
        System.err.println("[TenantCardinality] t=1:${statements[1]} t=10:${statements[10]} t=100:${statements[100]}")
        soft.assertAll()
        assertThat(statements.getValue(1)).describedAs("positive control: the statement counter really counts (raw: $statements)").isGreaterThanOrEqualTo(4)
        assertThat(statements.getValue(100))
            .describedAs("statements of /auth/me with 100 tenant memberships must not exceed statements with 1 membership + 2 (raw: $statements)")
            .isLessThanOrEqualTo(statements.getValue(1) + 2)
    }
}
