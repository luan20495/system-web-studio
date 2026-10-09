package com.systemwebstudio.organization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * C1 CONTRACT TESTS: the limits of the employee directory. The canonical position limit is 20 ACTIVE positions PER MEMBERSHIP (C1 contract decision, scoped by `membershipId`),
 * NOT per employee: each membership of the same employee has its own 20. The 21st answers `400 VALIDATION_FAILED` and creates nothing; an ended position frees its slot; the
 * nested `POST /employees` form enforces the same limit per nested list. Few accounts are created on purpose (the shared database caps users; the base class purges them).
 */
@Import(InMemoryOrganizationConfig::class)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OrganizationLimitsTests : OrganizationTestBase() {
    private val limit = EmployeeDirectoryService.MAX_POSITIONS
    private fun usersCount() = jdbc.queryForObject("SELECT count(*) FROM users", Long::class.java)!!
    private fun member(c: Company, u: UUID, unit: JsonNode) = c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(unit)}"}""")
        .also { assertThat(it.response.status).describedAs("membership").isEqualTo(201) }.let { c.admin.body(it) }
    private fun held(c: Company, u: UUID, inactive: Boolean = false) = c.admin.body(c.admin.get("${employees(c)}/$u/positions?includeInactive=$inactive")).toList()
    private fun heldIn(c: Company, u: UUID, m: JsonNode) = held(c, u).count { it.get("membershipId").asString() == id(m).toString() }
    /** `count` catalog positions P00, P01, ... */
    private fun catalog(c: Company, count: Int) = (0 until count).map { i ->
        c.admin.post(positions(c), """{"code":"P${"%02d".format(i)}","name":"Position $i"}""").also { assertThat(it.response.status).isEqualTo(201) }.let { c.admin.body(it) }
    }

    @Test
    fun `20 active positions PER MEMBERSHIP - a second membership has its own limit, the 21st is refused and creates nothing, an ended position frees a slot`() {
        assertThat(limit).isEqualTo(20)
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A"); val b = unit(c, t, "B"); val gone = unit(c, t, "GONE")
        val pos = catalog(c, limit + 1)
        val e = uid(newEmployee(c)); val mA = member(c, e, a); val mB = member(c, e, b)
        fun hold(m: JsonNode, p: JsonNode) = c.admin.post("${employees(c)}/$e/positions", """{"membershipId":"${id(m)}","positionId":"${id(p)}"}""")
        // membership A holds exactly 20 active positions
        pos.take(limit).forEach { p -> assertThat(hold(mA, p).response.status).describedAs("A <- ${p.get("code").asString()}").isEqualTo(201) }
        assertThat(heldIn(c, e, mA)).isEqualTo(limit)
        // membership B of the SAME employee independently accepts its own 20 (the limit is not per employee)
        pos.take(limit).forEach { p -> assertThat(hold(mB, p).response.status).describedAs("B <- ${p.get("code").asString()}").isEqualTo(201) }
        assertThat(heldIn(c, e, mB)).isEqualTo(limit); assertThat(held(c, e)).hasSize(2 * limit)
        // the 21st on A (and on B) is refused with the canonical error, and nothing is created
        for (m in listOf(mA, mB)) {
            val over = hold(m, pos[limit]); assertThat(over.response.status).isEqualTo(400)
            assertThat(code(over, c.admin)).isEqualTo("VALIDATION_FAILED"); assertThat(c.admin.body(over).get("message").asString()).contains("membership").contains("$limit")
        }
        assertThat(held(c, e, true)).describedAs("nothing created by the refused requests").hasSize(2 * limit)
        assertThat(auditCount("EMPLOYEE_POSITION_ASSIGNED", e)).isEqualTo(2L * limit)
        // ending one position on A frees exactly one slot on A
        val one = held(c, e).first { it.get("membershipId").asString() == id(mA).toString() }
        assertThat(c.admin.delete("${employees(c)}/$e/positions/${id(one)}?expectedVersion=${ver(one)}").response.status).isEqualTo(200)
        assertThat(hold(mA, pos[limit]).response.status).describedAs("the freed slot").isEqualTo(201)
        assertThat(heldIn(c, e, mA)).isEqualTo(limit)
        assertThat(code(hold(mA, one.let { p -> pos.single { id(it).toString() == p.get("positionId").asString() } }), c.admin)).describedAs("full again").isEqualTo("VALIDATION_FAILED")
        // the membership checks come first and keep their codes: another employee's membership, an ended membership, another tenant's membership
        val other = uid(newEmployee(c)); val mOther = member(c, other, b)
        assertThat(code(hold(mOther, pos[0]), c.admin)).describedAs("another employee's membership").isEqualTo("ORG_MEMBERSHIP_NOT_FOUND")
        val mGone = member(c, e, gone)
        assertThat(c.admin.delete("${employees(c)}/$e/organization-memberships/${id(mGone)}?expectedVersion=${ver(mGone)}").response.status).isEqualTo(200)
        val ended = hold(mGone, pos[0]); assertThat(ended.response.status).isEqualTo(409); assertThat(code(ended, c.admin)).describedAs("an ended membership").isEqualTo("ORG_MEMBERSHIP_INACTIVE")
        val foreign = company(); val ft = type(foreign, "unit"); val fu = unit(foreign, ft, "F"); val fe = uid(newEmployee(foreign))
        val mForeign = foreign.admin.body(foreign.admin.post("${employees(foreign)}/$fe/organization-memberships", """{"organizationUnitId":"${id(fu)}"}"""))
        val cross = hold(mForeign, pos[0]); assertThat(cross.response.status).isEqualTo(404); assertThat(code(cross, c.admin)).describedAs("another tenant's membership").isEqualTo("ORG_MEMBERSHIP_NOT_FOUND")
        assertThat(held(c, other, true)).isEmpty(); assertThat(heldIn(c, e, mA)).isEqualTo(limit); assertThat(heldIn(c, e, mB)).isEqualTo(limit)
    }

    @Test
    fun `the nested create form enforces the same per-membership limit - 21 in one membership is refused atomically, 20 plus 20 in two memberships is accepted`() {
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A"); val b = unit(c, t, "B")
        val pos = catalog(c, limit + 1)
        fun nested(list: List<JsonNode>) = list.joinToString(",", "[", "]") { """{"positionId":"${id(it)}"}""" }
        val users = usersCount()
        val over = c.admin.post(employees(c), """{"username":"${uname("over")}","displayName":"Over","organizationMemberships":[{"organizationUnitId":"${id(a)}","positions":${nested(pos)}}]}""")
        assertThat(over.response.status).isEqualTo(400); assertThat(code(over, c.admin)).isEqualTo("VALIDATION_FAILED")
        assertThat(usersCount()).describedAs("no account created").isEqualTo(users)
        assertThat(c.admin.body(c.admin.get("${units(c)}/${id(a)}")).get("activeMemberCount").asInt()).isZero()
        // 20 in A and 20 in B: each membership is within its own limit (the old per-employee count would have refused 40)
        val ok = c.admin.post(employees(c), """{"username":"${uname("ok")}","displayName":"Ok","organizationMemberships":[{"organizationUnitId":"${id(a)}","positions":${nested(pos.take(limit))}},{"organizationUnitId":"${id(b)}","positions":${nested(pos.drop(1))}}]}""")
        assertThat(ok.response.status).isEqualTo(201)
        val emp = c.admin.body(ok).get("employee"); val ms = emp.get("organizationMemberships").toList(); val ps = emp.get("positions").toList()
        assertThat(ps).hasSize(2 * limit)
        for (m in ms) assertThat(ps.count { it.get("membershipId").asString() == m.get("id").asString() }).isEqualTo(limit)
        assertThat(ps.count { it.get("primary").asBoolean() }).describedAs("one primary position").isEqualTo(1)
        // and the same membership is then full for the single-assignment route too
        val u = UUID.fromString(emp.get("userId").asString()); val mA = ms.single { it.get("organizationUnitId").asString() == id(a).toString() }
        assertThat(code(c.admin.post("${employees(c)}/$u/positions", """{"membershipId":"${id(mA)}","positionId":"${id(pos[limit])}"}"""), c.admin)).isEqualTo("VALIDATION_FAILED")
        assertThat(usersCount()).isEqualTo(users + 1)
    }
}
