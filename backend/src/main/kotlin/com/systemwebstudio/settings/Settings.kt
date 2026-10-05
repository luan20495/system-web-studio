package com.systemwebstudio.settings

import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.audit.AuditService
import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** DOMAINS = comma-separated host names (lowercase letters, digits, dots, dashes) */
enum class SettingType { BOOL, INT, DECIMAL, DOMAINS, TEXT }
enum class Risk { LOW, HIGH }

/** One editable policy value. The default comes from configuration (environment), never from a constant buried in code. */
data class SettingDef(
    val key: String, val group: String, val type: SettingType, val property: String, val fallback: String, val label: String,
    val risk: Risk = Risk.LOW, val min: Long = 0, val max: Long = Long.MAX_VALUE, val unit: String = ""
)

object SettingCatalog {
    val all = listOf(
        // identity
        SettingDef("signup.enabled", "Đăng nhập", SettingType.BOOL, "app.signup.enabled", "false", "Cho phép tự đăng ký tài khoản (Internet)", Risk.HIGH),
        SettingDef("session.timeout-minutes", "Đăng nhập", SettingType.INT, "app.session.timeout-minutes", "480", "Phiên hết hạn sau khi không hoạt động", min = 5, max = 1440, unit = "phút"),
        // AI
        SettingDef("ai.daily-requests-per-user", "AI", SettingType.INT, "app.openrouter.daily-limit-per-user", "50", "Lượt AI / người / ngày (0 = không giới hạn)", min = 0, max = 100000),
        SettingDef("ai.daily-tokens-per-user", "AI", SettingType.INT, "app.ai.daily-token-limit-per-user", "0", "Token / người / 24 giờ (0 = tắt)", min = 0, max = 1_000_000_000),
        SettingDef("ai.monthly-tokens-per-workspace", "AI", SettingType.INT, "app.ai.monthly-token-limit-per-workspace", "0", "Token / workspace / tháng (0 = tắt)", min = 0, max = 10_000_000_000),
        SettingDef("ai.default-model", "AI", SettingType.TEXT, "app.ai.default-model", "auto", "Mô hình AI mặc định (auto = Tự động)"),
        SettingDef("ai.paid-budget-per-user-month", "AI", SettingType.DECIMAL, "app.ai.paid-budget-per-user-month", "0", "Ngân sách AI trả phí / người / tháng (USD; 0 = chưa cấp)", min = 0, max = 1_000_000),
        SettingDef("ai.paid-budget-per-workspace-month", "AI", SettingType.DECIMAL, "app.ai.paid-budget-per-workspace-month", "0", "Ngân sách AI trả phí / không gian làm việc / tháng (USD; 0 = chưa cấp)", min = 0, max = 10_000_000),
        SettingDef("ai.stream-timeout-seconds", "AI", SettingType.INT, "app.ai.stream-timeout-seconds", "120", "Thời gian tối đa một yêu cầu AI dạng streaming (giây)", min = 10, max = 900),
        // server apps (stage J): generated server code running in the isolated runtime
        SettingDef("server-apps.enabled", "Ứng dụng có máy chủ", SettingType.BOOL, "app.runtime.enabled", "false", "Cho phép tạo và chạy ứng dụng có máy chủ (runtime cô lập)", Risk.HIGH),
        // websites
        SettingDef("site.external-link-domains", "Website", SettingType.DOMAINS, "app.sites.external-link-domains", "", "Tên miền ngoài được phép làm liên kết điều hướng (phân tách bằng dấu phẩy)"),
        // source-code apps
        SettingDef("source-apps.enabled", "Ứng dụng mã nguồn", SettingType.BOOL, "app.source-apps.enabled", "true", "Cho phép tạo ứng dụng mã nguồn"),
        SettingDef("source-apps.build-enabled", "Ứng dụng mã nguồn", SettingType.BOOL, "app.source-apps.build-enabled", "true", "Cho phép build trong sandbox", Risk.HIGH),
        SettingDef("source-apps.public-publish-enabled", "Ứng dụng mã nguồn", SettingType.BOOL, "app.source-apps.public-publish-enabled", "true", "Cho phép xuất bản ứng dụng mã nguồn CÔNG KHAI", Risk.HIGH),
        // build policy
        SettingDef("build.max-per-org-per-day", "Build", SettingType.INT, "app.build.max-per-org-per-day", "1000", "Build / toàn hệ thống / ngày", min = 0, max = 1_000_000),
        SettingDef("build.max-per-workspace-per-day", "Build", SettingType.INT, "app.build.max-per-workspace-per-day", "300", "Build / workspace / ngày", min = 0, max = 100_000),
        SettingDef("build.max-per-user-per-day", "Build", SettingType.INT, "app.build.max-per-user-per-day", "60", "Build / người / ngày", min = 0, max = 100_000),
        SettingDef("build.max-per-project-per-day", "Build", SettingType.INT, "app.build.max-per-project-per-day", "100", "Build / ứng dụng / ngày", min = 0, max = 100_000),
        SettingDef("build.max-concurrent-per-user", "Build", SettingType.INT, "app.build.max-concurrent-per-user", "2", "Build chạy đồng thời / người", min = 1, max = 100),
        SettingDef("build.max-concurrent-per-workspace", "Build", SettingType.INT, "app.build.max-concurrent-per-workspace", "4", "Build chạy đồng thời / workspace", min = 1, max = 100),
        SettingDef("build.max-duration-seconds", "Build", SettingType.INT, "app.build.max-duration-seconds", "600", "Thời gian build tối đa", min = 60, max = 3600, unit = "giây"),
        SettingDef("build.max-artifact-mib", "Build", SettingType.INT, "app.build.max-artifact-mib", "100", "Kích thước kết quả build tối đa", min = 1, max = 1024, unit = "MiB"),
        SettingDef("build.max-repo-mib", "Build", SettingType.INT, "app.build.max-repo-mib", "50", "Kích thước mã nguồn tối đa / ứng dụng", min = 1, max = 2048, unit = "MiB"),
        // storage quotas
        SettingDef("storage.max-assets-mib-per-project", "Lưu trữ", SettingType.INT, "app.storage.max-assets-mib-per-project", "200", "Tệp tải lên / ứng dụng", min = 1, max = 100_000, unit = "MiB"),
        SettingDef("storage.max-artifacts-mib-per-project", "Lưu trữ", SettingType.INT, "app.storage.max-artifacts-mib-per-project", "1000", "Artifact build / ứng dụng (đang giữ)", min = 1, max = 100_000, unit = "MiB"),
        // publishing
        SettingDef("publish.public-enabled", "Xuất bản", SettingType.BOOL, "app.publish.public-enabled", "true", "Cho phép xuất bản website CÔNG KHAI", Risk.HIGH),
        // retention
        SettingDef("retention.rollback-deployments", "Lưu giữ", SettingType.INT, "app.retention.rollback-deployments", "5", "Số bản xuất bản cũ giữ để quay lại / ứng dụng", min = 1, max = 100),
        SettingDef("retention.preview-days", "Lưu giữ", SettingType.INT, "app.retention.preview-days", "7", "Giữ bản xem trước", min = 1, max = 365, unit = "ngày"),
        SettingDef("retention.failed-build-days", "Lưu giữ", SettingType.INT, "app.retention.failed-build-days", "14", "Giữ build lỗi", min = 1, max = 365, unit = "ngày"),
        SettingDef("retention.deleted-project-days", "Lưu giữ", SettingType.INT, "app.retention.deleted-project-days", "30", "Giữ dữ liệu ứng dụng đã xoá (artifact, repo lưu trữ)", min = 1, max = 3650, unit = "ngày"),
        SettingDef("retention.form-submission-days", "Lưu giữ", SettingType.INT, "app.retention.form-submission-days", "180", "Giữ dữ liệu form gửi về", min = 1, max = 3650, unit = "ngày")
    ).associateBy { it.key }
}

data class SettingView(val key: String, val group: String, val type: SettingType, val label: String, val risk: Risk, val min: Long, val max: Long, val unit: String,
                       val value: String, val defaultValue: String, val overridden: Boolean, val updatedBy: String?, val updatedAt: Instant?)
data class SettingUpdate(@field:NotNull val value: String?, val confirm: Boolean? = null)

/**
 * Effective policy values: database override (set by a system admin, audited) or the configured default. Cached for a few seconds so
 * request paths can read it freely; a write invalidates the cache on this instance (others pick it up within the TTL).
 */
@Service
class SettingsService(private val jdbc: JdbcTemplate, private val env: Environment) {
    private val cache = AtomicReference<Pair<Long, Map<String, String>>>(0L to emptyMap())

    fun defaultOf(d: SettingDef): String = env.getProperty(d.property)?.trim()?.takeIf { it.isNotEmpty() } ?: d.fallback
    private fun overrides(): Map<String, String> {
        val (at, map) = cache.get()
        if (System.currentTimeMillis() - at < 5000) return map
        val fresh = runCatching { jdbc.query("SELECT key, value FROM system_settings") { rs, _ -> rs.getString(1) to rs.getString(2) }.toMap() }.getOrDefault(map)
        cache.set(System.currentTimeMillis() to fresh); return fresh
    }
    fun invalidate() = cache.set(0L to emptyMap())

    fun raw(key: String): String { val d = SettingCatalog.all[key] ?: error("unknown setting $key"); return overrides()[key] ?: defaultOf(d) }
    fun bool(key: String) = raw(key).equals("true", ignoreCase = true)
    fun decimal(key: String): BigDecimal = raw(key).toBigDecimalOrNull() ?: BigDecimal(defaultOf(SettingCatalog.all[key]!!))
    fun long(key: String) = raw(key).toLongOrNull() ?: defaultOf(SettingCatalog.all[key]!!).toLong()
    fun int(key: String) = long(key).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    fun views(): List<SettingView> {
        val rows = jdbc.queryForList("""SELECT s.key, s.value, s.updated_at, coalesce(u.display_name, u.username) AS who FROM system_settings s LEFT JOIN users u ON u.id = s.updated_by""")
            .associateBy { it["key"] as String }
        return SettingCatalog.all.values.map { d ->
            val r = rows[d.key]
            SettingView(d.key, d.group, d.type, d.label, d.risk, d.min, d.max, d.unit, (r?.get("value") as String?) ?: defaultOf(d), defaultOf(d), r != null,
                r?.get("who") as String?, (r?.get("updated_at") as java.sql.Timestamp?)?.toInstant())
        }
    }

    fun normalise(d: SettingDef, value: String): String {
        val v = value.trim()
        return when (d.type) {
            SettingType.BOOL -> when (v.lowercase()) { "true" -> "true"; "false" -> "false"; else -> throw ApiException.badRequest("INVALID_VALUE", "Expected true or false") }
            SettingType.INT -> (v.toLongOrNull() ?: throw ApiException.badRequest("INVALID_VALUE", "Expected a whole number")).also {
                if (it < d.min || it > d.max) throw ApiException.badRequest("OUT_OF_RANGE", "Allowed range ${d.min}–${d.max}")
            }.toString()
            SettingType.DOMAINS -> v.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().also { list ->
                if (list.size > 50) throw ApiException.badRequest("OUT_OF_RANGE", "At most 50 domains")
                list.firstOrNull { !Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$").matches(it) }?.let { throw ApiException.badRequest("INVALID_VALUE", "Not a host name: $it") }
            }.joinToString(",")
            SettingType.TEXT -> v.also { if (!Regex("^(auto|[A-Za-z0-9._/:@-]{1,100})$").matches(it)) throw ApiException.badRequest("INVALID_VALUE", "Mã mô hình không hợp lệ") }
            SettingType.DECIMAL -> (v.toBigDecimalOrNull() ?: throw ApiException.badRequest("INVALID_VALUE", "Expected a number")).also {
                if (it < BigDecimal(d.min) || it > BigDecimal(d.max)) throw ApiException.badRequest("OUT_OF_RANGE", "Allowed range ${d.min}–${d.max}")
            }.toPlainString()
        }
    }
}

@RestController
@RequestMapping("/api/v1/admin/settings/policies")
class SettingsController(private val guard: AdminGuard, private val settings: SettingsService, private val jdbc: JdbcTemplate, private val audit: AuditService) {
    @GetMapping
    fun list(@AuthenticationPrincipal me: StudioUserDetails): List<SettingView> { guard.require(me.userId); return settings.views() }

    /** High-risk settings need `confirm: true` (the UI asks first). Every change is audited with old and new value. */
    @PutMapping("/{key}")
    @Transactional
    fun update(@PathVariable key: String, @Valid @RequestBody body: SettingUpdate, @AuthenticationPrincipal me: StudioUserDetails): SettingView {
        guard.require(me.userId)
        val d = SettingCatalog.all[key] ?: throw ApiException.notFound("SETTING_NOT_FOUND", "Unknown setting")
        val value = settings.normalise(d, body.value!!)
        if (d.risk == Risk.HIGH && body.confirm != true) throw ApiException(HttpStatus.PRECONDITION_REQUIRED, "CONFIRMATION_REQUIRED", "This is a high-risk setting; confirm the change")
        val old = settings.raw(key)
        jdbc.update("""INSERT INTO system_settings (key, value, updated_by, updated_at) VALUES (?,?,?, now())
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()""", key, value, me.userId)
        settings.invalidate()
        audit.record("SETTING_CHANGED", "SETTING", key, oldValue = mapOf("value" to old), newValue = mapOf("value" to value, "risk" to d.risk.name))
        return settings.views().first { it.key == key }
    }

    /** Back to the configured default. */
    @DeleteMapping("/{key}")
    @Transactional
    fun reset(@PathVariable key: String, @AuthenticationPrincipal me: StudioUserDetails): SettingView {
        guard.require(me.userId)
        if (key !in SettingCatalog.all) throw ApiException.notFound("SETTING_NOT_FOUND", "Unknown setting")
        val old = settings.raw(key)
        jdbc.update("DELETE FROM system_settings WHERE key = ?", key); settings.invalidate()
        audit.record("SETTING_RESET", "SETTING", key, oldValue = mapOf("value" to old))
        return settings.views().first { it.key == key }
    }
}
