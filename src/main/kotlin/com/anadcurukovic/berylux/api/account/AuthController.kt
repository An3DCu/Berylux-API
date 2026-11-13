package com.anadcurukovic.berylux.api.account

import com.anadcurukovic.berylux.api.security.AccessTokens
import com.anadcurukovic.berylux.api.security.Limit
import com.anadcurukovic.berylux.api.security.PasswordHasher
import com.anadcurukovic.berylux.api.security.RateLimits
import com.anadcurukovic.berylux.api.web.ProblemShape
import com.anadcurukovic.berylux.api.web.Problems
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/auth")
@Tag(name = "Accounts")
class AuthController(
    private val accounts: Accounts,
    private val hasher: PasswordHasher,
    private val accessTokens: AccessTokens,
    private val refreshTokens: RefreshTokens,
    private val limits: RateLimits,
) {
    // Checked when an email has no account: an unknown email costs the same hash as a wrong password.
    private val standIn = hasher.hash(UUID.randomUUID().toString())

    @PostMapping("/register")
    @Operation(summary = "Make an account with the guest's profile, and sign in")
    @ApiResponse(responseCode = "201", description = "The account exists, and these are its first tokens")
    @ApiResponse(
        responseCode = "400",
        description = "Each field that's wrong, under `problems`, and what's wrong with it",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(
        responseCode = "409",
        description = "The email can't be used",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(
        responseCode = "413",
        description = "The body is over 8 KiB",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(
        responseCode = "429",
        description = "Too many tries from this address; `Retry-After` says how many seconds to wait",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    fun register(@RequestBody request: RegisterRequest): ResponseEntity<*> =
        when (val checked = RegisterRules.check(request)) {
            is Checked.Invalid -> Problems.invalid(checked.problems)
            is Checked.Valid -> create(checked.account)
        }

    @PostMapping("/login")
    @Operation(summary = "Sign in with an email and a password")
    @ApiResponse(responseCode = "200", description = "A new pair of tokens, in a new chain")
    @ApiResponse(
        responseCode = "400",
        description = "The email or the password is missing",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(
        responseCode = "401",
        description = "The same answer for a wrong password and an unknown email",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(
        responseCode = "429",
        description = "Too many tries from this address or for this email; `Retry-After` says how many seconds to wait",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    fun login(@RequestBody request: LoginRequest): ResponseEntity<*> {
        val email = request.email?.trim()?.lowercase()
        val password = request.password
        if (email.isNullOrEmpty() || password == null) {
            return Problems.invalid(
                buildMap {
                    if (email.isNullOrEmpty()) put("email", "missing")
                    if (password == null) put("password", "missing")
                },
            )
        }
        // Counted before the email is looked up, for every email: the limit itself says nothing about accounts.
        val wait = limits.take(Limit.LOGIN_EMAIL, email)
        return if (wait != null) Problems.tooManyTries(wait) else signIn(email, password)
    }

    private fun signIn(email: String, password: String): ResponseEntity<*> {
        val account = accounts.credentials(email)
        val signedIn = account != null && passwordMatches(password, account.passwordHash)
        if (account == null) passwordMatches(password, standIn)
        return if (account != null && signedIn) {
            tokens(HttpStatus.OK, account.userId, refreshTokens.start(account.userId))
        } else {
            Problems.wrongCredentials()
        }
    }

    @PostMapping("/refresh")
    @Operation(summary = "Trade a refresh token for the next pair")
    @ApiResponse(responseCode = "200", description = "A new pair; the refresh token sent stops working")
    @ApiResponse(
        responseCode = "401",
        description = "Unknown, expired, ended or already used: sign in again",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(
        responseCode = "429",
        description = "Too many tries from this address; `Retry-After` says how many seconds to wait",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<*> {
        val token = request.refreshToken ?: return Problems.invalid(mapOf("refreshToken" to "missing"))
        return when (val refresh = refreshTokens.rotate(token)) {
            Refresh.Rejected -> Problems.signInAgain()
            is Refresh.Rotated -> tokens(HttpStatus.OK, refresh.userId, refresh.refreshToken)
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "End this phone's chain of refresh tokens")
    @SecurityRequirement(name = "bearer")
    @ApiResponse(responseCode = "204", description = "Done, whether or not the token was still in use")
    @ApiResponse(
        responseCode = "400",
        description = "The refresh token is missing",
        content = [Content(mediaType = PROBLEM, schema = Schema(implementation = ProblemShape::class))],
    )
    @ApiResponse(responseCode = "401", description = "No access token, or one that has run out", content = [Content()])
    fun logout(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: RefreshRequest): ResponseEntity<*> {
        val token = request.refreshToken ?: return Problems.invalid(mapOf("refreshToken" to "missing"))
        refreshTokens.endChain(UUID.fromString(jwt.subject), token)
        return ResponseEntity.noContent().build<Unit>()
    }

    /** A password longer than register allows can't be anyone's, and isn't worth a hash. */
    private fun passwordMatches(password: String, hash: String): Boolean =
        password.codePointCount(0, password.length) <= RegisterRules.MAX_PASSWORD && hasher.matches(password, hash)

    private fun create(account: NewAccount): ResponseEntity<*> {
        // Hashed before the email is looked at: a taken email takes as long to answer as a free one.
        val hash = hasher.hash(account.password)
        val userId = try {
            accounts.create(account, hash)
        } catch (_: DuplicateKeyException) {
            return Problems.emailUnavailable()
        }
        return tokens(HttpStatus.CREATED, userId, refreshTokens.start(userId))
    }

    private fun tokens(status: HttpStatus, userId: UUID, refreshToken: String): ResponseEntity<TokenAnswer> =
        ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(
                TokenAnswer(
                    accessToken = accessTokens.issue(userId),
                    expiresIn = AccessTokens.LIFETIME.seconds,
                    refreshToken = refreshToken,
                    refreshExpiresIn = RefreshTokens.LIFETIME.seconds,
                ),
            )

    private companion object {
        const val PROBLEM = "application/problem+json"
    }
}
