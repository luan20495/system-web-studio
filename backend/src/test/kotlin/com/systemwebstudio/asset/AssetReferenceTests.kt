package com.systemwebstudio.asset

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** asset://<id> references in page schemas: same-project READY assets only, enforced on commit and again at publish. */
class AssetReferenceTests : IntegrationTestBase() {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
    private fun upload(sc: Scenario): String {
        val t = sc.s.body(sc.s.post("${sc.base}/assets/upload-url", """{"fileName":"hero.png","contentType":"image/png","size":${png.size}}"""))
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(t.get("uploadUrl").asString())).header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.discarding())
        val id = t.get("assetId").asString()
        assertThat(sc.s.post("${sc.base}/assets/complete", """{"assetId":"$id"}""").response.status).isEqualTo(200)
        return id
    }
    private fun setHeroImage(sc: Scenario, value: String) =
        sc.s.patch("${sc.base}/schema", """{"expectedRevision":${sc.revision()},"operations":[{"type":"UPDATE_PROP","sectionId":"hero-1","path":"image","value":"$value"}]}""")

    @Test
    fun `own READY asset can be referenced, another project's asset and malformed values cannot`() {
        val sc = scenario(); val other = scenario()
        val mine = upload(sc); val theirs = upload(other)
        assertThat(setHeroImage(sc, "asset://$mine").response.status).isEqualTo(200)
        assertThat(sc.section("Hero")!!.get("props").get("image").asString()).isEqualTo("asset://$mine")
        val foreign = setHeroImage(sc, "asset://$theirs")
        assertThat(foreign.response.status).isEqualTo(400); assertThat(sc.s.body(foreign).get("code").asString()).isEqualTo("ASSET_NOT_FOUND")
        for (bad in listOf("https://evil.example/x.png", "asset://not-a-uuid")) {      // raw URLs and malformed refs violate the registry format
            val r = setHeroImage(sc, bad)
            assertThat(r.response.status).isEqualTo(422); assertThat(sc.s.body(r).get("code").asString()).isEqualTo("SCHEMA_INVALID")
        }
        assertThat(setHeroImage(sc, "").response.status).isEqualTo(200)                       // clearing the image is allowed
    }

    @Test
    fun `publishing fails the policy check when a referenced asset was deleted afterwards`() {
        val sc = scenario(); val id = upload(sc)
        setHeroImage(sc, "asset://$id")
        assertThat(sc.s.delete("${sc.base}/assets/$id").response.status).isEqualTo(204)
        val d = sc.s.body(sc.s.post("${sc.base}/publish", """{"visibility":"PRIVATE","expectedRevision":${sc.revision()}}""", "Idempotency-Key" to "asset-gone-01")).get("id").asString()
        await().atMost(Duration.ofSeconds(30)).until { sc.s.body(sc.s.get("${sc.base}/deployments/$d")).get("status").asString() == "FAILED" }
        assertThat(sc.s.body(sc.s.get("${sc.base}/deployments/$d")).get("error").asString()).contains("no longer exist")
    }

    @Test
    fun `project lookup by id resolves the workspace for members and hides it from others`() {
        val sc = scenario()
        val r = sc.s.get("/api/v1/projects/${sc.projectId}")
        assertThat(r.response.status).isEqualTo(200); assertThat(sc.s.body(r).get("workspaceId").asString()).isEqualTo(sc.ws.toString())
        assertThat(sc.s.body(r).get("permissions").toList().map { it.asString() }).contains("PROJECT_EDIT")
        assertThat(scenario().s.get("/api/v1/projects/${sc.projectId}").response.status).isEqualTo(404)
        assertThat(session().get("/api/v1/projects/${sc.projectId}").response.status).isEqualTo(401)
    }
}
