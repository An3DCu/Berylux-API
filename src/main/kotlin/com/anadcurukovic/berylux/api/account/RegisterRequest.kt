package com.anadcurukovic.berylux.api.account

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED

/**
 * What the app sends to make an account. Every field may be missing on the way in. [RegisterRules]
 * names each missing or wrong one, instead of the whole body failing at the first.
 */
data class RegisterRequest(
    @field:Schema(requiredMode = REQUIRED, example = "player@example.com", maxLength = RegisterRules.MAX_EMAIL)
    val email: String? = null,
    @field:Schema(
        requiredMode = REQUIRED,
        minLength = RegisterRules.MIN_PASSWORD,
        maxLength = RegisterRules.MAX_PASSWORD,
        description = "Counted in characters, with no rules about which ones",
    )
    val password: String? = null,
    @field:Schema(requiredMode = REQUIRED)
    val profile: ProfileFields? = null,
)

/** The guest's profile from the phone. The account starts as the same player. */
data class ProfileFields(
    @field:Schema(requiredMode = REQUIRED, example = "Marlowe", maxLength = RegisterRules.MAX_NAME)
    val name: String? = null,
    @field:Schema(requiredMode = REQUIRED, description = "Draws the sigil")
    val sigilSeed: Long? = null,
    @field:Schema(requiredMode = REQUIRED, description = "Drives the daily picks and the rivals")
    val pickSeed: Long? = null,
    @field:Schema(requiredMode = REQUIRED, example = "Europe/Sarajevo", description = "An IANA time zone")
    val timeZone: String? = null,
    @field:Schema(requiredMode = REQUIRED, minimum = "0", maximum = "23", example = "4")
    val dayStartHour: Int? = null,
)
