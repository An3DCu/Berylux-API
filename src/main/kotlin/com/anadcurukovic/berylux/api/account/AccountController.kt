package com.anadcurukovic.berylux.api.account

import com.anadcurukovic.berylux.api.security.Limit
import com.anadcurukovic.berylux.api.security.PasswordHasher
import com.anadcurukovic.berylux.api.security.RateLimits
import com.anadcurukovic.berylux.api.web.ProblemShape
import com.anadcurukovic.berylux.api.web.Problems
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class DeleteAccountRequest(
    @field:Schema(requiredMode = REQUIRED, description = "Asked again: an access token alone can't delete an account")
    val password: String? = null,
)

@RestController
@RequestMapping("/account")
@Tag(name = "Accounts")
class AccountController(
    private val accounts: Accounts,
    private val hasher: PasswordHasher,
    private val limits: RateLimits,
) {
    @DeleteMapping
    @Operation(summary = "Delete the account and everything in it")
    @SecurityRequirement(name = "bearer")
    @ApiResponse(
        responseCode = "204",
        description = "Gone: the account, its profile, cases and tokens",
        content = [Content()],
    )
    @ApiResponse(
        responseCode = "400",
        description = "The password is missing",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "No access token, or the password is wrong",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(
        responseCode = "429",
        description = "Too many tries for this account; `Retry-After` says how many seconds to wait",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    fun delete(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: DeleteAccountRequest): ResponseEntity<*> {
        val password = request.password ?: return Problems.invalid(mapOf("password" to "missing"))
        val userId = UUID.fromString(jwt.subject)
        // Counted before the hash: a stolen access token gets 5 guesses in 15 minutes, not one per request.
        val wait = limits.take(Limit.DELETE_ACCOUNT, userId.toString())
        return when {
            wait != null -> Problems.tooManyTries(wait)
            !passwordMatches(userId, password) -> Problems.wrongPassword()
            else -> {
                accounts.delete(userId)
                ResponseEntity.noContent().build<Unit>()
            }
        }
    }

    private fun passwordMatches(userId: UUID, password: String): Boolean {
        val hash = accounts.passwordHash(userId) ?: return false
        val possible = password.codePointCount(0, password.length) <= RegisterRules.MAX_PASSWORD
        return possible && hasher.matches(password, hash)
    }

    private companion object {
        const val PROBLEM = "application/problem+json"
    }
}
