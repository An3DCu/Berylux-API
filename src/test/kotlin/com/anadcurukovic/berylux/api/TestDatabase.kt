package com.anadcurukovic.berylux.api

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** A PostgreSQL in Docker for the tests, the same major version Docker Compose runs. */
@TestConfiguration(proxyBeanMethods = false)
class TestDatabase {
    @Bean
    @ServiceConnection
    fun postgres() = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
}
