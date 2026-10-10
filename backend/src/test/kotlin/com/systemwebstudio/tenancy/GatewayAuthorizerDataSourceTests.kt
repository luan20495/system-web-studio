package com.systemwebstudio.tenancy

import com.systemwebstudio.access.adapters.AccessDecision
import com.systemwebstudio.access.adapters.GatewayAuthRequest
import com.systemwebstudio.access.adapters.GatewayAuthorizer
import com.systemwebstudio.access.adapters.Principal
import com.systemwebstudio.organization.InMemoryOrganizationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID

/**
 * C1 hardening (cfb064b) - E: c1GatewayAuthorizer accepts a dataSourceId only when `data_sources` has that id for the caller's tenant AND workspace.
 * Data source rows are inserted with JDBC (V28 columns) and removed after each test, before the base class purges the companies.
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayAuthorizerDataSourceTests : FinalIamTestBase() {
    @Autowired @Qualifier("c1GatewayAuthorizer") lateinit var gateway: GatewayAuthorizer

    private val created = mutableListOf<UUID>()
    @AfterEach fun removeDataSources() {
        if (created.isNotEmpty()) jdbc.execute("DELETE FROM data_sources WHERE id IN (${created.joinToString(",") { "'$it'" }})")
        created.clear()
    }

    private fun dataSource(tenant: UUID, workspace: UUID?): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO data_sources (id, tenant_id, workspace_id, type, name, status) VALUES (?, ?, ?, 'POSTGRES', ?, 'ACTIVE')", id, tenant, workspace, "hds-" + id.toString().take(8))
        created += id
        return id
    }

    private val NOT_IN_WS = AccessDecision.Denied("data source not in workspace")
    private fun g(user: UUID, tenant: UUID, ws: UUID?, ds: UUID?, op: String = "QUERY_EXECUTE", project: UUID? = null) =
        gateway.authorize(GatewayAuthRequest(Principal(com.systemwebstudio.tenancy.ActorKind.USER, user), tenant, ws, project, null, op, ds))

    @Test
    fun `E1 a WORKSPACE_ADMIN may use a data source of its own tenant and workspace only - other workspace, other tenant, tenant-level and unknown ids are denied`() {
        val sys = sysAdmin(); val c = company(sys); val other = company(sys)
        val wsA = wsIn(c, "A"); val wsB = wsIn(c, "B"); val wsO = wsIn(other, "O")
        val wa = fx.user("hds-wa"); fx.member(wsA, wa, "WORKSPACE_ADMIN")
        val dsA = dataSource(c.id, wsA); val dsB = dataSource(c.id, wsB); val dsTenant = dataSource(c.id, null); val dsOther = dataSource(other.id, wsO)

        assertThat(g(wa.id, c.id, wsA, dsA)).describedAs("own source").isEqualTo(AccessDecision.Allowed)
        assertThat(g(wa.id, c.id, wsA, null)).describedAs("no data source id: unchanged").isEqualTo(AccessDecision.Allowed)
        assertThat(g(wa.id, c.id, wsA, dsB)).describedAs("source of another workspace of the same tenant").isEqualTo(NOT_IN_WS)
        assertThat(g(wa.id, c.id, wsA, dsTenant)).describedAs("tenant-level source (no workspace)").isEqualTo(NOT_IN_WS)
        assertThat(g(wa.id, c.id, wsA, dsOther)).describedAs("source of another tenant").isEqualTo(NOT_IN_WS)
        assertThat(g(wa.id, c.id, wsA, UUID.randomUUID())).describedAs("unknown id").isEqualTo(NOT_IN_WS)

        // every operation applies the same rule
        for (op in GatewayAuthorizer.REQUIRED.keys) {
            assertThat(g(wa.id, c.id, wsA, dsA, op)).describedAs("$op own").isEqualTo(AccessDecision.Allowed)
            assertThat(g(wa.id, c.id, wsA, dsB, op)).describedAs("$op foreign").isEqualTo(NOT_IN_WS)
        }
        // the earlier gates still win: claiming the other tenant is a tenant mismatch, not a data source decision; a non-member never reaches the data source check
        assertThat(g(wa.id, other.id, wsA, dsOther)).isEqualTo(AccessDecision.Denied("tenant mismatch"))
        assertThat(g(wa.id, c.id, wsB, dsB).allowed()).describedAs("not a member of B").isFalse()
        // the workspace moving between rows: once B's source is re-assigned to A it is accepted, and the old one is not
        jdbc.update("UPDATE data_sources SET workspace_id = ? WHERE id = ?", wsA, dsB)
        assertThat(g(wa.id, c.id, wsA, dsB)).isEqualTo(AccessDecision.Allowed)
    }

    @Test
    fun `E2 a data source id is also checked with a project scope, and a member without the permission is denied before the data source check`() {
        val sys = sysAdmin(); val c = company(sys); val wsA = wsIn(c, "A"); val wsB = wsIn(c, "B")
        val owner = fx.user("hds-own"); fx.member(wsA, owner, "EDITOR"); val p = fx.project(wsA, owner)
        val dsA = dataSource(c.id, wsA); val dsB = dataSource(c.id, wsB)
        assertThat(g(owner.id, c.id, wsA, dsA, project = p.id)).describedAs("project owner, own source").isEqualTo(AccessDecision.Allowed)
        assertThat(g(owner.id, c.id, wsA, dsB, project = p.id)).isEqualTo(NOT_IN_WS)
        assertThat(g(owner.id, c.id, wsA, dsA, "MUTATION_EXECUTE", p.id)).describedAs("owner may not mutate").isEqualTo(AccessDecision.Denied("missing permission"))
        val viewer = fx.user("hds-vw"); fx.member(wsA, viewer, "VIEWER")
        assertThat(g(viewer.id, c.id, wsA, dsA)).describedAs("workspace VIEWER holds no QUERY_EXECUTE").isEqualTo(AccessDecision.Denied("missing permission"))
    }

    private fun AccessDecision.allowed() = this is AccessDecision.Allowed
}
