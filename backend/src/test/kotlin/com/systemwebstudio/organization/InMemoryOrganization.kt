package com.systemwebstudio.organization

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * TEST DOUBLE of the C3 persistence seams (OrganizationRepositories.kt). It is NOT production persistence: it exists so that C1's application services, API contract and
 * authorization can be tested end to end without C3's SQL, and as the reference behaviour that `OrganizationRepositoryContractKit` pins (C3 runs the same kit against its own
 * implementation). It honours the seam rules: tenant first, versioned writes, store-enforced uniqueness, and the AMBIENT TRANSACTION (a rollback restores the state, so the
 * atomicity of "account + profile + memberships" is observable in tests).
 */
class InMemoryOrganization(private val clock: () -> Instant = { Instant.now() }) {
    private class State {
        val types = LinkedHashMap<UUID, OrganizationUnitTypeDto>(); val units = LinkedHashMap<UUID, OrganizationUnitDto>()
        val profiles = LinkedHashMap<Pair<UUID, UUID>, EmployeeProfileRecord>(); val memberships = LinkedHashMap<UUID, OrganizationMembershipDto>()
        val positions = LinkedHashMap<UUID, PositionDto>(); val grades = LinkedHashMap<UUID, GradeDto>(); val assignments = LinkedHashMap<UUID, EmployeePositionDto>()
        fun copy() = State().also { c ->
            c.types.putAll(types); c.units.putAll(units); c.profiles.putAll(profiles); c.memberships.putAll(memberships); c.positions.putAll(positions); c.grades.putAll(grades); c.assignments.putAll(assignments)
        }
    }
    private var state = State()
    private val mutex = ReentrantLock()
    private val tenantLocks = ConcurrentHashMap<UUID, ReentrantLock>()
    private fun now() = clock()
    private fun dup(key: String): Nothing = throw DuplicateOrganizationKey(key)

    /** a read or a write under the global mutex; the first WRITE inside a Spring transaction registers a rollback that restores the snapshot */
    private fun <T> tx(write: Boolean = false, block: State.() -> T): T {
        mutex.lock()
        try {
            if (write && TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.getResource(this) == null) {
                val snapshot = state.copy(); TransactionSynchronizationManager.bindResource(this, snapshot)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        mutex.lock()
                        try { if (status != TransactionSynchronization.STATUS_COMMITTED) state = snapshot } finally { mutex.unlock() }
                        TransactionSynchronizationManager.unbindResourceIfPossible(this@InMemoryOrganization)
                    }
                })
            }
            return state.block()
        } finally { mutex.unlock() }
    }

    val lock = object : TenantStructureLock {
        override fun <T> withTenantLock(tenantId: UUID, block: () -> T): T { val l = tenantLocks.computeIfAbsent(tenantId) { ReentrantLock() }; l.lock(); try { return block() } finally { l.unlock() } }
    }

    val types = object : OrganizationUnitTypeRepository {
        override fun list(tenantId: UUID, includeInactive: Boolean) = tx { types.values.filter { it.tenantId == tenantId && (includeInactive || it.active) } }
        override fun find(tenantId: UUID, id: UUID) = tx { types[id]?.takeIf { it.tenantId == tenantId } }
        override fun findAll(tenantId: UUID, ids: Collection<UUID>) = tx { ids.distinct().mapNotNull { types[it]?.takeIf { t -> t.tenantId == tenantId } } }
        override fun insert(type: OrganizationUnitTypeDto) = tx(true) {
            if (types.values.any { it.tenantId == type.tenantId && it.code.equals(type.code, true) }) dup("code")
            type.copy(version = 0).also { types[it.id] = it }
        }
        override fun update(type: OrganizationUnitTypeDto, expectedVersion: Long) = tx(true) {
            val cur = types[type.id]?.takeIf { it.tenantId == type.tenantId && it.version == expectedVersion } ?: return@tx null
            cur.copy(name = type.name, icon = type.icon, rules = type.rules, active = type.active, version = cur.version + 1, updatedAt = now()).also { types[it.id] = it }
        }
    }

    val units = object : OrganizationUnitRepository {
        override fun find(tenantId: UUID, id: UUID) = tx { units[id]?.takeIf { it.tenantId == tenantId } }
        override fun listAll(tenantId: UUID, includeArchived: Boolean) = tx { units.values.filter { it.tenantId == tenantId && (includeArchived || it.active) } }
        override fun insert(unit: OrganizationUnitDto) = tx(true) {
            check(unit.parentId == null || units[unit.parentId]?.tenantId == unit.tenantId) { "foreign parent" }
            check(types[unit.typeId]?.tenantId == unit.tenantId) { "foreign type" }
            if (units.values.any { it.tenantId == unit.tenantId && it.active && it.code.equals(unit.code, true) }) dup("code")
            unit.copy(version = 0).also { units[it.id] = it }
        }
        override fun update(unit: OrganizationUnitDto, expectedVersion: Long) = tx(true) {
            val cur = units[unit.id]?.takeIf { it.tenantId == unit.tenantId && it.version == expectedVersion } ?: return@tx null
            if (cur.active && units.values.any { it.id != cur.id && it.tenantId == cur.tenantId && it.active && it.code.equals(unit.code, true) }) dup("code")
            cur.copy(name = unit.name, code = unit.code, sortOrder = unit.sortOrder, metadata = unit.metadata, version = cur.version + 1, updatedAt = now()).also { units[it.id] = it }
        }
        override fun move(tenantId: UUID, id: UUID, newParentId: UUID?, sortOrder: Int?, expectedVersion: Long) = tx(true) {
            val cur = units[id]?.takeIf { it.tenantId == tenantId && it.version == expectedVersion } ?: return@tx null
            if (newParentId != null && units[newParentId]?.tenantId != tenantId) return@tx null
            cur.copy(parentId = newParentId, sortOrder = sortOrder ?: cur.sortOrder, version = cur.version + 1, updatedAt = now()).also { units[id] = it }
        }
        override fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long) = tx(true) {
            val cur = units[id]?.takeIf { it.tenantId == tenantId && it.version == expectedVersion } ?: return@tx null
            if (active && units.values.any { it.id != id && it.tenantId == tenantId && it.active && it.code.equals(cur.code, true) }) dup("code")
            cur.copy(active = active, version = cur.version + 1, updatedAt = now()).also { units[id] = it }
        }
        override fun subtree(tenantId: UUID, id: UUID) = tx {
            val root = units[id]?.takeIf { it.tenantId == tenantId } ?: return@tx emptyList<SubtreeNode>()
            val out = ArrayList<SubtreeNode>(); val seen = HashSet<UUID>(); val queue = ArrayDeque<Pair<OrganizationUnitDto, Int>>(); queue.add(root to 0)
            while (queue.isNotEmpty()) {
                val (u, d) = queue.removeFirst(); if (!seen.add(u.id)) continue
                out += SubtreeNode(u.id, u.typeId, d, u.active); units.values.filter { it.tenantId == tenantId && it.parentId == u.id }.forEach { queue.add(it to d + 1) }
            }
            out
        }
        override fun depthOf(tenantId: UUID, id: UUID) = tx {
            var cur = units[id]?.takeIf { it.tenantId == tenantId } ?: return@tx null; var depth = 1
            while (cur.parentId != null && depth < 10_000) { cur = units[cur.parentId!!] ?: break; depth++ }
            depth
        }
        override fun activeChildCount(tenantId: UUID, id: UUID) = tx { units.values.count { it.tenantId == tenantId && it.parentId == id && it.active } }
    }

    val profiles = object : EmployeeProfileRepository {
        override fun find(tenantId: UUID, userId: UUID) = tx { profiles[tenantId to userId] }
        override fun insert(profile: EmployeeProfileRecord) = tx(true) {
            if (profiles.containsKey(profile.tenantId to profile.userId)) dup("employee")
            if (profile.employeeCode != null && profiles.values.any { it.tenantId == profile.tenantId && it.employeeCode.equals(profile.employeeCode, true) }) dup("employeeCode")
            profile.copy(version = 0).also { profiles[it.tenantId to it.userId] = it }
        }
        override fun update(profile: EmployeeProfileRecord, expectedVersion: Long) = tx(true) {
            val cur = profiles[profile.tenantId to profile.userId]?.takeIf { it.version == expectedVersion } ?: return@tx null
            if (profile.employeeCode != null && profiles.values.any { it.userId != cur.userId && it.tenantId == cur.tenantId && it.employeeCode.equals(profile.employeeCode, true) }) dup("employeeCode")
            cur.copy(employeeCode = profile.employeeCode, phone = profile.phone, joinedOn = profile.joinedOn, metadata = profile.metadata, version = cur.version + 1, updatedAt = now()).also { profiles[cur.tenantId to cur.userId] = it }
        }
        override fun setStatus(tenantId: UUID, userId: UUID, status: String, expectedVersion: Long) = tx(true) {
            val cur = profiles[tenantId to userId]?.takeIf { it.version == expectedVersion } ?: return@tx null
            cur.copy(status = status, version = cur.version + 1, updatedAt = now()).also { profiles[tenantId to userId] = it }
        }
        override fun search(tenantId: UUID, criteria: EmployeeSearch, identities: TenantIdentityDirectory) = tx {
            val who = identities.members(tenantId).associateBy { it.userId }; val needle = criteria.text?.lowercase()
            val rows = profiles.values.filter { p ->
                val i = who[p.userId]
                p.tenantId == tenantId && i != null && (criteria.status == null || p.status == criteria.status) && (criteria.userId == null || p.userId == criteria.userId) &&
                    (needle == null || listOf(i.username, i.displayName, i.email, p.employeeCode).any { it?.lowercase()?.contains(needle) == true }) &&
                    (criteria.unitIds == null || memberships.values.any { it.tenantId == tenantId && it.userId == p.userId && it.active && it.organizationUnitId in criteria.unitIds }) &&
                    (criteria.positionId == null || assignments.values.any { it.tenantId == tenantId && it.userId == p.userId && it.active && it.positionId == criteria.positionId }) &&
                    (criteria.gradeId == null || assignments.values.any { it.tenantId == tenantId && it.userId == p.userId && it.active && it.gradeId == criteria.gradeId })
            }
            val key: (EmployeeProfileRecord) -> String = { p -> val i = who[p.userId]!!; when (criteria.sort) {
                "username" -> i.username.lowercase(); "code" -> (p.employeeCode ?: "￿").lowercase(); "created" -> p.createdAt.toString(); else -> (i.displayName ?: i.username).lowercase() } }
            val sorted = rows.sortedWith(if (criteria.ascending) compareBy<EmployeeProfileRecord>(key).thenBy { it.userId } else compareByDescending<EmployeeProfileRecord>(key).thenBy { it.userId })
            Slice(sorted.drop(criteria.page * criteria.size).take(criteria.size), sorted.size.toLong())
        }
    }

    val memberships = object : EmployeeOrganizationMembershipRepository {
        override fun list(tenantId: UUID, userId: UUID, includeInactive: Boolean) = tx { memberships.values.filter { it.tenantId == tenantId && it.userId == userId && (includeInactive || it.active) } }
        override fun listForUsers(tenantId: UUID, userIds: Collection<UUID>, includeInactive: Boolean) = tx { memberships.values.filter { it.tenantId == tenantId && it.userId in userIds && (includeInactive || it.active) } }
        override fun find(tenantId: UUID, userId: UUID, membershipId: UUID) = tx { memberships[membershipId]?.takeIf { it.tenantId == tenantId && it.userId == userId } }
        override fun insert(membership: OrganizationMembershipDto) = tx(true) {
            check(units[membership.organizationUnitId]?.tenantId == membership.tenantId) { "foreign unit" }
            if (memberships.values.any { it.tenantId == membership.tenantId && it.userId == membership.userId && it.active && it.organizationUnitId == membership.organizationUnitId }) dup("membership")
            membership.copy(version = 0, primary = false, active = true).also { memberships[it.id] = it }
        }
        override fun update(membership: OrganizationMembershipDto, expectedVersion: Long) = tx(true) {
            val cur = memberships[membership.id]?.takeIf { it.tenantId == membership.tenantId && it.userId == membership.userId && it.version == expectedVersion } ?: return@tx null
            cur.copy(relationType = membership.relationType, version = cur.version + 1, updatedAt = now()).also { memberships[it.id] = it }
        }
        override fun setPrimary(tenantId: UUID, userId: UUID, membershipId: UUID, expectedVersion: Long) = tx(true) {
            val cur = memberships[membershipId]?.takeIf { it.tenantId == tenantId && it.userId == userId && it.active && it.version == expectedVersion } ?: return@tx null
            memberships.values.filter { it.tenantId == tenantId && it.userId == userId && it.primary && it.id != membershipId }.forEach { memberships[it.id] = it.copy(primary = false, version = it.version + 1, updatedAt = now()) }
            cur.copy(primary = true, version = cur.version + 1, updatedAt = now()).also { memberships[it.id] = it }
        }
        override fun end(tenantId: UUID, userId: UUID, membershipId: UUID, expectedVersion: Long) = tx(true) {
            val cur = memberships[membershipId]?.takeIf { it.tenantId == tenantId && it.userId == userId && it.active && it.version == expectedVersion } ?: return@tx null
            cur.copy(active = false, primary = false, version = cur.version + 1, updatedAt = now()).also { memberships[it.id] = it }
        }
        override fun activeCountByUnit(tenantId: UUID, unitId: UUID) = tx { memberships.values.count { it.tenantId == tenantId && it.organizationUnitId == unitId && it.active } }
    }

    val positions = object : PositionRepository {
        override fun list(tenantId: UUID, includeInactive: Boolean) = tx { positions.values.filter { it.tenantId == tenantId && (includeInactive || it.active) } }
        override fun find(tenantId: UUID, id: UUID) = tx { positions[id]?.takeIf { it.tenantId == tenantId } }
        override fun insert(position: PositionDto) = tx(true) {
            if (positions.values.any { it.tenantId == position.tenantId && it.code.equals(position.code, true) }) dup("code")
            position.copy(version = 0).also { positions[it.id] = it }
        }
        override fun update(position: PositionDto, expectedVersion: Long) = tx(true) {
            val cur = positions[position.id]?.takeIf { it.tenantId == position.tenantId && it.version == expectedVersion } ?: return@tx null
            cur.copy(name = position.name, description = position.description, version = cur.version + 1, updatedAt = now()).also { positions[it.id] = it }
        }
        override fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long) = tx(true) {
            val cur = positions[id]?.takeIf { it.tenantId == tenantId && it.version == expectedVersion } ?: return@tx null
            cur.copy(active = active, version = cur.version + 1, updatedAt = now()).also { positions[id] = it }
        }
    }

    val grades = object : GradeRepository {
        override fun list(tenantId: UUID, includeInactive: Boolean) = tx { grades.values.filter { it.tenantId == tenantId && (includeInactive || it.active) } }
        override fun find(tenantId: UUID, id: UUID) = tx { grades[id]?.takeIf { it.tenantId == tenantId } }
        override fun insert(grade: GradeDto) = tx(true) {
            if (grades.values.any { it.tenantId == grade.tenantId && it.code.equals(grade.code, true) }) dup("code")
            grade.copy(version = 0).also { grades[it.id] = it }
        }
        override fun update(grade: GradeDto, expectedVersion: Long) = tx(true) {
            val cur = grades[grade.id]?.takeIf { it.tenantId == grade.tenantId && it.version == expectedVersion } ?: return@tx null
            cur.copy(name = grade.name, rank = grade.rank, description = grade.description, version = cur.version + 1, updatedAt = now()).also { grades[it.id] = it }
        }
        override fun setActive(tenantId: UUID, id: UUID, active: Boolean, expectedVersion: Long) = tx(true) {
            val cur = grades[id]?.takeIf { it.tenantId == tenantId && it.version == expectedVersion } ?: return@tx null
            cur.copy(active = active, version = cur.version + 1, updatedAt = now()).also { grades[id] = it }
        }
    }

    val employeePositions = object : EmployeePositionRepository {
        override fun list(tenantId: UUID, userId: UUID, includeInactive: Boolean) = tx { assignments.values.filter { it.tenantId == tenantId && it.userId == userId && (includeInactive || it.active) } }
        override fun listForUsers(tenantId: UUID, userIds: Collection<UUID>, includeInactive: Boolean) = tx { assignments.values.filter { it.tenantId == tenantId && it.userId in userIds && (includeInactive || it.active) } }
        override fun find(tenantId: UUID, userId: UUID, id: UUID) = tx { assignments[id]?.takeIf { it.tenantId == tenantId && it.userId == userId } }
        override fun insert(assignment: EmployeePositionDto) = tx(true) {
            if (assignments.values.any { it.tenantId == assignment.tenantId && it.userId == assignment.userId && it.active && it.positionId == assignment.positionId && it.gradeId == assignment.gradeId && it.organizationUnitId == assignment.organizationUnitId }) dup("assignment")
            assignment.copy(version = 0, primary = false, active = true).also { assignments[it.id] = it }
        }
        override fun setPrimary(tenantId: UUID, userId: UUID, id: UUID, expectedVersion: Long) = tx(true) {
            val cur = assignments[id]?.takeIf { it.tenantId == tenantId && it.userId == userId && it.active && it.version == expectedVersion } ?: return@tx null
            assignments.values.filter { it.tenantId == tenantId && it.userId == userId && it.primary && it.id != id }.forEach { assignments[it.id] = it.copy(primary = false, version = it.version + 1, updatedAt = now()) }
            cur.copy(primary = true, version = cur.version + 1, updatedAt = now()).also { assignments[it.id] = it }
        }
        override fun end(tenantId: UUID, userId: UUID, id: UUID, expectedVersion: Long) = tx(true) {
            val cur = assignments[id]?.takeIf { it.tenantId == tenantId && it.userId == userId && it.active && it.version == expectedVersion } ?: return@tx null
            cur.copy(active = false, primary = false, version = cur.version + 1, updatedAt = now()).also { assignments[it.id] = it }
        }
    }
}

/** wires ONE in-memory store as every C3 seam: import it into an integration test to run the C1 API contract without C3 persistence */
@TestConfiguration
class InMemoryOrganizationConfig {
    private val store = InMemoryOrganization()
    @Bean fun orgTypeRepository(): OrganizationUnitTypeRepository = store.types
    @Bean fun orgUnitRepository(): OrganizationUnitRepository = store.units
    @Bean fun employeeProfileRepository(): EmployeeProfileRepository = store.profiles
    @Bean fun employeeMembershipRepository(): EmployeeOrganizationMembershipRepository = store.memberships
    @Bean fun positionRepository(): PositionRepository = store.positions
    @Bean fun gradeRepository(): GradeRepository = store.grades
    @Bean fun employeePositionRepository(): EmployeePositionRepository = store.employeePositions
    @Bean fun tenantStructureLock(): TenantStructureLock = store.lock
}
