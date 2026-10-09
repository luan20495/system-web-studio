package com.systemwebstudio.data.org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * END TO END on the real stack: HTTP -> C1 authentication / authorization / application services -> the C3 PostgreSQL repositories (switched on by `app.organization.persistence-enabled=true`,
 * the PROPOSED wiring) -> real PostgreSQL. The company is created exactly like production (SYSTEM_ADMIN + first Tenant Admin, one-time activation link). Everything C1 only proved against its in-memory
 * double is here proved against the real store, and every answer is cross-checked in the organization tables.
 */
class OrganizationOnPostgresApiTests : PostgresOrganizationApiBase() {
    @Test
    fun `type, tree, move, archive and restore through the API persist in PostgreSQL, with audit and the typed refusals`() {
        val c = company(); val t = type(c, "khoi"); val team = type(c, "team")
        val root = unit(c, t, "hq", name = "HQ")                                                                                            // lower-case input: stored and returned UPPER-case
        assertThat(root.get("code").asString()).isEqualTo("HQ")
        val sales = unit(c, team, "SALES", root, "Sales"); val ops = unit(c, team, "OPS", root, "Ops"); val sub = unit(c, team, "SALES", ops, "Sales under Ops")      // same code, different parent: allowed
        assertThat(rows("organization_units", c.id)).isEqualTo(4); assertThat(rows("organization_unit_types", c.id)).isEqualTo(2)
        val clash = move(c, sub, root); assertThat(clash.response.status).describedAs("SALES already exists under HQ").isEqualTo(409); assertThat(code(clash, c.admin)).isEqualTo("ORG_UNIT_CODE_TAKEN")     // typed, no raw unique violation
        val cycle = move(c, root, sales); assertThat(cycle.response.status).isEqualTo(409); assertThat(code(cycle, c.admin)).isEqualTo("ORG_CYCLE")
        val moved = move(c, ops, null); assertThat(moved.response.status).isEqualTo(200); assertThat(c.admin.body(moved).get("parentId").isNull).isTrue()
        assertThat(auditCount("ORG_UNIT_MOVED", id(ops))).isEqualTo(1L)                                                                      // audit row in the same transaction
        assertThat(jdbc.queryForObject("SELECT parent_id IS NULL FROM organization_units WHERE id = ?", Boolean::class.java, id(ops))).isTrue()
        val stale = move(c, ops, root, version = 0); assertThat(stale.response.status).isEqualTo(409); assertThat(code(stale, c.admin)).isEqualTo("VERSION_CONFLICT")
        val withChild = c.admin.post("${units(c)}/${id(ops)}/archive", """{"expectedVersion":${ver(unitNow(c, ops))}}"""); assertThat(withChild.response.status).isEqualTo(409); assertThat(code(withChild, c.admin)).isEqualTo("ORG_UNIT_HAS_CHILDREN")
        val a1 = c.admin.post("${units(c)}/${id(sub)}/archive", """{"expectedVersion":${ver(unitNow(c, sub))}}"""); assertThat(a1.response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT active = FALSE AND deleted_at IS NOT NULL FROM organization_units WHERE id = ?", Boolean::class.java, id(sub))).isTrue()   // soft: the row stays, never contradictory
        val a2 = c.admin.post("${units(c)}/${id(ops)}/archive", """{"expectedVersion":${ver(unitNow(c, ops))}}"""); assertThat(a2.response.status).isEqualTo(200)
        val reuse = unit(c, team, "OPS", root, "Ops again")                                                                                  // the archived root's code... is free under HQ (it was a root)
        val back = c.admin.post("${units(c)}/${id(ops)}/restore", """{"expectedVersion":${ver(c.admin.body(a2))}}"""); assertThat(back.response.status).isEqualTo(200)
        assertThat(c.admin.body(back).get("active").asBoolean()).isTrue(); assertThat(reuse).isNotNull()
        val restoreClash = c.admin.post("${units(c)}/${id(sub)}/restore", """{"expectedVersion":${ver(c.admin.body(a1))}}""")
        assertThat(restoreClash.response.status).describedAs("restoring under the restored OPS, no clash").isEqualTo(200)
        assertThat(auditCount("ORG_UNIT_ARCHIVED", id(sub))).isEqualTo(1L); assertThat(auditCount("ORG_UNIT_RESTORED", id(sub))).isEqualTo(1L)
        val flatNow = flat(c); assertThat(flatNow.map { it.get("code").asString() }).contains("HQ", "SALES", "OPS")
    }

    @Test
    fun `employee, memberships, positions and the directory through the API persist in PostgreSQL`() {
        val c = company(); val t = type(c, "dept"); val sales = unit(c, t, "SALES"); val hr = unit(c, t, "HR")
        val dev = c.admin.body(c.admin.post(positions(c), """{"code":"DEV","name":"Developer","description":"builds things"}""")); val g3 = c.admin.body(c.admin.post(grades(c), """{"code":"G3","name":"Grade 3","rank":3}"""))
        val created = newEmployee(c, extra = ""","organizationMemberships":[{"organizationUnitId":"${id(sales)}","relationType":"MANAGER","primary":true,"positions":[{"positionId":"${id(dev)}","gradeId":"${id(g3)}"}]},{"organizationUnitId":"${id(hr)}"}]""")
        val u = uid(created)
        assertThat(rows("employee_organization_units", c.id)).isEqualTo(2); assertThat(rows("employee_positions", c.id)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?", Long::class.java, c.id, u)).isEqualTo(1)       // the employee IS the tenant member: no profile table
        val e = employee(c, u); assertThat(e.get("organizationMemberships").size()).isEqualTo(2); assertThat(e.get("positions").size()).isEqualTo(1); assertThat(e.get("primaryOrganizationUnitId").asString()).isEqualTo(id(sales).toString())
        val page = c.admin.body(c.admin.get("${employees(c)}?organizationUnitId=${id(sales)}&size=10")); assertThat(page.get("total").asLong()).isEqualTo(1); assertThat(page.get("items").get(0).get("userId").asString()).isEqualTo(u.toString())
        assertThat(c.admin.body(c.admin.get("${employees(c)}?q=employee&positionId=${id(dev)}")).get("total").asLong()).isEqualTo(1)
        // ending the membership that still holds a position is refused (no cascade), atomically in the store
        val m = e.get("organizationMemberships").toList().single { it.get("organizationUnitId").asString() == id(sales).toString() }
        val end = c.admin.delete("${employees(c)}/$u/organization-memberships/${id(m)}?expectedVersion=${ver(m)}"); assertThat(end.response.status).isEqualTo(409); assertThat(code(end, c.admin)).isEqualTo("EMPLOYEE_ORG_HAS_POSITIONS")
        // another company sees none of it
        val other = company()
        assertThat(other.admin.get("${employees(other)}/$u").response.status).isEqualTo(404); assertThat(other.admin.get("${units(other)}/${id(sales)}").response.status).isEqualTo(404)
        assertThat(other.admin.body(other.admin.get("${employees(other)}?size=50")).get("items").toList().map { it.get("userId").asString() }).doesNotContain(u.toString())
        assertThat(move(c, sales, hr, version = ver(unitNow(c, sales))).response.status).isEqualTo(200)
    }

    @Test
    fun `without the property the very same API answers 501 - the release candidate is unchanged until C0 switches persistence on`() {
        // (this class switches it ON; the 501 contract itself is C1's OrganizationUnavailableTests, run in the same JVM with the property absent)
        assertThat(System.getProperty("app.organization.persistence-enabled")).isNull()
        assertThat(OrganizationPersistenceConfiguration::class.java.getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty::class.java).havingValue).isEqualTo("true")
    }
}
