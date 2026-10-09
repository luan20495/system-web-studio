package com.systemwebstudio.organization

import com.systemwebstudio.common.ApiException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.util.UUID

/*
 * C1 → C3 SEAMS. Everything the organization / employee application services need from persistence, and nothing about how it is stored.
 *
 * Rules every implementation MUST honour (C3 verifies them with `OrganizationRepositoryContractKit`, the abstract conformance test in the C1 test sources, `backend/src/test/kotlin/com/systemwebstudio/organization/`):
 *  1. TENANT FIRST. Every method takes the tenant id as its first argument and filters by it. A record of another tenant is indistinguishable from a missing one:
 *     `find*` answers null, lists omit it, a counter ignores it. The tenant id of a record NEVER comes from a request body: the service passes the one it derived.
 *  2. VERSIONED WRITES. Every `update` / `setActive` / `move` / `setPrimary` / `end` takes `expectedVersion` and applies only if the stored version equals it, in ONE atomic
 *     statement, then increments the version and refreshes `updatedAt`. It answers null when nothing was applied (stale version OR missing); the service then re-reads to tell
 *     404 from 409 VERSION_CONFLICT. No lost update, ever.
 *  3. UNIQUENESS is enforced by the store, not only by a pre-check: a violation is thrown as [DuplicateOrganizationKey] (the service maps it to 409 and the surrounding
 *     transaction rolls back). Keys: type `code` (per tenant, even when inactive); position / grade `code` (per tenant, case-insensitive, even when inactive); unit `code`
 *     SIBLING-SCOPED (D-C0-43): unique among the NON-ARCHIVED units with the same `(tenant, parentId)`, roots (`parentId = null`) being siblings of each other; the code is
 *     already CANONICAL when it reaches the store (trimmed, upper-cased, `Locale.ROOT`) so the store compares it exactly; archiving frees the code; `membership` (one ACTIVE
 *     membership per user and unit); `assignment` (one ACTIVE position assignment per membership and position: the grade is an ATTRIBUTE, never part of the identity).
 *  4. ONE TRANSACTION. Implementations take part in the AMBIENT Spring transaction (no REQUIRES_NEW, no second connection): employee creation provisions the account (C1 tables)
 *     and the memberships / positions (C3 tables) atomically, and an audit failure rolls the mutation back.
 *  4b. LOCKING. The tenant STRUCTURAL lock ([TenantStructuralLock]) is taken ONLY by the STRUCTURAL operations (D-C0-52): unit create, subtree move, unit restore and a unit-type RULE change - every mutation that can change the effective validity of the tree (depth, type rules). Every other write (update / archive of a unit, employees,
 *     memberships, position assignments, catalogs) uses the ordinary transaction, FK / unique constraints, row locks (e.g. `FOR SHARE` on the parent or unit row an insert depends
 *     on, `FOR UPDATE` on the row an archive reads) and the optimistic version: they never serialise on the tenant structural lock.
 *  4c. RACE-SAFE REFERENCES (the STORE is the authority, the service's pre-checks only give friendly errors in the contract order). Under READ COMMITTED a count made by the
 *     service and a write made afterwards can interleave with a concurrent writer, so the invariants below are enforced INSIDE the repository call, atomically, with typed refusals:
 *       - `units.setActive(false)`: lock the unit row FOR UPDATE, count ACTIVE child units and ACTIVE memberships, throw [OrganizationUnitInUse] when any exists, else apply;
 *       - `memberships.end`: lock the membership row FOR UPDATE, count ACTIVE position assignments held in it, throw [MembershipHasPositions] when any, else apply;
 *       - every insert / move / restore that depends on a row being ACTIVE (`units.insert` and `units.move` -> parent / destination unit, `units.setActive(true)` -> parent unit,
 *         `memberships.insert` -> the unit, `employeePositions.insert` -> the membership) locks that row FOR SHARE and throws [ReferencedRowInactive] (`kind` = "unit" | "membership")
 *         when it is archived / ended, so an active record can never appear under an archived / ended one;
 *       - one ACTIVE primary per (tenant, user) for memberships and for position assignments is guaranteed by a PARTIAL UNIQUE INDEX (tenant, user) WHERE primary AND active, or by
 *         locking all of the user's rows first; a violation is [DuplicateOrganizationKey] with key `primary`. A `setPrimary` race must never leave two primaries.
 *     Known, documented limit: a type `maxDepth` is validated by the service without the structural lock on create / restore; C3 SHOULD re-check the resulting depth inside `insert`
 *     / `setActive(true)` under the parent row lock (a concurrent move of an ancestor could otherwise push a new unit past its type's `maxDepth` in a rare race).
 *  5. DETERMINISM. Lists have a total order; the services re-sort anyway where the contract fixes one.
 *  6. NO CASCADE. Nothing is deleted behind the caller's back: archive / end are state changes of the row itself.
 */

/** thrown by a repository when a uniqueness rule (see above) is violated */
class DuplicateOrganizationKey(val key: String) : RuntimeException("duplicate organization key: $key")

/** thrown by `units.setActive(false)` when the unit still has ACTIVE child units / ACTIVE memberships (checked atomically under the row lock) */
class OrganizationUnitInUse(val activeChildren: Int, val activeMembers: Int) : RuntimeException("organization unit in use")
/** thrown by `memberships.end` when ACTIVE position assignments are still held in the membership (checked atomically under the row lock) */
class MembershipHasPositions(val activePositions: Int) : RuntimeException("membership has active positions")
/** thrown when a row the write depends on is archived / ended (`kind` = "unit" | "membership"), checked under a FOR SHARE lock of that row */
class ReferencedRowInactive(val kind: String) : RuntimeException("referenced $kind is not active")

/** thrown by [OrganizationUnitRepository.move] when the destination is the unit itself or one of its descendants (the store re-checks it atomically, whatever the service pre-checked) */
class OrganizationCycle : RuntimeException("organization cycle")

/**
 * The tenant STRUCTURAL lock (PostgreSQL: `pg_advisory_xact_lock` on the tenant). Used ONLY by the structural operations (unit create, subtree move, unit restore, unit-type rule change), whose cycle / depth / rule validation reads a tree that a
 * concurrent structural operation could change (D-C0-52: a descendant created or moved while an ancestor moves must never end deeper than maxDepth). `acquire` blocks until the lock is free and holds it until the AMBIENT TRANSACTION ends (commit or rollback); it is re-entrant inside one
 * transaction and fails with [IllegalStateException] when no transaction is active. The contract execution boundary of a move is ONE READ COMMITTED transaction:
 * `acquire` -> resolve source / destination -> recursive cycle / depth / rule validation -> `move` (expectedVersion CAS + parent UPDATE) -> audit -> commit.
 */
/** employee counts per unit; ONE statement for any set of units. Optional: a store without it leaves the count fields null. */
interface OrganizationEmployeeCounts {
    fun countsFor(tenantId: UUID, unitIds: Collection<UUID>): List<UnitEmployeeCounts>
    fun countsForAll(tenantId: UUID): List<UnitEmployeeCounts>
}

interface TenantStructuralLock {
    fun acquire(tenantId: UUID)
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
    /** key `code` (sibling-scoped, see rule 3) */
    fun insert(unit: OrganizationUnitDto): OrganizationUnitDto
    /** writes name, code, sortOrder, metadata ONLY (never parent, type or active); key `code` (among the siblings of the unit) */
    fun update(unit: OrganizationUnitDto, expectedVersion: Long): OrganizationUnitDto?
    /**
     * ATOMIC: sets the parent (null = root) and optionally the sortOrder of the unit; its whole subtree follows because only the unit's own parent changes.
     * The caller holds the [TenantStructuralLock]. The store MUST still refuse a cycle itself (recursive check in the same statement / transaction): [OrganizationCycle];
     * a sibling of the destination with the same code: key `code`; stale version / missing: null.
     */
    fun move(tenantId: UUID, id: UUID, newParentId: UUID?, sortOrder: Int?, expectedVersion: Long): OrganizationUnitDto?
    /**
     * archive (false: sets `archivedAt`, active=false; atomically refuses with [OrganizationUnitInUse] while ACTIVE children / memberships exist) / restore (true: clears `archivedAt`,
     * active=true; [ReferencedRowInactive] when the parent is archived); key `code` when a restore finds a sibling with the code
     */
    fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long): OrganizationUnitDto?
    /** the unit (relativeDepth 0) and EVERY descendant, whatever its state; empty when the unit is not in the tenant */
    fun subtree(tenantId: UUID, id: UUID): List<SubtreeNode>
    /** root = 1; null when the unit is not in the tenant */
    fun depthOf(tenantId: UUID, id: UUID): Int?
    fun activeChildCount(tenantId: UUID, id: UUID): Int
}

/**
 * The employee DIRECTORY. There is NO employee profile entity in V1 (frozen C0 decision EMPLOYEE_PROFILE = NOT_NEEDED): an employee is a tenant member, i.e.
 * `tenant_members JOIN users`, plus their organization memberships and position assignments. Enabled / disabled is `tenant_members.active`.
 */
interface EmployeeDirectoryRepository {
    /**
     * One page of user ids of the directory, already filtered / sorted / paginated. Candidates are the (active or inactive) members of the tenant. `sort` is `name` | `username`
     * (the C1 identity: a C3 implementation joins `users` / `tenant_members` READ-ONLY, or uses [identities]); ties are broken by userId. Text matches username, display name and
     * e-mail, case-insensitively and LITERALLY (`%`, `_`, `\` are text). `active` filters `tenant_members.active`. `unitIds` = users with an ACTIVE membership in any of them;
     * `positionId` / `gradeId` = users with an ACTIVE position assignment of it. A user matching through several memberships is still ONE row; `total` is the filtered count.
     */
    fun search(tenantId: UUID, criteria: EmployeeSearch, identities: TenantIdentityDirectory): Slice<UUID>
}

interface EmployeeOrganizationMembershipRepository {
    fun list(tenantId: UUID, userId: UUID, includeInactive: Boolean): List<OrganizationMembershipDto>
    /** one query for a page of the directory (no N+1); unordered, the service sorts */
    fun listForUsers(tenantId: UUID, userIds: Collection<UUID>, includeInactive: Boolean): List<OrganizationMembershipDto>
    fun find(tenantId: UUID, userId: UUID, membershipId: UUID): OrganizationMembershipDto?
    /** key `membership`. The user must be a member of the tenant (composite FK `tenant_members(tenant_id, user_id)`). At most ONE active primary per tenant and user (see [setPrimary]). */
    fun insert(membership: OrganizationMembershipDto): OrganizationMembershipDto
    /** writes relationType only */
    fun update(membership: OrganizationMembershipDto, expectedVersion: Long): OrganizationMembershipDto?
    /** ATOMIC: clears the previous primary of the user and sets this one, in one statement / one critical section */
    fun setPrimary(tenantId: UUID, userId: UUID, membershipId: UUID, expectedVersion: Long): OrganizationMembershipDto?
    /** ends the membership: active=false, primary=false (the row stays, for history); atomically refuses with [MembershipHasPositions] while ACTIVE position assignments are held in it */
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
    /** key `assignment` (one ACTIVE assignment per membership and position). The membership must belong to the same tenant and user (composite FK); `organizationUnitId` is the unit of that membership. */
    fun insert(assignment: EmployeePositionDto): EmployeePositionDto
    /** writes `gradeId` only (the grade is an attribute of the assignment); the position and the membership are immutable */
    fun update(assignment: EmployeePositionDto, expectedVersion: Long): EmployeePositionDto?
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
    private val directoryRepo: ObjectProvider<EmployeeDirectoryRepository>, private val membershipRepo: ObjectProvider<EmployeeOrganizationMembershipRepository>,
    private val positionRepo: ObjectProvider<PositionRepository>, private val gradeRepo: ObjectProvider<GradeRepository>,
    private val employeePositionRepo: ObjectProvider<EmployeePositionRepository>, private val lockProvider: ObjectProvider<TenantStructuralLock>,
    private val countsProvider: ObjectProvider<OrganizationEmployeeCounts>
) {
    companion object {
        const val NOT_AVAILABLE = "ORG_PERSISTENCE_NOT_AVAILABLE"
        fun notAvailable() = ApiException(HttpStatus.NOT_IMPLEMENTED, NOT_AVAILABLE, "The organization store is not available in this deployment")
    }
    val types: OrganizationUnitTypeRepository get() = typeRepo.getIfAvailable() ?: throw notAvailable()
    val units: OrganizationUnitRepository get() = unitRepo.getIfAvailable() ?: throw notAvailable()
    val directory: EmployeeDirectoryRepository get() = directoryRepo.getIfAvailable() ?: throw notAvailable()
    val memberships: EmployeeOrganizationMembershipRepository get() = membershipRepo.getIfAvailable() ?: throw notAvailable()
    val positions: PositionRepository get() = positionRepo.getIfAvailable() ?: throw notAvailable()
    val grades: GradeRepository get() = gradeRepo.getIfAvailable() ?: throw notAvailable()
    val employeePositions: EmployeePositionRepository get() = employeePositionRepo.getIfAvailable() ?: throw notAvailable()
    val counts: OrganizationEmployeeCounts? get() = countsProvider.getIfAvailable()
    val lock: TenantStructuralLock get() = lockProvider.getIfAvailable() ?: throw notAvailable()
}
