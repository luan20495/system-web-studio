package com.systemwebstudio.integration.storage

import com.systemwebstudio.integration.secrets.SecretProvider
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Object storage for build artifacts (ADR 0009), in its own private bucket. Keys are content-addressed
 * (`<project>/<sha256>/<file>`), written once and never overwritten; the bucket is never public — sites are served through the API
 * behind the sites gateway, so private sites stay private. Also reads uploaded project files from the assets bucket to copy them in.
 */
@Component
class ArtifactStore(
    secrets: SecretProvider,
    @Value("\${app.storage.endpoint}") endpoint: String,
    @Value("\${app.storage.bucket}") private val assetsBucket: String,
    @Value("\${app.storage.artifacts-bucket:studio-artifacts}") private val bucket: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = MinioClient.builder().endpoint(endpoint)
        .credentials(secrets.get("app.storage.access-key").orEmpty(), secrets.get("app.storage.secret-key").orEmpty()).region("us-east-1").build()
    @Volatile private var ready = false

    private fun ensureBucket() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build()); log.info("Created artifacts bucket {}", bucket)
            }
            ready = true
        }
    }

    fun exists(key: String): Boolean = try { ensureBucket(); client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build()); true }
        catch (e: ErrorResponseException) { if (e.errorResponse().code() == "NoSuchKey") false else throw e }

    /** Write-once: an existing key is left untouched (same key = same content, because keys contain the content hash). */
    fun putOnce(key: String, bytes: ByteArray, contentType: String) {
        ensureBucket()
        if (exists(key)) return
        client.putObject(PutObjectArgs.builder().bucket(bucket).`object`(key).data(bytes, bytes.size).contentType(contentType).build())
    }

    fun get(key: String): ByteArray? = try {
        ensureBucket(); client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(key).build()).use { it.readAllBytes() }
    } catch (e: ErrorResponseException) { if (e.errorResponse().code() == "NoSuchKey") null else throw e }

    /** Retention only (cleanup job); keys are content-addressed per project, so a key is never shared across artifact rows. */
    fun delete(key: String) { ensureBucket(); client.removeObject(io.minio.RemoveObjectArgs.builder().bucket(bucket).`object`(key).build()) }

    /** An uploaded project file (assets bucket), to copy into an artifact. */
    fun readAsset(storageKey: String): ByteArray =
        client.getObject(GetObjectArgs.builder().bucket(assetsBucket).`object`(storageKey).build()).use { it.readAllBytes() }
}
