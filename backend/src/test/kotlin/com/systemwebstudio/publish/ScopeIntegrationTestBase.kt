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
    "app.sites.data-api-base=https://sites.example.test/{slug}/_data"
])
abstract class ScopeIntegrationTestBase : IntegrationTestBase() {
    companion object {
        private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        /** the render worker is a separate service; this stub renders the hero title of the schema it is given */
        val renderStub: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/render-site") { ex ->
                val body = mapper.readTree(ex.requestBody.readBytes())
                val title = body.get("schema").get("sections").firstOrNull { it.get("type").asString() == "Hero" }?.get("props")?.get("title")?.asString() ?: ""
                // the real worker (workers/render) is covered by tests/page-runtime and tests/browser; this double follows its contract: a page with data bindings
                // also carries the one script reference and ships the runtime file. A few titles switch on the failure modes the builder must refuse.
                val bound = (body.get("schema").get("dataBindings")?.size() ?: 0) > 0
                if (title == "__REFUSE__") { val e = mapper.writeValueAsBytes(mapOf("error" to "binding 'b1': query 'q-title' is not public: mark it public in the app definition or remove the binding")); ex.sendResponseHeaders(422, e.size.toLong()); ex.responseBody.use { it.write(e) }; return@createContext }
                val tag = if ((bound && title != "__NO_TAG__") || title == "__ORPHAN_TAG__") "<script src=\"./_runtime/page-runtime.js\" defer></script>" else ""
                val inline = if (title == "__SCRIPT__") "<script>alert(1)</script>" else ""
                val out = linkedMapOf("index.html" to "<!doctype html><html><body><h1>$title</h1>$tag$inline</body></html>", "404.html" to "<!doctype html><title>404</title>")
                if ((bound && title != "__ORPHAN_TAG__") || title == "__UNUSED_RUNTIME__") out["_runtime/page-runtime.js"] = "/* page runtime test double */\n(function () { \"use strict\"; })();\n"
                val bytes = mapper.writeValueAsBytes(mapOf("files" to out))
                ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        @JvmStatic @DynamicPropertySource fun render(registry: DynamicPropertyRegistry) { registry.add("app.render.url") { "http://127.0.0.1:${renderStub.address.port}" } }
    }
}
