package com.systemwebstudio.data.gateway

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.TestTenant
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataSource
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.MutationOutcome
import com.systemwebstudio.data.query.DataJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Idempotency of writes (contract data-runtime §4). The key reaching C3 is the one C4 derives — base64url(sha256(tenant | app | user | action | clientKey)),
 * 43 characters — and it must survive the whole mutation: an ambiguous failure (timeout, lost connection, upstream 5xx, anything unclassified) keeps it
 * reserved, so a retry can never write a second time. Only a failure that certainly applied nothing frees it. The key is never logged or audited in the clear.
 */
class IdempotencyTests {
    private val f = GatewayFixture()
    private fun node(v: Any?) = DataJson.toNode(v)
    private fun setup(): Triple<TestTenant, DataSource, GatewayContext> {
        val t = f.tenant(); val ds = f.register(t); f.mutation(t, ds)
        return Triple(t, ds, f.ctx(t))
    }
    private fun derived(vararg parts: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray()))
    private fun mutate(ctx: GatewayContext, ds: UUID, key: String, name: String = "Grace") =
        f.gateway.mutate(ctx, GatewayMutation(ds, "create-customer", mapOf("name" to node(name)), key))

    // ---------------------------------------------------------------- the derived key

    @Test fun `the derived key shape sha256 base64url is accepted and the other encodings are not`() {
        val (_, ds, ctx) = setup()
        val key = derived("tenant", "app", "user", "action", "client-key")
        assertThat(key.length).isEqualTo(43)
        assertThat(mutate(ctx, ds.id, key).replayed).isFalse()
        assertThat(mutate(ctx, ds.id, key).replayed).isTrue()
        val std = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest("x".toByteArray()))       // standard alphabet with '=' padding (44 chars)
        for (bad in listOf(std, "ab+cd/ef" + "g".repeat(10), "k".repeat(129), "", " $key", "$key "))
            assertThat(f.failure { mutate(ctx, ds.id, bad) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    // ---------------------------------------------------------------- ambiguous failures keep the key

    @Test fun `an ambiguous failure keeps the key reserved - the retry does not run a second write`() {
        for (ambiguous in listOf<Exception>(
            ConnectorFailure(FailureCodes.TIMEOUT, "the data source did not answer in time"),
            ConnectorFailure(FailureCodes.CONNECT_FAILED, "the data source could not be reached"),
            ConnectorFailure(FailureCodes.UPSTREAM_STATUS, "the data source answered with an error status"),
            ConnectorFailure(FailureCodes.RESPONSE_INVALID, "bad response"),
            ConnectorFailure(FailureCodes.REDIRECT_BLOCKED, "redirected"),
            ConnectorFailure(FailureCodes.QUERY_FAILED, "failed"),
            IllegalStateException("something unclassified happened mid-write"))) {
            val (_, ds, ctx) = setup()
            f.connector.mutationCalls.set(0)
            var fail = true
            f.connector.mutationHook = { _, _, _ -> if (fail) throw ambiguous else MutationOutcome(1, null) }
            val key = derived("ambiguous", ambiguous.javaClass.simpleName, (ambiguous as? ConnectorFailure)?.code ?: "x")
            var threw = false
            try { mutate(ctx, ds.id, key) } catch (e: Exception) { threw = true }                // (the service may wrap an unclassified exception; either way it failed)
            assertThat(threw).isTrue()
            fail = false                                                                    // the source is healthy again, and the client retries
            val retry = f.failure { mutate(ctx, ds.id, key) }
            assertThat(retry.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
            assertThat(f.connector.mutationCalls.get()).isEqualTo(1)                        // the connector was called once in total: no duplicate write
            assertThat(f.failure { mutate(ctx, ds.id, key) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)       // and it stays that way
            assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
            assertThat(mutate(ctx, ds.id, derived("fresh", key)).replayed).isFalse()        // a genuinely new action (new derived key) is unaffected
        }
    }

    @Test fun `a definite not-executed failure frees the key`() {
        for (code in DefaultDataGateway.NOT_EXECUTED) {
            val (_, ds, ctx) = setup()
            var fail = true
            f.connector.mutationCalls.set(0)
            f.connector.mutationHook = { _, _, _ -> if (fail) throw ConnectorFailure(code, "refused") else MutationOutcome(1, null) }
            val key = derived("definite", code)
            assertThat(f.failure { mutate(ctx, ds.id, key) }.code).isEqualTo(code)
            fail = false
            assertThat(mutate(ctx, ds.id, key).replayed).isFalse()
            assertThat(f.connector.mutationCalls.get()).isEqualTo(2)
        }
        // the ambiguous ones are not in that set
        for (code in listOf(FailureCodes.TIMEOUT, FailureCodes.CONNECT_FAILED, FailureCodes.UPSTREAM_STATUS, FailureCodes.INTERNAL, FailureCodes.QUERY_FAILED, FailureCodes.RESPONSE_INVALID, FailureCodes.REDIRECT_BLOCKED))
            assertThat(code in DefaultDataGateway.NOT_EXECUTED).isFalse()
    }

    @Test fun `the unknown state is exact about parameters - a different payload under that key is still a conflict`() {
        val (_, ds, ctx) = setup()
        f.connector.mutationHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.TIMEOUT, "timeout") }
        val key = derived("conflict")
        f.failure { mutate(ctx, ds.id, key, "Grace") }
        assertThat(f.failure { mutate(ctx, ds.id, key, "Edsger") }.code).isEqualTo(FailureCodes.IDEMPOTENCY_CONFLICT)
        assertThat(f.failure { mutate(ctx, ds.id, key, "Grace") }.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
    }

    @Test fun `a holder that vanished does not free the key - after its lease the outcome is unknown, not retryable`() {
        val (_, ds, ctx) = setup()
        val key = derived("vanished")
        // the node dies in the middle of the write: an Error is not caught by the gateway's failure handling, so the record is left RESERVED, as after a crash
        f.connector.mutationHook = { _, _, _ -> throw java.lang.AssertionError("simulated crash") }
        try { mutate(ctx, ds.id, key); throw IllegalStateException("expected the simulated crash") } catch (e: AssertionError) { assertThat(e.message).isEqualTo("simulated crash") }
        f.connector.mutationHook = { _, _, _ -> MutationOutcome(1, null) }
        assertThat(f.failure { mutate(ctx, ds.id, key) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_IN_PROGRESS)                 // lease still valid: someone may be running it
        f.clock.advanceSeconds(InMemoryIdempotencyStore.DEFAULT_LEASE_SECONDS + 1)
        assertThat(f.failure { mutate(ctx, ds.id, key) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)             // never a second run: the first may have been applied
        assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
    }

    @Test fun `reservations expire with the idempotency window`() {
        val (_, ds, ctx) = setup()
        f.connector.mutationHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.TIMEOUT, "timeout") }
        val key = derived("expiry")
        f.failure { mutate(ctx, ds.id, key) }
        assertThat(f.failure { mutate(ctx, ds.id, key) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        f.clock.advanceSeconds(DefaultDataGateway.IDEMPOTENCY_TTL_SECONDS + 1)
        f.connector.mutationHook = { _, _, _ -> MutationOutcome(1, null) }
        assertThat(mutate(ctx, ds.id, key).replayed).isFalse()                              // the window is over: the key is a new key again
    }

    @Test fun `the reservation is per tenant, data source and mutation`() {
        val (_, ds, ctx) = setup()
        f.connector.mutationHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.TIMEOUT, "timeout") }
        val key = derived("scope")
        f.failure { mutate(ctx, ds.id, key) }
        val other = f.tenant(); val dsB = f.register(other); f.mutation(other, dsB)
        f.connector.mutationHook = { _, _, _ -> MutationOutcome(1, null) }
        assertThat(mutate(f.ctx(other), dsB.id, key).replayed).isFalse()                    // the same key text under another tenant is unrelated
        assertThat(f.failure { mutate(ctx, ds.id, key) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
    }

    // ---------------------------------------------------------------- the store itself failing

    @Test fun `a store that fails while recording an ambiguous failure still leaves the key unusable`() {
        val (_, ds, ctx) = setup()
        val inner = InMemoryIdempotencyStore(f.clock)
        val flaky = object : IdempotencyStore by inner {
            var failing = false
            override fun markUnknown(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String) { if (failing) error("store down"); inner.markUnknown(tenantId, dataSourceId, mutationId, key) }
            override fun release(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String) { if (failing) error("store down"); inner.release(tenantId, dataSourceId, mutationId, key) }
        }
        val gw = DefaultDataGateway(f.guard, f.service, f.queries, f.mutations, f.mappings, f.discovery, f.cache, flaky, f.notifier, f.audit, f.limiter, clock = f.clock)
        flaky.failing = true
        f.connector.mutationHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.TIMEOUT, "timeout") }
        val key = derived("flaky")
        val m = GatewayMutation(ds.id, "create-customer", mapOf("name" to node("Grace")), key)
        assertThat(f.failure { gw.mutate(ctx, m) }.code).isEqualTo(FailureCodes.TIMEOUT)          // the caller still sees the original failure
        flaky.failing = false
        assertThat(f.failure { gw.mutate(ctx, m) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_IN_PROGRESS)    // still RESERVED (lease), never free
        f.clock.advanceSeconds(InMemoryIdempotencyStore.DEFAULT_LEASE_SECONDS + 1)
        assertThat(f.failure { gw.mutate(ctx, m) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
        assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
    }

    @Test fun `a store that fails after a successful write does not turn the success into an error`() {
        val (_, ds, ctx) = setup()
        val inner = InMemoryIdempotencyStore(f.clock)
        val failingComplete = object : IdempotencyStore by inner {
            override fun complete(tenantId: UUID, dataSourceId: UUID, mutationId: String, key: String, result: StoredMutation) { error("store down") }
        }
        val gw = DefaultDataGateway(f.guard, f.service, f.queries, f.mutations, f.mappings, f.discovery, f.cache, failingComplete, f.notifier, f.audit, f.limiter, clock = f.clock)
        val key = derived("complete-fails")
        val r = gw.mutate(ctx, GatewayMutation(ds.id, "create-customer", mapOf("name" to node("Grace")), key))
        assertThat(r.replayed).isFalse()                                                    // the write happened, the caller is told so
        assertThat(f.failure { gw.mutate(ctx, GatewayMutation(ds.id, "create-customer", mapOf("name" to node("Grace")), key)) }.code).isEqualTo(FailureCodes.IDEMPOTENCY_IN_PROGRESS)
        assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
    }

    // ---------------------------------------------------------------- concurrency

    @Test fun `concurrent retries of one key run the mutation exactly once`() {
        val (_, ds, ctx) = setup()
        val key = derived("race")
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val ran = AtomicInteger(); val other = AtomicInteger()
        f.connector.mutationHook = { _, _, _ -> ran.incrementAndGet(); Thread.sleep(30); MutationOutcome(1, null) }
        val futures = (1..16).map { pool.submit<Unit> { start.await(); try { mutate(ctx, ds.id, key) } catch (e: ConnectorFailure) { other.incrementAndGet() } } }
        start.countDown(); futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertThat(ran.get()).isEqualTo(1)
        assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
        assertThat(mutate(ctx, ds.id, key).replayed).isTrue()
    }

    // ---------------------------------------------------------------- the key is never written in the clear

    @Test fun `the key appears in no log line, audit record or printed request`() {
        val (_, ds, ctx) = setup()
        val key = derived("secret-looking", "never-logged")
        LogCapture().use { logs ->
            f.connector.mutationHook = { _, _, _ -> throw ConnectorFailure(FailureCodes.TIMEOUT, "timeout") }
            f.failure { mutate(ctx, ds.id, key) }                                           // ambiguous failure
            f.failure { mutate(ctx, ds.id, key) }                                           // outcome unknown
            f.connector.mutationHook = { _, _, _ -> MutationOutcome(1, null) }
            mutate(ctx, ds.id, derived("another-key"))                                     // success
            assertThat(logs.text).doesNotContain(key)
        }
        for (e in f.audit.events) assertThat(e.details.values.map { it.toString() }.none { it.contains(key) }).isTrue()
        val audited = f.audit.events.filter { it.action == DataAuditActions.MUTATION_RUN && it.details["idem"] != null }
        assertThat(audited).isNotEmpty()
        assertThat(audited.map { it.details["idem"].toString() }.all { it.length == 12 }).isTrue()
        assertThat(audited.map { it.details["idem"] }.toSet().size).isGreaterThan(1)       // a short reference still tells the keys apart
        val m = GatewayMutation(ds.id, "create-customer", mapOf("name" to node("Grace Hopper")), key)
        assertThat(m.toString()).doesNotContain(key).doesNotContain("Grace")
        assertThat(GatewayQuery(ds.id, "customers", mapOf("ssn" to node("123-45-6789")), null, "m").toString()).doesNotContain("123-45")
    }
}
