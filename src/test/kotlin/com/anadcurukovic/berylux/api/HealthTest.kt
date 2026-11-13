package com.anadcurukovic.berylux.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class HealthTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `the health check says UP and nothing more`() {
        mvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.components") { doesNotExist() }
        }
    }

    @Test
    fun `no other actuator endpoint is open`() {
        mvc.get("/actuator/env").andExpect { status { isNotFound() } }
        mvc.get("/actuator/beans").andExpect { status { isNotFound() } }
    }
}
