package com.systemwebstudio.data.hardening

import com.github.dockerjava.api.model.ExposedPort
import com.systemwebstudio.data.org.FlywayHarness
import com.systemwebstudio.data.org.OrgFx
import com.systemwebstudio.data.org.OrgSeedGenerator
import com.systemwebstudio.data.org.OrgSeedLoader
import com.systemwebstudio.data.org.OrgTestDb
import com.systemwebstudio.data.org.SeedSpec
import com.systemwebstudio.organization.OrganizationCycle
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * RELOAD PERSISTENCE of the dynamic organization: what was written through the production repositories is exactly what a fresh pool, and then a RESTARTED PostgreSQL server
 * (a real `docker restart` of a dedicated container, so the data came back from disk and not from a cache), reads back - tree, memberships, primary flags, positions, grades,
 * archive state, versions, counts and the directory - and optimistic versions and the cycle guard keep working on the reloaded data.
 */
class OrgReloadPersistenceTests {
    private val tables = listOf("organization_unit_types", "organization_units", "employee_organization_units", "positions", "grades", "employee_positions")

    private fun pool(url: String, pg: PostgreSQLContainer) = HikariDataSource(HikariConfig().apply { jdbcUrl = url; username = pg.username; password = pg.password; maximumPoolSize = 6; connectionTimeout = 60_000; poolName = "reload" })

    private fun urlNow(pg: PostgreSQLContainer): String {
        val port = DockerClientFactory.lazyClient().inspectContainerCmd(pg.containerId).exec().networkSettings.ports.bindings[ExposedPort.tcp(5432)]!!.first().hostPortSpec
        return "jdbc:postgresql://${pg.host}:$port/${pg.databaseName}?loggerLevel=OFF"
    }

    private class State(val rows: Map<String, Pair<Long, String>>, val tree: List<String>, val counts: List<String>, val directory: List<String>, val primaries: List<String>)

    private fun capture(stack: OrgTestDb.Stack, jdbc: JdbcTemplate, tenant: UUID): State {
        val rows = FlywayHarness.snapshot(jdbc, tables.associateWith { "$it.*" }).perTable
        val tree = stack.units.fullTree(tenant, 5_000).map { "${it.id}|${it.parentId}|${it.code}|${it.active}|${it.version}|${it.depth}" }
        val counts = stack.counts.countsForAll(tenant).sortedBy { it.unitId }.map { "${it.unitId}|${it.direct}|${it.subtreeDistinct}" }
        val dir = stack.directory.search(tenant, com.systemwebstudio.organization.EmployeeSearch(null, null, null, null, null, null, "name", true, 0, 100), stack.identities).items.map { it.toString() }
        val primaries = jdbc.queryForList("SELECT user_id::text || '|' || organization_unit_id::text FROM employee_organization_units WHERE tenant_id = ? AND is_primary AND active ORDER BY 1", String::class.java, tenant)
        return State(rows, tree, counts, dir, primaries)
    }

    private fun assertSame(a: State, b: State, what: String) {
        assertThat(b.rows).describedAs("$what: every organization table, row count and content hash").isEqualTo(a.rows)
        assertThat(b.tree).describedAs("$what: tree").isEqualTo(a.tree); assertThat(b.counts).describedAs("$what: direct / subtree counts").isEqualTo(a.counts)
        assertThat(b.directory).describedAs("$what: directory page").isEqualTo(a.directory); assertThat(b.primaries).describedAs("$what: primary memberships").isEqualTo(a.primaries)
    }

    @Test
    fun `data written through the repositories survives a new pool and a restart of the PostgreSQL server unchanged, and versions and the cycle guard keep working`() {
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:17.6")).apply { start() }
        try {
            Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).locations("classpath:db/migration").load().migrate()
            var ds = pool(pg.jdbcUrl, pg); var jdbc = JdbcTemplate(ds); var stack = OrgTestDb.Stack(ds)

            // a company: seeded tree (3 roots, spine, a depth-60 chain, a 40-children node), 500 employees with several memberships, positions and grades ...
            val tenant = UUID.randomUUID()
            val data = OrgSeedGenerator.generate(SeedSpec(units = 400, roots = 3, spineDepth = 8, chainDepth = 60, broadChildren = 40, employees = 500, tag = "reload"))
            val set = OrgSeedLoader.load(jdbc, tenant, data)
            // ... then edits through the repositories: archive + restore, a move, a rename, an ended membership, a changed primary
            val fx = OrgFx(stack); val type = fx.type(tenant, "reloadtype")
            val a = fx.unit(tenant, type, "RA"); val b = fx.unit(tenant, type, "RB"); val c = fx.unit(tenant, type, "RC", b.id)
            val u = OrgTestDb.newMember(tenant, "reload-u", "Reload U", jdbc = jdbc); val ma = fx.membership(tenant, u, a.id); val mc = fx.membership(tenant, u, c.id)
            val pos = fx.position(tenant, "RPOS"); val grade = fx.grade(tenant, "RG1", 3); fx.hold(mc, pos, grade)
            stack.memberships.setPrimary(tenant, u, mc.id, mc.version)
            stack.inTx { stack.lock.acquire(tenant); stack.units.move(tenant, c.id, a.id, null, c.version) }
            assertThatThrownBy { stack.units.setActive(tenant, c.id, false, stack.units.find(tenant, c.id)!!.version) }.describedAs("a unit with an active member cannot be archived").isInstanceOf(com.systemwebstudio.organization.OrganizationUnitInUse::class.java)
            val d = fx.unit(tenant, type, "RD", a.id)
            val archived = stack.units.setActive(tenant, d.id, false, d.version)!!
            assertThat(archived.active).isFalse()
            val restored = stack.units.setActive(tenant, d.id, true, archived.version)!!
            assertThat(restored.version).isGreaterThan(archived.version)
            assertThat(stack.memberships.end(tenant, u, ma.id, stack.memberships.find(tenant, u, ma.id)!!.version)).isNotNull()
            val before = capture(stack, jdbc, tenant)
            assertThat(before.tree.size).isGreaterThan(400); assertThat(before.rows.getValue("employee_organization_units").first).isGreaterThan(500)

            // reload 1 - a brand-new pool and brand-new repositories
            ds.close(); ds = pool(pg.jdbcUrl, pg); jdbc = JdbcTemplate(ds); stack = OrgTestDb.Stack(ds)
            assertSame(before, capture(stack, jdbc, tenant), "new pool")

            // reload 2 - the server itself restarts (disk, not memory)
            ds.close()
            DockerClientFactory.lazyClient().restartContainerCmd(pg.containerId).withTimeout(20).exec()
            var url = ""; var ready = false; val deadline = System.currentTimeMillis() + 90_000
            while (!ready && System.currentTimeMillis() < deadline) {
                runCatching { url = urlNow(pg); java.sql.DriverManager.getConnection(url, pg.username, pg.password).use { it.createStatement().use { s -> s.execute("SELECT 1") } }; ready = true }.onFailure { Thread.sleep(500) }
            }
            assertThat(ready).describedAs("PostgreSQL is back after the restart").isTrue()
            ds = pool(url, pg); jdbc = JdbcTemplate(ds); stack = OrgTestDb.Stack(ds)
            assertThat(jdbc.queryForObject("SELECT pg_postmaster_start_time() > now() - interval '2 minutes'", Boolean::class.java)).describedAs("the server really restarted").isTrue()
            assertSame(before, capture(stack, jdbc, tenant), "after a server restart")

            // the reloaded data is live: versions are the CAS tokens they were, the cycle guard sees the stored tree, new writes persist
            val unit = stack.units.find(tenant, a.id)!!
            assertThat(stack.units.update(unit.copy(name = "renamed after restart"), unit.version + 99)).describedAs("a stale version applies nothing").isNull()
            val renamed = stack.units.update(unit.copy(name = "renamed after restart"), unit.version)!!; assertThat(renamed.version).isEqualTo(unit.version + 1)
            val deepest = set.unitIds.maxBy { id -> stack.units.depthOf(tenant, id) ?: 0 }
            val chain = stack.units.find(tenant, stack.units.ancestors(tenant, deepest).first().id)!! to stack.units.find(tenant, deepest)!!
            assertThatThrownBy { stack.inTx { stack.lock.acquire(tenant); stack.units.move(tenant, chain.first.id, chain.second.id, null, chain.first.version) } }.isInstanceOf(OrganizationCycle::class.java)
            ds.close(); ds = pool(urlNow(pg), pg); stack = OrgTestDb.Stack(ds)
            assertThat(stack.units.find(tenant, a.id)!!.name).isEqualTo("renamed after restart")
            ds.close()
        } finally { pg.stop() }
    }
}
