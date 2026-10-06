package com.systemwebstudio.data.cache

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Minimal string key/value port under the query cache. Two implementations: [RedisCacheBackend] (production, shared by all nodes) and
 * [InMemoryCacheBackend] (tests, single-node fallback). Every method may throw; [QueryCache] treats a throw as "cache unavailable",
 * never as a failure of the request being served.
 */
interface CacheBackend {
    fun get(key: String): String?
    fun put(key: String, value: String, ttlSeconds: Int)
    /** atomically adds one to a counter (created at 1); [ttlSeconds] refreshes the counter's lifetime */
    fun increment(key: String, ttlSeconds: Int): Long
    fun counter(key: String): Long
}

/** Production backend on the platform's existing Redis (`spring-boot-starter-data-redis`). All keys carry the `xw:data:` prefix applied by [QueryCache]. */
class RedisCacheBackend(private val redis: StringRedisTemplate) : CacheBackend {
    override fun get(key: String): String? = redis.opsForValue().get(key)
    override fun put(key: String, value: String, ttlSeconds: Int) { redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds.toLong())) }
    override fun increment(key: String, ttlSeconds: Int): Long {
        val n = redis.opsForValue().increment(key) ?: 1L
        redis.expire(key, Duration.ofSeconds(ttlSeconds.toLong()))
        return n
    }
    override fun counter(key: String): Long = redis.opsForValue().get(key)?.toLongOrNull() ?: 0L
}

/** Bounded (entries and bytes) LRU with TTL on an injectable clock. Thread-safe. */
class InMemoryCacheBackend(
    private val maxEntries: Int = 5_000, private val maxBytes: Long = 64L * 1024 * 1024, private val nowMs: () -> Long = System::currentTimeMillis
) : CacheBackend {
    private class Entry(val value: String, val expiresAt: Long)
    private val lock = ReentrantLock()
    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {}
    private var bytes = 0L
    @Volatile var failing = false                       // test hook: simulates an outage

    private fun check() { if (failing) throw IllegalStateException("cache backend unavailable") }
    private fun size(k: String, e: Entry) = (k.length + e.value.length) * 2L

    override fun get(key: String): String? = lock.withLock {
        check()
        val e = map[key] ?: return null
        if (e.expiresAt <= nowMs()) { remove(key); return null }
        e.value
    }

    override fun put(key: String, value: String, ttlSeconds: Int) = lock.withLock {
        check()
        remove(key)
        val e = Entry(value, nowMs() + ttlSeconds * 1000L)
        map[key] = e; bytes += size(key, e)
        val it = map.entries.iterator()
        while ((map.size > maxEntries || bytes > maxBytes) && it.hasNext()) { val old = it.next(); bytes -= size(old.key, old.value); it.remove() }
    }

    override fun increment(key: String, ttlSeconds: Int): Long = lock.withLock {
        check()
        val n = (get(key)?.toLongOrNull() ?: 0L) + 1
        put(key, n.toString(), ttlSeconds)
        n
    }

    override fun counter(key: String): Long = lock.withLock { check(); get(key)?.toLongOrNull() ?: 0L }

    private fun remove(key: String) { map.remove(key)?.let { bytes -= size(key, it) } }
    val entries: Int get() = lock.withLock { map.size }
    fun keys(): Set<String> = lock.withLock { map.keys.toSet() }
}
