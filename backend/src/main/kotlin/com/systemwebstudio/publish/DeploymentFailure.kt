package com.systemwebstudio.publish

import io.minio.errors.ErrorResponseException
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

/** Stable, machine-readable reason of a failed deployment step. Shown as a `[CODE]` prefix of `deployments.error`. */
enum class FailureCode {
    POLICY_REJECTED, SECURITY_REJECTED, BUILD_FAILED, ARTIFACT_MISSING,
    RENDER_UNAVAILABLE, ARTIFACT_STORE_UNAVAILABLE, DATABASE_UNAVAILABLE, STEP_TIMEOUT,
    DEPLOY_FAILED, DEPLOY_TIMEOUT, DEPLOY_STATE_UNKNOWN, VERIFICATION_FAILED, RUNTIME_DEPLOY_FAILED,
    /** a newer release operation already moved the active release (or took the scope while this one was working): this publish must not activate */
    STALE_PUBLISH,
    /** another release operation owned the scope for longer than the publish may wait */
    SCOPE_BUSY,
    INTERNAL_ERROR
}

/**
 * One failed attempt of a step.
 *  - [transient]: the cause is expected to go away, so the step may be retried (bounded, with backoff).
 *  - [ambiguous]: we do not know whether the step took effect (timeout while switching). Never retried blindly and never success:
 *    the processor verifies / restores the previous release and ends in FAILED.
 */
data class StepFailure(val code: FailureCode, val message: String, val transient: Boolean = false, val ambiguous: Boolean = false) {
    /** the text stored in `deployments.error` / event messages: always carries the code and the real reason */
    fun reason(attempts: Int = 1): String {
        val suffix = if (attempts > 1) " (after $attempts attempts)" else ""
        return "[$code] $message$suffix".take(MAX_LENGTH)
    }

    companion object {
        /** deployments.error and deployment_events.message are VARCHAR(1000) */
        const val MAX_LENGTH = 1000
        private val prefix = Regex("^\\[([A-Z_]+)]")

        /** the code of a stored error text, null for errors written before codes existed */
        fun codeOf(error: String?): FailureCode? =
            error?.let { prefix.find(it)?.groupValues?.get(1) }?.let { c -> FailureCode.entries.firstOrNull { it.name == c } }
    }
}

/** Turns what a step threw into a [StepFailure] without losing the cause and without leaking addresses or credentials. */
object FailureClassifier {
    private val url = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val secret = Regex("""(?i)\b(password|passwd|secret|token|api[-_]?key|access[-_]?key|authorization|bearer)\b\s*[=:]?\s*\S+""")
    private val permanentStoreCodes = setOf("AccessDenied", "InvalidAccessKeyId", "SignatureDoesNotMatch", "NoSuchBucket", "InvalidBucketName", "EntityTooLarge", "KeyTooLongError")

    /** by class name, so this classifier stays free of framework types (and unit-testable without Spring on the classpath) */
    private val transientDatabaseTypes = setOf("org.springframework.dao.TransientDataAccessException", "org.springframework.dao.DataAccessResourceFailureException")
    private fun isTransientDatabaseError(t: Throwable): Boolean =
        generateSequence<Class<*>>(t.javaClass) { it.superclass }.any { it.name in transientDatabaseTypes }

    /** message safe to show to project members and to store */
    fun safe(text: String?, max: Int = 300): String =
        (text ?: "").replace(url, "<url>").replace(secret) { "${it.groupValues[1]}=<redacted>" }.replace(Regex("\\s+"), " ").trim().take(max)

    fun classify(step: String, e: Throwable): StepFailure {
        val chain = generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(8).toList()
        val timeout = chain.any { it is TimeoutException || it is HttpTimeoutException || it is java.net.SocketTimeoutException }
        chain.firstOrNull { it is BuildFailure }?.let { b ->
            b as BuildFailure
            return StepFailure(if (b.transient) FailureCode.RENDER_UNAVAILABLE else FailureCode.BUILD_FAILED, safe(b.message ?: "Build failed"), transient = b.transient)
        }
        if (timeout) {
            return if (step == "DEPLOYING") StepFailure(FailureCode.DEPLOY_TIMEOUT, "The deployment did not answer in time; its state is not known", ambiguous = true)
            else StepFailure(FailureCode.STEP_TIMEOUT, "$step did not finish in time", transient = true)
        }
        chain.firstOrNull { it.javaClass.name.startsWith("io.minio.") }?.let { m ->
            val code = (m as? ErrorResponseException)?.errorResponse()?.code()
            return StepFailure(FailureCode.ARTIFACT_STORE_UNAVAILABLE, "Artifact storage failed" + (code?.let { " ($it)" } ?: "") + ": " + safe(m.message, 120),
                transient = code == null || code !in permanentStoreCodes)
        }
        if (chain.any { isTransientDatabaseError(it) }) {
            return StepFailure(FailureCode.DATABASE_UNAVAILABLE, "The database was temporarily unavailable", transient = true)
        }
        if (chain.any { it is IOException }) {
            return when (step) {
                "BUILDING" -> StepFailure(FailureCode.ARTIFACT_STORE_UNAVAILABLE, "I/O error while building the artifact: " + safe(e.message, 120), transient = true)
                "DEPLOYING" -> StepFailure(FailureCode.DEPLOY_FAILED, "I/O error while deploying: " + safe(e.message, 120), transient = true)
                else -> StepFailure(FailureCode.INTERNAL_ERROR, "I/O error during $step: " + safe(e.message, 120), transient = true)
            }
        }
        return StepFailure(FailureCode.INTERNAL_ERROR, "Internal error during $step: ${e.javaClass.simpleName}" + (safe(e.message, 160).takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""))
    }
}
