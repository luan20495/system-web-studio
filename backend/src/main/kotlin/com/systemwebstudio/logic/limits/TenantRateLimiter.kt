package com.systemwebstudio.logic.limits

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Per-tenant rate limits (`docs/contracts/v2/action-workflow.md` §6). A single global limit lets one noisy tenant starve every other tenant, so
 * every limit here is **per tenant and per scope**; there is deliberately no global-only variant.
 *
 * `logic.*` imports only base ports, so the limiter is a port ([TenantRateLimiter]) with an in-memory reference implementation. That
 * implementation is per node: with N nodes a tenant can use up to N × its limit. A cluster-wide limit is a C0 adapter (e.g. Redis token bucket
 * behind this same interface) — it needs no change in `logic.*`.
 */
enum class RateScope {
    /** One `ActionRuntime.execute` (also each action a workflow step or a chain runs). */
    ACTION_EXECUTE,
    /** One `WorkflowRuntime.start` / START_WORKFLOW (a scheduled fire does not count here, it has its own scope). */
    WORKFLOW_START,
    /** One schedule fire handed to the workflow runtime by the scheduler tick. */
    SCHEDULER_ENQUEUE
}

/** Token bucket: up to [capacity] immediate calls, refilled at [refillPerSecond]. */
data class RateLimit(val capacity: Int, val refillPerSecond: Double) {
    init {
        require(capacity >= 1) { "capacity must be at least 1" }
        require(refillPerSecond > 0.0 && refillPerSecond.isFinite()) { "refill must be positive" }
    }
}

/** Platform defaults plus per-tenant overrides (plan tiers). Unknown tenant ⇒ defaults. */
class TenantRateLimits(
    private val defaults: Map<RateScope, RateLimit> = DEFAULTS,
    private val overrides: Map<UUID, Map<RateScope, RateLimit>> = emptyMap()
) {
    init { require(RateScope.entries.all { it in defaults }) { "a default is needed for every scope" } }

    fun limitFor(tenantId: UUID, scope: RateScope): RateLimit = overrides[tenantId]?.get(scope) ?: defaults.getValue(scope)

    companion object {
        val DEFAULTS: Map<RateScope, RateLimit> = mapOf(
            RateScope.ACTION_EXECUTE to RateLimit(capacity = 600, refillPerSecond = 100.0),
            RateScope.WORKFLOW_START to RateLimit(capacity = 120, refillPerSecond = 20.0),
            RateScope.SCHEDULER_ENQUEUE to RateLimit(capacity = 60, refillPerSecond = 10.0)
        )
    }
}

sealed interface RateDecision {
    data class Allowed(val remaining: Int) : RateDecision
    /** [retryAfter] is when one token will be available again; the caller reports it as a retryable failure. */
    data class Limited(val retryAfter: Duration) : RateDecision
}

interface TenantRateLimiter {
    /** Consumes [cost] tokens of [tenantId]'s [scope] bucket if available. A throwing implementation is treated by callers as "cannot decide" (fail closed, retryable). */
    fun tryAcquire(tenantId: UUID, scope: RateScope, cost: Int = 1): RateDecision

    companion object {
        /** Explicit opt-out for tests of unrelated behaviour. Production wiring must not use it. */
        val UNLIMITED: TenantRateLimiter = object : TenantRateLimiter {
            override fun tryAcquire(tenantId: UUID, scope: RateScope, cost: Int): RateDecision = RateDecision.Allowed(Int.MAX_VALUE)
        }
    }
}

/**
 * In-memory token buckets, one per (tenant, scope). Memory is bounded: at most [maxBuckets] buckets are kept; when full, the least recently
 * used *idle* (full) bucket is dropped first — dropping an idle bucket changes nothing, because a full bucket is what a new one starts as.
 */
class InMemoryTenantRateLimiter(
    private val limits: TenantRateLimits = TenantRateLimits(),
    private val clock: Clock = Clock.systemUTC(),
    private val maxBuckets: Int = 50_000
) : TenantRateLimiter {
    private class Bucket(var tokens: Double, var updated: Instant, var lastUsed: Instant)
    private val buckets = HashMap<Pair<UUID, RateScope>, Bucket>()

    @Synchronized
    override fun tryAcquire(tenantId: UUID, scope: RateScope, cost: Int): RateDecision {
        require(cost >= 1) { "cost must be at least 1" }
        val limit = limits.limitFor(tenantId, scope)
        if (cost > limit.capacity) return RateDecision.Limited(Duration.ofSeconds(60)) // can never be satisfied: do not pretend it will
        val now = clock.instant()
        val key = tenantId to scope
        val b = buckets.getOrPut(key) { evictIfFull(now); Bucket(limit.capacity.toDouble(), now, now) }
        val elapsed = Duration.between(b.updated, now).toNanos().coerceAtLeast(0) / 1e9
        b.tokens = minOf(limit.capacity.toDouble(), b.tokens + elapsed * limit.refillPerSecond)
        b.updated = now; b.lastUsed = now
        if (b.tokens >= cost) { b.tokens -= cost; return RateDecision.Allowed(b.tokens.toInt()) }
        val seconds = (cost - b.tokens) / limit.refillPerSecond
        return RateDecision.Limited(Duration.ofMillis(Math.ceil(seconds * 1000).toLong().coerceAtLeast(1)))
    }

    private fun evictIfFull(now: Instant) {
        if (buckets.size < maxBuckets) return
        val victim = buckets.entries.filter { (k, b) -> b.tokens + Duration.between(b.updated, now).toNanos() / 1e9 * limits.limitFor(k.first, k.second).refillPerSecond >= limits.limitFor(k.first, k.second).capacity }
            .minByOrNull { it.value.lastUsed }
            ?: buckets.entries.minByOrNull { it.value.lastUsed }
        victim?.let { buckets.remove(it.key) }
    }

    /** Test/introspection. */
    @Synchronized fun bucketCount() = buckets.size
}
