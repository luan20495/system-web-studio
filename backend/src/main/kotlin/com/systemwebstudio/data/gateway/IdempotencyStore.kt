package com.systemwebstudio.data.gateway

import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a completed mutation leaves behind for replays: counts and a bounded result document, never the request parameters. */
data class StoredMutation(val affected: Long?, val outputJson: String?, val completedAt: Instant)

sealed interface IdempotencyDecision {
    /** this call owns the key and must run the mutation, then [IdempotencyStore.complete], [IdempotencyStore.release] or [IdempotencyStore.markUnknown] */
    data object Run : IdempotencyDecision
    data class Replay(val stored: StoredMutation) : IdempotencyDecision
    /** another call holds the key and its lease has not run out */
    data object InProgress : IdempotencyDecision
    /**
     * An earlier attempt with this key may or may not have been applied (it timed out, lost its connection, crashed or hung past its lease). The key stays
     * reserved and is **never** handed out for a second run until it expires: running again could write twice.
     */
    data object OutcomeUnknown : IdempotencyDecision
    /** same key, different parameters: a client bug or an attack, never silently re-run */
    data object Conflict : IdempotencyDecision
}

/**
 * Port: at-most-once execution of a mutation per (tenant, data source, mutation, idempotency key). `begin` must be atomic (two concurrent calls
 * with one key: exactly one gets [IdempotencyDecision.Run]). [fingerprint] is a hash of the canonical parameters (see [MutationFingerprint]).
 *
 * The key is the one C4 derives (`base64url(sha256(tenant | app | user | action | clientKey))`, 43 characters): C3 scopes it by (tenant, data source,
 * mutation) and never sees the client's raw key. It is kept for the whole life of the mutation — reserved at `begin`, and after anything except a
 * *definite* "not executed" failure it stays reserved ([markUnknown]) instead of being released.
 *
 * Record states: RESERVED (lease) → DONE ([complete]) | UNKNOWN ([markUnknown], or a RESERVED whose lease ran out) | gone ([release], only for failures that
 * certainly applied nothing). Persistent implementation: migration request in BOARD.md (`data_idempotency` with a state column).
 */
interface IdempotencyStore {
    fun begin(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String, fingerprint: String, ttlSeconds: Long): IdempotencyDecision
    fun complete(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String, result: StoredMutation)
    /** the mutation certainly did not execute (it was refused before anything was sent, or the source reported a definite rejection): free the key so a retry runs */
    fun release(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String)
    /** the outcome is ambiguous: keep the key reserved so a retry is answered [IdempotencyDecision.OutcomeUnknown] instead of running again */
    fun markUnknown(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String)
}

class InMemoryIdempotencyStore(private val clock: Clock = Clock.systemUTC(), private val leaseSeconds: Long = DEFAULT_LEASE_SECONDS) : IdempotencyStore {
    private enum class State { RESERVED, DONE, UNKNOWN }
    private class Rec(val fingerprint: String, var state: State, var result: StoredMutation?, val expiresAt: Instant, val leaseUntil: Instant)
    private val rows = ConcurrentHashMap<String, Rec>()
    private fun k(t: UUID, d: UUID, m: String, key: String) = "$t|$d|$m|$key"

    override fun begin(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String, fingerprint: String, ttlSeconds: Long): IdempotencyDecision {
        val id = k(tenantId, dataSourceId, mutationId, key); val now = clock.instant()
        var decision: IdempotencyDecision = IdempotencyDecision.Run
        rows.compute(id) { _, cur ->
            when {
                cur == null || cur.expiresAt <= now -> Rec(fingerprint, State.RESERVED, null, now.plusSeconds(ttlSeconds), now.plusSeconds(leaseSeconds))
                cur.fingerprint != fingerprint -> { decision = IdempotencyDecision.Conflict; cur }
                cur.state == State.DONE -> { decision = IdempotencyDecision.Replay(cur.result!!); cur }
                cur.state == State.UNKNOWN -> { decision = IdempotencyDecision.OutcomeUnknown; cur }
                cur.leaseUntil <= now -> { cur.state = State.UNKNOWN; decision = IdempotencyDecision.OutcomeUnknown; cur }     // the holder vanished or hung: ambiguous, not free
                else -> { decision = IdempotencyDecision.InProgress; cur }
            }
        }
        return decision
    }
    override fun complete(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String, result: StoredMutation) {
        rows[k(tenantId, dataSourceId, mutationId, key)]?.let { it.result = result; it.state = State.DONE }
    }
    override fun release(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String) { rows.remove(k(tenantId, dataSourceId, mutationId, key)) }
    override fun markUnknown(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String) {
        rows[k(tenantId, dataSourceId, mutationId, key)]?.let { if (it.state == State.RESERVED) it.state = State.UNKNOWN }
    }
    val size get() = rows.size

    companion object { const val DEFAULT_LEASE_SECONDS = 300L }
}
