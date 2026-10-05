package com.systemwebstudio.admin

import com.systemwebstudio.integration.git.ForgejoClient
import com.systemwebstudio.maintenance.BackupMonitor
import com.systemwebstudio.runtime.AppDbProvisioner
import com.systemwebstudio.runtime.ServerRuntimeService
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Last time the build runner polled for work (the runner polls every few seconds even when idle). In memory: UNKNOWN right after an API restart. */
@Component
class RunnerHeartbeat {
    private val last = AtomicReference<Instant?>(null)
    val startedAt: Instant = Instant.now()
    fun seen() { last.set(Instant.now()) }
    fun lastSeen(): Instant? = last.get()
}

/**
 * Health of the components added after the first platform (Git server, render worker, build runner, server runtime, backups). Every item is a
 * real probe or a fact from the database; when there is nothing to base a statement on the status is UNKNOWN (never a guessed "healthy").
 * Statuses: HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN, NOT_CONFIGURED.
 */
@Component
class HealthProbes(
    private val jdbc: JdbcTemplate, private val heartbeat: RunnerHeartbeat, private val git: ForgejoClient, private val runtime: ServerRuntimeService,
    private val appDb: AppDbProvisioner, private val backups: BackupMonitor,
    @Value("\${app.render.url:}") private val renderUrl: String, @Value("\${app.git.url:}") private val gitUrl: String,
    @Value("\${app.build.runner-token:}") private val runnerToken: String
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    private fun get(url: String): Int = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    private fun probe(name: String, block: () -> Pair<String, String?>): HealthItem {
        val t0 = System.nanoTime()
        return try { val (status, detail) = block(); HealthItem(name, status, ms(t0), detail) } catch (e: Exception) { HealthItem(name, "UNAVAILABLE", ms(t0), e.javaClass.simpleName) }
    }

    fun items(): List<HealthItem> = buildList {
        add(if (renderUrl.isBlank()) HealthItem("Render worker", "NOT_CONFIGURED", null, "RENDER_URL not set") else probe("Render worker") {
            if (get("${renderUrl.trimEnd('/')}/health") == 200) "HEALTHY" to "static renderer, previews, AST" else "UNAVAILABLE" to "unexpected answer" })
        add(if (!git.configured) HealthItem("Git server (Forgejo)", "NOT_CONFIGURED", null, "code projects off") else probe("Git server (Forgejo)") {
            if (get("${gitUrl.trimEnd('/')}/api/healthz") in 200..299) "HEALTHY" to null else "UNAVAILABLE" to "health endpoint not OK" })
        add(runner())
        add(runtimeItem())
        add(backupItem())
    }

    private fun runner(): HealthItem {
        if (runnerToken.isBlank()) return HealthItem("Build runner", "NOT_CONFIGURED", null, "BUILD_RUNNER_TOKEN not set")
        val failed = jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE status = 'FAILED' AND finished_at > now() - interval '24 hours'", Long::class.java) ?: 0
        val total = jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE finished_at > now() - interval '24 hours'", Long::class.java) ?: 0
        val stuck = jdbc.queryForObject("SELECT count(*) FROM build_jobs WHERE status = 'QUEUED' AND queued_at < now() - interval '5 minutes'", Long::class.java) ?: 0
        val facts = "builds 24 h: $total, failed $failed; waiting > 5 min: $stuck"
        val seen = heartbeat.lastSeen()
        return when {
            seen == null && Duration.between(heartbeat.startedAt, Instant.now()).seconds < 90 -> HealthItem("Build runner", "UNKNOWN", null, "no poll seen yet since the API started · $facts")
            seen == null || Duration.between(seen, Instant.now()).seconds > 60 -> HealthItem("Build runner", "UNAVAILABLE", null, "no poll for ${seen?.let { Duration.between(it, Instant.now()).seconds } ?: "—"} s · $facts")
            stuck > 0 -> HealthItem("Build runner", "DEGRADED", null, "polling, but $stuck job(s) waiting > 5 min · $facts")
            else -> HealthItem("Build runner", "HEALTHY", Duration.between(seen, Instant.now()).toMillis(), "last poll ${Duration.between(seen, Instant.now()).seconds} s ago · $facts")
        }
    }

    private fun runtimeItem(): HealthItem {
        if (!runtime.configured) return HealthItem("Server runtime", "NOT_CONFIGURED", null, "apps DB / gateway / secrets key not configured")
        if (!runtime.available) return HealthItem("Server runtime", "NOT_CONFIGURED", null, "configured, disabled by policy (server-apps.enabled)")
        val t0 = System.nanoTime()
        return try {
            appDb.ping()
            val gw = get("${runtime.gatewayUrl.trimEnd('/')}/healthz")
            val failed = jdbc.queryForObject("SELECT count(*) FROM server_deployments WHERE status = 'FAILED' AND created_at > now() - interval '24 hours'", Long::class.java) ?: 0
            val running = jdbc.queryForObject("SELECT count(*) FROM app_runtimes WHERE current_deployment_id IS NOT NULL", Long::class.java) ?: 0
            val facts = "apps running: $running, failed deployments 24 h: $failed"
            if (gw != 200) HealthItem("Server runtime", "DEGRADED", ms(t0), "apps DB reachable, gateway answered HTTP $gw · $facts") else HealthItem("Server runtime", "HEALTHY", ms(t0), "apps DB + gateway reachable · $facts")
        } catch (e: Exception) { HealthItem("Server runtime", "UNAVAILABLE", ms(t0), e.javaClass.simpleName) }
    }

    private fun backupItem(): HealthItem {
        val envs = runCatching { backups.report() }.getOrDefault(emptyList())
        if (envs.isEmpty()) return HealthItem("Backups", "UNKNOWN", null, "no backup status directory configured (BACKUP_STATUS_DIRS)")
        val bad = envs.filter { !it.healthy }
        return if (bad.isEmpty()) HealthItem("Backups", "HEALTHY", null, envs.joinToString { it.environment } + ": recent backups and restore drill passed")
        else HealthItem("Backups", "DEGRADED", null, bad.joinToString("; ") { "${it.environment}: ${it.problems.take(2).joinToString(", ")}" })
    }
}
