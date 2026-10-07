package com.systemwebstudio.tenancy

import com.systemwebstudio.access.adapters.*
import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.http.MediaType
import org.springframework.security.web.FilterChainProxy
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

/**
 * C1 · PUBLIC_SITE (D-C0-35, `docs/contracts/v2/published-runtime.md` §4): the anonymous visitor of a published site may run a READ-ONLY query that the ACTIVE
 * release lists as public, in LIVE mode, and nothing else. Real database and Spring Security chain, no mocks of the policy.
 *
 * Every refusal must be the same disclosure-safe answer (`404 QUERY_NOT_FOUND`); the allow-list provider does not exist on the baseline yet (C2 / C0), so the
 * tests plug one in only where a test is about the allow-list, and the Spring-wired policy is shown to deny everything.
 */
class PublicSiteAuthorizationTests : IntegrationTestBase() {
    @Autowired lateinit var wired: PublicSiteAuthorizer
    @Autowired lateinit var gateway: GatewayAuthorizer
    @Autowired lateinit var port: AccessPort
    @Autowired lateinit var gate: TenantGate
    @Autowired lateinit var resolver: PrincipalResolver
    @Autowired lateinit var tenants: TenantService
    @Autowired lateinit var filterChains: FilterChainProxy

    private class Site(val sc: Scenario, val slug: String, val releaseId: UUID, val versionId: UUID, val tenantId: UUID) {
        val ctx get() = PublicSiteGatewayContext(tenantId, sc.ws, sc.projectId, slug, releaseId)
    }

    /** a project with a site whose active release is a deployment in [status] / [visibility] (the shape `sites` + `deployments` have in production) */
    private fun publish(status: String = "RUNNING", visibility: String = "PUBLIC"): Site {
        val sc = scenario()
        val versionId = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? ORDER BY version_number LIMIT 1", UUID::class.java, sc.projectId)!!
        val release = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?,?,?,?,?,?,?,'mock')",
            release, sc.ws, sc.projectId, versionId, sc.user.id, visibility, status)
        val slug = "pub-" + UUID.randomUUID().toString().take(10)
        jdbc.update("INSERT INTO sites (project_id, slug, current_deployment_id) VALUES (?,?,?) ON CONFLICT (project_id) DO UPDATE SET slug = EXCLUDED.slug, current_deployment_id = EXCLUDED.current_deployment_id",
            sc.projectId, slug, release)
        val tenant = jdbc.queryForObject("SELECT tenant_id FROM workspaces WHERE id = ?", UUID::class.java, sc.ws)!!
        return Site(sc, slug, release, versionId, tenant)
    }

    private fun authorizer(list: PublicQueryAllowList?): PublicSiteAuthorizer {
        val bf = DefaultListableBeanFactory(); if (list != null) bf.registerSingleton("allowList", list)
        return PublicSiteAuthorizer(jdbc, bf.getBeanProvider(PublicQueryAllowList::class.java))
    }

    /** an allow-list as the release would carry it: per release id, nothing for any other */
    private fun listing(vararg entries: Pair<UUID, Set<String>>) = PublicQueryAllowList { _, _, release -> entries.toMap()[release] ?: emptySet() }

    private fun req(s: Site, op: String = "QUERY_EXECUTE", mode: String = "LIVE", query: String? = "orders-list", ctx: PublicSiteGatewayContext = s.ctx) = PublicSiteRequest(ctx, op, mode, query)
    private fun allowed(a: PublicSiteAuthorizer, r: PublicSiteRequest) = a.authorize(r) is PublicSiteDecision.Allowed
    private fun refused(a: PublicSiteAuthorizer, r: PublicSiteRequest) = a.authorize(r) is PublicSiteDecision.NotFound
    private val gatewayOps = GatewayAuthorizer.REQUIRED.keys
    private fun pub(workspace: UUID?, project: UUID?, version: UUID?, op: String, tenant: UUID, userId: UUID? = null) =
        GatewayAuthRequest(Principal(com.systemwebstudio.tenancy.ActorKind.PUBLIC_SITE, userId), tenant, workspace, project, version?.toString(), op, null)

    // ------------------------------------------------------------------------------------------------ the actor
    @Test
    fun `PUBLIC_SITE is a new actor kind that is not a USER and carries no user id, role or permission`() {
        assertThat(ActorKind.entries.map { it.name }.take(4)).describedAs("the four existing kinds keep their names and order").containsExactly("USER", "SYSTEM", "APP_TOKEN", "SERVICE")
        assertThat(ActorKind.entries.map { it.name }).contains("PUBLIC_SITE")
        assertThat(ActorKind.PUBLIC_SITE).isNotEqualTo(ActorKind.USER)
        val s = publish()
        val p = (authorizer(listing(s.releaseId to setOf("orders-list"))).authorize(req(s)) as PublicSiteDecision.Allowed).principal
        assertThat(p.kind).isEqualTo(ActorKind.PUBLIC_SITE)
        assertThat(p.toPrincipal()).isEqualTo(Principal(ActorKind.PUBLIC_SITE, null))
        assertThat(PublicSitePrincipal::class.java.declaredFields.map { it.name }.toSet())
            .describedAs("only server-derived ids; no user, role, permission or membership").containsExactlyInAnyOrder("tenantId", "workspaceId", "projectId", "siteSlug", "releaseId", "appVersionId")
        assertThat(PublicSiteGatewayContext::class.java.declaredFields.map { it.name }.toSet())
            .describedAs("the trusted input has no field for a header, a cookie, a token or an actor kind").containsExactlyInAnyOrder("tenantId", "workspaceId", "projectId", "siteSlug", "releaseId", "appVersionId")
        assertThat(p.auditAttributes("orders-list")).containsEntry("actorKind", "PUBLIC_SITE").containsEntry("site", s.slug).containsEntry("release", s.releaseId.toString()).containsEntry("query", "orders-list")
        assertThat(p.auditAttributes("q").keys).describedAs("no visitor identity").doesNotContain("user", "userId", "ip", "actor")
    }

    @Test
    fun `the USER permission model never admits a PUBLIC_SITE - app access, tenant gate users and principal resolution all refuse it, even with a real user id attached`() {
        val s = publish(); val me = s.sc.user.id
        for (c in listOf("APP_VIEW", "APP_USE", "APP_EDIT", "ACTION_EXECUTE", "DATA_MUTATE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE", "DATA_SOURCE_MANAGE", "QUERY_EXECUTE"))
            for (uid in listOf(null, me))
                assertThat(port.check(AppAccessRequest(Principal(ActorKind.PUBLIC_SITE, uid), s.tenantId, s.sc.ws, s.sc.projectId, c, "LIVE")).allowed).describedAs("port $c user=$uid").isFalse()
        assertThat(resolver.resolve(PrincipalContext(Principal(ActorKind.PUBLIC_SITE, me), s.tenantId, s.sc.ws), PrincipalQuery.User(me, s.tenantId))).isInstanceOf(PrincipalOutcome.Denied::class.java)
        assertThat(gate.isEnabled(s.tenantId)).describedAs("control: the tenant gate itself is a tenant question, not an actor one").isTrue()
    }

    // ------------------------------------------------------------------------------------------------ the one thing it may do
    @Test
    fun `LIVE + active public release + a query the release lists is allowed, with the release's own version`() {
        val s = publish(); val a = authorizer(listing(s.releaseId to setOf("orders-list", "customers")))
        val d = a.authorize(req(s)) as PublicSiteDecision.Allowed
        assertThat(d.principal).isEqualTo(PublicSitePrincipal(s.tenantId, s.sc.ws, s.sc.projectId, s.slug, s.releaseId, s.versionId))
        assertThat(allowed(a, req(s, query = "customers"))).isTrue()
        assertThat(allowed(a, req(s, ctx = s.ctx.copy(appVersionId = s.versionId))))
            .describedAs("a version supplied by the gateway adapter is accepted only when it IS the release's").isTrue()
        assertThat(refused(a, req(s, ctx = s.ctx.copy(appVersionId = UUID.randomUUID())))).describedAs("another version").isTrue()
    }

    @Test
    fun `LIVE only - TEST, draft, case variants and anything else is refused`() {
        val s = publish(); val a = authorizer(listing(s.releaseId to setOf("orders-list")))
        assertThat(allowed(a, req(s))).describedAs("control").isTrue()
        for (mode in listOf("TEST", "test", "Live", "live", "DRAFT", "PREVIEW", "", " LIVE", "LIVE ", "PRODUCTION"))
            assertThat(refused(a, req(s, mode = mode))).describedAs("mode '$mode'").isTrue()
    }

    @Test
    fun `an inactive release is refused - not RUNNING, private, offline, rolled back, archived project, suspended tenant`() {
        val a = { s: Site -> authorizer(listing(s.releaseId to setOf("orders-list"))) }
        for (status in listOf("QUEUED", "POLICY_CHECK", "SECURITY_CHECK", "BUILDING", "DEPLOYING", "ROLLING_BACK", "FAILED", "ROLLED_BACK")) {
            val s = publish(status = status); assertThat(refused(a(s), req(s))).describedAs("deployment $status").isTrue()
        }
        val priv = publish(visibility = "PRIVATE"); assertThat(refused(a(priv), req(priv))).describedAs("a PRIVATE release needs its own site session, not modelled in V1").isTrue()

        val offline = publish(); jdbc.update("UPDATE sites SET current_deployment_id = NULL WHERE project_id = ?", offline.sc.projectId)
        assertThat(refused(a(offline), req(offline))).describedAs("unpublished: the pointer is NULL").isTrue()

        val archived = publish(); jdbc.update("UPDATE projects SET lifecycle = 'ARCHIVED' WHERE id = ?", archived.sc.projectId)
        assertThat(refused(a(archived), req(archived))).describedAs("archived project").isTrue()
        val deleted = publish(); jdbc.update("UPDATE projects SET active = false WHERE id = ?", deleted.sc.projectId)
        assertThat(refused(a(deleted), req(deleted))).describedAs("deleted project").isTrue()

        val frozen = publish(); jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", frozen.tenantId)
        try { assertThat(refused(a(frozen), req(frozen))).describedAs("suspended tenant").isTrue() } finally { jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", frozen.tenantId) }

        val wrongSlug = publish(); assertThat(refused(a(wrongSlug), req(wrongSlug, ctx = wrongSlug.ctx.copy(siteSlug = "another-site")))).describedAs("the slug must be the site's").isTrue()
    }

    @Test
    fun `the pointer is read on every call - after a rollback the old release is refused and the restored one is allowed`() {
        val s = publish()
        val newer = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?,?,?,?,?,'PUBLIC','RUNNING','mock')", newer, s.sc.ws, s.sc.projectId, s.versionId, s.sc.user.id)
        val a = authorizer(listing(s.releaseId to setOf("orders-list"), newer to setOf("orders-list")))
        assertThat(allowed(a, req(s))).describedAs("old release is active").isTrue()
        jdbc.update("UPDATE sites SET current_deployment_id = ? WHERE project_id = ?", newer, s.sc.projectId)
        assertThat(refused(a, req(s))).describedAs("the pointer moved: the previous release is no longer the active one").isTrue()
        assertThat(allowed(a, req(s, ctx = s.ctx.copy(releaseId = newer)))).describedAs("the new active release").isTrue()
        jdbc.update("UPDATE sites SET current_deployment_id = ? WHERE project_id = ?", s.releaseId, s.sc.projectId)
        assertThat(allowed(a, req(s))).describedAs("rolled back").isTrue()
        jdbc.update("UPDATE sites SET current_deployment_id = NULL WHERE project_id = ?", s.sc.projectId)
        assertThat(refused(a, req(s))).describedAs("unpublished").isTrue()
    }

    // ------------------------------------------------------------------------------------------------ the allow-list
    @Test
    fun `an empty, missing or failing allow-list denies everything - the Spring-wired policy has no provider yet and so denies all`() {
        val s = publish()
        assertThat(refused(authorizer(listing(s.releaseId to emptySet())), req(s))).describedAs("empty list").isTrue()
        assertThat(refused(authorizer(null), req(s))).describedAs("no provider").isTrue()
        assertThat(refused(authorizer(PublicQueryAllowList { _, _, _ -> throw IllegalStateException("store down") }), req(s))).describedAs("provider failure fails closed").isTrue()
        assertThat(refused(wired, req(s))).describedAs("the bean wired in this application (no provider bean exists on the baseline)").isTrue()
        assertThat(DenyAllPublicQueryAllowList.publicQueryIds(s.tenantId, s.sc.projectId, s.releaseId)).isEmpty()
    }

    @Test
    fun `an unpublished query is refused, and so is every malformed query id`() {
        val s = publish(); val a = authorizer(listing(s.releaseId to setOf("orders-list")))
        assertThat(refused(a, req(s, query = "orders-delete"))).describedAs("not listed").isTrue()
        assertThat(refused(a, req(s, query = "ORDERS-LIST"))).describedAs("ids are exact").isTrue()
        for (bad in listOf<String?>(null, "", " ", "../orders-list", "orders-list/../x", "a".repeat(129), "q;DROP", "q\n", "*"))
            assertThat(refused(a, req(s, query = bad))).describedAs("query id '${bad?.take(20)}'").isTrue()
    }

    @Test
    fun `a query of a foreign release is refused and the provider is asked only about the release of the request`() {
        val a = publish(); val b = publish()
        val asked = mutableListOf<Triple<UUID, UUID, UUID>>()
        val list = PublicQueryAllowList { t, p, r -> asked += Triple(t, p, r); mapOf(a.releaseId to setOf("a-query"), b.releaseId to setOf("b-query"))[r] ?: emptySet() }
        val auth = authorizer(list)
        assertThat(allowed(auth, req(a, query = "a-query"))).isTrue()
        assertThat(refused(auth, req(a, query = "b-query"))).describedAs("B's public query through A's release").isTrue()
        assertThat(refused(auth, req(b, query = "a-query"))).describedAs("A's public query through B's release").isTrue()
        assertThat(refused(auth, req(a, ctx = a.ctx.copy(releaseId = b.releaseId), query = "b-query"))).describedAs("A's context naming B's release").isTrue()
        assertThat(refused(auth, req(a, ctx = b.ctx.copy(siteSlug = a.slug), query = "b-query"))).describedAs("B's ids under A's slug").isTrue()
        assertThat(asked).describedAs("the provider is never consulted for a release that is not the active one of the named site").allMatch { (t, p, r) -> (t == a.tenantId && p == a.sc.projectId && r == a.releaseId) || (t == b.tenantId && p == b.sc.projectId && r == b.releaseId) }
    }

    // ------------------------------------------------------------------------------------------------ QUERY_EXECUTE only
    @Test
    fun `QUERY_EXECUTE is the only operation - mutation, data source management, schema, cache refresh, events, sync, webhooks and unknown names are refused`() {
        val s = publish(); val a = authorizer(listing(s.releaseId to setOf("orders-list")))
        assertThat(allowed(a, req(s, op = "QUERY_EXECUTE"))).isTrue()
        for (op in gatewayOps - "QUERY_EXECUTE")
            assertThat(refused(a, req(s, op = op))).describedAs("public policy, operation $op").isTrue()
        for (op in listOf("MUTATION_EXECUTE", "DATASOURCE_MANAGE", "SCHEMA_DISCOVER", "SCHEMA_SAMPLE", "CACHE_REFRESH", "query_execute", "", "ACTION_EXECUTE", "WORKFLOW_START", "RAW_SQL"))
            assertThat(refused(a, req(s, op = op))).describedAs("operation '$op'").isTrue()

        // the same rule at C3's GatewayAuthorizer port (the C1 policy core the data gateway asks)
        assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, s.versionId, "QUERY_EXECUTE", s.tenantId)).allowed).describedAs("control: QUERY_EXECUTE at the gateway").isTrue()
        for (op in gatewayOps - "QUERY_EXECUTE")
            assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, s.versionId, op, s.tenantId)).allowed).describedAs("gateway $op").isFalse()
        for (op in listOf("MUTATION_EXECUTE", "DATASOURCE_MANAGE", "SCHEMA_DISCOVER", "SCHEMA_SAMPLE", "CACHE_REFRESH"))
            assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, s.versionId, op, s.tenantId))).describedAs("explicit $op").isInstanceOf(AccessDecision.Denied::class.java)
        assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, s.versionId, "NOT_AN_OPERATION", s.tenantId)).allowed).isFalse()
    }

    @Test
    fun `at the data gateway a PUBLIC_SITE needs the full server-derived scope and the active release's version - nothing less, nothing foreign`() {
        val s = publish(); val other = publish(); val t = s.tenantId
        assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, s.versionId, "QUERY_EXECUTE", t)).allowed).isTrue()
        assertThat(gateway.authorize(pub(null, s.sc.projectId, s.versionId, "QUERY_EXECUTE", t)).allowed).describedAs("no workspace").isFalse()
        assertThat(gateway.authorize(pub(s.sc.ws, null, s.versionId, "QUERY_EXECUTE", t)).allowed).describedAs("no project").isFalse()
        assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, null, "QUERY_EXECUTE", t)).allowed).describedAs("no release version").isFalse()
        assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, other.versionId, "QUERY_EXECUTE", t)).allowed).describedAs("the version of another project's release").isFalse()
        assertThat(gateway.authorize(GatewayAuthRequest(Principal(ActorKind.PUBLIC_SITE), t, s.sc.ws, s.sc.projectId, "not-a-uuid", "QUERY_EXECUTE", null)).allowed).isFalse()
        assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, s.versionId, "QUERY_EXECUTE", t, userId = s.sc.user.id)).allowed).describedAs("a PUBLIC_SITE with a user id attached is refused").isFalse()
        jdbc.update("UPDATE deployments SET status = 'DEPLOYING' WHERE id = ?", s.releaseId)
        assertThat(gateway.authorize(pub(s.sc.ws, s.sc.projectId, s.versionId, "QUERY_EXECUTE", t)).allowed).describedAs("release no longer active").isFalse()
    }

    @Test
    fun `SYSTEM, SERVICE and APP_TOKEN stay denied for every operation, with and without a user id`() {
        val s = publish()
        for (k in listOf(ActorKind.SYSTEM, ActorKind.SERVICE, ActorKind.APP_TOKEN)) for (uid in listOf<UUID?>(null, s.sc.user.id)) for (op in gatewayOps)
            assertThat(gateway.authorize(GatewayAuthRequest(Principal(k, uid), s.tenantId, s.sc.ws, s.sc.projectId, s.versionId.toString(), op, null)).allowed).describedAs("$k user=$uid $op").isFalse()
        // control: a real USER of the workspace is still authorised through the unchanged USER path
        assertThat(gateway.authorize(GatewayAuthRequest(Principal(ActorKind.USER, s.sc.user.id), s.tenantId, s.sc.ws, s.sc.projectId, null, "QUERY_EXECUTE", null)).allowed).describedAs("control: USER path unchanged").isTrue()
    }

    // ------------------------------------------------------------------------------------------------ scope and disclosure
    @Test
    fun `a foreign tenant, workspace or project is refused, and every refusal is the same 404`() {
        val a = publish(); val b = publish(); val otherTenant = tenants.create("pub-" + UUID.randomUUID().toString().take(8), "PubOther").id
        val auth = authorizer(listing(a.releaseId to setOf("orders-list"), b.releaseId to setOf("orders-list")))
        assertThat(allowed(auth, req(a))).describedAs("control").isTrue()
        val forged = mapOf(
            "foreign tenant" to a.ctx.copy(tenantId = otherTenant), "B's tenant id" to a.ctx.copy(tenantId = b.tenantId.takeIf { it != a.tenantId } ?: otherTenant),
            "foreign workspace" to a.ctx.copy(workspaceId = b.sc.ws), "foreign project" to a.ctx.copy(projectId = b.sc.projectId), "foreign release" to a.ctx.copy(releaseId = b.releaseId),
            "unknown release" to a.ctx.copy(releaseId = UUID.randomUUID()), "nil ids" to a.ctx.copy(tenantId = UUID(0, 0), workspaceId = UUID(0, 0), projectId = UUID(0, 0), releaseId = UUID(0, 0)),
            "unknown slug" to a.ctx.copy(siteSlug = "no-such-site"), "B's slug" to a.ctx.copy(siteSlug = b.slug), "empty slug" to a.ctx.copy(siteSlug = "")
        )
        val bodies = HashSet<String>()
        for ((label, ctx) in forged) {
            val d = auth.authorize(req(a, ctx = ctx))
            assertThat(d).describedAs(label).isInstanceOf(PublicSiteDecision.NotFound::class.java)
            val e = (d as PublicSiteDecision.NotFound).toException()
            assertThat(e.status.value()).describedAs("$label status").isEqualTo(404)
            bodies += e.code + "|" + e.message
        }
        // also the other refusals: TEST, an operation, an unlisted query and an inactive release give the very same answer
        for (d in listOf(auth.authorize(req(a, mode = "TEST")), auth.authorize(req(a, op = "MUTATION_EXECUTE")), auth.authorize(req(a, query = "nope")), authorizer(null).authorize(req(a))))
            bodies += (d as PublicSiteDecision.NotFound).toException().let { it.code + "|" + it.message }
        assertThat(bodies).describedAs("one code and one message for every refusal: no existence oracle").hasSize(1)
        assertThat(bodies.single()).isEqualTo("${PublicSiteResponses.NOT_FOUND_CODE}|${PublicSiteResponses.NOT_FOUND_MESSAGE}")
        assertThat(PublicSiteResponses.NOT_FOUND_STATUS).isEqualTo(404)
        // the audit reason (never returned) is a short constant: it does not echo ids or slugs supplied by the caller
        for ((_, ctx) in forged) assertThat((auth.authorize(req(a, ctx = ctx)) as PublicSiteDecision.NotFound).auditReason)
            .isIn("release not active", "version is not the release's", "release has no public query", "query not public", "query id invalid", "operation not public", "mode not public", "authorization failed")
    }

    // ------------------------------------------------------------------------------------------------ the HTTP edge: no browser authority, no Studio session
    private val publicRoute = "/sites/some-site/_data/queries/orders-list/run"

    private fun status(path: String, vararg headers: Pair<String, String>, body: String = """{"params":{}}""") =
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).also { b -> headers.forEach { (k, v) -> b.header(k, v) } }).andReturn().response.status

    @Test
    fun `the public route needs no USER session and no CSRF token - and opens nothing else under sites or api`() {
        val anonymous = status(publicRoute)
        assertThat(anonymous).describedAs("the security chain lets the anonymous POST through (the controller is C0's: 404 / 405 until it exists)").isNotIn(401, 403)
        // exactly the frozen shape: a single segment slug, `_data/queries/<id>/run`, POST
        for (p in listOf("/sites/s/_data/queries/q/run/extra", "/sites/s/_data/queries/q", "/sites/s/_data/queries/a/b/run", "/sites/s/_data/mutations/m/run", "/sites/s/_data/actions/a/run",
            "/sites/s/_data/workflows/w/runs", "/sites/s/_data/query", "/sites/s/other/queries/q/run", "/sites/a/b/_data/queries/q/run"))
            assertThat(status(p)).describedAs("POST $p stays closed").isIn(401, 403)
        // not a global POST on /sites/** and not an anonymous /api/v1 route
        assertThat(status("/sites/s/x")).isIn(401, 403)
        assertThat(status("/api/v1/workspaces/${UUID.randomUUID()}/projects/${UUID.randomUUID()}/app-runtime/queries/orders-list/run")).describedAs("the authenticated runtime route is still authenticated").isIn(401, 403)
        assertThat(status("/api/v1/me")).isIn(401, 403)
        // GET on the data route is not opened by this change (GET /sites/** was already the static site gateway)
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(publicRoute)).andReturn().response.status).describedAs("PUT is not opened").isIn(401, 403)
    }

    @Test
    fun `no Studio authority is used - the public route has no session, no CSRF, no user filter, and a Studio login changes nothing`() {
        val sc = scenario()
        val anonymous = status(publicRoute)
        val withStudioSession = sc.s.post(publicRoute, """{"params":{}}""").response.status      // logged-in Studio session + valid CSRF token
        assertThat(withStudioSession).describedAs("a Studio session neither unlocks nor blocks the public route").isEqualTo(anonymous)
        val chain = filterChains.getFilters(publicRoute)!!.map { it.javaClass.simpleName }
        assertThat(chain).describedAs("the sites chain: no session context, no CSRF, no active-user check, no login").doesNotContain("SecurityContextHolderFilter", "CsrfFilter", "ActiveUserFilter", "UsernamePasswordAuthenticationFilter", "SecurityContextPersistenceFilter", "BasicAuthenticationFilter")
        val studio = filterChains.getFilters("/api/v1/me")!!.map { it.javaClass.simpleName }
        assertThat(studio).describedAs("control: the Studio chain still has them").contains("SecurityContextHolderFilter", "CsrfFilter", "ActiveUserFilter")
    }

    @Test
    fun `forged browser authority cannot change the outcome - tenant, workspace, project, release, role, actor kind, tokens and cookies in headers or body are ignored by the chain`() {
        val baseline = status(publicRoute)
        val forged = arrayOf(
            "X-Tenant-Id" to UUID.randomUUID().toString(), "X-Workspace-Id" to UUID.randomUUID().toString(), "X-Project-Id" to UUID.randomUUID().toString(),
            "X-Release-Id" to UUID.randomUUID().toString(), "X-Deployment-Id" to UUID.randomUUID().toString(), "X-Actor-Id" to UUID.randomUUID().toString(),
            "X-Actor-Kind" to "SYSTEM", "X-Role" to "WORKSPACE_ADMIN", "X-Permissions" to "QUERY_EXECUTE,DATA_MUTATE", "Authorization" to "Bearer forged.token.value",
            "X-Api-Key" to "key-123", "X-Site-Token" to "token-123", "Cookie" to "SESSION=forged; XSRF-TOKEN=forged"
        )
        assertThat(status(publicRoute, *forged)).describedAs("headers").isEqualTo(baseline)
        val body = """{"queryId":"x","params":{},"tenantId":"${UUID.randomUUID()}","workspaceId":"${UUID.randomUUID()}","projectId":"${UUID.randomUUID()}","releaseId":"${UUID.randomUUID()}","actorKind":"SYSTEM","role":"ADMIN","permissions":["DATA_MUTATE"]}"""
        assertThat(status(publicRoute, body = body)).describedAs("body fields are not the chain's business and cannot open or close it").isEqualTo(baseline)
        // the policy itself has no input that could carry any of this: it takes the server-resolved context only (structural check in the first test)
        assertThat(PublicSiteRequest::class.java.declaredFields.map { it.name }.toSet()).containsExactlyInAnyOrder("context", "operation", "mode", "queryId")
    }
}
