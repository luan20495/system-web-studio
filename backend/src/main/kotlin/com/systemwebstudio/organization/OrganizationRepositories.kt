package com.systemwebstudio.organization

import com.systemwebstudio.common.ApiException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.UUID

/*
 * C1 → C3 SEAMS. Everything the organization / employee application services need from persistence, and nothing about how it is stored.
 *
 * Rules every implementation MUST honour (C3 verifies them with `OrganizationRepositoryContractKit`, the abstract conformance test in the C1 test sources):
 *  1. TENANT FIRST. Every method takes the tenant id as its first argument and filters by it. A record of another tenant is indistinguishable from a missing one:
 *     `find*` answers null, lists omit it, a counter ignores it. The tenant id of a record NEVER comes from a request body: the service passes the one it derived.
 *  2. VERSIONED WRITES. Every `update` / `setActive` / `move` / `setPrimary` / `end` takes `expectedVersion` and applies only if the stored version equals it, in ONE atomic
 *     statement, then increments the version and refreshes `updatedAt`. It answers null when nothing was applied (stale version OR missing); the service then re-reads to tell
 *     404 from 409 VERSION_CONFLICT. No lost update, ever.
 *  3. UNIQUENESS is enforced by the store, not only by a pre-check: a violation is thrown as [DuplicateOrganizationKey] (the service maps it to 409 and the surrounding
 *     transaction rolls back). Keys: type / position / grade `code` (per tenant, case-insensitive, even when inactive), unit `code` (per tenant, case-insensitive, among ACTIVE
 *     units: archiving frees it), employee `employee` (one profile per user and tenant) and `employeeCode`, `membership` (one ACTIVE membership per user and unit),
 *     `assignment` (one ACTIVE assignment per user, position, grade and unit).
 *  4. ONE TRANSACTION. Implementations take part in the AMBIENT Spring transaction (no REQUIRES_NEW, no second connection): employee creation provisions the account (C1 tables)
 *     and the profile / memberships / positions (C3 tables) atomically, and an audit failure rolls the mutation back.
 *  5. DETERMINISM. Lists have a total order; the services re-sort anyway where the contract fixes one.
 *  6. NO CASCADE. Nothing is deleted behind the caller's back: archive / end are state changes of the row itself.
 */

/** thrown by a repository when a uniqueness rule (see above) is violated */
class DuplicateOrganizationKey(val key: String) : RuntimeException("duplicate organization key: $key")

/** Serialises every structure mutation of ONE tenant for the rest of the ambient transaction (a move / archive / restore / membership change). Re-entrant. */
interface TenantStructureLock {
    fun <T> withTenantLock(tenantId: UUID, block: () -> T): T
}

interface OrganizationUnitTypeRepository {
    fun list(tenantId: UUID, includeInactive: Boolean): List<OrganizationUnitTypeDto>
    fun find(tenantId: UUID, id: UUID): OrganizationUnitTypeDto?
    fun findAll(tenantId: UUID, ids: Collection<UUID>): List<OrganizationUnitTypeDto>
    /** key `code` */
    fun insert(type: OrganizationUnitTypeDto): OrganizationUnitTypeDto
    /** writes name, icon, rules, active; the code is immutable */
    fun update(type: OrganizationUnitTypeDto, expectedVersion: Long): OrganizationUnitTypeDto?
}

/** the hierarchy: cycle and depth questions are answered by the store (a recursive query), the RULES are applied by C1 */
interface OrganizationUnitRepository {
    fun find(tenantId: UUID, id: UUID): OrganizationUnitDto?
    fun listAll(tenantId: UUID, includeArchived: Boolean): List<OrganizationUnitDto>
    /** key `code` (among active units) */
    fun insert(unit: OrganizationUnitDto): OrganizationUnitDto
    /** writes name, code, sortOrder, metadata ONLY (never parent, type or active); key `code` */
    fun update(unit: OrganizationUnitDto, expectedVersion: Long): OrganizationUnitDto?
    /** ATOMIC: sets the parent (null = root) and optionally the sortOrder of the unit; its whole subtree follows because only the unit's own parent changes */
    fun move(tenantId: UUID, id: UUID, newParentId: UUID?, sortOrder: Int?, expectedVersion: Long): OrganizationUnitDto?
    /** archive (false) / restore (true); key `code` when a restore finds the code taken by another active unit */
    fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long): OrganizationUnitDto?
    /** the unit (relativeDepth 0) and EVERY descendant, whatever its state; empty when the unit is not in the tenant */
    fun subtree(tenantId: UUID, id: UUID): List<SubtreeNode>
    /** root = 1; null when the unit is not in the tenant */
    fun depthOf(tenantId: UUID, id: UUID): Int?
    fun activeChildCount(tenantId: UUID, id: UUID): Int
}

interface EmployeeProfileRepository {
    fun find(tenantId: UUID, userId: UUID): EmployeeProfileRecord?
    /** keys `employee`, `employeeCode`. The user must already be a member of the tenant (the caller checked it against the C1 user system). */
    fun insert(profile: EmployeeProfileRecord): EmployeeProfileRecord
    /** writes employeeCode, phone, joinedOn, metadata; key `employeeCode` */
    fun update(profile: EmployeeProfileRecord, expectedVersion: Long): EmployeeProfileRecord?
    fun setStatus(tenantId: UUID, userId: UUID, status: String, expectedVersion: Long): EmployeeProfileRecord?
    /**
     * One page of the directory, already filtered / sorted / paginated. `sort` is one of name | username | code | created (name / username order by the C1 identity,
     * so an implementation may join the C1-owned read model `users` / `tenant_members` READ-ONLY, or use [identities]); ties are broken by userId. Text matches username,
     * display name, e-mail and employeeCode, case-insensitively and LITERALLY (`%`, `_`, `\` are text). `unitIds` = employees with an ACTIVE membership in any of them;
     * `positionId` / `gradeId` = employees with an ACTIVE assignment of it. An employee matching through several memberships is still ONE row.
     */
    fun search(tenantId: UUID, criteria: EmployeeSearch, identities: TenantIdentityDirectory): Slice<EmployeeProfileRecord>
}

interface EmployeeOrganizationMembershipRepository {
    fun list(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<OrganizationMembershipDto>
    /** one query for a page of the directory (no N+1); unordered, the service sorts */
    fun listForUsers(tenantId: UUID, userIds: Collection<UUID>, includeInactive: Boolean): List<OrganizationMembershipDto>
    fun find(tenantId: UUID, userId: UUID, membershipId: UUID): OrganizationMembershipDto?
    /** key `membership`. `primary=true` is applied through [setPrimary]'s rules: at most ONE active primary per tenant and user. */
    fun insert(membership: OrganizationMembershipDto): OrganizationMembershipDto
    /** writes relationType only */
    fun update(membership: OrganizationMembershipDto, expectedVersion: Long): OrganizationMembershipDto?
    /** ATOMIC: clears the previous primary of the user and sets this one, in one statement / one critical section */
    fun setPrimary(tenantId: UUID, userId: UUID, membershipId: UUID, expectedVersion: Long): OrganizationMembershipDto?
    /** ends the membership: active=false, primary=false (the row stays, for history) */
    fun end(tenantId: UUID, userId: UUID, membershipId: UUID, expectedVersion: Long): OrganizationMembershipDto?
    fun activeCountByUnit(tenantId: UUID, unitId: UUID): Int
}

interface PositionRepository {
    fun list(tenantId: UUID, includeInactive: Boolean): List<PositionDto>
    fun find(tenantId: UUID, id: UUID): PositionDto?
    /** key `code` */
    fun insert(position: PositionDto): PositionDto
    /** writes name, description */
    fun update(position: PositionDto, expectedVersion: Long): PositionDto?
    fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long): PositionDto?
}

interface GradeRepository {
    fun list(tenantId: UUID, includeInactive: Boolean): List<GradeDto>
    fun find(tenantId: UUID, id: UUID): GradeDto?
    /** key `code` */
    fun insert(grade: GradeDto): GradeDto
    /** writes name, rank, description */
    fun update(grade: GradeDto, expectedVersion: Long): GradeDto?
    fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long): GradeDto?
}

interface EmployeePositionRepository {
    fun list(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<EmployeePositionDto>
    fun listForUsers(tenantId: UUID, userIds: Collection<UUID>, includeInactive: Boolean): List<EmployeePositionDto>
    fun find(tenantId: UUID, userId: UUID, id: UUID): EmployeePositionDto?
    /** key `assignment` */
    fun insert(assignment: EmployeePositionDto): EmployeePositionDto
    /** ATOMIC: at most ONE active primary position per tenant and user */
    fun setPrimary(tenantId: UUID, userId: UUID, id: UUID, expectedVersion: Long): EmployeePositionDto?
    fun end(tenantId: UUID, userId: UUID, id: UUID, expectedVersion: Long): EmployeePositionDto?
}

/** The identity side of an employee (C1-owned): read-only projection of `users` + `tenant_members` for ONE tenant. */
data class TenantIdentity(
    val userId: UUID, val username: String, val displayName: String?, val email: String?,
    val accountEnabled: Boolean, val accountActivated: Boolean, val tenantRole: String, val tenantMemberActive: Boolean
)
interface TenantIdentityDirectory {
    /** a person who is (or was) a member of the tenant; null for a stranger */
    fun find(tenantId: UUID, userId: UUID): TenantIdentity?
    fun findAll(tenantId: UUID, userIds: Collection<UUID>): Map<UUID, TenantIdentity>
    /** every (active or inactive) member of the tenant */
    fun members(tenantId: UUID): List<TenantIdentity>
}

/**
 * The persistence the application services use. Every part is optional at start-up so that the application boots WITHOUT C3's implementation (the current release candidate
 * keeps working); a call that needs a missing part answers `501 ORG_PERSISTENCE_NOT_AVAILABLE`, always AFTER authentication and authorization (nothing leaks to a caller
 * who may not use the API).
 */
@Component
class OrganizationRepositories(
    private val typeRepo: ObjectProvider<OrganizationUnitTypeRepository>, private val unitRepo: ObjectProvider<OrganizationUnitRepository>,
    private val profileRepo: ObjectProvider<EmployeeProfileRepository>, private val membershipRepo: ObjectProvider<EmployeeOrganizationMembershipRepository>,
    private val positionRepo: ObjectProvider<PositionRepository>, private val gradeRepo: ObjectProvider<GradeRepository>,
    private val employeePositionRepo: ObjectProvider<EmployeePositionRepository>, private val lockProvider: ObjectProvider<TenantStructureLock>
) {
    companion object {
        const val NOT_AVAILABLE = "ORG_PERSISTENCE_NOT_AVAILABLE"
        fun notAvailable() = ApiException(HttpStatus.NOT_IMPLEMENTED, NOT_AVAILABLE, "The organization store is not available in this deployment")
    }
    val types: OrganizationUnitTypeRepository get() = typeRepo.getIfAvailable() ?: throw notAvailable()
    val units: OrganizationUnitRepository get() = unitRepo.getIfAvailable() ?: throw notAvailable()
    val profiles: EmployeeProfileRepository get() = profileRepo.getIfAvailable() ?: throw notAvailable()
    val memberships: EmployeeOrganizationMembershipRepository get() = membershipRepo.getIfAvailable() ?: throw notAvailable()
    val positions: PositionRepository get() = positionRepo.getIfAvailable() ?: throw notAvailable()
    val grades: GradeRepository get() = gradeRepo.getIfAvailable() ?: throw notAvailable()
    val employeePositions: EmployeePositionRepository get() = employeePositionRepo.getIfAvailable() ?: throw notAvailable()
    val lock: TenantStructureLock get() = lockProvider.getIfAvailable() ?: throw notAvailable()
}
