package com.systemwebstudio.data.org

import com.systemwebstudio.organization.EmployeeDirectoryRepository
import com.systemwebstudio.organization.EmployeeOrganizationMembershipRepository
import com.systemwebstudio.organization.EmployeePositionRepository
import com.systemwebstudio.organization.GradeRepository
import com.systemwebstudio.organization.OrganizationUnitRepository
import com.systemwebstudio.organization.OrganizationUnitTypeRepository
import com.systemwebstudio.organization.PositionRepository
import com.systemwebstudio.organization.TenantStructuralLock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.json.JsonMapper

/**
 * PROPOSED FOR C0 REVIEW / IMPORT (wiring is C0's). Registers the C3 PostgreSQL implementations as the seams of C1's `OrganizationRepositories`.
 *
 * OFF by default: it is active only with `app.organization.persistence-enabled=true`. Until C0 allocates the migration number, imports the schema and switches it on, no repository bean
 * exists and every organization route keeps answering `501 ORG_PERSISTENCE_NOT_AVAILABLE` (the release candidate is unchanged). The key belongs in `application.yml` (C0's file):
 * `app.organization.persistence-enabled: ${ORGANIZATION_PERSISTENCE_ENABLED:false}`; `app.organization.lock-timeout-ms` (default 5000) bounds every lock wait.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.organization", name = ["persistence-enabled"], havingValue = "true")
class OrganizationPersistenceConfiguration {
    @Bean fun organizationDb(jdbc: JdbcTemplate, txm: PlatformTransactionManager, json: JsonMapper, @org.springframework.beans.factory.annotation.Value("\${app.organization.lock-timeout-ms:5000}") lockTimeoutMs: Int) =
        OrgDb(jdbc, txm, json, lockTimeoutMs)
    @Bean fun organizationUnitTypeRepository(db: OrgDb): OrganizationUnitTypeRepository = PostgresOrganizationUnitTypeRepository(db)
    @Bean fun organizationUnitRepository(db: OrgDb): OrganizationUnitRepository = PostgresOrganizationUnitRepository(db)
    @Bean fun employeeDirectoryRepository(db: OrgDb): EmployeeDirectoryRepository = PostgresEmployeeDirectoryRepository(db)
    @Bean fun employeeOrganizationMembershipRepository(db: OrgDb): EmployeeOrganizationMembershipRepository = PostgresEmployeeOrganizationMembershipRepository(db)
    @Bean fun positionRepository(db: OrgDb): PositionRepository = PostgresPositionRepository(db)
    @Bean fun gradeRepository(db: OrgDb): GradeRepository = PostgresGradeRepository(db)
    @Bean fun employeePositionRepository(db: OrgDb): EmployeePositionRepository = PostgresEmployeePositionRepository(db)
    @Bean fun tenantStructuralLock(db: OrgDb): TenantStructuralLock = PostgresTenantStructuralLock(db)
    @Bean fun organizationCounts(db: OrgDb) = PostgresOrganizationCounts(db)
}
