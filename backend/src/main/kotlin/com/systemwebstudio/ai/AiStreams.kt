package com.systemwebstudio.ai

import com.systemwebstudio.common.ApiException
import com.systemwebstudio.identity.StudioUserDetails
import com.systemwebstudio.integration.llm.StreamSink
import com.systemwebstudio.settings.SettingsService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

/**
 * Server-sent events for AI prompts (ADR 0014 §streaming). Authorization, quotas and budgets are checked BEFORE a stream starts, so refusals
 * are ordinary HTTP errors. Events: `start` {streamId}, `delta` {text} (raw model output as it arrives), `status` {text}, `result` (the same
 * body the non-streamed endpoint returns, with final usage) and `error` {code, message}. A stream ends early by `POST /api/v1/ai/streams/{id}/cancel`,
 * by the client disconnecting, or by the deadline (Admin → Settings → AI); the partial output and any reported usage are kept.
 */
@Service
class AiStreams(private val settings: SettingsService) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pool = ThreadPoolExecutor(2, 16, 60, TimeUnit.SECONDS, SynchronousQueue()) { r -> Thread(r, "ai-stream").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ai-stream-deadline").apply { isDaemon = true } }
    private val active = ConcurrentHashMap<UUID, Job>()

    inner class Job(val id: UUID, val userId: UUID, override val deadline: Instant, private val emitter: SseEmitter) : StreamSink {
        @Volatile override var cancelled = false
        @Volatile override var inModelCall = false
        @Volatile var worker: Thread? = null
        @Volatile var closed = false
        override fun delta(text: String) = send("delta", mapOf("text" to text))
        override fun status(text: String) = send("status", mapOf("text" to text))
        fun send(event: String, data: Any) {
            if (closed) return
            try { emitter.send(SseEmitter.event().name(event).data(data)) } catch (e: Exception) { closed = true; cancelled = true }
        }
        /** wake a worker blocked on the provider stream (only while it is in the model call: never during database work) */
        fun stop() { if (inModelCall) worker?.interrupt() }
    }

    fun <T : Any> start(userId: UUID, work: (StreamSink) -> T, errorBody: (Exception) -> Map<String, Any?>): SseEmitter {
        // at most 2 concurrent streams per user so one person cannot hold the whole pool
        if (active.values.count { it.userId == userId } >= 2) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_STREAMS_PER_USER", "You already have 2 AI requests running; wait for one to finish or cancel it.")
        val timeout = settings.long("ai.stream-timeout-seconds").coerceIn(10, 900)
        val emitter = SseEmitter((timeout + 30) * 1000)
        val job = Job(UUID.randomUUID(), userId, Instant.now().plusSeconds(timeout), emitter)
        val security = SecurityContextHolder.getContext()
        // reverse proxies (nginx) must not buffer the event stream
        (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() as? org.springframework.web.context.request.ServletRequestAttributes)?.response?.let {
            it.setHeader("X-Accel-Buffering", "no"); it.setHeader("Cache-Control", "no-cache, no-transform")
        }
        emitter.onCompletion { job.closed = true; job.cancelled = true; job.stop() }
        emitter.onError { job.closed = true; job.cancelled = true; job.stop() }
        emitter.onTimeout { job.closed = true; job.cancelled = true; job.stop() }
        active[job.id] = job
        try {
            pool.execute {
                job.worker = Thread.currentThread()
                SecurityContextHolder.setContext(security)
                val deadline = timer.schedule({ job.stop() }, timeout, TimeUnit.SECONDS)
                try {
                    job.send("start", mapOf("streamId" to job.id, "deadline" to job.deadline))
                    val result = work(job)
                    Thread.interrupted()
                    job.send("result", result)
                    emitter.complete()
                } catch (e: Exception) {
                    Thread.interrupted()
                    if (e !is ApiException) log.warn("AI stream failed: {}", e.toString())
                    job.send("error", errorBody(e)); emitter.complete()
                } finally {
                    deadline.cancel(false); active.remove(job.id); job.worker = null; SecurityContextHolder.clearContext()
                }
            }
        } catch (e: RejectedExecutionException) {
            active.remove(job.id)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_BUSY", "Too many AI requests are streaming right now; try again in a moment.")
        }
        return emitter
    }

    fun cancel(userId: UUID, id: UUID): Boolean {
        val job = active[id]?.takeIf { it.userId == userId } ?: return false
        job.cancelled = true; job.stop(); return true
    }

    @PreDestroy fun shutdown() { active.values.forEach { it.cancelled = true; it.stop() }; pool.shutdownNow(); timer.shutdownNow() }
}

@RestController
class AiStreamController(private val streams: AiStreams) {
    @PostMapping("/api/v1/ai/streams/{id}/cancel")
    fun cancel(@PathVariable id: UUID, @AuthenticationPrincipal me: StudioUserDetails): Map<String, Any> {
        if (!streams.cancel(me.userId, id)) throw ApiException.notFound("STREAM_NOT_FOUND", "No running stream with this id")
        return mapOf("cancelled" to true)
    }
}
