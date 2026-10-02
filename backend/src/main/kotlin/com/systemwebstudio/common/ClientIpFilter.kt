package com.systemwebstudio.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resolves the real client address and scheme, and ONLY believes X-Forwarded-* when the TCP peer is a configured proxy.
 *
 * With app.proxy.trust=false (default) the headers are ignored entirely. With trust=true the header is honoured only if
 * the direct peer is inside app.proxy.trusted-cidrs; the client address is then the right-most entry of X-Forwarded-For
 * that is not itself a trusted proxy, so entries a client prepends are never used. Audit rows and rate limits read
 * request.remoteAddr, which this wrapper overrides.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class ClientIpFilter(
    @Value("\${app.proxy.trust:false}") private val trust: Boolean,
    @Value("\${app.proxy.trusted-cidrs:}") cidrs: String
) : OncePerRequestFilter() {
    private val matchers = cidrs.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { IpAddressMatcher(it) }

    init {
        require(!trust || matchers.isNotEmpty()) { "app.proxy.trust=true requires app.proxy.trusted-cidrs (TRUSTED_PROXY_CIDRS)" }
    }

    private fun trusted(ip: String) = matchers.any { runCatching { it.matches(ip) }.getOrDefault(false) }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!trust || !trusted(request.remoteAddr)) { chain.doFilter(request, response); return }
        val hops = request.getHeaders("X-Forwarded-For").toList().flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        val client = hops.asReversed().firstOrNull { !trusted(it) && VALID_IP.matches(it) } ?: request.remoteAddr
        val proto = request.getHeader("X-Forwarded-Proto")?.trim()?.lowercase()?.takeIf { it == "https" || it == "http" } ?: request.scheme
        chain.doFilter(object : HttpServletRequestWrapper(request) {
            override fun getRemoteAddr() = client
            override fun getScheme() = proto
            override fun isSecure() = proto == "https"
        }, response)
    }

    companion object { private val VALID_IP = Regex("^[0-9a-fA-F:.]{3,45}$") }
}
