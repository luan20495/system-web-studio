package com.systemwebstudio.asset

import com.systemwebstudio.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Uploads go to a real MinIO container through real presigned URLs. */
class AssetApiTests : IntegrationTestBase() {
    private val http = HttpClient.newHttpClient()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

    private fun requestUpload(sc: Scenario, name: String, type: String, size: Long) =
        sc.s.post("${sc.base}/assets/upload-url", """{"fileName":${json.writeValueAsString(name)},"contentType":"$type","size":$size}""")

    private fun put(url: String, type: String, bytes: ByteArray): Int =
        http.send(HttpRequest.newBuilder(URI(url)).header("Content-Type", type).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    @Test
    fun `upload through presigned URL, complete, list, download and delete`() {
        val sc = scenario()
        val r = requestUpload(sc, "logo.png", "image/png", png.size.toLong())
        assertThat(r.response.status).isEqualTo(200)
        val body = sc.s.body(r)
        val id = body.get("assetId").asString()
        assertThat(put(body.get("uploadUrl").asString(), "image/png", png)).isEqualTo(200)

        val done = sc.s.post("${sc.base}/assets/complete", """{"assetId":"$id"}""")
        assertThat(done.response.status).isEqualTo(200)
        val dto = sc.s.body(done)
        assertThat(dto.get("status").asString()).isEqualTo("READY")
        val dl = http.send(HttpRequest.newBuilder(URI(dto.get("downloadUrl").asString())).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        assertThat(dl.statusCode()).isEqualTo(200)
        assertThat(dl.body()).isEqualTo(png)
        assertThat(sc.s.body(sc.s.get("${sc.base}/assets")).size()).isEqualTo(1)
        assertThat(sc.auditCount("UPLOAD_ASSET")).isEqualTo(1L)

        assertThat(sc.s.post("${sc.base}/assets/complete", """{"assetId":"$id"}""").response.status).isEqualTo(409)   // not replayable
        assertThat(sc.s.delete("${sc.base}/assets/$id").response.status).isEqualTo(204)
        assertThat(sc.s.body(sc.s.get("${sc.base}/assets")).size()).isEqualTo(0)
        assertThat(sc.auditCount("DELETE_ASSET")).isEqualTo(1L)
    }

    @Test
    fun `dangerous types, oversize and path traversal names are rejected or neutralised`() {
        val sc = scenario()
        assertThat(requestUpload(sc, "x.svg", "image/svg+xml", 10).response.status).isEqualTo(415)
        assertThat(requestUpload(sc, "x.html", "text/html", 10).response.status).isEqualTo(415)
        assertThat(requestUpload(sc, "x.exe", "application/octet-stream", 10).response.status).isEqualTo(415)
        assertThat(requestUpload(sc, "big.png", "image/png", 11L * 1024 * 1024).response.status).isEqualTo(413)
        assertThat(requestUpload(sc, "zero.png", "image/png", 0).response.status).isEqualTo(413)
        val ok = sc.s.body(requestUpload(sc, "../../etc/passwd.png", "image/png", 5))
        val key = jdbc.queryForObject("SELECT storage_key FROM assets WHERE id = ?::uuid", String::class.java, ok.get("assetId").asString())!!
        assertThat(key).startsWith("${sc.ws}/${sc.projectId}/").doesNotContain("..").endsWith("/passwd.png")
    }

    @Test
    fun `complete fails without an upload and rejects a size mismatch`() {
        val sc = scenario()
        val a = sc.s.body(requestUpload(sc, "a.png", "image/png", png.size.toLong()))
        assertThat(sc.s.post("${sc.base}/assets/complete", """{"assetId":"${a.get("assetId").asString()}"}""").response.status).isEqualTo(409)

        val b = sc.s.body(requestUpload(sc, "b.png", "image/png", 999))        // declares 999 bytes, uploads 12
        assertThat(put(b.get("uploadUrl").asString(), "image/png", png)).isEqualTo(200)
        assertThat(sc.s.post("${sc.base}/assets/complete", """{"assetId":"${b.get("assetId").asString()}"}""").response.status).isEqualTo(422)
        assertThat(jdbc.queryForObject("SELECT status FROM assets WHERE id = ?::uuid", String::class.java, b.get("assetId").asString())).isEqualTo("DELETED")
    }

    @Test
    fun `viewer cannot upload and other projects cannot see or delete the asset`() {
        val sc = scenario()
        val viewer = fx.user("viewer"); fx.member(sc.ws, viewer, "VIEWER"); fx.projectRole(fx.projects.findById(sc.projectId).get(), viewer, "VIEWER")
        assertThat(sessionFor(viewer.username).post("${sc.base}/assets/upload-url", """{"fileName":"a.png","contentType":"image/png","size":5}""").response.status).isEqualTo(403)

        val a = sc.s.body(requestUpload(sc, "a.png", "image/png", png.size.toLong()))
        put(a.get("uploadUrl").asString(), "image/png", png)
        val id = a.get("assetId").asString()
        sc.s.post("${sc.base}/assets/complete", """{"assetId":"$id"}""")
        val other = scenario()
        assertThat(other.s.delete("${other.base}/assets/$id").response.status).isEqualTo(404)
        assertThat(other.s.body(other.s.get("${other.base}/assets")).size()).isEqualTo(0)
        assertThat(session().get("${sc.base}/assets").response.status).isEqualTo(401)
    }
}
