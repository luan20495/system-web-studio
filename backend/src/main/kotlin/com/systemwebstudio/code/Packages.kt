package com.systemwebstudio.code

import com.systemwebstudio.access.AccessService
import com.systemwebstudio.access.Permission
import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.git.GitFileChange
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

const val PKG_NAME = "^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]{0,213}$"
const val PKG_SPEC = "^(latest|[0-9A-Za-z.^~<>=| *+-]{1,100})$"

data class PackageView(val name: String, val status: String, val versionRange: String, val pinnedVersion: String?, val note: String, val riskAccepted: Boolean,
                       val dependencies: Int?, val findings: JsonNode?, val requestedBy: String?, val decidedBy: String?, val decidedAt: Instant?, val updatedAt: Instant)
data class PackageApproval(@field:NotBlank @field:Pattern(regexp = PKG_NAME) val name: String, @field:Pattern(regexp = PKG_SPEC) val versionRange: String? = null,
                           @field:Pattern(regexp = "^[0-9A-Za-z.+-]{1,64}$") val pinnedVersion: String? = null, @field:Size(max = 500) val note: String? = null)
data class PackageDecision(@field:Pattern(regexp = "ALLOWED|DENIED") val status: String, val acceptRisk: Boolean? = null, @field:Size(max = 500) val note: String? = null)
data class DependencyRequestBody(@field:NotBlank @field:Pattern(regexp = PKG_NAME) val name: String)
data class DependencyRequestView(val id: UUID, val packageName: String, val spec: String, val status: String, val changeId: UUID?, val error: String?, val createdAt: Instant)

/**
 * Approved package catalog (ADR 0013). The allowlist used by the package mirror and checked against every lockfile is:
 * the approved scaffold's lockfile + company packages (@company/…) + the resolved closure of every ALLOWED package.
 */
@Service
class PackageCatalogService(private val jdbc: JdbcTemplate, private val json: JsonMapper, private val audit: AuditService) {
    /** every approved scaffold's lockfile (static web app and server app) */
    private val scaffoldNames: Set<String> by lazy {
        CodeProjectService.SCAFFOLDS.values.distinct().flatMap { s ->
            val lock = json.readTree(ClassPathResource("scaffolds/$s/package-lock.json").inputStream)
            lock.get("packages")?.propertyNames()?.filter { it.isNotEmpty() }?.map { it.substringAfterLast("node_modules/") }.orEmpty()
        }.toSet()
    }

    fun allowlist(): Set<String> {
        val approved = jdbc.query("SELECT resolved::text FROM approved_packages WHERE status = 'ALLOWED' AND resolved IS NOT NULL") { rs, _ -> rs.getString(1) }
            .flatMap { json.readTree(it).toList().map { p -> p.get("name").asString() } }
        val names = jdbc.queryForList("SELECT name FROM approved_packages WHERE status = 'ALLOWED'", String::class.java)
        return scaffoldNames + approved + names
    }
    fun allowlistHash(names: Set<String>) = MessageDigest.getInstance("SHA-256").digest(names.sorted().joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    fun isCompany(name: String) = name.startsWith("@company/")

    fun view(name: String): PackageView? = jdbc.query("""SELECT p.*, coalesce(r.display_name, r.username) AS req, coalesce(d.display_name, d.username) AS dec FROM approved_packages p
        LEFT JOIN users r ON r.id = p.requested_by LEFT JOIN users d ON d.id = p.decided_by WHERE p.name = ?""", { rs, _ -> row(rs) }, name).firstOrNull()

    fun list(): List<PackageView> = jdbc.query("""SELECT p.*, coalesce(r.display_name, r.username) AS req, coalesce(d.display_name, d.username) AS dec FROM approved_packages p
        LEFT JOIN users r ON r.id = p.requested_by LEFT JOIN users d ON d.id = p.decided_by ORDER BY (p.status = 'PENDING') DESC, p.updated_at DESC LIMIT 500""") { rs, _ -> row(rs) }

    private fun row(rs: java.sql.ResultSet) = PackageView(rs.getString("name"), rs.getString("status"), rs.getString("version_range"), rs.getString("pinned_version"),
        rs.getString("note"), rs.getBoolean("risk_accepted"), rs.getString("resolved")?.let { json.readTree(it).size() }, rs.getString("findings")?.let { json.readTree(it) },
        rs.getString("req"), rs.getString("dec"), rs.getTimestamp("decided_at")?.toInstant(), rs.getTimestamp("updated_at").toInstant())

    /** spec handed to npm: the pinned version, else the admin's range (users cannot choose versions outside it) */
    fun spec(name: String): String? = jdbc.query("SELECT pinned_version, version_range FROM approved_packages WHERE name = ? AND status = 'ALLOWED'",
        { rs, _ -> rs.getString(1) ?: rs.getString(2) }, name).firstOrNull()

    // AFTER_COMMIT listeners still see the finished transaction bound: work in a NEW transaction so it really commits
    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun onJob(e: ToolJobFinished) { if (e.purpose == "RESOLVE") e.input?.get("name")?.asString()?.let { onResolved(it, e.result, e.error) } }

    /** RESOLVE job result: closure + OSV findings. HIGH/CRITICAL → DENIED unless an admin accepted the risk (documented exception). */
    fun onResolved(name: String, result: JsonNode?, error: String?) {
        if (result == null) {
            jdbc.update("UPDATE approved_packages SET status = 'DENIED', note = ?, updated_at = now() WHERE name = ? AND status = 'RESOLVING'", "Resolve failed: ${error ?: "unknown"}".take(500), name)
            audit.record("PACKAGE_DENIED", "PACKAGE", name, actorId = null, newValue = mapOf("reason" to "resolve failed")); return
        }
        val blocking = result.get("blocking")?.size() ?: 0
        val accepted = jdbc.queryForObject("SELECT risk_accepted FROM approved_packages WHERE name = ?", Boolean::class.java, name) == true
        val status = if (blocking > 0 && !accepted) "DENIED" else "ALLOWED"
        jdbc.update("""UPDATE approved_packages SET status = ?, resolved = CAST(? AS jsonb), findings = CAST(? AS jsonb), updated_at = now(),
            note = CASE WHEN ? THEN 'Tự động từ chối: lỗ hổng HIGH/CRITICAL (OSV)' ELSE note END WHERE name = ?""",
            status, json.writeValueAsString(result.get("packages")), json.writeValueAsString(result.get("findings")), status == "DENIED", name)
        audit.record(if (status == "ALLOWED") "PACKAGE_ALLOWED" else "PACKAGE_DENIED", "PACKAGE", name, actorId = null,
            newValue = mapOf("dependencies" to result.get("packages")?.size(), "blocking" to blocking))
    }
}

@RestController
@RequestMapping("/api/v1/admin/packages")
class AdminPackageController(private val guard: AdminGuard, private val catalog: PackageCatalogService, private val jobs: BuildJobService,
                             private val jdbc: JdbcTemplate, private val audit: AuditService) {
    @GetMapping
    fun list(@AuthenticationPrincipal me: StudioUserDetails): List<PackageView> { guard.require(me.userId); return catalog.list() }

    /** Approve (or re-check) a package: resolves its dependency closure in a sandbox without running package code, then scans it with OSV. */
    @PostMapping
    @Transactional
    fun approve(@Valid @RequestBody body: PackageApproval, @AuthenticationPrincipal me: StudioUserDetails): PackageView {
        guard.require(me.userId)
        if (catalog.isCompany(body.name)) throw ApiException.badRequest("COMPANY_PACKAGE", "Company packages are always allowed")
        val spec = body.pinnedVersion ?: body.versionRange ?: "latest"
        jdbc.update("""INSERT INTO approved_packages (name, status, version_range, pinned_version, note, decided_by, decided_at) VALUES (?, 'RESOLVING', ?, ?, ?, ?, now())
            ON CONFLICT (name) DO UPDATE SET status = 'RESOLVING', version_range = EXCLUDED.version_range, pinned_version = EXCLUDED.pinned_version,
            note = EXCLUDED.note, decided_by = EXCLUDED.decided_by, decided_at = now(), updated_at = now()""",
            body.name, body.versionRange ?: "latest", body.pinnedVersion, body.note?.trim().orEmpty(), me.userId)
        jobs.enqueueTool("RESOLVE", null, mapOf("name" to body.name, "spec" to spec), me.userId)
        audit.record("PACKAGE_REVIEW_STARTED", "PACKAGE", body.name, newValue = mapOf("spec" to spec))
        return catalog.view(body.name)!!
    }

    /** Explicit decision; allowing a package with HIGH/CRITICAL findings requires acceptRisk (recorded as an exception). */
    @PutMapping("/{name}/decision")
    @Transactional
    fun decide(@PathVariable name: String, @Valid @RequestBody body: PackageDecision, @AuthenticationPrincipal me: StudioUserDetails): PackageView {
        guard.require(me.userId)
        val p = catalog.view(name) ?: throw ApiException.notFound("PACKAGE_NOT_FOUND", "Unknown package")
        if (body.status == "ALLOWED") {
            if (p.dependencies == null) throw ApiException.conflict("NOT_RESOLVED", "Resolve the package first (approve it to start the check)")
            val blocking = p.findings?.toList()?.count { it.get("severity")?.asString() in setOf("HIGH", "CRITICAL") } ?: 0
            if (blocking > 0 && body.acceptRisk != true) throw ApiException(HttpStatus.PRECONDITION_REQUIRED, "RISK_ACCEPTANCE_REQUIRED", "This package has $blocking HIGH/CRITICAL advisories")
        }
        jdbc.update("UPDATE approved_packages SET status = ?, risk_accepted = ?, note = coalesce(?, note), decided_by = ?, decided_at = now(), updated_at = now() WHERE name = ?",
            body.status, body.status == "ALLOWED" && body.acceptRisk == true, body.note?.trim(), me.userId, name)
        audit.record(if (body.status == "ALLOWED") "PACKAGE_ALLOWED" else "PACKAGE_DENIED", "PACKAGE", name,
            oldValue = mapOf("status" to p.status), newValue = mapOf("status" to body.status, "riskAccepted" to (body.acceptRisk == true)))
        return catalog.view(name)!!
    }
}

/** Dependency requests from Code mode / AI: only catalog packages, versions chosen by the catalog, lockfile made in the sandbox. */
@Service
class DependencyService(txManager: org.springframework.transaction.PlatformTransactionManager, private val catalog: PackageCatalogService, private val jobs: BuildJobService, private val policy: BuildPolicyService,
                        private val code: CodeProjectService, private val changes: CodeChangeService, private val access: AccessService,
                        private val jdbc: JdbcTemplate, private val json: JsonMapper, private val audit: AuditService) {
    private val newTx = org.springframework.transaction.support.TransactionTemplate(txManager).apply { propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    @Transactional
    fun request(ctx: com.systemwebstudio.access.AccessContext, userId: UUID, name: String): DependencyRequestView {
        val project = ctx.project!!
        val spec = catalog.spec(name)
        if (spec == null) {
            // unknown/denied: record the wish for the admin queue (never auto-approved); own transaction, the request itself fails
            newTx.executeWithoutResult {
                jdbc.update("INSERT INTO approved_packages (name, status, requested_by) VALUES (?, 'PENDING', ?) ON CONFLICT (name) DO NOTHING", name, userId)
                audit.record("PACKAGE_REQUESTED", "PACKAGE", name, ctx.workspaceId, project.id)
            }
            throw ApiException(HttpStatus.FORBIDDEN, "PACKAGE_NOT_APPROVED", "“$name” is not in the approved package catalog; an administrator has been asked to review it")
        }
        policy.requireCapacity(project.id, ctx.workspaceId, userId)
        val repo = code.repo(project.id)
        val base = code.git { code.client.branchSha(repo.name, "main") } ?: throw ApiException.conflict("EMPTY_REPOSITORY", "Repository has no main branch")
        val id = UUID.randomUUID()
        val job = jobs.enqueueTool("LOCK", project.id, mapOf("name" to name, "spec" to spec, "exact" to (spec.firstOrNull()?.isDigit() == true), "requestId" to id.toString()), userId, base)
        jdbc.update("INSERT INTO dependency_requests (id, project_id, package, spec, build_job_id, requested_by) VALUES (?,?,?,?,?,?)", id, project.id, name, spec, job, userId)
        audit.record("DEPENDENCY_REQUESTED", "PROJECT", project.id, ctx.workspaceId, project.id, newValue = mapOf("package" to name, "spec" to spec))
        return list(project.id).first { it.id == id }
    }

    fun list(projectId: UUID): List<DependencyRequestView> = jdbc.query("""SELECT id, package, spec, status, code_change_id, error, created_at FROM dependency_requests
        WHERE project_id = ? ORDER BY created_at DESC LIMIT 50""", { rs, _ -> DependencyRequestView(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3),
        rs.getString(4), rs.getObject(5, UUID::class.java), rs.getString(6), rs.getTimestamp(7).toInstant()) }, projectId)

    // AFTER_COMMIT listeners still see the finished transaction bound: work in a NEW transaction so it really commits
    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    fun onJob(e: ToolJobFinished) { if (e.purpose == "LOCK") onLocked(e.jobId, e.result, e.error) }

    /**
     * LOCK job result: the new package.json / package-lock.json produced by npm in the sandbox (mirror only, scripts off). Accepted only if
     * package.json differs from main solely by the requested dependency and every lockfile package is on the allowlist.
     */
    fun onLocked(jobId: UUID, result: JsonNode?, error: String?) {
        val req = jdbc.queryForList("SELECT * FROM dependency_requests WHERE build_job_id = ?", jobId).firstOrNull() ?: return
        val id = req["id"] as UUID; val projectId = req["project_id"] as UUID; val name = req["package"] as String; val userId = req["requested_by"] as UUID
        // own transaction: a failure inside propose marks the listener transaction rollback-only
        fun fail(msg: String) = newTx.executeWithoutResult { jdbc.update("UPDATE dependency_requests SET status = 'FAILED', error = ?, updated_at = now() WHERE id = ?", msg.take(1000), id) }
        if (result == null) return fail(error ?: "Lockfile generation failed")
        try {
            val repo = code.repo(projectId)
            val oldPkg = json.readTree(code.client.raw(repo.name, "package.json", "main") ?: return fail("package.json missing")) as ObjectNode
            val newPkgText = result.get("packageJson").asString(); val lockText = result.get("packageLock").asString()
            val newPkg = json.readTree(newPkgText) as ObjectNode
            val section = if (newPkg.get("dependencies")?.has(name) == true) "dependencies" else return fail("npm did not add $name to dependencies")
            val expected = oldPkg.deepCopy().also { (it.get(section) as? ObjectNode ?: it.putObject(section)).set(name, newPkg.get(section).get(name)) }
            if (expected != newPkg) return fail("package.json changed beyond the requested dependency")
            val lock = json.readTree(lockText)
            val allow = catalog.allowlist()
            val outside = lock.get("packages")?.propertyNames()?.filter { it.isNotEmpty() }?.map { it.substringAfterLast("node_modules/") }?.filter { it !in allow }.orEmpty()
            if (outside.isNotEmpty()) return fail("Lockfile contains packages outside the approved catalog: ${outside.take(10).joinToString()}")
            val project = jdbc.queryForMap("SELECT workspace_id FROM projects WHERE id = ?", projectId)
            val ctx = access.forProject(userId, project["workspace_id"] as UUID, projectId)
            val change = changes.propose(ctx, userId, "Thêm thư viện $name (${newPkg.get(section).get(name).asString()})",
                listOf(GitFileChange("package.json", newPkgText.toByteArray()), GitFileChange("package-lock.json", lockText.toByteArray())), "DEPENDENCY", serverManaged = true)
            jdbc.update("UPDATE dependency_requests SET status = 'COMMITTED', code_change_id = ?, updated_at = now() WHERE id = ?", change.id, id)
        } catch (e: ApiException) { fail("${e.code}: ${e.message}") } catch (e: Exception) { org.slf4j.LoggerFactory.getLogger(javaClass).warn("Dependency request {} failed", id, e); fail("Could not apply the lockfile (${e.javaClass.simpleName})") }
    }
}

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/code/dependencies")
class DependencyController(private val access: AccessService, private val changes: CodeChangeService, private val deps: DependencyService, private val catalog: PackageCatalogService) {
    @GetMapping
    fun list(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any> {
        val c = access.forProject(me.userId, workspaceId, projectId); changes.requireCode(c)
        return mapOf("requests" to deps.list(projectId), "approved" to catalog.list().filter { it.status == "ALLOWED" }.map { mapOf("name" to it.name, "spec" to (it.pinnedVersion ?: it.versionRange)) })
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun request(@PathVariable workspaceId: UUID, @PathVariable projectId: UUID, @Valid @RequestBody body: DependencyRequestBody, @AuthenticationPrincipal me: StudioUserDetails): DependencyRequestView {
        val c = access.forProject(me.userId, workspaceId, projectId); changes.requireCode(c); c.require(Permission.PROJECT_EDIT)
        return deps.request(c, me.userId, body.name.trim())
    }
}
