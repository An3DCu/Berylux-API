package com.anadcurukovic.berylux.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
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
        // Security turns these away before Actuator sees them (until #3 they were 404s).
        mvc.get("/actuator/env").andExpect { status { isUnauthorized() } }
        mvc.get("/actuator/beans").andExpect { status { isUnauthorized() } }
    }
}
