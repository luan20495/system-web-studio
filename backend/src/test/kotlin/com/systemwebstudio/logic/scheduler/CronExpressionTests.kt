package com.systemwebstudio.logic.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class CronExpressionTests {
    private val utc: ZoneId = ZoneId.of("UTC")
    private fun next(cron: String, after: String, zone: ZoneId = utc) = CronExpression.parse(cron).next(Instant.parse(after), zone)

    @Test fun `every minute fires at the next minute boundary strictly after the given time`() {
        assertEquals(Instant.parse("2026-10-05T10:01:00Z"), next("* * * * *", "2026-10-05T10:00:00Z"))
        assertEquals(Instant.parse("2026-10-05T10:01:00Z"), next("* * * * *", "2026-10-05T10:00:59Z"))
    }

    @Test fun `fixed time of day rolls to the next day`() {
        assertEquals(Instant.parse("2026-10-05T09:30:00Z"), next("30 9 * * *", "2026-10-05T00:00:00Z"))
        assertEquals(Instant.parse("2026-10-06T09:30:00Z"), next("30 9 * * *", "2026-10-05T09:30:00Z"))
    }

    @Test fun `steps ranges and lists`() {
        assertEquals(Instant.parse("2026-10-05T10:15:00Z"), next("*/15 * * * *", "2026-10-05T10:00:00Z"))
        assertEquals(Instant.parse("2026-10-05T10:20:00Z"), next("10-30/10 * * * *", "2026-10-05T10:10:00Z"))
        assertEquals(Instant.parse("2026-10-05T12:00:00Z"), next("0 8,12,18 * * *", "2026-10-05T08:00:00Z"))
        assertEquals(Instant.parse("2026-10-05T10:45:00Z"), next("5/20 * * * *", "2026-10-05T10:25:00Z"))
    }

    @Test fun `day of week and names`() {
        // 2026-10-05 is a Monday
        assertEquals(Instant.parse("2026-10-09T09:00:00Z"), next("0 9 * * FRI", "2026-10-05T00:00:00Z"))
        assertEquals(Instant.parse("2026-10-11T09:00:00Z"), next("0 9 * * 0", "2026-10-09T10:00:00Z"))
        assertEquals(Instant.parse("2026-10-11T09:00:00Z"), next("0 9 * * 7", "2026-10-09T10:00:00Z"))
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), next("0 0 1 DEC *", "2026-10-05T00:00:00Z"))
        assertEquals(Instant.parse("2026-10-06T08:00:00Z"), next("0 8 * * MON-FRI", "2026-10-05T08:00:00Z"))
    }

    @Test fun `day of month and day of week are ORed when both are restricted`() {
        // the 13th OR any Friday: Friday 2026-10-09 comes before the 13th (Tuesday)
        assertEquals(Instant.parse("2026-10-09T00:00:00Z"), next("0 0 13 * FRI", "2026-10-05T00:00:00Z"))
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), next("0 0 13 * FRI", "2026-10-09T00:00:00Z"))
    }

    @Test fun `month end and leap days are handled`() {
        assertEquals(Instant.parse("2026-10-31T00:00:00Z"), next("0 0 31 * *", "2026-10-05T00:00:00Z"))
        assertEquals(Instant.parse("2026-12-31T00:00:00Z"), next("0 0 31 * *", "2026-10-31T00:00:00Z"))   // November has no 31st
        assertEquals(Instant.parse("2028-02-29T00:00:00Z"), next("0 0 29 2 *", "2026-10-05T00:00:00Z"))
    }

    @Test fun `the timezone decides the wall clock`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), next("0 9 * * *", "2026-10-04T12:00:00Z", tokyo))
    }

    @Test fun `spring forward - a local time that does not exist fires once, after the gap`() {
        val berlin = ZoneId.of("Europe/Berlin")      // 2026-03-29 02:00 -> 03:00
        val fires = generateSequence(CronExpression.parse("30 2 * * *").next(Instant.parse("2026-03-28T12:00:00Z"), berlin)) {
            CronExpression.parse("30 2 * * *").next(it, berlin)
        }.take(3).toList()
        val onGapDay = fires.filter { it.atZone(berlin).toLocalDate().toString() == "2026-03-29" }
        assertEquals(1, onGapDay.size)
        assertTrue(!onGapDay.single().atZone(berlin).toLocalTime().isBefore(java.time.LocalTime.of(3, 0)), "must not fire before the gap ends")
        assertEquals(Instant.parse("2026-03-30T00:30:00Z"), fires[1])   // the next day is back to 02:30 local (CEST, +02:00)
    }

    @Test fun `fall back - a local time that happens twice fires once`() {
        val berlin = ZoneId.of("Europe/Berlin")      // 2026-10-25 03:00 -> 02:00
        val c = CronExpression.parse("30 2 * * *")
        val first = c.next(Instant.parse("2026-10-24T12:00:00Z"), berlin)!!
        val second = c.next(first, berlin)!!
        val third = c.next(second, berlin)!!
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), first)       // first 02:30 (CEST)
        assertEquals("2026-10-26", second.atZone(berlin).toLocalDate().toString())   // not the repeated 02:30 (CET)
        assertTrue(third.isAfter(second))
    }

    @Test fun `an expression that can never fire is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CronExpression.parse("0 0 31 2 *") }
        assertThrows(IllegalArgumentException::class.java) { CronExpression.parse("0 0 30 2 *") }
    }

    @Test fun `malformed expressions are rejected with a message`() {
        for (bad in listOf("", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * * 13 *", "* * * * 8", "*/0 * * * *", "5-1 * * * *",
            "a * * * *", "*/x * * * *", "1,,2 * * * *", "1//2 * * * *", "* * * FOO *", "-1 * * * *", "1- * * * *", "@daily", "0 0 L * *", "0 0 * * 1#2")) {
            val e = assertThrows(IllegalArgumentException::class.java) { CronExpression.parse(bad) }
            assertTrue(!e.message.isNullOrBlank(), "no message for '$bad'")
        }
    }

    @Test fun `whitespace is tolerated and the source is kept`() {
        assertEquals("0  9 * * *", CronExpression.parse("  0  9 * * *  ").source)
        assertEquals(Instant.parse("2026-10-05T09:00:00Z"), next("  0   9  *  *  * ", "2026-10-05T00:00:00Z"))
    }

    @Test fun `next is always strictly increasing and in the zone's wall clock`() {
        val c = CronExpression.parse("*/7 3-5 * * 1-5")
        var t = Instant.parse("2026-01-01T00:00:00Z")
        val zone = ZoneId.of("America/New_York")
        repeat(300) {
            val n = c.next(t, zone)!!
            assertTrue(n.isAfter(t))
            val z: ZonedDateTime = n.atZone(zone)
            assertTrue(z.hour in 3..5 && z.minute % 7 == 0 && z.dayOfWeek.value in 1..5, "bad fire $z")
            t = n
        }
    }

    @Test fun `the century leap year rule is respected`() {
        // 2100 is not a leap year, so the next Feb 29 after March 2099 is in 2104
        assertEquals(Instant.parse("2104-02-29T00:00:00Z"), next("0 0 29 2 *", "2099-03-01T00:00:00Z"))
    }
}
