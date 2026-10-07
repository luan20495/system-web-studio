package com.systemwebstudio.data.discovery

import com.systemwebstudio.data.query.DataJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeType

/**
 * Masks sample values **before** they leave the connector, are stored, or are shown to the AI (T9: "PII sample phải mask trước khi đưa AI").
 * Two independent signals, either one masks:
 *
 *  - the **field name** (a column called `password`, `email`, `ssn`, `first_name` ... is masked whatever it contains);
 *  - the **value shape** (an email, a card number that passes Luhn, a JWT, an API-key prefix, a long opaque token, an IBAN, an SSN, a phone
 *    number, an IPv4 address) anywhere in a string, whatever the column is called.
 *
 * It is total (never throws), bounded (strings are cut before any pattern runs, nesting/array/object sizes are capped, so there is no
 * regex-cost or memory cliff) and idempotent (masking masked output changes nothing), which is what lets the discovery service run it again
 * as defence in depth. Over-masking is the accepted error direction: a false positive hides a harmless value, a false negative leaks.
 */
object SampleMasker {
    const val MAX_STRING = 64
    private const val SCAN_LIMIT = 512
    private const val MAX_FIELDS = 60
    private const val MAX_DEPTH = 3
    private const val MAX_ARRAY = 5
    const val REDACTED = "[redacted]"

    private val SECRET_TOKENS = setOf("password", "passwd", "pwd", "passphrase", "secret", "token", "apikey", "authorization", "auth", "credential", "credentials",
        "private", "privatekey", "salt", "hash", "otp", "pin", "cvv", "cvc", "signature", "session", "cookie", "bearer", "jwt")
    private val SECRET_COMPOUNDS = listOf("apikey", "accesskey", "secretkey", "privatekey", "clientsecret")
    private val IDENTIFIER_TOKENS = setOf("ssn", "sin", "nin", "passport", "iban", "swift", "bic", "routing", "card", "pan", "creditcard", "debitcard", "cardnumber",
        "accountnumber", "taxid", "nationalid", "license", "licence")
    private val IDENTIFIER_COMPOUNDS = listOf("creditcard", "debitcard", "cardnumber", "accountnumber", "taxid", "nationalid", "socialsecurity")
    private val PERSONAL_TOKENS = setOf("name", "firstname", "lastname", "fullname", "surname", "givenname", "familyname", "address", "street", "city", "zip", "postal",
        "postcode", "dob", "birth", "birthday", "birthdate", "phone", "mobile", "tel", "telephone", "email", "mail", "ip", "latitude", "longitude", "lat", "lng", "lon", "gender")
    private val PERSONAL_COMPOUNDS = listOf("dateofbirth", "phonenumber", "emailaddress")

    /** lower-case tokens of a field name: splits on non-alphanumerics and camelCase (`firstName` → first, name) */
    internal fun tokens(field: String): List<String> =
        field.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private enum class NameClass { SECRET, IDENTIFIER, PERSONAL, NONE }

    private fun classify(field: String): NameClass {
        val t = tokens(field)
        val compact = field.lowercase().filter { it.isLetterOrDigit() }
        return when {
            t.any { it in SECRET_TOKENS } || SECRET_COMPOUNDS.any { it in compact } -> NameClass.SECRET
            t.any { it in IDENTIFIER_TOKENS } || IDENTIFIER_COMPOUNDS.any { it in compact } -> NameClass.IDENTIFIER
            t.any { it in PERSONAL_TOKENS } || PERSONAL_COMPOUNDS.any { it in compact } -> NameClass.PERSONAL
            else -> NameClass.NONE
        }
    }

    /** true when a value of this column must not reach an AI prompt at all (not even masked) */
    fun isSensitiveField(field: String): Boolean = classify(field) != NameClass.NONE

    /** Masked copy of the first [maxRows] rows (default 5); never more than [MAX_FIELDS] columns per row. */
    fun maskRows(rows: List<Map<String, JsonNode>>, maxRows: Int = DiscoveryOptions.MAX_SAMPLE_ROWS): List<Map<String, JsonNode>> =
        rows.take(maxRows.coerceIn(0, DiscoveryOptions.MAX_SAMPLE_ROWS)).map { maskRow(it) }

    fun maskRow(row: Map<String, JsonNode>): Map<String, JsonNode> {
        val out = LinkedHashMap<String, JsonNode>()
        for ((k, v) in row.entries.take(MAX_FIELDS)) out[k] = mask(k, v, 0)
        return out
    }

    fun mask(field: String, value: JsonNode?, depth: Int = 0): JsonNode {
        if (value == null || value.isNull || value.isMissingNode) return DataJson.NULL
        val cls = classify(field)
        return when (DataJson.type(value)) {
            JsonNodeType.BOOLEAN -> value
            JsonNodeType.NUMBER -> if (cls == NameClass.NONE) value else text(REDACTED)
            JsonNodeType.STRING -> text(maskString(cls, DataJson.text(value)))
            JsonNodeType.ARRAY -> if (depth >= MAX_DEPTH) text(REDACTED) else DataJson.toNode(DataJson.elements(value).take(MAX_ARRAY).map { mask(field, it, depth + 1) })
            JsonNodeType.OBJECT -> if (depth >= MAX_DEPTH) text(REDACTED) else DataJson.toNode(LinkedHashMap<String, JsonNode>().also { o ->
                DataJson.keys(value).take(20).forEach { k -> o[k] = mask(k, value.get(k), depth + 1) } })
            else -> text(REDACTED)                                                          // binary, POJO, anything unforeseen
        }
    }

    private fun text(s: String): JsonNode = DataJson.toNode(s)

    private fun maskString(cls: NameClass, raw: String): String {
        if (cls == NameClass.SECRET || cls == NameClass.IDENTIFIER) return REDACTED
        var s = if (raw.length > SCAN_LIMIT) raw.substring(0, SCAN_LIMIT) else raw          // bound before any pattern runs
        if (cls == NameClass.PERSONAL) return partial(s)
        s = scrub(s)
        return if (s.length > MAX_STRING) s.substring(0, MAX_STRING) + "…" else s
    }

    /** `Jane Doe` → `J*** D***`; an email/phone/IP inside is shaped by [scrub] instead */
    private fun partial(s: String): String {
        val scrubbed = scrub(s)
        if (scrubbed != s) return scrubbed.take(MAX_STRING)                                 // an email/phone/ip inside was already shaped
        return s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6).joinToString(" ") { w -> if (w.startsWith("[") ) w else w.first() + "***" }.ifEmpty { REDACTED }
    }

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]{1,64}@([A-Za-z0-9-]{1,63}\\.)+[A-Za-z]{2,24}")
    private val JWT = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
    private val KEY_PREFIX = Regex("(?i)\\b(sk[-_](live|test)[-_][A-Za-z0-9]{8,}|sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|bearer\\s+[A-Za-z0-9._~+/=-]{8,})")
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b")
    private val SSN = Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")
    private val CARD = Regex("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])")
    private val PHONE = Regex("(?<![0-9])\\+?[0-9][0-9 ().-]{6,18}[0-9](?![0-9])")
    private val IPV4 = Regex("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\b")
    private val OPAQUE = Regex("[A-Za-z0-9+/_-]{32,}={0,2}")

    /** the value-shape pass; order matters (specific shapes before the generic opaque-token one) */
    internal fun scrub(input: String): String {
        if (UUID_RE.matches(input)) return input                                            // an identifier, not a secret
        var s = input
        s = JWT.replace(s, "[redacted:token]")
        s = KEY_PREFIX.replace(s, "[redacted:token]")
        s = EMAIL.replace(s) { m -> val at = m.value.indexOf('@'); val dom = m.value.substring(at + 1); m.value.first() + "***@" + dom.first() + "***." + dom.substringAfterLast('.') }
        s = SSN.replace(s, "[redacted:id]")
        s = IBAN.replace(s, "[redacted:iban]")
        s = CARD.replace(s) { m -> if (luhn(m.value.filter { it.isDigit() })) "[redacted:card]" else m.value }
        s = PHONE.replace(s) { m -> val d = m.value.count { it.isDigit() }; if (d < 8) m.value else maskDigits(m.value) }
        s = IPV4.replace(s) { m -> m.groupValues[1] + ".x" }
        s = OPAQUE.replace(s) { m -> if (UUID_RE.matches(m.value)) m.value else "[redacted:token]" }
        return s
    }

    private fun maskDigits(v: String): String {
        val total = v.count { it.isDigit() }; var seen = 0
        return buildString { for (c in v) if (c.isDigit()) { seen++; append(if (seen > total - 2) c else '*') } else append(c) }
    }

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0; var alt = false
        for (i in digits.indices.reversed()) { var n = digits[i] - '0'; if (alt) { n *= 2; if (n > 9) n -= 9 }; sum += n; alt = !alt }
        return sum % 10 == 0
    }
}
