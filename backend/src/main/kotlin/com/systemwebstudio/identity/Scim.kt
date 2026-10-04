package com.systemwebstudio.identity

import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** SCIM error (RFC 7644 §3.12): rendered as application/scim+json by [ScimController]. */
class ScimException(val status: HttpStatus, val scimType: String?, message: String) : RuntimeException(message)

/**
 * SCIM 2.0 user and group provisioning (stage I, RFC 7643/7644), OFF unless SCIM_ENABLED=true with a SCIM_TOKEN of at least 32 characters.
 * Rules: the IdP manages only the accounts it created (auth_source SCIM) — local accounts and the bootstrap admin can never be modified
 * through SCIM; no attribute maps to system admin; group membership becomes workspace membership only through an explicit admin mapping;
 * deprovisioning disables the account and revokes its sessions (data is kept).
 */
@Service
class ScimService(
    private val jdbc: JdbcTemplate, private val json: JsonMapper, private val encoder: PasswordEncoder, private val audit: AuditService,
    private val sessions: FindByIndexNameSessionRepository<*>
) {
    private val userName = Regex("^[a-z0-9][a-z0-9._@-]{0,119}$")
    private val email = Regex("^[^\\s@<>\"]{1,64}@[^\\s@<>\"]{1,190}$")
    companion object {
        const val USER = "urn:ietf:params:scim:schemas:core:2.0:User"
        const val GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group"
        const val LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
        const val PATCH = "urn:ietf:params:scim:api:messages:2.0:PatchOp"
    }

    private fun uuid(id: String) = runCatching { UUID.fromString(id) }.getOrNull() ?: throw ScimException(HttpStatus.NOT_FOUND, null, "Resource not found")

    // ------------------------------------------------------------------ users
    private data class U(val id: UUID, val userName: String, val displayName: String?, val email: String?, val active: Boolean, val externalId: String?, val created: Instant)

    private fun loadUser(id: UUID): U = jdbc.query("SELECT id, username, display_name, email, enabled, scim_external_id, created_at FROM users WHERE id = ? AND auth_source = 'SCIM'",
        { rs, _ -> U(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6), rs.getTimestamp(7).toInstant()) }, id)
        .firstOrNull() ?: throw ScimException(HttpStatus.NOT_FOUND, null, "User not found")

    private fun userJson(u: U, base: String): Map<String, Any?> = linkedMapOf(
        "schemas" to listOf(USER), "id" to u.id.toString(), "externalId" to u.externalId, "userName" to u.userName, "displayName" to u.displayName,
        "name" to mapOf("formatted" to u.displayName), "active" to u.active,
        "emails" to (u.email?.let { listOf(mapOf("value" to it, "primary" to true, "type" to "work")) } ?: emptyList()),
        "groups" to jdbc.query("SELECT g.id, g.display_name FROM scim_group_members m JOIN scim_groups g ON g.id = m.group_id WHERE m.user_id = ?",
            { rs, _ -> mapOf("value" to rs.getObject(1, UUID::class.java).toString(), "display" to rs.getString(2)) }, u.id),
        "meta" to mapOf("resourceType" to "User", "created" to u.created.toString(), "location" to "$base/Users/${u.id}"))

    fun getUser(id: String, base: String) = userJson(loadUser(uuid(id)), base)

    fun listUsers(filter: String?, start: Int, count: Int, base: String): Map<String, Any?> {
        val (where, args) = filterSql(filter, mapOf("username" to "username", "externalid" to "scim_external_id", "emails.value" to "email"))
        val total = jdbc.queryForObject("SELECT count(*) FROM users WHERE auth_source = 'SCIM'$where", Long::class.java, *args) ?: 0
        val rows = jdbc.query("SELECT id, username, display_name, email, enabled, scim_external_id, created_at FROM users WHERE auth_source = 'SCIM'$where ORDER BY created_at, id LIMIT ? OFFSET ?",
            { rs, _ -> U(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6), rs.getTimestamp(7).toInstant()) },
            *args, count.coerceIn(0, 200), (start - 1).coerceAtLeast(0))
        return linkedMapOf("schemas" to listOf(LIST), "totalResults" to total, "startIndex" to start, "itemsPerPage" to rows.size, "Resources" to rows.map { userJson(it, base) })
    }

    /** Only `attr eq "value"` (what IdPs send to look an account up); anything else is rejected with invalidFilter. */
    private fun filterSql(filter: String?, attrs: Map<String, String>): Pair<String, Array<Any?>> {
        if (filter.isNullOrBlank()) return "" to emptyArray()
        val m = Regex("^\\s*([A-Za-z.]+)\\s+eq\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$").find(filter) ?: throw ScimException(HttpStatus.BAD_REQUEST, "invalidFilter", "Only 'attribute eq \"value\"' filters are supported")
        val col = attrs[m.groupValues[1].lowercase()] ?: throw ScimException(HttpStatus.BAD_REQUEST, "invalidFilter", "Unsupported filter attribute")
        val v = m.groupValues[2].replace("\\\"", "\"")
        return (if (col == "username" || col == "email") " AND lower($col) = lower(?)" else " AND $col = ?") to arrayOf(v)
    }

    private fun bool(n: JsonNode?): Boolean? = when { n == null || n.isNull -> null; n.isBoolean -> n.asBoolean(); n.isString -> n.asString().equals("true", true); else -> null }
    private fun displayName(b: JsonNode) = b.get("displayName")?.asString()?.takeIf { it.isNotBlank() }
        ?: b.get("name")?.get("formatted")?.asString()?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(b.get("name")?.get("givenName")?.asString(), b.get("name")?.get("familyName")?.asString()).joinToString(" ").takeIf { it.isNotBlank() }
    private fun primaryEmail(b: JsonNode) = b.get("emails")?.takeIf { it.isArray }?.let { arr -> (arr.firstOrNull { it.get("primary")?.asBoolean() == true } ?: arr.firstOrNull())?.get("value")?.asString() }
        ?.trim()?.lowercase()?.takeIf { email.matches(it) && it.length <= 254 }

    private fun emailFree(e: String?, self: UUID?) = e != null && jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ? AND id IS DISTINCT FROM ?", Long::class.java, e, self)!! == 0L

    @Transactional
    fun createUser(b: JsonNode, base: String): Map<String, Any?> {
        val name = b.get("userName")?.asString()?.trim()?.lowercase() ?: throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "userName is required")
        if (!userName.matches(name) || name.startsWith("oidc-")) throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "userName: letters, digits, . _ @ - (oidc- is reserved)")
        if (jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(username) = ?", Long::class.java, name)!! > 0) throw ScimException(HttpStatus.CONFLICT, "uniqueness", "userName already exists")
        val ext = b.get("externalId")?.asString()?.take(255)
        if (ext != null && jdbc.queryForObject("SELECT count(*) FROM users WHERE scim_external_id = ?", Long::class.java, ext)!! > 0) throw ScimException(HttpStatus.CONFLICT, "uniqueness", "externalId already exists")
        val id = UUID.randomUUID()
        val unusable = encoder.encode(Base64.getEncoder().encodeToString(ByteArray(48).also(SecureRandom()::nextBytes)))     // no password login for SCIM accounts
        val mail = primaryEmail(b).takeIf { emailFree(it, null) }
        jdbc.update("INSERT INTO users (id, username, password_hash, enabled, display_name, email, auth_source, scim_external_id) VALUES (?,?,?,?,?,?, 'SCIM', ?)",
            id, name, unusable, bool(b.get("active")) ?: true, displayName(b)?.take(160), mail, ext)
        audit.record("SCIM_USER_CREATED", "USER", id, actorId = null, newValue = mapOf("userName" to name, "externalId" to ext))
        return getUser(id.toString(), base)
    }

    @Transactional
    fun replaceUser(id: String, b: JsonNode, base: String): Map<String, Any?> {
        val u = loadUser(uuid(id))
        val name = b.get("userName")?.asString()?.trim()?.lowercase() ?: u.userName
        if (!userName.matches(name)) throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "userName: letters, digits, . _ @ -")
        if (name != u.userName && jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(username) = ?", Long::class.java, name)!! > 0) throw ScimException(HttpStatus.CONFLICT, "uniqueness", "userName already exists")
        val mail = primaryEmail(b).takeIf { emailFree(it, u.id) }
        jdbc.update("UPDATE users SET username = ?, display_name = ?, email = ?, scim_external_id = coalesce(?, scim_external_id) WHERE id = ?",
            name, displayName(b)?.take(160), mail, b.get("externalId")?.asString()?.take(255), u.id)
        bool(b.get("active"))?.let { setActive(u, it) }
        audit.record("SCIM_USER_UPDATED", "USER", u.id, actorId = null)
        return getUser(id, base)
    }

    @Transactional
    fun patchUser(id: String, b: JsonNode, base: String): Map<String, Any?> {
        val u = loadUser(uuid(id))
        val ops = b.get("Operations")?.takeIf { it.isArray } ?: throw ScimException(HttpStatus.BAD_REQUEST, "invalidSyntax", "Operations are required")
        for (op in ops) {
            val kind = op.get("op")?.asString()?.lowercase()
            val path = op.get("path")?.asString()?.lowercase()
            val value = op.get("value")
            if (kind !in setOf("replace", "add")) throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "Unsupported op for users")
            // Entra/Okta send either {path:"active", value:false|"False"} or {value:{active:false, ...}}
            val fields: Map<String, JsonNode?> = if (path != null) mapOf(path to value) else value?.takeIf { it.isObject }?.let { v -> v.propertyNames().associate { it.lowercase() to v.get(it) } } ?: emptyMap()
            fields.forEach { (k, v) ->
                when (k) {
                    "active" -> bool(v)?.let { setActive(loadUser(u.id), it) }
                    "displayname", "name.formatted" -> jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", v?.asString()?.take(160), u.id)
                    "username" -> v?.asString()?.trim()?.lowercase()?.takeIf { userName.matches(it) }?.let { n ->
                        if (n != u.userName && jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(username) = ?", Long::class.java, n)!! > 0) throw ScimException(HttpStatus.CONFLICT, "uniqueness", "userName already exists")
                        jdbc.update("UPDATE users SET username = ? WHERE id = ?", n, u.id) }
                    "externalid" -> jdbc.update("UPDATE users SET scim_external_id = ? WHERE id = ?", v?.asString()?.take(255), u.id)
                    "emails", "emails[type eq \"work\"].value" -> {
                        val e = if (v?.isArray == true) primaryEmail(json.createObjectNode().set("emails", v)) else v?.asString()?.trim()?.lowercase()
                        jdbc.update("UPDATE users SET email = ? WHERE id = ?", e?.takeIf { email.matches(it) && emailFree(it, u.id) }, u.id)
                    }
                    else -> {}   // unknown attributes are ignored (roles/entitlements never grant anything here)
                }
            }
        }
        audit.record("SCIM_USER_UPDATED", "USER", u.id, actorId = null)
        return getUser(id, base)
    }

    private fun setActive(u: U, active: Boolean) {
        if (u.active == active) return
        jdbc.update("UPDATE users SET enabled = ? WHERE id = ?", active, u.id)
        if (!active) revoke(u.userName)
        audit.record(if (active) "SCIM_USER_ENABLED" else "SCIM_USER_DISABLED", "USER", u.id, actorId = null)
    }

    private fun revoke(username: String) = sessions.findByPrincipalName(username).keys.forEach { sessions.deleteById(it) }

    /** Deprovisioning disables (keeps data and authorship); memberships granted through SCIM groups are removed. */
    @Transactional
    fun deleteUser(id: String) {
        val u = loadUser(uuid(id))
        setActive(u, false)
        val groups = jdbc.queryForList("SELECT group_id FROM scim_group_members WHERE user_id = ?", UUID::class.java, u.id)
        jdbc.update("DELETE FROM scim_group_members WHERE user_id = ?", u.id)
        groups.forEach { syncGroup(it) }
        jdbc.update("DELETE FROM workspace_members WHERE user_id = ? AND source = 'SCIM'", u.id)
        audit.record("SCIM_USER_DEPROVISIONED", "USER", u.id, actorId = null)
    }

    // ------------------------------------------------------------------ groups
    private fun groupJson(id: UUID, base: String): Map<String, Any?> {
        val g = jdbc.queryForList("SELECT display_name, external_id, created_at FROM scim_groups WHERE id = ?", id).firstOrNull() ?: throw ScimException(HttpStatus.NOT_FOUND, null, "Group not found")
        val members = jdbc.query("SELECT u.id, u.username FROM scim_group_members m JOIN users u ON u.id = m.user_id WHERE m.group_id = ? ORDER BY u.username",
            { rs, _ -> mapOf("value" to rs.getObject(1, UUID::class.java).toString(), "display" to rs.getString(2)) }, id)
        return linkedMapOf("schemas" to listOf(GROUP), "id" to id.toString(), "externalId" to g["external_id"], "displayName" to g["display_name"], "members" to members,
            "meta" to mapOf("resourceType" to "Group", "created" to g["created_at"].toString(), "location" to "$base/Groups/$id"))
    }

    fun getGroup(id: String, base: String) = groupJson(uuid(id), base)

    fun listGroups(filter: String?, start: Int, count: Int, base: String): Map<String, Any?> {
        val (where, args) = filterSql(filter, mapOf("displayname" to "display_name", "externalid" to "external_id"))
        val w = where.replace("lower(display_name)", "lower(display_name)")
        val total = jdbc.queryForObject("SELECT count(*) FROM scim_groups WHERE TRUE$w", Long::class.java, *args) ?: 0
        val ids = jdbc.query("SELECT id FROM scim_groups WHERE TRUE$w ORDER BY created_at, id LIMIT ? OFFSET ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, *args, count.coerceIn(0, 200), (start - 1).coerceAtLeast(0))
        return linkedMapOf("schemas" to listOf(LIST), "totalResults" to total, "startIndex" to start, "itemsPerPage" to ids.size, "Resources" to ids.map { groupJson(it, base) })
    }

    private fun memberIds(arr: JsonNode?): List<UUID> = arr?.takeIf { it.isArray }?.mapNotNull { m -> m.get("value")?.asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() } }.orEmpty().also { ids ->
        // only accounts provisioned through SCIM can be group members
        ids.forEach { id -> if (jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ? AND auth_source = 'SCIM'", Long::class.java, id)!! == 0L)
            throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "Member $id is not a SCIM-provisioned user") }
    }

    @Transactional
    fun createGroup(b: JsonNode, base: String): Map<String, Any?> {
        val name = b.get("displayName")?.asString()?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: throw ScimException(HttpStatus.BAD_REQUEST, "invalidValue", "displayName is required")
        if (jdbc.queryForObject("SELECT count(*) FROM scim_groups WHERE display_name = ?", Long::class.java, name)!! > 0) throw ScimException(HttpStatus.CONFLICT, "uniqueness", "Group already exists")
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO scim_groups (id, display_name, external_id) VALUES (?,?,?)", id, name, b.get("externalId")?.asString()?.take(255))
        memberIds(b.get("members")).forEach { jdbc.update("INSERT INTO scim_group_members (group_id, user_id) VALUES (?,?) ON CONFLICT DO NOTHING", id, it) }
        syncGroup(id)
        audit.record("SCIM_GROUP_CREATED", "SCIM_GROUP", id, actorId = null, newValue = mapOf("displayName" to name))
        return groupJson(id, base)
    }

    @Transactional
    fun replaceGroup(id: String, b: JsonNode, base: String): Map<String, Any?> {
        val gid = uuid(id); groupJson(gid, base)
        b.get("displayName")?.asString()?.trim()?.take(200)?.takeIf { it.isNotEmpty() }?.let { jdbc.update("UPDATE scim_groups SET display_name = ?, updated_at = now() WHERE id = ?", it, gid) }
        val members = memberIds(b.get("members"))
        jdbc.update("DELETE FROM scim_group_members WHERE group_id = ?", gid)
        members.forEach { jdbc.update("INSERT INTO scim_group_members (group_id, user_id) VALUES (?,?) ON CONFLICT DO NOTHING", gid, it) }
        syncGroup(gid)
        audit.record("SCIM_GROUP_UPDATED", "SCIM_GROUP", gid, actorId = null)
        return groupJson(gid, base)
    }

    @Transactional
    fun patchGroup(id: String, b: JsonNode, base: String): Map<String, Any?> {
        val gid = uuid(id); groupJson(gid, base)
        val ops = b.get("Operations")?.takeIf { it.isArray } ?: throw ScimException(HttpStatus.BAD_REQUEST, "invalidSyntax", "Operations are required")
        for (op in ops) {
            val kind = op.get("op")?.asString()?.lowercase(); val path = op.get("path")?.asString().orEmpty()
            when {
                kind == "add" && path.lowercase() == "members" -> memberIds(op.get("value")).forEach { jdbc.update("INSERT INTO scim_group_members (group_id, user_id) VALUES (?,?) ON CONFLICT DO NOTHING", gid, it) }
                kind == "remove" && path.lowercase() == "members" -> {
                    val ids = op.get("value")?.takeIf { it.isArray }?.mapNotNull { m -> m.get("value")?.asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() } }
                    if (ids == null) jdbc.update("DELETE FROM scim_group_members WHERE group_id = ?", gid) else ids.forEach { jdbc.update("DELETE FROM scim_group_members WHERE group_id = ? AND user_id = ?", gid, it) }
                }
                kind == "remove" && path.startsWith("members[value eq \"") -> {
                    val uid = path.substringAfter("members[value eq \"").substringBefore("\"]")
                    runCatching { UUID.fromString(uid) }.getOrNull()?.let { jdbc.update("DELETE FROM scim_group_members WHERE group_id = ? AND user_id = ?", gid, it) }
                }
                kind == "replace" && (path.lowercase() == "displayname" || (path.isEmpty() && op.get("value")?.has("displayName") == true)) -> {
                    val v = if (path.isEmpty()) op.get("value").get("displayName") else op.get("value")
                    v?.asString()?.trim()?.take(200)?.takeIf { it.isNotEmpty() }?.let { jdbc.update("UPDATE scim_groups SET display_name = ?, updated_at = now() WHERE id = ?", it, gid) }
                }
                kind == "replace" && path.lowercase() == "members" -> {
                    val ids = memberIds(op.get("value")); jdbc.update("DELETE FROM scim_group_members WHERE group_id = ?", gid)
                    ids.forEach { jdbc.update("INSERT INTO scim_group_members (group_id, user_id) VALUES (?,?) ON CONFLICT DO NOTHING", gid, it) }
                }
                else -> throw ScimException(HttpStatus.BAD_REQUEST, "invalidPath", "Unsupported group operation")
            }
        }
        syncGroup(gid)
        audit.record("SCIM_GROUP_UPDATED", "SCIM_GROUP", gid, actorId = null)
        return groupJson(gid, base)
    }

    @Transactional
    fun deleteGroup(id: String) {
        val gid = uuid(id)
        val mapped = jdbc.queryForList("SELECT workspace_id FROM scim_group_mappings WHERE group_id = ?", UUID::class.java, gid)
        if (jdbc.update("DELETE FROM scim_groups WHERE id = ?", gid) == 0) throw ScimException(HttpStatus.NOT_FOUND, null, "Group not found")
        mapped.forEach { syncWorkspace(it) }
        audit.record("SCIM_GROUP_DELETED", "SCIM_GROUP", gid, actorId = null)
    }

    /** Workspace memberships follow the admin's group mappings: SCIM-sourced rows are added/updated/removed, manual rows are never touched. */
    fun syncGroup(groupId: UUID) = jdbc.queryForList("SELECT workspace_id FROM scim_group_mappings WHERE group_id = ?", UUID::class.java, groupId).forEach { syncWorkspace(it) }

    fun syncWorkspace(workspaceId: UUID) {
        val rank = mapOf("WORKSPACE_ADMIN" to 0, "EDITOR" to 1, "PUBLISHER" to 2, "VIEWER" to 3)
        // desired role per user = the strongest role among the mapped groups they are in (only enabled SCIM accounts)
        val desired = jdbc.query("""SELECT m.user_id, map.role FROM scim_group_mappings map JOIN scim_group_members m ON m.group_id = map.group_id
            JOIN users u ON u.id = m.user_id AND u.enabled AND u.auth_source = 'SCIM' WHERE map.workspace_id = ?""", { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }, workspaceId)
            .groupBy({ it.first }, { it.second }).mapValues { (_, roles) -> roles.minBy { rank[it] ?: 9 } }
        val current = jdbc.query("SELECT user_id, role, source FROM workspace_members WHERE workspace_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) to (rs.getString(2) to rs.getString(3)) }, workspaceId).toMap()
        desired.forEach { (uid, role) ->
            val cur = current[uid]
            when {
                cur == null -> jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role, active, source) VALUES (?,?,?, TRUE, 'SCIM')", workspaceId, uid, role)
                cur.second == "SCIM" && cur.first != role -> jdbc.update("UPDATE workspace_members SET role = ?, active = TRUE WHERE workspace_id = ? AND user_id = ?", role, workspaceId, uid)
                else -> {}
            }
        }
        current.filter { (uid, v) -> v.second == "SCIM" && uid !in desired }.keys.forEach { jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ? AND source = 'SCIM'", workspaceId, it) }
    }
}

/** RFC 7644 endpoints under /scim/v2 (its own stateless security chain; the bearer token is checked here). */
@RestController
@RequestMapping("/scim/v2", produces = ["application/scim+json", MediaType.APPLICATION_JSON_VALUE])
class ScimController(
    private val scim: ScimService, private val json: JsonMapper, private val limiter: RateLimiter,
    @Value("\${app.scim.enabled:false}") private val enabled: Boolean, @Value("\${app.scim.token:}") token: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tokenHash: ByteArray? = token.takeIf { it.length >= 32 }?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
    init { if (enabled && tokenHash == null) log.warn("SCIM_ENABLED=true but SCIM_TOKEN is missing or shorter than 32 characters: SCIM stays off") }

    private fun guard(request: HttpServletRequest) {
        if (!enabled || tokenHash == null) throw ScimException(HttpStatus.NOT_FOUND, null, "Not found")
        val d = limiter.hit("scim:${request.remoteAddr}", 600, 60)
        if (!d.allowed) throw ScimException(HttpStatus.TOO_MANY_REQUESTS, null, "Too many requests")
        val given = request.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        if (given == null || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(given.toByteArray()), tokenHash)) throw ScimException(HttpStatus.UNAUTHORIZED, null, "Invalid bearer token")
    }
    private fun base(request: HttpServletRequest) = request.requestURL.toString().substringBefore("/scim/v2") + "/scim/v2"
    private fun body(raw: String): JsonNode = runCatching { json.readTree(raw) }.getOrNull()?.takeIf { it.isObject } ?: throw ScimException(HttpStatus.BAD_REQUEST, "invalidSyntax", "Body must be a JSON object")
    private fun ok(status: HttpStatus, v: Any?): ResponseEntity<Any> = ResponseEntity.status(status).contentType(MediaType.parseMediaType("application/scim+json")).body(v)

    @ExceptionHandler(ScimException::class)
    fun error(e: ScimException): ResponseEntity<Any> = ok(e.status, linkedMapOf("schemas" to listOf("urn:ietf:params:scim:api:messages:2.0:Error"),
        "status" to e.status.value().toString(), "scimType" to e.scimType, "detail" to e.message))

    @GetMapping("/ServiceProviderConfig")
    fun config(request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, mapOf(
        "schemas" to listOf("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"), "patch" to mapOf("supported" to true), "bulk" to mapOf("supported" to false, "maxOperations" to 0, "maxPayloadSize" to 0),
        "filter" to mapOf("supported" to true, "maxResults" to 200), "changePassword" to mapOf("supported" to false), "sort" to mapOf("supported" to false), "etag" to mapOf("supported" to false),
        "authenticationSchemes" to listOf(mapOf("type" to "oauthbearertoken", "name" to "Bearer token", "description" to "Static bearer token (SCIM_TOKEN)")))) }

    @GetMapping("/ResourceTypes")
    fun types(request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, mapOf("schemas" to listOf(ScimService.LIST), "totalResults" to 2, "Resources" to listOf(
        mapOf("schemas" to listOf("urn:ietf:params:scim:schemas:core:2.0:ResourceType"), "id" to "User", "name" to "User", "endpoint" to "/Users", "schema" to ScimService.USER),
        mapOf("schemas" to listOf("urn:ietf:params:scim:schemas:core:2.0:ResourceType"), "id" to "Group", "name" to "Group", "endpoint" to "/Groups", "schema" to ScimService.GROUP)))) }

    @GetMapping("/Schemas")
    fun schemas(request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, mapOf("schemas" to listOf(ScimService.LIST), "totalResults" to 2, "Resources" to listOf(
        mapOf("id" to ScimService.USER, "name" to "User", "attributes" to listOf("userName", "displayName", "name", "emails", "active", "externalId").map { mapOf("name" to it) }),
        mapOf("id" to ScimService.GROUP, "name" to "Group", "attributes" to listOf("displayName", "members", "externalId").map { mapOf("name" to it) })))) }

    @GetMapping("/Users")
    fun users(@RequestParam(required = false) filter: String?, @RequestParam(defaultValue = "1") startIndex: Int, @RequestParam(defaultValue = "100") count: Int, request: HttpServletRequest): ResponseEntity<Any> {
        guard(request); return ok(HttpStatus.OK, scim.listUsers(filter, startIndex.coerceAtLeast(1), count, base(request)))
    }
    @GetMapping("/Users/{id}") fun user(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, scim.getUser(id, base(request))) }
    @PostMapping("/Users") fun createUser(@RequestBody raw: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.CREATED, scim.createUser(body(raw), base(request))) }
    @PutMapping("/Users/{id}") fun replaceUser(@PathVariable id: String, @RequestBody raw: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, scim.replaceUser(id, body(raw), base(request))) }
    @PatchMapping("/Users/{id}") fun patchUser(@PathVariable id: String, @RequestBody raw: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, scim.patchUser(id, body(raw), base(request))) }
    @DeleteMapping("/Users/{id}") fun deleteUser(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); scim.deleteUser(id); return ResponseEntity.noContent().build() }

    @GetMapping("/Groups")
    fun groups(@RequestParam(required = false) filter: String?, @RequestParam(defaultValue = "1") startIndex: Int, @RequestParam(defaultValue = "100") count: Int, request: HttpServletRequest): ResponseEntity<Any> {
        guard(request); return ok(HttpStatus.OK, scim.listGroups(filter, startIndex.coerceAtLeast(1), count, base(request)))
    }
    @GetMapping("/Groups/{id}") fun group(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, scim.getGroup(id, base(request))) }
    @PostMapping("/Groups") fun createGroup(@RequestBody raw: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.CREATED, scim.createGroup(body(raw), base(request))) }
    @PutMapping("/Groups/{id}") fun replaceGroup(@PathVariable id: String, @RequestBody raw: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, scim.replaceGroup(id, body(raw), base(request))) }
    @PatchMapping("/Groups/{id}") fun patchGroup(@PathVariable id: String, @RequestBody raw: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); return ok(HttpStatus.OK, scim.patchGroup(id, body(raw), base(request))) }
    @DeleteMapping("/Groups/{id}") fun deleteGroup(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Any> { guard(request); scim.deleteGroup(id); return ResponseEntity.noContent().build() }
}

data class ScimMappingRequest(@field:NotNull val groupId: UUID?, @field:NotNull val workspaceId: UUID?, @field:Pattern(regexp = "WORKSPACE_ADMIN|EDITOR|PUBLISHER|VIEWER") val role: String = "VIEWER")

/** Admin side: SCIM status, groups pushed by the IdP and the explicit group → workspace role mappings. */
@RestController
@RequestMapping("/api/v1/admin/scim")
class AdminScimController(private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val scim: ScimService, private val audit: AuditService,
                          @Value("\${app.scim.enabled:false}") private val enabled: Boolean, @Value("\${app.scim.token:}") private val token: String) {
    @GetMapping
    fun status(@AuthenticationPrincipal me: StudioUserDetails): Map<String, Any?> {
        guard.require(me.userId)
        return mapOf("enabled" to (enabled && token.length >= 32), "users" to jdbc.queryForObject("SELECT count(*) FROM users WHERE auth_source = 'SCIM'", Long::class.java),
            "groups" to jdbc.query("""SELECT g.id, g.display_name, (SELECT count(*) FROM scim_group_members m WHERE m.group_id = g.id) FROM scim_groups g ORDER BY g.display_name""") { rs, _ ->
                mapOf("id" to rs.getObject(1, UUID::class.java), "displayName" to rs.getString(2), "members" to rs.getLong(3)) },
            "mappings" to jdbc.query("""SELECT m.id, g.display_name, w.name, m.role, m.group_id, m.workspace_id FROM scim_group_mappings m JOIN scim_groups g ON g.id = m.group_id
                JOIN workspaces w ON w.id = m.workspace_id ORDER BY g.display_name""") { rs, _ ->
                mapOf("id" to rs.getObject(1, UUID::class.java), "group" to rs.getString(2), "workspace" to rs.getString(3), "role" to rs.getString(4),
                    "groupId" to rs.getObject(5, UUID::class.java), "workspaceId" to rs.getObject(6, UUID::class.java)) })
    }

    @PostMapping("/mappings")
    @Transactional
    fun addMapping(@Valid @RequestBody r: ScimMappingRequest, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any?> {
        guard.require(me.userId)
        val id = UUID.randomUUID()
        try { jdbc.update("INSERT INTO scim_group_mappings (id, group_id, workspace_id, role, created_by) VALUES (?,?,?,?,?)", id, r.groupId, r.workspaceId, r.role, me.userId) }
        catch (e: org.springframework.dao.DuplicateKeyException) { throw com.systemwebstudio.common.ApiException.conflict("MAPPING_EXISTS", "This group is already mapped to the workspace") }
        catch (e: org.springframework.dao.DataIntegrityViolationException) { throw com.systemwebstudio.common.ApiException.notFound("NOT_FOUND", "Group or workspace not found") }
        scim.syncWorkspace(r.workspaceId!!)
        audit.record("SCIM_MAPPING_ADDED", "SCIM_GROUP", r.groupId, r.workspaceId, newValue = mapOf("role" to r.role))
        return status(me)
    }

    @DeleteMapping("/mappings/{id}")
    @Transactional
    fun deleteMapping(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any?> {
        guard.require(me.userId)
        val ws = jdbc.query("SELECT workspace_id FROM scim_group_mappings WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, id).firstOrNull()
            ?: throw com.systemwebstudio.common.ApiException.notFound("NOT_FOUND", "Mapping not found")
        jdbc.update("DELETE FROM scim_group_mappings WHERE id = ?", id)
        scim.syncWorkspace(ws)
        audit.record("SCIM_MAPPING_REMOVED", "SCIM_GROUP", id, ws)
        return status(me)
    }
}
