package com.systemwebstudio.organization

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.AccountService
import com.systemwebstudio.tenancy.TenantRole
import com.systemwebstudio.tenancy.TenantService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The employee directory of ONE tenant. There is NO employee profile in V1 (frozen C0 decision EMPLOYEE_PROFILE = NOT_NEEDED): an employee IS a tenant member, `tenant_members
 * JOIN users`, read through [TenantIdentityDirectory] (username, display name, e-mail, credentials, activation, account state, tenant role, `active`). Only the organization
 * memberships and the position assignments live behind the C3 seams. Nothing of the identity is duplicated.
 * A new person is provisioned by [AccountService.createTenantUser] (the canonical provisioning behind `POST /admin/tenants/{t}/users`) in the SAME transaction as the memberships
 * and positions: nothing half-created survives a failure. Callers MUST authorize first (the controllers do); tenant isolation here is "tenant first, foreign = 404".
 *
 * Organization membership says WHERE a person belongs, a position (always held WITHIN one membership, with an optional grade) says WHAT they do: NEITHER grants a permission, and
 * no relation type (MANAGER, HEAD, ...) is ever read as authority.
 *
 * Enable / disable IS the canonical tenant-membership lifecycle ([TenantService.removeMember] / [TenantService.setMember]): the last Tenant Admin is protected, nobody disables
 * themselves, enabling restores a plain MEMBER and is refused for an account disabled platform-wide. Memberships and positions are kept (history) while disabled.
 *
 * LOCKING: none of these operations takes the tenant structural lock (it is taken only by the structural unit operations: create, move, restore, type-rule change): ordinary transactions, FK / unique constraints and versions.
 */
@Service
class EmployeeDirectoryService(
    private val repos: OrganizationRepositories, private val identities: TenantIdentityDirectory, private val audit: AuditService,
    private val accounts: AccountService, private val tenants: TenantService
) {
    companion object {
        const val MAX_PAGE_SIZE = 100
        /** the deepest directory page offset (D-C0-52): beyond it the list answers 400 OFFSET_TOO_LARGE */
        const val MAX_OFFSET = 10_000
        /** active organization memberships PER EMPLOYEE */
        const val MAX_MEMBERSHIPS = 20
        /** active position assignments PER MEMBERSHIP (not per employee: each membership has its own limit); the 21st answers 400 VALIDATION_FAILED */
        const val MAX_POSITIONS = 20
        private val SORTS = setOf("name", "username")
    }

    private fun noEmployee() = ApiException.notFound("EMPLOYEE_NOT_FOUND", "Employee not found")
    private fun noMembership() = ApiException.notFound("ORG_MEMBERSHIP_NOT_FOUND", "Organization membership not found")
    private fun noAssignment() = ApiException.notFound("POSITION_ASSIGNMENT_NOT_FOUND", "Position assignment not found")
    private fun byCreated(): Comparator<OrganizationMembershipDto> = compareBy({ !it.primary }, { it.createdAt }, { it.id })

    // ------------------------------------------------------------------------------------------------------------------------ read model
    private fun assemble(tenantId: UUID, ids: List<UUID>): List<EmployeeDto> {
        if (ids.isEmpty()) return emptyList()
        val who = identities.findAll(tenantId, ids)
        val ms = repos.memberships.listForUsers(tenantId, ids, false).groupBy { it.userId }
        val ps = repos.employeePositions.listForUsers(tenantId, ids, false).groupBy { it.userId }
        return ids.mapNotNull { id ->
            val i = who[id] ?: return@mapNotNull null
            val mine = ms[id].orEmpty().sortedWith(byCreated())
            EmployeeDto(id, tenantId, i.username, i.displayName, i.email, i.tenantMemberActive, i.accountEnabled, i.accountActivated, i.tenantRole,
                mine.firstOrNull { it.primary }?.organizationUnitId, ps[id].orEmpty().sortedWith(compareBy<EmployeePositionDto>({ !it.primary }, { it.createdAt }, { it.id })), mine)
        }
    }

    /** the store must exist before anything is looked up (501 comes after authorization, never after a 404 that would need the store to be decided) */
    private fun ready() { repos.memberships; repos.employeePositions; repos.directory }
    private fun identity(tenantId: UUID, userId: UUID): TenantIdentity = identities.find(tenantId, userId) ?: throw noEmployee()
    private fun activeIdentity(tenantId: UUID, userId: UUID): TenantIdentity =
        identity(tenantId, userId).also { if (!it.tenantMemberActive) throw ApiException.conflict("EMPLOYEE_INACTIVE", "The employee is disabled") }

    fun get(tenantId: UUID, userId: UUID): EmployeeDto { ready(); identity(tenantId, userId); return assemble(tenantId, listOf(userId)).firstOrNull() ?: throw noEmployee() }

    fun list(tenantId: UUID, q: String?, organizationUnitId: UUID?, includeDescendants: Boolean, positionId: UUID?, gradeId: UUID?, active: Boolean?, userId: UUID?,
             page: Int, size: Int, sort: String?, dir: String?): EmployeePageDto {
        if (page < 0) throw OrgRules.bad("page must be 0 or more")
        if (size < 1 || size > MAX_PAGE_SIZE) throw OrgRules.bad("size must be 1-$MAX_PAGE_SIZE")
        if (page.toLong() * size > MAX_OFFSET) throw OrgRules.bad("page * size must not exceed $MAX_OFFSET (narrow the search instead of paging deeper)", "OFFSET_TOO_LARGE")
        val s = sort ?: "name"; if (s !in SORTS) throw OrgRules.bad("sort must be one of $SORTS")
        val asc = when (dir?.lowercase() ?: "asc") { "asc" -> true; "desc" -> false; else -> throw OrgRules.bad("dir must be asc or desc") }
        val term = q?.trim()?.takeIf { it.isNotEmpty() }
        if (term != null && term.length < 2) throw OrgRules.bad("Search must contain at least 2 characters", "QUERY_TOO_SHORT")
        val unitIds = organizationUnitId?.let { id ->
            repos.units.find(tenantId, id) ?: throw ApiException.notFound("ORG_UNIT_NOT_FOUND", "Organization unit not found")            // a foreign / unknown unit is a 404, not an empty page
            if (includeDescendants) repos.units.subtree(tenantId, id).map { it.id }.toSet() else setOf(id)
        }
        positionId?.let { repos.positions.find(tenantId, it) ?: throw ApiException.notFound("POSITION_NOT_FOUND", "Position not found") }
        gradeId?.let { repos.grades.find(tenantId, it) ?: throw ApiException.notFound("GRADE_NOT_FOUND", "Grade not found") }
        val slice = repos.directory.search(tenantId, EmployeeSearch(term, unitIds, positionId, gradeId, active, userId, s, asc, page, size), identities)
        return EmployeePageDto(assemble(tenantId, slice.items), slice.total, page, size)
    }

    // ------------------------------------------------------------------------------------------------------------------------ create
    private fun relationOf(raw: String?): String {
        val r = (raw?.trim()?.takeIf { it.isNotEmpty() } ?: "MEMBER").uppercase(java.util.Locale.ROOT)
        if (!OrgRules.RELATION.matches(r)) throw OrgRules.bad("relationType must be 1-32 characters of A-Z 0-9 _ and start with a letter", "INVALID_CODE")
        return r
    }
    private fun activeUnit(tenantId: UUID, id: UUID): OrganizationUnitDto =
        (repos.units.find(tenantId, id) ?: throw ApiException.notFound("ORG_UNIT_NOT_FOUND", "Organization unit not found"))
            .also { if (!it.active) throw ApiException.conflict("ORG_UNIT_ARCHIVED", "The organization unit is archived") }
    private fun activePosition(tenantId: UUID, id: UUID): PositionDto =
        (repos.positions.find(tenantId, id) ?: throw ApiException.notFound("POSITION_NOT_FOUND", "Position not found")).also { if (!it.active) throw ApiException.conflict("POSITION_DISABLED", "The position is disabled") }
    private fun activeGrade(tenantId: UUID, id: UUID): GradeDto =
        (repos.grades.find(tenantId, id) ?: throw ApiException.notFound("GRADE_NOT_FOUND", "Grade not found")).also { if (!it.active) throw ApiException.conflict("GRADE_DISABLED", "The grade is disabled") }
    /** the ACTIVE membership of THIS employee a position is held within: unknown / foreign / of another employee = 404, ended = 409 */
    private fun activeMembership(tenantId: UUID, userId: UUID, membershipId: UUID): OrganizationMembershipDto =
        (repos.memberships.find(tenantId, userId, membershipId) ?: throw noMembership()).also { if (!it.active) throw ApiException.conflict("ORG_MEMBERSHIP_INACTIVE", "The organization membership has ended") }

    @Transactional
    fun create(tenantId: UUID, actorId: UUID, r: EmployeeCreateRequest): EmployeeCreatedDto {
        ready()
        val memberships = r.organizationMemberships.orEmpty()
        if (memberships.size > MAX_MEMBERSHIPS) throw OrgRules.bad("too many memberships")
        val unitIds = memberships.map { it.organizationUnitId ?: throw OrgRules.bad("organizationUnitId is required") }
        if (unitIds.size != unitIds.distinct().size) throw OrgRules.bad("a unit may appear once in organizationMemberships")
        if (memberships.count { it.primary == true } > 1) throw OrgRules.bad("at most one primary membership")
        val positions = memberships.map { it.positions.orEmpty() }          // positions are NESTED under the membership they are held within: there is no unit-as-scope input
        if (positions.any { it.size > MAX_POSITIONS }) throw OrgRules.bad("A membership can hold at most $MAX_POSITIONS active positions")    // the limit is PER MEMBERSHIP (C1 contract), not per employee
        if (positions.any { l -> l.count { it.primary == true } > 1 || l.mapNotNull { it.positionId }.let { ids -> ids.size != ids.distinct().size } }) throw OrgRules.bad("a position may appear once per membership, and at most one position may be primary")
        if (positions.sumOf { l -> l.count { it.primary == true } } > 1) throw OrgRules.bad("at most one primary position")
        // validate everything that does not need the account FIRST (cheap, and the order of the errors is the order of the contract)
        val units = memberships.map { m -> activeUnit(tenantId, m.organizationUnitId!!).also { relationOf(m.relationType) } }
        val assignments = positions.map { l -> l.map { p -> Triple(activePosition(tenantId, p.positionId ?: throw OrgRules.bad("positionId is required")), p.gradeId?.let { activeGrade(tenantId, it) }, p.primary == true) } }
        val activation = accounts.createTenantUser(actorId, tenantId, r.username?.trim().orEmpty(), r.displayName?.trim().orEmpty(), r.email, r.tenantRole ?: "MEMBER", r.workspaceId, r.workspaceRole)
        val userId = activation.userId
        val primaryUnit = memberships.indexOfFirst { it.primary == true }.takeIf { it >= 0 } ?: 0
        val madeMemberships = memberships.mapIndexed { i, m -> insertMembership(tenantId, userId, units[i].id, relationOf(m.relationType), i == primaryUnit) }
        val flat = assignments.flatMapIndexed { i, l -> l.map { i to it } }
        val primaryPos = flat.indexOfFirst { it.second.third }.takeIf { it >= 0 } ?: 0
        flat.forEachIndexed { k, (i, t) -> insertAssignment(tenantId, userId, madeMemberships[i], t.first.id, t.second?.id, k == primaryPos) }
        val created = get(tenantId, userId)
        audit.record("EMPLOYEE_CREATED", "EMPLOYEE", userId, actorId = actorId, newValue = snapshot(created, true))
        created.organizationMemberships.forEach { audit.record("EMPLOYEE_ORG_ASSIGNED", "EMPLOYEE", userId, actorId = actorId, newValue = membershipAudit(it)) }
        created.positions.forEach { audit.record("EMPLOYEE_POSITION_ASSIGNED", "EMPLOYEE", userId, actorId = actorId, newValue = assignmentAudit(it)) }
        return EmployeeCreatedDto(created, activation)
    }

    /** enable / disable = the canonical tenant-membership lifecycle; idempotent (no version: the state IS `tenant_members.active`) */
    @Transactional
    fun setActive(tenantId: UUID, userId: UUID, actorId: UUID, active: Boolean): EmployeeDto {
        ready()
        val before = get(tenantId, userId)
        if (before.active == active) return before
        if (!active) {
            if (userId == actorId) throw ApiException.forbidden("You cannot disable yourself", "SELF_GRANT_FORBIDDEN")
            tenants.removeMember(tenantId, userId, actorId)                                       // 409 LAST_TENANT_ADMIN
        } else {
            if (!before.accountEnabled) throw ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "USER_DISABLED", "That account is disabled")
            tenants.setMember(tenantId, userId, TenantRole.MEMBER, actorId)
        }
        val after = get(tenantId, userId)
        audit.record(if (active) "EMPLOYEE_ENABLED" else "EMPLOYEE_DISABLED", "EMPLOYEE", userId, actorId = actorId, oldValue = snapshot(before, false), newValue = snapshot(after, false))
        return after
    }

    // ------------------------------------------------------------------------------------------------------------------------ organization memberships (multi-org)
    fun memberships(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<OrganizationMembershipDto> {
        ready()
        identity(tenantId, userId)
        return repos.memberships.list(tenantId, userId, includeInactive).sortedWith(byCreated())
    }

    private fun insertMembership(tenantId: UUID, userId: UUID, unitId: UUID, relation: String, primary: Boolean): OrganizationMembershipDto {
        val now = Instant.now()
        val inserted = try { repos.memberships.insert(OrganizationMembershipDto(UUID.randomUUID(), tenantId, userId, unitId, relation, false, true, 0, now, now)) }
        catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("ORG_MEMBERSHIP_EXISTS", "The employee already belongs to this unit") }
        catch (e: ReferencedRowInactive) { throw ApiException.conflict("ORG_UNIT_ARCHIVED", "The organization unit is archived") }       // archived concurrently (store-checked)
        return if (primary) makePrimaryMembership(tenantId, userId, inserted) else inserted
    }

    @Transactional
    fun addMembership(tenantId: UUID, userId: UUID, actorId: UUID, r: MembershipCreateRequest): OrganizationMembershipDto {
        ready()
        activeIdentity(tenantId, userId)
        val unitId = r.organizationUnitId ?: throw OrgRules.bad("organizationUnitId is required")
        val unit = activeUnit(tenantId, unitId); val relation = relationOf(r.relationType)           // foreign / unknown -> 404 ORG_UNIT_NOT_FOUND; archived -> 409
        val existing = repos.memberships.list(tenantId, userId, false)
        if (existing.size >= MAX_MEMBERSHIPS) throw OrgRules.bad("An employee can belong to at most $MAX_MEMBERSHIPS units")
        val created = insertMembership(tenantId, userId, unit.id, relation, r.primary == true || existing.isEmpty())
        audit.record("EMPLOYEE_ORG_ASSIGNED", "EMPLOYEE", userId, actorId = actorId, newValue = membershipAudit(created))
        return created
    }

    @Transactional
    fun updateMembership(tenantId: UUID, userId: UUID, membershipId: UUID, actorId: UUID, r: MembershipUpdateRequest): OrganizationMembershipDto {
        ready()
        val expected = OrgRules.need(r.expectedVersion); activeIdentity(tenantId, userId)
        if (r.primary == false) throw OrgRules.bad("primary can only be set to true: make another membership primary instead")
        val before = repos.memberships.find(tenantId, userId, membershipId)?.takeIf { it.active } ?: throw noMembership()
        var current = before
        if (r.relationType != null) current = repos.memberships.update(before.copy(relationType = relationOf(r.relationType)), expected) ?: throw OrgRules.conflictOrMissing(repos.memberships.find(tenantId, userId, membershipId)?.version, noMembership())
        if (r.primary == true && !current.primary) current = try { repos.memberships.setPrimary(tenantId, userId, membershipId, if (r.relationType != null) current.version else expected) } catch (e: DuplicateOrganizationKey) { throw lostRace() } ?: throw OrgRules.conflictOrMissing(repos.memberships.find(tenantId, userId, membershipId)?.version, noMembership())
        else if (r.relationType == null && current.version != expected) throw OrgRules.conflictOrMissing(current.version, noMembership())
        audit.record("EMPLOYEE_ORG_UPDATED", "EMPLOYEE", userId, actorId = actorId, oldValue = membershipAudit(before), newValue = membershipAudit(current))
        return current
    }

    /**
     * Ends the membership (the row stays, inactive). LIFECYCLE (frozen): a membership that still has ACTIVE position assignments cannot end: `409 EMPLOYEE_ORG_HAS_POSITIONS
     * {activePositionCount}`. There is NO silent cascade: the caller ends the positions first (each one audited), then the membership. Ending the primary membership promotes
     * the oldest remaining active one.
     */
    @Transactional
    fun removeMembership(tenantId: UUID, userId: UUID, membershipId: UUID, actorId: UUID, expectedVersion: Long?): OrganizationMembershipDto {
        ready()
        val expected = OrgRules.need(expectedVersion); identity(tenantId, userId)
        val before = repos.memberships.find(tenantId, userId, membershipId)?.takeIf { it.active } ?: throw noMembership()
        val held = repos.employeePositions.list(tenantId, userId, false).count { it.membershipId == membershipId }
        if (held > 0) throw ApiException.conflict("EMPLOYEE_ORG_HAS_POSITIONS", "End the positions held in this organization unit first", mapOf("activePositionCount" to held))
        val ended = try { repos.memberships.end(tenantId, userId, membershipId, expected) }
            catch (e: MembershipHasPositions) { throw ApiException.conflict("EMPLOYEE_ORG_HAS_POSITIONS", "End the positions held in this organization unit first", mapOf("activePositionCount" to e.activePositions)) }     // store-checked atomically
            ?: throw OrgRules.conflictOrMissing(repos.memberships.find(tenantId, userId, membershipId)?.version, noMembership())
        var promoted: UUID? = null
        if (before.primary) repos.memberships.list(tenantId, userId, false).minWithOrNull(compareBy({ it.createdAt }, { it.id }))?.let {
            makePrimaryMembership(tenantId, userId, it); promoted = it.id
        }
        audit.record("EMPLOYEE_ORG_REMOVED", "EMPLOYEE", userId, actorId = actorId, oldValue = membershipAudit(before), newValue = membershipAudit(ended) + mapOf("promotedMembershipId" to promoted))
        return ended
    }

    // ------------------------------------------------------------------------------------------------------------------------ positions (held within a membership)
    fun positions(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<EmployeePositionDto> {
        ready()
        identity(tenantId, userId)
        return repos.employeePositions.list(tenantId, userId, includeInactive).sortedWith(compareBy({ !it.primary }, { it.createdAt }, { it.id }))
    }

    private fun insertAssignment(tenantId: UUID, userId: UUID, membership: OrganizationMembershipDto, positionId: UUID, gradeId: UUID?, primary: Boolean): EmployeePositionDto {
        val now = Instant.now()
        val inserted = try { repos.employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), tenantId, userId, membership.id, membership.organizationUnitId, positionId, gradeId, false, true, 0, now, now)) }
        catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("POSITION_ASSIGNMENT_EXISTS", "The employee already holds this position in this organization unit") }
        catch (e: ReferencedRowInactive) { throw ApiException.conflict("ORG_MEMBERSHIP_INACTIVE", "The organization membership has ended") }      // ended concurrently (store-checked)
        return if (primary) makePrimaryPosition(tenantId, userId, inserted) else inserted
    }

    /** a lost race on the primary flag (stale version or the partial unique index) is a 409, never a silent "no primary" */
    private fun lostRace(): ApiException = ApiException.conflict("VERSION_CONFLICT", "The record changed since it was read; reload and retry.")
    private fun makePrimaryMembership(tenantId: UUID, userId: UUID, m: OrganizationMembershipDto): OrganizationMembershipDto =
        try { repos.memberships.setPrimary(tenantId, userId, m.id, m.version) ?: throw lostRace() } catch (e: DuplicateOrganizationKey) { throw lostRace() }
    private fun makePrimaryPosition(tenantId: UUID, userId: UUID, a: EmployeePositionDto): EmployeePositionDto =
        try { repos.employeePositions.setPrimary(tenantId, userId, a.id, a.version) ?: throw lostRace() } catch (e: DuplicateOrganizationKey) { throw lostRace() }

    /** after the primary position ended: the oldest remaining active one becomes primary (a person with positions has one primary) */
    private fun promotePosition(tenantId: UUID, userId: UUID) {
        val left = repos.employeePositions.list(tenantId, userId, false)
        if (left.isNotEmpty() && left.none { it.primary }) left.minWith(compareBy({ it.createdAt }, { it.id })).let { makePrimaryPosition(tenantId, userId, it) }
    }

    @Transactional
    fun addPosition(tenantId: UUID, userId: UUID, actorId: UUID, r: EmployeePositionCreateRequest): EmployeePositionDto {
        ready()
        activeIdentity(tenantId, userId)
        val membership = activeMembership(tenantId, userId, r.membershipId ?: throw OrgRules.bad("membershipId is required: a position is held within an organization membership"))
        val pos = activePosition(tenantId, r.positionId ?: throw OrgRules.bad("positionId is required"))
        val grade = r.gradeId?.let { activeGrade(tenantId, it) }
        val existing = repos.employeePositions.list(tenantId, userId, false)
        // MAX_POSITIONS is PER MEMBERSHIP (C1 contract decision): only the ACTIVE assignments of THIS membership count; another membership of the same employee has its own limit
        if (existing.count { it.membershipId == membership.id && it.active } >= MAX_POSITIONS) throw OrgRules.bad("A membership can hold at most $MAX_POSITIONS active positions")
        val created = insertAssignment(tenantId, userId, membership, pos.id, grade?.id, r.primary == true || existing.isEmpty())
        audit.record("EMPLOYEE_POSITION_ASSIGNED", "EMPLOYEE", userId, actorId = actorId, newValue = assignmentAudit(created))
        return created
    }

    /** changes the GRADE of the assignment (set / clear) and / or makes it the primary one; the position and the membership are immutable (end it and add another) */
    @Transactional
    fun updatePosition(tenantId: UUID, userId: UUID, assignmentId: UUID, actorId: UUID, r: EmployeePositionUpdateRequest): EmployeePositionDto {
        ready()
        val expected = OrgRules.need(r.expectedVersion); activeIdentity(tenantId, userId)
        if (r.primary == false) throw OrgRules.bad("primary can only be set to true: make another position primary instead")
        if (r.gradeId != null && r.clearGrade == true) throw OrgRules.bad("send gradeId or clearGrade, not both")
        val before = repos.employeePositions.find(tenantId, userId, assignmentId)?.takeIf { it.active } ?: throw noAssignment()
        var current = before
        val gradeChange = r.gradeId != null || r.clearGrade == true
        if (gradeChange) {
            val gradeId = r.gradeId?.let { activeGrade(tenantId, it).id }
            current = repos.employeePositions.update(before.copy(gradeId = gradeId), expected) ?: throw OrgRules.conflictOrMissing(repos.employeePositions.find(tenantId, userId, assignmentId)?.version, noAssignment())
        }
        if (r.primary == true && !current.primary) current = try { repos.employeePositions.setPrimary(tenantId, userId, assignmentId, if (gradeChange) current.version else expected) } catch (e: DuplicateOrganizationKey) { throw lostRace() } ?: throw OrgRules.conflictOrMissing(repos.employeePositions.find(tenantId, userId, assignmentId)?.version, noAssignment())
        else if (!gradeChange && current.version != expected) throw OrgRules.conflictOrMissing(current.version, noAssignment())
        audit.record("EMPLOYEE_POSITION_UPDATED", "EMPLOYEE", userId, actorId = actorId, oldValue = assignmentAudit(before), newValue = assignmentAudit(current))
        return current
    }

    @Transactional
    fun removePosition(tenantId: UUID, userId: UUID, assignmentId: UUID, actorId: UUID, expectedVersion: Long?): EmployeePositionDto {
        ready()
        val expected = OrgRules.need(expectedVersion); identity(tenantId, userId)
        val before = repos.employeePositions.find(tenantId, userId, assignmentId)?.takeIf { it.active } ?: throw noAssignment()
        val ended = repos.employeePositions.end(tenantId, userId, assignmentId, expected) ?: throw OrgRules.conflictOrMissing(repos.employeePositions.find(tenantId, userId, assignmentId)?.version, noAssignment())
        if (before.primary) promotePosition(tenantId, userId)
        audit.record("EMPLOYEE_POSITION_REMOVED", "EMPLOYEE", userId, actorId = actorId, oldValue = assignmentAudit(before), newValue = assignmentAudit(ended))
        return ended
    }

    // ------------------------------------------------------------------------------------------------------------------------ audit payloads (ids and values only, never a secret)
    private fun snapshot(e: EmployeeDto, newAccount: Boolean) = mapOf("tenantId" to e.tenantId, "userId" to e.userId, "username" to e.username, "active" to e.active,
        "primaryOrganizationUnitId" to e.primaryOrganizationUnitId, "newAccount" to newAccount)
    private fun membershipAudit(m: OrganizationMembershipDto) = mapOf("tenantId" to m.tenantId, "userId" to m.userId, "membershipId" to m.id, "organizationUnitId" to m.organizationUnitId,
        "relationType" to m.relationType, "primary" to m.primary, "active" to m.active, "version" to m.version)
    private fun assignmentAudit(a: EmployeePositionDto) = mapOf("tenantId" to a.tenantId, "userId" to a.userId, "assignmentId" to a.id, "membershipId" to a.membershipId, "positionId" to a.positionId,
        "gradeId" to a.gradeId, "organizationUnitId" to a.organizationUnitId, "primary" to a.primary, "active" to a.active, "version" to a.version)
}
