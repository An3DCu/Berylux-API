package com.anadcurukovic.berylux.api.security

import com.anadcurukovic.berylux.api.TestAddresses
import com.anadcurukovic.berylux.api.TestDatabase
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals

/**
 * What anyone on the internet can reach, pinned. Opening a route means changing this test on
 * purpose, in a pull request that says what it opens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
class PublicSurfaceTest(@Autowired private val mvc: MockMvc) {
    /** Each route that reads a body, and the field it names first when the body is empty. */
    private val bodyRoutes = listOf(
        Triple(HttpMethod.POST, "/auth/register", "email"),
        Triple(HttpMethod.POST, "/auth/login", "email"),
        Triple(HttpMethod.POST, "/auth/refresh", "refreshToken"),
        Triple(HttpMethod.POST, "/auth/logout", "refreshToken"),
        Triple(HttpMethod.DELETE, "/account", "password"),
    )

    private fun accessToken(): String {
        val json = """
            {"email":"player-${UUID.randomUUID()}@example.com","password":"correct horse battery",
            "profile":{"name":"Marlowe","sigilSeed":7,"pickSeed":11,"timeZone":"Europe/Sarajevo","dayStartHour":4}}
        """.trimIndent()
        val answer = mvc.post("/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = json
            with(TestAddresses.from())
        }.andReturn()
        return JsonPath.read(answer.response.contentAsString, "$.accessToken")
    }

    @Test
    fun `the open routes are exactly these`() {
        assertEquals(
            listOf(
                HttpMethod.GET to "/actuator/health",
                HttpMethod.POST to "/auth/register",
                HttpMethod.POST to "/auth/login",
                HttpMethod.POST to "/auth/refresh",
                HttpMethod.GET to "/v3/api-docs",
                HttpMethod.GET to "/v3/api-docs/**",
                HttpMethod.GET to "/v3/api-docs.yaml",
                HttpMethod.GET to "/swagger-ui.html",
                HttpMethod.GET to "/swagger-ui/**",
            ),
            SecurityConfig.OPEN,
        )
    }

    @Test
    fun `every route that takes a body turns away 8 KiB and one byte`() {
        val tooBig = "x".repeat(8 * 1024 + 1)
        for ((method, path) in bodyRoutes) {
            mvc.perform(
                request(method, path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(tooBig)
                    .with(TestAddresses.from()),
            ).andExpect(status().`is`(413))
        }
    }

    @Test
    fun `every route that takes a body names what's missing`() {
        val token = accessToken()
        for ((method, path, field) in bodyRoutes) {
            mvc.perform(
                request(method, path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .header("Authorization", "Bearer $token")
                    .with(TestAddresses.from()),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.problems.$field").value("missing"))
        }
    }
}
