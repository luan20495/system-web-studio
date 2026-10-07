package com.systemwebstudio.publish

import com.systemwebstudio.app.definition.AppDefinitionCodec
import com.systemwebstudio.app.definition.PublicQueries
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The release a published site is serving RIGHT NOW, as the Public Data Runtime (published-runtime.md §4) needs to know it. Everything in it is derived
 * on the server from the site's slug; none of it comes from a browser and none of it is ever sent to one.
 *  - [publicQueries]: the local ids of the queries the release lists as public, read from the immutable version snapshot the release names.
 */
data class ActiveRelease(
    val slug: String, val tenantId: UUID, val workspaceId: UUID, val projectId: UUID, val deploymentId: UUID,
    val versionId: UUID, val versionNumber: Int, val visibility: String, val publicQueries: List<String>
)

/**
 * slug -> active release -> public query allow-list. The pointer is read on EVERY call (no cache), so a rollback or an unpublish bites with the next
 * request: after a rollback the allow-list is the restored release's, after an unpublish there is no active release and therefore no usable query.
 *
 * Why this is immutable per release without a column: a deployment names a project version (`deployments.version_id`), a version's snapshot never
 * changes, and the allow-list is a pure function of that snapshot ([PublicQueries.of]); the draft of the project is a different row. A query that is
 * only in the draft - or whose `public` flag was set after the release was published - is not in the allow-list of that release.
 *
 * "Active" is exactly what the gateway serves: the pointer of a live, active project whose deployment is DEPLOYING or RUNNING (D-C0-33, the same rule
 * as [SiteService.live]). A query id is never trusted because it exists in the project.
 */
@Service
class PublishedRelease(private val jdbc: JdbcTemplate, private val codec: AppDefinitionCodec, private val json: JsonMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** the release served at [slug], or null (unknown slug, offline, archived or deleted project, a deployment that is not serving) */
    fun active(slug: String): ActiveRelease? {
        if (!SLUG.matches(slug)) return null
        val row = jdbc.query(
            """SELECT s.project_id, p.workspace_id, p.tenant_id, d.id, v.id, v.version_number, d.visibility, v.schema_snapshot::text
               FROM sites s JOIN projects p ON p.id = s.project_id AND p.active AND p.lifecycle = 'ACTIVE'
               JOIN deployments d ON d.id = s.current_deployment_id AND d.status IN ('DEPLOYING', 'RUNNING')
               JOIN project_versions v ON v.id = d.version_id
               WHERE s.slug = ?""",
            { rs, _ -> Raw(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java),
                rs.getObject(5, UUID::class.java), rs.getInt(6), rs.getString(7), rs.getString(8)) }, slug).firstOrNull() ?: return null
        val allow = try { PublicQueries.of(codec.fromJson(json.readTree(row.snapshot))) }
            catch (e: Exception) { log.warn("Release {} of {} has a snapshot that cannot be read as an app definition; its allow-list is empty: {}", row.deployment, slug, e.javaClass.simpleName); emptyList() }
        return ActiveRelease(slug, row.tenant, row.workspace, row.project, row.deployment, row.version, row.versionNumber, row.visibility, allow)
    }

    /**
     * The active release IF [queryId] is in its allow-list, else null. Unknown slug, offline site, a release without that query, a query that exists
     * only in the draft: all the same null (the caller answers one uniform 404, no existence oracle).
     */
    fun resolve(slug: String, queryId: String): ActiveRelease? = active(slug)?.takeIf { queryId in it.publicQueries }

    private class Raw(val project: UUID, val workspace: UUID, val tenant: UUID, val deployment: UUID, val version: UUID, val versionNumber: Int, val visibility: String, val snapshot: String)

    private companion object { val SLUG = Regex("^[a-z0-9][a-z0-9-]{1,79}$") }
}
