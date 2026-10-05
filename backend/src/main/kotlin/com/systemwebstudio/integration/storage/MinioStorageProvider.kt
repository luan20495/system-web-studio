package com.systemwebstudio.integration.storage

import com.systemwebstudio.integration.secrets.SecretProvider
import io.minio.BucketExistsArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import io.minio.Http.Method
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Two clients on purpose: [internal] talks to MinIO from the server; [signer] only computes signatures for
 * the address the BROWSER can reach (the signed host must match). Credentials never leave the backend.
 */
@Component
class MinioStorageProvider(
    secrets: SecretProvider,
    @Value("\${app.storage.endpoint}") endpoint: String,
    @Value("\${app.storage.public-endpoint}") publicEndpoint: String,
    @Value("\${app.storage.bucket}") private val bucket: String
) : StorageProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    private val access = secrets.get("app.storage.access-key").orEmpty()
    private val secret = secrets.get("app.storage.secret-key").orEmpty()
    private val internal = MinioClient.builder().endpoint(endpoint).credentials(access, secret).region("us-east-1").build()
    private val signer = MinioClient.builder().endpoint(publicEndpoint).credentials(access, secret).region("us-east-1").build()
    @Volatile private var bucketReady = false

    private fun ensureBucket() {
        if (bucketReady) return
        synchronized(this) {
            if (bucketReady) return
            if (!internal.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                internal.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
                log.info("Created storage bucket {}", bucket)
            }
            bucketReady = true
        }
    }

    private fun presign(method: Method, key: String, expires: Duration) = signer.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder().method(method).bucket(bucket).`object`(key).expiry(expires.seconds.toInt(), TimeUnit.SECONDS).build()
    )

    override fun presignUpload(key: String, contentType: String, expires: Duration): PresignedUpload {
        ensureBucket()
        return PresignedUpload(presign(Method.PUT, key, expires), "PUT", mapOf("Content-Type" to contentType))
    }

    override fun presignDownload(key: String, expires: Duration): String = presign(Method.GET, key, expires)

    override fun stat(key: String): StoredObject? = try {
        ensureBucket()
        val s = internal.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build())
        StoredObject(s.size(), s.contentType())
    } catch (e: ErrorResponseException) {
        if (e.errorResponse().code() in setOf("NoSuchKey", "NoSuchObject", "NotFound")) null else throw e
    }

    override fun delete(key: String) {
        ensureBucket()
        internal.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
    }

    override fun isHealthy(): Boolean = try { internal.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()); true } catch (e: Exception) { false }
}
