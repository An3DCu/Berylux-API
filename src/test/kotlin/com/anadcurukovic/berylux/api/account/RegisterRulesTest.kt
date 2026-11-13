package com.anadcurukovic.berylux.api.account

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegisterRulesTest {
    private val profile = ProfileFields(
        name = "Marlowe",
        sigilSeed = 7,
        pickSeed = 11,
        timeZone = "Europe/Sarajevo",
        dayStartHour = 4,
    )
    private val request = RegisterRequest(email = "player@example.com", password = "ten chars!", profile = profile)

    private fun problems(request: RegisterRequest) = assertIs<Checked.Invalid>(RegisterRules.check(request)).problems

    private fun valid(request: RegisterRequest) = assertIs<Checked.Valid>(RegisterRules.check(request)).account

    @Test
    fun `a good registration is cleaned the way it's stored`() {
        val messy = request.copy(email = "  Player@Example.COM ", profile = profile.copy(name = "  Ana   Lee "))
        val account = valid(messy)
        assertEquals("player@example.com", account.email)
        assertEquals("Ana Lee", account.name)
        assertEquals(7, account.sigilSeed)
        assertEquals(11, account.pickSeed)
        assertEquals("Europe/Sarajevo", account.timeZone)
        assertEquals(4, account.dayStartHour)
    }

    @Test
    fun `a password is 10 to 128 characters`() {
        assertEquals(mapOf("password" to "too_short"), problems(request.copy(password = "123456789")))
        valid(request.copy(password = "1234567890"))
        valid(request.copy(password = "x".repeat(128)))
        assertEquals(mapOf("password" to "too_long"), problems(request.copy(password = "x".repeat(129))))
    }

    @Test
    fun `a password is counted in characters, not in UTF-16 units`() {
        // Each emoji is two UTF-16 units: nine of them are 18 units but only 9 characters.
        assertEquals(mapOf("password" to "too_short"), problems(request.copy(password = "😀".repeat(9))))
        valid(request.copy(password = "😀".repeat(10)))
    }

    @Test
    fun `a password keeps its spaces`() {
        assertEquals("  spaced out  ", valid(request.copy(password = "  spaced out  ")).password)
    }

    @Test
    fun `an email needs one at sign, a dot after it and no spaces`() {
        for (email in listOf("no-at-sign", "a@b", "a b@c.d", "a@b@c.d", "@b.c", "a@.")) {
            assertEquals(mapOf("email" to "invalid"), problems(request.copy(email = email)), email)
        }
    }

    @Test
    fun `an email is at most 254 characters`() {
        val domain = "@example.com"
        valid(request.copy(email = "a".repeat(254 - domain.length) + domain))
        val tooLong = "a".repeat(255 - domain.length) + domain
        assertEquals(mapOf("email" to "too_long"), problems(request.copy(email = tooLong)))
    }

    @Test
    fun `a name follows the app's rule`() {
        assertEquals(mapOf("profile.name" to "empty"), problems(request.copy(profile = profile.copy(name = "   "))))
        assertEquals("a b", valid(request.copy(profile = profile.copy(name = "a     b"))).name)
        valid(request.copy(profile = profile.copy(name = "n".repeat(20))))
        assertEquals(
            mapOf("profile.name" to "too_long"),
            problems(request.copy(profile = profile.copy(name = "n".repeat(21)))),
        )
    }

    @Test
    fun `a time zone is a named one`() {
        for (zone in listOf("Mars/Olympus", "+02:00", "Z", "")) {
            assertEquals(
                mapOf("profile.timeZone" to "unknown"),
                problems(request.copy(profile = profile.copy(timeZone = zone))),
                zone,
            )
        }
        valid(request.copy(profile = profile.copy(timeZone = "America/Argentina/Buenos_Aires")))
    }

    @Test
    fun `a day starts at an hour from 0 to 23`() {
        valid(request.copy(profile = profile.copy(dayStartHour = 0)))
        valid(request.copy(profile = profile.copy(dayStartHour = 23)))
        for (hour in listOf(-1, 24)) {
            assertEquals(
                mapOf("profile.dayStartHour" to "out_of_range"),
                problems(request.copy(profile = profile.copy(dayStartHour = hour))),
            )
        }
    }

    @Test
    fun `every missing field is named at once`() {
        assertEquals(
            mapOf("email" to "missing", "password" to "missing", "profile" to "missing"),
            problems(RegisterRequest()),
        )
        assertEquals(
            mapOf(
                "profile.name" to "empty",
                "profile.sigilSeed" to "missing",
                "profile.pickSeed" to "missing",
                "profile.timeZone" to "missing",
                "profile.dayStartHour" to "missing",
            ),
            problems(request.copy(profile = ProfileFields())),
        )
    }
}
