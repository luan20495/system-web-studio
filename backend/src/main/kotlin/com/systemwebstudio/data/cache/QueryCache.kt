package com.systemwebstudio.data.cache

import com.systemwebstudio.data.query.DataJson
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything that makes one cached answer different from another. [paramsCanonical] is the parameter map in a canonical form (sorted keys,
 * no insignificant whitespace), so the same logical request always maps to the same key and a different one never does.
 * [dataSourceVersion], [queryVersion] and [mappingVersion] are part of the key: editing the data source, the query or the mapping makes
 * every older entry unreachable immediately, before any explicit invalidation arrives.
 */
data class CacheScope(val tenantId: UUID, val dataSourceId: UUID, val queryId: String)

data class CacheKeyParts(
    val scope: CacheScope, val dataSourceVersion: Long, val queryVersion: Long, val mappingRef: String?, val mappingVersion: Long,
    val viewModelRef: String?, val paramsCanonical: String, val page: String?
)

/** What [QueryCache.lookup] found ([payload], null on a miss) and the right to fill the entry ([ticket], null when caching is unavailable right now). */
class CacheLookup(val payload: String?, val ticket: CacheTicket?)

/** The key captured at lookup time, with the generations it was derived from. Opaque: only [QueryCache.put] can use it. */
class CacheTicket internal constructor(internal val key: String, internal val scope: CacheScope, internal val dsGen: Long, internal val queryGen: Long, internal val digest: String) {
    override fun toString() = "CacheTicket(***)"
}

/**
 * Result cache of the Data Gateway (contract: key = tenant + data source + query + params + version).
 *
 * **Isolation.** The tenant id is part of the key, *and* the stored envelope repeats tenant and data source; a hit whose envelope disagrees
 * with the request is treated as a miss and is never returned. There is no key without a tenant, no shared namespace, no wildcard read.
 *
 * **Invalidation without scanning.** Each (tenant, data source) and each (tenant, data source, query) has a *generation* counter that is part
 * of every key. Invalidating = incrementing the counter: all older entries become unreachable at once and expire by TTL, no `KEYS`/`SCAN`.
 *
 * **Consistency over availability.** If a counter cannot be incremented (backend outage) the scope is *bypassed* in this process until the
 * longest possible TTL has passed, so a stale answer is never served after we knew we failed to invalidate. Other nodes cannot know this: that
 * is the documented limit of the design (DECISIONS D-C3-06). A backend outage on read or write simply means "miss".
 *
 * **No stale write.** Read-then-fill goes through [QueryCache.lookup] → [CacheTicket] → [QueryCache.put]: the key (with the generations it embeds) is captured
 * once, before the data is read, and the write reuses it — an invalidation that happens while the answer is being computed cannot be overwritten by that
 * older answer (it would land under a generation nobody reads any more, and `put` refuses it anyway). The stored envelope also carries a fingerprint of the
 * request (versions, mapping, view model, parameters, page) that every read re-verifies.
 *
 * The cache key does not contain the caller: a cached query must not depend on *who* asks (rows are chosen by declared parameters only).
 */
class QueryCache(
    private val backend: CacheBackend, private val clock: Clock = Clock.systemUTC(),
    private val keyPrefix: String = "xw:data:v1", private val maxValueBytes: Int = MAX_VALUE_BYTES
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val bypass = ConcurrentHashMap<String, Long>()          // scope key -> bypass-until (epoch ms)

    data class Stats(val hits: Long, val misses: Long, val stores: Long, val skippedTooLarge: Long, val invalidations: Long, val errors: Long, val staleWritesDropped: Long = 0)
    private val counters = java.util.concurrent.atomic.AtomicLongArray(7)
    fun stats() = Stats(counters.get(0), counters.get(1), counters.get(2), counters.get(3), counters.get(4), counters.get(5), counters.get(6))

    // ----------------------------------------------------------------------------------------------- read / write

    /**
     * The one read-then-fill protocol. [lookup] computes the key **once**, from the generation counters as they are *now*, and hands it back in a
     * [CacheTicket]; the caller computes the answer (which may take seconds) and passes the ticket to [put]. Because the write uses the key that was
     * captured *before* the data was read, an invalidation that lands in between (it bumps a generation) makes that key unreachable: the late write
     * of an answer computed from older data goes to a dead key and can never be served. [put] additionally re-reads the counters and refuses the
     * write outright when they moved (compare, then write; the key capture is what makes the pair safe without an atomic compare-and-set).
     */
    fun lookup(parts: CacheKeyParts): CacheLookup {
        val scope = parts.scope
        if (bypassed(scope)) { counters.incrementAndGet(1); return CacheLookup(null, null) }
        val ticket = try { ticketFor(parts) } catch (e: Exception) { counters.incrementAndGet(5); counters.incrementAndGet(1); return CacheLookup(null, null) }
        return try {
            val raw = backend.get(ticket.key)
            if (raw == null) { counters.incrementAndGet(1); CacheLookup(null, ticket) }
            else {
                val env = unwrap(raw)
                if (env == null || env.tenant != scope.tenantId.toString() || env.dataSource != scope.dataSourceId.toString() || env.query != scope.queryId || env.digest != ticket.digest) {
                    log.warn("cache entry rejected: envelope does not match its key")
                    counters.incrementAndGet(5); counters.incrementAndGet(1); CacheLookup(null, ticket)
                } else { counters.incrementAndGet(0); CacheLookup(env.payload, ticket) }
            }
        } catch (e: Exception) { counters.incrementAndGet(5); counters.incrementAndGet(1); CacheLookup(null, ticket) }
    }

    /** Read-only convenience (nothing will be written afterwards). A read-then-fill caller must use [lookup] and [put]. */
    fun get(parts: CacheKeyParts): String? = lookup(parts).payload

    /** @return true when stored; false when not cacheable, too large, bypassed, or invalidated since [lookup] (a stale write is dropped, never stored under a live key) */
    fun put(ticket: CacheTicket, payload: String, ttlSeconds: Int): Boolean {
        if (ttlSeconds <= 0) return false
        if (payload.length > maxValueBytes) { counters.incrementAndGet(3); return false }
        if (bypassed(ticket.scope)) return false
        return try {
            if (backend.counter(dsGenKey(ticket.scope.tenantId, ticket.scope.dataSourceId)) != ticket.dsGen || backend.counter(queryGenKey(ticket.scope)) != ticket.queryGen) {
                counters.incrementAndGet(6); return false                                  // invalidated while the answer was being computed
            }
            backend.put(ticket.key, wrap(ticket.scope, ticket.digest, payload), ttlSeconds.coerceAtMost(MAX_TTL_SECONDS))
            counters.incrementAndGet(2); true
        } catch (e: Exception) { counters.incrementAndGet(5); false }
    }

    // ----------------------------------------------------------------------------------------------- invalidation

    /** Drops every cached answer of one data source of one tenant. */
    fun invalidateDataSource(tenantId: UUID, dataSourceId: UUID): Boolean = bump(dsGenKey(tenantId, dataSourceId), "$tenantId:$dataSourceId")

    /** Drops every cached answer of one query (all parameters, all pages). */
    fun invalidateQuery(tenantId: UUID, dataSourceId: UUID, queryId: String): Boolean = bump(queryGenKey(CacheScope(tenantId, dataSourceId, queryId)), "$tenantId:$dataSourceId")

    private fun bump(genKey: String, scopeKey: String): Boolean {
        counters.incrementAndGet(4)
        return try {
            backend.increment(genKey, GEN_TTL_SECONDS)
            bypass.remove(scopeKey)
            true
        } catch (e: Exception) {
            counters.incrementAndGet(5)
            bypass[scopeKey] = clock.millis() + MAX_TTL_SECONDS * 1000L               // we know we could not invalidate: serve nothing from this scope in this process
            log.warn("cache invalidation failed; scope is bypassed in this process")
            false
        }
    }

    private fun bypassed(scope: CacheScope): Boolean {
        val until = bypass[bypassKey(scope)] ?: return false
        if (until <= clock.millis()) { bypass.remove(bypassKey(scope)); return false }
        return true
    }
    private fun bypassKey(s: CacheScope) = "${s.tenantId}:${s.dataSourceId}"

    // ----------------------------------------------------------------------------------------------- keys

    private fun dsGenKey(t: UUID, ds: UUID) = "$keyPrefix:gen:$t:$ds"
    private fun queryGenKey(s: CacheScope) = "$keyPrefix:qgen:${s.tenantId}:${s.dataSourceId}:${sha(s.queryId).take(16)}"

    /** `prefix:t:<tenant>:ds:<ds>:g<dsGen>.<qGen>:<sha256 of everything else>` — the readable part is only for operators, the hash decides */
    private fun ticketFor(p: CacheKeyParts): CacheTicket {
        val s = p.scope
        val dsGen = backend.counter(dsGenKey(s.tenantId, s.dataSourceId)); val qGen = backend.counter(queryGenKey(s))
        val digest = sha(listOf(s.tenantId, s.dataSourceId, s.queryId, p.dataSourceVersion, p.queryVersion, p.mappingRef ?: "-", p.mappingVersion, p.viewModelRef ?: "-", p.paramsCanonical, p.page ?: "-").joinToString("\u0000"))
        return CacheTicket("$keyPrefix:t:${s.tenantId}:ds:${s.dataSourceId}:g$dsGen.$qGen:$digest", s, dsGen, qGen, digest)
    }

    internal fun keyOf(p: CacheKeyParts): String = ticketFor(p).key

    private class Envelope(val tenant: String, val dataSource: String, val query: String, val digest: String, val payload: String)

    /** the envelope repeats tenant, data source, query and the request fingerprint (versions + parameters + page); a read re-verifies all four */
    private fun wrap(s: CacheScope, digest: String, payload: String): String =
        DataJson.mapper.writeValueAsString(mapOf("t" to s.tenantId.toString(), "d" to s.dataSourceId.toString(), "q" to s.queryId, "f" to digest, "p" to payload))

    private fun unwrap(raw: String): Envelope? {
        return try {
            val n = DataJson.mapper.readTree(raw)
            fun f(k: String): String? = n.get(k)?.takeIf { DataJson.isText(it) }?.let { DataJson.text(it) }
            val t = f("t"); val d = f("d"); val q = f("q"); val fp = f("f"); val p = f("p")
            if (t == null || d == null || q == null || fp == null || p == null) null else Envelope(t, d, q, fp, p)
        } catch (e: Exception) { null }
    }

    companion object {
        const val MAX_VALUE_BYTES = 512 * 1024
        const val MAX_TTL_SECONDS = 86_400
        const val GEN_TTL_SECONDS = 30 * 86_400

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** canonical JSON of request parameters: keys sorted, numbers as plain decimals, so `{"a":1,"b":2}` == `{"b":2,"a":1}` and `1` == `1.0` */
        fun canonical(params: Map<String, JsonNode>): String = buildString {
            append('{')
            params.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) -> if (i > 0) append(','); append(DataJson.mapper.writeValueAsString(k)).append(':'); canon(v, this) }
            append('}')
        }
        /** canonical form of parameters that were already bound and validated against a definition (defaults applied, types normalised) */
        fun canonicalBound(bound: Map<String, Any>): String = buildString {
            append('{')
            bound.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
                if (i > 0) append(','); append(DataJson.mapper.writeValueAsString(k)).append(':')
                append(when (v) {
                    is Boolean -> v.toString()
                    is Long, is Int -> v.toString()
                    is java.math.BigDecimal -> v.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
                    else -> DataJson.mapper.writeValueAsString(v.toString())
                })
            }
            append('}')
        }

        private fun canon(n: JsonNode?, sb: StringBuilder) {
            when {
                n == null || n.isNull || n.isMissingNode -> sb.append("null")
                n.isNumber -> sb.append(n.decimalValue().stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it })
                n.isArray -> { sb.append('['); DataJson.elements(n).forEachIndexed { i, c -> if (i > 0) sb.append(','); canon(c, sb) }; sb.append(']') }
                n.isObject -> { sb.append('{'); DataJson.keys(n).sorted().forEachIndexed { i, k -> if (i > 0) sb.append(','); sb.append(DataJson.mapper.writeValueAsString(k)).append(':'); canon(n.get(k), sb) }; sb.append('}') }
                else -> sb.append(DataJson.mapper.writeValueAsString(DataJson.toJava(n)))
            }
        }
    }
}
