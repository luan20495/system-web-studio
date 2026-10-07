package com.systemwebstudio.publish

import com.systemwebstudio.integration.storage.ArtifactStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** What "this artifact can be served" means, against a real MinIO: records, sizes and - before activation, for artifacts small enough - the bytes themselves. */
class StoredArtifactVerifierTests : ScopeIntegrationTestBase() {
    @Autowired lateinit var verifier: StoredArtifactVerifier
    @Autowired lateinit var store: ArtifactStore
    @Autowired lateinit var sites: SiteService

    private class Art(val id: UUID, val keys: List<String>, val bytes: List<ByteArray>)

    private fun artifact(sc: Scenario, files: List<ByteArray> = listOf("<h1>one</h1>".toByteArray(), "<p>two</p>".toByteArray())): Art {
        sites.ensureSlug(sc.projectId, "Verify")
        val manifest = files.mapIndexed { i, b -> ManifestFile(if (i == 0) "index.html" else "p$i/index.html", b.size, StaticSiteBuilder.sha256(b), "text/html; charset=utf-8") }
        val sha = StaticSiteBuilder.sha256(json.writeValueAsString(manifest).toByteArray()) ; val prefix = "${sc.projectId}/$sha-${UUID.randomUUID().toString().take(6)}"
        val id = UUID.randomUUID()
        // the artifact id is content-addressed, so the stored sha is the manifest's; the prefix only has to be unique for this test
        jdbc.update("INSERT INTO artifacts (id, project_id, sha256, storage_prefix, file_count, total_bytes, manifest) VALUES (?,?,?,?,?,?,CAST(? AS jsonb))",
            id, sc.projectId, sha, prefix, manifest.size, files.sumOf { it.size.toLong() }, json.writeValueAsString(manifest))
        val keys = manifest.map { "$prefix/${it.path}" }
        keys.zip(files).forEach { (k, b) -> store.putOnce(k, b, "text/html") }
        return Art(id, keys, files)
    }

    private fun cheapOnly() = StoredArtifactVerifier(jdbc, json, store, contentLimitBytes = 0)

    @Test
    fun `an intact artifact passes both checks`() {
        val a = artifact(scenario())
        assertThat(verifier.verify(a.id).ok).isTrue(); assertThat(verifier.verifyContent(a.id).ok).isTrue()
    }

    @Test
    fun `a file replaced by other bytes of the SAME size passes the cheap check and is caught by the content check`() {
        val a = artifact(scenario())
        val original = a.bytes[0]
        val tampered = ByteArray(original.size) { (original[it] + 1).toByte() }              // same length, different content
        store.delete(a.keys[0]); store.putOnce(a.keys[0], tampered, "text/html")
        assertThat(verifier.verify(a.id).ok).describedAs("the size check alone cannot see it").isTrue()
        val deep = verifier.verifyContent(a.id)
        assertThat(deep.ok).isFalse(); assertThat(deep.reason).contains("index.html").contains("altered").contains("checksum")
    }

    @Test
    fun `the content check also finds a missing file, and a file of the wrong size is found by both`() {
        val missing = artifact(scenario()); store.delete(missing.keys[1])
        assertThat(verifier.verify(missing.id).reason).contains("missing"); assertThat(verifier.verifyContent(missing.id).reason).contains("missing")
        val shorter = artifact(scenario()); store.delete(shorter.keys[0]); store.putOnce(shorter.keys[0], "x".toByteArray(), "text/html")
        assertThat(verifier.verify(shorter.id).reason).contains("bytes, expected"); assertThat(verifier.verifyContent(shorter.id).ok).isFalse()
    }

    @Test
    fun `a limit of zero never re-reads, and an artifact above the limit keeps the cheap check - the gateway still hashes what it serves`() {
        val a = artifact(scenario()); store.delete(a.keys[0]); store.putOnce(a.keys[0], ByteArray(a.bytes[0].size) { 7 }, "text/html")
        assertThat(cheapOnly().verifyContent(a.id).ok).describedAs("limit 0 = content check off").isTrue()
        val small = StoredArtifactVerifier(jdbc, json, store, contentLimitBytes = 5)
        assertThat(small.verifyContent(a.id).ok).describedAs("the artifact is larger than the limit").isTrue()
        assertThat(StoredArtifactVerifier(jdbc, json, store, contentLimitBytes = 1_000_000).verifyContent(a.id).ok).isFalse()
    }

    @Test
    fun `a removed or unknown artifact and a changed manifest fail whatever the mode`() {
        val sc = scenario(); val a = artifact(sc)
        assertThat(verifier.verifyContent(UUID.randomUUID()).reason).contains("does not exist")
        jdbc.update("UPDATE artifacts SET manifest = '[]'::jsonb WHERE id = ?", a.id)
        assertThat(verifier.verifyContent(a.id).ok).isFalse()
        val b = artifact(sc, listOf("<h1>another</h1>".toByteArray())); jdbc.update("UPDATE artifacts SET deleted_at = now() WHERE id = ?", b.id)
        assertThat(verifier.verifyContent(b.id).reason).contains("retention")
    }
}
