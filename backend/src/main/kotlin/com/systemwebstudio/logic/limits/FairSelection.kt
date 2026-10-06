package com.systemwebstudio.logic.limits

/**
 * Provably fair, bounded selection: from [candidates] (already in the order the caller wants served first, e.g. oldest-eligible-first) take at
 * most [limit] items such that
 *  - no [key] (tenant) gets more than [perKey] items, and
 *  - the keys are served **round-robin**, so with K keys that have eligible items every one of them gets at least `min(its items, perKey,
 *    limit / K)` slots — one tenant with a million due rows cannot starve the others.
 * Within a key the caller's order is kept. Stores use it as the reference semantics of their fair-claim query (SQL: `row_number() over
 * (partition by tenant_id order by …) <= perKey`, then the global `limit`).
 */
object FairSelection {
    fun <T, K> pick(candidates: List<T>, limit: Int, perKey: Int, key: (T) -> K): List<T> {
        if (limit <= 0 || perKey <= 0) return emptyList()
        val queues = LinkedHashMap<K, ArrayDeque<T>>()
        for (c in candidates) queues.getOrPut(key(c)) { ArrayDeque() }.addLast(c)
        val taken = HashMap<K, Int>()
        val out = ArrayList<T>(minOf(limit, candidates.size))
        var progressed = true
        while (out.size < limit && progressed) {
            progressed = false
            for ((k, q) in queues) {
                if (out.size >= limit) break
                if (q.isEmpty() || (taken[k] ?: 0) >= perKey) continue
                out += q.removeFirst(); taken[k] = (taken[k] ?: 0) + 1; progressed = true
            }
        }
        return out
    }
}
