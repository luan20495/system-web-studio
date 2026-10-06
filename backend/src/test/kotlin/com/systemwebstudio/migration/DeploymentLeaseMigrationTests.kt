package com.systemwebstudio.migration

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/** C2 · V30 `deployment_rollback_and_scope_lease` on a real PostgreSQL (Flyway runs V1..V30): the status model, the order of intents and the scope lease. */
class DeploymentLeaseMigrationTests : IntegrationTestBase() {
    private val deployments = mutableListOf<UUID>()
    private val sites = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        // nothing of this class may stay in flight: the recovery sweeper and the undo guard look at every row of the database
        sites.forEach { jdbc.update("UPDATE sites SET lease_operation_id = NULL, lease_kind = NULL, lease_deployment_id = NULL, lease_seq = NULL, lease_fence = NULL, lease_holder = NULL, lease_started_at = NULL, lease_until = NULL WHERE project_id = ?", it) }
        deployments.forEach { jdbc.update("UPDATE deployments SET status = 'FAILED' WHERE id = ?", it) }
        sites.clear(); deployments.clear()
    }

    private fun deployment(sc: Scenario, status: String = "RUNNING", previous: UUID? = null): UUID {
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, previous_deployment_id) VALUES (?,?,?,?,?,'PUBLIC',?,'static',?)",
            id, sc.ws, sc.projectId, version, sc.user.id, status, previous)
        deployments += id
        return id
    }

    private fun site(sc: Scenario): UUID {
        jdbc.update("INSERT INTO sites (project_id, slug) VALUES (?, ?)", sc.projectId, "lease-${sc.projectId.toString().take(8)}")
        sites += sc.projectId
        return sc.projectId
    }

    private fun lease(project: UUID, kind: String = "ROLLBACK", deployment: UUID? = null, fence: Long = 1) {
        jdbc.update("UPDATE sites SET fence_counter = greatest(fence_counter, ?) WHERE project_id = ?", fence, project)
        jdbc.update("""UPDATE sites SET lease_operation_id = ?, lease_kind = ?, lease_deployment_id = ?, lease_seq = 5, lease_fence = ?, lease_holder = 'w1',
            lease_started_at = now(), lease_until = now() + interval '90 seconds' WHERE project_id = ?""", UUID.randomUUID(), kind, deployment, fence, project)
    }

    private fun migrationText() = File(listOf(File("."), File("backend")).first { File(it, "src/main/kotlin").isDirectory }, "src/main/resources/db/migration/V30__deployment_rollback_and_scope_lease.sql").readText()
    private fun undoText() = File(listOf(File("."), File("backend")).first { File(it, "src/main/kotlin").isDirectory }, "../docs/parallel/c2/undo/U30__deployment_rollback_and_scope_lease.sql").readText()

    @Test
    fun `ROLLING_BACK is a valid status, ROLLBACK_FAILED and ROLLBACK_OFFLINE are not - they are events`() {
        val sc = scenario()
        deployment(sc, "ROLLING_BACK"); deployment(sc, "ROLLED_BACK"); deployment(sc, "DEPLOYING")
        for (bad in listOf("ROLLBACK_FAILED", "ROLLBACK_OFFLINE", "BOGUS")) assertThatThrownBy { deployment(sc, bad) }.describedAs(bad).hasMessageContaining("deployments_status_check")
        val ev = deployment(sc)
        jdbc.update("INSERT INTO deployment_events (id, deployment_id, status, message) VALUES (?,?,'ROLLBACK_FAILED','x'), (?,?,'ROLLBACK_OFFLINE','y')", UUID.randomUUID(), ev, UUID.randomUUID(), ev)
    }

    @Test
    fun `every deployment draws the next activation number, in order, and it is unique`() {
        val sc = scenario()
        val a = deployment(sc); val b = deployment(sc); val c = deployment(sc)
        val seq = listOf(a, b, c).map { jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?", Long::class.java, it)!! }
        assertThat(seq).isSorted(); assertThat(seq.toSet()).hasSize(3); assertThat(seq.first()).isPositive()
        assertThatThrownBy { jdbc.update("UPDATE deployments SET activation_seq = ? WHERE id = ?", seq[0], b) }.hasMessageContaining("deployments_activation_seq_uq")
        assertThat(jdbc.queryForObject("SELECT nextval('deployment_activation_seq')", Long::class.java)!!).isGreaterThan(seq.last())     // rollback / unpublish draw from the same sequence
    }

    @Test
    fun `a new site starts free, at version 0, with no active operation`() {
        val sc = scenario(); site(sc)
        val row = jdbc.queryForMap("SELECT pointer_version, active_seq, active_operation_id, fence_counter, lease_operation_id, lease_until FROM sites WHERE project_id = ?", sc.projectId)
        assertThat(row["pointer_version"]).isEqualTo(0L); assertThat(row["active_seq"]).isEqualTo(0L); assertThat(row["fence_counter"]).isEqualTo(0L)
        assertThat(row["active_operation_id"]).isNull(); assertThat(row["lease_operation_id"]).isNull(); assertThat(row["lease_until"]).isNull()
    }

    @Test
    fun `a lease is all or nothing, names its deployment when it is a publish, and its token is never ahead of the counter`() {
        val sc = scenario(); val p = site(sc); val d = deployment(sc, "DEPLOYING")
        assertThatThrownBy { jdbc.update("UPDATE sites SET lease_operation_id = ? WHERE project_id = ?", UUID.randomUUID(), p) }.hasMessageContaining("sites_lease_shape_check")
        assertThatThrownBy { jdbc.update("UPDATE sites SET lease_until = now() WHERE project_id = ?", p) }.hasMessageContaining("sites_lease_shape_check")
        assertThatThrownBy { lease(p, "PUBLISH", deployment = null) }.hasMessageContaining("sites_lease_shape_check")           // a publish lease without its deployment
        assertThatThrownBy { lease(p, "BOGUS") }.hasMessageContaining("sites_lease_kind_check")
        assertThatThrownBy { jdbc.update("UPDATE sites SET lease_deployment_id = ? WHERE project_id = ?", d, p) }.hasMessageContaining("sites_lease_shape_check")   // a deployment without a lease
        assertThatThrownBy { jdbc.update("UPDATE sites SET fence_counter = 1, lease_operation_id = ?, lease_kind = 'ROLLBACK', lease_seq = 1, lease_fence = 2, lease_holder = 'w', lease_started_at = now(), lease_until = now() WHERE project_id = ?", UUID.randomUUID(), p) }
            .hasMessageContaining("sites_fence_check")
        assertThatThrownBy { jdbc.update("UPDATE sites SET pointer_version = -1 WHERE project_id = ?", p) }.hasMessageContaining("sites_pointer_check")
        lease(p, "PUBLISH", deployment = d)                                                                                        // the valid shapes
        jdbc.update("UPDATE sites SET lease_operation_id = NULL, lease_kind = NULL, lease_deployment_id = NULL, lease_seq = NULL, lease_fence = NULL, lease_holder = NULL, lease_started_at = NULL, lease_until = NULL WHERE project_id = ?", p)
        lease(p, "UNPUBLISH")
    }

    @Test
    fun `the backfill of the previous release accepts only a deployment of the same project, and active_seq starts from the served deployment`() {
        val sc = scenario(); val other = scenario()
        val mine = deployment(sc); val next = deployment(sc); val foreign = deployment(other); val ghostNext = deployment(sc); val selfRef = deployment(sc)
        fun switchEvent(on: UUID, text: String) = jdbc.update("INSERT INTO deployment_events (id, deployment_id, status, message) VALUES (?,?,'SWITCH',?)", UUID.randomUUID(), on, text)
        switchEvent(next, "Replacing release v1 [$mine]"); switchEvent(ghostNext, "Replacing release v1 [$foreign]"); switchEvent(selfRef, "Replacing release v1 [$selfRef]")
        val sql = migrationText()
        val update = sql.substring(sql.indexOf("UPDATE deployments d SET previous_deployment_id"), sql.indexOf(';', sql.indexOf("UPDATE deployments d SET previous_deployment_id")))
        jdbc.update(update)                                                      // the migration's own backfill statement, on rows written after it ran
        fun previous(id: UUID) = jdbc.queryForObject("SELECT previous_deployment_id FROM deployments WHERE id = ?", UUID::class.java, id)
        assertThat(previous(next)).isEqualTo(mine)
        assertThat(previous(ghostNext)).isNull()                                 // another project's release is not a rollback target
        assertThat(previous(selfRef)).isNull()

        val p = site(sc); jdbc.update("UPDATE sites SET current_deployment_id = ? WHERE project_id = ?", next, p)
        val seed = sql.substring(sql.indexOf("UPDATE sites s SET active_seq"), sql.indexOf(';', sql.indexOf("UPDATE sites s SET active_seq")))
        jdbc.update(seed)
        val row = jdbc.queryForMap("SELECT active_seq, active_operation_id FROM sites WHERE project_id = ?", p)
        assertThat(row["active_seq"]).isEqualTo(jdbc.queryForObject("SELECT activation_seq FROM deployments WHERE id = ?", Long::class.java, next))
        assertThat(row["active_operation_id"]).isEqualTo(next)
    }

    @Test
    fun `the undo script refuses while a deployment is ROLLING_BACK or a scope lease is held, and changes nothing`() {
        val script = undoText()
        val sc = scenario(); val p = site(sc)
        val rolling = deployment(sc, "ROLLING_BACK")
        assertThatThrownBy { jdbc.execute(script) }.hasMessageContaining("U30 refused").hasMessageContaining("ROLLING_BACK")
        jdbc.update("UPDATE deployments SET status = 'FAILED' WHERE id = ?", rolling)
        lease(p)
        assertThatThrownBy { jdbc.execute(script) }.hasMessageContaining("U30 refused").hasMessageContaining("lease")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'sites' AND column_name = 'pointer_version'", Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT to_regclass('deployment_activation_seq') IS NOT NULL", Boolean::class.java)).isTrue()
    }

    @Test
    fun `the undo script drops exactly what V30 added and puts the old status list back`() {
        val script = undoText(); val up = migrationText()
        val addedSiteColumns = Regex("ALTER TABLE sites ADD COLUMN (\\w+)").findAll(up).map { it.groupValues[1] }.toList()
        assertThat(addedSiteColumns).hasSize(12)
        addedSiteColumns.forEach { assertThat(script).describedAs(it).contains("DROP COLUMN $it") }
        assertThat(script).contains("DROP COLUMN previous_deployment_id").contains("DROP COLUMN activation_seq").contains("DROP SEQUENCE")
        assertThat(script).contains("('QUEUED', 'POLICY_CHECK', 'SECURITY_CHECK', 'BUILDING', 'DEPLOYING', 'RUNNING', 'FAILED', 'ROLLED_BACK')").doesNotContain("'ROLLING_BACK')\n")
        listOf("sites_lease_kind_check", "sites_lease_shape_check", "sites_fence_check", "sites_pointer_check").forEach { assertThat(script).contains("DROP CONSTRAINT $it") }
    }

    @Test
    fun `V30 runs after V29 and is applied once`() {
        val ranks = jdbc.query("SELECT version, installed_rank, success FROM flyway_schema_history WHERE version IN ('29', '30')") { rs, _ -> Triple(rs.getString(1), rs.getInt(2), rs.getBoolean(3)) }.associateBy { it.first }
        assertThat(ranks.keys).containsExactlyInAnyOrder("29", "30")
        assertThat(ranks.getValue("30").third).isTrue()
        assertThat(ranks.getValue("30").second).isGreaterThan(ranks.getValue("29").second)
    }
}
