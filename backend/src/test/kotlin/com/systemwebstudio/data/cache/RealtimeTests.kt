package com.systemwebstudio.data.cache

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.putNow
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.GatewayOperation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RealtimeTests {
    private val f = GatewayFixture()
    private val now = Instant.parse("2026-10-05T10:00:00Z")
    private fun ev(t: UUID, ds: UUID?, type: DataEventType = DataEventType.DATA_CHANGED, q: String? = null, key: String? = null) =
        NewDataEvent(type, t, ds, ChangeCause.MUTATION, q, if (key != null) "customer" else null, if (key != null) RecordOperation.UPDATED else null, key, now)

    @Test fun `a stream carries only its own tenant's events`() {
        val bus = InMemoryDataEventBus(); val a = UUID.randomUUID(); val b = UUID.randomUUID(); val ds = UUID.randomUUID()
        val subA = bus.subscribe(a); val subB = bus.subscribe(b)
        bus.publish(ev(a, ds)); bus.publish(ev(b, UUID.randomUUID()))
        assertThat(subA.next(10)!!.tenantId).isEqualTo(a); assertThat(subA.next(10)).isNull()
        assertThat(subB.next(10)!!.tenantId).isEqualTo(b); assertThat(subB.next(10)).isNull()
    }

    @Test fun `a data source filter hides other data sources of the same tenant`() {
        val bus = InMemoryDataEventBus(); val t = UUID.randomUUID(); val d1 = UUID.randomUUID(); val d2 = UUID.randomUUID()
        val sub = bus.subscribe(t, setOf(d1))
        bus.publish(ev(t, d2)); bus.publish(ev(t, d1, q = "x"))
        val e = sub.next(10)!!
        assertThat(e.dataSourceId).isEqualTo(d1); assertThat(e.queryId).isEqualTo("x")
        assertThat(sub.next(10)).isNull()
    }

    @Test fun `sequence numbers increase per tenant and a reconnect resumes after the last id`() {
        val bus = InMemoryDataEventBus(); val t = UUID.randomUUID(); val ds = UUID.randomUUID()
        val first = bus.subscribe(t)
        repeat(3) { bus.publish(ev(t, ds, q = "q$it")) }
        val seen = generateSequence { first.next(10) }.toList()
        assertThat(seen.map { it.seq }).containsExactly(1L, 2L, 3L)
        first.close()
        bus.publish(ev(t, ds, q = "q3"))
        val resumed = bus.subscribe(t, null, afterSeq = 2L)
        assertThat(generateSequence { resumed.next(10) }.map { it.queryId }.toList()).containsExactly("q2", "q3")
    }

    @Test fun `a subscriber that fell behind the ring gets one reset and then continues`() {
        val bus = InMemoryDataEventBus(ringSize = 5); val t = UUID.randomUUID(); val ds = UUID.randomUUID()
        val sub = bus.subscribe(t, null, afterSeq = 0L)
        repeat(20) { bus.publish(ev(t, ds, q = "q$it")) }
        assertThat(sub.next(10)!!.type).isEqualTo(DataEventType.STREAM_RESET)
        val rest = generateSequence { sub.next(10) }.toList()
        assertThat(rest.map { it.type }.toSet()).isEqualTo(setOf(DataEventType.DATA_CHANGED))
        assertThat(rest).hasSize(5)
    }

    @Test fun `stream count per tenant is capped and closing frees a slot`() {
        val bus = InMemoryDataEventBus(maxSubscribersPerTenant = 2); val t = UUID.randomUUID()
        val s1 = bus.subscribe(t); bus.subscribe(t)
        assertThat(f.failure { bus.subscribe(t) }.code).isEqualTo(FailureCodes.RATE_LIMITED)
        bus.subscribe(UUID.randomUUID())                                                 // other tenants are unaffected
        s1.close()
        bus.subscribe(t)
    }

    @Test fun `a blocked reader wakes up when an event arrives`() {
        val bus = InMemoryDataEventBus(); val t = UUID.randomUUID(); val sub = bus.subscribe(t)
        val got = java.util.concurrent.atomic.AtomicReference<DataEvent?>()
        val th = Thread { got.set(sub.next(5_000)) }; th.start()
        Thread.sleep(50); bus.publish(ev(t, UUID.randomUUID())); th.join(3_000)
        assertThat(got.get()).isNotNull()
    }

    @Test fun `sse frames are single line json with no injectable field`() {
        val e = DataEvent(7, DataEventType.RECORD_CHANGED, UUID.randomUUID(), UUID.randomUUID(), ChangeCause.WEBHOOK, "q\nid: 999\n\nevent: evil", "ent\r\nx", RecordOperation.CREATED, "key\ndata: x", now)
        val frame = SseFormat.frame(e)
        assertThat(frame).startsWith("id: 7\nevent: RecordChanged\ndata: ")
        assertThat(frame.endsWith("\n\n")).isTrue()
        assertThat(frame.removeSuffix("\n\n").lines()).hasSize(3)                       // id, event, data — newlines in values were escaped inside the JSON string
        assertThat(frame).doesNotContain("tenant")
        assertThat(SseFormat.frame(DataEvent(1, DataEventType.STREAM_RESET, UUID.randomUUID(), null, null, at = now))).contains("StreamReset")
    }

    @Test fun `the notifier invalidates the cache before it publishes`() {
        val order = mutableListOf<String>()
        val inner = InMemoryCacheBackend()
        val backend = object : CacheBackend by inner {
            override fun increment(key: String, ttlSeconds: Int): Long { order += "invalidate"; return inner.increment(key, ttlSeconds) }
        }
        val bus = object : DataEventBus {
            override fun publish(event: NewDataEvent) { order += "event:" + event.type.wire }
            override fun subscribe(tenantId: UUID, dataSourceIds: Set<UUID>?, afterSeq: Long?): DataEventSubscription = throw UnsupportedOperationException()
        }
        DataChangeNotifier(QueryCache(backend), bus).onChange(DataChange(UUID.randomUUID(), UUID.randomUUID(), ChangeCause.WEBHOOK))
        assertThat(order).containsExactly("invalidate", "event:DataChanged", "event:QueryInvalidated")
    }

    @Test fun `a failing bus never breaks the writer`() {
        val bus = object : DataEventBus {
            override fun publish(event: NewDataEvent) = throw IllegalStateException("bus down")
            override fun subscribe(tenantId: UUID, dataSourceIds: Set<UUID>?, afterSeq: Long?): DataEventSubscription = throw UnsupportedOperationException()
        }
        DataChangeNotifier(QueryCache(InMemoryCacheBackend()), bus).onChange(DataChange(UUID.randomUUID(), UUID.randomUUID(), ChangeCause.MUTATION))
    }

    @Test fun `datasource update invalidates everything even when queries are named`() {
        val t = UUID.randomUUID(); val ds = UUID.randomUUID()
        val cache = QueryCache(InMemoryCacheBackend())
        val parts = CacheKeyParts(CacheScope(t, ds, "q"), 1, 1, null, 1, null, "{}", null)
        cache.putNow(parts, "x", 60)
        DataChangeNotifier(cache, InMemoryDataEventBus()).onChange(DataChange(t, ds, ChangeCause.DATASOURCE_UPDATED, listOf("other")))
        assertThat(cache.get(parts)).isNull()
    }

    // ------------------------------------------------------------------------------------------------ service

    private val realtime = RealtimeService(f.bus, f.guard, f.repo, f.audit)

    @Test fun `subscribing needs the permission and a foreign data source is not found`() {
        val a = f.tenant(); val dsA = f.register(a); val b = f.tenant()
        f.authorizer.denied += GatewayOperation.EVENTS_SUBSCRIBE
        assertThat(f.failure { realtime.subscribe(f.ctx(a)) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.failure { realtime.subscribe(f.ctx(a), setOf(dsA.id)) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        f.authorizer.denied.clear()
        assertThat(f.failure { realtime.subscribe(f.ctx(b), setOf(dsA.id)) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        realtime.subscribe(f.ctx(a), setOf(dsA.id)).close()
        assertThat(f.failure { realtime.subscribe(f.ctx(a), (1..60).map { UUID.randomUUID() }.toSet()) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `a revoked permission ends the stream at the next check`() {
        val a = f.tenant(); val dsA = f.register(a)
        val h = realtime.subscribe(f.ctx(a), setOf(dsA.id))
        assertThat(h.stillAllowed()).isTrue()
        f.authorizer.denied += GatewayOperation.EVENTS_SUBSCRIBE
        assertThat(h.stillAllowed()).isFalse()
        f.authorizer.denied.clear(); f.authorizer.failWith = IllegalStateException("down")
        assertThat(h.stillAllowed()).isFalse()                                          // an outage ends it too
        h.close()
    }

    @Test fun `events of other tenants never reach a handle`() {
        val a = f.tenant(); val b = f.tenant(); val dsA = f.register(a); val dsB = f.register(b)
        val h = realtime.subscribe(f.ctx(a))
        f.notifier.onChange(DataChange(b.tenantId, dsB.id, ChangeCause.WEBHOOK))
        assertThat(h.next(10)).isNull()
        f.notifier.onChange(DataChange(a.tenantId, dsA.id, ChangeCause.WEBHOOK))
        assertThat(h.next(10)!!.dataSourceId).isEqualTo(dsA.id)
        h.close()
    }
}
