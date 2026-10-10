package com.systemwebstudio.data.hardening

import com.systemwebstudio.data.InMemoryQueryCatalog
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceTypes
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.ResolvedCredential
import com.systemwebstudio.data.datasource.SystemHostResolver
import com.systemwebstudio.data.datasource.postgres.JdbcPgConnectionFactory
import com.systemwebstudio.data.datasource.postgres.PostgresConnector
import com.systemwebstudio.data.datasource.postgres.PostgresTargetPolicy
import com.systemwebstudio.data.org.OrgTestDb
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.PageSpec
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.QueryRequest
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.tenancy.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LARGE RESULT BEHAVIOUR of the production PostgreSQL connector against a 1,000,000-row table on a real server: a result is bounded by `maxRows`, by the page and by
 * `maxResponseBytes` WITHOUT reading the table, a deep page stays correct, a runaway sort is stopped by the statement timeout and leaves no session behind, and parallel
 * readers do not interfere. Timings are written to build/reports/data-query-scale.md (median / p95 of repeated calls); the assertions are deliberately loose bounds, the
 * numbers are the evidence.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataQueryScaleOnPostgresTests {
    private val pg = OrgTestDb.postgres
    private val tenant = UUID.randomUUID(); private val dsId = UUID.randomUUID()
    private val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
    private val database = "scale_$suffix"; private val role = "scale_ro_$suffix"; private val pass = "ro-pass-$suffix-Zq9"
    private val ro get() = ResolvedCredential.of(mapOf("username" to role, "password" to pass))
    private val report = StringBuilder()
    private val rowsTotal = 1_000_000

    private fun admin(db: String) = DriverManager.getConnection(pg.jdbcUrl.replaceAfterLast("/", db).substringBefore("?"), pg.username, pg.password)

    @BeforeAll fun seed() {
        admin("postgres").use { c -> c.createStatement().use { it.execute("CREATE DATABASE $database") } }
        admin(database).use { c -> c.createStatement().use { st ->
            st.execute("""CREATE SCHEMA shop;
                CREATE TABLE shop.events (id bigint PRIMARY KEY, kind text NOT NULL, amount numeric(12,2) NOT NULL, created timestamptz NOT NULL, payload text NOT NULL);
                INSERT INTO shop.events SELECT g, 'k' || (g % 50), (g % 10000) / 100.0, TIMESTAMPTZ '2026-01-01' + g * interval '1 second', md5(g::text) || repeat('x', 200) FROM generate_series(1, $rowsTotal) g;
                CREATE INDEX events_kind_idx ON shop.events (kind, id);
                ANALYZE shop.events;
                CREATE ROLE $role LOGIN PASSWORD '$pass' NOSUPERUSER NOCREATEDB NOCREATEROLE;
                GRANT CONNECT ON DATABASE $database TO $role; GRANT USAGE ON SCHEMA shop TO $role; GRANT SELECT ON ALL TABLES IN SCHEMA shop TO $role;""")
        } }
        report.append("# Data query scale - PostgreSQL connector, $rowsTotal-row table (machine load disclosed in the test log; numbers are evidence, not an SLA)\n\n| case | rows returned | truncated | median ms | p95 ms |\n|---|---|---|---|---|\n")
    }

    private fun ref(extra: Map<String, String> = emptyMap()) =
        DataSourceRef(dsId, tenant, DataSourceTypes.POSTGRES, mapOf("host" to pg.host, "port" to pg.getMappedPort(5432).toString(), "database" to database, "schemas" to "shop") + extra)
    private val policy get() = PostgresTargetPolicy(allowedPrivateHosts = setOf("${pg.host.lowercase()}:${pg.getMappedPort(5432)}"))
    private fun connector(vararg defs: SqlQueryDefinition) = PostgresConnector(InMemoryQueryCatalog(*defs), policy, SystemHostResolver, JdbcPgConnectionFactory(enforceTls = false))
    private fun req(id: String, params: Map<String, Any> = emptyMap(), page: PageSpec? = null) = QueryRequest(id, params.mapValues { DataJson.toNode(it.value) }, page, TenantContext(tenant, null))
    private fun def(id: String, sql: String, maxRows: Int = 1000, params: List<QueryParamSpec> = emptyList()) = SqlQueryDefinition(id, tenant, dsId, sql, params, maxRows)

    private fun measure(label: String, runs: Int = 9, block: () -> com.systemwebstudio.data.query.QueryResult): com.systemwebstudio.data.query.QueryResult {
        val first = block(); val times = (1..runs).map { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1_000_000.0 }.sorted()
        report.append("| $label | ${first.rows.size} | ${first.truncated} | ${"%.1f".format(times[times.size / 2])} | ${"%.1f".format(times[(times.size * 95 / 100).coerceAtMost(times.size - 1)])} |\n")
        return first
    }

    private fun sessionsOf(role: String) = admin("postgres").use { c -> c.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE usename = '$role'").use { it.next(); it.getInt(1) } } }

    @Test
    fun `a huge table never produces a huge result - maxRows, the page and the byte cap bound it, and the first rows come back without scanning the table`() {
        val all = def("all", "SELECT id, kind, amount, created, payload FROM shop.events", maxRows = 500)
        val unsorted = measure("SELECT * of 1M rows, maxRows 500 (no ORDER BY)") { connector(all).executor().execute(req("all"), ref(), ro) }
        assertThat(unsorted.rows.size).isEqualTo(500); assertThat(unsorted.truncated).isTrue()

        val sorted = def("byid", "SELECT id, kind, amount FROM shop.events ORDER BY id", maxRows = 200)
        val firstPage = measure("ORDER BY id, maxRows 200") { connector(sorted).executor().execute(req("byid"), ref(), ro) }
        assertThat(firstPage.rows.size).isEqualTo(200); assertThat(firstPage.truncated).isTrue()
        assertThat(DataJson.text(firstPage.rows.first()["id"]!!)).isEqualTo("1")

        val byKind = def("bykind", "SELECT id, kind FROM shop.events WHERE kind = :k ORDER BY id", maxRows = 100, params = listOf(QueryParamSpec("k", ParamType.STRING)))
        val kind = measure("indexed filter kind = :k, page 100") { connector(byKind).executor().execute(req("bykind", mapOf("k" to "k7"), PageSpec(100, 0)), ref(), ro) }
        assertThat(kind.rows.size).isEqualTo(100); assertThat(kind.rows.all { DataJson.text(it["kind"]!!) == "k7" }).isTrue()

        val wide = def("wide", "SELECT payload FROM shop.events", maxRows = 10_000)
        val capped = measure("byte cap 64 KiB over ~230-byte rows") { connector(wide).executor().execute(req("wide"), ref(mapOf("maxResponseBytes" to "65536")), ro) }
        assertThat(capped.truncated).isTrue(); assertThat(capped.rows.size).describedAs("about 65536 / 270 rows, never the 10,000 of maxRows").isBetween(100, 400)
    }

    @Test
    fun `pages are contiguous and complete at every depth including the deepest allowed offset, and a page past the end is empty`() {
        val d = def("page", "SELECT id FROM shop.events ORDER BY id", maxRows = 10_000)
        val patient = mapOf("timeoutMs" to "30000")                       // a deep OFFSET is O(offset) on the target (finding F-2): on a loaded host it can pass the 10 s default, so these two probes use the 30 s ceiling
        for (offset in listOf(0, 100, 9_900, 100_000, 900_000)) {
            val r = measure("page of 100 at offset $offset", runs = if (offset >= 900_000) 3 else 9) { connector(d).executor().execute(req("page", page = PageSpec(100, offset)), ref(patient), ro) }
            assertThat(r.rows.map { DataJson.text(it["id"]!!).toLong() }).describedAs("offset $offset").isEqualTo(((offset + 1L)..(offset + 100L)).toList())
        }
        val deepest = measure("page of 100 at the largest allowed offset ${PageSpec.MAX_PAGE_OFFSET}", runs = 3) { connector(d).executor().execute(req("page", page = PageSpec(100, PageSpec.MAX_PAGE_OFFSET)), ref(patient), ro) }
        assertThat(deepest.rows).isEmpty()                                                                           // exactly 1,000,000 rows: the offset is past the end
        val big = measure("page of 10,000 (the largest page, source maxRows 10000) at offset 500,000") { connector(d).executor().execute(req("page", page = PageSpec(PageSpec.MAX_PAGE_LIMIT, 500_000)), ref(patient + mapOf("maxRows" to "10000")), ro) }
        assertThat(big.rows.size).isEqualTo(10_000)
    }

    @Test
    fun `a runaway sort over the whole table is stopped by the statement timeout, comes back as TIMEOUT quickly, and leaves no session open`() {
        val heavy = def("heavy", "SELECT id FROM shop.events ORDER BY md5(payload || kind) DESC", maxRows = 10)
        val t0 = System.nanoTime()
        val f = try { connector(heavy).executor().execute(req("heavy"), ref(mapOf("timeoutMs" to "500")), ro); null } catch (e: ConnectorFailure) { e }
        val ms = (System.nanoTime() - t0) / 1_000_000
        report.append("| runaway ORDER BY md5(...) over 1M rows, timeoutMs 500 | - | - | $ms | - |\n")
        assertThat(f?.code).isEqualTo(FailureCodes.TIMEOUT); assertThat(ms).describedAs("stopped near the limit, not after the sort").isLessThan(10_000)
        Thread.sleep(500)
        assertThat(sessionsOf(role)).describedAs("no session of the connector role remains").isZero()
    }

    @Test
    fun `20 readers at once each get their own bounded, correct page and every session is closed afterwards`() {
        val d = def("conc", "SELECT id, kind FROM shop.events WHERE kind = :k ORDER BY id", maxRows = 100, params = listOf(QueryParamSpec("k", ParamType.STRING)))
        val ex = Executors.newFixedThreadPool(20); val started = System.nanoTime()
        try {
            val futures = (0 until 20).map { i -> ex.submit<Pair<Int, Boolean>> {
                val r = connector(d).executor().execute(req("conc", mapOf("k" to "k${i % 50}"), PageSpec(50, i * 10)), ref(), ro)
                r.rows.size to r.rows.all { DataJson.text(it["kind"]!!) == "k${i % 50}" }
            } }
            futures.forEach { assertThat(it.get(60, TimeUnit.SECONDS)).isEqualTo(50 to true) }
        } finally { ex.shutdownNow() }
        report.append("| 20 concurrent paged queries (indexed filter) | 50 each | - | ${(System.nanoTime() - started) / 1_000_000} total | - |\n")
        Thread.sleep(500); assertThat(sessionsOf(role)).isZero()
    }

    @org.junit.jupiter.api.AfterAll fun write() {
        val dir = Path.of("build", "reports"); Files.createDirectories(dir); Files.writeString(dir.resolve("data-query-scale.md"), report.toString())
        println(report)
    }
}
