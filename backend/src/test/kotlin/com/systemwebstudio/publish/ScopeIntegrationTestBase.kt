package com.systemwebstudio.publish

import com.sun.net.httpserver.HttpServer
import com.systemwebstudio.support.IntegrationTestBase
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.net.InetSocketAddress

/**
 * ONE Spring context for every test of the release scope that needs the static provider with a render stub and the scope timings shortened. A
 * context is expensive (pool, broker listeners, schedulers) and the test JVM keeps every distinct one in its cache, so these classes must not each
 * bring their own property set: that exhausted the test heap and took unrelated suites down with it.
 */
@TestPropertySource(properties = [
    "app.deploy.provider=static", "app.sites.origin=https://sites.example.test", "app.sites.studio-origin=https://studio.example.test", "app.render.token=render-test-token",
    "app.deploy.scope-wait-seconds=4", "app.deploy.scope-retry-ms=200", "app.deploy.scope-duplicate-wait-seconds=3", "app.deploy.recovery-interval-ms=600000",
    "app.sites.data-api-base=https://data.dev.example.test/api/v1"
])
abstract class ScopeIntegrationTestBase : IntegrationTestBase() {
    companion object {
        private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        /** the render worker is a separate service; this stub renders the hero title of the schema it is given */
        val renderStub: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/render-site") { ex ->
                val body = mapper.readTree(ex.requestBody.readBytes())
                val title = body.get("schema").get("sections").firstOrNull { it.get("type").asString() == "Hero" }?.get("props")?.get("title")?.asString() ?: ""
                val bytes = mapper.writeValueAsBytes(mapOf("files" to linkedMapOf("index.html" to "<!doctype html><html><body><h1>$title</h1></body></html>", "404.html" to "<!doctype html><title>404</title>")))
                ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        @JvmStatic @DynamicPropertySource fun render(registry: DynamicPropertyRegistry) { registry.add("app.render.url") { "http://127.0.0.1:${renderStub.address.port}" } }
    }
}
