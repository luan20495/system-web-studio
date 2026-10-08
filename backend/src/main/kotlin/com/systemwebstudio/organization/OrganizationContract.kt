package com.systemwebstudio.organization

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/*
 * C1 · H-C1-17 — dynamic organization contract (architecture source of truth: D-C0-43).
 *
 * C1 OWNS: the API / authorization contract, tenant isolation, permission semantics, audit semantics, the company bootstrap orchestration, and the seams below.
 * C3 OWNS: the persistence of these records (schema, migrations, SQL). Nothing in this package touches an organization table: the application services talk to the
 * repository interfaces of OrganizationRepositories.kt only. Until C3 provides them the routes answer 501 ORG_PERSISTENCE_NOT_AVAILABLE (after authorization).
 *
 * The same data classes are the API DTOs and the records the seams exchange (they carry `tenantId`; a body never does). Everything is tenant-scoped by construction:
 * every repository method takes the tenant FIRST and a record of another tenant is indistinguishable from a missing one (null / empty).
 */

// ---------------------------------------------------------------------------------------------------------------------------- unit types
/**
 * Optional placement rules of a unit type. EVERY field absent (null) means "no constraint": a type without rules can sit anywhere, so rules never block a hierarchy by absence.
 * - [allowedParentTypeIds]: non-null = a unit of this type may only be placed under a parent whose TYPE is listed ([] = under no parent type, i.e. root only).
 * - [allowedChildTypeIds]: non-null = only units of the listed types may be placed under a unit of this type ([] = this type is a leaf).
 * - [allowRoot]: may a unit of this type be a root? null = yes when [allowedParentTypeIds] is null, no when it is a non-empty list, yes when it is [].
 * - [maxDepth]: the deepest level (root = 1) at which a unit of this type may sit; checked for the unit and for every descendant of a moved subtree.
 */
data class OrgUnitTypeRules(
    val allowedParentTypeIds: List<UUID>? = null, val allowedChildTypeIds: List<UUID>? = null, val allowRoot: Boolean? = null, val maxDepth: Int? = null
)

data class OrganizationUnitTypeDto(
    val id: UUID, val tenantId: UUID, val name: String, val code: String, val icon: String?, val active: Boolean,
    val rules: OrgUnitTypeRules, val version: Long, val createdAt: Instant, val updatedAt: Instant
)

data class OrgUnitTypeCreateRequest(val name: String? = null, val code: String? = null, val icon: String? = null, val rules: OrgUnitTypeRules? = null)
/** `rules` present REPLACES the whole rules object; absent = unchanged. The code never changes. */
data class OrgUnitTypeUpdateRequest(val name: String? = null, val icon: String? = null, val rules: OrgUnitTypeRules? = null, val expectedVersion: Long? = null)

// ---------------------------------------------------------------------------------------------------------------------------- units
/** `active=false` means ARCHIVED (soft). There is no hard delete in the tenant admin API. */
data class OrganizationUnitDto(
    val id: UUID, val tenantId: UUID, val typeId: UUID, val parentId: UUID?, val name: String, val code: String, val sortOrder: Int,
    val metadata: JsonNode, val active: Boolean, val version: Long, val createdAt: Instant, val updatedAt: Instant,
    /** lifecycle: ACTIVE = (`active=true`, `archivedAt=null`); ARCHIVED = (`active=false`, `archivedAt` set). Always consistent; maps to C3's `deleted_at`. */
    val archivedAt: Instant? = null
)
data class OrganizationUnitNodeDto(val unit: OrganizationUnitDto, val children: List<OrganizationUnitNodeDto>)
data class OrganizationUnitDetailDto(val unit: OrganizationUnitDto, val path: List<OrganizationUnitDto>, val activeChildCount: Int, val activeMemberCount: Int)

data class OrgUnitCreateRequest(val typeId: UUID? = null, val parentId: UUID? = null, val name: String? = null, val code: String? = null, val sortOrder: Int? = null, val metadata: JsonNode? = null)
/** The type is immutable and the parent changes ONLY through the move command. */
data class OrgUnitUpdateRequest(val name: String? = null, val code: String? = null, val sortOrder: Int? = null, val metadata: JsonNode? = null, val expectedVersion: Long? = null)
data class OrgUnitMoveRequest(val newParentId: UUID?, val expectedVersion: Long, val sortOrder: Int? = null)
data class VersionRequest(val expectedVersion: Long? = null)

/** one node of a subtree: the unit itself is `relativeDepth` 0 */
data class SubtreeNode(val id: UUID, val typeId: UUID, val relativeDepth: Int, val active: Boolean)

// ---------------------------------------------------------------------------------------------------------------------------- employees
/**
 * An employee is a TENANT MEMBER: `tenant_members JOIN users` (identity, credentials, activation, tenant role: C1) plus the organization memberships and position assignments
 * (C3). There is NO employee profile entity in V1 (employeeCode / phone / joinedOn are NOT part of the contract; a future HR extension needs its own approval after V32).
 * `active` is `tenant_members.active`: disabling an employee is the canonical tenant-membership lifecycle, nothing is duplicated.
 */
data class EmployeeDto(
    val userId: UUID, val tenantId: UUID, val username: String, val displayName: String?, val email: String?,
    /** `tenant_members.active` */
    val active: Boolean, val accountEnabled: Boolean, val accountActivated: Boolean, val tenantRole: String,
    /** derived from the active primary membership: never written directly */
    val primaryOrganizationUnitId: UUID?,
    val positions: List<EmployeePositionDto>, val organizationMemberships: List<OrganizationMembershipDto>
)
data class EmployeePageDto(val items: List<EmployeeDto>, val total: Long, val page: Int, val size: Int)

/** a membership of the create request; the positions are held WITHIN it (nested: a position is never scoped by a free unit id) */
data class EmployeeMembershipInput(val organizationUnitId: UUID? = null, val relationType: String? = null, val primary: Boolean? = null, val positions: List<EmployeePositionInput>? = null)
data class EmployeePositionInput(val positionId: UUID? = null, val gradeId: UUID? = null, val primary: Boolean? = null)

/** Create = a NEW account provisioned by the canonical tenant provisioning (activation link returned, no password) plus optional memberships, each with its positions, in one transaction. */
data class EmployeeCreateRequest(
    val username: String? = null, val displayName: String? = null, val email: String? = null, val tenantRole: String? = null, val workspaceId: UUID? = null, val workspaceRole: String? = null,
    val organizationMemberships: List<EmployeeMembershipInput>? = null
)
data class EmployeeCreatedDto(val employee: EmployeeDto, val activation: com.systemwebstudio.identity.ActivationLink?)

/** Relation types are BUSINESS data (MEMBER, MANAGER, HEAD, ...: a tenant vocabulary): no authorization is ever derived from them. */
data class OrganizationMembershipDto(
    val id: UUID, val tenantId: UUID, val userId: UUID, val organizationUnitId: UUID, val relationType: String, val primary: Boolean, val active: Boolean,
    val version: Long, val createdAt: Instant, val updatedAt: Instant
)
data class MembershipCreateRequest(val organizationUnitId: UUID? = null, val relationType: String? = null, val primary: Boolean? = null)
data class MembershipUpdateRequest(val relationType: String? = null, val primary: Boolean? = null, val expectedVersion: Long? = null)

// ---------------------------------------------------------------------------------------------------------------------------- position / grade (separate taxonomies, NOT organization units, NO permission)
data class PositionDto(val id: UUID, val tenantId: UUID, val name: String, val code: String, val description: String?, val active: Boolean, val version: Long, val createdAt: Instant, val updatedAt: Instant)
data class GradeDto(val id: UUID, val tenantId: UUID, val name: String, val code: String, val rank: Int?, val description: String?, val active: Boolean, val version: Long, val createdAt: Instant, val updatedAt: Instant)
data class PositionCreateRequest(val name: String? = null, val code: String? = null, val description: String? = null)
data class PositionUpdateRequest(val name: String? = null, val description: String? = null, val expectedVersion: Long? = null)
data class GradeCreateRequest(val name: String? = null, val code: String? = null, val rank: Int? = null, val description: String? = null)
data class GradeUpdateRequest(val name: String? = null, val rank: Int? = null, val description: String? = null, val clearRank: Boolean? = null, val expectedVersion: Long? = null)

/**
 * A position held by an employee WITHIN one of their organization memberships (D-C0-43: EmployeePosition -> EmployeeOrganizationUnit -> OrganizationUnit). There is no
 * tenant-global position. `organizationUnitId` is the unit of the membership (derived, always equal to it). The grade is an attribute of the assignment.
 */
data class EmployeePositionDto(
    val id: UUID, val tenantId: UUID, val userId: UUID, val membershipId: UUID, val organizationUnitId: UUID, val positionId: UUID, val gradeId: UUID?, val primary: Boolean, val active: Boolean,
    val version: Long, val createdAt: Instant, val updatedAt: Instant
)
data class EmployeePositionCreateRequest(val membershipId: UUID? = null, val positionId: UUID? = null, val gradeId: UUID? = null, val primary: Boolean? = null)
data class EmployeePositionUpdateRequest(val gradeId: UUID? = null, val clearGrade: Boolean? = null, val primary: Boolean? = null, val expectedVersion: Long? = null)

// ---------------------------------------------------------------------------------------------------------------------------- search criteria handed to C3
/** Employee directory query. `unitIds` / `positionId` / `gradeId` are already resolved and tenant-validated by C1; text matching on identity fields uses [TenantIdentityDirectory]. */
data class EmployeeSearch(
    val text: String?, val unitIds: Set<UUID>?, val positionId: UUID?, val gradeId: UUID?, val active: Boolean?, val userId: UUID?,
    val sort: String, val ascending: Boolean, val page: Int, val size: Int
)
data class Slice<T>(val items: List<T>, val total: Long)
