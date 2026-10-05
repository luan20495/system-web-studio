package com.systemwebstudio.logic.scheduler

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Standard 5-field cron (`minute hour day-of-month month day-of-week`) with timezone and DST handling. Own implementation: no library
 * dependency, no seconds field, no `L`/`W`/`#` extensions.
 *
 * Syntax per field: `*`, `star-slash-n` (every n), `a`, `a-b`, `a-b/n`, `a/n`, comma lists. Month names JAN–DEC and day names SUN–SAT are accepted; day-of-week
 * 0 and 7 are both Sunday. As in classic cron, when both day-of-month and day-of-week are restricted a day matches if **either** matches.
 *
 * DST: schedules are evaluated on the local wall clock. A local time that does not exist (spring forward) fires once, at the first valid
 * instant after the gap. A local time that happens twice (fall back) fires once, at its first occurrence.
 */
class CronExpression private constructor(
    val source: String,
    private val minutes: Set<Int>, private val hours: Set<Int>, private val days: Set<Int>, private val months: Set<Int>, private val weekdays: Set<Int>,
    private val domRestricted: Boolean, private val dowRestricted: Boolean
) {
    /** First fire time strictly after [after], or null when the expression can never fire (e.g. 31 February). */
    fun next(after: Instant, zone: ZoneId): Instant? {
        val afterLocal = after.atZone(zone).toLocalDateTime()
        var dt = afterLocal.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val horizonYear = dt.year + 9
        while (dt.year <= horizonYear) {
            if (dt.monthValue !in months) { dt = dt.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).plusMonths(1); continue }
            if (!dayMatches(dt)) { dt = dt.truncatedTo(ChronoUnit.DAYS).plusDays(1); continue }
            if (dt.hour !in hours) { dt = dt.truncatedTo(ChronoUnit.HOURS).plusHours(1); continue }
            if (dt.minute !in minutes) { dt = dt.plusMinutes(1); continue }
            val instant = resolve(dt, zone)
            if (instant.isAfter(after)) return instant
            dt = dt.plusMinutes(1) // DST gap/overlap produced an instant that is not after `after`: keep looking
        }
        return null
    }

    private fun dayMatches(dt: LocalDateTime): Boolean {
        val domOk = dt.dayOfMonth in days
        val dowOk = (dt.dayOfWeek.value % 7) in weekdays // Monday=1 … Saturday=6, Sunday=0
        return when {
            domRestricted && dowRestricted -> domOk || dowOk
            domRestricted -> domOk
            dowRestricted -> dowOk
            else -> true
        }
    }

    private fun resolve(local: LocalDateTime, zone: ZoneId): Instant = ZonedDateTime.ofLocal(local, zone, null).toInstant()

    override fun toString() = source

    companion object {
        private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val DAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        /** @throws IllegalArgumentException with a message that is safe to show. */
        fun parse(expression: String): CronExpression {
            val parts = expression.trim().split(Regex("\\s+"))
            require(parts.size == 5) { "cron needs 5 fields (minute hour day-of-month month day-of-week)" }
            val minutes = field(parts[0], 0, 59, null, "minute")
            val hours = field(parts[1], 0, 23, null, "hour")
            val days = field(parts[2], 1, 31, null, "day-of-month")
            val months = field(parts[3], 1, 12, MONTHS.mapIndexed { i, n -> n to i + 1 }.toMap(), "month")
            val weekdays = field(parts[4], 0, 7, DAYS.mapIndexed { i, n -> n to i }.toMap(), "day-of-week").map { it % 7 }.toSet()
            val cron = CronExpression(
                expression.trim(), minutes, hours, days, months, weekdays,
                domRestricted = parts[2] != "*", dowRestricted = parts[4] != "*"
            )
            // reject expressions that can never fire (e.g. "0 0 31 2 *")
            require(cron.next(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC")) != null) { "cron never fires" }
            return cron
        }

        private fun field(raw: String, min: Int, max: Int, names: Map<String, Int>?, label: String): Set<Int> {
            val out = sortedSetOf<Int>()
            require(raw.isNotEmpty()) { "$label is empty" }
            for (item in raw.split(',')) {
                require(item.isNotEmpty()) { "$label has an empty list item" }
                val stepSplit = item.split('/')
                require(stepSplit.size <= 2) { "$label: too many '/' in '$item'" }
                val step = if (stepSplit.size == 2) (stepSplit[1].toIntOrNull() ?: throw IllegalArgumentException("$label: step must be a number")) else 1
                require(step in 1..max) { "$label: step must be between 1 and $max" }
                val range = stepSplit[0]
                val (lo, hi) = when {
                    range == "*" -> min to max
                    '-' in range -> {
                        val r = range.split('-')
                        require(r.size == 2) { "$label: bad range '$range'" }
                        value(r[0], names, label) to value(r[1], names, label)
                    }
                    else -> value(range, names, label).let { v -> if (stepSplit.size == 2) v to max else v to v }
                }
                require(lo in min..max && hi in min..max) { "$label: out of range $min-$max" }
                require(lo <= hi) { "$label: range start is after its end" }
                var v = lo
                while (v <= hi) { out += v; v += step }
            }
            return out
        }

        private fun value(s: String, names: Map<String, Int>?, label: String): Int =
            s.toIntOrNull() ?: names?.get(s.uppercase()) ?: throw IllegalArgumentException("$label: unknown value '$s'")
    }
}
