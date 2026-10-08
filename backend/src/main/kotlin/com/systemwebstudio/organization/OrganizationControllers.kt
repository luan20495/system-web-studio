package com.systemwebstudio.organization

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.access.TenantAccess
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import java.util.UUID

/*
 * Tenant-scoped organization / employee / position APIs (docs/parallel/c1/organization-employee-contract.md).
 *
 * Authorization sequence of EVERY route: authenticated session (401 otherwise, by the security chain) -> `access.forTenant(user, tenantId)` (the tenant in the PATH is only a resource
 * selector: an unknown or foreign tenant is a safe 404 and a SYSTEM_ADMIN gets only platform scope) -> `require(<capability>)` (403 for a caller of the same tenant who lacks it) ->
 * the application service, whose repository calls are all tenant-first (a foreign resource id is a safe 404). The decision is the PERMISSION, never a role name, and a
 * tenantId in a request body is never read.
 */
internal fun AccessService.org(user: StudioUserDetails, tenantId: UUID, p: Permission): TenantAccess = forTenant(user.userId, tenantId).also { it.require(p) }

private fun <T : Any> versioned(body: T, version: Long, status: HttpStatus = HttpStatus.OK): ResponseEntity<T> = ResponseEntity.status(status).eTag("\"$version\"").body(body)

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/organization-unit-types")
class OrganizationUnitTypeController(private val access: AccessService, private val service: OrganizationUnitTypeService) {
    @GetMapping
    fun list(@PathVariable tenantId: UUID, @RequestParam(defaultValue = "true") includeInactive: Boolean, @AuthenticationPrincipal me: StudioUserDetails): List<OrganizationUnitTypeDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_VIEW); return service.list(tenantId, includeInactive)
    }
    @GetMapping("/{typeId}")
    fun get(@PathVariable tenantId: UUID, @PathVariable typeId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitTypeDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_VIEW); return service.get(tenantId, typeId).let { versioned(it, it.version) }
    }
    @PostMapping
    fun create(@PathVariable tenantId: UUID, @RequestBody r: OrgUnitTypeCreateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitTypeDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE); return service.create(tenantId, me.userId, r).let { versioned(it, it.version, HttpStatus.CREATED) }
    }
    @PatchMapping("/{typeId}")
    fun update(@PathVariable tenantId: UUID, @PathVariable typeId: UUID, @RequestBody r: OrgUnitTypeUpdateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitTypeDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE); return service.update(tenantId, typeId, me.userId, r).let { versioned(it, it.version) }
    }
    @PostMapping("/{typeId}/disable")
    fun disable(@PathVariable tenantId: UUID, @PathVariable typeId: UUID, @RequestBody r: VersionRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitTypeDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE); return service.setActive(tenantId, typeId, me.userId, false, r.expectedVersion).let { versioned(it, it.version) }
    }
    @PostMapping("/{typeId}/enable")
    fun enable(@PathVariable tenantId: UUID, @PathVariable typeId: UUID, @RequestBody r: VersionRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitTypeDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE); return service.setActive(tenantId, typeId, me.userId, true, r.expectedVersion).let { versioned(it, it.version) }
    }
}

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/organization-units")
class OrganizationUnitController(private val access: AccessService, private val service: OrganizationUnitService) {
    /** `format=tree` (default: nested `{unit, children}`) or `format=flat` (the same units with `parentId`, one list); same order in both: sortOrder, name, id */
    @GetMapping
    fun list(@PathVariable tenantId: UUID, @RequestParam(defaultValue = "tree") format: String, @RequestParam(defaultValue = "false") includeArchived: Boolean, @AuthenticationPrincipal me: StudioUserDetails): Any {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_VIEW)
        return when (format) {
            "tree" -> service.tree(tenantId, includeArchived)
            "flat" -> service.flat(tenantId, includeArchived)
            else -> throw OrgRules.bad("format must be tree or flat")
        }
    }
    @GetMapping("/{unitId}")
    fun get(@PathVariable tenantId: UUID, @PathVariable unitId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitDetailDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_VIEW); return service.detail(tenantId, unitId).let { versioned(it, it.unit.version) }
    }
    @PostMapping
    fun create(@PathVariable tenantId: UUID, @RequestBody r: OrgUnitCreateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE); return service.create(tenantId, me.userId, r).let { versioned(it, it.version, HttpStatus.CREATED) }
    }
    @PatchMapping("/{unitId}")
    fun update(@PathVariable tenantId: UUID, @PathVariable unitId: UUID, @RequestBody r: OrgUnitUpdateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE); return service.update(tenantId, unitId, me.userId, r).let { versioned(it, it.version) }
    }

    /** the key `newParentId` MUST be present: a UUID = under that unit, an explicit null = to the root. An absent key is a 400 (never a silent move to the root). */
    @PostMapping("/{unitId}/move")
    fun move(@PathVariable tenantId: UUID, @PathVariable unitId: UUID, @RequestBody body: JsonNode, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE)
        if (!body.isObject || !body.has("newParentId")) throw OrgRules.bad("newParentId is required (a unit id, or null to move to the root)")
        val parent = body.get("newParentId").let { n -> if (n == null || n.isNull) null else runCatching { UUID.fromString(n.asString()) }.getOrNull() ?: throw OrgRules.bad("newParentId must be a UUID or null") }
        val version = body.get("expectedVersion")?.takeIf { it.isNumber }?.asLong() ?: throw OrgRules.bad("expectedVersion is required")
        val sort = body.get("sortOrder")?.takeIf { it.isNumber }?.asInt()
        return service.move(tenantId, unitId, me.userId, parent, sort, version).let { versioned(it, it.version) }
    }
    @PostMapping("/{unitId}/archive")
    fun archive(@PathVariable tenantId: UUID, @PathVariable unitId: UUID, @RequestBody r: VersionRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE); return service.archive(tenantId, unitId, me.userId, r.expectedVersion).let { versioned(it, it.version) }
    }
    @PostMapping("/{unitId}/restore")
    fun restore(@PathVariable tenantId: UUID, @PathVariable unitId: UUID, @RequestBody r: VersionRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationUnitDto> {
        access.org(me, tenantId, Permission.ORG_STRUCTURE_MANAGE); return service.restore(tenantId, unitId, me.userId, r.expectedVersion).let { versioned(it, it.version) }
    }
}

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/positions")
class PositionController(private val access: AccessService, private val service: PositionGradeService) {
    @GetMapping
    fun list(@PathVariable tenantId: UUID, @RequestParam(defaultValue = "true") includeInactive: Boolean, @AuthenticationPrincipal me: StudioUserDetails): List<PositionDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_VIEW); return service.listPositions(tenantId, includeInactive)
    }
    @GetMapping("/{positionId}")
    fun get(@PathVariable tenantId: UUID, @PathVariable positionId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<PositionDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_VIEW); return service.getPosition(tenantId, positionId).let { versioned(it, it.version) }
    }
    @PostMapping
    fun create(@PathVariable tenantId: UUID, @RequestBody r: PositionCreateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<PositionDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_MANAGE); return service.createPosition(tenantId, me.userId, r).let { versioned(it, it.version, HttpStatus.CREATED) }
    }
    @PatchMapping("/{positionId}")
    fun update(@PathVariable tenantId: UUID, @PathVariable positionId: UUID, @RequestBody r: PositionUpdateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<PositionDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_MANAGE); return service.updatePosition(tenantId, positionId, me.userId, r).let { versioned(it, it.version) }
    }
    @PostMapping("/{positionId}/disable")
    fun disable(@PathVariable tenantId: UUID, @PathVariable positionId: UUID, @RequestBody r: VersionRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<PositionDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_MANAGE); return service.setPositionActive(tenantId, positionId, me.userId, false, r.expectedVersion).let { versioned(it, it.version) }
    }
    @PostMapping("/{positionId}/enable")
    fun enable(@PathVariable tenantId: UUID, @PathVariable positionId: UUID, @RequestBody r: VersionRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<PositionDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_MANAGE); return service.setPositionActive(tenantId, positionId, me.userId, true, r.expectedVersion).let { versioned(it, it.version) }
    }
}

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/grades")
class GradeController(private val access: AccessService, private val service: PositionGradeService) {
    @GetMapping
    fun list(@PathVariable tenantId: UUID, @RequestParam(defaultValue = "true") includeInactive: Boolean, @AuthenticationPrincipal me: StudioUserDetails): List<GradeDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_VIEW); return service.listGrades(tenantId, includeInactive)
    }
    @GetMapping("/{gradeId}")
    fun get(@PathVariable tenantId: UUID, @PathVariable gradeId: UUID, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<GradeDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_VIEW); return service.getGrade(tenantId, gradeId).let { versioned(it, it.version) }
    }
    @PostMapping
    fun create(@PathVariable tenantId: UUID, @RequestBody r: GradeCreateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<GradeDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_MANAGE); return service.createGrade(tenantId, me.userId, r).let { versioned(it, it.version, HttpStatus.CREATED) }
    }
    @PatchMapping("/{gradeId}")
    fun update(@PathVariable tenantId: UUID, @PathVariable gradeId: UUID, @RequestBody r: GradeUpdateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<GradeDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_MANAGE); return service.updateGrade(tenantId, gradeId, me.userId, r).let { versioned(it, it.version) }
    }
    @PostMapping("/{gradeId}/disable")
    fun disable(@PathVariable tenantId: UUID, @PathVariable gradeId: UUID, @RequestBody r: VersionRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<GradeDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_MANAGE); return service.setGradeActive(tenantId, gradeId, me.userId, false, r.expectedVersion).let { versioned(it, it.version) }
    }
    @PostMapping("/{gradeId}/enable")
    fun enable(@PathVariable tenantId: UUID, @PathVariable gradeId: UUID, @RequestBody r: VersionRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<GradeDto> {
        access.org(me, tenantId, Permission.POSITION_GRADE_MANAGE); return service.setGradeActive(tenantId, gradeId, me.userId, true, r.expectedVersion).let { versioned(it, it.version) }
    }
}

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/employees")
class EmployeeController(private val access: AccessService, private val service: EmployeeDirectoryService) {
    @GetMapping
    fun list(
        @PathVariable tenantId: UUID, @RequestParam(required = false) q: String?, @RequestParam(required = false) organizationUnitId: UUID?,
        @RequestParam(defaultValue = "true") includeDescendants: Boolean, @RequestParam(required = false) positionId: UUID?, @RequestParam(required = false) gradeId: UUID?,
        @RequestParam(required = false) active: Boolean?, @RequestParam(required = false) userId: UUID?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int, @RequestParam(required = false) sort: String?, @RequestParam(required = false) dir: String?,
        @AuthenticationPrincipal me: StudioUserDetails
    ): EmployeePageDto {
        access.org(me, tenantId, Permission.EMPLOYEE_VIEW)
        return service.list(tenantId, q, organizationUnitId, includeDescendants, positionId, gradeId, active, userId, page, size, sort, dir)
    }
    /** an employee has no version of its own: its state is the tenant membership (`active`) and its memberships / positions carry their versions */
    @GetMapping("/{userId}")
    fun get(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @AuthenticationPrincipal me: StudioUserDetails): EmployeeDto {
        access.org(me, tenantId, Permission.EMPLOYEE_VIEW); return service.get(tenantId, userId)
    }

    /** a NEW account is account provisioning: it needs TENANT_MEMBERS on top of EMPLOYEE_MANAGE (both held by the company's Tenant Admin only) */
    @PostMapping
    fun create(@PathVariable tenantId: UUID, @RequestBody r: EmployeeCreateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<EmployeeCreatedDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE).require(Permission.TENANT_MEMBERS)
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(tenantId, me.userId, r))
    }
    @PostMapping("/{userId}/disable")
    fun disable(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @AuthenticationPrincipal me: StudioUserDetails): EmployeeDto {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE); return service.setActive(tenantId, userId, me.userId, false)
    }
    @PostMapping("/{userId}/enable")
    fun enable(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @AuthenticationPrincipal me: StudioUserDetails): EmployeeDto {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE); return service.setActive(tenantId, userId, me.userId, true)
    }

    // ---- organization memberships (multi-org)
    @GetMapping("/{userId}/organization-memberships")
    fun memberships(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @RequestParam(defaultValue = "false") includeInactive: Boolean, @AuthenticationPrincipal me: StudioUserDetails): List<OrganizationMembershipDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_VIEW); return service.memberships(tenantId, userId, includeInactive)
    }
    @PostMapping("/{userId}/organization-memberships")
    fun addMembership(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @RequestBody r: MembershipCreateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationMembershipDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE); return service.addMembership(tenantId, userId, me.userId, r).let { versioned(it, it.version, HttpStatus.CREATED) }
    }
    @PatchMapping("/{userId}/organization-memberships/{membershipId}")
    fun updateMembership(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @PathVariable membershipId: UUID, @RequestBody r: MembershipUpdateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationMembershipDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE); return service.updateMembership(tenantId, userId, membershipId, me.userId, r).let { versioned(it, it.version) }
    }
    @DeleteMapping("/{userId}/organization-memberships/{membershipId}")
    fun removeMembership(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @PathVariable membershipId: UUID, @RequestParam expectedVersion: Long?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<OrganizationMembershipDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE); return service.removeMembership(tenantId, userId, membershipId, me.userId, expectedVersion).let { versioned(it, it.version) }
    }

    // ---- positions held
    @GetMapping("/{userId}/positions")
    fun positions(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @RequestParam(defaultValue = "false") includeInactive: Boolean, @AuthenticationPrincipal me: StudioUserDetails): List<EmployeePositionDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_VIEW); return service.positions(tenantId, userId, includeInactive)
    }
    @PostMapping("/{userId}/positions")
    fun addPosition(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @RequestBody r: EmployeePositionCreateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<EmployeePositionDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE); return service.addPosition(tenantId, userId, me.userId, r).let { versioned(it, it.version, HttpStatus.CREATED) }
    }
    @PatchMapping("/{userId}/positions/{employeePositionId}")
    fun updatePosition(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @PathVariable employeePositionId: UUID, @RequestBody r: EmployeePositionUpdateRequest, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<EmployeePositionDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE); return service.updatePosition(tenantId, userId, employeePositionId, me.userId, r).let { versioned(it, it.version) }
    }
    @DeleteMapping("/{userId}/positions/{employeePositionId}")
    fun removePosition(@PathVariable tenantId: UUID, @PathVariable userId: UUID, @PathVariable employeePositionId: UUID, @RequestParam expectedVersion: Long?, @AuthenticationPrincipal me: StudioUserDetails): ResponseEntity<EmployeePositionDto> {
        access.org(me, tenantId, Permission.EMPLOYEE_MANAGE); return service.removePosition(tenantId, userId, employeePositionId, me.userId, expectedVersion).let { versioned(it, it.version) }
    }
}
