package com.systemwebstudio.publish

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The only legal edges of a deployment's status (C2_DEPLOY_CONTRACT.md §1.1). Pure. */
class DeploymentStatusTests {
    private val all = listOf("QUEUED", "POLICY_CHECK", "SECURITY_CHECK", "BUILDING", "DEPLOYING", "ROLLING_BACK", "RUNNING", "FAILED", "ROLLED_BACK")

    @Test
    fun `the full table of legal edges - and no other`() {
        val legal = setOf(
            "QUEUED" to "POLICY_CHECK", "POLICY_CHECK" to "SECURITY_CHECK", "SECURITY_CHECK" to "BUILDING", "BUILDING" to "DEPLOYING", "DEPLOYING" to "RUNNING",
            "QUEUED" to "FAILED", "POLICY_CHECK" to "FAILED", "SECURITY_CHECK" to "FAILED", "BUILDING" to "FAILED", "DEPLOYING" to "FAILED",
            "DEPLOYING" to "ROLLING_BACK", "ROLLING_BACK" to "FAILED", "RUNNING" to "ROLLED_BACK")
        val actual = all.flatMap { from -> all.map { to -> from to to } }.filter { (f, t) -> DeploymentStatus.allowed(f, t) }.toSet()
        assertThat(actual).isEqualTo(legal)
    }

    @Test
    fun `ROLLING_BACK is not terminal and leaves only to FAILED, so it can never roll forward`() {
        assertThat(DeploymentStatus.ROLLING_BACK).isNotIn(DeploymentStatus.terminal)
        assertThat(DeploymentStatus.ROLLING_BACK).isIn(DeploymentStatus.inProgress)
        for (to in all.filter { it != "FAILED" }) assertThat(DeploymentStatus.allowed("ROLLING_BACK", to)).describedAs("ROLLING_BACK -> $to").isFalse()
    }

    @Test
    fun `ROLLED_BACK is terminal, only a RUNNING deployment gets there, and nothing leaves it`() {
        assertThat(DeploymentStatus.terminal).containsExactlyInAnyOrder("RUNNING", "FAILED", "ROLLED_BACK")      // unchanged: pollers keep working
        for (from in all.filter { it != "RUNNING" }) assertThat(DeploymentStatus.allowed(from, "ROLLED_BACK")).describedAs("$from -> ROLLED_BACK").isFalse()
        for (to in all) assertThat(DeploymentStatus.allowed("ROLLED_BACK", to)).describedAs("ROLLED_BACK -> $to").isFalse()
    }

    @Test
    fun `only DEPLOYING can start a rollback, and ROLLBACK_FAILED and ROLLBACK_OFFLINE are not statuses`() {
        for (from in all.filter { it != "DEPLOYING" }) assertThat(DeploymentStatus.allowed(from, "ROLLING_BACK")).describedAs("$from -> ROLLING_BACK").isFalse()
        assertThat(all).doesNotContain("ROLLBACK_FAILED", "ROLLBACK_OFFLINE")
        assertThat(DeploymentStatus.allowed("DEPLOYING", "ROLLBACK_FAILED")).isFalse()
    }
}
