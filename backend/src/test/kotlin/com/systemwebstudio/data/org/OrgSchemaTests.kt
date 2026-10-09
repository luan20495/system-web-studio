package com.systemwebstudio.data.org

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** The schema itself, on the real database: structure (tables, composite FKs, partial unique indexes, checks) and the guarantees it gives with RAW SQL - no repository, no service check involved. */
class OrgSchemaTests {
    private val jdbc = OrgTestDb.jdbc
    private val fx = OrgFx()
    private fun id() = UUID.randomUUID()
    private fun refused(constraint: String, sql: () -> Unit) { assertThatThrownBy { sql() }.hasMessageContaining("\"$constraint\"") }

    private fun unitSql(t: UUID, type: UUID, parent: UUID?, code: String, uid: UUID = id()): UUID {
        jdbc.update("INSERT INTO organization_units (id, tenant_id, type_id, parent_id, code, name) VALUES (?, ?, ?, ?, ?, ?)", uid, t, type, parent, code, code); return uid
    }

    @Test
    fun `the six tables, every named constraint and every index exist (composite FKs carry the tenant)`() {
        val tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('organization_unit_types','organization_units','employee_organization_units','positions','grades','employee_positions')", String::class.java)
        assertThat(tables).containsExactlyInAnyOrder("organization_unit_types", "organization_units", "employee_organization_units", "positions", "grades", "employee_positions")
        val constraints = jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid::regclass::text IN ('organization_unit_types','organization_units','employee_organization_units','positions','grades','employee_positions')", String::class.java)
        assertThat(constraints).contains(
            "organization_units_type_fk", "organization_units_parent_fk", "organization_units_lifecycle_check", "organization_units_not_self", "organization_units_code_check", "organization_units_id_tenant_unique",
            "employee_organization_units_member_fk", "employee_organization_units_unit_fk", "employee_organization_units_primary_active", "employee_organization_units_id_tenant_user_unique",
            "employee_positions_membership_fk", "employee_positions_position_fk", "employee_positions_grade_fk", "employee_positions_primary_active",
            "organization_unit_types_id_tenant_unique", "positions_id_tenant_unique", "grades_id_tenant_unique"
        )
        // every foreign key of the new tables that points at a tenant-owned table is COMPOSITE and carries tenant_id (the FK to tenants itself is the plain one)
        val fks = jdbc.queryForList(
            """SELECT conrelid::regclass::text || '.' || conname || ' -> ' || confrelid::regclass::text || ' (' || array_length(conkey, 1) || ' cols)' FROM pg_constraint
               WHERE contype = 'f' AND conrelid::regclass::text IN ('organization_unit_types','organization_units','employee_organization_units','positions','grades','employee_positions') AND confrelid::regclass::text <> 'tenants'""", String::class.java
        )
        assertThat(fks).hasSize(7); assertThat(fks.filter { it.contains("tenant_members") }.single()).contains("(2 cols)")
        assertThat(fks.single { it.startsWith("employee_positions.employee_positions_membership_fk") }).contains("(3 cols)")                       // membership + tenant + EMPLOYEE
        assertThat(fks.filterNot { it.startsWith("employee_positions.employee_positions_membership_fk") }).allMatch { it.contains("(2 cols)") }
        fun def(name: String) = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?", String::class.java, name)!!
        assertThat(def("organization_units_sibling_code_unique")).contains("UNIQUE").contains("NULLS NOT DISTINCT").contains("WHERE (deleted_at IS NULL)")
        assertThat(def("employee_organization_units_active_unique")).contains("UNIQUE").contains("WHERE active")
        assertThat(def("employee_organization_units_one_primary_idx")).contains("UNIQUE").contains("WHERE is_primary")
        assertThat(def("employee_positions_active_unique")).contains("UNIQUE").contains("(membership_id, position_id)").contains("WHERE active")
        assertThat(def("employee_positions_one_primary_idx")).contains("UNIQUE").contains("WHERE is_primary")
        assertThat(def("organization_units_children_idx")).contains("(tenant_id, parent_id, sort_order, name, id)")
        listOf("organization_unit_types_code_unique", "positions_code_unique", "grades_code_unique").forEach { assertThat(def(it)).contains("UNIQUE").contains("lower(") }
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name = 'organization_units' AND column_name = 'version'", String::class.java)).isEqualTo("0")
    }

    @Test
    fun `DB rejects a unit whose parent or type belongs to another tenant, now and on update`() {
        val a = fx.tenant(); val b = fx.tenant(); val ta = fx.type(a); val tb = fx.type(b); val ua = fx.unit(a, ta, "UA"); val ub = fx.unit(b, tb, "UB")
        refused("organization_units_parent_fk") { unitSql(a, ta.id, ub.id, "X1") }
        refused("organization_units_type_fk") { unitSql(a, tb.id, null, "X2") }
        val child = unitSql(a, ta.id, ua.id, "X3")
        refused("organization_units_parent_fk") { jdbc.update("UPDATE organization_units SET parent_id = ? WHERE id = ?", ub.id, child) }
        refused("organization_units_type_fk") { jdbc.update("UPDATE organization_units SET type_id = ? WHERE id = ?", tb.id, child) }
        refused("organization_units_not_self") { jdbc.update("UPDATE organization_units SET parent_id = id WHERE id = ?", child) }
    }

    @Test
    fun `DB rejects a membership of a foreign unit or of somebody who is not a tenant member`() {
        val a = fx.tenant(); val b = fx.tenant(); val ta = fx.type(a); val tb = fx.type(b); val ua = fx.unit(a, ta, "UA"); val ub = fx.unit(b, tb, "UB"); val userA = fx.member(a); val userB = fx.member(b)
        fun raw(t: UUID, u: UUID, unit: UUID) = jdbc.update("INSERT INTO employee_organization_units (id, tenant_id, user_id, organization_unit_id) VALUES (?, ?, ?, ?)", id(), t, u, unit)
        refused("employee_organization_units_unit_fk") { raw(a, userA, ub.id) }
        refused("employee_organization_units_member_fk") { raw(a, userB, ua.id) }
        refused("employee_organization_units_member_fk") { raw(b, userA, ub.id) }
        assertThat(raw(a, userA, ua.id)).isEqualTo(1)
    }

    @Test
    fun `DB rejects an employee position with a position, grade or membership of another tenant or of another employee`() {
        val a = fx.tenant(); val b = fx.tenant(); val ta = fx.type(a); val tb = fx.type(b); val ua = fx.unit(a, ta, "UA"); val ub = fx.unit(b, tb, "UB")
        val alice = fx.member(a); val alice2 = fx.member(a); val bob = fx.member(b)
        val mAlice = fx.membership(a, alice, ua.id); val mAlice2 = fx.membership(a, alice2, ua.id); val mBob = fx.membership(b, bob, ub.id)
        val posA = fx.position(a); val posB = fx.position(b); val gA = fx.grade(a); val gB = fx.grade(b)
        fun raw(t: UUID, user: UUID, membership: UUID, position: UUID, grade: UUID?) =
            jdbc.update("INSERT INTO employee_positions (id, tenant_id, user_id, membership_id, position_id, grade_id) VALUES (?, ?, ?, ?, ?, ?)", id(), t, user, membership, position, grade)
        refused("employee_positions_position_fk") { raw(a, alice, mAlice.id, posB.id, null) }
        refused("employee_positions_grade_fk") { raw(a, alice, mAlice.id, posA.id, gB.id) }
        refused("employee_positions_membership_fk") { raw(a, alice, mBob.id, posA.id, null) }
        refused("employee_positions_membership_fk") { raw(a, alice, mAlice2.id, posA.id, null) }          // another employee's membership of the same tenant
        refused("employee_positions_membership_fk") { raw(a, alice, id(), posA.id, null) }
        assertThat(raw(a, alice, mAlice.id, posA.id, gA.id)).isEqualTo(1)
        refused("employee_organization_units_member_fk") { jdbc.update("DELETE FROM tenant_members WHERE tenant_id = ? AND user_id = ?", a, alice) }   // an employee with memberships cannot vanish
    }

    @Test
    fun `sibling code, root scope, lifecycle and primary rules are enforced by the database`() {
        val a = fx.tenant(); val b = fx.tenant(); val ta = fx.type(a); val tb = fx.type(b)
        val rootX = unitSql(a, ta.id, null, "HQ")
        refused("organization_units_sibling_code_unique") { unitSql(a, ta.id, null, "HQ") }                  // two roots, one code
        val x = unitSql(a, ta.id, rootX, "OPS"); refused("organization_units_sibling_code_unique") { unitSql(a, ta.id, rootX, "OPS") }
        unitSql(a, ta.id, unitSql(a, ta.id, null, "FIN"), "OPS"); unitSql(b, tb.id, null, "HQ")             // another parent, another tenant: allowed
        refused("organization_units_code_check") { unitSql(a, ta.id, null, "lower") }                         // canonical upper-case only
        refused("organization_units_lifecycle_check") { jdbc.update("UPDATE organization_units SET deleted_at = now() WHERE id = ?", x) }   // archived but active
        jdbc.update("UPDATE organization_units SET active = FALSE, deleted_at = now() WHERE id = ?", x); unitSql(a, ta.id, rootX, "OPS")      // archived releases the code
        refused("organization_units_sibling_code_unique") { jdbc.update("UPDATE organization_units SET active = TRUE, deleted_at = NULL WHERE id = ?", x) }
        val u = fx.member(a); val unit = fx.unit(a, ta, "MU"); val m1 = fx.membership(a, u, unit.id); val unit2 = fx.unit(a, ta, "MU2")
        fun rawMember(unitId: UUID) = jdbc.update("INSERT INTO employee_organization_units (id, tenant_id, user_id, organization_unit_id) VALUES (?, ?, ?, ?)", id(), a, u, unitId)
        refused("employee_organization_units_active_unique") { rawMember(unit.id) }
        jdbc.update("UPDATE employee_organization_units SET is_primary = TRUE WHERE id = ?", m1.id)
        val m2 = fx.membership(a, u, unit2.id); refused("employee_organization_units_one_primary_idx") { jdbc.update("UPDATE employee_organization_units SET is_primary = TRUE WHERE id = ?", m2.id) }
        refused("employee_organization_units_primary_active") { jdbc.update("UPDATE employee_organization_units SET active = FALSE WHERE id = ?", m1.id) }
        refused("organization_unit_types_code_unique") { jdbc.update("INSERT INTO organization_unit_types (id, tenant_id, code, name) VALUES (?, ?, ?, 'x')", id(), a, ta.code.uppercase()) }                    // type code: case-insensitive per tenant
    }
}
