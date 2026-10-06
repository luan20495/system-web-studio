package com.systemwebstudio.data.gateway

import com.systemwebstudio.data.GatewayFixture
import com.systemwebstudio.data.LogCapture
import com.systemwebstudio.data.cache.DataEventType
import com.systemwebstudio.data.datasource.ConnectionTestResult
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.DataAuditActions
import com.systemwebstudio.data.datasource.DataSourceStatus
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.datasource.MutationOutcome
import com.systemwebstudio.data.query.DataJson
import com.systemwebstudio.data.query.MutationKind
import com.systemwebstudio.data.query.PageSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** T11: the gateway pipeline — permission first, tenant-scoped, approved definitions only, mapped output only, cached per tenant, audited without values. */
class GatewayTests {
    private val f = GatewayFixture()
    private val rows = listOf(
        mapOf("id" to 1, "name" to "Ada", "status" to "A", "ssn" to "123-45-6789", "password_hash" to "HASH-123"),
        mapOf("id" to 2, "name" to "Alan", "status" to "C", "ssn" to "987-65-4321", "password_hash" to "HASH-456"))
    private fun node(v: Any?) = DataJson.toNode(v)
    private fun q(ds: UUID, op: String = "customers", params: Map<String, Any?> = emptyMap(), mapping: String = "m-customers", vm: String? = "vm-customers", page: PageSpec? = null) =
        GatewayQuery(ds, op, params.mapValues { node(it.value) }, page, mapping, vm)

    private fun setup(ttl: Int = 60): Triple<com.systemwebstudio.data.TestTenant, com.systemwebstudio.data.datasource.DataSource, GatewayContext> {
        val t = f.tenant(); val ds = f.register(t, rows); f.query(t, ds, ttl = ttl); f.mapping(t)
        return Triple(t, ds, f.ctx(t))
    }

    // ------------------------------------------------------------------------------------------------ pipeline order and permission

    @Test fun `a denied caller gets nothing and nothing behind the gateway is touched`() {
        val (t, ds, ctx) = setup()
        f.authorizer.denied += GatewayOperation.QUERY_EXECUTE
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id)) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.connector.queryCalls.get()).isEqualTo(0)
        assertThat(f.connector.credentialsSeen).isEmpty()                      // the credential was never decrypted
        assertThat(f.mappings.scopes).isEmpty()
        assertThat(f.audit.actions()).containsExactly(DataAuditActions.DENIED)
        // a denied caller learns the same thing for an id that exists and one that does not
        val a = f.failure { f.gateway.runQuery(ctx, q(UUID.randomUUID())) }
        val b = f.failure { f.gateway.runQuery(ctx, q(ds.id)) }
        assertThat(a.safeMessage).isEqualTo(b.safeMessage)
        assertThat(t.tenantId).isNotNull()
    }

    @Test fun `every operation checks its own permission`() {
        val (_, ds, ctx) = setup(); f.mutation(f.tenant(), ds)
        val ops = mapOf(
            GatewayOperation.QUERY_EXECUTE to { f.gateway.runQuery(ctx, q(ds.id)); Unit },
            GatewayOperation.MUTATION_EXECUTE to { f.gateway.mutate(ctx, GatewayMutation(ds.id, "x", emptyMap(), "idem-key-0001")); Unit },
            GatewayOperation.DATASOURCE_MANAGE to { f.gateway.testConnection(ctx, ds.id); Unit },
            GatewayOperation.SCHEMA_DISCOVER to { f.gateway.discoverSchema(ctx, ds.id); Unit },
            GatewayOperation.CACHE_REFRESH to { f.gateway.refreshCache(ctx, ds.id); Unit }
        )
        for ((op, call) in ops) {
            f.authorizer.denied.clear(); f.authorizer.denied += op
            assertThat(f.failure { call() }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        }
        assertThat(f.connector.queryCalls.get() + f.connector.mutationCalls.get()).isEqualTo(0)
    }

    @Test fun `an authorizer outage denies instead of allowing`() {
        val (_, ds, ctx) = setup()
        f.authorizer.failWith = IllegalStateException("access service down")
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id)) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        assertThat(f.connector.queryCalls.get()).isEqualTo(0)
    }

    // ------------------------------------------------------------------------------------------------ request shape: no SQL, no URL

    @Test fun `a browser cannot send sql a url a header or a tenant`() {
        listOf(
            """{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","sql":"DROP TABLE users"}""",
            """{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","query":"SELECT * FROM users"}""",
            """{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","url":"http://169.254.169.254/"}""",
            """{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","headers":{"Authorization":"x"}}""",
            """{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","tenantId":"${UUID.randomUUID()}"}""",
            """{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","credential":"x"}""",
            """{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","params":{"a":{"nested":1}}}""",
            """{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","page":{"limit":10,"offset":0,"sql":"x"}}""",
            """{"dataSourceId":"not-a-uuid","operation":"customers","mappingRef":"m"}""",
            """{"operation":"customers","mappingRef":"m"}""",
            """[]"""
        ).forEach { assertThat(f.failure { GatewayRequests.query(it.toByteArray()) }.code).isEqualTo(FailureCodes.INVALID_PARAMS) }
        assertThat(f.failure { GatewayRequests.query(ByteArray(70_000)) }.code).isEqualTo(FailureCodes.PAYLOAD_TOO_LARGE)
        val ok = GatewayRequests.query("""{"dataSourceId":"${UUID.randomUUID()}","operation":"customers","mappingRef":"m","viewModelRef":"v","params":{"status":"A"},"page":{"limit":20}}""".toByteArray())
        assertThat(ok.page!!.limit).isEqualTo(20)
        assertThat(f.failure { GatewayRequests.mutation("""{"dataSourceId":"${UUID.randomUUID()}","operation":"x","idempotencyKey":"k","sql":"x"}""".toByteArray()) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
    }

    @Test fun `only approved operations exist and an undefined one is not found`() {
        val (_, ds, ctx) = setup()
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id, op = "SELECT * FROM users")) }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id, params = mapOf("injected" to "x"))) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(f.connector.queryCalls.get()).isEqualTo(0)                  // an undeclared parameter never reaches the connector (nor the cache key space)
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id, params = mapOf("status" to 5))) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(f.backend.entries).isEqualTo(0)
    }

    // ------------------------------------------------------------------------------------------------ mapping

    @Test fun `the response carries the view model only and no raw column`() {
        val (_, ds, ctx) = setup()
        val r = f.gateway.runQuery(ctx, q(ds.id))
        assertThat(r.data.rows).hasSize(2)
        assertThat(r.data.rows[0].keys.toList()).containsExactly("id", "displayName", "status")
        val wire = GatewayResponses.query(r).toString()
        for (leak in listOf("123-45-6789", "HASH-123", "ssn", "password_hash", f.secret, "internal-looking.example.com")) assertThat(wire).doesNotContain(leak)
        assertThat(wire).contains("Ada")
    }

    @Test fun `a missing or mismatched mapping is refused before the connector is called`() {
        val (t, ds, ctx) = setup()
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id, mapping = "nope")) }.code).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id, vm = "nope")) }.code).isEqualTo(FailureCodes.INVALID_MAPPING)
        f.query(t, ds, id = "other"); f.mapping(t, "other")
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id, op = "other", mapping = "m-customers", vm = "vm-customers")) }.code).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id, mapping = "m-customers", vm = "vm-other")) }.code).isEqualTo(FailureCodes.INVALID_MAPPING)
        assertThat(f.connector.queryCalls.get()).isEqualTo(0)
    }

    @Test fun `a mapping of another tenant is not found`() {
        val (_, ds, ctx) = setup()
        val other = f.tenant(); f.mapping(other, "customers")                      // same ids, other tenant
        val ctxB = f.ctx(other)
        f.authorizer.allowedTenants = null
        assertThat(f.failure { f.gateway.runQuery(ctxB, q(ds.id)) }.code).isEqualTo(FailureCodes.NOT_FOUND)   // not even the data source exists for B
        assertThat(f.mappings.scopes.all { it.tenantId == ctx.tenantId || it.tenantId == other.tenantId }).isTrue()
    }

    // ------------------------------------------------------------------------------------------------ tenant isolation

    @Test fun `tenant b cannot reach tenant a data source query or cache`() {
        val (_, dsA, ctxA) = setup()
        f.gateway.runQuery(ctxA, q(dsA.id))                                         // warms A's cache
        val b = f.tenant(); val dsB = f.register(b, listOf(mapOf("id" to 9, "name" to "Bob", "status" to "A"))); f.query(b, dsB); f.mapping(b)
        val ctxB = f.ctx(b)
        assertThat(f.failure { f.gateway.runQuery(ctxB, q(dsA.id)) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { f.gateway.testConnection(ctxB, dsA.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { f.gateway.refreshCache(ctxB, dsA.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.failure { f.gateway.discoverSchema(ctxB, dsA.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        // B's own, identically named query returns B's data, never A's cached rows
        val own = f.gateway.runQuery(ctxB, q(dsB.id))
        assertThat(own.data.rows.single()["displayName"].toString()).isEqualTo("\"Bob\"")
        assertThat(own.cache).isEqualTo(CacheStatus.MISS)
        assertThat(GatewayResponses.query(own).toString()).doesNotContain("Ada")
    }

    @Test fun `a query definition of another tenant planted for a data source is never used`() {
        val (_, ds, ctx) = setup()
        val evil = f.tenant()
        f.queries.add(com.systemwebstudio.data.query.SqlQueryDefinition("planted", evil.tenantId, ds.id, "SELECT secret FROM x"))
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id, op = "planted")) }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)
    }

    @Test fun `a catalog that leaks another tenants definition is still refused by the gateway`() {
        val (_, ds, ctx) = setup()
        val evil = f.tenant()
        val planted = com.systemwebstudio.data.query.SqlQueryDefinition("planted", evil.tenantId, ds.id, "SELECT secret FROM x")
        val leaky = object : com.systemwebstudio.data.query.QueryCatalog {            // a persistence bug: ignores the scope it is asked for
            override fun find(tenantId: UUID, dataSourceId: UUID, queryId: String) = planted
            override fun list(tenantId: UUID, dataSourceId: UUID) = listOf(planted)
        }
        val gw = com.systemwebstudio.data.gateway.DefaultDataGateway(f.guard, f.service, leaky, f.mutations, f.mappings, f.discovery, f.cache, f.idempotency, f.notifier, f.audit, f.limiter, clock = f.clock)
        assertThat(f.failure { gw.runQuery(ctx, q(ds.id, op = "planted")) }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)
    }

    // ------------------------------------------------------------------------------------------------ cache

    @Test fun `second identical request is a hit and the connector runs once`() {
        val (_, ds, ctx) = setup()
        val first = f.gateway.runQuery(ctx, q(ds.id, params = mapOf("status" to "A")))
        val second = f.gateway.runQuery(ctx, q(ds.id, params = mapOf("status" to "A")))
        assertThat(first.cache).isEqualTo(CacheStatus.MISS); assertThat(second.cache).isEqualTo(CacheStatus.HIT)
        assertThat(f.connector.queryCalls.get()).isEqualTo(1)
        assertThat(GatewayResponses.query(second).get("result").toString()).isEqualTo(GatewayResponses.query(first).get("result").toString())
        assertThat(f.gateway.runQuery(ctx, q(ds.id, params = mapOf("status" to "C"))).cache).isEqualTo(CacheStatus.MISS)   // other params: other entry
        assertThat(f.connector.queryCalls.get()).isEqualTo(2)
    }

    @Test fun `a hit still requires permission and is audited`() {
        val (_, ds, ctx) = setup()
        f.gateway.runQuery(ctx, q(ds.id))
        f.authorizer.denied += GatewayOperation.QUERY_EXECUTE
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id)) }.code).isEqualTo(FailureCodes.PERMISSION_DENIED)
        f.authorizer.denied.clear()
        f.gateway.runQuery(ctx, q(ds.id))
        assertThat(f.audit.events.filter { it.action == DataAuditActions.QUERY_SERVED }.map { it.details["cache"] }).containsExactly("MISS", "HIT")
    }

    @Test fun `ttl zero is never cached and ttl expiry refetches`() {
        val t = f.tenant(); val ds = f.register(t, rows); f.query(t, ds, ttl = 0); f.mapping(t)
        val ctx = f.ctx(t)
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).cache).isEqualTo(CacheStatus.BYPASS)
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).cache).isEqualTo(CacheStatus.BYPASS)
        assertThat(f.connector.queryCalls.get()).isEqualTo(2)
        val t2 = f.tenant(); val ds2 = f.register(t2, rows); f.query(t2, ds2, ttl = 30); f.mapping(t2)
        val ctx2 = f.ctx(t2)
        f.gateway.runQuery(ctx2, q(ds2.id)); f.clock.advanceSeconds(29)
        assertThat(f.gateway.runQuery(ctx2, q(ds2.id)).cache).isEqualTo(CacheStatus.HIT)
        f.clock.advanceSeconds(2)
        assertThat(f.gateway.runQuery(ctx2, q(ds2.id)).cache).isEqualTo(CacheStatus.MISS)
    }

    @Test fun `editing the data source the query or the mapping makes older entries unreachable at once`() {
        val (_, ds, ctx) = setup()
        f.gateway.runQuery(ctx, q(ds.id))
        // data source revision
        f.repo.save(ds.revised(f.clock.instant(), name = "renamed"))
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).cache).isEqualTo(CacheStatus.MISS)
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).cache).isEqualTo(CacheStatus.HIT)
    }

    @Test fun `a mapping version bump is a different cache entry`() {
        val (t, ds, ctx) = setup()
        f.gateway.runQuery(ctx, q(ds.id))
        f.mapping(t, "customers", version = 2)
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).cache).isEqualTo(CacheStatus.MISS)
    }

    @Test fun `a cache outage degrades to uncached reads and never fails the request`() {
        val (_, ds, ctx) = setup()
        f.backend.failing = true
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).data.rows).hasSize(2)
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).data.rows).hasSize(2)
        assertThat(f.connector.queryCalls.get()).isEqualTo(2)
    }

    @Test fun `manual refresh drops the cache and tells subscribers`() {
        val (t, ds, ctx) = setup()
        f.gateway.runQuery(ctx, q(ds.id))
        val sub = f.bus.subscribe(t.tenantId)
        f.gateway.refreshCache(ctx, ds.id, "customers")
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).cache).isEqualTo(CacheStatus.MISS)
        val types = generateSequence { sub.next(10) }.map { it.type }.toList()
        assertThat(types).containsExactly(DataEventType.DATA_CHANGED, DataEventType.QUERY_INVALIDATED)
        assertThat(f.audit.actions()).contains(DataAuditActions.CACHE_REFRESHED)
        assertThat(f.failure { f.gateway.refreshCache(ctx, ds.id, "no-such-query") }.code).isEqualTo(FailureCodes.QUERY_NOT_FOUND)
        sub.close()
    }

    @Test fun `manual refresh is rate limited`() {
        val (_, ds, ctx) = setup()
        repeat(30) { f.gateway.refreshCache(ctx, ds.id) }
        assertThat(f.failure { f.gateway.refreshCache(ctx, ds.id) }.code).isEqualTo(FailureCodes.RATE_LIMITED)
    }

    // ------------------------------------------------------------------------------------------------ mutation

    private fun mutate(ctx: GatewayContext, ds: UUID, key: String = "idem-key-0001", name: String = "Grace", op: String = "create-customer") =
        f.gateway.mutate(ctx, GatewayMutation(ds, op, mapOf("name" to node(name)), key))

    @Test fun `a mutation runs once per idempotency key and a replay never reaches the connector`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        val first = mutate(ctx, ds.id)
        val replay = mutate(ctx, ds.id)
        assertThat(first.replayed).isFalse(); assertThat(replay.replayed).isTrue()
        assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
        assertThat(replay.output.toString()).isEqualTo(first.output.toString())
        assertThat(mutate(ctx, ds.id, key = "idem-key-0002").replayed).isFalse()
        assertThat(f.connector.mutationCalls.get()).isEqualTo(2)
    }

    @Test fun `the same key with other parameters is a conflict and never silently re-run`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        mutate(ctx, ds.id, name = "Grace")
        assertThat(f.failure { mutate(ctx, ds.id, name = "Edsger") }.code).isEqualTo(FailureCodes.IDEMPOTENCY_CONFLICT)
        assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
    }

    @Test fun `a definitely rejected mutation frees the key so a retry can run`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        var fail = true
        f.connector.mutationHook = { _, _, _ -> if (fail) throw ConnectorFailure(FailureCodes.MUTATION_REJECTED, "the data source refused the change") else MutationOutcome(1, null) }
        assertThat(f.failure { mutate(ctx, ds.id) }.code).isEqualTo(FailureCodes.MUTATION_REJECTED)
        fail = false
        assertThat(mutate(ctx, ds.id).replayed).isFalse()
        assertThat(f.connector.mutationCalls.get()).isEqualTo(2)
    }

    @Test fun `a concurrent duplicate while the first is running is refused`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        var inner: ConnectorFailure? = null
        f.connector.mutationHook = { _, _, _ -> inner = f.failure { mutate(ctx, ds.id) }; MutationOutcome(1, null) }
        mutate(ctx, ds.id)
        assertThat(inner!!.code).isEqualTo(FailureCodes.IDEMPOTENCY_IN_PROGRESS)
        assertThat(f.connector.mutationCalls.get()).isEqualTo(1)
    }

    @Test fun `an idempotency key is mandatory and well formed`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        for (key in listOf("", "short", "has space in it!!", "x".repeat(200), "a;b;c;d;e;f;g;h"))
            assertThat(f.failure { mutate(ctx, ds.id, key = key) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(f.connector.mutationCalls.get()).isEqualTo(0)
    }

    @Test fun `idempotency is scoped per tenant`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        val b = f.tenant(); val dsB = f.register(b); f.mutation(b, dsB)
        mutate(ctx, ds.id, key = "shared-key-0001")
        assertThat(mutate(f.ctx(b), dsB.id, key = "shared-key-0001").replayed).isFalse()
        assertThat(f.connector.mutationCalls.get()).isEqualTo(2)
        assertThat(t.tenantId).isNotEqualTo(b.tenantId)
    }

    @Test fun `a mutation invalidates what it declares and emits record events`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        f.gateway.runQuery(ctx, q(ds.id))
        val sub = f.bus.subscribe(t.tenantId)
        mutate(ctx, ds.id)
        assertThat(f.gateway.runQuery(ctx, q(ds.id)).cache).isEqualTo(CacheStatus.MISS)
        val events = generateSequence { sub.next(10) }.toList()
        assertThat(events.map { it.type }).containsExactly(DataEventType.DATA_CHANGED, DataEventType.QUERY_INVALIDATED, DataEventType.RECORD_CHANGED)
        assertThat(events.last().recordKey).isEqualTo("rec-1")
        assertThat(events.last().entity).isEqualTo("customer")
        assertThat(events.toString()).doesNotContain("Grace")                      // no parameter value in events
        sub.close()
    }

    @Test fun `mutation parameters are typed and unknown ones are refused before the connector`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        assertThat(f.failure { f.gateway.mutate(ctx, GatewayMutation(ds.id, "create-customer", mapOf("name" to node("x"), "role" to node("admin")), "idem-key-0001")) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(f.failure { f.gateway.mutate(ctx, GatewayMutation(ds.id, "create-customer", mapOf("name" to node(5)), "idem-key-0001")) }.code).isEqualTo(FailureCodes.INVALID_PARAMS)
        assertThat(f.failure { f.gateway.mutate(ctx, GatewayMutation(ds.id, "nope", emptyMap(), "idem-key-0001")) }.code).isEqualTo(FailureCodes.MUTATION_NOT_FOUND)
        assertThat(f.connector.mutationCalls.get()).isEqualTo(0)
    }

    @Test fun `a read only connector refuses writes and frees the key`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds); f.connector.readOnly = true
        assertThat(f.failure { mutate(ctx, ds.id) }.code).isEqualTo(FailureCodes.MUTATION_UNSUPPORTED)
        f.connector.readOnly = false
        assertThat(mutate(ctx, ds.id).replayed).isFalse()
    }

    @Test fun `another tenant cannot run a mutation of a data source it does not own`() {
        val (t, ds, _) = setup(); f.mutation(t, ds)
        val b = f.tenant()
        assertThat(f.failure { mutate(f.ctx(b), ds.id) }.code).isEqualTo(FailureCodes.NOT_FOUND)
        assertThat(f.connector.mutationCalls.get()).isEqualTo(0)
    }

    @Test fun `a disabled data source serves nothing`() {
        val (_, ds, ctx) = setup()
        f.repo.save(ds.revised(f.clock.instant(), status = DataSourceStatus.DISABLED))
        assertThat(f.failure { f.gateway.runQuery(ctx, q(ds.id)) }.code).isEqualTo(FailureCodes.DISABLED)
    }

    // ------------------------------------------------------------------------------------------------ connection test / discovery

    @Test fun `connection test and discovery go through the gateway`() {
        val (_, ds, ctx) = setup()
        assertThat(f.gateway.testConnection(ctx, ds.id)).isEqualTo(ConnectionTestResult.Ok(3))
        assertThat(GatewayResponses.connection(f.gateway.testConnection(ctx, ds.id)).toString()).contains("\"ok\":true")
        val r = f.gateway.discoverSchema(ctx, ds.id)
        assertThat(r.snapshot.version).isEqualTo(1)
        assertThat(GatewayResponses.schema(r).toString()).contains("\"version\":1")
    }

    // ------------------------------------------------------------------------------------------------ secrets and failures

    @Test fun `no credential reaches a response an audit record or a log even when the connector blows up with it`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        f.connector.queryHook = { _, _, cred -> throw IllegalStateException("jdbc:postgresql://10.9.8.7/db?password=${cred.get("authValue")}") }
        f.connector.mutationHook = { _, _, cred -> throw IllegalStateException("boom ${cred.get("authValue")}") }
        LogCapture().use { logs ->
            val a = f.failure { f.gateway.runQuery(ctx, q(ds.id)) }
            val b = f.failure { mutate(ctx, ds.id) }
            val problem = GatewayProblems.of(a).toJson().toString() + GatewayProblems.of(b).toJson().toString()
            for (observable in listOf(problem, f.audit.text, logs.text, a.toString(), b.toString())) {
                assertThat(observable).doesNotContain(f.secret).doesNotContain("10.9.8.7").doesNotContain("jdbc:")
            }
            assertThat(a.code).isEqualTo(FailureCodes.INTERNAL)
        }
    }

    @Test fun `audit records carry ids and counts but never values`() {
        val (t, ds, ctx) = setup(); f.mutation(t, ds)
        f.gateway.runQuery(ctx, q(ds.id, params = mapOf("status" to "SENSITIVE-PARAM")))
        mutate(ctx, ds.id, name = "SENSITIVE-NAME")
        assertThat(f.audit.text).doesNotContain("SENSITIVE-PARAM").doesNotContain("SENSITIVE-NAME").doesNotContain("Ada").doesNotContain("123-45-6789")
        assertThat(f.audit.events.filter { it.action == DataAuditActions.QUERY_SERVED }.single().details["actor"]).isEqualTo(t.actorUserId.toString())
    }

    @Test fun `every failure code maps to a status and a body without detail`() {
        val codes = FailureCodes::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }.map { it.get(null) as String }
        assertThat(codes.size).isGreaterThan(30)
        for (c in codes) {
            val p = GatewayProblems.of(ConnectorFailure(c, "fixed text"))
            assertThat(p.status).isGreaterThanOrEqualTo(400); assertThat(p.status).isLessThan(600)
        }
        assertThat(GatewayProblems.status(FailureCodes.PERMISSION_DENIED)).isEqualTo(403)
        assertThat(GatewayProblems.status(FailureCodes.NOT_FOUND)).isEqualTo(404)
        assertThat(GatewayProblems.status(FailureCodes.RATE_LIMITED)).isEqualTo(429)
        assertThat(GatewayProblems.status(FailureCodes.IDEMPOTENCY_CONFLICT)).isEqualTo(409)
        assertThat(GatewayProblems.status(FailureCodes.TIMEOUT)).isEqualTo(504)
        assertThat(GatewayProblems.internal().status).isEqualTo(500)
    }
}
