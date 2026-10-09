package com.systemwebstudio.identity.authme

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.PermissionCodes
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.UserEntity
import com.systemwebstudio.support.ApiSession
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Differential oracle: for every ACTIVE project_members row of the user, expected = AccessService.forProject(user, workspace, project) -> (projectRole, canonical codes),
 * or ABSENT when forProject throws ApiException. /auth/me projectScopes must equal that set exactly. Universal hard-coded invariants are checked on every scope too, so the
 * test cannot pass by comparing a wrong resolver with a wrong oracle.
 */
abstract class ProjectScopeDifferentialBase : AuthMeFixtureBase() {
    @Autowired lateinit var access: AccessService

    data class Key(val projectId: UUID, val workspaceId: UUID, val role: String?, val permissions: Set<String>)

    fun oracle(user: UserEntity): Map<UUID, Key> =
        jdbc.query("SELECT project_id, workspace_id FROM project_members WHERE user_id = ? AND active", { rs, _ ->
            rs.getObject(2, UUID::class.java) to rs.getObject(1, UUID::class.java)
        }, user.id).mapNotNull { (ws, p) ->
            try {
                val ctx = access.forProject(user.id, ws, p)
                p to Key(p, ws, ctx.projectRole, PermissionCodes.canonicalCodesOf(ctx.permissions).toSet())
            } catch (_: ApiException) { null }
        }.toMap()

    private fun archived(p: UUID) = jdbc.queryForObject("SELECT lifecycle FROM projects WHERE id = ?", String::class.java, p) == "ARCHIVED"

    /** asserts the differential property and the universal invariants; returns /auth/me scopes by project id */
    fun assertEquivalent(user: UserEntity, s: ApiSession, label: String): Map<UUID, Key> {
        val expected = oracle(user)
        val raw = scopesOf(me(s))
        assertThat(raw.map { it.projectId }).describedAs("$label: no duplicate project in projectScopes").doesNotHaveDuplicates()
        val actual = raw.associate { it.projectId to Key(it.projectId, it.workspaceId, it.role, it.permissions.toSet()) }
        assertThat(actual).describedAs("$label: /auth/me projectScopes == AccessService.forProject for every active project membership").isEqualTo(expected)
        for (k in actual.values) {
            val perms = k.permissions
            assertThat(perms).describedAs("$label: only canonical codes in ${k.projectId}").allMatch { it in PermissionCodes.CANONICAL }
            assertThat(perms).describedAs("$label: a scope always carries APP_VIEW (${k.projectId})").contains("APP_VIEW")
            if (archived(k.projectId)) {
                assertThat(perms).describedAs("$label: ARCHIVED ${k.projectId} keeps only view (audit is not canonical)").containsExactly("APP_VIEW")
            } else {
                assertThat(perms).describedAs("$label: APP_VIEW without APP_USE never appears on a live project (${k.projectId})").contains("APP_USE")
            }
            if ("APP_EDIT" in perms || "APP_PUBLISH" in perms || "APP_SHARE" in perms) assertThat(perms).contains("APP_VIEW", "APP_USE")
        }
        return actual
    }

    fun assertExactly(scopes: Map<UUID, Key>, project: UUID, role: String?, vararg perms: String) {
        val k = scopes[project]
        assertThat(k).describedAs("scope of $project").isNotNull
        assertThat(k!!.role).describedAs("role of $project").isEqualTo(role)
        assertThat(k.permissions).describedAs("permissions of $project").containsExactlyInAnyOrder(*perms)
    }

    companion object {
        val VIEWER = arrayOf("APP_USE", "APP_VIEW")
        val EDITOR = arrayOf("ACTION_EXECUTE", "APP_EDIT", "APP_USE", "APP_VIEW", "DATA_SOURCE_VIEW", "QUERY_EXECUTE")
        val PUBLISHER = arrayOf("APP_PUBLISH", "APP_USE", "APP_VIEW")
        val OWNER = arrayOf("ACTION_EXECUTE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "APP_USE", "APP_VIEW", "DATA_SOURCE_VIEW", "QUERY_EXECUTE")
        /** WORKSPACE_ADMIN (+ any project role): every project + data/logic code + MEMBER_MANAGE */
        val WORKSPACE_ADMIN = arrayOf("ACTION_EXECUTE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "APP_USE", "APP_VIEW", "DATA_MUTATE", "DATA_SOURCE_MANAGE",
            "DATA_SOURCE_VIEW", "MEMBER_MANAGE", "QUERY_EXECUTE", "WORKFLOW_EXECUTE", "WORKFLOW_MANAGE")
    }
}
