package com.systemwebstudio.publish

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * C2 final hardening - the parts of the publish / deployment / release / rollback matrix that no other class proves end to end:
 *  - publishes that really arrive at the same moment (HTTP, distinct keys, one revision);
 *  - the SAME deployment delivered several times at once (a duplicate queue message, the sweeper, two workers): one effect, not several;
 *  - a rollback, a duplicate rollback, an unpublish and a roll-forward after the queue consumers were stopped and started again (a worker restart);
 *  - the persisted publish approval: stored, kept by a failed update, reset by a policy change, audited;
 *  - a rollback does not read the policy (documented contract, pinned so a change is a decision, not an accident).
 * The authorization matrix is PublishAuthorizationTests / PublishDeniedTests, the approval gate PublicDataApprovalTests, errors and ordering
 * PublishScopeScenarioTests / ReleaseCasTests / SiteOperationApiTests / PublishApiContractTests.
 */
class PublishHardeningMatrixTests : ScopeIntegrationTestBase() {
    @Autowired lateinit var registry: RabbitListenerEndpointRegistry
    @Autowired lateinit var processor: DeploymentProcessor
    @Autowired lateinit var recovery: DeploymentRecovery

    private var n = 0
    private fun Scenario.setHero(value: String) =
        s.patch("$base/schema", """{"expectedRevision":${revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"title","value":"$value"}]}""")
    private fun Scenario.post(s: com.systemwebstudio.support.ApiSession = this.s, key: String = "hm-${System.nanoTime()}-${n++}") =
        s.post("$base/publish", """{"visibility":"PUBLIC","expectedRevision":${revision()}}""", "Idempotency-Key" to key)
    private fun Scenario.status(id: String) = s.body(s.get("$base/deployments/$id"))
    private fun Scenario.settle(id: String, seconds: Long = 60): tools.jackson.databind.JsonNode {
        await().atMost(Duration.ofSeconds(seconds)).until { status(id).get("status").asString() in setOf("RUNNING", "FAILED") }
        return status(id)
    }
    private fun Scenario.publishAndSettle(): String { val id = s.body(post()).get("id").asString(); assertThat(settle(id).get("status").asString()).isEqualTo("RUNNING"); return id }
    private fun pointer(sc: Scenario) = jdbc.queryForList("SELECT current_deployment_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId).firstOrNull()
    private fun pointerVersion(sc: Scenario) = jdbc.queryForObject("SELECT pointer_version FROM sites WHERE project_id = ?", Long::class.java, sc.projectId)!!
    private fun count(sql: String, vararg a: Any) = jdbc.queryForObject(sql, Long::class.java, *a)!!
    private fun artifacts(sc: Scenario) = count("SELECT count(*) FROM artifacts WHERE project_id = ?", sc.projectId)
    private fun deployments(sc: Scenario) = count("SELECT count(*) FROM deployments WHERE project_id = ?", sc.projectId)
    /** every step status appears once per deployment (SCOPE_BUSY waits and retries are not steps) */
    private fun assertNoRepeatedStep(id: String, what: String) {
        val repeated = jdbc.queryForList("""SELECT status FROM deployment_events WHERE deployment_id = ?::uuid
            AND status IN ('QUEUED','POLICY_CHECK','SECURITY_CHECK','BUILDING','DEPLOYING','RUNNING') GROUP BY status HAVING count(*) > 1""", String::class.java, id)
        assertThat(repeated).describedAs("$what: steps recorded more than once for $id").isEmpty()
    }
    private fun rollback(sc: Scenario, to: String, key: String) = sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"$to"}""", "Idempotency-Key" to key)

    // ------------------------------------------------------------------ concurrent publish

    @Test
    fun `publishes that really arrive at the same moment are all accepted, ordered by the scope, and never doubled or lost`() {
        val sc = scenario(); sc.setHero("Concurrent")
        val sessions = (1..4).map { sessionFor(sc.user.username) }
        val pool = Executors.newFixedThreadPool(4); val go = CountDownLatch(1); val rev = sc.revision()
        try {
            val answers = sessions.mapIndexed { i, s -> pool.submit<Pair<Int, String?>> {
                go.await(); val r = s.post("${sc.base}/publish", """{"visibility":"PUBLIC","expectedRevision":$rev}""", "Idempotency-Key" to "hm-race-$i-${UUID.randomUUID()}")
                r.response.status to runCatching { s.body(r).get("id").asString() }.getOrNull() } }
            go.countDown()
            val results = answers.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.map { it.first }).describedAs("every distinct request is accepted").containsOnly(202)
            val ids = results.map { it.second!! }; assertThat(ids.toSet()).hasSize(4)
            val settled = ids.associateWith { sc.settle(it) }
            // a publish is either RUNNING or explicitly refused for a canonical reason - never anything else, never silently lost
            settled.forEach { (id, d) -> if (d.get("status").asString() == "FAILED") assertThat(d.get("error").asString()).describedAs(id).matches("^\\[(STALE_PUBLISH|SCOPE_BUSY)].*") }
            val running = ids.filter { settled.getValue(it).get("status").asString() == "RUNNING" }
            assertThat(running).isNotEmpty()
            // the pointer is the NEWEST activated intent, and moved exactly once per activation
            val newest = running.maxBy { count("SELECT activation_seq FROM deployments WHERE id = ?::uuid", it) }
            assertThat(pointer(sc)).isEqualTo(UUID.fromString(newest))
            assertThat(pointerVersion(sc)).describedAs("one pointer move per activated release").isEqualTo(running.size.toLong())
            assertThat(artifacts(sc)).describedAs("same content, one artifact").isEqualTo(1L)
            ids.forEach { assertNoRepeatedStep(it, "concurrent publish") }
            assertThat(count("SELECT count(*) FROM sites WHERE project_id = ? AND lease_operation_id IS NOT NULL", sc.projectId)).describedAs("no lease left behind").isZero()
        } finally { pool.shutdownNow() }
    }

    // ------------------------------------------------------------------ duplicate delivery, stuck deployment

    @Test
    fun `one deployment delivered many times at once - a duplicate queue message, the sweeper and two workers - has one effect`() {
        val sc = scenario(); sc.setHero("Duplicate delivery")
        registry.stop()
        val id: String
        try {
            val r = sc.post(); assertThat(r.response.status).isEqualTo(202); id = sc.s.body(r).get("id").asString()
            jdbc.update("UPDATE deployments SET updated_at = now() - interval '10 minutes' WHERE id = ?::uuid", id)        // stuck: nobody picked it up for ten minutes
            recovery.republishStale()                                                                                     // the sweeper adds a second message for it
            assertThat(sc.status(id).get("status").asString()).isEqualTo("QUEUED")
            val pool = Executors.newFixedThreadPool(3); val go = CountDownLatch(1)
            try {
                val direct = (1..3).map { pool.submit { go.await(); processor.process(UUID.fromString(id)) } }                 // two more deliveries, in parallel
                registry.start(); go.countDown()
                direct.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
        } finally { registry.start() }
        val d = sc.settle(id)
        assertThat(d.get("status").asString()).describedAs(d.toString()).isEqualTo("RUNNING")
        assertNoRepeatedStep(id, "duplicate delivery")
        assertThat(deployments(sc)).isEqualTo(1L); assertThat(artifacts(sc)).isEqualTo(1L)
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(id)); assertThat(pointerVersion(sc)).describedAs("the pointer moved once").isEqualTo(1L)
        assertThat(sc.auditCount("PUBLISH")).isEqualTo(1L)
        // and a late delivery of a finished deployment changes nothing at all
        val events = count("SELECT count(*) FROM deployment_events WHERE deployment_id = ?::uuid", id); val audits = sc.auditCount("DEPLOY_STATUS_CHANGE")
        processor.process(UUID.fromString(id)); processor.process(UUID.fromString(id))
        assertThat(count("SELECT count(*) FROM deployment_events WHERE deployment_id = ?::uuid", id)).isEqualTo(events)
        assertThat(sc.auditCount("DEPLOY_STATUS_CHANGE")).isEqualTo(audits); assertThat(pointerVersion(sc)).isEqualTo(1L); assertThat(artifacts(sc)).isEqualTo(1L)
    }

    // ------------------------------------------------------------------ rollback / unpublish after a worker restart

    @Test
    fun `after the queue consumers were stopped and started again, rollback is idempotent, unpublish works and a roll-forward serves again - with no duplicate side effects`() {
        val sc = scenario(); sc.setHero("Release one"); val a = sc.publishAndSettle(); sc.setHero("Release two"); val b = sc.publishAndSettle()
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(b))
        registry.stop(); registry.start()                                                                                  // the worker restarted
        val artifactsBefore = artifacts(sc); val deploymentsBefore = deployments(sc); val pv = pointerVersion(sc)
        assertThat(rollback(sc, a, "hm-rollback-1").response.status).isEqualTo(200)
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(a))
        assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?::uuid", String::class.java, b)).isEqualTo("ROLLED_BACK")
        assertThat(pointerVersion(sc)).isEqualTo(pv + 1)
        // the same request again (a client retry after the restart): the same answer, no second effect
        assertThat(rollback(sc, a, "hm-rollback-1").response.status).isEqualTo(200)
        assertThat(pointerVersion(sc)).isEqualTo(pv + 1); assertThat(sc.auditCount("SITE_ROLLBACK")).isEqualTo(1L)
        assertThat(artifacts(sc)).describedAs("a rollback never rebuilds").isEqualTo(artifactsBefore); assertThat(deployments(sc)).isEqualTo(deploymentsBefore)
        // the same key with another target is refused, and nothing moves
        val reused = rollback(sc, b, "hm-rollback-1"); assertThat(reused.response.status).isEqualTo(409); assertThat(sc.s.body(reused).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED")
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(a))
        // unpublish, then serve the retained release again
        assertThat(sc.s.delete("${sc.base}/site").response.status).isEqualTo(200)
        assertThat(pointer(sc)).isNull(); assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("online").asBoolean()).isFalse()
        assertThat(rollback(sc, a, "hm-rollback-2").response.status).isEqualTo(200)
        assertThat(pointer(sc)).isEqualTo(UUID.fromString(a)); assertThat(artifacts(sc)).isEqualTo(artifactsBefore)
        // a new publish after all of it still works and moves the pointer forward once
        sc.setHero("Release three"); val c = sc.publishAndSettle(); assertThat(pointer(sc)).isEqualTo(UUID.fromString(c))
    }

    // ------------------------------------------------------------------ the persisted approval

    @Test
    fun `the publish approval is persisted, survives a refused update, is reset by a policy change and is audited without any secret`() {
        val sc = scenario()
        fun put(visibility: String, ack: Boolean): org.springframework.test.web.servlet.MvcResult {
            val cur = sc.s.body(sc.s.get("${sc.base}/publish-config")).get("config")
            val rev = if (cur == null || cur.isNull) "" else ""","expectedRevision":${cur.get("revision").asLong()}"""
            return sc.s.put("${sc.base}/publish-config", """{"mode":"STATIC","visibility":"$visibility","requiresAuth":false,"acknowledgePublicData":$ack$rev}""")
        }
        fun approved() = jdbc.queryForObject("SELECT public_data_approved FROM publish_configs WHERE project_id = ?", Boolean::class.java, sc.projectId)
        // data is bound, so a PUBLIC policy needs the explicit acknowledgement
        for (op in listOf("""{"type":"ADD_DATA_SOURCE","definition":{"id":"erp-db","name":"Shop","type":"postgres"}}""",
            """{"type":"ADD_QUERY","definition":{"id":"q-title","name":"q-title","dataSourceRef":"erp-db","operationKey":"shop.title","public":true,"maxRows":1}}""",
            """{"type":"ADD_MAPPING","definition":{"id":"m-title","queryRef":"q-title","fields":[{"from":"title","to":"title"}]}}""",
            """{"type":"ADD_DATA_BINDING","definition":{"id":"b-title","sectionId":"hero-1","prop":"title","queryRef":"q-title","mappingRef":"m-title"}}"""))
            assertThat(sc.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":[$op]}""").response.status).isEqualTo(200)
        val refused = put("PUBLIC", false)
        assertThat(refused.response.status).isEqualTo(422); assertThat(refused.response.contentAsString).contains("PUBLIC_DATA_NOT_APPROVED")
        assertThat(jdbc.queryForList("SELECT 1 FROM publish_configs WHERE project_id = ?", Int::class.java, sc.projectId)).describedAs("a refused update stores nothing").isEmpty()
        assertThat(put("PUBLIC", true).response.status).isEqualTo(200); assertThat(approved()).isTrue()
        assertThat(sc.s.body(sc.s.get("${sc.base}/publish-config")).get("config").get("publicDataApproved").asBoolean()).isTrue()
        assertThat(put("PUBLIC", false).response.status).describedAs("an update without the acknowledgement is refused").isEqualTo(422)
        assertThat(approved()).describedAs("and it does not withdraw the stored approval").isTrue()
        assertThat(put("PRIVATE", false).response.status).isEqualTo(200); assertThat(approved()).describedAs("a policy change resets it").isFalse()
        assertThat(put("PUBLIC", true).response.status).isEqualTo(200); assertThat(approved()).isTrue()
        assertThat(sc.auditCount("UPDATE_PUBLISH_CONFIG")).describedAs("three accepted updates, the refused ones leave no entry").isEqualTo(3L)
        val audit = jdbc.queryForList("SELECT new_value::text FROM audit_events WHERE project_id = ? AND action = 'UPDATE_PUBLISH_CONFIG'", String::class.java, sc.projectId).joinToString()
        assertThat(audit).doesNotContainIgnoringCase("secret").doesNotContainIgnoringCase("password").doesNotContain("\"linkToken\"")      // linkTokenCreated is a flag, never the token
        assertThat(Regex("linkTokenCreated\\W+false").findAll(audit).count()).isEqualTo(3)
    }

    // ------------------------------------------------------------------ P1, pinned: rollback does not read the policy

    @Test
    fun `P1 pinned - a rollback neither reads nor rewrites the publish policy, so it can serve an earlier PUBLIC release after the policy became PRIVATE`() {
        val sc = scenario(); sc.setHero("Public release"); val a = sc.publishAndSettle()                                 // no stored policy: the request decides (legacy)
        assertThat(sc.s.put("${sc.base}/publish-config", """{"mode":"STATIC","visibility":"PRIVATE","requiresAuth":false,"acknowledgePublicData":false}""").response.status).isEqualTo(200)
        sc.setHero("Private release")
        val b = sc.s.body(sc.s.post("${sc.base}/publish", """{"visibility":"PRIVATE","expectedRevision":${sc.revision()}}""", "Idempotency-Key" to "hm-private-${UUID.randomUUID()}")).get("id").asString()
        assertThat(sc.settle(b).get("status").asString()).isEqualTo("RUNNING")
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("visibility").asString()).isEqualTo("PRIVATE")
        val policyRevision = sc.s.body(sc.s.get("${sc.base}/publish-config")).get("config").get("revision").asLong()
        assertThat(rollback(sc, a, "hm-rollback-policy").response.status).isEqualTo(200)
        // documented contract (PublishConfigModel): "a rollback only moves the site pointer to an older deployment: it neither reads nor rewrites this policy"
        assertThat(sc.s.body(sc.s.get("${sc.base}/site")).get("visibility").asString()).describedAs("the restored release keeps the visibility it was accepted with").isEqualTo("PUBLIC")
        val config = sc.s.body(sc.s.get("${sc.base}/publish-config")).get("config")
        assertThat(config.get("visibility").asString()).isEqualTo("PRIVATE"); assertThat(config.get("revision").asLong()).isEqualTo(policyRevision)
    }
}
