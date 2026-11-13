package com.anadcurukovic.berylux.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed docs/openapi.yaml matches what the API serves locally. After changing an endpoint,
 * `./gradlew test -PupdateSpec` writes the new spec, and the diff shows in the pull request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(TestDatabase::class)
class OpenApiSpecTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `the committed spec is the one the API serves`() {
        val response = mvc.get("/v3/api-docs.yaml").andExpect { status { isOk() } }.andReturn().response
        val served = response.contentAsString
        assertTrue(served.startsWith("openapi:"), "not an OpenAPI spec: ${served.take(200)}")
        val file = File(System.getProperty("specFile"))
        if (System.getProperty("updateSpec") == "true") file.writeText(served)
        assertEquals(file.readText(), served, "docs/openapi.yaml is out of date: run ./gradlew test -PupdateSpec")
    }

    @Test
    fun `Swagger UI is served locally`() {
        mvc.get("/swagger-ui.html").andExpect { status { is3xxRedirection() } }
    }
}
