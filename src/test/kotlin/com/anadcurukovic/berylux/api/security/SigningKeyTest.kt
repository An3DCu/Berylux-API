package com.anadcurukovic.berylux.api.security

import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The API stops at start without a strong signing secret, and never prints it. */
class SigningKeyTest {
    private fun base64(bytes: Int) = Base64.getEncoder().encodeToString(ByteArray(bytes) { 7 })

    @Test
    fun `no secret stops the API`() {
        assertFailsWith<IllegalStateException> { SigningKey.from("") }
    }

    @Test
    fun `a secret that isn't Base64 stops the API without printing it`() {
        val secret = "not base64 at all!"
        val error = assertFailsWith<IllegalStateException> { SigningKey.from(secret) }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    @Test
    fun `a secret needs at least 32 bytes`() {
        val short = base64(31)
        val error = assertFailsWith<IllegalStateException> { SigningKey.from(short) }
        assertFalse(error.message.orEmpty().contains(short))
        assertEquals("HmacSHA256", SigningKey.from(base64(32)).algorithm)
    }
}
