package com.systemwebstudio.organization

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * CONFORMANCE KIT for the C3 persistence seams (OrganizationRepositories.kt). C3 extends this class in ITS test sources, supplies its implementation and the fixture hooks, and
 * these tests must pass unchanged: they pin the rules every implementation owes to the C1 application services (tenant first, versioned writes, store-enforced uniqueness with
 * SIBLING-scoped unit codes, atomic primary, atomic cycle refusal, subtree / depth answers, directory search semantics, position assignments scoped to a membership).
 * The in-memory double of the C1 tests runs the very same kit ([InMemoryOrganizationConformanceTest]).
 *
 * Hooks: [newTenant] / [newMember] / [setMemberActive] create the tenant and the identity rows the store may reference (a real database needs `tenants`, `users` and
 * `tenant_members` rows for its composite FKs; the in-memory double just keeps a list). [identities] reads them back (C1's `TenantIdentityDirectory`).
 * Concurrency and lock behaviour (advisory lock, row locks) are C3's own PostgreSQL tests; their contract is in docs/parallel/c1/organization-employee-contract.md §8.
 */
abstract class OrganizationRepositoryContractKit {
    protected abstract val types: OrganizationUnitTypeRepository
    protected abstract val units: OrganizationUnitRepository
    protected abstract val directory: EmployeeDirectoryRepository
    protected abstract val memberships: EmployeeOrganizationMembershipRepository
    protected abstract val positions: PositionRepository
    protected abstract val grades: GradeRepository
    protected abstract val employeePositions: EmployeePositionRepository
    protected abstract val identities: TenantIdentityDirectory
    protected abstract fun newTenant(): UUID
    protected abstract fun newMember(tenantId: UUID, username: String, displayName: String): UUID
    protected abstract fun setMemberActive(tenantId: UUID, userId: UUID, active: Boolean)

    private val json = JsonMapper.builder().build()
    private val now get() = Instant.now()
    private fun tag() = UUID.randomUUID().toString().take(6)

    private fun type(t: UUID, code: String = "c" + tag(), rules: OrgUnitTypeRules = OrgUnitTypeRules()) =
        types.insert(OrganizationUnitTypeDto(UUID.randomUUID(), t, "Type $code", code, null, true, rules, 0, now, now))
    /** unit codes reach the store CANONICAL (trimmed, upper-case); the kit passes them that way */
    private fun unit(t: UUID, type: OrganizationUnitTypeDto, code: String, parent: UUID? = null, sort: Int = 0) =
        units.insert(OrganizationUnitDto(UUID.randomUUID(), t, type.id, parent, "Unit $code", code.uppercase(), sort, json.createObjectNode(), true, 0, now, now))
    private fun member(t: UUID, u: UUID, unit: UUID, relation: String = "MEMBER") = memberships.insert(OrganizationMembershipDto(UUID.randomUUID(), t, u, unit, relation, false, true, 0, now, now))
    private fun position(t: UUID, code: String) = positions.insert(PositionDto(UUID.randomUUID(), t, "Pos $code", code, null, true, 0, now, now))
    private fun grade(t: UUID, code: String) = grades.insert(GradeDto(UUID.randomUUID(), t, "Grade $code", code, 1, null, true, 0, now, now))
    private fun hold(m: OrganizationMembershipDto, p: PositionDto, g: GradeDto? = null) =
        employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), m.tenantId, m.userId, m.id, m.organizationUnitId, p.id, g?.id, false, true, 0, now, now))
    private fun user(t: UUID, name: String = "u") = newMember(t, "$name-${tag()}", "Person ${name.uppercase()}")
    private fun search(t: UUID, text: String? = null, unitIds: Set<UUID>? = null, position: UUID? = null, grade: UUID? = null, active: Boolean? = null, sort: String = "name", asc: Boolean = true, page: Int = 0, size: Int = 50) =
        directory.search(t, EmployeeSearch(text, unitIds, position, grade, active, null, sort, asc, page, size), identities)

    @Test
    fun `1 tenant first - every find answers null for a record of another tenant, every list omits it, counters ignore it`() {
        val a = newTenant(); val b = newTenant(); val ta = type(a); val tb = type(b); val ua = unit(a, ta, "A1"); val ub = unit(b, tb, "B1")
        assertThat(types.find(a, tb.id)).isNull(); assertThat(types.findAll(a, listOf(ta.id, tb.id)).map { it.id }).containsExactly(ta.id); assertThat(types.list(a, true).map { it.id }).containsExactly(ta.id)
        assertThat(units.find(a, ub.id)).isNull(); assertThat(units.listAll(a, true).map { it.id }).containsExactly(ua.id)
        assertThat(units.subtree(a, ub.id)).isEmpty(); assertThat(units.depthOf(a, ub.id)).isNull(); assertThat(units.activeChildCount(a, ub.id)).isZero()
        assertThat(units.update(ub.copy(tenantId = a, name = "hijack"), 0)).describedAs("a write through the wrong tenant applies nothing").isNull()
        assertThat(units.move(a, ub.id, null, null, 0)).isNull(); assertThat(units.setActive(a, ub.id, false, 0)).isNull(); assertThat(units.find(b, ub.id)!!.version).isZero()
        val ma = user(a, "ma"); val mb = user(b, "mb"); member(b, mb, ub.id)
        assertThat(search(a).items).containsExactly(ma)
        assertThat(memberships.list(a, mb, true)).isEmpty(); assertThat(memberships.activeCountByUnit(a, ub.id)).isZero(); assertThat(memberships.activeCountByUnit(b, ub.id)).isEqualTo(1)
        val pb = position(b, "P1"); val mem = memberships.list(b, mb, true).single(); val asg = hold(mem, pb)
        assertThat(employeePositions.find(a, mb, asg.id)).isNull(); assertThat(employeePositions.list(a, mb, true)).isEmpty(); assertThat(positions.find(a, pb.id)).isNull()
    }

    @Test
    fun `2 versioned writes - a stale or missing version applies NOTHING and answers null, a current one applies once and bumps the version`() {
        val t = newTenant(); val ty = type(t); val u = unit(t, ty, "A")
        val ok = units.update(u.copy(name = "renamed"), 0)!!; assertThat(ok.version).isEqualTo(1); assertThat(ok.name).isEqualTo("renamed")
        assertThat(units.update(u.copy(name = "stale"), 0)).isNull(); assertThat(units.find(t, u.id)!!.name).isEqualTo("renamed")
        assertThat(types.update(ty.copy(name = "T2"), 0)!!.version).isEqualTo(1); assertThat(types.update(ty.copy(name = "T3"), 0)).isNull()
        assertThat(units.setActive(t, u.id, false, 0)).isNull(); assertThat(units.setActive(t, u.id, false, 1)!!.active).isFalse()
        val p = position(t, "P"); assertThat(positions.setActive(t, p.id, false, 5)).isNull(); assertThat(positions.setActive(t, p.id, false, 0)!!.active).isFalse()
        assertThat(positions.update(p.copy(name = "x"), 9)).isNull()
    }

    @Test
    fun `3 unit code is SIBLING scoped - unique among the non-archived units of one parent (roots are siblings), free across parents, archiving frees it`() {
        val t = newTenant(); val ty = type(t); val rootA = unit(t, ty, "ROOT-A"); val rootB = unit(t, ty, "ROOT-B")
        val salesA = unit(t, ty, "SALES", rootA.id); val salesB = unit(t, ty, "SALES", rootB.id)             // the SAME code under different parents: allowed
        assertThat(salesA.code).isEqualTo(salesB.code)
        assertThatThrownBy { unit(t, ty, "SALES", rootA.id) }.isInstanceOf(DuplicateOrganizationKey::class.java).extracting("key").isEqualTo("code")
        assertThatThrownBy { unit(t, ty, "ROOT-A") }.describedAs("two roots with one code").isInstanceOf(DuplicateOrganizationKey::class.java)
        unit(t, ty, "SALES")                                                                                    // a root named like a nested unit: fine
        val ops = unit(t, ty, "OPS", rootB.id)
        assertThatThrownBy { units.update(ops.copy(code = "SALES"), 0) }.describedAs("renaming onto a sibling's code").isInstanceOf(DuplicateOrganizationKey::class.java)
        assertThat(units.update(ops.copy(code = "OPS2"), 0)!!.code).isEqualTo("OPS2")
        units.setActive(t, salesA.id, false, 0)                                                                 // archiving frees the code among siblings ...
        val again = unit(t, ty, "SALES", rootA.id)
        assertThatThrownBy { units.setActive(t, salesA.id, true, 1) }.describedAs("... and restoring it while a sibling holds it is a duplicate").isInstanceOf(DuplicateOrganizationKey::class.java)
        assertThat(units.setActive(t, again.id, false, 0)!!.active).isFalse(); assertThat(units.setActive(t, salesA.id, true, 1)!!.active).isTrue()
        val other = newTenant(); unit(other, type(other), "ROOT-A")                                             // another tenant never collides
    }

    @Test
    fun `4 other uniqueness - type code per tenant, position and grade code per tenant case-insensitively, one active membership per user and unit`() {
        val a = newTenant(); val b = newTenant(); type(a, "khoi"); type(b, "khoi")
        assertThatThrownBy { type(a, "KHOI") }.isInstanceOf(DuplicateOrganizationKey::class.java).extracting("key").isEqualTo("code")
        position(a, "DEV"); assertThatThrownBy { position(a, "dev") }.isInstanceOf(DuplicateOrganizationKey::class.java); position(b, "DEV")
        grade(a, "G1"); assertThatThrownBy { grade(a, "g1") }.isInstanceOf(DuplicateOrganizationKey::class.java)
        val u = unit(a, type(a), "U"); val m = user(a)
        val first = member(a, m, u.id); assertThatThrownBy { member(a, m, u.id) }.isInstanceOf(DuplicateOrganizationKey::class.java).extracting("key").isEqualTo("membership")
        memberships.end(a, m, first.id, 0); assertThat(member(a, m, u.id).active).describedAs("an ended membership does not block a new one").isTrue()
    }

    @Test
    fun `5 hierarchy - subtree with relative depths, depth of a unit, active children, and an atomic move that takes the whole subtree with it`() {
        val t = newTenant(); val ty = type(t); val r = unit(t, ty, "R"); val a = unit(t, ty, "A", r.id); val b = unit(t, ty, "B", a.id); val c = unit(t, ty, "C", b.id); val other = unit(t, ty, "O")
        assertThat(units.depthOf(t, r.id)).isEqualTo(1); assertThat(units.depthOf(t, c.id)).isEqualTo(4)
        assertThat(units.subtree(t, a.id).associate { it.id to it.relativeDepth }).isEqualTo(mapOf(a.id to 0, b.id to 1, c.id to 2))
        assertThat(units.subtree(t, r.id)).hasSize(4); assertThat(units.activeChildCount(t, r.id)).isEqualTo(1)
        val moved = units.move(t, a.id, other.id, 7, 0)!!; assertThat(moved.parentId).isEqualTo(other.id); assertThat(moved.sortOrder).isEqualTo(7); assertThat(moved.version).isEqualTo(1)
        assertThat(units.depthOf(t, c.id)).describedAs("the descendants followed").isEqualTo(4); assertThat(units.subtree(t, other.id)).hasSize(4); assertThat(units.subtree(t, r.id)).hasSize(1)
        assertThat(units.find(t, b.id)!!.version).describedAs("descendants are not rewritten").isZero()
        assertThat(units.move(t, a.id, null, null, 0)).describedAs("stale").isNull(); assertThat(units.move(t, a.id, null, null, 1)!!.parentId).isNull(); assertThat(units.depthOf(t, c.id)).isEqualTo(3)
        units.setActive(t, c.id, false, 0); assertThat(units.activeChildCount(t, b.id)).describedAs("an archived child is not an active child").isZero(); assertThat(units.subtree(t, b.id).map { it.id }).contains(c.id)
        assertThat(units.find(t, c.id)!!.archivedAt).describedAs("archive sets the lifecycle timestamp").isNotNull(); assertThat(units.setActive(t, c.id, true, 1)!!.archivedAt).isNull()
        units.setActive(t, c.id, false, 2); assertThat(units.listAll(t, false).map { it.id }).doesNotContain(c.id); assertThat(units.listAll(t, true).map { it.id }).contains(c.id)
    }

    @Test
    fun `6 the store refuses a cycle itself and a sibling code clash on move - nothing is applied`() {
        val t = newTenant(); val ty = type(t); val r = unit(t, ty, "R"); val a = unit(t, ty, "A", r.id); val b = unit(t, ty, "B", a.id)
        assertThatThrownBy { units.move(t, r.id, b.id, null, 0) }.describedAs("below its own descendant").isInstanceOf(OrganizationCycle::class.java)
        assertThatThrownBy { units.move(t, r.id, r.id, null, 0) }.describedAs("below itself").isInstanceOf(OrganizationCycle::class.java)
        assertThat(units.find(t, r.id)!!.parentId).isNull(); assertThat(units.find(t, r.id)!!.version).isZero()
        unit(t, ty, "X", r.id); val xInA = unit(t, ty, "X", a.id)
        assertThatThrownBy { units.move(t, xInA.id, r.id, null, 0) }.describedAs("moving next to a sibling with the same code").isInstanceOf(DuplicateOrganizationKey::class.java).extracting("key").isEqualTo("code")
        assertThat(units.find(t, xInA.id)!!.parentId).isEqualTo(a.id)
    }

    @Test
    fun `7 exactly one active primary membership per user - setPrimary clears the previous one in the same operation, end clears the flag`() {
        val t = newTenant(); val ty = type(t); val a = unit(t, ty, "A"); val b = unit(t, ty, "B"); val c = unit(t, ty, "C"); val u = user(t)
        val ma = member(t, u, a.id); val mb = member(t, u, b.id); val mc = member(t, u, c.id)
        val pa = memberships.setPrimary(t, u, ma.id, 0)!!; assertThat(pa.primary).isTrue()
        val pb = memberships.setPrimary(t, u, mb.id, 0)!!; assertThat(pb.primary).isTrue()
        assertThat(memberships.list(t, u, false).filter { it.primary }.map { it.id }).containsExactly(mb.id)
        assertThat(memberships.setPrimary(t, u, mc.id, 99)).describedAs("stale version applies nothing").isNull(); assertThat(memberships.list(t, u, false).filter { it.primary }.map { it.id }).containsExactly(mb.id)
        val ended = memberships.end(t, u, mb.id, pb.version)!!; assertThat(ended.active).isFalse(); assertThat(ended.primary).isFalse()
        assertThat(memberships.list(t, u, false).filter { it.primary }).isEmpty(); assertThat(memberships.list(t, u, true)).hasSize(3); assertThat(memberships.activeCountByUnit(t, b.id)).isZero()
        val cur = memberships.find(t, u, ma.id)!!; assertThat(memberships.update(cur.copy(relationType = "HEAD"), cur.version)!!.relationType).isEqualTo("HEAD")
        assertThat(memberships.listForUsers(t, listOf(u), false)).hasSize(2)
    }

    @Test
    fun `8 position assignments are held WITHIN a membership - one active per membership and position, the grade is an attribute, one primary per user`() {
        val t = newTenant(); val ty = type(t); val sales = unit(t, ty, "SALES"); val ops = unit(t, ty, "OPS"); val u = user(t)
        val mSales = member(t, u, sales.id); val mOps = member(t, u, ops.id); val dev = position(t, "DEV"); val lead = position(t, "LEAD"); val g1 = grade(t, "G1"); val g2 = grade(t, "G2")
        val a1 = hold(mSales, dev, g1); assertThat(a1.membershipId).isEqualTo(mSales.id); assertThat(a1.organizationUnitId).isEqualTo(sales.id)
        assertThatThrownBy { hold(mSales, dev, g2) }.describedAs("the same position in the same membership, even with another grade").isInstanceOf(DuplicateOrganizationKey::class.java).extracting("key").isEqualTo("assignment")
        assertThatThrownBy { hold(mSales, dev) }.isInstanceOf(DuplicateOrganizationKey::class.java)
        val a2 = hold(mOps, dev, g1)                                                                            // the same position in ANOTHER membership: fine
        val a3 = hold(mSales, lead)
        val regraded = employeePositions.update(a1.copy(gradeId = g2.id), 0)!!; assertThat(regraded.gradeId).isEqualTo(g2.id); assertThat(regraded.version).isEqualTo(1)
        assertThat(employeePositions.update(a1.copy(gradeId = null), 0)).describedAs("stale").isNull(); assertThat(employeePositions.update(regraded.copy(gradeId = null), 1)!!.gradeId).isNull()
        employeePositions.setPrimary(t, u, a2.id, 0)!!; employeePositions.setPrimary(t, u, a3.id, 0)!!
        assertThat(employeePositions.list(t, u, false).filter { it.primary }.map { it.id }).containsExactly(a3.id)
        val ended = employeePositions.end(t, u, a3.id, employeePositions.find(t, u, a3.id)!!.version)!!; assertThat(ended.active).isFalse(); assertThat(ended.primary).isFalse()
        assertThat(employeePositions.list(t, u, true)).hasSize(3); assertThat(employeePositions.list(t, u, false)).hasSize(2); assertThat(employeePositions.listForUsers(t, listOf(u), false)).hasSize(2)
        assertThat(hold(mSales, lead).active).describedAs("an ended assignment does not block a new one").isTrue()
        assertThat(employeePositions.find(newTenant(), u, a1.id)).isNull()
    }

    @Test
    fun `9 directory search - tenant members only, text literally and case-insensitively, unit position grade filters, active flag, sort, deterministic paging, one row per employee`() {
        val t = newTenant(); val ty = type(t); val root = unit(t, ty, "R"); val child = unit(t, ty, "C", root.id)
        val users = listOf("ana", "bao", "chi", "dung").map { newMember(t, "$it-${tag()}", "Person ${it.uppercase()}") }
        val ma1 = member(t, users[0], root.id); member(t, users[0], child.id); member(t, users[1], child.id)               // ana belongs to TWO units of the subtree
        val dev = position(t, "DEV"); val g = grade(t, "G1"); hold(ma1, dev, g)
        assertThat(search(t).total).isEqualTo(4); assertThat(search(t, text = "PERSON BAO").items).containsExactly(users[1]); assertThat(search(t, text = "DUNG-").items).containsExactly(users[3])
        for (lit in listOf("%%", "a_", "\\")) assertThat(search(t, text = lit).total).describedAs("'$lit' is text").isZero()
        assertThat(search(t, unitIds = setOf(root.id, child.id)).items).containsExactlyInAnyOrder(users[0], users[1]); assertThat(search(t, unitIds = setOf(root.id, child.id)).total).isEqualTo(2)
        assertThat(search(t, unitIds = setOf(root.id)).items).containsExactly(users[0])
        assertThat(search(t, position = dev.id).items).containsExactly(users[0]); assertThat(search(t, grade = g.id).total).isEqualTo(1)
        setMemberActive(t, users[3], false); assertThat(search(t, active = false).items).containsExactly(users[3]); assertThat(search(t, active = true).total).isEqualTo(3); assertThat(search(t).total).describedAs("disabled members stay in the directory").isEqualTo(4)
        assertThat(search(t, sort = "name").items).containsExactly(*users.toTypedArray()); assertThat(search(t, sort = "name", asc = false, size = 1).items).containsExactly(users[3])
        assertThat(search(t, sort = "username").items).containsExactly(*users.toTypedArray())
        val pages = (0..2).flatMap { search(t, page = it, size = 2).items }; assertThat(pages).containsExactly(*users.toTypedArray())
        assertThat(search(t, page = 5).items).isEmpty(); assertThat(search(t, page = 5).total).describedAs("total is the filtered count, not the page").isEqualTo(4)
        assertThat(search(newTenant()).total).describedAs("another tenant sees none of them").isZero()
    }

    @Test
    fun `10 a rejected duplicate leaves the first write intact and adds nothing`() {
        val t = newTenant(); val ty = type(t); val u1 = unit(t, ty, "U1"); val m = user(t)
        member(t, m, u1.id)
        assertThatThrownBy { member(t, m, u1.id) }.isInstanceOf(DuplicateOrganizationKey::class.java)
        assertThat(memberships.list(t, m, true)).hasSize(1)
    }
}
