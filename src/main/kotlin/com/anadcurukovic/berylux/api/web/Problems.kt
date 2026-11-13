package com.anadcurukovic.berylux.api.web

import com.anadcurukovic.berylux.api.security.ServerBusyException
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * The API's error answers, as RFC 9457 problem details. Each says what went wrong in plain words
 * and never repeats what was sent, a password least of all.
 */
object Problems {
    const val BUSY_RETRY_SECONDS = 5

    fun invalid(problems: Map<String, String>): ResponseEntity<ProblemDetail> =
        answer(HttpStatus.BAD_REQUEST, "Some fields are wrong").also { it.body?.setProperty("problems", problems) }

    fun emailUnavailable(): ResponseEntity<ProblemDetail> = answer(HttpStatus.CONFLICT, "This email can't be used")

    /** The same for a wrong password and an unknown email: nobody learns which emails have accounts. */
    fun wrongCredentials(): ResponseEntity<ProblemDetail> = answer(HttpStatus.UNAUTHORIZED, "Wrong email or password")

    fun signInAgain(): ResponseEntity<ProblemDetail> = answer(HttpStatus.UNAUTHORIZED, "Sign in again")

    fun tooLarge(): ResponseEntity<ProblemDetail> = answer(HttpStatus.CONTENT_TOO_LARGE, "The body is too large")

    fun unreadable(): ResponseEntity<ProblemDetail> = answer(HttpStatus.BAD_REQUEST, "The body can't be read")

    fun busy(): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE).apply { title = "Try again shortly" }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, BUSY_RETRY_SECONDS.toString())
            .body(problem)
    }

    private fun answer(status: HttpStatus, title: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatus(status).apply { this.title = title })
}

/**
 * How a problem looks on the wire, for the OpenAPI spec. Spring's own class nests `problems` deeper.
 * There's no `type`: RFC 9457 reads a missing one as `about:blank`.
 */
@Schema(name = "Problem", description = "An RFC 9457 problem detail")
data class ProblemShape(
    @field:Schema(example = "Some fields are wrong") val title: String,
    @field:Schema(example = "400") val status: Int,
    @field:Schema(description = "The path that was called") val instance: String?,
    @field:Schema(
        description = "Only on a 400: each wrong field and what's wrong with it",
        example = """{"password": "too_short", "profile.dayStartHour": "out_of_range"}""",
    )
    val problems: Map<String, String>?,
)

@RestControllerAdvice
class ProblemAdvice {
    /** A body that isn't JSON, has the wrong types, or ran past [BodySizeLimit] while being read. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<ProblemDetail> =
        if (generateSequence<Throwable>(e) { it.cause }.any { it is BodyTooLargeException }) {
            Problems.tooLarge()
        } else {
            Problems.unreadable()
        }

    @ExceptionHandler(ServerBusyException::class)
    fun busy(): ResponseEntity<ProblemDetail> = Problems.busy()
}
