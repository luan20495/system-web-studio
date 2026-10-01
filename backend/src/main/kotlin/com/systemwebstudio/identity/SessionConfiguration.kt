package com.systemwebstudio.identity

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer

/** Explicit cookie policy so it cannot silently diverge from auto-configuration. */
@Configuration
class SessionConfiguration {
    @Bean
    fun cookieSerializer(
        @Value("\${server.servlet.session.cookie.name:STUDIO_SESSION}") name: String,
        @Value("\${server.servlet.session.cookie.secure:true}") secure: Boolean,
        @Value("\${server.servlet.session.cookie.same-site:lax}") sameSite: String
    ): CookieSerializer = DefaultCookieSerializer().apply {
        setCookieName(name)
        setCookiePath("/")
        setUseHttpOnlyCookie(true)
        setUseSecureCookie(secure)
        setSameSite(sameSite)
        setUseBase64Encoding(false)
    }
}
