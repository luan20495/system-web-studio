package com.systemwebstudio.code

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.integration.git.ForgejoClient
import com.systemwebstudio.integration.git.GitAuthor
import com.systemwebstudio.integration.git.GitFileChange
import com.systemwebstudio.integration.git.GitServerException
import com.systemwebstudio.project.ProjectEntity
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.UUID

data class RepoRef(val projectId: UUID, val owner: String, val name: String, val headSha: String?)

/**
 * What a change (from Code mode or the AI) may touch — enforced on the server before anything is committed (ADR 0012).
 * v1: app source under src/, static files under public/, and index.html; text files only; build configuration and dependencies
 * (package.json, package-lock.json, vite/ts config) are owned by the approved scaffold and cannot be changed.
 */
object CodeChangePolicy {
    const val MAX_FILES = 50
    const val MAX_FILE_BYTES = 200_000
    const val MAX_TOTAL_BYTES = 1_000_000
    private val PATH = Regex("^(index\\.html|src/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*|public/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*)$")
    private val TEXT_EXT = setOf("ts", "tsx", "js", "jsx", "css", "json", "md", "txt", "svg", "html")
    /** credential shapes that must never be committed (the build also scans with gitleaks) */
    val SECRET_PATTERNS = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"), Regex("\\bAKIA[0-9A-Z]{16}\\b"), Regex("\\bsk-(or-|ant-|proj-)?[A-Za-z0-9_-]{20,}"),
        Regex("\\bghp_[A-Za-z0-9]{30,}\\b"), Regex("\\bxox[abpr]-[A-Za-z0-9-]{10,}"), Regex("\\bAIza[0-9A-Za-z_-]{35}\\b")
    )

    fun check(changes: List<GitFileChange>) {
        fun bad(code: String, msg: String): Nothing = throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, msg)
        if (changes.isEmpty()) bad("NO_CHANGES", "The change contains no files")
        if (changes.size > MAX_FILES) bad("TOO_MANY_FILES", "At most $MAX_FILES files per change")
        if (changes.map { it.path }.toSet().size != changes.size) bad("DUPLICATE_PATH", "A file appears twice in the change")
        var total = 0
        for (c in changes) {
            val p = c.path
            if (".." in p || p.startsWith("/") || !PATH.matches(p)) bad("PATH_NOT_ALLOWED", "Not allowed: $p (only src/, public/ and index.html; configuration and dependencies are fixed)")
            if (p.substringAfterLast('.', "").lowercase() !in TEXT_EXT) bad("FILE_TYPE_NOT_ALLOWED", "Only text source files can be changed: $p")
            val bytes = c.content ?: continue
            if (bytes.size > MAX_FILE_BYTES) bad("FILE_TOO_LARGE", "$p is larger than $MAX_FILE_BYTES bytes")
            total += bytes.size
            val text = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: Exception) { bad("NOT_TEXT", "$p is not UTF-8 text") }
            if ('\u0000' in text) bad("NOT_TEXT", "$p contains binary data")
            SECRET_PATTERNS.firstOrNull { it.containsMatchIn(text) }?.let { bad("SECRET_DETECTED", "$p looks like it contains a credential; secrets never go into code") }
        }
        if (total > MAX_TOTAL_BYTES) bad("CHANGE_TOO_LARGE", "The change is larger than $MAX_TOTAL_BYTES bytes")
    }
}

@Service
class CodeProjectService(private val git: ForgejoClient, private val jdbc: JdbcTemplate, private val json: JsonMapper,
                         private val settings: com.systemwebstudio.settings.SettingsService) {
    private val random = SecureRandom()
    /** Git server configured AND enabled by policy (Settings → Ứng dụng mã nguồn) */
    val available: Boolean get() = git.configured && settings.bool("source-apps.enabled")
    val configured: Boolean get() = git.configured

    /** The approved scaffold (React + Vite, ADR 0012), read from the application's resources. */
    fun scaffold(): List<GitFileChange> {
        val base = "scaffolds/react-vite/"
        return PathMatchingResourcePatternResolver().getResources("classpath*:$base**").filter { it.isReadable && !it.uri.toString().endsWith("/") }
            .mapNotNull { r -> val u = r.uri.toString(); val rel = u.substring(u.lastIndexOf(base) + base.length); if (rel.isEmpty()) null else GitFileChange(rel, r.inputStream.use { it.readAllBytes() }) }
            .sortedBy { it.path }
    }

    fun repoName(project: ProjectEntity): String {
        val base = Normalizer.normalize(project.name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "app" }
        return "$base-${project.id.toString().replace("-", "").take(8)}"
    }

    fun author(userId: UUID): GitAuthor {
        val row = jdbc.queryForMap("SELECT username, coalesce(display_name, username) AS name FROM users WHERE id = ?", userId)
        return GitAuthor(row["name"] as String, "${row["username"]}@studio.local")
    }

    /** Creates the platform-owned repository with the scaffold as its first commit on a protected main. Returns the commit sha. */
    fun initialize(project: ProjectEntity, userId: UUID): String {
        if (!available) throw ApiException.conflict("CODE_PROJECTS_UNAVAILABLE", "Code projects are not available (Git server not configured or disabled by policy)")
        val name = repoName(project)
        try {
            git.createRepository(name, "Studio project ${project.id}")
            val sha = git.commit(name, "main", "main", scaffold(), "Khởi tạo từ khung React + Vite\n\nStudio-Project: ${project.id}\nStudio-User: $userId", author(userId), emptyRepo = true)
            git.protectMain(name)
            jdbc.update("INSERT INTO repositories (project_id, provider, owner, name, head_sha, size_bytes) VALUES (?, 'forgejo', ?, ?, ?, ?)", project.id, git.org, name, sha,
                scaffold().sumOf { (it.content?.size ?: 0).toLong() })
            return sha
        } catch (e: GitServerException) { throw ApiException(HttpStatus.BAD_GATEWAY, "GIT_SERVER_ERROR", e.message ?: "Git server error") }
    }

    fun repo(projectId: UUID): RepoRef = jdbc.query("SELECT owner, name, head_sha FROM repositories WHERE project_id = ? AND state = 'ACTIVE'",
        { rs, _ -> RepoRef(projectId, rs.getString(1), rs.getString(2), rs.getString(3)) }, projectId).firstOrNull()
        ?: throw ApiException.notFound("REPOSITORY_NOT_FOUND", "This project has no code repository")

    fun newPreviewToken(): String = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun <T> git(block: () -> T): T = try { block() } catch (e: GitServerException) {
        throw if (e.status == 409) ApiException.conflict("MAIN_MOVED", e.message ?: "Main has moved") else ApiException(HttpStatus.BAD_GATEWAY, "GIT_SERVER_ERROR", e.message ?: "Git server error")
    }
    val client get() = git
}
