package com.systemwebstudio.data.org

import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun median(v: List<Double>): Double = v.sorted().let { if (it.isEmpty()) 0.0 else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
fun percentile(v: List<Double>, p: Double): Double = v.sorted().let { if (it.isEmpty()) 0.0 else it[((it.size - 1) * p).toInt()] }
fun f2(d: Double) = "%.2f".format(d)

/** What `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` says about one execution. */
class PlanFacts(val planningMs: Double, val executionMs: Double, val rows: Long, val sharedHit: Long, val sharedRead: Long, val nodes: List<String>)

class Measurement(
    val name: String, val rows: Int, val firstExecMs: Double, val medianExecMs: Double, val planningMs: Double, val wallFirstMs: Double, val wallMedianMs: Double, val wallP95Ms: Double,
    val hit: Long, val read: Long, val nodes: List<String>
)

/** EXPLAIN + wall-clock runner. "first" = the first execution after seeding + ANALYZE (plan-cold, not disk-cold: the data was just written). */
class BenchRunner(private val jdbc: JdbcTemplate) {
    private val json = JsonMapper.builder().build()
    val results = mutableListOf<Measurement>()

    internal fun explain(q: Q): PlanFacts {
        val text = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + q.sql, String::class.java, *q.args.toTypedArray())!!
        val root = json.readTree(text)[0]; val plan = root["Plan"]; val nodes = LinkedHashSet<String>()
        fun walk(n: JsonNode) {
            val t = n["Node Type"].asString(); val rel = n["Relation Name"]?.asString(); val idx = n["Index Name"]?.asString()
            nodes += when { idx != null -> "$t using $idx"; rel != null -> "$t on $rel"; else -> t }
            n["Plans"]?.forEach { walk(it) }
        }
        walk(plan)
        return PlanFacts(root["Planning Time"].asDouble(), root["Execution Time"].asDouble(), plan["Actual Rows"].asLong(), plan["Shared Hit Blocks"]?.asLong() ?: 0, plan["Shared Read Blocks"]?.asLong() ?: 0, nodes.toList())
    }

    /** [call] runs the real operation (so the wall clock includes execute + fetch + mapping) and returns the number of rows it produced */
    internal fun measure(name: String, q: Q?, runs: Int = 31, call: () -> Int): Measurement {
        val first = q?.let { explain(it) }; val rest = if (q != null) (1..6).map { explain(q) } else emptyList()
        val wall = (0 until runs).map { val t0 = System.nanoTime(); call(); (System.nanoTime() - t0) / 1e6 }
        val rows = call(); val last = rest.lastOrNull() ?: first
        return Measurement(name, rows, first?.executionMs ?: Double.NaN, if (rest.isEmpty()) Double.NaN else median(rest.map { it.executionMs }), last?.planningMs ?: Double.NaN,
            wall[0], median(wall.drop(1)), percentile(wall.drop(1), 0.95), last?.sharedHit ?: 0, last?.sharedRead ?: 0, last?.nodes.orEmpty()).also { results += it }
    }

    fun table(): String = buildString {
        append("| case | rows | EXPLAIN exec ms (first) | EXPLAIN exec ms (median of 6 more) | planning ms | wall ms (first) | wall ms (median) | wall ms (p95) | buffers hit/read | plan nodes |\n|---|---|---|---|---|---|---|---|---|---|\n")
        results.forEach { append("| ${it.name} | ${it.rows} | ${f2(it.firstExecMs)} | ${f2(it.medianExecMs)} | ${f2(it.planningMs)} | ${f2(it.wallFirstMs)} | ${f2(it.wallMedianMs)} | ${f2(it.wallP95Ms)} | ${it.hit}/${it.read} | ${it.nodes.joinToString("; ")} |\n") }
    }
}

// ============================================================================================================================ concurrency harness
class OpResult(val code: String = "OK", val lockWaitNanos: Long = 0)
class Sample(val label: String, val code: String, val latencyMs: Double, val lockWaitMs: Double)

class ConcurrencyReport(
    val name: String, val operations: Int, val threads: Int, val poolSize: Int, val wallMs: Double, val samples: List<Sample>, val maxActiveConnections: Int, val maxThreadsAwaitingConnection: Int
) {
    val outcomes: Map<String, Int> get() = samples.groupingBy { it.code }.eachCount().toSortedMap()
    val throughputPerSec get() = operations / (wallMs / 1000.0)
    val lockTimeouts get() = outcomes[LOCK_TIMEOUT] ?: 0
    val deadlocks get() = outcomes[DEADLOCK] ?: 0
    val versionConflicts get() = outcomes[VERSION_CONFLICT] ?: 0
    fun markdown(): String = buildString {
        val lat = samples.map { it.latencyMs }; val lw = samples.map { it.lockWaitMs }
        append("| $name | $operations | $threads | $poolSize | ${f2(wallMs)} | ${f2(throughputPerSec)} | ${f2(median(lat))} | ${f2(percentile(lat, 0.95))} | ${f2(lat.maxOrNull() ?: 0.0)} | ")
        append("${f2(median(lw))} / ${f2(percentile(lw, 0.95))} / ${f2(lw.maxOrNull() ?: 0.0)} | $lockTimeouts | $deadlocks | $versionConflicts | $maxActiveConnections / $maxThreadsAwaitingConnection | ${outcomes} |\n")
    }
    companion object {
        const val LOCK_TIMEOUT = "LOCK_TIMEOUT"; const val DEADLOCK = "DEADLOCK"; const val VERSION_CONFLICT = "VERSION_CONFLICT"
        const val HEADER = "| scenario | ops | threads | pool | wall ms | ops/s | latency p50 ms | p95 ms | max ms | lock wait p50 / p95 / max ms | lock timeouts | deadlocks | version conflicts | max active / max awaiting connections | outcomes |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n"
    }
}

/** Reusable against any repository: an operation is a lambda returning an [OpResult]; every exception is classified by [classify]. All operations start together (a latch), like a burst of admins. */
class ConcurrencyHarness(private val pool: HikariDataSource, private val classify: (Throwable) -> String) {
    fun run(name: String, operations: List<Pair<String, () -> OpResult>>, threads: Int = operations.size): ConcurrencyReport {
        val executor = Executors.newFixedThreadPool(threads); val start = CountDownLatch(1); val samples = java.util.Collections.synchronizedList(ArrayList<Sample>())
        val mx = pool.hikariPoolMXBean; val maxActive = AtomicInteger(); val maxWaiting = AtomicInteger(); val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val sampler = Thread { while (!stop.get()) { maxActive.accumulateAndGet(mx.activeConnections, ::maxOf); maxWaiting.accumulateAndGet(mx.threadsAwaitingConnection, ::maxOf); Thread.sleep(2) } }.apply { isDaemon = true; start() }
        val futures = operations.map { (label, op) ->
            executor.submit(Callable {
                start.await()
                val t0 = System.nanoTime()
                val r = try { op() } catch (e: Throwable) { OpResult(classify(e)) }
                samples += Sample(label, r.code, (System.nanoTime() - t0) / 1e6, r.lockWaitNanos / 1e6)
            })
        }
        val t0 = System.nanoTime(); start.countDown()
        futures.forEach { it.get(300, TimeUnit.SECONDS) }
        val wall = (System.nanoTime() - t0) / 1e6
        stop.set(true); sampler.join(1000); executor.shutdownNow()
        return ConcurrencyReport(name, operations.size, threads, pool.maximumPoolSize, wall, samples.toList(), maxActive.get(), maxWaiting.get())
    }
}
