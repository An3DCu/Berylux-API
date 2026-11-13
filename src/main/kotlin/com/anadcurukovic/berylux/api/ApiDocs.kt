package com.anadcurukovic.berylux.api

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The title page of the OpenAPI spec, committed as docs/openapi.yaml. */
@Configuration
class ApiDocs {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Berylux API")
                    .description("Accounts, sync and the weekly leaderboard for the Berylux app.")
                    .version("0.2.0")
                    .license(License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")),
            )
            .components(
                Components().addSecuritySchemes(
                    "bearer",
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
                ),
            )
}
