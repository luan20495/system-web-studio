package com.systemwebstudio.organization

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Runs the C3 conformance kit against the C1 in-memory double: proves the kit is satisfiable and pins the reference behaviour. NOT a C3 PostgreSQL test. */
class InMemoryOrganizationConformanceTest : OrganizationRepositoryContractKit() {
    private val store = InMemoryOrganization()
    private val members = ConcurrentHashMap<UUID, MutableList<TenantIdentity>>()

    override val types get() = store.types
    override val units get() = store.units
    override val directory get() = store.directory
    override val memberships get() = store.memberships
    override val positions get() = store.positions
    override val grades get() = store.grades
    override val employeePositions get() = store.employeePositions
    override val identities = object : TenantIdentityDirectory {
        override fun find(tenantId: UUID, userId: UUID) = members[tenantId]?.firstOrNull { it.userId == userId }
        override fun findAll(tenantId: UUID, userIds: Collection<UUID>) = members[tenantId].orEmpty().filter { it.userId in userIds }.associateBy { it.userId }
        override fun members(tenantId: UUID) = members[tenantId].orEmpty().toList()
    }

    override fun newTenant(): UUID = UUID.randomUUID().also { members[it] = java.util.Collections.synchronizedList(mutableListOf()) }
    override fun newMember(tenantId: UUID, username: String, displayName: String): UUID =
        UUID.randomUUID().also { members.getValue(tenantId).add(TenantIdentity(it, username, displayName, "$username@example.test", true, true, "MEMBER", true)) }
    override fun setMemberActive(tenantId: UUID, userId: UUID, active: Boolean) {
        val l = members.getValue(tenantId); val i = l.indexOfFirst { it.userId == userId }; l[i] = l[i].copy(tenantMemberActive = active)
    }
}
