package com.systemwebstudio.organization

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.tenancy.TenantService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** shared helpers of the organization application services: version / not-found answers, input checks, audit payloads */
internal object OrgRules {
    val TYPE_CODE = Regex("^[a-z0-9][a-z0-9_-]{0,39}$")
    val UNIT_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$")
    val CATALOG_CODE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")
    val RELATION = Regex("^[A-Z][A-Z0-9_]{0,31}$")
    const val MAX_METADATA_BYTES = 8192
    const val MAX_RULE_IDS = 50
    const val MAX_DEPTH_LIMIT = 100

    fun bad(message: String, code: String = "VALIDATION_FAILED") = ApiException.badRequest(code, message)
    fun need(v: Long?): Long = v ?: throw bad("expectedVersion is required")
    fun text(raw: String?, max: Int, what: String, required: Boolean = true): String? {
        val t = raw?.trim()
        if (t.isNullOrEmpty()) { if (required) throw bad("$what is required (max $max characters)"); return null }
        if (t.length > max) throw bad("$what is limited to $max characters")
        return t
    }
    fun code(raw: String?, rx: Regex, what: String, lower: Boolean = false): String {
        val c = raw?.trim()?.let { if (lower) it.lowercase() else it }.orEmpty()
        if (!rx.matches(c)) throw bad("$what is not valid", "INVALID_CODE")
        return c
    }
    fun conflictOrMissing(currentVersion: Long?, notFound: ApiException): ApiException =
        if (currentVersion == null) notFound else ApiException.conflict("VERSION_CONFLICT", "The record changed since it was read; reload and retry.", mapOf("currentVersion" to currentVersion))
    fun metadata(json: JsonMapper, node: JsonNode?): JsonNode {
        if (node == null || node.isNull) return json.createObjectNode()
        if (!node.isObject) throw bad("metadata must be a JSON object")
        if (json.writeValueAsString(node).toByteArray().size > MAX_METADATA_BYTES) throw bad("metadata is limited to $MAX_METADATA_BYTES bytes")
        return node
    }
    fun active(status: Boolean) = if (status) "enabled" else "disabled"
}

// ================================================================================================================================ unit types
@Service
class OrganizationUnitTypeService(private val repos: OrganizationRepositories, private val audit: AuditService) {
    private fun notFound() = ApiException.notFound("ORG_UNIT_TYPE_NOT_FOUND", "Organization unit type not found")

    fun list(tenantId: UUID, includeInactive: Boolean): List<OrganizationUnitTypeDto> =
        repos.types.list(tenantId, includeInactive).sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    fun get(tenantId: UUID, id: UUID): OrganizationUnitTypeDto = repos.types.find(tenantId, id) ?: throw notFound()

    /** every referenced type must exist in THIS tenant: a foreign / unknown id is the same 404 */
    private fun checkRules(tenantId: UUID, self: UUID?, rules: OrgUnitTypeRules?): OrgUnitTypeRules {
        val r = rules ?: return OrgUnitTypeRules()
        r.maxDepth?.let { if (it < 1 || it > OrgRules.MAX_DEPTH_LIMIT) throw OrgRules.bad("rules.maxDepth must be 1-${OrgRules.MAX_DEPTH_LIMIT}") }
        for (ids in listOf(r.allowedParentTypeIds, r.allowedChildTypeIds)) {
            if (ids == null) continue
            if (ids.size > OrgRules.MAX_RULE_IDS) throw OrgRules.bad("a rule list is limited to ${OrgRules.MAX_RULE_IDS} types")
            val distinct = ids.distinct().filter { it != self }                              // a type may name itself (a Team inside a Team)
            if (repos.types.findAll(tenantId, distinct).size != distinct.size) throw notFound()
        }
        return r.copy(allowedParentTypeIds = r.allowedParentTypeIds?.distinct(), allowedChildTypeIds = r.allowedChildTypeIds?.distinct())
    }

    @Transactional
    fun create(tenantId: UUID, actorId: UUID, r: OrgUnitTypeCreateRequest): OrganizationUnitTypeDto {
        val code = OrgRules.code(r.code, OrgRules.TYPE_CODE, "code", lower = true)
        val name = OrgRules.text(r.name, 120, "name")!!; val icon = OrgRules.text(r.icon, 60, "icon", false)
        val now = Instant.now()
        return repos.lock.withTenantLock(tenantId) {
            val rules = checkRules(tenantId, null, r.rules)
            val created = try {
                repos.types.insert(OrganizationUnitTypeDto(UUID.randomUUID(), tenantId, name, code, icon, true, rules, 0, now, now))
            } catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("ORG_UNIT_TYPE_CODE_TAKEN", "A unit type with this code already exists") }
            audit.record("ORG_UNIT_TYPE_CREATED", "ORG_UNIT_TYPE", created.id, actorId = actorId, newValue = snapshot(created))
            created
        }
    }

    @Transactional
    fun update(tenantId: UUID, id: UUID, actorId: UUID, r: OrgUnitTypeUpdateRequest): OrganizationUnitTypeDto {
        val expected = OrgRules.need(r.expectedVersion)
        return repos.lock.withTenantLock(tenantId) {
            val before = get(tenantId, id)
            val next = before.copy(name = r.name?.let { OrgRules.text(it, 120, "name")!! } ?: before.name, icon = if (r.icon != null) OrgRules.text(r.icon, 60, "icon", false) else before.icon,
                rules = if (r.rules != null) checkRules(tenantId, id, r.rules) else before.rules)
            val after = repos.types.update(next, expected) ?: throw OrgRules.conflictOrMissing(repos.types.find(tenantId, id)?.version, notFound())
            audit.record("ORG_UNIT_TYPE_UPDATED", "ORG_UNIT_TYPE", id, actorId = actorId, oldValue = snapshot(before), newValue = snapshot(after))
            after
        }
    }

    /** a disabled type keeps its existing units untouched and editable; it can no longer be used for a new unit or a restore */
    @Transactional
    fun setActive(tenantId: UUID, id: UUID, actorId: UUID, active: Boolean, expectedVersion: Long?): OrganizationUnitTypeDto {
        val expected = OrgRules.need(expectedVersion)
        return repos.lock.withTenantLock(tenantId) {
            val before = get(tenantId, id)
            val after = repos.types.update(before.copy(active = active), expected) ?: throw OrgRules.conflictOrMissing(repos.types.find(tenantId, id)?.version, notFound())
            audit.record(if (active) "ORG_UNIT_TYPE_ENABLED" else "ORG_UNIT_TYPE_DISABLED", "ORG_UNIT_TYPE", id, actorId = actorId, oldValue = snapshot(before), newValue = snapshot(after))
            after
        }
    }

    private fun snapshot(t: OrganizationUnitTypeDto) = mapOf("tenantId" to t.tenantId, "code" to t.code, "name" to t.name, "active" to t.active, "rules" to t.rules, "version" to t.version)
}

// ================================================================================================================================ units
@Service
class OrganizationUnitService(private val repos: OrganizationRepositories, private val audit: AuditService, private val json: JsonMapper, private val tenants: TenantService) {
    private fun notFound() = ApiException.notFound("ORG_UNIT_NOT_FOUND", "Organization unit not found")
    private fun ruleViolation(reason: String, message: String) = ApiException.conflict("ORG_TYPE_RULE_VIOLATION", message, mapOf("reason" to reason))

    /** the contract order: sortOrder ASC, then name (case-insensitive) ASC, then id ASC */
    private val order = compareBy<OrganizationUnitDto>({ it.sortOrder }, { it.name.lowercase() }, { it.id })

    fun get(tenantId: UUID, id: UUID): OrganizationUnitDto = repos.units.find(tenantId, id) ?: throw notFound()

    fun flat(tenantId: UUID, includeArchived: Boolean): List<OrganizationUnitDto> = repos.units.listAll(tenantId, includeArchived).sortedWith(order)

    /** nested projection built from the flat list: roots first, siblings in contract order; a unit whose parent is hidden (archived) is hidden with it */
    fun tree(tenantId: UUID, includeArchived: Boolean): List<OrganizationUnitNodeDto> {
        val all = flat(tenantId, includeArchived); val byParent = all.groupBy { it.parentId }
        fun build(u: OrganizationUnitDto): OrganizationUnitNodeDto = OrganizationUnitNodeDto(u, byParent[u.id].orEmpty().map(::build))
        return byParent[null].orEmpty().map(::build)
    }

    fun detail(tenantId: UUID, id: UUID): OrganizationUnitDetailDto {
        val unit = get(tenantId, id)
        val path = ArrayDeque<OrganizationUnitDto>(); var cur: OrganizationUnitDto? = unit
        while (cur != null && path.size < OrgRules.MAX_DEPTH_LIMIT * 2) { path.addFirst(cur); cur = cur.parentId?.let { repos.units.find(tenantId, it) } }
        return OrganizationUnitDetailDto(unit, path.toList(), repos.units.activeChildCount(tenantId, id), repos.memberships.activeCountByUnit(tenantId, id))
    }

    /** type rules of the child against its parent (null = root) and the depth the unit would sit at */
    private fun checkPlacement(child: OrganizationUnitTypeDto, parentType: OrganizationUnitTypeDto?, depth: Int) {
        val r = child.rules
        if (parentType == null) {
            val rootOk = r.allowRoot ?: (r.allowedParentTypeIds == null || r.allowedParentTypeIds.isEmpty())
            if (!rootOk) throw ruleViolation("ROOT_NOT_ALLOWED", "Units of type '${child.code}' cannot be a root")
        } else {
            if (r.allowedParentTypeIds != null && parentType.id !in r.allowedParentTypeIds) throw ruleViolation("PARENT_TYPE_NOT_ALLOWED", "Units of type '${child.code}' cannot be placed under type '${parentType.code}'")
            val pc = parentType.rules.allowedChildTypeIds
            if (pc != null && child.id !in pc) throw ruleViolation("CHILD_TYPE_NOT_ALLOWED", "Units of type '${parentType.code}' cannot contain type '${child.code}'")
        }
        r.maxDepth?.let { if (depth > it) throw ruleViolation("MAX_DEPTH", "Units of type '${child.code}' cannot sit deeper than level $it") }
    }

    private fun activeUnit(tenantId: UUID, id: UUID): OrganizationUnitDto =
        get(tenantId, id).also { if (!it.active) throw ApiException.conflict("ORG_UNIT_ARCHIVED", "The organization unit is archived") }

    @Transactional
    fun create(tenantId: UUID, actorId: UUID, r: OrgUnitCreateRequest): OrganizationUnitDto {
        val code = OrgRules.code(r.code, OrgRules.UNIT_CODE, "code"); val name = OrgRules.text(r.name, 160, "name")!!; val meta = OrgRules.metadata(json, r.metadata)
        val typeId = r.typeId ?: throw OrgRules.bad("typeId is required")
        return repos.lock.withTenantLock(tenantId) {
            val type = repos.types.find(tenantId, typeId) ?: throw ApiException.notFound("ORG_UNIT_TYPE_NOT_FOUND", "Organization unit type not found")
            if (!type.active) throw ApiException.conflict("ORG_UNIT_TYPE_DISABLED", "The unit type '${type.code}' is disabled")
            val parent = r.parentId?.let { activeUnit(tenantId, it) }                        // foreign / unknown -> 404; archived -> 409
            val parentType = parent?.let { repos.types.find(tenantId, it.typeId) }
            val depth = if (parent == null) 1 else (repos.units.depthOf(tenantId, parent.id) ?: 1) + 1
            checkPlacement(type, parentType, depth)
            val now = Instant.now()
            val created = try {
                repos.units.insert(OrganizationUnitDto(UUID.randomUUID(), tenantId, typeId, parent?.id, name, code, r.sortOrder ?: 0, meta, true, 0, now, now))
            } catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("ORG_UNIT_CODE_TAKEN", "A unit with this code already exists in the company") }
            audit.record("ORG_UNIT_CREATED", "ORG_UNIT", created.id, actorId = actorId, newValue = snapshot(created))
            created
        }
    }

    @Transactional
    fun update(tenantId: UUID, id: UUID, actorId: UUID, r: OrgUnitUpdateRequest): OrganizationUnitDto {
        val expected = OrgRules.need(r.expectedVersion)
        return repos.lock.withTenantLock(tenantId) {
            val before = get(tenantId, id)
            if (!before.active) throw ApiException.conflict("ORG_UNIT_ARCHIVED", "An archived unit cannot be changed; restore it first")
            val next = before.copy(name = r.name?.let { OrgRules.text(it, 160, "name")!! } ?: before.name, code = r.code?.let { OrgRules.code(it, OrgRules.UNIT_CODE, "code") } ?: before.code,
                sortOrder = r.sortOrder ?: before.sortOrder, metadata = r.metadata?.let { OrgRules.metadata(json, it) } ?: before.metadata)
            val after = try { repos.units.update(next, expected) } catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("ORG_UNIT_CODE_TAKEN", "A unit with this code already exists in the company") }
                ?: throw OrgRules.conflictOrMissing(repos.units.find(tenantId, id)?.version, notFound())
            audit.record("ORG_UNIT_UPDATED", "ORG_UNIT", id, actorId = actorId, oldValue = snapshot(before), newValue = snapshot(after))
            after
        }
    }

    /**
     * Move a unit with its whole subtree (atomic: only the unit's own parent changes). Order: source (404) -> source active -> destination (404, foreign = unknown) -> destination
     * active -> not itself, not inside its own subtree (ORG_CYCLE) -> type rules and depth for the unit AND every descendant -> versioned write.
     */
    @Transactional
    fun move(tenantId: UUID, id: UUID, actorId: UUID, newParentId: UUID?, sortOrder: Int?, expectedVersion: Long): OrganizationUnitDto =
        repos.lock.withTenantLock(tenantId) {
            val source = get(tenantId, id)
            if (!source.active) throw ApiException.conflict("ORG_UNIT_ARCHIVED", "An archived unit cannot be moved")
            val dest = newParentId?.let { activeUnit(tenantId, it) }
            val subtree = repos.units.subtree(tenantId, id)
            if (dest != null && (dest.id == id || subtree.any { it.id == dest.id })) throw ApiException.conflict("ORG_CYCLE", "A unit cannot be moved below itself or one of its descendants")
            val types = repos.types.findAll(tenantId, subtree.map { it.typeId }.toSet() + listOfNotNull(dest?.typeId)).associateBy { it.id }
            val baseDepth = if (dest == null) 1 else (repos.units.depthOf(tenantId, dest.id) ?: 1) + 1
            val sourceType = types[source.typeId] ?: throw ApiException.notFound("ORG_UNIT_TYPE_NOT_FOUND", "Organization unit type not found")
            checkPlacement(sourceType, dest?.let { types[it.typeId] }, baseDepth)
            for (n in subtree) types[n.typeId]?.rules?.maxDepth?.let { if (baseDepth + n.relativeDepth > it) throw ruleViolation("MAX_DEPTH", "The move would put a '${types[n.typeId]!!.code}' unit deeper than level $it") }
            val after = repos.units.move(tenantId, id, dest?.id, sortOrder, expectedVersion) ?: throw OrgRules.conflictOrMissing(repos.units.find(tenantId, id)?.version, notFound())
            audit.record("ORG_UNIT_MOVED", "ORG_UNIT", id, actorId = actorId,
                oldValue = mapOf("tenantId" to tenantId, "parentId" to source.parentId, "sortOrder" to source.sortOrder, "version" to source.version),
                newValue = mapOf("tenantId" to tenantId, "parentId" to after.parentId, "sortOrder" to after.sortOrder, "version" to after.version, "subtreeSize" to subtree.size))
            after
        }

    /** Soft archive. Refused while an ACTIVE child unit or an ACTIVE employee membership remains: nothing cascades silently. */
    @Transactional
    fun archive(tenantId: UUID, id: UUID, actorId: UUID, expectedVersion: Long?): OrganizationUnitDto {
        val expected = OrgRules.need(expectedVersion)
        return repos.lock.withTenantLock(tenantId) {
            val before = get(tenantId, id)
            if (!before.active) throw ApiException.conflict("ORG_UNIT_ARCHIVED", "The unit is already archived")
            val children = repos.units.activeChildCount(tenantId, id)
            if (children > 0) throw ApiException.conflict("ORG_UNIT_HAS_CHILDREN", "Archive or move the child units first", mapOf("activeChildCount" to children))
            val members = repos.memberships.activeCountByUnit(tenantId, id)
            if (members > 0) throw ApiException.conflict("ORG_UNIT_HAS_MEMBERS", "Remove the members of this unit first", mapOf("activeMemberCount" to members))
            val after = repos.units.setActive(tenantId, id, false, expected) ?: throw OrgRules.conflictOrMissing(repos.units.find(tenantId, id)?.version, notFound())
            audit.record("ORG_UNIT_ARCHIVED", "ORG_UNIT", id, actorId = actorId, oldValue = snapshot(before), newValue = snapshot(after))
            after
        }
    }

    /** Restore: the unit's own parent is still active, its type is active and its placement rules hold, its code is still free, and the tenant is active; otherwise RESTORE_CONFLICT. */
    @Transactional
    fun restore(tenantId: UUID, id: UUID, actorId: UUID, expectedVersion: Long?): OrganizationUnitDto {
        val expected = OrgRules.need(expectedVersion)
        fun conflict(reason: String, message: String) = ApiException.conflict("RESTORE_CONFLICT", message, mapOf("reason" to reason))
        return repos.lock.withTenantLock(tenantId) {
            val before = get(tenantId, id)
            if (before.active) throw conflict("NOT_ARCHIVED", "The unit is not archived")
            if (tenants.get(tenantId).status != "ACTIVE") throw conflict("TENANT_INACTIVE", "The company is not active")
            val type = repos.types.find(tenantId, before.typeId)?.takeIf { it.active } ?: throw conflict("TYPE_DISABLED", "The unit type is disabled or gone")
            val parent = before.parentId?.let { repos.units.find(tenantId, it) }
            if (before.parentId != null && (parent == null || !parent.active)) throw conflict("PARENT_ARCHIVED", "Restore the parent unit first")
            val depth = if (parent == null) 1 else (repos.units.depthOf(tenantId, parent.id) ?: 1) + 1
            try { checkPlacement(type, parent?.let { repos.types.find(tenantId, it.typeId) }, depth) }
            catch (e: ApiException) { throw conflict("TYPE_RULE", "The unit no longer satisfies its type rules") }
            val after = try { repos.units.setActive(tenantId, id, true, expected) }
            catch (e: DuplicateOrganizationKey) { throw conflict("CODE_TAKEN", "Another active unit uses this code; rename one of them first") }
                ?: throw OrgRules.conflictOrMissing(repos.units.find(tenantId, id)?.version, notFound())
            audit.record("ORG_UNIT_RESTORED", "ORG_UNIT", id, actorId = actorId, oldValue = snapshot(before), newValue = snapshot(after))
            after
        }
    }

    private fun snapshot(u: OrganizationUnitDto) = mapOf("tenantId" to u.tenantId, "typeId" to u.typeId, "parentId" to u.parentId, "code" to u.code, "name" to u.name,
        "sortOrder" to u.sortOrder, "active" to u.active, "version" to u.version)
}

// ================================================================================================================================ position / grade catalogs
@Service
class PositionGradeService(private val repos: OrganizationRepositories, private val audit: AuditService) {
    private fun noPosition() = ApiException.notFound("POSITION_NOT_FOUND", "Position not found")
    private fun noGrade() = ApiException.notFound("GRADE_NOT_FOUND", "Grade not found")

    fun listPositions(tenantId: UUID, includeInactive: Boolean) = repos.positions.list(tenantId, includeInactive).sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    fun getPosition(tenantId: UUID, id: UUID): PositionDto = repos.positions.find(tenantId, id) ?: throw noPosition()
    /** ordered by rank (unranked last), then name, then id */
    fun listGrades(tenantId: UUID, includeInactive: Boolean) = repos.grades.list(tenantId, includeInactive).sortedWith(compareBy<GradeDto>({ it.rank ?: Int.MAX_VALUE }, { it.name.lowercase() }, { it.id }))
    fun getGrade(tenantId: UUID, id: UUID): GradeDto = repos.grades.find(tenantId, id) ?: throw noGrade()

    @Transactional
    fun createPosition(tenantId: UUID, actorId: UUID, r: PositionCreateRequest): PositionDto {
        val code = OrgRules.code(r.code, OrgRules.CATALOG_CODE, "code"); val name = OrgRules.text(r.name, 120, "name")!!; val desc = OrgRules.text(r.description, 500, "description", false); val now = Instant.now()
        val created = try { repos.positions.insert(PositionDto(UUID.randomUUID(), tenantId, name, code, desc, true, 0, now, now)) }
        catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("POSITION_CODE_TAKEN", "A position with this code already exists") }
        audit.record("POSITION_CREATED", "POSITION", created.id, actorId = actorId, newValue = snap(created))
        return created
    }

    @Transactional
    fun updatePosition(tenantId: UUID, id: UUID, actorId: UUID, r: PositionUpdateRequest): PositionDto {
        val expected = OrgRules.need(r.expectedVersion); val before = getPosition(tenantId, id)
        val next = before.copy(name = r.name?.let { OrgRules.text(it, 120, "name")!! } ?: before.name, description = if (r.description != null) OrgRules.text(r.description, 500, "description", false) else before.description)
        val after = repos.positions.update(next, expected) ?: throw OrgRules.conflictOrMissing(repos.positions.find(tenantId, id)?.version, noPosition())
        audit.record("POSITION_UPDATED", "POSITION", id, actorId = actorId, oldValue = snap(before), newValue = snap(after)); return after
    }

    @Transactional
    fun setPositionActive(tenantId: UUID, id: UUID, actorId: UUID, active: Boolean, expectedVersion: Long?): PositionDto {
        val expected = OrgRules.need(expectedVersion); val before = getPosition(tenantId, id)
        val after = repos.positions.setActive(tenantId, id, active, expected) ?: throw OrgRules.conflictOrMissing(repos.positions.find(tenantId, id)?.version, noPosition())
        audit.record(if (active) "POSITION_ENABLED" else "POSITION_DISABLED", "POSITION", id, actorId = actorId, oldValue = snap(before), newValue = snap(after)); return after
    }

    @Transactional
    fun createGrade(tenantId: UUID, actorId: UUID, r: GradeCreateRequest): GradeDto {
        val code = OrgRules.code(r.code, OrgRules.CATALOG_CODE, "code"); val name = OrgRules.text(r.name, 120, "name")!!; val desc = OrgRules.text(r.description, 500, "description", false); val now = Instant.now()
        if (r.rank != null && (r.rank < 0 || r.rank > 10_000)) throw OrgRules.bad("rank must be 0-10000")
        val created = try { repos.grades.insert(GradeDto(UUID.randomUUID(), tenantId, name, code, r.rank, desc, true, 0, now, now)) }
        catch (e: DuplicateOrganizationKey) { throw ApiException.conflict("GRADE_CODE_TAKEN", "A grade with this code already exists") }
        audit.record("GRADE_CREATED", "GRADE", created.id, actorId = actorId, newValue = snap(created)); return created
    }

    @Transactional
    fun updateGrade(tenantId: UUID, id: UUID, actorId: UUID, r: GradeUpdateRequest): GradeDto {
        val expected = OrgRules.need(r.expectedVersion); val before = getGrade(tenantId, id)
        if (r.rank != null && (r.rank < 0 || r.rank > 10_000)) throw OrgRules.bad("rank must be 0-10000")
        val next = before.copy(name = r.name?.let { OrgRules.text(it, 120, "name")!! } ?: before.name, rank = if (r.clearRank == true) null else (r.rank ?: before.rank),
            description = if (r.description != null) OrgRules.text(r.description, 500, "description", false) else before.description)
        val after = repos.grades.update(next, expected) ?: throw OrgRules.conflictOrMissing(repos.grades.find(tenantId, id)?.version, noGrade())
        audit.record("GRADE_UPDATED", "GRADE", id, actorId = actorId, oldValue = snap(before), newValue = snap(after)); return after
    }

    @Transactional
    fun setGradeActive(tenantId: UUID, id: UUID, actorId: UUID, active: Boolean, expectedVersion: Long?): GradeDto {
        val expected = OrgRules.need(expectedVersion); val before = getGrade(tenantId, id)
        val after = repos.grades.setActive(tenantId, id, active, expected) ?: throw OrgRules.conflictOrMissing(repos.grades.find(tenantId, id)?.version, noGrade())
        audit.record(if (active) "GRADE_ENABLED" else "GRADE_DISABLED", "GRADE", id, actorId = actorId, oldValue = snap(before), newValue = snap(after)); return after
    }

    private fun snap(p: PositionDto) = mapOf("tenantId" to p.tenantId, "code" to p.code, "name" to p.name, "active" to p.active, "version" to p.version)
    private fun snap(g: GradeDto) = mapOf("tenantId" to g.tenantId, "code" to g.code, "name" to g.name, "rank" to g.rank, "active" to g.active, "version" to g.version)
}
