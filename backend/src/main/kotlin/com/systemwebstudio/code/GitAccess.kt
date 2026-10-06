package com.systemwebstudio.code

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

data class CloneAccess(val cloneUrl: String, val username: String, val token: String?, val note: String)

/**
 * Read-only clone access for external IDEs (ADR 0011 §IDE). One Forgejo account per Studio user, added as READ collaborator on the
 * repositories of projects the user can read. The account's password is random, set just before creating a token and never stored;
 * the token (scope read:repository) is shown once. Pushes are not possible: main changes only through build → merge in the Studio.
 */
@Service
class GitAccessService(
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val code: CodeProjectService, private val access: AccessService, private val audit: AuditService,
    @Value("\${app.git.url:}") private val baseUrl: String, @Value("\${app.git.admin-token:}") private val adminToken: String,
    @Value("\${app.git.public-url:}") private val publicUrl: String
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val random = SecureRandom()
    val available get() = baseUrl.isNotBlank() && adminToken.isNotBlank()
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun call(method: String, path: String, body: Any? = null, basic: Pair<String, String>? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("${baseUrl.trimEnd('/')}/api/v1$path")).timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
        if (basic != null) b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("${basic.first}:${basic.second}".toByteArray()))
        else b.header("Authorization", "token $adminToken")
        val req = if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody()) else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        return try { http.send(req.build(), HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw ApiException(HttpStatus.BAD_GATEWAY, "GIT_SERVER_ERROR", "Git server unreachable") }
    }
    private fun ok(r: HttpResponse<String>, what: String) { if (r.statusCode() !in 200..299) throw ApiException(HttpStatus.BAD_GATEWAY, "GIT_SERVER_ERROR", "$what failed (HTTP ${r.statusCode()})") }
    private fun password() = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) } + "aA1!"

    private fun account(userId: UUID): String {
        jdbc.query("SELECT git_username FROM git_access WHERE user_id = ?", { rs, _ -> rs.getString(1) }, userId).firstOrNull()?.let { return it }
        val studio = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String::class.java, userId)!!
        val name = ("s-" + studio.lowercase().replace(Regex("[^a-z0-9-]"), "-")).take(30).trimEnd('-') + "-" + userId.toString().take(6)
        val r = call("POST", "/admin/users", mapOf("username" to name, "email" to "$name@studio.local", "password" to password(), "must_change_password" to false,
            "visibility" to "private", "restricted" to true))
        if (r.statusCode() != 201 && r.statusCode() != 422) ok(r, "Create git account")
        jdbc.update("INSERT INTO git_access (user_id, git_username) VALUES (?, ?) ON CONFLICT (user_id) DO NOTHING", userId, name)
        return name
    }

    fun issue(me: StudioUserDetails, workspaceId: UUID, projectId: UUID): CloneAccess {
        val ctx = access.forProject(me.userId, workspaceId, projectId)      // authorise FIRST: an outsider gets 404 whatever the server configuration is
        if (!available) throw ApiException.conflict("IDE_ACCESS_UNAVAILABLE", "IDE access is not configured on this server")
        if (ctx.project!!.appType != "STATIC_APP") throw ApiException.conflict("NOT_A_CODE_PROJECT", "This project has no repository")
        val repo = code.repo(projectId)
        val user = account(me.userId)
        ok(call("PUT", "/repos/${enc(repo.owner)}/${enc(repo.name)}/collaborators/${enc(user)}", mapOf("permission" to "read")), "Grant read access")
        jdbc.update("INSERT INTO git_access_repos (user_id, project_id) VALUES (?, ?) ON CONFLICT DO NOTHING", me.userId, projectId)
        // fresh random password (never stored) → basic auth → replace the clone token
        val pw = password()
        ok(call("PATCH", "/admin/users/${enc(user)}", mapOf("password" to pw, "must_change_password" to false, "login_name" to user, "source_id" to 0)), "Prepare git account")
        call("DELETE", "/users/${enc(user)}/tokens/studio-clone", basic = user to pw)
        val t = call("POST", "/users/${enc(user)}/tokens", mapOf("name" to "studio-clone", "scopes" to listOf("read:repository")), basic = user to pw)
        ok(t, "Create clone token")
        val token = json.readTree(t.body()).get("sha1").asString()
        jdbc.update("UPDATE git_access SET token_name = 'studio-clone', token_created_at = now() WHERE user_id = ?", me.userId)
        audit.record("GIT_CLONE_TOKEN_ISSUED", "REPOSITORY", projectId, workspaceId, projectId, newValue = mapOf("gitUser" to user))
        val url = "${(publicUrl.ifBlank { baseUrl }).trimEnd('/')}/${repo.owner}/${repo.name}.git"
        return CloneAccess(url, user, token, "Chỉ đọc. Token hiện một lần; tạo lại sẽ thay token cũ.")
    }

    fun revoke(userId: UUID) {
        val user = jdbc.query("SELECT git_username FROM git_access WHERE user_id = ?", { rs, _ -> rs.getString(1) }, userId).firstOrNull() ?: return
        val pw = password()
        call("PATCH", "/admin/users/${enc(user)}", mapOf("password" to pw, "must_change_password" to false, "login_name" to user, "source_id" to 0))
        call("DELETE", "/users/${enc(user)}/tokens/studio-clone", basic = user to pw)
        jdbc.update("UPDATE git_access SET token_name = NULL WHERE user_id = ?", userId)
        audit.record("GIT_CLONE_TOKEN_REVOKED", "USER", userId)
    }

    /** Cleanup job: remove read access where the user can no longer read the project (left, removed, disabled, project deleted). */
    fun syncCollaborators(): Int {
        if (!available) return 0
        var removed = 0
        jdbc.queryForList("""SELECT g.user_id, g.project_id, a.git_username, r.owner, r.name, p.workspace_id FROM git_access_repos g JOIN git_access a ON a.user_id = g.user_id
            JOIN repositories r ON r.project_id = g.project_id JOIN projects p ON p.id = g.project_id""").forEach { row ->
            val still = runCatching { access.forProject(row["user_id"] as UUID, row["workspace_id"] as UUID, row["project_id"] as UUID); true }.getOrDefault(false)
            if (!still) {
                call("DELETE", "/repos/${enc(row["owner"] as String)}/${enc(row["name"] as String)}/collaborators/${enc(row["git_username"] as String)}")
                jdbc.update("DELETE FROM git_access_repos WHERE user_id = ? AND project_id = ?", row["user_id"], row["project_id"])
                audit.record("GIT_ACCESS_REMOVED", "REPOSITORY", row["project_id"], projectId = row["project_id"] as UUID, actorId = null, newValue = mapOf("gitUser" to row["git_username"]))
                removed++
            }
        }
        return removed
    }
}

@RestController
class GitAccessController(private val git: GitAccessService) {
    @PostMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/clone-access")
    fun issue(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): CloneAccess = git.issue(me, workspaceId, projectId)

    @DeleteMapping("/api/v1/me/clone-access")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@AuthenticationPrincipal me: StudioUserDetails) = git.revoke(me.userId)
}
