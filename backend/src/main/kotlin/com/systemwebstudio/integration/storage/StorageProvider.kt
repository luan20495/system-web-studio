package com.systemwebstudio.integration.storage

import java.time.Duration

data class PresignedUpload(val url: String, val method: String, val headers: Map<String, String>)
data class StoredObject(val size: Long, val contentType: String?)

/** Port for object storage. Application code never sees MinIO/S3/Azure/GCS types or credentials. */
interface StorageProvider {
    fun presignUpload(key: String, contentType: String, expires: Duration): PresignedUpload
    fun presignDownload(key: String, expires: Duration): String
    fun stat(key: String): StoredObject?
    fun delete(key: String)
    fun isHealthy(): Boolean
}
