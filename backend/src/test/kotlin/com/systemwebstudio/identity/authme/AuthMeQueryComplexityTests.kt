package com.systemwebstudio.identity.authme

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext

/**
 * GET /api/v1/auth/me must resolve projectScopes with a BOUNDED number of SQL statements: the count may not grow with the number of project memberships.
 * Exact property asserted: statements(N = 2000) <= statements(N = 25) + 3. (An N+1 resolver - one AccessService.forProject per membership - fails it.)
 */
@Import(SqlStatementCountingConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthMeQueryComplexityTests : AuthMeFixtureBase() {

    @Test
    fun `auth me statement count does not grow with the number of project memberships and the scopes are correct`() {
        val statements = linkedMapOf<Int, Long>()
        val soft = SoftAssertions()
        for (n in SIZES) {
            val b = bulk(n)
            val s = login(b.user)
            repeat(2) { me(s) }                                    // warm-up (caches, lazy beans, JIT), discarded
            val m = measureMe(s)
            statements[n] = m.statements
            System.err.println("[AuthMeQueryComplexity] N=$n statements(request thread)=${m.statements} statements(all threads)=${m.statementsAllThreads} ms=${"%.1f".format(m.millis)} bytes=${m.bytes}")
            checkScopes(soft, b, m)
        }
        System.err.println("[AuthMeQueryComplexity] raw: " + statements.entries.joinToString(" ") { "N=${it.key}:${it.value}" })
        soft.assertAll()
        assertThat(statements.getValue(25)).describedAs("positive control: the statement counter really counts (raw: $statements)").isGreaterThanOrEqualTo(4)
        assertThat(statements.getValue(2000))
            .describedAs("statements of /auth/me with 2000 project memberships must not exceed statements with 25 memberships + 3 (raw: $statements)")
            .isLessThanOrEqualTo(statements.getValue(25) + 3)
    }

    private fun checkScopes(soft: SoftAssertions, b: Bulk, m: Measurement) {
        val scopes = scopesOf(m.body)
        soft.assertThat(scopes).describedAs("N=${b.n}: one scope per visible membership").hasSize(b.expected.size)
        soft.assertThat(scopes.map { it.projectId }.toSet()).describedAs("N=${b.n}: project ids").isEqualTo(b.expected.keys)
        for (sc in scopes) {
            val e = b.expected[sc.projectId] ?: continue
            val wsRole = b.workspaceRoles.getValue(e.workspaceId)
            soft.assertThat(sc.workspaceId).describedAs("workspace of ${sc.projectId}").isEqualTo(e.workspaceId)
            soft.assertThat(sc.role).describedAs("role of ${sc.projectId}").isEqualTo(e.role)
            if (e.archived) {
                // ARCHIVED: read + audit only; AUDIT_READ is not a canonical code, so exactly APP_VIEW
                soft.assertThat(sc.permissions).describedAs("archived ${sc.projectId} ($wsRole/${e.role})").containsExactly("APP_VIEW")
                continue
            }
            soft.assertThat(sc.permissions).describedAs("${sc.projectId} ($wsRole/${e.role})").contains("APP_VIEW", "APP_USE")
            if (wsRole == "WORKSPACE_ADMIN") {
                soft.assertThat(sc.permissions).describedAs("workspace admin on ${sc.projectId}")
                    .contains("APP_EDIT", "APP_PUBLISH", "APP_SHARE", "DATA_SOURCE_MANAGE", "DATA_MUTATE", "WORKFLOW_MANAGE", "MEMBER_MANAGE")
            } else {
                val expected = when (e.role) {
                    "VIEWER" -> listOf("APP_USE", "APP_VIEW")
                    "EDITOR" -> listOf("ACTION_EXECUTE", "APP_EDIT", "APP_USE", "APP_VIEW", "DATA_SOURCE_VIEW", "QUERY_EXECUTE")
                    "PUBLISHER" -> listOf("APP_PUBLISH", "APP_USE", "APP_VIEW")
                    else -> listOf("ACTION_EXECUTE", "APP_EDIT", "APP_PUBLISH", "APP_SHARE", "APP_USE", "APP_VIEW", "DATA_SOURCE_VIEW", "QUERY_EXECUTE")
                }
                soft.assertThat(sc.permissions).describedAs("${sc.projectId} ($wsRole/${e.role})").containsExactlyInAnyOrderElementsOf(expected)
            }
        }
    }
}
