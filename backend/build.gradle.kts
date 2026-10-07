plugins {
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
}

group = "com.systemwebstudio"
version = "0.1.0-SNAPSHOT"

dependencyLocking {
    lockAllConfigurations()
}

// Security patch overrides of Boot-managed versions (OSV audit 2026-10-04, docs/SECURITY.md): remove once Boot's own BOM includes them.
extra["tomcat.version"] = "11.0.26"            // GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5
extra["jackson-bom.version"] = "3.1.7"         // tools.jackson: GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54 …
extra["jackson-2-bom.version"] = "2.21.7"      // com.fasterxml.jackson (transitive): same advisories
extra["rabbit-amqp-client.version"] = "5.37.0" // GHSA-68mj-5wr7-6fgg, GHSA-6g32-pxv4-2wfj, GHSA-93j5-89vc-pph4, GHSA-jh4v-gfqj-7rhx …

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    implementation("io.minio:minio:9.0.3")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")   // ≥1.85: GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26, GHSA-574f-3g2m-x479 …
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")   // generic OIDC; inert unless OIDC_ENABLED=true
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")                           // traceId in logs; export is opt-in (OTLP endpoint)
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testImplementation("org.testcontainers:testcontainers-minio")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // D-C0-32: the suite caches one Spring context per distinct configuration (queue / data platform / release scope / G3 variants); the default 512 MB test heap ran out
    // after the C4 and C2 imports (OutOfMemoryError, then every later context 'failure threshold exceeded'). A fixed ceiling keeps the result reproducible.
    maxHeapSize = "2g"
}