package com.systemwebstudio.common

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val headers: Map<String, String> = emptyMap()
) : RuntimeException(message) {
    companion object {
        fun notFound(code: String, message: String) = ApiException(HttpStatus.NOT_FOUND, code, message)
        fun forbidden(message: String = "You do not have permission for this action", code: String = "FORBIDDEN") =
            ApiException(HttpStatus.FORBIDDEN, code, message)
        fun conflict(code: String, message: String, details: Map<String, Any?> = emptyMap()) =
            ApiException(HttpStatus.CONFLICT, code, message, details)
        fun badRequest(code: String, message: String, details: Map<String, Any?> = emptyMap()) =
            ApiException(HttpStatus.BAD_REQUEST, code, message, details)
        fun tooManyRequests(message: String, retryAfterSeconds: Long) = ApiException(
            HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message,
            mapOf("retryAfterSeconds" to retryAfterSeconds), mapOf("Retry-After" to retryAfterSeconds.toString())
        )
    }
}

data class ApiError(
    val code: String,
    val message: String,
    val requestId: String?,
    val details: Map<String, Any?> = emptyMap()
)

fun requestIdOf(request: HttpServletRequest?): String? =
    (request?.getAttribute(RequestIdFilter.ATTRIBUTE) as? String) ?: RequestIdFilter.current()

/** For code that runs outside Spring MVC (security filters). */
class ApiErrorWriter(private val json: JsonMapper) {
    fun write(
        request: HttpServletRequest, response: HttpServletResponse, status: HttpStatus,
        code: String, message: String, details: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()
    ) {
        if (response.isCommitted) return
        response.status = status.value()
        response.characterEncoding = "UTF-8"
        response.contentType = "application/json;charset=UTF-8"
        headers.forEach { (k, v) -> response.setHeader(k, v) }
        response.writer.write(json.writeValueAsString(ApiError(code, message, requestIdOf(request), details)))
    }
}
