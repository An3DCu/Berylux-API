package com.anadcurukovic.berylux.api.account

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Expired refresh tokens can't be used, but they would pile up. Every six hours they go. */
@Component
class RefreshTokenCleanup(private val tokens: RefreshTokens) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    fun deleteExpired() {
        val deleted = tokens.deleteExpired()
        if (deleted > 0) log.info("Deleted {} expired refresh tokens", deleted)
    }
}

@Configuration
@EnableScheduling
class Scheduling
