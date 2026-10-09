package com.systemwebstudio.data.org

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Test-only graph verifier, run after every concurrency scenario. Per tenant it proves: every unit (archived ones included) reaches a root by following parent links (a cycle's members
 * never do), nothing is reached twice, no parent lives in another tenant, and the traversal stayed within the bound.
 */
data class OrgGraphReport(val tenantId: UUID, val totalUnits: Long, val reachableFromRoots: Long, val visits: Long, val maxDepth: Int, val crossTenantParents: Long, val roots: Long) {
    val unreachable get() = totalUnits - reachableFromRoots
    val acyclic get() = unreachable == 0L && visits == reachableFromRoots
    val ok get() = acyclic && crossTenantParents == 0L && maxDepth < OrgDb.MAX_DEPTH
    override fun toString() = "tenant=$tenantId units=$totalUnits roots=$roots reachable=$reachableFromRoots visits=$visits unreachable=$unreachable maxDepth=$maxDepth crossTenant=$crossTenantParents"
}

object OrgGraph {
    fun verify(jdbc: JdbcTemplate, tenantId: UUID): OrgGraphReport {
        val total = jdbc.queryForObject("SELECT count(*) FROM organization_units WHERE tenant_id = ?", Long::class.java, tenantId)!!
        val roots = jdbc.queryForObject("SELECT count(*) FROM organization_units WHERE tenant_id = ? AND parent_id IS NULL", Long::class.java, tenantId)!!
        val cross = jdbc.queryForObject(
            "SELECT count(*) FROM organization_units u JOIN organization_units p ON p.id = u.parent_id WHERE u.tenant_id = ? AND p.tenant_id <> u.tenant_id", Long::class.java, tenantId
        )!!
        // visits counts rows produced by the traversal; distinct counts nodes: equal <=> nothing was reached twice
        val row = jdbc.queryForMap(
            """WITH RECURSIVE walk AS (SELECT id, 1 AS depth FROM organization_units WHERE tenant_id = ? AND parent_id IS NULL
                   UNION ALL SELECT c.id, w.depth + 1 FROM walk w JOIN organization_units c ON c.tenant_id = ? AND c.parent_id = w.id WHERE w.depth < ${OrgDb.MAX_DEPTH})
               SELECT count(*) AS visits, count(DISTINCT id) AS reachable, coalesce(max(depth), 0) AS max_depth FROM walk""", tenantId, tenantId
        )
        return OrgGraphReport(tenantId, total, (row["reachable"] as Number).toLong(), (row["visits"] as Number).toLong(), (row["max_depth"] as Number).toInt(), cross, roots)
    }
}
