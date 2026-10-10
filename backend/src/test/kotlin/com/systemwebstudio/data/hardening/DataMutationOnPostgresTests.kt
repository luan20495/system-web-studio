package com.systemwebstudio.data.hardening

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.TestTenant
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataConnectorRegistry
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.DataSourceRef
import com.systemwebstudio.data.datasource.DataSourceService
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.MutationOutcome
import com.systemwebstudio.data.gateway.DefaultDataGateway
import com.systemwebstudio.data.gateway.GatewayContext
import com.systemwebstudio.data.gateway.GatewayMutation
import com.systemwebstudio.data.gateway.GatewayQuery
import com.systemwebstudio.data.org.OrgTestDb
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationDefinition
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.ParamType
import com.systemwebstudio.data.query.QueryParamSpec
import com.systemwebstudio.data.query.SqlQueryDefinition
import com.systemwebstudio.wiring.persistence.JdbcDataSourceRepository
import com.systemwebstudio.wiring.persistence.JdbcIdempotencyStore
import com.systemwebstudio.wiring.persistence.JdbcMutationCatalog
import com.systemwebstudio.wiring.persistence.JdbcQueryCatalog
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The write path of the Data Gateway on REAL PostgreSQL: the gateway of production (`DefaultDataGateway`) over the production JDBC adapters of V28
 * (`JdbcDataSourceRepository`, `JdbcQueryCatalog`, `JdbcMutationCatalog`, `JdbcIdempotencyStore`) and a scripted connector standing in for the external system.
 * What is proved here and nowhere else: the idempotency record is DURABLE and visible to other connections BEFORE the external write starts, an ambiguous outcome
 * is persisted as UNKNOWN and is never retried, a definite refusal frees the key, one key is written exactly once under contention, and the application pool is never
 * held across the external call (a pool of 3 serves 12 simultaneous external calls).
 */
class DataMutationOnPostgresTests {
    private val pool: HikariDataSource = OrgTestDb.pool(3, "data-gw-pool3")
    private val outsider: HikariDataSource = OrgTestDb.pool(2, "data-gw-outsider")        // ANOTHER set of connections: sees only what is committed
    private val jdbc = JdbcTemplate(pool)
    private val seen = JdbcTemplate(outsider)
    private val f = GatewayFixture()
    private val repo = JdbcDataSourceRepository(jdbc)
    private val service = DataSourceService(repo, f.vault, DataConnectorRegistry(listOf(f.connector)), f.limiter, f.audit)
    private val mutations = JdbcMutationCatalog(jdbc)
    private val queries = JdbcQueryCatalog(jdbc)
    private val idem = JdbcIdempotencyStore(jdbc)
    private val gateway = DefaultDataGateway(f.guard, service, queries, mutations, f.mappings, f.discovery, f.cache, idem, f.notifier, f.audit, f.limiter)

    @AfterEach fun close() { pool.close(); outsider.close() }

    private class Rig(val tenantId: UUID, val t: TestTenant, val ds: DataSource, val ctx: GatewayContext)

    private fun rig(): Rig {
        val tenantId = OrgTestDb.newTenant(jdbc = jdbc); val t = TestTenant(tenantId = tenantId); val now = Instant.now()
        val ds = repo.save(DataSource(DataSourceRef(UUID.randomUUID(), tenantId, "fake", emptyMap()), "src-" + UUID.randomUUID().toString().take(8), createdAt = now, updatedAt = now))
        mutations.save(MutationDefinition("create-customer", tenantId, ds.id, MutationKind.CREATE, "customers", listOf(QueryParamSpec("name", ParamType.STRING, true)), listOf("customers"), "customer"))
        queries.save(SqlQueryDefinition("customers", tenantId, ds.id, "SELECT 1", listOf(QueryParamSpec("n", ParamType.NUMBER, false)), 100, 0))
        f.mapping(t)
        return Rig(tenantId, t, ds, f.ctx(t))
    }

    private fun key(s: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private fun mutate(r: Rig, key: String, name: String = "Grace") = gateway.mutate(r.ctx, GatewayMutation(r.ds.id, "create-customer", mapOf("name" to DataJson.toNode(name)), key))
    private fun state(r: Rig, key: String, via: JdbcTemplate = seen) =
        via.queryForList("SELECT state FROM data_idempotency WHERE tenant_id = ? AND data_source_id = ? AND mutation_id = 'create-customer' AND idem_key = ?", String::class.java, r.tenantId, r.ds.id, key).firstOrNull()
    private fun failure(block: () -> Unit): ConnectorFailure { try { block() } catch (e: ConnectorFailure) { return e }; throw AssertionError("expected a ConnectorFailure") }

    @Test
    fun `the reservation is committed and visible to another connection BEFORE the external write starts, and the outcome is stored after it`() {
        val r = rig(); val k = key("durable"); var duringWrite: String? = "not-called"
        f.connector.mutationHook = { _, _, _ -> duringWrite = state(r, k); MutationOutcome(1, DataJson.toNode(mapOf("id" to "rec-7"))) }
        val res = mutate(r, k)
        assertThat(res.replayed).isFalse(); assertThat(res.affected).isEqualTo(1)
        assertThat(duringWrite).describedAs("another connection must see the reservation while the external system is being written").isEqualTo("RESERVED")
        assertThat(state(r, k)).isEqualTo("DONE")
        val row = seen.queryForMap("SELECT affected, output_json, expires_at > now() + interval '6 days' AS long_retention FROM data_idempotency WHERE tenant_id = ? AND idem_key = ?", r.tenantId, k)
        assertThat((row["affected"] as Number).toLong()).isEqualTo(1); assertThat(row["output_json"].toString()).contains("rec-7"); assertThat(row["long_retention"]).isEqualTo(true)
        assertThat(mutate(r, k).replayed).describedAs("a replay answers from the store").isTrue(); assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
    }

    @Test
    fun `an ambiguous failure is stored as UNKNOWN and never retried, a definite refusal frees the key, a different key is unaffected`() {
        val r = rig()
        val ambiguous = key("ambiguous"); f.connector.mutationHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.TIMEOUT, "the change exceeded the time limit") }
        assertThat(failure { mutate(r, ambiguous) }.code).isEqualTo(FailureCodes.TIMEOUT)
        assertThat(state(r, ambiguous)).describedAs("persisted for every node and every connection").isEqualTo("UNKNOWN")
        f.connector.mutationHook = { _, _, _ -> MutationOutcome(1, null) }
        assertThat(failure { mutate(r, ambiguous) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(failure { mutate(r, ambiguous, "Other") }.code).describedAs("other parameters under an unknown key are a conflict, not a second write").isEqualTo(FailureCodes.IDEMPOTENCY_CONFLICT)
        assertThat(f.connector.mutationCalls.get()).describedAs("one attempt reached the external system, none since").isEqualTo(1)

        val rejected = key("rejected"); f.connector.mutationHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.MUTATION_REJECTED, "constraint") }
        assertThat(failure { mutate(r, rejected) }.code).isEqualTo(FailureCodes.MUTATION_REJECTED)
        assertThat(state(r, rejected)).describedAs("a definite refusal freed the key").isNull()
        f.connector.mutationHook = { _, _, _ -> MutationOutcome(1, null) }
        assertThat(mutate(r, rejected).replayed).isFalse()                                                           // the corrected request can use the same key

        assertThat(mutate(r, key("fresh")).replayed).isFalse()
        assertThat(state(r, ambiguous)).describedAs("the unknown record is untouched").isEqualTo("UNKNOWN")
    }

    @Test
    fun `32 simultaneous requests with one key write exactly once, every other caller is told in-progress or replayed, and the record ends DONE`() {
        val r = rig(); val k = key("contended"); val inHook = AtomicInteger()
        f.connector.mutationHook = { _, _, _ -> inHook.incrementAndGet(); Thread.sleep(250); MutationOutcome(1, DataJson.toNode(mapOf("id" to "rec-1"))) }
        val go = CountDownLatch(1); val ex = Executors.newFixedThreadPool(32)
        try {
            val futures = (1..32).map { ex.submit<String> { go.await(); try { if (mutate(r, k).replayed) "replayed" else "ran" } catch (e: ConnectorFailure) { e.code } } }
            go.countDown(); val outcomes = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertThat(outcomes.count { it == "ran" }).describedAs("exactly one writer: $outcomes").isEqualTo(1)
            assertThat(f.connector.mutationCalls.get()).isEqualTo(1); assertThat(inHook.get()).isEqualTo(1)
            assertThat(outcomes.filter { it != "ran" }.toSet()).isSubsetOf("replayed", FailureCodes.IDEMPOTENCY_IN_PROGRESS)
        } finally { ex.shutdownNow() }
        assertThat(state(r, k)).isEqualTo("DONE")
        assertThat(mutate(r, k).replayed).isTrue(); assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
    }

    @Test
    fun `the application pool is not held across the external call - a pool of 3 serves 12 external writes and 12 external reads at the same time`() {
        val r = rig(); val writes = CountDownLatch(12); val reads = CountDownLatch(12); val activeAtBarrier = AtomicInteger(-1)
        val gate = Any()
        f.connector.mutationHook = { _, _, _ ->
            synchronized(gate) { if (writes.count == 1L) activeAtBarrier.set(pool.hikariPoolMXBean.activeConnections); writes.countDown() }          // the LAST writer samples: the other 11 already wait inside the external call
            assertThat(writes.await(30, TimeUnit.SECONDS)).describedAs("all 12 writers are inside the external call together (a held pool connection would cap this at 3)").isTrue()
            MutationOutcome(1, null)
        }
        f.connector.queryHook = { _, _, _ ->
            reads.countDown()
            assertThat(reads.await(30, TimeUnit.SECONDS)).describedAs("all 12 readers are inside the external call together").isTrue()
        }
        val ex = Executors.newFixedThreadPool(12)
        try {
            val w = (1..12).map { i -> ex.submit<Long?> { mutate(r, key("pool-$i"), "n$i").affected } }
            assertThat(w.map { it.get(60, TimeUnit.SECONDS) }).containsOnly(1L)
            val q = (1..12).map { i -> ex.submit<Int> { gateway.runQuery(r.ctx, GatewayQuery(r.ds.id, "customers", mapOf("n" to DataJson.toNode(i)), null, "m-customers", "vm-customers")).data.rows.size } }
            q.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { ex.shutdownNow() }
        assertThat(activeAtBarrier.get()).describedAs("pool connections in use while all 12 writers were inside the external call").isZero()
        assertThat(pool.hikariPoolMXBean.threadsAwaitingConnection).isZero()
        assertThat(seen.queryForObject("SELECT count(*) FROM data_idempotency WHERE tenant_id = ? AND state = 'DONE'", Long::class.java, r.tenantId)).isEqualTo(12)
    }

    /**
     * CHARACTERISATION of a caller rule, not a feature: the reservation is only a fence while it is committed on its own. Production callers (the action runtime and the
     * data runtime controllers) run the gateway OUTSIDE any application transaction, so this holds today; a caller that wrapped `mutate` in its own transaction and rolled
     * it back would lose the reservation while the external write stays. Reported as finding F-1 in docs/parallel/c3/DATA_HARDENING_REPORT.md with the proposed guard.
     */
    @Test
    fun `caller rule - a mutate wrapped in a caller transaction that rolls back loses the reservation but not the external write`() {
        val r = rig(); val k = key("wrapped"); f.connector.mutationHook = { _, _, _ -> MutationOutcome(1, null) }
        val tx = TransactionTemplate(org.springframework.jdbc.datasource.DataSourceTransactionManager(pool))
        runCatching { tx.execute { mutate(r, k); throw IllegalStateException("the caller fails after the write") } }
        assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
        assertThat(state(r, k)).describedAs("rolled back together with the caller's transaction: this is why callers must not wrap the gateway").isNull()
        assertThat(mutate(r, k).replayed).describedAs("and so a retry would write a SECOND time").isFalse(); assertThat(f.connector.mutationCalls.get()).isEqualTo(2)
    }
}
