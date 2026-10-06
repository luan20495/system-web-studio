package com.systemwebstudio.data.cache

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.MutableClock
import com.systemwebstudio.data.putNow
import com.systemwebstudio.data.gateway.CacheStatus
import com.systemwebstudio.data.gateway.GatewayQuery
import com.systemwebstudio.data.query.DataJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The stale-put race: a reader misses, starts computing from the data as it is, the data changes and the cache is invalidated, and only then does the
 * reader finish and write. Recomputing the key at write time (the old behaviour) stores the OLD answer under the NEW generation, where it is served as
 * current. The ticket protocol captures the key before the data is read, so the late write lands on a dead key (or is refused).
 */
class CacheRaceTests {
    private val clock = MutableClock()
    private val backend = InMemoryCacheBackend(nowMs = { clock.millis() })
    private val cache = QueryCache(backend, clock)
    private val tenantA = UUID.randomUUID(); private val tenantB = UUID.randomUUID(); private val ds = UUID.randomUUID()
    private fun parts(t: UUID = tenantA, q: String = "customers", params: String = "{}") = CacheKeyParts(CacheScope(t, ds, q), 1, 1, "m", 1, "vm", params, null)
    private val pool = Executors.newFixedThreadPool(8)

    private fun <T> async(block: () -> T) = pool.submit<T> { block() }

    @Test fun `an answer computed before an invalidation cannot be stored after it - query level`() {
        val looked = CountDownLatch(1); val invalidated = CountDownLatch(1)
        val stored = AtomicReference<Boolean>()
        val reader = async {
            val ticket = cache.lookup(parts()).ticket!!                      // miss: reader starts working from the OLD data
            looked.countDown(); invalidated.await(5, TimeUnit.SECONDS)
            stored.set(cache.put(ticket, "OLD", 60))                          // …and finishes only after the data changed and the cache was invalidated
        }
        looked.await(5, TimeUnit.SECONDS)
        assertThat(cache.invalidateQuery(tenantA, ds, "customers")).isTrue()
        invalidated.countDown(); reader.get(5, TimeUnit.SECONDS)

        assertThat(stored.get()).isFalse()                                   // refused outright
        assertThat(cache.get(parts())).isNull()                              // and nothing is served from the new generation
        assertThat(cache.stats().staleWritesDropped).isEqualTo(1L)
        assertThat(cache.putNow(parts(), "NEW", 60)).isTrue()                // a reader that starts after the invalidation caches normally
        assertThat(cache.get(parts())).isEqualTo("NEW")
    }

    @Test fun `an answer computed before an invalidation cannot be stored after it - data source level`() {
        val ticket = cache.lookup(parts()).ticket!!
        cache.invalidateDataSource(tenantA, ds)
        assertThat(cache.put(ticket, "OLD", 60)).isFalse()
        assertThat(cache.get(parts())).isNull()
    }

    @Test fun `even a write that slipped past the compare lands on a key nobody reads`() {
        // the compare in put() is not atomic with the write; what makes the pair safe is the captured key. Prove that on its own by writing the
        // OLD key directly, as a put() that lost the race between its compare and its write would have done.
        val ticket = cache.lookup(parts()).ticket!!
        val oldKey = cache.keyOf(parts())
        cache.invalidateQuery(tenantA, ds, "customers")
        backend.put(oldKey, """{"t":"$tenantA","d":"$ds","q":"customers","f":"x","p":"OLD"}""", 60)
        assertThat(cache.get(parts())).isNull()                              // the live key embeds the new generation
        assertThat(cache.keyOf(parts())).isNotEqualTo(oldKey)
        assertThat(ticket.toString()).doesNotContain(tenantA.toString())
    }

    @Test fun `an unrelated invalidation does not discard a good write`() {
        val ticket = cache.lookup(parts()).ticket!!
        cache.invalidateQuery(tenantA, ds, "orders")                         // another query
        cache.invalidateDataSource(tenantB, UUID.randomUUID())              // another tenant
        assertThat(cache.put(ticket, "GOOD", 60)).isTrue()
        assertThat(cache.get(parts())).isEqualTo("GOOD")
    }

    @Test fun `a ticket is bound to its tenant - it cannot fill another tenant's entry`() {
        val a = cache.lookup(parts(tenantA)).ticket!!
        val b = cache.lookup(parts(tenantB)).ticket!!
        assertThat(cache.put(a, "A-ONLY", 60)).isTrue()
        assertThat(cache.get(parts(tenantB))).isNull()
        assertThat(cache.put(b, "B-ONLY", 60)).isTrue()
        assertThat(cache.get(parts(tenantA))).isEqualTo("A-ONLY"); assertThat(cache.get(parts(tenantB))).isEqualTo("B-ONLY")
        // invalidating tenant A neither discards nor serves anything of tenant B
        val b2 = cache.lookup(parts(tenantB, params = """{"x":1}""")).ticket!!
        cache.invalidateDataSource(tenantA, ds)
        assertThat(cache.put(b2, "B2", 60)).isTrue()
        assertThat(cache.get(parts(tenantB, params = """{"x":1}"""))).isEqualTo("B2")
        assertThat(cache.get(parts(tenantA))).isNull()
    }

    @Test fun `the stored envelope carries the request fingerprint - a value copied under another request's key is rejected`() {
        cache.putNow(parts(params = """{"status":"A"}"""), "ROWS-FOR-A", 60)
        val keyA = cache.keyOf(parts(params = """{"status":"A"}"""))
        val keyC = cache.keyOf(parts(params = """{"status":"C"}"""))
        backend.put(keyC, backend.get(keyA)!!, 60)                           // same tenant, data source and query: only the parameters differ
        assertThat(cache.get(parts(params = """{"status":"C"}"""))).isNull()
        assertThat(cache.get(parts(params = """{"status":"A"}"""))).isEqualTo("ROWS-FOR-A")
        assertThat(cache.stats().errors).isGreaterThan(0)
    }

    @Test fun `a failed invalidation between lookup and put also refuses the write`() {
        val ticket = cache.lookup(parts()).ticket!!
        backend.failing = true; assertThat(cache.invalidateQuery(tenantA, ds, "customers")).isFalse(); backend.failing = false
        assertThat(cache.put(ticket, "OLD", 60)).isFalse()                    // bypassed: we know we could not invalidate
        assertThat(cache.get(parts())).isNull()
    }

    @Test fun `no ticket while the backend is down - nothing to write and no error`() {
        backend.failing = true
        val found = cache.lookup(parts())
        assertThat(found.payload).isNull(); assertThat(found.ticket).isNull()
    }

    @Test fun `stress - writers change the data and invalidate while readers fill, and the cache never ends up holding an old answer`() {
        val data = AtomicInteger(0)                                           // the "database": one value, bumped by every write
        val rounds = 300; val readers = 6
        val start = CountDownLatch(1); val failure = AtomicReference<Throwable>()
        val writerDone = AtomicBoolean(false)
        val futures = ArrayList<java.util.concurrent.Future<*>>()
        repeat(readers) {
            futures += async {
                try {
                    start.await()
                    while (!writerDone.get()) {
                        val found = cache.lookup(parts())
                        if (found.payload == null) {
                            val ticket = found.ticket ?: continue
                            val read = data.get()                             // the data is read AFTER the key was captured
                            Thread.yield()
                            cache.put(ticket, read.toString(), 60)
                        }
                    }
                } catch (t: Throwable) { failure.compareAndSet(null, t) }
            }
        }
        futures += async {
            try {
                start.await()
                repeat(rounds) { data.incrementAndGet(); cache.invalidateQuery(tenantA, ds, "customers"); if (it % 7 == 0) Thread.yield() }   // change first, invalidate second (as a mutation does)
            } catch (t: Throwable) { failure.compareAndSet(null, t) } finally { writerDone.set(true) }
        }
        start.countDown()
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        assertThat(failure.get()).isNull()
        // after the last change and its invalidation, whatever is cached must be the final value — or nothing
        val cached = cache.get(parts())
        assertThat(cached == null || cached == data.get().toString()).isTrue()
        // and it can never have gone backwards afterwards either
        cache.putNow(parts(), data.get().toString(), 60)
        assertThat(cache.get(parts())).isEqualTo(rounds.toString())
    }

    @Test fun `gateway level - a query that is running while a mutation invalidates does not re-poison the cache`() {
        val f = GatewayFixture()
        val t = f.tenant(); val ds = f.register(t, listOf(mapOf("id" to 1, "name" to "OLD-NAME", "status" to "A"))); f.query(t, ds, ttl = 300); f.mapping(t); f.mutation(t, ds)
        val ctx = f.ctx(t)
        fun query() = GatewayQuery(ds.id, "customers", emptyMap(), null, "m-customers", "vm-customers")

        val inConnector = CountDownLatch(1); val release = CountDownLatch(1)
        val firstCall = AtomicBoolean(true)
        f.connector.queryHook = { _, _, _ ->
            if (firstCall.getAndSet(false)) { inConnector.countDown(); release.await(5, TimeUnit.SECONDS) }   // the slow reader holds the OLD rows
        }
        val slow = async { f.gateway.runQuery(ctx, query()) }
        inConnector.await(5, TimeUnit.SECONDS)

        // meanwhile the source changes and the cache is invalidated, exactly as a committed mutation does
        f.connector.rowsFor[ds.id] = listOf(mapOf("id" to 1, "name" to "NEW-NAME", "status" to "A"))
        f.gateway.refreshCache(ctx, ds.id, "customers")
        release.countDown()
        slow.get(5, TimeUnit.SECONDS)                                          // the slow reader answers its own caller with what it read

        val next = f.gateway.runQuery(ctx, query())
        assertThat(next.cache).isEqualTo(CacheStatus.MISS)                       // the stale answer was not cached under the new generation
        assertThat(DataJson.text(next.data.rows.first()["displayName"]!!)).isEqualTo("NEW-NAME")
        assertThat(f.gateway.runQuery(ctx, query()).cache).isEqualTo(CacheStatus.HIT)
    }
}
