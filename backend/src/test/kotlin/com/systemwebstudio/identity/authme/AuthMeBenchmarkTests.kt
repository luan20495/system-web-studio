package com.systemwebstudio.identity.authme

import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.io.File

/**
 * Benchmark of GET /api/v1/auth/me by number of project memberships. Asserts NO latency (no SLA is defined); it only records. HTTP-level only, so it runs unchanged on
 * the N+1 resolver and on the bulk resolver. One line per N goes to System.err and to <BENCH_DIR, default build/reports/bench>/bench-<label>.txt, label = env / system property BENCH_LABEL (default "run").
 */
@Import(SqlStatementCountingConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthMeBenchmarkTests : AuthMeFixtureBase() {
    private val outDir = File(System.getProperty("BENCH_DIR") ?: System.getenv("BENCH_DIR") ?: "build/reports/bench")
    private val label = (System.getProperty("BENCH_LABEL") ?: System.getenv("BENCH_LABEL"))?.takeIf { it.isNotBlank() } ?: "run"

    @Test
    fun `benchmark auth me by number of project memberships`() {
        val lines = mutableListOf<String>()
        for (n in SIZES) {
            val b = bulk(n)
            val s = login(b.user)
            repeat(2) { me(s) }                                    // warm-up, discarded
            val runs = (1..3).map { measureMe(s) }
            val ms = runs.map { it.millis }
            val line = "N=$n queries=${runs.first().statements} run1_ms=${"%.1f".format(ms[0])} run2_ms=${"%.1f".format(ms[1])} run3_ms=${"%.1f".format(ms[2])} " +
                "median_ms=${"%.1f".format(ms.sorted()[1])} response_bytes=${runs.first().bytes}"
            System.err.println("[AuthMeBenchmark:$label] $line")
            lines += line
        }
        runCatching {
            outDir.mkdirs()
            File(outDir, "bench-$label.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        }.onFailure { System.err.println("[AuthMeBenchmark:$label] could not write ${outDir}/bench-$label.txt: $it") }
    }
}
