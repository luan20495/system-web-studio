package com.systemwebstudio.asset

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RateLimiter
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.storage.StorageProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class UploadUrlRequest(
    @field:NotBlank @field:Size(max = 255) val fileName: String,
    @field:NotBlank @field:Size(max = 100) val contentType: String,
    @field:NotNull val size: Long?
)
data class UploadUrlResponse(val assetId: UUID, val uploadUrl: String, val method: String, val headers: Map<String, String>, val expiresInSeconds: Long)
data class CompleteUploadRequest(@field:NotNull val assetId: UUID?)
data class AssetDto(
    val id: UUID, val workspaceId: UUID, val projectId: UUID, val name: String, val contentType: String, val size: Long,
    val status: String, val createdBy: UUID, val createdAt: Instant, val downloadUrl: String?
)

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/assets")
class AssetController(
    private val access: AccessService,
    private val storage: StorageProvider,
    private val jdbc: JdbcTemplate,
    private val audit: AuditService,
    private val limiter: RateLimiter,
    @Value("\${app.storage.presign-minutes:10}") private val presignMinutes: Long,
    @Value("\${app.storage.max-image-bytes}") private val maxImage: Long,
    @Value("\${app.storage.max-document-bytes}") private val maxDocument: Long,
    @Value("\${app.rate-limit.upload-max:60}") private val uploadMax: Long,
    @Value("\${app.limits.max-assets-per-project:200}") private val maxAssets: Long,
    private val settings: com.systemwebstudio.settings.SettingsService
) {
    private val imageTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    private val documentTypes = setOf("application/pdf")      // SVG/HTML are rejected: they can carry active content

    private fun safeName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.', '_')
        return cleaned.take(100).ifEmpty { "file" }
    }

    private fun toDto(rs: java.sql.ResultSet, withUrl: Boolean) = AssetDto(
        rs.getObject("id", UUID::class.java), rs.getObject("workspace_id", UUID::class.java), rs.getObject("project_id", UUID::class.java),
        rs.getString("name"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("status"),
        rs.getObject("created_by", UUID::class.java), rs.getTimestamp("created_at").toInstant(),
        if (withUrl && rs.getString("status") == "READY") storage.presignDownload(rs.getString("storage_key"), Duration.ofMinutes(presignMinutes)) else null
    )

    private val columns = "id, workspace_id, project_id, name, content_type, size_bytes, status, created_by, created_at, storage_key"

    private fun find(projectId: UUID, workspaceId: UUID, assetId: UUID): Map<String, Any?> =
        jdbc.queryForList("SELECT $columns FROM assets WHERE id = ? AND project_id = ? AND workspace_id = ? AND status <> 'DELETED'", assetId, projectId, workspaceId)
            .firstOrNull() ?: throw ApiException.notFound("ASSET_NOT_FOUND", "Asset not found")

    @PostMapping("/upload-url")
    @Transactional
    fun uploadUrl(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID,
        @Valid @RequestBody request: UploadUrlRequest, @AuthenticationPrincipal me: StudioUserDetails
    ): UploadUrlResponse {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        limiter.require("upload:${me.userId}", uploadMax, 60, "upload")
        val held = jdbc.queryForObject("SELECT count(*) FROM assets WHERE project_id = ? AND status <> 'DELETED'", Long::class.java, projectId)!!
        if (held >= maxAssets) throw ApiException.conflict("ASSET_LIMIT", "This project reached its limit of $maxAssets files", mapOf("limit" to maxAssets))
        val type = request.contentType.trim().lowercase()
        val limit = when (type) { in imageTypes -> maxImage; in documentTypes -> maxDocument
            else -> throw ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "File type '$type' is not allowed") }
        val size = request.size!!
        // storage quota per project (READY and still-pending uploads count; deleted files do not)
        val usedBytes = jdbc.queryForObject("SELECT coalesce(sum(size_bytes), 0) FROM assets WHERE project_id = ? AND status <> 'DELETED'", Long::class.java, projectId)!!
        val quota = settings.long("storage.max-assets-mib-per-project") * 1024 * 1024
        if (usedBytes + size > quota) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "STORAGE_QUOTA", "This project's file storage is full (${usedBytes / 1048576} of ${quota / 1048576} MiB)",
            mapOf("usedBytes" to usedBytes, "quotaBytes" to quota))
        if (size <= 0 || size > limit) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File must be between 1 byte and $limit bytes", mapOf("maxBytes" to limit))
        val id = UUID.randomUUID()
        val name = safeName(request.fileName)
        val key = "$workspaceId/$projectId/$id/$name"            // always server generated
        jdbc.update("INSERT INTO assets (id, workspace_id, project_id, name, content_type, size_bytes, storage_key, status, created_by) VALUES (?,?,?,?,?,?,?,'PENDING',?)",
            id, workspaceId, projectId, name, type, size, key, me.userId)
        val expires = Duration.ofMinutes(presignMinutes)
        val signed = storage.presignUpload(key, type, expires)
        return UploadUrlResponse(id, signed.url, signed.method, signed.headers, expires.seconds)
    }

    @PostMapping("/complete")
    @Transactional(noRollbackFor = [ApiException::class])
    fun complete(
        @PathVariable workspaceId: UUID, @PathVariable projectId: UUID,
        @Valid @RequestBody request: CompleteUploadRequest, @AuthenticationPrincipal me: StudioUserDetails
    ): AssetDto {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        val row = find(projectId, workspaceId, request.assetId!!)
        if (row["status"] != "PENDING") throw ApiException.conflict("ASSET_NOT_PENDING", "Upload was already completed")
        val key = row["storage_key"] as String
        val declaredSize = (row["size_bytes"] as Number).toLong()
        val declaredType = row["content_type"] as String
        val stored = storage.stat(key) ?: throw ApiException.conflict("UPLOAD_MISSING", "No file was uploaded for this asset")
        val limit = if (declaredType in imageTypes) maxImage else maxDocument
        if (stored.size != declaredSize || stored.size > limit || !declaredType.equals(stored.contentType, ignoreCase = true)) {
            storage.delete(key)
            jdbc.update("UPDATE assets SET status = 'DELETED' WHERE id = ?", request.assetId)
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_UPLOAD", "Uploaded file does not match the declared size or type")
        }
        jdbc.update("UPDATE assets SET status = 'READY', completed_at = now() WHERE id = ?", request.assetId)
        audit.record("UPLOAD_ASSET", "ASSET", request.assetId, workspaceId, projectId, newValue = mapOf("name" to row["name"], "size" to declaredSize, "contentType" to declaredType))
        return jdbc.query("SELECT $columns FROM assets WHERE id = ?", { rs, _ -> toDto(rs, true) }, request.assetId).first()
    }

    @GetMapping
    @Transactional(readOnly = true)
    fun list(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<AssetDto> {
        access.forProject(me.userId, workspaceId, projectId)
        return jdbc.query("SELECT $columns FROM assets WHERE project_id = ? AND workspace_id = ? AND status = 'READY' ORDER BY created_at DESC LIMIT 200",
            { rs, _ -> toDto(rs, true) }, projectId, workspaceId)
    }

    @DeleteMapping("/{assetId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable assetId: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        val ctx = access.forProject(me.userId, workspaceId, projectId)
        ctx.require(Permission.PROJECT_EDIT)
        val row = find(projectId, workspaceId, assetId)
        jdbc.update("UPDATE assets SET status = 'DELETED' WHERE id = ?", assetId)
        runCatching { storage.delete(row["storage_key"] as String) }
        audit.record("DELETE_ASSET", "ASSET", assetId, workspaceId, projectId, oldValue = mapOf("name" to row["name"]))
    }
}
