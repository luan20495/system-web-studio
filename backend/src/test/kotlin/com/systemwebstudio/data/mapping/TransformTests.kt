package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.query.DataJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TransformTests {
    private fun t(json: String) = Transforms.parse(DataJson.parse(json.toByteArray()))
    private fun run(json: String, v: Any?, row: Map<String, Any?> = emptyMap()): Any? =
        Transforms.apply(t(json), v, row.mapValues { DataJson.toNode(it.value) })
    private fun bad(json: String) { assertThatThrownBy { t(json) }.isInstanceOf(IllegalArgumentException::class.java) }
    private fun fails(json: String, v: Any?) { assertThatThrownBy { run(json, v) }.isInstanceOf(ValueError::class.java) }

    @Test fun `toString toNumber trim lower upper`() {
        assertThat(run("""{"type":"toString"}""", BigDecimal("1.50"))).isEqualTo("1.5")
        assertThat(run("""{"type":"toString"}""", true)).isEqualTo("true")
        assertThat(run("""{"type":"toNumber"}""", " 12.50 ")).isEqualTo(BigDecimal("12.5"))
        fails("""{"type":"toNumber"}""", "12abc")
        fails("""{"type":"toNumber"}""", "1e999999")
        assertThat(run("""{"type":"trim"}""", "  x ")).isEqualTo("x")
        assertThat(run("""{"type":"lower"}""", "AbC")).isEqualTo("abc")
        assertThat(run("""{"type":"upper"}""", "AbC")).isEqualTo("ABC")
        assertThat(run("""{"type":"toNumber"}""", null)).isNull()
    }

    @Test fun `toBoolean uses closed word sets that the author can override`() {
        assertThat(run("""{"type":"toBoolean"}""", "YES")).isEqualTo(true)
        assertThat(run("""{"type":"toBoolean"}""", "off")).isEqualTo(false)
        assertThat(run("""{"type":"toBoolean"}""", BigDecimal.ONE)).isEqualTo(true)
        fails("""{"type":"toBoolean"}""", "maybe")
        fails("""{"type":"toBoolean"}""", BigDecimal("2"))
        assertThat(run("""{"type":"toBoolean","trueValues":["active"],"falseValues":["closed"]}""", "Active")).isEqualTo(true)
        bad("""{"type":"toBoolean","trueValues":["a"],"falseValues":["A"]}""")
    }

    @Test fun `date converts between iso epoch and patterns in a fixed zone`() {
        assertThat(run("""{"type":"date"}""", "2024-03-05T10:00:00+07:00")).isEqualTo("2024-03-05T03:00:00Z")
        assertThat(run("""{"type":"date","output":"date","zone":"Asia/Ho_Chi_Minh"}""", "2024-03-05T20:00:00Z")).isEqualTo("2024-03-06")
        assertThat(run("""{"type":"date","input":"epochSeconds"}""", BigDecimal(1709604000))).isEqualTo("2024-03-05T02:00:00Z")
        assertThat(run("""{"type":"date","input":"epochMillis","output":"epochSeconds"}""", BigDecimal(1709604000123))).isEqualTo(BigDecimal(1709604000))
        assertThat(run("""{"type":"date","input":"dd/MM/yyyy","output":"yyyy-MM-dd"}""", "05/03/2024")).isEqualTo("2024-03-05")
        assertThat(run("""{"type":"date","input":"dd/MM/yyyy HH:mm","zone":"UTC"}""", "05/03/2024 10:30")).isEqualTo("2024-03-05T10:30:00Z")
        fails("""{"type":"date"}""", "yesterday")
        fails("""{"type":"date","input":"dd/MM/yyyy"}""", "2024-03-05")
        fails("""{"type":"date","input":"epochSeconds"}""", BigDecimal("1.5"))
        fails("""{"type":"date","input":"epochSeconds"}""", BigDecimal("99999999999999999"))
        bad("""{"type":"date","zone":"Not/AZone"}""")
        bad("""{"type":"date","output":"yyyy'; DROP"}""")
        bad("""{"type":"date","input":"zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"}""")
    }

    @Test fun `enumMap maps default strict and ignoreCase`() {
        val m = """"map":{"A":"Active","C":"Closed","1":"One"}"""
        assertThat(run("""{"type":"enumMap",$m}""", "A")).isEqualTo("Active")
        assertThat(run("""{"type":"enumMap",$m}""", "Z")).isEqualTo("Z")                 // unmapped passes through by default
        assertThat(run("""{"type":"enumMap",$m,"default":"Unknown"}""", "Z")).isEqualTo("Unknown")
        assertThat(run("""{"type":"enumMap",$m,"default":"Unknown"}""", null)).isEqualTo("Unknown")
        assertThat(run("""{"type":"enumMap",$m,"ignoreCase":true}""", "a")).isEqualTo("Active")
        assertThat(run("""{"type":"enumMap",$m}""", BigDecimal.ONE)).isEqualTo("One")
        fails("""{"type":"enumMap",$m,"strict":true}""", "Z")
        bad("""{"type":"enumMap","map":{}}""")
        bad("""{"type":"enumMap","map":{"a":{"x":1}}}""")
    }

    @Test fun `join reads several fields and skips nulls`() {
        val row = mapOf("first" to "Ada", "last" to "Lovelace", "mid" to null, "n" to 7)
        assertThat(run("""{"type":"join","fields":["first","mid","last"],"separator":" "}""", null, row)).isEqualTo("Ada Lovelace")
        assertThat(run("""{"type":"join","fields":["first","mid","last"],"separator":"-","skipNull":false}""", null, row)).isEqualTo("Ada--Lovelace")
        assertThat(run("""{"type":"join","fields":["first","n"]}""", null, row)).isEqualTo("Ada7")
        assertThat(run("""{"type":"join","fields":["mid"]}""", null, row)).isNull()
        bad("""{"type":"join","fields":[]}""")
        bad("""{"type":"join","fields":["a b; c"]}""")
        assertThatThrownBy { run("""{"type":"join","fields":["o"]}""", null, mapOf("o" to mapOf("k" to 1))) }.isInstanceOf(ValueError::class.java)
    }

    @Test fun `split returns one part or the array and never a regex`() {
        assertThat(run("""{"type":"split","separator":",","index":1}""", "a,b,c")).isEqualTo("b")
        assertThat(run("""{"type":"split","separator":",","index":9}""", "a,b")).isNull()
        assertThat(run("""{"type":"split","separator":","}""", "a,b").toString()).isEqualTo("""["a","b"]""")
        assertThat(run("""{"type":"split","separator":".*","index":0}""", "x.*y")).isEqualTo("x")   // literal separator
        bad("""{"type":"split"}""")
        bad("""{"type":"split","separator":""}""")
        bad("""{"type":"split","separator":"abcdef"}""")
    }

    @Test fun `formula runs the sandboxed expression with the current value and the row`() {
        val r = run("""{"type":"formula","expression":"round(price * qty, 2)"}""", null, mapOf("price" to 2.5, "qty" to 3)) as BigDecimal
        assertThat(r.compareTo(BigDecimal("7.5"))).isEqualTo(0)
        assertThat(run("""{"type":"formula","expression":"value * 2"}""", BigDecimal(4))).isEqualTo(BigDecimal("8"))
        bad("""{"type":"formula","expression":"Runtime.exec('x')"}""")
        bad("""{"type":"formula"}""")
        assertThatThrownBy { run("""{"type":"formula","expression":"1 < 'a'"}""", null) }.isInstanceOf(ValueError::class.java)
    }

    @Test fun `the dsl is closed unknown types and keys are rejected`() {
        bad("""{"type":"javascript","code":"1"}""")
        bad("""{"type":"script"}""")
        bad("""{"type":"toString","code":"x"}""")
        bad("""{"type":"formula","expression":"1","language":"js"}""")
        bad("""{}""")
        bad("""[]""")
        bad("""{"type":5}""")
        assertThat(Transforms.types).containsExactlyInAnyOrder("toString", "toNumber", "toBoolean", "date", "enumMap", "join", "split", "formula", "trim", "lower", "upper")
    }

    @Test fun `value errors never carry the value`() {
        val secret = "TOPSECRETVALUE"
        val ex = runCatching { run("""{"type":"toNumber"}""", secret) }.exceptionOrNull() as ValueError
        assertThat(ex.message).doesNotContain(secret)
        val ex2 = runCatching { run("""{"type":"date"}""", secret) }.exceptionOrNull() as ValueError
        assertThat(ex2.message).doesNotContain(secret)
        val ex3 = runCatching { run("""{"type":"enumMap","map":{"a":"b"},"strict":true}""", secret) }.exceptionOrNull() as ValueError
        assertThat(ex3.message).doesNotContain(secret)
    }
}
