package com.systemwebstudio.tenancy

import com.systemwebstudio.access.adapters.*
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** C1 · GatewayAuthorizer / AccessPort / TenantGate / PrincipalResolver policy cores — default policy (system-admin business access OFF). */
class AccessAdaptersTests : IntegrationTestBase() {
    @Autowired lateinit var gateway: GatewayAuthorizer
    @Autowired lateinit var port: AccessPort
    @Autowired lateinit var gate: TenantGate
    @Autowired lateinit var resolver: PrincipalResolver
    @Autowired lateinit var tenants: TenantService

    private val DEFAULT = TenantIds.DEFAULT
    private fun user(id: UUID) = Principal(ActorKind.USER, id)
    private fun slug() = "ad-" + UUID.randomUUID().toString().take(8)
    private fun g(who: Principal, ws: UUID?, op: String, project: UUID? = null, tenant: UUID = DEFAULT, version: String? = null) =
        gateway.authorize(GatewayAuthRequest(who, tenant, ws, project, version, op, null))
    private fun p(who: Principal, ws: UUID?, code: String, project: UUID? = null, tenant: UUID = DEFAULT, mode: String = "LIVE") =
        port.check(AppAccessRequest(who, tenant, ws, project, code, mode))

    private val allOps = GatewayAuthorizer.REQUIRED.keys

    // ------------------------------------------------------------------------------------------------ GatewayAuthorizer
    @Test
    fun `gateway - operation to permission mapping is total and matches the contract`() {
        assertThat(allOps).containsExactlyInAnyOrder("DATASOURCE_READ", "DATASOURCE_MANAGE", "QUERY_EXECUTE", "MUTATION_EXECUTE", "SCHEMA_DISCOVER", "SCHEMA_SAMPLE", "CACHE_REFRESH", "EVENTS_SUBSCRIBE", "SYNC_MANAGE", "WEBHOOK_MANAGE")
        val m = GatewayAuthorizer.REQUIRED
        assertThat(m.getValue("DATASOURCE_READ")).containsExactly(com.systemwebstudio.access.Permission.DATA_SOURCE_VIEW)
        assertThat(m.getValue("MUTATION_EXECUTE")).containsExactly(com.systemwebstudio.access.Permission.DATA_MUTATE)
        assertThat(m.getValue("SCHEMA_SAMPLE")).containsExactlyInAnyOrder(com.systemwebstudio.access.Permission.DATA_SOURCE_MANAGE, com.systemwebstudio.access.Permission.QUERY_EXECUTE)
        for (op in listOf("DATASOURCE_MANAGE", "SCHEMA_DISCOVER", "CACHE_REFRESH", "SYNC_MANAGE", "WEBHOOK_MANAGE")) assertThat(m.getValue(op)).containsExactly(com.systemwebstudio.access.Permission.DATA_SOURCE_MANAGE)
        for (op in listOf("QUERY_EXECUTE", "EVENTS_SUBSCRIBE")) assertThat(m.getValue(op)).containsExactly(com.systemwebstudio.access.Permission.QUERY_EXECUTE)
    }

    @Test
    fun `gateway - a project owner may read and query but not manage, mutate or sample`() {
        val sc = scenario(); val me = user(sc.user.id)
        for (op in listOf("DATASOURCE_READ", "QUERY_EXECUTE", "EVENTS_SUBSCRIBE")) assertThat(g(me, sc.ws, op, sc.projectId)).describedAs(op).isEqualTo(AccessDecision.Allowed)
        for (op in listOf("DATASOURCE_MANAGE", "MUTATION_EXECUTE", "SCHEMA_DISCOVER", "SCHEMA_SAMPLE", "CACHE_REFRESH", "SYNC_MANAGE", "WEBHOOK_MANAGE")) assertThat(g(me, sc.ws, op, sc.projectId).allowed).describedAs(op).isFalse()
    }

    @Test
    fun `gateway - a workspace admin may do every operation, a plain member of nothing may do none`() {
        val sc = scenario(); val admin = fx.user("gwadmin"); fx.member(sc.ws, admin, "WORKSPACE_ADMIN"); val outsider = fx.user("gwout")
        for (op in allOps) { assertThat(g(user(admin.id), sc.ws, op).allowed).describedAs("admin $op").isTrue(); assertThat(g(user(outsider.id), sc.ws, op).allowed).describedAs("outsider $op").isFalse() }
    }

    @Test
    fun `gateway - default deny - unknown operation, missing workspace, non-user actors, disabled account, unknown ids`() {
        val sc = scenario(); val me = user(sc.user.id)
        assertThat(g(me, sc.ws, "DROP_EVERYTHING").allowed).isFalse()
        assertThat(g(me, null, "QUERY_EXECUTE").allowed).isFalse()                                            // tenant-level source: no permission holder
        assertThat(g(me, UUID.randomUUID(), "QUERY_EXECUTE").allowed).isFalse()
        assertThat(g(me, sc.ws, "QUERY_EXECUTE", UUID.randomUUID()).allowed).isFalse()                        // project not in workspace
        for (k in listOf(ActorKind.SYSTEM, ActorKind.SERVICE, ActorKind.APP_TOKEN)) assertThat(g(Principal(k, sc.user.id), sc.ws, "QUERY_EXECUTE", sc.projectId).allowed).describedAs(k.name).isFalse()
        assertThat(g(Principal(ActorKind.USER, null), sc.ws, "QUERY_EXECUTE").allowed).isFalse()
        fx.disable(sc.user.id)
        assertThat(g(me, sc.ws, "QUERY_EXECUTE", sc.projectId).allowed).isFalse()
    }

    @Test
    fun `gateway - cross tenant - the claimed tenant must be the tenant of the workspace`() {
        val sc = scenario(); val other = tenants.create(slug(), "Other"); val me = user(sc.user.id)
        assertThat(g(me, sc.ws, "QUERY_EXECUTE", sc.projectId, tenant = other.id)).isInstanceOf(AccessDecision.Denied::class.java)      // claims the wrong tenant
        assertThat(g(me, sc.ws, "QUERY_EXECUTE", sc.projectId, tenant = DEFAULT).allowed).isTrue()
        jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", other.id, sc.ws)                      // the workspace moves: the old claim is now wrong
        assertThat(g(me, sc.ws, "QUERY_EXECUTE", sc.projectId, tenant = DEFAULT).allowed).isFalse()
        assertThat(g(me, sc.ws, "QUERY_EXECUTE", sc.projectId, tenant = other.id).allowed).isTrue()
        tenants.setStatus(other.id, TenantStatus.SUSPENDED)
        assertThat(g(me, sc.ws, "QUERY_EXECUTE", sc.projectId, tenant = other.id).allowed).isFalse()           // suspended tenant: denied through AccessService
    }

    @Test
    fun `gateway - a user of another workspace cannot use a foreign project id`() {
        val a = scenario(); val b = scenario()
        assertThat(g(user(a.user.id), a.ws, "QUERY_EXECUTE", b.projectId).allowed).isFalse()
        assertThat(g(user(a.user.id), b.ws, "QUERY_EXECUTE", b.projectId).allowed).isFalse()
    }

    @Test
    fun `gateway - an app version must belong to the project`() {
        val a = scenario(); val b = scenario()
        assertThat(g(user(a.user.id), a.ws, "QUERY_EXECUTE", a.projectId, version = UUID.randomUUID().toString()).allowed).isFalse()
        assertThat(g(user(a.user.id), a.ws, "QUERY_EXECUTE", a.projectId, version = "not-a-uuid").allowed).isFalse()
        assertThat(g(user(a.user.id), a.ws, "QUERY_EXECUTE", null, version = UUID.randomUUID().toString()).allowed).isFalse()   // version without project
        val own = jdbc.queryForList("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, a.projectId).firstOrNull()
        val foreign = jdbc.queryForList("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, b.projectId).firstOrNull()
        if (foreign != null) assertThat(g(user(a.user.id), a.ws, "QUERY_EXECUTE", a.projectId, version = foreign.toString()).allowed).isFalse()
        assumeTrue(own != null, "the scenario project has no version row")
        assertThat(g(user(a.user.id), a.ws, "QUERY_EXECUTE", a.projectId, version = own.toString()).allowed).isTrue()
    }

    @Test
    fun `gateway - system admin with the flag OFF has no data permission anywhere`() {
        val sc = scenario(); val sys = fx.user("gwsys", systemAdmin = true)
        for (op in allOps) assertThat(g(user(sys.id), sc.ws, op, sc.projectId).allowed).describedAs(op).isFalse()
        for (op in allOps) assertThat(g(user(sys.id), sc.ws, op).allowed).describedAs("ws-level $op").isFalse()
    }

    // ------------------------------------------------------------------------------------------------ AccessPort
    @Test
    fun `port - canonical codes only, viewer can use but not edit, test mode needs APP_EDIT`() {
        val sc = scenario(); val viewer = fx.user("pv"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(fx.projects.findById(sc.projectId).get(), viewer, "VIEWER")
        val owner = user(sc.user.id); val v = user(viewer.id)
        assertThat(p(v, sc.ws, "APP_USE", sc.projectId)).isEqualTo(AccessDecision.Allowed)
        assertThat(p(v, sc.ws, "APP_VIEW", sc.projectId).allowed).isTrue()
        assertThat(p(v, sc.ws, "APP_EDIT", sc.projectId).allowed).isFalse()
        assertThat(p(v, sc.ws, "ACTION_EXECUTE", sc.projectId).allowed).isFalse()
        assertThat(p(v, sc.ws, "APP_USE", sc.projectId, mode = "TEST").allowed).isFalse()                    // TEST needs APP_EDIT
        assertThat(p(owner, sc.ws, "ACTION_EXECUTE", sc.projectId, mode = "TEST").allowed).isTrue()
        assertThat(p(owner, sc.ws, "ACTION_EXECUTE", sc.projectId, mode = "DRY").allowed).isFalse()           // unknown mode
        assertThat(p(owner, sc.ws, "PROJECT_READ", sc.projectId).allowed).isFalse()                           // legacy storage name is not a canonical code
        assertThat(p(owner, sc.ws, "WORKFLOW_EXECUTE", sc.projectId).allowed).isFalse()                       // default deny: not granted to owners
        assertThat(p(owner, sc.ws, "DATA_MUTATE", sc.projectId).allowed).isFalse()
        assertThat(p(owner, sc.ws, "EVERYTHING", sc.projectId).allowed).isFalse()
        val admin = fx.user("padm"); fx.member(sc.ws, admin, "WORKSPACE_ADMIN")
        for (c in listOf("WORKFLOW_EXECUTE", "WORKFLOW_MANAGE", "DATA_MUTATE", "DATA_SOURCE_MANAGE")) assertThat(p(user(admin.id), sc.ws, c, sc.projectId).allowed).describedAs(c).isTrue()
    }

    @Test
    fun `port - tenant gate, claimed tenant and actor kinds`() {
        val sc = scenario(); val owner = user(sc.user.id); val other = tenants.create(slug(), "PortOther")
        assertThat(p(owner, sc.ws, "APP_USE", sc.projectId, tenant = other.id).allowed).isFalse()              // wrong tenant claimed
        assertThat(p(Principal(ActorKind.APP_TOKEN, sc.user.id), sc.ws, "APP_USE", sc.projectId).allowed).isFalse()
        assertThat(p(Principal(ActorKind.SYSTEM), sc.ws, "APP_USE", sc.projectId).allowed).isFalse()
        assertThat(p(owner, null, "APP_USE").allowed).isFalse()
        tenants.setStatus(other.id, TenantStatus.SUSPENDED)
        jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", other.id, sc.ws)
        assertThat(p(owner, sc.ws, "APP_USE", sc.projectId, tenant = other.id).allowed).isFalse()              // a suspended tenant runs nothing
        tenants.setStatus(other.id, TenantStatus.ACTIVE)
        assertThat(p(owner, sc.ws, "APP_USE", sc.projectId, tenant = other.id).allowed).isTrue()
    }

    @Test
    fun `port - an archived application keeps only view, not use`() {
        val sc = scenario(); val owner = user(sc.user.id)
        assertThat(sc.s.post("${sc.base}/archive").response.status).isIn(200, 204)
        assertThat(p(owner, sc.ws, "APP_VIEW", sc.projectId).allowed).isTrue()
        assertThat(p(owner, sc.ws, "APP_USE", sc.projectId).allowed).isFalse()
    }

    // ------------------------------------------------------------------------------------------------ TenantGate
    @Test
    fun `tenant gate - only an existing ACTIVE tenant is enabled`() {
        val t = tenants.create(slug(), "Gate")
        assertThat(gate.isEnabled(DEFAULT)).isTrue(); assertThat(gate.isEnabled(t.id)).isTrue()
        tenants.setStatus(t.id, TenantStatus.SUSPENDED); assertThat(gate.isEnabled(t.id)).isFalse()
        tenants.setStatus(t.id, TenantStatus.DELETED); assertThat(gate.isEnabled(t.id)).isFalse()
        assertThat(gate.isEnabled(UUID.randomUUID())).isFalse()
    }

    // ------------------------------------------------------------------------------------------------ PrincipalResolver
    private fun ids(o: PrincipalOutcome): Set<UUID> = (o as? PrincipalOutcome.Resolved)?.userIds ?: emptySet()
    private fun r(who: Principal, q: PrincipalQuery, ws: UUID? = null, tenant: UUID = DEFAULT) = resolver.resolve(PrincipalContext(who, tenant, ws), q)

    @Test
    fun `resolver - users are addressable only inside the context tenant`() {
        val sc = scenario(); val me = user(sc.user.id)
        val mate = fx.user("mate"); fx.member(sc.ws, mate, "VIEWER")                                      // trigger makes mate a DEFAULT tenant member
        val stranger = fx.user("stranger"); val disabled = fx.user("dis"); fx.member(sc.ws, disabled, "VIEWER"); fx.disable(disabled.id)
        assertThat(r(me, PrincipalQuery.User(mate.id), sc.ws)).isEqualTo(PrincipalOutcome.Resolved(setOf(mate.id)))
        assertThat(r(me, PrincipalQuery.User(stranger.id), sc.ws)).isInstanceOf(PrincipalOutcome.Denied::class.java)      // not in the tenant
        assertThat(r(me, PrincipalQuery.User(disabled.id), sc.ws)).isInstanceOf(PrincipalOutcome.Denied::class.java)
        val other = tenants.create(slug(), "ResOther", stranger.id)
        assertThat(r(me, PrincipalQuery.User(stranger.id, other.id), sc.ws)).isInstanceOf(PrincipalOutcome.Denied::class.java)    // cross-tenant principal refused
        assertThat(r(me, PrincipalQuery.User(mate.id, DEFAULT), sc.ws)).isInstanceOf(PrincipalOutcome.Resolved::class.java)
        tenants.setMember(DEFAULT, mate.id, TenantRole.MEMBER); tenants.removeMember(DEFAULT, mate.id)
        assertThat(r(me, PrincipalQuery.User(mate.id), sc.ws)).isInstanceOf(PrincipalOutcome.Denied::class.java)                  // removed from the tenant
    }

    @Test
    fun `resolver - roles, groups, department managers and requester checks`() {
        val sc = scenario(); val me = user(sc.user.id); val admin = fx.user("radm"); fx.member(sc.ws, admin, "WORKSPACE_ADMIN")
        val ta = fx.user("rta"); tenants.setMember(DEFAULT, ta.id, TenantRole.TENANT_ADMIN)
        assertThat(r(me, PrincipalQuery.Role("WORKSPACE_ADMIN"), sc.ws)).isEqualTo(PrincipalOutcome.Resolved(setOf(admin.id)))
        assertThat(r(me, PrincipalQuery.Role("WORKSPACE_ADMIN"), null)).isInstanceOf(PrincipalOutcome.Denied::class.java)       // a workspace role needs a workspace in the context
        assertThat(ids(r(me, PrincipalQuery.Role("TENANT_ADMIN"), sc.ws))).contains(ta.id)                                       // the DEFAULT tenant may hold other admins from other tests
        assertThat(r(me, PrincipalQuery.Role("GOD"), sc.ws)).isInstanceOf(PrincipalOutcome.Denied::class.java)
        assertThat(r(me, PrincipalQuery.Group("g1"), sc.ws)).isInstanceOf(PrincipalOutcome.Denied::class.java)
        assertThat(r(me, PrincipalQuery.DepartmentManager(null), sc.ws)).isInstanceOf(PrincipalOutcome.Denied::class.java)
        val outsider = user(fx.user("rout").id)
        assertThat(r(outsider, PrincipalQuery.Role("WORKSPACE_ADMIN"), sc.ws)).isInstanceOf(PrincipalOutcome.Denied::class.java)  // requester not in the workspace
        // TEMPORARY V2 POLICY: only USER actors are authorised, whatever they ask for and with or without a workspace
        for (k in listOf(ActorKind.SYSTEM, ActorKind.SERVICE, ActorKind.APP_TOKEN)) for (w in listOf(sc.ws, null)) {
            assertThat(r(Principal(k), PrincipalQuery.Role("TENANT_ADMIN"), w)).describedAs("$k workspace=$w").isInstanceOf(PrincipalOutcome.Denied::class.java)
            assertThat(r(Principal(k, sc.user.id), PrincipalQuery.User(sc.user.id), w)).describedAs("$k with a user id workspace=$w").isInstanceOf(PrincipalOutcome.Denied::class.java)
        }
        assertThat(r(me, PrincipalQuery.Role("TENANT_ADMIN"), sc.ws, tenant = UUID.randomUUID())).isInstanceOf(PrincipalOutcome.Denied::class.java)   // unknown tenant
    }

    @Test
    fun `resolver - a suspended tenant addresses nobody`() {
        val sc = scenario(); val t = tenants.create(slug(), "ResSusp"); jdbc.update("UPDATE workspaces SET tenant_id = ? WHERE id = ?", t.id, sc.ws)
        tenants.setStatus(t.id, TenantStatus.SUSPENDED)
        assertThat(r(user(sc.user.id), PrincipalQuery.Role("WORKSPACE_ADMIN"), sc.ws, tenant = t.id)).isInstanceOf(PrincipalOutcome.Denied::class.java)
        assertThat(r(user(sc.user.id), PrincipalQuery.User(sc.user.id), sc.ws, tenant = t.id)).isInstanceOf(PrincipalOutcome.Denied::class.java)
    }
}
