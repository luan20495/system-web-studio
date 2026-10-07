package com.systemwebstudio.data.testkit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * A stand-in for the Redis *server* behind the real [StringRedisTemplate], for unit tests that must not need a container.
 *
 * It is a real `StringRedisTemplate` subclass, so production code under test (`RedisCacheBackend`, `RedisWebhookReplayGuard`) is handed exactly the
 * type it takes in production and exercises the same calls it makes there: `opsForValue().get/set(key, value, Duration)/increment/setIfAbsent`,
 * `expire(key, Duration)`, `delete(key)`, `keys(pattern)`. The server behaviour that matters to those callers is reproduced faithfully:
 * per-key expiry on the injectable clock ([nowMs]), `increment` keeping an existing expiry, `SET NX` semantics, glob `keys`, and an outage switch
 * ([failing]) under which every command fails the way an unreachable server does. Any `ValueOperations` command the double does not model fails loudly,
 * so a production change to a new command cannot silently pass against it.
 */
class RedisServerDouble(private val nowMs: () -> Long = { System.currentTimeMillis() }) : StringRedisTemplate() {
    /** While true every command throws, as a dropped connection would. */
    @Volatile var failing: Boolean = false

    private class Entry(var value: String, var expiresAtMs: Long?)
    private val data = ConcurrentHashMap<String, Entry>()

    private fun check() { if (failing) throw IllegalStateException("redis down") }

    /** The live entry for [key], dropping it first when its expiry has passed (what the server does on access). */
    private fun live(key: String): Entry? {
        val e = data[key] ?: return null
        val exp = e.expiresAtMs
        if (exp != null && exp <= nowMs()) { data.remove(key, e); return null }
        return e
    }

    @Synchronized private fun getValue(key: String): String? { check(); return live(key)?.value }
    @Synchronized private fun setValue(key: String, value: String, ttl: Duration?) { check(); data[key] = Entry(value, ttl?.let { nowMs() + it.toMillis() }) }
    @Synchronized private fun setNx(key: String, value: String, ttl: Duration?): Boolean {
        check(); if (live(key) != null) return false
        data[key] = Entry(value, ttl?.let { nowMs() + it.toMillis() }); return true
    }
    @Synchronized private fun incr(key: String): Long {
        check(); val e = live(key)
        val next = (e?.value?.toLongOrNull() ?: 0L) + 1
        data[key] = Entry(next.toString(), e?.expiresAtMs)   // INCR keeps an existing TTL
        return next
    }

    private val ops: ValueOperations<String, String> = Proxy.newProxyInstance(
        ValueOperations::class.java.classLoader, arrayOf(ValueOperations::class.java),
        InvocationHandler { proxy, m, a ->
            val args = a ?: emptyArray()
            when (m.name) {
                "get" -> if (args.size == 1) getValue(args[0] as String) else unsupported(m.toString())
                "set" -> if (args.size == 3 && args[2] is Duration) { setValue(args[0] as String, args[1] as String, args[2] as Duration); null } else unsupported(m.toString())
                "setIfAbsent" -> if (args.size == 3 && args[2] is Duration) setNx(args[0] as String, args[1] as String, args[2] as Duration) else if (args.size == 2) setNx(args[0] as String, args[1] as String, null) else unsupported(m.toString())
                "increment" -> if (args.size == 1) incr(args[0] as String) else unsupported(m.toString())
                "toString" -> "RedisServerDouble.ValueOperations"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args[0]
                else -> unsupported(m.toString())
            }
        }) as ValueOperations<String, String>

    private fun unsupported(what: String): Nothing = throw UnsupportedOperationException("RedisServerDouble does not model $what")

    override fun opsForValue(): ValueOperations<String, String> = ops

    @Synchronized override fun expire(key: String, timeout: Duration): Boolean {
        check(); val e = live(key) ?: return false
        e.expiresAtMs = nowMs() + timeout.toMillis(); return true
    }

    @Synchronized override fun delete(key: String): Boolean { check(); return data.remove(key) != null }

    @Synchronized override fun keys(pattern: String): Set<String> {
        check()
        val glob = Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) })
        return data.keys.filter { live(it) != null && glob.matches(it) }.toSet()
    }
}
