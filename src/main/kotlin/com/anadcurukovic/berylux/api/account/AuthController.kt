package com.anadcurukovic.berylux.api.account

import com.anadcurukovic.berylux.api.security.PasswordHasher
import com.anadcurukovic.berylux.api.web.ProblemShape
import com.anadcurukovic.berylux.api.web.Problems
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
@Tag(name = "Accounts")
class AuthController(
    private val accounts: Accounts,
    private val hasher: PasswordHasher,
) {
    @PostMapping("/register")
    @Operation(summary = "Make an account with the guest's profile")
    @ApiResponse(responseCode = "201", description = "The account exists", content = [Content()])
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
    fun register(@RequestBody request: RegisterRequest): ResponseEntity<*> =
        when (val checked = RegisterRules.check(request)) {
            is Checked.Invalid -> Problems.invalid(checked.problems)
            is Checked.Valid -> create(checked.account)
        }

    private fun create(account: NewAccount): ResponseEntity<*> {
        // Hashed before the email is looked at: a taken email takes as long to answer as a free one.
        val hash = hasher.hash(account.password)
        return try {
            accounts.create(account, hash)
            ResponseEntity.status(HttpStatus.CREATED).build<Unit>()
        } catch (_: DuplicateKeyException) {
            Problems.emailUnavailable()
        }
    }

    private companion object {
        const val PROBLEM = "application/problem+json"
    }
}
