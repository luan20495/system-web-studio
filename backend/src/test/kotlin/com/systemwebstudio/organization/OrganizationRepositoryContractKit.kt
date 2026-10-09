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
 * The kit calls the repositories OUTSIDE any surrounding test transaction (a store that raises DuplicateOrganizationKey from a unique violation inside a transaction would poison it; run the
 * kit non-transactionally, or make the store use savepoints). Concurrency and lock behaviour (advisory lock, row locks, FOR SHARE / FOR UPDATE, the primary partial unique index) are C3's own PostgreSQL tests; their contract is in docs/parallel/c1/organization-employee-contract.md §8.
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
        unit(b, tb, "B2", ub.id)
        assertThat(units.subtree(a, ub.id)).isEmpty(); assertThat(units.depthOf(a, ub.id)).isNull(); assertThat(units.activeChildCount(a, ub.id)).describedAs("B's unit has a child, A sees none").isZero(); assertThat(units.activeChildCount(b, ub.id)).isEqualTo(1)
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
    fun `10 inside ONE tenant a record is scoped by its USER too - another user's membership or assignment is invisible and untouchable`() {
        val t = newTenant(); val ty = type(t); val u1 = unit(t, ty, "U1"); val alice = user(t, "alice"); val bob = user(t, "bob")
        val ma = member(t, alice, u1.id); val dev = position(t, "DEV"); val asg = hold(ma, dev)
        assertThat(memberships.find(t, bob, ma.id)).describedAs("another user's membership").isNull(); assertThat(memberships.list(t, bob, true)).isEmpty()
        assertThat(memberships.update(ma.copy(userId = bob, relationType = "HEAD"), 0)).isNull(); assertThat(memberships.setPrimary(t, bob, ma.id, 0)).isNull(); assertThat(memberships.end(t, bob, ma.id, 0)).isNull()
        assertThat(memberships.find(t, alice, ma.id)).describedAs("nothing was applied").extracting("id", "version", "relationType", "active", "primary").containsExactly(ma.id, 0L, "MEMBER", true, false)
        assertThat(employeePositions.find(t, bob, asg.id)).describedAs("another user's assignment").isNull(); assertThat(employeePositions.list(t, bob, true)).isEmpty()
        assertThat(employeePositions.setPrimary(t, bob, asg.id, 0)).isNull(); assertThat(employeePositions.end(t, bob, asg.id, 0)).isNull(); assertThat(employeePositions.update(asg.copy(userId = bob, gradeId = null), 0)).isNull()
        assertThat(employeePositions.find(t, alice, asg.id)).extracting("id", "version", "active", "primary").containsExactly(asg.id, 0L, true, false)
        // an assignment must be held within a membership of THE SAME user and unit: anything else is refused (a programming error, not a business answer)
        val mb = member(t, bob, u1.id)
        assertThatThrownBy { employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), t, alice, mb.id, u1.id, dev.id, null, false, true, 0, now, now)) }.describedAs("bob's membership for alice").isInstanceOf(RuntimeException::class.java)
        val u2 = unit(t, ty, "U2")
        assertThatThrownBy { employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), t, bob, mb.id, u2.id, dev.id, null, false, true, 0, now, now)) }.describedAs("a unit that is not the membership's").isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), t, bob, UUID.randomUUID(), u1.id, dev.id, null, false, true, 0, now, now)) }.describedAs("an unknown membership").isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `11 the store refuses atomically what a concurrent writer could otherwise slip past a count - typed exceptions, nothing applied`() {
        val t = newTenant(); val ty = type(t); val root = unit(t, ty, "ROOT"); val child = unit(t, ty, "CHILD", root.id); val u = user(t)
        // archive refuses while an ACTIVE child exists, then while an ACTIVE membership exists; counts are reported
        val inUse = assertThatThrownBy { units.setActive(t, root.id, false, 0) }.isInstanceOf(OrganizationUnitInUse::class.java); inUse.extracting("activeChildren").isEqualTo(1); inUse.extracting("activeMembers").isEqualTo(0)
        assertThat(units.find(t, root.id)!!.active).isTrue()
        units.setActive(t, child.id, false, 0)
        val m = member(t, u, root.id); assertThatThrownBy { units.setActive(t, root.id, false, 0) }.isInstanceOf(OrganizationUnitInUse::class.java).extracting("activeMembers").isEqualTo(1)
        val dev = position(t, "DEV"); val asg = hold(m, dev)
        // a membership with an ACTIVE position cannot end; once the position ended it can
        assertThatThrownBy { memberships.end(t, u, m.id, 0) }.isInstanceOf(MembershipHasPositions::class.java).extracting("activePositions").isEqualTo(1)
        assertThat(memberships.find(t, u, m.id)!!.active).isTrue()
        employeePositions.end(t, u, asg.id, 0)!!; val ended = memberships.end(t, u, m.id, 0)!!; assertThat(ended.active).isFalse()
        assertThat(memberships.end(t, u, m.id, ended.version)).describedAs("an already ended membership").isNull(); assertThat(memberships.setPrimary(t, u, m.id, ended.version)).describedAs("an ended membership cannot become primary").isNull()
        assertThat(employeePositions.end(t, u, asg.id, 1)).describedAs("an already ended assignment").isNull()
        units.setActive(t, root.id, false, 0)!!                                                                                       // now nothing is in use
        // nothing may appear under an ARCHIVED unit / ended membership
        assertThatThrownBy { unit(t, ty, "LATE", root.id) }.describedAs("a child under an archived parent").isInstanceOf(ReferencedRowInactive::class.java).extracting("kind").isEqualTo("unit")
        assertThatThrownBy { member(t, u, root.id) }.describedAs("a membership in an archived unit").isInstanceOf(ReferencedRowInactive::class.java).extracting("kind").isEqualTo("unit")
        val live = unit(t, ty, "LIVE"); assertThatThrownBy { units.move(t, live.id, root.id, null, 0) }.describedAs("a move under an archived destination").isInstanceOf(ReferencedRowInactive::class.java)
        assertThat(units.find(t, live.id)!!.parentId).isNull()
        assertThatThrownBy { hold(ended, dev) }.describedAs("a position in an ended membership").isInstanceOf(ReferencedRowInactive::class.java).extracting("kind").isEqualTo("membership")
        // restoring a child whose parent is archived
        val kid = unit(t, ty, "KID", live.id); units.setActive(t, kid.id, false, 0)!!; units.setActive(t, live.id, false, 0)!!
        assertThatThrownBy { units.setActive(t, kid.id, true, 1) }.describedAs("restore under an archived parent").isInstanceOf(ReferencedRowInactive::class.java)
    }

    @Test
    fun `12 sibling code edges - a move or restore to the ROOT clashes with a root, a move to the CURRENT parent never clashes with itself, an archived sibling does not clash`() {
        val t = newTenant(); val ty = type(t); val rootX = unit(t, ty, "X"); val p = unit(t, ty, "P"); val xInP = unit(t, ty, "X", p.id)
        assertThatThrownBy { units.move(t, xInP.id, null, null, 0) }.describedAs("a root named X already exists").isInstanceOf(DuplicateOrganizationKey::class.java).extracting("key").isEqualTo("code")
        val same = units.move(t, xInP.id, p.id, 3, 0)!!; assertThat(same.parentId).isEqualTo(p.id); assertThat(same.sortOrder).isEqualTo(3); assertThat(same.version).isEqualTo(1)       // re-order under the same parent
        assertThat(units.setActive(t, rootX.id, false, 0)!!.active).isFalse()
        assertThat(units.move(t, xInP.id, null, null, 1)!!.parentId).describedAs("the archived root no longer clashes").isNull()
        assertThatThrownBy { units.setActive(t, rootX.id, true, 1) }.describedAs("restoring the archived root now clashes with the new root X").isInstanceOf(DuplicateOrganizationKey::class.java)
        assertThat(units.update(units.find(t, p.id)!!.copy(code = "P"), 0)!!.code).describedAs("keeping its own code is not a clash").isEqualTo("P")
    }

    @Test
    fun `13 directory search by userId, and the other filters combine with AND`() {
        val t = newTenant(); val ty = type(t); val a = unit(t, ty, "A"); val ana = user(t, "ana"); val bao = user(t, "bao"); val ma = member(t, ana, a.id); member(t, bao, a.id)
        val dev = position(t, "DEV"); hold(ma, dev)
        assertThat(directory.search(t, EmployeeSearch(null, null, null, null, null, ana, "name", true, 0, 50), identities).items).containsExactly(ana)
        assertThat(directory.search(t, EmployeeSearch(null, null, null, null, null, UUID.randomUUID(), "name", true, 0, 50), identities).total).isZero()
        assertThat(directory.search(t, EmployeeSearch("person", setOf(a.id), dev.id, null, true, null, "name", true, 0, 50), identities).items).describedAs("unit AND position AND text AND active").containsExactly(ana)
        assertThat(directory.search(t, EmployeeSearch("person", setOf(a.id), dev.id, null, false, null, "name", true, 0, 50), identities).total).isZero()
    }
}
