package com.anadcurukovic.berylux.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** Closed by default, as it runs anywhere but locally. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
class SecurityTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `a route that isn't open needs a signed-in player`() {
        mvc.get("/cases").andExpect { status { isUnauthorized() } }
        mvc.post("/cases").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the health check is open to reading only`() {
        mvc.post("/actuator/health").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the API docs aren't served outside the local profile`() {
        mvc.get("/v3/api-docs").andExpect { status { isNotFound() } }
        mvc.get("/swagger-ui.html").andExpect { status { isNotFound() } }
    }

    @Test
    fun `an error says nothing about how the server is built`() {
        mvc.get("/v3/api-docs").andExpect {
            jsonPath("$.trace") { doesNotExist() }
            jsonPath("$.exception") { doesNotExist() }
            jsonPath("$.message") { doesNotExist() }
        }
    }
}
