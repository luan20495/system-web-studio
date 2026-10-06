package com.systemwebstudio.publish

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** The previous release of a switch is read back from the history, but only when what it says is a real release of the same app. */
class PreviousReleaseTests : IntegrationTestBase() {
    @Autowired lateinit var releases: JdbcReleaseStore

    private fun deployment(sc: Scenario): UUID {
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? LIMIT 1", UUID::class.java, sc.projectId)
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider) VALUES (?,?,?,?,?,'PUBLIC','RUNNING','static')",
            id, sc.ws, sc.projectId, version, sc.user.id)
        return id
    }
    private fun switchEvent(on: UUID, text: String) =
        jdbc.update("INSERT INTO deployment_events (id, deployment_id, status, message) VALUES (?,?,'SWITCH',?)", UUID.randomUUID(), on, text)

    @Test
    fun `the release recorded by the switch is returned, and recording it round-trips`() {
        val sc = scenario(); val previous = deployment(sc); val next = deployment(sc)
        assertThat(releases.recordedPrevious(next)).isNull()
        releases.recordPrevious(next, previous)
        assertThat(releases.recordedPrevious(next)).isEqualTo(previous)
    }

    @Test
    fun `an id that is not a release of the same app is never trusted as the rollback target`() {
        val sc = scenario(); val other = scenario()
        val next = deployment(sc); val foreign = deployment(other)
        switchEvent(next, "Replacing release v1 [$foreign]")                         // another app's release
        assertThat(releases.recordedPrevious(next)).isNull()
        val ghost = deployment(sc).also { jdbc.update("DELETE FROM deployments WHERE id = ?", it) }
        val next2 = deployment(sc); switchEvent(next2, "Replacing release v1 [$ghost]")  // a release that does not exist
        assertThat(releases.recordedPrevious(next2)).isNull()
        val next3 = deployment(sc); switchEvent(next3, "Replacing release v1 [$next3]")  // itself
        assertThat(releases.recordedPrevious(next3)).isNull()
        val next4 = deployment(sc); switchEvent(next4, "Replacing release [not-a-uuid]") // text that is not an id
        assertThat(releases.recordedPrevious(next4)).isNull()
    }
}
