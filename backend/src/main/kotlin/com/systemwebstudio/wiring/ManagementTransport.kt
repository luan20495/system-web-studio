package com.systemwebstudio.wiring

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.common.RequestIdFilter
import com.systemwebstudio.common.requestIdOf
import com.systemwebstudio.data.datasource.ConnectorFailure
import com.systemwebstudio.data.datasource.FailureCodes
import com.systemwebstudio.data.gateway.GatewayProblems
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream

/*
 * C0 · HTTP transport of the Management API (docs/contracts/v2/management-api.md section 1 and 4, D-C0-28). C3 owns the domain rules and the fixed-text
 * `ConnectorFailure`s; the HTTP envelope and the body-size limit are transport and live here.
 *
 *  - ONE envelope for every non-2xx answer of the Management routes: `{"code","message","requestId","retryable","details"}` (the runtime-api.md envelope, built by
 *    [RuntimeResponses.error] so there is no second error framework). Errors of the C1 access proof (`ApiException` 404 / 403) and the framework (malformed JSON,
 *    bad path id, wrong method / media type) are mapped by [ManagementExceptionAdvice]; C3 failures by [ManagementErrors.of].
 *  - 401 `AUTHENTICATION_REQUIRED` and 403 `CSRF_INVALID` are written by the platform's security filters before any controller runs (unchanged).
 *  - [ManagementBodyLimitFilter] answers 413 `PAYLOAD_TOO_LARGE` before a body is parsed.
 *  - Nothing here ever echoes a request body, a header value or a parser message.
 */

/** the management routes (the three controllers of `DataManagementControllers.kt`) */
internal val MANAGEMENT_PATH = Regex("^/api/v1/workspaces/[^/]+/(data-sources(/.*)?|projects/[^/]+/data-bindings(/.*)?)$")

object ManagementErrors {
    private val responses = RuntimeResponses(JsonMapper.builder().build())

    /** a source failure is worth retrying later; everything else is a fact about the request */
    private val RETRYABLE = setOf(FailureCodes.RATE_LIMITED, FailureCodes.REFRESH_TOO_SOON, FailureCodes.TIMEOUT)

    fun reply(status: Int, code: String, message: String, requestId: String? = RequestIdFilter.current(), retryable: Boolean = false, retryAfterSeconds: Long? = null): ResponseEntity<JsonNode> {
        val r = responses.error(status, code, message, retryable, requestId)
        val b = ResponseEntity.status(r.status).contentType(MediaType.APPLICATION_JSON)
        if (retryAfterSeconds != null) b.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        return b.body(r.body)
    }

    /** a C3 failure: status and fixed message from [GatewayProblems], `retryable` only where the contract says so */
    fun of(e: ConnectorFailure, requestId: String? = RequestIdFilter.current()): ResponseEntity<JsonNode> {
        val p = GatewayProblems.of(e)
        return reply(p.status, p.code, p.message, requestId, retryable = p.code in RETRYABLE, retryAfterSeconds = p.retryAfterSeconds?.toLong())
    }
}

/**
 * Maps what the Management controllers can throw to the frozen envelope. Mounted with the controllers (same flag); it applies to those controllers only, so the
 * platform-wide `ApiExceptionHandler` and every other API keep their current answers.
 */
@RestControllerAdvice(assignableTypes = [DataSourceManagementController::class, DataDefinitionController::class, DataBindingController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class ManagementExceptionAdvice {
    private val log = LoggerFactory.getLogger(javaClass)

    /** the C1 access proof (404 workspace / project, 403 permission) keeps its status and code */
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException, r: HttpServletRequest): ResponseEntity<JsonNode> =
        ManagementErrors.reply(e.status.value(), e.code, e.message, requestIdOf(r), retryAfterSeconds = e.headers["Retry-After"]?.toLongOrNull())

    @ExceptionHandler(ConnectorFailure::class)
    fun connector(e: ConnectorFailure, r: HttpServletRequest) = ManagementErrors.of(e, requestIdOf(r))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(r: HttpServletRequest) = ManagementErrors.reply(400, FailureCodes.INVALID_PARAMS, "request body is missing or not valid JSON", requestIdOf(r))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(r: HttpServletRequest) = ManagementErrors.reply(400, FailureCodes.INVALID_PARAMS, "a path value is not valid", requestIdOf(r))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(r: HttpServletRequest) = ManagementErrors.reply(405, "METHOD_NOT_ALLOWED", "HTTP method not allowed", requestIdOf(r))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(r: HttpServletRequest) = ManagementErrors.reply(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type", requestIdOf(r))

    /** the class name only: driver / JDK messages can carry hosts, SQL and secrets */
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, r: HttpServletRequest): ResponseEntity<JsonNode> {
        log.error("management request failed unexpectedly: {}", e.javaClass.name)
        return ManagementErrors.reply(500, FailureCodes.INTERNAL, "unexpected error", requestIdOf(r))
    }
}

/**
 * Errors raised while Spring is still choosing a handler (wrong method, wrong media type, no such path) have no controller, so [ManagementExceptionAdvice] cannot see
 * them. For a Management path they get the same envelope; for every other path the existing platform handler answers exactly as before (it is called, not copied).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ManagementRoutingAdvice(private val platform: com.systemwebstudio.common.ApiExceptionHandler) {
    private fun management(r: HttpServletRequest) = MANAGEMENT_PATH.matches(r.requestURI)

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(r: HttpServletRequest): ResponseEntity<*> =
        if (management(r)) ManagementErrors.reply(405, "METHOD_NOT_ALLOWED", "HTTP method not allowed", requestIdOf(r)) else platform.method(r)

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(r: HttpServletRequest): ResponseEntity<*> =
        if (management(r)) ManagementErrors.reply(415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type", requestIdOf(r)) else platform.mediaType(r)

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException::class)
    fun noResource(r: HttpServletRequest): ResponseEntity<*> =
        if (management(r)) ManagementErrors.reply(404, FailureCodes.NOT_FOUND, "not found", requestIdOf(r)) else platform.noResource(r)
}

/**
 * Body-size limit of the Management routes (contract section 1 / 4: `413 PAYLOAD_TOO_LARGE`). Runs after the security filters (an unauthenticated or CSRF-less
 * request is refused first and its body is never read) and before any parsing. At most `limit + 1` bytes are read, so memory is bounded whatever the client
 * sends or declares; an accepted body is replayed to the controller unchanged. The body, its size beyond the limit and its content are never logged.
 */
@Component
@ConditionalOnProperty(prefix = "app.data-platform", name = ["enabled"], havingValue = "true")
class ManagementBodyLimitFilter(
    @Value("\${app.data-platform.management.max-body-bytes:65536}") private val limit: Int,
    private val json: JsonMapper
) : OncePerRequestFilter() {
    init { require(limit in 1024..1_048_576) { "app.data-platform.management.max-body-bytes must be between 1024 and 1048576" } }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method in NO_BODY || !MANAGEMENT_PATH.matches(request.requestURI)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val declared = request.contentLengthLong
        if (declared > limit) return tooLarge(request, response)
        val bytes = request.inputStream.readNBytes(limit + 1)
        if (bytes.size > limit) return tooLarge(request, response)
        chain.doFilter(Replay(request, bytes), response)
    }

    private fun tooLarge(request: HttpServletRequest, response: HttpServletResponse) {
        val r = ManagementErrors.reply(413, FailureCodes.PAYLOAD_TOO_LARGE, "request body is too large", requestIdOf(request))
        response.status = 413
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader(HttpHeaders.CONNECTION, "close")      // the rest of the body is not read
        response.writer.write(json.writeValueAsString(r.body))
    }

    private class Replay(request: HttpServletRequest, private val bytes: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val delegate = ByteArrayInputStream(bytes)
            return object : ServletInputStream() {
                override fun read(): Int = delegate.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
                override fun isFinished(): Boolean = delegate.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) { throw UnsupportedOperationException("blocking reads only") }
            }
        }
        override fun getContentLength(): Int = bytes.size
        override fun getContentLengthLong(): Long = bytes.size.toLong()
        override fun getReader() = java.io.BufferedReader(java.io.InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
    }

    private companion object {
        val NO_BODY = setOf("GET", "HEAD", "OPTIONS")
    }
}
