package com.systemwebstudio.publish

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** The release that was active when a switch started is a typed column (V30): written once, only ever another deployment of the same app. */
class PreviousReleaseTests : IntegrationTestBase() {
    @Autowired lateinit var releases: JdbcReleaseStore

    private fun deployment(sc: Scenario, status: String = "RUNNING"): UUID {
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?,?,?,?,?,'PUBLIC',?,'static')", id, sc.ws, sc.projectId, version, sc.user.id, status)
        return id
    }
    private fun events(id: UUID) = jdbc.queryForList("SELECT message FROM deployment_events WHERE deployment_id = ? AND status = 'SWITCH'", String::class.java, id)

    @Test
    fun `the previous release is recorded once, in the typed column, with the text kept for people`() {
        val sc = scenario(); val previous = deployment(sc); val next = deployment(sc, "DEPLOYING")
        assertThat(releases.recordedPrevious(next)).isNull()
        releases.recordPrevious(next, previous)
        assertThat(releases.recordedPrevious(next)).isEqualTo(previous)
        assertThat(jdbc.queryForObject("SELECT previous_deployment_id FROM deployments WHERE id = ?", UUID::class.java, next)).isEqualTo(previous)
        assertThat(events(next)).hasSize(1); assertThat(events(next).single()).contains(previous.toString())
        // a resumed worker records it again: nothing changes, nothing is duplicated
        releases.recordPrevious(next, deployment(sc))
        assertThat(releases.recordedPrevious(next)).isEqualTo(previous); assertThat(events(next)).hasSize(1)
    }

    @Test
    fun `another app's release, a missing release or the deployment itself can never be recorded as the previous one`() {
        val sc = scenario(); val other = scenario()
        val next = deployment(sc, "DEPLOYING"); val foreign = deployment(other)
        releases.recordPrevious(next, foreign)
        assertThat(releases.recordedPrevious(next)).isNull()
        releases.recordPrevious(next, next)
        assertThat(releases.recordedPrevious(next)).isNull()
        assertThat(events(next)).isEmpty()
        releases.recordPrevious(next, UUID.randomUUID())                                   // an unknown id writes nothing and does not fail
        assertThat(releases.recordedPrevious(next)).isNull()
    }

    @Test
    fun `text in an event is not state - a SWITCH message does not make a release the previous one`() {
        val sc = scenario(); val previous = deployment(sc); val next = deployment(sc, "DEPLOYING")
        jdbc.update("INSERT INTO deployment_events (id, deployment_id, status, message) VALUES (?,?,'SWITCH',?)", UUID.randomUUID(), next, "Replacing release v1 [$previous]")
        assertThat(releases.recordedPrevious(next)).isNull()
    }
}
