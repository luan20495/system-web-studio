package com.systemwebstudio.data.cache

import com.systemwebstudio.data.MutableClock
import com.systemwebstudio.data.putNow
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.testkit.RedisServerDouble
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Cache contract: tenant separation, generation invalidation, outage behaviour, bounds, canonical keys — on both backends. */
class CacheTests {
    private val clock = MutableClock()
    private val backend = InMemoryCacheBackend(nowMs = { clock.millis() })
    private val cache = QueryCache(backend, clock)
    private val tenantA = UUID.randomUUID(); private val tenantB = UUID.randomUUID(); private val ds = UUID.randomUUID()

    private fun parts(t: UUID = tenantA, d: UUID = ds, q: String = "customers", params: String = "{}", dsVersion: Long = 1, qVersion: Long = 1, mapping: String? = "m", mappingVersion: Long = 1, vm: String? = "vm", page: String? = null) =
        CacheKeyParts(CacheScope(t, d, q), dsVersion, qVersion, mapping, mappingVersion, vm, params, page)

    @Test fun `tenants never share an entry even with identical ids and parameters`() {
        cache.putNow(parts(tenantA), "A-DATA", 60)
        assertThat(cache.get(parts(tenantA))).isEqualTo("A-DATA")
        assertThat(cache.get(parts(tenantB))).isNull()
        cache.putNow(parts(tenantB), "B-DATA", 60)
        assertThat(cache.get(parts(tenantA))).isEqualTo("A-DATA")
        assertThat(cache.get(parts(tenantB))).isEqualTo("B-DATA")
        assertThat(backend.keys().filter { it.contains(tenantA.toString()) }.none { it.contains(tenantB.toString()) }).isTrue()
        assertThat(backend.keys().all { it.startsWith("xw:data:v1:") }).isTrue()
    }

    @Test fun `a forged entry whose envelope names another tenant is rejected and never returned`() {
        cache.putNow(parts(tenantB), "B-SECRET", 60)
        val bKey = backend.keys().single { it.contains(":t:$tenantB:") }
        val bValue = backend.get(bKey)!!
        // plant B's value under the key tenant A would compute (a bug or an attacker with write access to the store)
        val aKey = cache.keyOf(parts(tenantA))
        backend.put(aKey, bValue, 60)
        assertThat(cache.get(parts(tenantA))).isNull()
        assertThat(cache.stats().errors).isGreaterThan(0)
        backend.put(aKey, "not json at all", 60)
        assertThat(cache.get(parts(tenantA))).isNull()
        backend.put(aKey, """{"t":"$tenantA","d":"$ds"}""", 60)                       // missing fields
        assertThat(cache.get(parts(tenantA))).isNull()
    }

    @Test fun `every part of the key matters`() {
        cache.putNow(parts(), "base", 60)
        assertThat(cache.get(parts())).isEqualTo("base")
        val variants = listOf(parts(d = UUID.randomUUID()), parts(q = "orders"), parts(params = """{"a":1}"""), parts(dsVersion = 2), parts(qVersion = 2), parts(mapping = "m2"),
            parts(mappingVersion = 2), parts(vm = "vm2"), parts(vm = null), parts(page = "10/0"))
        variants.forEach { assertThat(cache.get(it)).isNull() }
    }

    @Test fun `ttl is honoured per entry and zero is never stored`() {
        assertThat(cache.putNow(parts(), "x", 0)).isFalse()
        assertThat(cache.get(parts())).isNull()
        cache.putNow(parts(), "x", 10)
        clock.advanceSeconds(9); assertThat(cache.get(parts())).isEqualTo("x")
        clock.advanceSeconds(2); assertThat(cache.get(parts())).isNull()
    }

    @Test fun `invalidating a data source drops all its queries but not other data sources or tenants`() {
        val other = UUID.randomUUID()
        cache.putNow(parts(q = "a"), "a", 60); cache.putNow(parts(q = "b"), "b", 60); cache.putNow(parts(d = other), "other-ds", 60); cache.putNow(parts(tenantB), "other-tenant", 60)
        assertThat(cache.invalidateDataSource(tenantA, ds)).isTrue()
        assertThat(cache.get(parts(q = "a"))).isNull(); assertThat(cache.get(parts(q = "b"))).isNull()
        assertThat(cache.get(parts(d = other))).isEqualTo("other-ds")
        assertThat(cache.get(parts(tenantB))).isEqualTo("other-tenant")
        cache.putNow(parts(q = "a"), "a2", 60)
        assertThat(cache.get(parts(q = "a"))).isEqualTo("a2")                           // new generation is usable
    }

    @Test fun `invalidating one query leaves its siblings`() {
        cache.putNow(parts(q = "a"), "a", 60); cache.putNow(parts(q = "a", params = """{"x":1}"""), "a-x", 60); cache.putNow(parts(q = "b"), "b", 60)
        cache.invalidateQuery(tenantA, ds, "a")
        assertThat(cache.get(parts(q = "a"))).isNull(); assertThat(cache.get(parts(q = "a", params = """{"x":1}"""))).isNull()
        assertThat(cache.get(parts(q = "b"))).isEqualTo("b")
    }

    @Test fun `a failed invalidation bypasses the scope so nothing stale is served`() {
        cache.putNow(parts(), "stale", 600)
        backend.failing = true
        assertThat(cache.invalidateDataSource(tenantA, ds)).isFalse()
        backend.failing = false                                                         // backend is back, but we know the entry may be stale
        assertThat(cache.get(parts())).isNull()
        assertThat(cache.putNow(parts(), "fresh-but-bypassed", 60)).isFalse()
        assertThat(cache.invalidateDataSource(tenantA, ds)).isTrue()                    // a successful invalidation ends the bypass
        assertThat(cache.get(parts())).isNull()
        assertThat(cache.putNow(parts(), "fresh", 60)).isTrue()
        assertThat(cache.get(parts())).isEqualTo("fresh")
        assertThat(cache.get(parts(tenantB))).isNull()
    }

    @Test fun `bypass is limited to the failing scope and expires`() {
        val other = UUID.randomUUID()
        cache.putNow(parts(d = other), "other", 60)
        backend.failing = true; cache.invalidateDataSource(tenantA, ds); backend.failing = false
        assertThat(cache.get(parts(d = other))).isEqualTo("other")
        clock.advanceSeconds(QueryCache.MAX_TTL_SECONDS + 1L)
        assertThat(cache.putNow(parts(), "later", 60)).isTrue()                           // every pre-failure entry has expired by now
    }

    @Test fun `a backend outage on read or write is a miss never an error`() {
        backend.failing = true
        assertThat(cache.get(parts())).isNull()
        assertThat(cache.putNow(parts(), "x", 60)).isFalse()
        backend.failing = false
        assertThat(cache.get(parts())).isNull()
    }

    @Test fun `oversized values are not stored`() {
        assertThat(cache.putNow(parts(), "x".repeat(QueryCache.MAX_VALUE_BYTES + 1), 60)).isFalse()
        assertThat(cache.stats().skippedTooLarge).isEqualTo(1L)
        assertThat(cache.get(parts())).isNull()
    }

    @Test fun `canonical parameters ignore order and number formatting but not values`() {
        fun c(vararg kv: Pair<String, Any?>) = QueryCache.canonical(kv.associate { it.first to DataJson.toNode(it.second) })
        assertThat(c("a" to 1, "b" to "x")).isEqualTo(c("b" to "x", "a" to 1))
        assertThat(c("a" to 1)).isEqualTo(c("a" to 1.0))
        assertThat(c("a" to 1)).isNotEqualTo(c("a" to "1"))
        assertThat(c("a" to 1)).isNotEqualTo(c("a" to 2))
        assertThat(c("a" to null)).isNotEqualTo(c("a" to "null"))
        assertThat(QueryCache.canonicalBound(mapOf("n" to java.math.BigDecimal("1.50"), "s" to "x"))).isEqualTo(QueryCache.canonicalBound(mapOf("s" to "x", "n" to java.math.BigDecimal("1.5"))))
    }

    @Test fun `the in memory backend is bounded in entries and bytes`() {
        val small = InMemoryCacheBackend(maxEntries = 3, nowMs = { clock.millis() })
        (1..10).forEach { small.put("k$it", "v", 60) }
        assertThat(small.entries).isEqualTo(3)
        assertThat(small.get("k10")).isEqualTo("v"); assertThat(small.get("k1")).isNull()
        val bytes = InMemoryCacheBackend(maxBytes = 1_000, nowMs = { clock.millis() })
        (1..50).forEach { bytes.put("k$it", "x".repeat(100), 60) }
        assertThat(bytes.entries).isLessThan(10)
    }

    @Test fun `the redis adapter obeys the same contract`() {
        val redis = RedisServerDouble { clock.millis() }
        val c = QueryCache(RedisCacheBackend(redis), clock)
        c.putNow(parts(tenantA), "A", 30); c.putNow(parts(tenantB), "B", 30)
        assertThat(c.get(parts(tenantA))).isEqualTo("A"); assertThat(c.get(parts(tenantB))).isEqualTo("B")
        assertThat(redis.keys("*").all { it.startsWith("xw:data:v1:") }).isTrue()
        c.invalidateDataSource(tenantA, ds)
        assertThat(c.get(parts(tenantA))).isNull(); assertThat(c.get(parts(tenantB))).isEqualTo("B")
        clock.advanceSeconds(31)
        assertThat(c.get(parts(tenantB))).isNull()
        redis.failing = true
        assertThat(c.get(parts(tenantA))).isNull()
        assertThat(c.invalidateDataSource(tenantA, ds)).isFalse()
        redis.failing = false
        assertThat(c.putNow(parts(tenantA), "x", 30)).isFalse()                          // bypassed after the failed invalidation
    }
}
