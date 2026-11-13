package com.anadcurukovic.berylux.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** The server for Berylux: accounts, sync and the leaderboard. */
@SpringBootApplication
class BeryluxApiApplication

// The spread copies the arguments once, at startup.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<BeryluxApiApplication>(*args)
}
