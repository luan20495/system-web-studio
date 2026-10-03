package com.systemwebstudio.integration.git

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64

class GitServerException(message: String, val status: Int = 0) : RuntimeException(message)

data class GitFileChange(val path: String, val content: ByteArray?)      // null = delete
data class GitAuthor(val name: String, val email: String)
data class GitTreeEntry(val path: String, val size: Long, val sha: String)
data class GitCommitInfo(val sha: String, val message: String, val authorName: String, val authorEmail: String, val committerName: String, val date: Instant,
                         val verified: Boolean = false, val signer: String? = null)

/**
 * Forgejo/Gitea REST client for platform-owned code repositories (ADR 0011). The bot token comes from the environment only and is
 * sent only in the Authorization header; error bodies are never copied into messages. Repositories live in one organisation.
 */
@Component
class ForgejoClient(
    private val json: JsonMapper,
    @Value("\${app.git.url:}") private val baseUrl: String,
    @Value("\${app.git.token:}") private val token: String,
    @Value("\${app.git.org:factory}") val org: String,
    @Value("\${app.git.bot-name:factory-bot}") private val botName: String,
    @Value("\${app.git.bot-email:factory-bot@factory.local}") private val botEmail: String
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    val configured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
    private val api get() = "${baseUrl.trimEnd('/')}/api/v1"
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun encPath(p: String) = p.split('/').joinToString("/") { enc(it) }

    private fun send(method: String, path: String, body: Any? = null, timeout: Long = 30): HttpResponse<ByteArray> {
        if (!configured) throw GitServerException("Git server is not configured")
        val b = HttpRequest.newBuilder(URI("$api$path")).timeout(Duration.ofSeconds(timeout)).header("Authorization", "token $token").header("Accept", "application/json")
        val req = if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody())
            else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        return try { http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray()) } catch (e: Exception) { throw GitServerException("Git server unreachable (${e.javaClass.simpleName})") }
    }
    private fun ok(r: HttpResponse<ByteArray>, what: String): JsonNode {
        if (r.statusCode() !in 200..299) throw GitServerException("$what failed (HTTP ${r.statusCode()})", r.statusCode())
        return if (r.body().isEmpty()) json.createObjectNode() else json.readTree(r.body())
    }

    fun health(): Boolean = try { val r = send("GET", "/user"); r.statusCode() == 200 } catch (e: Exception) { false }

    fun createRepository(name: String, description: String) {
        ok(send("POST", "/orgs/${enc(org)}/repos", mapOf("name" to name, "description" to description.take(200), "private" to true, "default_branch" to "main", "auto_init" to false)), "Create repository")
    }

    /** main: only the platform bot may push, never force-push (merges happen only after a green build). */
    fun protectMain(repo: String) {
        ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/branch_protections", mapOf("rule_name" to "main", "enable_push" to true, "enable_push_whitelist" to true,
            "push_whitelist_usernames" to listOf(botName), "enable_force_push" to false)), "Protect main")
    }

    fun branchSha(repo: String, branch: String): String? {
        val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/branches/${enc(branch)}")
        if (r.statusCode() == 404) return null
        return ok(r, "Read branch").get("commit")?.get("id")?.asString()
    }

    private fun fileSha(repo: String, path: String, ref: String): String? {
        val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/contents/${encPath(path)}?ref=${enc(ref)}")
        if (r.statusCode() == 404) return null
        return ok(r, "Read file").get("sha")?.asString()
    }

    /**
     * One commit with all changes. With [newBranch] the commit starts a new branch from [baseBranch]; for an empty repository pass
     * baseBranch = newBranch = "main". Author = the Studio user; committer = the bot. Returns the new commit sha.
     */
    fun commit(repo: String, baseBranch: String, newBranch: String?, changes: List<GitFileChange>, message: String, author: GitAuthor, emptyRepo: Boolean = false): String {
        val files = changes.map { c ->
            val existing = if (emptyRepo) null else fileSha(repo, c.path, baseBranch)
            when {
                c.content == null -> mapOf("operation" to "delete", "path" to c.path, "sha" to (existing ?: throw GitServerException("File ${c.path} does not exist", 404)))
                existing == null -> mapOf("operation" to "create", "path" to c.path, "content" to Base64.getEncoder().encodeToString(c.content))
                else -> mapOf("operation" to "update", "path" to c.path, "sha" to existing, "content" to Base64.getEncoder().encodeToString(c.content))
            }
        }
        val body = mutableMapOf<String, Any>("branch" to baseBranch, "message" to message, "files" to files,
            "author" to mapOf("name" to author.name, "email" to author.email), "committer" to mapOf("name" to botName, "email" to botEmail))
        if (newBranch != null) body["new_branch"] = newBranch
        val n = ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/contents", body, 60), "Commit")
        return n.get("commit")?.get("sha")?.asString() ?: n.get("files")?.get(0)?.get("last_commit_sha")?.asString()
            ?: branchSha(repo, newBranch ?: baseBranch) ?: throw GitServerException("Commit returned no sha")
    }

    fun tree(repo: String, ref: String): List<GitTreeEntry> {
        val n = ok(send("GET", "/repos/${enc(org)}/${enc(repo)}/git/trees/${enc(ref)}?recursive=true&per_page=10000"), "Read tree")
        return n.get("tree")?.toList().orEmpty().filter { it.get("type")?.asString() == "blob" }
            .map { GitTreeEntry(it.get("path").asString(), it.get("size")?.asLong() ?: 0, it.get("sha").asString()) }
    }

    fun raw(repo: String, path: String, ref: String): ByteArray? {
        val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/raw/${encPath(path)}?ref=${enc(ref)}")
        if (r.statusCode() == 404) return null
        if (r.statusCode() !in 200..299) throw GitServerException("Read file failed (HTTP ${r.statusCode()})", r.statusCode())
        return r.body()
    }

    fun commits(repo: String, ref: String, limit: Int): List<GitCommitInfo> {
        val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/commits?sha=${enc(ref)}&limit=${limit.coerceIn(1, 100)}&stat=false&files=false")
        if (r.statusCode() == 409 || r.statusCode() == 404) return emptyList()      // empty repository
        return ok(r, "Read commits").toList().map { c ->
            val m = c.get("commit")
            GitCommitInfo(c.get("sha").asString(), m.get("message").asString(), m.get("author").get("name").asString(), m.get("author").get("email").asString(),
                m.get("committer").get("name").asString(), Instant.parse(m.get("author").get("date").asString()),
                m.get("verification")?.get("verified")?.asBoolean() == true, m.get("verification")?.get("signer")?.get("name")?.asString())
        }
    }

    /** Fast-forward main to [branch] (keeps the commit and its author); fails with 409 when main moved since the branch was made. */
    fun fastForward(repo: String, branch: String, title: String): String {
        val pr = ok(send("POST", "/repos/${enc(org)}/${enc(repo)}/pulls", mapOf("head" to branch, "base" to "main", "title" to title.take(200))), "Open merge request")
        val number = pr.get("number").asInt()
        val r = send("POST", "/repos/${enc(org)}/${enc(repo)}/pulls/$number/merge", mapOf("Do" to "fast-forward-only", "delete_branch_after_merge" to true), 60)
        if (r.statusCode() !in 200..299) {
            LoggerFactory.getLogger(javaClass).warn("Fast-forward of {} into main refused: HTTP {} {}", branch, r.statusCode(), String(r.body()).take(200))
            send("PATCH", "/repos/${enc(org)}/${enc(repo)}/pulls/$number", mapOf("state" to "closed"))
            throw GitServerException("Main has moved; rebuild the change from the latest version", 409)
        }
        return branchSha(repo, "main") ?: throw GitServerException("main has no commit")
    }

    fun deleteBranch(repo: String, branch: String) { send("DELETE", "/repos/${enc(org)}/${enc(repo)}/branches/${enc(branch)}") }

    /** tar.gz of the tree at [sha] (for the build runner; the runner itself never gets a Git credential). */
    fun archive(repo: String, sha: String): ByteArray {
        val r = send("GET", "/repos/${enc(org)}/${enc(repo)}/archive/${enc(sha)}.tar.gz", timeout = 120)
        if (r.statusCode() != 200) throw GitServerException("Archive failed (HTTP ${r.statusCode()})", r.statusCode())
        return r.body()
    }

    /** Hard delete (admin action after the retention period). 404 = already gone. */
    fun deleteRepository(repo: String) {
        val r = send("DELETE", "/repos/${enc(org)}/${enc(repo)}")
        if (r.statusCode() != 204 && r.statusCode() != 404) throw GitServerException("Delete repository failed (HTTP ${r.statusCode()})", r.statusCode())
    }

    fun setArchived(repo: String, archived: Boolean) {
        ok(send("PATCH", "/repos/${enc(org)}/${enc(repo)}", mapOf("archived" to archived)), "Archive repository")
    }
}
