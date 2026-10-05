package com.systemwebstudio.common

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.csrf.CsrfException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun respond(
        request: HttpServletRequest, status: HttpStatus, code: String, message: String,
        details: Map<String, Any?> = emptyMap(), headers: Map<String, String> = emptyMap()
    ): ResponseEntity<ApiError> {
        val builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.body(ApiError(code, message, requestIdOf(request), details))
    }

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException, r: HttpServletRequest) = respond(r, e.status, e.code, e.message, e.details, e.headers)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException, r: HttpServletRequest): ResponseEntity<ApiError> {
        val fields = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return respond(r, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", mapOf("fields" to fields))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(r: HttpServletRequest) =
        respond(r, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or not valid JSON")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParam(e: MissingServletRequestParameterException, r: HttpServletRequest) =
        respond(r, HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "Missing parameter: ${e.parameterName}")

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException, r: HttpServletRequest) =
        respond(r, HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Missing header: ${e.headerName}")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(e: MethodArgumentTypeMismatchException, r: HttpServletRequest) =
        respond(r, HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid value for ${e.name}")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(r: HttpServletRequest) = respond(r, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not allowed")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(r: HttpServletRequest) = respond(r, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported content type")

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(r: HttpServletRequest) = respond(r, HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found")

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun stale(r: HttpServletRequest) =
        respond(r, HttpStatus.CONFLICT, "REVISION_CONFLICT", "The resource changed elsewhere; reload before saving.")

    @ExceptionHandler(CsrfException::class)
    fun csrf(r: HttpServletRequest) = respond(r, HttpStatus.FORBIDDEN, "CSRF_INVALID", "Missing or invalid CSRF token")

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(r: HttpServletRequest) = respond(r, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, r: HttpServletRequest): ResponseEntity<ApiError> {
        log.error("Unhandled error requestId={}", requestIdOf(r), e)
        return respond(r, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error")
    }
}
