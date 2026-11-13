package com.anadcurukovic.berylux.api.security

import io.github.bucket4j.TimeMeter
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every wait here is worked out by hand from the windows in [Limit]. */
class RateLimitsTest {
    private class FakeTime : TimeMeter {
        var nanos = 0L

        fun at(seconds: Double) {
            nanos = (seconds * TimeUnit.SECONDS.toNanos(1)).toLong()
        }

        override fun currentTimeNanos() = nanos

        override fun isWallClockBased() = false
    }

    private val time = FakeTime()
    private val limits = RateLimits(time)

    private fun takeAll(limit: Limit, key: String, tries: Int) = repeat(tries) {
        assertNull(limits.take(limit, key), "try ${it + 1}")
    }

    @Test
    fun `register allows 3 an hour and 10 a day from one address`() {
        takeAll(Limit.REGISTER, "a", 3)
        assertEquals(3_600, limits.take(Limit.REGISTER, "a"))
        // 3 more at 1 hour, 3 at 2 hours and 1 at 3 hours make 10 for the day.
        time.at(3_600.0)
        takeAll(Limit.REGISTER, "a", 3)
        time.at(7_200.0)
        takeAll(Limit.REGISTER, "a", 3)
        time.at(10_800.0)
        takeAll(Limit.REGISTER, "a", 1)
        // The hour has 2 left, the day none: the wait runs to 24 hours, 86,400 - 10,800 seconds.
        assertEquals(75_600, limits.take(Limit.REGISTER, "a"))
    }

    @Test
    fun `log in allows 5 a minute and 20 an hour from one address`() {
        takeAll(Limit.LOGIN_ADDRESS, "a", 5)
        assertEquals(60, limits.take(Limit.LOGIN_ADDRESS, "a"))
        for (minute in 1..3) {
            time.at(60.0 * minute)
            takeAll(Limit.LOGIN_ADDRESS, "a", 5)
        }
        // 20 in the hour by 3 minutes. At 4 minutes the minute has room and the hour doesn't: 3,600 - 240.
        time.at(240.0)
        assertEquals(3_360, limits.take(Limit.LOGIN_ADDRESS, "a"))
    }

    @Test
    fun `log in allows 5 tries per email in 15 minutes`() {
        takeAll(Limit.LOGIN_EMAIL, "player@example.com", 5)
        assertEquals(900, limits.take(Limit.LOGIN_EMAIL, "player@example.com"))
        time.at(900.0)
        takeAll(Limit.LOGIN_EMAIL, "player@example.com", 5)
    }

    @Test
    fun `refresh allows 10 a minute from one address`() {
        takeAll(Limit.REFRESH, "a", 10)
        assertEquals(60, limits.take(Limit.REFRESH, "a"))
    }

    @Test
    fun `a wait under a second is said as one second`() {
        takeAll(Limit.REFRESH, "a", 10)
        time.at(59.5)
        assertEquals(1, limits.take(Limit.REFRESH, "a"))
    }

    @Test
    fun `each address and each limit counts on its own`() {
        takeAll(Limit.REFRESH, "a", 10)
        assertNull(limits.take(Limit.REFRESH, "b"))
        assertNull(limits.take(Limit.REGISTER, "a"))
        assertNull(limits.take(Limit.LOGIN_ADDRESS, "a"))
    }

    @Test
    fun `a flood of new addresses can't grow the buckets past their bound`() {
        val small = RateLimits(time, maxKeys = 100)
        for (address in 1..1_000) small.take(Limit.REGISTER, "10.0.${address / 256}.${address % 256}")
        assertTrue(small.keys(Limit.REGISTER) <= 100, "${small.keys(Limit.REGISTER)} buckets")
    }
}
