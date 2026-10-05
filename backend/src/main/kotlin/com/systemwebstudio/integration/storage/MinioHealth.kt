package com.systemwebstudio.integration.storage

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component("minio")
class MinioHealth(private val storage: StorageProvider) : HealthIndicator {
    override fun health(): Health = if (storage.isHealthy()) Health.up().build() else Health.down().build()
}
