package com.systemwebstudio.migration

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/** C0 · V29 `workflow_run_persistence` schema behaviour on a real PostgreSQL (Flyway runs V1..V29). Every row is written under a fresh tenant and removed afterwards. */
class WorkflowRunPersistenceMigrationTests : IntegrationTestBase() {
    private val tables = listOf("action_runs", "workflow_runs", "workflow_run_steps")
    private val tenantsUsed = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        for (t in tenantsUsed) {
            jdbc.update("DELETE FROM workflow_runs WHERE tenant_id = ?", t)     // steps cascade
            jdbc.update("DELETE FROM action_runs WHERE tenant_id = ?", t)
        }
        tenantsUsed.clear()
    }

    private fun newTenant(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, 'T')", id, "t-" + id.toString().take(8))
        tenantsUsed += id
        return id
    }

    private fun workspaceOf(tenant: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO workspaces (id, name, slug, tenant_id) VALUES (?, 'x', ?, ?)", id, "w-" + id.toString().take(8), tenant)
        return id
    }

    private fun projectIn(ws: UUID, tenant: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO projects (id, workspace_id, name, owner_user_id, tenant_id) VALUES (?, ?, 'p', ?, ?)", id, ws, fx.user().id, tenant)
        return id
    }

    private fun actionRun(
        tenant: UUID, ws: UUID? = null, app: UUID? = null, user: UUID = UUID.randomUUID(), key: String = "k-" + UUID.randomUUID(),
        mode: String = "LIVE", status: String = "RUNNING", finished: Boolean = false, result: String? = null, attempt: Int = 1
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO action_runs (run_id, tenant_id, workspace_id, app_id, action_id, user_id, idempotency_key, fingerprint, mode, status, attempt, result, started_at, finished_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'act', ?, ?, 'fp', ?, ?, ?, ?::jsonb, now(), CASE WHEN ? THEN now() END, now(), now())",
            id, tenant, ws, app, user, key, mode, status, attempt, result, finished
        )
        return id
    }

    private fun workflowRun(
        tenant: UUID, ws: UUID?, app: UUID, status: String = "PENDING", key: String = "k-" + UUID.randomUUID(), mode: String = "LIVE",
        compensation: String = "NONE", finished: Boolean = false, createdBy: UUID = UUID.randomUUID()
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO workflow_runs (run_id, tenant_id, workspace_id, app_id, workflow_id, mode, status, created_by, actor_kind, idempotency_key, fingerprint, input, definition, compensation, created_at, updated_at, finished_at) " +
                "VALUES (?, ?, ?, ?, 'wf', ?, ?, ?, 'USER', ?, 'fp', '{}'::jsonb, '{}'::jsonb, ?, now(), now(), CASE WHEN ? THEN now() END)",
            id, tenant, ws, app, mode, status, createdBy, key, compensation, finished
        )
        return id
    }

    private fun step(tenant: UUID, run: UUID, stepId: String = "a") =
        jdbc.update("INSERT INTO workflow_run_steps (run_id, tenant_id, step_id, status) VALUES (?, ?, ?, 'PENDING')", run, tenant, stepId)

    @Test
    fun `the three tables exist and tenant_id is mandatory on all of them`() {
        for (t in tables) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, t)).describedAs(t).isTrue()
            val nullable = jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = 'tenant_id'", String::class.java, t)
            assertThat(nullable).describedAs(t).isEqualTo("NO")
        }
    }

    @Test
    fun `action_runs app_id accepts NULL and a NULL app is one key scope`() {
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'action_runs' AND column_name = 'app_id'", String::class.java)).isEqualTo("YES")
        val t = newTenant()
        val user = UUID.randomUUID()
        actionRun(t, user = user, key = "same")
        assertThatThrownBy { actionRun(t, user = user, key = "same") }.hasMessageContaining("action_runs_key_unique")      // NULL app is NOT "every app is different"
        actionRun(t, user = UUID.randomUUID(), key = "same")                                                                 // another user: another scope
        actionRun(newTenant(), user = user, key = "same")                                                                    // another tenant: another scope
    }

    @Test
    fun `an application and its workspace come together or not at all`() {
        val t = newTenant(); val ws = workspaceOf(t); val app = projectIn(ws, t)
        assertThatThrownBy { actionRun(t, ws = ws, app = null) }.hasMessageContaining("action_runs_scope_check")
        assertThatThrownBy { actionRun(t, ws = null, app = app) }.hasMessageContaining("action_runs_scope_check")
        actionRun(t, ws = ws, app = app)
    }

    @Test
    fun `an action run cannot point at a workspace of another tenant or at a project of another workspace`() {
        val mine = newTenant(); val myWs = workspaceOf(mine); val myApp = projectIn(myWs, mine)
        val other = newTenant(); val otherWs = workspaceOf(other); val otherApp = projectIn(otherWs, other)
        // a consistent (workspace, project) pair of another tenant, claimed for this tenant
        assertThatThrownBy { actionRun(mine, ws = otherWs, app = otherApp) }.hasMessageContaining("action_runs_workspace_tenant_fk")
        // this tenant's workspace with a project that belongs to another workspace
        assertThatThrownBy { actionRun(mine, ws = myWs, app = otherApp) }.hasMessageContaining("action_runs_project_fk")
        actionRun(mine, ws = myWs, app = myApp)
        actionRun(other, ws = otherWs, app = otherApp)
    }

    @Test
    fun `status, mode, attempt and the RUNNING versus finished shape are enforced`() {
        val t = newTenant()
        assertThatThrownBy { actionRun(t, status = "BOGUS", finished = true, result = "{}") }.hasMessageContaining("action_runs_status_check")
        assertThatThrownBy { actionRun(t, mode = "TEST") }.hasMessageContaining("action_runs_mode_check")
        assertThatThrownBy { actionRun(t, attempt = 0) }.hasMessageContaining("action_runs_attempt_check")
        assertThatThrownBy { actionRun(t, status = "SUCCEEDED", finished = true, result = null) }.hasMessageContaining("action_runs_state_check")     // finished without a result
        assertThatThrownBy { actionRun(t, status = "FAILED", finished = false, result = "{}") }.hasMessageContaining("action_runs_state_check")       // finished without an end
        assertThatThrownBy { actionRun(t, status = "RUNNING", finished = false, result = "{}") }.hasMessageContaining("action_runs_state_check")      // RUNNING with a result
        assertThatThrownBy { actionRun(t, status = "SUCCEEDED", finished = true, result = "[]") }.hasMessageContaining("action_runs_result_check")    // result must be an object
        actionRun(t, status = "SUCCEEDED", finished = true, result = """{"t":"OK"}""")
    }

    @Test
    fun `a workflow run must name a real project, and its workspace must match the tenant and the project`() {
        val mine = newTenant(); val myWs = workspaceOf(mine); val myApp = projectIn(myWs, mine)
        val other = newTenant(); val otherWs = workspaceOf(other); val otherApp = projectIn(otherWs, other)
        assertThatThrownBy { workflowRun(mine, null, UUID.randomUUID()) }.hasMessageContaining("workflow_runs_app_id_fkey")
        assertThatThrownBy { workflowRun(mine, otherWs, otherApp) }.hasMessageContaining("workflow_runs_workspace_tenant_fk")
        assertThatThrownBy { workflowRun(mine, myWs, otherApp) }.hasMessageContaining("workflow_runs_project_fk")
        workflowRun(mine, myWs, myApp)
        workflowRun(mine, null, myApp)               // workspace is optional, as WorkflowRun.workspaceId is
    }

    @Test
    fun `workflow run start is idempotent per scope and a TEST run never shares a key with a LIVE run`() {
        val t = newTenant(); val ws = workspaceOf(t); val app = projectIn(ws, t)
        val user = UUID.randomUUID()
        workflowRun(t, ws, app, key = "k1", createdBy = user)
        assertThatThrownBy { workflowRun(t, ws, app, key = "k1", createdBy = user) }.hasMessageContaining("workflow_runs_key_unique")
        workflowRun(t, ws, app, key = "k1", createdBy = user, mode = "TEST")
        workflowRun(t, ws, app, key = "k1", createdBy = UUID.randomUUID())
    }

    @Test
    fun `status, compensation and the finished timestamp are constrained`() {
        val t = newTenant(); val ws = workspaceOf(t); val app = projectIn(ws, t)
        assertThatThrownBy { workflowRun(t, ws, app, status = "BOGUS") }.hasMessageContaining("workflow_runs_status_check")
        assertThatThrownBy { workflowRun(t, ws, app, compensation = "BOGUS") }.hasMessageContaining("workflow_runs_compensation_check")
        assertThatThrownBy { workflowRun(t, ws, app, mode = "BOGUS") }.hasMessageContaining("workflow_runs_mode_check")
        assertThatThrownBy { workflowRun(t, ws, app, status = "RUNNING", finished = true) }.hasMessageContaining("workflow_runs_finished_check")
        workflowRun(t, ws, app, status = "SUCCEEDED", finished = true)
    }

    @Test
    fun `C4 contract columns are part of V29 - action_runs mutating defaults to TRUE and is mandatory, the workflow lease is nullable and paired`() {
        fun column(table: String, name: String) = jdbc.queryForMap(
            "SELECT is_nullable, data_type, column_default, character_maximum_length FROM information_schema.columns WHERE table_name = ? AND column_name = ?", table, name)
        val mutating = column("action_runs", "mutating")
        assertThat(mutating["is_nullable"]).isEqualTo("NO")
        assertThat(mutating["data_type"]).isEqualTo("boolean")
        assertThat(mutating["column_default"].toString()).isEqualTo("true")
        val owner = column("workflow_runs", "lease_owner")
        assertThat(owner["is_nullable"]).isEqualTo("YES"); assertThat(owner["character_maximum_length"]).isEqualTo(64)
        assertThat(column("workflow_runs", "lease_until")["data_type"]).isEqualTo("timestamp with time zone")
        assertThat(column("workflow_runs", "lease_until")["is_nullable"]).isEqualTo("YES")

        val t = newTenant(); val ws = workspaceOf(t); val app = projectIn(ws, t)
        // a row that does not say anything about `mutating` is treated as mutating (the safe side for an abandoned run)
        val legacy = actionRun(t)
        assertThat(jdbc.queryForObject("SELECT mutating FROM action_runs WHERE run_id = ?", Boolean::class.java, legacy)).isTrue()
        // owner and expiry come together
        val run = workflowRun(t, ws, app, status = "RUNNING")
        assertThat(jdbc.queryForList("SELECT 1 FROM workflow_runs WHERE run_id = ? AND lease_owner IS NULL AND lease_until IS NULL", Int::class.java, run)).hasSize(1)
        assertThatThrownBy { jdbc.update("UPDATE workflow_runs SET lease_owner = 'node-1' WHERE run_id = ?", run) }.hasMessageContaining("workflow_runs_lease_check")
        assertThatThrownBy { jdbc.update("UPDATE workflow_runs SET lease_until = now() WHERE run_id = ?", run) }.hasMessageContaining("workflow_runs_lease_check")
        assertThat(jdbc.update("UPDATE workflow_runs SET lease_owner = 'node-1', lease_until = now() + interval '2 minutes' WHERE run_id = ?", run)).isEqualTo(1)
        assertThat(jdbc.update("UPDATE workflow_runs SET lease_owner = NULL, lease_until = NULL WHERE run_id = ?", run)).isEqualTo(1)
    }

    @Test
    fun `steps belong to a run of the same tenant and go with their run`() {
        val a = newTenant(); val aWs = workspaceOf(a); val aApp = projectIn(aWs, a)
        val b = newTenant()
        val run = workflowRun(a, aWs, aApp)
        assertThatThrownBy { step(b, run) }.hasMessageContaining("workflow_run_steps_run_fk")                // a step cannot claim another tenant for a run
        assertThatThrownBy { step(a, UUID.randomUUID()) }.hasMessageContaining("workflow_run_steps_run_fk")  // nor hang in the air
        step(a, run, "a"); step(a, run, "b")
        assertThatThrownBy { step(a, run, "a") }.hasMessageContaining("workflow_run_steps_pkey")
        assertThatThrownBy { jdbc.update("INSERT INTO workflow_run_steps (run_id, tenant_id, step_id, status) VALUES (?, ?, 'z', 'BOGUS')", run, a) }.hasMessageContaining("workflow_run_steps_status_check")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run_steps WHERE run_id = ?", Long::class.java, run)).isEqualTo(2L)
        jdbc.update("DELETE FROM workflow_runs WHERE run_id = ?", run)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run_steps WHERE run_id = ?", Long::class.java, run)).isEqualTo(0L)
    }

    @Test
    fun `the undo script refuses while a run is in flight and changes nothing`() {
        val t = newTenant(); val ws = workspaceOf(t); val app = projectIn(ws, t)
        actionRun(t)                                                    // a RUNNING action run
        val script = undoScript()
        for (inFlight in 0..1) {
            if (inFlight == 1) { jdbc.update("DELETE FROM action_runs WHERE tenant_id = ?", t); workflowRun(t, ws, app, status = "RUNNING") }
            val c = jdbc.dataSource!!.connection
            try {
                c.autoCommit = false
                assertThatThrownBy { c.createStatement().use { it.execute(script) } }.hasMessageContaining("U29 refused")
                c.rollback()
            } finally {
                c.autoCommit = true
                c.close()
            }
            for (tbl in tables) assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, tbl)).describedAs(tbl).isTrue()
        }
    }

    @Test
    fun `the undo script guards in flight work and drops exactly the three V29 tables children first`() {
        val script = undoScript()
        assertThat(script).contains("U29 refused")
        assertThat(script).contains("status = 'RUNNING'").contains("compensation = 'IN_PROGRESS'")
        val drops = Regex("DROP TABLE (\\w+);").findAll(script).map { it.groupValues[1] }.toList()
        assertThat(drops).containsExactly("workflow_run_steps", "workflow_runs", "action_runs")
        assertThat(script).doesNotContain("data_").doesNotContain("DELETE FROM")                  // V28 is not touched
    }

    @Test
    fun `V29 is registered as the next migration and V28 is not renumbered`() {
        val versions = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String::class.java)
        assertThat(versions).contains("28", "29")
        assertThat(versions.indexOf("29")).isGreaterThan(versions.indexOf("28"))
        val script = jdbc.queryForObject("SELECT script FROM flyway_schema_history WHERE version = '29'", String::class.java)
        assertThat(script).isEqualTo("V29__workflow_run_persistence.sql")
    }

    private fun undoScript(): String {
        val root = listOf(File("."), File("backend")).first { File(it, "src/main/kotlin").isDirectory }
        return File(root, "../docs/parallel/c0/undo/U29__workflow_run_persistence.sql").readText()
    }
}
