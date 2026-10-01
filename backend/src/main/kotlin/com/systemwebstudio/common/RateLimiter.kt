package com.systemwebstudio.common

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/** Redis fixed-window counters. Shared across instances, survives restarts. */
@Component
class RateLimiter(private val redis: StringRedisTemplate) {
    data class Decision(val allowed: Boolean, val count: Long, val retryAfterSeconds: Long)

    /** Count one event; returns whether it is still within [limit] per [windowSeconds]. */
    fun hit(key: String, limit: Long, windowSeconds: Long): Decision {
        val k = "rl:$key"
        val n = redis.opsForValue().increment(k) ?: 1L
        var ttl = redis.getExpire(k)
        if (n == 1L || ttl < 0) {
            redis.expire(k, Duration.ofSeconds(windowSeconds))
            ttl = windowSeconds
        }
        return Decision(n <= limit, n, ttl.coerceAtLeast(1))
    }

    /** Current state without counting. */
    fun peek(key: String, limit: Long): Decision {
        val k = "rl:$key"
        val n = redis.opsForValue().get(k)?.toLongOrNull() ?: 0L
        return Decision(n < limit, n, redis.getExpire(k).coerceAtLeast(1))
    }

    fun reset(key: String) { redis.delete("rl:$key") }

    /** Throws 429 when [key] exceeds the budget. */
    fun require(key: String, limit: Long, windowSeconds: Long, what: String) {
        val d = hit(key, limit, windowSeconds)
        if (!d.allowed) throw ApiException.tooManyRequests("Too many $what requests; retry later.", d.retryAfterSeconds)
    }
}
