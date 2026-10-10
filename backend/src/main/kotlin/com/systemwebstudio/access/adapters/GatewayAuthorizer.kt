package com.systemwebstudio.access.adapters

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.tenancy.ActorKind
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** What the Data Platform asks about. [operation] is the NAME of C3's `GatewayOperation` (kept as a string: `access` may not import `data.*`). */
data class GatewayAuthRequest(
    val actor: Principal,
    /** tenant the caller claims; compared with the tenant of [workspaceId], never trusted */
    val tenantId: UUID,
    val workspaceId: UUID?,
    val projectId: UUID? = null,
    val appVersionId: String? = null,
    val operation: String,
    val dataSourceId: UUID? = null
)

/**
 * Policy core of C3's `GatewayAuthorizer` port. Operation -> required permissions (all required):
 * DATASOURCE_READ -> DATA_SOURCE_VIEW; DATASOURCE_MANAGE / SCHEMA_DISCOVER / CACHE_REFRESH / SYNC_MANAGE / WEBHOOK_MANAGE -> DATA_SOURCE_MANAGE;
 * QUERY_EXECUTE / EVENTS_SUBSCRIBE -> QUERY_EXECUTE; MUTATION_EXECUTE -> DATA_MUTATE;
 * SCHEMA_SAMPLE -> DATA_SOURCE_MANAGE **and** QUERY_EXECUTE (sampling reads real rows: managing is not enough).
 * Unknown operation, non-USER actor (SYSTEM / SERVICE / APP_TOKEN: TEMPORARY V2 POLICY, see ActorPolicy), missing workspace (tenant-level data sources have
 * no permission holder: TENANT_ADMIN has no implicit data access, D-C1-12), tenant mismatch, unknown project/version, a data source that is not of this tenant + workspace => Denied.
 *
 * The one non-USER exception is [ActorKind.PUBLIC_SITE] (D-C0-35): it may reach `QUERY_EXECUTE` and nothing else, and only for the app version of the ACTIVE public
 * release of the project it names (see [PublicSiteAuthorizer.authorizeGateway]); it has no user, so no USER role or permission is ever consulted for it.
 */
@Component("c1GatewayAuthorizer")
class GatewayAuthorizer(private val access: AccessService, private val jdbc: JdbcTemplate, private val publicSite: PublicSiteAuthorizer) {
    companion object {
        val REQUIRED: Map<String, Set<Permission>> = mapOf(
            "DATASOURCE_READ" to setOf(Permission.DATA_SOURCE_VIEW),
            "DATASOURCE_MANAGE" to setOf(Permission.DATA_SOURCE_MANAGE),
            "QUERY_EXECUTE" to setOf(Permission.QUERY_EXECUTE),
            "MUTATION_EXECUTE" to setOf(Permission.DATA_MUTATE),
            "SCHEMA_DISCOVER" to setOf(Permission.DATA_SOURCE_MANAGE),
            "SCHEMA_SAMPLE" to setOf(Permission.DATA_SOURCE_MANAGE, Permission.QUERY_EXECUTE),
            "CACHE_REFRESH" to setOf(Permission.DATA_SOURCE_MANAGE),
            "EVENTS_SUBSCRIBE" to setOf(Permission.QUERY_EXECUTE),
            "SYNC_MANAGE" to setOf(Permission.DATA_SOURCE_MANAGE),
            "WEBHOOK_MANAGE" to setOf(Permission.DATA_SOURCE_MANAGE)
        )
    }

    fun authorize(r: GatewayAuthRequest): AccessDecision = decide {
        val required = REQUIRED[r.operation] ?: return@decide AccessDecision.Denied("unknown operation")
        if (r.actor.kind == ActorKind.PUBLIC_SITE) return@decide publicSite.authorizeGateway(r)       // QUERY_EXECUTE only; never reaches the USER permission model
        val userId = ActorPolicy.userOrNull(r.actor) ?: return@decide AccessDecision.Denied(ActorPolicy.DENIED_REASON)   // TEMPORARY V2 POLICY
        val workspaceId = r.workspaceId ?: return@decide AccessDecision.Denied("workspace required")
        if (r.appVersionId != null && r.projectId == null) return@decide AccessDecision.Denied("project required for an app version")
        val ctx: AccessContext =
            if (r.projectId != null) access.forProject(userId, workspaceId, r.projectId) else access.forWorkspace(userId, workspaceId)
        if (ctx.tenantId != r.tenantId) return@decide AccessDecision.Denied("tenant mismatch")
        if (!required.all { it in ctx.permissions }) return@decide AccessDecision.Denied("missing permission")
        if (r.appVersionId != null && !versionBelongs(r.appVersionId, r.projectId!!, workspaceId)) return@decide AccessDecision.Denied("version not in project")
        // defense in depth: a data source id is accepted only if it is a source of THIS tenant and workspace (C3's own workspace scope is not the only barrier)
        if (r.dataSourceId != null && !dataSourceBelongs(r.dataSourceId, r.tenantId, workspaceId)) return@decide AccessDecision.Denied("data source not in workspace")
        AccessDecision.Allowed
    }

    private fun dataSourceBelongs(dataSourceId: UUID, tenantId: UUID, workspaceId: UUID): Boolean =
        jdbc.queryForList("SELECT 1 FROM data_sources WHERE id = ? AND tenant_id = ? AND workspace_id = ?", dataSourceId, tenantId, workspaceId).isNotEmpty()

    /** An app version id is accepted only if it is a `project_versions` row of this project and workspace. */
    private fun versionBelongs(appVersionId: String, projectId: UUID, workspaceId: UUID): Boolean {
        val id = runCatching { UUID.fromString(appVersionId) }.getOrNull() ?: return false
        return jdbc.queryForList(
            "SELECT 1 FROM project_versions WHERE id = ? AND project_id = ? AND workspace_id = ?", id, projectId, workspaceId
        ).isNotEmpty()
    }
}
