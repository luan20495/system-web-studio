package com.systemwebstudio.maintenance

import com.systemwebstudio.admin.AdminGuard
import com.systemwebstudio.ai.AlertService
import com.systemwebstudio.identity.StudioUserDetails
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class BackupComponent(val name: String, val state: String, val lastSuccess: Instant?, val lastRun: Instant?, val ageHours: Long?, val sizeBytes: Long?,
                           val error: String?, val stale: Boolean)
data class DrillCheck(val component: String, val result: String, val detail: String)
data class BackupEnvironment(val environment: String, val components: List<BackupComponent>, val drillAt: Instant?, val drillPassed: Boolean?, val drill: List<DrillCheck>,
                             val healthy: Boolean, val problems: List<String>)

/**
 * Backup monitoring (stage L): reads the status files written by scripts/backup-all.sh and scripts/restore-drill-all.sh (the API never runs
 * backups itself and never touches Docker). A component is stale when its last success is older than BACKUP_MAX_AGE_HOURS (26); the drill
 * is stale after 8 days. Problems raise admin alerts (one per condition and day).
 */
@Service
class BackupMonitor(private val json: JsonMapper, private val alerts: AlertService,
                    @Value("\${app.backup.status-dirs:}") private val dirs: String, @Value("\${app.backup.max-age-hours:26}") private val maxAge: Long) {
    private val components = listOf("postgres", "appdb", "minio", "forgejo", "offsite")

    fun report(): List<BackupEnvironment> = dirs.split(',').map { it.trim() }.filter { it.contains(':') }.map { entry ->
        val env = entry.substringBefore(':'); val dir = File(entry.substringAfter(':'))
        val status = File(dir, "status.json").takeIf { it.isFile }?.let { runCatching { json.readTree(it) }.getOrNull() }
        val now = Instant.now()
        val comps = components.map { c ->
            val n = status?.get(c)
            val ok = n?.get("lastSuccess")?.asString()?.let { Instant.parse(it) }
            val age = ok?.let { Duration.between(it, now).toHours() }
            val state = n?.get("lastState")?.asString() ?: "NEVER"
            BackupComponent(c, state, ok, n?.get("lastRun")?.asString()?.let { Instant.parse(it) }, age, n?.get("sizeBytes")?.takeIf { it.isNumber }?.asLong(),
                n?.get("lastError")?.takeIf { it.isString }?.asString(),
                // the off-host copy is optional until production: only a failing configured target is a problem here (its absence is shown, not alerted)
                if (c == "offsite") state == "FAILED" || (state == "OK" && (age ?: 0) >= maxAge) else state != "SKIPPED" && (ok == null || (age ?: 0) >= maxAge || state == "FAILED"))
        }
        val drill = File(dir, "drill.json").takeIf { it.isFile }?.let { runCatching { json.readTree(it) }.getOrNull() }
        val drillAt = drill?.get("at")?.asString()?.let { Instant.parse(it) }
        val checks = drill?.get("checks")?.toList().orEmpty().map { DrillCheck(it.get("component").asString(), it.get("result").asString(), it.get("detail").asString()) }
        val problems = comps.filter { it.stale }.map { "${it.name}: ${if (it.state == "FAILED") "lần sao lưu gần nhất lỗi" else if (it.lastSuccess == null) "chưa có bản sao lưu" else "bản sao lưu đã ${it.ageHours} giờ"}" } +
            listOfNotNull(when { drillAt == null -> "chưa diễn tập khôi phục"; drill.get("passed")?.asBoolean() != true -> "diễn tập khôi phục không đạt"
                Duration.between(drillAt, now).toDays() > 8 -> "diễn tập khôi phục đã quá 8 ngày"; else -> null })
        BackupEnvironment(env, comps, drillAt, drill?.get("passed")?.asBoolean(), checks, problems.isEmpty(), problems)
    }

    @Scheduled(fixedDelayString = "\${app.backup.check-interval-ms:3600000}", initialDelayString = "\${app.backup.check-initial-delay-ms:120000}")
    fun check() {
        runCatching { report() }.getOrNull()?.forEach { e ->
            e.problems.forEach { p -> alerts.raise("BACKUP_PROBLEM", "CRITICAL", "Sao lưu (${e.environment}): $p", "backup:${e.environment}:${p.substringBefore(':')}:${LocalDate.now()}", "BACKUP", e.environment) }
        }
    }
}

@RestController
class AdminBackupController(private val guard: AdminGuard, private val monitor: BackupMonitor) {
    @GetMapping("/api/v1/admin/backups")
    fun backups(@AuthenticationPrincipal me: StudioUserDetails): List<BackupEnvironment> { guard.require(me.userId); return monitor.report() }
}
