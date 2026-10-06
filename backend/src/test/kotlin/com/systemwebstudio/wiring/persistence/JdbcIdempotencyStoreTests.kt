package com.systemwebstudio.wiring.persistence

import com.systemwebstudio.data.MutableClock
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.IdempotencyDecision
import com.systemwebstudio.data.gateway.StoredMutation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The durable idempotency store: the same decision table as `InMemoryIdempotencyStore`, plus persistence, retention, expiry and real concurrency. */
class JdbcIdempotencyStoreTests : DataRuntimeJdbcTestBase() {
    private val clock = MutableClock()
    private val ttl24h = 86_400L
    private lateinit var tenant: UUID
    private lateinit var dsId: UUID
    private lateinit var store: JdbcIdempotencyStore

    @BeforeEach
    fun setUp() {
        tenant = newTenant()
        dsId = dataSource(tenant).also { JdbcDataSourceRepository(jdbc).save(it) }.id
        store = JdbcIdempotencyStore(jdbc, clock)
    }

    private val key = "k-" + "a".repeat(41)
    private val mutation = "orders.create"

    private fun begin(fp: String = "fp-1", k: String = key, s: JdbcIdempotencyStore = store) = s.begin(tenant, dsId, mutation, k, fp, ttl24h)

    @Test
    fun `the first call runs, a second one with the same key is in progress, other parameters conflict`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        assertThat(begin()).isEqualTo(IdempotencyDecision.InProgress)
        assertThat(begin("fp-other")).isEqualTo(IdempotencyDecision.Conflict)
    }

    @Test
    fun `a completed mutation is replayed with its counts and result, and a conflict still applies`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        store.complete(tenant, dsId, mutation, key, StoredMutation(1, """{"id":"rec-1"}""", clock.instant()))
        val replay = begin() as IdempotencyDecision.Replay
        assertThat(replay.stored.affected).isEqualTo(1L)
        assertThat(replay.stored.outputJson).isEqualTo("""{"id":"rec-1"}""")
        assertThat(replay.stored.completedAt).isEqualTo(clock.instant())
        assertThat(begin("fp-other")).isEqualTo(IdempotencyDecision.Conflict)
        // a completed mutation is never forgotten by release
        store.release(tenant, dsId, mutation, key)
        assertThat(begin()).isInstanceOf(IdempotencyDecision.Replay::class.java)
    }

    @Test
    fun `a replay survives a new store instance (a restart)`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        store.complete(tenant, dsId, mutation, key, StoredMutation(null, null, clock.instant()))
        val afterRestart = JdbcIdempotencyStore(jdbc, clock)
        val replay = begin(s = afterRestart) as IdempotencyDecision.Replay
        assertThat(replay.stored.affected).isNull()
        assertThat(replay.stored.outputJson).isNull()
    }

    @Test
    fun `a released key runs again`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        store.release(tenant, dsId, mutation, key)
        assertThat(begin("fp-new")).isEqualTo(IdempotencyDecision.Run)
    }

    @Test
    fun `an ambiguous outcome keeps the key reserved and is never handed out again`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        store.markUnknown(tenant, dsId, mutation, key)
        repeat(3) { assertThat(begin()).isEqualTo(IdempotencyDecision.OutcomeUnknown) }
        assertThat(begin("fp-other")).isEqualTo(IdempotencyDecision.Conflict)
        // complete after an unknown (the source answered late) turns it into a replay
        store.complete(tenant, dsId, mutation, key, StoredMutation(2, null, clock.instant()))
        assertThat(begin()).isInstanceOf(IdempotencyDecision.Replay::class.java)
    }

    @Test
    fun `markUnknown does not touch a completed key`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        store.complete(tenant, dsId, mutation, key, StoredMutation(1, null, clock.instant()))
        store.markUnknown(tenant, dsId, mutation, key)
        assertThat(begin()).isInstanceOf(IdempotencyDecision.Replay::class.java)
    }

    @Test
    fun `a holder that vanished is not retried - after the lease the key is unknown`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        clock.advanceSeconds(JdbcIdempotencyStore.DEFAULT_LEASE_SECONDS - 1)
        assertThat(begin()).isEqualTo(IdempotencyDecision.InProgress)
        clock.advanceSeconds(2)
        assertThat(begin()).isEqualTo(IdempotencyDecision.OutcomeUnknown)
        assertThat(state()).isEqualTo("UNKNOWN")
        assertThat(begin()).isEqualTo(IdempotencyDecision.OutcomeUnknown)
    }

    @Test
    fun `a key lives for the configured retention even when the caller asks for 24 hours, then it can run again`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        store.complete(tenant, dsId, mutation, key, StoredMutation(1, null, clock.instant()))
        val days = jdbc.queryForObject(
            "SELECT extract(epoch FROM (expires_at - created_at)) / 86400 FROM data_idempotency WHERE tenant_id = ? AND idem_key = ?", Double::class.java, tenant, key
        )!!
        assertThat(days).isBetween(29.99, 30.01)
        clock.advanceSeconds(Duration.ofDays(29).seconds)
        assertThat(begin()).isInstanceOf(IdempotencyDecision.Replay::class.java)
        clock.advanceSeconds(Duration.ofDays(2).seconds)
        assertThat(begin("fp-after-expiry")).isEqualTo(IdempotencyDecision.Run)
    }

    @Test
    fun `retention is configurable, a longer request wins, and anything under 7 days is refused`() {
        val long = JdbcIdempotencyStore(jdbc, clock, minRetention = Duration.ofDays(7))
        assertThat(long.begin(tenant, dsId, mutation, "seven-day-key-1", "fp", Duration.ofDays(60).seconds)).isEqualTo(IdempotencyDecision.Run)
        val days = jdbc.queryForObject("SELECT extract(epoch FROM (expires_at - created_at)) / 86400 FROM data_idempotency WHERE idem_key = 'seven-day-key-1'", Double::class.java)!!
        assertThat(days).isBetween(59.99, 60.01)
        assertThat(long.begin(tenant, dsId, mutation, "seven-day-key-2", "fp", 60)).isEqualTo(IdempotencyDecision.Run)
        val floor = jdbc.queryForObject("SELECT extract(epoch FROM (expires_at - created_at)) / 86400 FROM data_idempotency WHERE idem_key = 'seven-day-key-2'", Double::class.java)!!
        assertThat(floor).isBetween(6.99, 7.01)
        assertThatThrownBy { JdbcIdempotencyStore(jdbc, clock, minRetention = Duration.ofDays(6)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { JdbcIdempotencyStore(jdbc, clock, minRetention = Duration.ofHours(24)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `keys are scoped by tenant, data source and mutation`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        val otherTenant = newTenant(); val otherSource = dataSource(otherTenant).also { JdbcDataSourceRepository(jdbc).save(it) }
        val secondSource = dataSource(tenant).also { JdbcDataSourceRepository(jdbc).save(it) }
        assertThat(store.begin(otherTenant, otherSource.id, mutation, key, "fp-1", ttl24h)).isEqualTo(IdempotencyDecision.Run)
        assertThat(store.begin(tenant, secondSource.id, mutation, key, "fp-1", ttl24h)).isEqualTo(IdempotencyDecision.Run)
        assertThat(store.begin(tenant, dsId, "orders.update", key, "fp-1", ttl24h)).isEqualTo(IdempotencyDecision.Run)
        // a tenant cannot reserve a key on a data source that is not its own: the composite foreign key refuses it
        val failed = try { store.begin(otherTenant, dsId, mutation, "foreign-key-123", "fp", ttl24h); false } catch (e: org.springframework.dao.DataIntegrityViolationException) { true }
        assertThat(failed).isTrue()
    }

    @Test
    fun `a key that is not the derived shape is refused before the database is touched`() {
        for (bad in listOf("short", "has space in it 12345", "a".repeat(129), "semi;colon-12345")) {
            assertThat(failureCode { store.begin(tenant, dsId, mutation, bad, "fp", ttl24h) }).describedAs(bad).isEqualTo(FailureCodes.INVALID_PARAMS)
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM data_idempotency WHERE tenant_id = ?", Long::class.java, tenant)).isZero()
    }

    @Test
    fun `an oversized result is dropped but the completion and count are kept`() {
        assertThat(begin()).isEqualTo(IdempotencyDecision.Run)
        store.complete(tenant, dsId, mutation, key, StoredMutation(3, "x".repeat(1_048_577), clock.instant()))
        val replay = begin() as IdempotencyDecision.Replay
        assertThat(replay.stored.affected).isEqualTo(3L)
        assertThat(replay.stored.outputJson).isNull()
    }

    @Test
    fun `only an expired row is purged, and a purge is bounded`() {
        store.purgeExpired(clock.instant().plus(Duration.ofDays(365)), 100_000)          // start from an empty table: purge is global by design
        for (n in 1..3) assertThat(begin("fp", "purge-key-000$n")).isEqualTo(IdempotencyDecision.Run)
        assertThat(store.purgeExpired(clock.instant())).isZero()
        assertThat(store.purgeExpired(clock.instant().plus(Duration.ofDays(29)))).isZero()
        assertThat(rowCount()).isEqualTo(3L)
        assertThat(store.purgeExpired(clock.instant().plus(Duration.ofDays(31)), 2)).isEqualTo(2)
        assertThat(rowCount()).isEqualTo(1L)
        assertThat(store.purgeExpired(clock.instant().plus(Duration.ofDays(31)))).isEqualTo(1)
        assertThat(rowCount()).isZero()
    }

    @Test
    fun `only the derived key, a fingerprint and counts are stored`() {
        val columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'data_idempotency'", String::class.java)
        assertThat(columns).noneMatch { it.contains("param") || it.contains("request") || it.contains("client") || it.contains("secret") }
    }

    @Test
    fun `concurrent callers with one key - exactly one runs`() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads); val go = CountDownLatch(1)
        try {
            val futures = (1..threads).map {
                pool.submit<IdempotencyDecision> { ready.countDown(); go.await(); store.begin(tenant, dsId, mutation, "race-key-000001", "fp", ttl24h) }
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue()
            go.countDown()
            val decisions = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertThat(decisions.count { it == IdempotencyDecision.Run }).isEqualTo(1)
            assertThat(decisions.count { it == IdempotencyDecision.InProgress }).isEqualTo(threads - 1)
        } finally { pool.shutdownNow() }
        assertThat(rowCount()).isEqualTo(1L)
    }

    private fun state(): String = jdbc.queryForObject("SELECT state FROM data_idempotency WHERE tenant_id = ? AND idem_key = ?", String::class.java, tenant, key)!!
    private fun rowCount(): Long = jdbc.queryForObject("SELECT count(*) FROM data_idempotency WHERE tenant_id = ?", Long::class.java, tenant)!!
}
