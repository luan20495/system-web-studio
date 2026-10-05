package com.systemwebstudio.publish

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Hashtable
import java.util.UUID
import javax.naming.directory.InitialDirContext

data class DomainDto(val id: UUID, val hostname: String, val status: String, val tlsStatus: String, val lastError: String?, val verifiedAt: Instant?,
                     val lastCheckedAt: Instant?, val createdAt: Instant,
                     /** what the owner must put in DNS (no DNS credentials are ever requested or stored) */
                     val txtName: String, val txtValue: String, val cnameTarget: String)
data class AddDomainRequest(@field:NotBlank @field:Size(max = 253) val hostname: String)

/** DNS TXT lookup (JNDI DNS provider, system resolvers). A bean so tests can replace it. */
@Component
class DnsLookup {
    fun txt(name: String): List<String> = try {
        val env = Hashtable<String, String>()
        env["java.naming.factory.initial"] = "com.sun.jndi.dns.DnsContextFactory"
        env["com.sun.jndi.dns.timeout.initial"] = "2000"; env["com.sun.jndi.dns.timeout.retries"] = "1"
        val attr = InitialDirContext(env).getAttributes(name, arrayOf("TXT")).get("TXT")
        if (attr == null) emptyList() else (0 until attr.size()).map { attr.get(it).toString().trim().removeSurrounding("\"") }
    } catch (e: Exception) { emptyList() }
}

/** A real HTTPS request to the host: the JDK validates the certificate chain and the host name. A bean so tests can replace it. */
@Component
class TlsProbe {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build()
    /** (status, detail) with status ACTIVE | ERROR | PENDING */
    fun check(host: String): Pair<String, String?> = if (!com.systemwebstudio.runtime.PublicAddress.isPublic(host)) "ERROR" to "Tên miền trỏ tới địa chỉ không công khai" else try {
        http.send(HttpRequest.newBuilder(URI("https://$host/")).timeout(Duration.ofSeconds(8)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        "ACTIVE" to null
    } catch (e: javax.net.ssl.SSLException) { "ERROR" to "Chứng chỉ TLS không hợp lệ cho tên miền này" }
    catch (e: Exception) { "PENDING" to "Chưa kết nối HTTPS được tới tên miền (${e.javaClass.simpleName})" }
}

/**
 * Custom domains for published websites (stage G). Ownership is proven with a DNS TXT record; the site is then served for that Host by the
 * sites gateway. Only PUBLIC websites are served on custom domains (private sites keep the company sites host, where their session lives).
 * TLS is terminated in front of the gateway (CDN / tunnel); the status shown is the result of a real HTTPS handshake, never assumed.
 */
@Service
class SiteDomainService(private val jdbc: JdbcTemplate, private val sites: SiteService, private val dns: DnsLookup, private val tls: TlsProbe, private val audit: AuditService) {
    private val random = SecureRandom()
    private val hostRe = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    val sitesHost: String get() = runCatching { URI(sites.sitesOrigin).host }.getOrNull() ?: ""
    private val studioHost: String get() = runCatching { URI(sites.studioOrigin).host }.getOrNull() ?: ""

    private fun dto(rs: java.sql.ResultSet): DomainDto {
        val host = rs.getString(2)
        return DomainDto(rs.getObject(1, UUID::class.java), host, rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6)?.toInstant(),
            rs.getTimestamp(7)?.toInstant(), rs.getTimestamp(8).toInstant(), "_hbl-verify.$host", rs.getString(9), sitesHost)
    }
    private val select = "SELECT id, hostname, status, tls_status, last_error, verified_at, last_checked_at, created_at, verification_token FROM site_domains"

    fun list(projectId: UUID): List<DomainDto> = jdbc.query("$select WHERE project_id = ? ORDER BY created_at", { rs, _ -> dto(rs) }, projectId)
    private fun one(projectId: UUID, id: UUID): DomainDto = jdbc.query("$select WHERE id = ? AND project_id = ?", { rs, _ -> dto(rs) }, id, projectId).firstOrNull()
        ?: throw ApiException.notFound("DOMAIN_NOT_FOUND", "Domain not found")

    fun add(projectId: UUID, workspaceId: UUID, userId: UUID, raw: String): DomainDto {
        val host = raw.trim().lowercase().removeSuffix(".")
        if (!hostRe.matches(host)) throw ApiException.badRequest("INVALID_HOSTNAME", "Enter a host name such as www.example.com")
        val own = listOf(sitesHost, studioHost).filter { it.isNotBlank() }
        if (own.any { host == it || host.endsWith(".$it") }) throw ApiException.badRequest("RESERVED_HOSTNAME", "This host belongs to the platform")
        if (jdbc.queryForObject("SELECT count(*) FROM site_domains WHERE project_id = ?", Long::class.java, projectId)!! >= 5) throw ApiException.conflict("TOO_MANY_DOMAINS", "At most 5 domains per website")
        // a VERIFIED host belongs to one website; pending claims do not block anyone (the first to prove ownership wins)
        if (jdbc.queryForObject("SELECT count(*) FROM site_domains WHERE hostname = ? AND status = 'VERIFIED'", Long::class.java, host)!! > 0)
            throw ApiException.conflict("DOMAIN_TAKEN", "This domain is already connected to a website")
        val token = "hbl-" + ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val id = UUID.randomUUID()
        try { jdbc.update("INSERT INTO site_domains (id, project_id, hostname, verification_token, created_by) VALUES (?,?,?,?,?)", id, projectId, host, token, userId) }
        catch (e: org.springframework.dao.DuplicateKeyException) { throw ApiException.conflict("DOMAIN_EXISTS", "This website already has this domain") }
        audit.record("DOMAIN_ADDED", "SITE_DOMAIN", id, workspaceId, projectId, newValue = mapOf("hostname" to host))
        return one(projectId, id)
    }

    fun verify(projectId: UUID, workspaceId: UUID, id: UUID): DomainDto {
        val d = one(projectId, id)
        val found = dns.txt(d.txtName)
        val ok = d.txtValue in found
        if (ok && jdbc.queryForObject("SELECT count(*) FROM site_domains WHERE hostname = ? AND status = 'VERIFIED' AND id <> ?", Long::class.java, d.hostname, id)!! > 0)
            throw ApiException.conflict("DOMAIN_TAKEN", "Another website proved ownership of this domain first")
        jdbc.update("""UPDATE site_domains SET status = ?, last_error = ?, last_checked_at = now(), verified_at = CASE WHEN ? THEN coalesce(verified_at, now()) ELSE verified_at END WHERE id = ?""",
            if (ok) "VERIFIED" else "FAILED",
            if (ok) null else "Không tìm thấy bản ghi TXT ${d.txtName} = ${d.txtValue}" + if (found.isNotEmpty()) " (đang có ${found.size} giá trị khác)" else "", ok, id)
        if (ok) {
            audit.record("DOMAIN_VERIFIED", "SITE_DOMAIN", id, workspaceId, projectId, newValue = mapOf("hostname" to d.hostname))
            jdbc.update("DELETE FROM site_domains WHERE hostname = ? AND id <> ? AND status <> 'VERIFIED'", d.hostname, id)   // other claims are void now
        }
        if (ok) checkTls(projectId, id)
        return one(projectId, id)
    }

    fun checkTls(projectId: UUID, id: UUID): DomainDto {
        val d = one(projectId, id)
        if (d.status != "VERIFIED") throw ApiException.conflict("DOMAIN_NOT_VERIFIED", "Verify the domain first")
        val (status, detail) = tls.check(d.hostname)
        jdbc.update("UPDATE site_domains SET tls_status = ?, last_error = ?, last_checked_at = now() WHERE id = ?", status, detail, id)
        return one(projectId, id)
    }

    fun remove(projectId: UUID, workspaceId: UUID, id: UUID) {
        val d = one(projectId, id)
        jdbc.update("DELETE FROM site_domains WHERE id = ?", id)
        audit.record("DOMAIN_REMOVED", "SITE_DOMAIN", id, workspaceId, projectId, oldValue = mapOf("hostname" to d.hostname))
    }

    /**
     * Daily: verified domains are re-checked (TXT removed → FAILED, no longer served; a stale ownership must not keep serving), pending
     * claims older than 7 days are deleted.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "\${app.domains.recheck-interval-ms:86400000}", initialDelayString = "\${app.domains.recheck-initial-delay-ms:600000}")
    fun recheck() {
        jdbc.update("DELETE FROM site_domains WHERE status <> 'VERIFIED' AND created_at < now() - interval '7 days'")
        jdbc.queryForList("SELECT id, project_id, hostname, verification_token FROM site_domains WHERE status = 'VERIFIED'").forEach { r ->
            if ((r["verification_token"] as String) !in dns.txt("_hbl-verify.${r["hostname"]}")) {
                jdbc.update("UPDATE site_domains SET status = 'FAILED', last_error = ?, last_checked_at = now() WHERE id = ?", "Bản ghi TXT xác minh đã bị gỡ; tên miền ngừng phục vụ", r["id"])
                audit.record("DOMAIN_UNVERIFIED", "SITE_DOMAIN", r["id"], projectId = r["project_id"] as UUID, actorId = null, newValue = mapOf("hostname" to r["hostname"]))
            } else jdbc.update("UPDATE site_domains SET last_checked_at = now() WHERE id = ?", r["id"])
        }
    }

    /** Host the visitor asked for (the gateway passes Host through); lowercase, without port */
    fun requestHost(request: HttpServletRequest): String? = request.serverName?.lowercase()?.removeSuffix(".")?.takeIf { hostRe.matches(it) }

    /** the live PUBLIC website for a verified custom domain */
    fun liveFor(host: String): LiveSite? {
        val slug = jdbc.query("""SELECT s.slug FROM site_domains d JOIN sites s ON s.project_id = d.project_id WHERE d.hostname = ? AND d.status = 'VERIFIED'""",
            { rs, _ -> rs.getString(1) }, host).firstOrNull() ?: return null
        return sites.live(slug)
    }
}

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/domains")
class SiteDomainController(private val access: AccessService, private val domains: SiteDomainService) {
    private fun ctx(me: StudioUserDetails, w: UUID, p: UUID, publish: Boolean) = access.forProject(me.userId, w, p).also {
        if (publish) it.require(Permission.PROJECT_PUBLISH)
        if (it.project!!.appType == "STATIC_APP") throw ApiException.conflict("NOT_A_WEBSITE", "Custom domains are available for websites")
    }

    @GetMapping
    fun list(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<DomainDto> {
        ctx(me, workspaceId, projectId, false); return domains.list(projectId)
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody r: AddDomainRequest, @AuthenticationPrincipal me: StudioUserDetails): DomainDto {
        ctx(me, workspaceId, projectId, true); return domains.add(projectId, workspaceId, me.userId, r.hostname)
    }

    @PostMapping("/{id}/verify")
    fun verify(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): DomainDto {
        ctx(me, workspaceId, projectId, true); return domains.verify(projectId, workspaceId, id)
    }

    @PostMapping("/{id}/check-tls")
    fun tls(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): DomainDto {
        ctx(me, workspaceId, projectId, true); return domains.checkTls(projectId, id)
    }

    @DeleteMapping("/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails) {
        ctx(me, workspaceId, projectId, true); domains.remove(projectId, workspaceId, id)
    }
}
