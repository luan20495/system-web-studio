package com.systemwebstudio.code

import com.systemwebstudio.access.AccessContext
import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.git.GitFileChange
import com.systemwebstudio.version.SchemaRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class FileEdit(@field:NotBlank @field:Size(max = 200) val path: String, val content: String? = null, val delete: Boolean? = null)
data class ChangeRequest(@field:NotBlank @field:Size(max = 300) val summary: String, @field:Valid val files: List<FileEdit> = emptyList())
data class TreeFile(val path: String, val size: Long)
data class CommitDto(val sha: String, val message: String, val author: String, val committer: String, val date: Instant)
data class CodeChangeDto(
    val id: UUID, val kind: String, val status: String, val summary: String, val files: List<String>, val branch: String, val baseSha: String, val headSha: String,
    val promptId: UUID?, val error: String?, val createdBy: String?, val createdAt: Instant, val updatedAt: Instant,
    val previewUrl: String?, val previewExpiresAt: Instant?, val build: BuildInfo?
)
data class BuildInfo(val id: UUID, val status: String, val stage: String?, val error: String?, val log: String?, val scans: tools.jackson.databind.JsonNode?,
                     val queuedAt: Instant, val startedAt: Instant?, val finishedAt: Instant?)
data class DiffFile(val path: String, val before: String?, val after: String?)

/** Change lifecycle for code projects: commit on its own branch → sandbox build → preview → merge (fast-forward) or discard. */
@Service
class CodeChangeService(
    private val code: CodeProjectService, private val jobs: BuildJobService, private val jdbc: JdbcTemplate, private val json: JsonMapper,
    private val versions: SchemaRepository, private val audit: AuditService, private val policy: BuildPolicyService,
    @Value("\${app.sites.origin:http://127.0.0.1:18088}") private val sitesOrigin: String
) {
    fun requireCode(ctx: AccessContext) { if (ctx.project!!.appType != "STATIC_APP") throw ApiException.conflict("NOT_A_CODE_PROJECT", "This project is page-schema based") }

    @Transactional
    fun propose(ctx: AccessContext, userId: UUID, summary: String, changes: List<GitFileChange>, kind: String, promptId: UUID? = null): CodeChangeDto {
        CodeChangePolicy.check(changes)
        val project = ctx.project!!
        val repo = code.repo(project.id)
        policy.requireCapacity(project.id, ctx.workspaceId, userId)
        val base = code.git { code.client.branchSha(repo.name, "main") } ?: throw ApiException.conflict("EMPTY_REPOSITORY", "Repository has no main branch")
        // repository size after this change (tree of main with changed files replaced)
        val tree = code.git { code.client.tree(repo.name, base) }.associate { it.path to it.size }.toMutableMap()
        changes.forEach { c -> if (c.content == null) tree.remove(c.path) else tree[c.path] = c.content.size.toLong() }
        val newSize = tree.values.sum()
        policy.requireRepoSize(project.id, ctx.workspaceId, userId, newSize)
        val id = UUID.randomUUID()
        val branch = "${if (kind == "AI") "ai" else "edit"}/${id.toString().take(8)}"
        val message = "${summary.trim().take(200)}\n\nCode-Change-Id: $id\n${promptId?.let { "Prompt-Id: $it\n" } ?: ""}Studio-User: $userId"
        val head = code.git { code.client.commit(repo.name, "main", branch, changes, message, code.author(userId)) }
        val job = jobs.enqueue(project.id, head, "PREVIEW", codeChangeId = null, workspaceId = ctx.workspaceId, requestedBy = userId)
        jdbc.update("UPDATE repositories SET size_bytes = ?, updated_at = now() WHERE project_id = ?", newSize, project.id)
        jdbc.update("""INSERT INTO code_changes (id, project_id, kind, prompt_id, branch, base_sha, head_sha, status, summary, files, build_job_id, created_by)
            VALUES (?,?,?,?,?,?,?,'BUILDING',?,CAST(? AS jsonb),?,?)""", id, project.id, kind, promptId, branch, base, head, summary.trim().take(500),
            json.writeValueAsString(changes.map { it.path }), job, userId)
        jdbc.update("UPDATE build_jobs SET code_change_id = ? WHERE id = ?", id, job)
        audit.record("PROPOSE_CODE_CHANGE", "CODE_CHANGE", id, ctx.workspaceId, project.id, newValue = mapOf("kind" to kind, "files" to changes.size, "head" to head))
        return get(project.id, id)
    }

    fun list(projectId: UUID, limit: Int = 50): List<CodeChangeDto> =
        jdbc.queryForList("SELECT id FROM code_changes WHERE project_id = ? ORDER BY created_at DESC LIMIT ?", UUID::class.java, projectId, limit).map { get(projectId, it) }

    fun get(projectId: UUID, id: UUID): CodeChangeDto {
        val r = jdbc.queryForList("""SELECT c.*, coalesce(u.display_name, u.username) AS creator FROM code_changes c LEFT JOIN users u ON u.id = c.created_by
            WHERE c.id = ? AND c.project_id = ?""", id, projectId).firstOrNull() ?: throw ApiException.notFound("CHANGE_NOT_FOUND", "Change not found")
        val build = (r["build_job_id"] as UUID?)?.let { b -> jdbc.queryForList("SELECT * FROM build_jobs WHERE id = ?", b).firstOrNull()?.let { j ->
            BuildInfo(b, j["status"] as String, j["stage"] as String?, j["error"] as String?, j["log"] as String?, (j["scans"])?.toString()?.let { json.readTree(it) },
                (j["queued_at"] as java.sql.Timestamp).toInstant(), (j["started_at"] as java.sql.Timestamp?)?.toInstant(), (j["finished_at"] as java.sql.Timestamp?)?.toInstant())
        } }
        val token = r["preview_token"] as String?; val expires = (r["preview_expires_at"] as java.sql.Timestamp?)?.toInstant()
        val live = token != null && expires != null && expires.isAfter(Instant.now()) && r["status"] in setOf("READY", "MERGED")
        return CodeChangeDto(id, r["kind"] as String, r["status"] as String, r["summary"] as String, json.readTree(r["files"].toString()).toList().map { it.asString() },
            r["branch"] as String, r["base_sha"] as String, r["head_sha"] as String, r["prompt_id"] as UUID?, r["error"] as String?, r["creator"] as String?,
            (r["created_at"] as java.sql.Timestamp).toInstant(), (r["updated_at"] as java.sql.Timestamp).toInstant(),
            if (live) "${sitesOrigin.trimEnd('/')}/_preview/$token/" else null, if (live) expires else null, build)
    }

    fun diff(projectId: UUID, id: UUID): List<DiffFile> {
        val c = get(projectId, id); val repo = code.repo(projectId)
        fun text(b: ByteArray?) = b?.toString(Charsets.UTF_8)
        return c.files.map { p -> DiffFile(p, code.git { text(code.client.raw(repo.name, p, c.baseSha)) }, code.git { text(code.client.raw(repo.name, p, c.headSha)) }) }
    }

    /** Only a change whose exact head commit built green can reach main; fast-forward keeps the commit and its author. */
    @Transactional
    fun merge(ctx: AccessContext, userId: UUID, id: UUID): CodeChangeDto {
        val project = ctx.project!!
        val c = get(project.id, id)
        if (c.status != "READY") throw ApiException.conflict("CHANGE_NOT_READY", "Only a change with a successful build can be merged (status ${c.status})")
        val repo = code.repo(project.id)
        val main = code.git { code.client.fastForward(repo.name, c.branch, c.summary) }
        if (main != c.headSha) throw ApiException(HttpStatus.CONFLICT, "MERGE_MISMATCH", "main does not point at the built commit")
        jdbc.update("UPDATE code_changes SET status = 'MERGED', updated_at = now() WHERE id = ?", id)
        jdbc.update("UPDATE repositories SET head_sha = ?, updated_at = now() WHERE project_id = ?", main, project.id)
        jdbc.update("UPDATE projects SET revision = revision + 1, updated_at = now() WHERE id = ?", project.id)
        val number = versions.nextVersionNumber(project.id)
        val versionId = versions.insertVersion(project.workspaceId, project.id, number, json.createObjectNode().put("appType", "STATIC_APP").put("commit", main),
            "COMMIT", c.summary.take(200), c.promptId, null, null, userId)
        jdbc.update("UPDATE project_versions SET commit_sha = ? WHERE id = ?", main, versionId)
        audit.record("MERGE_CODE_CHANGE", "CODE_CHANGE", id, ctx.workspaceId, project.id, newValue = mapOf("commit" to main, "versionNumber" to number))
        return get(project.id, id)
    }

    @Transactional
    fun discard(ctx: AccessContext, id: UUID): CodeChangeDto {
        val project = ctx.project!!
        val c = get(project.id, id)
        if (c.status == "MERGED" || c.status == "DISCARDED") throw ApiException.conflict("CHANGE_CLOSED", "This change is already ${c.status}")
        runCatching { code.client.deleteBranch(code.repo(project.id).name, c.branch) }
        jdbc.update("UPDATE code_changes SET status = 'DISCARDED', preview_token = NULL, updated_at = now() WHERE id = ?", id)
        audit.record("DISCARD_CODE_CHANGE", "CODE_CHANGE", id, ctx.workspaceId, project.id)
        return get(project.id, id)
    }
}

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/code")
class CodeController(private val access: AccessService, private val code: CodeProjectService, private val changes: CodeChangeService) {
    private fun ctx(me: StudioUserDetails, w: UUID, p: UUID) = access.forProject(me.userId, w, p).also { changes.requireCode(it) }

    @GetMapping("/tree")
    fun tree(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @RequestParam(defaultValue = "main") ref: String, @AuthenticationPrincipal me: StudioUserDetails): List<TreeFile> {
        ctx(me, workspaceId, projectId)
        if (!Regex("^[A-Za-z0-9._/-]{1,120}$").matches(ref)) throw ApiException.badRequest("INVALID_REF", "Invalid ref")
        return code.git { code.client.tree(code.repo(projectId).name, ref) }.map { TreeFile(it.path, it.size) }
    }

    @GetMapping("/file")
    fun file(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @RequestParam path: String, @RequestParam(defaultValue = "main") ref: String,
             @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any?> {
        ctx(me, workspaceId, projectId)
        if (".." in path || path.startsWith("/") || path.length > 200 || !Regex("^[A-Za-z0-9._/-]{1,120}$").matches(ref)) throw ApiException.badRequest("INVALID_PATH", "Invalid path")
        val bytes = code.git { code.client.raw(code.repo(projectId).name, path, ref) } ?: throw ApiException.notFound("FILE_NOT_FOUND", "File not found")
        val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        return mapOf("path" to path, "ref" to ref, "size" to bytes.size, "text" to (if (text != null && bytes.size <= 500_000) text else null),
            "editable" to runCatching { CodeChangePolicy.check(listOf(GitFileChange(path, "x".toByteArray()))); true }.getOrDefault(false))
    }

    @GetMapping("/commits")
    fun commits(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<CommitDto> {
        ctx(me, workspaceId, projectId)
        return code.git { code.client.commits(code.repo(projectId).name, "main", 50) }.map { CommitDto(it.sha, it.message, it.authorName, it.committerName, it.date) }
    }

    @GetMapping("/changes")
    fun list(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<CodeChangeDto> {
        ctx(me, workspaceId, projectId); return changes.list(projectId)
    }

    @PostMapping("/changes")
    @ResponseStatus(HttpStatus.CREATED)
    fun propose(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody request: ChangeRequest, @AuthenticationPrincipal me: StudioUserDetails): CodeChangeDto {
        val c = ctx(me, workspaceId, projectId); c.require(Permission.PROJECT_EDIT)
        val files = request.files.map { GitFileChange(it.path.trim(), if (it.delete == true) null else (it.content ?: "").toByteArray(Charsets.UTF_8)) }
        return changes.propose(c, me.userId, request.summary, files, "EDIT")
    }

    @GetMapping("/changes/{id}")
    fun get(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): CodeChangeDto {
        ctx(me, workspaceId, projectId); return changes.get(projectId, id)
    }

    @GetMapping("/changes/{id}/diff")
    fun diff(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<DiffFile> {
        ctx(me, workspaceId, projectId); return changes.diff(projectId, id)
    }

    @PostMapping("/changes/{id}/merge")
    fun merge(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): CodeChangeDto {
        val c = ctx(me, workspaceId, projectId); c.require(Permission.PROJECT_EDIT); return changes.merge(c, me.userId, id)
    }

    @PostMapping("/changes/{id}/discard")
    fun discard(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): CodeChangeDto {
        val c = ctx(me, workspaceId, projectId); c.require(Permission.PROJECT_EDIT); return changes.discard(c, id)
    }
}
