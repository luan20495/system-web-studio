package com.systemwebstudio.data.org

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The parts of the matrix that C1 proved only against its in-memory double, proved here against the REAL stack (HTTP -> C1 services -> C3 repositories -> PostgreSQL): unit-type rules
 * (allowRoot, allowedParentTypeIds, allowedChildTypeIds, maxDepth) on create / move / restore, the depth of a WHOLE moved subtree, canonical unit codes, and the lifecycle of memberships and
 * employee positions (ended rows stay as history). Every answer is cross-checked in the tables.
 */
class OrganizationRulesOnPostgresTests : PostgresOrganizationApiBase() {
    private fun unitRow(id: UUID) = jdbc.queryForMap("SELECT parent_id, code, active, deleted_at, version FROM organization_units WHERE id = ?", id)
    private fun createUnit(c: Company, type: JsonNode, code: String, parent: JsonNode? = null) =
        c.admin.post(units(c), """{"typeId":"${id(type)}","code":"$code","name":"$code"${if (parent != null) ""","parentId":"${id(parent)}"""" else ""}}""")
    private fun archive(c: Company, u: JsonNode) = c.admin.post("${units(c)}/${id(u)}/archive", """{"expectedVersion":${ver(unitNow(c, u))}}""")
    private fun restore(c: Company, u: JsonNode) = c.admin.post("${units(c)}/${id(u)}/restore", """{"expectedVersion":${ver(unitNow(c, u))}}""")
    private fun rule(c: Company, r: org.springframework.test.web.servlet.MvcResult, expected: String) {
        assertThat(r.response.status).describedAs(expected).isEqualTo(409); assertThat(code(r, c.admin)).isEqualTo("ORG_TYPE_RULE_VIOLATION"); assertThat(reason(c, r)).isEqualTo(expected)
    }

    @Test
    fun `type rules are ONE canonical JSONB document and drive create, move and restore - allowRoot, parent types, child types, maxDepth, whole subtree depth`() {
        val c = company()
        val mid = type(c, "mid"); val team = type(c, "team"); val other = type(c, "other")
        // team: never a root, only under mid or under another team, at most 4 levels deep; mid: a root, may only contain teams
        val teamPatched = c.admin.patch("${types(c)}/${id(team)}", """{"expectedVersion":${ver(team)},"rules":{"allowRoot":false,"allowedParentTypeIds":["${id(mid)}","${id(team)}"],"maxDepth":4}}""")
        assertThat(teamPatched.response.status).isEqualTo(200)
        val midPatched = c.admin.patch("${types(c)}/${id(mid)}", """{"expectedVersion":${ver(mid)},"rules":{"allowRoot":true,"allowedChildTypeIds":["${id(team)}"]}}""")
        assertThat(midPatched.response.status).isEqualTo(200)
        // the rules are stored as one JSONB object and read back unchanged
        assertThat(jdbc.queryForObject("SELECT rules->>'maxDepth' FROM organization_unit_types WHERE id = ?", String::class.java, id(team))).isEqualTo("4")
        assertThat(jdbc.queryForObject("SELECT (rules->>'allowRoot')::boolean FROM organization_unit_types WHERE id = ?", Boolean::class.java, id(team))).isFalse()
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(rules->'allowedParentTypeIds') FROM organization_unit_types WHERE id = ?", Int::class.java, id(team))).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(rules) FROM organization_unit_types WHERE id = ?", String::class.java, id(other))).isEqualTo("object")
        val read = c.admin.body(c.admin.get("${types(c)}/${id(team)}")); assertThat(read.get("rules").get("maxDepth").asInt()).isEqualTo(4); assertThat(read.get("rules").get("allowRoot").asBoolean()).isFalse()

        val hq = unit(c, mid, "hq"); val o1 = unit(c, other, "o1")
        // allowRoot
        rule(c, createUnit(c, team, "lonely"), "ROOT_NOT_ALLOWED")
        // allowedParentTypeIds (team under a type that is not listed) and allowedChildTypeIds (mid does not list `other`)
        rule(c, createUnit(c, team, "t-under-other", o1), "PARENT_TYPE_NOT_ALLOWED")
        rule(c, createUnit(c, other, "o-under-mid", hq), "CHILD_TYPE_NOT_ALLOWED")
        // maxDepth 4: HQ(1) -> t1(2) -> t2(3) -> t3(4) -> t4(5) refused
        val t1 = unit(c, team, "t1", hq); val t2 = unit(c, team, "t2", t1); val t3 = unit(c, team, "t3", t2)
        rule(c, createUnit(c, team, "t4", t3), "MAX_DEPTH")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_units WHERE tenant_id = ? AND code = 't4'", Long::class.java, c.id)).isZero()

        // a second tree: HQ2(1) -> s1(2) -> s2(3)
        val hq2 = unit(c, mid, "hq2"); val s1 = unit(c, team, "s1", hq2); val s2 = unit(c, team, "s2", s1)
        // MOVE: type rule failures
        rule(c, move(c, t1, null), "ROOT_NOT_ALLOWED"); rule(c, move(c, t1, o1), "PARENT_TYPE_NOT_ALLOWED"); rule(c, move(c, o1, hq), "CHILD_TYPE_NOT_ALLOWED")
        // MOVE: s1 itself would fit under t2 (level 4) but its descendant s2 would sit at level 5 -> the WHOLE subtree is checked, nothing is applied
        val before = unitRow(id(s1)); val beforeChild = unitRow(id(s2))
        rule(c, move(c, s1, t2), "MAX_DEPTH")
        assertThat(unitRow(id(s1))).describedAs("a refused move changes nothing").isEqualTo(before); assertThat(unitRow(id(s2))).isEqualTo(beforeChild)
        // under t1 (level 2) the same subtree fits: s1 = 3, s2 = 4
        val ok = move(c, s1, t1); assertThat(ok.response.status).isEqualTo(200)
        assertThat(jdbc.queryForObject("SELECT parent_id FROM organization_units WHERE id = ?", UUID::class.java, id(s1))).isEqualTo(id(t1))
        assertThat(jdbc.queryForObject("SELECT parent_id FROM organization_units WHERE id = ?", UUID::class.java, id(s2))).describedAs("descendants are not rewritten").isEqualTo(id(s1))
        assertThat(jdbc.queryForObject("""WITH RECURSIVE d(id, lvl) AS (SELECT id, 1 FROM organization_units WHERE id = ? UNION ALL SELECT u.id, d.lvl + 1 FROM organization_units u JOIN d ON u.parent_id = d.id) SELECT max(lvl) FROM d""",
            Int::class.java, id(hq))).describedAs("hq(1) -> t1(2) -> t2(3) -> t3(4) and t1(2) -> s1(3) -> s2(4): nothing deeper than the type allows").isEqualTo(4)
    }

    @Test
    fun `cycles are refused through the API - self, direct child and deep descendant - and the graph stays acyclic`() {
        val c = company(); val t = type(c, "node")
        val a = unit(c, t, "a"); val b = unit(c, t, "b", a); val cc = unit(c, t, "c", b); val d = unit(c, t, "d", cc)
        for ((who, under) in listOf(a to a, a to b, a to d, b to d, b to cc)) {
            val r = move(c, unitNow(c, who), under); assertThat(r.response.status).describedAs("${who.get("code")} under ${under.get("code")}").isEqualTo(409); assertThat(code(r, c.admin)).isEqualTo("ORG_CYCLE")
        }
        assertThat(jdbc.queryForObject("SELECT parent_id IS NULL FROM organization_units WHERE id = ?", Boolean::class.java, id(a))).isTrue()
        val cyclic = jdbc.queryForObject("""WITH RECURSIVE walk(id, path, cyc) AS (SELECT id, ARRAY[id], false FROM organization_units WHERE tenant_id = ? UNION ALL
            SELECT u.parent_id, path || u.parent_id, u.parent_id = ANY (path) FROM organization_units u JOIN walk w ON u.id = w.id WHERE u.parent_id IS NOT NULL AND NOT cyc) SELECT count(*) FROM walk WHERE cyc""", Long::class.java, c.id)
        assertThat(cyclic).isZero()
    }

    @Test
    fun `restore conflicts on PostgreSQL - code taken by a sibling, archived parent, type rule - each with its reason and nothing applied`() {
        val c = company(); val mid = type(c, "mid"); val team = type(c, "team")
        val hq = unit(c, mid, "hq"); val t1 = unit(c, team, "t1", hq); val t2 = unit(c, team, "t2", t1)
        assertThat(archive(c, t2).response.status).isEqualTo(200)
        // a sibling takes the released code -> the restore must not collide
        val usurper = unit(c, team, "t2", t1)
        val clash = restore(c, t2); assertThat(clash.response.status).isEqualTo(409); assertThat(code(clash, c.admin)).isEqualTo("RESTORE_CONFLICT"); assertThat(reason(c, clash)).isEqualTo("CODE_TAKEN")
        assertThat(unitRow(id(t2))["active"]).isEqualTo(false)
        assertThat(archive(c, usurper).response.status).isEqualTo(200)
        // the parent is archived -> restore the parent first
        assertThat(archive(c, t1).response.status).isEqualTo(200)
        val noParent = restore(c, t2); assertThat(noParent.response.status).isEqualTo(409); assertThat(reason(c, noParent)).isEqualTo("PARENT_ARCHIVED")
        assertThat(restore(c, t1).response.status).isEqualTo(200)
        // the type rule changed meanwhile: team may no longer sit below level 2 -> TYPE_RULE
        val tp = c.admin.patch("${types(c)}/${id(team)}", """{"expectedVersion":${ver(c.admin.body(c.admin.get("${types(c)}/${id(team)}")))},"rules":{"maxDepth":2}}""")
        assertThat(tp.response.status).isEqualTo(200)
        val rules = restore(c, t2); assertThat(rules.response.status).isEqualTo(409); assertThat(reason(c, rules)).isEqualTo("TYPE_RULE")
        assertThat(unitRow(id(t2))["deleted_at"]).describedAs("still archived").isNotNull()
    }

    @Test
    fun `unit code - canonical upper case, sibling scoped, roots are siblings, archived siblings release the code`() {
        val c = company(); val t = type(c, "dept")
        val root = unit(c, t, "hq"); assertThat(root.get("code").asString()).isEqualTo("HQ")
        assertThat(jdbc.queryForObject("SELECT code FROM organization_units WHERE id = ?", String::class.java, id(root))).isEqualTo("HQ")
        val sales = unit(c, t, "sales", root); assertThat(sales.get("code").asString()).isEqualTo("SALES")
        for (variant in listOf("sales", "Sales", "SALES", " sales ")) {
            val dup = createUnit(c, t, variant, root); assertThat(dup.response.status).describedAs("'$variant'").isEqualTo(409); assertThat(code(dup, c.admin)).isEqualTo("ORG_UNIT_CODE_TAKEN")
        }
        val rootDup = createUnit(c, t, "Hq"); assertThat(rootDup.response.status).describedAs("two roots with one code").isEqualTo(409); assertThat(code(rootDup, c.admin)).isEqualTo("ORG_UNIT_CODE_TAKEN")
        val other = unit(c, t, "ops", root); val under = unit(c, t, "sales", other)                          // same code under another parent: fine
        assertThat(under.get("code").asString()).isEqualTo("SALES")
        val rename = c.admin.patch("${units(c)}/${id(other)}", """{"expectedVersion":${ver(other)},"code":"Sales"}"""); assertThat(rename.response.status).isEqualTo(409); assertThat(code(rename, c.admin)).isEqualTo("ORG_UNIT_CODE_TAKEN")
        val bad = createUnit(c, t, "no spaces!", root); assertThat(bad.response.status).isEqualTo(400); assertThat(code(bad, c.admin)).isEqualTo("INVALID_CODE")
        // an archived sibling releases its code
        assertThat(archive(c, sales).response.status).isEqualTo(200)
        assertThat(createUnit(c, t, "sales", root).response.status).describedAs("the archived SALES released its code").isEqualTo(201)
        val sql = jdbc.queryForObject("SELECT count(*) FROM organization_units WHERE tenant_id = ? AND parent_id = ? AND code = 'SALES'", Long::class.java, c.id, id(root)); assertThat(sql).isEqualTo(2)   // one archived, one active
        // the database itself refuses a non-canonical code
        val e = runCatching { jdbc.update("UPDATE organization_units SET code = 'lower' WHERE id = ?", id(root)) }
        assertThat(e.exceptionOrNull()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test
    fun `membership and employee position lifecycle - ended rows stay as history, reassignment, nullable grade, one primary membership and one primary position`() {
        val c = company(); val t = type(c, "dept"); val a = unit(c, t, "a"); val b = unit(c, t, "b")
        val p1 = c.admin.body(c.admin.post(positions(c), """{"code":"P1","name":"Position 1"}""")); val p2 = c.admin.body(c.admin.post(positions(c), """{"code":"P2","name":"Position 2"}""")); val g = c.admin.body(c.admin.post(grades(c), """{"code":"G1","name":"Grade 1","rank":1}"""))
        val u = uid(newEmployee(c))
        fun addMembership(unit: JsonNode, primary: Boolean) = c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(unit)}","primary":$primary}""")
        fun addPosition(m: JsonNode, p: JsonNode, grade: JsonNode? = null, primary: Boolean = false) =
            c.admin.post("${employees(c)}/$u/positions", """{"membershipId":"${id(m)}","positionId":"${id(p)}"${if (grade != null) ""","gradeId":"${id(grade)}"""" else ""}${if (primary) ""","primary":true""" else ""}}""")
        fun mrow(id: UUID) = jdbc.queryForMap("SELECT active, is_primary, version FROM employee_organization_units WHERE id = ?", id)
        fun posNow(id: UUID) = c.admin.body(c.admin.get("${employees(c)}/$u/positions?includeInactive=true")).toList().single { id(it) == id }       // versions move when another assignment takes the primary flag
        fun primaries(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table WHERE tenant_id = ? AND user_id = ? AND is_primary", Long::class.java, c.id, u)!!

        val ma = c.admin.body(addMembership(a, true).also { assertThat(it.response.status).isEqualTo(201) }); val mb = c.admin.body(addMembership(b, false).also { assertThat(it.response.status).isEqualTo(201) })
        assertThat(addMembership(a, false).response.status).describedAs("an active duplicate (employee, unit)").isEqualTo(409)
        assertThat(primaries("employee_organization_units")).isEqualTo(1)
        // primary moves: exactly one primary membership at any time
        val promote = c.admin.patch("${employees(c)}/$u/organization-memberships/${id(mb)}", """{"primary":true,"expectedVersion":${ver(mb)}}"""); assertThat(promote.response.status).isEqualTo(200)
        assertThat(primaries("employee_organization_units")).isEqualTo(1); assertThat(mrow(id(ma))["is_primary"]).isEqualTo(false); assertThat(mrow(id(mb))["is_primary"]).isEqualTo(true)

        // positions: held within a membership, grade optional, the same position in another membership is allowed
        val e1 = c.admin.body(addPosition(ma, p1).also { assertThat(it.response.status).isEqualTo(201) })
        assertThat(jdbc.queryForObject("SELECT grade_id IS NULL FROM employee_positions WHERE id = ?", Boolean::class.java, id(e1))).describedAs("the grade is nullable").isTrue()
        val dup = addPosition(ma, p1, g); assertThat(dup.response.status).describedAs("same membership + position, even with another grade").isEqualTo(409); assertThat(code(dup, c.admin)).isEqualTo("POSITION_ASSIGNMENT_EXISTS")
        val e2 = c.admin.body(addPosition(mb, p1, g, primary = true).also { assertThat(it.response.status).isEqualTo(201) })
        assertThat(jdbc.queryForObject("SELECT grade_id FROM employee_positions WHERE id = ?", UUID::class.java, id(e2))).isEqualTo(id(g))
        assertThat(primaries("employee_positions")).isEqualTo(1)
        val e3 = c.admin.body(addPosition(mb, p2).also { assertThat(it.response.status).isEqualTo(201) })
        val pp = c.admin.patch("${employees(c)}/$u/positions/${id(e3)}", """{"primary":true,"expectedVersion":${ver(e3)}}"""); assertThat(pp.response.status).isEqualTo(200)
        assertThat(primaries("employee_positions")).describedAs("one primary position per employee").isEqualTo(1)

        // ending a position keeps the row (history); the same (membership, position) can be assigned again
        val v1 = ver(posNow(id(e1))); val end = c.admin.delete("${employees(c)}/$u/positions/${id(e1)}?expectedVersion=${ver(posNow(id(e1)))}"); assertThat(end.response.status).isIn(200, 204)
        val ended = jdbc.queryForMap("SELECT active, version FROM employee_positions WHERE id = ?", id(e1)); assertThat(ended["active"]).isEqualTo(false); assertThat((ended["version"] as Number).toLong()).isEqualTo(v1 + 1)
        val again = c.admin.body(addPosition(ma, p1).also { assertThat(it.response.status).describedAs("historical reassignment").isEqualTo(201) })
        assertThat(id(again)).isNotEqualTo(id(e1))
        assertThat(jdbc.queryForObject("SELECT count(*) FILTER (WHERE active) || '/' || count(*) FROM employee_positions WHERE membership_id = ? AND position_id = ?", String::class.java, id(ma), id(p1))).isEqualTo("1/2")

        // ending a membership that still holds an active position is refused; after the positions end it works and the row stays
        val refused = c.admin.delete("${employees(c)}/$u/organization-memberships/${id(ma)}?expectedVersion=${ver(c.admin.body(c.admin.get("${employees(c)}/$u/organization-memberships")).toList().single { id(it) == id(ma) })}")
        assertThat(refused.response.status).isEqualTo(409); assertThat(code(refused, c.admin)).isEqualTo("EMPLOYEE_ORG_HAS_POSITIONS")
        assertThat(c.admin.delete("${employees(c)}/$u/positions/${id(again)}?expectedVersion=${ver(posNow(id(again)))}").response.status).isIn(200, 204)
        val current = c.admin.body(c.admin.get("${employees(c)}/$u/organization-memberships")).toList().single { id(it) == id(ma) }
        val done = c.admin.delete("${employees(c)}/$u/organization-memberships/${id(ma)}?expectedVersion=${ver(current)}"); assertThat(done.response.status).isIn(200, 204)
        val row = mrow(id(ma)); assertThat(row["active"]).isEqualTo(false); assertThat(row["is_primary"]).isEqualTo(false); assertThat((row["version"] as Number).toLong()).isGreaterThan(ver(current))
        assertThat(rows("employee_organization_units", c.id)).describedAs("ended membership row remains").isEqualTo(2)
        assertThat(c.admin.body(c.admin.get("${employees(c)}/$u/organization-memberships")).toList().map { id(it) }).describedAs("default list = active only").doesNotContain(id(ma))
        assertThat(c.admin.body(c.admin.get("${employees(c)}/$u/organization-memberships?includeInactive=true")).toList().map { id(it) }).contains(id(ma), id(mb))
        // a new active membership in the same unit is a NEW row; the ended one stays as history
        val ma2 = c.admin.body(addMembership(a, false).also { assertThat(it.response.status).isEqualTo(201) })
        assertThat(id(ma2)).isNotEqualTo(id(ma))
        assertThat(jdbc.queryForObject("SELECT count(*) FILTER (WHERE active) || '/' || count(*) FROM employee_organization_units WHERE tenant_id = ? AND user_id = ? AND organization_unit_id = ?", String::class.java, c.id, u, id(a))).isEqualTo("1/2")
    }
}
