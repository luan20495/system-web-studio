package com.systemwebstudio.tenancy

import com.systemwebstudio.access.adapters.AccessDecision
import com.systemwebstudio.access.adapters.GatewayAuthRequest
import com.systemwebstudio.access.adapters.GatewayAuthorizer
import com.systemwebstudio.access.adapters.Principal
import com.systemwebstudio.organization.InMemoryOrganizationConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID

/**
 * Decision A (C7, 2026-10-11): the C1 gateway authorizer decides on PERMISSION (and tenant / workspace / project scope) only. It never looks a data source id up.
 * Whether a data source exists in the caller's workspace is C3's job and C3's answer is the canonical 404 (found-in-workspace or not found, indistinguishable).
 * An authorizer that answered `Denied("data source not in workspace")` for a foreign id and `Allowed` for an own id would be an existence oracle (403 vs 404 differs by id)
 * and would also change the C3 contract. This test pins that the decision does not depend on the data source id.
 */
@Import(InMemoryOrganizationConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayAuthorizerNoOracleTests : FinalIamTestBase() {
    @Autowired @Qualifier("c1GatewayAuthorizer") lateinit var gateway: GatewayAuthorizer

    private fun g(user: UUID, tenant: UUID, ws: UUID?, ds: UUID?, op: String = "QUERY_EXECUTE", project: UUID? = null) =
        gateway.authorize(GatewayAuthRequest(Principal(com.systemwebstudio.tenancy.ActorKind.USER, user), tenant, ws, project, null, op, ds))

    @Test
    fun `the decision for a WORKSPACE_ADMIN is the same for an own, a foreign and an unknown data source id (no existence oracle) and every operation`() {
        val sys = sysAdmin(); val c = company(sys); val other = company(sys)
        val wsA = wsIn(c, "A")
        val wa = fx.user("noo-wa"); fx.member(wsA, wa, "WORKSPACE_ADMIN")
        for (op in GatewayAuthorizer.REQUIRED.keys) {
            val withoutId = g(wa.id, c.id, wsA, null, op)
            assertThat(g(wa.id, c.id, wsA, UUID.randomUUID(), op)).describedAs("$op unknown id").isEqualTo(withoutId)
            assertThat(g(wa.id, c.id, wsA, UUID.randomUUID(), op)).describedAs("$op another random id").isEqualTo(withoutId)
        }
        // the earlier gates still decide, independent of the id: a tenant mismatch is a tenant mismatch, not a data source answer
        assertThat(g(wa.id, other.id, wsA, UUID.randomUUID())).isEqualTo(AccessDecision.Denied("tenant mismatch"))
    }

    @Test
    fun `a missing permission is denied the same way with or without a data source id`() {
        val sys = sysAdmin(); val c = company(sys); val wsA = wsIn(c, "A")
        val viewer = fx.user("noo-vw"); fx.member(wsA, viewer, "VIEWER")
        val ds = UUID.randomUUID()
        assertThat(g(viewer.id, c.id, wsA, ds)).describedAs("workspace VIEWER holds no QUERY_EXECUTE").isEqualTo(AccessDecision.Denied("missing permission"))
        assertThat(g(viewer.id, c.id, wsA, null)).isEqualTo(AccessDecision.Denied("missing permission"))
    }
}
