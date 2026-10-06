package com.systemwebstudio.wiring

import com.systemwebstudio.access.adapters.AccessDecision
import com.systemwebstudio.access.adapters.AppAccessRequest
import com.systemwebstudio.access.adapters.GatewayAuthRequest
import com.systemwebstudio.access.adapters.Principal
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayDecision
import com.systemwebstudio.data.gateway.GatewayOperation
import com.systemwebstudio.logic.action.AccessRequest
import com.systemwebstudio.logic.action.ActionActor
import com.systemwebstudio.logic.action.ActionContext
import com.systemwebstudio.logic.action.AuthorizationDecision
import com.systemwebstudio.logic.action.ExecutionMode
import com.systemwebstudio.logic.action.Fx
import com.systemwebstudio.logic.action.LogicPermissions
import com.systemwebstudio.logic.action.ResourceKind
import com.systemwebstudio.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID
import com.systemwebstudio.access.adapters.AccessPort as C1AccessPort
import com.systemwebstudio.access.adapters.GatewayAuthorizer as C1GatewayAuthorizer
import com.systemwebstudio.logic.action.ActorKind as LogicActorKind
import com.systemwebstudio.tenancy.ActorKind as TenancyActorKind

/** The adapters only translate: types, actor kinds and decisions. The policy itself is C1's and is tested there. */
class C1PortAdaptersTests {
    private val ws = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private fun ctx(kind: LogicActorKind = LogicActorKind.USER, app: UUID? = Fx.appA) =
        ActionContext(Fx.tenantA, ActionActor(Fx.user, kind), ws, app, "req-9")

    @Test
    fun `the access adapter sends canonical code, tenant, workspace, project, mode and a converted principal to C1`() {
        val c1 = mock(C1AccessPort::class.java)
        val expected = AppAccessRequest(Principal(TenancyActorKind.USER, Fx.user), Fx.tenantA, ws, Fx.appA, LogicPermissions.ACTION_EXECUTE, "TEST")
        `when`(c1.check(expected)).thenReturn(AccessDecision.Allowed)
        val d = C4AccessPortAdapter(c1).check(ctx(), AccessRequest(LogicPermissions.ACTION_EXECUTE, ResourceKind.ACTION, "a1", Fx.appA, ExecutionMode.TEST))
        assertThat(d).isEqualTo(AuthorizationDecision.Allowed)
    }

    @Test
    fun `a denial of C1 is passed on with its reason`() {
        val c1 = mock(C1AccessPort::class.java)
        val expected = AppAccessRequest(Principal(TenancyActorKind.SERVICE, Fx.user), Fx.tenantA, ws, Fx.appA, LogicPermissions.APP_USE, "LIVE")
        `when`(c1.check(expected)).thenReturn(AccessDecision.Denied("actor kind not authorised"))
        val d = C4AccessPortAdapter(c1).check(ctx(LogicActorKind.SERVICE), AccessRequest(LogicPermissions.APP_USE, ResourceKind.APP, null, Fx.appA))
        assertThat(d).isEqualTo(AuthorizationDecision.Denied("actor kind not authorised"))
    }

    @Test
    fun `a request for another application than the context is denied without asking C1`() {
        val c1 = mock(C1AccessPort::class.java)
        val d = C4AccessPortAdapter(c1).check(ctx(), AccessRequest(LogicPermissions.APP_USE, ResourceKind.APP, null, Fx.appB))
        assertThat(d).isInstanceOf(AuthorizationDecision.Denied::class.java)
        verifyNoInteractions(c1)
    }

    @Test
    fun `the gateway authorizer adapter converts the context and the decision`() {
        val c1 = mock(C1GatewayAuthorizer::class.java)
        val ds = UUID.randomUUID()
        val gctx = GatewayContext(TenantContext(Fx.tenantA, null), Fx.user, TenancyActorKind.USER, "r1", ws, Fx.appA, "v1")
        val expected = GatewayAuthRequest(Principal(TenancyActorKind.USER, Fx.user), Fx.tenantA, ws, Fx.appA, "v1", "MUTATION_EXECUTE", ds)
        `when`(c1.authorize(expected)).thenReturn(AccessDecision.Allowed)
        val adapter = C3GatewayAuthorizerAdapter(c1)
        assertThat(adapter.authorize(gctx, GatewayOperation.MUTATION_EXECUTE, ds)).isEqualTo(GatewayDecision.Allowed)

        val denied = GatewayAuthRequest(Principal(TenancyActorKind.USER, Fx.user), Fx.tenantA, ws, Fx.appA, "v1", "QUERY_EXECUTE", null)
        `when`(c1.authorize(denied)).thenReturn(AccessDecision.Denied("missing permission"))
        assertThat(adapter.authorize(gctx, GatewayOperation.QUERY_EXECUTE, null)).isEqualTo(GatewayDecision.Denied("missing permission"))
    }
}
