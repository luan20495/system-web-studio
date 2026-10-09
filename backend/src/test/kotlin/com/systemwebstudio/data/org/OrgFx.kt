package com.systemwebstudio.data.org

import com.systemwebstudio.organization.EmployeePositionDto
import com.systemwebstudio.organization.GradeDto
import com.systemwebstudio.organization.OrgUnitTypeRules
import com.systemwebstudio.organization.OrganizationMembershipDto
import com.systemwebstudio.organization.OrganizationUnitDto
import com.systemwebstudio.organization.OrganizationUnitTypeDto
import com.systemwebstudio.organization.PositionDto
import java.time.Instant
import java.util.UUID

/** Fixtures for the organization tests: everything goes through the PRODUCTION repositories (so the fixtures themselves exercise them); unit codes are passed canonical (upper-case). */
class OrgFx(val s: OrgTestDb.Stack = OrgTestDb.stack) {
    private val now get() = Instant.now()
    fun tag() = UUID.randomUUID().toString().take(8)
    fun tenant(): UUID = OrgTestDb.newTenant()
    fun member(t: UUID, name: String = "m", display: String? = "Person " + name.uppercase(), email: String? = null, active: Boolean = true): UUID = OrgTestDb.newMember(t, "$name-${tag()}", display, email, active)

    fun type(t: UUID, code: String = "c" + tag(), rules: OrgUnitTypeRules = OrgUnitTypeRules()) =
        s.types.insert(OrganizationUnitTypeDto(UUID.randomUUID(), t, "Type $code", code, null, true, rules, 0, now, now))

    fun unit(t: UUID, type: OrganizationUnitTypeDto, code: String, parent: UUID? = null, sort: Int = 0, name: String = "Unit $code") =
        s.units.insert(OrganizationUnitDto(UUID.randomUUID(), t, type.id, parent, name, code.uppercase(), sort, OrgTestDb.json.createObjectNode(), true, 0, now, now))

    /** a chain of [n] units, the first under [parent]; returned top-down */
    fun chain(t: UUID, type: OrganizationUnitTypeDto, parent: UUID?, n: Int, prefix: String = "L"): List<OrganizationUnitDto> {
        var p = parent; return (1..n).map { i -> unit(t, type, "$prefix$i", p).also { p = it.id } }
    }

    fun membership(t: UUID, user: UUID, unit: UUID, relation: String = "MEMBER") = s.memberships.insert(OrganizationMembershipDto(UUID.randomUUID(), t, user, unit, relation, false, true, 0, now, now))
    fun position(t: UUID, code: String = "P" + tag()) = s.positions.insert(PositionDto(UUID.randomUUID(), t, "Pos $code", code, null, true, 0, now, now))
    fun grade(t: UUID, code: String = "G" + tag(), rank: Int? = 1) = s.grades.insert(GradeDto(UUID.randomUUID(), t, "Grade $code", code, rank, null, true, 0, now, now))
    fun hold(m: OrganizationMembershipDto, p: PositionDto, g: GradeDto? = null) =
        s.employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), m.tenantId, m.userId, m.id, m.organizationUnitId, p.id, g?.id, false, true, 0, now, now))
}
