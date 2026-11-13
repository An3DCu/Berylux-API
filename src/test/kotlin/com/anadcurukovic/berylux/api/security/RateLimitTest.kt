package com.anadcurukovic.berylux.api.security

import com.anadcurukovic.berylux.api.TestAddresses
import com.anadcurukovic.berylux.api.TestDatabase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The limits on the running routes. The exact windows are in RateLimitsTest, on a clock of its own. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
class RateLimitTest(@Autowired private val mvc: MockMvc) {
    private fun post(path: String, json: String, address: String): ResultActionsDsl = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = json
        with(TestAddresses.from(address))
    }

    private fun login(email: String, address: String) =
        post("/auth/login", """{"email":"$email","password":"not the password"}""", address)

    private fun freshEmail() = "player-${UUID.randomUUID()}@example.com"

    private fun ResultActionsDsl.isTooManyTries(longestWaitSeconds: Long) = andExpect {
        status { isTooManyRequests() }
        content { contentTypeCompatibleWith("application/problem+json") }
        jsonPath("$.title") { value("Too many tries") }
        header { exists("Retry-After") }
    }.andReturn().response.getHeader("Retry-After")!!.toLong().also {
        assertTrue(it in 1..longestWaitSeconds, "Retry-After $it")
    }

    @Test
    fun `register takes 3 tries from one address, then asks it to wait`() {
        val address = TestAddresses.fresh()
        repeat(3) { post("/auth/register", "{}", address).andExpect { status { isBadRequest() } } }
        post("/auth/register", "{}", address).isTooManyTries(longestWaitSeconds = 3_600)
        post("/auth/register", "{}", TestAddresses.fresh()).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `log in takes 5 tries a minute from one address`() {
        val address = TestAddresses.fresh()
        repeat(5) { login(freshEmail(), address).andExpect { status { isUnauthorized() } } }
        login(freshEmail(), address).isTooManyTries(longestWaitSeconds = 60)
    }

    @Test
    fun `log in takes 5 tries per email in 15 minutes, from any number of addresses`() {
        val email = freshEmail()
        repeat(5) { login(email, TestAddresses.fresh()).andExpect { status { isUnauthorized() } } }
        val address = TestAddresses.fresh()
        login(email, address).isTooManyTries(longestWaitSeconds = 900)
        login(freshEmail(), address).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the limit per email says nothing about whether it has an account`() {
        val known = freshEmail()
        val json = """
            {"email":"$known","password":"correct horse battery","profile":{"name":"Marlowe","sigilSeed":7,
            "pickSeed":11,"timeZone":"Europe/Sarajevo","dayStartHour":4}}
        """.trimIndent()
        post("/auth/register", json, TestAddresses.fresh()).andExpect { status { isCreated() } }
        val unknown = freshEmail()
        val answers = listOf(known, unknown).map { email ->
            repeat(5) { login(email, TestAddresses.fresh()) }
            val blocked = login(email, TestAddresses.fresh()).andExpect { status { isTooManyRequests() } }
            blocked.andReturn().response.contentAsString
        }
        assertEquals(answers[0], answers[1])
    }

    @Test
    fun `refresh takes 10 tries a minute from one address`() {
        val address = TestAddresses.fresh()
        val unknownToken = """{"refreshToken":"x"}"""
        repeat(10) { post("/auth/refresh", unknownToken, address).andExpect { status { isUnauthorized() } } }
        post("/auth/refresh", unknownToken, address).isTooManyTries(longestWaitSeconds = 60)
    }

    @Test
    fun `routes other than sign-in have no limit`() {
        val address = TestAddresses.fresh()
        repeat(30) {
            mvc.get("/actuator/health") { with(TestAddresses.from(address)) }.andExpect { status { isOk() } }
        }
    }
}
