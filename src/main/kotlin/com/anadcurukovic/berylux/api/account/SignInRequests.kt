package com.anadcurukovic.berylux.api.account

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED

data class LoginRequest(
    @field:Schema(requiredMode = REQUIRED, example = "player@example.com")
    val email: String? = null,
    @field:Schema(requiredMode = REQUIRED)
    val password: String? = null,
)

/** For refresh and for log out: the refresh token the phone holds. */
data class RefreshRequest(
    @field:Schema(requiredMode = REQUIRED)
    val refreshToken: String? = null,
)

/** What sign-up, log in and refresh give the phone. */
data class TokenAnswer(
    @field:Schema(description = "Sent as `Authorization: Bearer` on every signed-in call")
    val accessToken: String,
    @field:Schema(example = "Bearer")
    val tokenType: String = "Bearer",
    @field:Schema(description = "Seconds the access token lasts", example = "900")
    val expiresIn: Long,
    @field:Schema(description = "Works once, for the next pair of tokens")
    val refreshToken: String,
    @field:Schema(description = "Seconds the refresh token lasts", example = "2592000")
    val refreshExpiresIn: Long,
)
