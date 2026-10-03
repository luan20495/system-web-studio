package com.systemwebstudio.admin

import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.settings.SettingsService
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

data class DepartmentDto(val id: UUID, val name: String, val kind: String, val parentId: UUID?, val users: Int, val workspaces: Int, val createdAt: Instant)
data class DepartmentRequest(@field:NotBlank @field:Size(max = 120) val name: String, @field:Pattern(regexp = "DEPARTMENT|TEAM") val kind: String? = "DEPARTMENT", val parentId: UUID? = null)
data class AssignRequest(val departmentId: UUID? = null)

/**
 * Departments and teams (stage H): an organisational grouping for reporting (costs, usage). It grants NO permission; access stays
 * workspace and project membership. A team belongs to a department; users and workspaces belong to at most one unit.
 */
@RestController
@RequestMapping("/api/v1/admin/departments")
class AdminDepartmentController(private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService) {
    private fun list() = jdbc.query("""SELECT d.id, d.name, d.kind, d.parent_id, (SELECT count(*) FROM users u WHERE u.department_id = d.id),
        (SELECT count(*) FROM workspaces w WHERE w.department_id = d.id), d.created_at FROM departments d ORDER BY d.kind, lower(d.name)""") { rs, _ ->
        DepartmentDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getInt(5), rs.getInt(6), rs.getTimestamp(7).toInstant())
    }

    @GetMapping
    fun get(@AuthenticationPrincipal me: StudioUserDetails): List<DepartmentDto> { guard.require(me.userId); return list() }

    private fun checkParent(kind: String, parentId: UUID?, self: UUID? = null) {
        if (kind == "TEAM" && parentId == null) throw ApiException.badRequest("PARENT_REQUIRED", "A team belongs to a department")
        if (parentId != null) {
            val pk = jdbc.query("SELECT kind FROM departments WHERE id = ?", { rs, _ -> rs.getString(1) }, parentId).firstOrNull() ?: throw ApiException.notFound("DEPARTMENT_NOT_FOUND", "Parent not found")
            if (pk != "DEPARTMENT" || parentId == self) throw ApiException.badRequest("INVALID_PARENT", "Only a department can contain teams")
            if (kind == "DEPARTMENT") throw ApiException.badRequest("INVALID_PARENT", "Departments are top-level")
        }
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody r: DepartmentRequest, @AuthenticationPrincipal me: StudioUserDetails): List<DepartmentDto> {
        guard.require(me.userId)
        val kind = r.kind ?: "DEPARTMENT"; checkParent(kind, r.parentId)
        val id = UUID.randomUUID()
        try { jdbc.update("INSERT INTO departments (id, name, kind, parent_id) VALUES (?,?,?,?)", id, r.name.trim(), kind, r.parentId) }
        catch (e: org.springframework.dao.DuplicateKeyException) { throw ApiException.conflict("NAME_TAKEN", "A unit with this name already exists here") }
        audit.record("DEPARTMENT_CREATED", "DEPARTMENT", id, newValue = mapOf("name" to r.name.trim(), "kind" to kind, "parentId" to r.parentId))
        return list()
    }

    @PatchMapping("/{id}")
    @Transactional
    fun rename(@PathVariable id: UUID, @Valid @RequestBody r: DepartmentRequest, @AuthenticationPrincipal me: StudioUserDetails): List<DepartmentDto> {
        guard.require(me.userId)
        val old = jdbc.queryForList("SELECT name, kind, parent_id FROM departments WHERE id = ?", id).firstOrNull() ?: throw ApiException.notFound("DEPARTMENT_NOT_FOUND", "Not found")
        val kind = old["kind"] as String
        if (kind == "DEPARTMENT" && r.parentId != null) throw ApiException.badRequest("INVALID_PARENT", "Departments are top-level")
        if (kind == "TEAM") checkParent(kind, r.parentId ?: old["parent_id"] as UUID?, id)
        try { jdbc.update("UPDATE departments SET name = ?, parent_id = ? WHERE id = ?", r.name.trim(), if (kind == "TEAM") (r.parentId ?: old["parent_id"]) else null, id) }
        catch (e: org.springframework.dao.DuplicateKeyException) { throw ApiException.conflict("NAME_TAKEN", "A unit with this name already exists here") }
        audit.record("DEPARTMENT_UPDATED", "DEPARTMENT", id, oldValue = old, newValue = mapOf("name" to r.name.trim(), "parentId" to r.parentId))
        return list()
    }

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): List<DepartmentDto> {
        guard.require(me.userId)
        val used = jdbc.queryForObject("""SELECT (SELECT count(*) FROM users WHERE department_id = ?) + (SELECT count(*) FROM workspaces WHERE department_id = ?)
            + (SELECT count(*) FROM departments WHERE parent_id = ?)""", Long::class.java, id, id, id)!!
        if (used > 0) throw ApiException.conflict("DEPARTMENT_IN_USE", "Move its users, workspaces and teams first")
        if (jdbc.update("DELETE FROM departments WHERE id = ?", id) == 0) throw ApiException.notFound("DEPARTMENT_NOT_FOUND", "Not found")
        audit.record("DEPARTMENT_DELETED", "DEPARTMENT", id)
        return list()
    }

    private fun exists(id: UUID?) { if (id != null && jdbc.queryForObject("SELECT count(*) FROM departments WHERE id = ?", Long::class.java, id)!! == 0L) throw ApiException.notFound("DEPARTMENT_NOT_FOUND", "Department not found") }

    @PutMapping("/assign/users/{userId}")
    @Transactional
    fun assignUser(@PathVariable userId: UUID, @RequestBody r: AssignRequest, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any?> {
        guard.require(me.userId); exists(r.departmentId)
        if (jdbc.update("UPDATE users SET department_id = ? WHERE id = ?", r.departmentId, userId) == 0) throw ApiException.notFound("USER_NOT_FOUND", "User not found")
        audit.record("USER_DEPARTMENT_SET", "USER", userId, newValue = mapOf("departmentId" to r.departmentId))
        return mapOf("departmentId" to r.departmentId)
    }

    @PutMapping("/assign/workspaces/{workspaceId}")
    @Transactional
    fun assignWorkspace(@PathVariable workspaceId: UUID, @RequestBody r: AssignRequest, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any?> {
        guard.require(me.userId); exists(r.departmentId)
        if (jdbc.update("UPDATE workspaces SET department_id = ? WHERE id = ?", r.departmentId, workspaceId) == 0) throw ApiException.notFound("WORKSPACE_NOT_FOUND", "Workspace not found")
        audit.record("WORKSPACE_DEPARTMENT_SET", "WORKSPACE", workspaceId, workspaceId, newValue = mapOf("departmentId" to r.departmentId))
        return mapOf("departmentId" to r.departmentId)
    }
}

// ---------------------------------------------------------------- hosting cost

data class CostPriceDto(val id: UUID, val item: String, val unitPrice: BigDecimal, val currency: String, val usdPerUnit: BigDecimal, val note: String, val effectiveFrom: Instant, val createdBy: String?)
data class CostPriceRequest(@field:NotBlank @field:Pattern(regexp = "STORAGE_GIB_MONTH|BUILD_CPU_HOUR|BUILD_MINUTE|EGRESS_GIB") val item: String,
                            @field:NotNull @field:DecimalMin("0") @field:DecimalMax("1000000") val unitPrice: BigDecimal?,
                            @field:Pattern(regexp = "^[A-Z]{3}$") val currency: String? = "USD", @field:DecimalMin("0.0000000001") val usdPerUnit: BigDecimal? = null,
                            @field:Size(max = 200) val note: String? = null)
/** measured quantities and, per item, the cost in USD or null when no price exists (shown as "unknown", never estimated) */
data class CostLine(val key: String, val label: String?, val storageBytes: Long, val buildCpuMs: Long, val buildMs: Long, val aiUsd: BigDecimal, val aiUnknownCalls: Long,
                    val storageUsd: BigDecimal?, val cpuUsd: BigDecimal?, val buildUsd: BigDecimal?, val totalKnownUsd: BigDecimal, val complete: Boolean)
data class CostReport(val days: Int, val prices: List<CostPriceDto>, val missingPrices: List<String>, val egress: String,
                      val total: CostLine, val byDepartment: List<CostLine>, val byWorkspace: List<CostLine>, val byApplication: List<CostLine>)

/**
 * Hosting cost (stage H) = measured quantities × explicit admin prices. Measured: stored bytes now (artifacts, uploaded files, repositories),
 * build CPU (container cgroup) and build wall time over the period, AI cost already known per call (provider-reported or priced tokens).
 * Not measured: network egress (shown as not measured). Any item without a price stays null, and the line says it is incomplete.
 */
@RestController
@RequestMapping("/api/v1/admin/costs")
class AdminCostController(private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val audit: AuditService) {
    private val priceSelect = """SELECT c.id, c.item, c.unit_price, c.currency, c.usd_per_unit, c.note, c.effective_from, coalesce(u.display_name, u.username)
        FROM cost_prices c LEFT JOIN users u ON u.id = c.created_by"""
    private fun priceRow(rs: java.sql.ResultSet) = CostPriceDto(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6),
        rs.getTimestamp(7).toInstant(), rs.getString(8))
    private fun current(): Map<String, CostPriceDto> = jdbc.query("$priceSelect WHERE c.effective_from <= now() ORDER BY c.item, c.effective_from DESC, c.created_at DESC") { rs, _ -> priceRow(rs) }
        .groupBy { it.item }.mapValues { it.value.first() }

    private class Q(var storage: Long = 0, var cpu: Long = 0, var build: Long = 0, var ai: BigDecimal = BigDecimal.ZERO, var aiUnknown: Long = 0)

    @GetMapping
    @Transactional(readOnly = true)
    fun report(@RequestParam(defaultValue = "30") days: Int, @AuthenticationPrincipal me: StudioUserDetails): CostReport {
        guard.require(me.userId)
        val d = days.coerceIn(1, 366)
        val apps = HashMap<UUID, Q>()
        fun q(id: UUID) = apps.getOrPut(id) { Q() }
        jdbc.query("SELECT project_id, coalesce(sum(total_bytes), 0) FROM artifacts WHERE deleted_at IS NULL GROUP BY project_id") { rs -> q(rs.getObject(1, UUID::class.java)).storage += rs.getLong(2) }
        jdbc.query("SELECT project_id, coalesce(sum(size_bytes), 0) FROM assets WHERE status = 'READY' GROUP BY project_id") { rs -> q(rs.getObject(1, UUID::class.java)).storage += rs.getLong(2) }
        jdbc.query("SELECT project_id, coalesce(sum(size_bytes), 0) FROM repositories WHERE state <> 'DELETED' GROUP BY project_id") { rs -> q(rs.getObject(1, UUID::class.java)).storage += rs.getLong(2) }
        jdbc.query("""SELECT project_id, coalesce(sum(cpu_ms), 0), coalesce(sum(duration_ms), 0) FROM build_jobs WHERE project_id IS NOT NULL AND finished_at >= now() - make_interval(days => ?)
            GROUP BY project_id""", { rs -> q(rs.getObject(1, UUID::class.java)).apply { cpu += rs.getLong(2); build += rs.getLong(3) } }, d)
        jdbc.query("""SELECT project_id, coalesce(sum(cost_usd), 0), count(*) FILTER (WHERE cost_usd IS NULL AND total_tokens IS NOT NULL AND provider NOT IN ('openrouter', 'local'))
            FROM ai_calls WHERE created_at >= now() - make_interval(days => ?) GROUP BY project_id""", { rs -> q(rs.getObject(1, UUID::class.java)).apply { ai += rs.getBigDecimal(2); aiUnknown += rs.getLong(3) } }, d)
        val meta = jdbc.query("""SELECT p.id, p.name, w.id, w.name, dep.id, dep.name FROM projects p JOIN workspaces w ON w.id = p.workspace_id
            LEFT JOIN departments dep ON dep.id = w.department_id""") { rs, _ ->
            rs.getObject(1, UUID::class.java) to listOf(rs.getString(2), rs.getObject(3, UUID::class.java)?.toString(), rs.getString(4), rs.getObject(5, UUID::class.java)?.toString(), rs.getString(6))
        }.toMap()
        val prices = current()
        fun usd(item: String, qty: BigDecimal): BigDecimal? = prices[item]?.let { p -> qty.multiply(p.unitPrice).multiply(p.usdPerUnit).setScale(6, RoundingMode.HALF_UP) }
        fun line(key: String, label: String?, x: Q): CostLine {
            val gib = BigDecimal(x.storage).divide(BigDecimal(1L shl 30), 10, RoundingMode.HALF_UP)
            val storage = usd("STORAGE_GIB_MONTH", gib.multiply(BigDecimal(d)).divide(BigDecimal(30), 10, RoundingMode.HALF_UP))
            val cpu = usd("BUILD_CPU_HOUR", BigDecimal(x.cpu).divide(BigDecimal(3_600_000), 10, RoundingMode.HALF_UP))
            val build = usd("BUILD_MINUTE", BigDecimal(x.build).divide(BigDecimal(60_000), 10, RoundingMode.HALF_UP))
            val known = listOfNotNull(storage, cpu, build).fold(x.ai) { a, b -> a + b }.setScale(6, RoundingMode.HALF_UP)
            val complete = (x.storage == 0L || storage != null) && (x.cpu == 0L || cpu != null) && (x.build == 0L || build != null) && x.aiUnknown == 0L
            return CostLine(key, label, x.storage, x.cpu, x.build, x.ai.setScale(6, RoundingMode.HALF_UP), x.aiUnknown, storage, cpu, build, known, complete)
        }
        fun sum(list: Collection<Q>) = Q().also { t -> list.forEach { t.storage += it.storage; t.cpu += it.cpu; t.build += it.build; t.ai += it.ai; t.aiUnknown += it.aiUnknown } }
        val byApp = apps.map { (id, x) -> line(id.toString(), meta[id]?.get(0), x) }.sortedByDescending { it.totalKnownUsd }
        val byWs = apps.entries.groupBy { meta[it.key]?.get(1) ?: "?" }.map { (ws, e) -> line(ws, meta[e.first().key]?.get(2), sum(e.map { it.value })) }.sortedByDescending { it.totalKnownUsd }
        val byDep = apps.entries.groupBy { meta[it.key]?.get(3) ?: "none" }.map { (dep, e) -> line(dep, if (dep == "none") "Chưa gán phòng ban" else meta[e.first().key]?.get(4), sum(e.map { it.value })) }
        return CostReport(d, prices.values.sortedBy { it.item }, listOf("STORAGE_GIB_MONTH", "BUILD_CPU_HOUR", "BUILD_MINUTE").filter { it !in prices },
            "Lưu lượng mạng (egress) chưa được đo: không tính.", line("total", "Toàn tổ chức", sum(apps.values)), byDep, byWs, byApp.take(100))
    }

    @GetMapping("/prices")
    fun prices(@AuthenticationPrincipal me: StudioUserDetails): List<CostPriceDto> {
        guard.require(me.userId); return jdbc.query("$priceSelect ORDER BY c.item, c.effective_from DESC LIMIT 200") { rs, _ -> priceRow(rs) }
    }

    @PostMapping("/prices")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    fun addPrice(@Valid @RequestBody r: CostPriceRequest, @AuthenticationPrincipal me: StudioUserDetails): List<CostPriceDto> {
        guard.require(me.userId)
        val currency = r.currency ?: "USD"
        val rate = if (currency == "USD") BigDecimal.ONE else r.usdPerUnit ?: throw ApiException.badRequest("EXCHANGE_RATE_REQUIRED", "A non-USD price needs usdPerUnit, entered by you")
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO cost_prices (id, item, unit_price, currency, usd_per_unit, note, created_by) VALUES (?,?,?,?,?,?,?)", id, r.item, r.unitPrice, currency, rate, r.note?.trim().orEmpty(), me.userId)
        audit.record("COST_PRICE_ADDED", "COST_PRICE", id, newValue = mapOf("item" to r.item, "unitPrice" to r.unitPrice, "currency" to currency, "usdPerUnit" to rate))
        return prices(me)
    }
}

// ---------------------------------------------------------------- security findings

data class SecurityFinding(val severity: String, val source: String, val title: String, val detail: String, val resourceType: String?, val resourceId: String?,
                           val resourceName: String?, val detectedAt: Instant?)
data class SecurityReport(val counts: Map<String, Int>, val findings: List<SecurityFinding>, val note: String)

/**
 * Security findings by severity (stage H), all from real data: dependency advisories and secret-scan hits of each code app's latest build,
 * approved packages with an accepted HIGH/CRITICAL risk, and risky platform settings. There is deliberately no aggregate "security score".
 */
@RestController
@RequestMapping("/api/v1/admin/security")
class AdminSecurityController(private val guard: AdminGuard, private val jdbc: JdbcTemplate, private val json: JsonMapper, private val settings: SettingsService,
                              private val registry: com.systemwebstudio.integration.llm.AiProviderRegistry) {
    private val rank = mapOf("CRITICAL" to 0, "HIGH" to 1, "MEDIUM" to 2, "LOW" to 3, "INFO" to 4)

    @GetMapping("/findings")
    @Transactional(readOnly = true)
    fun findings(@AuthenticationPrincipal me: StudioUserDetails): SecurityReport {
        guard.require(me.userId)
        val out = ArrayList<SecurityFinding>()
        // latest finished build with scans per code app
        jdbc.query("""SELECT DISTINCT ON (b.project_id) b.project_id, p.name, b.scans::text, b.finished_at FROM build_jobs b JOIN projects p ON p.id = b.project_id AND p.active
            WHERE b.scans IS NOT NULL AND b.finished_at IS NOT NULL ORDER BY b.project_id, b.finished_at DESC""") { rs ->
            val pid = rs.getObject(1, UUID::class.java).toString(); val name = rs.getString(2); val at = rs.getTimestamp(4).toInstant()
            val scans = json.readTree(rs.getString(3))
            scans.get("dependencies")?.get("findings")?.forEach { f ->
                val sev = f.get("severity")?.asString()?.uppercase()?.takeIf { it in rank } ?: "MEDIUM"
                out += SecurityFinding(sev, "DEPENDENCY", "${f.get("id")?.asString()} trong ${f.get("package")?.asString()}", "Lỗ hổng đã công bố (OSV) trong phụ thuộc của bản build gần nhất",
                    "PROJECT", pid, name, at)
            }
            listOf("sourceSecrets" to "mã nguồn", "outputSecrets" to "kết quả build").forEach { (k, where) ->
                scans.get(k)?.get("findings")?.forEach { f ->
                    out += SecurityFinding("CRITICAL", "SECRET", "Phát hiện bí mật trong $where (${f.get("rule")?.asString()})", "Tệp ${f.get("file")?.asString()} dòng ${f.get("line")?.asString()}; giá trị không được lưu",
                        "PROJECT", pid, name, at)
                }
            }
        }
        jdbc.query("SELECT name, findings::text, decided_at FROM approved_packages WHERE status = 'ALLOWED' AND risk_accepted AND findings IS NOT NULL") { rs ->
            json.readTree(rs.getString(2)).filter { it.get("severity")?.asString() in setOf("HIGH", "CRITICAL") }.forEach { f ->
                out += SecurityFinding(f.get("severity").asString(), "PACKAGE", "Rủi ro đã chấp nhận: ${f.get("id")?.asString()} (${rs.getString(1)})", "Package được cho phép dù có lỗ hổng; xem lại định kỳ",
                    "PACKAGE", rs.getString(1), rs.getString(1), rs.getTimestamp(3)?.toInstant())
            }
        }
        // platform configuration
        if (settings.bool("signup.enabled")) out += SecurityFinding("MEDIUM", "CONFIG", "Tự đăng ký tài khoản đang bật", "Bất kỳ ai trên Internet có thể tạo tài khoản (Cài đặt → signup.enabled)", "SETTING", "signup.enabled", null, null)
        val paidEnabled = jdbc.queryForList("SELECT model_id FROM ai_model_policies WHERE enabled", String::class.java).filter { registry.split(it)?.first?.paid == true }
        if (paidEnabled.isNotEmpty()) {
            val hard = jdbc.queryForObject("SELECT count(*) FROM ai_budgets WHERE hard", Long::class.java)!!
            if (hard == 0L) out += SecurityFinding("MEDIUM", "CONFIG", "Model AI trả phí đang bật nhưng chưa có ngân sách cứng", "Model: ${paidEnabled.take(5).joinToString()}", "SETTING", "ai.budgets", null, null)
            val unpriced = paidEnabled.filter { jdbc.queryForObject("SELECT count(*) FROM ai_model_pricing WHERE model_id = ?", Long::class.java, it)!! == 0L }
            if (unpriced.isNotEmpty()) out += SecurityFinding("LOW", "CONFIG", "Model AI trả phí chưa có giá", "Chi phí không tính được cho: ${unpriced.take(5).joinToString()}", "SETTING", "ai.pricing", null, null)
        }
        val admins = jdbc.queryForObject("SELECT count(*) FROM users WHERE system_admin AND enabled", Long::class.java)!!
        if (admins > 5) out += SecurityFinding("LOW", "CONFIG", "Nhiều quản trị hệ thống ($admins)", "Giữ số quản trị viên hệ thống ở mức tối thiểu", "USER", null, null, null)
        val sorted = out.sortedWith(compareBy({ rank[it.severity] ?: 9 }, { it.source }))
        return SecurityReport(listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO").associateWith { s -> out.count { it.severity == s } }, sorted.take(500),
            "Chỉ liệt kê phát hiện từ dữ liệu thật (quét build, danh mục package, cấu hình). Không có điểm bảo mật tổng hợp.")
    }
}
