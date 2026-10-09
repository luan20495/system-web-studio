package com.systemwebstudio.data.org

import com.systemwebstudio.organization.OrganizationUnitDto
import com.systemwebstudio.organization.OrganizationUnitRepository
import com.systemwebstudio.organization.TenantStructuralLock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.MvcResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * C0 integration tests of D-C0-52 on the REAL stack (HTTP -> C1 services -> C3 PostgreSQL repositories -> PostgreSQL, `app.organization.persistence-enabled=true`):
 * the directory offset bound, the employee counts, the `ORG_STRUCTURE_BUSY` mapping, the structural lock (who takes it, who must not wait for it) and the maxDepth race.
 *
 * The lock is held by a SECOND transaction (a structural operation "in flight": it has taken the tenant structural lock and has not committed), so every ordering below is deterministic:
 * no sleeps decide an outcome, the only waiting is "this call must NOT have finished while the lock is held".
 */
@TestPropertySource(properties = ["app.organization.structural-lock-timeout-ms=3000"])
class OrganizationIntegrationWiringTests : PostgresOrganizationApiBase() {
    @Autowired lateinit var txm: PlatformTransactionManager
    @Autowired lateinit var unitRepo: OrganizationUnitRepository
    @Autowired lateinit var lock: TenantStructuralLock

    // ------------------------------------------------------------------------------------------------------------------------ a structural operation in flight
    private inner class InFlight(tenant: UUID, work: () -> Unit = {}) {
        private val held = CountDownLatch(1); private val release = CountDownLatch(1); private val pool = Executors.newSingleThreadExecutor()
        private val done: Future<*> = pool.submit { TransactionTemplate(txm).execute { lock.acquire(tenant); work(); held.countDown(); release.await(60, TimeUnit.SECONDS) } }
        init { check(held.await(30, TimeUnit.SECONDS)) { "the in-flight structural transaction did not start" } }
        fun commit() { release.countDown(); done.get(30, TimeUnit.SECONDS); pool.shutdown() }
    }
    private val callers = Executors.newFixedThreadPool(6)
    private fun <T> call(block: () -> T): Future<T> = callers.submit<T> { block() }
    private fun blockedWhileHeld(f: Future<MvcResult>, what: String) { Thread.sleep(700); assertThat(f.isDone).describedAs("$what must wait for the tenant structural lock").isFalse() }

    private fun maxDepthInDb(tenant: UUID): Int = jdbc.queryForObject("""WITH RECURSIVE t AS (SELECT id, 1 AS d FROM organization_units WHERE tenant_id = ? AND parent_id IS NULL AND deleted_at IS NULL
        UNION ALL SELECT c.id, t.d + 1 FROM organization_units c JOIN t ON c.parent_id = t.id WHERE c.tenant_id = ? AND c.deleted_at IS NULL) SELECT coalesce(max(d), 0) FROM t""", Int::class.java, tenant, tenant)!!

    private fun insertViaRepository(c: Company, type: JsonNode, parent: JsonNode, code: String): OrganizationUnitDto =
        unitRepo.insert(OrganizationUnitDto(UUID.randomUUID(), c.id, id(type), id(parent), code, code, 0, json.createObjectNode(), true, 0, Instant.now(), Instant.now()))

    // ------------------------------------------------------------------------------------------------------------------------ MAX_DEPTH race
    private fun threeDeepCompany(): Triple<Company, JsonNode, Triple<JsonNode, JsonNode, JsonNode>> {
        val c = company(); val t = type(c, "unit", rules = """{"maxDepth":3}""")
        val a = unit(c, t, "A"); val b = unit(c, t, "B", a); val k = unit(c, t, "K")
        return Triple(c, t, Triple(a, b, k))
    }

    @Test
    fun `MAX_DEPTH race 1 - an ancestor move in flight, a descendant create must wait and then be refused, the tree never exceeds maxDepth`() {
        val (c, t, n) = threeDeepCompany(); val (a, b, k) = n
        val flight = InFlight(c.id) { checkNotNull(unitRepo.move(c.id, id(a), id(k), null, 0)) }             // A (with B) goes below K: B is now at level 3
        val create = call { c.admin.post(units(c), """{"typeId":"${id(t)}","code":"D","name":"D","parentId":"${id(b)}"}""") }
        blockedWhileHeld(create, "create")
        flight.commit()
        val r = create.get(30, TimeUnit.SECONDS)
        assertThat(r.response.status).isEqualTo(409); assertThat(code(r, c.admin)).isEqualTo("ORG_TYPE_RULE_VIOLATION"); assertThat(reason(c, r)).isEqualTo("MAX_DEPTH")
        assertThat(maxDepthInDb(c.id)).describedAs("deepest level in the database").isLessThanOrEqualTo(3)
    }

    @Test
    fun `MAX_DEPTH race 2 - a descendant create in flight, the ancestor move must wait and then be refused, the tree never exceeds maxDepth`() {
        val (c, t, n) = threeDeepCompany(); val (a, b, k) = n
        val flight = InFlight(c.id) { insertViaRepository(c, t, b, "D") }                                      // D at level 3 below B: valid on the tree as it is now, not yet committed
        val mv = call { move(c, a, k) }                                                                        // A below K would put D at level 4
        blockedWhileHeld(mv, "move")
        flight.commit()
        val r = mv.get(30, TimeUnit.SECONDS)
        assertThat(r.response.status).isEqualTo(409); assertThat(code(r, c.admin)).isEqualTo("ORG_TYPE_RULE_VIOLATION"); assertThat(reason(c, r)).isEqualTo("MAX_DEPTH")
        assertThat(maxDepthInDb(c.id)).describedAs("deepest level in the database").isLessThanOrEqualTo(3)
    }

    // ------------------------------------------------------------------------------------------------------------------------ who takes the lock and who must not wait
    @Test
    fun `structural operations wait for the tenant structural lock - unit create, restore, move and a unit-type rule change`() {
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A"); val b = unit(c, t, "B"); val gone = unit(c, t, "GONE", a)
        assertThat(c.admin.post("${units(c)}/${id(gone)}/archive", """{"expectedVersion":0}""").response.status).isEqualTo(200)
        val flight = InFlight(c.id)
        val create = call { c.admin.post(units(c), """{"typeId":"${id(t)}","code":"NEW","name":"New"}""") }
        val restore = call { c.admin.post("${units(c)}/${id(gone)}/restore", """{"expectedVersion":1}""") }
        val mv = call { move(c, b, a) }
        val rule = call { c.admin.patch("${types(c)}/${id(t)}", """{"rules":{"maxDepth":9},"expectedVersion":0}""") }
        listOf("create" to create, "restore" to restore, "move" to mv, "type rule change" to rule).forEach { (n, f) -> blockedWhileHeld(f, n) }
        flight.commit()
        assertThat(create.get(30, TimeUnit.SECONDS).response.status).isEqualTo(201); assertThat(restore.get(30, TimeUnit.SECONDS).response.status).isEqualTo(200)
        assertThat(mv.get(30, TimeUnit.SECONDS).response.status).isEqualTo(200); assertThat(rule.get(30, TimeUnit.SECONDS).response.status).isEqualTo(200)
    }

    @Test
    fun `non-structural operations never wait for the structural lock - employee, membership, position, grade, catalog, directory, unit metadata`() {
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A")
        val flight = InFlight(c.id)
        try {
            val started = System.nanoTime()
            val e = newEmployee(c); val u = uid(e)
            val m = c.admin.body(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(a)}"}""")); assertThat(m.has("id")).isTrue()
            val pos = c.admin.post(positions(c), """{"code":"P","name":"P"}"""); assertThat(pos.response.status).isEqualTo(201)
            assertThat(c.admin.post(grades(c), """{"code":"G","name":"G"}""").response.status).isEqualTo(201)
            assertThat(c.admin.post("${employees(c)}/$u/positions", """{"membershipId":"${id(m)}","positionId":"${id(c.admin.body(pos))}"}""").response.status).isEqualTo(201)
            assertThat(c.admin.get("${employees(c)}?size=10").response.status).isEqualTo(200)
            assertThat(c.admin.patch("${units(c)}/${id(a)}", """{"name":"A2","expectedVersion":0}""").response.status).isEqualTo(200)
            assertThat(c.admin.post("${types(c)}/${id(t)}/disable", """{"expectedVersion":0}""").response.status).isEqualTo(200)
            assertThat((System.nanoTime() - started) / 1_000_000).describedAs("ms for all of it while the lock was held").isLessThan(2500)       // the lock timeout is 3000 ms: nothing waited
        } finally { flight.commit() }
    }

    // ------------------------------------------------------------------------------------------------------------------------ ORG_STRUCTURE_BUSY
    @Test
    fun `ORG_STRUCTURE_BUSY - 503, retryable, Retry-After = ceil(timeout over 1000), nothing was executed, and the retry succeeds`() {
        val c = company(); val t = type(c, "unit"); unit(c, t, "A"); val before = rows("organization_units", c.id)
        val flight = InFlight(c.id)
        val r = try { c.admin.post(units(c), """{"typeId":"${id(t)}","code":"LATE","name":"Late"}""") } finally { flight.commit() }
        assertThat(r.response.status).isEqualTo(503); assertThat(code(r, c.admin)).isEqualTo("ORG_STRUCTURE_BUSY")
        assertThat(r.response.getHeader("Retry-After")).describedAs("timeout 3000 ms").isEqualTo("3")
        assertThat(c.admin.body(r).get("details").get("retryable").asBoolean()).isTrue(); assertThat(c.admin.body(r).get("details").get("retryAfterSeconds").asInt()).isEqualTo(3)
        assertThat(rows("organization_units", c.id)).describedAs("the refused create executed nothing").isEqualTo(before)
        assertThat(c.admin.post(units(c), """{"typeId":"${id(t)}","code":"LATE","name":"Late"}""").response.status).describedAs("retry after the lock is free").isEqualTo(201)
    }

    @Test
    fun `Retry-After is max(1, ceil(structuralLockTimeoutMs over 1000)) whole seconds`() {
        fun s(ms: Int) = OrgDb(org.springframework.jdbc.core.JdbcTemplate(), DataSourceTransactionManager(), JsonMapper.builder().build(), ms).retryAfterSeconds
        assertThat(listOf(1, 250, 1000, 1001, 2500, 5000, 5001).map(::s)).containsExactly(1, 1, 1, 2, 3, 5, 6)
    }

    // ------------------------------------------------------------------------------------------------------------------------ directory bounds
    @Test
    fun `directory bounds - offset 0 to 10000 and size 1 to 100 are served, everything else is 400 (never an empty page with a total)`() {
        val c = company(); newEmployee(c)
        fun list(q: String) = c.admin.get("${employees(c)}?$q")
        assertThat(list("page=100&size=100").response.status).describedAs("offset 10000").isEqualTo(200)
        assertThat(list("page=2000&size=5").response.status).describedAs("offset 10000").isEqualTo(200)
        listOf("page=101&size=100", "page=2001&size=5", "page=10001&size=1", "page=-1&size=10", "page=0&size=101", "page=0&size=0", "page=0&size=-5").forEach { q ->
            val r = list(q); assertThat(r.response.status).describedAs(q).isEqualTo(400)
        }
        val tooDeep = list("page=101&size=100"); assertThat(code(tooDeep, c.admin)).isEqualTo("OFFSET_TOO_LARGE")
        assertThat(code(list("page=-1&size=10"), c.admin)).isEqualTo("VALIDATION_FAILED"); assertThat(code(list("page=0&size=101"), c.admin)).isEqualTo("VALIDATION_FAILED")
    }

    // ------------------------------------------------------------------------------------------------------------------------ counts
    private fun nodeOf(nodes: List<JsonNode>, code: String): JsonNode? = nodes.firstNotNullOfOrNull { n -> if (n.get("unit").get("code").asString() == code) n else nodeOf(n.get("children").toList(), code) }

    @Test
    fun `counts - direct = active direct memberships, subtree = DISTINCT active employees of the active subtree (duplicates once, ended, disabled and archived excluded)`() {
        val c = company(); val t = type(c, "unit")
        val hq = unit(c, t, "HQ"); val a = unit(c, t, "A", hq); val b = unit(c, t, "B", a); val k = unit(c, t, "K", hq)
        fun join(u: UUID, unit: JsonNode) = c.admin.body(c.admin.post("${employees(c)}/$u/organization-memberships", """{"organizationUnitId":"${id(unit)}"}""")).also { assertThat(it.has("id")).isTrue() }
        val e1 = uid(newEmployee(c)); val e2 = uid(newEmployee(c)); val e3 = uid(newEmployee(c)); val e4 = uid(newEmployee(c)); val e5 = uid(newEmployee(c))
        join(e1, hq); join(e2, a); join(e2, b); join(e3, b); join(e5, a)                                                       // e2 sits twice in the subtree of A
        val ended = join(e4, b); assertThat(c.admin.delete("${employees(c)}/$e4/organization-memberships/${id(ended)}?expectedVersion=${ver(ended)}").response.status).isEqualTo(200)   // history kept, not counted
        assertThat(c.admin.post("${employees(c)}/$e5/disable").response.status).isEqualTo(200)                                  // a disabled employee is not counted
        assertThat(c.admin.post("${units(c)}/${id(k)}/archive", """{"expectedVersion":0}""").response.status).isEqualTo(200)
        fun dir(n: JsonNode?) = n!!.get("directMemberCount").asLong()
        fun sub(n: JsonNode?) = n!!.get("subtreeEmployeeCount").asLong()
        val tree = c.admin.body(c.admin.get(units(c))).toList()
        assertThat(listOf("HQ", "A", "B").map { dir(nodeOf(tree, it)) }).describedAs("direct HQ, A, B").containsExactly(1L, 1L, 2L)
        assertThat(listOf("HQ", "A", "B").map { sub(nodeOf(tree, it)) }).describedAs("subtree HQ, A, B (distinct)").containsExactly(3L, 2L, 2L)
        assertThat(nodeOf(tree, "K")).describedAs("an archived unit is not in the default tree").isNull()
        val detail = c.admin.body(c.admin.get("${units(c)}/${id(hq)}")); assertThat(detail.get("directMemberCount").asLong()).isEqualTo(1); assertThat(detail.get("subtreeEmployeeCount").asLong()).isEqualTo(3)
        assertThat(detail.get("activeMemberCount").asInt()).describedAs("C1's own count of memberships is unchanged").isEqualTo(1)
        val archived = c.admin.body(c.admin.get("${units(c)}/${id(k)}")); assertThat(archived.get("directMemberCount").asLong()).isZero(); assertThat(archived.get("subtreeEmployeeCount").asLong()).isZero()
        val other = company(); assertThat(other.admin.body(other.admin.get(units(other))).size()).describedAs("another company's tree holds none of it").isZero()
    }

    // ------------------------------------------------------------------------------------------------------------------------ rule change vs the existing tree
    @Test
    fun `a unit-type rule change that the existing tree already violates is refused, one it satisfies is applied`() {
        val c = company(); val t = type(c, "unit"); val a = unit(c, t, "A"); val b = unit(c, t, "B", a); unit(c, t, "C", b)
        fun rules(json: String) = c.admin.patch("${types(c)}/${id(t)}", """{"rules":$json,"expectedVersion":${ver(c.admin.body(c.admin.get("${types(c)}/${id(t)}")))}}""")
        val tooShallow = rules("""{"maxDepth":2}"""); assertThat(tooShallow.response.status).isEqualTo(409); assertThat(code(tooShallow, c.admin)).isEqualTo("ORG_TYPE_RULE_VIOLATION"); assertThat(reason(c, tooShallow)).isEqualTo("MAX_DEPTH")
        val noRoot = rules("""{"allowRoot":false}"""); assertThat(noRoot.response.status).isEqualTo(409); assertThat(reason(c, noRoot)).isEqualTo("ROOT_NOT_ALLOWED")
        assertThat(rules("""{"maxDepth":3}""").response.status).isEqualTo(200)
        assertThat(rules("""{"maxDepth":2}""").response.status).describedAs("the stored rule is still maxDepth 3").isEqualTo(409)
    }
}
