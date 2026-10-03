package com.systemwebstudio.publish

import com.systemwebstudio.integration.storage.ArtifactStore
import com.systemwebstudio.schema.PageSchemaValidator
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Client of the render worker (workers/render): the SAME TypeScript renderer the Studio preview uses, run as a separate local process,
 * so a published page is byte-for-byte the preview markup and the renderer is not re-implemented in Kotlin. Input is data only.
 */
@Component
class RenderClient(
    private val json: JsonMapper,
    @Value("\${app.render.url:http://127.0.0.1:18095}") private val url: String,
    @Value("\${app.render.token:}") private val token: String
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun render(schema: JsonNode, assets: Map<String, String>): String {
        val body = json.writeValueAsString(mapOf("schema" to schema, "assets" to assets))
        val request = HttpRequest.newBuilder(URI("${url.trimEnd('/')}/render")).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").header("X-Render-Token", token).POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw BuildFailure("Render worker is not reachable") }
        if (response.statusCode() != 200) throw BuildFailure("Render worker answered HTTP ${response.statusCode()}")
        return response.body()
    }

    /** Screenshot of the static render (worker: JavaScript disabled, network blocked). */
    fun preview(schema: JsonNode): PreviewResult {
        val request = HttpRequest.newBuilder(URI("${url.trimEnd('/')}/preview")).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").header("X-Render-Token", token).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("schema" to schema)))).build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofByteArray()) } catch (e: Exception) { return PreviewResult.Failed("render worker not reachable") }
        return when (response.statusCode()) {
            200 -> response.body().takeIf { it.size in 100..5_000_000 && it[1] == 'P'.code.toByte() }?.let { PreviewResult.Png(it) } ?: PreviewResult.Failed("not a PNG")
            501 -> PreviewResult.Unavailable
            else -> PreviewResult.Failed("HTTP ${response.statusCode()}")
        }
    }
}

class BuildFailure(message: String) : RuntimeException(message)

/** Result of a safe-render preview: PNG bytes, or why there is none (UNAVAILABLE = no browser configured on the worker). */
sealed interface PreviewResult { class Png(val bytes: ByteArray) : PreviewResult; object Unavailable : PreviewResult; class Failed(val reason: String) : PreviewResult }

/** AST service of the same worker (Design mode for code apps): parse-only, nothing executed. */
@Component
class AstClient(private val json: JsonMapper, @Value("\${app.render.url:http://127.0.0.1:18095}") private val url: String, @Value("\${app.render.token:}") private val token: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    fun post(path: String, body: Any): Pair<Int, JsonNode> {
        val r = try { http.send(HttpRequest.newBuilder(URI("${url.trimEnd('/')}$path")).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .header("X-Render-Token", token).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString()) }
            catch (e: Exception) { throw com.systemwebstudio.common.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "AST_UNAVAILABLE", "Design service is not reachable") }
        return r.statusCode() to json.readTree(r.body())
    }
}

data class ManifestFile(val path: String, val size: Int, val sha256: String, val contentType: String)

/**
 * Builds the immutable artifact of one page-schema version (ADR 0009): index.html from the render worker plus the referenced project
 * files, a manifest with per-file SHA-256, and an artifact id derived from the manifest. Rebuilding the same content reuses the row.
 */
@Service
class StaticSiteBuilder(private val jdbc: JdbcTemplate, private val json: JsonMapper, private val store: ArtifactStore, private val render: RenderClient) {
    private val ext = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif", "image/avif" to "avif")

    fun build(projectId: UUID, versionId: UUID, schemaText: String): UUID {
        val schema = json.readTree(schemaText)
        val refs = PageSchemaValidator.assetRefs(schema)
        val files = sortedMapOf<String, Pair<ByteArray, String>>()
        val urls = HashMap<String, String>()
        if (refs.isNotEmpty()) {
            jdbc.query("""SELECT id, storage_key, content_type FROM assets WHERE project_id = ? AND status = 'READY' AND id::text = ANY(string_to_array(?, ','))""",
                { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) }, projectId, refs.joinToString(",")).forEach { (id, key, type) ->
                // only images can appear in a page; anything else is left out of the artifact (and renders without an image)
                val e = ext[type] ?: return@forEach
                val path = "assets/$id.$e"
                files[path] = store.readAsset(key) to type
                urls[id.toString()] = path
            }
        }
        val html = render.render(schema, urls)
        // the renderer never emits scripts for a published page; anything else means the input or renderer is wrong
        if (Regex("<\\s*script", RegexOption.IGNORE_CASE).containsMatchIn(html)) throw BuildFailure("Rendered page contains a script")
        files["index.html"] = html.toByteArray(Charsets.UTF_8) to "text/html; charset=utf-8"
        val manifest = files.map { (path, f) -> ManifestFile(path, f.first.size, sha256(f.first), f.second) }
        val manifestJson = json.writeValueAsString(manifest)
        val sha = sha256(manifestJson.toByteArray())
        existing(projectId, sha)?.let { return it }
        val prefix = "$projectId/$sha"
        files.forEach { (path, f) -> store.putOnce("$prefix/$path", f.first, f.second) }
        jdbc.update("""INSERT INTO artifacts (id, project_id, version_id, sha256, storage_prefix, file_count, total_bytes, manifest)
            VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb)) ON CONFLICT (project_id, sha256) DO NOTHING""",
            UUID.randomUUID(), projectId, versionId, sha, prefix, manifest.size, manifest.sumOf { it.size.toLong() }, manifestJson)
        return existing(projectId, sha)!!
    }

    private fun existing(projectId: UUID, sha: String): UUID? =
        jdbc.query("SELECT id FROM artifacts WHERE project_id = ? AND sha256 = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, projectId, sha).firstOrNull()

    companion object {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
