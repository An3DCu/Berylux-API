package com.anadcurukovic.berylux.api.account

import java.time.ZoneId

/** A registration that passed every check, cleaned the way it's stored. */
data class NewAccount(
    val email: String,
    val password: String,
    val name: String,
    val sigilSeed: Long,
    val pickSeed: Long,
    val timeZone: String,
    val dayStartHour: Int,
)

/** Either a [NewAccount] or, for each field that's wrong, what's wrong with it. */
sealed interface Checked {
    data class Valid(val account: NewAccount) : Checked
    data class Invalid(val problems: Map<String, String>) : Checked
}

/**
 * The checks on a registration. The name follows the app's own rule: trimmed, runs of spaces made
 * single, 1 to 20 characters. A password is counted in characters, not bytes, and isn't trimmed.
 */
object RegisterRules {
    const val MAX_EMAIL = 254
    const val MIN_PASSWORD = 10
    const val MAX_PASSWORD = 128
    const val MAX_NAME = 20
    private val DAY_START_HOURS = 0..23

    // One @, something on each side, a dot in the domain, no spaces or control characters.
    private val EMAIL = Regex("[^@\\s\\p{Cntrl}]+@[^@\\s\\p{Cntrl}]+\\.[^@\\s\\p{Cntrl}]+")
    private val SPACES = Regex("\\s+")

    fun check(request: RegisterRequest): Checked {
        val profile = request.profile
        val problems = buildMap {
            emailProblem(request.email)?.let { put("email", it) }
            passwordProblem(request.password)?.let { put("password", it) }
            if (profile == null) {
                put("profile", MISSING)
            } else {
                nameProblem(profile.name)?.let { put("profile.name", it) }
                if (profile.sigilSeed == null) put("profile.sigilSeed", MISSING)
                if (profile.pickSeed == null) put("profile.pickSeed", MISSING)
                timeZoneProblem(profile.timeZone)?.let { put("profile.timeZone", it) }
                hourProblem(profile.dayStartHour)?.let { put("profile.dayStartHour", it) }
            }
        }
        if (problems.isNotEmpty() || profile == null) return Checked.Invalid(problems)
        return Checked.Valid(
            NewAccount(
                email = checkNotNull(request.email).trim().lowercase(),
                password = checkNotNull(request.password),
                name = cleanName(checkNotNull(profile.name)),
                sigilSeed = checkNotNull(profile.sigilSeed),
                pickSeed = checkNotNull(profile.pickSeed),
                timeZone = checkNotNull(profile.timeZone),
                dayStartHour = checkNotNull(profile.dayStartHour),
            ),
        )
    }

    fun cleanName(name: String): String = name.trim().replace(SPACES, " ")

    private fun emailProblem(email: String?): String? {
        val trimmed = email?.trim()
        return when {
            trimmed.isNullOrEmpty() -> MISSING
            trimmed.length > MAX_EMAIL -> TOO_LONG
            !EMAIL.matches(trimmed) -> "invalid"
            else -> null
        }
    }

    private fun passwordProblem(password: String?): String? {
        val length = password?.let { it.codePointCount(0, it.length) }
        return when {
            length == null -> MISSING
            length < MIN_PASSWORD -> "too_short"
            length > MAX_PASSWORD -> TOO_LONG
            else -> null
        }
    }

    private fun nameProblem(name: String?): String? {
        val cleaned = name?.let(::cleanName)
        return when {
            cleaned.isNullOrEmpty() -> "empty"
            cleaned.length > MAX_NAME -> TOO_LONG
            else -> null
        }
    }

    private fun timeZoneProblem(zone: String?): String? = when {
        zone == null -> MISSING
        zone !in ZoneId.getAvailableZoneIds() -> "unknown"
        else -> null
    }

    private fun hourProblem(hour: Int?): String? = when {
        hour == null -> MISSING
        hour !in DAY_START_HOURS -> "out_of_range"
        else -> null
    }

    private const val MISSING = "missing"
    private const val TOO_LONG = "too_long"
}
