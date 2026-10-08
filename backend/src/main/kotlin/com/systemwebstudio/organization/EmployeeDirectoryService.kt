package com.systemwebstudio.organization

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.AccountService
import com.systemwebstudio.tenancy.TenantRole
import com.systemwebstudio.tenancy.TenantService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * The employee directory of ONE tenant. An employee is a PROJECTION: identity (username, display name, e-mail, credentials, activation, account state, tenant role) stays in the C1
 * user system and is read through [TenantIdentityDirectory]; the HR profile, the organization memberships and the position assignments live behind the C3 seams.
 * A new person is provisioned by [AccountService.createTenantUser] (the canonical provisioning behind `POST /admin/tenants/{t}/users`) in the SAME transaction as the profile,
 * memberships and positions: nothing half-created survives a failure. Callers MUST authorize first (the controllers do); tenant isolation here is "tenant first, foreign = 404".
 *
 * Organization membership says WHERE a person belongs, a position says WHAT they do: NEITHER grants a permission, and no relation type (MANAGER, HEAD, ...) is ever read as authority.
 *
 * Enable / disable: disabling deactivates the person's membership of THIS tenant (via [TenantService.removeMember]; the last Tenant Admin is protected, nobody disables
 * themselves) and marks the profile INACTIVE; the account and other tenants are untouched. Enabling restores a plain MEMBER and is refused for an account disabled platform-wide.
 */
@Service
class EmployeeDirectoryService(
    private val repos: OrganizationRepositories, private val identities: TenantIdentityDirectory, private val audit: AuditService, private val json: JsonMapper,
    private val accounts: AccountService, private val tenants: TenantService
) {
    companion object { const val MAX_PAGE_SIZE = 100; const val MAX_MEMBERSHIPS = 20; const val MAX_POSITIONS = 20; private val SORTS = setOf("name", "username", "code", "created") }

    private fun noEmployee() = ApiException.notFound("EMPLOYEE_NOT_FOUND", "Employee not found")
    private fun noMembership() = ApiException.notFound("ORG_MEMBERSHIP_NOT_FOUND", "Organization membership not found")
    private fun noAssignment() = ApiException.notFound("POSITION_ASSIGNMENT_NOT_FOUND", "Position assignment not found")

    // ------------------------------------------------------------------------------------------------------------------------ read model
    private fun assemble(tenantId: UUID, profiles: List<EmployeeProfileRecord>): List<EmployeeDto> {
        if (profiles.isEmpty()) return emptyList()
        val ids = profiles.map { it.userId }
        val who = identities.findAll(tenantId, ids)
        val ms = repos.memberships.listForUsers(tenantId, ids, false).groupBy { it.userId }
        val ps = repos.employeePositions.listForUsers(tenantId, ids, false).groupBy { it.userId }
        return profiles.mapNotNull { p ->
            val i = who[p.userId] ?: return@mapNotNull null                                    // a profile without a tenant member cannot be shown
            val mine = ms[p.userId].orEmpty().sortedWith(compareBy<OrganizationMembershipDto>({ !it.primary }, { it.createdAt }, { it.id }))
            EmployeeDto(p.userId, p.tenantId, i.username, p.employeeCode, i.displayName, i.email, p.phone, p.joinedOn, p.metadata, p.status, i.accountEnabled, i.accountActivated, i.tenantRole,
                mine.firstOrNull { it.primary }?.organizationUnitId, ps[p.userId].orEmpty().sortedWith(compareBy<EmployeePositionDto>({ !it.primary }, { it.createdAt }, { it.id })), mine,
                p.createdAt, p.updatedAt, p.version)
        }
    }

    fun get(tenantId: UUID, userId: UUID): EmployeeDto {
        val profile = repos.profiles.find(tenantId, userId) ?: throw noEmployee()
        return assemble(tenantId, listOf(profile)).firstOrNull() ?: throw noEmployee()
    }

    private fun profile(tenantId: UUID, userId: UUID): EmployeeProfileRecord = repos.profiles.find(tenantId, userId) ?: throw noEmployee()
    private fun activeProfile(tenantId: UUID, userId: UUID): EmployeeProfileRecord =
        profile(tenantId, userId).also { if (it.status != OrgStatus.ACTIVE) throw ApiException.conflict("EMPLOYEE_INACTIVE", "The employee is disabled") }

    fun list(tenantId: UUID, q: String?, organizationUnitId: UUID?, includeDescendants: Boolean, positionId: UUID?, gradeId: UUID?, active: Boolean?, userId: UUID?,
             page: Int, size: Int, sort: String?, dir: String?): EmployeePageDto {
        if (page < 0) throw OrgRules.bad("page must be 0 or more")
        if (size < 1 || size > MAX_PAGE_SIZE) throw OrgRules.bad("size must be 1-$MAX_PAGE_SIZE")
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
        val slice = repos.profiles.search(tenantId, EmployeeSearch(term, unitIds, positionId, gradeId, active?.let { if (it) OrgStatus.ACTIVE else OrgStatus.INACTIVE }, userId, s, asc, page, size), identities)
        return EmployeePageDto(assemble(tenantId, slice.items), slice.total, page, size)
    }

    // ------------------------------------------------------------------------------------------------------------------------ create / invite
    private fun relationOf(raw: String?): String {
        val r = (raw?.trim()?.takeIf { it.isNotEmpty() } ?: "MEMBER").uppercase()
        if (!OrgRules.RELATION.matches(r)) throw OrgRules.bad("relationType must be 1-32 characters of A-Z 0-9 _ and start with a letter", "INVALID_CODE")
        return r
    }
    private fun employeeCodeOf(raw: String?): String? {
        val c = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!OrgRules.CATALOG_CODE.matches(c)) throw OrgRules.bad("employeeCode is not valid", "INVALID_CODE"); return c
    }
    private fun activeUnit(tenantId: UUID, id: UUID): OrganizationUnitDto =
        (repos.units.find(tenantId, id) ?: throw ApiException.notFound("ORG_UNIT_NOT_FOUND", "Organization unit not found"))
            .also { if (!it.active) throw ApiException.conflict("ORG_UNIT_ARCHIVED", "The organization unit is archived") }
    private fun activePosition(tenantId: UUID, id: UUID): PositionDto =
        (repos.positions.find(tenantId, id) ?: throw ApiException.notFound("POSITION_NOT_FOUND", "Position not found")).also { if (!it.active) throw ApiException.conflict("POSITION_DISABLED", "The position is disabled") }
    private fun activeGrade(tenantId: UUID, id: UUID): GradeDto =
        (repos.grades.find(tenantId, id) ?: throw ApiException.notFound("GRADE_NOT_FOUND", "Grade not found")).also { if (!it.active) throw ApiException.conflict("GRADE_DISABLED", "The grade is disabled") }

    @Transactional
    fun create(tenantId: UUID, actorId: UUID, r: EmployeeCreateRequest): EmployeeCreatedDto {
        val newAccount = r.userId == null
        if (!newAccount && listOf(r.username, r.displayName, r.email, r.tenantRole, r.workspaceId, r.workspaceRole).any { it != null })
            throw OrgRules.bad("Send either userId (an existing member of this company) or the fields of a new account, not both")
        val code = employeeCodeOf(r.employeeCode); val phone = OrgRules.text(r.phone, 40, "phone", false); val meta = OrgRules.metadata(json, r.metadata)
        val memberships = r.organizationMemberships.orEmpty(); val positions = r.positions.orEmpty()
        if (memberships.size > MAX_MEMBERSHIPS || positions.size > MAX_POSITIONS) throw OrgRules.bad("too many memberships or positions")
        if (memberships.mapNotNull { it.organizationUnitId }.let { it.size != it.distinct().size }) throw OrgRules.bad("a unit may appear once in organizationMemberships")
        if (memberships.count { it.primary == true } > 1 || positions.count { it.primary == true } > 1) throw OrgRules.bad("at most one primary membership and one primary position")
        return repos.lock.withTenantLock(tenantId) {
            // validate everything that does not need the account FIRST (cheap, and the order of the errors is the order of the contract)
            val units = memberships.map { m -> activeUnit(tenantId, m.organizationUnitId ?: throw OrgRules.bad("organizationUnitId is required")).also { relationOf(m.relationType) } }
            val assignments = positions.map { p ->
                val pos = activePosition(tenantId, p.positionId ?: throw OrgRules.bad("positionId is required"))
                val grade = p.gradeId?.let { activeGrade(tenantId, it) }; val unit = p.organizationUnitId?.let { activeUnit(tenantId, it) }
                Triple(pos, grade, unit)
            }
            val userId: UUID; var activation: com.systemwebstudio.identity.ActivationLink? = null
            if (newAccount) {
                activation = accounts.createTenantUser(actorId, tenantId, r.username?.trim().orEmpty(), r.displayName?.trim().orEmpty(), r.email, r.tenantRole ?: "MEMBER", r.workspaceId, r.workspaceRole)
                userId = activation.userId
            } else {
                userId = r.userId!!
                if (identities.find(tenantId, userId)?.tenantMemberActive != true) throw ApiException.notFound("USER_NOT_FOUND", "User not found")      // a stranger and an unknown id look the same
                if (repos.profiles.find(tenantId, userId) != null) throw ApiException.conflict("EMPLOYEE_EXISTS", "This person already has an employee profile")
            }
            val now = Instant.now()
            try { repos.profiles.insert(EmployeeProfileRecord(tenantId, userId, code, phone, r.joinedOn, meta, OrgStatus.ACTIVE, 0, now, now)) }
            catch (e: DuplicateOrganizationKey) {
                throw if (e.key == "employeeCode") ApiException.conflict("EMPLOYEE_CODE_TAKEN", "Another employee already has this employee code")
                else ApiException.conflict("EMPLOYEE_EXISTS", "This person already has an employee profile")
            }                                                                                       // the account created above rolls back with the transaction
            val primaryUnit = memberships.indexOfFirst { it.primary == true }.takeIf { it >= 0 } ?: 0
            memberships.forEachIndexed { i, m -> insertMembership(tenantId, userId, actorId, units[i].id, relationOf(m.relationType), i == primaryUnit) }
            val primaryPos = positions.indexOfFirst { it.primary == true }.takeIf { it >= 0 } ?: 0
            assignments.forEachIndexed { i, (pos, grade, unit) -> insertAssignment(tenantId, userId, actorId, pos.id, grade?.id, unit?.id, i == primaryPos) }
            val created = get(tenantId, userId)
            audit.record("EMPLOYEE_CREATED", "EMPLOYEE", userId, actorId = actorId, newValue = snapshot(created, newAccount))
            created.organizationMemberships.forEach { audit.record("EMPLOYEE_ORG_ASSIGNED", "EMPLOYEE", userId, actorId = actorId, newValue = membershipAudit(it)) }
            created.positions.forEach { audit.record("EMPLOYEE_POSITION_ASSIGNED", "EMPLOYEE", userId, actorId = actorId, newValue = assignmentAudit(it)) }
            EmployeeCreatedDto(created, activation)
        }
    }

    @Transactional
    fun update(tenantId: UUID, userId: UUID, actorId: UUID, r: EmployeeUpdateRequest): EmployeeDto {
        val expected = OrgRules.need(r.expectedVersion)
        return repos.lock.withTenantLock(tenantId) {
            val before = get(tenantId, userId); val p = profile(tenantId, userId)
            val next = p.copy(
                employeeCode = if (r.clearEmployeeCode == true) null else (employeeCodeOf(r.employeeCode) ?: p.employeeCode),
                phone = if (r.clearPhone == true) null else (OrgRules.text(r.phone, 40, "phone", false) ?: p.phone),
                joinedOn = if (r.clearJoinedOn == true) null else (r.joinedOn ?: p.joinedOn),
                metadata = r.metadata?.let { OrgRules.metadata(json, it) } ?: p.metadata)
            try { repos.profiles.update(next, expected) } catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("EMPLOYEE_CODE_TAKEN", "Another employee already has this employee code") }
                ?: throw OrgRules.conflictOrMissing(repos.profiles.find(tenantId, userId)?.version, noEmployee())
            val after = get(tenantId, userId)
            audit.record("EMPLOYEE_UPDATED", "EMPLOYEE", userId, actorId = actorId, oldValue = snapshot(before, false), newValue = snapshot(after, false))
            after
        }
    }

    @Transactional
    fun setActive(tenantId: UUID, userId: UUID, actorId: UUID, active: Boolean, expectedVersion: Long?): EmployeeDto {
        val expected = OrgRules.need(expectedVersion)
        return repos.lock.withTenantLock(tenantId) {
            val before = get(tenantId, userId)
            val want = if (active) OrgStatus.ACTIVE else OrgStatus.INACTIVE
            if (before.status == want) {                                                          // already in the requested state: an idempotent no-op for a current version
                if (before.version != expected) throw OrgRules.conflictOrMissing(before.version, noEmployee())
                return@withTenantLock before
            }
            if (!active) {
                if (userId == actorId) throw ApiException.forbidden("You cannot disable yourself", "SELF_GRANT_FORBIDDEN")
                if (identities.find(tenantId, userId)?.tenantMemberActive == true) tenants.removeMember(tenantId, userId, actorId)      // 409 LAST_TENANT_ADMIN
            } else {
                if (!before.accountEnabled) throw ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "USER_DISABLED", "That account is disabled")
                if (identities.find(tenantId, userId)?.tenantMemberActive != true) tenants.setMember(tenantId, userId, TenantRole.MEMBER, actorId)
            }
            repos.profiles.setStatus(tenantId, userId, want, expected) ?: throw OrgRules.conflictOrMissing(repos.profiles.find(tenantId, userId)?.version, noEmployee())     // rolls the membership change back too
            val after = get(tenantId, userId)
            audit.record(if (active) "EMPLOYEE_ENABLED" else "EMPLOYEE_DISABLED", "EMPLOYEE", userId, actorId = actorId, oldValue = snapshot(before, false), newValue = snapshot(after, false))
            after
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------ organization memberships (multi-org)
    fun memberships(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<OrganizationMembershipDto> {
        profile(tenantId, userId)
        return repos.memberships.list(tenantId, userId, includeInactive).sortedWith(compareBy({ !it.primary }, { it.createdAt }, { it.id }))
    }

    private fun insertMembership(tenantId: UUID, userId: UUID, actorId: UUID, unitId: UUID, relation: String, primary: Boolean): OrganizationMembershipDto {
        val now = Instant.now()
        val inserted = try { repos.memberships.insert(OrganizationMembershipDto(UUID.randomUUID(), tenantId, userId, unitId, relation, false, true, 0, now, now)) }
        catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("ORG_MEMBERSHIP_EXISTS", "The employee already belongs to this unit") }
        return if (primary) repos.memberships.setPrimary(tenantId, userId, inserted.id, inserted.version) ?: inserted else inserted
    }

    @Transactional
    fun addMembership(tenantId: UUID, userId: UUID, actorId: UUID, r: MembershipCreateRequest): OrganizationMembershipDto = repos.lock.withTenantLock(tenantId) {
        activeProfile(tenantId, userId)
        val unitId = r.organizationUnitId ?: throw OrgRules.bad("organizationUnitId is required")
        val unit = activeUnit(tenantId, unitId); val relation = relationOf(r.relationType)           // foreign / unknown -> 404 ORG_UNIT_NOT_FOUND; archived -> 409
        val existing = repos.memberships.list(tenantId, userId, false)
        if (existing.size >= MAX_MEMBERSHIPS) throw OrgRules.bad("An employee can belong to at most $MAX_MEMBERSHIPS units")
        val created = insertMembership(tenantId, userId, actorId, unit.id, relation, r.primary == true || existing.isEmpty())
        audit.record("EMPLOYEE_ORG_ASSIGNED", "EMPLOYEE", userId, actorId = actorId, newValue = membershipAudit(created))
        created
    }

    @Transactional
    fun updateMembership(tenantId: UUID, userId: UUID, membershipId: UUID, actorId: UUID, r: MembershipUpdateRequest): OrganizationMembershipDto = repos.lock.withTenantLock(tenantId) {
        val expected = OrgRules.need(r.expectedVersion); activeProfile(tenantId, userId)
        if (r.primary == false) throw OrgRules.bad("primary can only be set to true: make another membership primary instead")
        val before = repos.memberships.find(tenantId, userId, membershipId)?.takeIf { it.active } ?: throw noMembership()
        var current = before
        if (r.relationType != null) current = repos.memberships.update(before.copy(relationType = relationOf(r.relationType)), expected) ?: throw OrgRules.conflictOrMissing(repos.memberships.find(tenantId, userId, membershipId)?.version, noMembership())
        if (r.primary == true && !current.primary) current = repos.memberships.setPrimary(tenantId, userId, membershipId, if (r.relationType != null) current.version else expected) ?: throw OrgRules.conflictOrMissing(repos.memberships.find(tenantId, userId, membershipId)?.version, noMembership())
        else if (r.relationType == null && current.version != expected) throw OrgRules.conflictOrMissing(current.version, noMembership())
        audit.record("EMPLOYEE_ORG_UPDATED", "EMPLOYEE", userId, actorId = actorId, oldValue = membershipAudit(before), newValue = membershipAudit(current))
        current
    }

    /** ends the membership (the row stays, inactive). Ending the primary one promotes the oldest remaining active membership. */
    @Transactional
    fun removeMembership(tenantId: UUID, userId: UUID, membershipId: UUID, actorId: UUID, expectedVersion: Long?): OrganizationMembershipDto = repos.lock.withTenantLock(tenantId) {
        val expected = OrgRules.need(expectedVersion); profile(tenantId, userId)
        val before = repos.memberships.find(tenantId, userId, membershipId)?.takeIf { it.active } ?: throw noMembership()
        val ended = repos.memberships.end(tenantId, userId, membershipId, expected) ?: throw OrgRules.conflictOrMissing(repos.memberships.find(tenantId, userId, membershipId)?.version, noMembership())
        var promoted: UUID? = null
        if (before.primary) repos.memberships.list(tenantId, userId, false).minWithOrNull(compareBy({ it.createdAt }, { it.id }))?.let {
            repos.memberships.setPrimary(tenantId, userId, it.id, it.version); promoted = it.id
        }
        audit.record("EMPLOYEE_ORG_REMOVED", "EMPLOYEE", userId, actorId = actorId, oldValue = membershipAudit(before), newValue = membershipAudit(ended) + mapOf("promotedMembershipId" to promoted))
        ended
    }

    // ------------------------------------------------------------------------------------------------------------------------ positions
    fun positions(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<EmployeePositionDto> {
        profile(tenantId, userId)
        return repos.employeePositions.list(tenantId, userId, includeInactive).sortedWith(compareBy({ !it.primary }, { it.createdAt }, { it.id }))
    }

    private fun insertAssignment(tenantId: UUID, userId: UUID, actorId: UUID, positionId: UUID, gradeId: UUID?, unitId: UUID?, primary: Boolean): EmployeePositionDto {
        val now = Instant.now()
        val inserted = try { repos.employeePositions.insert(EmployeePositionDto(UUID.randomUUID(), tenantId, userId, positionId, gradeId, unitId, false, true, 0, now, now)) }
        catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("POSITION_ASSIGNMENT_EXISTS", "The employee already holds this position") }
        return if (primary) repos.employeePositions.setPrimary(tenantId, userId, inserted.id, inserted.version) ?: inserted else inserted
    }

    @Transactional
    fun addPosition(tenantId: UUID, userId: UUID, actorId: UUID, r: EmployeePositionCreateRequest): EmployeePositionDto = repos.lock.withTenantLock(tenantId) {
        activeProfile(tenantId, userId)
        val pos = activePosition(tenantId, r.positionId ?: throw OrgRules.bad("positionId is required"))
        val grade = r.gradeId?.let { activeGrade(tenantId, it) }; val unit = r.organizationUnitId?.let { activeUnit(tenantId, it) }     // the unit is independent of the employee's memberships
        val existing = repos.employeePositions.list(tenantId, userId, false)
        if (existing.size >= MAX_POSITIONS) throw OrgRules.bad("An employee can hold at most $MAX_POSITIONS positions")
        val created = insertAssignment(tenantId, userId, actorId, pos.id, grade?.id, unit?.id, r.primary == true || existing.isEmpty())
        audit.record("EMPLOYEE_POSITION_ASSIGNED", "EMPLOYEE", userId, actorId = actorId, newValue = assignmentAudit(created))
        created
    }

    @Transactional
    fun removePosition(tenantId: UUID, userId: UUID, assignmentId: UUID, actorId: UUID, expectedVersion: Long?): EmployeePositionDto = repos.lock.withTenantLock(tenantId) {
        val expected = OrgRules.need(expectedVersion); profile(tenantId, userId)
        val before = repos.employeePositions.find(tenantId, userId, assignmentId)?.takeIf { it.active } ?: throw noAssignment()
        val ended = repos.employeePositions.end(tenantId, userId, assignmentId, expected) ?: throw OrgRules.conflictOrMissing(repos.employeePositions.find(tenantId, userId, assignmentId)?.version, noAssignment())
        audit.record("EMPLOYEE_POSITION_REMOVED", "EMPLOYEE", userId, actorId = actorId, oldValue = assignmentAudit(before), newValue = assignmentAudit(ended))
        ended
    }

    // ------------------------------------------------------------------------------------------------------------------------ audit payloads (ids and values only, never a secret)
    private fun snapshot(e: EmployeeDto, newAccount: Boolean) = mapOf("tenantId" to e.tenantId, "userId" to e.userId, "username" to e.username, "employeeCode" to e.employeeCode,
        "phone" to e.phone, "status" to e.status, "primaryOrganizationUnitId" to e.primaryOrganizationUnitId, "version" to e.version, "newAccount" to newAccount)
    private fun membershipAudit(m: OrganizationMembershipDto) = mapOf("tenantId" to m.tenantId, "userId" to m.userId, "membershipId" to m.id, "organizationUnitId" to m.organizationUnitId,
        "relationType" to m.relationType, "primary" to m.primary, "active" to m.active, "version" to m.version)
    private fun assignmentAudit(a: EmployeePositionDto) = mapOf("tenantId" to a.tenantId, "userId" to a.userId, "assignmentId" to a.id, "positionId" to a.positionId, "gradeId" to a.gradeId,
        "organizationUnitId" to a.organizationUnitId, "primary" to a.primary, "active" to a.active, "version" to a.version)
}
