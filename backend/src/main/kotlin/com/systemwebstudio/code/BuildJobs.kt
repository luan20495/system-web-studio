package com.systemwebstudio.code

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.integration.queue.JobQueue
import com.systemwebstudio.integration.queue.Queues
import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.publish.StaticSiteBuilder
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

data class ClaimedJob(val id: UUID, val projectId: UUID, val commitSha: String, val purpose: String, val sourceUrl: String,
                      val limits: Map<String, Any>, val image: String)
data class FinishRequest(val status: String = "FAILED", val stage: String? = null, val log: String? = null, val error: String? = null, val scans: JsonNode? = null,
                         /** measured by the runner: wall clock and container cgroup CPU (cpu.stat usage_usec) */
                         val durationMs: Long? = null, val cpuMs: Long? = null, val sourceBytes: Long? = null)

/** Reads a build output tar.gz as DATA (nothing is executed): regular files only, no links/devices, normalised paths, size limits. */
object SafeTar {
    const val MAX_FILES = 2000
    const val MAX_TOTAL = 100L * 1024 * 1024
    const val MAX_FILE = 20L * 1024 * 1024
    private val SAFE = Regex("^[A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*$")

    fun read(gz: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        var total = 0L
        GZIPInputStream(ByteArrayInputStream(gz)).use { inp ->
            var longName: String? = null
            while (true) {
                val header = inp.readNBytes(512)
                if (header.size < 512 || header.all { it.toInt() == 0 }) break
                val name = longName ?: (cstr(header, 345, 155).let { if (it.isNotEmpty()) "$it/" else "" } + cstr(header, 0, 100))
                longName = null
                val size = cstr(header, 124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val padded = (size + 511) / 512 * 512
                when (type) {
                    '0', '\u0000' -> {
                        if (size > MAX_FILE) throw IllegalArgumentException("file too large: $name")
                        total += size; if (total > MAX_TOTAL) throw IllegalArgumentException("output larger than ${MAX_TOTAL / 1024 / 1024} MiB")
                        val data = inp.readNBytes(size.toInt()); skip(inp, padded - size)
                        val path = name.removePrefix("./").trimStart('/')
                        if (path.isEmpty() || ".." in path.split('/') || !SAFE.matches(path)) throw IllegalArgumentException("unsafe path in output: $name")
                        out[path] = data
                        if (out.size > MAX_FILES) throw IllegalArgumentException("more than $MAX_FILES files")
                    }
                    '5' -> skip(inp, padded)
                    'L' -> { longName = String(inp.readNBytes(size.toInt()), Charsets.UTF_8).trimEnd('\u0000'); skip(inp, padded - size) }
                    'x', 'g' -> skip(inp, padded)
                    else -> throw IllegalArgumentException("links and special files are not allowed in build output ($name)")
                }
            }
        }
        return out
    }
    private fun cstr(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.UTF_8).substringBefore('\u0000')
    private fun skip(inp: InputStream, n: Long) { var left = n; while (left > 0) { val s = inp.skip(left); if (s <= 0) { if (inp.read() < 0) break; left-- } else left -= s } }
}

/**
 * Build jobs (ADR 0010): the API only queues work and stores results; the runner (workers/runner, a separate process) does the
 * sandboxed install/build/scan in Docker and talks to the API over these internal endpoints with its own token.
 */
@Service
class BuildJobService(
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val store: ArtifactStore, private val code: CodeProjectService,
    private val queue: JobQueue, private val policy: BuildPolicyService, private val settings: com.systemwebstudio.settings.SettingsService,
    @Value("\${app.build.image:node:22-alpine}") private val image: String,
    @Value("\${app.build.preview-days:7}") private val previewDays: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val types = mapOf("html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8", "mjs" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8", "json" to "application/json", "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp", "ico" to "image/x-icon", "txt" to "text/plain; charset=utf-8",
        "woff" to "font/woff", "woff2" to "font/woff2", "map" to "application/json", "webmanifest" to "application/manifest+json")

    fun enqueue(projectId: UUID, commitSha: String, purpose: String, codeChangeId: UUID? = null, deploymentId: UUID? = null,
                workspaceId: UUID? = null, requestedBy: UUID? = null): UUID {
        val id = UUID.randomUUID()
        val ws = workspaceId ?: jdbc.queryForObject("SELECT workspace_id FROM projects WHERE id = ?", UUID::class.java, projectId)
        jdbc.update("INSERT INTO build_jobs (id, project_id, purpose, code_change_id, deployment_id, commit_sha, workspace_id, requested_by) VALUES (?,?,?,?,?,?,?,?)",
            id, projectId, purpose, codeChangeId, deploymentId, commitSha, ws, requestedBy)
        return id
    }

    /** Oldest queued job, or a RUNNING one whose runner stopped reporting (claim expired). SKIP LOCKED: several runners are safe. */
    @Transactional
    fun claim(runner: String, sourceBase: String): ClaimedJob? {
        val row = jdbc.query("""SELECT id, project_id, commit_sha, purpose FROM build_jobs WHERE status = 'QUEUED' OR (status = 'RUNNING' AND claimed_until < now())
            ORDER BY queued_at LIMIT 1 FOR UPDATE SKIP LOCKED""", { rs, _ -> listOf(rs.getObject(1), rs.getObject(2), rs.getString(3), rs.getString(4)) }).firstOrNull() ?: return null
        val id = row[0] as UUID
        jdbc.update("UPDATE build_jobs SET status = 'RUNNING', runner = ?, stage = 'CLAIMED', started_at = now(), claimed_until = now() + interval '25 minutes' WHERE id = ?", runner.take(64), id)
        return ClaimedJob(id, row[1] as UUID, row[2] as String, row[3] as String, "$sourceBase/internal/build-jobs/$id/source",
            mapOf("cpus" to 2, "memory" to "2g", "pids" to 512, "installSeconds" to 300, "buildSeconds" to policy.maxDurationSeconds(),
                "outputMiB" to policy.maxArtifactBytes() / 1048576), image)
    }

    fun running(id: UUID): Map<String, Any?> = jdbc.queryForList("SELECT * FROM build_jobs WHERE id = ? AND status = 'RUNNING'", id).firstOrNull()
        ?: throw ApiException.notFound("JOB_NOT_FOUND", "No running job with this id")

    fun source(id: UUID): ByteArray {
        val job = running(id)
        val repo = code.repo(job["project_id"] as UUID)
        return code.client.archive(repo.name, job["commit_sha"] as String)
    }

    /** Stores the build output as an immutable artifact (kind STATIC_APP), after a second, independent secret scan. */
    fun storeOutput(id: UUID, gz: ByteArray): UUID {
        val job = running(id)
        val projectId = job["project_id"] as UUID
        val files = try { SafeTar.read(gz) } catch (e: Exception) { throw ApiException.badRequest("UNSAFE_OUTPUT", e.message ?: "Unreadable build output") }
        if ("index.html" !in files) throw ApiException.badRequest("NO_INDEX", "The build produced no index.html")
        val outBytes = files.values.sumOf { it.size.toLong() }
        if (outBytes > policy.maxArtifactBytes()) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE", "Build output exceeds ${policy.maxArtifactBytes() / 1048576} MiB")
        val held = jdbc.queryForObject("SELECT coalesce(sum(total_bytes), 0) FROM artifacts WHERE project_id = ? AND deleted_at IS NULL", Long::class.java, projectId)!!
        val quota = settings.long("storage.max-artifacts-mib-per-project") * 1024 * 1024
        if (held + outBytes > quota) {
            policy.recordRejection(projectId, job["workspace_id"] as UUID?, job["requested_by"] as UUID?, "ARTIFACT_STORAGE_QUOTA", "Artifacts would use ${(held + outBytes) / 1048576} of ${quota / 1048576} MiB")
            throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_STORAGE_QUOTA", "This project's artifact storage is full; old previews are removed by the cleanup job")
        }
        for ((path, bytes) in files) {
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext in setOf("html", "js", "mjs", "css", "json", "txt", "svg", "map")) {
                val text = String(bytes, Charsets.UTF_8)
                CodeChangePolicy.SECRET_PATTERNS.firstOrNull { it.containsMatchIn(text) }?.let { throw ApiException.badRequest("SECRET_IN_OUTPUT", "Build output $path looks like it contains a credential") }
            }
        }
        val typed = files.toSortedMap().mapValues { (p, b) -> b to (types[p.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream") }
        val artifactId = storeArtifact(projectId, job["commit_sha"] as String, typed)
        jdbc.update("UPDATE build_jobs SET artifact_id = ?, stage = 'UPLOADED', claimed_until = now() + interval '10 minutes' WHERE id = ?", artifactId, id)
        return artifactId
    }

    private fun storeArtifact(projectId: UUID, commit: String, files: Map<String, Pair<ByteArray, String>>): UUID {
        val manifest = files.map { (p, f) -> mapOf("path" to p, "size" to f.first.size, "sha256" to StaticSiteBuilder.sha256(f.first), "contentType" to f.second) }
        val manifestJson = json.writeValueAsString(manifest)
        val sha = StaticSiteBuilder.sha256(manifestJson.toByteArray())
        jdbc.query("SELECT id FROM artifacts WHERE project_id = ? AND sha256 = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, projectId, sha).firstOrNull()?.let { return it }
        val prefix = "$projectId/$sha"
        files.forEach { (p, f) -> store.putOnce("$prefix/$p", f.first, f.second) }
        jdbc.update("""INSERT INTO artifacts (id, project_id, sha256, kind, storage_prefix, file_count, total_bytes, manifest, commit_sha)
            VALUES (?,?,?,'STATIC_APP',?,?,?,CAST(? AS jsonb),?) ON CONFLICT (project_id, sha256) DO NOTHING""",
            UUID.randomUUID(), projectId, sha, prefix, files.size, files.values.sumOf { it.first.size.toLong() }, manifestJson, commit)
        return jdbc.queryForObject("SELECT id FROM artifacts WHERE project_id = ? AND sha256 = ?", UUID::class.java, projectId, sha)!!
    }

    @Transactional
    fun finish(id: UUID, req: FinishRequest) {
        val job = running(id)
        val ok = req.status == "SUCCEEDED" && job["artifact_id"] != null
        val error = if (ok) null else (req.error ?: if (req.status == "SUCCEEDED") "No output was uploaded" else "Build failed").take(1000)
        jdbc.update("""UPDATE build_jobs SET status = ?, stage = ?, log = ?, error = ?, scans = CAST(? AS jsonb), finished_at = now(), claimed_until = NULL,
            duration_ms = ?, cpu_ms = ?, source_bytes = ?, artifact_bytes = (SELECT total_bytes FROM artifacts WHERE id = build_jobs.artifact_id) WHERE id = ?""",
            if (ok) "SUCCEEDED" else "FAILED", req.stage?.take(16), req.log?.takeLast(64_000), error, req.scans?.let { json.writeValueAsString(it) },
            req.durationMs?.coerceAtLeast(0), req.cpuMs?.coerceAtLeast(0), req.sourceBytes?.coerceAtLeast(0), id)
        (job["code_change_id"] as UUID?)?.let { change ->
            if (ok) jdbc.update("""UPDATE code_changes SET status = 'READY', preview_artifact_id = ?, preview_token = ?, preview_expires_at = now() + make_interval(days => ?),
                updated_at = now() WHERE id = ? AND status = 'BUILDING'""", job["artifact_id"], code.newPreviewToken(), previewDays.toInt(), change)
            else jdbc.update("UPDATE code_changes SET status = 'FAILED', error = ?, updated_at = now() WHERE id = ? AND status = 'BUILDING'", error, change)
        }
        (job["deployment_id"] as UUID?)?.let { dep ->
            // continue the publish pipeline (DeploymentProcessor picks up the result at BUILDING)
            runCatching { queue.publish(Queues.PUBLISH, dep.toString()) }.onFailure { log.warn("Broker unavailable; recovery sweeper will resume {}", dep) }
        }
    }
}

/** Runner-only endpoints. Not routed by the Studio UI proxy or the sites gateway; disabled unless BUILD_RUNNER_TOKEN is set. */
@RestController
@RequestMapping("/internal/build-jobs")
class BuildRunnerController(
    private val jobs: BuildJobService,
    @Value("\${app.build.runner-token:}") private val token: String,
    @Value("\${app.build.api-base:http://127.0.0.1:8080}") private val apiBase: String
) {
    private fun auth(request: HttpServletRequest) {
        val got = request.getHeader("X-Runner-Token") ?: ""
        if (token.isBlank() || !MessageDigest.isEqual(got.toByteArray(), token.toByteArray())) throw ApiException.notFound("NOT_FOUND", "Not found")
    }

    @PostMapping("/claim")
    fun claim(@RequestParam(defaultValue = "runner") runner: String, request: HttpServletRequest, response: HttpServletResponse): ClaimedJob? {
        auth(request)
        return jobs.claim(runner, apiBase) ?: run { response.status = 204; null }
    }

    @GetMapping("/{id}/source", produces = ["application/gzip"])
    fun source(@PathVariable id: UUID, request: HttpServletRequest): ByteArray { auth(request); return jobs.source(id) }

    @PutMapping("/{id}/artifact", consumes = ["application/gzip", "application/octet-stream"])
    fun artifact(@PathVariable id: UUID, @RequestBody body: ByteArray, request: HttpServletRequest): Map<String, Any> {
        auth(request)
        if (body.size > SafeTar.MAX_TOTAL) throw ApiException.badRequest("OUTPUT_TOO_LARGE", "Build output too large")
        return mapOf("artifactId" to jobs.storeOutput(id, body))
    }

    @PostMapping("/{id}/finish")
    fun finish(@PathVariable id: UUID, @RequestBody req: FinishRequest, request: HttpServletRequest) { auth(request); jobs.finish(id, req) }
}
