package com.systemwebstudio.organization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * C1 CONTRACT TESTS: the employee directory (an employee IS a tenant member: no profile entity), multi-org memberships, positions held WITHIN a membership (with grade),
 * directory search, enable / disable as the tenant-membership lifecycle, and the atomic orchestration with account provisioning. In-memory test double for the C3 seams;
 * real PostgreSQL for accounts, tenants, authorization and audit.
 */
@Import(InMemoryOrganizationConfig::class)
class EmployeeApiContractTests : OrganizationTestBase() {
    private fun usersCount() = jdbc.queryForObject("SELECT count(*) FROM users", Long::class.java)!!
    private fun page(c: Company, q: String) = c.admin.body(c.admin.get("${employees(c)}?$q"))
    private fun names(p: JsonNode) = p.get("items").toList().map { it.get("username").asString() }
    private fun memberships(c: Company, u: UUID, inactive: Boolean = false) = c.admin.body(c.admin.get("${employees(c)}/$u/organization-memberships?includeInactive=$inactive")).toList()
    private fun heldPositions(c: Company, u: UUID, inactive: Boolean = false) = c.admin.body(c.admin.get("${employees(c)}/$u/positions?includeInactive=$inactive")).toList()
    private fun primaries(list: List<JsonNode>) = list.filter { it.get("primary").asBoolean() && it.get("active").asBoolean() }
    private fun member(c: Company, u: UUID, unit: JsonNode, extra: String = "") = c.admin.body(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(unit)}"$extra}"""))

    // ------------------------------------------------------------------------------------------------ create through the canonical provisioning
    @Test
    fun `G an employee is one account (canonical provisioning) plus an activation link - no profile, no second identity, every tenant member is an employee`() {
        val c = company(); val before = usersCount(); val name = uname("anna")
        val created = newEmployee(c, name, ""","email":"$name@example.com"""")
        assertThat(usersCount()).isEqualTo(before + 1)
        val e = created.get("employee"); val link = created.get("activation")
        assertThat(e.get("username").asString()).isEqualTo(name); assertThat(e.get("displayName").asString()).isEqualTo("Employee $name"); assertThat(e.get("email").asString()).isEqualTo("$name@example.com")
        assertThat(e.get("active").asBoolean()).describedAs("tenant_members.active").isTrue(); assertThat(e.get("tenantRole").asString()).isEqualTo("MEMBER"); assertThat(e.get("accountActivated").asBoolean()).isFalse()
        assertThat(e.get("positions").size()).isZero(); assertThat(e.get("organizationMemberships").size()).isZero(); assertThat(e.get("primaryOrganizationUnitId").isNull).isTrue()
        assertThat(link.get("purpose").asString()).isEqualTo("ACTIVATION"); assertThat(link.has("password")).isFalse()
        val keys = (json.convertValue(e, Map::class.java) as Map<*, *>).keys.map { it.toString() }
        assertThat(keys).describedAs("no credential, and NO employee-profile field (EMPLOYEE_PROFILE = NOT_NEEDED)").doesNotContain("password", "passwordHash", "token", "authSource", "employeeCode", "phone", "joinedOn", "metadata", "status", "version")
        assertThat(jdbc.queryForMap("SELECT system_admin, activated_at FROM users WHERE id = ?", uid(created))).containsEntry("system_admin", false).containsEntry("activated_at", null)
        assertThat(jdbc.queryForObject("SELECT role FROM tenant_members WHERE tenant_id = ? AND user_id = ? AND active", String::class.java, c.id, uid(created))).isEqualTo("MEMBER")
        val s = activateAndLogin(name, link.get("token").asString()); assertThat(s.body(s.get("/api/v1/auth/me")).get("username").asString()).isEqualTo(name)
        assertThat(employee(c, uid(created)).get("accountActivated").asBoolean()).isTrue()
        // an existing tenant member IS an employee already: the Tenant Admin is in the directory without any extra record
        assertThat(employee(c, c.adminId).get("tenantRole").asString()).isEqualTo("TENANT_ADMIN"); assertThat(page(c, "size=100").get("total").asLong()).isEqualTo(2)
        // a person of another tenant / an unknown id is a plain 404
        assertThat(c.admin.get("${employees(c)}/${UUID.randomUUID()}").response.status).isEqualTo(404)
        // the account rules are the provisioning rules
        for ((body, expected) in listOf(
            """{"username":"$name","displayName":"Again"}""" to "USERNAME_TAKEN", """{"username":"Bad Name","displayName":"x"}""" to "INVALID_USERNAME",
            """{"username":"${uname("m")}","displayName":"x","email":"$name@example.com"}""" to "EMAIL_TAKEN", """{"username":"${uname("t")}","displayName":"x","tenantRole":"SYSTEM_ADMIN"}""" to "TENANT_ROLE_INVALID",
            """{"userId":"${c.adminId}"}""" to "INVALID_USERNAME"))
            assertThat(code(c.admin.post(employees(c), body), c.admin)).describedAs(body).isEqualTo(expected)
        assertThat(c.admin.patch("${employees(c)}/${uid(created)}", """{"employeeCode":"E1","expectedVersion":0}""").response.status).describedAs("there is no profile to patch").isIn(404, 405)
    }

    @Test
    fun `a rejected create leaves NO account, membership or position behind`() {
        val c = company(); val other = company(); val t = type(c, "unit"); val u = unit(c, t, "U"); val tOther = type(other, "unit"); val uOther = unit(other, tOther, "UO")
        val users = usersCount()
        val foreign = c.admin.post(employees(c), """{"username":"${uname("c")}","displayName":"C","organizationMemberships":[{"organizationUnitId":"${id(uOther)}"}]}"""); assertThat(foreign.response.status).isEqualTo(404); assertThat(code(foreign, c.admin)).isEqualTo("ORG_UNIT_NOT_FOUND")
        val pos = c.admin.body(c.admin.post(positions(c), """{"code":"DEV","name":"Dev"}""")); c.admin.post("${positions(c)}/${id(pos)}/disable", """{"expectedVersion":${ver(pos)}}""")
        val disabled = c.admin.post(employees(c), """{"username":"${uname("d")}","displayName":"D","organizationMemberships":[{"organizationUnitId":"${id(u)}","positions":[{"positionId":"${id(pos)}"}]}]}""")
        assertThat(disabled.response.status).isEqualTo(409); assertThat(code(disabled, c.admin)).isEqualTo("POSITION_DISABLED")
        val dupUnit = c.admin.post(employees(c), """{"username":"${uname("e")}","displayName":"E","organizationMemberships":[{"organizationUnitId":"${id(u)}"},{"organizationUnitId":"${id(u)}"}]}""")
        assertThat(dupUnit.response.status).isEqualTo(400)
        val live = c.admin.body(c.admin.post(positions(c), """{"code":"OPS","name":"Ops"}"""))
        // a position is held WITHIN a membership: it is NESTED in it (there is no free unit-as-scope input), once per membership, at most one primary
        for (body in listOf(
            """{"username":"${uname("f")}","displayName":"F","organizationMemberships":[{"organizationUnitId":"${id(u)}","positions":[{"positionId":"${id(live)}"},{"positionId":"${id(live)}"}]}]}""",
            """{"username":"${uname("g")}","displayName":"G","organizationMemberships":[{"organizationUnitId":"${id(u)}","positions":[{}]}]}""",
            """{"username":"${uname("i")}","displayName":"I","organizationMemberships":[{"organizationUnitId":"${id(u)}","positions":[{"positionId":"${id(live)}","primary":true},{"positionId":"${id(pos)}","primary":true}]}]}"""))
            assertThat(c.admin.post(employees(c), body).response.status).describedAs(body).isEqualTo(400)
        assertThat(code(c.admin.post(employees(c), """{"username":"${uname("j")}","displayName":"J","organizationMemberships":[{"organizationUnitId":"${id(u)}","positions":[{"positionId":"${id(live)}","gradeId":"${UUID.randomUUID()}"}]}]}"""), c.admin)).isEqualTo("GRADE_NOT_FOUND")
        assertThat(usersCount()).describedAs("no account was created by any rejected request").isEqualTo(users)
        assertThat(c.admin.body(c.admin.get("${units(c)}/${id(u)}")).get("activeMemberCount").asInt()).isZero()
        // the happy path in one request: account + membership + position scoped to it
        val ok = c.admin.post(employees(c), """{"username":"${uname("h")}","displayName":"H","organizationMemberships":[{"organizationUnitId":"${id(u)}","relationType":"head","positions":[{"positionId":"${id(live)}"}]}]}""")
        assertThat(ok.response.status).isEqualTo(201); val e = c.admin.body(ok).get("employee")
        val pm = e.get("organizationMemberships").single(); val pp = e.get("positions").single()
        assertThat(pm.get("relationType").asString()).isEqualTo("HEAD"); assertThat(pp.get("membershipId").asString()).isEqualTo(pm.get("id").asString()); assertThat(pp.get("organizationUnitId").asString()).isEqualTo(id(u).toString()); assertThat(pp.get("primary").asBoolean()).isTrue()
        assertThat(usersCount()).isEqualTo(users + 1)
    }

    // ------------------------------------------------------------------------------------------------ K / L multi-org
    @Test
    fun `K an employee belongs to several units with business relation types - none of which grants any permission`() {
        val c = company(); val t = type(c, "unit"); val sales = unit(c, t, "SALES"); val hr = unit(c, t, "HR"); val ops = unit(c, t, "OPS")
        val created = newEmployee(c); val u = uid(created)
        val m1 = c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(sales)}","relationType":"MANAGER"}""")
        assertThat(m1.response.status).isEqualTo(201); val first = c.admin.body(m1)
        assertThat(first.get("relationType").asString()).isEqualTo("MANAGER"); assertThat(first.get("primary").asBoolean()).describedAs("the first membership is the primary one").isTrue(); assertThat(first.get("active").asBoolean()).isTrue()
        assertThat(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(hr)}"}""").response.status).isEqualTo(201)
        assertThat(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(ops)}","relationType":"HEAD"}""").response.status).isEqualTo(201)
        val all = memberships(c, u); assertThat(all).hasSize(3); assertThat(all.map { it.get("relationType").asString() }).containsExactlyInAnyOrder("MANAGER", "MEMBER", "HEAD")
        val e = employee(c, u); assertThat(e.get("organizationMemberships").size()).isEqualTo(3); assertThat(e.get("primaryOrganizationUnitId").asString()).isEqualTo(id(sales).toString())
        // relation types are a vocabulary, validated as a token, never an authority
        assertThat(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(unit(c, t, "X1"))}","relationType":"bad type!"}""").response.status).isEqualTo(400)
        // a duplicate ACTIVE membership of the same unit is refused
        val dup = c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(sales)}"}"""); assertThat(dup.response.status).isEqualTo(409); assertThat(code(dup, c.admin)).isEqualTo("ORG_MEMBERSHIP_EXISTS")
        // the employee is found by ANY of its units, as one row
        val bySales = page(c, "organizationUnitId=${id(sales)}"); val byOps = page(c, "organizationUnitId=${id(ops)}")
        assertThat(bySales.get("total").asLong()).isEqualTo(1); assertThat(byOps.get("total").asLong()).isEqualTo(1)
        // being MANAGER / HEAD of units changes nothing about what the person may do: it is a plain tenant member without company capabilities
        val s = activateAndLogin(created.get("employee").get("username").asString(), created.get("activation").get("token").asString())
        assertThat(s.body(s.get("/api/v1/auth/me")).get("permissions").toList()).isEmpty()
        for (path in listOf(units(c), employees(c), types(c), positions(c))) assertThat(s.get(path).response.status).describedAs("MANAGER / HEAD GET $path").isEqualTo(403)
        assertThat(s.post("${units(c)}/${id(sales)}/archive", """{"expectedVersion":0}""").response.status).isEqualTo(403)
        assertThat(s.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(sales)}"}""").response.status).isEqualTo(403)
    }

    @Test
    fun `L exactly one active primary membership - setting another clears the previous one atomically, ending the primary promotes the oldest`() {
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A"); val b = unit(c, t, "B"); val d = unit(c, t, "D")
        val created = newEmployee(c); val u = uid(created)
        val ma = member(c, u, a); val mb = member(c, u, b); val md = member(c, u, d, ""","primary":true""")
        assertThat(md.get("primary").asBoolean()).isTrue(); assertThat(primaries(memberships(c, u)).map { it.get("organizationUnitId").asString() }).containsExactly(id(d).toString())
        assertThat(employee(c, u).get("primaryOrganizationUnitId").asString()).describedAs("the employee only PROJECTS the primary membership").isEqualTo(id(d).toString())
        // PATCH primary=true on another membership moves it; false is refused
        val cur = memberships(c, u).single { it.get("organizationUnitId").asString() == id(b).toString() }
        val sw = c.admin.patch("${employees(c)}/$u/organization-memberships/${id(mb)}", """{"primary":true,"expectedVersion":${ver(cur)}}"""); assertThat(sw.response.status).isEqualTo(200)
        assertThat(primaries(memberships(c, u))).hasSize(1); assertThat(primaries(memberships(c, u)).single().get("organizationUnitId").asString()).isEqualTo(id(b).toString())
        assertThat(c.admin.patch("${employees(c)}/$u/organization-memberships/${id(mb)}", """{"primary":false,"expectedVersion":${ver(c.admin.body(sw))}}""").response.status).isEqualTo(400)
        // change the relation type with the version guard
        val rel = c.admin.patch("${employees(c)}/$u/organization-memberships/${id(ma)}", """{"relationType":"HEAD","expectedVersion":${ver(memberships(c, u).single { it.get("id").asString() == id(ma).toString() })}}""")
        assertThat(rel.response.status).isEqualTo(200); assertThat(c.admin.body(rel).get("relationType").asString()).isEqualTo("HEAD")
        val stale = c.admin.patch("${employees(c)}/$u/organization-memberships/${id(ma)}", """{"relationType":"MEMBER","expectedVersion":0}"""); assertThat(stale.response.status).isEqualTo(409); assertThat(code(stale, c.admin)).isEqualTo("VERSION_CONFLICT")
        // ending the primary one: the membership stays as history (inactive), the OLDEST remaining active one becomes primary
        val primaryNow = primaries(memberships(c, u)).single()
        val end = c.admin.delete("${employees(c)}/$u/organization-memberships/${id(primaryNow)}?expectedVersion=${ver(primaryNow)}"); assertThat(end.response.status).isEqualTo(200)
        assertThat(c.admin.body(end).get("active").asBoolean()).isFalse(); assertThat(c.admin.body(end).get("primary").asBoolean()).isFalse()
        assertThat(primaries(memberships(c, u)).single().get("organizationUnitId").asString()).describedAs("the oldest remaining is promoted").isEqualTo(id(a).toString())
        assertThat(memberships(c, u, true)).hasSize(3); assertThat(memberships(c, u)).hasSize(2)
        assertThat(code(c.admin.delete("${employees(c)}/$u/organization-memberships/${id(primaryNow)}?expectedVersion=${ver(c.admin.body(end))}"), c.admin)).describedAs("already ended").isEqualTo("ORG_MEMBERSHIP_NOT_FOUND")
        assertThat(c.admin.delete("${employees(c)}/$u/organization-memberships/${UUID.randomUUID()}?expectedVersion=0").response.status).isEqualTo(404)
        assertThat(c.admin.delete("${employees(c)}/$u/organization-memberships/${id(ma)}").response.status).describedAs("expectedVersion required").isEqualTo(400)
        for (action in listOf("EMPLOYEE_ORG_ASSIGNED", "EMPLOYEE_ORG_UPDATED", "EMPLOYEE_ORG_REMOVED")) assertThat(auditCount(action, u)).describedAs(action).isGreaterThanOrEqualTo(1L)
        // an unknown unit is a 404
        assertThat(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${UUID.randomUUID()}"}""").response.status).isEqualTo(404)
    }

    // ------------------------------------------------------------------------------------------------ M position / grade (CF-2: held WITHIN a membership)
    @Test
    fun `M catalogs - positions and grades have a nullable description, a rank, versions and no permission`() {
        val c = company()
        val dev = c.admin.body(c.admin.post(positions(c), """{"code":"DEV","name":"Developer","description":"builds"}""")); c.admin.body(c.admin.post(positions(c), """{"code":"LEAD","name":"Team lead"}"""))
        val g1 = c.admin.body(c.admin.post(grades(c), """{"code":"G1","name":"Junior","rank":1,"description":"entry"}""")); val g2 = c.admin.body(c.admin.post(grades(c), """{"code":"G2","name":"Senior","rank":2}""")); c.admin.post(grades(c), """{"code":"GX","name":"Unranked"}""")
        assertThat(dev.get("active").asBoolean()).isTrue(); assertThat(ver(dev)).isZero(); assertThat(dev.get("description").asString()).isEqualTo("builds"); assertThat(g1.get("description").asString()).isEqualTo("entry"); assertThat(g2.get("description").isNull).isTrue()
        assertThat(c.admin.body(c.admin.get(grades(c))).toList().map { it.get("code").asString() }).describedAs("by rank, unranked last").containsExactly("G1", "G2", "GX")
        assertThat(code(c.admin.post(positions(c), """{"code":"dev","name":"again"}"""), c.admin)).isEqualTo("POSITION_CODE_TAKEN"); assertThat(code(c.admin.post(grades(c), """{"code":"g1","name":"again"}"""), c.admin)).isEqualTo("GRADE_CODE_TAKEN")
        assertThat(c.admin.patch("${positions(c)}/${id(dev)}", """{"name":"Software developer","expectedVersion":0}""").response.status).isEqualTo(200)
        assertThat(code(c.admin.patch("${positions(c)}/${id(dev)}", """{"name":"stale","expectedVersion":0}"""), c.admin)).isEqualTo("VERSION_CONFLICT")
        assertThat(c.admin.patch("${grades(c)}/${id(g1)}", """{"clearRank":true,"expectedVersion":0}""").response.status).isEqualTo(200)
        val off = c.admin.body(c.admin.post("${grades(c)}/${id(g2)}/disable", """{"expectedVersion":${ver(g2)}}""")); assertThat(off.get("active").asBoolean()).isFalse()
        assertThat(c.admin.post("${grades(c)}/${id(g2)}/enable", """{"expectedVersion":${ver(off)}}""").response.status).isEqualTo(200)
        for (action in listOf("POSITION_CREATED", "POSITION_UPDATED")) assertThat(auditCount(action, id(dev))).describedAs(action).isEqualTo(1L)
        assertThat(auditCount("GRADE_CREATED", id(g1))).isEqualTo(1L); assertThat(auditCount("GRADE_DISABLED", id(g2))).isEqualTo(1L); assertThat(auditCount("GRADE_ENABLED", id(g2))).isEqualTo(1L)
    }

    @Test
    fun `M2 a position is held WITHIN an active membership - scope is mandatory, one per membership and position, the grade is an attribute, a membership with positions cannot end`() {
        val c = company(); val t = type(c, "unit"); val sales = unit(c, t, "SALES"); val ops = unit(c, t, "OPS"); val outsider = unit(c, t, "OUT")
        val dev = c.admin.body(c.admin.post(positions(c), """{"code":"DEV","name":"Developer"}""")); val lead = c.admin.body(c.admin.post(positions(c), """{"code":"LEAD","name":"Lead"}"""))
        val g1 = c.admin.body(c.admin.post(grades(c), """{"code":"G1","name":"Junior","rank":1}""")); val g2 = c.admin.body(c.admin.post(grades(c), """{"code":"G2","name":"Senior","rank":2}"""))
        val created = newEmployee(c); val e = uid(created); val mSales = member(c, e, sales); val mOps = member(c, e, ops)
        fun hold(body: String) = c.admin.post("${employees(c)}/$e/positions", body)
        // CF-2: no tenant-global position - a membership is required, and it must be THIS employee's, in THIS tenant, and active
        assertThat(hold("""{"positionId":"${id(dev)}"}""").response.status).describedAs("membershipId missing").isEqualTo(400)
        assertThat(hold("""{"positionId":"${id(dev)}","organizationUnitId":"${id(sales)}"}""").response.status).describedAs("a unit is not a scope: the membership is").isEqualTo(400)
        assertThat(code(hold("""{"membershipId":"${UUID.randomUUID()}","positionId":"${id(dev)}"}"""), c.admin)).isEqualTo("ORG_MEMBERSHIP_NOT_FOUND")
        val other = uid(newEmployee(c)); val mOther = member(c, other, outsider)
        assertThat(code(hold("""{"membershipId":"${id(mOther)}","positionId":"${id(dev)}"}"""), c.admin)).describedAs("the membership of ANOTHER employee").isEqualTo("ORG_MEMBERSHIP_NOT_FOUND")
        // held in sales with a grade
        val a1 = hold("""{"membershipId":"${id(mSales)}","positionId":"${id(dev)}","gradeId":"${id(g1)}"}"""); assertThat(a1.response.status).isEqualTo(201)
        val first = c.admin.body(a1); assertThat(first.get("primary").asBoolean()).describedAs("the first assignment is primary").isTrue()
        assertThat(first.get("membershipId").asString()).isEqualTo(id(mSales).toString()); assertThat(first.get("organizationUnitId").asString()).describedAs("derived from the membership").isEqualTo(id(sales).toString()); assertThat(first.get("gradeId").asString()).isEqualTo(id(g1).toString())
        // uniqueness = (membership, position): the grade is an attribute, NOT part of the identity
        val dup = hold("""{"membershipId":"${id(mSales)}","positionId":"${id(dev)}","gradeId":"${id(g2)}"}"""); assertThat(dup.response.status).isEqualTo(409); assertThat(code(dup, c.admin)).isEqualTo("POSITION_ASSIGNMENT_EXISTS")
        assertThat(code(hold("""{"membershipId":"${id(mSales)}","positionId":"${id(dev)}"}"""), c.admin)).isEqualTo("POSITION_ASSIGNMENT_EXISTS")
        val a2 = hold("""{"membershipId":"${id(mOps)}","positionId":"${id(dev)}"}"""); assertThat(a2.response.status).describedAs("the same position in ANOTHER membership").isEqualTo(201)
        val a3 = c.admin.body(hold("""{"membershipId":"${id(mSales)}","positionId":"${id(lead)}","primary":true}"""))
        assertThat(primaries(heldPositions(c, e)).map { it.get("id").asString() }).containsExactly(id(a3).toString())
        // change the grade (set / clear): same assignment, same identity, version guard
        val regraded = c.admin.patch("${employees(c)}/$e/positions/${id(first)}", """{"gradeId":"${id(g2)}","expectedVersion":${ver(heldPositions(c, e).single { it.get("id").asString() == id(first).toString() })}}""")
        assertThat(regraded.response.status).isEqualTo(200); assertThat(c.admin.body(regraded).get("gradeId").asString()).isEqualTo(id(g2).toString()); assertThat(c.admin.body(regraded).get("id").asString()).isEqualTo(id(first).toString())
        assertThat(code(c.admin.patch("${employees(c)}/$e/positions/${id(first)}", """{"clearGrade":true,"expectedVersion":0}"""), c.admin)).isEqualTo("VERSION_CONFLICT")
        val cleared = c.admin.patch("${employees(c)}/$e/positions/${id(first)}", """{"clearGrade":true,"expectedVersion":${ver(c.admin.body(regraded))}}"""); assertThat(c.admin.body(cleared).get("gradeId").isNull).isTrue()
        assertThat(c.admin.patch("${employees(c)}/$e/positions/${id(first)}", """{"gradeId":"${id(g1)}","clearGrade":true,"expectedVersion":${ver(c.admin.body(cleared))}}""").response.status).isEqualTo(400)
        assertThat(code(c.admin.patch("${employees(c)}/$e/positions/${id(first)}", """{"gradeId":"${UUID.randomUUID()}","expectedVersion":${ver(c.admin.body(cleared))}}"""), c.admin)).isEqualTo("GRADE_NOT_FOUND")
        // directory filters by position and grade
        assertThat(page(c, "positionId=${id(dev)}").get("total").asLong()).isEqualTo(1); assertThat(page(c, "gradeId=${id(g2)}").get("total").asLong()).isZero()
        // LIFECYCLE: a membership with ACTIVE positions cannot end (no silent cascade); the caller ends the positions first, each one audited
        fun endMembershipOfSales() = c.admin.delete("${employees(c)}/$e/organization-memberships/${id(mSales)}?expectedVersion=${ver(memberships(c, e).single { it.get("id").asString() == id(mSales).toString() })}")
        val blocked = endMembershipOfSales(); assertThat(blocked.response.status).isEqualTo(409); assertThat(code(blocked, c.admin)).isEqualTo("EMPLOYEE_ORG_HAS_POSITIONS"); assertThat(c.admin.body(blocked).get("details").get("activePositionCount").asInt()).isEqualTo(2)
        assertThat(memberships(c, e)).describedAs("nothing changed").hasSize(2); assertThat(heldPositions(c, e)).hasSize(3)
        fun endPosition(a: JsonNode) = c.admin.delete("${employees(c)}/$e/positions/${id(a)}?expectedVersion=${ver(heldPositions(c, e).single { it.get("id").asString() == id(a).toString() })}")
        assertThat(endPosition(a3).response.status).isEqualTo(200)
        assertThat(primaries(heldPositions(c, e)).map { it.get("id").asString() }).describedAs("the primary position is re-elected (oldest remaining)").containsExactly(id(first).toString())
        assertThat(code(endMembershipOfSales(), c.admin)).describedAs("one position still held").isEqualTo("EMPLOYEE_ORG_HAS_POSITIONS")
        assertThat(endPosition(first).response.status).isEqualTo(200)
        assertThat(endMembershipOfSales().response.status).describedAs("no active position left: the membership ends").isEqualTo(200)
        val left = heldPositions(c, e); assertThat(left).hasSize(1); assertThat(left.single().get("membershipId").asString()).isEqualTo(id(mOps).toString()); assertThat(left.single().get("primary").asBoolean()).describedAs("the remaining one is promoted").isTrue()
        assertThat(heldPositions(c, e, true)).hasSize(3); assertThat(auditCount("EMPLOYEE_POSITION_REMOVED", e)).isEqualTo(2L); assertThat(auditCount("EMPLOYEE_ORG_REMOVED", e)).isEqualTo(1L)
        assertThat(code(hold("""{"membershipId":"${id(mSales)}","positionId":"${id(lead)}"}"""), c.admin)).describedAs("an ended membership holds nothing new").isEqualTo("ORG_MEMBERSHIP_INACTIVE")
        // an unknown position / grade, a disabled grade
        assertThat(code(hold("""{"membershipId":"${id(mOps)}","positionId":"${UUID.randomUUID()}"}"""), c.admin)).isEqualTo("POSITION_NOT_FOUND")
        assertThat(code(hold("""{"membershipId":"${id(mOps)}","positionId":"${id(lead)}","gradeId":"${UUID.randomUUID()}"}"""), c.admin)).isEqualTo("GRADE_NOT_FOUND")
        c.admin.post("${grades(c)}/${id(g2)}/disable", """{"expectedVersion":${ver(g2)}}"""); assertThat(code(hold("""{"membershipId":"${id(mOps)}","positionId":"${id(lead)}","gradeId":"${id(g2)}"}"""), c.admin)).isEqualTo("GRADE_DISABLED")
        // end one assignment explicitly; its primary flag moves on
        val last = heldPositions(c, e).single(); val end = c.admin.delete("${employees(c)}/$e/positions/${id(last)}?expectedVersion=${ver(last)}")
        assertThat(end.response.status).isEqualTo(200); assertThat(c.admin.body(end).get("active").asBoolean()).isFalse(); assertThat(heldPositions(c, e)).isEmpty()
        assertThat(code(c.admin.delete("${employees(c)}/$e/positions/${UUID.randomUUID()}?expectedVersion=0"), c.admin)).isEqualTo("POSITION_ASSIGNMENT_NOT_FOUND")
        assertThat(auditCount("EMPLOYEE_POSITION_ASSIGNED", e)).isEqualTo(3L); assertThat(auditCount("EMPLOYEE_POSITION_UPDATED", e)).isGreaterThanOrEqualTo(2L)
        // a holder of a position / grade has no capability because of it
        val s = activateAndLogin(created.get("employee").get("username").asString(), created.get("activation").get("token").asString())
        assertThat(s.get(employees(c)).response.status).isEqualTo(403); assertThat(s.get(positions(c)).response.status).isEqualTo(403)
    }

    // ------------------------------------------------------------------------------------------------ search, filter, page
    @Test
    fun `J search, filters, sort and pagination - deterministic, bounded, literal text, one row per employee`() {
        val c = company(); val t = type(c, "unit"); val root = unit(c, t, "R"); val child = unit(c, t, "C", root); val other = unit(c, t, "O")
        val made = listOf("ana", "bao", "chi", "dung", "em").mapIndexed { i, n ->
            val unitFor = if (i < 2) root else if (i < 4) child else other
            newEmployee(c, "$n-${UUID.randomUUID().toString().take(5)}", ""","organizationMemberships":[{"organizationUnitId":"${id(unitFor)}"}]""")
        }
        assertThat(c.admin.post("${employees(c)}/${uid(made[0])}/organization-memberships", """{"organizationUnitId":"${id(child)}"}""").response.status).describedAs("a second unit inside the same subtree").isEqualTo(201)
        val all = page(c, "size=100"); assertThat(all.get("total").asLong()).describedAs("5 employees + the Tenant Admin").isEqualTo(6); assertThat(all.get("items").size()).isEqualTo(6)
        val p = (0..3).map { page(c, "size=2&page=$it&sort=username") }
        assertThat(p.flatMap { names(it) }).isSorted(); assertThat(p.flatMap { names(it) }).hasSize(6).doesNotHaveDuplicates(); assertThat(p[0].get("total").asLong()).isEqualTo(6); assertThat(p[3].get("items").size()).isZero()
        assertThat(names(page(c, "sort=username&dir=desc&size=1")).single()).isEqualTo(names(page(c, "sort=username&size=100")).last())
        assertThat(page(c, "sort=name&size=100").get("items").toList().map { it.get("displayName").asString().lowercase() }).isSorted()
        assertThat(names(page(c, "q=BAO"))).hasSize(1).allMatch { it.startsWith("bao") }
        for (q in listOf("%%", "a_", "\\\\")) assertThat(page(c, "q=" + java.net.URLEncoder.encode(q, "UTF-8")).get("total").asLong()).describedAs("'$q' is text, not a wildcard").isZero()
        assertThat(c.admin.get("${employees(c)}?q=a").response.status).isEqualTo(400)
        assertThat(page(c, "organizationUnitId=${id(root)}&includeDescendants=false").get("total").asLong()).isEqualTo(2)
        assertThat(page(c, "organizationUnitId=${id(root)}").get("total").asLong()).describedAs("with descendants: ana (root AND child) counts ONCE").isEqualTo(4)
        assertThat(page(c, "organizationUnitId=${id(other)}").get("total").asLong()).isEqualTo(1)
        assertThat(c.admin.get("${employees(c)}?organizationUnitId=${UUID.randomUUID()}").response.status).describedAs("unknown unit").isEqualTo(404)
        assertThat(page(c, "userId=${uid(made[2])}").get("total").asLong()).isEqualTo(1)
        assertThat(c.admin.post("${employees(c)}/${uid(made[0])}/disable").response.status).isEqualTo(200)
        assertThat(page(c, "active=true").get("total").asLong()).isEqualTo(5); assertThat(page(c, "active=false").get("total").asLong()).isEqualTo(1)
        for (q in listOf("size=0", "size=101", "page=-1", "sort=password", "sort=code", "sort=created", "dir=sideways")) assertThat(c.admin.get("${employees(c)}?$q").response.status).describedAs(q).isEqualTo(400)
        assertThat(c.admin.get("${employees(c)}?positionId=${UUID.randomUUID()}").response.status).isEqualTo(404)
    }

    // ------------------------------------------------------------------------------------------------ enable / disable = tenant membership lifecycle (CF-1)
    @Test
    fun `enable and disable ARE the tenant membership lifecycle - no version, idempotent, nothing deleted, self and last admin protected`() {
        val sys = sysAdmin(); val c = company(sys); val created = newEmployee(c); val u = uid(created)
        val es = activateAndLogin(created.get("employee").get("username").asString(), created.get("activation").get("token").asString()); assertThat(es.body(es.get("/api/v1/auth/me")).get("tenants").size()).isEqualTo(1)
        val unitT = type(c, "unit"); val un = unit(c, unitT, "U"); member(c, u, un)
        val off = c.admin.post("${employees(c)}/$u/disable"); assertThat(off.response.status).isEqualTo(200)
        val d = c.admin.body(off); assertThat(d.get("active").asBoolean()).isFalse(); assertThat(d.get("accountEnabled").asBoolean()).isTrue(); assertThat(d.get("organizationMemberships").size()).describedAs("history stays").isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT active FROM tenant_members WHERE tenant_id = ? AND user_id = ?", Boolean::class.java, c.id, u)).describedAs("the canonical tenant_members.active").isFalse()
        assertThat(es.body(es.get("/api/v1/auth/me")).get("tenants").size()).describedAs("the person lost THIS company").isZero()
        assertThat(c.admin.post("${employees(c)}/$u/disable").response.status).describedAs("idempotent").isEqualTo(200); assertThat(auditCount("EMPLOYEE_DISABLED", u)).isEqualTo(1L)
        assertThat(code(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(unit(c, unitT, "U2"))}"}"""), c.admin)).describedAs("a disabled employee takes no new membership").isEqualTo("EMPLOYEE_INACTIVE")
        assertThat(page(c, "active=false").get("total").asLong()).isEqualTo(1); assertThat(employee(c, u).get("active").asBoolean()).isFalse()
        val on = c.admin.post("${employees(c)}/$u/enable"); assertThat(on.response.status).isEqualTo(200); assertThat(c.admin.body(on).get("active").asBoolean()).isTrue(); assertThat(c.admin.body(on).get("tenantRole").asString()).isEqualTo("MEMBER")
        // oneself is protected; the last Tenant Admin is protected by the tenant service; a platform-disabled account is not re-enabled
        val self = c.admin.post("${employees(c)}/${c.adminId}/disable"); assertThat(self.response.status).isEqualTo(403); assertThat(code(self, c.admin)).isEqualTo("SELF_GRANT_FORBIDDEN")
        val x = newEmployee(c); c.admin.post("${employees(c)}/${uid(x)}/disable"); jdbc.update("UPDATE users SET enabled = false WHERE id = ?", uid(x))
        val blocked = c.admin.post("${employees(c)}/${uid(x)}/enable"); assertThat(blocked.response.status).isEqualTo(422); assertThat(code(blocked, c.admin)).isEqualTo("USER_DISABLED")
        for (a in listOf("EMPLOYEE_CREATED", "EMPLOYEE_DISABLED", "EMPLOYEE_ENABLED")) assertThat(auditCount(a, u)).describedAs(a).isGreaterThanOrEqualTo(1L)
        assertThat(auditCount("EMPLOYEE_ORG_ASSIGNED", u)).isEqualTo(1L)
        val token = created.get("activation").get("token").asString()
        val trail = jdbc.queryForList("SELECT coalesce(old_value::text,'') || coalesce(new_value::text,'') AS t FROM audit_events WHERE actor_id = ?", c.adminId).joinToString { it["t"] as String }
        assertThat(trail).doesNotContain(token).doesNotContain(PASSWORD); assertThat(trail.lowercase()).doesNotContain("password", "argon", "hash")
    }
}
