package com.systemwebstudio.data.mapping

import com.systemwebstudio.data.query.DataJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeType
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/*
 * The safe expression language behind the `formula` transform (T10: "Không chạy arbitrary JavaScript; expression engine phải sandbox /
 * allowlisted"). It is **not** an embedding of any scripting engine and it has no way out of itself:
 *
 *   - its own lexer and parser; the only runtime values are null, Boolean, BigDecimal and String;
 *   - a fixed table of functions (below); an unknown name is a compile error, there is no reflection, no method call on a value, no `new`,
 *     no assignment, no loop, no recursion, no I/O, no clock, no random, no access to anything but the current row;
 *   - hard limits: source length, token count, nesting depth, node count, evaluation steps, number magnitude and string length, so a
 *     hostile expression cannot burn CPU or memory (there is deliberately no power operator and no repeat/pad function);
 *   - errors are fixed text and never contain a data value (they may name the function or column the author wrote).
 *
 * Grammar (lowest precedence first):  ternary `c ? a : b`, `||`, `&&`, `== !=`, `< <= > >=`, `+ -`, `* / %`, unary `! -`, then
 * literals (number, 'string' / "string", true, false, null), column references (`price`, `address.city`, `items.0.sku`, `col('First Name')`),
 * `value` (the current pipeline value of the field) and calls `fn(a, b, ...)`.
 */
class ExpressionException(val phase: Phase, message: String) : RuntimeException(message, null, false, false) {
    enum class Phase { COMPILE, EVALUATE }
}

object ExpressionLimits {
    const val MAX_SOURCE = 500
    const val MAX_TOKENS = 250
    const val MAX_DEPTH = 24
    const val MAX_NODES = 120
    const val MAX_STEPS = 2_000
    const val MAX_ARGS = 10
    const val MAX_STRING = 10_000
    const val MAX_PRECISION = 60
    const val MAX_SCALE = 100
}

class Expression private constructor(val source: String, private val root: Node, val columns: Set<String>) {
    /** two expressions are equal when their source text is: the AST is a pure function of it (lets [Transform.Formula] take part in definition equality) */
    override fun equals(other: Any?) = other is Expression && other.source == source
    override fun hashCode() = source.hashCode()
    override fun toString() = "Expression($source)"

    /** @return null, Boolean, BigDecimal or String */
    fun evaluate(row: Map<String, JsonNode>, current: Any? = null): Any? = Evaluator(row, current).eval(root)

    // ------------------------------------------------------------------------------------------------ AST
    internal sealed interface Node
    internal class Lit(val value: Any?) : Node
    internal class Ref(val path: List<String>) : Node
    internal class Col(val name: String) : Node
    internal data object Current : Node
    internal class Unary(val op: String, val a: Node) : Node
    internal class Binary(val op: String, val a: Node, val b: Node) : Node
    internal class Cond(val c: Node, val a: Node, val b: Node) : Node
    internal class Call(val fn: String, val args: List<Node>) : Node

    // ------------------------------------------------------------------------------------------------ lexer
    private enum class T { NUM, STR, ID, OP, EOF }
    private class Tok(val type: T, val text: String, val pos: Int)

    private class Lexer(private val s: String) {
        fun tokens(): List<Tok> {
            val out = ArrayList<Tok>(); var i = 0
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) { out += Tok(T.EOF, "", i); return out }
                if (out.size >= ExpressionLimits.MAX_TOKENS) throw compileError("expression is too long")
                val c = s[i]; val start = i
                when {
                    c.isDigit() -> {
                        while (i < s.length && s[i].isDigit()) i++
                        if (i < s.length && s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()) { i++; while (i < s.length && s[i].isDigit()) i++ }
                        if (i < s.length && (s[i].isLetter() || s[i] == '_')) throw compileError("malformed number at $start")
                        if (i - start > 30) throw compileError("number literal is too long")
                        out += Tok(T.NUM, s.substring(start, i), start)
                    }
                    c == '\'' || c == '"' -> {
                        val sb = StringBuilder(); i++
                        while (true) {
                            if (i >= s.length) throw compileError("unterminated string at $start")
                            val d = s[i]
                            if (d == c) { i++; break }
                            if (d == '\\') {
                                if (i + 1 >= s.length) throw compileError("unterminated string at $start")
                                when (val e = s[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); '\\', '\'', '"' -> sb.append(e); else -> throw compileError("unknown escape at $i") }
                                i += 2
                            } else { sb.append(d); i++ }
                            if (sb.length > 200) throw compileError("string literal is too long")
                        }
                        out += Tok(T.STR, sb.toString(), start)
                    }
                    c.isLetter() && c.code < 128 || c == '_' -> {
                        while (i < s.length && (s[i].code < 128 && s[i].isLetterOrDigit() || s[i] == '_')) i++
                        out += Tok(T.ID, s.substring(start, i), start)
                    }
                    else -> {
                        val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                        if (two in TWO) { out += Tok(T.OP, two, start); i += 2 }
                        else if (c in ONE) { out += Tok(T.OP, c.toString(), start); i++ }
                        else throw compileError("unexpected character at $start")             // `=`, `&`, `|`, backtick, $, @, #, \, [, ], {, }, ; ... are not part of the language
                    }
                }
            }
        }
        companion object { val TWO = setOf("==", "!=", "<=", ">=", "&&", "||"); val ONE = setOf('+', '-', '*', '/', '%', '(', ')', ',', '?', ':', '!', '<', '>', '.') }
    }

    // ------------------------------------------------------------------------------------------------ parser
    private class Parser(private val toks: List<Tok>) {
        private var p = 0; private var nodes = 0; private var depth = 0
        val columns = LinkedHashSet<String>()

        private fun peek() = toks[p]
        private fun next() = toks[p++]
        private fun isOp(t: String) = peek().type == T.OP && peek().text == t
        private fun expectOp(t: String) { if (!isOp(t)) throw compileError("expected '$t' at ${peek().pos}"); p++ }
        private fun <N : Node> node(n: N): N { if (++nodes > ExpressionLimits.MAX_NODES) throw compileError("expression is too complex"); return n }

        fun parse(): Node {
            val n = expr()
            if (peek().type != T.EOF) throw compileError("unexpected '${peek().text}' at ${peek().pos}")
            return n
        }

        private fun expr(): Node {
            if (++depth > ExpressionLimits.MAX_DEPTH) throw compileError("expression is nested too deeply")
            try {
                val c = binary(0)
                if (isOp("?")) { p++; val a = expr(); expectOp(":"); val b = expr(); return node(Cond(c, a, b)) }
                return c
            } finally { depth-- }
        }

        private val levels = listOf(setOf("||"), setOf("&&"), setOf("==", "!="), setOf("<", "<=", ">", ">="), setOf("+", "-"), setOf("*", "/", "%"))

        private fun binary(level: Int): Node {
            if (level == levels.size) return unary()
            var left = binary(level + 1)
            while (peek().type == T.OP && peek().text in levels[level]) { val op = next().text; left = node(Binary(op, left, binary(level + 1))) }
            return left
        }

        private fun unary(): Node {
            if (isOp("!") || isOp("-")) {
                if (++depth > ExpressionLimits.MAX_DEPTH) throw compileError("expression is nested too deeply")
                try { val op = next().text; return node(Unary(op, unary())) } finally { depth-- }
            }
            return primary()
        }

        private fun primary(): Node {
            val t = next()
            when (t.type) {
                T.NUM -> return node(Lit(BigDecimal(t.text)))
                T.STR -> return node(Lit(t.text))
                T.ID -> {
                    when (t.text) { "true" -> return node(Lit(true)); "false" -> return node(Lit(false)); "null" -> return node(Lit(null)); "value" -> return node(Current) }
                    if (isOp("(")) return call(t)
                    val path = arrayListOf(t.text)
                    while (isOp(".")) {
                        p++
                        val seg = next()
                        if (seg.type == T.ID || (seg.type == T.NUM && seg.text.all { it.isDigit() } && seg.text.length <= 4)) path += seg.text else throw compileError("bad path at ${seg.pos}")
                    }
                    columns += path[0]
                    return node(Ref(path))
                }
                T.OP -> if (t.text == "(") { val e = expr(); expectOp(")"); return e }
                else -> Unit
            }
            throw compileError("unexpected '${t.text}' at ${t.pos}")
        }

        private fun call(name: Tok): Node {
            expectOp("(")
            val args = ArrayList<Node>()
            if (!isOp(")")) {
                while (true) {
                    args += expr()
                    if (args.size > ExpressionLimits.MAX_ARGS) throw compileError("too many arguments")
                    if (isOp(",")) { p++; continue }
                    break
                }
            }
            expectOp(")")
            val spec = FUNCTIONS[name.text] ?: throw compileError("unknown function '${name.text}'")
            if (args.size < spec.min || args.size > spec.max) throw compileError("function '${name.text}' takes ${spec.min}..${spec.max} arguments")
            if (name.text == "col") {
                val lit = (args[0] as? Lit)?.value as? String ?: throw compileError("col() takes a quoted column name")
                columns += lit.substringBefore('.')
                return node(Col(lit))
            }
            if (name.text == "if") return node(Cond(args[0], args[1], args[2]))              // lazy: only the chosen branch is evaluated
            return node(Call(name.text, args))
        }
    }

    // ------------------------------------------------------------------------------------------------ evaluator
    private class Evaluator(private val row: Map<String, JsonNode>, private val current: Any?) {
        private var steps = 0

        fun eval(n: Node): Any? {
            if (++steps > ExpressionLimits.MAX_STEPS) throw runtimeError("expression is too expensive")
            return when (n) {
                is Lit -> n.value
                is Current -> current
                is Ref -> fromRow(n.path)
                is Col -> fromRow(listOf(n.name.substringBefore('.')) + n.name.substringAfter('.', "").split('.').filter { it.isNotEmpty() })
                is Unary -> unary(n.op, eval(n.a))
                is Binary -> binary(n)
                is Cond -> if (truth(eval(n.c))) eval(n.a) else eval(n.b)
                is Call -> call(n.fn, n.args)
            }
        }

        private fun fromRow(path: List<String>): Any? {
            var node: JsonNode? = row[path[0]]
            for (seg in path.drop(1)) {
                node = when {
                    node == null || node.isNull || node.isMissingNode -> return null
                    node.isObject -> node.get(seg)
                    node.isArray -> seg.toIntOrNull()?.let { node!!.get(it) }
                    else -> return null
                }
            }
            return scalar(node)
        }

        private fun truth(v: Any?): Boolean = when (v) { null -> false; is Boolean -> v; else -> throw runtimeError("a condition must be true or false") }

        private fun unary(op: String, v: Any?): Any? = when (op) {
            "!" -> !truth(v)
            else -> when (v) { null -> null; is BigDecimal -> bounded(v.negate()); else -> throw runtimeError("'-' needs a number") }
        }

        private fun binary(n: Binary): Any? {
            when (n.op) {
                "&&" -> return if (!truth(eval(n.a))) false else truth(eval(n.b))
                "||" -> return if (truth(eval(n.a))) true else truth(eval(n.b))
            }
            val a = eval(n.a); val b = eval(n.b)
            return when (n.op) {
                "==" -> same(a, b)
                "!=" -> !same(a, b)
                "<", "<=", ">", ">=" -> compare(n.op, a, b)
                else -> arithmetic(n.op, a, b)
            }
        }

        private fun same(a: Any?, b: Any?): Boolean = when {
            a == null || b == null -> a == null && b == null
            a is BigDecimal && b is BigDecimal -> a.compareTo(b) == 0
            else -> a == b                                                                   // no coercion: "1" is not 1
        }

        private fun compare(op: String, a: Any?, b: Any?): Boolean {
            if (a == null || b == null) return false
            val c = when {
                a is BigDecimal && b is BigDecimal -> a.compareTo(b)
                a is String && b is String -> a.compareTo(b)
                else -> throw runtimeError("cannot compare these types")
            }
            return when (op) { "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; else -> c >= 0 }
        }

        private fun arithmetic(op: String, a: Any?, b: Any?): Any? {
            if (a == null || b == null) return null                                          // SQL-style: unknown in, unknown out
            if (a !is BigDecimal || b !is BigDecimal) throw runtimeError("'$op' needs numbers (use num() or concat())")
            return when (op) {
                "+" -> bounded(a.add(b)); "-" -> bounded(a.subtract(b)); "*" -> bounded(a.multiply(b))
                "/" -> if (b.signum() == 0) null else bounded(a.divide(b, MC))
                else -> if (b.signum() == 0) null else bounded(a.remainder(b, MC))
            }
        }

        private fun call(name: String, argNodes: List<Node>): Any? {
            val a = argNodes.map { eval(it) }
            fun num(i: Int): BigDecimal? = when (val v = a[i]) { null -> null; is BigDecimal -> v; else -> throw runtimeError("$name() needs a number") }
            fun str(i: Int): String? = when (val v = a[i]) { null -> null; is String -> v; else -> throw runtimeError("$name() needs text") }
            return when (name) {
                "abs" -> num(0)?.abs()
                "round" -> { val d = if (a.size > 1) num(1)?.toInt() ?: 0 else 0; if (d !in 0..10) throw runtimeError("round() digits must be 0..10"); num(0)?.setScale(d, RoundingMode.HALF_UP) }
                "floor" -> num(0)?.setScale(0, RoundingMode.FLOOR)
                "ceil" -> num(0)?.setScale(0, RoundingMode.CEILING)
                "min", "max" -> extreme(name == "max", a)
                "num" -> toNum(a[0])
                "str" -> text(a[0])
                "bool" -> toBool(a[0])
                "lower" -> str(0)?.lowercase()
                "upper" -> str(0)?.uppercase()
                "trim" -> str(0)?.trim()
                "len" -> str(0)?.length?.let { BigDecimal(it) }
                "substr" -> str(0)?.let { s ->
                    val from = (num(1)?.toInt() ?: 0).coerceIn(0, s.length)
                    val len = if (a.size > 2) (num(2)?.toInt() ?: 0).coerceAtLeast(0) else s.length - from
                    s.substring(from, minOf(s.length.toLong(), from.toLong() + len).toInt())
                }
                "concat" -> limitString(a.joinToString("") { text(it) ?: "" })
                "replace" -> str(0)?.let { s ->
                    val f = str(1) ?: throw runtimeError("replace() needs text to find"); if (f.isEmpty()) throw runtimeError("replace() needs text to find")
                    val r = str(2) ?: ""
                    // size is known before the work is done: no input can make this allocate more than MAX_STRING characters
                    if (r.length > f.length) {
                        var hits = 0L; var at = s.indexOf(f)
                        while (at >= 0 && hits <= ExpressionLimits.MAX_STRING) { hits++; at = s.indexOf(f, at + f.length) }
                        if (s.length.toLong() + hits * (r.length - f.length) > ExpressionLimits.MAX_STRING) throw runtimeError("text is too long")
                    }
                    limitString(s.replace(f, r))
                }
                "contains" -> str(0)?.let { s -> s.contains(str(1) ?: return@let false) }
                "startsWith" -> str(0)?.let { s -> s.startsWith(str(1) ?: return@let false) }
                "endsWith" -> str(0)?.let { s -> s.endsWith(str(1) ?: return@let false) }
                "coalesce" -> a.firstOrNull { it != null }
                "isNull" -> a[0] == null
                else -> throw runtimeError("unknown function")                                    // unreachable: compile rejects unknown names
            }
        }

        private fun extreme(max: Boolean, a: List<Any?>): Any? {
            val v = a.filterNotNull()
            if (v.isEmpty()) return null
            if (v.all { it is BigDecimal }) return v.map { it as BigDecimal }.let { l -> if (max) l.maxOrNull() else l.minOrNull() }
            if (v.all { it is String }) return v.map { it as String }.let { l -> if (max) l.maxOrNull() else l.minOrNull() }
            throw runtimeError("min()/max() need values of one type")
        }

        private fun limitString(s: String): String { if (s.length > ExpressionLimits.MAX_STRING) throw runtimeError("text is too long"); return s }
    }

    companion object {
        private val MC = MathContext(34, RoundingMode.HALF_UP)

        private class Fn(val min: Int, val max: Int)
        private val FUNCTIONS: Map<String, Fn> = mapOf(
            "abs" to Fn(1, 1), "round" to Fn(1, 2), "floor" to Fn(1, 1), "ceil" to Fn(1, 1), "min" to Fn(1, 10), "max" to Fn(1, 10),
            "num" to Fn(1, 1), "str" to Fn(1, 1), "bool" to Fn(1, 1),
            "lower" to Fn(1, 1), "upper" to Fn(1, 1), "trim" to Fn(1, 1), "len" to Fn(1, 1), "substr" to Fn(2, 3), "concat" to Fn(1, 10),
            "replace" to Fn(3, 3), "contains" to Fn(2, 2), "startsWith" to Fn(2, 2), "endsWith" to Fn(2, 2),
            "coalesce" to Fn(1, 10), "isNull" to Fn(1, 1), "if" to Fn(3, 3), "col" to Fn(1, 1)
        )
        /** names usable in a formula, for documentation and for the validator's "unknown function" message */
        val functionNames: Set<String> get() = FUNCTIONS.keys

        private fun compileError(msg: String) = ExpressionException(ExpressionException.Phase.COMPILE, msg)
        private fun runtimeError(msg: String) = ExpressionException(ExpressionException.Phase.EVALUATE, msg)

        fun compile(source: String): Expression {
            if (source.isBlank() || source.length > ExpressionLimits.MAX_SOURCE) throw compileError("expression is empty or too long")
            val parser = Parser(Lexer(source).tokens())
            val root = parser.parse()
            return Expression(source, root, parser.columns)
        }

        /** a number that would be expensive to hold or print is refused, so no result can blow up later */
        private fun bounded(v: BigDecimal): BigDecimal {
            if (v.precision() > ExpressionLimits.MAX_PRECISION || Math.abs(v.scale()) > ExpressionLimits.MAX_SCALE) throw runtimeError("number out of range")
            return v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        }

        /** JSON → runtime value; arrays and objects are not scalars and are refused */
        internal fun scalar(node: JsonNode?): Any? {
            if (node == null || node.isNull || node.isMissingNode) return null
            return when (DataJson.type(node)) {
                JsonNodeType.BOOLEAN -> node.asBoolean()
                JsonNodeType.NUMBER -> bounded(node.decimalValue())
                JsonNodeType.STRING -> DataJson.text(node)
                else -> throw runtimeError("a column used here is not a plain value")
            }
        }

        internal fun toNum(v: Any?): BigDecimal? = when (v) {
            null -> null
            is BigDecimal -> v
            is String -> v.trim().takeIf { it.isNotEmpty() && it.length <= 50 }?.let { runCatching { bounded(BigDecimal(it)) }.getOrNull() }
            else -> null
        }

        internal fun toBool(v: Any?): Boolean? = when (v) {
            null -> null
            is Boolean -> v
            is BigDecimal -> when { v.compareTo(BigDecimal.ZERO) == 0 -> false; v.compareTo(BigDecimal.ONE) == 0 -> true; else -> null }
            is String -> when (v.trim().lowercase()) { "true", "yes", "y", "1" -> true; "false", "no", "n", "0" -> false; else -> null }
            else -> null
        }

        internal fun text(v: Any?): String? = when (v) {
            null -> null
            is String -> v
            is BigDecimal -> v.stripTrailingZeros().toPlainString()
            is Boolean -> v.toString()
            else -> null
        }
    }
}
