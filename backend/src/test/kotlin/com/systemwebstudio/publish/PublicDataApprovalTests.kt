package com.systemwebstudio.publish

import com.systemwebstudio.integration.storage.ArtifactStore
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID

/**
 * H-C2-07 - `POST /publish` consults the PERSISTED publication policy (publish_configs.public_data_approved, through PublishConfigService), not the browser.
 * A PUBLIC release whose immutable version binds data is refused with 422 PUBLIC_DATA_NOT_APPROVED unless the stored approval is true; the refusal happens
 * before the deployment row, the queue message, the build, the scope lease and the pointer, and rolls the idempotency key back with the request.
 *
 * "Binds data" is the policy's own definition (a non-empty `dataBindings` array), read from the SNAPSHOT of the version that will be deployed.
 */
class PublicDataApprovalTests : ScopeIntegrationTestBase() {
    @Autowired lateinit var sites: SiteService
    @Autowired lateinit var store: ArtifactStore

    // ------------------------------------------------------------------ helpers

    private class Snap(val deployments: Long, val events: Long, val artifacts: Long, val keys: Long, val pointer: String)

    private fun snap(sc: Scenario) = Snap(
        jdbc.queryForObject("SELECT count(*) FROM deployments WHERE project_id = ?", Long::class.java, sc.projectId)!!,
        jdbc.queryForObject("SELECT count(*) FROM deployment_events e JOIN deployments d ON d.id = e.deployment_id WHERE d.project_id = ?", Long::class.java, sc.projectId)!!,
        jdbc.queryForObject("SELECT count(*) FROM artifacts WHERE project_id = ?", Long::class.java, sc.projectId)!!,
        jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE scope_key LIKE ?", Long::class.java, "publish:${sc.projectId}:%")!!,
        jdbc.queryForList("SELECT coalesce(current_deployment_id::text, '-') || '/' || pointer_version || '/' || coalesce(lease_operation_id::text, '-') FROM sites WHERE project_id = ?", String::class.java, sc.projectId).firstOrNull() ?: "no-site-row")

    /** a refused publish creates nothing: no deployment, event, artifact (= no build), key, lease, and the pointer and its version stay */
    private fun assertNothingHappened(sc: Scenario, before: Snap, what: String) {
        Thread.sleep(600)       // a job could only be enqueued for a deployment row; a late one is given time to show
        val after = snap(sc)
        assertThat(after.deployments).describedAs("$what: deployments").isEqualTo(before.deployments)
        assertThat(after.events).describedAs("$what: events").isEqualTo(before.events)
        assertThat(after.artifacts).describedAs("$what: artifacts / build").isEqualTo(before.artifacts)
        assertThat(after.keys).describedAs("$what: idempotency keys").isEqualTo(before.keys)
        assertThat(after.pointer).describedAs("$what: pointer, pointer_version, lease").isEqualTo(before.pointer)
    }

    private fun patch(sc: Scenario, vararg operation: String) =
        sc.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":${operation.joinToString(",", "[", "]")}}""")

    /** the committed document binds data: a slot, a public READ query, a mapping and a binding on the Hero title (the shape the Studio produces) */
    private fun bindData(sc: Scenario) {
        assertThat(patch(sc, """{"type":"ADD_DATA_SOURCE","definition":{"id":"erp-db","name":"Shop","type":"postgres"}}""").response.status).isEqualTo(200)
        assertThat(patch(sc, """{"type":"ADD_QUERY","definition":{"id":"q-title","name":"q-title","dataSourceRef":"erp-db","operationKey":"shop.title","public":true,"maxRows":1}}""",
            """{"type":"ADD_MAPPING","definition":{"id":"m-title","queryRef":"q-title","fields":[{"from":"title","to":"title"}]}}""").response.status).isEqualTo(200)
        assertThat(patch(sc, """{"type":"ADD_DATA_BINDING","definition":{"id":"b-title","sectionId":"hero-1","prop":"title","queryRef":"q-title","mappingRef":"m-title"}}""").response.status).isEqualTo(200)
        assertThat(sc.schema().get("dataBindings").size()).isEqualTo(1)
    }

    /** PUT the stored policy; [ack] is the publisher's explicit confirmation (the only way the approval becomes true) */
    private fun setPolicy(sc: Scenario, visibility: String, ack: Boolean): org.springframework.test.web.servlet.MvcResult {
        val current = sc.s.body(sc.s.get("${sc.base}/publish-config")).get("config")
        val rev = if (current == null || current.isNull) "" else ""","expectedRevision":${current.get("revision").asLong()}"""
        return sc.s.put("${sc.base}/publish-config", """{"mode":"STATIC","visibility":"$visibility","requiresAuth":false,"acknowledgePublicData":$ack$rev}""")
    }
    private fun storedApproval(sc: Scenario) = jdbc.queryForObject("SELECT public_data_approved FROM publish_configs WHERE project_id = ?", Boolean::class.java, sc.projectId)

    private fun publish(sc: Scenario, visibility: String = "PUBLIC", key: String = "pd-" + UUID.randomUUID(), extra: String = "") =
        sc.s.post("${sc.base}/publish", """{"visibility":"$visibility","expectedRevision":${sc.revision()}$extra}""", "Idempotency-Key" to key)

    private fun code(sc: Scenario, r: org.springframework.test.web.servlet.MvcResult) = sc.s.body(r).get("code").asString()

    private fun settle(sc: Scenario, id: String) =
        await().atMost(Duration.ofSeconds(30)).until { sc.s.body(sc.s.get("${sc.base}/deployments/$id")).get("status").asString() in setOf("RUNNING", "FAILED") }

    private fun pointer(sc: Scenario) = jdbc.queryForList("SELECT current_deployment_id FROM sites WHERE project_id = ?", UUID::class.java, sc.projectId).firstOrNull()

    /** a real, verifiable release: a RUNNING deployment with an artifact whose file is in the store */
    private fun release(sc: Scenario): UUID {
        sites.ensureSlug(sc.projectId, "Approval")
        val bytes = ("<h1>approval</h1>" + UUID.randomUUID()).toByteArray()
        val files = listOf(ManifestFile("index.html", bytes.size, StaticSiteBuilder.sha256(bytes), "text/html; charset=utf-8"))
        val sha = StaticSiteBuilder.sha256(json.writeValueAsString(files).toByteArray()); val prefix = "${sc.projectId}/$sha"
        val art = UUID.randomUUID(); val dep = UUID.randomUUID()
        store.putOnce("$prefix/index.html", bytes, "text/html")
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,?,1,?,CAST(? AS jsonb))", art, sc.projectId, sha, prefix, bytes.size, json.writeValueAsString(files))
        val version = jdbc.queryForObject("SELECT id FROM project_versions WHERE project_id = ? ORDER BY version_number LIMIT 1", UUID::class.java, sc.projectId)
        jdbc.update("INSERT INTO deployments (id, workspace_id, project_id, version_id, requested_by, visibility, status, provider, artifact_id) VALUES (?,?,?,?,?,'PUBLIC','RUNNING','static',?)", dep, sc.ws, sc.projectId, version, sc.user.id, art)
        return dep
    }
    private fun activate(sc: Scenario, dep: UUID) {
        jdbc.update("""UPDATE sites SET current_deployment_id = ?, pointer_version = pointer_version + 1, active_seq = (SELECT activation_seq FROM deployments WHERE id = ?), active_operation_id = ?
            WHERE project_id = ?""", dep, dep, dep, sc.projectId)
    }

    // ------------------------------------------------------------------ A. approved

    @Test
    fun `A - a PUBLIC release that binds data is accepted once the stored policy carries the approval`() {
        val sc = scenario(); bindData(sc)
        assertThat(setPolicy(sc, "PUBLIC", ack = true).response.status).isEqualTo(200)
        assertThat(storedApproval(sc)).isTrue()
        val before = snap(sc)
        val r = publish(sc)
        assertThat(r.response.status).isEqualTo(202)
        val id = sc.s.body(r).get("id").asString()
        assertThat(snap(sc).deployments).isEqualTo(before.deployments + 1)                  // exactly one
        settle(sc, id)
    }

    // ------------------------------------------------------------------ B. unapproved

    @Test
    fun `B - PUBLIC with the stored approval false and data bound is 422 PUBLIC_DATA_NOT_APPROVED and nothing starts`() {
        val sc = scenario()
        assertThat(setPolicy(sc, "PUBLIC", ack = false).response.status).isEqualTo(200)      // no data bound yet: no acknowledgement needed, so it is stored as not approved
        bindData(sc)                                                                         // data is added later: the stored approval does not cover it
        assertThat(storedApproval(sc)).isFalse()
        val before = snap(sc)
        val r = publish(sc)
        assertThat(r.response.status).isEqualTo(422)
        assertThat(code(sc, r)).isEqualTo("PUBLIC_DATA_NOT_APPROVED")
        assertThat(sc.s.body(r).get("details").get("issues").get(0).get("field").asString()).isEqualTo("acknowledgePublicData")
        assertNothingHappened(sc, before, "unapproved public data")
    }

    // ------------------------------------------------------------------ C. forged approval

    @Test
    fun `C - a forged approval in the request body changes nothing, the persisted false is the authority`() {
        val sc = scenario(); setPolicy(sc, "PUBLIC", ack = false); bindData(sc)
        val before = snap(sc)
        for (forged in listOf(""","publicDataApproved":true""", ""","acknowledgePublicData":true""", ""","publicDataApproved":true,"acknowledgePublicData":true,"approved":true""")) {
            val r = publish(sc, extra = forged)
            assertThat(r.response.status).describedAs(forged).isEqualTo(422)
            assertThat(code(sc, r)).describedAs(forged).isEqualTo("PUBLIC_DATA_NOT_APPROVED")
        }
        assertThat(storedApproval(sc)).isFalse()                                             // the forged fields were not stored either
        assertNothingHappened(sc, before, "forged approval")
    }

    // ------------------------------------------------------------------ D. stale approval

    @Test
    fun `D - an approval that the server no longer holds is not honoured, whatever the browser remembers`() {
        val sc = scenario(); bindData(sc)
        setPolicy(sc, "PUBLIC", ack = true)
        val seenByBrowser = sc.s.body(sc.s.get("${sc.base}/publish-config")).get("config")
        assertThat(seenByBrowser.get("publicDataApproved").asBoolean()).isTrue()             // the page the user is looking at says "approved"
        // the canonical state is reset on the server (the approval is not a property of the browser)
        jdbc.update("UPDATE publish_configs SET public_data_approved = false WHERE project_id = ?", sc.projectId)
        val before = snap(sc)
        val r = publish(sc)
        assertThat(r.response.status).isEqualTo(422); assertThat(code(sc, r)).isEqualTo("PUBLIC_DATA_NOT_APPROVED")
        assertNothingHappened(sc, before, "stale approval (reset)")
        // and the other way the policy moves on: the publisher changes the app to PRIVATE; a page that still shows the old PUBLIC approval is refused too
        jdbc.update("UPDATE publish_configs SET public_data_approved = true WHERE project_id = ?", sc.projectId)
        assertThat(setPolicy(sc, "PRIVATE", ack = false).response.status).isEqualTo(200)
        assertThat(storedApproval(sc)).isFalse()
        val r2 = publish(sc)
        assertThat(r2.response.status).isEqualTo(409); assertThat(code(sc, r2)).isEqualTo("PUBLISH_POLICY_MISMATCH")
        assertNothingHappened(sc, before, "stale approval (policy changed)")
    }

    // ------------------------------------------------------------------ E. retry / idempotency

    @Test
    fun `E - a refusal keeps no idempotency key, so the same key succeeds once after the approval and then replays`() {
        val sc = scenario(); setPolicy(sc, "PUBLIC", ack = false); bindData(sc)
        val key = "pd-retry-key-1"; val before = snap(sc)
        assertThat(publish(sc, key = key).response.status).isEqualTo(422)
        assertThat(snap(sc).keys).describedAs("the refusal left no reservation behind").isEqualTo(before.keys)
        assertThat(publish(sc, key = key).response.status).describedAs("still unapproved: refused again, deterministically").isEqualTo(422)
        // the publisher confirms on the server (revision-checked), the same key is tried again
        assertThat(setPolicy(sc, "PUBLIC", ack = true).response.status).isEqualTo(200)
        val ok = publish(sc, key = key)
        assertThat(ok.response.status).isEqualTo(202)
        val id = sc.s.body(ok).get("id").asString()
        // the accepted key now replays: same deployment, no second one
        val replay = publish(sc, key = key)
        assertThat(replay.response.status).isEqualTo(202); assertThat(replay.response.getHeader("Idempotent-Replay")).isEqualTo("true")
        assertThat(sc.s.body(replay).get("id").asString()).isEqualTo(id)
        assertThat(snap(sc).deployments).isEqualTo(before.deployments + 1)
        // a replay answers with what was ACCEPTED and does not re-judge it; a NEW key after the approval is withdrawn is refused
        jdbc.update("UPDATE publish_configs SET public_data_approved = false WHERE project_id = ?", sc.projectId)
        assertThat(publish(sc, key = key).response.getHeader("Idempotent-Replay")).isEqualTo("true")
        assertThat(publish(sc, key = "pd-retry-key-2").response.status).isEqualTo(422)
        settle(sc, id)
    }

    // ------------------------------------------------------------------ F. a refused publish never activates, rollback is unchanged

    @Test
    fun `F - release N stays active after a refused N+1, and rollback behaves as before`() {
        val sc = scenario()
        val older = release(sc); val n = release(sc); activate(sc, n)
        setPolicy(sc, "PUBLIC", ack = false); bindData(sc)
        val before = snap(sc)
        assertThat(publish(sc).response.status).isEqualTo(422)
        assertNothingHappened(sc, before, "refused N+1")
        assertThat(pointer(sc)).isEqualTo(n)
        // rollback: to the active release is a no-op, to a retained earlier release still works exactly as it did
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"$n"}""", "Idempotency-Key" to "pd-rollback-1").response.status).isEqualTo(200)
        assertThat(pointer(sc)).isEqualTo(n)
        assertThat(sc.s.post("${sc.base}/site/rollback", """{"deploymentId":"$older"}""", "Idempotency-Key" to "pd-rollback-2").response.status).isEqualTo(200)
        assertThat(pointer(sc)).isEqualTo(older)
    }

    // ------------------------------------------------------------------ G. / H. what must not be affected

    @Test
    fun `G - a non-public publish is not touched by the public-data approval, with or without data bound`() {
        val sc = scenario(); setPolicy(sc, "PRIVATE", ack = false); bindData(sc)
        val a = publish(sc, visibility = "PRIVATE"); assertThat(a.response.status).isEqualTo(202); settle(sc, sc.s.body(a).get("id").asString())
        // a stored PUBLIC policy that is not approved still lets the author publish PRIVATELY: a narrower request is not public (the API cannot store this state with data
        // bound, so it is set directly - it is exactly what the policy looks like when the app gained data after it was set to PUBLIC)
        jdbc.update("UPDATE publish_configs SET visibility = 'PUBLIC', public_data_approved = false WHERE project_id = ?", sc.projectId)
        val b = publish(sc, visibility = "PRIVATE"); assertThat(b.response.status).isEqualTo(202); settle(sc, sc.s.body(b).get("id").asString())
        assertThat(publish(sc, visibility = "PUBLIC").response.status).isEqualTo(422)                     // while the same policy refuses the PUBLIC one
    }

    @Test
    fun `H - a PUBLIC publish without data bound is not touched by the approval, and the version snapshot decides what binds data`() {
        val sc = scenario()
        assertThat(setPolicy(sc, "PUBLIC", ack = false).response.status).isEqualTo(200)
        assertThat(storedApproval(sc)).isFalse()
        val a = publish(sc); assertThat(a.response.status).isEqualTo(202); settle(sc, sc.s.body(a).get("id").asString())
        // the DRAFT is not the authority: a draft whose bindings were removed without a new version cannot dodge the approval of a version that binds data ...
        bindData(sc)
        jdbc.update("UPDATE page_schemas SET schema = schema - 'dataBindings' WHERE project_id = ?", sc.projectId)
        assertThat(sc.s.body(sc.s.get("${sc.base}/schema")).get("schema").has("dataBindings")).isFalse()
        val before = snap(sc)
        val r = publish(sc); assertThat(r.response.status).isEqualTo(422); assertThat(code(sc, r)).isEqualTo("PUBLIC_DATA_NOT_APPROVED")
        assertNothingHappened(sc, before, "draft without bindings, version with")
    }

    // ------------------------------------------------------------------ the policy cannot be widened by the request; no stored policy = unchanged behaviour

    @Test
    fun `a request cannot widen the stored policy - PUBLIC against a PRIVATE policy is 409 with nothing created`() {
        val sc = scenario(); setPolicy(sc, "PRIVATE", ack = false)
        val before = snap(sc)
        val r = publish(sc, visibility = "PUBLIC")
        assertThat(r.response.status).isEqualTo(409); assertThat(code(sc, r)).isEqualTo("PUBLISH_POLICY_MISMATCH")
        assertNothingHappened(sc, before, "policy mismatch")
    }

    @Test
    fun `without a stored policy publish behaves as it always did - the request decides`() {
        val sc = scenario(); bindData(sc)
        val r = publish(sc)
        assertThat(r.response.status).isEqualTo(202)                                         // legacy compatibility (the Public Runtime still needs the approval row to serve any data)
        settle(sc, sc.s.body(r).get("id").asString())
    }

    @Test
    fun `only a publisher may store the approval, and a viewer cannot publish around it`() {
        val sc = scenario(); setPolicy(sc, "PUBLIC", ack = false); bindData(sc)
        val v = fx.user("viewer"); fx.member(sc.ws, v, "VIEWER"); fx.projectRole(fx.projects.findById(sc.projectId).get(), v, "VIEWER")
        val vs = sessionFor(v.username)
        val put = vs.put("${sc.base}/publish-config", """{"mode":"STATIC","visibility":"PUBLIC","requiresAuth":false,"acknowledgePublicData":true,"expectedRevision":1}""")
        assertThat(put.response.status).describedAs(put.response.contentAsString).isEqualTo(403)
        assertThat(storedApproval(sc)).isFalse()
        assertThat(vs.post("${sc.base}/publish", """{"visibility":"PUBLIC","expectedRevision":${sc.revision()}}""", "Idempotency-Key" to "pd-viewer-key").response.status).isEqualTo(403)
    }
}
