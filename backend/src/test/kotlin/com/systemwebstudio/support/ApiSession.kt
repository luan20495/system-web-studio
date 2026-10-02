package com.systemwebstudio.support

import jakarta.servlet.http.Cookie
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders as B
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** A browser-like client: keeps cookies, performs the real CSRF handshake. */
class ApiSession(private val mvc: MockMvc, private val json: JsonMapper) {
    private val cookies = LinkedHashMap<String, Cookie>()
    private var csrf: String? = null

    private fun collect(r: MvcResult) = r.response.cookies.forEach { if (it.maxAge == 0) cookies.remove(it.name) else cookies[it.name] = it }

    fun initCsrf(): ApiSession {
        val r = mvc.perform(withCookies(B.get("/api/v1/auth/csrf"))).andReturn()
        collect(r)
        csrf = json.readTree(r.response.contentAsString).get("token").asString()
        return this
    }

    private fun withCookies(b: MockHttpServletRequestBuilder): MockHttpServletRequestBuilder {
        cookies.values.forEach { b.cookie(it) }
        return b
    }

    fun perform(builder: MockHttpServletRequestBuilder, includeCsrf: Boolean = true): MvcResult {
        if (csrf == null) initCsrf()
        withCookies(builder)
        if (includeCsrf) builder.header("X-XSRF-TOKEN", csrf!!)
        val r = mvc.perform(builder).andReturn()
        collect(r)
        return r
    }

    fun login(username: String, password: String = TestFixtures.PASSWORD): MvcResult =
        perform(B.post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"$username","password":"$password"}"""))

    fun get(path: String): MvcResult = perform(B.get(path))
    fun post(path: String, body: String = "{}", vararg headers: Pair<String, String>): MvcResult =
        perform(B.post(path).contentType(MediaType.APPLICATION_JSON).content(body).also { b -> headers.forEach { b.header(it.first, it.second) } })
    fun put(path: String, body: String): MvcResult = perform(B.put(path).contentType(MediaType.APPLICATION_JSON).content(body))
    fun patch(path: String, body: String): MvcResult = perform(B.patch(path).contentType(MediaType.APPLICATION_JSON).content(body))
    fun delete(path: String): MvcResult = perform(B.delete(path))

    fun cookie(name: String): Cookie? = cookies[name]
    fun body(r: MvcResult): JsonNode = json.readTree(r.response.contentAsString)
}
