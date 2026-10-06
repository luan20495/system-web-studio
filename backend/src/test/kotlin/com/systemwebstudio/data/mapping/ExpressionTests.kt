package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.query.DataJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** T10: the formula language is a closed, bounded, side-effect free allowlist — not JavaScript, not SpEL, not anything reflective. */
class ExpressionTests {
    private fun row(vararg kv: Pair<String, Any?>): Map<String, tools.jackson.databind.JsonNode> = kv.associate { it.first to DataJson.toNode(it.second) }
    private fun eval(src: String, r: Map<String, tools.jackson.databind.JsonNode> = emptyMap(), cur: Any? = null) = Expression.compile(src).evaluate(r, cur)
    private fun num(s: String) = BigDecimal(s)
    private fun phaseOf(block: () -> Unit): ExpressionException.Phase {
        try { block() } catch (e: ExpressionException) { return e.phase }
        throw AssertionError("expected an ExpressionException")
    }
    private fun compileFails(src: String) { assertThat(phaseOf { Expression.compile(src) }).isEqualTo(ExpressionException.Phase.COMPILE) }
    private fun evalFails(src: String, r: Map<String, tools.jackson.databind.JsonNode> = emptyMap()) { assertThat(phaseOf { eval(src, r) }).isEqualTo(ExpressionException.Phase.EVALUATE) }

    @Test fun `arithmetic is exact decimal with precedence`() {
        assertThat(eval("1 + 2 * 3")).isEqualTo(num("7"))
        assertThat(eval("(1 + 2) * 3")).isEqualTo(num("9"))
        assertThat(eval("0.1 + 0.2")).isEqualTo(num("0.3"))
        assertThat(eval("10 / 4")).isEqualTo(num("2.5"))
        assertThat(eval("7 % 4")).isEqualTo(num("3"))
        assertThat(eval("-price * 2", row("price" to 5))).isEqualTo(num("-10"))
    }

    @Test fun `division by zero and null input give null instead of failing or guessing`() {
        assertThat(eval("1 / 0")).isNull()
        assertThat(eval("1 % 0")).isNull()
        assertThat(eval("a + 1", row("a" to null))).isNull()
        assertThat(eval("missing * 2")).isNull()
    }

    @Test fun `plus is numeric only so text never silently concatenates or coerces`() {
        evalFails("'a' + 'b'")
        evalFails("'1' + 1")
        assertThat(eval("concat('a', 1, true, null, 'b')")).isEqualTo("a1trueb")
        assertThat(eval("num('1') + 1")).isEqualTo(num("2"))
    }

    @Test fun `comparison and logic`() {
        assertThat(eval("1 < 2 && 'b' > 'a'")).isEqualTo(true)
        assertThat(eval("1 == 1.0")).isEqualTo(true)
        assertThat(eval("'1' == 1")).isEqualTo(false)                  // no coercion
        assertThat(eval("null == null")).isEqualTo(true)
        assertThat(eval("a < 3", row("a" to null))).isEqualTo(false)
        assertThat(eval("!(1 > 2) || false")).isEqualTo(true)
        evalFails("1 < 'a'")
        evalFails("1 && true")
    }

    @Test fun `if is lazy and the ternary works`() {
        assertThat(eval("if(true, 1, 1/0)")).isEqualTo(num("1"))
        assertThat(eval("a > 0 ? 'pos' : 'neg'", row("a" to 3))).isEqualTo("pos")
        assertThat(eval("false && (1 < 'x')")).isEqualTo(false)       // right side never evaluated
        assertThat(eval("true || (1 < 'x')")).isEqualTo(true)
    }

    @Test fun `functions of the allowlist`() {
        assertThat(eval("round(2.345, 2)")).isEqualTo(num("2.35"))
        assertThat(eval("floor(-1.5)")).isEqualTo(num("-2"))
        assertThat(eval("ceil(1.2)")).isEqualTo(num("2"))
        assertThat(eval("abs(-3)")).isEqualTo(num("3"))
        assertThat(eval("max(1, 5, 3)")).isEqualTo(num("5"))
        assertThat(eval("min('b', 'a')")).isEqualTo("a")
        assertThat(eval("upper(trim('  ab '))")).isEqualTo("AB")
        assertThat(eval("len('héllo')")).isEqualTo(num("5"))
        assertThat(eval("substr('abcdef', 2, 3)")).isEqualTo("cde")
        assertThat(eval("substr('abc', 10)")).isEqualTo("")
        assertThat(eval("replace('a-b-c', '-', '+')")).isEqualTo("a+b+c")
        assertThat(eval("contains('abc', 'b') && startsWith('abc', 'a') && endsWith('abc', 'c')")).isEqualTo(true)
        assertThat(eval("coalesce(null, null, 'x')")).isEqualTo("x")
        assertThat(eval("isNull(a)", row("a" to null))).isEqualTo(true)
        assertThat(eval("bool('yes')")).isEqualTo(true)
        assertThat(eval("str(1.50)")).isEqualTo("1.5")
        evalFails("min(1, 'a')")
        evalFails("round(1, 11)")
    }

    @Test fun `value is the current pipeline value and col reads awkward column names`() {
        assertThat(eval("value * 2", cur = num("4"))).isEqualTo(num("8"))
        assertThat(eval("col('first name')", row("first name" to "Ada"))).isEqualTo("Ada")
        assertThat(eval("address.city", row("address" to mapOf("city" to "Hue")))).isEqualTo("Hue")
        assertThat(eval("tags.1", row("tags" to listOf("a", "b")))).isEqualTo("b")
        assertThat(Expression.compile("a + col('b.c') + d.e").columns).containsExactlyInAnyOrder("a", "b", "d")
    }

    @Test fun `a column that is an object or array cannot be used as a value`() {
        evalFails("a", row("a" to mapOf("x" to 1)))
        evalFails("b", row("b" to listOf(1, 2)))
    }

    // ------------------------------------------------------------------ sandbox: nothing outside the language is reachable

    @Test fun `javascript java and expression-language injection attempts do not compile`() {
        listOf(
            "Runtime.getRuntime().exec('id')", "java.lang.System.exit(0)", "''.getClass()", "T(java.lang.Runtime).getRuntime()",
            "eval('1')", "function(){return 1}()", "(() => 1)()", "x => x", 
            "require('fs')", "import('x')", "new Date()", "a[0]", "{a:1}", "`x`", "a = 1", "a; b", "a & b", "a | b", "\$a", "@a", "#a", "a ?? b", "a?.b",
            "1 ** 2", "'\\u0041'", "sleep(1000)", "readFile('x')", "system('ls')", "toString()", "col(a)", "col('a', 'b')", "round()", "abs(1, 2)"
        ).filter { runCatching { Expression.compile(it) }.isSuccess }.let { accepted -> assertThat(accepted.toString()).isEqualTo("[]") }
    }

    @Test fun `property access can only walk json data never objects of the host`() {
        // `a.constructor.name` parses as a path over the row's JSON value; with no such key it is simply null — there is no reflection to reach
        assertThat(eval("a.constructor.name", row("a" to mapOf("k" to 1)))).isNull()
        assertThat(eval("a.class", row("a" to mapOf("k" to 1)))).isNull()
        assertThat(eval("a.length", row("a" to "text"))).isNull()
        // bare words that look dangerous elsewhere are just column names here: absent columns read as null, nothing is looked up in the host
        assertThat(eval("this")).isNull()
        assertThat(eval("process.env")).isNull()
        assertThat(eval("a.__proto__", row("a" to mapOf("k" to 1)))).isNull()
    }

    @Test fun `size depth node and step limits hold`() {
        compileFails("1" + " + 1".repeat(200))                                   // > 120 nodes / > 250 tokens
        compileFails("(".repeat(40) + "1" + ")".repeat(40))                      // depth
        compileFails("!".repeat(40) + "true")
        compileFails("'" + "a".repeat(300) + "'")                               // string literal
        compileFails("x".repeat(600))                                            // source length
        compileFails("")
        compileFails("   ")
        compileFails("1" + "0".repeat(40))                                       // literal length
    }

    @Test fun `results cannot grow without bound`() {
        evalFails("replace(a, 'a', b)", row("a" to "a".repeat(9_000), "b" to "x".repeat(9_000)))   // amplification refused before allocating
        evalFails("concat(a, a, a)", row("a" to "x".repeat(6_000)))
        evalFails("99999999999999999999 * 99999999999999999999 * 99999999999999999999 * 99999999999999999999")  // precision
        assertThat(eval("1000000000 * 1000000000")).isEqualTo(num("1000000000000000000"))
    }

    @Test fun `evaluation is deterministic and has no clock random or io`() {
        val e = Expression.compile("round(a * 1.1, 2) + len(concat('x', b))")
        val r = row("a" to 10, "b" to "yy")
        val first = e.evaluate(r)
        repeat(50) { assertThat(e.evaluate(r)).isEqualTo(first) }
        assertThat(Expression.functionNames).doesNotContain("now", "random", "uuid", "eval", "exec", "sleep", "read", "http", "fetch")
    }

    @Test fun `error messages never contain the data`() {
        val secret = "TOPSECRETVALUE"
        val ex = runCatching { eval("a < 1", row("a" to secret)) }.exceptionOrNull() as ExpressionException
        assertThat(ex.message).doesNotContain(secret)
        val ex2 = runCatching { eval("a + 1", row("a" to secret)) }.exceptionOrNull() as ExpressionException
        assertThat(ex2.message).doesNotContain(secret)
    }
}
