package com.systemwebstudio.data.org

import com.systemwebstudio.organization.OrganizationTestBase
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/**
 * Base of the end-to-end organization tests on the REAL stack: HTTP -> C1 -> the C3 PostgreSQL repositories (switched on by `app.organization.persistence-enabled=true`, the PROPOSED wiring) -> PostgreSQL.
 * C1's base removes the tenants / accounts a test created; with REAL organization rows (FK RESTRICT, by design: nothing disappears behind a tenant) they must go first: the subclass @AfterEach
 * below runs before the base's.
 */
@TestPropertySource(properties = ["app.organization.persistence-enabled=true"])
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
abstract class PostgresOrganizationApiBase : OrganizationTestBase() {
    private var tenantsBefore: Set<UUID> = emptySet()
    @org.junit.jupiter.api.BeforeEach fun snapshotTenantsForOrganizationPurge() { tenantsBefore = jdbc.queryForList("SELECT id FROM tenants", UUID::class.java).toSet() }
    @org.junit.jupiter.api.AfterEach fun purgeOrganizationRowsOfThisTest() {
        val mine = (jdbc.queryForList("SELECT id FROM tenants", UUID::class.java).toSet() - tenantsBefore).toTypedArray(); if (mine.isEmpty()) return
        listOf("employee_positions", "employee_organization_units").forEach { jdbc.update("DELETE FROM $it WHERE tenant_id = ANY (?)", mine) }
        while (jdbc.update("DELETE FROM organization_units u WHERE u.tenant_id = ANY (?) AND NOT EXISTS (SELECT 1 FROM organization_units c WHERE c.parent_id = u.id)", mine) > 0) { }     // leaves first (parent FK is RESTRICT)
        listOf("organization_unit_types", "positions", "grades").forEach { jdbc.update("DELETE FROM $it WHERE tenant_id = ANY (?)", mine) }
    }

    protected fun rows(table: String, tenant: UUID) = jdbc.queryForObject("SELECT count(*) FROM $table WHERE tenant_id = ?", Long::class.java, tenant)!!
    protected fun reason(c: Company, r: org.springframework.test.web.servlet.MvcResult): String = c.admin.body(r).get("details").get("reason").asString()
}
