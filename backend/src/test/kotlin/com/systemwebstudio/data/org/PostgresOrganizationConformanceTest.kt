package com.systemwebstudio.data.org

import com.systemwebstudio.organization.EmployeeDirectoryRepository
import com.systemwebstudio.organization.EmployeeOrganizationMembershipRepository
import com.systemwebstudio.organization.EmployeePositionRepository
import com.systemwebstudio.organization.GradeRepository
import com.systemwebstudio.organization.OrganizationRepositoryContractKit
import com.systemwebstudio.organization.OrganizationUnitRepository
import com.systemwebstudio.organization.OrganizationUnitTypeRepository
import com.systemwebstudio.organization.PositionRepository
import com.systemwebstudio.organization.TenantIdentityDirectory
import java.util.UUID

/**
 * C1's `OrganizationRepositoryContractKit` (the 13 conformance tests of H-C1-17, C1 branch fix/c1-h-c1-17-reconciliation @ 93c5cc4) run UNCHANGED against the C3 PostgreSQL
 * repositories on a real database. The kit calls the repositories outside any surrounding transaction, exactly as it documents.
 */
class PostgresOrganizationConformanceTest : OrganizationRepositoryContractKit() {
    private val s = OrgTestDb.stack
    override val types: OrganizationUnitTypeRepository = s.types
    override val units: OrganizationUnitRepository = s.units
    override val directory: EmployeeDirectoryRepository = s.directory
    override val memberships: EmployeeOrganizationMembershipRepository = s.memberships
    override val positions: PositionRepository = s.positions
    override val grades: GradeRepository = s.grades
    override val employeePositions: EmployeePositionRepository = s.employeePositions
    override val identities: TenantIdentityDirectory = s.identities
    override fun newTenant(): UUID = OrgTestDb.newTenant()
    override fun newMember(tenantId: UUID, username: String, displayName: String): UUID = OrgTestDb.newMember(tenantId, username, displayName)
    override fun setMemberActive(tenantId: UUID, userId: UUID, active: Boolean) { OrgTestDb.jdbc.update("UPDATE tenant_members SET active = ? WHERE tenant_id = ? AND user_id = ?", active, tenantId, userId) }
}
