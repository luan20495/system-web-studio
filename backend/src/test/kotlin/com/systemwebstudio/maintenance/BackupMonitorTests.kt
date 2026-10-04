package com.systemwebstudio.maintenance

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Stage L: backup monitoring reads the scripts' status files; stale/failed backups and failed drills become problems and alerts. */
class BackupMonitorTests : IntegrationTestBase() {
    companion object {
        val dir: File = Files.createTempDirectory("bk").toFile()
        @JvmStatic @DynamicPropertySource
        fun props(r: DynamicPropertyRegistry) { r.add("app.backup.status-dirs") { "testenv:${dir.absolutePath}" } }
    }
    @Autowired lateinit var monitor: BackupMonitor

    @Test
    fun `fresh, stale, failed and skipped components and the drill are reported honestly and stale ones raise alerts`() {
        val now = Instant.now(); val old = now.minus(30, ChronoUnit.HOURS)
        File(dir, "status.json").writeText("""{
          "postgres": {"lastRun":"$now","lastState":"OK","lastSuccess":"$now","sizeBytes":1000},
          "minio": {"lastRun":"$old","lastState":"OK","lastSuccess":"$old","sizeBytes":5},
          "forgejo": {"lastRun":"$now","lastState":"FAILED","lastError":"forgejo dump failed","lastSuccess":"$old"},
          "appdb": {"lastRun":"$now","lastState":"SKIPPED","lastError":"no apps DB server"}}""")
        File(dir, "drill.json").writeText("""{"at":"$now","passed":false,"checks":[{"component":"postgres","result":"FAIL","detail":"restore incomplete"}]}""")
        val e = monitor.report().single()
        val c = e.components.associateBy { it.name }
        assertThat(c["postgres"]!!.stale).isFalse(); assertThat(c["minio"]!!.stale).isTrue(); assertThat(c["forgejo"]!!.stale).isTrue(); assertThat(c["appdb"]!!.stale).isFalse()
        assertThat(e.healthy).isFalse(); assertThat(e.problems).anyMatch { it.startsWith("minio") }.anyMatch { it.contains("diễn tập khôi phục không đạt") }
        jdbc.update("DELETE FROM admin_alerts WHERE kind = 'BACKUP_PROBLEM'")
        monitor.check()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_alerts WHERE kind = 'BACKUP_PROBLEM'", Long::class.java)).isEqualTo(3)
        monitor.check()                                                                                                     // deduplicated per day
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_alerts WHERE kind = 'BACKUP_PROBLEM'", Long::class.java)).isEqualTo(3)
        val admin = sessionFor(fx.user("bkadm", systemAdmin = true).username)
        assertThat(admin.body(admin.get("/api/v1/admin/backups"))[0].get("environment").asString()).isEqualTo("testenv")
        assertThat(sessionFor(fx.user("bkuser").username).get("/api/v1/admin/backups").response.status).isEqualTo(403)
    }
}
