package com.systemwebstudio.access.adapters

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.tenancy.ActorKind
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/*
 * C1 · PUBLIC_SITE (D-C0-35, docs/contracts/v2/published-runtime.md §4): the anonymous visitor of a published site, seen through the same-origin sites gateway.
 *
 *   public browser -> sites gateway -> API route -> [C0/C2: slug -> site -> ACTIVE release -> project -> workspace -> tenant] -> PublicSiteGatewayContext
 *     -> PublicSiteAuthorizer (this file) -> PublicSitePrincipal -> C3 DataGateway.runQuery (QUERY_EXECUTE only)
 *
 * What a PUBLIC_SITE may do, V1: run a READ-ONLY query that the ACTIVE release lists as public, in LIVE mode. Nothing else: no action, no mutation, no workflow,
 * no TEST, no draft, no schema discovery, no cache refresh, no management. It has no user id, no role, no membership and no permission set; the USER permission
 * model is never consulted for it (there is nobody to call `AccessService.forProject` for).
 *
 * Authority comes from the SERVER only. [PublicSiteGatewayContext] is built by the trusted gateway adapter from its own resolution of the slug; it is never read
 * from a header, cookie, query string or body, and there is no public token, site token, API key or Authorization header in V1. Even so, this policy does not
 * trust the context: every id in it is re-verified against the database on every call (the pointer is read per request, so rollback / unpublish bite at once).
 */

/**
 * What the trusted gateway adapter resolved on the server for ONE public request. Every field is derived from the slug, never supplied by the browser.
 * [appVersionId] is optional: when given it must equal the version pinned by the release; the principal always carries the release's own version.
 */
data class PublicSiteGatewayContext(
    val tenantId: UUID,
    val workspaceId: UUID,
    val projectId: UUID,
    val siteSlug: String,
    val releaseId: UUID,
    val appVersionId: UUID? = null
)

/** One public call: the resolved context + what is asked. [operation] is the NAME of C3's `GatewayOperation`; [mode] is `LIVE` (anything else is refused). */
data class PublicSiteRequest(
    val context: PublicSiteGatewayContext,
    val operation: String,
    val mode: String,
    val queryId: String?
)

/**
 * The verified PUBLIC_SITE identity. It carries only server-derived ids and NO user id; [toPrincipal] is the generic [Principal] with kind PUBLIC_SITE, which every
 * other adapter of this package refuses (`ActorPolicy.userOrNull` is null for it). Created only by [PublicSiteAuthorizer] after a successful decision.
 */
data class PublicSitePrincipal(
    val tenantId: UUID,
    val workspaceId: UUID,
    val projectId: UUID,
    val siteSlug: String,
    val releaseId: UUID,
    val appVersionId: UUID
) {
    val kind: ActorKind get() = ActorKind.PUBLIC_SITE
    fun toPrincipal(): Principal = Principal(ActorKind.PUBLIC_SITE, null)

    /** audit shape (H-C1 item 4): actor kind + site + release + query; never a visitor identity (the hashed IP is C0's, as for the forms route) */
    fun auditAttributes(queryId: String?): Map<String, Any?> =
        mapOf("actorKind" to ActorKind.PUBLIC_SITE.name, "site" to siteSlug, "release" to releaseId.toString(), "query" to queryId)
}

sealed interface PublicSiteDecision {
    data class Allowed(val principal: PublicSitePrincipal) : PublicSiteDecision
    /**
     * Every refusal is the SAME answer to the visitor: `404 QUERY_NOT_FOUND`, same body, whatever the cause (unknown slug, offline site, TEST, a query that is not
     * public, another release, another tenant ...). [auditReason] is for the audit trail only and must never reach the response.
     */
    data class NotFound(val auditReason: String) : PublicSiteDecision {
        fun toException(): ApiException = ApiException.notFound(PublicSiteResponses.NOT_FOUND_CODE, PublicSiteResponses.NOT_FOUND_MESSAGE)
    }
}

/** The mapping of the public route (C1's side of the contract): there is no 401 (nobody to authenticate) and no 403 (it would be an existence oracle). */
object PublicSiteResponses {
    const val NOT_FOUND_CODE = "QUERY_NOT_FOUND"
    const val NOT_FOUND_MESSAGE = "Query not found"
    /** strict parsing, rate limiting and the 5xx of an unavailable data platform belong to C0 / C3; they never reveal a refusal of this policy */
    const val NOT_FOUND_STATUS = 404
}

/**
 * Which queries of a release are public: the immutable allow-list, derived by the server at publish time and snapshotted into the release (published-runtime.md §5).
 * It does NOT exist on the baseline yet (C2 / C0 own where it is stored, H-C2 item 5); until a provider bean exists the policy uses [DenyAllPublicQueryAllowList],
 * so nothing is public. An implementation must answer only for the release it is asked about and must never include a mutating or writable query.
 */
fun interface PublicQueryAllowList {
    fun publicQueryIds(tenantId: UUID, projectId: UUID, releaseId: UUID): Set<String>
}

/** Default and fail-closed answer: no query of any release is public. */
object DenyAllPublicQueryAllowList : PublicQueryAllowList {
    override fun publicQueryIds(tenantId: UUID, projectId: UUID, releaseId: UUID): Set<String> = emptySet()
}

/**
 * Read-only definition of "ACTIVE release" for the public runtime, the same pointer the sites gateway serves (`sites.current_deployment_id`), stricter than
 * `SiteService.live` (which also serves DEPLOYING): the deployment must be RUNNING and PUBLIC (a PRIVATE site needs its own session, not modelled in V1), of the
 * site's own project and workspace; the project active and not archived; the workspace's tenant ACTIVE and equal to the tenant claimed. Any error => no release.
 */
internal class PublicReleaseLookup(private val jdbc: JdbcTemplate) {
    private val base = """FROM sites s
        JOIN projects p ON p.id = s.project_id AND p.active AND p.lifecycle = 'ACTIVE'
        JOIN workspaces w ON w.id = p.workspace_id
        JOIN tenants t ON t.id = w.tenant_id AND t.status = 'ACTIVE'
        JOIN deployments d ON d.id = s.current_deployment_id AND d.project_id = p.id AND d.workspace_id = p.workspace_id AND d.status = 'RUNNING' AND d.visibility = 'PUBLIC'
        WHERE s.project_id = ? AND p.workspace_id = ? AND w.tenant_id = ?"""

    /** the version pinned by [releaseId] iff it is the active public release of site [slug] of this project / workspace / tenant */
    fun versionOfActiveRelease(tenantId: UUID, workspaceId: UUID, projectId: UUID, slug: String, releaseId: UUID): UUID? = try {
        jdbc.queryForList("SELECT d.version_id $base AND s.slug = ? AND d.id = ?", UUID::class.java, projectId, workspaceId, tenantId, slug, releaseId).firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** true iff the ACTIVE public release of this project is the one pinned to [versionId] (the gateway path knows the version, not the release id) */
    fun activeReleaseHasVersion(tenantId: UUID, workspaceId: UUID, projectId: UUID, versionId: UUID): Boolean = try {
        jdbc.queryForList("SELECT d.version_id $base AND d.version_id = ?", UUID::class.java, projectId, workspaceId, tenantId, versionId).isNotEmpty()
    } catch (e: Exception) {
        false
    }
}

/**
 * The policy of the public route. Default deny; every failure, including an exception, is [PublicSiteDecision.NotFound].
 * Order: operation (QUERY_EXECUTE only) -> mode (LIVE only) -> query id -> active release re-verified against the database -> (optional) version equality ->
 * release allow-list (empty or unavailable = deny, the query must be listed).
 */
@Component("c1PublicSiteAuthorizer")
class PublicSiteAuthorizer(jdbc: JdbcTemplate, private val allowLists: ObjectProvider<PublicQueryAllowList>) {
    private val releases = PublicReleaseLookup(jdbc)

    companion object {
        const val LIVE = "LIVE"
        const val QUERY_EXECUTE = "QUERY_EXECUTE"
        private val QUERY_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }

    fun authorize(r: PublicSiteRequest): PublicSiteDecision = try {
        decideInner(r)
    } catch (e: Exception) {
        PublicSiteDecision.NotFound("authorization failed")      // fail closed
    }

    private fun decideInner(r: PublicSiteRequest): PublicSiteDecision {
        if (r.operation != QUERY_EXECUTE) return PublicSiteDecision.NotFound("operation not public")
        if (r.mode != LIVE) return PublicSiteDecision.NotFound("mode not public")           // exact match: TEST, test, null-ish, draft ... all refused
        val queryId = r.queryId?.takeIf { QUERY_ID.matches(it) } ?: return PublicSiteDecision.NotFound("query id invalid")
        val c = r.context
        val version = releases.versionOfActiveRelease(c.tenantId, c.workspaceId, c.projectId, c.siteSlug, c.releaseId)
            ?: return PublicSiteDecision.NotFound("release not active")
        if (c.appVersionId != null && c.appVersionId != version) return PublicSiteDecision.NotFound("version is not the release's")
        val allowed = (allowLists.getIfAvailable { DenyAllPublicQueryAllowList }).publicQueryIds(c.tenantId, c.projectId, c.releaseId)
        if (allowed.isEmpty()) return PublicSiteDecision.NotFound("release has no public query")
        if (queryId !in allowed) return PublicSiteDecision.NotFound("query not public")
        return PublicSiteDecision.Allowed(PublicSitePrincipal(c.tenantId, c.workspaceId, c.projectId, c.siteSlug, c.releaseId, version))
    }

    /**
     * Second line of defence behind C3's `GatewayAuthorizer` port (the PUBLIC_SITE branch of [GatewayAuthorizer] calls this): the data gateway knows the actor, the
     * ids and the app version, not the query id, so here only what it can see is checked. The allow-list is checked by [authorize], which the public route must call
     * FIRST; a PUBLIC_SITE [GatewayAuthRequest] is never enough on its own to name a query.
     */
    internal fun authorizeGateway(r: GatewayAuthRequest): AccessDecision {
        if (r.operation != QUERY_EXECUTE) return AccessDecision.Denied("operation not public")
        if (r.actor.kind != ActorKind.PUBLIC_SITE || r.actor.userId != null) return AccessDecision.Denied("public site has no user")
        val ws = r.workspaceId ?: return AccessDecision.Denied("workspace required")
        val project = r.projectId ?: return AccessDecision.Denied("project required")
        val version = r.appVersionId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return AccessDecision.Denied("release version required")
        return if (releases.activeReleaseHasVersion(r.tenantId, ws, project, version)) AccessDecision.Allowed else AccessDecision.Denied("release not active")
    }
}
